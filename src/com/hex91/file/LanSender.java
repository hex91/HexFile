package com.hex91.file;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 局域网快传的发送端（v1.74.0）。
 *
 * <p>协议极简：{@code POST http://对端IP:端口/lan/send?name=..&size=..}，body 就是文件
 * 原始字节。之所以不用 multipart，是因为接收端只需要一个文件名 + 一段字节流，
 * multipart 会增加一层边界解析和一次临时文件拷贝，大文件（几百 MB 的视频/安装包）
 * 上这笔开销很实在。</p>
 *
 * <p>用 {@code setFixedLengthStreamingMode} 边读边发，不把文件读进内存——手机上
 * 动辄几百 MB 的文件，全量读入必然 OOM。</p>
 */
public final class LanSender {

    public interface Cb {
        /** 主线程回调，pct 0~100 */
        void onProgress(int pct, long sent, long total);

        /** 发送成功，msg 为对方返回的保存路径 */
        void onDone(String msg);

        /** 失败（连接超时、对方拒绝、读文件出错等） */
        void onError(String msg);
    }

    private LanSender() {
    }

    public static void send(final Context ctx, final LanProtocol.Device device,
                            final File file, final Cb cb) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                FileInputStream in = null;
                try {
                    in = new FileInputStream(file);
                    doSend(device, in, file.getName(), file.length(), cb);
                } catch (Throwable e) {
                    fail(cb, e);
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
            }
        }, "lan-send");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 发送 SAF 选中的文件。size 已知时直接流式发送；未知（少数网盘类 provider）
     * 退化成 0，由调用方先复制到缓存再发。
     */
    public static void sendUri(final Context ctx, final LanProtocol.Device device,
                               final Uri uri, final String displayName,
                               final long size, final Cb cb) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                InputStream in = null;
                try {
                    in = ctx.getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("无法读取所选文件");
                    doSend(device, in, displayName, size, cb);
                } catch (Throwable e) {
                    fail(cb, e);
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
            }
        }, "lan-send-uri");
        t.setDaemon(true);
        t.start();
    }

    private static void doSend(LanProtocol.Device device, InputStream in, String name,
                               long size, Cb cb) throws IOException {
        if (device == null) throw new IOException("设备信息缺失");
        if (size <= 0) throw new IOException("文件大小未知（请先复制到本机再发送）");
        String host = device.host == null ? "" : device.host;
        if (host.indexOf(':') >= 0) host = "[" + host + "]";   // IPv6 字面量
        String url = "http://" + host + ":" + device.port + "/lan/send?name="
                + LanProtocol.percentEncode(name) + "&size=" + size;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(120000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(size);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Connection", "close");

            OutputStream os = conn.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            long sent = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                sent += n;
                int pct = (int) (sent * 100 / size);
                if (pct != lastPct) {
                    lastPct = pct;
                    progress(cb, pct, sent, size);
                }
            }
            os.flush();
            os.close();
            if (sent != size) throw new IOException("读取字节数不足：" + sent + "/" + size);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                done(cb, readText(conn.getInputStream()));
            } else {
                String body = readText(conn.getErrorStream());
                fail(cb, new IOException("对方拒绝（HTTP " + code + "）" + body));
            }
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String readText(InputStream in) {
        if (in == null) return "";
        try {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void progress(final Cb cb, final int pct, final long sent, final long total) {
        if (cb == null) return;
        main().post(new Runnable() {
            public void run() {
                try { cb.onProgress(pct, sent, total); } catch (Throwable ignored) { }
            }
        });
    }

    private static void done(final Cb cb, final String msg) {
        if (cb == null) return;
        main().post(new Runnable() {
            public void run() {
                try { cb.onDone(msg); } catch (Throwable ignored) { }
            }
        });
    }

    private static void fail(final Cb cb, final Throwable e) {
        if (cb == null) return;
        final String msg = friendly(e);
        main().post(new Runnable() {
            public void run() {
                try { cb.onError(msg); } catch (Throwable ignored) { }
            }
        });
    }

    /**
     * 把底层异常翻成人能看懂的话。{@code CLEARTEXT ...not permitted} 是系统的明文流量策略
     * 在建连前就拒绝（连 TCP 都没发出去），跟对方是否在线无关，直接抛原文会让人误判成
     * 「对方没开服务」，所以单独挑出来说明。
     */
    static String friendly(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m != null && m.contains("CLEARTEXT")) {
            return "系统拦截了明文 HTTP（" + m + "）。请确认两台设备都是 1.74.1 及以上版本";
        }
        return m;
    }

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }
}
