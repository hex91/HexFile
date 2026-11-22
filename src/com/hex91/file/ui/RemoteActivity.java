package com.hex91.file.ui;


import com.hex91.file.fs.WorkDir;
import com.hex91.file.net.RemoteServer;
import com.hex91.file.R;
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
    /** R-45：16 位 × 32 字符集 ≈ 80 bit。原 8 位只有 40 bit，在线爆破可行 */
    private static final int KEY_LEN = 16;

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

        // R-45：密钥不再跨重启复用。原来「长度 8 就继续用」等于同一把 40 bit 密钥
        // 长期有效；现在每次进入页面重新生成，并清掉历史持久化的旧值。
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("key").apply();
        mAccessKey = genKey();
        mKey.setText(mAccessKey);
        // 「仅在可信网络使用」这句必须在页面常驻，不能只写在 README
        ((TextView) findViewById(R.id.remote_hint)).setText(R.string.rmt_hint);

        mToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        mRegen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { regenKey(); }
        });
        // v1.76.0：主按钮胶囊化（具体配色随运行状态在 applyUi 里刷新）
        UiDecor.barButton((TextView) mToggle, this, UiDecor.KIND_PRIMARY, 13f);
        UiDecor.barButton((TextView) mRegen, this, UiDecor.KIND_SOFT, 12f);
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
                // 接管必须用旧服务的密钥：它校验的是构造时那一把，页面显示新的会全是 403
                mAccessKey = sActive.accessKey();
                mKey.setText(mAccessKey);
                appendLog(getString(R.string.rmt_takeover));
                return;
            }
            // R-45：每次启动服务换一把新密钥（旧链接立即失效，页面上已写明）
            mAccessKey = genKey();
            mKey.setText(mAccessKey);
            File root = safeRoot();
            RemoteServer s = new RemoteServer(this, DEFAULT_PORT, root, mAccessKey,
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
            appendLog(getString(R.string.rmt_log_started));
            Toast.makeText(this, R.string.rmt_toast_started, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            mServer = null;
            sActive = null;
            Toast.makeText(this, getString(R.string.rmt_start_fail, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopServer() {
        if (mServer != null) {
            try { mServer.stop(); } catch (Exception ignore) {}
            if (sActive == mServer) sActive = null;
            mServer = null;
            releaseWifiLock();
            appendLog(getString(R.string.rmt_log_stopped));
            Toast.makeText(this, R.string.rmt_toast_stopped, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 服务根目录（R-45）：工作目录，而不是整个外部存储根。
     *
     * <p>原来给的是 {@code Environment.getExternalStorageDirectory()}：密钥一旦被猜中，
     * 攻击面是 /sdcard 下的 DCIM、Download、Android/data 全部内容，而不只是一个子目录。
     * 现在默认只开放工作目录；需要更大范围时应该由用户在页面显式选目录（后续再做）。</p>
     */
    private File safeRoot() {
        File base = WorkDir.base();
        if (base != null && base.isDirectory() && base.canRead()) return base;
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
        mStatus.setText(running ? R.string.rmt_status_on : R.string.rmt_status_off);
        mStatus.setTextColor(running ? 0xFF2E9E44 : ThemeManager.textSub(this));
        mToggle.setText(running ? R.string.rmt_btn_stop : R.string.rmt_btn_start);
        // 运行中用危险色（停止是一项"中断服务"的操作），未启动用主操作色
        UiDecor.barButton((TextView) mToggle, this,
                running ? UiDecor.KIND_DANGER : UiDecor.KIND_PRIMARY, 13f);
        mRegen.setEnabled(!running);
        mRegen.setAlpha(running ? 0.45f : 1f);

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

    /**
     * 生成访问密钥（R-45）。
     *
     * <p>原来用 {@code java.util.Random}：种子可由 {@code System.nanoTime()} 一类外部可观测值
     * 推算，8 位字符只有约 40 bit 熵，而服务端又不限速 —— 同一 Wi-Fi 下在线爆破是可行的。
     * 一次猜中即拥有浏览 / 下载 / 上传 / 删除全部能力，攻击面是整个服务根。</p>
     *
     * <p>现在用 {@code SecureRandom}、长度 16（约 80 bit），且不再落盘：密钥只活在进程内，
     * 服务停了就作废。</p>
     */
    private String genKey() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(KEY_LEN);
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        for (int i = 0; i < KEY_LEN; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private void regenKey() {
        if (mServer != null) return;
        mAccessKey = genKey();
        mKey.setText(mAccessKey);
        Toast.makeText(this, R.string.rmt_key_generated, Toast.LENGTH_SHORT).show();
    }

    private void copyUrl() {
        if (mServer == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && !TextUtils.isEmpty(mUrl.getText())) {
            cm.setText(mUrl.getText().toString());
            Toast.makeText(this, R.string.rmt_url_copied, Toast.LENGTH_SHORT).show();
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
            appendLog(getString(R.string.rmt_takeover));
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
