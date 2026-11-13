package com.hex91.file.net;

import com.hex91.file.fs.TempFiles;
import com.hex91.file.fs.WorkDir;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/**
 * 局域网快传节点（v1.74.0）：同时是「发现者」与「接收端」。
 *
 * <p>两条腿：</p>
 * <ul>
 *   <li><b>UDP 发现</b>：固定端口 {@link LanProtocol#DISCOVERY_PORT}，每 2.5 秒发一次探测
 *       广播，收到探测的应用回一条单播应答；双方都把对方记进设备表。</li>
 *   <li><b>HTTP 接收</b>：NanoHTTPD 监听一个临时端口（端口号随应答广播出去），
 *       接收 {@code POST /lan/send} 的裸字节流，直接落盘。</li>
 * </ul>
 *
 * <p>用进程内单例：Activity 与前台服务共享同一份状态。服务负责「后台也能收」，
 * Activity 只负责展示与触发扫描。</p>
 */
public final class LanNode {

    private static final String TAG = "LanNode";
    private static final String PREFS = "lan_share";
    private static final String KEY_KEEP = "keep_receiving";

    /** 探测间隔 */
    private static final long PROBE_INTERVAL = 2500L;

    public interface Listener {
        /** 设备表变化（发现/超时剔除），主线程回调 */
        void onDevices(List<LanProtocol.Device> devices);

        /** 接收进度，主线程回调；节流到 ~300ms 一次 */
        void onReceiveProgress(String name, long got, long total);

        /** 收到一个完整文件 */
        void onReceived(String name, long size, File saved);

        /** 状态提示（启动失败、端口占用等） */
        void onNotice(String text);
    }

    private static LanNode sInst;

    public static synchronized LanNode get(Context c) {
        if (sInst == null) sInst = new LanNode(c.getApplicationContext());
        return sInst;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, LanProtocol.Device> devices =
            new LinkedHashMap<String, LanProtocol.Device>();
    private final List<Listener> listeners = new ArrayList<Listener>();

    private String selfId;
    private String selfName;
    private int httpPort;

    private volatile boolean running;
    private DatagramSocket udp;
    private Server server;
    private Thread rxThread;
    private Thread probeThread;
    private Object mcastLock;
    private Object wifiLock;

    private LanNode(Context c) {
        this.app = c;
    }

    // ---------------------------------------------------------------- 生命周期

    public synchronized boolean start() {
        if (running) return true;
        selfId = LanNet.selfId(app);
        selfName = LanNet.deviceName();
        try {
            server = new Server();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            httpPort = server.getListeningPort();
        } catch (Throwable t) {
            Log.w(TAG, "start server failed", t);
            server = null;
            notice("接收服务启动失败：" + t.getMessage());
            return false;
        }
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.setBroadcast(true);
            udp.setSoTimeout(1500);
            udp.bind(new InetSocketAddress(LanProtocol.DISCOVERY_PORT));
        } catch (Throwable t) {
            Log.w(TAG, "bind udp failed", t);
            try { server.stop(); } catch (Throwable ignored) { }
            server = null;
            udp = null;
            notice("发现端口 " + LanProtocol.DISCOVERY_PORT + " 被占用，无法搜索设备");
            return false;
        }

        mcastLock = LanNet.acquireMulticast(app);
        wifiLock = LanNet.acquireWifi(app);
        running = true;

        rxThread = new Thread(new Runnable() {
            public void run() { rxLoop(); }
        }, "lan-rx");
        rxThread.setDaemon(true);
        rxThread.start();

        probeThread = new Thread(new Runnable() {
            public void run() { probeLoop(); }
        }, "lan-probe");
        probeThread.setDaemon(true);
        probeThread.start();

        Log.i(TAG, "started, http=" + httpPort + " id=" + selfId);
        return true;
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (udp != null) udp.close(); } catch (Throwable ignored) { }
        try { if (server != null) server.stop(); } catch (Throwable ignored) { }
        if (rxThread != null) rxThread.interrupt();
        if (probeThread != null) probeThread.interrupt();
        udp = null;
        server = null;
        LanNet.releaseQuietly(mcastLock);
        LanNet.releaseQuietly(wifiLock);
        mcastLock = null;
        wifiLock = null;
        synchronized (devices) {
            devices.clear();
        }
        notifyDevices();
    }

    public boolean isRunning() {
        return running;
    }

    public String selfName() {
        if (selfName == null) selfName = LanNet.deviceName();
        return selfName;
    }

    public String selfId() {
        if (selfId == null) selfId = LanNet.selfId(app);
        return selfId;
    }

    public int httpPort() {
        return httpPort;
    }

    /** 当前在线设备（按名字排序，避免列表每次刷新都跳来跳去） */
    public List<LanProtocol.Device> devices() {
        List<LanProtocol.Device> out;
        synchronized (devices) {
            out = new ArrayList<LanProtocol.Device>(devices.values());
        }
        java.util.Collections.sort(out, new java.util.Comparator<LanProtocol.Device>() {
            public int compare(LanProtocol.Device a, LanProtocol.Device b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /** 立刻探测一次（进入页面、点刷新时调用），不阻塞 UI */
    public void probeNow() {
        if (!running) return;
        Thread t = new Thread(new Runnable() {
            public void run() { sendProbe(); }
        }, "lan-probe-now");
        t.setDaemon(true);
        t.start();
    }

    public void addListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
        l.onDevices(devices());
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    /** 后台保持接收的偏好（前台服务用） */
    public boolean keepReceiving() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP, false);
    }

    public void setKeepReceiving(boolean keep) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_KEEP, keep).apply();
    }

    /**
     * 接收目录：统一放外部存储根下的 {@code HexFile/LanShare}
     * （即 {@code /sdcard/HexFile/LanShare}），与其他 HexFile 产物同处一棵树，方便统一管理。
     * 取不到时逐级回退：外部根 → 应用外部私有目录 → 应用内部目录。
     */
    public File saveDir() {
        try {
            File dir = new File(WorkDir.base(), "LanShare");
            if (dir.isDirectory() || dir.mkdirs()) return dir;
        } catch (Throwable t) {
            Log.w(TAG, "saveDir: 外部根不可用，回退到应用目录", t);
        }
        File appExt = app.getExternalFilesDir("LanShare");
        if (appExt != null && (appExt.isDirectory() || appExt.mkdirs())) return appExt;
        File in = new File(app.getFilesDir(), "LanShare");
        in.mkdirs();
        return in;
    }

    /** v1.75.0 之前的接收目录（Downloads/HexFile/LanShare），仅用于旧文件迁移提示 */
    public File legacyDir() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) return new File(new File(dl, "HexFile"), "LanShare");
        } catch (Throwable t) {
            Log.w(TAG, "saveDir: 应用外部私有目录不可用，回退到内部目录", t);
        }
        return null;
    }

    // ---------------------------------------------------------------- UDP

    private void sendProbe() {
        DatagramSocket s = udp;
        if (s == null) return;
        byte[] data = LanProtocol.encode(LanProtocol.TYPE_PROBE, selfId(), httpPort, selfName())
                .getBytes(java.nio.charset.Charset.forName("UTF-8"));
        List<InetAddress> targets = LanNet.broadcastAddresses();
        for (int i = 0; i < targets.size(); i++) {
            try {
                s.send(new DatagramPacket(data, data.length, targets.get(i),
                        LanProtocol.DISCOVERY_PORT));
            } catch (Throwable t) {
                Log.d(TAG, "probe send fail to " + targets.get(i) + ": " + t);
            }
        }
    }

    private void rxLoop() {
        byte[] buf = new byte[1024];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                udp.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Throwable t) {
                if (running) Log.d(TAG, "rx stopped: " + t);
                break;
            }
            try {
                String msg = new String(p.getData(), 0, p.getLength(),
                        java.nio.charset.Charset.forName("UTF-8"));
                String host = p.getAddress() == null ? "" : p.getAddress().getHostAddress();
                LanProtocol.Device d = LanProtocol.parse(msg, host, System.currentTimeMillis());
                if (d == null) continue;
                if (d.id.equals(selfId())) continue;   // 自己发的广播会被自己收到
                boolean isNew;
                synchronized (devices) {
                    isNew = !devices.containsKey(d.id);
                    devices.put(d.id, d);
                }
                if (LanProtocol.TYPE_PROBE.equals(d.kind)) {
                    replyHere(p.getAddress(), p.getPort());
                }
                if (isNew) notifyDevices();
            } catch (Throwable t) {
                Log.d(TAG, "bad packet: " + t);
            }
        }
    }

    /** 有人在找设备，回一条单播应答（端口用对方发包的源端口，即固定发现端口） */
    private void replyHere(InetAddress addr, int port) {
        DatagramSocket s = udp;
        if (s == null || addr == null) return;
        try {
            byte[] data = LanProtocol.encode(LanProtocol.TYPE_HERE, selfId(), httpPort, selfName())
                    .getBytes(java.nio.charset.Charset.forName("UTF-8"));
            s.send(new DatagramPacket(data, data.length, addr, port));
        } catch (Throwable t) {
            Log.d(TAG, "reply fail: " + t);
        }
    }

    private void probeLoop() {
        while (running) {
            sendProbe();
            long now = System.currentTimeMillis();
            boolean changed = false;
            synchronized (devices) {
                Iterator<Map.Entry<String, LanProtocol.Device>> it = devices.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().isStale(now)) {
                        it.remove();
                        changed = true;
                    }
                }
            }
            if (changed) notifyDevices();
            try {
                Thread.sleep(PROBE_INTERVAL);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ---------------------------------------------------------------- 回调（主线程 + 节流）

    private void notifyDevices() {
        final List<LanProtocol.Device> snapshot = devices();
        main.post(new Runnable() {
            public void run() {
                List<Listener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<Listener>(listeners);
                }
                for (int i = 0; i < copy.size(); i++) {
                    try {
                        copy.get(i).onDevices(snapshot);
                    } catch (Throwable t) {
                        Log.w(TAG, "设备列表回调失败（不影响接收）", t);
                    }
                }
            }
        });
    }

    private void notifyProgress(final String name, final long got, final long total) {
        main.post(new Runnable() {
            public void run() {
                List<Listener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<Listener>(listeners);
                }
                for (int i = 0; i < copy.size(); i++) {
                    try {
                        copy.get(i).onReceiveProgress(name, got, total);
                    } catch (Throwable t) {
                        Log.w(TAG, "接收进度回调失败（不影响接收）", t);
                    }
                }
            }
        });
    }

    private void notifyReceived(final String name, final long size, final File saved) {
        main.post(new Runnable() {
            public void run() {
                List<Listener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<Listener>(listeners);
                }
                for (int i = 0; i < copy.size(); i++) {
                    try {
                        copy.get(i).onReceived(name, size, saved);
                    } catch (Throwable t) {
                        Log.w(TAG, "接收完成回调失败（文件已落盘）", t);
                    }
                }
            }
        });
    }

    private void notice(final String text) {
        main.post(new Runnable() {
            public void run() {
                List<Listener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<Listener>(listeners);
                }
                for (int i = 0; i < copy.size(); i++) {
                    try {
                        copy.get(i).onNotice(text);
                    } catch (Throwable t) {
                        Log.w(TAG, "提示回调失败", t);
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------- HTTP 接收

    private final class Server extends NanoHTTPD {

        Server() throws IOException {
            super(0);   // 端口交给系统分配，避开 8080 这类常被占用的端口
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri() == null ? "/" : session.getUri();
            try {
                if ("/lan/info".equals(uri)) {
                    String body = "{\"id\":\"" + selfId() + "\",\"name\":\""
                            + LanProtocol.percentEncode(selfName()) + "\",\"port\":" + httpPort + "}";
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                            "application/json; charset=utf-8", body);
                }
                if ("/lan/send".equals(uri)) {
                    if (session.getMethod() != Method.POST) {
                        return NanoHTTPD.newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,
                                "text/plain; charset=utf-8", "POST only");
                    }
                    return receive(session);
                }
                return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                        "text/plain; charset=utf-8", "HexFile LAN");
            } catch (Throwable t) {
                Log.w(TAG, "serve fail", t);
                return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "text/plain; charset=utf-8", "ERR " + t.getMessage());
            }
        }

        /** 裸字节流落盘：不经过 multipart 解析，省一次拷贝，也能边收边报进度 */
        private Response receive(IHTTPSession session) throws IOException {
            Map<String, String> params = session.getParms();
            String rawName = params == null ? null : params.get("name");
            long declared = -1;
            String cl = session.getHeaders() == null ? null
                    : session.getHeaders().get("content-length");
            if (cl != null) {
                try {
                    declared = Long.parseLong(cl.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (declared <= 0) {
                return NanoHTTPD.newFixedLengthResponse(Response.Status.LENGTH_REQUIRED,
                        "text/plain; charset=utf-8", "content-length required");
            }

            File dir = saveDir();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "text/plain; charset=utf-8", "cannot create save dir");
            }
            String safe = LanProtocol.safeFileName(rawName);
            String finalName = LanProtocol.uniqueName(safe, existingNames(dir));
            File part = new File(dir, finalName + ".part");
            File dst = new File(dir, finalName);
            // v2.4.0（R-08）：整个接收期间都算活动临时文件。局域网接收的 .part
            // 就是唯一进度载体，被遗留扫描列出来删掉 = 已接收的字节全丢
            TempFiles.begin(part);

            long got = 0;
            long lastTick = 0;
            FileOutputStream out = null;
            try {
                InputStream in = session.getInputStream();
                out = new FileOutputStream(part);
                byte[] buf = new byte[64 * 1024];
                while (got < declared) {
                    int want = (int) Math.min(buf.length, declared - got);
                    int n = in.read(buf, 0, want);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    got += n;
                    long now = System.currentTimeMillis();
                    if (now - lastTick > 300) {
                        lastTick = now;
                        notifyProgress(finalName, got, declared);
                    }
                }
                out.flush();
            } finally {
                try { if (out != null) out.close(); } catch (Throwable ignored) { }
                // 写完即注销：下面无论走「不完整 → 删掉」还是「rename 成功」，
                // 注册表里都不该再留着这个路径
                TempFiles.end(part);
            }

            if (got != declared) {
                part.delete();
                return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "text/plain; charset=utf-8", "incomplete " + got + "/" + declared);
            }
            if (!part.renameTo(dst)) {
                // 极少数文件系统不允许 rename，退化成复制后删除
                if (!copyThenDelete(part, dst)) {
                    part.delete();
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                            "text/plain; charset=utf-8", "rename failed");
                }
            }
            notifyProgress(finalName, got, declared);
            notifyReceived(finalName, got, dst);
            Log.i(TAG, "received " + finalName + " " + got + "B");
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                    "text/plain; charset=utf-8", "OK " + dst.getAbsolutePath());
        }
    }

    private static Set<String> existingNames(File dir) {
        Set<String> out = new HashSet<String>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (int i = 0; i < fs.length; i++) {
                out.add(fs[i].getName());
            }
        }
        return out;
    }

    private static boolean copyThenDelete(File src, File dst) {
        FileOutputStream os = null;
        java.io.FileInputStream is = null;
        try {
            is = new java.io.FileInputStream(src);
            os = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            is.close();
            is = null;
            return src.delete();
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Throwable ignored) { }
            try { if (is != null) is.close(); } catch (Throwable ignored) { }
        }
    }
}
