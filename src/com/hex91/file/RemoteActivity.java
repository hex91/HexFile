package com.hex91.file;


import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


import io.nayuki.qrcodegen.QrCode;

/**
 * 远程管理（v1.50.0）：局域网 HTTP 服务器，电脑浏览器直接浏览/上传/下载/管理手机文件。
 *
 * <p>服务端为 {@link RemoteServer}（NanoHTTPD），二维码为 Nayuki QrCode（MIT）。
 * 生命周期：服务随本页面开启/关闭（保持本页开启即可持续访问）。</p>
 */
public final class RemoteActivity extends BaseActivity {

    private static final int DEFAULT_PORT = 8765;
    private static final String PREFS = "remote";

    private TextView mStatus, mUrl, mKey, mLog;
    private ImageView mQr;
    private Button mToggle, mRegen;

    private RemoteServer mServer;

    /** 进程内运行中的服务单例：Activity 重建/多实例时 UI 状态仍与真实服务保持一致 */
    private static RemoteServer sActive;

    private String mAccessKey;
    private WifiManager.WifiLock mWifiLock;

    private final StringBuilder mLogBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);

        mStatus = findViewById(R.id.remote_status);
        mUrl = findViewById(R.id.remote_url);
        mKey = findViewById(R.id.remote_key);
        mLog = findViewById(R.id.remote_log);
        mQr = findViewById(R.id.remote_qr);
        mToggle = findViewById(R.id.remote_toggle);
        mRegen = findViewById(R.id.remote_regen);

        mAccessKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString("key", null);
        if (mAccessKey == null || mAccessKey.length() != 8) mAccessKey = genKey();
        mKey.setText(mAccessKey);

        mToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        mRegen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { regenKey(); }
        });
        mUrl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyUrl(); }
        });

        applyUi();
    }

    // ----------------------------------------------------------------
    // 服务控制
    // ----------------------------------------------------------------

    private void toggle() {
        if (mServer != null) stopServer();
        else startServer();
        applyUi();
    }

    private void startServer() {
        try {
            // 进程内已有服务（如 Activity 重建后）→ 直接接管，避免重复绑定端口失败
            if (sActive != null) {
                mServer = sActive;
                appendLog("已接管运行中的服务");
                return;
            }
            File root = safeRoot();
            RemoteServer s = new RemoteServer(DEFAULT_PORT, root, mAccessKey,
                    new File(getCacheDir(), "remote_upload"), new RemoteServer.Listener() {
                @Override public void onLog(String line) { appendLog(line); }
            });
            s.start(5000, true); // daemon 线程，随进程退出结束
            mServer = s;
            sActive = s;
            try {
                acquireWifiLock(); // 权限缺失等异常不应拖垮已启动的服务
            } catch (Throwable ignore) {
            }
            appendLog("服务已启动 :");
            Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            mServer = null;
            sActive = null;
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopServer() {
        if (mServer != null) {
            try { mServer.stop(); } catch (Exception ignore) {}
            if (sActive == mServer) sActive = null;
            mServer = null;
            releaseWifiLock();
            appendLog("服务已停止");
            Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
        }
    }

    /** 服务根目录：外部存储根（不可用时回退应用私有目录） */
    private File safeRoot() {
        File sd = android.os.Environment.getExternalStorageDirectory();
        if (sd != null && sd.isDirectory() && sd.canRead()) return sd;
        return getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir();
    }

    // ----------------------------------------------------------------
    // 地址 / 二维码 / 密钥
    // ----------------------------------------------------------------

    /** 枚举非回环 IPv4 地址（热点多网段时取全部，主地址排最前） */
    private List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    String h = addr.getHostAddress();
                    if (h == null || h.contains(":")) continue; // 只要 IPv4
                    out.add(h);
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private String primaryUrl() {
        List<String> ips = lanAddresses();
        if (ips.isEmpty()) return null;
        return "http://" + ips.get(0) + ":" + DEFAULT_PORT + "/" + mAccessKey + "/";
    }

    private void applyUi() {
        boolean running = mServer != null;
        mStatus.setText(running ? "● 运行中" : "○ 未启动");
        mStatus.setTextColor(running ? 0xFF2E9E44 : ThemeManager.textSub(this));
        mToggle.setText(running ? "停止服务" : "启动服务");
        mRegen.setEnabled(!running);

        if (running) {
            List<String> ips = lanAddresses();
            StringBuilder sb = new StringBuilder();
            for (String ip : ips) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("http://").append(ip).append(':').append(DEFAULT_PORT).append('/').append(mAccessKey).append('/');
            }
            String url = sb.length() > 0 ? sb.toString() : "—";
            mUrl.setText(url);
            mQr.setVisibility(View.VISIBLE);
            renderQr(primaryUrl());
        } else {
            mUrl.setText("—");
            mQr.setVisibility(View.GONE);
        }
    }

    /** Nayuki QrCode → Bitmap（二维码带 key 全链接，白底黑码 + 静区） */
    private void renderQr(String text) {
        if (text == null) {
            mQr.setImageBitmap(null);
            return;
        }
        try {
            QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
            final int quiet = 4;
            final int scale = 6;
            int size = (qr.size + quiet * 2) * scale;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int mx = (x / scale) - quiet;
                    int my = (y / scale) - quiet;
                    boolean dark = mx >= 0 && my >= 0 && mx < qr.size && my < qr.size && qr.getModule(mx, my);
                    bmp.setPixel(x, y, dark ? Color.BLACK : Color.WHITE);
                }
            }
            mQr.setImageBitmap(bmp);
        } catch (Throwable t) {
            mQr.setVisibility(View.GONE);
        }
    }

    private String genKey() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder(8);
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        String k = sb.toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("key", k).apply();
        return k;
    }

    private void regenKey() {
        if (mServer != null) return;
        mAccessKey = genKey();
        mKey.setText(mAccessKey);
        Toast.makeText(this, "已生成新密钥", Toast.LENGTH_SHORT).show();
    }

    private void copyUrl() {
        if (mServer == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && !TextUtils.isEmpty(mUrl.getText())) {
            cm.setText(mUrl.getText().toString());
            Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendLog(String line) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (mLogBuf.length() > 0) mLogBuf.insert(0, '\n');
                mLogBuf.insert(0, line);
                int max = 1200;
                if (mLogBuf.length() > max) mLogBuf.setLength(max);
                mLog.setText(mLogBuf);
            }
        });
    }

    // ----------------------------------------------------------------
    // Wi-Fi 锁：防止传输中 Wi-Fi 休眠断流
    // ----------------------------------------------------------------

    private void acquireWifiLock() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        releaseWifiLock();
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HexFile:remote");
        mWifiLock.setReferenceCounted(false);
        mWifiLock.acquire();
    }

    private void releaseWifiLock() {
        if (mWifiLock != null) {
            try { mWifiLock.release(); } catch (Throwable ignore) {}
            mWifiLock = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 状态自同步：无论实例如何重建/切换，UI 始终反映进程内真实服务状态
        if (mServer == null && sActive != null) {
            mServer = sActive;
            appendLog("已接管运行中的服务");
        }
        applyUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 页面关闭即停服务（服务生命周期与页面绑定）
        stopServer();
    }
}
