package com.hex91.file;

import com.hex91.file.fs.FileKind;
import com.hex91.file.fs.Shell;
import com.hex91.file.utils.Bg;
import com.hex91.file.utils.CrashLog;
import com.hex91.file.utils.DragPlan;
import com.hex91.file.utils.GridAction;
import com.hex91.file.utils.ImageBatchUtil;
import com.hex91.file.utils.IntentImport;
import com.hex91.file.utils.KeyCipher;
import com.hex91.file.utils.LogRing;
import com.hex91.file.utils.OpenWith;
import com.hex91.file.utils.StorageStat;
import com.hex91.file.controller.ArchiveAddController;
import com.hex91.file.controller.ArchiveController;
import com.hex91.file.controller.DirectoryController;
import com.hex91.file.controller.DrawerController;
import com.hex91.file.controller.Pane;
import com.hex91.file.controller.PropsController;
import com.hex91.file.controller.SignFlowController;
import com.hex91.file.controller.TaskUiController;
import com.hex91.file.ui.ArchiveText;
import com.hex91.file.ui.PackText;
import com.hex91.file.ui.ApkInfoActivity;
import com.hex91.file.ui.ApkSignHelper;
import com.hex91.file.ui.ArscEditorActivity;
import com.hex91.file.ui.ArscPlusActivity;
import com.hex91.file.ui.BaseActivity;
import com.hex91.file.ui.BatchRenameActivity;
import com.hex91.file.ui.BookmarkStore;
import com.hex91.file.ui.CompareActivity;
import com.hex91.file.ui.CrashReportActivity;
import com.hex91.file.ui.DexEditorActivity;
import com.hex91.file.ui.DirPickerDialog;
import com.hex91.file.ui.DupFinderActivity;
import com.hex91.file.ui.FileAdapter;
import com.hex91.file.ui.FileGridAdapter;
import com.hex91.file.ui.FontPreviewActivity;
import com.hex91.file.ui.HexViewerActivity;
import com.hex91.file.ui.HfDialogBuilder;
import com.hex91.file.ui.HlsDownloadActivity;
import com.hex91.file.ui.ImageInfoActivity;
import com.hex91.file.ui.ImageViewerActivity;
import com.hex91.file.ui.InstallResultReceiver;
import com.hex91.file.ui.LanShareActivity;
import com.hex91.file.ui.ManifestEditorActivity;
import com.hex91.file.ui.PdfPreviewActivity;
import com.hex91.file.ui.PullRefreshLayout;
import com.hex91.file.ui.RecentStore;
import com.hex91.file.ui.SearchActivity;
import com.hex91.file.ui.SettingsActivity;
import com.hex91.file.ui.SignKeyManagerActivity;
import com.hex91.file.ui.StringsViewerActivity;
import com.hex91.file.ui.SyncActivity;
import com.hex91.file.ui.TaskCenterActivity;
import com.hex91.file.ui.TextEditorActivity;
import com.hex91.file.ui.SmaliReport;
import com.hex91.file.ui.ThemeManager;
import com.hex91.file.ui.TrashActivity;
import com.hex91.file.ui.UiDecor;
import com.hex91.file.ui.VideoPlayerActivity;
import com.hex91.file.ui.WidgetProvider;
import com.hex91.file.net.StreamUrl;
import com.hex91.file.net.TsRemuxer;
import com.hex91.file.apk.AppInstaller;
import com.hex91.file.apk.ApkCloneTool;
import com.hex91.file.apk.InstallCommand;
import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.ApkPackager;
import com.hex91.file.apk.ApkRepack;
import com.hex91.file.apk.ApkSignCheck;
import com.hex91.file.apk.ApkSignerTool;
import com.hex91.file.apk.ApkUtil;
import com.hex91.file.apk.ArchivePasswords;
import com.hex91.file.apk.ArchiveUtil;
import com.hex91.file.apk.ArchiveWrite;
import com.hex91.file.apk.FileInspect;
import com.hex91.file.apk.SmaliTool;
import com.hex91.file.apk.SplitInstaller;
import com.hex91.file.apk.SplitPackage;
import com.hex91.file.R;
import com.hex91.file.ui.FsText;
import com.hex91.file.ui.UtilText;
import com.hex91.file.fs.DiskScanUtil;
import com.hex91.file.fs.FileClipboard;
import com.hex91.file.fs.FileCrypt;
import com.hex91.file.fs.FileItem;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.FileSplit;
import com.hex91.file.fs.FileTask;
import com.hex91.file.fs.FileTaskEngine;
import com.hex91.file.fs.RootIO;
import com.hex91.file.fs.RootUtil;
import com.hex91.file.fs.Trash;
import com.hex91.file.fs.TrashJanitor;
import com.hex91.file.fs.TreeOps;
import com.hex91.file.fs.WorkDir;
import com.hex91.file.fs.ZipUtil;
import com.hex91.file.fs.ZstdUtil;
import com.hex91.file.core.MimeUtil;
import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

    // ---------------------------------------------------------- 文件动作码（v2.9.0，O-25）
    // 收敛前这里有两套并行的编号：showOpsMenu 与 showApkOpsMenu 各自声明一段局部
    // final int A_…（同名不同值：A_DEXPP 在文件菜单是 14、在 APK 菜单是 15），中间还夹着
    // 8 个绕过常量直接写数字的地方（36/37/38/39/41/42/43/44）。派发端 runFileAction 是 52 个 case 的
    // 裸 int switch，所以菜单写错号只会静默：写了个没有 case 的号 → 点了没反应；
    // 写成语义相近的号（13 签名信息 vs 20 APK 签名）→ 弹错功能。
    //
    // 现在只有这一张表：数值一个都不改（保持 52 个 case 的语义原样，避免"只换名字却改
    // 行为"），菜单构造与派发引用同一符号，switch 补 default 把静默失败变成可见失败。
    // v2.11.0（O-26）收尾：runFileAction 的 52 个 case 也从数字换成了 A_*，至此菜单与派发
    // 两侧都不再出现裸数字；test/action_check.sh 会拦住「又写回数字 case」的改动。
    // ArchiveController 里那套归档条目编号（A_EDIT / A_HERE …）不动：它服务的是压缩包
    // 内条目菜单，是另一张表，合并只会把两个控制器耦在一起。
    private static final int A_SMALI = 0;          // 独立 dex → 反编译 smali
    private static final int A_EDIT = 1;           // 文本编辑
    private static final int A_HEX = 2;            // HEX 查看
    private static final int A_COPY = 3;           // 复制到对面窗格
    private static final int A_MOVE = 4;           // 移动到对面窗格
    private static final int A_REN = 5;            // 重命名
    private static final int A_ZIP = 6;            // 压缩为 ZIP
    private static final int A_DEL = 7;            // 删除（回收站）
    private static final int A_PROP = 8;           // 属性
    private static final int A_ASSEMBLE = 9;       // smali 目录 → 编译为 dex
    private static final int A_DEX2SMALI = 10;     // APK → 反编译全部 dex
    private static final int A_EXTRACT_DEX = 11;   // APK → 仅提取 dex
    private static final int A_SIGN = 13;          // 签名详情
    private static final int A_DEXPP = 14;         // 独立 dex → Dex 编辑器++
    private static final int A_DEXPP_MULTI = 15;   // APK → Dex 编辑器++（MultiDex 多选）
    private static final int A_ARSCPP = 16;        // APK → ARSC 编辑
    private static final int A_MFPP = 17;          // APK → Manifest 编辑器++
    private static final int A_TOZIP_C = 18;       // 复制到对面压缩包
    private static final int A_TOZIP_M = 19;       // 移动到对面压缩包
    private static final int A_SIGN_APK = 20;      // APK 签名（内置密钥）
    private static final int A_COMPARE = 21;       // 对比…（自动判别目录/文本/哈希）
    private static final int A_APK_COMPARE = 22;   // APK 对比
    private static final int A_APK_CLONE = 23;     // APK 共存
    private static final int A_ENCRYPT = 24;       // 加密文件（.enc）
    private static final int A_DECRYPT = 25;       // 解密文件
    private static final int A_SIGKILL = 26;       // 去除签名校验
    private static final int A_SPLIT = 27;         // 文件分割
    private static final int A_MERGE = 28;         // 分卷合并
    private static final int A_ZSTDC = 29;         // Zstd 压缩
    private static final int A_ZSTDD = 30;         // Zstd 解压
    private static final int A_STRINGS = 31;       // strings 提取
    private static final int A_INFO = 32;          // APK 信息
    private static final int A_UNPACK = 33;        // APK 解包
    private static final int A_SIGN_CUSTOM = 34;   // 自定义密钥签名
    private static final int A_RM_SIGN = 35;       // 去除签名 → 未签名 APK
    private static final int A_APK_PACK = 36;      // 解包目录 → 打包为 APK
    private static final int A_IMGBATCH = 37;      // 图片处理（缩放/转格式/水印）
    private static final int A_HASH = 38;          // 计算哈希（MD5/SHA1/SHA256）
    private static final int A_DUPFIND = 39;       // 查找重复文件
    private static final int A_ARSCPP2 = 40;       // APK → ARSC++ 编辑
    private static final int A_DEX_PROP = 41;      // Dex 属性
    private static final int A_DEX_FIX = 42;       // Dex 修复
    private static final int A_KS_INFO = 43;       // 签名文件 → 签名信息
    private static final int A_KS_IMPORT = 44;     // 签名文件 → 导入签名管理
    private static final int A_SHARE = 45;         // 分享
    private static final int A_LAN = 46;           // 局域网快传
    private static final int A_OPEN = 47;          // 打开方式
    private static final int A_CLIP_C = 48;        // 复制到剪贴板
    private static final int A_CLIP_M = 49;        // 剪切到剪贴板
    private static final int A_PLAYER = 50;        // 内置播放器
    private static final int A_TSMP4 = 51;         // TS → MP4（转封装）
    private static final int A_HLSD = 52;          // 本地 m3u8 → 下载/合并页
    private static final int A_EXIF = 53;          // 图片元数据（EXIF）
    private static final int A_SPLITINST = 54;     // 分包安装（.apks / .xapk / .apkm）
    private static final int A_VAULT = 55;         // 移入隐私空间（F-22）
    // 选 keystore 的请求码已随签名簇搬到 SignFlowController.REQ_CUSTOM_KEYSTORE（v2.7.0）

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

    });

    /**
     * 「往压缩包里加东西」控制器（O-22 刀2）。
     *
     * <p>与 {@link #archCtl} 分开：那一簇是「包内浏览/条目操作」，这一簇是「把外部文件塞进去」。
     * 两者共用 {@code Pane} 与压缩包写回工具，但调用时机与失败提示完全不同。</p>
     */
    private final ArchiveAddController archiveAdd = new ArchiveAddController(
            new ArchiveAddController.Host() {
                public android.content.Context ctx() { return MainActivity.this; }

                public void toast(String s) { MainActivity.this.toast(s); }

                public void ui(Runnable r) { runOnUiThread(r); }

                public Pane active() { return MainActivity.this.active(); }

                public void showArchiveDir(Pane p) { archCtl.showArchiveDir(p); }

                public void refreshAll() { MainActivity.this.refreshAll(); }

                public boolean ensureArchiveWritable(File f) {
                    return MainActivity.this.ensureArchiveWritable(f);
                }
            });

    /**
     * 「APK 签名」控制器（v2.7.0，O-22 刀1）。
     *
     * <p>这一簇与另外两个控制器不同：它有<b>跨 Activity 的状态</b>——「选 keystore 文件」要起
     * 系统选择器，结果从 {@code onActivityResult} 回来。原来靠 {@code mCustomSignSrc} /
     * {@code mCustomSignInPlace} 两个字段跨过这个缝，字段读写散在三处。现在状态收进控制器，
     * 宿主只在 {@code onActivityResult} 里转发一次 {@link SignFlowController#handleResult}。</p>
     */
    private final SignFlowController signFlow = new SignFlowController(new SignFlowController.Host() {
        public android.content.Context ctx() { return MainActivity.this; }

        public void toast(String s) { MainActivity.this.toast(s); }

        public void ui(Runnable r) { runOnUiThread(r); }

        public void refreshAll() { MainActivity.this.refreshAll(); }

        public void pickKeystore(Intent chooser) {
            startActivityForResult(chooser, SignFlowController.REQ_CUSTOM_KEYSTORE);
        }

        public void importKeystore(File f) { MainActivity.this.importKeystore(f); }
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

    /**
     * v2.3.0（F-15）：双栏分隔比例。
     *
     * <p>只在平板/大屏（sw600dp）上可拖；夹取在 0.3–0.7，避免把某一栏拖成看不见。
     * 存在 PREF_UI 里，和排序/视图模式同一份偏好，重启与旋转都保持。</p>
     */
    private static final String KEY_SPLIT = "split_ratio";
    private float splitRatio = 0.5f;
    /** v2.3.0（F-15）：分隔条对象；单栏或网格模式下 GONE */
    private View paneDivider;

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
        // 必须在 restorePanesOrLoad 之前：那条路径会经 setActive → applyViewMode，
        // 用得到 paneDivider 的显隐与已恢复的分隔比例。
        setupPaneDivider();
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
            public void onClick(View v) { toggleOrSwapPanes(); }
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
        handleIncoming(getIntent());
        promptCrash();          // v2.12.0（F-18）：上次崩溃过就问一次要不要看现场
    }

    /**
     * v2.12.0（F-18）：启动时提示未读崩溃。
     *
     * <p>只在 onCreate 问，不挂 onResume —— 崩溃后用户切回来一次就问一次，会变成骚扰。
     * 「忽略」直接把这批标记为已读，下次不再提；「查看」进崩溃页（进页即已读）。</p>
     */
    private void promptCrash() {
        final int n = CrashLog.pendingCount(this);
        if (n <= 0) return;
        new HfDialogBuilder(this)
                .setTitle(R.string.crash_prompt_title)
                .setMessage(getString(R.string.crash_prompt_msg, n))
                .setPositiveButton(R.string.crash_prompt_view,
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                startActivity(new Intent(MainActivity.this,
                                        CrashReportActivity.class));
                            }
                        })
                .setNegativeButton(R.string.crash_prompt_ignore,
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                CrashLog.markSeen(MainActivity.this);
                            }
                        })
                .show();
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
            toast(s(R.string.main_paste_clip_empty));
            updatePasteButton();
            return;
        }
        if (p.archiveFile != null) { toast(s(R.string.main_paste_in_arch)); return; }
        if (p.dir == null || !p.dir.isDirectory()) { toast(s(R.string.main_paste_no_dir)); return; }
        final List<File> srcs = cb.alive();
        if (srcs.isEmpty()) {
            cb.clear();
            updatePasteButton();
            toast(s(R.string.main_paste_gone));
            return;
        }
        final boolean move = cb.isMove();
        FileTaskEngine.get().submit(move ? FileTask.MOVE : FileTask.COPY, srcs, p.dir);
        cb.clearIfMove();                 // 剪切是一次性的；复制可以连着粘多处
        updatePasteButton();
        toast(move ? s(R.string.main_move_started, srcs.size(), p.dir.getName())
                : s(R.string.main_copy_started, srcs.size(), p.dir.getName()));
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
            menu.add(Menu.NONE, 900, 0, s(R.string.main_extract_all_here));
        }
        menu.add(Menu.NONE, 0, 1, s(R.string.refresh));
        menu.add(Menu.NONE, 1, 1, s(R.string.main_search));
        menu.add(Menu.NONE, 2, 2, s(R.string.main_select_all));
        menu.add(Menu.NONE, 3, 3, s(R.string.main_sort));
        // v1.80.0：列表 ⇄ 网格（大图）切换，偏好持久化
        menu.add(Menu.NONE, 10, 3, viewMode == VIEW_GRID
                ? s(R.string.main_view_list) : s(R.string.main_view_grid));
        menu.add(Menu.NONE, 4, 4, p.adapter.showHidden
                ? s(R.string.main_hide_dot) : s(R.string.main_show_dot));
        menu.add(Menu.NONE, 5, 5, s(R.string.main_bookmarks));
        menu.add(Menu.NONE, 6, 6, s(R.string.main_trash));
        menu.add(Menu.NONE, 7, 7, s(R.string.main_swap));
        // v1.78.0：剪贴板非空时把粘贴放到显眼位置，并带上「复制 3 项」这样的摘要
        menu.add(Menu.NONE, 9, 0, FileClipboard.get().isEmpty()
                ? s(R.string.main_paste_empty)
                : s(R.string.main_paste_label, FsText.clipboardLabel(this, FileClipboard.get())));
        menu.add(Menu.NONE, 8, 8, s(R.string.main_recent));
        // v1.87.0：云同步（WebDAV 双向）
        menu.add(Menu.NONE, 13, 9, s(R.string.main_cloud_sync));
        // v1.91.0：播放网络地址（m3u8 / mpd / rtsp / 直链）
        menu.add(Menu.NONE, 14, 9, s(R.string.main_play_url));
        // v1.92.0：把 m3u8 拉成单个 MP4（下载 + 合并 + 换容器）
        menu.add(Menu.NONE, 15, 9, s(R.string.main_hls_download));
        // v1.83.0：设置页（主题 / 工作目录 / Root / 回收站策略 / 版本）
        menu.add(Menu.NONE, 12, 9, s(R.string.settings_title));
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
                    case 7: toggleOrSwapPanes(); break;
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
                .setTitle(s(R.string.main_sort))
                .setSingleChoiceItems(
                        new String[]{s(R.string.main_sort_name), s(R.string.main_sort_size),
                                s(R.string.main_sort_time)}, p.adapter.sortMode,
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                saveSortMode(which);
                                for (Pane q : new Pane[]{left, right}) q.adapter.sortMode = which;
                                refreshAll();
                                d.dismiss();
                            }
                        })
                .setNegativeButton(s(R.string.common_cancel), null)
                .show();
    }

    /** ＋ 新建：文件夹 / 文本文件 */
    private void askCreateNew() {
        new HfDialogBuilder(this)
                .setTitle(s(R.string.main_new))
                .setItems(new String[]{s(R.string.main_new_folder), s(R.string.main_new_file)},
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
        if (p.archiveFile != null) { toast(s(R.string.main_new_in_arch)); return; }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(this)
                .setTitle(s(R.string.main_new_file_title, p.dir.getName()))
                .setView(et)
                .setPositiveButton(s(R.string.common_create), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        File nf = new File(p.dir, name);
                        boolean ok = false;
                        try { ok = nf.createNewFile(); } catch (Exception e) { ok = false; }
                        // v1.44.0：root 区回退（su 写空文件）
                        if (!ok && !nf.exists()) ok = RootIO.writeBytes(nf, new byte[0]);
                        if (ok) {
                            toast(s(R.string.main_created, name));
                            loadDir(p, p.dir);
                        } else {
                            toast(s(R.string.main_create_fail));
                        }
                    }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
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
    /**
     * v2.3.0（F-15）：⇄ 有两种语义。
     *
     * <p>双栏并排时是「交换两栏内容」，和以前一样；单栏时屏幕上只有一栏可见，
     * 交换无从谈起，改为「切到另一栏」—— 否则单栏模式下另一栏永远够不着
     * （点击只能落在可见的那一栏上）。</p>
     *
     * <p>默认双栏（{@link #SINGLE_PANE_MAX_DP} 为 0），因此手机上也一直走交换分支。</p>
     */
    private void toggleOrSwapPanes() {
        if (singlePane()) {
            setActive(active() == left ? right : left);
        } else {
            swapPanes();
        }
    }

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

    /** 拖放被拦的原因码 → 文案（F-14/14：DragPlan 只给 {@link DragPlan.Block}） */
    private String dragText(DragPlan.Block b) {
        switch (b) {
            case TARGET_INVALID:
                return s(R.string.drag_block_invalid);
            case NO_ITEMS:
                return s(R.string.drag_block_no_items);
            case SELF:
                return s(R.string.drag_block_self);
            case SAME:
                return s(R.string.drag_block_same);
            default:
                return "";
        }
    }

    /** 拖放标题里的「被拖的东西」：单项给文件名，多项给数量 */
    private String dragItemDesc(DragPlan.Check ck) {
        return ck.items.size() == 1
                ? ck.items.get(0).getName()
                : s(R.string.drag_items_n, ck.items.size());
    }

    /** 落到某个窗格：先判定，再让用户选复制还是移动 */
    private boolean handleDrop(final Pane dst) {
        if (dst.archiveFile != null) {
            toast(s(R.string.drag_arch_readonly));
            return false;
        }
        final DragPlan.Check ck = DragPlan.check(new ArrayList<File>(mDragSrcs), dst.dir);
        if (!ck.ok()) {
            // 拖回自己这一栏（长按后没动就松手）不算失败：接着会弹长按菜单，
            // 这时再弹一句「已经在目标目录里了」纯属噪音
            boolean backHome = dst == mDragFrom && ck.skippedSame > 0 && ck.skippedSelf == 0;
            if (!backHome && ck.block != DragPlan.Block.NONE) toast(dragText(ck.block));
            return false;
        }
        final File dir = dst.dir;
        String msg = ck.skippedSame > 0
                ? s(R.string.drag_msg_skip, dir.getPath(), ck.skippedSame)
                : s(R.string.drag_msg, dir.getPath());
        new HfDialogBuilder(this)
                .setTitle(s(R.string.drag_title, dragItemDesc(ck), dir.getName()))
                .setMessage(msg)
                .setPositiveButton(R.string.drag_copy_here,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        submitDrop(ck.items, dir, false);
                    }
                })
                .setNeutralButton(R.string.drag_move_here,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        submitDrop(ck.items, dir, true);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
        return true;
    }

    /** 拖放落地：走任务队列，所以有进度条、可取消、失败不连带 */
    private void submitDrop(List<File> items, File dir, boolean move) {
        if (items.isEmpty()) return;
        FileTaskEngine.get().submit(move ? FileTask.MOVE : FileTask.COPY, items, dir);
        toast(move ? s(R.string.main_queued_move, items.size(), dir.getPath())
                : s(R.string.main_queued_copy, items.size(), dir.getPath()));
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
        boolean single = singlePane();
        boolean onePane = grid || single;
        Pane a = active();
        for (Pane p : new Pane[]{left, right}) {
            if (p.list != null) p.list.setVisibility(grid ? View.GONE : View.VISIBLE);
            if (p.grid != null) p.grid.setVisibility(grid ? View.VISIBLE : View.GONE);
            if (p.pull != null) p.pull.setVisibility(onePane && p != a ? View.GONE : View.VISIBLE);
        }
        if (paneDivider != null) paneDivider.setVisibility(onePane ? View.GONE : View.VISIBLE);
    }

    /**
     * 单栏阈值（dp）：窗口宽度小于该值时只显示活动栏。
     *
     * <p>{@code <= 0} 表示永不切单栏 —— 手机竖屏也保持双栏。</p>
     */
    private static final int SINGLE_PANE_MAX_DP = 0;

    /**
     * v2.3.1：手机默认保持双栏。
     *
     * <p>v2.3.0 曾按 400dp 阈值在手机上切单栏，实际用起来双栏才是这工具的主场
     * （左边找文件、右边看压缩包），所以默认关掉。机制整条保留，把
     * {@link #SINGLE_PANE_MAX_DP} 改成 400（或其它 dp 值）即可恢复按宽度自动单栏，
     * 供极窄的分屏窗口使用。</p>
     *
     * <p>返回 true 时的显隐复用 {@link #setActive} 已有的「显示活动栏、收起另一栏」
     * 路径，不另写一套逻辑。</p>
     */
    private boolean singlePane() {
        return SINGLE_PANE_MAX_DP > 0
                && getResources().getConfiguration().screenWidthDp < SINGLE_PANE_MAX_DP;
    }

    // ---------------------------------------------------------------- 分隔条（v2.3.0 F-15）

    /**
     * 双栏分隔条：平板/大屏可拖，手机只是 1dp 装饰线。
     *
     * <p>是否可拖看 {@code bool/pane_divider_draggable}（values 为 false，
     * values-sw600dp 为 true），宽度看 {@code dimen/pane_divider_width}。
     * 两种形态共用同一份布局：复制一份 756 行的 activity_main.xml 到
     * layout-sw600dp，以后每次改布局都得记得改两处，迟早漂移。</p>
     *
     * <p>判空是兜底：单栏/网格模式下分隔条会被 GONE，但对象一直在。</p>
     */
    private void setupPaneDivider() {
        paneDivider = findViewById(R.id.pane_divider);
        if (paneDivider == null) return;

        splitRatio = getSharedPreferences(PREF_UI, MODE_PRIVATE).getFloat(KEY_SPLIT, 0.5f);
        applySplit(splitRatio);

        if (!getResources().getBoolean(R.bool.pane_divider_draggable)) return;
        final View bar = (View) paneDivider.getParent();
        if (bar == null) return;

        paneDivider.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int[] loc = new int[2];
                        bar.getLocationOnScreen(loc);
                        int w = bar.getWidth();
                        if (w <= 0) return true;
                        applySplit((e.getRawX() - loc[0]) / w);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 只松手时落盘：拖动过程每帧都写 SP 会一直触发 apply()
                        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit()
                                .putFloat(KEY_SPLIT, splitRatio).apply();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 按比例分配两栏宽度；0.3–0.7 夹取，拖动时实时生效 */
    private void applySplit(float ratio) {
        splitRatio = Math.max(0.3f, Math.min(0.7f, ratio));
        if (left.pull == null || right.pull == null) return;
        setWeight(left.pull, splitRatio);
        setWeight(right.pull, 1f - splitRatio);
        ViewGroup parent = (ViewGroup) left.pull.getParent();
        if (parent != null) parent.requestLayout();
    }

    private static void setWeight(View v, float w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof LinearLayout.LayoutParams)) return;
        ((LinearLayout.LayoutParams) lp).weight = w;
        v.setLayoutParams(lp);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // 旋转 / 分屏改变窗口宽度：重新决定单栏还是双栏。
        // 夜间模式切换由 BaseActivity 那边 recreate，不在这里处理。
        applyViewMode();
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
            headerStats.setText(s(R.string.main_stat_counts_arch, dirs, files,
                    p.archive != null ? p.archive.format() : "ZIP"));
            return;
        }
        if (p.dir == null) return;
        headerPath.setText(p.dir.getPath());
        // v1.99.0（O-06）：路径已经切到新目录，计数却还是上一层的。
        // 大目录/root 回退时这段时间有几百毫秒到几秒，用户看到「路径是 A，文件数还是 B 的」
        // 会以为读错了目录，甚至照着旧数字做删除。读取期间明确写「读取中…」，
        // 顺便跳过下面的 usageOf（那是一次 statfs，读目录期间没必要连着做）。
        if (p.adapter.isLoading()) {
            headerStats.setText(R.string.main_stat_loading);
            return;
        }
        // v1.96.0：计数由 DirReader 读目录时顺带算好（原来这里每次再遍历一遍适配器，
        // 几千项时和读目录叠加成双份开销，而且读目录已经不在主线程了，这里也不能留 O(n)）
        int dirs = p.dirCount, files = p.fileCount;
        // v1.72.0：根目录/系统分区统一按 /data 统计，避免顶层显示「987M/987M」
        long[] u = StorageStat.usageOf(p.dir.getAbsolutePath());
        if (u != null) {
            long used = u[0] > u[1] ? u[0] - u[1] : 0;
            headerStats.setText(s(R.string.main_stat_counts_store, dirs, files,
                    FileOps.formatSize(used), FileOps.formatSize(u[0])));
        } else {
            headerStats.setText(s(R.string.main_stat_counts, dirs, files));
        }
    }

    private void onFileClick(File f) {
        new RecentStore(this).record(f.getAbsolutePath());
        String lower = f.getName().toLowerCase();
        // v2.13.0（F-03）：分包安装包本身是 zip，但用户点它的意图是「装」，不是「看里面」
        if (SplitPackage.isSplitName(lower)) { showSplitInfo(f); return; }
        if (FileKind.isArchiveExt(lower) || lower.endsWith(".apk")) {
            // 压缩包/APK 均在当前窗格内嵌浏览（v1.14，不再全屏）
            if (lower.endsWith(".apk")) showApkInfo(f);
            else openArchiveInPane(active(), f);
            return;
        } else if (lower.endsWith(".dex")) {
            askDexAction(f);
        } else if (FileKind.isImageExt(lower)) {
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
        } else if (FileKind.isTextFile(f, lower)) {
            openEditor(f);
        } else if (!OpenWith.open(this, f)) {
            // v1.77.0：以前所有不认识的文件一律弹属性，视频/Office 在文件管理器里等于打不开。
            // 先交给系统找应用；真没应用能处理时才退回属性页，并说清原因。
            toast(s(R.string.main_no_app_for_type));
            showProps(f);
        }
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
        tv.setText(R.string.main_reading_index);
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
                .setTitle(R.string.main_remux_title)
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
                                        tv.setText(s(R.string.main_remux_progress, sec, pct));
                                    } else {
                                        tv.setText(s(R.string.main_remux_progress_bare, sec));
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
                            toast(s(R.string.main_remux_done, dst.getName(),
                                    fs / 1024 / 1024));
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            dlg.dismiss();
                            String m = t.getMessage();
                            toast(s(R.string.main_remux_fail,
                                    m == null ? t.getClass().getSimpleName() : m));
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
        input.setHint(R.string.main_url_hint);
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
                .setTitle(R.string.main_url_title)
                .setView(input)
                .setMessage(R.string.main_url_msg)
                .setPositiveButton(R.string.mops_play,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String raw = input.getText() == null ? "" : input.getText().toString();
                        String url = StreamUrl.normalize(raw);
                        if (url == null) {
                            toast(s(R.string.main_url_bad));
                            return;
                        }
                        if (StreamUrl.kindOf(url) == StreamUrl.KIND_RTMP) {
                            toast(s(R.string.main_url_rtmp));
                            return;
                        }
                        startActivity(new Intent(MainActivity.this, VideoPlayerActivity.class)
                                .putExtra(VideoPlayerActivity.EXTRA_URL, url));
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
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
        final String[] ops = {s(R.string.mops_ks_import), s(R.string.mops_sign_info),
                s(R.string.mops_hex), s(R.string.mops_props)};
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
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 读取文件字节并弹出「导入签名」对话框（复用签名管理里的同一套流程） */
    private void importKeystore(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.main_ks_reading));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                byte[] bytes = null;
                String err = null;
                try { bytes = FileOps.readAllBytes(f); }
                catch (Throwable t) { err = t.getMessage(); }
                final byte[] fb = bytes;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (fb == null) { toast(s(R.string.ksm_err_read, fe)); return; }
                        SignKeyManagerActivity.promptImport(MainActivity.this, fb, f.getName(),
                                new Runnable() {
                                    public void run() {
                                        toast(s(R.string.main_ks_imported));
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
        et.setHint(R.string.main_ks_pw_hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new HfDialogBuilder(this)
                .setTitle(R.string.mops_sign_info)
                .setMessage(s(R.string.main_ks_info_msg, f.getName()))
                .setView(et)
                .setPositiveButton(R.string.main_ks_parse,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        signFlow.parseKeystore(f, et.getText().toString());
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void openViewer(File f) {
        Intent i = new Intent(MainActivity.this, ImageViewerActivity.class);
        i.putExtra(ImageViewerActivity.EXTRA_FILE, f.getAbsolutePath());
        startActivity(i);
    }

    /** 点击 .dex → 操作菜单（Dex 编辑器++ / 反编译 / HEX / 属性） */
    private void askDexAction(final File f) {
        String[] ops = {s(R.string.mops_dexpp), s(R.string.mops_smali_decompile),
                s(R.string.mops_hex), s(R.string.mops_props)};
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
        toast(s(R.string.main_baksmali_running));
        new Thread(new Runnable() {
            public void run() {
                final String err = SmaliTool.decompile(dexFile, outDir, 4);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err == null) {
                            toast(s(R.string.main_baksmali_done, outDir.getPath()));
                            Intent i = new Intent(MainActivity.this, MainActivity.class);
                            i.putExtra("dir", outDir.getAbsolutePath());
                            startActivity(i);   // singleTop：onNewIntent 跳转目录
                        } else {
                            toast(s(R.string.main_decompile_fail,
                                    SmaliReport.err(MainActivity.this, err)));
                        }
                    }
                });
            }
        }, "baksmali").start();
    }

    // ================================================================ 窗格内嵌压缩包浏览（v1.14）

    /** 写归档前校验：RAR 弹「转存为 ZIP」，其余只读格式给说明；返回 false 中止操作 */
    private boolean ensureArchiveWritable(File f) {
        if (f == null) { toast(s(R.string.main_arch_none)); return false; }
        if (ArchiveWrite.canWrite(f)) return true;
        if (f.getName().toLowerCase().endsWith(".rar")) { askRarToZip(f); return false; }
        toast(s(R.string.main_arch_readonly));
        return false;
    }

    /**
     * RAR 无法直接写入：压缩算法专有，全球没有任何免费/付费通用库支持
     * （包括 7-Zip native 引擎，MT 管理器同样只读）。提供「转存为同名 ZIP」绕行。
     */
    private void askRarToZip(final File rar) {
        new HfDialogBuilder(this)
                .setTitle(R.string.main_rar_title)
                .setMessage(s(R.string.main_rar_msg, rar.getParent()))
                .setPositiveButton(R.string.main_rar_conv,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        convertRarToZip(rar);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** RAR → 同名 ZIP：解压到缓存再打包（带密码的 RAR 用会话密码解密） */
    private void convertRarToZip(final File rar) {
        toast(s(R.string.main_rar_converting));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = new File(rar.getParentFile(),
                        FileKind.stripExt(rar.getName()) + ".zip");
                File tmp = new File(getCacheDir(), "rar2zip_" + System.currentTimeMillis());
                try {
                    tmp.mkdirs();
                    ArchiveUtil.Archive a = ArchiveUtil.open(rar, ArchivePasswords.get(rar));
                    try { a.extractAll(tmp); } finally { a.close(); }
                    File[] kids = tmp.listFiles();
                    if (kids == null || kids.length == 0) throw new RuntimeException("rar empty");
                    if (!out.createNewFile() && !out.isFile()) throw new RuntimeException("cannot create " + out.getName());
                    if (!ZipUtil.createZip(out, java.util.Arrays.asList(kids))) {
                        throw new RuntimeException("zip create failed");
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
                    err = ArchiveText.errorText(MainActivity.this, t);
                }
                FileOps.deleteRecursive(tmp);
                final String ferr = err;
                final File fout = out.isFile() ? out : null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast(s(R.string.main_rar_fail, ferr)); return; }
                        toast(s(R.string.main_rar_done, fout.getName()));
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


    /** Dex 属性（v1.66.5）：版本 + String/Type/Proto/Field/Method/Class 数量 + 校验状态 */
    private void showDexProps(final File dex) {
        toast(s(R.string.main_reading));
        new Thread(new Runnable() {
            public void run() {
                final String info = SmaliTool.dexProps(dex);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (info == null) { toast(s(R.string.main_dex_invalid)); return; }
                        showTextDialog(dex.getName(), SmaliReport.props(MainActivity.this, info));
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
                .setPositiveButton(R.string.common_confirm, null)
                .show();
    }

    /** Dex 修复（v1.66.5，独立 dex）：确认 → 备份 → 修头部校验 → dexlib2 验证 */
    private void askDexFix(final File dex) {
        new HfDialogBuilder(this)
                .setTitle(R.string.mops_dex_fix)
                .setMessage(R.string.main_dexfix_msg)
                .setPositiveButton(R.string.main_dexfix_do,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast(s(R.string.main_dexfix_running));
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
                                final String msg = err != null
                                        ? s(R.string.main_dexfix_fail,
                                                SmaliReport.err(MainActivity.this, err))
                                        : s(R.string.main_dexfix_done,
                                                SmaliReport.repairReport(MainActivity.this,
                                                        rep.toString()));
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
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }


    /** 用内置密钥给 APK 签名：产物 同名_signed.apk（长按菜单「APK 签名」与修改后询问共用） */
    private void signApkBuiltin(final File apk) {
        toast(s(R.string.main_signing));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = new File(apk.getParentFile(),
                        FileKind.stripExt(apk.getName()) + "_signed.apk");
                try {
                    // v1.67.3：sign 抛异常时也必须关闭 assets 流（原实现 close 在 try 末尾，异常路径不执行）
                    java.io.InputStream ks = getAssets().open("hexfile.p12");
                    try {
                        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, apk, out, 21);
                    } finally {
                        try { ks.close(); } catch (java.io.IOException ignored) {}
                    }
                } catch (Throwable t) {
                    err = ApkSignHelper.errorText(MainActivity.this, t);
                }
                final String ferr = err;
                final File fout = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ferr != null ? s(R.string.main_sign_fail, ferr)
                                : s(R.string.main_signed, fout.getName()));
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
            if (custom) signFlow.askCustomSign(apk, true);
            else signApkInPlace(apk);
            return;
        }
        if (custom) signFlow.askCustomSign(apk, false);
        else signApkBuiltin(apk);
    }

    /** 内置密钥原地签名（v1.66.1 引入，v1.66.8 起为修改 APK 的默认签名方式）：临时文件 → 原子覆盖 */
    private void signApkInPlace(final File apk) {
        toast(s(R.string.main_signing));
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
                    err = ApkSignHelper.errorText(MainActivity.this, t);
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ferr != null ? s(R.string.main_sign_fail, ferr)
                                : s(R.string.main_signed_inplace, apk.getName()));
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
        l1.setText(R.string.main_img_scale);
        l1.setTextSize(13);
        l1.setTextColor(ThemeManager.textSub(this));
        box.addView(l1);
        final EditText scale = new EditText(this);
        scale.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        scale.setText("100");
        box.addView(scale);

        TextView l2 = new TextView(this);
        l2.setText(R.string.main_img_format);
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
        l3.setText(R.string.main_img_quality);
        l3.setTextSize(13);
        l3.setTextColor(ThemeManager.textSub(this));
        l3.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(l3);
        final EditText quality = new EditText(this);
        quality.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        quality.setText("85");
        box.addView(quality);

        TextView l4 = new TextView(this);
        l4.setText(R.string.main_img_watermark);
        l4.setTextSize(13);
        l4.setTextColor(ThemeManager.textSub(this));
        l4.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(l4);
        final EditText wm = new EditText(this);
        wm.setHint(R.string.main_img_wm_hint);
        box.addView(wm);

        new HfDialogBuilder(this)
                .setTitle(s(R.string.main_img_title, paths.size()))
                .setView(box)
                .setPositiveButton(R.string.main_img_start,
                        new android.content.DialogInterface.OnClickListener() {
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
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void doImageBatch(final java.util.List<String> paths, final ImageBatchUtil.Options o) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(R.string.mops_img_batch);
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
                        if (firstErr == null) firstErr = UtilText.errorText(MainActivity.this, t);
                    }
                    pd.incrementProgressBy(1);
                }
                final int fok = ok, ffail = fail;
                final String ferr = firstErr;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        String msg = ffail == 0
                                ? s(R.string.main_img_done, fok)
                                : (ferr != null
                                        ? s(R.string.main_img_done_err, fok, ffail, ferr)
                                        : s(R.string.main_img_done_fail, fok, ffail));
                        toast(msg);
                        refreshAll();
                    }
                });
            }
        }, "img-batch").start();
    }

    /** 哈希校验（v1.59）：MD5/SHA1/SHA256 流式计算 + 期望值比对 + 点击复制 */
    private void doHash(final File f) {
        final ProgressDialog pd = ProgressDialog.show(this, s(R.string.mops_hash),
                f.getName(), true, false);
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
                        if (err != null) { toast(s(R.string.main_hash_fail, err)); return; }
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
        l.setText(R.string.main_hash_expect);
        l.setTextSize(13);
        l.setTextColor(ThemeManager.textSub(this));
        l.setPadding(0, (int) (10 * den), 0, 0);
        box.addView(l);
        final EditText expect = new EditText(this);
        expect.setHint(R.string.main_hash_hint);
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
                result.setText(m ? R.string.main_hash_match : R.string.main_hash_mismatch);
                result.setTextColor(m ? 0xFF2E7D32 : 0xFFC62828);
            }
        });

        new HfDialogBuilder(this)
                .setTitle(s(R.string.main_hash_dlg_title, f.getName()))
                .setView(box)
                .setPositiveButton(R.string.common_close, null)
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
                toast(s(R.string.main_copied, value));
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
        if (imgs.isEmpty()) { toast(s(R.string.main_img_none)); return; }
        askImageBatch(imgs);
    }

    /** APK 解包（v1.57）：全部条目提取到 <name>_unpack/，另存可读 Manifest */
    private void doApkUnpack(final File apk) {
        File out = new File(apk.getParentFile(), FileKind.stripExt(apk.getName()) + "_unpack");
        if (out.exists()) {
            toast(s(R.string.main_dir_exists, out.getName()));
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(this,
                s(R.string.mops_apk_unpack), apk.getName(), true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                ApkPackager.Stats st = null;
                try {
                    st = ApkPackager.unpack(apk, out);
                } catch (Throwable t) {
                    err = t instanceof ApkPackager.PackException
                            ? PackText.errorText(MainActivity.this, t)
                            : t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                final ApkPackager.Stats fst = st;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) toast(s(R.string.main_unpack_fail, ferr));
                        else {
                            toast(s(R.string.main_unpack_done, out.getName(),
                                    s(R.string.pack_stats, fst.files,
                                            FileOps.formatSize(fst.bytes))));
                            refreshAll();
                        }
                    }
                });
            }
        }, "apk-unpack").start();
    }

    /** 去除签名（v1.57）：重写包并剥离 META-INF 签名文件 → <name>_unsigned.apk */
    private void doRemoveSignature(final File apk) {
        File out = new File(apk.getParentFile(), FileKind.stripExt(apk.getName()) + "_unsigned.apk");
        final ProgressDialog pd = ProgressDialog.show(this,
                s(R.string.mops_rm_sign), apk.getName(), true, false);
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
                        if (ferr != null) toast(s(R.string.main_fail, ferr));
                        else {
                            toast(s(R.string.main_unsigned_out, fout.getName()));
                            refreshAll();
                        }
                    }
                });
            }
        }, "apk-rmsign").start();
    }

    /** 解包目录 → 打包为 APK（v1.57）：产物未签名，询问是否用内置密钥直接签 */
    private void doApkPack(final File dir) {
        File out = new File(dir.getParentFile(), FileKind.stripExt(dir.getName()) + "_packed.apk");
        final ProgressDialog pd = ProgressDialog.show(this,
                s(R.string.mops_apk_pack), dir.getName(), true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                final File fout = out;
                try {
                    ApkPackager.pack(dir, out);
                } catch (Throwable t) {
                    err = t instanceof ApkPackager.PackException
                            ? PackText.errorText(MainActivity.this, t)
                            : t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) { toast(s(R.string.main_pack_fail, ferr)); return; }
                        refreshAll();
                        new HfDialogBuilder(MainActivity.this)
                                .setTitle(R.string.main_pack_done_title)
                                .setMessage(s(R.string.main_pack_done_msg, fout.getName()))
                                .setPositiveButton(R.string.main_sign_builtin,
                                        new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        signApkBuiltin(fout);
                                    }
                                })
                                .setNeutralButton(R.string.mops_sign_custom,
                                        new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        signFlow.askCustomSign(fout, false);
                                    }
                                })
                                .setNegativeButton(R.string.main_sign_skip, null)
                                .show();
                    }
                });
            }
        }, "apk-pack").start();
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

    private void showApkInfo(final File f) {
        final PackageManager pm = getPackageManager();
        List<String[]> rows = ApkUtil.rows(f, pm, this);
        final boolean broken = rows == null;
        if (broken) {
            // 解析失败（如未重签名导致 arsc 校验不过）：降级显示基本信息，仍可查看/功能/安装
            rows = new ArrayList<String[]>();
            rows.add(new String[]{s(R.string.apk_row_pkg), s(R.string.apk_info_unparsable)});
            rows.add(new String[]{s(R.string.apk_row_size), FileOps.formatSize(f.length())});
            rows.add(new String[]{s(R.string.apk_row_sign), s(R.string.apk_info_bad_sign)});
            rows.add(new String[]{s(R.string.apk_row_note), s(R.string.apk_info_bad_desc)});
            rows.add(new String[]{s(R.string.apk_row_path), f.getAbsolutePath()});
        }
        final List<String[]> rowsF = rows;
        // v1.66.7：备份文件角色说明——_bak 永远保持原厂签名（设计如此，用于还原）；
        // v1.66.8 起修改直接写回原包，签名状态行即可判断当前包是否已重签
        String role = FileKind.apkFileRole(f, this);
        if (role != null) rowsF.add(1, new String[]{s(R.string.apk_row_role), role});

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
                // 与 ApkUtil 生成的标签同源（同一个资源键），换语言也不会错位
                if (s(R.string.apk_row_sign).equals(rowsF.get(i)[0])) {
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
                final ApkSignCheck.Scheme sc = ApkSignCheck.check(f);
                runOnUiThread(new Runnable() {
                    public void run() {
                        for (String[] r : rowsF) {
                            if (s(R.string.apk_row_sign).equals(r[0])) {
                                r[1] = signSchemeText(sc);
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

    /** 签名三态 → 文案（信息页签名行用） */
    private String signSchemeText(ApkSignCheck.Scheme sc) {
        if (sc.state == ApkSignCheck.ST_FAILED) return s(R.string.api_sign_read_fail);
        if (sc.state == ApkSignCheck.ST_UNSIGNED) return s(R.string.api_sign_unsigned);
        return sc.list.isEmpty() ? s(R.string.api_sign_verified) : sc.list;
    }

    /** 详情行标签：tagNo > 0 时加「证书[n] 」前缀 */
    private String signRowLabel(ApkSignCheck.Row r) {
        int res;
        switch (r.label) {
            case ApkSignCheck.ROW_RESULT: res = R.string.sign_row_result; break;
            case ApkSignCheck.ROW_V1: res = R.string.sign_row_v1; break;
            case ApkSignCheck.ROW_V2: res = R.string.sign_row_v2; break;
            case ApkSignCheck.ROW_V3: res = R.string.sign_row_v3; break;
            case ApkSignCheck.ROW_CERT_COUNT: res = R.string.sign_row_cert_count; break;
            case ApkSignCheck.ROW_SUBJECT: res = R.string.sign_row_subject; break;
            case ApkSignCheck.ROW_ISSUER: res = R.string.sign_row_issuer; break;
            case ApkSignCheck.ROW_SERIAL: res = R.string.sign_row_serial; break;
            case ApkSignCheck.ROW_SIG_ALG: res = R.string.sign_row_algo; break;
            case ApkSignCheck.ROW_NOT_AFTER: res = R.string.sign_row_not_after; break;
            case ApkSignCheck.ROW_WARNINGS: res = R.string.sign_row_warnings; break;
            case ApkSignCheck.ROW_ERRORS: res = R.string.sign_row_errors; break;
            case ApkSignCheck.ROW_MD5: res = R.string.sign_row_md5; break;
            case ApkSignCheck.ROW_SHA1: res = R.string.sign_row_sha1; break;
            case ApkSignCheck.ROW_SHA256: res = R.string.sign_row_sha256; break;
            default: res = R.string.sign_row_result; break;
        }
        return r.tagNo > 0 ? s(R.string.sign_cert_tag, r.tagNo) + s(res) : s(res);
    }

    /** 详情行值：值码优先，0 表示直接用原文（DN / 指纹 / 算法名） */
    private String signRowValue(ApkSignCheck.Row r) {
        switch (r.valueCode) {
            case ApkSignCheck.V_PASS: return s(R.string.sign_val_pass);
            case ApkSignCheck.V_FAIL: return s(R.string.sign_val_fail);
            case ApkSignCheck.V_V1_OK: return s(R.string.sign_val_v1_ok);
            case ApkSignCheck.V_V1_FILES: return s(R.string.sign_val_v1_files);
            case ApkSignCheck.V_NONE: return s(R.string.sign_val_none);
            case ApkSignCheck.V_SIGNED: return s(R.string.sign_val_signed);
            case ApkSignCheck.V_COUNT: return s(R.string.sign_val_count, r.count);
            case ApkSignCheck.V_CALC_FAIL: return s(R.string.sign_val_calc_fail);
            default: return r.value == null ? "" : r.value;
        }
    }

    /** 签名详情页：后台读取 apksig 结果，逐行展示，点击行复制 */
    private void showSignDialog(final File f) {
        toast(s(R.string.main_sign_reading));
        new Thread(new Runnable() {
            public void run() {
                final List<String[]> rows = new ArrayList<String[]>();
                for (ApkSignCheck.Row r : ApkSignCheck.detailRows(f)) {
                    rows.add(new String[]{signRowLabel(r), signRowValue(r)});
                }
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
                                .setTitle(s(R.string.main_sign_detail_title, f.getName()))
                                .setView(lv)
                                .setPositiveButton(R.string.common_close, null)
                                .show();
                    }
                });
            }
        }, "apk-sign-detail").start();
    }

    /**
     * v2.121.0：自建安装器入口，不再发 {@code ACTION_INSTALL_PACKAGE}。
     *
     * <p>Root / Shizuku 可用时走 {@code pm install} 静默安装，既不出现 ROM 安装器
     * 的推广页也不弹系统确认；无特权时回退 {@link AppInstaller} 的会话通道，进度
     * 与失败原因仍在 HexFile 界面里，仅保留 Android 强制的系统确认。</p>
     *
     * <p>取消按钮在会话 commit 后不再有效（已经交给系统），所以在进入系统确认前
     * 就收起进度条——避免用户以为还能取消，实际只关掉了一个空壳。</p>
     */
    private void installApk(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setTitle(R.string.install_title);
        pd.setMessage(getString(R.string.install_stage_prepare));
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(false);
        pd.show();
        pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,
                getString(R.string.split_cancel),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        AppInstaller.cancel();
                        d.dismiss();
                    }
                });

        Intent result = new Intent(this, InstallResultReceiver.class);
        AppInstaller.install(this, f, result, new AppInstaller.Listener() {
            public void onStage(int stage, int backend) {
                if (!pd.isShowing()) return;
                if (stage == AppInstaller.STAGE_SYSTEM_CONFIRM) {
                    dismissQuietly(pd);
                    toast(getString(R.string.install_stage_confirm));
                    return;
                }
                String name = backend == Shell.ROOT ? getString(R.string.install_backend_root)
                        : backend == Shell.SHIZUKU ? getString(R.string.install_backend_shizuku)
                        : getString(R.string.install_backend_system);
                pd.setMessage(stage == AppInstaller.STAGE_PREPARE
                        ? getString(R.string.install_stage_prepare)
                        : getString(R.string.install_stage_install, name));
            }

            public void onProgress(int percent) {
                if (pd.isShowing()) pd.setProgress(percent);
            }

            public void onResult(int code, String detail, int backend) {
                dismissQuietly(pd);
                if (isFinishing() || isDestroyed()) return;
                String text = UtilText.installResult(MainActivity.this, code, detail);
                if (code == InstallCommand.OK) {
                    toast(text);
                } else {
                    new HfDialogBuilder(MainActivity.this)
                            .setTitle(R.string.install_notif_fail)
                            .setMessage(text)
                            .setPositiveButton(R.string.common_close, null)
                            .show();
                }
            }
        });
    }

    /** 安装期间页面可能已销毁，dismiss 抛异常不该盖掉安装结果 */
    private static void dismissQuietly(ProgressDialog pd) {
        try {
            if (pd.isShowing()) pd.dismiss();
        } catch (Throwable t) {
            LogRing.add("install", "pd.dismiss: " + t);
        }
    }

    // ---------------------------------------------------------- 分包安装（v2.13.0 F-03）

    /**
     * 分包安装包的信息框：先看清单，再决定装不装。
     *
     * <p>扫描放后台线程：几百 MB 的 xapk 列条目也要一会儿，UI 线程卡一下就会被当成
     * 「点了没反应」。扫描结果出来才弹框，中间不弹「正在读取」，因为正常包一瞬就好。</p>
     */
    private void showSplitInfo(final File f) {
        final String lower = f.getName().toLowerCase();
        new Thread(new Runnable() {
            public void run() {
                SplitPackage.Contents c = null;
                String err = null;
                try {
                    c = SplitPackage.scan(f);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
                }
                final SplitPackage.Contents fc = c;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() { showSplitDialog(f, lower, fc, fe); }
                });
            }
        }, "split-scan").start();
    }

    private void showSplitDialog(final File f, String lower, SplitPackage.Contents c, String err) {
        if (isFinishing() || isDestroyed()) return;
        StringBuilder sb = new StringBuilder();
        if (c == null) {
            sb.append(getString(R.string.split_scan_failed, err));
        } else {
            sb.append(getString(R.string.split_format)).append(": ")
                    .append(SplitPackage.formatName(lower)).append('\n');
            sb.append(getString(R.string.split_size)).append(": ")
                    .append(FileOps.formatSize(f.length())).append('\n');
            sb.append(getString(R.string.split_apks)).append(": ")
                    .append(getString(R.string.split_apks_value, c.splitCount(), c.apks.size()))
                    .append('\n');
            if (!c.obb.isEmpty()) {
                sb.append(getString(R.string.split_obb)).append(": ")
                        .append(getString(R.string.split_obb_value, c.obb.size())).append('\n');
            }
            if (c.other > 0) {
                sb.append(getString(R.string.split_other)).append(": ").append(c.other);
            }
        }
        AlertDialog.Builder b = new HfDialogBuilder(this);
        b.setTitle(f.getName())
                .setMessage(sb.toString().trim())
                .setNegativeButton(R.string.split_cancel, null);
        if (c != null && c.hasApks()) {
            b.setPositiveButton(R.string.split_install,
                    new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            installSplit(f);
                        }
                    });
            b.setNeutralButton(R.string.split_browse,
                    new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            openArchiveInPane(active(), f);      // .apks 也是 zip，包内浏览照常
                        }
                    });
        }
        b.show();
    }

    /**
     * 分包安装：进度条 + 显式取消按钮，其余全交给 {@link SplitInstaller}。
     *
     * <p>取消按钮是刻意留的：xapk 动辄一两 GB，解包一半发现选错了包，没有取消只能等。
     * 进度条本身 {@code setCancelable(false)} —— 点外部/返回键关掉进度条会让用户以为
     * 安装停了，实际还在后台跑，那种「静默完成」比多给一个按钮更让人困惑。</p>
     */
    private void installSplit(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setTitle(R.string.split_progress_title);
        pd.setMessage(getString(R.string.split_progress_extract, 0));
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(false);
        pd.show();
        pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,
                getString(R.string.split_cancel),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        SplitInstaller.cancel();
                        d.dismiss();
                    }
                });
        Intent result = new Intent(this, InstallResultReceiver.class);
        SplitInstaller.install(this, f, result, new SplitInstaller.Listener() {
            public void onProgress(int percent) {
                if (!pd.isShowing()) return;
                pd.setProgress(percent);
                // 45% 之前是解包，之后是写会话（48/55 是两个阶段的分界，取 48 作判据）
                pd.setMessage(getString(percent < 48
                        ? R.string.split_progress_extract : R.string.split_progress_install, percent));
            }

            public void onResult(boolean ok, String msg) {
                if (pd.isShowing()) {
                    try {
                        pd.dismiss();
                    } catch (Throwable t) {
                        // 页面已销毁（安装期间用户退出）：dismiss 抛异常不该盖掉结果
                        LogRing.add("split", "pd.dismiss: " + t);
                    }
                }
                if (isFinishing() || isDestroyed()) return;
                if (!ok && getString(R.string.split_cancelled).equals(msg)) {
                    toast(msg);          // 用户自己点的取消，不必再弹一个「失败」框
                    return;
                }
                new HfDialogBuilder(MainActivity.this)
                        .setTitle(ok ? R.string.split_notif_ok : R.string.split_notif_fail)
                        .setMessage(msg)
                        .setPositiveButton(R.string.common_ok, null)
                        .show();
            }
        });
    }

    /** 复制文本到剪贴板并提示 */
    private void copyToClipboard(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) { toast(s(R.string.main_clip_unavailable)); return; }
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
        String show = value.length() > 40 ? value.substring(0, 40) + "…" : value;
        toast(s(R.string.main_copied_label, label, show));
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
        final boolean isSmaliDir = !isFile && TreeOps.hasSmali(f);
        // 动作码：统一用类级 A_*（O-25 前这里是本方法内的局部 final int）
        final java.util.List<String> ops = new ArrayList<String>();
        final java.util.List<Integer> acts = new ArrayList<Integer>();
        // v1.91.0：视频与流清单直接给「播放」（内置播放器），不必先绕「打开方式」
        if (isFile && (MimeUtil.isVideo(f.getName().toLowerCase())
                || MimeUtil.isPlaylist(f.getName().toLowerCase()))) {
            ops.add(s(R.string.mops_play)); acts.add(A_PLAYER);
        }
        // v1.92.0：TS 录像/切片换容器成 MP4（不重编码，秒级完成）
        if (isFile && f.getName().toLowerCase().endsWith(".ts") && looksLikeMpegTs(f)) {
            ops.add(s(R.string.mops_ts_to_mp4)); acts.add(A_TSMP4);
        }
        // 本地 m3u8（整目录下好的切片）→ 打开下载页，路径已预填
        if (isFile && f.getName().toLowerCase().endsWith(".m3u8")) {
            ops.add(s(R.string.mops_hls_merge)); acts.add(A_HLSD);
        }
        // v2.13.0（F-03）：分包安装包——「装」放最前面，其余动作照旧
        if (isFile && SplitPackage.isSplitName(f.getName().toLowerCase())) {
            ops.add(s(R.string.mops_split_install)); acts.add(A_SPLITINST);
        }
        if (isSmaliDir) { ops.add(s(R.string.mops_smali_assemble)); acts.add(A_ASSEMBLE); }
        // v1.68.0：签名文件专属操作（导入签名管理 / 查看别名与证书）
        if (isKs) { ops.add(s(R.string.mops_sign_info)); acts.add(A_KS_INFO); }
        if (isKs) { ops.add(s(R.string.mops_ks_import)); acts.add(A_KS_IMPORT); }
        if (!isFile && new java.io.File(f, "AndroidManifest.xml").isFile()) {
            ops.add(s(R.string.mops_apk_pack)); acts.add(A_APK_PACK);   // v1.57：解包目录回打包
        }
        if (isDex) { ops.add(s(R.string.mops_dexpp)); acts.add(A_DEXPP); }
        if (isDex) { ops.add(s(R.string.mops_smali_decompile)); acts.add(A_SMALI); }
        if (isDex) { ops.add(s(R.string.mops_dex_props)); acts.add(A_DEX_PROP); }
        if (isDex) { ops.add(s(R.string.mops_dex_fix)); acts.add(A_DEX_FIX); }
        if (isFile) { ops.add(s(R.string.mops_text_edit)); acts.add(A_EDIT); }
        if (isFile) { ops.add(s(R.string.mops_hash)); acts.add(A_HASH);   // v1.59：MD5/SHA1/SHA256
        }
        if (isFile && ImageBatchUtil.supported(f.getName().toLowerCase())) {
            ops.add(s(R.string.mops_img_batch)); acts.add(A_IMGBATCH);   // v1.58：缩放/转格式/水印
        }
        // v2.12.0（F-09）：图片元数据——拍摄设备/时间/是否带 GPS，发图前先看一眼
        if (isFile && FileKind.isImageExt(f.getName().toLowerCase())) {
            ops.add(s(R.string.mops_exif)); acts.add(A_EXIF);
        }
        if (!isFile) { ops.add(s(R.string.mops_dup_find)); acts.add(A_DUPFIND); }   // v1.59：内容级查重
        if (isFile) { ops.add(s(R.string.mops_hex)); acts.add(A_HEX); }
        if (isFile) { ops.add(s(R.string.mops_open_with)); acts.add(A_OPEN); }   // v1.77.0：交给系统应用
        if (isFile) { ops.add(s(R.string.mops_share)); acts.add(A_SHARE); }
        if (isFile) { ops.add(s(R.string.mops_lan)); acts.add(A_LAN); }   // v1.74.0
        ops.add(s(R.string.mops_copy_other)); acts.add(A_COPY);
        ops.add(s(R.string.mops_move_other)); acts.add(A_MOVE);
        // v1.78.0：剪贴板——先复制/剪切，再翻到任意目录点底部 📋 粘贴
        ops.add(s(R.string.mops_clip_copy)); acts.add(A_CLIP_C);
        ops.add(s(R.string.mops_clip_cut)); acts.add(A_CLIP_M);
        // 对面窗格打开了压缩包 → 增加写入压缩包动作（文件/目录均可；只读格式点击时给说明）
        final boolean archDst = dst.archiveFile != null;
        if (archDst) { ops.add(s(R.string.mops_copy_to_arch)); acts.add(A_TOZIP_C); }
        if (archDst) { ops.add(s(R.string.mops_move_to_arch)); acts.add(A_TOZIP_M); }
        ops.add(s(R.string.mops_rename)); acts.add(A_REN);
        ops.add(s(R.string.mops_zip)); acts.add(A_ZIP);
        if (!ZstdUtil.isZstName(f.getName())) { ops.add(s(R.string.mops_zstd_compress)); acts.add(A_ZSTDC); }
        if (isFile && ZstdUtil.isZstName(f.getName())) { ops.add(s(R.string.mops_zstd_extract)); acts.add(A_ZSTDD); }
        if (isFile) { ops.add(s(R.string.mops_encrypt)); acts.add(A_ENCRYPT); }
        if (isFile) { ops.add(s(R.string.mops_decrypt)); acts.add(A_DECRYPT); }
        // v2.18.0（F-22）：移入隐私空间（加密存放 + 删掉明文，才算真的藏起来）
        if (isFile) { ops.add(s(R.string.vault_move_in)); acts.add(A_VAULT); }
        if (isFile && !FileSplit.isSplitPart(f)) { ops.add(s(R.string.mops_split)); acts.add(A_SPLIT); }
        if (FileSplit.isSplitPart(f)) { ops.add(s(R.string.mops_merge)); acts.add(A_MERGE); }
        if (isFile && f.length() > 0 && !FileKind.isTextName(f.getName().toLowerCase(), f.length())) {
            ops.add(s(R.string.mops_strings)); acts.add(A_STRINGS);
        }
        ops.add(s(R.string.mops_compare)); acts.add(A_COMPARE);
        ops.add(s(R.string.common_delete)); acts.add(A_DEL);
        ops.add(s(R.string.mops_props)); acts.add(A_PROP);
        showGridMenu(f.getName(), ops, acts, new GridAction() {
            public void run(int act) { runFileAction(act, f, dst); }
        });
    }

    /** MT 风格 APK 功能菜单（对标 MT 长按 APK 的功能项，本地版裁剪） */
    private void showApkOpsMenu(final File f) {
        final Pane dst = other();
        // 动作码：统一用类级 A_*（O-25 前这里是本方法内的局部 final int）
        final java.util.List<String> ops = new ArrayList<String>();
        final java.util.List<Integer> acts = new ArrayList<Integer>();
        ops.add(s(R.string.mops_dexpp)); acts.add(A_DEXPP_MULTI);
        ops.add(s(R.string.mops_arsc_edit)); acts.add(A_ARSCPP);
        ops.add(s(R.string.mops_arscpp_edit)); acts.add(A_ARSCPP2);
        ops.add(s(R.string.mops_mfpp)); acts.add(A_MFPP);
        ops.add(s(R.string.mops_dex2smali)); acts.add(A_DEX2SMALI);
        ops.add(s(R.string.mops_extract_dex)); acts.add(A_EXTRACT_DEX);
        ops.add(s(R.string.mops_share)); acts.add(A_SHARE);
        ops.add(s(R.string.mops_open_with)); acts.add(A_OPEN);   // v1.77.0
        ops.add(s(R.string.mops_lan)); acts.add(A_LAN);   // v1.74.0
        ops.add(s(R.string.mops_sign_info)); acts.add(A_SIGN);
        ops.add(s(R.string.mops_apk_sign)); acts.add(A_SIGN_APK);
        ops.add(s(R.string.mops_apk_compare)); acts.add(A_APK_COMPARE);
        ops.add(s(R.string.mops_apk_clone)); acts.add(A_APK_CLONE);
        ops.add(s(R.string.mops_sigkill)); acts.add(A_SIGKILL);
        ops.add(s(R.string.mops_apk_info)); acts.add(A_INFO);
        ops.add(s(R.string.mops_apk_unpack)); acts.add(A_UNPACK);
        ops.add(s(R.string.mops_sign_custom)); acts.add(A_SIGN_CUSTOM);
        ops.add(s(R.string.mops_rm_sign)); acts.add(A_RM_SIGN);
        ops.add(s(R.string.vault_move_in)); acts.add(A_VAULT);   // v2.18.0（F-22）
        ops.add(s(R.string.common_delete)); acts.add(A_DEL);        ops.add(s(R.string.mops_copy_other)); acts.add(A_COPY);
        // v1.78.0：剪贴板（APK 也走同一套，翻到别的目录再粘贴）
        ops.add(s(R.string.mops_clip_copy)); acts.add(A_CLIP_C);
        ops.add(s(R.string.mops_clip_cut)); acts.add(A_CLIP_M);
        // 对面窗格打开了压缩包 → 增加写入压缩包动作（只读格式点击时给说明）
        final boolean archDst = dst.archiveFile != null;
        if (archDst) { ops.add(s(R.string.mops_copy_to_arch)); acts.add(A_TOZIP_C); }
        if (archDst) { ops.add(s(R.string.mops_move_to_arch)); acts.add(A_TOZIP_M); }
        ops.add(s(R.string.mops_rename)); acts.add(A_REN);
        ops.add(s(R.string.mops_props)); acts.add(A_PROP);
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
            case A_SMALI: decompileDexFile(f); break;   // 反编译 smali（独立 dex）
            case A_EDIT: openEditor(f); break;         // 文本编辑
            case A_HEX: openHex(f); break;            // HEX 查看
            case A_COPY:
                FileTaskEngine.get().submit(FileTask.COPY,
                        java.util.Collections.singletonList(f), dst.dir);
                toast(s(R.string.main_queued_copy_one));
                break;
            case A_MOVE:
                FileTaskEngine.get().submit(FileTask.MOVE,
                        java.util.Collections.singletonList(f), dst.dir);
                toast(s(R.string.main_queued_move_one));
                break;
            case A_REN: askRename(f); break;
            case A_ZIP: archiveAdd.askCompress(f); break;
            case A_DEL: confirmDelete(f); break;
            case A_ENCRYPT: askEncryptFile(f); break;       // 加密文件（自家格式 .enc）
            case A_DECRYPT: askDecryptFile(f); break;       // 解密 .enc 文件
            case A_VAULT:                                   // v2.18.0（F-22）移入隐私空间
                com.hex91.file.ui.VaultFlow.moveIn(this,
                        java.util.Collections.singletonList(f), new Runnable() {
                            public void run() { refreshAll(); }
                        });
                break;
            case A_PROP: showProps(f); break;
            case A_EXIF:                                   // v2.12.0（F-09）图片元数据
                startActivity(new Intent(this, ImageInfoActivity.class)
                        .putExtra(ImageInfoActivity.EXTRA_FILE, f.getAbsolutePath()));
                break;
            case A_SPLITINST: installSplit(f); break;      // v2.13.0（F-03）分包安装
            case A_ASSEMBLE: assembleDexDir(f); break;     // smali 目录编译为 dex
            case A_DEX2SMALI: extractApkDexes(f, true); break;   // APK 内全部 dex 反编译
            case A_EXTRACT_DEX: extractApkDexes(f, false); break;  // 仅提取 dex
            case A_SIGN: showSignDialog(f); break;    // 签名详情
            case A_SIGN_APK: startSign(f, false); break;  // APK 签名（内置密钥；_edit.apk 原地签）
            case A_DEXPP:                              // 独立 dex → Dex 编辑器++
                startActivity(new Intent(this, DexEditorActivity.class)
                        .putExtra(DexEditorActivity.EXTRA_FILE, f.getAbsolutePath()));
                break;
            case A_DEXPP_MULTI:                              // APK → Dex 编辑器++（MultiDex 多选）
                startActivity(new Intent(this, DexEditorActivity.class)
                        .putExtra(DexEditorActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case A_ARSCPP:                              // APK → ARSC 编辑
                startActivity(new Intent(this, ArscEditorActivity.class)
                        .putExtra(ArscEditorActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case A_ARSCPP2:                              // APK → ARSC++ 编辑
                startActivity(new Intent(this, ArscPlusActivity.class)
                        .putExtra(ArscPlusActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case A_MFPP:                              // APK → Manifest 编辑器++
                startActivity(new Intent(this, ManifestEditorActivity.class)
                        .putExtra(ManifestEditorActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case A_TOZIP_C:                              // 复制到对面窗格已打开的压缩包
                if (dst.archiveFile != null) archiveAdd.askAddToArchive(f, dst, false);
                break;
            case A_TOZIP_M:                              // 移动到对面窗格已打开的压缩包
                if (dst.archiveFile != null) archiveAdd.askAddToArchive(f, dst, true);
                break;
            case A_COMPARE:                              // 对比…（自动判别 目录/文本/哈希）
                askCompareTarget(f, false);
                break;
            case A_APK_COMPARE:                              // APK 对比
                askCompareTarget(f, true);
                break;
            case A_APK_CLONE:                              // APK 共存
                askApkClone(f);
                break;
            case A_SIGKILL:                              // 去除签名校验（SigKill）
                signFlow.doSigKill(f);
                break;
            case A_SPLIT:                              // 文件分割（对标 MT）
                askSplitSize(f);
                break;
            case A_MERGE:                              // 分卷合并
                doMerge(f);
                break;
            case A_ZSTDC:                              // Zstd 压缩（文件→.zst，目录→.tar.zst）
                doZstdCompress(f);
                break;
            case A_ZSTDD:                              // Zstd 解压（.zst/.tar.zst/.tzst）
                doZstdExtract(f);
                break;
            case A_STRINGS:                              // strings 提取（二进制可读字符串）
                startActivity(new Intent(this, StringsViewerActivity.class)
                        .putExtra(StringsViewerActivity.EXTRA_FILE, f.getAbsolutePath()));
                break;
            case A_INFO:                              // APK 信息（v1.57）
                startActivity(new Intent(this, ApkInfoActivity.class)
                        .putExtra(ApkInfoActivity.EXTRA_APK, f.getAbsolutePath()));
                break;
            case A_UNPACK:                              // APK 解包（v1.57）
                doApkUnpack(f);
                break;
            case A_SIGN_CUSTOM:                              // 自定义密钥签名（v1.57）
                signFlow.askCustomSign(f, false);
                break;
            case A_RM_SIGN:                              // 去除签名 → 未签名 APK（v1.57）
                doRemoveSignature(f);
                break;
            case A_APK_PACK:                              // 解包目录 → 打包为 APK（v1.57）
                doApkPack(f);
                break;
            case A_IMGBATCH:                              // 图片处理（缩放/转格式/水印，v1.58）
                askImageBatch(java.util.Collections.singletonList(f.getAbsolutePath()));
                break;
            case A_HASH:                              // 计算哈希（MD5/SHA1/SHA256，v1.59）
                doHash(f);
                break;
            case A_DUPFIND:                              // 查找重复文件（v1.59）
                startActivity(new Intent(this, DupFinderActivity.class)
                        .putExtra(DupFinderActivity.EXTRA_ROOT, f.getAbsolutePath()));
                break;
            case A_DEX_PROP:                              // Dex 属性（v1.66.5）
                showDexProps(f);
                break;
            case A_DEX_FIX:                              // Dex 修复（v1.66.5）
                askDexFix(f);
                break;
            case A_KS_INFO:                              // 签名文件 → 签名信息（v1.68.0）
                showKeystoreInfo(f);
                break;
            case A_KS_IMPORT:                              // 签名文件 → 导入签名管理（v1.68.0）
                importKeystore(f);
                break;
            case A_SHARE:                              // 分享（v1.72.0，APK 与普通文件共用）
                shareFile(f);
                break;
            case A_LAN:                              // 局域网快传（v1.74.0）
                openLanShare(f);
                break;
            case A_OPEN:                              // 打开方式（v1.77.0）
                if (!OpenWith.open(this, f)) toast(s(R.string.main_no_app_for_type));
                break;
            case A_CLIP_C:                              // 复制到剪贴板（v1.78.0）
                FileClipboard.get().put(java.util.Collections.singletonList(f), false);
                updatePasteButton();
                toast(s(R.string.main_clip_copy_done, f.getName()));
                break;
            case A_PLAYER:                              // v1.91.0：内置播放器
                openVideo(f);
                break;
            case A_TSMP4:                              // v1.92.0：TS → MP4（转封装）
                remuxToMp4(f);
                break;
            case A_HLSD:                              // v1.92.0：本地 m3u8 → 下载/合并页
                startActivity(new Intent(MainActivity.this, HlsDownloadActivity.class)
                        .putExtra(HlsDownloadActivity.EXTRA_URL, f.getAbsolutePath()));
                break;
            case A_CLIP_M:                              // 剪切到剪贴板
                FileClipboard.get().put(java.util.Collections.singletonList(f), true);
                updatePasteButton();
                toast(s(R.string.main_clip_cut_done, f.getName()));
                break;
            default:                              // O-25：没有 case 的号不再静默
                toast(s(R.string.main_unknown_action, act));
                break;
        }
    }

    /** 带着文件进入局域网快传页：对方设备在雷达上点一下就发 */
    private void openLanShare(File f) {
        if (f == null || !f.isFile()) {
            toast(s(R.string.main_lan_files_only));
            return;
        }
        try {
            Intent i = new Intent(this, LanShareActivity.class);
            i.putExtra(LanShareActivity.EXTRA_PATH, f.getAbsolutePath());
            startActivity(i);
        } catch (Throwable t) {
            toast(s(R.string.main_open_fail, t.getMessage()));
        }
    }

    // ============================================================ APK 共存（v1.20.0）

    /** APK 共存入口：后台读原包名 → 弹框输入新包名 → 改清单重签名 */
    private void askApkClone(final File f) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.main_clone_reading));
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
                        if (e != null) { toast(s(R.string.ksm_err_read, e)); return; }
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
                .setTitle(R.string.mops_apk_clone)
                .setMessage(s(R.string.main_clone_msg, oldPkg == null
                        ? s(R.string.main_clone_old_unknown) : oldPkg))
                .setView(et)
                .setPositiveButton(R.string.main_clone_generate,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String np = et.getText().toString().trim();
                        if (!np.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
                            toast(s(R.string.main_clone_bad_pkg));
                            return;
                        }
                        doApkClone(f, np);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void doApkClone(final File f, final String newPkg) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.main_clone_working));
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
                    info = s(R.string.main_clone_info, r.oldPkg, r.newPkg,
                            r.replaced, out.getAbsolutePath());
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
                                    .setTitle(R.string.main_clone_fail_title).setMessage(e)
                                    .setPositiveButton(R.string.common_got_it, null).show();
                        } else {
                            refreshAll();
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle(R.string.main_clone_done_title)
                                    .setMessage(ok)
                                    .setPositiveButton(R.string.common_close, null)
                                    .show();
                        }
                    }
                });
            }
        }, "apk-clone").start();
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
        msg.setText(s(R.string.main_split_size_msg, FileOps.formatSize(f.length())));
        msg.setTextSize(14);
        msg.setTextColor(ThemeManager.textSub(this));
        msg.setPadding((int) (20 * den), (int) (14 * den), (int) (20 * den), (int) (4 * den));
        box.addView(msg);
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(s(R.string.main_split_per_vol, labels[i]));
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
                .setTitle(R.string.mops_split)
                .setView(box)
                .show();
    }

    private void doSplit(final File f, final long chunkSize, final String chunkLabel) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.main_split_working, chunkLabel));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    java.util.List<File> parts = FileSplit.split(f, f.getParentFile(), chunkSize);
                    info = s(R.string.main_split_info, parts.size(), chunkLabel,
                            parts.get(0).getName(), parts.get(parts.size() - 1).getName(),
                            f.getName(), f.getParent());
                } catch (Throwable t) {
                    err = FsText.errorText(MainActivity.this, t);
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            toast(s(R.string.main_split_fail, e));
                        } else {
                            refreshAll();
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle(R.string.main_split_done_title)
                                    .setMessage(ok)
                                    .setPositiveButton(R.string.common_close, null)
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
        pd.setMessage(s(R.string.main_merge_working));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    FileSplit.MergeResult r = FileSplit.merge(f, f.getParentFile());
                    info = s(R.string.main_merge_info, r.parts,
                            r.output.getAbsolutePath(), FileOps.formatSize(r.bytes));
                } catch (Throwable t) {
                    err = FsText.errorText(MainActivity.this, t);
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle(R.string.main_merge_fail_title).setMessage(e)
                                    .setPositiveButton(R.string.common_got_it, null).show();
                        } else {
                            refreshAll();
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle(R.string.main_merge_done_title)
                                    .setMessage(ok)
                                    .setPositiveButton(R.string.common_close, null)
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
        pd.setMessage(s(R.string.main_zstd_c_working));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    java.util.List<File> srcs = new java.util.ArrayList<File>();
                    srcs.add(f);
                    ZstdUtil.compress(use, srcs, ZstdUtil.DEFAULT_LEVEL);
                    info = s(R.string.main_zstd_info, use.getAbsolutePath(),
                            FileOps.formatSize(use.length()),
                            FileOps.formatSize(f.isFile() ? f.length() : dirSize(f)),
                            ZstdUtil.DEFAULT_LEVEL);
                } catch (Throwable t) {
                    err = FsText.errorText(MainActivity.this, t);
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            toast(s(R.string.main_zstd_c_fail, e));
                        } else {
                            refreshAll();
                            toast(s(R.string.main_zstd_c_done, use.getName()));
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
        pd.setMessage(s(R.string.main_zstd_x_working));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                try {
                    File r = ZstdUtil.extract(f, outDir);
                    info = s(R.string.main_zstd_x_info, r.getAbsolutePath());
                } catch (Throwable t) {
                    err = FsText.errorText(MainActivity.this, t);
                }
                final String e = err, ok = info;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            toast(s(R.string.main_zstd_x_fail, e));
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
        et.setHint(R.string.main_cmp_path_hint);
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
        bBrowse.setText(R.string.main_cmp_browse);
        UiDecor.barButton(bBrowse, this, UiDecor.KIND_SOFT, 12f);
        bBrowse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickComparePath(f, startDir, et); }
        });
        Button bOther = new Button(this);
        bOther.setText(R.string.main_cmp_other_pane);
        UiDecor.barButton(bOther, this, UiDecor.KIND_SOFT, 12f);
        UiDecor.gap(bOther, this, 4f);
        bOther.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { et.setText(startDir); }
        });
        row.addView(bBrowse, blp);
        row.addView(bOther, blp);
        box.addView(row);
        final android.widget.CheckBox cbHash = new android.widget.CheckBox(this);
        cbHash.setText(R.string.main_cmp_hash);
        cbHash.setTextSize(13);
        cbHash.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(cbHash);
        new HfDialogBuilder(this)
                .setTitle(apkMode
                        ? (f.isDirectory() ? R.string.main_cmp_title_apk_dir
                                : R.string.main_cmp_title_apk_file)
                        : (f.isDirectory() ? R.string.main_cmp_title_dir
                                : R.string.main_cmp_title_file))
                .setMessage(prefill.isEmpty()
                        ? s(R.string.main_cmp_msg_a, f.getAbsolutePath())
                        : s(R.string.main_cmp_msg_a_default, f.getAbsolutePath(), prefill))
                .setView(box)
                .setPositiveButton(R.string.main_cmp_start,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String p = et.getText().toString().trim();
                        if (p.isEmpty()) { toast(s(R.string.main_cmp_need_path)); return; }
                        File b = new File(p);
                        if (!b.exists()) { toast(s(R.string.main_cmp_not_exist, p)); return; }
                        startCompare(f, b, apkMode, cbHash.isChecked());
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
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
                holder[0].setTitle(s(R.string.main_cmp_pick_title_path, d.getAbsolutePath()));
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
                if (f.isDirectory()) { toast(s(R.string.main_cmp_pick_dir_a)); return; }
                et.setText(k.getAbsolutePath());
                holder[0].dismiss();
            }
        });
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.addView(lv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        holder[0] = new HfDialogBuilder(this)
                .setTitle(R.string.main_cmp_pick_title)
                .setView(wrap)
                .setPositiveButton(R.string.main_cmp_pick_ok,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        et.setText(cur[0].getAbsolutePath());
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
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
            boolean textA = FileKind.isTextName(la, a.length()) || FileKind.isTextName(lb, b.length());
            // 任一像文本且都能按文本解码 → 文本对比；否则哈希对比
            mode = textA ? CompareActivity.MODE_TEXT : CompareActivity.MODE_HASH;
        } else {
            toast(s(R.string.main_cmp_type_mismatch));
            return;
        }
        startActivity(new Intent(this, CompareActivity.class)
                .putExtra(CompareActivity.EXTRA_MODE, mode)
                .putExtra(CompareActivity.EXTRA_PATH_A, a.getAbsolutePath())
                .putExtra(CompareActivity.EXTRA_PATH_B, b.getAbsolutePath())
                .putExtra(CompareActivity.EXTRA_VERIFY_HASH, verifyHash));
    }

    /** smali 目录后台编译为 dex → 输出到同级 <目录名>.dex */
    private void assembleDexDir(final File dir) {
        final File out = new File(dir.getParentFile(), dir.getName() + ".dex");
        toast(s(R.string.main_asm_running));
        new Thread(new Runnable() {
            public void run() {
                final String err = SmaliTool.assemble(dir, out, 4);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err == null) {
                            toast(s(R.string.main_asm_done, out.getPath()));
                            refreshAll();
                        } else {
                            new HfDialogBuilder(MainActivity.this)
                                    .setTitle(R.string.main_asm_fail_title)
                                    .setMessage(s(R.string.main_asm_fail_msg,
                                            SmaliReport.err(MainActivity.this, err)))
                                    .setPositiveButton(R.string.common_close, null)
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
                ? new File(WorkDir.base(), "Smali/" + FileKind.stripExt(apk.getName()))
                : new File(other().dir, FileKind.stripExt(apk.getName()) + "_dex");
        toast(decompile ? s(R.string.main_dex_decompiling) : s(R.string.main_dex_extracting));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(cacheTmp);
                    cacheTmp.mkdirs();
                    List<String> dexes = apkDexEntries(apk);
                    if (dexes.isEmpty()) { err = s(R.string.main_dex_none_in_apk); }
                    for (String entry : dexes) {
                        if (err != null) break;
                        if (!ZipUtil.extractEntry(apk, entry, cacheTmp)) {
                            err = s(R.string.main_extract_entry_fail, entry); break;
                        }
                        File tmpDex = new File(cacheTmp, entry);
                        if (decompile) {
                            String base = new File(entry).getName();
                            if (base.endsWith(".dex")) base = base.substring(0, base.length() - 4);
                            err = SmaliTool.decompile(tmpDex, new File(outRoot, base), 4);
                        } else {
                            FileOps.copy(tmpDex, outRoot);   // 复制保留原名
                            if (!new File(outRoot, tmpDex.getName()).exists())
                                err = s(R.string.main_write_entry_fail, entry);
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
                            toast(decompile
                                    ? s(R.string.main_baksmali_done, outRoot.getPath())
                                    : s(R.string.main_dex_extracted, outRoot.getPath()));
                            refreshAll();
                            if (decompile) {
                                Intent i = new Intent(MainActivity.this, MainActivity.class);
                                i.putExtra("dir", outRoot.getAbsolutePath());
                                startActivity(i);   // singleTop：onNewIntent 跳转
                            }
                        } else {
                            toast(s(R.string.main_fail,
                                    SmaliReport.err(MainActivity.this, ferr)));
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
            toast(s(R.string.main_share_files_only));
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
            Intent chooser = Intent.createChooser(send, s(R.string.main_share_chooser, f.getName()));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Throwable t) {
            toast(s(R.string.main_share_fail, t.getMessage()));
        }
    }

    private void askRename(final File f) {
        final EditText et = new EditText(this);
        et.setText(f.getName());
        new HfDialogBuilder(this)
                .setTitle(R.string.mops_rename)
                .setView(et)
                .setPositiveButton(R.string.common_confirm,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (!name.isEmpty() && FileOps.rename(f, name)) {
                            toast(s(R.string.main_rename_ok));
                            refreshAll();
                        } else {
                            toast(s(R.string.main_rename_fail));
                        }
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 加密文件（自家私有格式 .enc，需本工具解密） */
    private void askEncryptFile(final File f) {
        final float den = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * den), (int) (8 * den), (int) (24 * den), (int) (8 * den));
        final EditText pw = new EditText(this);
        pw.setHint(R.string.main_pw_hint_enc);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText pw2 = new EditText(this);
        pw2.setHint(R.string.main_pw_hint_confirm);
        pw2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pw);
        root.addView(pw2);
        new HfDialogBuilder(this)
                .setTitle(R.string.mops_encrypt)
                .setMessage(s(R.string.main_enc_msg, f.getName()))
                .setView(root)
                .setPositiveButton(R.string.main_enc_btn,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String p = pw.getText().toString();
                        String p2 = pw2.getText().toString();
                        if (p.isEmpty()) { toast(s(R.string.main_pw_empty)); return; }
                        if (!p.equals(p2)) { toast(s(R.string.main_pw_mismatch)); return; }
                        runCrypt(f, p, true);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /**
     * 后台执行加/解密并显示进度对话框。
     * PBKDF2（10 万次迭代）+ 流式加解密耗时明显，绝不可在 UI 线程执行。
     */
    private void runCrypt(final File f, final String pw, final boolean enc) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(enc ? R.string.mops_encrypt : R.string.mops_decrypt);
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
                            toast(enc ? s(R.string.main_crypt_done_enc, out.getName())
                                    : s(R.string.main_crypt_done_dec, out.getName()));
                            refreshAll();
                        }
                    });
                } catch (final SecurityException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast(s(R.string.main_crypt_bad_pw));
                        }
                    });
                } catch (final IllegalArgumentException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast(cryptError(e));
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast(enc ? s(R.string.main_crypt_fail_enc, e.getMessage())
                                    : s(R.string.main_crypt_fail_dec, e.getMessage()));
                        }
                    });
                }
            }
        }, "file-crypt").start();
    }

    /** 加解密失败码 → 文案（F-14/16：FileCrypt 只给 {@link FileCrypt.Code}） */
    private String cryptError(IllegalArgumentException e) {
        if (!(e instanceof FileCrypt.Fail)) {
            String m = e.getMessage();
            return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
        }
        FileCrypt.Fail f = (FileCrypt.Fail) e;
        switch (f.code) {
            case SRC_MISSING:
                return s(R.string.crypt_src_missing);
            case PW_EMPTY:
                return s(R.string.main_pw_empty);
            case ENC_MISSING:
                return s(R.string.crypt_enc_missing);
            case CORRUPT:
                return s(R.string.crypt_corrupt);
            case NOT_OURS:
                return s(R.string.crypt_not_ours);
            case BAD_VERSION:
                return s(R.string.crypt_bad_version, f.num());
            default:
                return s(R.string.crypt_bad_header);
        }
    }

    /** 解密 .enc 文件（自家私有格式） */
    private void askDecryptFile(final File f) {
        final EditText pw = new EditText(this);
        pw.setHint(R.string.main_pw_hint_dec);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new HfDialogBuilder(this)
                .setTitle(R.string.mops_decrypt)
                .setMessage(s(R.string.main_dec_msg, f.getName()))
                .setView(pw)
                .setPositiveButton(R.string.main_dec_btn,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String p = pw.getText().toString();
                        if (p.isEmpty()) { toast(s(R.string.main_pw_empty)); return; }
                        runCrypt(f, p, false);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void confirmDelete(final File f) {
        new HfDialogBuilder(this)
                .setTitle(R.string.main_del_title)
                .setMessage(s(R.string.main_del_msg, f.getName()))
                .setPositiveButton(R.string.main_del_do,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        Trash.Entry e = Trash.moveToTrash(f);
                        toast(s(e != null ? R.string.main_trashed : R.string.main_del_fail));
                        refreshAll();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
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
                    if (!alreadySelMode) toast(s(R.string.main_sel_mode_hint));
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
            toast(s(R.string.main_back_up));
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
        ((Button) findViewById(R.id.btn_sel_done)).setText(s(R.string.main_sel_done, n));
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
            toast(s(R.string.main_batch_rename_plain_only));
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
        if (sel.isEmpty()) { toast(s(R.string.main_nothing_selected)); return; }
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
                archiveAdd.addToArchive(realFiles.toArray(new File[0]), dst, null, isMove);
                return;
            }
            toast(s(R.string.main_nothing_selected));
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
        toast(isMove ? s(R.string.main_queued_move, realFiles.size(), dst.dir.getPath())
                : s(R.string.main_queued_copy, realFiles.size(), dst.dir.getPath()));
        exitSelection(selPanel);
    }

    /** 批量提取压缩包条目到目标窗格目录；alsoDelete=true 时提取后从压缩包删除（仅 ZIP/APK） */
    private void extractEntriesBatch(final Pane p, final List<FileItem> entries,
            final Pane dst, final boolean alsoDelete) {
        toast(alsoDelete ? s(R.string.main_extract_del_running) : s(R.string.main_extract_running));
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
                    if (alsoDelete && ArchiveWrite.canWrite(p.archiveFile) && !done.isEmpty()) {
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
                        if (ferr != null) toast(s(R.string.main_extract_partial_fail, fok, ferr));
                        else toast(alsoDelete ? s(R.string.main_extract_del_done, fok)
                                : s(R.string.main_extract_done, fok));
                        if (alsoDelete && ApkEditSession.isApk(p.archiveFile)
                                && ApkEditSession.hasEdits(p.archiveFile))
                            toast(s(R.string.main_written_back));
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
        if (sel.isEmpty()) { toast(s(R.string.main_nothing_selected)); return; }
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
                .setTitle(s(R.string.main_batch_delete))
                .setMessage(s(R.string.main_batch_delete_msg, realFiles.size()))
                .setPositiveButton(s(R.string.common_delete), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int ok = 0;
                        for (File f : realFiles) if (Trash.moveToTrash(f) != null) ok++;
                        toast(s(R.string.main_batch_deleted, ok, realFiles.size()));
                        exitSelection(p);
                        refreshAll();
                    }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
                .show();
    }

    // ---------- 书签 ----------

    private void showBookmarks() {
        final BookmarkStore store = new BookmarkStore(this);
        final List<String> marks = store.all();
        final String cur = active().dir.getAbsolutePath();
        String[] items = new String[marks.size() + 2];
        for (int i = 0; i < marks.size(); i++) items[i] = marks.get(i);
        items[marks.size()] = s(R.string.main_bm_add_cur, cur);
        items[marks.size() + 1] = s(R.string.main_bm_del);
        new HfDialogBuilder(this)
                .setTitle(R.string.main_bookmarks)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which < marks.size()) {
                            File f = new File(marks.get(which));
                            if (f.isDirectory()) loadDir(active(), f);
                            else toast(s(R.string.main_bm_dir_missing, marks.get(which)));
                        } else if (which == marks.size()) {
                            toast(s(store.add(cur) ? R.string.main_bm_added : R.string.main_bm_exists));
                        } else {
                            showBookmarkRemove(store, marks);
                        }
                    }
                })
                .setNegativeButton(R.string.common_close, null)
                .show();
    }

    private void showBookmarkRemove(final BookmarkStore store, final List<String> marks) {
        if (marks.isEmpty()) { toast(s(R.string.main_bm_none)); return; }
        new HfDialogBuilder(this)
                .setTitle(R.string.main_bm_del_title)
                .setItems(marks.toArray(new String[marks.size()]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                store.remove(marks.get(which));
                                toast(s(R.string.main_bm_deleted, marks.get(which)));
                            }
                        })
                .setNegativeButton(R.string.main_bm_done, null)
                .show();
    }

    // ---------- 最近打开 ----------

    private void showRecent() {
        final RecentStore store = new RecentStore(this);
        final List<RecentStore.Item> items = store.list();
        if (items.isEmpty()) { toast(s(R.string.main_recent_none)); return; }
        String[] opts = new String[items.size() + 1];
        for (int i = 0; i < items.size(); i++) {
            RecentStore.Item it = items.get(i);
            opts[i] = it.name() + "\n" + FileOps.formatTime(it.time) + "  ·  " + it.path;
        }
        opts[items.size()] = s(R.string.main_recent_clear);
        new HfDialogBuilder(this)
                .setTitle(R.string.main_recent)
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w >= items.size()) {
                            store.clear();
                            toast(s(R.string.main_recent_cleared));
                            return;
                        }
                        RecentStore.Item it = items.get(w);
                        File f = new File(it.path);
                        if (!f.exists()) { toast(s(R.string.main_recent_gone, it.path)); return; }
                        onFileClick(f);
                    }
                })
                .setNegativeButton(R.string.common_close, null)
                .show();
    }

    // ============================================================ 外部导入（v2.12.0，F-17）

    /**
     * 处理「别的应用把文件递给我们」：分享（{@code ACTION_SEND} / {@code SEND_MULTIPLE}）
     * 与「用 HexFile 打开」（{@code ACTION_VIEW}）。
     *
     * <p>分两步，是因为落点必须让用户确认：用户正在 /sdcard/Download 里看东西，
     * 微信分享进来一个 apk，直接塞进当前目录是对的；但正在浏览系统目录时，
     * 塞进 /system 必然失败 —— 所以给一次「选择目录」的机会，默认走当前窗格。</p>
     *
     * <p>处理完立刻 {@link #consumeIncoming} 把 Intent 掏空：横竖屏切换会重建 Activity
     * 并重新读同一份 getIntent()，不清掉就会再导入一遍（用户看到两份文件）。</p>
     */
    private void handleIncoming(Intent intent) {
        if (!IntentImport.isIncoming(intent)) return;
        final boolean openAfter = IntentImport.isView(intent);
        final List<IntentImport.Item> items = IntentImport.collect(this, intent);
        consumeIncoming(intent);
        if (items.isEmpty()) {
            toast(s(R.string.main_import_none));
            return;
        }
        long total = 0;
        boolean unknown = false;
        for (IntentImport.Item it : items) {
            if (it.size > 0) total += it.size;
            else unknown = true;
        }
        String size = total > 0
                ? FileOps.formatSize(total) + (unknown ? s(R.string.main_import_size_above) : "")
                : s(R.string.main_import_size_unknown);
        new HfDialogBuilder(this)
                .setTitle(R.string.main_import_title)
                .setMessage(s(R.string.main_import_msg, items.size(), size,
                        dirForImport().getName()))
                .setPositiveButton(R.string.main_import_here,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        startImport(items, dirForImport(), openAfter);
                    }
                })
                .setNeutralButton(R.string.main_import_pick,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        DirPickerDialog.show(MainActivity.this, dirForImport(),
                                new DirPickerDialog.OnPick() {
                                    public void onPick(File dir) {
                                        startImport(items, dir, openAfter);
                                    }
                                });
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 掏空已经处理过的导入 Intent，避免重建 Activity 时重复导入 */
    private void consumeIncoming(Intent it) {
        if (it == null) return;
        it.setAction(Intent.ACTION_MAIN);
        it.setData(null);          // setData 同时清掉 type
        it.setClipData(null);
        it.removeExtra(Intent.EXTRA_STREAM);
    }

    /** 导入落点：正在浏览压缩包时落到压缩包所在目录（压缩包内不能直接放文件） */
    private File dirForImport() {
        Pane p = active();
        if (p == null) return new File("/sdcard");
        if (p.archiveFile != null) {
            File par = p.archiveFile.getParentFile();
            if (par != null) return par;
        }
        return p.dir != null ? p.dir : new File("/sdcard");
    }

    /**
     * 真正的复制：后台流式搬字节 + 横条进度 + 可取消，完成后刷新两栏。
     *
     * <p>进度条用取消按钮而不是 {@code setCancelable(true)}：导入 1 GB 文件时误触返回键
     * 会留下半截文件（虽然 {@code .part} 会被清掉，但用户看到的是「什么都没发生」），
     * 显式按钮至少让「取消」是一次有意的操作。</p>
     */
    private void startImport(final List<IntentImport.Item> items, final File dstDir,
                             final boolean openAfter) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(R.string.main_import_pd_title);
        pd.setMessage(s(R.string.main_import_preparing));
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(false);
        final boolean[] cancel = {false};
        pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, s(R.string.common_cancel),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        cancel[0] = true;    // 仅置标记：真正的停在 128KB 检查点上
                    }
                });
        pd.show();
        final int[] shown = {-1};
        Bg.io(new Runnable() {
            public void run() {
                final IntentImport.Result r = IntentImport.copyInto(MainActivity.this, items, dstDir,
                        new IntentImport.Watch() {
                            public void onProgress(final String name, final long done, final long total) {
                                Bg.ui(new Runnable() {
                                    public void run() {
                                        if (!pd.isShowing()) return;
                                        pd.setMessage(name + "  " + FileOps.formatSize(done)
                                                + (total > 0 ? " / " + FileOps.formatSize(total) : ""));
                                        if (total > 0) {
                                            int pct = (int) (done * 100 / total);
                                            if (pct != shown[0]) {
                                                shown[0] = pct;
                                                pd.setProgress(pct);
                                            }
                                        }
                                    }
                                });
                            }

                            public boolean cancelled() {
                                return cancel[0];
                            }
                        });
                Bg.ui(new Runnable() {
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable t) {
                            // 界面已销毁（导入期间用户退出）时 dismiss 可能抛；
                            // 记一行就够，不要用 toast 打扰已经离开的用户
                            LogRing.add("import", "pd.dismiss: " + t);
                        }
                        String msg = UtilText.importSummary(MainActivity.this, r);
                        if (r.errors.size() > 0)
                            msg = msg + "\n" + UtilText.importError(MainActivity.this, r, 0);
                        toast(msg);
                        refreshAll();
                        // 「用 HexFile 打开」：导入后直接按类型打开；多选导入只落盘不抢焦点
                        if (openAfter && r.files.size() == 1 && active() != null
                                && active().archiveFile == null) {
                            onFileClick(r.files.get(0));
                        }
                    }
                });
            }
        });
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
        handleIncoming(intent);
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
        // 签名簇的请求码与「待签 APK」状态都在控制器里（v2.7.0）：这里只转发一次，
        // 不再由宿主判断 res/data 是否齐全——那三处判断原来和字段生命周期纠缠在一起。
        if (signFlow.handleResult(req, res, data)) return;
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
            toast(s(R.string.main_exit_hint));
        }
    }

    private void askNewFolder() {
        final Pane p = active();
        // v2.13.1（F-11）：压缩包内也能新建目录了（可写格式 + 非 APK，判定在控制器里）
        if (p.archiveFile != null) { archCtl.archNewFolder(p); return; }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(this)
                .setTitle(s(R.string.main_new_folder_title, p.dir.getName()))
                .setView(et)
                .setPositiveButton(s(R.string.common_create), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (!name.isEmpty()) {
                            FileOps.newFolder(p.dir, name);
                            refreshAll();
                        }
                    }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
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

    /** 取资源文案（F-14：页面文案全部走 values/values-en，不在代码里留硬编码） */
    private String s(int id) {
        return getString(id);
    }

    private String s(int id, Object... args) {
        return getString(id, args);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        if (req == REQ_STORAGE) {
            boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) toast(s(R.string.main_storage_perm_limited));
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
        toast(s(R.string.main_perm_all_files));
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
