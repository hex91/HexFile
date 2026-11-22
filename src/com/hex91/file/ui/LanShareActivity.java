package com.hex91.file.ui;

import com.hex91.file.net.LanNet;
import com.hex91.file.net.LanNode;
import com.hex91.file.net.LanProtocol;
import com.hex91.file.net.LanSender;
import com.hex91.file.R;
import com.hex91.file.MainActivity;
import com.hex91.file.fs.FileOps;
import com.hex91.file.core.MimeUtil;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 局域网快传（v1.74.0，v1.75.0 改版）：雷达扫描同网设备，点光点即发文件。
 *
 * <p>入口有两个：文件长按 → 「局域网快传」（带上文件路径），以及抽屉里的独立入口
 * （只接收，也可以现场选文件）。</p>
 *
 * <p>页面打开即开始「被发现 + 搜索」，离开页面时若没开「后台接收」就停止监听，
 * 避免用户以为关了应用还在后台占端口。</p>
 *
 * <p>v1.75.0 的两点变化：</p>
 * <ul>
 *   <li>接收记录不再是 {@code AlertDialog.setItems} 的纯文字弹窗，而是页面内的
 *       「接收列表」标签页，每行带打开 / 定位 / 删除；</li>
 *   <li>接收目录统一到 {@code /sdcard/HexFile/LanShare}，与其它 HexFile 产物同一棵树。</li>
 * </ul>
 */
public class LanShareActivity extends BaseActivity implements LanNode.Listener, LanNode.Confirmer {

    public static final String EXTRA_PATH = "lan_path";

    private static final int REQ_PICK = 0x4C41;

    private RadarView radar;
    private LinearLayout deviceBox;
    private LinearLayout recvBox;
    private TextView hint;
    private TextView state;
    private TextView sub;
    private View fileCard;
    private TextView fileName;
    private TextView fileTip;
    private TextView keepBtn;
    private TextView tabRadar;
    private TextView tabRecv;
    private TextView recvCount;
    private TextView recvPath;
    private View pageRadar;
    private View pageRecv;

    /** 当前是否停在「雷达」页（false = 接收列表） */
    private boolean onRadarTab = true;

    private LanNode node;

    /** 待发送来源：本机文件，或 SAF 的 Uri（两者最多一个非空） */
    private File sendFile;
    private Uri sendUri;
    private String sendName;
    private long sendSize;

    /** 还没选文件就点了设备：记住目标，选完直接发 */
    private LanProtocol.Device pendingTarget;
    /** R-47：本次会话记住上次输入的配对码，连发多个文件不用重复输 */
    private String lastCode;
    private boolean sending;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lanshare);
        node = LanNode.get(this);

        radar = (RadarView) findViewById(R.id.lan_radar);
        deviceBox = (LinearLayout) findViewById(R.id.lan_devices);
        recvBox = (LinearLayout) findViewById(R.id.lan_recv_list);
        hint = (TextView) findViewById(R.id.lan_hint);
        state = (TextView) findViewById(R.id.lan_state);
        sub = (TextView) findViewById(R.id.lan_sub);
        fileCard = findViewById(R.id.lan_file);
        fileName = (TextView) findViewById(R.id.lan_file_name);
        fileTip = (TextView) findViewById(R.id.lan_file_tip);
        keepBtn = (TextView) findViewById(R.id.lan_keep);
        tabRadar = (TextView) findViewById(R.id.lan_tab_radar);
        tabRecv = (TextView) findViewById(R.id.lan_tab_recv);
        recvCount = (TextView) findViewById(R.id.lan_recv_count);
        recvPath = (TextView) findViewById(R.id.lan_recv_path);
        pageRadar = findViewById(R.id.lan_page_radar);
        pageRecv = findViewById(R.id.lan_page_recv);

        findViewById(R.id.lan_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        radar.setOnDeviceTap(new RadarView.OnDeviceTap() {
            public void onTap(LanProtocol.Device d) { askSend(d); }
        });

        hint.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                node.probeNow();
                hint.setText(R.string.lan_searching);
            }
        });

        TextView pick = (TextView) findViewById(R.id.lan_pick);
        pick.setText(R.string.lan_pick_file);
        pick.setPadding(dp(12), dp(9), dp(12), dp(9));
        UiDecor.paintPill(pick, this, true);
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFile(); }
        });

        keepBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleKeep(); }
        });

        UiDecor.paintTab(tabRadar, this, true);
        UiDecor.paintTab(tabRecv, this, false);
        tabRadar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(true); }
        });
        tabRecv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(false); }
        });

        TextView openDir = (TextView) findViewById(R.id.lan_recv_open);
        openDir.setText(R.string.lan_open_dir);
        openDir.setPadding(UiDecor.dp(this, 10), UiDecor.dp(this, 6),
                UiDecor.dp(this, 10), UiDecor.dp(this, 6));
        UiDecor.paintPill(openDir, this, false);
        openDir.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openFolder(node.saveDir()); }
        });

        TextView refresh = (TextView) findViewById(R.id.lan_recv_refresh);
        refresh.setText(R.string.lan_refresh);
        refresh.setPadding(UiDecor.dp(this, 10), UiDecor.dp(this, 6),
                UiDecor.dp(this, 10), UiDecor.dp(this, 6));
        UiDecor.paintPill(refresh, this, false);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshReceived(); }
        });

        updateSelfLine();

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path != null) {
            File f = new File(path);
            if (f.isFile() && f.canRead()) {
                sendFile = f;
                sendName = f.getName();
                sendSize = f.length();
            } else {
                Toast.makeText(this, getString(R.string.lan_unreadable, path),
                        Toast.LENGTH_SHORT).show();
            }
        }
        updateFileCard();
        updateKeepBtn();
        refreshReceived();
    }

    @Override
    protected void onResume() {
        super.onResume();
        node.addListener(this);
        if (!node.start()) {
            state.setText(R.string.lan_state_idle);
            state.setTextColor(0xFF9E9E9E);
            hint.setText(R.string.lan_start_fail);
        } else {
            state.setText(R.string.lan_state_recv);
            state.setTextColor(0xFF5CFFC8);
            // R-47：只有页面真的在前台才问「收不收」；后台接收时没有可问的界面
            node.setConfirmer(this);
        }
        updateSelfLine();      // 配对码随 start() 生成，必须在 start 之后刷
        radar.start();
        node.probeNow();
        renderDevices(node.devices());
        if (!onRadarTab) refreshReceived();
    }

    @Override
    protected void onPause() {
        radar.stop();
        node.setConfirmer(null);
        node.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 没开「后台接收」就把监听停掉：端口不该在用户不知情时一直占着
        if (!node.keepReceiving()) node.stop();
        super.onDestroy();
    }

    /** 顶部那行：设备名 + 本机 IP + 本次的配对码 */
    private void updateSelfLine() {
        String ip = LanNet.localIp();
        String line = getString(R.string.lan_self, node.selfName(),
                ip.length() > 0 ? getString(R.string.lan_self_ip, ip) : "");
        String code = node.pairCode();
        // start() 之前没有码，这时只显示名字与 IP，不留一个「配对码 null」
        if (code != null && code.length() > 0) line = line + getString(R.string.lan_pair_code, code);
        sub.setText(line);
    }

    /**
     * 收件确认（R-47）：在 HTTP 线程被调，阻塞到用户选择或 20 秒超时。
     *
     * <p>为什么必须阻塞而不是「先回 200 再异步处理」：对端是边发边等响应的流式上传，
     * 先回 200 等于告诉它「收下了」，可用户可能点拒绝 —— 那时文件已经落盘了。
     * 阻塞在这条连接上，拒绝就干脆一个字节都不写。</p>
     *
     * <p>超时按拒绝处理：用户不在看屏幕（或页面刚好在切换）时，不该默认接收。</p>
     */
    public boolean confirm(final String from, final String name, final long size) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final boolean[] accepted = {false};
        ui.post(new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    latch.countDown();
                    return;
                }
                String shown = name == null || name.length() == 0
                        ? getString(R.string.lan_ask_unnamed) : name;
                String who = from == null || from.length() == 0
                        ? getString(R.string.lan_ask_unknown) : from;
                new HfDialogBuilder(LanShareActivity.this)
                        .setTitle(R.string.lan_ask_title)
                        .setMessage(getString(R.string.lan_ask_msg, who, shown,
                                FileOps.formatSize(size)))
                        .setPositiveButton(R.string.lan_ask_accept,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        accepted[0] = true;
                                        latch.countDown();
                                    }
                                })
                        .setNegativeButton(R.string.lan_ask_decline,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        latch.countDown();
                                    }
                                })
                        .setCancelable(false)
                        .show();
            }
        });
        try {
            return latch.await(20, java.util.concurrent.TimeUnit.SECONDS) && accepted[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------------------------------------------------------- 分页

    /** 切换标签页。雷达页停下扫描动画只在可见时跑，省电 */
    private void showTab(boolean radarTab) {
        onRadarTab = radarTab;
        pageRadar.setVisibility(radarTab ? View.VISIBLE : View.GONE);
        pageRecv.setVisibility(radarTab ? View.GONE : View.VISIBLE);
        UiDecor.paintTab(tabRadar, this, radarTab);
        UiDecor.paintTab(tabRecv, this, !radarTab);
        if (radarTab) {
            radar.start();
            renderDevices(node.devices());
        } else {
            radar.stop();
            refreshReceived();
        }
    }

    // ---------------------------------------------------------------- 设备列表

    @Override
    public void onDevices(final List<LanProtocol.Device> list) {
        renderDevices(list);
    }

    @Override
    public void onReceiveProgress(String name, long got, long total) {
        int pct = total > 0 ? (int) (got * 100 / total) : 0;
        hint.setText(getString(R.string.lan_receiving, name, pct));
    }

    @Override
    public void onReceived(String name, long size, File saved) {
        hint.setText(getString(R.string.lan_received, name, FileOps.formatSize(size),
                saved == null ? node.saveDir().getAbsolutePath() : saved.getParent()));
        Toast.makeText(this, getString(R.string.lan_received_toast, name),
                Toast.LENGTH_LONG).show();
        // 正在看接收列表时立刻出现，否则只更新标签上的计数
        refreshReceived();
    }

    @Override
    public void onNotice(String text) {
        hint.setText(text);
    }

    private void renderDevices(List<LanProtocol.Device> list) {
        if (!onRadarTab) return;
        radar.setDevices(list);
        deviceBox.removeAllViews();
        if (list == null || list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.lan_no_device);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(18), 0, dp(18));
            empty.setTextColor(ThemeManager.textSub(this));
            empty.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    node.probeNow();
                    hint.setText(R.string.lan_searching);
                }
            });
            deviceBox.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            hint.setText(R.string.lan_no_device_hint);
            return;
        }
        hint.setText(getString(R.string.lan_found, list.size()));
        for (int i = 0; i < list.size(); i++) {
            deviceBox.addView(deviceRow(list.get(i)));
        }
    }

    /** 设备行：圆角卡片 + 右侧「发送」胶囊，整行也可点 */
    private View deviceRow(final LanProtocol.Device d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(9), dp(10), dp(9));
        card.setBackground(UiDecor.cardBg(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askSend(d); }
        });

        TextView avatar = new TextView(this);
        avatar.setText("📱");
        avatar.setTextSize(17);
        avatar.setGravity(Gravity.CENTER);
        card.addView(avatar, new LinearLayout.LayoutParams(dp(30),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(d.name);
        name.setTextSize(14);
        name.setTextColor(ThemeManager.text(this));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView addr = new TextView(this);
        addr.setText(getString(R.string.lan_addr, d.host, d.port));
        addr.setTextSize(10.5f);
        addr.setTextColor(ThemeManager.textSub(this));
        texts.addView(addr);
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView send = UiDecor.action(this, "📤", getString(R.string.lan_send),
                ThemeManager.accent(this));
        card.addView(send);
        return card;
    }

    // ---------------------------------------------------------------- 接收列表

    /** 重新枚举接收目录并重建列表（文件数很少，直接全量重建最简单也最不会错） */
    private void refreshReceived() {
        final File dir = node.saveDir();
        recvPath.setText(dir.getAbsolutePath());
        recvBox.removeAllViews();
        List<File> files = LanProtocol.receivedFiles(dir);
        recvCount.setText(getString(R.string.lan_recv_count, files.size()));
        tabRecv.setText(files.isEmpty() ? getString(R.string.lan_tab_recv)
                : getString(R.string.lan_tab_recv_n, files.size()));

        if (files.isEmpty()) {
            recvBox.addView(emptyReceived(dir));
            return;
        }
        for (int i = 0; i < files.size(); i++) {
            recvBox.addView(recvRow(files.get(i)));
        }
    }

    private View emptyReceived(final File dir) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(16), dp(28), dp(16), dp(16));

        TextView tip = new TextView(this);
        tip.setText(R.string.lan_recv_empty);
        tip.setTextSize(14);
        tip.setTextColor(ThemeManager.textSub(this));
        tip.setGravity(Gravity.CENTER);
        box.addView(tip);

        TextView path = new TextView(this);
        path.setText(getString(R.string.lan_save_to, dir.getAbsolutePath()));
        path.setTextSize(11);
        path.setTextColor(ThemeManager.textSub(this));
        path.setGravity(Gravity.CENTER);
        path.setPadding(0, dp(6), 0, 0);
        box.addView(path);

        TextView how = new TextView(this);
        how.setText(R.string.lan_recv_how);
        how.setTextSize(11);
        how.setTextColor(ThemeManager.textSub(this));
        how.setGravity(Gravity.CENTER);
        how.setPadding(0, dp(4), 0, 0);
        box.addView(how);

        // 旧版本把文件收到 Downloads/HexFile/LanShare，升级后给一次一键搬迁
        final File legacy = node.legacyDir();
        if (legacy != null && !legacy.equals(dir)) {
            List<File> old = LanProtocol.receivedFiles(legacy);
            if (!old.isEmpty()) {
                box.addView(migrateCard(legacy, dir, old.size()));
            }
        }
        return box;
    }

    private View migrateCard(final File from, final File to, final int count) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(UiDecor.cardBg(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(getString(R.string.lan_legacy, count, from.getAbsolutePath()));
        t.setTextSize(11);
        t.setTextColor(ThemeManager.textSub(this));
        card.addView(t);

        TextView go = UiDecor.pill(this, "📦", getString(R.string.lan_migrate), false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        go.setLayoutParams(glp);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { migrate(from, to); }
        });
        card.addView(go);
        return card;
    }

    private View recvRow(final File f) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(8));
        card.setBackground(UiDecor.cardBg(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView ic = new TextView(this);
        ic.setText(iconOf(f.getName()));
        ic.setTextSize(18);
        ic.setGravity(Gravity.CENTER);
        top.addView(ic, new LinearLayout.LayoutParams(dp(32),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(13.5f);
        name.setTextColor(ThemeManager.text(this));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);

        TextView meta = new TextView(this);
        meta.setText(FileOps.formatSize(f.length()) + " · " + timeOf(f.lastModified()));
        meta.setTextSize(10.5f);
        meta.setTextColor(ThemeManager.textSub(this));
        texts.addView(meta);
        top.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        acts.setLayoutParams(alp);

        TextView open = UiDecor.action(this, "📂", getString(R.string.lan_open),
                ThemeManager.accent(this));
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openFile(f); }
        });
        acts.addView(open);

        TextView locate = UiDecor.action(this, "📍", getString(R.string.lan_locate),
                ThemeManager.ok(this));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(6);
        locate.setLayoutParams(llp);
        locate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openFolder(f.getParentFile()); }
        });
        acts.addView(locate);

        TextView del = UiDecor.action(this, "🗑", getString(R.string.common_delete),
                ThemeManager.err(this));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(6);
        del.setLayoutParams(dlp);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(f); }
        });
        acts.addView(del);

        card.addView(acts);
        return card;
    }

    private void confirmDelete(final File f) {
        new HfDialogBuilder(this)
                .setTitle(R.string.lan_del_title)
                .setMessage(getString(R.string.lan_del_msg, f.getName()))
                .setPositiveButton(R.string.common_delete,
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (f.delete()) {
                            Toast.makeText(LanShareActivity.this, R.string.lan_deleted,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(LanShareActivity.this, R.string.lan_del_fail,
                                    Toast.LENGTH_LONG).show();
                        }
                        refreshReceived();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 把旧目录的文件搬进新目录（同卷 rename 很快，跨卷退化成流拷贝） */
    private void migrate(final File from, final File to) {
        final ProgressDialog pd = ProgressDialog.show(this,
                getString(R.string.lan_migrate_title), getString(R.string.lan_migrating),
                true, false);
        new Thread(new Runnable() {
            public void run() {
                List<File> files = LanProtocol.receivedFiles(from);
                int ok = 0, fail = 0;
                for (int i = 0; i < files.size(); i++) {
                    if (moveTo(files.get(i), to)) ok++;
                    else fail++;
                }
                final int fok = ok, ffail = fail;
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        Toast.makeText(LanShareActivity.this,
                                ffail > 0 ? getString(R.string.lan_migrated_part, fok, ffail)
                                        : getString(R.string.lan_migrated, fok),
                                Toast.LENGTH_LONG).show();
                        refreshReceived();
                    }
                });
            }
        }, "lan-migrate").start();
    }

    /** 搬到目标目录；重名交给 LanProtocol.uniqueName 规避覆盖 */
    private boolean moveTo(File src, File dstDir) {
        try {
            String name = LanProtocol.uniqueName(src.getName(), namesOf(dstDir));
            File dst = new File(dstDir, name);
            if (src.renameTo(dst)) return true;
            InputStream in = new FileInputStream(src);
            try {
                FileOutputStream os = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
            if (dst.length() == src.length()) {
                src.delete();
                return true;
            }
            dst.delete();
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private java.util.Set<String> namesOf(File dir) {
        java.util.HashSet<String> set = new java.util.HashSet<String>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) set.add(f.getName());
        return set;
    }

    private static String iconOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.UK);
        if (n.endsWith(".apk")) return "📦";
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
                || n.endsWith(".tar") || n.endsWith(".gz")) return "🗜";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".gif")) return "🖼";
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov")) return "🎬";
        if (n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".wav")) return "🎵";
        if (n.endsWith(".pdf")) return "📕";
        if (n.endsWith(".dex") || n.endsWith(".jar")) return "📘";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "📄";
        return "📄";
    }

    private static String timeOf(long ms) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 发送

    private void askSend(final LanProtocol.Device d) {
        if (sending) {
            Toast.makeText(this, R.string.lan_sending_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (sendFile == null && sendUri == null) {
            pendingTarget = d;
            Toast.makeText(this, R.string.lan_pick_first, Toast.LENGTH_SHORT).show();
            pickFile();
            return;
        }
        // R-47：发送前必须填对方的配对码。这一步看着麻烦，但它把「同网任何人都能
        // 往我手机里塞文件」变成了「知道屏幕上那 6 位数字的人才能」。
        final EditText codeEt = new EditText(this);
        codeEt.setSingleLine(true);
        codeEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeEt.setHint(R.string.lan_pair_hint);
        if (lastCode != null) codeEt.setText(lastCode);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiDecor.dp(this, 16);
        box.setPadding(pad, 0, pad, 0);
        TextView line = new TextView(this);
        line.setText(sendName + "\n" + FileOps.formatSize(sendSize));
        line.setTextSize(12f);
        box.addView(line);
        box.addView(codeEt);

        final AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle(getString(R.string.lan_send_to, d.name))
                .setView(box)
                .setPositiveButton(R.string.lan_send, null)
                .setNegativeButton(R.string.common_cancel, null)
                .create();
        // 注意：create() 里 HfDialogBuilder 已经挂了 polishDialog 的 OnShowListener，
        // 这里再设一次会覆盖它，所以顺手补一次美化（幂等）
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                UiDecor.polishDialog(dlg);
                dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String c = codeEt.getText().toString().trim();
                                if (c.length() != LanProtocol.PAIR_CODE_LEN) {
                                    Toast.makeText(LanShareActivity.this,
                                            R.string.lan_pair_need, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                lastCode = c;
                                dlg.dismiss();
                                startSend(d, c);
                            }
                        });
            }
        });
        dlg.show();
    }

    private void startSend(final LanProtocol.Device d, final String code) {
        sending = true;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(getString(R.string.lan_sending_title));
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
                        getString(R.string.lan_send_done, sendName), Toast.LENGTH_LONG).show();
                hint.setText(getString(R.string.lan_sent_to, sendName, d.name));
            }

            public void onError(String msg) {
                sending = false;
                try { pd.dismiss(); } catch (Throwable ignored) { }
                Toast.makeText(LanShareActivity.this,
                        getString(R.string.lan_send_fail, msg), Toast.LENGTH_LONG).show();
                hint.setText(getString(R.string.lan_send_fail_hint, msg));
            }
        };

        if (sendUri != null) {
            LanSender.sendUri(this, d, sendUri, sendName, sendSize, code, cb);
        } else {
            LanSender.send(this, d, sendFile, code, cb);
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
            Toast.makeText(this, getString(R.string.lan_pick_fail, t.getMessage()),
                    Toast.LENGTH_LONG).show();
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
        pd.setMessage(getString(R.string.lan_preparing));
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
                    if (in == null) throw new java.io.IOException("cannot read the picked file");
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
                                    getString(R.string.lan_prepare_fail, t.getMessage()),
                                    Toast.LENGTH_LONG).show();
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
        fileName.setText(getString(R.string.lan_pending, sendName));
        fileTip.setText(getString(R.string.lan_pending_tip,
                FileOps.formatSize(sendSize > 0 ? sendSize : 0)));
    }

    // ---------------------------------------------------------------- 打开 / 后台

    /** 在当前应用里定位到某个目录（复用文件管理器的 dir 入口） */
    private void openFolder(File dir) {
        if (dir == null) return;
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("dir", dir.getAbsolutePath());
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.lan_open_dir_fail, t.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openFile(File f) {
        try {
            Uri u = Uri.parse("content://com.hex91.file.files" + f.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, MimeUtil.of(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.lan_no_app, Toast.LENGTH_LONG).show();
        }
    }

    private void toggleKeep() {
        boolean now = !node.keepReceiving();
        node.setKeepReceiving(now);
        if (now) {
            try {
                startService(new Intent(this, LanShareService.class));
            } catch (Throwable t) {
                Toast.makeText(this, getString(R.string.lan_keep_fail, t.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        } else {
            stopService(new Intent(this, LanShareService.class));
        }
        updateKeepBtn();
    }

    private void updateKeepBtn() {
        boolean on = node.keepReceiving();
        keepBtn.setText(on ? R.string.lan_keep_on : R.string.lan_keep_off);
        keepBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
        UiDecor.paintPill(keepBtn, this, on);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
