package com.hex91.file;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.PopupMenu;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 双栏主界面 —— 对标 bin.mt.plus.Main 的架构：
 * BaseActivity 职责由本类直接承担，FeatureActivity 的注册表模式见 FeatureRegistry。
 */
public class MainActivity extends BaseActivity {

    private static final int REQ_STORAGE = 1;
    private static final int REQ_BATCH_RENAME = 3001;
    private static final int REQ_CUSTOM_KEYSTORE = 3002;
    /** 自定义签名待处理的 APK（onActivityResult 期间持有） */
    private File mCustomSignSrc;
    private boolean mCustomSignInPlace;

    private TextView headerPath, headerStats;
    private ListView listLeft, listRight;

    /** 单侧面板状态（对标 MT 的左右窗口） */
    private class Panel {
        ListView list;
        /** v1.80.0：网格/大图视图（与 list 共用同一份数据，同一时刻只有一个可见） */
        GridView grid;
        FileGridAdapter gridAdapter;
        /** v1.79.0：列表外层的下拉刷新容器 */
        PullRefreshLayout pull;
        FileAdapter adapter;
        File dir;
        boolean active;
        // ---------- 窗格内嵌压缩包浏览（v1.14） ----------
        File archiveFile;                 // 非空 = 该窗格正在浏览压缩包
        ArchiveUtil.Archive archive;
        String archPrefix = "";           // 压缩包内当前路径前缀
        /** 目录历史栈（‹ › 前进后退用） */
        final java.util.List<String> history = new java.util.ArrayList<String>();
        int hIndex = -1;
    }
    private Panel left = new Panel();
    private Panel right = new Panel();

    /** v1.78.0：窗格显示偏好（排序方式 / 是否显示 . 文件 / 列表or网格），两栏共用 */
    private static final String PREF_UI = "hf_view";
    private static final String KEY_SORT = "sort_mode";
    private static final String KEY_HIDDEN = "show_hidden";
    /** v1.80.0：视图模式（0=列表 1=网格/大图） */
    private static final String KEY_VIEW = "view_mode";
    private static final int VIEW_LIST = 0;
    private static final int VIEW_GRID = 1;
    private int viewMode = VIEW_LIST;

    private void saveViewMode(int mode) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putInt(KEY_VIEW, mode).apply();
    }

    private void saveSortMode(int mode) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putInt(KEY_SORT, mode).apply();
    }

    private void saveShowHidden(boolean show) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putBoolean(KEY_HIDDEN, show).apply();
    }

    /** v1.46.0：旧版（bin.mtclone/MTClone 时代）遗留目录一次性迁移到 HexFile 体系 */
    private void migrateLegacyDirs() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.File sd = android.os.Environment.getExternalStorageDirectory();
                    java.io.File newRoot = new java.io.File(sd, "HexFile");
                    // 旧回收站 .MTCloneTrash → HexFile/Trash
                    java.io.File oldTrash = new java.io.File(sd, ".MTCloneTrash");
                    java.io.File newTrash = new java.io.File(newRoot, "Trash");
                    if (oldTrash.isDirectory() && !newTrash.exists()) {
                        newRoot.mkdirs();
                        oldTrash.renameTo(newTrash);
                    }
                    // 旧 smali 输出 MTCloneSmali → HexFile/Smali
                    java.io.File oldSmali = new java.io.File(sd, "MTCloneSmali");
                    java.io.File newSmali = new java.io.File(newRoot, "Smali");
                    if (oldSmali.isDirectory() && !newSmali.exists()) {
                        newRoot.mkdirs();
                        oldSmali.renameTo(newSmali);
                    }
                    // 旧操作目录 MTClone（Apps 等）→ 合并进 HexFile
                    java.io.File oldRoot = new java.io.File(sd, "MTClone");
                    if (oldRoot.isDirectory()) {
                        newRoot.mkdirs();
                        java.io.File[] kids = oldRoot.listFiles();
                        if (kids != null)
                            for (java.io.File k : kids) k.renameTo(new java.io.File(newRoot, k.getName()));
                        oldRoot.delete();
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "migrate-dirs").start();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        migrateLegacyDirs();   // v1.46.0 更名：旧 MTClone 目录一次性搬入 HexFile

        // v1.67.3：清理 v1.66.x 遗留的明文密码（dexpp_sign 里的 ks_pass/ks_path，v1.67.0 起已不再使用）
        android.content.SharedPreferences legacySign = getSharedPreferences("dexpp_sign", MODE_PRIVATE);
        if (legacySign.contains("ks_pass") || legacySign.contains("ks_path")) {
            legacySign.edit().remove("ks_pass").remove("ks_path").apply();
        }

        // v1.67.3：后台预热签名密码的 Keystore 密钥（首次生成可能走 TEE，避免首次签名时卡 UI）
        KeyCipher.warmUp();

        // v1.82.0：按回收站保留策略静默清理一次（后台线程，进程内只跑一次）
        TrashJanitor.startOnce();

        headerPath = (TextView) findViewById(R.id.header_path);
        headerStats = (TextView) findViewById(R.id.header_stats);
        listLeft = (ListView) findViewById(R.id.list_left);
        listRight = (ListView) findViewById(R.id.list_right);

        left.list = listLeft;
        right.list = listRight;
        left.pull = (PullRefreshLayout) findViewById(R.id.pull_left);
        right.pull = (PullRefreshLayout) findViewById(R.id.pull_right);
        left.grid = (GridView) findViewById(R.id.grid_left);
        right.grid = (GridView) findViewById(R.id.grid_right);

        if (Build.VERSION.SDK_INT >= 30 && !hasAllFilesAccess()) {
            // Android 11+：文件管理器必须「所有文件访问」权限（对标 MT Manager 做法）
            requestManageAllFiles();
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }

        setupPanel(left);
        setupPanel(right);
        loadDir(left, Environment.getExternalStorageDirectory());
        loadDir(right, Environment.getExternalStorageDirectory());
        left.active = true;
        // v1.80.0：视图模式也持久化（和排序/隐藏文件同一份偏好），重启保持
        viewMode = getSharedPreferences(PREF_UI, MODE_PRIVATE).getInt(KEY_VIEW, VIEW_LIST);
        applyViewMode();

        // 底部 MT 工具栏：‹ 历史 › ＋ 新建 ⇄ 交换 ↑ 上级
        findViewById(R.id.btn_up).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Panel p = active();
                if (p.archiveFile != null) { archGoUp(p); return; }
                File up = p.dir.getParentFile();
                if (up != null && up.canRead()) loadDir(p, up);
            }
        });
        findViewById(R.id.btn_hist_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { jumpHistory(-1); }
        });
        findViewById(R.id.btn_hist_fwd).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { jumpHistory(1); }
        });
        findViewById(R.id.btn_new).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askCreateNew(); }
        });
        findViewById(R.id.btn_swap).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { swapPanes(); }
        });
        // 顶部路径可点 = 书签；⋮ = 主菜单
        headerPath.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showBookmarks(); }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMoreMenu(); }
        });
        setupDrawer();

        setupSelectionBar();
        setupTaskBar();
        setupPasteButton();
        handleJump(getIntent());
    }

    // ============================================================ 剪贴板（v1.78.0）

    private TextView btnPaste;

    private void setupPasteButton() {
        btnPaste = (TextView) findViewById(R.id.btn_paste);
        if (btnPaste == null) return;
        btnPaste.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pasteClipboard(); }
        });
        updatePasteButton();
    }

    /** 剪贴板非空时按钮变强调色并显示数量；空时置灰（不 GONE，免得底部整排跳动） */
    private void updatePasteButton() {
        if (btnPaste == null) return;
        FileClipboard cb = FileClipboard.get();
        cb.prune();                       // 源文件被删掉的不再计入，避免显示过期数量
        boolean has = !cb.isEmpty();
        btnPaste.setText(has ? "📋 " + cb.size() : "📋");
        btnPaste.setAlpha(has ? 1f : 0.32f);
        btnPaste.setTextColor(has ? ThemeManager.accent(this) : ThemeManager.textSub(this));
    }

    /** 粘贴到活动窗格当前目录；复用任务队列，所以照样有进度、可取消 */
    private void pasteClipboard() {
        final Panel p = active();
        FileClipboard cb = FileClipboard.get();
        cb.prune();
        if (cb.isEmpty()) {
            toast("剪贴板是空的：长按文件选「复制(剪贴板)」或「剪切(剪贴板)」");
            updatePasteButton();
            return;
        }
        if (p.archiveFile != null) { toast("正在浏览压缩包，先返回到目录再粘贴"); return; }
        if (p.dir == null || !p.dir.isDirectory()) { toast("当前没有可写入的目录"); return; }
        final List<File> srcs = cb.alive();
        if (srcs.isEmpty()) {
            cb.clear();
            updatePasteButton();
            toast("剪贴板里的文件已不存在");
            return;
        }
        final boolean move = cb.isMove();
        FileTaskEngine.get().submit(move ? FileTask.MOVE : FileTask.COPY, srcs, p.dir);
        cb.clearIfMove();                 // 剪切是一次性的；复制可以连着粘多处
        updatePasteButton();
        toast((move ? "开始移动 " : "开始复制 ") + srcs.size() + " 项 → " + p.dir.getName());
    }

    // ============================================================ 文件任务条（v1.77.0）

    private View taskBar;
    private TextView taskText, taskAction;
    private android.widget.ProgressBar taskProgress;
    private final android.os.Handler taskUi =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 已结束任务计数：变化时才刷新窗格，避免每次进度回调都重列目录 */
    private int taskDoneSeen;

    /** 任务条自动收起：全部结束后停留 8 秒，让用户看得见结果 */
    private final Runnable hideTaskBar = new Runnable() {
        public void run() {
            if (taskBar != null && !FileTaskEngine.get().isBusy()) {
                taskBar.setVisibility(View.GONE);
            }
        }
    };

    private final FileTaskEngine.Listener taskListener = new FileTaskEngine.Listener() {
        public void onTasksChanged() {
            taskUi.post(new Runnable() {
                public void run() { renderTaskBar(); }
            });
        }
    };

    private void setupTaskBar() {
        taskBar = findViewById(R.id.task_bar);
        if (taskBar == null) return;
        taskText = (TextView) findViewById(R.id.task_text);
        taskAction = (TextView) findViewById(R.id.task_action);
        taskProgress = (android.widget.ProgressBar) findViewById(R.id.task_progress);
        UiDecor.paintPill(taskAction, this, UiDecor.KIND_SOFT);
        taskAction.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // v1.77.1：这里原来先判断 isBusy()，而 isBusy() 在「正在拷贝」时返回 false
                // （worker 已把任务从 pending 取走），于是点「取消」反而打开了任务面板。
                // 现在用 hasActive()，并且一点就取消，不再弹二次确认。
                if (FileTaskEngine.get().hasActive()) {
                    FileTaskEngine.get().cancelAll();
                    renderTaskBar();                 // 立刻把「取消中」画出来
                } else {
                    showTaskPanel();
                }
            }
        });
        findViewById(R.id.task_body).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTaskPanel(); }
        });
        // root 区（/data 等）File API 无权限时的兜底，注入给引擎，引擎本身保持纯 Java
        FileTaskEngine.get().setRootFallback(new FileTaskEngine.RootFallback() {
            public boolean copy(File src, File dstDir) { return RootIO.copyInto(src, dstDir); }

            public boolean move(File src, File dstDir) { return RootIO.moveInto(src, dstDir); }

            public boolean delete(File f) { return RootIO.delete(f); }
        });
    }

    /** 任务条：一个活动任务显示「正在复制 3 项 · 42% · 12 MB/s」，全部结束显示结果 */
    private void renderTaskBar() {
        if (taskBar == null) return;
        List<FileTask> list = FileTaskEngine.get().tasks();
        FileTask running = null, last = null;
        int active = 0, done = 0;
        for (FileTask t : list) {
            if (t.cancelable()) {
                active++;
                if (running == null || t.state == FileTask.RUNNING) running = t;
            } else {
                done++;
                last = t;
            }
        }
        if (done != taskDoneSeen) {
            taskDoneSeen = done;
            if (active == 0) refreshAll();     // 收尾刷新：新文件立刻出现在对面窗格
        }
        if (active == 0 && last == null) {
            taskBar.setVisibility(View.GONE);
            return;
        }
        taskBar.setVisibility(View.VISIBLE);
        if (active > 0) {
            taskUi.removeCallbacks(hideTaskBar);
            int pct = running.percent();
            taskProgress.setProgress(pct);
            if (running.cancelRequested) {
                // 取消标志每 128KB 才检查一次，写大块数据时还会晚几百毫秒；
                // 这段时间必须给出反馈，否则按钮看起来「点了没反应」，用户会连点。
                taskText.setText("正在取消 " + running.title + "…");
                taskAction.setText("取消中");
                taskAction.setEnabled(false);
                taskAction.setAlpha(0.45f);
            } else {
                StringBuilder sb = new StringBuilder("正在").append(running.title)
                        .append(" · ").append(pct).append('%');
                long sp = running.speed(System.currentTimeMillis());
                if (sp > 0) sb.append(" · ").append(FileOps.formatSize(sp)).append("/s");
                if (active > 1) sb.append(" · 另 ").append(active - 1).append(" 项排队");
                taskText.setText(sb.toString());
                taskAction.setText("取消");
                taskAction.setEnabled(true);
                taskAction.setAlpha(1f);
            }
        } else {
            taskProgress.setProgress(100);
            taskText.setText(resultLine(last));
            taskAction.setText("查看");
            taskAction.setEnabled(true);
            taskAction.setAlpha(1f);
            taskUi.removeCallbacks(hideTaskBar);
            taskUi.postDelayed(hideTaskBar, 8000);
        }
    }

    private String resultLine(FileTask t) {
        switch (t.state) {
            case FileTask.DONE:
                return "✓ " + t.title + "完成 · " + FileOps.formatSize(t.totalBytes);
            case FileTask.PARTIAL:
                return "⚠ " + t.title + "，失败 " + t.failedFiles + " 项";
            case FileTask.FAILED:
                return "✗ " + t.title + "失败";
            case FileTask.CANCELLED:
                return "已取消 " + t.title;
            default:
                return t.title;
        }
    }

    /** 任务面板：所有任务的进度、速度、目标目录，可逐个取消 */
    private void showTaskPanel() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiDecor.dp(this, 14);
        box.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        final AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle("文件任务")
                .setView(sv)
                .setNeutralButton("清理已完成", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        FileTaskEngine.get().clearFinished();
                    }
                })
                .setNegativeButton("全部取消", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        FileTaskEngine.get().cancelAll();
                    }
                })
                .setPositiveButton("关闭", null)
                .create();

        // 面板开着时每 500ms 刷一次；不用引擎回调是因为弹窗里还要重算自己的行布局
        final Runnable tick = new Runnable() {
            public void run() {
                fillTaskPanel(box);
                if (dlg.isShowing()) taskUi.postDelayed(this, 500);
            }
        };
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) {
                taskUi.removeCallbacks(tick);
            }
        });
        dlg.show();
        tick.run();
    }

    private void fillTaskPanel(LinearLayout box) {
        box.removeAllViews();
        List<FileTask> list = FileTaskEngine.get().tasks();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无任务\n\n复制 / 移动文件时会在这里显示进度，可随时取消。");
            empty.setTextSize(12.5f);
            empty.setTextColor(ThemeManager.textSub(this));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiDecor.dp(this, 16), 0, UiDecor.dp(this, 16));
            box.addView(empty);
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {   // 最新在上
            box.addView(taskRow(list.get(i)));
        }
    }

    private View taskRow(final FileTask t) {
        LinearLayout card = UiDecor.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = UiDecor.dp(this, 8);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(t.title);
        name.setTextSize(13f);
        name.setTextColor(ThemeManager.text(this));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = new TextView(this);
        state.setText(t.stateText());
        state.setTextSize(11.5f);
        boolean bad = t.finished() && t.state != FileTask.DONE;
        state.setTextColor(bad ? ThemeManager.err(this) : ThemeManager.accent(this));
        top.addView(state);
        card.addView(top);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(t.percent());
        // 必须带 theme 解析：进度条颜色是 ?attr/hfAccent，无主题的 getDrawable 会解析失败
        pb.setProgressDrawable(getResources().getDrawable(R.drawable.hf_task_progress, getTheme()));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiDecor.dp(this, 4));
        plp.topMargin = UiDecor.dp(this, 8);
        card.addView(pb, plp);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView info = new TextView(this);
        info.setText(taskDetail(t));
        info.setTextSize(11f);
        info.setTextColor(ThemeManager.textSub(this));
        info.setPadding(0, UiDecor.dp(this, 7), UiDecor.dp(this, 6), 0);
        foot.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (t.cancelable()) {
            TextView cancel = UiDecor.action(this, "✕", "取消", ThemeManager.err(this));
            cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { FileTaskEngine.get().cancel(t.id); }
            });
            foot.addView(cancel);
        }
        card.addView(foot);
        return card;
    }

    private String taskDetail(FileTask t) {
        StringBuilder sb = new StringBuilder();
        sb.append(FileOps.formatSize(t.doneBytes)).append(" / ")
                .append(FileOps.formatSize(t.totalBytes))
                .append(" · ").append(t.doneFiles).append('/').append(t.totalFiles).append(" 个文件");
        if (t.state == FileTask.RUNNING) {
            long sp = t.speed(System.currentTimeMillis());
            if (sp > 0) sb.append(" · ").append(FileOps.formatSize(sp)).append("/s");
        }
        if (t.message != null && t.finished() && t.state != FileTask.DONE) {
            sb.append('\n').append(t.message);
        }
        sb.append('\n').append(t.dstPath);
        return sb.toString();
    }

    // ============================================================ 左侧抽屉（v1.41.0）

    private View drawer, drawerMask;

    /** 抽屉初始化：☰ 打开 / 遮罩点击关闭 / 菜单项分发 */
    private void setupDrawer() {
        drawer = findViewById(R.id.drawer);
        drawerMask = findViewById(R.id.drawer_mask);
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.drawer_version)).setText("v" + ver);
        } catch (Exception ignored) {}
        findViewById(R.id.btn_drawer).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDrawer(); }
        });
        drawerMask.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        findViewById(R.id.drawer_bookmark).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); showBookmarks(); }
        });
        // v1.42.0：本地存储区（根目录 / 内部存储）
        findViewById(R.id.drawer_root).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                loadDir(active(), new File("/"));
                if (!RootUtil.isRootAvailable())
                    toast("未获取 Root，仅能浏览可读部分（/data 等目录不可见）");
            }
        });
        findViewById(R.id.drawer_sd).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                loadDir(active(), Environment.getExternalStorageDirectory());
            }
        });
        refreshDrawerStorage();
        checkRootAsync();
        findViewById(R.id.drawer_trash).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, TrashActivity.class));
            }
        });
        // v1.45.0：应用管理（已安装应用列表 + 提取安装包）
        findViewById(R.id.drawer_apps).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, AppManagerActivity.class));
            }
        });
        findViewById(R.id.drawer_backups).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.54.0：备份管理（列出所有备份，支持已卸载应用还原）
                startActivity(new Intent(MainActivity.this, BackupManagerActivity.class));
            }
        });
        // v1.68.0：签名管理（导入 / 创建 / 查看 / 重命名 / 删除 / 设为默认）
        findViewById(R.id.drawer_signkeys).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, SignKeyManagerActivity.class));
            }
        });
        // v1.69.0：Activity 记录（无障碍监听页面切换 + 透明浮窗）
        findViewById(R.id.drawer_activity_log).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, ActivityLogActivity.class));
            }
        });
        // v1.70.0：工具箱（编码 / 加解密 / 进制 / 网络 / 时间戳）
        findViewById(R.id.drawer_toolbox).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, ToolboxActivity.class));
            }
        });
        // v1.74.0：局域网快传（雷达扫描同网设备，点光点传文件）
        findViewById(R.id.drawer_lan).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, LanShareActivity.class));
            }
        });
        // v1.83.0：统一设置页入口（抽屉 + ⋮ 菜单各一处）
        findViewById(R.id.drawer_settings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.drawer_theme).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.48.0：主题设置页（22 套色板 + 明暗模式）
                startActivity(new Intent(MainActivity.this, ThemeActivity.class));
            }
        });
        findViewById(R.id.drawer_remote).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.50.0：远程管理（局域网 HTTP 服务器 + 浏览器管理手机文件）
                startActivity(new Intent(MainActivity.this, RemoteActivity.class));
            }
        });
        findViewById(R.id.drawer_net).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.56.0：网络存储（SMB/WebDAV 客户端，直连电脑共享）
                startActivity(new Intent(MainActivity.this, NetStorageActivity.class));
            }
        });
        findViewById(R.id.drawer_storage).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.59.0：存储分析（目录占用树下钻 + 大文件清理 + 重复文件查找）
                startActivity(new Intent(MainActivity.this, StorageAnalysisActivity.class));
            }
        });
        findViewById(R.id.drawer_smali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.61.0：Smali 语法查询（红色语法 + 中文注释解析 + 搜索，数据可扩展）
                startActivity(new Intent(MainActivity.this, SmaliDocActivity.class));
            }
        });
        findViewById(R.id.drawer_java2smali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.62.0：Java → Smali（nb-javac 编译 + dx 打包 + baksmali 反汇编，全离线）
                startActivity(new Intent(MainActivity.this, Java2SmaliActivity.class));
            }
        });
        findViewById(R.id.drawer_terminal).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.49.0：内置终端模拟器，开局即当前浏览目录
                String cwd = null;
                try {
                    File d = active().dir;
                    if (d != null) cwd = d.getAbsolutePath();
                } catch (Throwable ignore) {
                }
                if (cwd == null || cwd.isEmpty()) {
                    cwd = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                }
                Intent it = new Intent(MainActivity.this, TerminalActivity.class);
                it.putExtra(TerminalActivity.EXTRA_CWD, cwd);
                startActivity(it);
            }
        });
    }

    /** v1.42.0：抽屉存储容量统计（根目录 / 内部存储），后台线程 StatFs */
    private void refreshDrawerStorage() {
        new Thread(new Runnable() {
            public void run() {
                final String rootStat = statFs("/");
                // 内部存储与根目录同属 /data 分区，数值必然一样；这行只报可用量，避免两行完全重复
                final String sdStat = statFree(Environment.getExternalStorageDirectory().getPath());
                runOnUiThread(new Runnable() {
                    public void run() {
                        ((TextView) findViewById(R.id.drawer_root_usage)).setText(rootStat);
                        ((TextView) findViewById(R.id.drawer_sd_usage)).setText(sdStat);
                    }
                });
            }
        }, "drawer-storage").start();
    }

    /**
     * v1.72.0：把任意路径映射到「真正存放数据的可写分区」。
     *
     * 以前直接 StatFs("/")，拿到的是 system 分区（只读、约 1G、getAvailableBytes 恒为 0），
     * 于是抽屉里根目录显示「987M 已用 / 987M（100%）」，和 MT 等管理器显示的可用空间对不上。
     * 根目录下真正可写的是 /data，所以 / 与各系统只读挂载点统一按 /data 统计。
     */
    private static String statPathFor(String path) {
        String data = Environment.getDataDirectory().getAbsolutePath();
        if (path == null || path.isEmpty() || path.equals("/")) return data;
        String[] ro = {"/system", "/vendor", "/product", "/odm", "/apex", "/sbin", "/etc", "/init"};
        for (String p : ro) {
            if (path.equals(p) || path.startsWith(p + "/")) return data;
        }
        return path;
    }

    /** 返回 {总量, 可用}；路径自身拿不到就逐级回退（→ /data → 外部存储），全失败返回 null */
    private static long[] usageOf(String path) {
        String data = Environment.getDataDirectory().getAbsolutePath();
        String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[] tries = {statPathFor(path), data, ext};
        for (String t : tries) {
            try {
                android.os.StatFs sf = new android.os.StatFs(t);
                long total = sf.getTotalBytes();
                if (total > 0) return new long[]{total, sf.getAvailableBytes()};
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String statFs(String path) {
        long[] u = usageOf(path);
        if (u == null) return "容量不可用";
        long total = u[0], avail = u[1];
        long used = total > avail ? total - avail : 0;
        int pct = total > 0 ? (int) (used * 100 / total) : 0;
        return FileOps.formatSize(used) + " 已用 / " + FileOps.formatSize(total)
                + "（" + pct + "%）  可用 " + FileOps.formatSize(avail);
    }

    private String statFree(String path) {
        long[] u = usageOf(path);
        if (u == null) return "容量不可用";
        return "可用 " + FileOps.formatSize(u[1]) + " / 共 " + FileOps.formatSize(u[0]);
    }

    /** v1.42.0：root 检测（su -c id），结果落 RootUtil 缓存并更新抽屉状态行 */
    private void checkRootAsync() {
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = RootUtil.detect();
                runOnUiThread(new Runnable() {
                    public void run() {
                        TextView tv = (TextView) findViewById(R.id.drawer_rootstat);
                        tv.setText(ok ? "Root: 已授权 ✓" : "Root: 未获取");
                        tv.setTextColor(ok ? 0xFFAED581 : 0xFF90A4AE);
                    }
                });
            }
        }, "root-detect").start();
    }

    private void openDrawer() {
        drawerMask.setVisibility(View.VISIBLE);
        drawerMask.setAlpha(0f);
        drawerMask.animate().alpha(1f).setDuration(220).start();
        drawer.setVisibility(View.VISIBLE);
        // GONE 期间宽度为 0，等布局完成后再从屏外滑入
        drawer.post(new Runnable() {
            public void run() {
                drawer.setTranslationX(-drawer.getWidth() - 20);
                drawer.animate().translationX(0).setDuration(220).start();
                // v1.73.1：每次打开回到菜单顶部。此前滚到中间再关掉，
                // 下次打开会停在半截位置，看不到「本地」区和前几个入口。
                View scroll = findViewById(R.id.drawer_scroll);
                if (scroll != null) scroll.scrollTo(0, 0);
            }
        });
    }

    private void closeDrawer() {
        drawer.animate().translationX(-drawer.getWidth() - 20).setDuration(180)
                .withEndAction(new Runnable() {
                    public void run() {
                        drawer.setVisibility(View.GONE);
                        drawerMask.setVisibility(View.GONE);
                    }
                }).start();
        drawerMask.animate().alpha(0f).setDuration(180).start();
    }

    /** 未完成功能占位弹窗（主题设置/远程管理/终端模拟器） */
    private void showComingSoon(String title, String desc) {
        new HfDialogBuilder(this)
                .setTitle(title)
                .setMessage(desc)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** ⋮ 主菜单（对标 MT 溢出菜单，锚定在右上角 ⋮ 按钮往下弹出） */
    private void showMoreMenu() {
        final Panel p = active();
        View anchor = findViewById(R.id.btn_more);
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        // 压缩包浏览模式：置顶提供「解压全部到此目录」一键入口
        if (p.archiveFile != null) {
            menu.add(Menu.NONE, 900, 0, "解压全部到此目录");
        }
        menu.add(Menu.NONE, 0, 1, "刷新");
        menu.add(Menu.NONE, 1, 1, "搜索");
        menu.add(Menu.NONE, 2, 2, "全选");
        menu.add(Menu.NONE, 3, 3, "排序方式");
        // v1.80.0：列表 ⇄ 网格（大图）切换，偏好持久化
        menu.add(Menu.NONE, 10, 3, viewMode == VIEW_GRID ? "列表视图（双栏）" : "网格视图（大图）");
        menu.add(Menu.NONE, 4, 4, p.adapter.showHidden ? "隐藏 .文件" : "显示 .文件");
        menu.add(Menu.NONE, 5, 5, "书签");
        menu.add(Menu.NONE, 6, 6, "回收站");
        menu.add(Menu.NONE, 7, 7, "交换窗口");
        // v1.78.0：剪贴板非空时把粘贴放到显眼位置，并带上「复制 3 项」这样的摘要
        menu.add(Menu.NONE, 9, 0, FileClipboard.get().isEmpty()
                ? "粘贴（剪贴板为空）"
                : "粘贴：" + FileClipboard.get().label());
        menu.add(Menu.NONE, 8, 8, "最近打开");
        // v1.87.0：云同步（WebDAV 双向）
        menu.add(Menu.NONE, 13, 9, "云同步…");
        // v1.83.0：设置页（主题 / 工作目录 / Root / 回收站策略 / 版本）
        menu.add(Menu.NONE, 12, 9, "设置");
        // 让菜单右边缘与锚点（右上角 ⋮ 按钮）对齐，呈现“吸附右上角”效果
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            popup.setGravity(Gravity.END);
        }
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 900:
                        // 解压全部到此目录（压缩包所在文件夹）
                        extractArchAll(p);
                        break;
                    case 0:
                        // 刷新：压缩包浏览中 = 重列压缩包（不再退出压缩包）
                        // v1.79.0：走和下拉刷新同一条路径（转圈 + 不写历史栈）
                        refreshPanel(p);
                        break;
                    case 1:
                        Intent i = new Intent(MainActivity.this, SearchActivity.class);
                        i.putExtra(SearchActivity.EXTRA_START, p.dir.getAbsolutePath());
                        startActivity(i);
                        break;
                    case 2:
                        enterSelectionMode(p);
                        p.adapter.selectAll(true);
                        updateSelDone();
                        break;
                    case 3: askSortMode(); break;
                    case 10:                       // v1.80.0 网格/列表切换
                        viewMode = viewMode == VIEW_GRID ? VIEW_LIST : VIEW_GRID;
                        saveViewMode(viewMode);
                        applyViewMode();
                        updateHeader();
                        // 从列表切到网格时当前滚动位置不再对应，回到顶部更符合预期
                        if (viewMode == VIEW_GRID && p.grid != null) p.grid.setSelection(0);
                        break;
                    case 4:
                        // v1.78.0：两栏共用这套显示设置，改完存盘并同步刷新
                        boolean nh = !p.adapter.showHidden;
                        saveShowHidden(nh);
                        for (Panel q : new Panel[]{left, right}) q.adapter.showHidden = nh;
                        refreshAll();
                        break;
                    case 5: showBookmarks(); break;
                    case 6:
                        startActivity(new Intent(MainActivity.this, TrashActivity.class));
                        break;
                    case 7: swapPanes(); break;
                    case 9: pasteClipboard(); break;   // v1.78.0
                    case 8: showRecent(); break;
                    case 12:
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        break;
                    case 13:
                        startActivity(new Intent(MainActivity.this, SyncActivity.class));
                        break;
                }
                return true;
            }
        });
        popup.show();
    }

    /** 排序方式：名称 / 大小 / 修改时间（两栏共用，会记住选择） */
    private void askSortMode() {
        final Panel p = active();
        new HfDialogBuilder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(
                        new String[]{"按名称", "按大小", "按修改时间"}, p.adapter.sortMode,
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                saveSortMode(which);
                                for (Panel q : new Panel[]{left, right}) q.adapter.sortMode = which;
                                refreshAll();
                                d.dismiss();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** ＋ 新建：文件夹 / 文本文件 */
    private void askCreateNew() {
        new HfDialogBuilder(this)
                .setTitle("新建")
                .setItems(new String[]{"文件夹", "文本文件"},
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which == 0) askNewFolder();
                                else askNewFile();
                            }
                        })
                .show();
    }

    private void askNewFile() {
        final Panel p = active();
        if (p.archiveFile != null) { toast("压缩包浏览中不可新建，请先退出压缩包"); return; }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(this)
                .setTitle("新建文本文件 @ " + p.dir.getName())
                .setView(et)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        File nf = new File(p.dir, name);
                        boolean ok = false;
                        try { ok = nf.createNewFile(); } catch (Exception e) { ok = false; }
                        // v1.44.0：root 区回退（su 写空文件）
                        if (!ok && !nf.exists()) ok = RootIO.writeBytes(nf, new byte[0]);
                        if (ok) {
                            toast("已创建: " + name);
                            loadDir(p, p.dir);
                        } else {
                            toast("创建失败（已存在或无权限）");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** ‹ › 历史导航：沿活动栏自己的历史栈前进/后退；压缩包浏览中先逐级退回 */
    private void jumpHistory(int delta) {
        Panel p = active();
        if (p.archiveFile != null) {          // 压缩包浏览中 → 先退压缩包（根则退出）
            archGoUp(p);
            return;
        }
        int ni = p.hIndex + delta;
        if (ni < 0 || ni >= p.history.size()) {
            toast(delta < 0 ? "没有更早的历史" : "没有更新的历史");
            return;
        }
        p.hIndex = ni;
        File dir = new File(p.history.get(ni));
        if (dir.isDirectory()) {
            loadDir(p, dir, false);
        } else {
            toast("目录已不存在");
            p.history.remove(ni);
            p.hIndex = Math.min(p.hIndex, p.history.size() - 1);
        }
    }

    private Panel active() { return left.active ? left : right; }
    private Panel other() { return left.active ? right : left; }

    /**
     * ⇄ 交换窗口：完整交换两侧状态（目录 / 压缩包浏览 / 历史栈 / 适配器）。
     * v1.15 起不再强制关闭压缩包——交换后压缩包仍在原屏幕侧浏览，活动栏跟随屏幕侧不变。
     */
    private void swapPanes() {
        if (left.adapter.selectionMode) exitSelection(left);
        if (right.adapter.selectionMode) exitSelection(right);
        Panel t = left;
        left = right;
        right = t;
        // Panel 自带 list/adapter 配对，交换引用后两侧各自仍然成套；
        // active 标记随 Panel 移动 = 屏幕侧焦点不变
        for (Panel p : new Panel[]{left, right}) {
            if (p.archiveFile != null) showArchiveDir(p);
            else if (p.dir != null) loadDir(p, p.dir, false);
        }
        updateHeader();
    }

    private void setupPanel(final Panel p) {
        p.adapter = new FileAdapter(this);
        p.adapter.rootDir = Environment.getExternalStorageDirectory();
        // v1.78.0：排序方式与「显示 . 文件」原来是内存态，重启就回到默认值。
        // 现在存 SharedPreferences；两栏共用一套设置，切换目录也不会被重置。
        android.content.SharedPreferences sp =
                getSharedPreferences(PREF_UI, MODE_PRIVATE);
        p.adapter.sortMode = sp.getInt(KEY_SORT, FileAdapter.SORT_NAME);
        p.adapter.showHidden = sp.getBoolean(KEY_HIDDEN, false);
        p.list.setAdapter(p.adapter);

        // v1.79.0：下拉刷新。adb push / 其他应用写入文件后，目录列表不会收到任何通知，
        // 以前只能退出再进一次目录；现在下拉一下就重读（两栏各管自己）。
        if (p.pull != null) {
            p.pull.setOnRefreshListener(new PullRefreshLayout.OnRefreshListener() {
                public void onRefresh() {
                    refreshPanel(p);
                }
            });
        }

        // 手势（旁听模式，返回 false，事件照常给 ListView，滚动/点击零干扰）：
        //  - 左滑（快速甩动 或 慢速拖动）→ 选中手指起点处的条目并进入多选
        //  - 右滑 → 取消该条目选中；非多选时 = 返回上一级
        //  - 多选中垂直拖动 → 滑到哪里选到哪里（连续刷选）
        // handled 保证"选中/取消/返回"每个触摸手势只触发一次
        final boolean[] handled = new boolean[1];
        final GestureDetector g = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    public boolean onDown(MotionEvent e) {
                        handled[0] = false;
                        // 点列表空白处 = 切换活动窗格（不触发任何条目动作，v1.15）
                        if (p.list.pointToPosition((int) e.getX(), (int) e.getY())
                                == AdapterView.INVALID_POSITION) {
                            setActive(p);
                        }
                        return true;
                    }

                    public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                        if (e1 == null || handled[0]) return false;
                        float totalDx = e2.getX() - e1.getX();
                        float totalDy = e2.getY() - e1.getY();
                        // 横向慢拖：灵敏度兜底（不触发 fling 的慢滑也能选中/取消/返回）
                        if (Math.abs(totalDx) > Math.abs(totalDy) && Math.abs(totalDx) > 80f) {
                            handled[0] = true;
                            int pos = p.list.pointToPosition(
                                    (int) e1.getX(), (int) e1.getY());
                            onHorizontalSwipe(p, totalDx > 0, pos);
                            return false;
                        }
                        // 纵向拖动：多选中连续刷选
                        if (p.adapter.selectionMode
                                && Math.abs(totalDy) > Math.abs(totalDx) && Math.abs(totalDy) > 30f) {
                            int pos = p.list.pointToPosition(
                                    (int) e2.getX(), (int) e2.getY());
                            if (pos != AdapterView.INVALID_POSITION) {
                                FileItem it = p.adapter.get(pos);
                                if (!it.upEntry && !it.selected) {
                                    it.selected = true;
                                    p.adapter.notifyDataSetChanged();
                                    updateSelDone();
                                }
                            }
                        }
                        return false;
                    }

                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (e1 == null || e2 == null || handled[0]) return false;
                        if (Math.abs(vx) > Math.abs(vy) * 1.2f && Math.abs(vx) > 350f
                                && Math.abs(e2.getX() - e1.getX()) > 80f) {
                            handled[0] = true;
                            int pos = p.list.pointToPosition(
                                    (int) e1.getX(), (int) e1.getY());
                            onHorizontalSwipe(p, vx > 0, pos);
                            return true;
                        }
                        return false;
                    }
                });
        p.list.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                g.onTouchEvent(event);
                return false;
            }
        });

        p.list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                handleItemClick(p, i);
            }
        });
        p.list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> av, View v, int i, long id) {
                return handleItemHold(p, i);
            }
        });

        // v1.80.0：网格视图与列表共用同一份数据、同一套点击处理（下标含义相同）
        p.gridAdapter = new FileGridAdapter(this, p.adapter);
        p.adapter.setMirror(p.gridAdapter);
        if (p.grid != null) {
            p.grid.setAdapter(p.gridAdapter);
            p.grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                    handleItemClick(p, i);
                }
            });
            p.grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                public boolean onItemLongClick(AdapterView<?> av, View v, int i, long id) {
                    return handleItemHold(p, i);
                }
            });
        }
    }

    /** 点击条目：列表与网格共用（两侧下标都指向同一个 FileAdapter） */
    private void handleItemClick(Panel p, int i) {
        setActive(p);
        FileItem it = p.adapter.get(i);
        // v1.40.0：列表顶部 .. 返回项（多选中点击 = 退出多选并返回，loadDir 内处理）
        if (it.upEntry) {
            File up = p.dir.getParentFile();
            if (up != null && (up.canRead() || RootUtil.isRootAvailable()))
                loadDir(p, up);
            return;
        }
        if (p.adapter.selectionMode) {
            p.adapter.toggle(i);
            updateSelDone();
            return;
        }
        if (it.isArchUp()) { archGoUp(p); return; }
        if (it.isArchEntry()) { onArchItemClick(p, it); return; }
        if (it.isDir()) {
            loadDir(p, it.file);
        } else {
            onFileClick(it.file);
        }
    }

    /** 长按条目：列表与网格共用 */
    private boolean handleItemHold(Panel p, int i) {
        setActive(p);
        FileItem it = p.adapter.get(i);
        if (it.upEntry) return true;    // .. 返回项无操作菜单
        if (p.adapter.selectionMode) {
            p.adapter.toggle(i);
            updateSelDone();
            return true;
        }
        if (it.isArchEntry()) {
            if (!it.isArchUp()) showArchOpsMenu(p, it);
            return true;
        }
        showOpsMenu(p.adapter.get(i).file);
        return true;
    }

    private void setActive(Panel p) {
        left.active = (p == left);
        right.active = (p == right);
        applyViewMode();
        updateHeader();
    }

    /**
     * v1.80.0：列表 / 网格视图切换。
     *
     * 网格模式下把非活动窗格整块收起来，让活动栏占满宽度 —— 双栏各 180dp 时，
     * 一行只放得下 1 个 96dp 的格子，大图浏览就没意义了；列表模式下恢复双栏。
     * 交换窗格（⇄）仍然可用：Panel 自带视图引用，交换后活动栏照样占满。
     */
    private void applyViewMode() {
        boolean grid = viewMode == VIEW_GRID;
        Panel a = active();
        for (Panel p : new Panel[]{left, right}) {
            if (p.list != null) p.list.setVisibility(grid ? View.GONE : View.VISIBLE);
            if (p.grid != null) p.grid.setVisibility(grid ? View.VISIBLE : View.GONE);
            if (p.pull != null) p.pull.setVisibility(grid && p != a ? View.GONE : View.VISIBLE);
        }
        View div = findViewById(R.id.pane_divider);
        if (div != null) div.setVisibility(grid ? View.GONE : View.VISIBLE);
    }

    private void loadDir(Panel p, File dir) {
        loadDir(p, dir, true);
    }

    /** push=false 用于历史导航（不写入历史栈） */
    private void loadDir(Panel p, File dir, boolean push) {
        // v1.42.0：root 设备上 stat 可能被拒（ls 才需要 su），放行交给 setDir 的 root 回退
        if (dir == null || (!dir.isDirectory() && !RootUtil.isRootAvailable())) {
            Toast.makeText(this, "无法访问: " + dir, Toast.LENGTH_SHORT).show();
            return;
        }
        if (p.adapter != null && p.adapter.selectionMode) exitSelection(p);
        closeArchiveQuiet(p);
        p.dir = dir;
        if (push) {
            while (p.history.size() > p.hIndex + 1) p.history.remove(p.history.size() - 1);
            p.history.add(dir.getPath());
            p.hIndex = p.history.size() - 1;
        }
        p.adapter.setDir(dir);
        // 换目录后网格要回到顶部：数据换了一批，保留旧滚动位置会落在中间
        if (p.grid != null) p.grid.setSelection(0);
        updateHeader();
    }

    /** 顶部深色栏：显示活动栏路径 + 统计（文件夹/文件/储存），对标 MT */
    private void updateHeader() {
        if (headerPath == null) return;
        Panel p = active();
        // 压缩包浏览模式：路径 = 压缩包路径 / 条目前缀
        if (p.archiveFile != null) {
            headerPath.setText(p.archiveFile.getPath()
                    + (p.archPrefix.isEmpty() ? "" : "/" + p.archPrefix));
            int dirs = 0, files = 0;
            for (int i = 0; i < p.adapter.getCount(); i++) {
                FileItem it = p.adapter.get(i);
                if (it.isArchUp() || it.upEntry) continue;
                if (it.isDir()) dirs++; else files++;
            }
            headerStats.setText("文件夹: " + dirs + "  文件: " + files
                    + "  [" + (p.archive != null ? p.archive.format() : "ZIP") + "]");
            return;
        }
        if (p.dir == null) return;
        headerPath.setText(p.dir.getPath());
        // v1.42.0：从 adapter 统计（root 列取的条目也在其中；listFiles 在受保护目录会拿不到）
        int dirs = 0, files = 0;
        for (int i = 0; i < p.adapter.getCount(); i++) {
            FileItem it = p.adapter.get(i);
            if (it.upEntry) continue;
            if (it.isDir()) dirs++; else files++;
        }
        String store = "";
        // v1.72.0：根目录/系统分区统一按 /data 统计，避免顶层显示「987M/987M」
        long[] u = usageOf(p.dir.getAbsolutePath());
        if (u != null) {
            long used = u[0] > u[1] ? u[0] - u[1] : 0;
            store = "  储存: " + FileOps.formatSize(used) + "/" + FileOps.formatSize(u[0]);
        }
        headerStats.setText("文件夹: " + dirs + "  文件: " + files + store);
    }

    private void onFileClick(File f) {
        new RecentStore(this).record(f.getAbsolutePath());
        String lower = f.getName().toLowerCase();
        if (isArchiveExt(lower) || lower.endsWith(".apk")) {
            // 压缩包/APK 均在当前窗格内嵌浏览（v1.14，不再全屏）
            if (lower.endsWith(".apk")) showApkInfo(f);
            else openArchiveInPane(active(), f);
            return;
        } else if (lower.endsWith(".dex")) {
            askDexAction(f);
        } else if (isImageExt(lower)) {
            openViewer(f);
        } else if (lower.endsWith(".pdf")) {
            startActivity(new Intent(this, PdfPreviewActivity.class)
                    .putExtra(PdfPreviewActivity.EXTRA_FILE, f.getAbsolutePath()));
        } else if (lower.endsWith(".ttf") || lower.endsWith(".otf")) {
            startActivity(new Intent(this, FontPreviewActivity.class)
                    .putExtra(FontPreviewActivity.EXTRA_FILE, f.getAbsolutePath()));
        } else if (isKeystoreFile(f, lower)) {
            showKeystoreAction(f);          // v1.68.0：签名文件 → 导入 / 查看 / HEX
        } else if (isTextFile(f, lower)) {
            openEditor(f);
        } else if (!OpenWith.open(this, f)) {
            // v1.77.0：以前所有不认识的文件一律弹属性，视频/Office 在文件管理器里等于打不开。
            // 先交给系统找应用；真没应用能处理时才退回属性页，并说清原因。
            toast("没有可打开该类型的应用");
            showProps(f);
        }
    }

    /** 常见图片扩展名 → 点击进图片查看器 */
    public static boolean isImageExt(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    // ============================================================ 签名文件（v1.68.0）

    /** keystore 类文件（扩展名或魔数）——点击时给「导入签名管理 / 查看 / HEX」 */
    public static boolean isKeystoreFile(File f, String lower) {
        // v1.69.0：判定口径与文件列表 🔑 图标统一（FileItem.isKeystoreName，含 .ks）
        if (FileItem.isKeystoreName(lower)) return true;
        if (f == null || !f.isFile() || f.length() < 12) return false;
        byte[] head = FileInspect.readHead(f, 16);
        String m = FileInspect.magicName(head);
        return "JKS".equals(m) || "BKS".equals(m);
    }

    /** 点击签名文件：导入签名管理 / 查看签名信息 / HEX 查看 / 属性 */
    private void showKeystoreAction(final File f) {
        final String[] ops = {"导入到签名管理", "查看签名信息", "HEX 查看", "属性"};
        new HfDialogBuilder(this)
                .setTitle(f.getName())
                .setItems(ops, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) importKeystore(f);
                        else if (which == 1) showKeystoreInfo(f);
                        else if (which == 2) openHex(f);
                        else showProps(f);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 读取文件字节并弹出「导入签名」对话框（复用签名管理里的同一套流程） */
    private void importKeystore(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("读取签名文件…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                byte[] bytes = null;
                String err = null;
                try { bytes = readFileBytes(f); }
                catch (Throwable t) { err = t.getMessage(); }
                final byte[] fb = bytes;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (fb == null) { toast("读取失败: " + fe); return; }
                        SignKeyManagerActivity.promptImport(MainActivity.this, fb, f.getName(),
                                new Runnable() {
                                    public void run() {
                                        toast("已导入签名管理（抽屉 → 签名管理 可查看）");
                                    }
                                });
                    }
                });
            }
        }, "ks-import").start();
    }

    /** 查看签名文件信息（keystore 一定需要密码，先问再解析） */
    private void showKeystoreInfo(final File f) {
        final EditText et = new EditText(this);
        et.setHint("keystore 密码");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new HfDialogBuilder(this)
                .setTitle("查看签名信息")
                .setMessage(f.getName() + "\n\n请输入 keystore 密码（私钥密码不同时留空即可）")
                .setView(et)
                .setPositiveButton("解析", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        parseKeystore(f, et.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void parseKeystore(final File f, final String pass) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("解析 keystore…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final StringBuilder sb = new StringBuilder();
                try {
                    byte[] bytes = readFileBytes(f);
                    sb.append("文件: ").append(f.getName()).append("（")
                            .append(FileOps.formatSize(f.length())).append("）\n");
                    sb.append("格式: ").append(FileInspect.typeLabel(
                            FileInspect.readHead(f, 16), f.getName())).append('\n');
                    sb.append("文件头: ").append(FileInspect.hexHead(
                            FileInspect.readHead(f, 16), 8)).append("\n\n");
                    List<KeyStoreUtil.AliasInfo> list =
                            KeyStoreUtil.inspect(bytes, pass.toCharArray(), null);
                    if (list.isEmpty()) sb.append("（没有任何条目）");
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append(list.get(i).detail());
                    }
                } catch (Throwable t) {
                    sb.setLength(0);
                    sb.append("解析失败: ").append(SignKeyManagerActivity.friendlyKsError(t));
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
                        TextView tv = new TextView(MainActivity.this);
                        int pad = (int) (20 * getResources().getDisplayMetrics().density);
                        sv.setPadding(pad, pad / 2, pad, 0);
                        tv.setText(sb.toString());
                        tv.setTextIsSelectable(true);
                        tv.setTextSize(13);
                        tv.setTextColor(ThemeManager.text(MainActivity.this));
                        sv.addView(tv);
                        new HfDialogBuilder(MainActivity.this)
                                .setTitle("签名信息")
                                .setView(sv)
                                .setPositiveButton("导入到签名管理",
                                        new android.content.DialogInterface.OnClickListener() {
                                            public void onClick(android.content.DialogInterface d, int w) {
                                                importKeystore(f);
                                            }
                                        })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }, "ks-parse").start();
    }

    /** 读整个文件（root 区回退 su cat） */
    private byte[] readFileBytes(File f) throws Exception {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                try { in.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            byte[] rb = RootIO.readBytes(f);
            if (rb != null) return rb;
            throw e;
        }
    }

    private void openViewer(File f) {
        Intent i = new Intent(MainActivity.this, ImageViewerActivity.class);
        i.putExtra(ImageViewerActivity.EXTRA_FILE, f.getAbsolutePath());
        startActivity(i);
    }

    /** 是否压缩包类扩展名（ZIP/7z/TAR/RAR/Zstd 等，交给内嵌多格式浏览器） */
    private static boolean isArchiveExt(String lower) {
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")
                || lower.endsWith(".tar") || lower.endsWith(".tgz") || lower.endsWith(".tar.gz")
                || lower.endsWith(".tar.zst") || lower.endsWith(".tzst") || lower.endsWith(".zst");
    }

    /** 点击 .dex → 操作菜单（Dex 编辑器++ / 反编译 / HEX / 属性） */
    private void askDexAction(final File f) {
        String[] ops = {"Dex 编辑器++", "反编译为 smali", "HEX 查看", "属性"};
        new HfDialogBuilder(this)
                .setTitle(f.getName())
                .setItems(ops, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            Intent i = new Intent(MainActivity.this, DexEditorActivity.class);
                            i.putExtra(DexEditorActivity.EXTRA_FILE, f.getAbsolutePath());
                            startActivity(i);
                        }
                        else if (which == 1) decompileDexFile(f);
                        else if (which == 2) openHex(f);
                        else showProps(f);
                    }
                })
                .show();
    }

    /** baksmali 后台反编译独立 dex 文件 → /sdcard/HexFile/Smali/<dex名>/ 并跳转浏览 */
    private void decompileDexFile(final File dexFile) {
        String name = dexFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        final File outDir = new File(WorkDir.base(), "Smali/" + name);
        toast("反编译中…（大型 dex 可能需要较长时间）");
        new Thread(new Runnable() {
            public void run() {
                final String err = SmaliTool.decompile(dexFile, outDir, 4);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err == null) {
                            toast("已生成 smali →\n" + outDir.getPath());
                            Intent i = new Intent(MainActivity.this, MainActivity.class);
                            i.putExtra("dir", outDir.getAbsolutePath());
                            startActivity(i);   // singleTop：onNewIntent 跳转目录
                        } else {
                            toast("反编译失败: " + err);
                        }
                    }
                });
            }
        }, "baksmali").start();
    }

    /** 常见文本扩展名 → 点击直接进编辑器 */
    private static boolean isTextFile(File f, String lower) {
        return isTextName(lower, f.length());
    }

    /** 按扩展名+大小判断是否按文本处理（压缩包条目用，无 File 可查） */
    public static boolean isTextName(String lower, long size) {
        String[] exts = {".txt", ".xml", ".json", ".java", ".kt", ".kts", ".gradle",
                ".properties", ".ini", ".conf", ".cfg", ".log", ".md", ".csv",
                ".html", ".htm", ".css", ".js", ".ts", ".py", ".c", ".cpp", ".h",
                ".hpp", ".sh", ".bat", ".smali", ".yml", ".yaml", ".toml", ".sql"};
        for (String e : exts) if (lower.endsWith(e)) return true;
        // 无扩展名的小文件也按文本处理（如 README、Makefile）
        return lower.indexOf('.') < 0 && size > 0 && size < 1024 * 1024;
    }

    // ================================================================ 窗格内嵌压缩包浏览（v1.14）

    /** 该归档是否支持写回（ZIP/APK/JAR/TAR/TGZ/7Z；RAR 专有格式无写入器） */
    private static boolean writableArchive(File f) {
        return ArchiveWrite.canWrite(f);
    }

    /** 写归档前校验：RAR 弹「转存为 ZIP」，其余只读格式给说明；返回 false 中止操作 */
    private boolean ensureArchiveWritable(File f) {
        if (f == null) { toast("对面窗格未打开压缩包"); return false; }
        if (ArchiveWrite.canWrite(f)) return true;
        if (f.getName().toLowerCase().endsWith(".rar")) { askRarToZip(f); return false; }
        toast("该格式暂不支持写入；可先「提取到对面目录」再操作");
        return false;
    }

    /**
     * RAR 无法直接写入：压缩算法专有，全球没有任何免费/付费通用库支持
     * （包括 7-Zip native 引擎，MT 管理器同样只读）。提供「转存为同名 ZIP」绕行。
     */
    private void askRarToZip(final File rar) {
        new HfDialogBuilder(this)
                .setTitle("RAR 为只读格式")
                .setMessage("RAR 是专有压缩格式，任何通用库都无法写入（MT 亦只读）。\n\n"
                        + "可立即转存为同名 ZIP（内容不变），之后即可像文件夹一样增删改。\n"
                        + "转存文件将生成在: " + rar.getParent())
                .setPositiveButton("转存为 ZIP", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        convertRarToZip(rar);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** RAR → 同名 ZIP：解压到缓存再打包（带密码的 RAR 用会话密码解密） */
    private void convertRarToZip(final File rar) {
        toast("转存中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = new File(rar.getParentFile(),
                        stripExt(rar.getName()) + ".zip");
                File tmp = new File(getCacheDir(), "rar2zip_" + System.currentTimeMillis());
                try {
                    tmp.mkdirs();
                    ArchiveUtil.Archive a = ArchiveUtil.open(rar, ArchivePasswords.get(rar));
                    try { a.extractAll(tmp); } finally { a.close(); }
                    File[] kids = tmp.listFiles();
                    if (kids == null || kids.length == 0) throw new RuntimeException("RAR 内容为空");
                    if (!out.createNewFile() && !out.isFile()) throw new RuntimeException("无法创建 " + out.getName());
                    if (!ZipUtil.createZip(out, java.util.Arrays.asList(kids))) {
                        throw new RuntimeException("打包 ZIP 失败");
                    }
                } catch (Throwable t) {
                    if (t instanceof ArchiveUtil.NeedsPasswordException
                            || ArchiveUtil.cryptoError(t)) {
                        // 密码问题：提示输密码（仅记住），用户重试转存即可
                        runOnUiThread(new Runnable() {
                            public void run() { promptArchivePasswordSave(rar); }
                        });
                    }
                    out.delete();
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                FileOps.deleteRecursive(tmp);
                final String ferr = err;
                final File fout = out.isFile() ? out : null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("转存失败: " + ferr); return; }
                        toast("已转存 → " + fout.getName() + "（RAR 原件保留）");
                        refreshAll();
                    }
                });
            }
        }, "rar2zip").start();
    }

    private void closeArchiveQuiet(Panel p) {
        // v1.66.0：退出 APK 浏览时，有未签名修改版 → 统一弹窗（不签名/内置签名文件/自有签名文件）
        if (p.archiveFile != null && ApkEditSession.isApk(p.archiveFile)
                && ApkEditSession.needsSignPrompt(p.archiveFile)) {
            ApkEditSession.launchSignPrompt(this, p.archiveFile);
        }
        if (p.archive != null) {
            try { p.archive.close(); } catch (Throwable ignored) {}
        }
        p.archive = null;
        p.archiveFile = null;
        p.archPrefix = "";
    }

    /** 在指定窗格内打开压缩包（替代全屏 ZipBrowserActivity）；加密时弹密码框重试 */
    private void openArchiveInPane(final Panel p, final File f) {
        toast("正在打开压缩包…");
        new Thread(new Runnable() {
            public void run() {
                ArchiveUtil.Archive a = null;
                Throwable err = null;
                boolean needPw = false;
                try {
                    a = ArchiveUtil.open(f, ArchivePasswords.get(f));
                } catch (ArchiveUtil.NeedsPasswordException np) {
                    needPw = true;
                    android.util.Log.w("HexFile", "arch-open NEED-PW " + f.getName() + " pw="
                            + (ArchivePasswords.get(f) != null) + " : " + np.getMessage());
                } catch (Throwable t) {
                    err = t;
                    android.util.Log.w("HexFile", "arch-open ERR " + f.getName() + " : " + t);
                }
                if (a != null) {
                    android.util.Log.w("HexFile", "arch-open OK " + f.getName()
                            + " fmt=" + a.format() + " pw=" + (ArchivePasswords.get(f) != null));
                }
                final ArchiveUtil.Archive fa = a;
                final boolean fNeedPw = needPw;
                final String ferr = err == null ? null
                        : err.getClass().getSimpleName() + ": " + err.getMessage();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fNeedPw) {
                            // 之前已存过密码还失败 = 密码错误；清掉并提示重输
                            boolean wrong = ArchivePasswords.get(f) != null;
                            ArchivePasswords.clear(f);
                            promptArchivePassword(p, f, wrong);
                            return;
                        }
                        if (fa == null) { toast("打开失败: " + ferr); return; }
                        boolean same = p.archiveFile != null
                                && p.archiveFile.getAbsolutePath().equals(f.getAbsolutePath());
                        if (!same) closeArchiveQuiet(p);
                        else if (p.archive != null && p.archive != fa) {
                            try { p.archive.close(); } catch (Throwable ignored) {}
                        }
                        p.archiveFile = f;
                        p.archive = fa;
                        p.archPrefix = "";
                        if (p.adapter.selectionMode) exitSelection(p);
                        showArchiveDir(p);
                    }
                });
            }
        }, "arch-open").start();
    }

    /** 当前显示中的密码弹窗（防叠加：新弹窗顶掉旧的） */
    private AlertDialog mPwDialog;

    /** 加密归档密码输入框（无后续动作重载） */
    private void promptArchivePassword(final Panel p, final File f, final boolean wrong) {
        promptArchivePassword(p, f, wrong, null);
    }

    /** 加密归档密码输入框：确定后记住密码并重开窗格或执行 afterOk；取消则清除密码。
     *  wrong=true 表示上一轮密码已验证失败，标题与提示变为"密码错误，请重输" */
    private void promptArchivePassword(final Panel p, final File f, final boolean wrong,
            final Runnable afterOk) {
        android.util.Log.w("HexFile", "pw-dialog show wrong=" + wrong + " file=" + f.getName()
                + " pane=" + (p != null));
        if (mPwDialog != null) { try { mPwDialog.dismiss(); } catch (Throwable ignored) {} }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // 右侧眼睛图标：点击切换密码显示/隐藏
        final android.graphics.drawable.Drawable eyeOn =
                getResources().getDrawable(R.drawable.ic_eye);
        final android.graphics.drawable.Drawable eyeOff =
                getResources().getDrawable(R.drawable.ic_eye_off);
        int iconSize = Math.max(eyeOn.getIntrinsicWidth(), 48);
        eyeOn.setBounds(0, 0, iconSize, iconSize);
        eyeOff.setBounds(0, 0, iconSize, iconSize);
        et.setCompoundDrawables(null, null, eyeOn, null);
        et.setCompoundDrawablePadding(20);
        et.setPadding(et.getPaddingLeft(), et.getPaddingTop(),
                iconSize + 40, et.getPaddingBottom());
        final boolean[] pwVisible = {false};
        et.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP) return false;
                int dw = iconSize + 20;
                if (e.getX() < et.getWidth() - et.getPaddingRight() - dw) return false;
                pwVisible[0] = !pwVisible[0];
                int sel = et.getSelectionEnd();
                if (pwVisible[0]) {
                    et.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    et.setCompoundDrawables(null, null, eyeOff, null);
                } else {
                    et.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    et.setCompoundDrawables(null, null, eyeOn, null);
                }
                et.setSelection(sel < 0 ? et.getText().length() : sel);
                return true;
            }
        });
        AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle(wrong ? "密码错误" : "输入密码")
                .setMessage(wrong
                        ? f.getName() + " 密码不正确，请重新输入"
                        : f.getName() + " 已加密，请输入密码（会话内记住，不落盘）")
                .setView(et)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String pw = et.getText().toString();
                        if (pw.isEmpty()) { toast("密码为空，已取消打开"); ArchivePasswords.clear(f); return; }
                        ArchivePasswords.put(f, pw);
                        if (p != null) openArchiveInPane(p, f);     // 带密码重开；密码错会再次弹框提示
                        else if (afterOk != null) afterOk.run();
                    }
                })
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        ArchivePasswords.clear(f);
                        toast("已取消：加密压缩包需要密码才能打开");
                    }
                })
                .create();
        mPwDialog = dlg;
        dlg.show();
    }

    /** 只记密码并自动重试转存（转存流程用） */
    private void promptArchivePasswordSave(final File f) {
        promptArchivePassword(null, f, ArchivePasswords.get(f) != null, new Runnable() {
            public void run() { convertRarToZip(f); }   // 密码记住后自动重试转存
        });
    }

    /** 提取/编辑等后台操作密码类失败 → 弹密码框重开归档，返回 true 表示已处理 */
    private boolean archPwError(final Panel p, Throwable t) {
        if (t == null) return false;
        if (ArchiveUtil.cryptoError(t)) {
            final boolean wrong = p.archiveFile != null && ArchivePasswords.get(p.archiveFile) != null;
            if (p.archiveFile != null) ArchivePasswords.clear(p.archiveFile);
            runOnUiThread(new Runnable() {
                public void run() {
                    if (p.archiveFile != null) promptArchivePassword(p, p.archiveFile, wrong);
                }
            });
            return true;
        }
        return false;
    }

    /** 刷新窗格当前压缩包子级列表（后台列条目，回主线程渲染） */
    private void showArchiveDir(final Panel p) {
        final ArchiveUtil.Archive a = p.archive;
        final File zf = p.archiveFile;
        final String prefix = p.archPrefix == null ? "" : p.archPrefix;
        if (a == null || zf == null) return;
        new Thread(new Runnable() {
            public void run() {
                List<ArchiveUtil.Entry> entries = null;
                String err = null;
                try { entries = a.listChildren(prefix); }
                catch (Throwable t) { err = t.getMessage(); }
                final List<ArchiveUtil.Entry> fe = entries;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (p.archive != a || !prefix.equals(p.archPrefix)) return; // 已切走
                        if (p.adapter.selectionMode) exitSelection(p);
                        List<FileItem> list = new ArrayList<FileItem>();
                        if (!prefix.isEmpty()) list.add(FileItem.archUp());
                        if (fe != null) {
                            for (ArchiveUtil.Entry e : fe) {
                                if (!p.adapter.showHidden && e.name.startsWith(".")) continue;
                                list.add(new FileItem(e.full, e.name, e.dir, e.size));
                            }
                        }
                        p.adapter.setItems(list);
                        updateHeader();
                        // v1.79.0：下拉刷新走的是这条异步路径，列表真正拿到数据后收起转圈
                        if (p.pull != null && p.pull.isRefreshing()) p.pull.finishRefresh();
                        if (ferr != null && fe == null) toast("读取压缩包失败: " + ferr);
                    }
                });
            }
        }, "arch-list").start();
    }

    /** 压缩包内返回上级；已到根则退回普通目录模式 */
    private void archGoUp(Panel p) {
        String pre = p.archPrefix == null ? "" : p.archPrefix;
        if (pre.isEmpty()) {
            closeArchiveQuiet(p);
            loadDir(p, p.dir);
            return;
        }
        int cut = pre.lastIndexOf('/', pre.length() - 2);
        p.archPrefix = cut < 0 ? "" : pre.substring(0, cut + 1);
        showArchiveDir(p);
    }

    /** 点击压缩包内条目：目录进入 / 文本直接编辑 / 其它弹提取菜单 */
    private void onArchItemClick(final Panel p, final FileItem it) {
        if (it.archDir) {
            p.archPrefix = it.archFull;
            showArchiveDir(p);
            return;
        }
        // 专属路由优先于 isTextName，否则 .xml 会被文本编辑截胡（如 AndroidManifest.xml 进不了编辑菜单）
        String low = it.archShort.toLowerCase();
        if (low.equals("androidmanifest.xml")) { askArchManifestAction(p, it); return; }
        if (low.endsWith(".dex")) { askArchDexAction(p, it); return; }
        if (low.endsWith(".arsc")) { openArchArsc(p, it); return; }
        if (isTextName(low, it.archSize)) {
            editArchEntry(p, it);
            return;
        }
        if (isImageExt(low)) {
            viewArchEntry(p, it);
            return;
        }
        final boolean canToArch = other().archiveFile != null;
        java.util.List<String> ops = new ArrayList<String>();
        ops.add("提取到当前窗格目录");
        ops.add("提取到对面窗格");
        if (canToArch) ops.add("写入对面压缩包");
        new HfDialogBuilder(this)
                .setTitle(it.archShort)
                .setItems(ops.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) extractArchEntry(p, it.archPath(), p);
                                else if (w == 1) extractArchEntry(p, it.archPath(), other());
                                else askAddEntriesToArchive(p,
                                        java.util.Collections.singletonList(it), other(), false);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** APK 内点击 AndroidManifest.xml：改属性走 Manifest 编辑器++，文本编辑走往返编译 */
    private void askArchManifestAction(final Panel p, final FileItem it) {
        new HfDialogBuilder(this)
                .setTitle(it.archShort)
                .setItems(new String[] {
                        "Manifest 编辑器++（可视化改属性）",
                        "XML 编辑（文本方式，保存自动编译回 APK）",
                        "XML 查看（只读预览）",
                        "HEX 查看",
                        "提取到当前窗格目录" },
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    startActivity(new Intent(MainActivity.this,
                                            ManifestEditorActivity.class)
                                            .putExtra(ManifestEditorActivity.EXTRA_APK,
                                                    p.archiveFile.getAbsolutePath()));
                                } else if (w == 1) {
                                    editArchEntryXml(p, it);
                                } else if (w == 2) {
                                    editArchEntry(p, it);
                                } else if (w == 3) {
                                    hexArchEntry(p, it);
                                } else {
                                    extractArchEntry(p, it.archPath(), p);
                                }
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Manifest 文本编辑（往返编译）：提取二进制 AXML → 转 pretty 文本 → 编辑器打开；
     * 保存时 TextEditorActivity 走 EXTRA_RT_* 往返编译写回 APK。
     */
    private void editArchEntryXml(final Panel p, final FileItem it) {
        final File zip = p.archiveFile;
        final File tmpDir = new File(getCacheDir(), "arch_edit_xml");
        toast("正在读取…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = t.getMessage();
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : "提取失败");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("读取失败: " + ferr); return; }
                        if (!isAxml(tmpFile)) {
                            toast("不是二进制 XML，请用普通文本编辑");
                            return;
                        }
                        try {
                            AxmlFile ax = AxmlFile.parse(readAllBytes(tmpFile));
                            File pretty = new File(tmpDir, it.archPath() + ".pretty.xml");
                            java.io.PrintWriter pw = new java.io.PrintWriter(
                                    new java.io.OutputStreamWriter(
                                            new FileOutputStream(pretty), "UTF-8"));
                            pw.print(ax.prettyXml());
                            pw.close();
                            Intent i = new Intent(MainActivity.this, TextEditorActivity.class);
                            i.putExtra(TextEditorActivity.EXTRA_FILE, pretty.getAbsolutePath());
                            i.putExtra(TextEditorActivity.EXTRA_RT_AXML, tmpFile.getAbsolutePath());
                            i.putExtra(TextEditorActivity.EXTRA_RT_APK, zip.getAbsolutePath());
                            startActivity(i);
                        } catch (Throwable t) {
                            toast("解析失败: " + t);
                        }
                    }
                });
            }
        }, "arch-edit-xml").start();
    }

    /** 提取压缩包内条目（含子树）到目标窗格目录（后台） */
    private void extractArchEntry(final Panel p, final String entryName, final Panel dst) {
        toast("提取中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try { p.archive.extractEntry(entryName, dst.dir); }
                catch (Throwable t) {
                    if (archPwError(p, t)) return;      // 密码问题 → 弹框重开，用户重试
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr == null) {
                            toast("已提取 → " + dst.dir.getPath());
                            if (dst.archiveFile == null) loadDir(dst, dst.dir);
                        } else toast("提取失败: " + ferr);
                    }
                });
            }
        }, "arch-extract").start();
    }

    /** 解压整个压缩包到当前窗格目录（压缩包所在文件夹，即"此目录"），一步到位 */
    private void extractArchAll(final Panel p) {
        if (p.archive == null || p.archiveFile == null) return;
        final File outDir = p.dir;
        toast("解压全部中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try { p.archive.extractAll(outDir); }
                catch (Throwable t) {
                    if (archPwError(p, t)) return;   // 密码问题 → 弹框重开，用户重试
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr == null) {
                            toast("已解压全部 →\n" + outDir.getPath());
                        } else {
                            toast("解压失败: " + ferr);
                        }
                    }
                });
            }
        }, "arch-extract-all").start();
    }

    /** 压缩包内文本条目 → 解到缓存后进编辑器（zip/apk 可写回） */
    private void editArchEntry(final Panel p, final FileItem it) {
        final File zip = p.archiveFile;
        final File tmpDir = new File(getCacheDir(), "arch_edit");
        toast("正在读取…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = t.getMessage();
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : "提取失败");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("读取失败: " + ferr); return; }
                        File openFile = tmpFile;
                        boolean converted = false;
                        // AXML 二进制（APK 内 AndroidManifest / res 下编译过的 xml）→ 转 pretty 文本查看
                        if (isAxml(tmpFile)) {
                            try {
                                AxmlFile ax = AxmlFile.parse(readAllBytes(tmpFile));
                                File pretty = new File(tmpDir,
                                        it.archPath() + ".pretty.xml");
                                java.io.PrintWriter pw = new java.io.PrintWriter(
                                        new java.io.OutputStreamWriter(
                                                new FileOutputStream(pretty), "UTF-8"));
                                pw.print(ax.prettyXml());
                                pw.close();
                                openFile = pretty;
                                converted = true;
                            } catch (Throwable ignored) { }  // 解析失败按普通文本打开
                        }
                        Intent i = new Intent(MainActivity.this, TextEditorActivity.class);
                        i.putExtra(TextEditorActivity.EXTRA_FILE, openFile.getAbsolutePath());
                        if (converted) {
                            // pretty 文本只落缓存、强制只读（文本编辑会破坏二进制 AXML；
                            // 要改 Manifest 请用「Manifest 编辑器++」）
                            i.putExtra(TextEditorActivity.EXTRA_READONLY, true);
                        } else if (writableArchive(zip)) {
                            i.putExtra(TextEditorActivity.EXTRA_ARCHIVE, zip.getAbsolutePath());
                            i.putExtra(TextEditorActivity.EXTRA_ENTRY, it.archPath());
                        } else {
                            i.putExtra(TextEditorActivity.EXTRA_EXPORT, p.dir.getAbsolutePath());
                        }
                        startActivity(i);
                    }
                });
            }
        }, "arch-edit").start();
    }

    /** AXML 二进制判定：.xml 扩展名 + 文件头类型字 0x0002/0x0003（RES_XML_TYPE，LE） */
    private static boolean isAxml(File f) {
        if (!f.getName().toLowerCase().endsWith(".xml")) return false;
        FileInputStream in = null;
        try {
            byte[] h = new byte[4];
            in = new FileInputStream(f);
            int n = 0;
            while (n < 4) {
                int r = in.read(h, n, 4 - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 4) return false;
            int t = (h[0] & 0xFF) | ((h[1] & 0xFF) << 8);
            return t == 0x0002 || t == 0x0003;
        } catch (Throwable e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readAllBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    /** 压缩包内点击 .dex → 操作菜单（MT 风格） */
    private void askArchDexAction(final Panel p, final FileItem it) {
        // v1.66.5：属性/修复放点击弹窗（对标 NP 打开方式），长按菜单不再重复
        final java.util.List<String> ops = new ArrayList<String>();
        ops.add("Dex 编辑器++（可多选 dex，直接改指令）");
        ops.add("反编译为 smali");
        ops.add("Dex 属性");
        final boolean isApk = ApkEditSession.isApk(p.archiveFile);
        if (isApk) ops.add("Dex 修复");
        ops.add("HEX 查看");
        ops.add("提取到当前窗格");
        new HfDialogBuilder(this)
                .setTitle(it.archShort)
                .setItems(ops.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                // 用文案分派（Dex 修复仅 APK 归档出现，索引会移位）
                                String op = ops.get(w);
                                if (op.startsWith("Dex 编辑器++")) {
                                    Intent i = new Intent(MainActivity.this,
                                            DexEditorActivity.class);
                                    i.putExtra(DexEditorActivity.EXTRA_APK,
                                            p.archiveFile.getAbsolutePath());
                                    i.putExtra(DexEditorActivity.EXTRA_DEXES,
                                            new String[]{it.archPath()});
                                    startActivity(i);
                                } else if (op.equals("反编译为 smali")) {
                                    decompileArchDex(p, it);
                                } else if (op.equals("Dex 属性")) {
                                    showArchDexProps(p, it);
                                } else if (op.equals("Dex 修复")) {
                                    askArchDexFix(p, it);
                                } else if (op.equals("HEX 查看")) {
                                    hexArchEntry(p, it);
                                } else {
                                    extractArchEntry(p, it.archPath(), p);
                                }
                            }
                        })
                .show();
    }

    /** v1.65.0：压缩包内点击 .arsc → 弹选择框（用哪个编辑器打开），不直接进入修改 */
    private void openArchArsc(final Panel p, final FileItem it) {
        new HfDialogBuilder(this)
                .setTitle(it.archShort)
                .setItems(new String[] {
                        "ARSC 编辑（传统资源表编辑器）",
                        "ARSC++ 编辑（树状列表 + XML 源码）" },
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    startActivity(new Intent(MainActivity.this,
                                            ArscEditorActivity.class)
                                            .putExtra(ArscEditorActivity.EXTRA_APK,
                                                    p.archiveFile.getAbsolutePath())
                                            .putExtra(ArscEditorActivity.EXTRA_ENTRY,
                                                    it.archPath()));
                                } else {
                                    openArchArscPlus(p, it);
                                }
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** v1.64.0：压缩包内 .arsc → ARSC++ 编辑（树状列表 + values XML 源码编辑） */
    private void openArchArscPlus(final Panel p, final FileItem it) {
        Intent i = new Intent(MainActivity.this, ArscPlusActivity.class);
        i.putExtra(ArscPlusActivity.EXTRA_APK, p.archiveFile.getAbsolutePath());
        i.putExtra(ArscPlusActivity.EXTRA_ENTRY, it.archPath());
        startActivity(i);
    }

    /** 压缩包内单个 dex → 反编译为 smali（/sdcard/HexFile/Smali/<apk名>/<dex名>/）并跳转 */
    private void decompileArchDex(final Panel p, final FileItem it) {
        final File apk = p.archiveFile;
        final File cacheTmp = new File(getCacheDir(), "arch_dex_tmp");
        final String base = stripExt(new File(it.archPath()).getName());
        final File outRoot = new File(WorkDir.base(),
                "Smali/" + stripExt(apk.getName()) + "/" + base);
        toast("反编译中…（大型 dex 可能需要较长时间）");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(cacheTmp);
                    cacheTmp.mkdirs();
                    if (!ZipUtil.extractEntry(apk, it.archPath(), cacheTmp))
                        throw new IllegalStateException("提取失败: " + it.archPath());
                    File tmpDex = new File(cacheTmp, it.archPath());
                    err = SmaliTool.decompile(tmpDex, outRoot, 4);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                FileOps.deleteRecursive(cacheTmp);
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("反编译失败: " + ferr); return; }
                        toast("已生成 smali →\n" + outRoot.getPath());
                        Intent i = new Intent(MainActivity.this, MainActivity.class);
                        i.putExtra("dir", outRoot.getAbsolutePath());
                        startActivity(i);
                    }
                });
            }
        }, "arch-dex-dec").start();
    }

    /** Dex 属性（v1.66.5）：版本 + String/Type/Proto/Field/Method/Class 数量 + 校验状态 */
    private void showDexProps(final File dex) {
        toast("正在读取…");
        new Thread(new Runnable() {
            public void run() {
                final String info = SmaliTool.dexProps(dex);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (info == null) { toast("不是有效的 DEX 文件"); return; }
                        showTextDialog(dex.getName(), info);
                    }
                });
            }
        }, "dex-props").start();
    }

    /** 等宽只读弹窗（Dex 属性等用） */
    private void showTextDialog(String title, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        float den = getResources().getDisplayMetrics().density;
        tv.setPadding((int) (24 * den), (int) (16 * den), (int) (24 * den), (int) (8 * den));
        new HfDialogBuilder(this)
                .setTitle(title)
                .setView(tv)
                .setPositiveButton("确定", null)
                .show();
    }

    /** Dex 修复（v1.66.5，独立 dex）：确认 → 备份 → 修头部校验 → dexlib2 验证 */
    private void askDexFix(final File dex) {
        new HfDialogBuilder(this)
                .setTitle("Dex 修复")
                .setMessage("将修正 file_size / header_size / endian_tag，重算 SHA-1 签名与 "
                        + "Adler-32 校验和，并用 dexlib2 实际解析验证。\n"
                        + "首次修改自动备份 <名>_bak.dex。适用于头部被十六进制编辑改坏的 "
                        + "dex；结构级损坏（索引区/数据区错乱）无法恢复。")
                .setPositiveButton("修复", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast("修复中…");
                        new Thread(new Runnable() {
                            public void run() {
                                String err = null;
                                StringBuilder rep = new StringBuilder();
                                try {
                                    ApkRepack.backupOriginal(dex);
                                    err = SmaliTool.repairDex(dex, rep);
                                } catch (Throwable t) {
                                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                                }
                                final String msg = err != null ? "修复失败: " + err
                                        : "修复完成:\n" + rep;
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast(msg);
                                        refreshAll();
                                    }
                                });
                            }
                        }, "dex-fix").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 压缩包内 dex → 提取到缓存后查看属性（v1.66.5） */
    private void showArchDexProps(final Panel p, final FileItem it) {
        final File apk = p.archiveFile;
        final File tmpDir = new File(getCacheDir(), "arch_dexprops");
        toast("正在读取…");
        new Thread(new Runnable() {
            public void run() {
                String info = null, err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    if (!ZipUtil.extractEntry(apk, it.archPath(), tmpDir))
                        throw new IllegalStateException("提取失败: " + it.archPath());
                    info = SmaliTool.dexProps(new File(tmpDir, it.archPath()));
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                FileOps.deleteRecursive(tmpDir);
                final String fInfo = info, fErr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fErr != null) { toast(fErr); return; }
                        if (fInfo == null) { toast("不是有效的 DEX 文件"); return; }
                        showTextDialog(new File(it.archPath()).getName(), fInfo);
                    }
                });
            }
        }, "arch-dex-props").start();
    }

    /** 压缩包内 dex 修复（v1.66.5，仅 APK 归档）：提取 → 修复 → 会话写回原包 */
    private void askArchDexFix(final Panel p, final FileItem it) {
        final File apk = p.archiveFile;
        new HfDialogBuilder(this)
                .setTitle("Dex 修复")
                .setMessage("对 " + it.archShort + " 修正 file_size / header_size / endian_tag，"
                        + "重算 SHA-1 签名与 Adler-32 校验和，并用 dexlib2 实际解析验证。\n"
                        + "修复直接写回该 APK（首次修改自动备份 <名>_bak.apk）。")
                .setPositiveButton("修复", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast("修复中…");
                        new Thread(new Runnable() {
                            public void run() {
                                final File tmpDir = new File(getCacheDir(), "arch_dexfix");
                                String err = null;
                                StringBuilder rep = new StringBuilder();
                                try {
                                    FileOps.deleteRecursive(tmpDir);
                                    tmpDir.mkdirs();
                                    if (!ZipUtil.extractEntry(apk, it.archPath(), tmpDir))
                                        throw new IllegalStateException("提取失败: " + it.archPath());
                                    File dex = new File(tmpDir, it.archPath());
                                    err = SmaliTool.repairDex(dex, rep);
                                    if (err == null) {
                                        // v1.67.2：登记为「文件型修改」再统一重打包——
                                        // 不再把整个 dex 读成 byte[]（大 dex 的额外一份内存占用）
                                        ApkEditSession.stageAdd(MainActivity.this, apk,
                                                it.archPath(), dex);
                                        ApkEditSession.flushRegen(MainActivity.this, apk);
                                    }
                                } catch (Throwable t) {
                                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                                }
                                FileOps.deleteRecursive(tmpDir);
                                final String msg = err != null ? "修复失败: " + err
                                        : "修复完成，已写回:\n" + rep;
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast(msg);
                                        refreshAll();
                                    }
                                });
                            }
                        }, "arch-dex-fix").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 压缩包内任意条目 → 提取到缓存后 HEX 查看 */
    private void hexArchEntry(final Panel p, final FileItem it) {
        final File tmpDir = new File(getCacheDir(), "arch_hex");
        toast("正在读取…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = t.getMessage();
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : "提取失败");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("读取失败: " + ferr); return; }
                        Intent i = new Intent(MainActivity.this, HexViewerActivity.class);
                        i.putExtra(HexViewerActivity.EXTRA_FILE, tmpFile.getAbsolutePath());
                        startActivity(i);
                    }
                });
            }
        }, "arch-hex").start();
    }

    /** 压缩包内图片条目 → 解到缓存后进查看器（只读展示，不写回） */
    private void viewArchEntry(final Panel p, final FileItem it) {
        final File tmpDir = new File(getCacheDir(), "arch_view");
        toast("正在读取…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = t.getMessage();
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : "提取失败");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("读取失败: " + ferr); return; }
                        Intent i = new Intent(MainActivity.this, ImageViewerActivity.class);
                        i.putExtra(ImageViewerActivity.EXTRA_FILE, tmpFile.getAbsolutePath());
                        startActivity(i);
                    }
                });
            }
        }, "arch-view").start();
    }

    /** 压缩包条目长按菜单（MT 式双列宫格） */
    private void showArchOpsMenu(final Panel p, final FileItem it) {
        final boolean canWrite = writableArchive(p.archiveFile);
        final boolean isText = isTextName(it.archShort.toLowerCase(), it.archSize);
        final boolean canToArch = other().archiveFile != null;
        final int A_EDIT = 0, A_HERE = 1, A_OTHER = 2, A_DEL = 3, A_PROP = 4,
                A_TOARCH_C = 5, A_TOARCH_M = 6, A_DEXPP = 7, A_SMALI = 8, A_HEX = 9, A_ARSCPP = 10, A_MANIFEST = 11,
                A_ARSCPP2 = 12;
        String low = it.archShort.toLowerCase();
        final boolean isDex = low.endsWith(".dex");
        final boolean isArs = low.endsWith(".arsc");
        final boolean isManifest = low.equals("androidmanifest.xml");
        final java.util.List<String> ops = new ArrayList<String>();
        final java.util.List<Integer> acts = new ArrayList<Integer>();
        if (isManifest) { ops.add("Manifest 编辑器++"); acts.add(A_MANIFEST); }
        if (isDex) { ops.add("Dex 编辑器++"); acts.add(A_DEXPP); }
        if (isDex) { ops.add("反编译 smali"); acts.add(A_SMALI); }
        if (isArs) { ops.add("ARSC 编辑"); acts.add(A_ARSCPP); }
        if (isArs) { ops.add("ARSC++ 编辑"); acts.add(A_ARSCPP2); }
        if (isText) { ops.add("文本编辑"); acts.add(A_EDIT); }
        if (isDex || isArs) { ops.add("HEX 查看"); acts.add(A_HEX); }
        ops.add("提取到当前窗格"); acts.add(A_HERE);
        ops.add("提取到对面窗格"); acts.add(A_OTHER);
        if (canToArch) { ops.add("复制到对面压缩包"); acts.add(A_TOARCH_C); }
        // 移动到对面压缩包需要源也可写（写完后要删原条目）
        if (canToArch && canWrite) { ops.add("移动到对面压缩包"); acts.add(A_TOARCH_M); }
        if (canWrite) { ops.add("删除"); acts.add(A_DEL); }
        ops.add("属性"); acts.add(A_PROP);
        showGridMenu(it.archShort, ops, acts, new GridAction() {
            public void run(int act) {
                if (act == A_MANIFEST) {
                    startActivity(new Intent(MainActivity.this, ManifestEditorActivity.class)
                            .putExtra(ManifestEditorActivity.EXTRA_APK,
                                    p.archiveFile.getAbsolutePath()));
                } else if (act == A_DEXPP) {
                    Intent i = new Intent(MainActivity.this, DexEditorActivity.class);
                    i.putExtra(DexEditorActivity.EXTRA_APK, p.archiveFile.getAbsolutePath());
                    i.putExtra(DexEditorActivity.EXTRA_DEXES, new String[]{it.archPath()});
                    startActivity(i);
                } else if (act == A_SMALI) {
                    decompileArchDex(p, it);
                } else if (act == A_ARSCPP) {
                    openArchArsc(p, it);
                } else if (act == A_ARSCPP2) {
                    openArchArscPlus(p, it);
                } else if (act == A_HEX) {
                    hexArchEntry(p, it);
                } else if (act == A_EDIT) editArchEntry(p, it);
                else if (act == A_HERE) extractArchEntry(p, it.archPath(), p);
                else if (act == A_OTHER) extractArchEntry(p, it.archPath(), other());
                else if (act == A_DEL) archDelete(p, java.util.Collections.singletonList(it));
                else if (act == A_PROP) toast("条目: " + it.archFull
                        + "\n大小: " + FileOps.formatSize(it.archSize));
                else if (act == A_TOARCH_C) askAddEntriesToArchive(p,
                        java.util.Collections.singletonList(it), other(), false);
                else if (act == A_TOARCH_M) askAddEntriesToArchive(p,
                        java.util.Collections.singletonList(it), other(), true);
            }
        });
    }

    /**
     * 压缩包条目 → 对面压缩包（v1.15）：先把条目（含子树）提取到缓存，再写入目标归档。
     * nameOverride 非空 = 单条目自定义目标名；move = 写入后从源归档删除（需源可写）。
     */
    private void askAddEntriesToArchive(final Panel src, final List<FileItem> entries,
            final Panel dst, final boolean move) {
        if (!ensureArchiveWritable(dst.archiveFile)) return;
        if (move && !writableArchive(src.archiveFile)) {
            toast("源压缩包为只读格式，无法删除原条目（改为复制）");
            addEntriesToArchive(src, entries, dst, null, false);
            return;
        }
        if (entries.size() == 1) {
            final FileItem it = entries.get(0);
            final EditText et = new EditText(this);
            et.setText(it.archShort);
            et.setInputType(InputType.TYPE_CLASS_TEXT);
            new HfDialogBuilder(this)
                    .setTitle((move ? "移动" : "复制") + "到压缩包: " + dst.archiveFile.getName())
                    .setMessage("目标条目名（含后缀；目录将作为前缀，可写 a/b 加入子目录）")
                    .setView(et)
                    .setPositiveButton(move ? "移动" : "复制",
                            new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int w) {
                                    String target = et.getText().toString().trim()
                                            .replace("\\", "/");
                                    while (target.startsWith("/")) target = target.substring(1);
                                    while (target.endsWith("/")) {
                                        target = target.substring(0, target.length() - 1);
                                    }
                                    if (target.isEmpty()) { toast("条目名不能为空"); return; }
                                    addEntriesToArchive(src, entries, dst, target, move);
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            addEntriesToArchive(src, entries, dst, null, move);
        }
    }

    /** 执行「压缩包条目 → 对面压缩包」：提取到缓存 → 逐文件写入 → 可选删源条目 */
    private void addEntriesToArchive(final Panel src, final List<FileItem> entries,
            final Panel dst, final String nameOverride, final boolean move) {
        toast("写入压缩包…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                final File tmp = new File(getCacheDir(), "arch_xfer");
                try {
                    FileOps.deleteRecursive(tmp);
                    tmp.mkdirs();
                    // 1) 提取全部选中条目到缓存
                    for (FileItem it : entries) {
                        src.archive.extractEntry(it.archPath(), tmp);
                    }
                } catch (Throwable t) {
                    if (archPwError(src, t)) return;
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                if (err == null) {
                    // 2) 逐文件写入目标归档（APK → 会话重打包写回原包；其他压缩包原地写）
                    final boolean dstApk = ApkEditSession.isApk(dst.archiveFile);
                    try {
                        for (FileItem it : entries) {
                            String root = entries.size() == 1 && nameOverride != null
                                    ? nameOverride : it.archShort;
                            File extracted = new File(tmp, it.archPath());
                            if (extracted.isDirectory()) {
                                writeTree(extracted, root, dst.archiveFile, dstApk);
                            } else if (extracted.isFile()) {
                                if (dstApk) {
                                    ApkEditSession.stageAdd(MainActivity.this, dst.archiveFile, root, extracted);
                                } else if (!ArchiveWrite.writeEntry(dst.archiveFile, root, extracted)) {
                                    throw new RuntimeException("写入失败: " + root);
                                }
                            } else {
                                throw new RuntimeException("提取失败: " + it.archPath());
                            }
                        }
                        // 批量登记完成 → 一次性重打包写回
                        if (dstApk && err == null)
                            ApkEditSession.flushRegen(MainActivity.this, dst.archiveFile);
                        // 3) move：从源归档删除原条目
                        if (move) {
                            List<String> names = new ArrayList<String>();
                            for (FileItem it : entries) names.add(it.archPath());
                            if (ApkEditSession.isApk(src.archiveFile)) {
                                ApkEditSession.putRemove(MainActivity.this, src.archiveFile, names);
                            } else if (!ArchiveWrite.removeEntries(src.archiveFile, names)) {
                                throw new RuntimeException("源归档删除失败（内容已复制）");
                            }
                        }
                    } catch (Throwable t) {
                        if (archPwError(dst, t)) return;
                        err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
                FileOps.deleteRecursive(tmp);
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("失败: " + ferr); return; }
                        if (dst.archiveFile != null && ApkEditSession.isApk(dst.archiveFile)
                                && ApkEditSession.hasEdits(dst.archiveFile)) {
                            toast((move ? "已移动进压缩包" : "已复制进压缩包")
                                    + "（已写回原包，首次修改自动备份 _bak.apk）");
                        } else {
                            toast(move ? "已移动进压缩包" : "已复制进压缩包");
                        }
                        showArchiveDir(dst);
                        if (src.archiveFile != null) {
                            showArchiveDir(src);
                        } else if (src.dir != null) {
                            loadDir(src, src.dir, false);
                        }
                    }
                });
            }
        }, "arch-xfer").start();
    }

    /** 从压缩包删除条目（重建 zip；APK 删除后询问自动签名） */
    private void archDelete(final Panel p, final List<FileItem> items) {
        final List<String> names = new ArrayList<String>();
        for (FileItem it : items) names.add(it.archPath());
        new HfDialogBuilder(this)
                .setTitle("删除确认")
                .setMessage("从压缩包永久删除 " + names.size() + " 项（不可恢复）？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast("删除中…");
                final boolean apkMode = ApkEditSession.isApk(p.archiveFile);
                new Thread(new Runnable() {
                    public void run() {
                        String err = null;
                        try {
                            if (apkMode) {
                                // v1.66.8：APK 删除条目 → 会话登记 + 重打包写回原包
                                ApkEditSession.putRemove(MainActivity.this, p.archiveFile, names);
                            } else if (!ArchiveWrite.removeEntries(p.archiveFile, names)) {
                                err = "删除失败";
                            }
                        } catch (Throwable t) {
                            err = t.getMessage();
                        }
                        final String ferr = err;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (ferr != null) { toast(ferr); return; }
                                toast("已删除 " + names.size() + " 项"
                                        + (apkMode ? "（已写回原包，首次修改自动备份 _bak.apk）" : ""));
                                showArchiveDir(p);
                            }
                        });
                    }
                }, "arch-del").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 用内置密钥给 APK 签名：产物 同名_signed.apk（长按菜单「APK 签名」与修改后询问共用） */
    private void signApkBuiltin(final File apk) {
        toast("签名中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = new File(apk.getParentFile(),
                        stripExt(apk.getName()) + "_signed.apk");
                try {
                    // v1.67.3：sign 抛异常时也必须关闭 assets 流（原实现 close 在 try 末尾，异常路径不执行）
                    java.io.InputStream ks = getAssets().open("hexfile.p12");
                    try {
                        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, apk, out, 21);
                    } finally {
                        try { ks.close(); } catch (java.io.IOException ignored) {}
                    }
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                final File fout = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ferr != null ? "签名失败: " + ferr
                                : "已签名 → " + fout.getName());
                        refreshAll();
                    }
                });
            }
        }, "apk-sign").start();
    }

    /**
     * 长按菜单「APK 签名 / 自定义密钥签名」统一入口（v1.66.8 原地模型）：
     *  - APK 有本会话修改 → 原地签名（修改就在这个文件上，签完直接装）；
     *  - 文件名是 _edit.apk（v1.66.7 及更早版本的修改版）→ 原地签名（兼容）；
     *  - 其余（未动过的 APK）维持原行为：生成 <名>_signed.apk / <名>_csigned.apk，
     *    不动原包。
     */
    private void startSign(final File apk, final boolean custom) {
        String n = apk.getName().toLowerCase();
        boolean legacyEdit = n.endsWith("_edit.apk");
        boolean modified = legacyEdit || ApkEditSession.hasEdits(apk);
        if (modified) {
            if (custom) askCustomSign(apk, true);
            else signApkInPlace(apk);
            return;
        }
        if (custom) askCustomSign(apk, false);
        else signApkBuiltin(apk);
    }

    /** 内置密钥原地签名（v1.66.1 引入，v1.66.8 起为修改 APK 的默认签名方式）：临时文件 → 原子覆盖 */
    private void signApkInPlace(final File apk) {
        toast("签名中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    File tmp = new File(apk.getParentFile(), apk.getName() + ".signtmp");
                    if (tmp.exists()) tmp.delete();
                    java.io.InputStream ks = getAssets().open("hexfile.p12");
                    try {
                        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, apk, tmp, 21);
                    } finally {
                        ks.close();
                    }
                    // v1.66.4：safeReplace 先原子 rename（Linux 可替换已存在文件），
                    // 失败才备份+复制——不再「先 delete」，避免写失败丢原包
                    ApkEditSession.safeReplace(tmp, apk);
                    try { ApkEditSession.markSigned(apk); } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ferr != null ? "签名失败: " + ferr
                                : "已签名（原地覆盖）→ " + apk.getName());
                        refreshAll();
                    }
                });
            }
        }, "apk-sign-inplace").start();
    }

    /** 图片批处理参数对话框（v1.58）：缩放/格式/质量/水印 → 后台逐张处理 */
    private void askImageBatch(final java.util.List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p16 = (int) (16 * den);
        box.setPadding(p16, (int) (8 * den), p16, 0);

        TextView l1 = new TextView(this);
        l1.setText("缩放（%）");
        l1.setTextSize(13);
        l1.setTextColor(ThemeManager.textSub(this));
        box.addView(l1);
        final EditText scale = new EditText(this);
        scale.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        scale.setText("100");
        box.addView(scale);

        TextView l2 = new TextView(this);
        l2.setText("输出格式");
        l2.setTextSize(13);
        l2.setTextColor(ThemeManager.textSub(this));
        l2.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(l2);
        final android.widget.Spinner fmt = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, ImageBatchUtil.FORMATS);
        fmt.setAdapter(ad);
        box.addView(fmt);

        TextView l3 = new TextView(this);
        l3.setText("质量（JPEG/WebP 有效，1-100）");
        l3.setTextSize(13);
        l3.setTextColor(ThemeManager.textSub(this));
        l3.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(l3);
        final EditText quality = new EditText(this);
        quality.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        quality.setText("85");
        box.addView(quality);

        TextView l4 = new TextView(this);
        l4.setText("水印文字（留空不加）");
        l4.setTextSize(13);
        l4.setTextColor(ThemeManager.textSub(this));
        l4.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(l4);
        final EditText wm = new EditText(this);
        wm.setHint("如：© HexFile");
        box.addView(wm);

        new HfDialogBuilder(this)
                .setTitle("图片处理（" + paths.size() + " 张）")
                .setView(box)
                .setPositiveButton("开始处理", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        ImageBatchUtil.Options o = new ImageBatchUtil.Options();
                        try {
                            o.scalePercent = Integer.parseInt(scale.getText().toString());
                        } catch (NumberFormatException ignore) {}
                        o.scalePercent = Math.max(10, Math.min(400, o.scalePercent));
                        o.formatIdx = fmt.getSelectedItemPosition();
                        try {
                            o.quality = Integer.parseInt(quality.getText().toString());
                        } catch (NumberFormatException ignore) {}
                        o.quality = Math.max(1, Math.min(100, o.quality));
                        o.watermark = wm.getText().toString().trim();
                        if (o.watermark.isEmpty()) o.watermark = null;
                        doImageBatch(paths, o);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImageBatch(final java.util.List<String> paths, final ImageBatchUtil.Options o) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("图片处理");
        pd.setMax(paths.size());
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, fail = 0;
                String firstErr = null;
                File dir0 = new File(paths.get(0)).getParentFile();
                for (String p : paths) {
                    try {
                        ImageBatchUtil.process(new File(p), dir0, o);
                        ok++;
                    } catch (Throwable t) {
                        fail++;
                        if (firstErr == null) firstErr = t.getMessage();
                    }
                    pd.incrementProgressBy(1);
                }
                final int fok = ok, ffail = fail;
                final String ferr = firstErr;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        String msg = "成功 " + fok + " 张";
                        if (ffail > 0) msg += "，失败 " + ffail + " 张" + (ferr != null ? "（" + ferr + "）" : "");
                        toast(msg + "，输出在同目录（_p 后缀）");
                        refreshAll();
                    }
                });
            }
        }, "img-batch").start();
    }

    /** 哈希校验（v1.59）：MD5/SHA1/SHA256 流式计算 + 期望值比对 + 点击复制 */
    private void doHash(final File f) {
        final ProgressDialog pd = ProgressDialog.show(this, "计算哈希", f.getName(), true, false);
        new Thread(new Runnable() {
            String md5, sha1, sha256, err;
            public void run() {
                try {
                    md5 = DiskScanUtil.hashFull(f, "MD5");
                    sha1 = DiskScanUtil.hashFull(f, "SHA-1");
                    sha256 = DiskScanUtil.hashFull(f, "SHA-256");
                } catch (Throwable t) { err = t.getMessage(); }
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (err != null) { toast("计算失败: " + err); return; }
                        showHashDialog(f, md5, sha1, sha256);
                    }
                });
            }
        }, "hash").start();
    }

    private void showHashDialog(final File f, String md5, String sha1, String sha256) {
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p16 = (int) (16 * den);
        box.setPadding(p16, (int) (8 * den), p16, 0);

        final TextView md5v = hashRow(box, "MD5", md5, den);
        final TextView sha1v = hashRow(box, "SHA-1", sha1, den);
        final TextView sha256v = hashRow(box, "SHA-256", sha256, den);
        hashRowClick(md5v, md5);
        hashRowClick(sha1v, sha1);
        hashRowClick(sha256v, sha256);

        TextView l = new TextView(this);
        l.setText("比对值（可选，粘贴期望的哈希）");
        l.setTextSize(13);
        l.setTextColor(ThemeManager.textSub(this));
        l.setPadding(0, (int) (10 * den), 0, 0);
        box.addView(l);
        final EditText expect = new EditText(this);
        expect.setHint("粘贴 MD5/SHA1/SHA256 任一值");
        expect.setTextSize(13);
        box.addView(expect);
        final TextView result = new TextView(this);
        result.setTextSize(14);
        result.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        result.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(result);
        expect.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                String v = s.toString().trim().toLowerCase();
                if (v.isEmpty()) { result.setText(""); return; }
                boolean m = v.equals(md5) || v.equals(sha1) || v.equals(sha256);
                result.setText(m ? "✅ 校验一致" : "❌ 不匹配");
                result.setTextColor(m ? 0xFF2E7D32 : 0xFFC62828);
            }
        });

        new HfDialogBuilder(this)
                .setTitle("文件校验 · " + f.getName() + "（点击哈希值可复制）")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    private TextView hashRow(LinearLayout box, String label, String value, float den) {
        TextView tv = new TextView(this);
        tv.setText(label + "  " + value);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(ThemeManager.text(this));
        tv.setTextIsSelectable(true);
        tv.setPadding(0, (int) (6 * den), 0, 0);
        box.addView(tv);
        return tv;
    }

    private void hashRowClick(final TextView tv, final String value) {
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("hash", value));
                toast("已复制 " + value);
            }
        });
    }

    /** 多选栏「处理」：筛出选中图片进批处理对话框 */
    private void doBatchImage() {
        if (selPanel == null) return;
        java.util.List<String> imgs = new ArrayList<String>();
        for (FileItem it : selPanel.adapter.getSelectedItems()) {
            if (it.isArchEntry() || it.file == null) continue;
            if (ImageBatchUtil.supported(it.file.getName().toLowerCase()))
                imgs.add(it.file.getAbsolutePath());
        }
        if (imgs.isEmpty()) { toast("选中的项里没有可处理的图片"); return; }
        askImageBatch(imgs);
    }

    /** APK 解包（v1.57）：全部条目提取到 <name>_unpack/，另存可读 Manifest */
    private void doApkUnpack(final File apk) {
        File out = new File(apk.getParentFile(), stripExt(apk.getName()) + "_unpack");
        if (out.exists()) {
            toast("目录已存在: " + out.getName());
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(this, "APK 解包", apk.getName(), true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null, ok = null;
                try {
                    ok = ApkPackager.unpack(apk, out);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err, fok = ok;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) toast("解包失败: " + ferr);
                        else {
                            toast("已解包 → " + out.getName() + "（" + fok + "）");
                            refreshAll();
                        }
                    }
                });
            }
        }, "apk-unpack").start();
    }

    /** 去除签名（v1.57）：重写包并剥离 META-INF 签名文件 → <name>_unsigned.apk */
    private void doRemoveSignature(final File apk) {
        File out = new File(apk.getParentFile(), stripExt(apk.getName()) + "_unsigned.apk");
        final ProgressDialog pd = ProgressDialog.show(this, "去除签名", apk.getName(), true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    ApkRepack.repack(apk,
                            java.util.Collections.<String, byte[]>emptyMap(), out);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                final File fout = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) toast("失败: " + ferr);
                        else {
                            toast("未签名包 → " + fout.getName());
                            refreshAll();
                        }
                    }
                });
            }
        }, "apk-rmsign").start();
    }

    /**
     * 自定义密钥签名（v1.57；v1.67.0 重构）：
     * 已导入签名 → 列表直接选择并签名（密码已在导入时保存，无需再从文件加载/输密码）；
     * 没有导入 → 走旧版「选文件 + 输密码」一次性流程；两个入口都可进「签名管理」。
     */
    private void askCustomSign(final File apk, final boolean inPlace) {
        List<SignKeyManagerActivity.KeyEntry> keys = new ArrayList<SignKeyManagerActivity.KeyEntry>();
        File dir = new File(getFilesDir(), "signkeys");
        File[] fs = dir.listFiles();
        List<String> ids = new ArrayList<String>();
        if (fs != null) for (File f : fs)
            if (f.getName().endsWith(".meta"))
                ids.add(f.getName().substring(0, f.getName().length() - 5));
        java.util.Collections.sort(ids);
        String defId = SignKeyManagerActivity.signPrefs(this).getString("key_id", null);
        if (defId != null && ids.remove(defId)) ids.add(0, defId);
        for (String id : ids) {
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.loadEntry(this, id);
            if (e != null) keys.add(e);
        }

        if (keys.isEmpty()) {
            // 无导入签名 → 旧版一次性流程
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(i, "选择 keystore（.p12/.jks/.keystore/.bks）"),
                        REQ_CUSTOM_KEYSTORE);
            } catch (Throwable t) {
                toast("无法打开文件选择器: " + t.getMessage());
            }
            mCustomSignSrc = apk;
            mCustomSignInPlace = inPlace;
            return;
        }

        List<String> items = new ArrayList<String>();
        for (SignKeyManagerActivity.KeyEntry e : keys)
            items.add((e.id.equals(defId) ? "★ " : "") + e.name
                    + (e.broken() ? "  ⚠ 密码已损坏"
                                  : (e.keyCount > 0 ? "（" + e.keyCount + " 个私钥别名）" : "")));
        items.add("从文件选择（一次性）…");
        items.add("管理签名…（导入 / 查看证书信息 / 删除）");
        new HfDialogBuilder(this)
                .setTitle("自定义密钥签名 — " + apk.getName())
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w < keys.size()) {
                            doCustomSignWithEntry(keys.get(w), apk, inPlace);
                        } else if (w == keys.size()) {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            try {
                                startActivityForResult(Intent.createChooser(i,
                                        "选择 keystore（.p12/.jks/.keystore/.bks）"),
                                        REQ_CUSTOM_KEYSTORE);
                            } catch (Throwable t) {
                                toast("无法打开文件选择器: " + t.getMessage());
                            }
                            mCustomSignSrc = apk;
                            mCustomSignInPlace = inPlace;
                        } else {
                            startActivity(new Intent(MainActivity.this,
                                    SignKeyManagerActivity.class));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 用已导入签名直接签（密码已保存）；inPlace = 原地覆盖，否则 <名>_csigned.apk */
    private void doCustomSignWithEntry(final SignKeyManagerActivity.KeyEntry entry,
            final File apk, final boolean inPlace) {
        if (entry.broken()) {
            toast("该签名的密码已损坏（换机/清除数据导致），请到「签名管理」重新导入");
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(MainActivity.this,
                "自定义签名", entry.name + " → " + apk.getName(), true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = inPlace
                        ? new File(apk.getParentFile(), apk.getName() + ".signtmp")
                        : new File(apk.getParentFile(),
                                stripExt(apk.getName()) + "_csigned.apk");
                try {
                    byte[] ksBytes = SignKeyManagerActivity.loadKeyBytes(MainActivity.this, entry.id);
                    ApkSignerTool.signAutoAlias(ksBytes,
                            entry.storePass.toCharArray(),
                            entry.keyPass != null ? entry.keyPass.toCharArray() : null,
                            entry.alias, apk, out, 21);
                    if (inPlace) {
                        ApkEditSession.safeReplace(out, apk);
                        try { ApkEditSession.markSigned(apk); }
                        catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                }
                final String ferr = err;
                final File fout = apk;
                final boolean finPlace = inPlace;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) toast("签名失败: " + ferr);
                        else {
                            toast("已签名（" + entry.name + "）→ "
                                    + (finPlace ? fout.getName() + "（原地覆盖）" : fout.getName()));
                            refreshAll();
                        }
                    }
                });
            }
        }, "apk-csign").start();
    }

    /** 解包目录 → 打包为 APK（v1.57）：产物未签名，询问是否用内置密钥直接签 */
    private void doApkPack(final File dir) {
        File out = new File(dir.getParentFile(), stripExt(dir.getName()) + "_packed.apk");
        final ProgressDialog pd = ProgressDialog.show(this, "打包 APK", dir.getName(), true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                final File fout = out;
                try {
                    ApkPackager.pack(dir, out);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) { toast("打包失败: " + ferr); return; }
                        refreshAll();
                        new HfDialogBuilder(MainActivity.this)
                                .setTitle("打包完成")
                                .setMessage(fout.getName()
                                        + "（未签名）\n\n选择签名方式：")
                                .setPositiveButton("内置密钥签名", new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        signApkBuiltin(fout);
                                    }
                                })
                                .setNeutralButton("自定义密钥签名", new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        askCustomSign(fout, false);
                                    }
                                })
                                .setNegativeButton("先不签", null)
                                .show();
                    }
                });
            }
        }, "apk-pack").start();
    }

    /** 写文件/目录进压缩包（nameOverride 非空 = 单源自定义条目名/目录前缀；move = 成功后删源） */
    private void addToArchive(final File[] sources, final Panel archPane,
            final String nameOverride, final boolean move) {
        if (!ensureArchiveWritable(archPane.archiveFile)) return;
        toast("写入压缩包…");
        final boolean apkMode = ApkEditSession.isApk(archPane.archiveFile);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                for (File f : sources) {
                    String root = sources.length == 1 && nameOverride != null
                            ? nameOverride : f.getName();
                    try {
                        if (f.isDirectory()) {
                            // 目录整树写入：root 作为压缩包内前缀
                            writeTree(f, root, archPane.archiveFile, apkMode);
                        } else if (apkMode) {
                            // v1.66.8：APK → 会话登记（加 lib/assets 文件等），重打包写回原包
                            ApkEditSession.stageAdd(MainActivity.this,
                                    archPane.archiveFile, root, f);
                        } else {
                            if (!ArchiveWrite.writeEntry(archPane.archiveFile, root, f)) {
                                err = "写入失败: " + root;
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        err = root + ": " + t.getMessage();
                        break;
                    }
                }
                if (apkMode && err == null) {
                    try {
                        ApkEditSession.flushRegen(MainActivity.this, archPane.archiveFile);
                    } catch (Throwable t) {
                        err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("失败: " + ferr); return; }
                        if (apkMode) {
                            toast((move ? "已移动进压缩包" : "已复制进压缩包")
                                    + "（已写回原包，首次修改自动备份 _bak.apk）");
                        } else {
                            toast(move ? "已移动进压缩包" : "已复制进压缩包");
                        }
                        if (move) {
                            for (File f : sources) deleteRecursiveOrTrash(f);
                        }
                        showArchiveDir(archPane);
                        refreshAll();
                    }
                });
            }
        }, "arch-add").start();
    }

    /** 把目录 dir 整树写入归档，条目前缀 = prefix（如 "myfolder" → myfolder/a.txt）；
     *  apkMode = APK 目标：条目登记进 ApkEditSession（调用方随后 flushRegen），否则原地写入 */
    private void writeTree(File dir, String prefix, File archive, boolean apkMode)
            throws java.io.IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String entry = prefix + "/" + k.getName();
            if (k.isDirectory()) {
                writeTree(k, entry, archive, apkMode);
            } else if (apkMode) {
                ApkEditSession.stageAdd(MainActivity.this, archive, entry, k);
            } else {
                if (!ArchiveWrite.writeEntry(archive, entry, k)) {
                    throw new RuntimeException("写入失败: " + entry);
                }
            }
        }
    }

    private void deleteRecursiveOrTrash(File f) {
        if (f.isFile()) {
            if (!f.delete()) Trash.moveToTrash(f);
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursiveOrTrash(k);
        if (!f.delete()) Trash.moveToTrash(f);
    }

    /** 复制/移动文件或目录进压缩包：可自定义目标条目名（含后缀，支持子目录路径） */
    private void askAddToArchive(final File src, final Panel archPane, final boolean move) {
        if (!ensureArchiveWritable(archPane.archiveFile)) return;
        final EditText et = new EditText(this);
        et.setText(src.getName());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(this)
                .setTitle((move ? "移动" : "复制") + "到压缩包: "
                        + archPane.archiveFile.getName())
                .setMessage(src.isDirectory()
                        ? "将整目录写入，目标条目前缀（含后缀；可写 a/b 加入子目录）"
                        : "目标条目名（含后缀；可写 a/b/c.txt 加入子目录）")
                .setView(et)
                .setPositiveButton(move ? "移动" : "复制",
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                String target = et.getText().toString().trim()
                                        .replace("\\", "/");
                                while (target.startsWith("/")) target = target.substring(1);
                                while (target.endsWith("/")) target = target.substring(0, target.length() - 1);
                                if (target.isEmpty()) { toast("条目名不能为空"); return; }
                                addToArchive(new File[]{src}, archPane, target, move);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 双列宫格动作回调（文件动作 / 压缩包条目动作共用同一宫格 UI） */
    private interface GridAction { void run(int act); }

    private void openEditor(File f) {
        Intent i = new Intent(MainActivity.this, TextEditorActivity.class);
        i.putExtra(TextEditorActivity.EXTRA_FILE, f.getAbsolutePath());
        startActivity(i);
    }

    private void openHex(File f) {
        Intent i = new Intent(MainActivity.this, HexViewerActivity.class);
        i.putExtra(HexViewerActivity.EXTRA_FILE, f.getAbsolutePath());
        startActivity(i);
    }

    /** MT 风格 APK 信息弹窗：图标 + 逐行信息；点行复制，点「签名状态」行进签名详情页 */
    private static String apkFileRole(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith("_edit.apk"))
            return "修改版（v1.66.7 及更早版本生成；新版本修改直接写回原包，不再生成 _edit）";
        if (n.endsWith("_bak.apk"))
            return "原包备份（首次修改时自动生成，保持原厂签名，用于还原；勿安装）";
        if (n.endsWith("_signed.apk"))
            return "签名副本（长按「APK 签名」用内置密钥生成的独立文件）";
        if (n.endsWith("_csigned.apk"))
            return "自定义签名副本（用自有 keystore 签名的独立文件）";
        if (n.endsWith("_unsigned.apk"))
            return "去除签名副本（仅剥离签名文件，内容同原包）";
        return null;
    }

    private void showApkInfo(final File f) {
        final PackageManager pm = getPackageManager();
        List<String[]> rows = ApkUtil.rows(f, pm);
        final boolean broken = rows == null;
        if (broken) {
            // 解析失败（如未重签名导致 arsc 校验不过）：降级显示基本信息，仍可查看/功能/安装
            rows = new ArrayList<String[]>();
            rows.add(new String[]{"包名", "(无法解析)"});
            rows.add(new String[]{"安装包大小", FileOps.formatSize(f.length())});
            rows.add(new String[]{"签名状态", "未签名/已失效"});
            rows.add(new String[]{"说明", "内容已修改且未重新签名，系统解析失败；仍可查看、编辑或长按菜单选「APK 签名」"});
            rows.add(new String[]{"APK 路径", f.getAbsolutePath()});
        }
        final List<String[]> rowsF = rows;
        // v1.66.7：备份文件角色说明——_bak 永远保持原厂签名（设计如此，用于还原）；
        // v1.66.8 起修改直接写回原包，签名状态行即可判断当前包是否已重签
        String role = apkFileRole(f);
        if (role != null) rowsF.add(1, new String[]{"文件角色", role});

        View view = getLayoutInflater().inflate(R.layout.dialog_apk_info, null);
        Drawable icon = ApkUtil.icon(f, pm);
        if (icon != null) ((ImageView) view.findViewById(R.id.apk_icon)).setImageDrawable(icon);
        ((TextView) view.findViewById(R.id.apk_title)).setText(ApkUtil.appTitle(f, pm));

        ListView lv = (ListView) view.findViewById(R.id.apk_rows);
        final float density = getResources().getDisplayMetrics().density;
        final BaseAdapter rowsAdapter = rowsAdapter(rows, density);
        lv.setAdapter(rowsAdapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                if ("签名状态".equals(rowsF.get(i)[0])) {
                    showSignDialog(f);          // 进入签名详情页
                } else {
                    copyToClipboard(rowsF.get(i)[0], rowsF.get(i)[1]);
                }
            }
        });

        final AlertDialog dlg = new HfDialogBuilder(this)
                .setView(view)
                .create();
        view.findViewById(R.id.apk_act_features).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showOpsMenu(f); }          // 功能：更多操作
        });
        view.findViewById(R.id.apk_act_view).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {                            // 查看：进入压缩包（窗格内嵌）
                dlg.dismiss();
                openArchiveInPane(active(), f);
            }
        });
        view.findViewById(R.id.apk_act_install).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { installApk(f); }           // 安装到手机
        });
        view.findViewById(R.id.apk_act_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.show();

        // 后台检测签名方案（V1+V2+V3 等），完成后刷新签名状态行（解析失败时跳过）
        if (!broken) new Thread(new Runnable() {
            public void run() {
                final String sc = ApkSignCheck.schemes(f);
                runOnUiThread(new Runnable() {
                    public void run() {
                        for (String[] r : rowsF) {
                            if ("签名状态".equals(r[0])) {
                                r[1] = sc;
                                rowsAdapter.notifyDataSetChanged();
                            }
                        }
                    }
                });
            }
        }, "apk-scheme-detect").start();
    }

    /** {label, value} 行列表适配器（两列文本行） */
    private BaseAdapter rowsAdapter(final List<String[]> rows, final float density) {
        return new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int i) { return rows.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View convert, android.view.ViewGroup parent) {
                return makeRow(rows.get(i), density);
            }
        };
    }

    /** 单行视图：灰色标签 + 黑色值 */
    private View makeRow(String[] entry, float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (10 * density), (int) (6 * density),
                (int) (10 * density), (int) (6 * density));
        TextView label = new TextView(this);
        label.setText(entry[0]);
        label.setTextSize(13);
        label.setTextColor(ThemeManager.textSub(this));
        label.setWidth((int) (92 * density));
        TextView value = new TextView(this);
        value.setText(entry[1]);
        value.setTextSize(13);
        value.setTextColor(ThemeManager.text(this));
        row.addView(label);
        row.addView(value, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 签名详情页：后台读取 apksig 结果，逐行展示，点击行复制 */
    private void showSignDialog(final File f) {
        toast("正在读取签名信息…");
        new Thread(new Runnable() {
            public void run() {
                final List<String[]> rows = ApkSignCheck.detailRows(f);
                runOnUiThread(new Runnable() {
                    public void run() {
                        float density = getResources().getDisplayMetrics().density;
                        ListView lv = new ListView(MainActivity.this);
                        lv.setDivider(null);
                        lv.setAdapter(rowsAdapter(rows, density));
                        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                                copyToClipboard(rows.get(i)[0], rows.get(i)[1]);
                            }
                        });
                        new HfDialogBuilder(MainActivity.this)
                                .setTitle("签名详情: " + f.getName())
                                .setView(lv)
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                });
            }
        }, "apk-sign-detail").start();
    }

    /** 通过自定义 ContentProvider 以 content:// URI 调起系统安装器 */
    private void installApk(File f) {
        try {
            Uri uri = Uri.parse("content://" + getPackageName() + ".files" + f.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("安装失败: " + e.getMessage());
        }
    }

    /** 复制文本到剪贴板并提示 */
    private void copyToClipboard(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) { toast("剪贴板不可用"); return; }
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
        String show = value.length() > 40 ? value.substring(0, 40) + "…" : value;
        toast("已复制「" + label + "」: " + show);
    }

    /** v1.43.0：MT 式属性弹窗——名称/目录/类型/大小/修改时间 + 权限·所有者·用户组（可更改）+ 更多/校验/关闭 */
    private AlertDialog mPropsDialog;   // v1.43.1：当前属性弹窗引用（重开前先关旧的，避免叠两层）

    private void showProps(final File f) {
        final boolean isDir = f.isDirectory();
        final float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), 0);

        propRow(root, "名称", f.getName() + (isDir ? "/" : ""));
        propRow(root, "目录", f.getParent() == null ? "/" : f.getParent());
        propRow(root, "类型", isDir ? "文件夹" : "文件");
        propRow(root, "大小", isDir ? "—"
                : FileOps.formatSize(f.length()) + " (" + f.length() + ")");

        final FileAttrs.Attr cached = FileAttrs.get(f);
        // v1.80.0：修改时间可编辑（root 区 File API 拿不到时用 stat 回读的 mtime）
        final long shownMtime = cached != null && cached.mtime > 0 ? cached.mtime : f.lastModified();
        propRowBtn(root, "修改时间", FileOps.formatTime(shownMtime), "更改",
                new View.OnClickListener() {
                    public void onClick(View v) { showTimeEditor(f, shownMtime); }
                });

        final TextView tvPerm = propRowBtn(root, "权限",
                cached != null ? permText(cached) : "…",
                "更改", new View.OnClickListener() {
                    public void onClick(View v) { showPermEditor(f); }
                });
        final TextView tvOwner = propRowBtn(root, "所有者",
                cached != null ? (cached.owner == null ? "—" : cached.owner) : "…",
                "更改", new View.OnClickListener() {
                    public void onClick(View v) { showOwnerEditor(f); }
                });
        final TextView tvGroup = propRowBtn(root, "用户组",
                cached != null ? (cached.group == null ? "—" : cached.group) : "…",
                "更改", new View.OnClickListener() {
                    public void onClick(View v) { showOwnerEditor(f); }
                });

        final AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle("属性")
                .setView(root)
                .setNeutralButton("更多", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        showPropsMore(f);
                    }
                })
                .setNegativeButton("校验", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        showHashDialog(f);
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
        mPropsDialog = dlg;   // v1.43.1：记录引用，供更改成功后先关旧弹窗再重开

        // 异步取属性刷新（未缓存时）
        FileAttrs.load(f, new FileAttrs.Callback() {
            public void onAttr(FileAttrs.Attr a) {
                if (a == null) return;
                if (tvPerm.isAttachedToWindow()) {
                    tvPerm.setText(permText(a));
                    tvOwner.setText(a.owner == null ? "—" : a.owner);
                    tvGroup.setText(a.group == null ? "—" : a.group);
                }
            }
        });
    }

    /** "rw-r--r--(644)"；无 root 无八进制时只显示权限串 */
    private static String permText(FileAttrs.Attr a) {
        return a.perm + (a.octal >= 0 ? "(" + Integer.toOctalString(a.octal) + ")" : "");
    }

    /** 属性「更多」：常用操作入口 */
    private void showPropsMore(final File f) {
        String[] ops = {"重命名", "删除", "复制路径"};
        new HfDialogBuilder(this)
                .setTitle(f.getName())
                .setItems(ops, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        if (w == 0) askRename(f);
                        else if (w == 1) confirmDelete(f);
                        else copyToClipboard("路径", f.getPath());
                    }
                }).show();
    }

    // ---------- v1.43.0 属性弹窗布局辅助 ----------

    /** 标签 + 值（+可选右侧按钮）一行；返回值 TextView 供后续刷新 */
    private TextView propRowBtn(LinearLayout root, String label, String value,
                                String btnText, View.OnClickListener btnClick) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (9 * d);
        row.setLayoutParams(rlp);

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(ThemeManager.textSub(this));
        lv.setTextSize(14);
        lv.setWidth((int) (80 * d));
        row.addView(lv);

        TextView vv = new TextView(this);
        vv.setText(value);
        vv.setTextColor(ThemeManager.text(this));
        vv.setTextSize(15);
        vv.setTextIsSelectable(true);
        vv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                copyToClipboard("值", ((TextView) v).getText().toString());
                return true;
            }
        });
        row.addView(vv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (btnText != null) {
            Button b = new Button(this);
            b.setText(btnText);
            // v1.76.0：属性弹窗里行内按钮改为小胶囊（原来是系统方角块）
            UiDecor.barButton(b, this, UiDecor.KIND_SOFT, 11.5f);
            b.setOnClickListener(btnClick);
            row.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        root.addView(row);
        return vv;
    }

    private TextView propRow(LinearLayout root, String label, String value) {
        return propRowBtn(root, label, value, null, null);
    }

    /**
     * v1.43.0：更改权限——勾选矩阵 + 八进制实时显示，OK 走 su chmod。
     * v1.43.1：布局转置为横排（行=拥有者/用户组/其他，列=读/写/执行，与 MT 一致）；
     *          chmod 结果回读校验（FUSE 存储不支持 chmod 时如实提示）；
     *          成功后先关旧属性弹窗再重开，消除双层弹窗。
     */
    private void showPermEditor(final File f) {
        FileAttrs.Attr a = FileAttrs.stat(f);
        // v1.80.0：没有 root 也允许尝试 —— 应用私有目录里的文件本应用就能 chmod，
        // 以前一上来就 toast「需要 Root」把入口堵死了
        final boolean hasRoot = RootUtil.isRootAvailable();
        final float d = getResources().getDisplayMetrics().density;
        int o = (a != null && a.octal >= 0) ? a.octal : 0644;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), 0);

        final TextView oct = new TextView(this);
        oct.setTextColor(ThemeManager.accent(this));
        oct.setTextSize(15);
        root.addView(oct);

        // 表头：读 / 写 / 执行（横排三列）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView corner = new TextView(this);
        corner.setWidth((int) (60 * d));
        head.addView(corner);
        String[] permNames = {"读", "写", "执行"};
        for (int p = 0; p < 3; p++) {
            TextView t = new TextView(this);
            t.setText(permNames[p]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            head.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(head);

        final CheckBox[][] cbs = new CheckBox[3][3];
        String[] whoNames = {"拥有者", "用户组", "其他"};
        CompoundButton.OnCheckedChangeListener listen = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean s) { recalc(); }
            void recalc() {
                int v = 0;
                for (int w = 0; w < 3; w++)
                    for (int p = 0; p < 3; p++)
                        if (cbs[w][p].isChecked()) v |= 1 << (8 - w * 3 - p);
                oct.setText("八进制: " + Integer.toOctalString(v));   // v1.43.0 修复：按八进制显示
            }
        };
        for (int w = 0; w < 3; w++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView t = new TextView(this);
            t.setText(whoNames[w]);
            t.setTextSize(13);
            t.setWidth((int) (60 * d));
            t.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(t);
            for (int p = 0; p < 3; p++) {
                CheckBox c = new CheckBox(this);
                c.setChecked(((o >> (8 - w * 3 - p)) & 1) == 1);
                c.setOnCheckedChangeListener(listen);
                c.setGravity(Gravity.CENTER);
                cbs[w][p] = c;
                row.addView(c, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            root.addView(row);
        }
        oct.setText("八进制: " + Integer.toOctalString(o));   // 初值（v1.43.0 八进制修复）

        if (!hasRoot) {
            TextView tip = new TextView(this);
            tip.setText("未 Root：只能改本应用拥有的文件；/sdcard 这类 FUSE 挂载通常改不动。");
            tip.setTextSize(11.5f);
            tip.setTextColor(ThemeManager.textSub(this));
            tip.setPadding(0, (int) (8 * d), 0, 0);
            root.addView(tip);
        }

        // v1.80.0：常用权限预设 —— 644/755 这些天天用，9 个勾选框点起来太慢
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, (int) (12 * d), 0, 0);
        TextView pLabel = new TextView(this);
        pLabel.setText("常用");
        pLabel.setTextSize(12);
        pLabel.setTextColor(ThemeManager.textSub(this));
        pLabel.setWidth((int) (60 * d));
        pLabel.setGravity(Gravity.CENTER_VERTICAL);
        presets.addView(pLabel);
        final int[] PRESET = {0644, 0755, 0600, 0777};
        for (final int pv : PRESET) {
            TextView chip = UiDecor.pill(this, null, Integer.toOctalString(pv), false);
            chip.setTextSize(11.5f);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { applyOctalToBoxes(cbs, pv); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * d);
            presets.addView(chip, lp);
        }
        root.addView(presets);

        // 目录才有「递归」的意义
        final CheckBox rec = new CheckBox(this);
        rec.setText("同时应用到子项（递归）");
        rec.setTextSize(12);
        if (f.isDirectory()) {
            rec.setPadding(0, (int) (4 * d), 0, 0);
            root.addView(rec);
        }

        new HfDialogBuilder(this)
                .setTitle("更改权限 — " + f.getName())
                .setView(root)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        int v = 0;
                        for (int w2 = 0; w2 < 3; w2++)
                            for (int p = 0; p < 3; p++)
                                if (cbs[w2][p].isChecked()) v |= 1 << (8 - w2 * 3 - p);
                        final int fv = v;
                        final boolean deep = rec.isChecked() && f.isDirectory();
                        new Thread(new Runnable() {
                            public void run() {
                                final int rc = deep ? FileAttrs.chmodTree(f, fv)
                                        : FileAttrs.chmod(f, fv);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (rc == 0) {
                                            toast("权限已更改为 " + Integer.toOctalString(fv)
                                                    + (deep ? "（含子项）" : ""));
                                            reopenProps(f);   // v1.43.1：关旧开新，不再叠层
                                        } else if (rc == 1) {
                                            toast("该存储区不支持 chmod（FUSE 挂载），权限未实际变更");
                                            reopenProps(f);
                                        } else toast(hasRoot
                                                ? "chmod 失败，请检查路径是否可写"
                                                : "chmod 失败：没有 Root，只能改本应用拥有的文件");
                                    }
                                });
                            }
                        }, "chmod").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** v1.43.1：更改成功后刷新属性——先关旧属性弹窗（否则压在底下要关两次），再重开取新值 */
    private void reopenProps(File f) {
        if (mPropsDialog != null) {
            try { mPropsDialog.dismiss(); } catch (Throwable ignored) {}
            mPropsDialog = null;
        }
        showProps(f);
    }

    /** 把八进制权限位灌进 9 个勾选框（勾选监听会自己刷新「八进制」显示） */
    private void applyOctalToBoxes(CheckBox[][] cbs, int v) {
        for (int w = 0; w < 3; w++)
            for (int p = 0; p < 3; p++)
                cbs[w][p].setChecked(((v >> (8 - w * 3 - p)) & 1) == 1);
    }

    /**
     * v1.80.0：修改时间编辑器。
     * 只提供一个固定格式的输入框 + 「设为现在」；解析用 lenient=false，
     * 写错会明确报错，而不是被「宽松解析」悄悄改成别的日期。
     */
    private void showTimeEditor(final File f, long cur) {
        final float d = getResources().getDisplayMetrics().density;
        final java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
        fmt.setLenient(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (12 * d), (int) (22 * d), 0);

        TextView hint = new TextView(this);
        hint.setText("格式：yyyy-MM-dd HH:mm:ss（本机时区）");
        hint.setTextSize(11.5f);
        hint.setTextColor(ThemeManager.textSub(this));
        root.addView(hint);

        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(fmt.format(new java.util.Date(cur)));
        et.setSelection(et.getText().length());
        root.addView(et);

        new HfDialogBuilder(this)
                .setTitle("修改时间 — " + f.getName())
                .setView(root)
                .setNeutralButton("设为现在", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        applyMtime(f, System.currentTimeMillis());
                    }
                })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        try {
                            long ms = fmt.parse(et.getText().toString().trim()).getTime();
                            applyMtime(f, ms);
                        } catch (Exception e) {
                            toast("时间格式不正确，应为 2026-11-09 16:30:00");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyMtime(final File f, final long ms) {
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = FileAttrs.setMtime(f, ms);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ok ? "修改时间已更新" : "修改失败：无权限（root 区需要 Root）");
                        reopenProps(f);
                        if (ok) refreshAll();
                    }
                });
            }
        }, "touch").start();
    }

    /** v1.43.0：更改所有者/用户组——su chown */
    private void showOwnerEditor(final File f) {
        if (!RootUtil.isRootAvailable()) { toast("更改所有者需要 Root"); return; }
        FileAttrs.Attr a = FileAttrs.stat(f);
        if (a == null || a.owner == null) { toast("无法获取当前所有者（需要 Root）"); return; }
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (12 * d), (int) (22 * d), 0);

        final EditText etOwner = new EditText(this);
        etOwner.setHint("所有者 (如 root)");
        etOwner.setText(a.owner);
        etOwner.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(etOwner);
        final EditText etGroup = new EditText(this);
        etGroup.setHint("用户组 (如 root)");
        etGroup.setText(a.group == null ? "" : a.group);
        etGroup.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = (int) (6 * d);
        root.addView(etGroup, glp);

        new HfDialogBuilder(this)
                .setTitle("更改所有者 — " + f.getName())
                .setView(root)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        final String ow = etOwner.getText().toString().trim();
                        final String gp = etGroup.getText().toString().trim();
                        if (ow.isEmpty()) { toast("所有者不能为空"); return; }
                        new Thread(new Runnable() {
                            public void run() {
                                final int rc = FileAttrs.chown(f, ow,
                                        gp.isEmpty() ? null : gp);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (rc == 0) {
                                            toast("所有者已更改");
                                            reopenProps(f);   // v1.43.1：关旧开新
                                        } else if (rc == 1) {
                                            toast("该存储区不支持 chown，所有者未实际变更");
                                            reopenProps(f);
                                        } else toast("chown 失败（需要 Root 授权）");
                                    }
                                });
                            }
                        }, "chown").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** v1.43.0：校验弹窗——MD5/SHA1/SHA256/CRC32 + 复制 + 粘贴并校验 + 大写字母；关闭回属性 */
    private void showHashDialog(final File f) {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), 0);

        // 文件名（点击复制）
        propRowBtn(root, "文件名", f.getName(), "复制", new View.OnClickListener() {
            public void onClick(View v) { copyToClipboard("文件名", f.getName()); }
        });

        final String[] algs = {"MD5", "SHA1", "SHA256", "CRC32"};
        final String[] hashKeys = {"MD5", "SHA-1", "SHA-256", "CRC32"};
        final TextView[] values = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            values[i] = propRowBtn(root, algs[i], "计算中…", "复制",
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            String s = values[idx].getText().toString();
                            if (!s.isEmpty() && !"计算中…".equals(s))
                                copyToClipboard(algs[idx], s);
                        }
                    });
        }

        // 粘贴并校验
        TextView pasteLabel = new TextView(this);
        pasteLabel.setText("粘贴并校验");
        pasteLabel.setTextColor(ThemeManager.textSub(this));
        pasteLabel.setTextSize(14);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = (int) (14 * d);
        root.addView(pasteLabel, plp);

        LinearLayout pasteRow = new LinearLayout(this);
        pasteRow.setOrientation(LinearLayout.HORIZONTAL);
        pasteRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etPaste = new EditText(this);
        etPaste.setHint("粘贴校验值…");
        pasteRow.addView(etPaste, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button pasteBtn = new Button(this);
        pasteBtn.setText("粘贴");
        UiDecor.barButton(pasteBtn, this, UiDecor.KIND_SOFT, 12f);
        pasteRow.addView(pasteBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(pasteRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView resultV = new TextView(this);
        resultV.setTextSize(14);
        resultV.setPadding(0, (int) (6 * d), 0, 0);
        root.addView(resultV);

        // 大写字母 + 关闭
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        final boolean[] upper = {false};
        CheckBox upperCk = new CheckBox(this);
        upperCk.setText("大写字母");
        upperCk.setTextSize(14);
        bottom.addView(upperCk, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button closeBtn = new Button(this);
        closeBtn.setText("关闭");
        UiDecor.barButton(closeBtn, this, UiDecor.KIND_SOFT, 12.5f);
        bottom.addView(closeBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) (8 * d);
        root.addView(bottom, blp);

        final java.util.Map<String, String> hashCache =
                new java.util.HashMap<String, String>();
        final AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle("校验")
                .setView(root)
                .show();

        final Runnable render = new Runnable() {
            public void run() {
                for (int i = 0; i < 4; i++) {
                    String h = hashCache.get(hashKeys[i]);
                    if (h == null) values[i].setText("计算中…");
                    else if (h.isEmpty()) values[i].setText("—");
                    else values[i].setText(upper[0]
                            ? h.toUpperCase(java.util.Locale.CHINA)
                            : h.toLowerCase(java.util.Locale.CHINA));
                }
            }
        };

        upperCk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean s) {
                upper[0] = s;
                render.run();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {          // 关闭校验 → 回属性弹窗
                dlg.dismiss();
                showProps(f);
            }
        });
        pasteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                String text = "";
                if (cm != null && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0)
                    text = String.valueOf(cm.getPrimaryClip()
                            .getItemAt(0).coerceToText(MainActivity.this)).trim();
                etPaste.setText(text);
                if (text.isEmpty()) {
                    resultV.setTextColor(ThemeManager.textSub(MainActivity.this));
                    resultV.setText("剪贴板为空");
                    return;
                }
                boolean matched = false;
                for (int i = 0; i < 4; i++) {
                    String h = hashCache.get(hashKeys[i]);
                    if (h == null || h.isEmpty()) continue;
                    if (h.equalsIgnoreCase(text)) {
                        resultV.setTextColor(ThemeManager.ok(MainActivity.this));
                        resultV.setText("✓ 与 " + algs[i] + " 校验值一致");
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    resultV.setTextColor(ThemeManager.err(MainActivity.this));
                    resultV.setText("✗ 与任何校验值都不匹配");
                }
            }
        });

        // 后台计算四算法
        if (!f.isFile()) {
            for (int i = 0; i < 4; i++) hashCache.put(hashKeys[i], "");
            resultV.setTextColor(ThemeManager.textSub(MainActivity.this));
            resultV.setText("文件夹无校验值");
            render.run();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                java.util.Map<String, String> all = HashUtil.hashAll(f);
                for (int i = 0; i < 4; i++) {
                    String h = all.get(hashKeys[i]);
                    hashCache.put(hashKeys[i], h == null ? "" : h);
                }
                runOnUiThread(new Runnable() {
                    public void run() { render.run(); }
                });
            }
        }, "hash-all").start();
    }

    /** MT 式长按菜单：双列宫格弹窗 */
    private void showOpsMenu(final File f) {
        if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) {
            showApkOpsMenu(f);       // APK 用专属功能菜单（对标 MT）
            return;
        }
        final Panel dst = other();
        final boolean isFile = f.isFile();
        final boolean isDex = isFile && f.getName().toLowerCase().endsWith(".dex");
        final boolean isKs = isFile && isKeystoreFile(f, f.getName().toLowerCase());
        final boolean isSmaliDir = !isFile && hasSmali(f);
        // 动作码
        final int A_SMALI = 0, A_EDIT = 1, A_HEX = 2, A_COPY = 3, A_MOVE = 4,
                A_REN = 5, A_ZIP = 6, A_DEL = 7, A_PROP = 8, A_ASSEMBLE = 9, A_DEXPP = 14,
                A_TOZIP_C = 18, A_TOZIP_M = 19, A_COMPARE = 21,
                A_ENCRYPT = 24, A_DECRYPT = 25, A_SPLIT = 27, A_MERGE = 28,
                A_ZSTDC = 29, A_ZSTDD = 30, A_STRINGS = 31, A_SHARE = 45, A_LAN = 46, A_OPEN = 47,
                A_CLIP_C = 48, A_CLIP_M = 49;
        final java.util.List<String> ops = new ArrayList<String>();
        final java.util.List<Integer> acts = new ArrayList<Integer>();
        if (isSmaliDir) { ops.add("编译为 dex"); acts.add(A_ASSEMBLE); }
        // v1.68.0：签名文件专属操作（导入签名管理 / 查看别名与证书）
        if (isKs) { ops.add("签名信息"); acts.add(43); }
        if (isKs) { ops.add("导入签名管理"); acts.add(44); }
        if (!isFile && new java.io.File(f, "AndroidManifest.xml").isFile()) {
            ops.add("打包为 APK"); acts.add(36);   // v1.57：解包目录回打包
        }
        if (isDex) { ops.add("Dex 编辑器++"); acts.add(A_DEXPP); }
        if (isDex) { ops.add("反编译 smali"); acts.add(A_SMALI); }
        if (isDex) { ops.add("Dex 属性"); acts.add(41); }
        if (isDex) { ops.add("Dex 修复"); acts.add(42); }
        if (isFile) { ops.add("文本编辑"); acts.add(A_EDIT); }
        if (isFile) { ops.add("计算哈希"); acts.add(38);   // v1.59：MD5/SHA1/SHA256
        }
        if (isFile && ImageBatchUtil.supported(f.getName().toLowerCase())) {
            ops.add("图片处理"); acts.add(37);   // v1.58：缩放/转格式/水印
        }
        if (!isFile) { ops.add("查找重复文件"); acts.add(39); }   // v1.59：内容级查重
        if (isFile) { ops.add("HEX 查看"); acts.add(A_HEX); }
        if (isFile) { ops.add("打开方式"); acts.add(A_OPEN); }   // v1.77.0：交给系统应用
        if (isFile) { ops.add("分享"); acts.add(A_SHARE); }
        if (isFile) { ops.add("局域网快传"); acts.add(A_LAN); }   // v1.74.0
        ops.add("复制到对面"); acts.add(A_COPY);
        ops.add("移动到对面"); acts.add(A_MOVE);
        // v1.78.0：剪贴板——先复制/剪切，再翻到任意目录点底部 📋 粘贴
        ops.add("复制(剪贴板)"); acts.add(A_CLIP_C);
        ops.add("剪切(剪贴板)"); acts.add(A_CLIP_M);
        // 对面窗格打开了压缩包 → 增加写入压缩包动作（文件/目录均可；只读格式点击时给说明）
        final boolean archDst = dst.archiveFile != null;
        if (archDst) { ops.add("复制到对面压缩包"); acts.add(A_TOZIP_C); }
        if (archDst) { ops.add("移动到对面压缩包"); acts.add(A_TOZIP_M); }
        ops.add("重命名"); acts.add(A_REN);
        ops.add("压缩为 ZIP"); acts.add(A_ZIP);
        if (!ZstdUtil.isZstName(f.getName())) { ops.add("Zstd 压缩"); acts.add(A_ZSTDC); }
        if (isFile && ZstdUtil.isZstName(f.getName())) { ops.add("Zstd 解压"); acts.add(A_ZSTDD); }
        if (isFile) { ops.add("加密文件"); acts.add(A_ENCRYPT); }
        if (isFile) { ops.add("解密文件"); acts.add(A_DECRYPT); }
        if (isFile && !FileSplit.isSplitPart(f)) { ops.add("文件分割"); acts.add(A_SPLIT); }
        if (FileSplit.isSplitPart(f)) { ops.add("文件合并"); acts.add(A_MERGE); }
        if (isFile && f.length() > 0 && !MainActivity.isTextName(f.getName().toLowerCase(), f.length())) {
            ops.add("strings 提取"); acts.add(A_STRINGS);
        }
        ops.add("对比…"); acts.add(A_COMPARE);
        ops.add("删除"); acts.add(A_DEL);
        ops.add("属性"); acts.add(A_PROP);
        showGridMenu(f.getName(), ops, acts, new GridAction() {
            public void run(int act) { runFileAction(act, f, dst); }
        });
    }

    /** MT 风格 APK 功能菜单（对标 MT 长按 APK 的功能项，本地版裁剪） */
    private void showApkOpsMenu(final File f) {
        final Panel dst = other();
        // 动作码（APK 专属，与 runFileAction 对应）
        final int A_DEX2SMALI = 10, A_EXTRACT_DEX = 11,
                A_SIGN = 13, A_COPY = 3, A_REN = 5, A_PROP = 8, A_DEXPP = 15, A_ARSCPP = 16, A_MFPP = 17,
                A_TOZIP_C = 18, A_TOZIP_M = 19, A_SIGN_APK = 20, A_APK_COMPARE = 22, A_APK_CLONE = 23,
                A_SIGKILL = 26, A_INFO = 32, A_UNPACK = 33, A_SIGN_CUSTOM = 34, A_RM_SIGN = 35,
                A_DEL = 7, A_IMGBATCH = 37, A_ARSCPP2 = 40, A_SHARE = 45, A_LAN = 46, A_OPEN = 47,
                A_CLIP_C = 48, A_CLIP_M = 49;
        final java.util.List<String> ops = new ArrayList<String>();
        final java.util.List<Integer> acts = new ArrayList<Integer>();
        ops.add("Dex 编辑器++"); acts.add(A_DEXPP);
        ops.add("ARSC 编辑"); acts.add(A_ARSCPP);
        ops.add("ARSC++ 编辑"); acts.add(A_ARSCPP2);
        ops.add("Manifest 编辑器++"); acts.add(A_MFPP);
        ops.add("反编译 dex→smali"); acts.add(A_DEX2SMALI);
        ops.add("提取 dex"); acts.add(A_EXTRACT_DEX);
        ops.add("分享"); acts.add(A_SHARE);
        ops.add("打开方式"); acts.add(A_OPEN);   // v1.77.0
        ops.add("局域网快传"); acts.add(A_LAN);   // v1.74.0
        ops.add("签名信息"); acts.add(A_SIGN);
        ops.add("APK 签名"); acts.add(A_SIGN_APK);
        ops.add("APK 对比"); acts.add(A_APK_COMPARE);
        ops.add("APK 共存"); acts.add(A_APK_CLONE);
        ops.add("去除签名校验"); acts.add(A_SIGKILL);
        ops.add("APK 信息"); acts.add(A_INFO);
        ops.add("APK 解包"); acts.add(A_UNPACK);
        ops.add("自定义密钥签名"); acts.add(A_SIGN_CUSTOM);
        ops.add("去除签名"); acts.add(A_RM_SIGN);
        ops.add("删除"); acts.add(A_DEL);        ops.add("复制到对面"); acts.add(A_COPY);
        // v1.78.0：剪贴板（APK 也走同一套，翻到别的目录再粘贴）
        ops.add("复制(剪贴板)"); acts.add(A_CLIP_C);
        ops.add("剪切(剪贴板)"); acts.add(A_CLIP_M);
        // 对面窗格打开了压缩包 → 增加写入压缩包动作（只读格式点击时给说明）
        final boolean archDst = dst.archiveFile != null;
        if (archDst) { ops.add("复制到对面压缩包"); acts.add(A_TOZIP_C); }
        if (archDst) { ops.add("移动到对面压缩包"); acts.add(A_TOZIP_M); }
        ops.add("重命名"); acts.add(A_REN);
        ops.add("属性"); acts.add(A_PROP);
        showGridMenu(f.getName(), ops, acts, new GridAction() {
            public void run(int act) { runFileAction(act, f, dst); }
        });
    }

    /** 双列宫格弹窗（MT 式）：ops/acts 一一对应，点击动作分发给回调；点击后自动关闭弹窗 */
    private void showGridMenu(final String title,
            final java.util.List<String> ops, final java.util.List<Integer> acts,
            final GridAction cb) {
        final float den = getResources().getDisplayMetrics().density;
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < ops.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2; j++) {
                final int idx = i + j;
                TextView tv = new TextView(this);
                if (idx < ops.size()) {
                    tv.setText(ops.get(idx));
                    tv.setTextSize(15);
                    tv.setTextColor(ThemeManager.text(this));
                    tv.setPadding((int) (20 * den), (int) (14 * den),
                            (int) (8 * den), (int) (14 * den));
                    tv.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (holder[0] != null) holder[0].dismiss();
                            cb.run(acts.get(idx));
                        }
                    });
                }
                row.addView(tv, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(row);
        }
        holder[0] = new HfDialogBuilder(this)
                .setTitle(title)
                .setView(grid)
                .show();
    }

    /** 长按菜单动作分发 */
    private void runFileAction(int act, File f, Panel dst) {
        switch (act) {
            case 0: decompileDexFile(f); break;   // 反编译 smali（独立 dex）
            case 1: openEditor(f); break;         // 文本编辑
            case 2: openHex(f); break;            // HEX 查看
            case 3:
                FileTaskEngine.get().submit(FileTask.COPY,
                        java.util.Collections.singletonList(f), dst.dir);
                toast("已加入复制队列");
                break;
            case 4:
                FileTaskEngine.get().submit(FileTask.MOVE,
                        java.util.Collections.singletonList(f), dst.dir);
                toast("已加入移动队列");
                break;
            case 5: askRename(f); break;
            case 6: askCompress(f); break;
            case 7: confirmDelete(f); break;
            case 24: askEncryptFile(f); break;       // 加密文件（自家格式 .enc）
            case 25: askDecryptFile(f); break;       // 解密 .enc 文件
            case 8: showProps(f); break;
            case 9: assembleDexDir(f); break;     // smali 目录编译为 dex
            case 10: extractApkDexes(f, true); break;   // APK 内全部 dex 反编译
            case 11: extractApkDexes(f, false); break;  // 仅提取 dex
            case 13: showSignDialog(f); break;    // 签名详情
            case 20: startSign(f, false); break;  // APK 签名（内置密钥；_edit.apk 原地签）
            case 14:                              // 独立 dex → Dex 编辑器++
                startActivity(new Intent(this, DexEditorActivity.class)
                        .putExtra(DexEditorActivity.EXTRA_FILE, f.getAbsolutePath()));
                break;
            case 15:                              // APK → Dex 编辑器++（MultiDex 多选）
                startActivity(new Intent(this, DexEditorActivity.class)
                        .putExtra(DexEditorActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case 16:                              // APK → ARSC 编辑
                startActivity(new Intent(this, ArscEditorActivity.class)
                        .putExtra(ArscEditorActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case 40:                              // APK → ARSC++ 编辑
                startActivity(new Intent(this, ArscPlusActivity.class)
                        .putExtra(ArscPlusActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case 17:                              // APK → Manifest 编辑器++
                startActivity(new Intent(this, ManifestEditorActivity.class)
                        .putExtra(ManifestEditorActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case 18:                              // 复制到对面窗格已打开的压缩包
                if (dst.archiveFile != null) askAddToArchive(f, dst, false);
                break;
            case 19:                              // 移动到对面窗格已打开的压缩包
                if (dst.archiveFile != null) askAddToArchive(f, dst, true);
                break;
            case 21:                              // 对比…（自动判别 目录/文本/哈希）
                askCompareTarget(f, false);
                break;
            case 22:                              // APK 对比
                askCompareTarget(f, true);
                break;
            case 23:                              // APK 共存
                askApkClone(f);
                break;
            case 26:                              // 去除签名校验（SigKill）
                doSigKill(f);
                break;
            case 27:                              // 文件分割（对标 MT）
                askSplitSize(f);
                break;
            case 28:                              // 分卷合并
                doMerge(f);
                break;
            case 29:                              // Zstd 压缩（文件→.zst，目录→.tar.zst）
                doZstdCompress(f);
                break;
            case 30:                              // Zstd 解压（.zst/.tar.zst/.tzst）
                doZstdExtract(f);
                break;
            case 31:                              // strings 提取（二进制可读字符串）
                startActivity(new Intent(this, StringsViewerActivity.class)
                        .putExtra(StringsViewerActivity.EXTRA_FILE, f.getAbsolutePath()));
                break;
            case 32:                              // APK 信息（v1.57）
                startActivity(new Intent(this, ApkInfoActivity.class)
                        .putExtra(ApkInfoActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case 33:                              // APK 解包（v1.57）
                doApkUnpack(f);
                break;
            case 34:                              // 自定义密钥签名（v1.57）
                askCustomSign(f, false);
                break;
            case 35:                              // 去除签名 → 未签名 APK（v1.57）
                doRemoveSignature(f);
                break;
            case 36:                              // 解包目录 → 打包为 APK（v1.57）
                doApkPack(f);
                break;
            case 37:                              // 图片处理（缩放/转格式/水印，v1.58）
                askImageBatch(java.util.Collections.singletonList(f.getAbsolutePath()));
                break;
            case 38:                              // 计算哈希（MD5/SHA1/SHA256，v1.59）
                doHash(f);
                break;
            case 39:                              // 查找重复文件（v1.59）
                startActivity(new Intent(this, DupFinderActivity.class)
                        .putExtra(DupFinderActivity.EXTRA_ROOT, f.getAbsolutePath()));
                break;
            case 41:                              // Dex 属性（v1.66.5）
                showDexProps(f);
                break;
            case 42:                              // Dex 修复（v1.66.5）
                askDexFix(f);
                break;
            case 43:                              // 签名文件 → 签名信息（v1.68.0）
                showKeystoreInfo(f);
                break;
            case 44:                              // 签名文件 → 导入签名管理（v1.68.0）
                importKeystore(f);
                break;
            case 45:                              // 分享（v1.72.0，APK 与普通文件共用）
                shareFile(f);
                break;
            case 46:                              // 局域网快传（v1.74.0）
                openLanShare(f);
                break;
            case 47:                              // 打开方式（v1.77.0）
                if (!OpenWith.open(this, f)) toast("没有可打开该类型的应用");
                break;
            case 48:                              // 复制到剪贴板（v1.78.0）
                FileClipboard.get().put(java.util.Collections.singletonList(f), false);
                updatePasteButton();
                toast("已复制 " + f.getName() + "，翻到目标目录点底部 📋 粘贴");
                break;
            case 49:                              // 剪切到剪贴板
                FileClipboard.get().put(java.util.Collections.singletonList(f), true);
                updatePasteButton();
                toast("已剪切 " + f.getName() + "，粘贴后原位置的文件会消失");
                break;
        }
    }

    /** 带着文件进入局域网快传页：对方设备在雷达上点一下就发 */
    private void openLanShare(File f) {
        if (f == null || !f.isFile()) {
            toast("只能传文件（文件夹请先打包）");
            return;
        }
        try {
            Intent i = new Intent(this, LanShareActivity.class);
            i.putExtra(LanShareActivity.EXTRA_PATH, f.getAbsolutePath());
            startActivity(i);
        } catch (Throwable t) {
            toast("打开失败: " + t.getMessage());
        }
    }

    // ============================================================ APK 共存（v1.20.0）

    /** APK 共存入口：后台读原包名 → 弹框输入新包名 → 改清单重签名 */
    private void askApkClone(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("读取包名…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String oldPkg = null, err = null;
                try { oldPkg = ApkCloneTool.currentPackage(f); }
                catch (Throwable t) { err = t.getMessage(); }
                final String pkg = oldPkg, e = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) { toast("读取失败: " + e); return; }
                        askApkCloneInput(f, pkg);
                    }
                });
            }
        }, "apk-clone-read").start();
    }

    private void askApkCloneInput(final File f, final String oldPkg) {
        final EditText et = new EditText(this);
        et.setText((oldPkg == null ? "com.example.app" : oldPkg) + ".clone1");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        new HfDialogBuilder(this)
                .setTitle("APK 共存")
                .setMessage("原包名: " + (oldPkg == null ? "(读取失败，请手动输入)" : oldPkg)
                        + "\n\n输入新包名（provider authorities 等引用将同步替换，"
                        + "生成后自动 v1+v2+v3 重签名，可与原版并存安装）")
                .setView(et)
                .setPositiveButton("生成", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String np = et.getText().toString().trim();
                        if (!np.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
                            toast("包名格式不合法（如 com.example.app）");
                            return;
                        }
                        doApkClone(f, np);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doApkClone(final File f, final String newPkg) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("生成共存 APK …");
        pd.setCancelable(false);
        pd.show();
        String base = f.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final File out = new File(f.getParentFile(), base + "_clone.apk");
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                File unsigned = null;
                try {
                    ApkCloneTool.Result r = ApkCloneTool.buildClonedManifest(f, newPkg);
                    File tmp = new File(getCacheDir(), "apk_clone");
                    FileOps.deleteRecursive(tmp);
                    tmp.mkdirs();
                    unsigned = new File(tmp, "unsigned.apk");
                    Map<String, byte[]> replace = new java.util.HashMap<String, byte[]>();
                    replace.put("AndroidManifest.xml", r.newManifest);
                    ApkRepack.repack(f, replace, unsigned);
                    if (out.exists()) out.delete();
                    InputStream ksIn = getAssets().open("hexfile.p12");
                    ApkSignerTool.sign(ksIn, ApkSignerTool.KEY_PASSWORD, unsigned, out, 21);
                    ksIn.close();
                    FileOps.deleteRecursive(tmp);
                    info = "原包名: " + r.oldPkg + "\n新包名: " + r.newPkg
                            + "\n共修改 " + r.replaced + " 处引用\n\n输出: " + out.getAbsolutePath();
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Throwable c = t.getCause();
                    while (c != null) {
                        err += "\n↳ " + c.getClass().getSimpleName() + ": " + c.getMessage();
                        c = c.getCause();
                    }
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("共存失败").setMessage(e)
                                    .setPositiveButton("知道了", null).show();
                        } else {
                            refreshAll();
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("共存 APK 已生成")
                                    .setMessage(ok)
                                    .setPositiveButton("关闭", null)
                                    .show();
                        }
                    }
                });
            }
        }, "apk-clone").start();
    }

    // ============================================================ 去除签名校验（v1.34.0）

    /**
     * SigKill 入口：反编译全部 dex → 启发式 patch 签名校验点 → 回编译 → 重打包 → v1+v2+v3 重签名。
     * 输出与原 APK 同目录：<名>_nosig.apk。
     */
    private void doSigKill(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("去除签名校验…");
        pd.setCancelable(false);
        pd.show();
        String base = f.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final File out = new File(f.getParentFile(), base + "_nosig.apk");
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                File work = new File(getCacheDir(), "sigkill_tmp");
                try {
                    FileOps.deleteRecursive(work);
                    work.mkdirs();

                    // 1) 抽取全部 dex → 反编译 → patch → 回编译
                    Map<String, byte[]> replace = new java.util.LinkedHashMap<String, byte[]>();
                    java.util.List<SigKill.Patch> patches = new ArrayList<SigKill.Patch>();
                    java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
                    try {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                        int dexIdx = 0;
                        while (en.hasMoreElements()) {
                            java.util.zip.ZipEntry ze = en.nextElement();
                            String nm = ze.getName();
                            if (!nm.toLowerCase().endsWith(".dex")) continue;
                            File dex = new File(work, "src_" + dexIdx + ".dex");
                            java.io.InputStream in = zf.getInputStream(ze);
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(dex);
                            byte[] buf = new byte[65536]; int n;
                            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                            fo.close(); in.close();

                            File smaliDir = new File(work, "smali_" + dexIdx);
                            String e = SmaliTool.decompile(dex, smaliDir, 4);
                            if (e != null) throw new Exception("反编译 " + nm + " 失败: " + e);
                            patches.addAll(SigKill.run(smaliDir));
                            File outDex = new File(work, "out_" + dexIdx + ".dex");
                            e = SmaliTool.assemble(smaliDir, outDex, 4);
                            if (e != null) throw new Exception("回编译 " + nm + " 失败: " + e);

                            byte[] data = readAllBytes(outDex);
                            if (data != null) replace.put(nm, data);
                            dexIdx++;
                        }
                    } finally {
                        try { zf.close(); } catch (Throwable ig) {}
                    }

                    if (patches.isEmpty()) {
                        err = "未发现可去除的签名校验点（没有 PackageManager.checkSignatures 调用、"
                                + "Signature.verify/equals 调用点或自带 Signature 副本），原 APK 未修改。";
                    } else {
                        // 2) 重打包（替换 dex）→ 重签名
                        File unsigned = new File(work, "unsigned.apk");
                        ApkRepack.repack(f, replace, unsigned);
                        if (out.exists()) out.delete();
                        InputStream ksIn = getAssets().open("hexfile.p12");
                        ApkSignerTool.sign(ksIn, ApkSignerTool.KEY_PASSWORD, unsigned, out, 21);
                        ksIn.close();

                        // 3) 汇总报告
                        int cs = 0, call = 0, body = 0;
                        for (SigKill.Patch p : patches) {
                            if (p.kind.equals("checkSignatures→0")) cs++;
                            else if (p.kind.equals("Signature调用→true")) call++;
                            else body++;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("共 patch ").append(patches.size()).append(" 处\n");
                        if (cs > 0) sb.append("· checkSignatures 返回值强置 0: ").append(cs).append(" 处\n");
                        if (call > 0) sb.append("· Signature.verify/equals 调用点强 true: ").append(call).append(" 处\n");
                        if (body > 0) sb.append("· 自带 Signature 副本方法体重写: ").append(body).append(" 处\n");
                        sb.append("\n输出: ").append(out.getAbsolutePath())
                                .append("\n已用内置密钥 v1+v2+v3 重签名");
                        info = sb.toString();
                    }
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Throwable c = t.getCause();
                    while (c != null) {
                        err += "\n↳ " + c.getClass().getSimpleName() + ": " + c.getMessage();
                        c = c.getCause();
                    }
                } finally {
                    FileOps.deleteRecursive(work);
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("去除签名校验").setMessage(e)
                                    .setPositiveButton("知道了", null).show();
                        } else {
                            refreshAll();
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("已生成 <名>_nosig.apk")
                                    .setMessage(ok)
                                    .setPositiveButton("关闭", null)
                                    .show();
                        }
                    }
                });
            }
        }, "sigkill").start();
    }

    // ============================================================ 文件分割/合并（v1.35.0）

    /** 文件分割：选择分卷大小 → 后台切割到源文件所在目录（源文件保留）。
     *  用自定义视图而非 setMessage+setItems——部分系统主题下二者同用时列表不显示。 */
    private void askSplitSize(final File f) {
        final long[] sizes = { 1L << 20, 5L << 20, 10L << 20, 50L << 20, 100L << 20 };
        final String[] labels = { "1 MB", "5 MB", "10 MB", "50 MB", "100 MB" };
        final float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(this);
        msg.setText("文件大小: " + FileOps.formatSize(f.length())
                + "\n分卷输出到当前目录（<名>.001 起），原文件保留；合并时长按任一分卷选「文件合并」。");
        msg.setTextSize(14);
        msg.setTextColor(ThemeManager.textSub(this));
        msg.setPadding((int) (20 * den), (int) (14 * den), (int) (20 * den), (int) (4 * den));
        box.addView(msg);
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText("每卷 " + labels[i]);
            tv.setTextSize(16);
            tv.setTextColor(ThemeManager.accent(this));
            tv.setPadding((int) (28 * den), (int) (12 * den), (int) (20 * den), (int) (12 * den));
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    doSplit(f, sizes[idx], labels[idx]);
                }
            });
            box.addView(tv);
        }
        holder[0] = new HfDialogBuilder(this)
                .setTitle("文件分割")
                .setView(box)
                .show();
    }

    private void doSplit(final File f, final long chunkSize, final String chunkLabel) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("分割中（每卷 " + chunkLabel + "）…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    java.util.List<File> parts = FileSplit.split(f, f.getParentFile(), chunkSize);
                    info = "共 " + parts.size() + " 卷（每卷 " + chunkLabel + "）\n"
                            + parts.get(0).getName() + " ~ " + parts.get(parts.size() - 1).getName() + "\n"
                            + "另生成清单 " + f.getName() + ".parts（合并时用于缺卷校验）\n"
                            + "目录: " + f.getParent();
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName();
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            toast("分割失败: " + e);
                        } else {
                            refreshAll();
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("分割完成")
                                    .setMessage(ok)
                                    .setPositiveButton("关闭", null)
                                    .show();
                        }
                    }
                });
            }
        }, "file-split").start();
    }

    /** 分卷合并：长按任一分卷（惯例 .001）→ 按连续序号拼回原文件，输出到分卷所在目录 */
    private void doMerge(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("合并分卷…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    FileSplit.MergeResult r = FileSplit.merge(f, f.getParentFile());
                    info = "共合并 " + r.parts + " 卷\n输出: " + r.output.getAbsolutePath()
                            + "\n大小: " + FileOps.formatSize(r.bytes);
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName();
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("合并失败").setMessage(e)
                                    .setPositiveButton("知道了", null).show();
                        } else {
                            refreshAll();
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("合并完成")
                                    .setMessage(ok)
                                    .setPositiveButton("关闭", null)
                                    .show();
                        }
                    }
                });
            }
        }, "file-merge").start();
    }

    // ============================================================ Zstd 压缩/解压（v1.36.0）

    /** Zstd 压缩：单文件 → <名>.zst；目录/多源 → <名>.tar.zst。输出到源所在目录，级别 3。 */
    private void doZstdCompress(final File f) {
        final File out = new File(f.getParentFile(),
                f.isDirectory() ? f.getName() + ".tar.zst" : f.getName() + ".zst");
        final File use;
        if (out.exists()) {
            use = new File(f.getParentFile(),
                    ZstdUtil.stripName(out.getName()) + " (1).zst");
        } else {
            use = out;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Zstd 压缩中…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    java.util.List<File> srcs = new java.util.ArrayList<File>();
                    srcs.add(f);
                    ZstdUtil.compress(use, srcs, ZstdUtil.DEFAULT_LEVEL);
                    info = "输出: " + use.getAbsolutePath() + "\n大小: "
                            + FileOps.formatSize(use.length()) + "（原 "
                            + FileOps.formatSize(f.isFile() ? f.length() : dirSize(f)) + "）\n"
                            + "级别: " + ZstdUtil.DEFAULT_LEVEL;
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName();
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            toast("Zstd 压缩失败: " + e);
                        } else {
                            refreshAll();
                            toast("Zstd 压缩完成 → " + use.getName());
                        }
                    }
                });
            }
        }, "zstd-compress").start();
    }

    /** Zstd 解压：.zst / .tar.zst / .tzst → 同名目录（或单文件还原）到源所在目录 */
    private void doZstdExtract(final File f) {
        File t = new File(f.getParentFile(), ZstdUtil.stripName(f.getName()));
        int n = 1;
        while (t.exists()) {
            t = new File(f.getParentFile(),
                    ZstdUtil.stripName(f.getName()) + " (" + (++n) + ")");
        }
        final File outDir = t;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Zstd 解压中…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    File r = ZstdUtil.extract(f, outDir);
                    info = "已解压 → " + r.getAbsolutePath();
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName();
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            toast("Zstd 解压失败: " + e);
                        } else {
                            refreshAll();
                            toast(ok);
                        }
                    }
                });
            }
        }, "zstd-extract").start();
    }

    private static long dirSize(File d) {
        long s = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File c : fs) s += c.isDirectory() ? dirSize(c) : c.length();
        return s;
    }

    // ============================================================ 对比（v1.17.0）

    /**
     * 选择对比的另一个操作数：默认预填「对面窗格同相对路径」（存在时）。
     * @param apkMode true = APK 对比（清单+条目），false = 自动判别模式
     */
    private void askCompareTarget(final File f, final boolean apkMode) {
        // 对面窗格同相对路径（当前窗格目录 → 对面目录 映射）
        String prefill = "";
        try {
            File curDir = active().dir;
            File dstDir = other().dir;
            if (curDir != null && dstDir != null) {
                String fa = f.getAbsolutePath(), ca = curDir.getAbsolutePath();
                if (fa.startsWith(ca)) {
                    String rel = fa.substring(ca.length());
                    while (rel.startsWith("/")) rel = rel.substring(1);
                    File cand = new File(dstDir, rel);
                    // 防自比：预填路径不能是 A 自身
                    if (cand.exists() && !cand.getAbsolutePath().equals(f.getAbsolutePath()))
                        prefill = cand.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {}
        final EditText et = new EditText(this);
        et.setText(prefill);
        et.setHint("对面窗格路径，或手动输入");
        et.setTextSize(13);
        // 目录对比可选内容校验（mtime 不同但内容相同时不误判；慢）
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(et);
        // 快捷路径：浏览…（选择器）/ 对面窗格（直接填对面当前目录）
        final String startDir = other().dir != null
                ? other().dir.getAbsolutePath() : f.getParent();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        Button bBrowse = new Button(this);
        bBrowse.setText("浏览…");
        UiDecor.barButton(bBrowse, this, UiDecor.KIND_SOFT, 12f);
        bBrowse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickComparePath(f, startDir, et); }
        });
        Button bOther = new Button(this);
        bOther.setText("对面窗格");
        UiDecor.barButton(bOther, this, UiDecor.KIND_SOFT, 12f);
        UiDecor.gap(bOther, this, 4f);
        bOther.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { et.setText(startDir); }
        });
        row.addView(bBrowse, blp);
        row.addView(bOther, blp);
        box.addView(row);
        final android.widget.CheckBox cbHash = new android.widget.CheckBox(this);
        cbHash.setText("内容校验（SHA-256，慢但准）");
        cbHash.setTextSize(13);
        cbHash.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(cbHash);
        new HfDialogBuilder(this)
                .setTitle((apkMode ? "APK 对比" : "对比") + "：另一个"
                        + (f.isDirectory() ? "目录" : "文件"))
                .setMessage("A: " + f.getAbsolutePath()
                        + (prefill.isEmpty() ? "" : "\n默认: " + prefill))
                .setView(box)
                .setPositiveButton("开始对比", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String p = et.getText().toString().trim();
                        if (p.isEmpty()) { toast("请输入对比目标路径"); return; }
                        File b = new File(p);
                        if (!b.exists()) { toast("路径不存在: " + p); return; }
                        startCompare(f, b, apkMode, cbHash.isChecked());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 简易路径选择器（对比目标用）：从 startDir 开始浏览，
     * 点目录进入，点文件直接选定；「选此目录」返回当前所在目录。
     * A 为目录时只能选目录；A 为文件时点文件直接选定。
     */
    private void pickComparePath(final File f, final String startDir, final EditText et) {
        final File[] cur = {new File(startDir != null ? startDir : "/sdcard")};
        final java.util.List<File> curKids = new ArrayList<File>();
        final android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        final AlertDialog[] holder = new AlertDialog[1];
        final ListView lv = new ListView(this);
        lv.setAdapter(adapter);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = new Runnable() {
            public void run() {
                File d = cur[0];
                curKids.clear();
                adapter.clear();
                File parent = d.getParentFile();
                if (parent != null) adapter.add("⬆ ..");
                File[] kids = d.listFiles();
                if (kids != null) {
                    java.util.Arrays.sort(kids, new java.util.Comparator<File>() {
                        public int compare(File x, File y) {
                            if (x.isDirectory() != y.isDirectory())
                                return x.isDirectory() ? -1 : 1;
                            return x.getName().compareToIgnoreCase(y.getName());
                        }
                    });
                    for (File k : kids) {
                        curKids.add(k);
                        adapter.add((k.isDirectory() ? "📁 " : "📄 ") + k.getName()
                                + (k.isDirectory() ? "/" : ""));
                    }
                }
                holder[0].setTitle("选择对比目标\n" + d.getAbsolutePath());
            }
        };
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                File parent = cur[0].getParentFile();
                int off = parent != null ? 1 : 0;
                if (parent != null && pos == 0) { cur[0] = parent; refresh[0].run(); return; }
                int idx = pos - off;
                if (idx < 0 || idx >= curKids.size()) return;
                File k = curKids.get(idx);
                if (k.isDirectory()) { cur[0] = k; refresh[0].run(); return; }
                if (f.isDirectory()) { toast("A 是目录，请选目录（或点「选此目录」）"); return; }
                et.setText(k.getAbsolutePath());
                holder[0].dismiss();
            }
        });
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.addView(lv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        holder[0] = new HfDialogBuilder(this)
                .setTitle("选择对比目标")
                .setView(wrap)
                .setPositiveButton("选此目录", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        et.setText(cur[0].getAbsolutePath());
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        holder[0].show();
        refresh[0].run();
    }

    /** 按两侧类型自动判别对比模式并打开 CompareActivity */
    private void startCompare(File a, File b, boolean apkMode, boolean verifyHash) {
        String mode;
        if (apkMode) {
            mode = CompareActivity.MODE_APK;
        } else if (a.isDirectory() && b.isDirectory()) {
            mode = CompareActivity.MODE_FOLDER;
        } else if (a.isFile() && b.isFile()) {
            String la = a.getName().toLowerCase(), lb = b.getName().toLowerCase();
            boolean textA = isTextName(la, a.length()) || isTextName(lb, b.length());
            // 任一像文本且都能按文本解码 → 文本对比；否则哈希对比
            mode = textA ? CompareActivity.MODE_TEXT : CompareActivity.MODE_HASH;
        } else {
            toast("无法对比：目录只能与目录比，文件只能与文件比");
            return;
        }
        startActivity(new Intent(this, CompareActivity.class)
                .putExtra(CompareActivity.EXTRA_MODE, mode)
                .putExtra(CompareActivity.EXTRA_PATH_A, a.getAbsolutePath())
                .putExtra(CompareActivity.EXTRA_PATH_B, b.getAbsolutePath())
                .putExtra(CompareActivity.EXTRA_VERIFY_HASH, verifyHash));
    }

    /** 目录树中是否含 .smali 文件（限制遍历规模防止大目录卡顿） */
    private static boolean hasSmali(File dir) {
        java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<File>();
        queue.add(dir);
        int visited = 0;
        while (!queue.isEmpty()) {
            File d = queue.poll();
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (++visited > 5000) return false;   // 目录太大，放弃检测
                if (k.isDirectory()) queue.add(k);
                else if (k.getName().toLowerCase().endsWith(".smali")) return true;
            }
        }
        return false;
    }

    /** smali 目录后台编译为 dex → 输出到同级 <目录名>.dex */
    private void assembleDexDir(final File dir) {
        final File out = new File(dir.getParentFile(), dir.getName() + ".dex");
        toast("编译中…（大量 smali 可能需要较长时间）");
        new Thread(new Runnable() {
            public void run() {
                final String err = SmaliTool.assemble(dir, out, 4);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err == null) {
                            toast("已编译 → " + out.getPath());
                            refreshAll();
                        } else {
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle("编译失败")
                                    .setMessage(err + "\n\n提示：可打开输出侧 .smali 排查语法错误")
                                    .setPositiveButton("关闭", null)
                                    .show();
                        }
                    }
                });
            }
        }, "smali").start();
    }

    /** 列出 APK 内全部 .dex 条目名 */
    private static List<String> apkDexEntries(File apk) {
        List<String> out = new ArrayList<String>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apk))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String n = ze.getName();
                if (!ze.isDirectory() && n.endsWith(".dex")) out.add(n);
                zis.closeEntry();
            }
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * 从 APK 提取全部 dex；decompile=true 时逐个反编译成 smali 并跳转浏览，
     * 否则仅把 dex 文件提取到对面面板的 <apk名>_dex/ 目录。
     */
    private void extractApkDexes(final File apk, final boolean decompile) {
        final File cacheTmp = new File(getCacheDir(), "apk_dex_tmp");
        final File outRoot = decompile
                ? new File(WorkDir.base(), "Smali/" + stripExt(apk.getName()))
                : new File(other().dir, stripExt(apk.getName()) + "_dex");
        toast(decompile ? "反编译中…（APK 内 dex 越多耗时越长）" : "提取中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(cacheTmp);
                    cacheTmp.mkdirs();
                    List<String> dexes = apkDexEntries(apk);
                    if (dexes.isEmpty()) { err = "APK 内未找到 dex"; }
                    for (String entry : dexes) {
                        if (err != null) break;
                        if (!ZipUtil.extractEntry(apk, entry, cacheTmp)) {
                            err = "提取失败: " + entry; break;
                        }
                        File tmpDex = new File(cacheTmp, entry);
                        if (decompile) {
                            String base = new File(entry).getName();
                            if (base.endsWith(".dex")) base = base.substring(0, base.length() - 4);
                            err = SmaliTool.decompile(tmpDex, new File(outRoot, base), 4);
                        } else {
                            FileOps.copy(tmpDex, outRoot);   // 复制保留原名
                            if (!new File(outRoot, tmpDex.getName()).exists())
                                err = "写入失败: " + entry;
                        }
                    }
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                FileOps.deleteRecursive(cacheTmp);
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr == null) {
                            toast(decompile ? "已生成 smali →\n" + outRoot.getPath()
                                    : "已提取 dex → " + outRoot.getPath());
                            refreshAll();
                            if (decompile) {
                                Intent i = new Intent(MainActivity.this, MainActivity.class);
                                i.putExtra("dir", outRoot.getAbsolutePath());
                                startActivity(i);   // singleTop：onNewIntent 跳转
                            }
                        } else {
                            toast("失败: " + ferr);
                        }
                    }
                });
            }
        }, "apk-dex").start();
    }

    /**
     * v1.72.0：分享文件 → 系统分享面板（微信 / QQ / 邮件 / 蓝牙…凡是能接收文件的都列出来）。
     *
     * 走项目自带的 ApkFileProvider（content://com.hex91.file.files + 绝对路径），
     * targetSdk 30 下用 file:// 会直接抛 FileUriExposedException。
     * ClipData + FLAG_GRANT_READ_URI_PERMISSION 缺一不可：只给 intent 加 flag、
     * 不设 ClipData 时，部分 ROM/接收方拿不到读权限打开就报「文件不存在」。
     */
    private void shareFile(final File f) {
        if (f == null || !f.isFile()) {
            toast("只能分享文件（文件夹请先打包）");
            return;
        }
        try {
            Uri uri = Uri.parse("content://com.hex91.file.files" + f.getAbsolutePath());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(MimeUtil.of(f.getName()));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            send.setClipData(ClipData.newRawUri(f.getName(), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "分享 " + f.getName());
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Throwable t) {
            toast("分享失败: " + t.getMessage());
        }
    }

    /** 去掉文件名扩展名 */
    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void askRename(final File f) {
        final EditText et = new EditText(this);
        et.setText(f.getName());
        new HfDialogBuilder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (!name.isEmpty() && FileOps.rename(f, name)) {
                            toast("已重命名");
                            refreshAll();
                        } else {
                            toast("重命名失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askCompress(final File f) {
        final File out = new File(active().dir, f.getName() + ".zip");
        final float den = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * den), (int) (8 * den), (int) (24 * den), (int) (8 * den));
        final EditText pw = new EditText(this);
        pw.setHint("留空=普通 ZIP；输入密码=加密 ZIP（AES 或 兼容 ZipCrypto，见下方选择）");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pw);
        // 加密方式选择：AES（需 7-Zip/ZArchiver）或 兼容 ZipCrypto（系统可直接解）
        final String[] modes = {
                "AES-256（需 7-Zip / ZArchiver，推荐）",
                "AES-192",
                "AES-128",
                "兼容 ZipCrypto（Windows/系统可直接解，弱加密）"};
        final int[] sel = {0};
        final TextView stv = new TextView(this);
        stv.setText("加密方式: " + modes[sel[0]]);
        stv.setTextSize(15);
        stv.setTextColor(ThemeManager.text(this));
        stv.setPadding(0, (int) (10 * den), 0, (int) (4 * den));
        stv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new HfDialogBuilder(MainActivity.this)
                        .setTitle("选择加密方式")
                        .setSingleChoiceItems(modes, sel[0],
                                new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        sel[0] = which;
                                        stv.setText("加密方式: " + modes[which]);
                                        d.dismiss();
                                    }
                                })
                        .show();
            }
        });
        root.addView(stv);
        new HfDialogBuilder(this)
                .setTitle("压缩为 ZIP")
                .setMessage("将 " + f.getName() + " 打包为:\n" + out.getPath())
                .setView(root)
                .setPositiveButton("压缩", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        java.util.List<File> src = new java.util.ArrayList<File>();
                        src.add(f);
                        String p = pw.getText().toString();
                        boolean ok;
                        if (p.isEmpty()) {
                            ok = ZipUtil.createZip(out, src);
                        } else if (sel[0] == 3) {
                            ok = ZipUtil.createZipEncryptedCompat(out, src, p);
                        } else {
                            ok = ZipUtil.createZipEncrypted(out, src, p, 3 - sel[0]); // 0→256,1→192,2→128
                        }
                        toast(ok ? "已压缩: " + out.getName() : "压缩失败");
                        refreshAll();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 加密文件（自家私有格式 .enc，需本工具解密） */
    private void askEncryptFile(final File f) {
        final float den = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * den), (int) (8 * den), (int) (24 * den), (int) (8 * den));
        final EditText pw = new EditText(this);
        pw.setHint("输入加密密码");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText pw2 = new EditText(this);
        pw2.setHint("再次确认密码");
        pw2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pw);
        root.addView(pw2);
        new HfDialogBuilder(this)
                .setTitle("加密文件")
                .setMessage("将 " + f.getName() + " 加密为 " + f.getName() + ".enc\n（AES-256-CBC + HMAC-SHA256 认证，仅本工具可解密）")
                .setView(root)
                .setPositiveButton("加密", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String p = pw.getText().toString();
                        String p2 = pw2.getText().toString();
                        if (p.isEmpty()) { toast("密码不能为空"); return; }
                        if (!p.equals(p2)) { toast("两次密码不一致"); return; }
                        runCrypt("加密", f, p, true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 后台执行加/解密并显示进度对话框。
     * PBKDF2（10 万次迭代）+ 流式加解密耗时明显，绝不可在 UI 线程执行。
     */
    private void runCrypt(final String verb, final File f, final String pw, final boolean enc) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(verb + "文件");
        pd.setMessage(f.getName());
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final int[] last = { -1 };
                FileCrypt.Progress cb = new FileCrypt.Progress() {
                    public void on(long done, long total) {
                        if (total <= 0) return;
                        final int pct = (int) (done * 100 / total);
                        if (pct != last[0]) {
                            last[0] = pct;
                            runOnUiThread(new Runnable() {
                                public void run() { pd.setProgress(pct); }
                            });
                        }
                    }
                };
                try {
                    final File out = enc ? FileCrypt.encrypt(f, pw, cb)
                            : FileCrypt.decrypt(f, pw, cb);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast("已" + verb + ": " + out.getName());
                            refreshAll();
                        }
                    });
                } catch (final SecurityException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast("密码错误或文件被篡改");
                        }
                    });
                } catch (final IllegalArgumentException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast(e.getMessage());
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast(verb + "失败: " + e.getMessage());
                        }
                    });
                }
            }
        }, "file-crypt").start();
    }

    /** 解密 .enc 文件（自家私有格式） */
    private void askDecryptFile(final File f) {
        final EditText pw = new EditText(this);
        pw.setHint("输入解密密码");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new HfDialogBuilder(this)
                .setTitle("解密文件")
                .setMessage("解密 " + f.getName())
                .setView(pw)
                .setPositiveButton("解密", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String p = pw.getText().toString();
                        if (p.isEmpty()) { toast("密码不能为空"); return; }
                        runCrypt("解密", f, p, false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final File f) {
        new HfDialogBuilder(this)
                .setTitle("删除确认")
                .setMessage("将 " + f.getName() + " 移入回收站（可恢复）？")
                .setPositiveButton("移入回收站", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        Trash.Entry e = Trash.moveToTrash(f);
                        toast(e != null ? "已移入回收站" : "删除失败（可能无权限）");
                        refreshAll();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 手势与多选批量操作 ----------

    /** 当前处于多选模式的窗格 */
    private Panel selPanel;

    /**
     * 处理面板上的水平快速滑动（pos = 手指起点所在条目，可能无效）：
     * 左滑 → 选中该条目并进入多选；右滑 → 取消该条目选中（非多选时右滑 = 返回上一级）
     */
    private void onHorizontalSwipe(Panel p, boolean toRight, int pos) {
        if (!toRight) {
            if (pos != AdapterView.INVALID_POSITION) {
                boolean alreadySelMode = p.adapter.selectionMode;
                if (!alreadySelMode) enterSelectionMode(p);
                FileItem it = p.adapter.get(pos);
                if (!it.upEntry && !it.selected) {
                    it.selected = true;
                    p.adapter.notifyDataSetChanged();
                    updateSelDone();
                    if (!alreadySelMode) toast("多选模式：下滑连选，右滑取消");
                }
            } else {
                toggleSelectionMode(p); // 空白处左滑 = 进入/退出多选
            }
            return;
        }
        // 右滑
        if (p.adapter.selectionMode) {
            if (pos != AdapterView.INVALID_POSITION && p.adapter.get(pos).selected) {
                FileItem it = p.adapter.get(pos);
                it.selected = false;
                p.adapter.notifyDataSetChanged();
                updateSelDone();
            } else {
                exitSelection(p);
            }
            return;
        }
        // 压缩包浏览中：右滑 = 压缩包内返回上级（根则退出压缩包），不再走目录历史（v1.15）
        if (p.archiveFile != null) { archGoUp(p); return; }
        File up = p.dir.getParentFile();
        if (up != null && up.canRead()) {
            loadDir(p, up);
            toast("已返回上一级");
        }
    }

    private void toggleSelectionMode(Panel p) {
        if (p.adapter.selectionMode) {
            exitSelection(p);
        } else {
            enterSelectionMode(p);
        }
    }

    private void enterSelectionMode(Panel p) {
        if (p.adapter.selectionMode) return;
        if (left.adapter.selectionMode) exitSelection(left);
        if (right.adapter.selectionMode) exitSelection(right);
        p.adapter.setSelectionMode(true);
        selPanel = p;
        // v1.79.0：多选中的竖直拖动是连续刷选，关掉下拉刷新避免抢事件
        if (p.pull != null) p.pull.setPullEnabled(false);
        findViewById(R.id.selection_bar).setVisibility(View.VISIBLE);
        updateSelDone();
    }

    private void exitSelection(Panel p) {
        p.adapter.setSelectionMode(false);
        if (p.pull != null) p.pull.setPullEnabled(true);
        if (selPanel == p) {
            selPanel = null;
            findViewById(R.id.selection_bar).setVisibility(View.GONE);
        }
    }

    private void updateSelDone() {
        int n = selPanel == null ? 0 : selPanel.adapter.selectedCount();
        ((Button) findViewById(R.id.btn_sel_done)).setText("完成(" + n + ")");
    }

    private void setupSelectionBar() {
        styleSelectionBar();
        findViewById(R.id.btn_sel_all).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (selPanel == null) return;
                selPanel.adapter.selectAll(
                        selPanel.adapter.selectedCount() < selPanel.adapter.size());
                updateSelDone();
            }
        });
        findViewById(R.id.btn_sel_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doBatch(false); }
        });
        findViewById(R.id.btn_sel_move).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doBatch(true); }
        });
        findViewById(R.id.btn_sel_del).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doBatchDelete(); }
        });
        findViewById(R.id.btn_sel_rename).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doBatchRename(); }
        });
        findViewById(R.id.btn_sel_img).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doBatchImage(); }
        });
        findViewById(R.id.btn_sel_done).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (selPanel != null) exitSelection(selPanel); }
        });
    }

    /**
     * v1.76.0：多选栏外观。
     *
     * <p>原来 7 个系统 Button 是「灰底方角 + 各自为政」，挤在一条 4dp 内边距的横条里，
     * 既看不出主次也不知道能点。改为：整条包成一块圆角工具条，按钮全部做成小胶囊，
     * 用语义上色——完成=实心强调色（主操作）、删除=红、其余=描边。</p>
     *
     * <p>7 个按钮同权重平分宽度，所以字号压到 11sp、左右内边距 4dp，
     * 否则「重命名」在 360dp 屏上会被截断。</p>
     */
    private void styleSelectionBar() {
        View bar = findViewById(R.id.selection_bar);
        bar.setBackground(UiDecor.barBg(this));
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) lp;
            int m = UiDecor.dp(this, 6);
            p.leftMargin = m;
            p.rightMargin = m;
            p.topMargin = UiDecor.dp(this, 2);
            p.bottomMargin = m;
            bar.setLayoutParams(p);
        }
        int[] soft = {R.id.btn_sel_all, R.id.btn_sel_copy, R.id.btn_sel_move,
                R.id.btn_sel_rename, R.id.btn_sel_img};
        for (int id : soft) {
            UiDecor.barButton((TextView) findViewById(id), this, UiDecor.KIND_SOFT, 11f);
        }
        UiDecor.barButton((TextView) findViewById(R.id.btn_sel_del), this, UiDecor.KIND_DANGER, 11f);
        UiDecor.barButton((TextView) findViewById(R.id.btn_sel_done), this, UiDecor.KIND_PRIMARY, 11f);
        int[] spaced = {R.id.btn_sel_copy, R.id.btn_sel_move, R.id.btn_sel_del,
                R.id.btn_sel_rename, R.id.btn_sel_img, R.id.btn_sel_done};
        for (int id : spaced) {
            UiDecor.gap((TextView) findViewById(id), this, 2f);
        }
    }

    /** 批量重命名入口（v1.30.0）：仅支持普通文件，压缩包内条目暂不支持 */
    private void doBatchRename() {
        if (selPanel == null) return;
        List<FileItem> sel = selPanel.adapter.getSelectedItems();
        ArrayList<String> paths = new ArrayList<String>();
        int archSkipped = 0;
        for (FileItem it : sel) {
            if (it.isArchEntry()) archSkipped++;
            else if (it.file != null) paths.add(it.file.getAbsolutePath());
        }
        if (paths.isEmpty()) {
            toast("请选择普通文件（暂不支持压缩包内条目重命名）");
            return;
        }
        Intent i = new Intent(this, BatchRenameActivity.class);
        i.putStringArrayListExtra(BatchRenameActivity.EXTRA_PATHS, paths);
        startActivityForResult(i, REQ_BATCH_RENAME);
    }

    /** 批量复制/移动：目标为目录或对面窗格已打开的压缩包；压缩包条目 → 提取 */
    private void doBatch(boolean isMove) {
        if (selPanel == null) return;
        List<FileItem> sel = selPanel.adapter.getSelectedItems();
        if (sel.isEmpty()) { toast("未选中任何项"); return; }
        final Panel dst = (selPanel == left ? right : left);
        List<File> realFiles = new ArrayList<File>();
        List<FileItem> archEntries = new ArrayList<FileItem>();
        for (FileItem it : sel) {
            if (it.isArchEntry()) archEntries.add(it);
            else if (it.file != null) realFiles.add(it.file);
        }
        // 目标窗格打开了压缩包 → 文件写入压缩包 / 压缩包条目互拷（v1.15）
        if (dst.archiveFile != null) {
            if (!ensureArchiveWritable(dst.archiveFile)) return;
            if (!archEntries.isEmpty()) {
                exitSelection(selPanel);
                addEntriesToArchive(selPanel, archEntries, dst, null, isMove);
                return;
            }
            if (!realFiles.isEmpty()) {
                exitSelection(selPanel);
                addToArchive(realFiles.toArray(new File[0]), dst, null, isMove);
                return;
            }
            toast("未选中任何项");
            return;
        }
        // 源窗格是压缩包 → 条目提取到对面目录（移动 = 提取后删除条目）
        if (!archEntries.isEmpty()) {
            exitSelection(selPanel);
            extractEntriesBatch(selPanel, archEntries, dst, isMove);
            return;
        }
        // v1.77.0：改为提交后台任务——有真实进度、可取消、失败不会连带中断
        FileTaskEngine.get().submit(isMove ? FileTask.MOVE : FileTask.COPY, realFiles, dst.dir);
        toast((isMove ? "已加入移动队列 " : "已加入复制队列 ") + realFiles.size()
                + " 项 →\n" + dst.dir.getPath());
        exitSelection(selPanel);
    }

    /** 批量提取压缩包条目到目标窗格目录；alsoDelete=true 时提取后从压缩包删除（仅 ZIP/APK） */
    private void extractEntriesBatch(final Panel p, final List<FileItem> entries,
            final Panel dst, final boolean alsoDelete) {
        toast(alsoDelete ? "提取并删除中…" : "提取中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                int ok = 0;
                List<String> done = new ArrayList<String>();
                try {
                    dst.dir.mkdirs();
                    for (FileItem it : entries) {
                        p.archive.extractEntry(it.archPath(), dst.dir);
                        done.add(it.archPath());
                        ok++;
                    }
                    if (alsoDelete && writableArchive(p.archiveFile) && !done.isEmpty()) {
                        if (ApkEditSession.isApk(p.archiveFile))
                            ApkEditSession.putRemove(MainActivity.this, p.archiveFile, done);
                        else
                            ArchiveWrite.removeEntries(p.archiveFile, done);
                    }
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final int fok = ok;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) toast("部分失败(" + fok + "): " + ferr);
                        else toast((alsoDelete ? "已提取并删除 " : "已提取 ") + fok + " 项");
                        if (alsoDelete && ApkEditSession.isApk(p.archiveFile)
                                && ApkEditSession.hasEdits(p.archiveFile))
                            toast("已写回原包（首次修改自动备份 _bak.apk）");
                        showArchiveDir(p);
                        if (dst.archiveFile == null) loadDir(dst, dst.dir);
                    }
                });
            }
        }, "arch-batch").start();
    }

    /** 批量删除：压缩包条目走 zip 重建；普通文件走回收站 */
    private void doBatchDelete() {
        if (selPanel == null) return;
        final List<FileItem> sel = selPanel.adapter.getSelectedItems();
        if (sel.isEmpty()) { toast("未选中任何项"); return; }
        List<FileItem> archEntries = new ArrayList<FileItem>();
        List<File> realFiles = new ArrayList<File>();
        for (FileItem it : sel) {
            if (it.isArchEntry()) archEntries.add(it);
            else if (it.file != null) realFiles.add(it.file);
        }
        if (!archEntries.isEmpty()) {
            exitSelection(selPanel);
            archDelete(selPanel, archEntries);
            return;
        }
        final Panel p = selPanel;
        new HfDialogBuilder(this)
                .setTitle("批量删除")
                .setMessage("将 " + realFiles.size() + " 项移入回收站（可恢复）？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int ok = 0;
                        for (File f : realFiles) if (Trash.moveToTrash(f) != null) ok++;
                        toast("已移入回收站 " + ok + "/" + realFiles.size() + " 项");
                        exitSelection(p);
                        refreshAll();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 书签 ----------

    private void showBookmarks() {
        final BookmarkStore store = new BookmarkStore(this);
        final List<String> marks = store.all();
        final String cur = active().dir.getAbsolutePath();
        String[] items = new String[marks.size() + 2];
        for (int i = 0; i < marks.size(); i++) items[i] = marks.get(i);
        items[marks.size()] = "+ 添加当前目录: " + cur;
        items[marks.size() + 1] = "- 删除书签";
        new HfDialogBuilder(this)
                .setTitle("书签")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which < marks.size()) {
                            File f = new File(marks.get(which));
                            if (f.isDirectory()) loadDir(active(), f);
                            else toast("目录不存在: " + marks.get(which));
                        } else if (which == marks.size()) {
                            toast(store.add(cur) ? "已添加书签" : "该书签已存在");
                        } else {
                            showBookmarkRemove(store, marks);
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showBookmarkRemove(final BookmarkStore store, final List<String> marks) {
        if (marks.isEmpty()) { toast("暂无书签"); return; }
        new HfDialogBuilder(this)
                .setTitle("点击删除书签")
                .setItems(marks.toArray(new String[marks.size()]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                store.remove(marks.get(which));
                                toast("已删除: " + marks.get(which));
                            }
                        })
                .setNegativeButton("完成", null)
                .show();
    }

    // ---------- 最近打开 ----------

    private void showRecent() {
        final RecentStore store = new RecentStore(this);
        final List<RecentStore.Item> items = store.list();
        if (items.isEmpty()) { toast("暂无最近打开记录"); return; }
        String[] opts = new String[items.size() + 1];
        for (int i = 0; i < items.size(); i++) {
            RecentStore.Item it = items.get(i);
            opts[i] = it.name() + "\n" + FileOps.formatTime(it.time) + "  ·  " + it.path;
        }
        opts[items.size()] = "- 清空记录";
        new HfDialogBuilder(this)
                .setTitle("最近打开")
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w >= items.size()) {
                            store.clear();
                            toast("已清空记录");
                            return;
                        }
                        RecentStore.Item it = items.get(w);
                        File f = new File(it.path);
                        if (!f.exists()) { toast("文件已不存在: " + it.path); return; }
                        onFileClick(f);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------- 搜索结果跳转 ----------

    private void handleJump(Intent intent) {
        if (intent == null) return;
        String dir = intent.getStringExtra("dir");
        if (dir != null) {
            File f = new File(dir);
            if (f.isDirectory()) loadDir(active(), f);
            intent.removeExtra("dir");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleJump(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.85.0：回前台时顺手刷一下桌面小组件的存储数字（没有卡片会直接跳过）
        WidgetProvider.refreshIfExists(this);
        // v1.77.0：任务可能在后台继续，回到前台先接上进度回调再补一次刷新
        FileTaskEngine.get().setListener(taskListener);
        renderTaskBar();
        updatePasteButton();      // 剪贴板里的文件可能在本应用外被删了
        for (Panel p : new Panel[]{left, right}) {
            if (p.archiveFile != null) showArchiveDir(p);
            else if (p.dir != null) loadDir(p, p.dir);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不持有 Activity 引用，避免任务线程把已销毁的界面拖住
        FileTaskEngine.get().setListener(null);
        taskUi.removeCallbacks(hideTaskBar);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BATCH_RENAME && res == RESULT_OK) {
            if (selPanel != null && selPanel.adapter.selectionMode) exitSelection(selPanel);
            refreshAll();
        }
        if (req == REQ_CUSTOM_KEYSTORE && res == RESULT_OK && data != null
                && data.getData() != null && mCustomSignSrc != null) {
            askCustomKeyPassword(data.getData(), mCustomSignSrc);
        }
    }

    /** 自定义签名第 2 步：输入 keystore 密码后执行 signAutoAlias */
    private void askCustomKeyPassword(final android.net.Uri keystoreUri, final File apk) {
        final EditText pass = new EditText(this);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("keystore 密码");
        new HfDialogBuilder(this)
                .setTitle("自定义密钥签名")
                .setMessage("keystore: " + (keystoreUri.getLastPathSegment() != null
                        ? keystoreUri.getLastPathSegment() : "已选择")
                        + "\nAPK: " + apk.getName())
                .setView(pass)
                .setPositiveButton("签名", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String pwd = pass.getText().toString();
                        if (pwd.isEmpty()) { toast("密码不能为空"); return; }
                        final boolean inPlace = mCustomSignInPlace;
                        final ProgressDialog pd = ProgressDialog.show(MainActivity.this,
                                "自定义签名", apk.getName(), true, false);
                        new Thread(new Runnable() {
                            public void run() {
                                String err = null;
                                // inPlace（_edit.apk）→ 临时文件原地覆盖；否则 <名>_csigned.apk
                                File out = inPlace
                                        ? new File(apk.getParentFile(), apk.getName() + ".signtmp")
                                        : new File(apk.getParentFile(),
                                                stripExt(apk.getName()) + "_csigned.apk");
                                try {
                                    byte[] ksBytes = readUriBytes(keystoreUri);
                                    ApkSignerTool.signAutoAlias(ksBytes, pwd.toCharArray(),
                                            apk, out, 21);
                                    if (inPlace) {
                                        // v1.66.4：safeReplace 先原子 rename，失败才
                                        // 备份+复制，不再先 delete 防写失败丢原包
                                        ApkEditSession.safeReplace(out, apk);
                                        try { ApkEditSession.markSigned(apk); }
                                        catch (Throwable ignored) {}
                                    }
                                } catch (Throwable t) {
                                    err = t.getMessage() != null
                                            ? t.getMessage()
                                            : t.getClass().getSimpleName();
                                }
                                final String ferr = err;
                                final File fout = apk;
                                final boolean finPlace = inPlace;
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        pd.dismiss();
                                        if (ferr != null) toast("签名失败: " + ferr);
                                        else {
                                            toast("已签名 → " + (finPlace
                                                    ? fout.getName() + "（原地覆盖）"
                                                    : fout.getName()));
                                            refreshAll();
                                        }
                                    }
                                });
                            }
                        }, "apk-csign").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private byte[] readUriBytes(android.net.Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private long lastBackPress = 0;

    /** 返回键/系统返回手势：先退多选 → 压缩包逐级/退出 → 再逐级返回目录 → 根目录需再按一次才退出 */
    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        if (left.adapter.selectionMode) { exitSelection(left); return; }
        if (right.adapter.selectionMode) { exitSelection(right); return; }
        Panel p = active();
        if (p.archiveFile != null) {          // 压缩包内：逐级返回，根则退出压缩包
            archGoUp(p);
            return;
        }
        // 活动栏在目录模式，但对面窗格开着压缩包 → 先退对面压缩包（v1.15）
        Panel o = other();
        if (o.archiveFile != null) { archGoUp(o); return; }
        File root = Environment.getExternalStorageDirectory();
        String rootPath = root.getAbsolutePath();
        String cur = p.dir.getAbsolutePath();
        if (!cur.equals(rootPath)) {
            // 在 /sdcard 子树内逐级返回；异常位置直接回根
            File up = cur.startsWith(rootPath) ? p.dir.getParentFile() : root;
            if (up != null && up.canRead()) {
                loadDir(p, up);
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            toast("再按一次退出程序");
        }
    }

    private void askNewFolder() {
        final Panel p = active();
        if (p.archiveFile != null) { toast("压缩包浏览中不可新建，请先退出压缩包"); return; }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(this)
                .setTitle("新建文件夹 @ " + p.dir.getName())
                .setView(et)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (!name.isEmpty()) {
                            FileOps.newFolder(p.dir, name);
                            refreshAll();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshAll() {
        for (Panel p : new Panel[]{left, right}) {
            if (p.archiveFile != null) showArchiveDir(p);
            // v1.79.0：push=false —— 刷新不是「进入新目录」，否则每刷一次就往历史栈里塞一条重复路径，
            // ‹ › 要按很多下才退得出去
            else if (p.dir != null) loadDir(p, p.dir, false);
        }
        updateHeader();
    }

    /**
     * v1.79.0 下拉刷新：重读当前目录（压缩包浏览中则重列压缩包当前层）。
     *
     * 读目录是同步的（大目录、root 回退的 ls 可能要几十毫秒到几秒），所以先让转圈画出来
     * 再干活；结束时再停留 200ms，避免一闪而过让人以为没刷。刷新不写历史栈。
     */
    private void refreshPanel(final Panel p) {
        if (p == null || p.pull == null || p.adapter == null) return;
        p.pull.setRefreshing(true);
        p.list.postDelayed(new Runnable() {
            public void run() {
                if (p.adapter.selectionMode) exitSelection(p);
                if (p.archiveFile != null) showArchiveDir(p);
                else if (p.dir != null) loadDir(p, p.dir, false);
                updateHeader();
                updatePasteButton();      // 目录内容变了，剪贴板里失效的源要重新剔除
                p.pull.postDelayed(new Runnable() {
                    public void run() {
                        p.pull.finishRefresh();
                    }
                }, 220);
            }
        }, 90);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        if (req == REQ_STORAGE) {
            boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) toast("未授予存储权限，功能将受限");
            refreshAll();
        }
    }

    // ---------- 存储权限（Android 11+ 分区存储适配）----------

    /** 是否拥有「所有文件访问」权限；API < 30 返回 true（旧权限模型够用） */
    private boolean hasAllFilesAccess() {
        try {
            java.lang.reflect.Method m = android.os.Environment.class
                    .getMethod("isExternalStorageManager");
            return (Boolean) m.invoke(null);
        } catch (Exception e) {
            return true;
        }
    }

    private void requestManageAllFiles() {
        toast("需授予「所有文件访问」权限才能浏览手机存储");
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }
}
