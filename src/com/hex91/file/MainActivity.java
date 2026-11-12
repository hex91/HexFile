package com.hex91.file;

import com.hex91.file.utils.*;
import com.hex91.file.controller.*;
import com.hex91.file.ui.*;
import com.hex91.file.net.*;
import com.hex91.file.apk.*;
import com.hex91.file.R;
import com.hex91.file.fs.*;
import com.hex91.file.core.*;
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
import android.widget.ProgressBar;
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
 * BaseActivity 职责由本类直接承担（不再有 FeatureRegistry 那层注册表——v1.96.0 删除，
 * 它从未被任何代码引用，留着只会误导后来人）。
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
    /** v1.99.0（O-03）：吞掉 onCreate 之后那一次重复的目录读取，见 onResume */
    private boolean skipResumeReload = true;

    /** 单侧窗格状态（对标 MT 的左右窗口）；v2.0.0（O-15 刀1）搬到 Pane */
    private Pane left = new Pane();
    private Pane right = new Pane();

    /**
     * v2.0.0（O-15 刀2）：目录加载 / 刷新 / 历史。宿主只提供能力转发，逻辑在控制器里。
     * Host 里的 {@code ui()} 就是下面那个 {@code taskUi} —— 控制器不自己 new Handler。
     */
    private final DirectoryController dirCtl = new DirectoryController(new DirectoryController.Host() {
        public android.content.Context ctx() { return MainActivity.this; }

        public Pane[] panes() { return new Pane[]{left, right}; }

        public Pane active() { return MainActivity.this.active(); }

        public boolean alive() { return !isFinishing() && !isDestroyed(); }

        public void toast(String s) { MainActivity.this.toast(s); }

        public void exitSelection(Pane p) { MainActivity.this.exitSelection(p); }

        public void updateHeader() { MainActivity.this.updateHeader(); }

        public void updatePasteButton() { MainActivity.this.updatePasteButton(); }

        public void setActive(Pane p) { MainActivity.this.setActive(p); }

        public void closeArchiveQuiet(Pane p) { MainActivity.this.closeArchiveQuiet(p); }

        public void showArchiveDir(Pane p) { MainActivity.this.showArchiveDir(p); }

        public void archGoUp(Pane p) { MainActivity.this.archGoUp(p); }

        public android.os.Handler ui() { return taskUi; }
    });

    /**
     * v2.0.0（O-15 刀3）：压缩包内浏览。同样只转发能力，逻辑与线程模型都在控制器里。
     * {@code ui(r)} 就是 {@code runOnUiThread} 加一道 alive 检查（原来退出页面后的 toast 会白跑）。
     */
    private final ArchiveController archCtl = new ArchiveController(new ArchiveController.Host() {
        public android.content.Context ctx() { return MainActivity.this; }

        public boolean alive() { return !isFinishing() && !isDestroyed(); }

        public void toast(String s) { MainActivity.this.toast(s); }

        public void ui(Runnable r) {
            if (isFinishing() || isDestroyed()) return;
            runOnUiThread(r);
        }

        public void open(Intent i) { startActivity(i); }

        public Pane other() { return MainActivity.this.other(); }

        public void loadDir(Pane p, File dir, boolean push) { dirCtl.loadDir(p, dir, push); }

        public void refreshAll() { MainActivity.this.refreshAll(); }

        public void updateHeader() { MainActivity.this.updateHeader(); }

        public void exitSelection(Pane p) { MainActivity.this.exitSelection(p); }

        public void showTextDialog(String title, String text) {
            MainActivity.this.showTextDialog(title, text);
        }

        public void showGridMenu(String title, List<String> ops, List<Integer> acts,
                GridAction cb) {
            MainActivity.this.showGridMenu(title, ops, acts, cb);
        }

        public boolean ensureArchiveWritable(File f) {
            return MainActivity.this.ensureArchiveWritable(f);
        }

        public void convertRarToZip(File f) { MainActivity.this.convertRarToZip(f); }

        public void writeTree(File dir, String prefix, File archive, boolean apkMode)
                throws java.io.IOException {
            MainActivity.this.writeTree(dir, prefix, archive, apkMode);
        }
    });

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
        restorePanesOrLoad(b);
        taskCtl.setupJournal();
        // v1.80.0：视图模式也持久化（和排序/隐藏文件同一份偏好），重启保持
        viewMode = getSharedPreferences(PREF_UI, MODE_PRIVATE).getInt(KEY_VIEW, VIEW_LIST);
        applyViewMode();

        // 底部 MT 工具栏：‹ 历史 › ＋ 新建 ⇄ 交换 ↑ 上级
        findViewById(R.id.btn_up).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Pane p = active();
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
        drawerCtl.setupDrawer();

        setupSelectionBar();
        taskCtl.setup();
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
        final Pane p = active();
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

    /** 主线程 Handler：任务条与读目录的 20 秒看门狗共用（v2.0.0 O-15 刀4 起两个控制器共享） */
    private final android.os.Handler taskUi =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * v2.0.0（O-15 刀4）：任务条 / 任务中心入口 / 「上次有任务被中断」提示。
     * 监听器的接上与摘下改成 {@code attach()/detach()}，销毁时 {@code destroy()} 收队列。
     */
    private final TaskUiController taskCtl = new TaskUiController(new TaskUiController.Host() {
        public android.app.Activity activity() { return MainActivity.this; }

        public android.content.Context ctx() { return MainActivity.this; }

        public View find(int id) { return findViewById(id); }

        public void toast(String s) { MainActivity.this.toast(s); }

        public void refreshAll() { MainActivity.this.refreshAll(); }

        public android.os.Handler ui() { return taskUi; }

        public void post(Runnable r) { listLeft.post(r); }

        public boolean alive() { return !isFinishing() && !isDestroyed(); }

        public void openPanel() {
            startActivity(new Intent(MainActivity.this, TaskCenterActivity.class));
        }
    });

    // ============================================================ 左侧抽屉（v1.41.0）

    /** v2.0.0（O-15 刀5）：抽屉的视图引用与 16 个入口都搬进 DrawerController，Activity 只管转发 */
    private final DrawerController drawerCtl = new DrawerController(new DrawerController.Host() {
        public android.content.Context ctx() { return MainActivity.this; }

        public View find(int id) { return findViewById(id); }

        public Pane active() { return MainActivity.this.active(); }

        public void loadDir(Pane p, File dir, boolean push) { dirCtl.loadDir(p, dir, push); }

        public void toast(String s) { MainActivity.this.toast(s); }

        public void showBookmarks() { MainActivity.this.showBookmarks(); }

        public void open(Intent i) { startActivity(i); }

        public void ui(Runnable r) {
            if (isFinishing() || isDestroyed()) return;
            runOnUiThread(r);
        }
    });

    /** 返回键先关抽屉（实现在 DrawerController） */
    private void closeDrawer() { drawerCtl.closeDrawer(); }

    /** v2.0.0（O-15 刀6）：属性 / 权限 / 时间 / 所有者 / 哈希弹窗，实现在 PropsController */
    private final PropsController propsCtl = new PropsController(new PropsController.Host() {
        public android.content.Context ctx() { return MainActivity.this; }

        public void toast(String s) { MainActivity.this.toast(s); }

        public void refreshAll() { MainActivity.this.refreshAll(); }

        public void copyToClipboard(String label, String value) {
            MainActivity.this.copyToClipboard(label, value);
        }

        public void ui(Runnable r) {
            if (isFinishing() || isDestroyed()) return;
            runOnUiThread(r);
        }

        public void askRename(File f) { MainActivity.this.askRename(f); }

        public void confirmDelete(File f) { MainActivity.this.confirmDelete(f); }
    });










    /** ⋮ 主菜单（对标 MT 溢出菜单，锚定在右上角 ⋮ 按钮往下弹出） */
    private void showMoreMenu() {
        final Pane p = active();
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
        // v1.91.0：播放网络地址（m3u8 / mpd / rtsp / 直链）
        menu.add(Menu.NONE, 14, 9, "播放链接 / 串流…");
        // v1.92.0：把 m3u8 拉成单个 MP4（下载 + 合并 + 换容器）
        menu.add(Menu.NONE, 15, 9, "下载视频（m3u8 → MP4）…");
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
                        for (Pane q : new Pane[]{left, right}) q.adapter.showHidden = nh;
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
                    case 14:
                        promptPlayUrl();
                        break;
                    case 15:
                        startActivity(new Intent(MainActivity.this, HlsDownloadActivity.class));
                        break;
                }
                return true;
            }
        });
        popup.show();
    }

    /** 排序方式：名称 / 大小 / 修改时间（两栏共用，会记住选择） */
    private void askSortMode() {
        final Pane p = active();
        new HfDialogBuilder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(
                        new String[]{"按名称", "按大小", "按修改时间"}, p.adapter.sortMode,
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                saveSortMode(which);
                                for (Pane q : new Pane[]{left, right}) q.adapter.sortMode = which;
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
        final Pane p = active();
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

    /** ‹ › 历史导航（实现在 DirectoryController） */
    private void jumpHistory(int delta) {
        dirCtl.jumpHistory(delta);
    }

    private Pane active() { return left.active ? left : right; }
    private Pane other() { return left.active ? right : left; }

    /**
     * ⇄ 交换窗口：完整交换两侧状态（目录 / 压缩包浏览 / 历史栈 / 适配器）。
     * v1.15 起不再强制关闭压缩包——交换后压缩包仍在原屏幕侧浏览，活动栏跟随屏幕侧不变。
     */
    private void swapPanes() {
        if (left.adapter.selectionMode) exitSelection(left);
        if (right.adapter.selectionMode) exitSelection(right);
        Pane t = left;
        left = right;
        right = t;
        // Pane 自带 list/adapter 配对，交换引用后两侧各自仍然成套；
        // active 标记随 Pane 移动 = 屏幕侧焦点不变
        for (Pane p : new Pane[]{left, right}) {
            if (p.archiveFile != null) showArchiveDir(p);
            else if (p.dir != null) loadDir(p, p.dir, false);
        }
        updateHeader();
    }

    private void setupPanel(final Pane p) {
        p.adapter = new FileAdapter(this);
        p.adapter.rootDir = Environment.getExternalStorageDirectory();
        // v1.78.0：排序方式与「显示 . 文件」原来是内存态，重启就回到默认值。
        // 现在存 SharedPreferences；两栏共用一套设置，切换目录也不会被重置。
        android.content.SharedPreferences sp =
                getSharedPreferences(PREF_UI, MODE_PRIVATE);
        p.adapter.sortMode = sp.getInt(KEY_SORT, FileAdapter.SORT_NAME);
        p.adapter.showHidden = sp.getBoolean(KEY_HIDDEN, false);
        p.list.setAdapter(p.adapter);
        // v2.0.1（F-16）：告诉适配器「你画在哪个列表上」，勾选一行才能只重画那一行
        p.adapter.bindList(p.list);

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
                                    p.adapter.refreshRow(pos);
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

        // v1.88.0：拖放目标（列表与网格共用一个外层容器，两种视图都能接住）
        setupDropTarget(p);
        if (p.grid != null) {
            p.grid.setAdapter(p.gridAdapter);
            p.adapter.bindGrid(p.grid);   // v2.0.1：网格侧的单行刷新入口
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

    // ============================================================ 双栏拖拽（v1.88.0）

    /**
     * 长按条目 → 抬起手指即可拖到另一栏放下（复制/移动）。
     *
     * <p>为什么用系统 drag 而不是自己跟 MotionEvent：ListView 自己要吃滚动、
     * 我们又在同一条 touch 流上挂了手势检测器（左右滑选中等）。系统 drag 会
     * 给原视图发 ACTION_CANCEL 并接管后续事件，等于把这两套逻辑天然隔开，
     * 不用在 onScroll 里到处判断「现在到底是在拖还是在滑」。</p>
     */
    private final List<File> mDragSrcs = new ArrayList<File>();
    /** 拖拽发起窗格（只有它会负责在拖拽结束时收尾） */
    private Pane mDragFrom;
    /** 拖拽发起条目（未投放时用它弹出原来的长按菜单） */
    private FileItem mDragItem;
    /** 这一次拖拽是否已成功投放 */
    private boolean mDropped;

    /** 给一个窗格挂上「可接收拖放」的监听（列表/网格外层容器整体作为目标） */
    private void setupDropTarget(final Pane p) {
        if (p.pull == null) return;
        p.pull.setOnDragListener(new View.OnDragListener() {
            public boolean onDrag(View v, android.view.DragEvent ev) {
                switch (ev.getAction()) {
                    case android.view.DragEvent.ACTION_DRAG_STARTED:
                        // 只接本界面发起的拖拽：别的应用拖进来的文本/文件在这里没有意义
                        return !mDragSrcs.isEmpty();
                    case android.view.DragEvent.ACTION_DRAG_ENTERED:
                        paintDropTarget(p, true);
                        return true;
                    case android.view.DragEvent.ACTION_DRAG_EXITED:
                        paintDropTarget(p, false);
                        return true;
                    case android.view.DragEvent.ACTION_DROP: {
                        paintDropTarget(p, false);
                        boolean accepted = handleDrop(p);
                        if (accepted) mDropped = true;
                        return accepted;
                    }
                    case android.view.DragEvent.ACTION_DRAG_ENDED:
                        paintDropTarget(p, false);
                        if (p == mDragFrom) finishDrag();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    /** 拖拽收尾：清了状态，未投放时补弹长按菜单（拖拽是从长按发起的，不能让它白按） */
    private void finishDrag() {
        final File f = (mDragItem != null && !mDragItem.isArchEntry() && !mDragItem.upEntry)
                ? mDragItem.file : null;
        final boolean dropped = mDropped;
        mDragSrcs.clear();
        mDragFrom = null;
        mDragItem = null;
        mDropped = false;
        if (dropped || f == null) return;
        // 延后一点：ACTION_DRAG_ENDED 之后系统还要收尾，立刻弹窗会被紧接着的触摸事件点掉
        taskUi.postDelayed(new Runnable() {
            public void run() { showOpsMenu(f); }
        }, 80);
    }

    /** 拖拽经过时高亮目标栏：能放=强调色，不能放=错误色（13% 透明，不遮内容） */
    private void paintDropTarget(Pane p, boolean on) {
        if (p.pull == null) return;
        if (!on) {
            p.pull.setBackgroundColor(0x00000000);
            return;
        }
        DragPlan.Check ck = DragPlan.check(mDragSrcs, p.archiveFile == null ? p.dir : null);
        int c = ck.ok() ? ThemeManager.accent(this) : ThemeManager.err(this);
        p.pull.setBackgroundColor((c & 0x00FFFFFF) | 0x22000000);
    }

    /** 落到某个窗格：先判定，再让用户选复制还是移动 */
    private boolean handleDrop(final Pane dst) {
        if (dst.archiveFile != null) {
            toast("暂不支持拖进压缩包，请用长按菜单里的「复制到对面压缩包」");
            return false;
        }
        final DragPlan.Check ck = DragPlan.check(new ArrayList<File>(mDragSrcs), dst.dir);
        if (!ck.ok()) {
            // 拖回自己这一栏（长按后没动就松手）不算失败：接着会弹长按菜单，
            // 这时再弹一句「已经在目标目录里了」纯属噪音
            boolean backHome = dst == mDragFrom && ck.skippedSame > 0 && ck.skippedSelf == 0;
            if (!backHome && !ck.reason.isEmpty()) toast(ck.reason);
            return false;
        }
        final File dir = dst.dir;
        String extra = "";
        if (ck.skippedSame > 0) extra = "\n（" + ck.skippedSame + " 项已在目标目录，已跳过）";
        new HfDialogBuilder(this)
                .setTitle("拖放 " + DragPlan.title(ck, dir))
                .setMessage("目标：" + dir.getPath() + extra)
                .setPositiveButton("复制到此处", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        submitDrop(ck.items, dir, false);
                    }
                })
                .setNeutralButton("移动到此处", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        submitDrop(ck.items, dir, true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }

    /** 拖放落地：走任务队列，所以有进度条、可取消、失败不连带 */
    private void submitDrop(List<File> items, File dir, boolean move) {
        if (items.isEmpty()) return;
        FileTaskEngine.get().submit(move ? FileTask.MOVE : FileTask.COPY, items, dir);
        toast("已加入" + (move ? "移动" : "复制") + "队列 " + items.size() + " 项 →\n" + dir.getPath());
        if (selPanel != null) exitSelection(selPanel);
    }

    /** 发起拖拽；系统拒绝时（极少）退回直接弹菜单，不让长按失灵 */
    private void startItemDrag(final Pane p, int pos, final List<File> srcs) {
        if (srcs.isEmpty()) {
            FileItem it = p.adapter.get(pos);
            if (!it.isArchEntry() && !it.upEntry) showOpsMenu(it.file);
            return;
        }
        mDragSrcs.clear();
        mDragSrcs.addAll(srcs);
        mDragFrom = p;
        mDragItem = p.adapter.get(pos);
        mDropped = false;

        View anchor = fileView(p);
        View itemView = itemViewAt(p, pos);
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(itemView != null ? itemView : anchor);
        ClipData clip = ClipData.newPlainText("HexFile", srcs.get(0).getAbsolutePath());
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            started = anchor.startDragAndDrop(clip, shadow, null, 0);
        } else {
            started = anchor.startDrag(clip, shadow, null, 0);
        }
        if (!started) {
            FileItem it = mDragItem;
            mDragSrcs.clear();
            mDragFrom = null;
            mDragItem = null;
            if (it != null && !it.isArchEntry() && !it.upEntry) showOpsMenu(it.file);
        }
    }

    /** 当前视图模式下真正在显示的列表控件（网格模式下 list 是 GONE，不能作为拖拽发起者） */
    private View fileView(Pane p) {
        if (viewMode == VIEW_GRID && p.grid != null) return p.grid;
        return p.list;
    }

    /** 取某个下标的行视图（拖拽影子用）；不可见时返回 null */
    private View itemViewAt(Pane p, int pos) {
        android.widget.AdapterView<?> av = (viewMode == VIEW_GRID && p.grid != null)
                ? p.grid : (android.widget.AdapterView<?>) p.list;
        int first = av.getFirstVisiblePosition();
        int idx = pos - first;
        if (idx < 0) return null;
        return av.getChildAt(idx);
    }

    /** 点击条目：列表与网格共用（两侧下标都指向同一个 FileAdapter） */
    private void handleItemClick(Pane p, int i) {
        setActive(p);
        FileItem it = p.adapter.get(i);
        // v1.40.0：列表顶部 .. 返回项（多选中点击 = 退出多选并返回，loadDir 内处理）
        // v1.97.0：这一条必须在 loading 判断之前 —— 读取中原先把所有点击都拦了，
        // 于是「进了一个读得很慢的目录」就退不出来（只能等它读完或杀进程）。
        // 返回上级是唯一的逃生口，任何时候都得留着。
        if (it != null && it.upEntry) {
            File up = p.dir == null ? null : p.dir.getParentFile();
            if (up != null && (up.canRead() || RootUtil.isRootAvailable()))
                loadDir(p, up);
            return;
        }
        // v1.96.0：读目录期间列表里可能还是上一个目录的条目，此时点击会打开错文件
        if (p.adapter.isLoading()) return;
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
    private boolean handleItemHold(Pane p, int i) {
        setActive(p);
        if (p.adapter.isLoading()) return true;   // v1.96.0：同上，读取中不响应长按
        FileItem it = p.adapter.get(i);
        if (it.upEntry) return true;    // .. 返回项无操作菜单
        if (p.adapter.selectionMode) {
            // v1.88.0：多选态下长按**已选中**的条目 = 把整批拖到另一栏（长按未选中的仍然只做勾选，
            // 免得刷选时误拖）；单独想拖一项就退出多选再长按
            if (it.selected && !it.isArchEntry()) {
                List<File> sel = new ArrayList<File>();
                for (FileItem s : p.adapter.getSelectedItems()) {
                    if (!s.isArchEntry() && s.file != null) sel.add(s.file);
                }
                startItemDrag(p, i, sel);
                return true;
            }
            p.adapter.toggle(i);
            updateSelDone();
            return true;
        }
        if (it.isArchEntry()) {
            if (!it.isArchUp()) showArchOpsMenu(p, it);
            return true;
        }
        // 长按即进入拖拽：拖到对面栏放下 = 复制/移动；原地松手照旧弹出功能菜单
        List<File> one = new ArrayList<File>(1);
        one.add(it.file);
        startItemDrag(p, i, one);
        return true;
    }

    private void setActive(Pane p) {
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
     * 交换窗格（⇄）仍然可用：Pane 自带视图引用，交换后活动栏照样占满。
     */
    private void applyViewMode() {
        boolean grid = viewMode == VIEW_GRID;
        Pane a = active();
        for (Pane p : new Pane[]{left, right}) {
            if (p.list != null) p.list.setVisibility(grid ? View.GONE : View.VISIBLE);
            if (p.grid != null) p.grid.setVisibility(grid ? View.VISIBLE : View.GONE);
            if (p.pull != null) p.pull.setVisibility(grid && p != a ? View.GONE : View.VISIBLE);
        }
        View div = findViewById(R.id.pane_divider);
        if (div != null) div.setVisibility(grid ? View.GONE : View.VISIBLE);
    }

    // ---- 目录加载 / 刷新 / 历史：实现搬去 DirectoryController（v2.0.0 O-15 刀2）----
    // 下面几个都是同名壳方法，签名一字未改，所以 25 处调用点零改动。

    private void loadDir(Pane p, File dir) {
        dirCtl.loadDir(p, dir);
    }

    private boolean loadDir(Pane p, File dir, boolean push) {
        return dirCtl.loadDir(p, dir, push);
    }

    /** 顶部深色栏：显示活动栏路径 + 统计（文件夹/文件/储存），对标 MT */
    private void updateHeader() {
        if (headerPath == null) return;
        Pane p = active();
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
        // v1.99.0（O-06）：路径已经切到新目录，计数却还是上一层的。
        // 大目录/root 回退时这段时间有几百毫秒到几秒，用户看到「路径是 A，文件数还是 B 的」
        // 会以为读错了目录，甚至照着旧数字做删除。读取期间明确写「读取中…」，
        // 顺便跳过下面的 usageOf（那是一次 statfs，读目录期间没必要连着做）。
        if (p.adapter.isLoading()) {
            headerStats.setText("读取中…");
            return;
        }
        // v1.96.0：计数由 DirReader 读目录时顺带算好（原来这里每次再遍历一遍适配器，
        // 几千项时和读目录叠加成双份开销，而且读目录已经不在主线程了，这里也不能留 O(n)）
        int dirs = p.dirCount, files = p.fileCount;
        String store = "";
        // v1.72.0：根目录/系统分区统一按 /data 统计，避免顶层显示「987M/987M」
        long[] u = StorageStat.usageOf(p.dir.getAbsolutePath());
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
        } else if (lower.endsWith(".ts") && looksLikeMpegTs(f)) {
            // .ts 在本工程里默认是 TypeScript 源码，只有真的 MPEG-TS 才当视频
            openVideo(f);
        } else if (MimeUtil.isVideo(lower) || MimeUtil.isPlaylist(lower)) {
            // v1.91.0：视频/流清单交给内置播放器（以前一律交给系统，等于「管理器里看不了视频」）
            openVideo(f);
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

    // ============================================================ 内置播放器（v1.91.0）

    /**
     * 字节嗅探 MPEG-TS：188 字节一个包、包头固定 0x47。
     * <p>不这么做的话只有两个选择：把 .ts 一律当视频（点 TypeScript 源码会进播放器），
     * 或一律当文本（真的 TS 录像打不开）。两种都能用，但都别扭。</p>
     */
    static boolean looksLikeMpegTs(File f) {
        if (f == null || !f.isFile() || f.length() < 376) return false;
        byte[] h = FileInspect.readHead(f, 377);
        if (h == null || h.length < 377) return false;
        return h[0] == 0x47 && h[188] == 0x47 && h[376] == 0x47;
    }

    /** 用内置播放器打开本地视频/流清单 */
    /**
     * TS → MP4 转封装（v1.92.0）。
     *
     * <p>为什么不放到下载页里：本地已有的 TS（录屏、别的工具下的切片、行车记录仪）
     * 不需要「下载」，只需要换个容器。走同一个 {@link TsRemuxer}，
     * 但入口放在长按菜单里更符合「就地操作这个文件」的直觉。</p>
     */
    private void remuxToMp4(final File src) {
        if (src == null || !src.isFile()) return;
        final File dst = TsRemuxer.mp4Sibling(src);
        final float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * den);
        box.setPadding(pad, (int) (6 * den), pad, 0);
        final TextView tv = new TextView(this);
        tv.setText("正在读取索引…");
        tv.setTextSize(13);
        final ProgressBar pb = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (4 * den));
        plp.topMargin = (int) (10 * den);
        box.addView(tv);
        box.addView(pb, plp);
        final AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle("转为 MP4（只换容器，不重新编码）")
                .setView(box)
                .create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();

        new Thread(new Runnable() {
            public void run() {
                try {
                    long size = TsRemuxer.remuxToMp4(src, dst, new TsRemuxer.Progress() {
                        public void onProgress(final long us, final long total) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (isFinishing()) return;
                                    long sec = us / 1000000L;
                                    if (total > 0) {
                                        int pct = (int) Math.min(100, us * 100 / total);
                                        pb.setProgress(pct);
                                        tv.setText("已处理 " + sec + " 秒 · " + pct + "%");
                                    } else {
                                        tv.setText("已处理 " + sec + " 秒");
                                    }
                                }
                            });
                        }
                    });
                    final long fs = size;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            dlg.dismiss();
                            refreshAll();
                            toast("已生成 " + dst.getName() + "（"
                                    + (fs / 1024 / 1024) + " MB）");
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            dlg.dismiss();
                            String m = t.getMessage();
                            toast("转换失败：" + (m == null ? t.getClass().getSimpleName() : m));
                        }
                    });
                }
            }
        }, "ts-remux").start();
    }

    private void openVideo(File f) {
        startActivity(new Intent(this, VideoPlayerActivity.class)
                .putExtra(VideoPlayerActivity.EXTRA_FILE, f.getAbsolutePath())
                .putExtra(VideoPlayerActivity.EXTRA_TITLE, f.getName()));
    }

    /**
     * 「播放链接 / 串流…」：手输或粘贴地址。
     * <p>输入框预填剪贴板里的第一个地址 —— 用户通常是从浏览器/聊天里复制完再切过来，
     * 预填能省掉一次长按粘贴；剪贴板里没有合法地址就留空。</p>
     */
    private void promptPlayUrl() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("http(s)://…/index.m3u8  或  rtsp://…");
        input.setTextSize(13);
        input.setSingleLine(true);
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence txt = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                String url = StreamUrl.firstUrl(txt == null ? null : txt.toString());
                if (url != null) input.setText(url);
            }
        } catch (Throwable ignore) {
        }
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new HfDialogBuilder(this)
                .setTitle("播放网络地址 / 串流")
                .setView(input)
                .setMessage("支持 m3u8（HLS）、mpd（DASH）、rtsp 摄像头、直链视频。\n"
                        + "m3u8 会自动解析多档画质，可在播放器里锁定。")
                .setPositiveButton("播放", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String raw = input.getText() == null ? "" : input.getText().toString();
                        String url = StreamUrl.normalize(raw);
                        if (url == null) {
                            toast("不是可播放的地址（只支持 http/https/rtsp）");
                            return;
                        }
                        if (StreamUrl.kindOf(url) == StreamUrl.KIND_RTMP) {
                            toast("RTMP 需要额外解码扩展，当前版本请改用 m3u8/HLS 地址");
                            return;
                        }
                        startActivity(new Intent(MainActivity.this, VideoPlayerActivity.class)
                                .putExtra(VideoPlayerActivity.EXTRA_URL, url));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
    public static boolean writableArchive(File f) {
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


    // ---- 压缩包内浏览：实现搬去 ArchiveController（v2.0.0 O-15 刀3）----
    // 下面都是同名壳方法（签名一字未改）：目录控制器、批量操作和长按菜单照旧这么调。

    private void closeArchiveQuiet(Pane p) { archCtl.closeArchiveQuiet(p); }

    private void openArchiveInPane(Pane p, File f) { archCtl.openArchiveInPane(p, f); }

    private void promptArchivePasswordSave(File f) { archCtl.promptArchivePasswordSave(f); }

    private boolean archPwError(Pane p, Throwable t) { return archCtl.archPwError(p, t); }

    private void showArchiveDir(Pane p) { archCtl.showArchiveDir(p); }

    private void archGoUp(Pane p) { archCtl.archGoUp(p); }

    private void onArchItemClick(Pane p, FileItem it) { archCtl.onArchItemClick(p, it); }

    private void extractArchAll(Pane p) { archCtl.extractArchAll(p); }

    private void showArchOpsMenu(Pane p, FileItem it) { archCtl.showArchOpsMenu(p, it); }

    private void addEntriesToArchive(Pane src, List<FileItem> entries, Pane dst,
            String nameOverride, boolean move) {
        archCtl.addEntriesToArchive(src, entries, dst, nameOverride, move);
    }

    private void archDelete(Pane p, List<FileItem> items) { archCtl.archDelete(p, items); }


    public static byte[] readAllBytes(File f) throws Exception {
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


    /**
     * v1.99.2（R-06）：写回失败的说明。
     *
     * <p>压缩包写回失败时 {@link AtomicReplace} 会把原内容挪成 {@code .bakrepl}、把新内容留在临时文件，
     * 这些路径记在 {@code lastError} 里 —— 必须拼进提示，否则用户只看到「写入失败」，
     * 同时在自己目录里发现一个不认识的 {@code xxx.bakrepl}。</p>
     */
    public static String writeFailMsg(String what) {
        String why = AtomicReplace.lastError;
        return why == null ? what : what + "（" + why + "）";
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
                    // v1.66.4 / v1.99.1：原子替换统一在 AtomicReplace（先 rename，失败才备份+复制）
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
    private void addToArchive(final File[] sources, final Pane archPane,
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
                                err = writeFailMsg("写入失败: " + root);
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
                    throw new RuntimeException(writeFailMsg("写入失败: " + entry));
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
    private void askAddToArchive(final File src, final Pane archPane, final boolean move) {
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

    /** 属性/权限/哈希弹窗：实现在 PropsController（v2.0.0 O-15 刀6） */
    private void showProps(File f) { propsCtl.showProps(f); }

    private void showOpsMenu(final File f) {
        if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) {
            showApkOpsMenu(f);       // APK 用专属功能菜单（对标 MT）
            return;
        }
        final Pane dst = other();
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
                A_CLIP_C = 48, A_CLIP_M = 49, A_PLAYER = 50, A_TSMP4 = 51, A_HLSD = 52;
        final java.util.List<String> ops = new ArrayList<String>();
        final java.util.List<Integer> acts = new ArrayList<Integer>();
        // v1.91.0：视频与流清单直接给「播放」（内置播放器），不必先绕「打开方式」
        if (isFile && (MimeUtil.isVideo(f.getName().toLowerCase())
                || MimeUtil.isPlaylist(f.getName().toLowerCase()))) {
            ops.add("播放"); acts.add(A_PLAYER);
        }
        // v1.92.0：TS 录像/切片换容器成 MP4（不重编码，秒级完成）
        if (isFile && f.getName().toLowerCase().endsWith(".ts") && looksLikeMpegTs(f)) {
            ops.add("转为 MP4"); acts.add(A_TSMP4);
        }
        // 本地 m3u8（整目录下好的切片）→ 打开下载页，路径已预填
        if (isFile && f.getName().toLowerCase().endsWith(".m3u8")) {
            ops.add("合并为 MP4"); acts.add(A_HLSD);
        }
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
        final Pane dst = other();
        // 动作码（APK 专属，与 runFileAction 对应）
        final int A_DEX2SMALI = 10, A_EXTRACT_DEX = 11,
                A_SIGN = 13, A_COPY = 3, A_REN = 5, A_PROP = 8, A_DEXPP = 15, A_ARSCPP = 16, A_MFPP = 17,
                A_TOZIP_C = 18, A_TOZIP_M = 19, A_SIGN_APK = 20, A_APK_COMPARE = 22, A_APK_CLONE = 23,
                A_SIGKILL = 26, A_INFO = 32, A_UNPACK = 33, A_SIGN_CUSTOM = 34, A_RM_SIGN = 35,
                A_DEL = 7, A_IMGBATCH = 37, A_ARSCPP2 = 40, A_SHARE = 45, A_LAN = 46, A_OPEN = 47,
                A_CLIP_C = 48, A_CLIP_M = 49, A_PLAYER = 50;
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
    private void runFileAction(int act, File f, Pane dst) {
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
            case 50:                              // v1.91.0：内置播放器
                openVideo(f);
                break;
            case 51:                              // v1.92.0：TS → MP4（转封装）
                remuxToMp4(f);
                break;
            case 52:                              // v1.92.0：本地 m3u8 → 下载/合并页
                startActivity(new Intent(MainActivity.this, HlsDownloadActivity.class)
                        .putExtra(HlsDownloadActivity.EXTRA_URL, f.getAbsolutePath()));
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

    /**
     * 递归求目录大小（压缩前估算用）。
     *
     * <p>v1.97.0：原来自己写的递归既有软链接跟进的问题，也没有深度上限 ——
     * 遇到链接环会 StackOverflow 把整个进程带走。统一走 {@link TreeOps#size}。</p>
     */
    private static long dirSize(File d) {
        return TreeOps.size(d);
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
    public static String stripExt(String name) {
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
                        if (ok) {
                            toast("已压缩: " + out.getName());
                        } else {
                            // 压缩是「新建文件」，不经过原子替换，所以这里没有 lastError 可用；
                            // 最常见的两种失败：源全是软链接（会被跳过，不写进包）、目标不可写
                            toast("压缩失败（全是软链接/无法读取，或目标不可写）");
                        }
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
    private Pane selPanel;

    /**
     * 处理面板上的水平快速滑动（pos = 手指起点所在条目，可能无效）：
     * 左滑 → 选中该条目并进入多选；右滑 → 取消该条目选中（非多选时右滑 = 返回上一级）
     */
    private void onHorizontalSwipe(Pane p, boolean toRight, int pos) {
        if (!toRight) {
            if (pos != AdapterView.INVALID_POSITION) {
                boolean alreadySelMode = p.adapter.selectionMode;
                if (!alreadySelMode) enterSelectionMode(p);
                FileItem it = p.adapter.get(pos);
                if (!it.upEntry && !it.selected) {
                    it.selected = true;
                    p.adapter.refreshRow(pos);   // v2.0.1：只重画这一行
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
                p.adapter.refreshRow(pos);   // v2.0.1：只重画这一行
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

    private void toggleSelectionMode(Pane p) {
        if (p.adapter.selectionMode) {
            exitSelection(p);
        } else {
            enterSelectionMode(p);
        }
    }

    private void enterSelectionMode(Pane p) {
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

    private void exitSelection(Pane p) {
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
        final Pane dst = (selPanel == left ? right : left);
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
    private void extractEntriesBatch(final Pane p, final List<FileItem> entries,
            final Pane dst, final boolean alsoDelete) {
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
        final Pane p = selPanel;
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
        taskCtl.attach();
        updatePasteButton();      // 剪贴板里的文件可能在本应用外被删了
        // v1.99.0（O-03）：onCreate 里 restorePanesOrLoad 已经把两栏读起来了，
        // 紧接着的第一次 onResume 不能再读一遍 —— 冷启动时同一个目录读两次，
        // 第二次还会把第一次的代次推走（第一次白读，慢目录上等于启动多等一倍）。
        if (skipResumeReload) {
            skipResumeReload = false;
            return;
        }
        for (Pane p : new Pane[]{left, right}) {
            if (p.archiveFile != null) showArchiveDir(p);
            // v1.99.0（O-04）：三参 push=false。回前台不是「进入新目录」，
            // 用两参版本会往历史栈里补当前路径，用户从子页面回来再按 ‹ 就退不动。
            else if (p.dir != null) loadDir(p, p.dir, false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不持有 Activity 引用，避免任务线程把已销毁的界面拖住
        taskCtl.detach();
    }

    /** 冷启动恢复两栏（实现在 DirectoryController，v2.0.0 O-15 刀2） */
    private void restorePanesOrLoad(Bundle b) {
        dirCtl.restorePanesOrLoad(b);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // 只存「位置」这种几十字节的状态（排序/隐藏/视图模式本来就在 SharedPreferences）
        out.putString("left_dir", left.dir == null ? null : left.dir.getPath());
        out.putString("right_dir", right.dir == null ? null : right.dir.getPath());
        out.putStringArrayList("left_hist", new ArrayList<String>(left.history));
        out.putStringArrayList("right_hist", new ArrayList<String>(right.history));
        out.putInt("left_hidx", left.hIndex);
        out.putInt("right_hidx", right.hIndex);
        out.putBoolean("right_active", right.active);
    }

    @Override
    protected void onDestroy() {
        // v1.96.0：Bg 的线程池是进程级的，Activity 销毁后读目录任务仍会跑完 —— 把代次推走，
        // 让回来的结果自己作废，别去碰已经拆掉的视图（也就是 1.5「生命周期没人收尾」那条）。
        left.dirGen++;
        right.dirGen++;
        taskCtl.destroy();
        super.onDestroy();
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
                                        // v1.66.4 / v1.99.1：AtomicReplace 先 rename，失败才
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
        if (drawerCtl.isOpen()) {
            closeDrawer();
            return;
        }
        if (left.adapter.selectionMode) { exitSelection(left); return; }
        if (right.adapter.selectionMode) { exitSelection(right); return; }
        Pane p = active();
        if (p.archiveFile != null) {          // 压缩包内：逐级返回，根则退出压缩包
            archGoUp(p);
            return;
        }
        // 活动栏在目录模式，但对面窗格开着压缩包 → 先退对面压缩包（v1.15）
        Pane o = other();
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
        final Pane p = active();
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

    /** 两栏一起刷新（实现在 DirectoryController） */
    private void refreshAll() {
        dirCtl.refreshAll();
    }

    /** 下拉刷新（实现在 DirectoryController，含 220ms 兜底与代次校验） */
    private void refreshPanel(final Pane p) {
        dirCtl.refreshPanel(p);
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
