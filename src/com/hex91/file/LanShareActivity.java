package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 局域网快传（v1.74.0）：雷达扫描同网设备，点光点即发文件。
 *
 * <p>入口有两个：文件长按 → 「局域网快传」（带上文件路径），以及抽屉里的独立入口
 * （只接收，也可以现场选文件）。</p>
 *
 * <p>页面打开即开始「被发现 + 搜索」，离开页面时若没开「后台接收」就停止监听，
 * 避免用户以为关了应用还在后台占端口。</p>
 */
public class LanShareActivity extends BaseActivity implements LanNode.Listener {

    public static final String EXTRA_PATH = "lan_path";

    private static final int REQ_PICK = 0x4C41;

    private RadarView radar;
    private LinearLayout deviceBox;
    private TextView hint;
    private TextView state;
    private TextView sub;
    private View fileCard;
    private TextView fileName;
    private TextView fileTip;
    private Button keepBtn;

    private LanNode node;

    /** 待发送来源：本机文件，或 SAF 的 Uri（两者最多一个非空） */
    private File sendFile;
    private Uri sendUri;
    private String sendName;
    private long sendSize;

    /** 还没选文件就点了设备：记住目标，选完直接发 */
    private LanProtocol.Device pendingTarget;
    private boolean sending;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lanshare);
        node = LanNode.get(this);

        radar = (RadarView) findViewById(R.id.lan_radar);
        deviceBox = (LinearLayout) findViewById(R.id.lan_devices);
        hint = (TextView) findViewById(R.id.lan_hint);
        state = (TextView) findViewById(R.id.lan_state);
        sub = (TextView) findViewById(R.id.lan_sub);
        fileCard = findViewById(R.id.lan_file);
        fileName = (TextView) findViewById(R.id.lan_file_name);
        fileTip = (TextView) findViewById(R.id.lan_file_tip);
        keepBtn = (Button) findViewById(R.id.lan_keep);

        findViewById(R.id.lan_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        radar.setOnDeviceTap(new RadarView.OnDeviceTap() {
            public void onTap(LanProtocol.Device d) { askSend(d); }
        });

        hint.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                node.probeNow();
                hint.setText("正在重新搜索…");
            }
        });

        ((Button) findViewById(R.id.lan_pick)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFile(); }
        });
        ((Button) findViewById(R.id.lan_dir)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showReceived(); }
        });
        keepBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleKeep(); }
        });

        sub.setText("本机 " + node.selfName() + (LanNet.localIp().length() > 0
                ? " · " + LanNet.localIp() : ""));

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path != null) {
            File f = new File(path);
            if (f.isFile() && f.canRead()) {
                sendFile = f;
                sendName = f.getName();
                sendSize = f.length();
            } else {
                Toast.makeText(this, "文件不可读：" + path, Toast.LENGTH_SHORT).show();
            }
        }
        updateFileCard();
        updateKeepBtn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        node.addListener(this);
        if (!node.start()) {
            state.setText("○  未就绪");
            state.setTextColor(0xFF9E9E9E);
            hint.setText("接收服务未能启动，请检查是否有其他应用占用了发现端口");
        } else {
            state.setText("●  接收中");
            state.setTextColor(0xFF5CFFC8);
        }
        radar.start();
        node.probeNow();
        renderDevices(node.devices());
    }

    @Override
    protected void onPause() {
        radar.stop();
        node.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 没开「后台接收」就把监听停掉：端口不该在用户不知情时一直占着
        if (!node.keepReceiving()) node.stop();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 设备列表

    @Override
    public void onDevices(final List<LanProtocol.Device> list) {
        renderDevices(list);
    }

    @Override
    public void onReceiveProgress(String name, long got, long total) {
        int pct = total > 0 ? (int) (got * 100 / total) : 0;
        hint.setText("正在接收 " + name + " · " + pct + "%");
    }

    @Override
    public void onReceived(String name, long size, File saved) {
        hint.setText("已接收 " + name + " · " + FileOps.formatSize(size) + " → " + saved.getParent());
        Toast.makeText(this, "收到文件：" + name, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onNotice(String text) {
        hint.setText(text);
    }

    private void renderDevices(List<LanProtocol.Device> list) {
        radar.setDevices(list);
        deviceBox.removeAllViews();
        if (list == null || list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("未发现设备\n确认两台手机连同一个 Wi-Fi、都安装了本应用并停在这一页");
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(18), 0, dp(18));
            empty.setTextColor(ThemeManager.textSub(this));
            empty.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    node.probeNow();
                    hint.setText("正在重新搜索…");
                }
            });
            deviceBox.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            hint.setText("未发现设备 · 点此重新搜索");
            return;
        }
        hint.setText("发现 " + list.size() + " 台设备 · 点击雷达光点或下方设备发送");
        for (int i = 0; i < list.size(); i++) {
            deviceBox.addView(deviceRow(list.get(i)));
        }
    }

    private View deviceRow(final LanProtocol.Device d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackgroundColor(ThemeManager.surface(this));
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askSend(d); }
        });

        TextView dot = new TextView(this);
        dot.setText("◉");
        dot.setTextSize(15);
        dot.setTextColor(RadarView.accentColor());
        row.addView(dot);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(d.name);
        name.setTextSize(14);
        name.setTextColor(ThemeManager.text(this));
        texts.addView(name);
        TextView addr = new TextView(this);
        addr.setText(d.host + ":" + d.port + " · 同一网络");
        addr.setTextSize(11);
        addr.setTextColor(ThemeManager.textSub(this));
        texts.addView(addr);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(this);
        action.setText("发送  ›");
        action.setTextSize(12);
        action.setTextColor(ThemeManager.accent(this));
        row.addView(action);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View line = new View(this);
        line.setBackgroundColor(ThemeManager.divider(this));
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f))));
        return wrap;
    }

    // ---------------------------------------------------------------- 发送

    private void askSend(final LanProtocol.Device d) {
        if (sending) {
            Toast.makeText(this, "正在发送，请等当前传输结束", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sendFile == null && sendUri == null) {
            pendingTarget = d;
            Toast.makeText(this, "先选择要发送的文件", Toast.LENGTH_SHORT).show();
            pickFile();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发送到 " + d.name)
                .setMessage(sendName + "\n" + FileOps.formatSize(sendSize))
                .setPositiveButton("发送", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) { startSend(d); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startSend(final LanProtocol.Device d) {
        sending = true;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("正在发送");
        pd.setMessage(sendName);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        LanSender.Cb cb = new LanSender.Cb() {
            public void onProgress(int pct, long sent, long total) {
                pd.setProgress(pct);
                pd.setMessage(sendName + "\n" + FileOps.formatSize(sent) + " / "
                        + FileOps.formatSize(total));
            }

            public void onDone(String msg) {
                sending = false;
                try { pd.dismiss(); } catch (Throwable ignored) { }
                Toast.makeText(LanShareActivity.this,
                        "发送完成：" + sendName, Toast.LENGTH_LONG).show();
                hint.setText("已发送 " + sendName + " 到 " + d.name);
            }

            public void onError(String msg) {
                sending = false;
                try { pd.dismiss(); } catch (Throwable ignored) { }
                Toast.makeText(LanShareActivity.this,
                        "发送失败：" + msg, Toast.LENGTH_LONG).show();
                hint.setText("发送失败 · " + msg);
            }
        };

        if (sendUri != null) {
            LanSender.sendUri(this, d, sendUri, sendName, sendSize, cb);
        } else {
            LanSender.send(this, d, sendFile, cb);
        }
    }

    // ---------------------------------------------------------------- 选文件

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = null;
        long size = -1;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0) name = c.getString(ni);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
        if (name == null || name.length() == 0) name = "file_" + System.currentTimeMillis() + ".bin";
        name = LanProtocol.safeFileName(name);

        sendFile = null;
        sendUri = null;
        sendName = name;
        sendSize = size > 0 ? size : -1;

        if (size > 0) {
            sendUri = uri;
            afterPick();
        } else {
            copyToCache(uri, name);
        }
    }

    /** 少数 provider 不给 SIZE，先落到缓存目录，才能给出准确 Content-Length */
    private void copyToCache(final Uri uri, final String name) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("准备文件…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                File out = null;
                InputStream in = null;
                try {
                    File dir = new File(getCacheDir(), "lan_send");
                    dir.mkdirs();
                    out = new File(dir, name);
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("无法读取文件");
                    FileOutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                    os.close();
                    final File ok = out;
                    ui.post(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Throwable ignored) { }
                            sendFile = ok;
                            sendName = ok.getName();
                            sendSize = ok.length();
                            afterPick();
                        }
                    });
                } catch (final Throwable t) {
                    try { if (out != null) out.delete(); } catch (Throwable ignored) { }
                    ui.post(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Throwable ignored) { }
                            Toast.makeText(LanShareActivity.this,
                                    "准备文件失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
            }
        }, "lan-copy").start();
    }

    private void afterPick() {
        updateFileCard();
        if (pendingTarget != null) {
            LanProtocol.Device d = pendingTarget;
            pendingTarget = null;
            askSend(d);
        }
    }

    private void updateFileCard() {
        boolean has = sendFile != null || sendUri != null;
        fileCard.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) return;
        fileName.setText("待发送：" + sendName);
        fileTip.setText(FileOps.formatSize(sendSize > 0 ? sendSize : 0)
                + " · 点击下方设备或雷达光点发送");
    }

    // ---------------------------------------------------------------- 接收目录 / 后台

    private void showReceived() {
        final File dir = node.saveDir();
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("接收记录")
                    .setMessage("还没有收到文件。\n保存位置：\n" + dir.getAbsolutePath())
                    .setPositiveButton("复制路径", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            copy(dir.getAbsolutePath());
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        List<File> list = new ArrayList<File>();
        for (int i = 0; i < fs.length; i++) {
            if (fs[i].isFile() && !fs[i].getName().endsWith(".part")) list.add(fs[i]);
        }
        java.util.Collections.sort(list, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        final File[] arr = list.toArray(new File[0]);
        String[] items = new String[arr.length];
        for (int i = 0; i < arr.length; i++) {
            items[i] = arr[i].getName() + "   " + FileOps.formatSize(arr[i].length());
        }
        new AlertDialog.Builder(this)
                .setTitle("接收记录（" + arr.length + "）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { openFile(arr[which]); }
                })
                .setNeutralButton("复制目录", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { copy(dir.getAbsolutePath()); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openFile(File f) {
        try {
            Uri u = Uri.parse("content://com.hex91.file.files" + f.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, MimeUtil.of(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "没有能打开该文件的应用（可到文件管理器查看）",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copy(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("HexFile", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void toggleKeep() {
        boolean now = !node.keepReceiving();
        node.setKeepReceiving(now);
        if (now) {
            try {
                startService(new Intent(this, LanShareService.class));
            } catch (Throwable t) {
                Toast.makeText(this, "开启失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            stopService(new Intent(this, LanShareService.class));
        }
        updateKeepBtn();
    }

    private void updateKeepBtn() {
        keepBtn.setText(node.keepReceiving() ? "后台接收：开" : "后台接收");
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
