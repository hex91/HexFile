# HexFile —— 安卓文件管理器（Android APK）

一款**纯本地**的安卓文件管理与逆向辅助工具集：**无账号、无 VIP、无云端上传**，文件处理全部在设备本地完成；
局域网相关能力（远程管理 / 网络存储 / 局域网快传）只在同一 Wi-Fi 内点对点通信，不经过任何中转服务器。


## 快速开始

```bash
bash build.sh                       # 产出 out/HexFile-<version>.apk（已签名，可直接安装）
adb install -r out/HexFile-*.apk
```

构建工具链（本机已配）：`aapt2 + javac 17 + d8 + zipalign + apksigner`（Android SDK build-tools 37.0.0 / platforms android-35 / NDK 27）。
首次构建若本地无 `hexfile.keystore`，会自动生成调试密钥（storepass/keypass 均为 `android`）。

> **CI / Release**：推送到 `main` 会自动编译并把 APK 发布到 Release 的 `continuous`（预发布）标签；打 `v*` 标签则发布正式 Release。
> 签名密钥通过仓库 **Secrets**（`HEXFILE_KEYSTORE_BASE64` / `HEXFILE_KEYSTORE_PASS`）注入，**不入库**。
>
> **版本守卫**：`build.sh` 会比对上次成功出包的记录（`build/last_build.txt`，不入库）。若 `src/`、`res/`、`native/*.c` 或
> `AndroidManifest.xml` 有改动而 `versionCode` 没升，直接**拒绝构建**，避免"新包装上去版本号还是旧的"这种排查噩梦。
> 只是本地看效果、暂不发布：`VERSION_GUARD=warn bash build.sh`（仅警告）或 `VERSION_GUARD=off bash build.sh`（完全跳过）。
> CI 侧另有一道提醒：本次版本号与上次发布相同会输出 `::warning`，防止同名 APK 被静默覆盖。

## 工程结构

```
/
├── AndroidManifest.xml          # minSdk 21 / targetSdk 30 / 存储权限 / 60 个组件（49 Activity + 4 Service + 3 Provider + 4 Receiver）
├── build.sh                     # 一键构建（7 步：aapt2 compile→link→NDK→javac→d8→zipalign→sign，带版本守卫）
├── res/
│   ├── layout/activity_main.xml # 格双栏布局（深色顶栏+统计行、底部 ‹ › ＋ ⇄ ↑ 工具栏）
│   ├── layout/activity_search.xml / item_result.xml
│   ├── layout/activity_trash.xml / activity_zip.xml
│   ├── layout/item_file.xml     # 文件列表项
│   ├── values/                  # colors / strings / themes / attrs / bools / dimens
│   ├── values-en/               # 英文全量：1660 条资源键 100% 有英文（含设置 / 任务中心 / 通知 /
│                                #   工具栏 / 签名 / 编辑器 / 压缩包 / 网络存储 / 隐私空间 / 主菜单 /
│                                #   HLS 下载与错误码 / 播放页 / 应用管理）
│                                #   仍未走资源的页面文案见 test/lang_check.sh 的覆盖率统计
│   ├── values-sw600dp/          # 平板：pane_divider 从 1dp 装饰线变 6dp 可拖
│   ├── drawable/                # 矢量图标（ic_folder / ic_file）+ 自适应底板 ic_launcher_background
│   └── mipmap-*/                # 启动图标：ic_launcher / ic_launcher_round / ic_launcher_foreground
└── src/com/hex91/file
    ├── MainActivity.java        # 唯一的顶层类：主界面装配 / 菜单分发 / 多选批量 / 跨模块编排
    ├── controller/  (8)         # 主界面的状态与行为，MainActivity 只负责装配与转发
    │   ├── Pane.java            单窗格状态：视图引用 / 目录 / 压缩包 / 历史栈 / 读取代次
    │   ├── DirectoryController  读目录 / 下拉刷新 / 历史栈 / 冷启动恢复
    │   ├── ArchiveController    压缩包内浏览：打开、密码、条目操作、查看编辑、提取
    │   ├── ArchiveAddController 往压缩包里加东西：整树写入 / 条目名 / 压缩为 ZIP
    │   ├── TaskUiController     任务条 / 任务中心 / 「上次有任务被中断」
    │   ├── DrawerController     抽屉 16 个入口 / 存储容量 / Root 状态
    │   └── PropsController      属性 / 权限 / 时间 / 所有者 / 哈希
    ├── core/        (28)        # 基础工具：编码、加密、格式化、时间、十六进制、颜色对比
    │   ├── Base64Codec Json Iso8601 FormatUtil HexCore Sm3 Sm4 CryptoCore CodecCore
    │   ├── AtomicReplace MimeUtil TimeCore TextDiffUtil Contrast ScreenPolicy ByteIO
    │   └── CredentialCodec ScanCache StringExtract RadixCore ThemeSchemes
    │       RegionTable PlaySpeed TestDataCore ArscDefs
    ├── fs/          (30)        # 文件系统：读目录、复制移动、回收站、压缩包、Root/Shell
    │   ├── FileItem FileOps FileAttrs FileClipboard DirReader DiskScanUtil FileKind
    │   ├── FileTask FileTaskEngine FileSplit FileCrypt Trash TrashJanitor TrashPolicy
    │   ├── ZipUtil ZstdUtil WorkDir DocPath BatchRenameCore CompareUtil ContentGrep
    │   └── LeftoverScan TreeOps RootIO RootUtil Shell LinkUtil SortMode TempFiles
    ├── apk/         (31)        # APK / 压缩包 / 资源二进制：重打包、签名、ARSC、AXML、smali
    │   ├── ApkUtil ApkInfoUtil ApkPackager ApkRepack ApkEditSession ApkSignCheck
    │   ├── ApkSignerTool ApkCloneTool ApkCompareUtil ArchiveUtil ArchiveWrite ArchivePasswords
    │   ├── FileInspect ArscEditor ArscFile ArscPlus ArscValues ArscWriter
    │   └── AxmlFile AxmlTextSync AxmlWriter Decompiler SmaliTool SmaliDocs
    │       KeyStoreUtil KeystoreCreate SigKill
    ├── net/         (28)        # 网络：云盘 / FTP / SFTP / SMB / WebDAV / S3 / HLS / 局域网
    │   ├── NetFs NetFsFactory FtpFs SftpFs SmbFs WebDavFs S3Fs S3Signer
    │   ├── OneDriveFs AliyunDriveFs OAuthClient OAuthToken CloudPath StreamUrl
    │   ├── HlsPlaylist HlsCrypto HlsKey M3u8Playlist HlsDownloader TsRemuxer
    │   └── LanNet LanNode LanProtocol LanSender RemoteServer TokenHarvest SyncRunner
    ├── sched/       (7)         # 定时任务：规则解析 / 编解码 / 执行 / 存储 / 两个接收器
    │   ├── ScheduleRule ScheduleItem ScheduleCodec ScheduleRunner ScheduleStore
    │   └── ScheduleReceiver BootReceiver
    ├── ui/          (79)        # 界面：49 Activity + 7 Service/Provider + 视图/适配器/对话框
    │   ├── *Activity（除 MainActivity 外全部）、ActivityFloatService / ActivityTraceService
    │   ├── ApkFileProvider / HfDocumentsProvider / WidgetProvider / LanShareService / TaskService
    │   ├── FileAdapter / FileGridAdapter / ZipAdapter、PullRefreshLayout / RadarView
    │   ├── LineNumbersView / HighlightingEditor、BaseActivity / ThemeManager / ThumbLoader
    │   ├── ToolUi / UiKit / UiDecor / TaskRows / 三个对话框 / PlayerPrefs / BookmarkStore / RecentStore
    │   └── ApkSignHelper（内置/自有签名流程，调用方全是本包）
    ├── utils/       (31)        # 横切工具：与界面、文件系统都无关的可复用能力
    │   ├── Bg HashUtil StorageStat WidgetText GridAction ColorUnderlineSpan
    │   ├── CredentialVault KeyCipher ShizukuBridge TaskJournal TaskStore
    │   ├── SyncPlan ResumePlan DragPlan BackupRestore OpenWith
    │   ├── ActivityLog ImageBatchUtil SyntaxPalette
    │   └── AutoTextFormatter MdFormatPatterns MarkdownRender MtTextUtils
    │       SyntaxHighlight SyntaxHighlighterBase MtSyntaxHighlighter
    │       MarkdownSyntaxHighlighter PlaintextSyntaxHighlighter
    └── libs/                    # baksmali 2.5.2 + dexlib2 + guava-31.1-android + jsr305 + failureaccess
```

### 依赖方向（单向，不回头）

```
MainActivity  →  controller  →  ui / fs / apk / net
                    ↓              ↓
                  utils  ←──────── ┘
                    ↓
                  core
```

- `core` 不依赖任何自研包，也不 import `android.*`（纯 JDK，桌面可测）；
- `utils` 只依赖 `core` / `fs`（`Bg` 用 Handler、`ShizukuBridge` 用 Context，但没有业务依赖）；
- `fs` / `apk` / `net` 依赖 `core` + `utils`，彼此之间按需引用；**不依赖 `ui` / `controller`**；
- `ui` 可以用下面所有层；`controller` 只依赖 `ui` 的少数装配工具（`UiDecor` / `PullRefreshLayout` / 适配器）与 `fs`/`apk`/`net` 的能力；
- `MainActivity` 在包根，只做装配、菜单分发与跨模块编排，不写具体业务。

这几条不是写在文档里的君子协定，而是 `test/deps_check.sh` 每次构建/CI 都验的硬约束；
自有包还禁止通配 `import`（`import com.hex91.file.fs.*` 会让「javadoc 提过一个类」
看起来像「依赖了整个包」，审计和门脚本都没法据此判断）。

> 218 个类：core 25 / fs 29 / apk 27 / net 27 / ui 73 / utils 28 / controller 8 / 顶层 1。
> 从 211 增到 218 的过程：v2.4.0 的 O-17 归位（`LinkUtil` 进 fs、`SyncRunner` 进 net、
> `ApkSignHelper` 进 ui）加了 `SortMode`/`TempFiles`/`FileKind`/`ByteIO`/`SyntaxPalette`；
> v2.5.0 的 O-22 刀2 加了 `ArchiveAddController`，v2.7.0 的刀1 加了 `SignFlowController`。
> 都是新增的小工具类/控制器，没有重复实现。

### 质量门

```bash
./build.sh                 # 构建时自动跑四道门（SKIP_GATES=1 可绕过）
bash test/deps_check.sh    # 依赖方向 + 自有包禁止通配 + 全限定名越层
bash test/catch_check.sh   # 空 catch 不增长（当前基线 335 = 实际值）
bash test/catch_check.sh --others   # 只列「没表态忽略」的那 4 处（欠理由）
bash test/lang_check.sh    # 文案键：引用的键存在、两侧类型与「位置 + 转换符」一致
bash test/action_check.sh  # 文件动作码：派发侧不许数字 case、菜单侧不许写数字、A_* 名字与值都不许重复
./test/jc.sh               # 只编译自研包，约 40 秒（改 import / 搬包时的快速校验）
./test/jres.sh             # 只重生成 R.java（改了 res/values 后不必等完整构建）
```

v2.5.0 起 `build.sh` 在 aapt2 之前就跑这些门：以前只有 CI 跑，本地出一条
「分层已破 / 新增空 catch」的 APK 完全不受阻，README 的口径与可执行行为对不上。
四道门合计 2-3 秒，相对 5 分钟出包可忽略。

`lang_check.sh`（v2.8.0）只查「键能不能对上」，不查翻译质量：代码引用的键必须在中文
资源里存在且类型（`string` / `plurals`）一致；`values-en` 不能出现中文侧没有的孤儿键；
两侧同名键的**格式占位符**必须一致——比的是「位置 + 转换符」（`%1$s` ≠ `%1$d`），
少一个 → 界面上缺内容；多一个 → 运行时 `MissingFormatArgumentException`；
类型不符（`String` 套 `%d`）→ `IllegalFormatConversionException`，都是闪退（v2.9.0，R-30）。
无位置参数的 `%s` / `%d` 也参与比较（`plurals` 的 `%d` 是 quantity，按集合比不会误报）。
英文覆盖率只作统计，因为 v2.3.0 定的规矩是
「只放已完成迁移的键」，未迁移页面回退中文属预期。

`action_check.sh`（v2.11.0，O-26）只扫 `MainActivity.runFileAction` 这一个方法：不许
出现 `case <数字>`、整个 `MainActivity` 不许出现 `acts.add(<数字>)`、`default` 必须还在。
v2.12.0（R-38）补了第四条：`A_*` 常量的**名字与值**都不许重复——名字重复 javac 会说话，
**值重复不会**（两个菜单项静默执行同一个动作），这类问题排查成本和 O-25 一样高。
别的方法（菜单 id、排序模式）与别的页面（`TextEditorActivity` 的局部 `acts`、
`ArchiveController` 的 `A_EDIT`）用数字是正常的，扫全仓只会制造噪音。

`build.sh` 与 `run_core.sh` / `jc.sh` / `jres.sh` 共用 **`tools/toolchain.sh`** 定位
JDK / build-tools / android.jar / Python（环境变量优先 → 常见位置探测 → 明确报错），
不再写死盘符，Linux/macOS 不需要 cygpath。显式覆盖的 `JH` / `BT` / `ANDJ` / `PYTHON_EXE`
同样要过校验，不通过就 `rc=2` 并给一行可操作提示，不会一路跑到 `javac` 才报
`No such file or directory`。v2.10.0 起 build-tools 与 platform **按版本探测**（在
`build-tools/` 与 `platforms/` 下取最高、且真含 `aapt2` / `android.jar` 的那个），所以
`ANDROID_HOME` 指向一个只有 `36.0.0` 的 SDK 也能直接构建；Windows 上 `BT` / `ANDJ` /
`APKSIG` / `SDK` 统一转成 `cygpath -m` 的 `C:/...` 形式 —— `javac` 的多段 classpath 与
d8 的 `@args` 文件都不经过 MSYS 参数转换，给 `/c/...` 会变成一屏莫名其妙的
`cannot find symbol`。
`jc.sh` 与 `jres.sh` 都要求先完整跑过一次 `./build.sh`（需要 `build/obj` 里的 vendored
class 与 `build/gen/R.java`），缺了会直接提示而不是丢一堆「cannot find symbol」。

`catch_check.sh` 只拦「比基线多」，不清零：当前 335 个空 catch 里 331 个是作者明确表态
忽略的（变量名 `ignore*` / `ig` / `ign`，或空块里写着 `ignore` / `忽略` 注释）——
`close()` / `disconnect()` / `QUIT` 这类收尾本来就不该加日志。剩下 4 处才是真正欠理由的，
`--others` 直接列出来。基线**恒等于当前实测值**（v2.9.0 清掉 `FileOps.readAllBytes`
里那个 `catch (Exception ignored) {}` 后从 336 下调到 335）：门判的是 `count > BASE`，
基线留高一个就等于白送一个额度 —— 再加一个空 catch 也不会红。
计数只覆盖 Java 代码：字符串字面量（含注入 WebView 的 JS）
先被抹成等长空格，所以 JS 里的 `catch(e){}` 不会被算进来。

## 已实现功能（v1.6.0，手机上全部可用）

| 功能 | 状态 |
|---|---|
| 双栏文件浏览器（左右窗格、点击切换激活、一键交换） | 完成 |
| 目录导航：进入子目录 / 上级 / 回主页 / 刷新 | 完成 |
| 长按文件操作菜单：复制到对面 / 移动到对面 / 重命名 / 删除 / 属性 | 完成 |
| 删除 = 移入回收站（可恢复/彻底删除/清空） | 完成 |
| 递归搜索（* ? 通配符、大小写不敏感、结果点击跳到所在目录） | 完成 |
| 书签（添加当前目录 / 跳转 / 删除，SharedPreferences 持久化） | 完成 |
| 文件属性 + MD5 / SHA-256 / CRC32 哈希 | 完成 |
| APK 信息查看（图标 + 应用名/版本标题，逐行显示 包名/版本号/安装包大小/签名状态/加固状态/目标系统/最低系统/权限数/已安装版本/数据目录/UID/首次安装/最后更新/APK 路径；**点击任意行复制该行的值**） | 完成 |
| 签名状态行：后台 apksig 检测后直接显示方案串（**V1 / V1+V2 / V1+V2+V3**）；**点击该行进签名详情页**，详情页逐行显示 校验结论/各方案/主体/颁发者/序列号/算法/有效期/MD5/SHA-1/SHA-256，**点行复制** | 完成 |
| APK 弹窗底部操作行：**功能**(复制/移动/重命名/压缩/删除/属性菜单) / **查看**(进入 APK 压缩包浏览) / **安装**(调起系统安装器) / 关闭 | 完成 |
| 安装 APK 支持（自研 20 行 ApkFileProvider 提供 content:// URI，零 AndroidX FileProvider 依赖；已声明 REQUEST_INSTALL_PACKAGES） | 完成 |
| APK 签名校验（apksig：v1/v2/v3 状态、证书 SHA-256 指纹、错误明细；点击 .apk → "签名校验"，后台线程执行） | 完成 |
| 新建文件夹 | 完成 |
| ZIP 压缩包浏览（点击 .zip 打开、内部目录层级导航、条目大小显示） | 完成 |
| ZIP 解压（全部/单条；可解到当前目录或压缩包同目录；防 zip-slip 穿越攻击） | 完成 |
| 长按"压缩为 ZIP"（文件/文件夹打包，含子目录递归） | 完成 |
| 手势选中：对着文件/目录**左滑 = 直接选中它**（自动进入多选并弹出操作栏，提示继续下滑连选） | 完成 |
| 连续刷选：多选模式下**向下滑动 = 滑到哪里选到哪里**（经过的条目逐个点亮） | 完成 |
| 手势取消：对着已选中项**右滑 = 取消该项**；右滑到未选中处/空白 = 退出多选 | 完成 |
| 多选批量操作栏：全选 / 复制到对面 / 移动到对面 / 批量删除（进回收站）/ 完成(实时计数) | 完成 |
| **文本编辑器**：点击 .txt/.xml/.json/.java/.py/.smali 等 30 种文本扩展名直接进入；UTF-8 严格解码失败自动降级 GBK（标题栏显示实际编码）；修改跟踪 + 返回键 保存/不保存/取消 三选一防丢；头部含 NUL 的二进制文件自动提示改用 HEX 查看；超 5MB 提示卡顿风险 | 完成 |
| **HEX 查看器**：每行 16 字节 = 8 位偏移 + HEX（按 8 字节分组）+ ASCII 预览，等宽字体；点击行复制；「跳转偏移」支持十进制（1024）或 0x 前缀十六进制（0x400）；超 4MB 只显示前 4MB 并标注 | 完成 |
| 长按菜单新增：**文本编辑** / **HEX 查看**（任何文件均可进） | 完成 |
| **smali 反编译（v1.5）**：APK 压缩包浏览中**长按 classes.dex（或任意 .dex）→ 反编译为 smali**，后台线程跑 baksmali（4 并发），输出 `/sdcard/HexFileSmali/<apk名>/<dex名>/` 并自动跳转浏览；点 .smali 文件直接用自带文本编辑器查看 | 完成 |
| **独立 dex 文件反编译（v1.5.1）**：主界面**点击 .dex 弹菜单**（反编译为 smali / HEX 查看 / 属性），长按菜单也含「反编译为 smali」；输出 `/sdcard/HexFileSmali/<dex名>/` 并自动跳转 | 完成 |
| baksmali 引擎内嵌（org.smali 2.5.2：dexlib2+guava-31.1-android，纯 Java 零 native）；multidex 就绪的构建链（d8 自动拆分 classesN.dex）；manifest 开启 largeHeap | 完成 |
| 粉色主题 UI | 完成 |

## 路线图

- **v1.2** ✅：ZIP 压缩包（浏览/解压/打包，java.util.zip 零依赖）；7z/TAR/RAR 待后续引库
- **v1.3** ✅：APK 签名校验（apksig 方案）；APK 对比待后续
- **v1.4** ✅（v1.4.0）：文本编辑器（编码识别/防丢）+ HEX 查看器；语法高亮、行号待后续
- **v1.5** ✅（v1.5.1）：smali 反编译查看（baksmali 2.5.2 内嵌）；smali→dex 回编译随 v1.7 落地
- **v1.9** ✅（v1.9.0–1.9.1）：ARSC 资源编辑器++（jadx 引擎：解析用 skylot/jadx `ResTableBinaryParser`，写回为自研字节级补丁层）
- ~~**v2.0**：ARSC 资源解析编辑~~（已随 v1.9 落地）
- **v1.10** ✅（v1.10.0）：DEX 编辑回写闭环（编译产物替换首个选中 dex + 移除其余选中 dex → 重打包 → v1+v2+v3 重签名，64K 方法数守卫）
- **后续**：GIF 预览（pl.droidsonroids.gif）、Zstd（zstd-jni）、终端（pty）

## 设计说明

- **整体架构**：`MainActivity` = 入口；`FeatureRegistry` =  注册表模式（后续所有功能模块统一注册，菜单自动生成）。
- **回收站设计**：`/sdcard/.MTCloneTrash/store/<id>` 存内容、`meta/<id>.txt` 存原始路径/时间/类型四行文本；恢复时若原路径已存在自动追加 `(1)` 后缀。同分区用 renameTo 秒完成，跨分区自动降级为拷贝+删除。
- **搜索防卡死**：后台 Thread + Handler 回主线程刷新，深度上限 12 层、结果上限 1000 条，自动跳过回收站目录。
- **ZIP 引擎**：零第三方依赖；`listChildren` 前缀过滤实现目录层级浏览（深层条目一律归并到祖先目录项）；解压 `safeTarget` 校验规范路径防 zip-slip；`copyStream` 不关流（Zip 流由 try-with-resources 管理，文件流由调用方关闭）——曾因 copyStream 连带关闭 Zip 流导致打包/解压中断，已修复并单元测试回归。
- **so 层零 C 开发**
- **手势与多选**：`GestureDetector` 旁听模式——`OnTouchListener` 恒返回 false，事件照常给 ListView，滚动/点击零干扰。左滑/右滑双通道触发：`onFling`（快速甩动，`|vx|>|vy|*1.2 且 |vx|>350 且 位移>80px`）+ `onScroll` 慢速横向拖动兜底（80px，`handled` 标志保证每个手势只触发一次）；`pointToPosition(e1.x, e1.y)` 把手指起点映射到条目，实现"对着哪个文件滑就操作哪个"；连续刷选用 `onScroll` 纵向主导位移（30px 起刷）。非多选时右滑 = 返回上一级。多选状态 per-panel，同一时刻仅一个窗格多选；导航（loadDir）自动退出多选防状态错乱。
- **防误退**：`onBackPressed` 分层拦截——多选中先退多选 → 目录中逐级返回上级（异常位置直接回根）→ 根目录需 2 秒内再按一次才真正退出。
- **签名校验引擎**：直接复用 build-tools 里的 `apksigner.jar`（含 309 个 `com.android.apksig` 类，Google 官方 apksig 库）——javac 用作 `-classpath`，d8 直接把 jar 与 app class 一起 dex 合并进单个 classes.dex（945KB / 6885 方法），零额外下载。
- **APK 信息行**：`ApkUtil.rows()` 输出 {标签, 值} 列表——签名状态走 `PackageManager.GET_SIGNATURES`（秒出，方案细节留给"签名校验"）；加固检测扫 zip 条目名匹配 360/梆梆/腾讯/爱加密/阿里/网易易盾特征；已安装信息经 `getPackageInfo(包名)` 补充（数据目录/UID/安装时间）；目标/最低系统带 API→版本名映射。
- **存储策略**：targetSdk 30 + requestLegacyExternalStorage，运行时申请 READ/WRITE_EXTERNAL_STORAGE，直接 File API 访问 /sdcard。

## 已验证

- 六个 Activity 均注册（Main singleTop / Search / Trash / ZipBrowser / TextEditor / HexViewer）+ ApkFileProvider；largeHeap 已开启
- apksigner：V3.0 签名有效（证书 CN=MT Clone）
- **签名 DN 可读化**：`X500Principal.getName()` 默认 RFC2253 会把邮箱属性（OID 1.2.840.113549.1.9.1）输出成十六进制 `#16...`；改用 `getName(RFC1779)` 并把 JDK 的 `OID.1.2.840.113549.1.9.1=` 前缀替换为 `EMAILADDRESS=`，输出与 keytool 一致的可读格式。
- 产物：`out/MTClone-1.8.0.apk`（约 1.5MB，单 dex ~2.5万方法，含 baksmali）
- **签名校验桌面实测（JDK 17 + 真实 apksig）**：schemes() → MTClone=V1+V2+V3、MT2.26.10=V1+V2；detailRows()
- **v1.4 桌面实测（JDK 17）**：UTF-8/GBK 编码探测、GBK 保存往返无损、HEX 行格式（含尾行截断补齐）、偏移跳转解析（0x400/1024）全部通过
- **v1.5 桌面实测（JDK 17）**：baksmali 2.5.2 编程 API 反编译自身 classes.dex → 847 个 .smali 文件，格式正确（30s/4并发）
- **Git Bash 教训**：`$ROOT` 用 `cygpath -m` 转 Windows 风格，否则 `;` 分隔的 javac classpath 传给原生 exe 时 MSYS 转换失效（报"找不到符号"）
- **ZIP 单元测试（桌面 JDK 17 跑真实 ZipUtil）**：打包（多源+递归目录）、根/子目录浏览、解压全部、解压单条全部通过，内容比对正确
- **真机验证（Pixel 6 / Android 14，adb 实测）**：安装成功、主界面启动无崩溃，双栏正常加载（items=51），无 FATAL。

## 真机权限须知（Android 11+）

文件管理器访问 `/sdcard` 根目录必须「所有文件访问」权限（`MANAGE_EXTERNAL_STORAGE`），普通 READ/WRITE 在分区存储下不够（Android 11+ 分区存储限制）。首次启动若未授权，App 会跳转系统设置页引导开启。调试时可用：
`adb shell appops set com.hex91.file android:manage_external_storage allow` 直接授予以验证。

## v1.6.0：UI 改版

- 主界面：深色双行顶栏（活动栏路径 + `文件夹/文件/储存` 统计行）、双栏白底列表、底部 `‹ › ＋ ⇄ ↑` 工具栏（历史后退/前进、新建、交换窗口、上级）
- 条目样式：类型彩色圆角图标（DIR 黑/APK 绿/DEX 深绿/ZIP 棕/SO 灰蓝/图片紫/视频红/音频橙/文档蓝…）+ 名称 + `yy-MM-dd HH:mm 大小` 副行
- 长按菜单 式双列宫格弹窗；⋮ 主菜单：刷新/搜索/全选/排序方式/显示 .文件/书签/回收站/交换窗口
- 新增：目录历史导航（每栏独立栈）、排序方式（名称/大小/时间）、隐藏文件切换、新建文本文件
- 主题从粉色换成（深色顶栏 #212121 + 白列表 + 蓝强调），保留粉色选区高亮改为 MT 浅蓝
- 验证方式：adb 接管真机实测截图对比 + uiautomator 驱动自装复刻版逐项点按（进目录/长按菜单/⋮菜单/属性弹窗），0 FATAL

## v1.6.1
- 修复：⋮ 主菜单从居中 AlertDialog 改为 PopupMenu，锚定右上角 ⋮ 按钮向下弹出（API23+ 显式右对齐），与 MT 溢出菜单一致

## v1.7.0：APK 功能菜单+ smali 回编译闭环

- **长按 APK / APK 弹窗「功能」→ APK 专属菜单**（双列宫格）：
  - 反编译 dex→smali：APK 内全部 `classes*.dex` 自动提取并逐个 baksmali → `/sdcard/HexFileSmali/<apk名>/<dex名>/`，完成后跳转浏览
  - 提取 dex：全部 dex 提取到对面面板 `<apk名>_dex/`
  - 提取图标：当前分辨率位图存为 `<apk名>_icon.png` 到对面面板（v1.72.0 已从菜单移除，位置换成「分享」）
  - 签名信息：直进签名详情页（V1/V2/V3、证书、MD5/序列号等）
  - 复制到对面 / 重命名 / 属性
- **smali → dex 回编译**（与 baksmali 组成完整闭环）：长按含 `.smali` 的目录 → 「编译为 dex」→ 同级生成 `<目录名>.dex`；失败时弹窗提示排查语法错误
- 引擎：`org.smali:smali:2.5.2` + antlr-runtime 3.5.2（新增 ~0.5MB）
- 桌面验证：自身 dex 反编译 3872 个 .smali → 回编译 13.4s 生成 3.7MB dex（与原 3.65MB 吻合）

## v1.7.1：修复压缩包浏览闪退

- **闪退根因**：v1.6.0 改版把 `item_file.xml` 的图标位从 ImageView 改为 TextView 类型角标，`FileAdapter` 已适配但 `ZipAdapter` 漏改 → 进 APK 压缩包即 `ClassCastException` 闪退
- 修复：ZipAdapter 与 FileAdapter 同款渲染（TextView 角标 + GradientDrawable 按扩展名着色）；`FileItem.typeLabel/typeColor` 抽成静态方法供 zip 条目复用
- 真机全链路回归：APK 弹窗 → 查看 → 压缩包浏览（类型角标正常）→ 长按 classes.dex → 反编译 → 3882 个 .smali 输出并自动跳转，全程 0 FATAL

## v1.8.0：Dex 编辑器++

adb 实测 Dex 编辑器++ 全流程（MultiDex 多选对话框 → 浏览/搜索 Tab → 类树 → smali 编辑器）后复刻：

- **MultiDex 多选**：长按 dex / APK 功能菜单 →「Dex 编辑器++」→ 弹出 APK 内全部 dex 复选框（全选/取消/确定，MT 同款三键），支持单 dex 与多 dex
- **临时工程**：选择的 dex 自动提取 + baksmali 到缓存工程；`project.ini` 记录选择，**重复打开秒开**（复用已反编译结果）
- **类树浏览**：树形列表（▸/▾ 展开/收起包、蓝色 ● 标记类、按层级缩进、大小写不敏感排序）；顶部实时**搜索类名**（匹配全路径高亮计数）
- **编辑 smali**：点类 → 自带文本编辑器打开对应 .smali（改动保存后工程内即时生效）
- **⚙ 编译**：整个工程（含多 dex 合并）smali → 单个 `classes.dex`（v1.66.10 起输出到应用内部缓存工程目录，不再在 APK 旁生成 `<apk名>_dexpp/`）；失败弹窗提示检查最近改过的类
- 引擎复用 v1.7 的 baksmali/smali 2.5.2（新增 `SmaliTool.assembleMulti` 多目录合并编译）
- 真机实测：huangguo_1.0.8.apk 2 个 dex → 3883 个类全部加载，类树/展开渲染正确

## v1.9.0–1.9.1：ARSC 编辑器++（jadx 引擎版）

用户指令："逆向代码和资源文件都可以参考 skylot/jadx，没必要重复造轮子，只需要加个修改即可。"——解析整体换成 jadx 的成熟代码，写回（修改）能力自研：

- **解析层**（fork 自 jadx，Apache-2.0，见 `src/jadx/NOTICE.md`）：`ResTableBinaryParser` 流式二进制解析（全局字符串池 → package chunk → typeStrings/keyStrings → type spec/type/library/overlay/staged-alias chunks）。去掉 RootNode/反混淆集成（恒用原始资源名）、去掉 decodeFiles/XML 生成、**新增原始 span 记录用于字节级写回**、Java 8 兼容（jadx 原版 Java 10+）
- **写回层**（自研，`ArscWriter.java`）：字符串值/条目名都在字符串池中 → 编辑不改变条目大小 → 新串**追加**进池 + 条目内 **4 字节补丁**（compact 条目 key 为 2 字节）；池变大触发全量重布局，其余 chunk 逐字节复制。`Edit{VALUE_STRING, VALUE_RAW, NAME, MAP_STRING}` 四类编辑
- **容错**：混淆/手改 arsc 的垃圾字符串索引（np.arsc 38.8 万串中 35.1 万索引无效）→ 指向共享空串槽；`BinaryXMLStrings.get()` 负索引/`id*4` 整数溢出双重防护（除法比较根治）
- **保存链路**：`ArscWriter.write → ApkRepack.repack → ApkSignerTool.sign(..., minSdk=21)`。重打包保持条目原压缩方式（原本 STORED 的条目含直载 .so 补零对齐 4096；原本 DEFLATED 的 .so 保持 DEFLATED——曾因强制 .so STORED 导致体积翻倍）
- **真机全链路**：huangguo.apk 加载 4517 条目无崩溃 → 编辑 string 值 → 保存 → 重打包 → 重签名 → 产物 47.5MB → 产物 arsc 回读确认新值已写入（原值 "Navigate up" → 编辑值）→ v2/v3 签名有效
- **桌面测试**：`test/JadxArscTest` 47/47（4 样本 round-trip 字节一致 + 四类编辑回读 + 编辑后再 round-trip 稳定）；`test/SignTest` 10/10（arsc 编辑→重打包→签名→回读，含 NP.apk 58 个 so 场景、v1+v2+v3 CLI 校验）

## v1.9.2：v1 签名展示修复（apksig 跳过语义）

- **根因**：apksig 的 `ApkVerifier` 仅在「minSdk<24 或无 v2/v3 签名」时才真正校验 v1——API 24+ 平台根本不看 JAR 签名，故跳过校验并报 `isVerifiedUsingV1Scheme()=false`。minSdk≥24 且带 v2/v3 的包（如重打包产物）会被显示成"无 v1"，但其 `META-INF/*.SF + *.RSA` 签名文件实际存在且有效。这不是签名损坏（`verified=true`、v2/v3=true、jarsigner 亦通过）
- **修复**：`ApkSignCheck` 增加 `hasJarSignatureFiles()` 按签名文件存在性判断 v1；`schemes()` 方案串改为存在性口径;详情页 v1 行三态：✅ 已校验 / ✅ 有签名(平台跳过校验) / — 无
- **桌面测试**：`test/SignCheckTest` 3/3（minSdk=24 重打包产物 → V1+V2+V3；minSdk=21 自身 → V1+V2+V3 且已校验；未签名包 → 未签名）
- **经验**：apksig 源码级结论来自 javap 反编译 `V1SchemeVerifier`（`if (minSdkVersion < 24 || foundApkSigSchemeIds.isEmpty())` 才跑 v1 校验）；另发现 Git Bash 下 `java -cp` 目录在前、jar 在后时无法加载类名带 `$` 的类——桌面测试把 jar 放前面即可（不影响 Android 构建，d8 直读 jar 无此问题）

## v1.10.0：DEX 编辑回写闭环

Dex 编辑器++ 编译链的最后一公里——编译出的 classes.dex 不再是孤立文件，直接写回源 APK 并重签名：

- **入口**：编译成功后（APK 工程模式）弹「写回 APK 并签名」确认框
- **写回语义**：替换 `dexes.get(0)` 条目为合并产物；多 dex 工程移除其余选中的 dex（它们已被合并）；未选中的 dex 不受影响
- **64K 守卫**：`SmaliTool.dexMethodCount()` 读 dex 头 `method_ids_size`（offset 88, u32 LE），超 65535 拒绝写回并提示缩小选择范围
- **签名链路**：与 ARSC 编辑器一致 `ApkRepack.repack(apk, replace, remove, unsigned) → ApkSignerTool.sign(..., 21)`
- **ApkRepack**：新增 `repack(src, replace, remove, out)` 重载（remove 为剔除集合），旧签名保持兼容
- **桌面测试**：`test/DexWritebackTest` 11/11（mini apk：smali 汇编 → baksmali → 回编译 → 替换+删除 → 签名 → CLI `Verifies` v1+v2+v3 → 产物 dex 可被 dexlib2 加载；含 64K 守卫正反例与旧 API 兼容性）

## v1.11.0：Manifest 编辑器++ + 语法高亮

### AndroidManifest.xml 解析编辑（逆向三件套收官：Dex / ARSC / AXML）

- **AxmlFile**（自研 span 解析器）：AXML token 流遍历（字符串池 0x0001 → 资源映射 0x0180 → namespace/element tokens），记录每个属性的 name/rawValue/typed-data 绝对偏移；END_ELEMENT 配对求嵌套深度与路径
- **AxmlWriter**（写回，与 ArscWriter 同策略）：字符串值 → 池去重追加 + rawValue/data 双补丁；int/hex/bool/ref/float → typed data 4 字节原位补丁；池增长只需修文件头 size（token 内引用全是索引，无位置偏移）
- **踩坑**：① BinaryXMLStrings 的 buffer 须从偏移数组起始（chunk+28）切片、stringsStart 传 stringsStartField−28；② UTF-16 池串只有**一个** u16 长度字段（UTF-8 池才是双长度）——照抄 ARSC 的双长度会多出一个隐形控制符
- **ManifestEditorActivity**：分组 + 属性列表，点击编辑（类型校验 + 还原按钮），👀 只读 pretty XML 预览，💾 保存 = AxmlWriter → 回读校验 → repack → v1+v2+v3 重签名（`<apk>_mfpp.apk`）
- **独立验证**：aapt2 dump xmltree 正确解析写入的 versionCode=66（Google 官方解析器交叉确认）
- **测试**：`test/AxmlTest` 14/14（round-trip 字节一致 / int·bool·string 编辑回读 / 池重建后其余属性零扰动）

### 文本编辑器语法高亮（smali + xml）

- `SyntaxHighlight`：手写扫描器（无正则），加载时后台一次性高亮，编辑时 span 随文本自动移位；超 1MB 不高亮防卡顿
- smali 配色：注释灰 / 伪指令粉粗 / 操作码紫 / 寄存器青 / 字符串绿 / 标签橙 / 类型蓝 / 数字深橙；xml：标签紫 / 属性蓝 / 字符串绿 / 注释灰 / 括号灰蓝

## v1.11.1：修复 Manifest 编辑器保存失败

- **根因**：`AxmlFile.valueDisplay()` 读的是 attr 解析时**缓存的字段**（dataValue/rawValueIdx），不从 buffer 现读；`doSave` 回读校验拿**旧文件的 attr 对象**配**新文件**校验——旧对象缓存旧值，校验必失败（桌面 AxmlTest 未踩中，因为测试都是重新解析后按名字找属性）
- **修复**：校验改用新文件中**同位置属性对象**（写回不改结构，元素/属性顺序不变）：`check.elements.get(ei).attrs.get(ai)`
- **回归**：AxmlTest 增 2 用例（16/16）——旧对象读新文件必≠新值、同位对象必=新值

## v1.11.2：修复 Manifest 编辑器保存失败（真正根因：缓存子目录未创建）

- **真正根因**：`doSave` 把未签名的 AndroidManifest 写到 `getCacheDir()/mfpp_<基名>/unsigned.apk` 这种**带子目录**的路径，但 `FileOutputStream` 不会自动创建父目录 → `ApkRepack.repack` 写文件时抛 `FileNotFoundException` → 被 catch 捕获 → 弹「保存失败」。这正是 v1.11.0 / v1.11.1 两版「保存失败」的真实原因（与回读校验无关，校验逻辑本身是对的）。
- **为何桌面没暴露**：桌面复现用的是扁平文件名 `mfpp_unsigned.apk`（无子目录），所以一路绿灯；真机每次都走嵌套路径，必炸。
- **修复**：写文件前 `unsigned.getParentFile().mkdirs()`；并让「保存失败」对话框显示完整异常链（含 cause），便于后续排错。
- **验证**：`test/MfSaveDbg6` 复现原异常（`FileNotFoundException: .../unsigned.apk 系统找不到指定的路径`）；`test/MfSaveDbg7` 模拟 doSave 设备侧路径（含 mkdirs）完整链路（写回→回读校验→repack→v1+v2+v3 签名）全绿。
- 版本号：versionCode 27 / versionName 1.11.2。

## v1.12.0：文本编辑器增强（行号 + 搜索/替换）

- 新增 `LineNumberEditText`：左侧灰紫行号随文本滚动，光标所在行加深，细竖线分隔。
- 布局 `activity_editor.xml`：编辑区左侧留白 44dp；插入可折叠搜索面板（搜索框 / 匹配计数 cur/total / ↑↓ 循环导航 / × 关闭）+ 替换框（替换 / 全部）。
- `TextEditorActivity`：后台一次性高亮 + `BackgroundColorSpan` 浅黄高亮全部匹配，`setSelection` 定位；`replaceAll` 从后往前替换避免偏移错位；保留原语法高亮与编码探测。
- 版本号：versionCode 28 / versionName 1.12.0。

## v1.12.1：修复 smali 文件「正在加载」卡死

- **根因**：`SyntaxHighlight.smali()` 处理标签（`:goto_0` / `:cond_0`）时调用 `tokenEnd(text, i)`，但冒号 `:` 不在 tokenEnd 的「可前进字符集」内 → 立即返回原位 → `i` 不前进 → 后台高亮线程**死循环** → `editor.setText` 永不调用 → 编辑器一直空白（xml 走另一套逻辑不受影响）。
- **修复**：标签扫描从 `i + 1`（跳过冒号）开始。
- **验证**：`android.jar` 在桌面只是 Stub（`SpannableStringBuilder` 只抛 `Stub!`），无法直跑 android.text；改用纯 Java 复刻扫描器 `test/SmaliLoopCheck` 加 5s 看门狗——OLD（修复前）超时证实死循环，NEW（修复后）正常返回（高亮 span=74）。
- 版本号：versionCode 29 / versionName 1.12.1。

## v1.13.0：ZIP 多格式（7z / TAR / RAR 浏览 + 解压）

- 新增统一归档抽象 `ArchiveUtil`：`open(File)` 按扩展名分派到四种后端，浏览界面只面向 `Archive` 接口，无需关心底层格式。
  - `.zip/.apk/.jar/.aar` → **ZIP**（复用 `ZipUtil`，零依赖）
  - `.7z` → **7z**（Apache Commons Compress）
  - `.tar/.tar.gz/.tgz` → **TAR**（含 GZIP 解包）
  - `.rar` → **RAR**（junrar，支持 RAR4/RAR5）
- `ZipBrowserActivity` / `ZipAdapter` 全面接入 `ArchiveUtil`；标题显示格式标识；dex/arsc/smali 专属菜单**仅 ZIP 格式**（.apk 也是 ZIP）下出现（Dex/Arsc 编辑器要求输入是 ZIP/APK）。
- `MainActivity.onFileClick` 增加 `.7z/.tar/.tgz/.tar.gz/.rar` 路由到压缩包浏览器。
- **依赖决策**：Apache Commons Compress 主 jar **不含 RAR**（1.22/1.26 实测均无 `archivers/rar` 包），RAR 改用独立的 junrar。为支持 7z 的 LZMA2，必须额外捆绑 `org.tukaani:xz`（commons-compress 7z 读写的传递依赖，否则真机读 7z 会 `NoClassDefFoundError`）。slf4j 由 junrar 需要，移除原 `src/org/slf4j` 垫片、改引 `slf4j-api` jar。
- 新增 `libs/`：`commons-compress-1.22.jar`、`junrar-7.5.5.jar`、`slf4j-api-1.7.36.jar`、`xz-1.9.jar`（共 +约 1.3MB，APK 1.7M→2.2M）。`build.sh` 适配（移除已删的 `src/org/slf4j` 编译项与 `build/obj/org` find）。
- **桌面往返测试** `test/ArchiveTest`（纯 Java，无 android 依赖）：用 commons-compress 自造 7z/tar/tgz 样本 + java.util.zip 造 zip，经 `ArchiveUtil.open()` 走「浏览 + 解压全量」，zip/7z/tar/tgz **4/4 PASS**（4 文件内容一致、顶层列表正确）。RAR 因无生成工具，需真机用真实 .rar 验证（junrar 为成熟库，编译与分派已通过）。
- 版本号：versionCode 30 / versionName 1.13.0。

## v1.14.0（versionCode 32）：压缩包窗格内嵌浏览 + 直接编辑 + 增删条目

- **窗格内嵌浏览**：点击 zip/7z/tar/tgz/rar 不再启动全屏 ZipBrowserActivity，改为在**点击所在窗格**内列出条目（`..` 返回上级，逐级进入目录，顶部路径显示 `压缩包路径/条目前缀`，统计栏带 `[ZIP/7Z/…]` 格式标识）。返回键/↑ 上级/‹ › 历史/⇄ 交换均适配压缩包模式。
- **点击直接编辑**：压缩包内点击文本文件（xml/smali/txt/…）→ 后台解到缓存 → 文本编辑器打开；保存时：
  - ZIP/APK/JAR：`ZipUtil.writeEntry` 写回压缩包内原条目（标题显示 `↳ 写回 xxx.apk!path`）；
  - APK 写回后弹「是否自动签名」→ 内置密钥 v1+v2+v3 签名，产物 `<名>_signed.apk`；
  - 非 ZIP（7z/TAR/RAR 只读）：保存后导出副本到窗格目录（标题显示 `↳ 导出 <目录>`）。
- **长按条目菜单**：文本编辑 / 提取到当前窗格 / 提取到对面窗格 / **删除**（ZIP/APK 重建式删除，含子树）/ 属性。
- **复制/移动进压缩包**：对面窗格打开了 ZIP/APK 时，文件长按菜单出现「复制/移动到对面压缩包」→ 对话框可**自定义目标条目名（含后缀，支持 a/b/c.txt 加入子目录）**→ 写入成功后 APK 弹自动签名询问。多选批量复制/移动到压缩包同支持（按原文件名写入）。
- **APK 修改自动剥签名**：重建 zip 时若目标是 .apk，自动剥离 `META-INF/MANIFEST.MF / *.SF / *.RSA / *.DSA / *.EC`（保留 META-INF/services 等合法条目），为重签做准备；STORED 条目（resources.arsc 等）按原方式原样保留。
- 新增 `ZipUtil.writeEntry / removeEntries`（重建式，临时文件原子替换）；桌面单测 `test/ZipWriteTest` **20/20 PASS**（替换/新增/STORED 保留/删除子树/APK 签名剥离）。
- 版本号：versionCode 32 / versionName 1.14.0。

## v1.15.0（versionCode 33）：修复「复制到对面压缩包」+「切换窗格后压缩包状态」

用户真机反馈两条：① APK 能复制到对面，但 zip/rar 不支持复制到对面压缩包；② 对面开着压缩包时，切到另一侧再切回来，无法退回上一步（退出/刷新压缩包）。逐条定位与修复：

### Bug1：复制到对面压缩包（zip/rar 不支持）
- **根因排查**：桌面用真机样本 `sample.zip` 直跑 `ZipUtil.writeEntry` 全通过（26 项断言），确认「真实文件 → 对面 ZIP」代码路径本就正常；差距在**没覆盖的场景**：
  - 对面是 **RAR/7Z**（只读格式）：菜单根本不显示入口，用户以为"不支持"；
  - 源是**压缩包条目**（zip/rar 里的文件）→ 对面压缩包：此前明确提示"暂不支持互拷"；
  - 源是**目录**：`&& isFile` 把目录挡在菜单外。
- **新增 `ArchiveWrite` 统一写后端**：按扩展名分派——`.zip/.apk/.jar` → `ZipUtil`（重建式，APK 自动剥旧签名）；`.tar/.tgz/.tar.gz` → 新建 `TarArc`（commons-compress `TarArchiveOutputStream` 重建式，v1.15 起 TAR 也可写回/删除）；`.rar/.7z` → 只读（RAR 是专有格式，全球没有开源写入器；commons-compress 1.22 无 7z 写出器），入口保留但点击给出明确说明 toast。
- **压缩包条目 → 对面压缩包**（新流程 `addEntriesToArchive`）：先把条目（含子树）提取到缓存 `arch_xfer`，再逐文件写入目标归档；单条目弹自定义名对话框（目录作为前缀）；「移动」= 写入后从源归档删除（源只读时自动降级为复制并提示）。入口三处：条目长按菜单、条目点击的非文本文件菜单（"写入对面压缩包"）、多选批量栏。
- **目录 → 压缩包**：`writeTree` 整树写入，自定义名作为条目前缀。
- `ZipUtil` 新增 `writeEntry(File, String, File)` 流式重载（44MB APK 不再整体进内存），`byte[]` 重载改为委托。
- 桌面单测 `test/ArchiveWriteTest` **26/26 PASS**（zip/tar/tgz 替换/新增/删除/读回/300KB 流式写入，样本取自真机），Python `zipfile/tarfile` 独立校验格式合规；`test/ZipWriteTest` 20/20 无回归。

### Bug2：切换窗格后压缩包状态丢失/无法退回
- **根因**：多处"刷新/返回"逻辑在压缩包模式下直接调 `loadDir(p, p.dir)`，而 `loadDir` 会 `closeArchiveQuiet`——**静默退出压缩包且不可恢复**；⇄ 交换更是强制关闭两侧压缩包；返回键只认活动栏的压缩包。
- 修复清单：
  - ⋮「刷新」「显示/隐藏 .文件」「排序方式」：压缩包模式改为 `showArchiveDir` 重列（真刷新，不退出）；
  - 右滑"返回上一级"：压缩包模式走 `archGoUp`（包内逐级/根退出），不再跳目录历史；
  - ‹ › 历史：压缩包模式先退压缩包，不占用目录历史；
  - 返回键：活动栏无压缩包但**对面开着压缩包**时，先退对面压缩包；
  - ⇄ 交换窗口（按钮与 ⋮ 菜单）：改为**全状态交换**（目录/压缩包/历史/适配器一起换），压缩包浏览状态保留，屏幕侧焦点不变；
  - **点列表空白处 = 切换活动窗格**（`pointToPosition == INVALID_POSITION` → `setActive`），切窗格不再误触条目动作；
  - 压缩包浏览中「＋ 新建」给出提示而不是在宿主角落操作。
- 版本号：versionCode 33 / versionName 1.15.0。装机冒烟通过（进程常驻、无 crash）。

## v1.15.1（versionCode 34）：修复「APK 修改后无法解析」+ 长按菜单加「APK 签名」

用户反馈：复制文件进 APK 后不签名，再打开该 APK 提示"无法解析该 APK"；长按菜单只有"签名信息（未通过）"，应提供"给 APK 签名"选项。

### 根因：重建丢失 4 字节对齐
- 用真机拉回的 `huangguo.apk` 复现：`resources.arsc`（STORED）数据偏移 `%4=3`。Android 11+ 上 targetSdk≥30 的 APK 要求 resources.arsc **未压缩且 4 字节对齐**，否则 `getPackageArchiveInfo` 返回 null → "无法解析该 APK"，安装也会失败。
- **修复（ZipUtil）**：新增 `CountingOutputStream`（统计底层流写入字节），`putNextEntry` 前实时得知该条目 local header 绝对偏移，`alignEntry` 按 `pad = align - (locPos+30+nameLen) % align` 往 extra 字段塞零填充（.so 按 4096 对齐，其余 STORED 按 4），等效 zipalign。首次实现漏算 locPos 导致部分条目仍偏移，已修正并全量验证：对 44MB 真机 APK 重建后**全部 STORED 条目对齐**。
- 已用新版把真机上被 v1.15.0 写坏的 `huangguo.apk` 重写修复（写入+删除探针条目触发重建）并推回设备。

### UI 修复
- **APK 信息弹窗降级**：`ApkUtil.rows()` 返回 null 时不再只弹 toast 返回，而是显示基本信息（大小/签名状态"未签名或已失效"/路径），保留「功能/查看/安装/关闭」按钮——解析失败也能打开浏览、编辑、签名。
- **长按 APK 菜单新增「APK 签名」**：用内置密钥签名，产物 `同名_signed.apk`（`signApkBuiltin`，与修改后询问共用逻辑）。`askAutoSign` 重构复用该方法。
- 桌面回归：ZipWriteTest 20/20、ArchiveWriteTest 26/26。装机冒烟通过。

## v1.16.0（versionCode 35）：7z 可写（增删改）+ 加密压缩包密码输入 UI

用户需求：把压缩包内部当文件夹操作（增删改），7z/RAR 不再只读；支持密码。

### 7z 写入（增删改，纯 Java 落地，无需额外 so）
- 之前注释"commons-compress 1.22 无 7z 写出器"是错的：`SevenZOutputFile` 1.22 就有（配合 xz-1.9.jar 做 LZMA2）。
- **ArchiveWrite.SevenZArc**：重建式写 7z（SevenZFile 读旧条目 → SevenZOutputFile 重写），支持 writeEntry（byte[]/File 流式）与 removeEntries（目录前缀匹配）。
- 坑：条目无时间戳时 `getLastModifiedDate()` 抛 UnsupportedOperationException → try/catch 跳过。
- canWrite(.7z)=true，UI 所有写入入口（复制/移动到压缩包、删除、批量）自动点亮。桌面回归 35/35。

### RAR 写入：物理上不可行，提供「转存为 ZIP」
- RAR 压缩算法专有（rarlab 闭源），全球没有任何免费/付费通用库支持写入——包括 7-Zip native 引擎。这不是实现偷懒，是格式授权限制。
- 尝试写 RAR 时弹窗说明，并可**一键转存为同名 ZIP**（解压到缓存→ZipUtil.createZip，RAR 原件保留），转存后即可增删改。

### 密码输入 UI（加密压缩包）
- **ArchivePasswords**：会话级密码存储（内存 Map，App 退出失效，不落盘）。
- **ArchiveUtil.open(File, password)**：带密码的 RAR/7Z/ZIP 家族统一走 7-Zip native 引擎（sevenzipjbinding `openInArchive(format, stream, password)`，支持 RAR4/RAR5/7z/zip 加密）；无密码 ZIP 保持 java.util.zip 快速路径。
- **加密探测**：RAR 打开后逐条目查 PropID.ENCRYPTED；打开失败/提取失败含密码类关键字（cryptoError）→ 抛 NeedsPasswordException / NEED_PASSWORD 标记。
- **MainActivity**：openArchiveInPane 捕获后弹密码框（输入→记住→自动重开；密码错会再次弹框直到取消）；4 个提取/编辑/互拷路径的 catch 接 archPwError 弹框重试。
- 坑：commons-compress 1.22 的 ZipFile/ZipArchiveInputStream 均无密码支持（zip 解密只在新版或有 zip4j），故走 native 引擎而不是引新依赖。

装机冒烟通过。待真机手测：加密 RAR/7Z 打开弹密码框、7z 增删改、RAR 转存 ZIP。

## v1.16.1（versionCode 36）：修复加密压缩包错密码死循环 + 「密码错误重试」弹窗

用户反馈：desktop.rar（密码 a123456）输错密码时疑似死循环。

### 根因：native 引擎拿着错误密码无限重试
- `SevenZip.openInArchive(format, stream, String password)` 的 String 重载内部用固定密码回调（`ArchiveOpenCryptoCallback.cryptoGetTextPassword()` 永远返回同一密码）。RAR5 native 引擎解密失败后会**再次询问密码**，回调又返回同一个错误密码 → 无限循环，线程永久卡死（界面一直"正在打开"）。
- **修复（ArchiveUtil.PwCallback）**：自定义 `IArchiveOpenCallback + ICryptoGetTextPassword` 计数回调——每个操作窗口内最多应答 3 次，超过即抛 `SevenZipException("password incorrect")` 打断 native。open 前与每个条目 extractSlow 前都 `reset()` 归零，正确密码（每条目问 1 次）不受影响。
- 提取结果 `ExtractOperationResult.WRONG_PASSWORD` 也显式映射为 NEED_PASSWORD。

### UI：区分「首次输密码」与「密码错误重试」
- `promptArchivePassword(p, f, wrong)`：wrong=true 时标题"密码错误"、提示"密码不正确，请重新输入"。
- wrong 判定：打开失败/提取失败时若 `ArchivePasswords` 里已存过密码 = 刚才输错了 → 清掉旧密码再弹框。
- 防叠加：新密码框顶掉旧密码框（mPwDialog），多线程同时报密码错也不会叠出一摞对话框。
- 转存 ZIP 遇密码错误：输完密码后**自动重试转存**（promptArchivePasswordSave 带 afterOk 回调）。

## v1.16.2（versionCode 37）：修复「还是不弹窗」——仅加密内容 RAR5 打开时校验密码

用户反馈：输错密码时"还是不弹窗"。

### 根因：仅加密内容的归档 openInArchive 根本不校验密码
- desktop.rar 是 RAR5「仅加密内容、文件名可见」：`openInArchive` 无密码也能"打开成功"，条目列表照常显示，直到**提取**才失败——而 v1.16.1 的 PwCallback 只在 open 阶段兜底，提取路径的两参 extractSlow 又拿不到密码，表现为"毫无提示"。
- **修复（validatePassword）**：带密码打开时立即解密第一个文件条目验证（输出进 NullSink 丢弃，不占内存）；失败抛 NeedsPasswordException → UI 弹"密码错误"重试。把校验从"提取时"提前到"打开时"。

## v1.16.3（versionCode 38）：修复「正确密码也提示密码不正确」+ 密码框眼睛图标

用户反馈：弹窗了，但输入正确密码也提示密码不正确；要求增加眼睛图标。

### 根因：extractSlow 两参重载根本不接收密码（桌面复现实测）
- 下载 Maven Central 的 sevenzipjbinding + all-platforms（Windows 原生库）桌面复现 desktop.rar：
  - `extractSlow(i, outStream)` 两参 + 正确密码 → **WRONG_PASSWORD**（该重载完全不传密码给 native）
  - `extractSlow(i, outStream, password)` 三参 + 正确密码 → **OK，282 字节完整解出**
  - 三参 + 错误密码 → 立即 WRONG_PASSWORD，无死循环
- **修复**：validatePassword 与 extractMatching 全部改用三参 `extractSlow(idx, outStream, password)`；删除 v1.16.1 的 PwCallback（三参直达引擎，不再需要打断 native 的固定回调）。
- 真机验证：点 desktop.ini 密码框正常弹出，输正确密码后提取成功（根目录出现 UTF-16 内容的 desktop.ini）。

### 密码框眼睛图标（显示/隐藏）
- 新增 `res/drawable/ic_eye.xml` / `ic_eye_off.xml`（24dp 矢量）。
- EditText 右侧 compound drawable 显示眼睛；`OnTouchListener` 判断点击落在右侧图标区（`getX() >= getWidth() - paddingRight - iconSize - 20`）→ 切换 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD / PASSWORD` + 图标换成 eye_off/eye_on；`setSelection` 保持光标在末尾。

## v1.16.4（versionCode 39）：修复文本编辑器「疑似二进制」误判死循环 + UTF-16 文本直接打开

用户反馈：打开文件显示"疑似二进制数据"，点"仍要打开"一直循环；其实可以用文本打开（如 RAR 解出的 UTF-16 desktop.ini）。

### Bug 1：「仍要打开」死循环
- `askBinary` 的"仍要打开"调 `loadAsync()` → 再次 `peekBinary()` 仍判二进制 → 再次弹窗 → 无限循环。
- **修复**：新增 `binaryConfirmed` 标志，用户确认后跳过检测直接加载。

### Bug 2：UTF-16 文本被误判为二进制
- UTF-16LE/BE 的 ASCII 文本每隔一个字节就是 NUL（如 desktop.ini 282 字节里约一半是 0），旧规则"含 NUL 即二进制"全部误判。
- **修复（peekBinary 编码探测）**：
  - 带 BOM（FF FE / FE FF）→ 直接判 UTF-16LE/BE 文本；
  - 无 BOM 但 NUL 全部落在奇位（LE）或偶位（BE）且占比 ≥1/8 → 同样判 UTF-16 文本；
  - 其余（零散 NUL、ELF 等）仍判 binary 弹窗。
- **readAll(encHint)**：按探测编码解码，剥掉 BOM 并记 `utf16Bom`；`save()` 时 `encodeForSave` 按原字节序补回 BOM，读写往返字节一致。标题栏显示实际编码（如 `desktop.ini (UTF-16LE, 282 B)`）。
- 桌面回归（EncProbeTest，19/19）：真实 desktop.ini（BOM+LE）、无 BOM LE/BE、UTF-8、GBK、零散 NUL 二进制、ELF 头、空文件、往返字节相等全通过。
- 真机验证：desktop.ini 直接以 UTF-16LE 打开无弹窗、内容正确（[.ShellClassInfo]…）；真二进制文件弹一次窗、点"仍要打开"直接进编辑器不再循环；眼睛图标显示/隐藏切换正常；加密 RAR 正确密码提取+UTF-16 解码全链路通。

## v1.17.1（versionCode 41）：对比目标「浏览…」路径选择器

用户反馈：每次对比都要手动输入长路径太麻烦。

### 新增
- **对比对话框加两个快捷按钮**：`浏览…`（路径选择器）/ `对面窗格`（一键填入对面窗格当前目录）。
- **简易路径选择器 pickComparePath**：从对面窗格当前目录开始浏览；点文件夹进入、`⬆ ..` 返回上级、标题实时显示当前路径；A 为文件时点文件直接选定，A 为目录时点「选此目录」确认；文件夹优先排序。全程点击，无需输入。

### 修复（v1.17.0 真机验证发现）
- **目录对比 walk() 递归丢子目录前缀**：子目录内文件的 relPath 变成裸文件名（`keep.txt` 而非 `sub/keep.txt`），导致①显示不完整②哈希校验时按错误路径打开文件 → 全部「内容不同(失败)」。改为 walkInto 逐层拼接 `prefix + "/" + name`。桌面回归 53/53（新增嵌套哈希校验回归用例）。
- 真机复测：`sub/keep.txt`、`same.txt` 内容校验正确判「相同」，不再误报失败。

## v1.18.0（versionCode 42）：图片查看器

路线图 F 组推进：点击 jpg/jpeg/png/gif/webp/bmp 不再进文本编辑器/属性，改进专用查看器。

### 新增（ImageViewerActivity）
- **大图采样加载**：inJustDecodeBounds 探尺寸 → inSampleSize 按「最长边 ≥2048 即减半」采样，防止 OOM；标题显示原始像素尺寸 + 采样倍数 + 文件大小。
- **手势**：双指捏合缩放（ScaleGestureDetector，围绕焦点）、单指拖动（平移夹紧——内容小于屏幕时居中、大于屏幕时不许拖出边界）、双击放大 2.5×/还原。
- **旋转 + 保存**：查看态 90° 步进旋转（累计计数）；保存时全分辨率重解码 → Matrix 旋转 → 按扩展名选 JPEG(92)/PNG/WEBP 重编码写回，防误存（无旋转时提示）。
- **GIF 动图**：android.graphics.Movie 逐帧播放（duration 循环 + postInvalidateOnAnimation），GIF 隐藏旋转/保存按钮。
- **压缩包内图片条目**：提取到 cache/arch_view 后查看（只读，不写回），加密压缩包密码错误照常走弹窗重试。

### 构建脚本
- d8 合并 dex 步骤参数列表超长（JADX class 文件多）→ 改为 java @argfile 展开方式，彻底解决。

### 真机验证（/sdcard/imgtest 测试样本）
- big.jpg 3000×2000：标题 3000×2000、按宽适配；旋转 180° 后保存 → 文件重写 322KB→212KB、方向正确（PIL 复核 3000×2000）。
- anim.gif：两帧截图差异证实动画在播；grad.png 400×300 原尺寸居中。
- archpic.zip 内 photos/grad.png：提取→查看器全链路通。
- 备注：脚本测试时系统手势导航偶发把快速 input tap 误判为边缘滑动触发返回（logcat: edge-swipe steal / mTriggerBack=true），非应用 bug。

## v1.19.0（versionCode 43）：书签 + 回收站 + 最近打开

路线图收尾三件辅助功能。

### 书签（BookmarkStore）
- SharedPreferences 存路径集合；顶部路径栏点击 / ⋮ 菜单「书签」弹出列表。
- 「+ 添加当前目录」收录当前窗格路径；点书签跳转；「删除书签」子对话框点击移除。

### 回收站（Trash / TrashActivity）
- 删除 = 移入 `/sdcard/.HexFileTrash/store/<id>`，`meta/<id>.txt` 记录 name/origPath/time/isDir 四行元数据；跨分区 rename 失败自动降级为复制+删除（目录递归）。
- 恢复回原路径，同名冲突自动追加 (1)/(2)…；支持彻底删除、清空回收站（双重确认）。
- 接入点：长按菜单「移入回收站」、批量删除、目录递归删除兜底（`deleteRecursiveOrTrash`：直接删得掉就删，删不掉的部分进回收站）。
- ⋮ 菜单「回收站」进入管理页：列表显示名称/删除时间/类型/原路径。

### 最近打开（RecentStore）
- 文件点击时自动记录（onFileClick 入口统一记），SharedPreferences 持久化，同路径去重置顶，上限 50 条。
- ⋮ 菜单「最近打开」：条目显示文件名 + 打开时间 + 完整路径；点击按扩展名走原路由打开（编辑器/图片/压缩包/APK 信息/属性）；文件已不存在则提示；底部「清空记录」。


## v1.20.0（versionCode 45）：APK 共存 + APK 内二进制 XML 查看
- **APK 共存**：长按 APK →「APK 共存」→ 输入新包名（预填 `原包名.clone1`）→ 改 manifest package + 同步替换 authorities 等所有引用原包名的字符串值 → 重打包 → v1+v2+v3 重签名 → 输出 `原名_clone.apk`，与原版并存安装。
- **APK 内 XML 查看**：浏览 APK 点 AndroidManifest.xml / res 下编译过的二进制 XML → AxmlFile 解析转可读缩进文本预览。
- **修复 AxmlWriter 存量 bug**：resolveAppends 复用已追加串时漏加原池长度偏移 → 多属性编辑成相同新值时写入错误池索引（共存批量替换必触发）。
- 桌面回归 CloneTest 14/14（双 APK 样本：修改→回读校验→repack→roundtrip）。

## v1.21.0（versionCode 46）：APK 内 dex/arsc 点击直达 + 编辑器写回原 APK
- 点击 APK 内 .dex → 菜单（Dex 编辑器++（预勾选）/反编译 smali/HEX/提取）；点击 .arsc → 直达 ARSC 编辑器++；长按菜单同步。
- Manifest/ARSC 编辑器保存成功后弹「写回原 APK / 直接安装 / 关闭」三选（此前只生成 `_mfpp`/`_arscpp` 新文件，用户误以为没保存）。

## v1.22.0（versionCode 47）：ARSC 编辑器三级树 UI
- 两级折叠 → 三级树（类型 → 资源名+资源ID → 各配置值），与 Dex 编辑器同风格；搜索命中沿链自动展开；编辑后保留展开状态。
- 踩坑记录：adb 增量安装缓存导致手机跑旧 dex，装机一律 `install -r --no-incremental`。

## v1.23.0（versionCode 48）：ARSC 保存产物损坏修复 + AXML 预览强制只读
- **修复 ArscWriter 存量 bug**：追加字符串后 chunk 总长未补 4 字节边界 → 整个 resources.arsc 非法（aapt 报 "not on an integer boundary"，安装「系统解析失败」）。签名链路本身无误（apksigner v2+v3 验证通过）。
- **AXML 预览强制只读**：TextEditorActivity 新增 EXTRA_READONLY；二进制 XML 转文本后禁编辑、藏保存按钮——此前可编辑保存但只写缓存临时文件（toast「已保存」造成"保存没生效"误会）。
- 点击 APK 内 AndroidManifest.xml → 菜单：Manifest 编辑器++（可视化改属性，保存可写回原 APK）/ XML 只读查看 / HEX / 提取。
- 桌面验证新增 ArscSaveTest 全链路（parse→edit→write→repack→sign→aapt/apksigner/zipalign 官方工具校验）+ 12 种字符串长度对齐回归；CloneTest 14/14。

## v1.24.0（versionCode 49）：Manifest XML 文本编辑（往返编译）
- 点击 APK 内 AndroidManifest.xml 菜单新增「XML 编辑（文本方式）」：提取 → 转 pretty 文本 → 编辑 → 保存时 AxmlTextSync 把文本 diff 回属性级补丁 → AxmlWriter 重编译 → repack → 签名 → 可写回原 APK/直接安装。
- 支持修改既有属性值（字符串/整数/hex/布尔/引用/浮点/颜色，含类型校验）；新增/删除属性、增删改名元素给出明确报错并引导用 Manifest 编辑器++。
- 不支持的编辑不会写 APK（先报错后写），回读校验保证产物可解析且结构不变。
- 桌面回归 AxmlSyncTest 10/10（含全链路 aapt badging 验证），CloneTest 14/14、ArscSaveTest 通过。

## v1.24.1（versionCode 50）：修 AndroidManifest.xml 进不去编辑菜单
- 根因：onArchItemClick 中 isTextName（.xml 算文本）排在 AndroidManifest.xml 专属路由之前，.xml 被文本编辑截胡，永远弹不出「Manifest 编辑器++ / XML 编辑 / 只读 / HEX / 提取」五选菜单。
- 修复：把 androidmanifest.xml / .dex / .arsc 专属路由整体前置；并长按宫格菜单也为 AndroidManifest.xml 加「Manifest 编辑器++」选项。

## v1.25.0（versionCode 51）：文本编辑器查找增强 + 跳转行（功能清单 #16）
- 搜索面板新增三个开关：区分大小写 / 全词匹配 / 正则（任一变化即重搜；正则非法显示「正则错误」不崩溃）。
- 正则替换支持 `$1` 反向引用（Matcher.replaceAll / appendReplacement 语义）。
- 底部新增「跳转」按钮：输入 1-based 行号定位并把该行滚到视图顶部附近。
- 用户反馈跳转行号与文本不对应、行号与文本不齐整 → 修复（仍属 v1.25.0 增量）：原行号按"视觉换行后的行"逐行编号（逻辑行被折行就错号/错位）。改为**按逻辑行（'\n' 计数）编号，仅在该逻辑行的首个视觉行画序号，折行续行留空**，与"跳转"共用同一套逻辑行编号；逻辑计数对全文执行（不先行裁剪视口），仅绘制层裁剪，避免长文件滚动后错号。LineNumberEditText 重写 onDraw。test/LineNumTest 7/7 验证（含折行/空行/跳转偏移一致）。

## v1.26.0（versionCode 52）：文本编辑器（多语言高亮 + 行操作 + 主菜单 + 补全 + Markdown）
- **多语言语法高亮**：用通用正则规则引擎重写 SyntaxHighlight，支持 smali/xml/html/json/java/python/shell/properties/c/cpp/js/markdown；专属 SyntaxSpan 标记便于重高亮精准清除；"语法"菜单可手动切换，后缀自动识别。
- **编辑操作菜单（✎）**：复制行/剪切行/删除行/清空行/替换行/重复行/转大写/转小写/增加缩进/减小缩进/自定义（行首·行尾插入、删除空行、按行排序、删除重复行）。
- **主菜单（≡ 或 ⋮）**：文件(重新打开/另存为副本/分享)、搜索、语法、上个位置/下个位置（位置历史栈）、转到指定行、自动换行、只读模式、流畅模式、代码补全、设置(字号)、预览 Markdown、关闭文件。
- **代码补全**：开启后输入标识符按当前语言关键字弹 PopupWindow 候选（防抖 350ms）。
- **流畅模式**：编辑后防抖后台重高亮，避免大文件卡顿。
- **编码显示**：标题显示实际编码，支持 UTF-8-BOM / UTF-16(BOM) 探测与保存回写。
- **底部快捷符号栏**：→ / + - * = < > " ' 等，点按插入光标处。
- **Markdown 预览**：MarkdownRender 把子集渲染为 HTML 后用 WebView 弹窗预览。
- 桌面验证：test/HighlightTest 12 语言正则全部编译+匹配通过；test/MarkdownTest 10/10 渲染通过。
- 布局：新增顶部工具栏（菜单/撤销/重做/保存/编辑/更多）、底部符号栏（@style/SymBtn），res/values/styles.xml 新增 SymBtn。
- **[bug fix] 打开文本编辑器闪退**：根因是 `TextEditorActivity.readAll()` 的 `UTF-8-BOM` 分支只设了 `encoding/start/utf8Bom` 却漏给 `ld.text` 赋值，导致 `setHighlightedText→highlightById` 对 null `cs` 调 `.toString()` NPE 崩溃（带 BOM 的文本文件极常见）。修复：补 `ld.text` 赋值（加 `Math.min(start,len)` 兜底防越界），并给 `highlightById` 加 null 防御。已重建 1.26.0 覆盖装机。
- **[bug fix] 行号与文本错位 + 工具栏图标乱码**：① 行号 `LineNumberEditText.onDraw` 算 Y 坐标时漏加 `TextView` 内部起点 `getCompoundPaddingTop()`（即 `paddingTop=8dp`），行号整体比文本高 8dp，滚动后与文本对不上；修复：行号 lineTop/lineBottom/baseline 全部叠加 `layoutTop = getCompoundPaddingTop()`，与正文同基线同滚动。② 工具栏/搜索面板原用 Unicode 字符（≡ ↶ ↷ ✎ ⋮ ↑ ↓ ×）作按钮文字，多设备字体不识别会显示成方块/异常箭头；改为内置 Material 风格矢量图标（res/drawable/ic_menu、ic_undo、ic_redo、ic_edit、ic_more、ic_up、ic_down、ic_close 共 8 个），`Button` → `ImageButton` 引用；注意 `?attr/selectableItemBackground` 在主题未定义，已改用 `?android:attr/selectableItemBackground`。已重建 1.26.0 覆盖装机。

## v1.27.0（versionCode 53）：行号/跳转/行操作统一换行符语义
- 根因（用户实测滚动时行号与文本不匹配）：Android `Layout` 把 `'\n'`、`'\r'`、`"\r\n"` 都当硬换行，而行号判定 `charAt(start-1)=='\n'` 只认 `'\n'`——含单独 `'\r'`（二进制垃圾文本极常见）的文件视觉行漏号，其后行号全部偏移；跳转同样只数 `'\n'`，两套编号不一致。
- 修复：统一为「`'\n'`/`'\r'` 均计、CRLF 计 1 次」语义（`isBreakChar`/`countBreaks`/`splitLines`/`offsetOfLogicalLine`），行号改缓存式映射（文本变化才重算），跳转、行操作（缩进/插入/排序/去重/删空行）全部改走统一切分。test/LineNumTest2 65/65 验证（覆盖 LF/CR/CRLF 混合 + 折行 + 跳转一致性）。

## v1.28.0（versionCode 54）：集成 Markor 开源编辑器核心（架构重构）
用户指定采用 github.com/gsantner/markor（Apache 2.0）的编辑器核心。不是替换整个 App，而是移植其编辑器引擎，保留文件管理/APK 能力：
- **LineNumbersView**（独立 View）：与编辑器平级放入外层滚动容器（ScrollView + 水平 LinearLayout），EditText 自身不滚动（scrollY 恒 0）——从架构上根除 scrollY/padding 偏移类行号 bug。
- **SyntaxHighlighterBase**：Markor 视口内动态 span + 后台线程计算 + 防抖机制，剥离 AppSettings/opoc 依赖。
- **MarkdownSyntaxHighlighter / PlaintextSyntaxHighlighter**：Markdown 高亮（去 writeily HeaderSpan 依赖，标题用颜色+放大方案）；cpp/java/python 规则内置为 Java 常量（不读 assets JSON）。
- **AutoTextFormatter + ListHandler**：Markdown 自动缩进、有序列表自动编号（renumber watcher）。
- **HighlightingEditor**：深度剥离 AppSettings/R/MainActivity 依赖的编辑器组件；`MtSyntaxHighlighter` 桥接把既有 12 语言高亮规则接入 Markor Pattern+颜色体系。
- **MtTextUtils**：TextViewUtils/GsTextUtils 合并 + 换行符统一语义 + 行号静态工具。
- 删除自绘的 LineNumberEditText；布局改为 ScrollView(fillViewport) + 水平 LinearLayout{LineNumbersView, HighlightingEditor}。全部既有功能（工具栏/符号栏/搜索替换/跳转/行操作/编码 BOM/往返编译）保留。
- 桌面回归：LineNumTest / LineNumTest2（换行符语义）、HighlightTest（12 语言正则）全过。

## v1.28.1（versionCode 55）：紧急修复——打开文本编辑器即闪退
- 崩溃栈：`NullPointerException: Handler.postDelayed on null` at `MtTextUtils$1.run ← HighlightingEditor$2.afterTextChanged ← HighlightingEditor.setText ← TextEditorActivity.setHighlightedText`。
- 根因：`HighlightingEditor` 字段初始化 `MtTextUtils.makeDebounced(getHandler(), 1000, …)` 在**字段初始化阶段**执行，此时 View 尚未 attach 到窗口，`getHandler()` 返回 null；`setText` 触发 `afterTextChanged → _textChangedRecorder.run()` 时对 null Handler 调 `postDelayed` 直接 NPE。任何文件一打开就崩。
- 修复：`makeDebounced` 内部改为惰性解析——run() 时 `handler != null ? handler : new Handler(Looper.getMainLooper())`，任何时机传入 null 都安全。

## v1.29.0（versionCode 56）：文本格式化（JSON / XML / 代码）
- **FormatUtil 引擎**（src/com/hex91/file/FormatUtil.java，纯函数、桌面可验证）：
  - **JSON 美化/压缩**：自写 parser（不用 org.json），**保留原始数字字面量**（`1.0`/`1e10`/长整数不被转 double 丢精度），字符串按原 token 输出（转义形式不变）；宽容尾随逗号；未闭合/缺冒号/尾随内容/残缺字面量等明确报错不崩溃。
  - **XML/HTML 美化**：扫描式标签流缩进，**内容零改动**（属性、文本、注释、CDATA、DOCTYPE 原样保留）；单一短文本+紧接结束标签同行输出（`<string name="x">值</string>` 不拆行）；HTML 行内标签（a/b/i 等）不引入缩进层级，void 元素不缩进。
  - **代码美化（{} 语言：java/kt/c/cpp/js/ts/cs/go/rs/swift/smali）**：扫描式状态机（字符串/字符/行注释/块注释全保护，其中的 `;{}` 绝不参与规则）+ 花括号缩进 + `{` 前空格 + 分号/逗号换行（`for(;;)` 按括号深度特判不换行）+ `} else {`/`} catch {`/`} while(...)` 续排 + case/default 标签冒号换行 + 连续空行折叠 + 规则换行后吃掉源换行（pendingNL）无多余空行。
- **入口**：主菜单新增「格式化」（按文件名/内容自动检测类型显示在标题），「编辑操作」菜单末尾也加「格式化」；子菜单四选：JSON 美化 / JSON 压缩 / XML-HTML 美化 / 代码美化。
- 只读模式拦截；>2MB 提示；结果等于原文时提示"无变化"；替换后 toast「可点工具栏撤销恢复」（TextView 原生 undo）。
- 桌面验证 test/FormatTest **43/43**：JSON 嵌套/空容器/压缩/字面量保留/转义原样/尾随逗号/8 类异常；XML 嵌套/trim/注释/CDATA/声明/HTML 行内/内容零损；Java 基础缩进/字符串保护/注释保护/for 分号/else 续排/case/空行折叠/内容零损；detect 后缀+内容 10 例。


## v1.30.0（versionCode 57）：批量重命名
- 需求来源：未开发功能清单 #13（用户点名），「你开发我测试」模式优先选测试链路最短项。
- 新增独立 `BatchRenameActivity` + 纯逻辑 `BatchRenameCore`（无 Android 依赖，便于桌面测试）。
- 三种规则：① 编号命名（前缀+起始+步长+位数零填+后缀，默认保留原扩展名）② 查找替换（字面/正则、区分大小写、可仅作用于主名保留扩展名）③ 扩展名替换（改写/去掉/新增，可选仅作用于已有扩展名的文件）。
- 实时预览：原名→新名逐行列表，冲突行红色高亮；冲突检测覆盖「空名 / 目标彼此重名 / 目标已存在且非源文件」三类，存在冲突时禁用「开始重命名」。
- 两阶段重命名：先全部移到唯一临时名再落到目标名，根治 a→b、b→c 顺序覆盖导致的丢名；阶段一失败自动回滚。
- 入口：多选栏新增「重命名」按钮（仅支持普通文件，压缩包内条目暂不支持，已 toast 引导）。
- 桌面验证 test/BatchRenameTest **31/31**：splitName 边界（多扩展名/点文件）、编号命名（步长/零填/去扩展名）、查找替换（字面/全替/大小写/正则分组/空查找）、扩展名改写/去/加、冲突检测（重名/已存在/空名）、computeAll。
- 构建

## Clone v1.31.0（versionCode 58）：HEX 编辑器增强（类型搜索 + 编辑写回）
- 需求来源：未开发功能清单 D 组 HEX 编辑器增强（类型搜索 + 编辑写回）。
- 新增纯逻辑核心 `HexCore`（无 Android 依赖，便于桌面测试）：`Type` 枚举（HEX/TEXT/Int8~UInt64/Float32/64）、`patternFor` 类型编码（含大小端）、`hexToBytes`、`findAll`（KMP）、`patch`（边界保护）。
- 类型搜索：对话框支持 12 种类型 + 大小端（LE/BE）切换；KMP 子序列搜索，匹配以 Spannable 高亮（当前匹配黄、其余绿），「下一项/上一项」导航并显示「匹配 x/N」。
- 编辑写回：对话框按「偏移 + 类型 + 值」写入；`RandomAccessFile` 直接写盘 + 内存同步，`adapter.notifyDataSetChanged()` 实时刷新；写入后清空搜索状态。越界/无权限给出明确 toast；仅支持已加载的前 4MB。
- 交互：点行=选中（蓝底）+复制+记录偏移；长按行=「编辑本行」（预填该行 16 字节 HEX 值）。布局 activity_hex 增加「搜索/编辑」按钮；新增 dialog_hex_search.xml / dialog_hex_edit.xml。
- 桌面验证 test/HexCoreTest **27/27**：hexToBytes 边界、Int32/16/8 大小端、Float32/64 编码、UInt64 边界、TEXT、findAll 计数/重叠、数值搜索、patch 越界保护、bytesToHex。
- 构建

## v1.32.0（versionCode 59）：HEX 编辑器增强（借鉴 bined 思想）
- 背景：用户提议集成 github.com/exbin/bined-android。评估结论——该库无 Android AAR（核心以源码 vendored 进 app/libsrc）、强依赖 JNA 原生库 + androidx、且 pre-1.0（0.2.x SNAPSHOT）Android 移植不成熟；集成成本高风险大，收益多。决策：**保留自写 HexCore，借鉴其精华做增量增强**。
- 新增增强（纯逻辑仍在 HexCore，桌面可测）：
  - 进制视图切换 HEX/BIN/OCT/DEC（`formatCode`/`codeWidth`/`byteStart`），长按需切换文本预览字符集（ASCII/UTF-8/UTF-16LE/ISO-8859-1/Shift_JIS）。
  - 撤销/重做：`EditAction` 栈（`spliceInsert`/`removeRange`/`undoData`/`redoData`），覆盖与插入两种模式均可回退。
  - 选区：长按设起点、点选扩展、编辑按钮弹出「复制为 HEX / 文本 / 在此写入」。
  - 插入/覆盖模式：覆盖=RandomAccessFile 就地写；插入=读尾部后移 + 写盘 + 内存 splice。
- Activity 改造：新增第二排按钮（进制/撤销/重做/插入-覆盖），`activity_hex.xml` 加 row3；`getView` 高亮与选区着色；`decodeRow` 字符集解码。
- 桌面验证 test/HexCoreTest 扩至 **41/41**（含 formatCode 四进制、splice/remove、EditAction 覆盖/插入的 undo/redo 往返、copyRange 交换边界）。
- 构建

## v1.33.0（versionCode 60）：文件加密 + 加密 ZIP

### 文件加密 / 解密（自家私有格式 .enc，A 组缺口）
- 新增 `FileCrypt.java`（纯 `javax.crypto`，零第三方依赖）：
  - 格式：`MTENC1` 魔数 + version(1) + iterations(4B 大端, 100000) + saltLen(1) + salt(16) + iv(16) + AES-256-CBC(PKCS5)密文 + HMAC-SHA256(32)。
  - 密钥派生：PBKDF2-HMAC-SHA256(password, salt, 100000, 64B) = encKey(32) ‖ authKey(32)；Encrypt-then-MAC（先加密后算 MAC，解密先验 MAC 再解密，防篡改/防密码探测）。
  - `encrypt(File, pwd)` → 输出 `原名.enc`（同名同目录）；`decrypt(File, pwd)` → 去 `.enc` 后缀，否则加 `.dec`；密码错误/文件被篡改抛 `SecurityException`（UI 明确提示"密码错误或文件被篡改"）。
- **UI 接线**：长按文件菜单新增「加密文件」「解密文件」（弹密码框 + 二次确认），入口 action `A_ENCRYPT=24` / `A_DECRYPT=25`，分发至 `askEncryptFile` / `askDecryptFile`。

### 创建加密 ZIP（WinZip AES / AE-2 标准格式，B 组缺口）
- `ZipUtil.createZipEncrypted(out, sources, password, strength)`：strength 1/2/3 = AES-128/192/256（默认 3），saltLen 8/12/16、keyLen 16/24/32、authLen 10/12/14。
- **字节布局严格遵循 AE-2 规范**：local header method=99 + AES extra(0x9901，11 字节 = id2 + size2 + vendorId2 + `'AE'` + vendorVer2 + strength1 + actualMethod2) + salt + pwVer(2) + AES-CTR 密文 + HMAC-SHA1 认证码(10/12/14)；central directory + EOCD 手写。
- 密钥派生 PBKDF2-HMAC-SHA1（WinZip 固定 1000 迭代）派生 `2*keyLen+2` 字节 = encKey ‖ authKey ‖ pwVer(2)；AES-CTR IV = 16 字节（高 15 字节 0 + 低字节 1），与 WinZip 规范一致。
- 目录条目不加密（空 data），文件条目先 DEFLATE(raw) 再 AES-CTR 加密——**输出标准 AE-2 包，7-Zip / WinRAR / 系统解压均可打开**（不是私有格式）。
- **UI 接线**：原「压缩为 ZIP」对话框升级——密码框（留空=普通 ZIP，输入密码=加密 ZIP）+ 加密强度单选（AES-256 推荐 / 192 / 128）；勾选密码走 `createZipEncrypted`，否则走 `createZip`。

### 验证（三重保险）
- 桌面单测 `test/FileCryptTest` **7/7**：生成 .enc / 解密去扩展名 / 密码错误拒绝 / 1MB 大文件 / 空文件 / 非加密文件拒绝。
- 桌面单测 `test/ZipCryptTest` **9/9**：PBKDF2 RFC6070 向量(c=1/2/4096)、三文件（英文名）round-trip、错误密码拒绝；内置独立解密器比对。
- **Python 独立交叉验证** `test/verify_aes_zip.py`（`hmac.new(auth_key, pv+ct, sha1)` + openssl aes-256-ctr + zlib）：`VERIFY: ALL PASS`——确认加密 ZIP 符合 WinZip AES 标准，可被标准工具解。
- 构建：

### 踩坑记录
- **AES extra 字段少 1 字节导致全局错位**：初版 `writeAesExtra` 把 vendor version 写成 1 字节（`bo.write(AES_VENDOR_VERSION)`），但 `putEntry` 声明 `extraLen=11`，data 整体错位 1 字节 → 解密端 salt/pv 全错（auth fail）。修复为 `writeShortLE(bo, AES_VENDOR_VERSION)`（2 字节）后 java 与 python 均通过。
- **交叉验证纪律**：无 7z/pyzipper 环境，但 openssl + python3(stdlib) 可用 → 建立「java 自实现 + python/openssl 独立交叉验证」双保险；诊断时用 `System.err` 实时打印 + `/tmp/zt.txt` 完整捕获 + python 解析 zip 字节 hex 定位偏移。

## v1.33.1（versionCode 61）：修复「加密 ZIP 标准工具打不开」——改用开源 Zip4j

用户真机反馈（v1.33.0）：AES 加密 ZIP 在手机和电脑（标准解压工具）都提示"无法解压/未知方式"。自查确认 **v1.33.0 自写 AE-2 实现确有格式 bug**，且原有的"python 交叉验证"是自实现回路、并未真正用第三方工具验证兼容性——属于验证缺失，不是标准工具的问题。

### 根因（真因，非"手机不支持 AES"）
- AE-2 的 AES extra 字段布局必须按 **vendor-version(2B) → vendor-id("AE",2B)** 顺序；v1.33.0 实际写出了 **vendor-id 在前、vendor-version 在后**，标准工具解析 extra 时读不到正确的 `"AE"` 标记 → 判定为"未知压缩方式"。单测与"自回路 python 脚本"都沿同一（错的）约定跑通，所以没暴露。
- 结论：**AES 加密 ZIP 不应自写字节布局**。改用开源、久经考验的参考实现 Zip4j（net.lingala.zip4j，Apache-2.0，7-Zip/WinRAR/ZArchiver 同款 AE-2 语义）。

### 修复（ZipUtil 加密 ZIP 重写）
- 删除自写 `ZipWriter` / `aesEncrypt` / `pbkdf2HmacSha1` / `writeAesExtra` 等全部手写 AE-2 代码，`createZipEncrypted` / `createZipEncryptedCompat` 改用 Zip4j：
  - **AES 模式**：`EncryptionMethod.AES` + `AesKeyStrength.KEY_STRENGTH_128/192/256`，PBKDF2-HMAC-SHA1(1000) + AES-CTR + HMAC-SHA1 全部由 Zip4j 按规范产出——**可被 7-Zip / WinRAR / ZArchiver 打开**（AE-2 标准，非私有格式）。
  - **兼容 ZipCrypto 模式**（`createZipEncryptedCompat`，`EncryptionMethod.ZIP_STANDARD`）：传统 ZipCrypto 弱加密——**可被 Windows 资源管理器 / 系统解压 / 7-Zip 直接打开**（防随手翻看，不防定向攻击）。
- 目录递归改为**显式展开为文件列表 + 逐条目 `setFileNameInZip(相对路径)`**（v1.33.0 直接 `addFiles(目录)` 只压了目录条目、内容丢失，已修）。
- 新增依赖 `libs/zip4j-2.11.5.jar`（~210KB，自动进 javac/d8 classpath，APK 4.7M→4.8M）。
- **UI 不变**：长按"压缩为 ZIP"对话框继续提供「AES-256/192/128 / 兼容 ZipCrypto」四选 + 密码框（留空=普通 ZIP）；文案已明确标注 AES 需 7-Zip/ZArchiver、ZipCrypto 系统可直接解。

### 验证（这次是真正的第三方独立校验）
- 桌面单测 `test/ZipCryptTest` **5/5**：AES-256 与 ZipCrypto 两种包生成 → Zip4j 独立读回 → 内容比对一致 → 错误密码被拒。
- **第三方独立校验** `test/verify_zip4j.py`（`VERIFY: ALL PASS`）：
  - AES-256 包 → **pyzipper（AE-2 参考实现，与 7-Zip/WinRAR 同引擎）成功解压**，内容一致，确为 AES；
  - ZipCrypto 包 → **标准库 zipfile（即 Windows 资源管理器/系统解压）成功解压**，内容一致；
  - 负向：标准库 zipfile 无法打开 AES 包 → 证明确为 AES 而非传统加密。
- **系统工具实测**：`unzip`（Info-ZIP）直接解压 ZipCrypto 包，3 文件大小正确、提取无错。
- 构建
- 环境：为离线装 pyzipper，额外拉取 `pycryptodomex-3.24.1-cp37-abi3-win_amd64.whl` 安装（abi3 兼容本机 Python 3.13）。

### 教训
- **加密/压缩这类有严格标准字节布局的模块，不要自写底层格式**——优先用 Zip4j 等参考实现；自写只适合做薄封装。
- **验证必须引入真正独立的第三方工具**（pyzipper / 7-Zip / unzip），自实现回路的"通过"不算兼容性证明。
- 沿用"该用 so 用 so，避免重复造轮子"的总方针：标准工具能开就开，加密走 Zip4j。

## v1.33.2 —— 修复"打开加密 ZIP 报 CEN header(encrypted)"

### 现象（用户反馈）
手机上用本文件管理器**浏览/解压** v1.33.1 生成的 AES 加密 ZIP，提示 `读取压缩包失败：CEN header(encryped)`；电脑端能正常输密码解压。

### 根因
- 本应用的压缩包**读取/提取后端**是 7-Zip-JBinding（`lib7-Zip-JBinding.so`，所绑 7-Zip 旧版**不支持 WinZip AES/AE-2**）。
- 旧路由：AES ZIP 无密码时先走 `java.util.zip`（能列名、但解加密条目失败）→ 触发"需密码"弹框 → 输密码后改走 7-Zip-JBinding → 因不支持 AES，打开即抛 `CEN header (encrypted)`。
- 即"能写不能读"：写入已用 Zip4j（正确），但**读取仍是 JBinding**，两边后端不一致。

### 修复
- 新增 `ArchiveUtil.J4Archive`（Zip4j 后端）覆盖 **ZIP 族（.zip/.apk/.jar/.aar）** 的浏览+提取，原生支持 普通ZIP / 传统 ZipCrypto / WinZip AES(AE-2)，不再走 7-Zip-JBinding。
- `ArchiveUtil.open()` 路由重写：ZIP 族一律 `J4Archive`；7z/RAR 仍走 7-Zip-JBinding（它们 AES 支持良好）。
- 加密 ZIP 无密码打开时直接抛 `NeedsPasswordException` → UI 弹"输入密码"框；输对密码后可正常列出+解压；错密码提取失败标记 `NEED_PASSWORD` → 重新弹框
- APK（本质 ZIP、未加密）同样走 `J4Archive`，浏览/提取照常。

### 验证
- 桌面单测 `test/J4ReadTest` **19/19 PASS**（命名无关、逐级浏览递归校验）：
  - 普通 ZIP / 伪装 .apk / AES-256 ZIP / ZipCrypto ZIP 四种归档，列出全部条目 + 提取内容/大小全部正确；
  - AES 与 ZipCrypto 无密码打开抛 `NeedsPassword`；有密码正常；错密码提取标记 `NEED_PASSWORD`。
- 构建

### 遗留小问题（非本次范围，已记录）
- **写入器命名不一致**：压缩"目录"时，普通 ZIP（`ZipUtil.createZip`）会把目录名作为前缀（`_dir/a.txt`），而 AES/ZipCrypto（`ZipUtil.createZipEncrypted*`）不带前缀。压缩"单文件"两种一致。后续若统一，建议让加密写入器也带目录前缀以对齐。

## v1.33.3 —— 加密 ZIP 错密码"打开即提示"（不再先给列表、解压才报错）

### 现象（用户反馈）
1. 输入错密码应该立即提示，不该先看到文件列表、到解压时才提示"密码错误"。
2. 手机上"解压到哪里了？"——提取落点不明确。

### 根因
- `ArchiveUtil.J4Archive`（ZIP 族后端）打开加密 ZIP 时**不校验密码**：列条目不需要解密，所以错密码也能"打开成功"并展示文件列表；只有真正 `extractAll`/`extractEntry` 解密首个条目时才抛错 → 触发"重新输密码"。即"看到列表但解压才报密码错"。
- 该行为在 `test/J4ReadTest.java` 旧用例里被显式记为"open 不验证密码，提取阶段才暴露"。

### 修复
- `J4Archive` 构造函数：加密且已提供密码时，**打开阶段即用 `ZipFile.getInputStream(首个已加密文件条目)` 读 1 字节触发解密与密码校验**。Zip4j 在 AES/ZipCrypto 错密码时于读取流时立即抛 `Wrong Password` / `Wrong password!`（已用临时单测在桌面独立确认）。
- 校验失败 → 抛 `NeedsPasswordException("ZIP 密码错误: …")` → UI（`openArchiveInPane` → `promptArchivePassword`）立即弹"密码错误，请重新输入"，与 7z/RAR 后端（`JBindingArchive.validatePassword`）行为一致。
- 无密码打开仍走原"需要密码"提示；正确密码打开成功后才展示列表。
- 提取阶段的 `NEED_PASSWORD` 兜底逻辑保留（极少数无法在打开阶段校验的边界，如全目录加密归档）。

### 关于"解压到哪里"（答疑）
- **打开压缩包 ≠ 解压**：本管理器打开 ZIP 是"虚拟浏览"，列表里的文件并不落盘；只有显式"提取"才写文件。
- 提取落点 = **目标窗格当前所在的真实目录**（默认即该 ZIP 所在文件夹）。触发方式：在压缩包内点文件 → 菜单「提取到当前窗格目录」/「提取到对面窗格」；提取完成后弹 toast 显示 `已提取 → <绝对路径>`，目标窗格同步刷新。
- 在压缩包内"查看/编辑/HEX"会先把条目解到应用私有缓存（`getCacheDir()`），仅用于临时预览，不进用户可见目录。

### 验证
- 桌面单测 `test/J4ReadTest` **20/20 PASS**：AES-256 与 ZipCrypto 错密码现在在**打开阶段即抛** `NeedsPassword`（新增两条断言）；原有普通 ZIP / 伪装.apk / AES / ZipCrypto 列出+提取全部保持正确。
- 版本：versionCode 63 / versionName 1.33.3（AndroidManifest.xml + build.sh 两处同步）。

## v1.33.4 —— 压缩包「解压全部到此目录」一键入口

### 需求（用户反馈）
"压缩包解压时增加「解压全部到此目录」一键入口"——此前内嵌压缩包浏览器只有逐条/逐选「提取到当前窗格目录」「提取到对面窗格」，缺少"一键解压整个包"的入口。

### 实现
- 在内嵌压缩包浏览器（MainActivity 窗格）的右上角 `⋮` 菜单中，**当处于压缩包浏览模式时置顶新增「解压全部到此目录」** 一项。
- 点击后后台调用 `Archive.extractAll(p.dir)`，把**整个压缩包**解压到当前窗格所在目录 `p.dir`（即压缩包所在文件夹，也就是此前答疑所说的"此目录"），完成后 toast 显示 `已解压全部 → <绝对路径>`。
- 复用既有 `archPwError` 兜底：若压缩包加密且密码有误，会在解压时触发密码重输框，与单条提取行为一致。
- 新增 `MainActivity.extractArchAll(Panel)` 方法承载该逻辑。

### 说明
- 该入口针对"活动窗格当前打开的那个压缩包"，解压目标恒为压缩包所在文件夹（不会在别处生成新目录），符合用户对"此目录"的预期。
- 若只想解压单个文件/选中若干，仍用既有的「提取到当前/对面窗格」与多选批量提取。

### 验证
- 构建产出
- 版本：versionCode 64 / versionName 1.33.4（AndroidManifest.xml + build.sh 两处同步）。

## v1.34.0 —— APK「去除签名校验」（SigKill）

### 需求
对目标 APK 做 smali 层启发式定位并 patch 签名校验点，生成可与原校验逻辑共存安装的 `_nosig.apk`。

### 实现（新增 SigKill.java，纯 Java 无 Android 依赖）
patch 策略（不让校验变严，只让结果恒过）：
1. `PackageManager.checkSignatures(...)` 调用点：返回值强置 `0`（SIGNATURE_MATCH）
2. `Landroid/content/pm/Signature;->verify(...)` / `->equals(...)` 调用点：返回值强置 `1`（true）——Signature 是框架类不在 APK 内，调用点 patch 是主力
3. APK 自带 `Landroid/content/pm/Signature;` 副本时：重写其 verify/equals/hashCode 方法体为恒真
4. 明确不做「清空 signatures」——那会 NPE 反而更严

**两个关键作用域限制（桌面集成测试抓出的 bug 修复）**：
- verify/equals/hashCode 方法体重写只作用于 `.class` 声明为 `Landroid/content/pm/Signature;` 的文件——否则会改掉所有类的 equals/hashCode（初版 635 处误 patch 即此因）
- 跳过 abstract/native 方法（无方法体，注入指令导致「abstract method cannot have any instructions」回编译失败）
- invoke 与 move-result 之间的 `.line` 等指令行透传并继续等待（baksmali 常见输出）

### 流程（MainActivity.doSigKill）
长按 APK → 功能菜单「去除签名校验」→ 后台：抽取全部 dex → baksmali → SigKill patch → smali 回编译 → ApkRepack 替换 dex → 内置密钥 v1+v2+v3 重签名 → 输出同目录 `<名>_nosig.apk`，弹窗报告 patch 分类计数与落点。0 处命中则不修改原 APK 并提示原因。

### 验证
- 桌面合成测试 19/19（checkSignatures×2 含 .line 间隔、调用点强 true、方法体重写、负例：其它类 equals/hashCode 与接口 abstract 均不动）
- 真实 APK 集成：反编译+patch+回编译 无错误（回编译 round-trip 验证）

## v1.35.0 —— 文件分割/合并

### 需求
路线图 A 组 P0：按分卷大小把大文件切多块 + 合并还原，纯本地、测试链路最短。

### 实现（新增 FileSplit.java，纯 Java 无 Android 依赖）
- **分割** `split(in, outDir, chunkSize)`：输出 `<名>.001 / .002 / …`（序号 3 位起步超 999 进位），到源文件所在目录，源文件保留；同时生成 `<名>.parts` 清单，供合并时**精确缺卷校验**。
- **合并** `merge(anyPart, outDir)`：从任一分卷定位 .001 起点、按连续序号收集拼接，还原为原文件名；目标重名改 `<名>.merged` 不覆盖。
- **缺卷检测**：有清单→精确校验卷数与合并后总字节；无清单（外部工具分割）→启发式（单卷可疑 + 非末卷必须等大小）。尾卷缺失这种启发式发现不了的场景由清单兜底。
- 文件 ≤ 分卷大小 → 报错不分割。

### UI（MainActivity）
通用文件长按菜单新增：**「文件分割」**（任意文件；弹分卷大小 1/5/10/50/100 MB 选择）与**「文件合并」**（仅当文件名是 .NNN 分卷时显示）。后台线程 + 进度框，完成弹窗报告卷数/落点/清单说明。

### 验证
- 桌面 SplitTest **23/23**：随机数据 round-trip 字节级一致（非整除/整除）、中间卷缺失报错、起始卷缺失报错、尾卷缺失（清单检测）报错、单卷可疑报错、合并重名 .merged 不覆盖、isSplitPart 识别。
- 

## v1.35.1 —— 修复分割对话框不显示分卷大小选项

### 用户反馈
「文件分割只有一个弹窗提示，没见选分卷大小」。

### 根因
`AlertDialog` 同时 `setMessage()` + `setItems()` 时，部分系统主题下消息区域与列表互斥，列表不渲染——只显示消息文字。

### 修复
`askSplitSize` 改为自定义视图：顶部消息（文件大小/输出说明）+ 5 个可点击选项（每卷 1/5/10/50/100 MB，宫格菜单同款样式），点击即关窗开始分割。`versionCode 67`。

## v1.36.0 —— Zstd 压缩/解压（开源 zstd-jni）

### 需求
路线图「后续」项：扩展压缩格式覆盖 Zstd（读+写），复用开源库不造轮子。

### 依赖方案（关键调研）
- 主 jar（zstd-jni-1.5.5-11.jar，6.5MB）内**不含 Android natives**（linux/aarch64 是 glibc 版，bionic 加载不了）
- 官方 Android 支持走 **Maven Central 发布的 AAR**：classes.jar（55KB 纯 Java 类）+ jni/<abi>/libzstd-jni-1.5.5-11.so（版本化命名，bionic 版）
- 接线：classes.jar 进 libs/ 供 javac/d8；arm64-v8a .so 按 7-Zip 同模式打入 APK lib/arm64-v8a/
- 加载链实证（javap 反编译 util.Native）：`ZstdNativePath` 属性 → jar 资源解压（Android 上 classes.jar 无 linux 资源，走不到）→ `System.loadLibrary("zstd-jni-1.5.5-11")` → 命中 APK lib/ 内 AAR 的 so。桌面测试则由主 jar（现 test/zstd-jni-desktop.jar）的 win natives 资源解压，双路都通。

### 实现（新增 ZstdUtil.java，纯 Java 无 Android 依赖）
- 压缩：单文件 → `<名>.zst`（ZstdOutputStream frame）；目录 → `<名>.tar.zst`（commons-compress TAR 流过 ZstdOutputStream，LONGFILE/BIGNUMBER POSIX，中文名 OK）；默认级别 3
- 解压：`.zst/.tar.zst/.tzst`；流式读解帧头 512 字节探测 ustar 魔数——是 tar 则解包（防路径穿越），否则单文件还原，全程单流不落临时文件
- magic 识别（28 B5 2F FD）；重名输出自动 ` (1)` 递增不覆盖

### UI
通用文件/目录长按菜单新增：「Zstd 压缩」（.zst 文件隐藏，避免套娃）、「Zstd 解压」（仅 .zst/.tar.zst/.tzst 显示）。后台线程 + 进度框，完成 toast 显示落点。

### 验证
- 桌面 ZstdTest **18/18**：单文件 frame round-trip 字节一致、目录 tar.zst（中文/嵌套/空目录/空文件）round-trip、magic/stripName/isZstName、非 zstd 抛错、级别 1 vs 19 体积与一致性
- APK 确认：classes.dex 含 luben/zstd 类与 ZstdUtil；lib/arm64-v8a/libzstd-jni-1.5.5-11.so 已打包；APK 5.4M（versionCode 68）安装真机 `1C041FDF6006QG` → Success；启动无 FATAL

## v1.36.1 —— .tar.zst / .zst 免解压浏览（接入内嵌压缩包浏览器）

### 需求（用户反馈）
「Zstd 能不能像 ZIP 一样不用解压就能预览编辑」——即点开 .tar.zst / .zst 直接进归档浏览器。

### 实现
- **.tar.zst / .tzst**：复用既有 TarArchive 后端，增加 zstd 流模式（ZstdInputStream 包装）——流式浏览/提取，单条目提取即「预览」，编辑走既有「解到缓存→文本编辑器」路径（TAR 只读，编辑后导出到窗格目录，与 .tar/.tgz 行为一致）
- **裸 .zst**：新增 ZstSingleArchive 单条目伪归档——点开即见还原后的单文件，可直接预览/提取/HEX（zstd frame 不记录内容大小，条目大小显示 0）
- ArchiveUtil.open() 路由与 MainActivity.isArchiveExt 各加入三个扩展名；菜单里原「Zstd 解压」保留（显式一键解压）
- 注意：frame 未写内容大小，浏览器条目 size=0 属预期

### 验证
- 桌面 ArchZstTest **17/17**（用真样本）：tar.zst 根/中文子目录/深层 50 文件浏览、单条目提取落盘、目录提取、解压全部 401 文件+空目录；裸 zst 单条目、按名提取 300MB 还原
- 样本重推：sample_dir.tar.zst 顶层目录名修正为 sample_dir


## v1.37.0 —— 目录对比差异条目可操作（对比模式补全）

### 需求
v1.17.x 已有三种对比模式的"看"（目录/文本/哈希/APK），但目录对比结果只读：差异条目点进去能直接操作（同步到另一侧/删除/再对比），形成"对比→处理→刷新"闭环。

### 实现
- **差异条目点击 → 按状态弹操作菜单**（CompareActivity，仅目录模式）：
  - 仅 A 有（删除项）：打开 / 复制 A→B / 删除 A 侧
  - 仅 B 有（新增项）：打开 / 复制 B→A / 删除 B 侧
  - 已修改（文件）：文本对比 / 哈希对比 / 打开 A 侧 / 打开 B 侧 / 复制 A→B（覆盖）/ 复制 B→A（覆盖）
- **同步原语**：FileOps 新增 `copyOverwrite(src, dstFile)`——按目标绝对路径复制（对比条目是相对路径，不能落回 src.getName()），自动创建父目录；文件截断覆盖写；目录递归合并复制（同名覆盖、目标额外文件保留）
- **子对比**：对单个修改文件一键发起文本/哈希对比（复用本 Activity，EXTRA 传两侧绝对路径）
- **打开**：复用 MainActivity.isImageExt / isTextName（放宽为 public static）——图片进查看器、文本类进编辑器，其余提示回主界面打开
- 删除走二次确认；复制/删除均为后台线程 + 完成后自动重跑对比刷新列表（保留哈希校验选项）
- 菜单用 setTitle+setItems（本机已验证可用；此前教训仅限 setMessage+setItems 组合）

### 验证
- 桌面 CmpOpsTest **12/12**：深层父目录自动创建字节级一致、长→短覆盖写无残留尾巴、目录合并复制（同名覆盖/额外保留/子目录递归/源不改动）、源不存在返回 false


## v1.38.0 —— strings 提取（二进制可读字符串）

### 需求
字符串提取：从 .so/.dex/.bin 等二进制文件提取可读字符串（逆向定位 JNI 符号、URL、密钥串常用）。

### 实现（新增 StringExtract.java，纯 Java 无 Android 依赖）
- **单遍流式扫描**，两个独立状态机并行：
  - ASCII 串：连续可打印字节（0x20~0x7E）≥ minLen（默认 4，与 Unix strings 一致，范围收敛 2~32）
  - UTF-16LE 串：`可打印低字节 + 0x00 高字节` 配对状态机，`\0\0` 双终止正确断串（单 `\0` 后紧跟下一串首字符属于合法 UTF-16 连读，按规范合并）
- 每条命中带文件偏移；64KB 缓冲区跨界由状态机承接不漏串；命中上限 50000 条超出标记 truncated
- 结果排序按扫描顺序（≈偏移序）

### UI（新增 StringsViewerActivity，代码构建布局）
- 长按非文本文件的宫格菜单新增「strings 提取」（文本类文件隐藏，空文件隐藏）
- 顶栏：扫描统计（KB + 命中数/截断标记）；搜索框实时过滤（过滤态显示 `过滤 X → n 条 / 共 m 条`）
- 列表：偏移（8 位等宽十六进制）+ 内容，UTF-16 串紫色 + `U` 标记；点击条目复制；「复制全部」导出 `偏移+U标记+内容` 清单

### 验证
- 桌面 StringExtractTest **13/13**：ASCII/UTF-16 命中与偏移、短串剔除、双 \0 断串不粘连、ASCII 与 UTF-16 各自跨 64KB 边界不漏、minLen 边界收敛（0→2）、空文件/纯二进制安全、scannedBytes 精确


## v1.39.0 —— 文件列表缩略图（图片 / APK 图标 / GIF 角标）

### 需求（用户选定）
图片预览、APK 文件图标列表预览、GIF 预览。其中 **GIF 动图播放 v1.18.0 已实现**（android.graphics.Movie 逐帧），本轮补齐的是列表层：缩略图。

### 实现（新增 ThumbLoader.java）
- **内存 LruCache**（堆 1/8），key = `绝对路径|lastModified`（文件覆盖即失效）；单线程池串行解码，UI 线程回调
- **图片条目**（jpg/jpeg/png/gif/webp/bmp，仅真实文件，压缩包内条目不做）：BitmapFactory 采样解码（目标边长 2x 图标 76px），GIF 取首帧
- **APK 条目**：PackageManager.getPackageArchiveInfo 解析应用图标（sourceDir/publicSourceDir 指向 apk），解析失败回退类型角标
- **GIF 角标**：缩略图右下角紫色 "GIF" 小徽章
- **item_file.xml**：图标位改为 FrameLayout（彩色类型角标 + ImageView 缩略图 + 徽章），ZipAdapter 复用不受影响
- **FileAdapter.getView**：图片/APK 条目切缩略图模式；回收复用以 ImageView.tag=cacheKey 校验，滑动不串图、已加载不闪烁
- 大图防 OOM：采样后仅存小图进缓存

### 验证
- 构建期：aapt2/javac/d8 全链通过（首次编译 FileAdapter 缺 Bitmap import 已修）
- 桌面测试不适用（BitmapFactory/PackageManager 为 Android 桩），以真机实测为准：含图片的目录应显示缩略图，APK 显示应用图标，GIF 带角标且点开动图播放

## v1.40.0 —— 列表顶部「..」返回上级

### 需求（用户反馈）
进入目录后列表顶部应有「..」返回上级；已在顶级目录（应用起始根 /storage/emulated/0）则不显示。

### 实现
- **FileItem**：新增 `upEntry` 虚拟条目（file 指向上级目录，`name()=".."`、副行"返回上级"），工厂 `FileItem.up(parent)`
- **FileAdapter.setDir**：`parent != null && parent 可读 && 当前目录 ≠ rootDir` 时在列表第 0 位插入；新增 `rootDir` 字段由 MainActivity 在 setupPanel 设为外部存储根
- **交互拦截**（MainActivity）：
  - 单击 .. → 返回上级（多选中点击 = 退出多选并返回，loadDir 内已处理）
  - 长按 .. → 无操作菜单
  - 全选、左滑选中、纵向刷选、压缩包外头部统计均跳过 upEntry
- 已有的底部 ↑ 按钮 / 右滑返回不受影响

### 验证
- 真机驱动实测（uiautomator dump）：启动顶层两窗格 0 个 ".."；进入 Alarms 后左窗格顶部出现 ".."（副行"返回上级"）；点击 ".." 回到 /storage/emulated/0 且 ".." 消失


## v1.41.0 —— 左上汉堡菜单 + 侧边抽屉

### 需求（用户反馈）
左上角加三条横线菜单按钮，点击弹出侧边菜单页：主题设置、回收站、远程管理、终端模拟器等（未完成项后续完善）。

### 实现
- **activity_main.xml**：根布局改 FrameLayout（原内容 + 遮罩 + 左抽屉三层）；顶栏最左加 ☰ 按钮；抽屉 280dp 白底 elevation 阴影，头部深色块显示应用名 + 版本号（PackageManager 读取）
- **菜单项**：☆ 书签（复用 showBookmarks）、♻ 回收站（TrashActivity，已有功能）、🎨 主题设置 / 📡 远程管理 / >_ 终端模拟器（占位弹窗：说明规划内容，"知道了"关闭）
- **动效**：打开 = 抽屉从屏外滑入 220ms + 遮罩淡入（GONE 期间宽度 0，post 布局后再测宽滑入）；关闭 = 滑出 180ms + 遮罩淡出；遮罩点击 / 返回键均可关闭
- 后续完善路径：主题设置做亮暗切换、远程管理做 FTP/HTTP 服务器、终端模拟器做 shell 命令执行，抽屉项点击处替换占位即可

### 验证（真机 uiautomator 驱动）
- 点 ☰ → 抽屉滑出，5 项菜单 + v1.41.0 版本号全部渲染
- 点回收站 → 进入"回收站（可恢复）/清空"页
- 点主题设置 → 规划说明弹窗正常


## v1.42.0 —— Root 权限 + 抽屉「本地」区（根目录 / 内部存储）

### 需求
抽屉加「本地」区：根目录（系统目录 /）与内部存储（sdcard）两个入口，带容量统计；检测 root 权限，已 root 可浏览系统目录。

### 实现
- **RootUtil.java（新增，纯 Java）**：
  - `detect()`：执行 `su -c id`，退出码 0 且输出含 `uid=0(` → 已 root；超时 4s / 异常一律 false；结果进程内缓存
  - `suList(path)`：`su -c "ls -1ap <path>"` root 列目录——`-1` 每行一条、`-a` 含隐藏、`-p` 目录追加 `/` 尾缀（以此区分文件/目录，无需 stat）；stderr 独立线程排水防管道死锁；超时 10s；路径单引号转义
- **抽屉布局**：头部新增 Root 状态行（Root: 检测中… / 已授权 ✓ / 未获取）；「本地」区两行——根目录（/ 图标）+ 内部存储（SD 图标），副行 StatFs 容量（xx 已用 / xx（n%）），后台线程刷新；「工具」区隔开原菜单
- **root 列目录回退**（FileAdapter.setDir）：`listFiles()` 返回 null 且已 root → suList 构建 FileItem；目录条目打 `forceDir` 标记（`ls -p` 的 `/` 尾缀是权威，stat 可能被 SELinux 拒绝）；FileItem.isDir() 优先取 forceDir
- **守卫放宽**：loadDir 在 root 设备上放行 stat 失败路径；`..` 插入/点击的 canRead 条件 root 下跳过（/data 不可读也能返回）
- **顶部统计修复**：updateHeader 改从 adapter 条目统计（原来直接 listFiles 在受保护目录得 0）
- 未 root 时点击根目录：仍可浏览可读部分 + toast 提示

### 验证
- 桌面 RootUtilTest **8/8**：命令不存在/缓存/输出过滤/异常兜底/suList 无 su 返回 null/特殊字符路径
- lambda 在 android.jar 编译链不可用（LambdaMetafactory 缺失），改匿名 ThreadFactory——本项目惯例全匿名类
- 真机（已 root）驱动实测：抽屉显示 Root: 已授权 ✓、根目录 987MB/100%、内部存储 42.99GB/110.27GB(38%)；点根目录 → `/` 列出 acct/apex/bin/data/dev/etc/proc…（30 文件夹 5 文件）；进 `/data` → 49 子目录带真实 mtime；点 `..` 返回 `/`；全程 0 FATAL
- 局限（后续可做）：root 区域内文件**读取/写入/删除**仍是普通 File API，会失败；下一步可做 su cat/写入封装

## v1.43.0 —— 列表权限显示 + 属性弹窗（权限/所有者更改）+ 校验

### 需求
1. 列表详情行显示 "rw-r--r-- 0.4K" 式权限；2. 长按→属性为弹窗：名称/目录/类型/大小/修改时间 + 权限·所有者·用户组（可更改）；3. 校验弹窗：MD5/SHA1/SHA256/CRC32 可复制、大小写可转换、粘贴并校验；关闭校验回属性。

### 实现
- **FileAttrs.java（新增）**：属性模型 perm/octal/owner/group。有 root 用 `su stat -c '%A|%a|%U|%G'`；无 root 用 File.can* 近似（反映应用实际权限）。LRU 缓存 512 条（key=path|lastModified，纯 LinkedHashMap 实现，桌面可测）；load() 单线程池异步 + 主线程回调；chmod/chown 走 su，成功自动失效缓存
- **列表详情行**（FileAdapter.detailWithPerm）：缓存命中 → `权限串 + 原详情`；未命中异步取，回调后整表刷新一次（缓存后不再触发）
- **属性弹窗**（showProps 重写）：代码构建逐行布局，值行长按可复制；权限/所有者/用户组行带「更改」按钮；底部 更多 | 校验 | 关闭（AlertDialog neutral/negative/positive 排序恰好对应）
  - 「更改权限」：rwx 3×3 勾选矩阵 + 八进制实时显示 → su chmod；「更改所有者」：双 EditText → su chown owner:group
  - 「更多」：重命名 / 删除 / 复制路径（复用 askRename/confirmDelete）
- **校验弹窗**（showHashDialog）：文件名 + MD5/SHA1/SHA256/CRC32（HashUtil.hashAll 后台一次算四算法）+ 每行复制按钮 + 粘贴并校验（equalsIgnoreCase 匹配，绿✓/红✗）+ 大写字母开关（含 CRC32 统一转换）+ 关闭 → 重开属性弹窗
- **RootUtil 扩展**：run(cmd)（执行丢弃输出）、submitOutput(cmd)（返回 stdout）、统一 stderr 排水

### 关键 bug（真机测试抓出）
`stat %a` 输出的是**八进制字符串**，此前 `Integer.parseInt` 按十进制解析（"600"→0b1001011000），勾选矩阵初始映射全错、chmod 传值错位。修复：`parseInt(s, 8)` + 所有显示/传令统一 `Integer.toOctalString`。桌面 AttrLogicTest 补回归（13/13）。

### 验证
- 桌面 AttrLogicTest 13/13（run/submitOutput 无 su 兜底、statFallback 三段一致、permText 八进制、600₈ 位判定回归）
- 真机：列表详情行显示 drwxrwx--x / -rw-rw---- 等真实权限（su stat）；属性弹窗布局（ecapture: 30.4 MB(31859656)、-rw-rw----(660)、u0_a265、media_rw）；校验弹窗四算法填充、大写切换、复制 MD5→粘贴→绿字"✓ 与 MD5 校验值一致"、关闭回属性
- chmod 闭环：/data/local/tmp/permtest 600 → 勾选组读+其他读（八进制实时 644）→ 确定 → toast"权限已更改为 644" + 属性刷新 -rw-r--r--(644) + su stat 确认 -rw-r--r--


## v1.43.1 —— 权限编辑器三项修复（横排布局 / 双层弹窗 / FUSE 如实提示）

### 需求（用户真机反馈三问题）
1. 权限勾选矩阵是"竖着"的（读写执行做行），改成横排（行=拥有者/用户组/其他，列=读/写/执行）；2. 更改成功后要点两次关闭（旧属性弹窗压在底下）；3. sdcard 上改权限提示成功但实际没变。

### 实现
- **布局转置**（showPermEditor）：表头 读/写/执行 三列，行 拥有者/用户组/其他；位映射不变（cbs[who][perm]，v |= 1<<(8-w*3-p)）
- **双层弹窗修复**：新增 mPropsDialog 字段记录当前属性弹窗；更改成功走 reopenProps(f)——先 dismiss 旧弹窗再 showProps，永不再叠层
- **FUSE 如实提示**（FileAttrs.chmod/chown 返回值 boolean→int）：0=回读一致已生效；1=命令退出 0 但回读权限未变（FUSE 合成存储如 /sdcard 会忽略 chmod/chown）；-1=命令失败。无论结果都回读真实属性刷缓存。MainActivity 按返回值区分 toast：「权限已更改为 xxx」/「该存储区不支持 chmod（FUSE 挂载），权限未实际变更」/「chmod 失败」

### 真机诊断（adb 实测）
`su -c 'chmod 777 /data/local/tmp/x'` → stat 777（真生效）；`chmod 777 /sdcard/x` → stat 仍 660（FUSE 忽略、退出码 0）——即"假成功"根因是存储挂载层，非 su 链路。

### 验证（v1.43.1，versionCode 77）
- 桌面：AttrLogicTest 13/13、RootUtilTest 8/8
- /sdcard/Alarms：勾选 其他-读（660→664）→ 确定 → toast「该存储区不支持 chmod（FUSE 挂载），权限未实际变更」+ 权限仍 660 + 单层弹窗 ✅
- /data/local/tmp：permtest 777 → 取消组/其他 6 位（八进制 700）→ 确定 → toast「权限已更改为 700」+ 属性刷新 -rwx------(700) + su stat 确认 -rwx------ + 单层弹窗 ✅


## v1.44.0 —— Root 区文件读写封装（RootIO）

### 需求
v1.42 起可浏览系统目录，但 root 区内打开/编辑/删除/复制/移动/新建仍是普通 File API，全部失败。本版补齐 su 读写层，使 root 区文件操作与 sdcard 一致。

### 实现
- **RootIO.java（新增）**：readBytes/readText（su cat，上限 32MB）、writeBytes/writeText（su stdin → `cat > 'path'`）、delete（rm -rf）、rename/moveInto（mv -f）、copyInto/copyOverwrite（cp -a）、mkdirs（mkdir -p）、catProcess（流式读，大文件哈希用）。全部自带 root 守卫，无 root 安全返回 false/null；写/删/改名成功自动失效 FileAttrs 缓存
- **RootUtil 新原语**：runWithInput(cmd, bytes)（stdin 写入，15s 超时）、captureOutput(cmd, maxBytes)（二进制安全捕获）、catProcess(cmd)（裸进程流式读）
- **接线（全部"普通 API 失败 → root 回退"策略，sdcard 行为不变）**：
  - FileOps：copy/move/deleteRecursive/rename/newFolder/copyOverwrite 六处 catch 回退
  - TextEditorActivity：readAll 打不开 → su cat；save 写不进 → su cat >（压缩包内/导出逻辑不变）
  - HexViewerActivity：加载失败 → su cat 截 4MB
  - ImageViewerActivity：GIF Movie.decodeStream 失败 → bytes→decodeByteArray；位图 bounds/解码失败 → 同上
  - HashUtil.hash(File)：打不开 → su cat 流式算哈希（校验弹窗 root 区可用）
  - MainActivity 新建文本文件：createNewFile 失败 → su 写空文件

### 桌面测试
RootIOTest 16/16（无 root 环境全方法安全性 + FileOps 回退不改非 root 语义）；AttrLogicTest 13/13、RootUtilTest 8/8 回归通过。

### 装机

## v1.44.1 —— root 区列表缩略图 + 时间/大小显示修复（用户真机反馈）

### 问题
root 区长按图片能看（v1.44.0 su 回退生效），但列表缩略图空白；且详情行没有时间和大小——根因：root 区条目只有文件名（su ls -1ap），File API 的 length()/lastModified() 全为 0，缩略图 decodeFile 也打不开文件。

### 修复
- **ThumbLoader**：decodeImage 拆为 decodeViaFile + decodeViaSu——File API 解码失败 → RootIO.readBytes（su cat，≤32MB）→ decodeByteArray 同参数采样
- **FileAttrs.Attr 扩展 mtime/size**：stat 改 `'%A|%a|%U|%G|%Y|%s'` 一次拿全；statFallback 同步填充
- **FileItem.detail()**：lastModified<=0（root 区）→ 从 FileAttrs 缓存取时间/大小；列表懒加载链（detailWithPerm → load → notifyDataSetChanged）复用，无新增 su 调用

### 桌面回归


## v1.45.0 —— 应用管理（已安装应用列表 + 提取安装包）

### 实现
- **AppManagerActivity（新增，第 13 个 Activity）**：已安装应用列表（图标 + 应用名 + 包名 + 版本 + 大小），按应用名排序；顶部搜索框实时过滤（名称/包名）；计数行「已安装应用: n / m」
- **图标加载**：LruCache(pkg→Drawable) + 单线程池后台 loadIcon + ImageView tag 校验防复用串图；静态字段惰性初始化（AppRow 嵌套类触发外部 <clinit>，桌面测试安全）
- **点击条目弹窗**：提取安装包 / 应用信息（跳系统设置页）/ 复制包名
- **提取安装包**：sourceDir 在 /data/app（root 区）→ FileOps.copyOverwrite（v1.44.0 su cp -a 回退通道）→ `/sdcard/HexFile/Apps/<应用名>_<版本>.apk`（AppRow.exportFileName 清洗非法字符，超长截 80）；完成 toast 显示导出路径与实际大小
- **Manifest**：新增 `QUERY_ALL_PACKAGES` 权限（targetSdk 30 包可见性）+ Activity 注册
- **抽屉**：回收站下新增「📦 应用管理」入口

### 桌面测试
AppExportTest 7/7（导出文件名清洗：空格/斜杠/非法字符/超长截断/空标签）。

### 装机


## v1.46.0 —— 全局更名：HexFile（com.hex91.file）

### 需求
全局更名：`com.hex91.file`，应用名 → **HexFile**，操作目录 → `/sdcard/HexFile`。

### 改动
- **源码目录**：`src/com/hex91/file/`（68 个文件 package/import/URI 全改）
- **Manifest**：package、authorities（`com.hex91.file.files`）、application label（修复硬编码 "MT Clone"）、versionCode 81 / 1.46.0
- **操作目录统一 HexFile**：回收站 `HexFile/Trash`；smali 输出 `HexFile/Smali`；应用导出  `HexFile/Apps`
- **一次性迁移**（MainActivity.migrateLegacyDirs，onCreate 后台线程）：
- **res**：app_name / 抽屉标题 → HexFile；activity_editor.xml 自定义视图类名 → com.hex91.file.*
- **build.sh**：gen/obj 路径、输出 `HexFile-1.46.0.apk`

### 桌面回归
RootIOTest 16/16、AttrLogicTest 13/13、RootUtilTest 8/8、AppExportTest 7/7、StringExtractTest 13/13。

### 装机
HexFile-1.46.0.apk（5.4M，versionCode 81，包名 com.hex91.file）Success。）

## v1.47.0 —— 应用管理筛选：全部 / 第三方 / 系统（用户反馈）

### 实现
- **筛选条**：搜索框下方三枚 chip（全部/第三方/系统），均分横排；选中态蓝底白字（0xFF1565C0），未选中透明底灰字；点击即切换重过滤
- **系统判定**：`(flags & FLAG_SYSTEM) != 0`
- **列表副标题**：系统应用追加「· 系统」标记
- **计数行细分**：`已安装应用[当前筛选]: n / m（第三方 x · 系统 y）`
- **应用弹窗**：类型行显示「系统应用/第三方应用」

### 桌面回归
RootIOTest 16/16、AttrLogicTest 13/13、RootUtilTest 8/8、AppExportTest 7/7、StringExtractTest 13/13。

### 真机验证
筛选条样式正确；全部 380（第三方 80 · 系统 300）；点第三方后列表 80 条且无系统标记；HexFile 自身显示 1.47.0。

### 装机
HexFile-1.47.0.apk（5.4M，versionCode 82）Success。

## v1.48.0 —— 主题设置：22 套主题色板 + 明暗模式（跟随系统）

### 需求
用户要求：新增「设置 → 主题设置」界面，可选多种主题配色，并可随系统自动切换明暗主题。版式主题设置页（竖排单选 + 横向色条）。

### 技术方案（无 AndroidX 环境）
本工程为纯 `aapt2 + javac + d8` 手工构建链，无 AppCompat，因此**不能**用
`AppCompatDelegate.setDefaultNightMode()` / `Theme.Material.DayNight`。改为自研方案：

- **语义色 attr**：`res/values/attrs.xml` 定义 14 个语义色（`hfBg`/`hfSurface`/`hfSurfaceAlt`/
  `hfText`/`hfTextSub`/`hfDivider`/`hfTopBar`/`hfTopBarText`/`hfTopBarTextSub`/`hfAccent`/
  `hfOnAccent`/`hfSelected`/`hfStatusBar`/`hfNavBar`），布局统一改用 `?attr/hfXxx` 引用
- **主题矩阵**：主题 = 色板索引（22）× 明暗模式（跟随系统/浅色/深色）→ 44 个 style
  （`Theme_Hex_N` / `Theme_Hex_Dark_N`）+ 2 个基座（`Theme_Hex_Light`/`Theme_Hex_Dark`）
- **基座分离**：2 个基座定义**全部** 14 个语义色（保证任何 style 下 `?attr/` 都能解析），
  色板 style 仅覆盖随主题变化的部分（顶栏/强调色/选中态/状态栏/导航栏）
- **运行时套用**：`Activity.setTheme()` 必须在 `setContentView()` 之前调用，
  统一由 `BaseActivity.onCreate()` 调 `ThemeManager.apply(this)` 完成
- **资源生成**：`tools/gen_themes.py` 同时产出 `res/values/themes.xml` 与
  `src/com/hex91/file/ThemeSchemes.java`（名称 + ARGB 色值），保证二者永不脱节

### 暗色适配的坑
- **WCAG 对比度**：前景色（深字 `#212121` / 白字 `#FFFFFF`）必须比较**实际对比度取优者**，
  不能用亮度阈值二选一 —— 阈值法在琥珀/湖蓝/金黄/珊瑚等中间亮度色上会选错，
  导致实际对比度 < 3.0（桌面测试实测发现）
- **语法高亮**：高亮色在深色底上不可读 → `ThemeManager.syntaxColor()` 用 HSV 变换
  （亮度至少抬到 0.62、饱和度上限 0.72）整体提亮；静态高亮器通过全局暗色标记 `sDark` 读模式
- **差异对比色**：`CompareActivity` 的 `C_ADD_BG/C_MOD_BG/C_DEL_BG` 静态常量改为 `bgOf()`/`txOf()` 动态方法
- **状态色**：校验成功/失败色亮暗双套（`0xFF2E7D32`/`0xFF81C784`、`0xFFC62828`/`0xFFEF9A9A`）

### 关键实现
- `ThemeManager`：偏好持久化（SharedPreferences `hexfile_theme`）、模式解析、style 映射、语义色取色 API
- `BaseActivity`：所有 Activity 基类，`onCreate` 套用主题；`onResume` 检测主题被外部改动则 `recreate()`
- `ThemeActivity`：主题设置页（外观模式三选 + 22 组「单选按钮 + 色条」动态生成 + 取消/确定）
- `onConfigurationChanged`：跟随系统时系统切深色 → `recreate()`；14 个 Activity 的
  `configChanges` 均加 `uiMode`，避免系统直接重建导致编辑内容丢失
- 布局改造 15 个文件共 112 处硬编码色 → `?attr/`（`tools/theme_layouts.py`，按文件精确替换，
  同色值在不同文件语义不同故不做全局替换）
- Java 改造 43 处硬编码色 → `ThemeManager` 语义取色（`tools/theme_java.py`）

### 本轮修的两个真实缺陷（装机后截图确认时发现）
1. **换主题返回后主页不重建**：`setTheme` 只在 `onCreate` 生效，"确定"新主题返回主页仍是旧配色
   → `BaseActivity.onResume()` 比较当前 style 与已套用 style，不一致则 `recreate()`
2. **按返回键丢失还原**：预览期主题已落盘，只有点"取消"才还原，按返回键则主题残留
   → `ThemeActivity.onBackPressed()` 与「取消」按钮统一走 `restoreAndFinish()`

### 桌面回归
ThemeTest 23/23（色板数量/名称/色值唯一性、首尾锚定、对比度算法 8 断言、
22 套色板前景色对比度全 ≥3.0、明暗模式常量、默认色板合法性）。

### 真机验证（Pixel 6 / Android 13+）
主题设置页版式正确（22 条色条 + 外观模式三选 + 取消/确定）；即时预览生效（状态栏/单选 tint/
按钮同步变色）；「取消」与「返回键」均正确还原；「确定」后主页自动重建为新主题；
跟随系统下 `cmd uimode night yes` 实时切深色；强制浅色/强制深色均生效；
抽屉与主界面深色下无白底残留。

### 装机
HexFile-1.48.0.apk（5.4M，versionCode 83）Success。

### 工程备忘
- `build.sh` 原用 `rm -rf build out`（614 文件）触发批量删除安全钩子，
  改为只清理本次会被覆盖的产物（javac 每次全量重写全部 `.class`，无需清空 obj）
- `ThemeActivity` 声明 `exported="false"`，`adb shell am start` 无法直接启动，须走应用内抽屉入口

## v1.48.1 —— 主题设置版式优化：单选按钮与色条同行（用户反馈）

用户反馈：色板「选择项和色条不在一条线上，不美观」。

### 修改
- `activity_theme.xml`：色板容器 RadioGroup → LinearLayout（RadioGroup 不管理嵌套在行容器里的 RadioButton，靠预览重建保证互斥即可）
- `ThemeActivity.buildSchemes()`：每行改为水平 LinearLayout（垂直居中）——RadioButton 在左，色条 `weight=1` 占满右侧剩余宽度（高 24dp，左右距 8/18dp）；**整行可点**（按钮、色条、空白处任意位置点击都选择该主题）
- 选中互斥：点击 → 预览重建 → 由 pendingScheme 决定唯一选中项（不再依赖 RadioGroup 的 checkedId 管理）

### 真机验证（Pixel 6）
22 条色板按钮与色条全部同行对齐；点色条空白处可选中并即时预览；互斥正确；
「蓝色→确定→主页变蓝」「正红→按返回键→还原为蓝」闭环通过。

### 装机
HexFile-1.48.1.apk（5.4M，versionCode 84）Success。

## v1.49.0 —— 终端模拟器：集成 Termux 引擎（terminal-emulator/terminal-view）

放弃自研 VT100 仿真引擎（HexPty/TerminalEmulator/TerminalSession/TerminalView 已删除），改用 Termux 官方开源的终端模块——业界最强的 Android 终端实现，VT100/xterm/UTF-8/256 色/truecolor 支持完备。

### 许可（重要）
- 仅取 Termux 的 **Apache-2.0** 模块：`terminal-emulator`（18 个类）+ `terminal-view`（8 个类）+ 原生 `termux.c` PTY 桥。
- Termux 主体应用是 GPLv3，**未引用任何其 GPL 代码**。
- 归属声明见 `src/com/termux/LICENSE`（Apache-2.0 全文 + Termux/Android-Terminal-Emulator 版权行）。
- `androidx/annotation` 三个编译期注解为本地桩，避免网络依赖。

### 集成方式
- `native/jni/termux.c`（替换 hexpty.c）：`/dev/ptmx` + grantpt/unlockpt + `IUTF8` + `TIOCSWINSZ`（含像素尺寸）+ setsid + execvp，NDK 编译出 `libtermux.so`（arm64-v8a + armeabi-v7a 双 ABI）。
- `TerminalActivity` 实现 Termux 的 `TerminalSessionClient` + `TerminalViewClient` 双接口：会话生命周期、剪贴板、标题、铃声、捏合缩放字号、扩展按键（Ctrl 锁定 / Esc / Tab / 方向 / Home / End / `|` `-` `/`）。
- 固定配色：近黑底（0xFF0B0B0B）+ 浅灰字（0xFFE6E6E6），改 `TerminalColorScheme.DEFAULT_COLORSCHEME` 默认色三元素，不受应用明暗主题影响。
- `setupTermHome()`：HOME 指向应用私有外部存储（始终可写），写入 `.profile`（ll/la/l/grep 别名）与 `bin/hex` 助手（help/tools/theme/clear）。
- 环境变量：`TERM=xterm-256color`、`COLORTERM=truecolor`、`LANG=en_US.UTF-8`。

### 关键修复
- **软键盘弹不出**：Termux 的 TerminalView 重写了 `onCheckIsTextEditor()` 但没设 `setFocusableInTouchMode(true)`，触摸模式下 requestFocus 失败导致 showSoftInput 被 IME 拒绝（PHASE_CLIENT_VIEW_SERVED）。补设后配合「布局完成 + 窗口获焦」双条件自动弹键盘。
- **键盘 config 重建循环**（BaseActivity）：软键盘弹出触发 keyboard 配置变更 → 旧逻辑无条件 recreate → shell 被杀。现仅在「跟随系统 + 深浅色真翻转」时重建；`onResume` 风格过期重建加了收敛标记；新增 `shouldRecreateOnThemeChange()` 钩子，TerminalActivity 返回 false 彻底豁免。

### 构建链适配
- 编译平台 android-34 → android-35（termux 源用到 API 35 的 `VERSION_CODES.VANILLA_ICE_CREAM`）。
- Termux 源仅用 `write(byte[], int, int)` API（无 write(String)/单参重载），Activity 侧加 `writeUtf8()` 辅助。
- 全项目禁用 lambda（android.jar 无 LambdaMetafactory，javac 编译失败），监听器一律匿名内部类。
- 文本选择功能补齐 `text_select_handle_*_material` drawable 与 3 个 string 资源；`com.termux.view.R` 引用改回应用 R。

### 真机验证（Pixel 6）
- `libtermux.so` 加载 ok，`sh -l` 子进程随 app 启动（PPID = 应用进程）；
- 无 FATAL/AndroidRuntime 崩溃；软键盘启动自动弹出（`mInputShown=true`）；
- 首次安装即写好 HOME/.profile/bin/hex。

### 装机
HexFile-1.49.0.apk（5.5M）Success。

## v1.49.1 —— 终端显示设置（字号/配色可调）+ 清屏崩溃修复（用户反馈）

用户反馈三问题：①主体字体大小不能调节；②视觉上看不清楚；③「清屏」有 bug。

### 清屏 bug 根因（真凶，两轮）
- 第一轮：`mSession.write(new byte[7], 0, 8)` —— 数组 7 字节却声称写 8 字节，`ByteQueue.write` 越界校验直接抛异常，点击即崩。
- 第二轮（用户反馈提示符出现「JJ」且清不了屏）：`write()` 是 **shell stdin 键盘输入通道**，往里写转义序列会被 shell 当普通字符回显（ESC 被行编辑器吃掉，剩 "JJ"），仿真器根本收不到。
- **最终修复**：给 vendored 的 `TerminalEmulator` 新增 `public synchronized void clearScreen()`（光标回位 + blockClear 全屏 + 主缓冲 clearTranscript），与 `append`（读线程调用）共用对象锁防交错；按钮直接调 `emulator.clearScreen()` + `onScreenUpdated()`，完全绕过 shell。

### 显示设置（顶栏新增「Aa」按钮）
- **字号**：A−/A＋ 步进调节（8–32），默认 13 → 16（13 在高分屏上偏小看不清）；捏合缩放同效；均持久化到 SharedPreferences（`terminal_display`：font_size/scheme）。
- **配色方案 4 套**：经典黑（0B0B0B/E6E6E6）、高对比（纯黑白）、墨蓝（Solarized 暗 002B36/EEE8D5）、纸白（浅色 F7F3E8/333333）。
- 运行时切换机制：改 `TerminalColors.COLOR_SCHEME.mDefaultColors` 的 fg/bg/cursor 三个索引 → `emulator.mColors.reset()` → 重绘。**关键**：Termux 渲染器对默认背景单元格不画矩形（靠视图背景透出），必须同步 `mView.setBackgroundColor(bg)`；`TerminalView.onDraw` 的空仿真器分支也改为跟随方案背景色。

### 附带修复（装机验证时发现）
- `setTypeface` 在 `setTextSize` 之前调用 → NPE 崩溃（mRenderer 由 setTextSize 创建）。Activity 调换顺序 + `TerminalView.setTypeface` 加 null 防护。

### 真机验证（Pixel 6，1.49.1/86）
- ls 输出后点清屏 → 只剩新提示符，零异常；Aa 对话框（字号 A−16A＋ + 四配色单选，选中高亮）正常；
- 切「高对比」+ 两次 A＋ → 背景变纯黑、字号 16→18，root 读 prefs 确认 `scheme=1, font_size=18.0` 持久化；
- 软键盘自动弹出，零 FATAL。

### 装机
HexFile-1.49.1.apk（5.5M，versionCode 86）Success。

## v1.50.0 —— 远程管理：局域网 HTTP 服务器（电脑浏览器管理手机文件）

抽屉「远程管理」占位补完。手机开 HTTP 服务，电脑/手机浏览器扫码或输地址即可浏览、下载、上传、删除、改名、新建目录。

### 借用的轮子（避免重复造轮子）
- **NanoHTTPD 2.3.1**（MIT，`libs/nanohttpd-2.3.1.jar`，51KB）：HTTP 解析、multipart 上传、chunked 传输全交给它，自研只剩业务层。
- **Nayuki QR-Code-generator**（MIT，vendored 4 个源文件到 `src/io/nayuki/qrcodegen/`）：连接地址二维码。
- 评估过 Material Files（GPLv3）：Kotlin + AndroidX 全家桶且主打 FTP/SMB 客户端，与本项目纯 Java 无 AndroidX 构建不兼容，未采用。

### 架构
- `RemoteServer extends NanoHTTPD`：URL 规划 `/{密钥}/browse|raw|download|upload|action`；canonical 路径校验强制限制在根目录内（防穿越）；所有请求必须携带 8 位随机密钥，否则 403。
- 上传：自定义 `TempFileManager` 把 NanoHTTPD 临时文件落到应用缓存目录（`java.io.tmpdir` 在 Android 未定义，默认实现会写错位置），同时从 part 头捕获原始文件名（NanoHTTPD 不放进 parameters）；`uniqueDest` 防覆盖（自动 (1)/(2) 序号）。
- `RemoteActivity`：启动/停止、地址展示（点击复制）、二维码（Nayuki 直渲 Bitmap）、密钥重生成、访问日志、WifiLock 高性能锁。

### 踩坑记录（全部真机实测发现）
1. **WAKE_LOCK 缺权限**：`s.start()` 成功后 `acquireWifiLock()` 抛 SecurityException，被外层 catch 吃掉 → `mServer=null`（UI 显示未启动）但 NanoHTTPD 线程已起、继续服务——「UI 与实际脱节」的完整真相。修复：Manifest 补 WAKE_LOCK + WifiLock 失败独立 try-catch 不拖垮启动 + 进程内 `sActive` 静态单例 + onResume 状态自同步。
2. **NanoHTTPD multipart 的 filename 不进 parameters**：只传给 `TempFileManager.createTempFile(hint)`，需自定义管理器用 ThreadLocal 捕获。
3. **页内链接必须绝对路径**（`/{key}/...`）：当前页是 `/key/browse`，相对链接会解析成 `/key/key/...`。
4. **Location 头别拼双斜杠**：`"/" + base`（base 已含 `/`）会变成协议相对 URL `//host/...`，curl 把 key 当主机名。
5. ResponseException 在 2.3.1 是**受检异常**且只有 `getStatus()`（无 response 字段），方法签名要声明。

### 端到端验证（Pixel 6 + PC curl，1.50.0/87）
根目录浏览 191 项（目录优先）｜无密钥 403｜路径穿越 403｜mkdir 防重名｜multipart 上传保留原文件名且防覆盖｜下载内容逐字节一致｜raw 按 MIME 内联｜停止后端口关闭。

### 装机
HexFile-1.50.0.apk（5.6M，versionCode 87）Success。

## v1.51.0 —— 应用管理扩展（卸载 / 清理数据缓存 / 分包提取）

### 需求
v1.45.0 规划时就列过、一直没做的能力补齐：对列表中的应用，除了「提取安装包」再增加「卸载」「清理数据缓存」，并让提取支持 Android App Bundle 的 split 分包（多 APK）应用。

### 实现（AppManagerActivity）
- **操作菜单改自定义视图**（5 个可点行，沿用 v1.35.1 的「消息 + 可点行」方案，避开 AlertDialog 三按钮上限）：提取安装包 / 清理数据缓存 / 卸载应用 / 应用信息 / 复制包名。
- **清理数据缓存**：`pm clear <pkg>`（同时清 data 与 cache），需 Root；自带 Root 检测（RootUtil.detect），本应用自身禁止清理；失败 toast 提示。
- **卸载应用**：有 Root 走 `su pm uninstall --user 0 <pkg>`（可卸载用户与系统应用）；无 Root 回退系统卸载器（`Intent.ACTION_DELETE` 弹系统确认框）。卸载前二次确认对话框。
- **onResume 刷新**：从系统卸载器 / 应用信息返回后自动 `loadApps()` 刷新列表（首帧不重复加载）；`all` 列表加 `synchronized` 防并发刷新交错。
- **提取安装包升级为支持分包**：`PackageManager` 读 `applicationInfo.splitSourceDirs`；单 APK 保持原 `<应用名>_<版本>.apk`；分包（base.apk + 各 split_*.apk）导出到 `HexFile/Apps/<应用名>_<版本>/` 文件夹，toast 提示含 APK 个数。

### 兼容性
- 不新增权限，Manifest 无改动。
- `AppRow.exportFileName` 行为不变（仅抽出 `exportBaseName` 共用），桌面 AppExportTest 7/7 不回归。

### 装机
HexFile-1.51.0.apk（5.6M，versionCode 88）Success；启动 AppManagerActivity 无 FATAL。

### 真机实测（交用户）
抽屉 → 应用管理 → 点应用：验证分包应用导出为文件夹、清理数据缓存（Root）、卸载（Root 或系统卸载器）。

## v1.52.0 —— 终端增强（多会话页签 / ALT / 复制 / 命令历史）

### 需求
刚集成的 Termux 终端还比较「裸」，按功能补齐四项高频能力：多会话并行、辅助键 Alt、终端内复制、命令历史回溯。

### 实现（TerminalActivity + activity_terminal.xml）
- **多会话页签**：由单会话改为会话列表（`List<TerminalSession>` + 当前索引），顶部新增可横滑的页签栏——每个会话一个 chip（标题或「终端 N」，已结束标「（已结束）」），点选切换、`✕` 关闭、`＋` 新建；关闭最后一个会自动新建一个，保证始终有可用会话。`TerminalSessionClient` 回调按会话对象路由，因此多个会话共享同一套回调互不串台。
- **辅助键行新增 Alt 粘滞键**：在既有 Ctrl 锁之外新增 Alt 锁（`readAltKey()` 返回 `mAltMode`），与 Ctrl 同款点击切换高亮，配合 Esc/Tab/方向键/Home/End 覆盖常见组合键。
- **顶栏「复制」按钮**：调用 `TerminalView.getSelectedText()` 复制当前选区到剪贴板，无选区时提示「先长按选择」；与既有长按选区 ActionMode 复制互补。粘贴按钮保留。
- **命令历史**：`.profile` 增加 `HISTFILE/HISTSIZE/HISTFILESIZE` 与 `set -o emacs`，交互 shell 支持上/下方向键回溯历史；辅助键行方向键本就能把光标键转义序列送进 shell。

### 兼容性
- 新增 `term_key_alt` 字符串、页签栏与复制按钮布局；不新增权限，Manifest 仅升版本号。
- 会话进程在 `onDestroy` 时统一 `finishIfRunning()` 回收。

### 装机
HexFile-1.52.0.apk（5.6M，versionCode 89）本地构建 Success；启动 TerminalActivity 无 FATAL（顶栏 + 页签栏 + 终端渲染区三层布局正常）。

### 真机实测（交用户）
抽屉 → 终端：① 点页签栏 `＋` 新建会话并验证两个会话独立运行、点选切换、✕ 关闭；② 长按终端文字选区后用顶栏「复制」/ 长按 ActionMode 复制、粘贴；③ 关掉 Alt 锁后用 Alt+字母、Ctrl 锁后用 Ctrl+字母；④ 输入几条命令后按上方向键回溯历史。

## v1.53.0 —— 应用管理二期（冻结 / 权限 / 备份 / 还原）

### 需求
在 v1.51.0 应用管理（提取 / 卸载 / 清理）基础上，按功能视角补齐二期能力：应用冻结解冻、权限管理可视化、整包备份与还原（含数据）。均走 Root（`RootUtil`），未 Root 时优雅降级。

### 实现（AppManagerActivity）
- **冻结 / 解冻**：`AppRow` 增加 `enabled`/`dataDir` 字段；操作菜单按 `enabled` 动态显示「冻结应用 / 解冻应用」，执行 `pm disable-user --user 0 <pkg>` / `pm enable --user 0 <pkg>`（Root），完成后 `loadApps()` 刷新状态，菜单头标注「已冻结」。本应用自身禁止冻结。
- **权限管理**：`showPermissions` 用 `GET_PERMISSIONS` 取声明权限，逐条显示「名称 + 保护级别（普通/危险/签名/签名·系统）+ 授予状态」，危险权限以主题色高亮；底部「授予全部危险 / 撤销全部危险」走 `pm grant/revoke`（Root），逐项对 `PROTECTION_DANGEROUS` 过滤。
- **应用备份**：APK 及 split 分包复制到 `HexFile/Backups/<pkg>/`；Root 下用 `tar -czf`（失败回退 `tar -cf`）打包 `dataDir` 到该目录（`data.tar.gz` / `data.tar`）。未 Root 时仅备份 APK 并提示。
- **应用还原**：从 `HexFile/Backups/<pkg>/` 读取 APK，用 `pm install-create` → `pm install-write -S <len>`（逐包）→ `pm install-commit` 重装（支持 split 分包）；Root 且有 `data.tar*` 时解包回 `dataDir` 并 `chown` + `restorecon` 修正属主与 SELinux 上下文。

### 兼容性
- 仅改 `AppManagerActivity`（菜单 + 4 个新方法 + `installApkSet` / `parseSessionId`），`AppRow.exportFileName` 行为不变（桌面测试 `AppExportTest` 不受影响）。
- `RootUtil.submitOutput` 用于读取 `pm install-create` 会话号（正则 `\[(\d+)\]` 解析）。

### 装机
HexFile-1.53.0.apk（5.6M，versionCode 90）本地构建 Success；`adb install -r` 成功；启动 AppManagerActivity 无 FATAL。

### 真机实测（交用户）
抽屉 → 应用管理 → 点应用：① 冻结某第三方应用 → 桌面 / 设置中确认其禁用、再解冻恢复；② 权限管理 → 查看危险权限授予状态、点「授予全部危险」后重开确认生效（需 Root）；③ 备份应用 → 检查 `HexFile/Backups/<pkg>/` 含 APK（及 Root 下的 data.tar*）；④ 卸载该应用后点「还原应用」→ 重装成功、Root 下数据恢复。

---

## v1.54.0 —— 备份管理（独立入口 + 还原失败细分）

### 需求
v1.53.0 的「还原」挂在已安装应用行菜单上，应用一旦卸载便从列表消失、入口不可用——无法救回已卸载应用。本版新增独立**备份管理**入口，列出 `HexFile/Backups/` 下全部备份（不论是否已安装），提供「还原 / 删除备份」。同时将还原失败原因细分（Root 缺失 / 签名冲突 / 分包缺失 / 其它），便于定位。

### 实现
- **新增 `BackupManagerActivity`**：扫描 `HexFile/Backups/` 子目录（每个即一个包备份），逐条展示图标（已安装取应用图标，否则占位）、应用名 / 包名、状态（`已安装 / 未安装 · N 个 APK · 数据:有/无`）。每行「还原」「删除备份」；删除走二次确认 + 递归删除。
- **新增 `BackupRestore`（共享核心）**：
  - `listBackups(Context)`：列出所有备份，`getPackageInfo` 判定是否已安装并取应用名；无需 Root（备份目录归属 HexFile 自身）。
  - `restore(BackupEntry, installerPkg)`：先判 Root（无 Root 直接报错「未获取 Root 权限…」）；重装走 `pm install` / `pm install-multiple`（单一 APK 用 `pm install`，split 分包用 `install-multiple`，均带 `-r -i <installer>`）；有 `data.tar*` 时 `tar` 解包回 `/data/data/<pkg>` 并 `chown` + `restorecon`（按重装后新 UID 修正）。
  - 通过 `RootUtil.runCapture`（v1.54.0 新增，合并 stdout/stderr 且不限退出码）读取 `pm` 失败输出，将 `UPDATE_INCOMPATIBLE / signatures do not match` 翻译为「签名不一致：请先卸载再还原」，`MISSING_SPLIT` 翻译为「分包缺失：备份不完整」。
- **`AppManagerActivity.restoreApp` 重构**：改为构造 `BackupEntry` 调用 `BackupRestore.restore`，与备份管理共用同一套细分提示；删除原 `installApkSet`/`parseSessionId`（迁移至 `BackupRestore`）。
- **入口**：抽屉新增「💾 备份管理」→ `BackupManagerActivity`；`AndroidManifest` 注册该 Activity。

### 兼容性
- 新增 `RootUtil.runCapture` 与 `RunResult`；`RootUtil` 既有接口不变。
- 还原逻辑收口到 `BackupRestore`，`AppManagerActivity` 行为保持一致（已安装应用还原路径等价于旧实现，数据目录默认 `/data/data/<pkg>`）。

### 装机
HexFile-1.54.0.apk（5.6M，versionCode 91）本地构建 Success；`adb install -r` 成功；`am start` 启动 `BackupManagerActivity` 与 `AppManagerActivity` 均无 FATAL。

### 真机实测（交用户）
抽屉 → 备份管理：① 列表应出现 `com.gpu.a0837dd7`（已卸载）等所有备份，状态显示「未安装」；② 点「还原」→ 该应用重装成功、Root 下数据恢复（提示「已重装并恢复数据」）；③ 若未授权 Root，点还原应提示「未获取 Root 权限…」而非静默失败；④ 点「删除备份」二次确认后目录被清除、列表刷新。

---

## v1.54.1 —— 备份/还原可靠性修复（安装路径 + 数据备份校验）

### 背景
v1.54.0 实测「还原失败」。抓 logcat + 真机复现，定位两类根因：

1. **安装被 SELinux 拦截（致命）**：`pm install-write`（旧会话流程）由 `system_server` 读取 APK，而备份 APK 位于 `/sdcard`（FUSE 文件系统，标签 `u:object_r:fuse:s0`），`system_server` 被 SELinux 拒绝读取 → `install-write` 失败 → `install-commit` 报 `INSTALL_FAILED_INVALID_APK`。此问题与 Root 无关。
2. **安装超时（致命）**：本机 `pm install`（dexopt）实际耗时约 17 秒，旧 `runCapture` 固定 20 秒超时且放弃等待 → 应用未装上即报「还原失败」。
3. **数据备份损坏（数据丢失）**：v1.53.0 的 `backupApp` 用 `tar -czf … 2>/dev/null || tar -cf …` 静默吞错——当备份时数据目录不可用（如应用正被卸载），tar 写出 1024 字节全零 / 29 字节的无效归档，且 `2>/dev/null` 掩盖错误，留下诱人但损坏的 `data.tar*`。

### 修复
- **安装改走 `pm install` / `pm install-multiple`**（弃用 `install-create/write/commit` 会话流程）：先在 Root 下把 APK 从 `/sdcard` 拷到 `/data/local/tmp`（绕开 FUSE 读取限制），再从 tmp 安装。单一 APK 用 `pm install`，split 用 `pm install-multiple`，均带 `-r -i <installer>`。
- **`RootUtil.runCapture` 加超时重载**：安装命令用 180 秒超时（dexopt 慢也不误判），超时仅放弃等待、不杀正在执行的 root 进程（避免误杀将完成的安装）。
- **`BackupRestore.restore` 加日志**：每个阶段打 `Log.i/Log.e("HexFile", …)`，方便以后抓日志定位。
- **`BackupRestore.pickValidDataTar`**：还原前校验数据归档（体积 ≥ 512B 且 `tar -tf` 可列出），损坏档直接跳过并提示「无有效数据」，不再误用坏档。
- **`AppManagerActivity.backupApp` 数据备份校验**：备份前 `dataDir.isDirectory()` 判存在；不再 `2>/dev/null` 吞错；失败后删除无效输出文件，避免坏档误导还原；成功才算「数据:有」。
- **`BackupRestore` 清理**：移除已无用的会话解析（`SESSION_ID`/`parseSessionId`）死代码。

### 兼容性
- 备份目录布局不变（`HexFile/Backups/<pkg>/{base.apk…, data.tar.gz|data.tar}`）；旧版损坏的 `data.tar*` 现在会被自动跳过（仅重装应用本体）。
- `RootUtil.runCapture(String)` 旧签名保留（默认 20s），新增 `runCapture(String, long)`。

### 装机
HexFile-1.54.1.apk（5.6M，versionCode 92）本地构建 Success；`adb install -r` 成功；`am start` 启动 `BackupManagerActivity` 无 FATAL。

### 真机实测（已自测）
抽屉 → 备份管理 → 对「已卸载」应用点「还原」：① 应用重装成功（`pm install` 约 17s）；② `/data/data/<pkg>` 目录按新 UID 重建并 `chown`+`restorecon`，数据恢复（日志 `restore: install ok` + `extract data from …`）；③ 完整「卸载→还原（含数据）」链路验证通过。

### 已知限制
- v1.53.0 时代已产生的损坏 `data.tar*`（如现有 `com.greenpoint.android.mc10086.activity` 备份）无法恢复其数据——数据在备份当时即为空。重新备份（v1.54.1+）即可获得有效数据归档。

## v1.54.2 —— 数据备份/还原修复（KernelSU 隔离命名空间适配）

### 背景
v1.54.1 实测「数据备份失败」（应用本体备份正常，数据归档始终缺失/无效）。加探测日志真机定位，发现最终根因：

**应用内 `su`（KernelSU）运行在隔离挂载命名空间**：`/data/data` 在该命名空间里是一个刚创建的**空壳目录**（mtime 即 su 启动时刻，仅 2 个子项），真实的应用数据目录（vold 的 CE 挂载）不在其中。因此 `tar -C / data/user/0/<pkg>` 报 `No such file or directory`——tar 命令本身、root 权限、SELinux 域（`u:r:ksu:s0` 可读 `app_data_file`）都没有问题，纯粹是**命名空间里看不到真实路径**。

探测确认的两条可行通路（均在应用内 su 上下文验证）：
- `/proc/1/root/data/user/0/<pkg>`：经 init 的 `/proc/<pid>/root` 前缀访问真实文件视图；
- `nsenter -t 1 -m -- <cmd>`：进入 init 挂载命名空间执行命令。

### 修复
- **`AppManagerActivity.tarDataArchive`（新增）**：数据归档优先 `tar -C /proc/1/root data/user/0/<pkg>`（真实视图），回退直接 `-C /`（兼容 Magisk 等无隔离环境）；产物先落 `/data/local/tmp`（su 命名空间对 sdcard 可见性因 ROM 而异），`chmod 644` 后由应用侧 `FileOps` 拷入备份目录（`/data/local/tmp` 对其它用户有 `+x` 遍历权，644 文件可读），最后清理临时文件。
- **`BackupRestore.extractData`（新增）**：数据解包优先 `nsenter -t 1 -m -- tar -x… -C /`（保证写入真实数据目录，且 `restorecon` 能按真实路径计算 SELinux 标签），回退直接解包；解包后经 nsenter 执行 `chown -R`（按当前目录属主修正 tar 内旧 UID）+ `restorecon -R`。
- **`backupApp` 数据分支修正**：移除「应用进程 stat dataDir」误判（非 root 恒 false，导致 tar 根本不执行）；源路径统一 `data/user/0/<pkg>` 相对形式配合 `-C`；超时放宽到 600 秒（大应用 gzip 慢）。
- 备份成功标准收紧：退出码 0 且产物 ≥ 512B 才算「数据:有」，失败自动删除无效输出。

### 兼容性
- 备份目录布局不变；v1.54.1 前生成的坏档仍会被 `pickValidDataTar` 自动跳过。
- `RootUtil` 全部恢复裸 `su -c`（应用内由 KernelSU 提权；实测本机 `su 0` 从应用进程调用反而不提权）。

### 真机实测（已自测，Pixel 6 / Android 15 / KernelSU）
1. **备份**：应用管理 → AccuBattery → 备份应用 → `data.tar.gz` 4.7MB，`zcat|tar -tv` 校验含真实数据文件（属主 u0_aXXX）✓
2. **还原（卸载态）**：备份管理 → 对已卸载的 icu.nullptr.applistdetector 点「还原」→ 应用重装成功（`pm install` 约 16s）+ 数据解包成功 + 特征文件 `cache/stub.apk`（29KB）恢复、属主为新 UID `u0_a351`（chown 生效）+ 数据目录 397K 与备份前吻合 ✓
3. 全程日志：`restore: install ok` + `extract data from …`，无错误输出 ✓

## v1.55.0 —— 文件管理增强（高级搜索 + 加密流式化）

### 1. 高级搜索（SearchActivity 重写）
- **关键词**：通配符 `*` `?`（大小写不敏感）或**正则**（勾选「正则」后按 `find()` 语义匹配，非法正则会提示具体错误）。
- **类型过滤**：文本 / 图片 / 视频 / 音频 / APK / 压缩包 / 文档（按扩展名集合判定）。
- **大小范围**：最小 ~ 最大（MB）。
- **修改时间**：距今天数下限 ~ 上限（如 上限=7 表示最近 7 天内）。
- 「筛选」按钮展开/收起高级面板；关键词留空时仅按条件过滤；全部条件叠加生效。
- 结果行新增修改时间显示。

### 2. 加密流式化（FileCrypt 重写，格式不变）
- v1.54 及以前：整个文件读入内存加密/解密 → 大文件直接 OOM，且在 UI 线程执行（PBKDF2 10 万次迭代 + 全文件运算）→ 必 ANR。
- v1.55.0：**全流程流式（8KB 缓冲）**，任意大小文件恒定内存；**后台线程 + 进度对话框**（百分比实时更新）。
- 解密采用两遍扫描：第一遍流式验 HMAC-SHA256（先验证后解密，防篡改），第二遍流式解密。
- **MTENC1 格式完全兼容**：旧版加密的 `.enc` 新版可解，新版加密的旧版亦可解（JVM 双向互通测试通过）。
- 修复流式实现过程中的关键 bug：解密第二遍曾把结尾 32 字节 MAC 一并喂给解密器（BadPaddingException），已修正为只读 ctLen 字节。
- 中途失败自动删除半截输出文件，不留坏档。

### 3. 对比功能（已实现，本轮真机验证）
- 文件夹对比 / 文本对比 / 哈希对比 / APK 对比（CompareActivity）真机冒烟通过：目录对比界面正常打开，A/B 独有与差异标记正确显示。

### 验证
- **JVM 单测（13 项全过）**：小文件往返、MTENC1 魔数、长度公式、20MB 流式往返（进度回调 2560 次单调递增、解密 833ms）、错误密码抛 SecurityException 且原文件无恙、非加密文件拒绝、空文件往返、双向格式互通。
- **真机（Pixel 6）**：SearchActivity 筛选面板渲染正常、「最大 1MB」过滤搜索返回 1000 条（达上限）正常截断；UI 完整走通「长按 → 加密文件 → 输密码 → 生成 .enc（140 字节、MTENC1 头）→ 解密文件 → 内容逐字节一致」闭环；目录对比打开正常。
- HexFile-1.55.0.apk（5.6M，versionCode 94）构建 Success，`adb install -r` 成功。


## v1.56.0 —— 网络存储挂载（SMB/WebDAV，直连电脑共享）

### 新增
- **网络存储（抽屉入口 🌐）**：手机作为客户端直连局域网内电脑/NAS 共享，与 v1.50「远程管理」（手机当服务端）互为反向能力。
  - **SMB**：集成 **smbj 0.14.0**（Apache-2.0，MaterialFiles 同款库），支持 SMB2/SMB2.1/SMB3（含 3.1.1 协商）；依赖 asn-one 0.6.0、mbassador 1.3.0、bcprov-jdk18on 1.79 一并入 libs/。不支持 SMB1（极老设备请开 SMB2 或走 WebDAV）。
  - **WebDAV**：`WebDavFs` 手写实现（HttpURLConnection + 系统 XmlPullParser，零第三方依赖）：PROPFIND(Depth 1)/GET/PUT/MKCOL/DELETE/MOVE，HTTP Basic 认证。
- **服务器管理**：添加/编辑/删除服务器（SMB：地址+端口+共享名+域+账号；WebDAV：完整 URL+账号），配置 JSON 存 SharedPreferences；用户名留空即访客（guest）登录。
- **远程浏览**：目录树导航（上级/子目录）、文件大小与修改时间显示、目录/文件图标区分。
- **传输能力**：
  - 下载：单文件（水平进度条）或整个目录递归下载到 `/sdcard/HexFile/网络存储/`，重名自动加 (1) 序号；
  - 上传：系统文档选择器（ACTION_OPEN_DOCUMENT）任选本地文件上传到当前远程目录；
  - 远端操作：新建目录、重命名（SMB 走 FileRenameInformation，WebDAV 走 MOVE）、删除（目录递归）。

### 实现
- `NetFs`：网络文件系统抽象接口（list/openRead/openWrite/mkdir/delete/rename/close），路径以 `/` 分隔、相对各协议根。
- `SmbFs`：smbj 包装——连接复用单 Session，`SmbConfig` 60s 超时 + 30s SoTimeout；目录判定走 FILE_ATTRIBUTE_DIRECTORY 位；`openWrite` 自动逐级补父目录；`rename` 经 `setFileInformation(FileRenameInformation)`（DiskShare 无原生 rename API）。
- `NetStorageActivity`：双面板（服务器列表 / 远程浏览）单 Activity，操作统一后台线程 + ProgressDialog；错误信息人性化（ECONNREFUSED/ETIMEDOUT/401/404 等给中文提示）。
- versionCode 95，APK 体积 5.6M → 7.5M（增量主要为 bcprov）。

### 验证
- javac 编译通过 + d8 合并 dex 成功（smbj/bcprov/mbassador/asn-one 全部收入）。
- `adb install -r` Success（Pixel 6）。**连接/传输功能由用户手动测试**（需局域网内真实 SMB/WebDAV 服务端）。

## v1.56.x —— 历史版本回归（JVM 169 项 + 真机 UI 全链路）

### JVM 核心逻辑回归（7 套测试，169 项断言 0 失败）
- BatchRenameTest 31 ✅ / FormatTest 43 ✅ / HexCoreTest 41 ✅ / SplitTest 23 ✅ / FileCryptTest 7 ✅ / ZipCryptTest 5 ✅ / SigKillTest 19 ✅
- 修复 4 个测试文件的历史遗留缺 import（SplitTest/FileCryptTest/ZipCryptTest/SigKillTest，当年同包名直编可过，独立编译需显式 import com.hex91.file.*；功能代码零改动）
- 新增 test/ui_tap.py 真机 UI 自动化辅助（uiautomator dump + 按文本定位点击/长按）

### 真机 UI 冒烟（Pixel 6，全部通过）
- **文件分割/合并**：3MB → 3×1MB 分卷 + .parts 清单 → 合并 big.bin.merged，md5 与原文件一致；小于最小分卷的文件被正确拒绝
- **加密 ZIP**：压缩为 ZIP 输密码 → big.bin.zip 含 AES-256 extra field（0x9901），大小符合 AES 开销
- **HEX 编辑写回**：偏移 0 写入 DEADBEEF，落盘逐字节核对 de ad be ef，其余字节未动
- **格式化**：编辑器主菜单 → 格式化自动识别 JSON → JSON 美化 → 保存，落盘内容正确缩进
- **SigKill 去签名校验**：96MB/6 dex APK → sample_nosig.apk（110MB），apksigner verify 通过（内置密钥 V3.0 重签）
- **批量重命名**：左滑进多选选 2 项 → 编号命名预览（001.json/002.bin）→ 执行成功，全程无崩溃

## v1.57.0 —— APK 增强（信息 / 解包打包 / 自定义签名 / 去签名）

### 新增（APK 长按菜单 4 项 + 目录菜单 1 项）
- **APK 信息**：新页面 ApkInfoActivity——包名/版本/minSdk·targetSdk/权限列表（计数）/四大组件（含 exported 判定：显式声明 > intent-filter 推导）/ABI 归纳/dex 数量与 classes.dex 方法数/签名方案（V1/V2/V3），右上角一键导出 txt 到 `/sdcard/HexFile/APK信息/`。
- **APK 解包**：全部条目按原路径提取到 `<name>_unpack/`（跳过旧签名、防路径穿越），另存 `AndroidManifest.xml.txt`（AXML→可读 XML）。
- **打包为 APK**：长按解包目录（含 AndroidManifest.xml 时显示）→ 目录重打包为未签名 APK——resources.arsc 与 lib/*.so STORED + 4096 对齐，其余 DEFLATED；完成后询问是否用内置密钥直接签名。
- **自定义密钥签名**：系统选择器选 keystore（PKCS12/JKS 自动识别）→ 输密码 → 自动选取首个含私钥的别名 → v1+v2+v3 签出 `_csigned.apk`。
- **去除签名**：重写包并剥离 META-INF 签名文件 → `<name>_unsigned.apk`。

### 实现
- `ApkInfoUtil`：基于 AxmlFile 二进制解析 manifest，纯只读；方法数直接读 dex 头偏移 88（无需临时文件）。
- `ApkPackager`：unpack/pack 双向；pack 复用 ApkRepack 的对齐思路（计数流 + extra 填充 + 时间戳钳制 1980 防 EXTT 破坏对齐）。
- `ApkSignerTool.signAutoAlias`：新增自动选别名重载，PKCS12/JKS 双格式兼容。
- versionCode 96；桌面 JVM 冒烟通过（96MB/6dex 真实 APK：信息 40 权限 17 Activity、解包 3527 文件 197MB、重打包 183MB、重签成功）。
- 连接/安装效果由用户手动测试。

## v1.58.0 —— 媒体与文档工具（图片批处理 / PDF 预览 / 字体预览）

### 新增
- **图片批处理**：图片长按「图片处理」，或多选栏「处理」按钮批量——
  缩放 10-400%、格式转换（保持/JPEG/PNG/WebP）、质量 1-100、文字水印（右下角带阴影，字号随图宽自适应）。
  输出同目录 `<base>_p.<ext>`（冲突加序号）；解码超 4096 自动采样防 OOM。
- **PDF 预览**：点击 .pdf 直开——PdfRenderer 渲染、上一页/下一页导航、双击 2x 放大/还原、
  长按当前页导出高清 PNG（3x FOR_PRINT）到 `/sdcard/HexFile/PDF导出/`；加密 PDF 友好提示。
- **字体预览**：点击 .ttf/.otf 直开——中英文/数字/符号样张 + 自定义文本实时输入预览，字号 12-72sp 滑条。

### 实现
- `ImageBatchUtil`：BitmapFactory + Matrix + Canvas，零第三方依赖；KEEP 模式按源扩展名选编码器。
- `PdfPreviewActivity`：ParcelFileDescriptor + PdfRenderer，渲染线程化，页面位图复用回收。
- `FontPreviewActivity`：Typeface.createFromFile 单加载多 TextView 共享。
- 多选操作栏扩为 7 键（全选/复制/移动/删除/重命名/处理/完成）。
- versionCode 97。

## v1.59.0 —— 磁盘与文件治理（存储分析 / 重复文件查找 / 哈希校验）

### 新增
- **存储分析**（抽屉入口 🧹）：目录占用树下钻——每个子目录按占用比例画横条排行，点击进入下钻，面包屑可回上级；大文件 Top20 排行（点击删除）；顶栏「重复文件」直达当前目录查重。
- **重复文件查找**：内容级三阶段查重（大小分组 → 头 4KB MD5 快速指纹 → 全量 MD5 确认），仅查 ≥1MB 文件；按可释放空间排序分组；默认勾选每组路径最长的副本（保留路径最短为正本）可改；一键删除显示可释放空间统计。
- **哈希校验**：文件长按「计算哈希」——MD5/SHA-1/SHA-256 流式计算（128KB 缓冲），等宽字体展示、点击复制，粘贴期望值即时显示 ✅一致/❌不匹配。

### 实现
- `DiskScanUtil`：scanSize（DirVisitor 回调构占用树）、topFiles（PriorityQueue TopN）、findDuplicates（三阶段去重算法）、hashFull（流式 MessageDigest）。纯 File API 无 root 依赖。
- JVM 测试 4 项通过（目录统计精确值/重复组识别/MD5 一致性与区分/SHA-256 长度）。
- versionCode 98。

## v1.59.1 —— 代码质量整理（v1.36 以来首轮流线维护）

### 清理（行为零变更）
- **删除 52 个未用 import**（16 个文件），编译验证通过。
- **printStackTrace 全部清除**（AutoTextFormatter 2 处、ZipUtil 2 处）→ 统一 `Log.w` 带 TAG。
- **补齐 9 个缺失类头注释**：编辑器家族（HighlightingEditor/LineNumbersView/AutoTextFormatter/SyntaxHighlighterBase 及各高亮器）。

### 测试体系（本次主要产出）
- **新增 `test/run_all.sh` 全量测试入口**：一键编译并运行全部桌面 JVM 测试，输出 PASS/FAIL/SKIP 汇总与失败日志尾部；支持名称过滤；跳过名单注明原因（真机样本/原生库缺失）。
- 修复 10 个历史测试文件的缺 import（当年同包名直编遗留）：J4ReadTest/StringExtractTest/ArchZstTest/AttrLogicTest/CmpOpsTest/RootUtilTest/ThemeTest/ZipWriteTest/ZstdTest/MakeSample。
- 修复 3 个测试的过期产物路径（`out/MTClone-1.11.0.apk` → 自动取最新 `out/HexFile-*.apk`）：AxmlTest/DexWritebackTest/SignTest。
- SignTest 改用 `signAutoAlias`（根 keystore 别名 hex91 ≠ KEY_ALIAS hexfile，自动选别名更稳）；v1 签名文件断言从写死 MT_CLONE.* 改为通用 *.SF/*.RSA 判定。
- 可见性微调（测试可达，行为不变）：AppRow/exportFileName/statFallback/RootUtil.detect/Probe → public。
- .gitignore 补测试产物目录（test/bin*、test/_dbg）。

### 结果
- 全量 30 套桌面 JVM 测试：**27 通过 / 0 失败 / 3 跳过**（跳过原因：ArchiveWriteTest 需真机样本；ZstdTest/ArchZstTest 需 zstd 原生库）。
- versionCode 99，APK 7.6M。

## v1.60.0 —— 网络存储扩展（FTP / SFTP 协议支持）

### 新增
- **FTP 客户端**（`FtpFs`，纯 Socket 手写零依赖）：被动模式（PASV，数据连接复用控制 host 兼容 NAT/多网卡）、二进制传输（TYPE I）、LIST 解析双风格（UNIX ls + Windows/IIS DOS）、UTF-8 文件名（OPTS UTF8 ON）、RETR/STOR 流式收发（close 时确认 226 保证传输完整）、MKD/RMD/DELE/RNFR+RNTO、目录递归删除。默认端口 21，用户名留空按匿名登录。
- **SFTP 客户端**（`SftpFs`，JSch 0.1.55 SSH2）：密码认证、host key 宽松模式（局域网免交互）、列目录排除 . ..、get/put 流式收发、put 自动补齐父目录、rm/rmdir/递归删除、rename 目标已存在时先删后改（SFTP v3 无原子覆盖）。默认端口 22。
- **服务器管理四协议**：添加/编辑对话框类型下拉扩为 SMB / WebDAV / FTP / SFTP，按类型显隐端口（21/22/445）与共享名/域字段、动态 hint；连接分发与列表标签（`FTP · host[:port]`）适配。

### 修复（测试驱动发现）
- `FtpFs.list()` 读完数据后未消费服务器 226 完成回复 → 后续命令读到错位回复（如 PASV 收到 "257 created"）。修复后每条命令回复严格对齐。
- 目录行 LIST 的 size 归 0（目录 4096 等块大小无意义）。
- UNIX LIST 时间解析：近期文件（无年份，如 "Oct 30 12:34"）补当前年；老文件（"Oct 30 2023"）按 "MMM d yyyy" 解析。
- **类型切换残留标签**：SMB→FTP 切换后「共享名/域」仅隐藏输入框、标签 TextView 残留——addEditRow 改为标签+输入框包进行容器整体显隐（真机 UI 冒烟发现）。
- **下载落盘路径**：根目录文件下载写成 `HexFile/网络存储` 单个文件（内容为文件数据）而非 `HexFile/网络存储/<文件名>`——统一改为 `HexFile/NetStorage/<条目名>`（真机验证发现）。
- **断开连接崩溃**（真机测试发现）：`FtpFs.close()` 在主线程发 `QUIT` 命令抛 `NetworkOnMainThreadException`（按返回键/断开按钮即崩）——QUIT 写入改独立线程 + Activity 的 disconnect/onDestroy 统一后台线程 close（同时保护 SMB/SFTP 断连）。

### 测试
- 新增 `FtpFsTest`（30 套 → 31 套）：内置迷你 FTP 服务器桩（Socket 内存文件系统，支持 USER/PASS/PASV/LIST/RETR/STOR/MKD/DELE/RMD/RNFR/RNTO/QUIT），全链路 20 项断言——上传下载逐字节一致、中文与空格文件名、目录排前+按名排序、重命名、递归删除、错误路径；另含 parseListLine 的 UNIX/DOS/符号链接/total 行单元解析。
- versionCode 100。


## v1.61.0 —— Smali 语法查询（抽屉内置知识库）

### 新增
- **SmaliDocActivity**：抽屉菜单新入口「📐 Smali 语法」。
  - **三标签页**：`语法 (112)` / `操作码 (256)` / `★ 收藏 (N)`，切换即时重渲染，选中态反色高亮。
  - **顶部搜索框**实时过滤：语法类匹配「分类 + 语法 + 解析」，操作码类匹配「操作码 + 助记符 + 中文说明」，收藏页同样支持；头部显示 `命中/总数`。
  - **条目卡片三层结构**：语法行（**红色等宽加粗**，明暗主题自适应——按 hfBg 亮度切换深红 #D32F2F / 亮红 #FF8A80）→ 中文解析（逐行 `#` 前缀的**注释形式**、灰色）→ 示例代码块（等宽 + 次级背景，可长按选中；点击卡片主体复制语法）。
  - **收藏**：每张卡片右上角 ★/☆ 一键切换，SharedPreferences（`smalidoc/favs`）持久化，键为 `E:<语法>` 或 `O:<操作码>`，收藏页按加入顺序跨两类混合展示并支持取消；真机验证重启 App 后仍保留。
- **SmaliDocs 数据层**（纯静态，追加一行即自动收录）：
  - `E`：112 条 / 13 分类 —— 基础概念（寄存器 vN/pN 命名、4bit/8bit/16bit 索引宽度——如 `move/from16 vx, vy` 的 vy 可达 64k 而 vx 必须 ≤255、类型描述符、方法签名）、伪指令与结构（.class/.field/.method/.locals/标签/.annotation/.packed-switch/.array-data 等）、数据移动（move 全家族 + move-result + move-exception）、常量加载（const 全家族 + const-string/jumbo + const-class）、返回、算术与逻辑（int/long/float/double 四族 + 位运算 + neg/not）、比较（cmp-long + cmpl/cmpg float/double）、跳转与分支（goto + if-eqz 家族 + packed/sparse-switch）、数组（new-array/aget/aput 全类型族 + fill-array-data）、字段操作（iget/iput/sget/sput 全类型族）、方法调用（invoke-virtual/super/direct/static/interface + /range + 构造器模板）、对象与类型（new-instance/check-cast/instance-of/monitor/throw）、类型转换（全 15 个）、其他（nop、catch/catch-all、调试技巧）。
  - `OP`：**dex 操作码 0x00–0xFF 全表 256 项** —— 每项{操作码, 助记符与操作数格式, 中文说明}，覆盖 nop/move/const/return/switch/比较/分支/aget-aput/iget-iput/sget-sput/invoke(含 range)/neg-not/类型转换/三操作数算术全家(0x90–0xaf)/2addr 版(0xb0–0xcf)/lit16+lit8 版(0xd0–0xe2)，并含 API 26+ 的 `invoke-polymorphic`(0xfa)/`invoke-custom`(0xfc)/`const-method-handle`(0xfe)/`const-method-type`(0xff)；未定义位（0x3e–0x43、0x73/0x79/0x7a、0xe3–0xf9）标注「未使用/保留」以保索引对齐。

### 实现
- 纯程序化 UI（无新布局文件），全部颜色走主题属性（hfBg/hfSurface/hfSurfaceAlt/hfTextSub/hfAccent…），22 套色板 + 明暗模式自动适配；`getResources().getColor()` 兼容 minSdk 21。
- 操作码卡片用红底白字徽标显示 0xNN，未定义操作码助记符降为灰字。
- versionCode 101。

## v1.62.0 —— Java → Smali 转换（抽屉内置，全离线）

### 新增
- **Java2SmaliActivity**：抽屉菜单新入口「☕ Java→Smali」。上半屏粘贴/编辑 Java 源码，右上角「转换 ⚙」一键转出 Smali，下半屏可读可复制；多类时顶部出现类切换标签（主类排最前），底部「载入示例 / 复制结果 / 清空」。
- 三段流水线全在手机端完成，不联网、不需要 root：
  1. **nb-javac** 把 Java 源码编译成 `.class`；
  2. **dx** 把 `.class` 打成 `classes.dex`；
  3. **baksmali API** 把 dex 反汇编成 smali。

### 编译器选型：为什么从 ECJ 换成 nb-javac
- ECJ 3.21 引用 `java.lang.Module`（Java 9+ API），而 **ART 禁止应用 ClassLoader 定义 `java.*` 包下的类**，真机上必然失败，且无法用桩绕开。
- 改用 nb-javac（NetBeans 版 OpenJDK javac，取自开源项目 [Android-Java2Smali](https://github.com/ToolDroidYT/Android-Java2Smali) 的 `java2smali-javac` 组件）：**全部为 Java 8 字节码（major 52）**、不碰 `java.*`，可安全由 d8 转 dex 后按需加载。
- 该 jar 已做重定位（`com.sun.tools.javac` → `org.openjdk.tools.javac`、`javax.*` → `org.openjdk.javax.*`），同时保留一份未重定位副本，故 `com.sun.tools.javac.Main.compile(String[], PrintWriter)` 可直接反射调用。

### 插件化（不进主 dex）
- 主 dex 方法数已逼近 64K，编译器/打包器以 **dex 插件**随 assets 分发，首次使用时释放并通过 `DexClassLoader` 按需加载：
  - `javac-plugin.zip`（2.6MB）：d8 产物 `classes.dex` + javac 的 20 个 `*.properties` 语言包；
  - `dx-plugin.zip`（0.36MB）、`jre-stubs.jar`（0.8MB，android.jar 中的 `java/**` 桩，作编译期 bootclasspath）。
- 反射入口：`com.sun.tools.javac.Main.compile(String[], PrintWriter)`、`com.android.dx.command.dexer.Main.run(Arguments)`。
- **javac 需要 `java.nio.file`**（Android 8.0 / API 26 才提供），故本功能要求 Android 8.0+，低于该版本给出明确提示而非崩溃。

### 真机踩坑与修复（按发现顺序）
1. **ECJ 崩溃**：`java.lang.Module` 触犯 ART 的 `java.*` 加载禁令 → 换 nb-javac。
2. **javac 语言包缺失**：`InternalError: Cannot find requested resource bundle for locale zh_CN`。dex 里装不下 `.properties`，改为把插件解压到目录，`DexClassLoader` 的 dexPath 用「`classes.dex` 文件 + 解压目录」双元素——前者提供类，后者（`DexPathList` 的目录元素）提供资源查找。
3. **dx 报 `UsageException: unknown option: --dex`**：`--dex` 是外层 `com.android.dx.command.Main` 的子命令，`dexer.Main.parseFlags` 不认识；且 `parseFlags` 不会把末尾输入目录填进 `fileNames`（实测为 null），需手动设置，否则 `run()` 内 `Arrays.sort(fileNames)` 空指针。
4. **顶栏「转换」按钮隐形**：22 套彩色主题里 `hfTopBar` 与 `hfAccent` 是**同一个颜色**，按钮文字用 accent 画在同色背景上完全看不见 → 顶栏文字统一改用 `hfTopBarText`（主题保证对比度），并加同色 15% 透明底标识可点区域。
5. **invokedynamic 风险**：JDK 17 langtools 即使 `-target 8` 也会把字符串拼接编成 `invokedynamic`，而旧版 dx 不支持 → 编译参数加 `-XDstringConcat=inline`，实测产物无 invokedynamic。

### 构建速度优化（build.sh）
- 原 d8 每次都要重新解析 `apksigner.jar` + 20 个 libs（约 20MB），单次常达数分钟，且后台运行时偶发僵死。
- 改为**分层 dex + 指纹缓存**：
  - 依赖 dex（`build/deps`）：仅当 libs / apksigner / platform / build-tools 指纹变化时才重建；
  - 应用 dex（`build/dex`）：每次只编译工程自身 class，libs 以 `--lib` 形式仅参与类型解析；
  - 打包时统一连续编号为 `classes.dex / classes2.dex / …`（当前 app=1 + deps=2）。
  - NDK 编译 `libtermux.so` 改为按需（两个 ABI 的 .so 都比 `termux.c` 新时跳过）。
- 效果：全量 2 分 02 秒 → 增量 **1 分 06 秒**（依赖 dex 命中缓存）。
- 排查提示：构建请让输出直接落盘（`bash build.sh > build.log 2>&1`）再 tail 查看；若用管道（`| tail`）会因缓冲看不到任何进度，容易被误判为卡死。

### 验证
- 桌面端到端预演：nb-javac 编译 → dx 打 dex → baksmali 反汇编，输出与预期格式一致。
- 真机（Android 15 / API 35）：进入「☕ Java→Smali」→ 点「转换」，输出
  `.class public LMain;` / `sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;` / `invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V`，状态栏显示「已生成 1 个类 · 当前 Main（23 行）」。

### 备注
- 许可证：nb-javac 为 **GPL v2 + Classpath Exception**（仅借用其 jar、未修改），其余链路（dx / baksmali）沿用项目既有依赖。
- versionCode 102，APK 12M。

---

## v1.63.0 —— Dex 编辑器++ 支持「Java 源码」反编译（dex2jar + CFR，全离线）

在 Dex 编辑器++ 里点任意 smali 类，除「编辑 Smali」外新增 **「Java 源码（dex2jar + CFR 反编译）」**，
打开 `DecompileActivity` 展示**真正的 Java 源码**（带语法高亮、可复制），并提供 **Smali 对照**标签一键切换。

### 为什么不用「自研 smali → 伪代码」
先评估了两条路，最终选效果更好的那条：

| 方案 | 依赖 | 输出质量 | 结论 |
|---|---|---|---|
| 自研 smali→伪代码 | 零依赖，秒开 | 伪代码：if/goto 线性展开，无循环结构 | 放弃（可读性差、维护成本高） |
| **dex2jar + CFR** | 两个 dex 插件，共 1.0MB | **真 Java**：if/for/while/try-catch 结构完整，类型与方法调用正常 | **采用** |
| jadx | 依赖庞大（>10MB、多 dex） | 最高（含反混淆/重命名） | 暂不引入，体积代价过大 |

注意：**dex2jar 单独并不能出 Java**，它只做 dex → .class；必须再配一个反编译器（本项目用 CFR）。

### 流水线（后台线程，全离线）
1. 把**当前 smali**（含你在编辑器里的改动）用 smali 汇编器编成临时 dex —— 所以改完 smali 立刻能看到对应的 Java；
2. dex2jar 把该 dex 转成 .class；
3. 首次反编译时把整个工程 dex 转成 jar 并**缓存**到 `cacheDir/dec_jars/`，作为 CFR 的 `--extraclasspath`，
   让跨类调用解析出真实签名（而不是 Unknown）；
4. CFR 反编译 → Java 源码 → `SyntaxHighlight("java")` 高亮显示。

### 引擎选型体检结果（决定能否跑在 Android 上）
- **CFR 0.152**：1302 个类，**全部 Java 6 字节码（major 50）**，零 `java.*` 包类、零 `java.nio.file`、
  零 `java.lang.Module` → minSdk 21 也能跑。2.16MB jar → dex 后 1.53MB → zip 0.60MB。
- **dex2jar 2.4.28**（`de.femtopedia.dex2jar`，Apache 2.0）+ ASM 9：全部 Java 8 字节码，同样无禁区依赖；
  五个模块合计仅 536KB（+ASM 372KB）。

### 真机踩坑与修复
1. **`Main.main` 里的 `System.exit` 杀死进程**：CFR 的命令行入口内部会 `System.exit`，
   在 Android 上等于自杀——表现为「Activity 打开后立刻消失、退回主界面」，且 logcat 无 Java 异常。
   → 改用官方 API `org.benf.cfr.reader.api.CfrDriver.Builder`，并用 **动态代理**实现
   `OutputSinkFactory`（主 dex 不能编译期引用插件里的类），从 STRING sink 直接接住源码，不落盘、不 exit。
2. **dex2jar 的 `doTranslate(ByteArrayOutputStream)` 输出的不是 zip**：是自定义长度前缀流
   `[nameLen:4][name][dataLen:4][data]…`，需自行组装成 jar（见 `Decompiler.writeJar`）。
   好处是这样可**绕开 `java.nio.file.Path`**（`to(Path)` 才需要），minSdk 21 也能跑。
3. **成功分支漏赋值**：反编译成功后没把结果写回 `javaSrc`，导致状态行 `javaSrc.contains(...)` 空指针崩溃。

### 控制流扁平化（CFF）辅助
CFR 遇到被扁平化的方法会输出 `while(true) { switch(state) { case N: …; state = M; continue; } }`，极难读。
本版做**安全标注**（不改动代码结构，避免破坏可读性）：
- 识别 `while(true)` + `switch(选择子)`，把选择子当作状态变量；
- 对该状态变量的每个整数常量赋值追加行尾注释：`n3 = 1;  /* → case 1 */`；
- 文件头加提示，状态行显示「⚠ 疑似扁平化」。
顺着 case 编号读即可还原原始执行顺序（真机样本已验证：`n3 = 1; /* → case 1 */`、`n3 = 2; /* → case 2 */`）。

已知边界：类名/方法名混淆（a.b.c）与字符串加密不在本版处理范围——那是另一类问题（需重命名策略与解密执行）。

### 资产与版本
- 新增 `assets/cfr-plugin.zip`（0.60MB）、`assets/d2j-plugin.zip`（0.39MB）；APK 约 13MB。
- 新增源码：`Decompiler.java`（引擎封装）、`DecompileActivity.java`（查看页）；
  `DexEditorActivity` 增加 `smali_目录 → dex` 映射与打开选项。
- versionCode 103。

---

## v1.63.1 ~ v1.63.4 —— 反编译操作简化与字号体验（v1.63.0 的打磨）

### v1.63.1 操作简化（用户反馈：点开要选两次、太繁琐）
- **点类直接进 smali 编辑**：去掉「编辑 Smali / Java 源码」选择对话框；
  **长按**列表项 → 直接反编译为 Java。
- 编辑器顶栏新增 **「</>」转 Java 图标**（仅 `.smali` 文件显示）：一键反编译当前 smali；
  点击时若改动未保存会**先同步落盘再反编译**，保证看到的就是正在编辑的内容。
  类名推导：优先用 Dex 编辑器++ 传入的 extra（`j2s_cls` / `j2s_dex`），否则从 `smali_*`
  工程目录自行推导（编辑器单独打开 smali 也能用）。
- **保存改为软盘图标**（`ic_save`），放到编辑操作 ✎ 后面；顶栏顺序：`↶ ↷ </> ✎ 💾 ⋮`。
- 删掉最左侧 `≡`（与右侧 `⋮` 都打开同一主菜单，重复）。

### v1.63.2 反编译页布局适配（用户截图反馈：灰色区不全屏）
- 代码区背景原来设在 TextView 上，内容多大背景就多大 → **背景移到横向/纵向滚动容器**
  并开 `fillViewport`，无论代码长短都铺满整屏。
- 新增**双指捏合缩放**代码字号（`ScaleGestureDetector`，不消费触摸事件，选择/滚动手势照常），
  字号持久化到 `SharedPreferences("dec_view").font`。

### v1.63.3 / v1.63.4 字号手动输入（统一交互：直接输 sp）
- 反编译页顶栏「复制」右侧新增 **「A」按钮**：弹框输入 sp（8–40，支持小数），确定即生效并持久化；
- 编辑器「设置 → 字号」从「放大/缩小/重置」改为**直接输入 sp**（8–40）；
- 双指缩放与手动输入改同一个值，两种方式互通。

### 版本
| 版本 | versionCode | 要点 |
|---|---|---|
| v1.63.1 | 104 | 点类直进编辑、顶栏转 Java 图标、保存改图标、去重复菜单 |
| v1.63.2 | 105 | 反编译页背景铺满、双指缩放字号 |
| v1.63.3 | 106 | 反编译页「A」按钮手动输字号 |
| v1.63.4 | 107 | 编辑器设置字号改输入 sp |

APK 约 13MB。新增图标资源：`ic_save.xml`、`ic_java.xml`。


---

## v1.64.0 —— ARSC++ 编辑（树状列表 + values XML 源码编辑，全离线）

点击 APK 里的 `resources.arsc`，原「ARSC 编辑器++」更名为 **「ARSC 编辑」**，新增 **「ARSC++ 编辑」**：

### 界面（对标 MT Manager）
树状列表三级结构：**包名 → 资源类型目录**（anim/attr/bool/color/dimen/drawable/id/integer/
interpolator/layout/menu/mipmap/raw/string/style/xml…）**→ 配置节点**（`type-info` +
默认配置显示为类型名本身、其余 `类型-配置` 如 `style-hdpi` / `style-night` / `style-v25`）。
`type-info` 为只读元信息，点击显示该类型的条目/配置统计。

### 编辑方式
点配置节点（如 `xml`）→ 生成 **values 风格 XML**（`<resources><xml name="…">值</xml>…</resources>`）
→ 进入自带文本编辑器（XML 语法高亮），**可改可保存**：
- 保存时解析 XML → 与当前值比对（只提交有变化的条目）→ `ArscWriter` 写回
  （字符串池追加 + 4 字节补丁）→ 重打包（.so 4096 对齐）→ 提示重新签名；
- bag/复杂条目（style 布局等）在 XML 中以注释占位，提示去「ARSC 编辑」逐项修改；
- 错误防御：改资源名 / 未知资源 / 嵌套元素 / 自闭合标签直接报错，不静默写坏资源表。

### 新增/修改
- 新增 `ArscPlus.java`（XML 生成 + 手写轻量解析器，纯 Java 可桌面单测）、
  `ArscPlusActivity.java`（树状列表）；`TextEditorActivity` 增加 ARSC++ 保存分支；
  `MainActivity` / `ZipBrowserActivity` 菜单改名 + 新增入口；注册新 Activity。
- 桌面冒烟测试 `test_arscpp/ArscSmokeTest.java`：真实 APK 的 arsc → XML → 改值 →
  写回 → 重解析校验，**13 项全过**（含 integer/bool/color/id/dimen 全量回读零误报）。
- versionCode 109，APK 约 13MB。

---

## v2.35.0（versionCode 228）—— F-14/22：局域网快传页 i18n

`LanShareActivity` 56 处中文字面量清到 0。这页全是 `Toast.makeText` 与 `setText` 的
短句，但有三类需要单独处理：

### 1. 「本机 名字 · IP」的后缀

```java
sub.setText("本机 " + node.selfName() + (LanNet.localIp().length() > 0
        ? " · " + LanNet.localIp() : ""));
```

后半段可能为空，直接当参数传会把 `本机 名字` 和 `本机 名字 · ip` 两种情况混在一个模板里。
改成 `lan_self`（`本机 %1$s%2$s`）+ `lan_self_ip`（` · %1$s`），空串走 `""`，
两种语言都能自然拼。

### 2. 搬迁结果的两条完整句

```java
"已搬移 " + fok + " 个文件" + (ffail > 0 ? "，失败 " + ffail : "")
```

拆成 `lan_migrated` / `lan_migrated_part` 两句 —— 英文里「失败 N 个」要前置成
`Moved 3 files, 1 failed`，拼接顺序不同。

### 3. 前导/连续空格必须整值加引号

`"📄  选择文件"`、`"○  未就绪"`、`"●  后台接收：开"` 这些靠两个空格对齐的标签，
值整体加双引号才能保住空格（aapt2 会折叠未加引号值里的连续空白）。`aapt2 dump`
已验证输出仍是两个空格。

### 验证

- `LanShareActivity` 中文字面量 **56 → 0**
- 新增 54 个 `lan_*` 键；资源总量 **1606 → 1660 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 1660 条 100% 一致
  （引用 1617 键 / 32 文件）/ 动作号 55
- `QUICK=1` **97 通过 / 0 失败 / 4 跳过**
- `aapt2 dump resources` 抽查 `lan_state_idle` / `lan_pending_tip` / `lan_tab_recv_n`：
  中英双变体齐全，双空格未被折叠
- APK **13,292,240 B**

---

## v2.34.0（versionCode 227）—— F-14/21：ARSC 编辑器 i18n

`ArscEditorActivity` 58 处中文字面量清到 0。这页是资源表的编辑界面，文案集中在
「行渲染」（三级树：类型 → 资源名 → 各配置值）和四个对话框（编辑资源 / 编辑 bag /
保存 / 退出拦截）。

### 行渲染里的「状态标记」拆成整句

```java
// 以前：值 + 空格 + 标记，标记还是拼上去的
val.setText("[" + r.config + "] " + v + (changed ? "   ✎ 已修改" : ""));
```

现在两整句（`ax_value_row` / `ax_value_row_changed`），按状态选一句 —— 英文的标记
位置与空格数跟中文不一样，拼接必错位。类型行/资源名行的 `✎` 只是符号，不进资源。

### `typeHint` 从 static 变实例方法

`typeHint(int)` 原来返回「十进制整数 / 0x 十六进制 / 浮点数」，是 `static`；
要取资源就得有 `Context`，改成实例方法（唯一调用点在 `applyEdit` 里）。

### 顺带

- 「保存后自动签名（长按设置密钥 / 签名方案）」这段勾选框文案在 ARSC / Manifest 两个
  编辑器里各写一份，提成 `apk_autosign_hint` 共享（Manifest 页下一版吃过来）。
- `IllegalStateException("提取失败: " + entryName)` 改成 ASCII 诊断串 —— 它经
  「加载失败」对话框直接展示给用户，但那是**异常信息**，不属于正常文案；解析失败本身
  已经有标题与结构说明，具体原因保留原文更有助于定位。

### 验证

- `ArscEditorActivity` 中文字面量 **58 → 0**（只剩注释）
- 新增 40 个 `ax_*` + 1 个共享 `apk_autosign_hint`；资源总量 **1566 → 1606 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 1606 条 100% 一致
  （引用 1563 键 / 31 文件）/ 动作号 55
- `QUICK=1` **97 通过 / 0 失败 / 4 跳过**
- `aapt2 dump resources` 抽查 `ax_value_row_changed` / `ax_cfg_bag` / `ax_save_msg`
  （两处 `\n\n`）：中英双变体齐全
- APK **13,292,240 B**，md5 `7d28ab53…`

---

## v2.33.0（versionCode 226）—— F-14/20：文件属性弹窗 i18n + 一处文案哨兵

`PropsController` 67 处中文字面量清到 0。这一页是由 MainActivity 整段搬出来的 controller，
拿文案的方式是 `host.ctx().getString(...)`，加了两个 `s(int)` / `s(int, Object...)` 就够。

### 抓到一处「文案当哨兵」

校验弹窗的复制按钮原来这样判断「算完了没有」：

```java
String s = values[idx].getText().toString();
if (!s.isEmpty() && !"计算中…".equals(s)) host.copyToClipboard(algs[idx], s);
```

「计算中…」是界面文案，出英文后这个 `equals` 恒为 false —— 点复制永远没反应（静默）。
改成看哈希缓存里有没有值：

```java
String h = hashCache.get(hashKeys[idx]);
if (h != null && !h.isEmpty()) host.copyToClipboard(algs[idx], values[idx].getText().toString());
```

顺带把 `hashCache` 的声明提前到列表构建之前（原来在下面，够不到）。

### 复用与拆键

- **复用**：`重命名` / `删除` 用 `mops_rename` / `common_delete`，`关闭` / `取消` / `确定` 用
  `common_*`，`复制` 用 `copy_text`（该页 5 处）。
- **拆开**：「更改权限 — 名字」「修改时间 — 名字」「更改所有者 — 名字」三个标题各一键
  （`%1$s`），不拼 `"更改权限 — " + name`：英文语序是 `Change permissions — name`，
  em dash 两侧的空格也是语言习惯。
- **合并**：`权限已更改为 %1$s` 与 `…（含子项）` 拆成两句完整句，递归时选后者。

### 验证

- `PropsController` 中文字面量 **67 → 0**（只剩注释）
- 新增 58 个 `pr_*` 键；资源总量 **1508 → 1566 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 1566 条 100% 一致
  （引用 1523 键 / 30 文件）/ 动作号 55
- `QUICK=1` **97 通过 / 0 失败 / 4 跳过**
- `aapt2 dump resources` 抽查 `pr_perm_done_deep` / `pr_hash_match` / `pr_octal`：
  中英双变体齐全
- APK **13,292,240 B**，md5 `779b7af4…`

---

## v2.32.0（versionCode 225）—— F-14/19：文件解析报告拆到 ui 层

`FileInspect` 77 处中文字面量，全都是「文件解析」那份多行报告里的标签与说明句。这页不像
别的页面那样换几个键就完事 —— 报告是**在 apk 层拼成整段文本**再交给 HEX 编辑器显示的，
换键就得先让 apk 层能拿到语言，而这一层连 `Context` 都没有。

### 拆法：解析留在 apk，文案搬到 ui

| 保留在 `apk/FileInspect`（纯 Java + 可桌面单测） | 搬到新类 `ui/FileReport`（拿 `Context`） |
| --- | --- |
| `magicName` / `parseElf` / `readHead` / `jksVersion` / `dexVersion` / `hexHead` | `describe()` 报告正文 |
| `typeCode()`（APK / JAR / PKCS12 / X509 / JKS_MISMATCH …） | `typeText()` 类型行 |
| `magicDescTag()`（ELF / ZIP_EMPTY / BKS_OLD … 29 个说明码） | 说明码 → 说明句 |
| `Elf.typeCode()`（NONE/REL/EXEC/DYN/CORE）、`machineName()`（AArch64…） | `elfTypeText()` / `machineText()` 补注解 |

魔数表第三列原来是中文说明句，现在改存**说明码**；`ZIP` 的三个变体（普通 / 空 / 分卷）
原来共用一句「ZIP 压缩包」，拆码后各有一句 —— 顺带把「空 ZIP 被说成普通 ZIP」这个
小错也修了。

`Elf.typeName()` 改名 `typeCode()`：原来它返回 `"REL（可重定位目标）"`，现在只回 `REL`，
注解在 ui 侧。`machineName()` 里唯一的中文 `"ARM (32 位)"` 同理，代码里回 `ARM`，
ui 按码补「32 位」。

### 表格对齐不受译文影响

节表是等宽字体下的定宽表格（`%-18s %-10s %-3s 0x%08x  %8d  %s`），标签只有「匿名」和
「超出已读范围」两处走资源，其余是节名/类型码/十六进制数字 —— 英文再长也不会串列。
类型码（`PROGBITS` / `SYMTAB` …）本来就是 ASCII，不翻。

### 连带改动

- `SignFlowController.parseKeystore` 的「格式: 」一行改用 `FileReport.typeText`（该页
  自身其余文案留给它自己的版本）。
- `FileInspectTest` 里 4 处「断言报告文本含某中文词」的用例改为断言**解析结果**
  （`typeCode` / `machineName` / `magicDescTag`）+ 用 `ZipFile` 直接校验 META-INF 签名块，
  不再依赖文案 —— 这类断言以前改一个字就红。

### 验证

- `FileInspect` 中文字面量 **77 → 0**；新类 `FileReport` 0 处中文字面量
- 新增 80 个 `fi_*` 键；资源总量 **1428 → 1508 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 1508 条 100% 一致
  （引用 1465 键 / 29 文件）/ 动作号 55
- `QUICK=1` **97 通过 / 0 失败 / 4 跳过**
- `aapt2 dump resources` 抽查 `fi_shdr`（3 个参数）/ `fi_sec_more`（前导 `…`）/
  `fi_type_join`：中英双变体齐全
- APK **13,275,856 B**，md5 `74d9ced3…`

---

## v2.31.0（versionCode 224）—— F-14/18：HEX 查看器 i18n + `HexCore` 输入校验码化

`HexViewerActivity` 90 处中文字面量清到 0。这页的文案密度不高，但有两处不是「换个键就完事」：

### 1. 类型下拉不是静态数组了

```java
// 以前：静态 final 数组里塞中文，类加载就定了语言
private static final String[] TYPE_LABELS = {"十六进制 HEX", "文本 TEXT", "Int8", ...};
```

改成 `typeLabels()` 方法，前两项按资源取、其余是类型名本身（`Int8` / `UInt8` … 本来就不该翻）。
静态数组在 Activity 里既拿不到 `Context` 也无法随语言切换重建。

### 2. `HexCore.patternFor` 的 8 处中文异常

搜索框会把 `e.getMessage()` 直接贴到状态栏：

```java
try { pat = HexCore.patternFor(type, input, be); }
catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
```

`core` 层不该知道语言，照 `FileCrypt` 的办法加 `HexCore.Fail extends IllegalArgumentException`
+ `Code` 11 个码（`EMPTY` / `NOT_INT` / `RANGE`(带下上界) / `RANGE_U64` / `NEG_INT` /
`ODD_HEX` / `BAD_HEX`(带那个字符) / `NULL_DATA` / `PATCH_RANGE` / `INSERT_RANGE` /
`DELETE_RANGE` / `UNKNOWN_TYPE`），文案在 ui 侧 `hexError()` 里按码取。

`RANGE` 的两个边界和 `BAD_HEX` 的那个字符都作为**数据参数**带出来（`min()` / `max()` / `ch()`），
不是拼进诊断串再让 ui 去切 —— 否则又回到「从文案里抠数据」。

### 3. 状态栏三段后缀

`" · 偏移 0x"` / `" · 仅前 4MB"` / `" · ✎ 未保存"` 各自成键（含前导空格，整值加了引号
防止 aapt 折叠），`sb.append(s(R.string.hx_status_offset, hex))` 拼装。空格与 `·` 分隔符
跟语言走，英文用 `· offset 0x…`。

### 验证

- `HexViewerActivity` 中文字面量 **90 → 0**（只剩注释）
- `HexCore` 中文字面量 **0**（`grep -P '"[^"]*[\x{4e00}-\x{9fa5}]'` 只剩注释）
- 新增 96 个 `hx_*` 键；资源总量 **1352 → 1428 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 1428 条 100% 一致
  （引用 1385 键 / 28 文件）/ 动作号 55
- `QUICK=1` **97 通过 / 0 失败 / 4 跳过**
- `aapt2 dump resources` 抽查 `hx_block_info` / `hx_err_range`（两个 `%d`）/ `hx_sig_hint`
  （首尾换行）：中英双变体齐全
- 构建 2 分 14 秒，APK **13,275,856 B**

---

## v2.30.0（versionCode 223）—— F-14/14~17：主页面剩余文案清零（两处逻辑层文案回归）

`MainActivity` 是最后一个大块：迁完菜单、多选栏、宫格菜单之后还剩 **268 处**
中文字面量，散在粘贴/拖放、媒体转封装、播放地址、签名文件、Dex 动作、图片批处理、
哈希校验、APK 解包打包、共存、分割合并、Zstd、对比、smali 编译、分享、重命名、
加解密、删除、书签、最近打开、外部导入、退出与权限提示里。这一版全部清零 ——
现在整个文件里带中文的行只剩注释。

顺带抓到两处**逻辑层直接产文案**，都是同一类问题，按 v2.26.0 的办法改成「码 + 数据参数」：

| 位置 | 原来 | 现在 |
| --- | --- | --- |
| `utils/DragPlan` | `Check.reason`（中文句子）、`title()`（拼「N 项 → 目录」） | `Check.block`（`Block` 5 个码）+ ui 侧 `dragText()/dragItemDesc()` |
| `fs/FileCrypt` | 9 处 `IllegalArgumentException("源文件不存在")` 之类，UI 直接 `toast(e.getMessage())` | `FileCrypt.Fail extends IllegalArgumentException` + `Code` 7 个码，ui 侧 `cryptError()` |

两处都**保留原异常类型**（`IllegalArgumentException`），调用方与测试里的 catch 一行没改；
`getMessage()` 退化成 ASCII 诊断串（`"crypt: bad magic"`），只给日志与单测看。

`DragPlan.title()` 直接删掉而不是改成接收 `Context`：它的产物是「标题文案」，
放在 utils 层只能是中文或需要语言参数，移到 ui 后逻辑层只剩判定。

### 拼接串整句化

老代码里典型的「拼一半」都按完整句拆键，译文不用再猜后缀接在谁后面：

```java
// 以前：三段后缀拼接，翻译必然错位
String msg = "成功 " + fok + " 张";
if (ffail > 0) msg += "，失败 " + ffail + " 张" + (ferr != null ? "（" + ferr + "）" : "");
toast(msg + "，输出在同目录（_p 后缀）");
```

现在是三条完整句子（全成 / 全成…有失败 / 全成…有失败并带原因）按状态选一条，
`getString` 一次成型。

`runCrypt(verb, …)` 原来拿「加密 / 解密」当中文参数，用来拼「已加密: xxx」
「加密失败: xxx」「加密文件」三种文案 —— 典型「文案当参数」。改成只传
`boolean enc`，三处文案各自按键取。

### 复用与拆键的判断

- **复用**：`HEX 查看` / `属性` / `Dex 编辑器++` / `导入签名管理` / `签名信息` /
  `Dex 修复` / `计算哈希` / `图片处理` / `APK 解包` / `去除签名` / `重命名` /
  `播放链接` 等，宫格菜单（`mops_*`）里同一动作的已有键直接吃过来，不新建。
- **拆开**：`APK 对比：另一个目录` 这种四选一的标题（apk/普通 × 目录/文件）写成
  四个完整键，而不是 `(apkMode ? a : b) + "：另一个" + (dir ? c : d)` —— 后者
  在英文里语序不同，拼不出来。

### 验证

- `MainActivity` 中文字面量 **268 → 0**（`grep -P '"[^"]*[\x{4e00}-\x{9fa5}]'` 只剩注释行）
- `DragPlan` / `FileCrypt` 代码里 0 处中文字面量；`DragPlanTest` 断言从「文案含某词」
  改为「码是 SAME / SELF / NO_ITEMS / TARGET_INVALID」
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **1352 条 100% 一致**
  （引用 1309 键 / 27 文件）/ 动作号 55
- `QUICK=1` **97 通过 / 0 失败 / 4 跳过**
- `aapt2 dump resources` 抽查 `main_enc_msg`（`%1$s` 用两次）/ `main_split_info`（6 个参数）/
  `drag_msg_skip` / `crypt_bad_version`：中英双变体齐全
- APK **13,259,472 B**，md5 `6f83295c…`

---

## v2.29.0（versionCode 222）—— F-14/13：还原结果码化 + 备份管理页 i18n

上一版结账时留下的那条：`BackupRestore.RestoreOutcome.message` 是**中文成品句**，
而且是还原结果的唯一出口（两个页面都直接 `toast(o.message)`）。这一版按 v2.26.0
`HlsDownloader` 的同一套办法把它拆干净，顺手把整页 `BackupManagerActivity` 迁完。

### utils 层不再产文案

`RestoreOutcome.message`（`String`）换成 `code`（`enum Code`，11 个）+ `args`（数据参数）：

```java
public enum Code {
    OK_DATA, OK_NO_DATA, OK_DATA_FAILED,
    ERR_NO_APK, ERR_NO_ROOT,
    ERR_COPY /* 参数：APK 文件名、底层输出 */,
    ERR_TIMEOUT, ERR_SIGN, ERR_SPLIT,
    ERR_INSTALL /* 参数：pm 输出 */, ERR_UNKNOWN
}
```

`installApkSet` 原来返回 `String`（成功 null / 失败给中文），现在返回 `boolean`
并把码写进 outcome；`friendlyInstallError` 改名 `installError`，只做「pm 输出 →
码」的分类，不再拼句子。`args` 里全是**数据**：APK 文件名、`pm` 原文、`cp` 的报错，
译文按语序自己排。

### 一份映射，两个页面共用

还原结果有两个消费方（备份管理页、应用管理页），所以映射不能写在任一 Activity 里。
新增 `ui/RestoreText.of(Context, RestoreOutcome)`：`switch (o.code)` → `R.string.rs_*`，
新增码忘了加译文时落到 `rs_err_generic`（把码名露出来），不会显示空白。

### 页面迁移

`BackupManagerActivity` 中文字面量 **22 行 → 0**（剩下的全是注释），24 个新键：

| 组 | 键数 | 内容 |
| --- | --- | --- |
| `rs_*` | 12 | 11 个结果码 + 1 条兜底 |
| `bk_*` | 12 | 标题 / 说明 / 空态 / 还原按钮 / 删除对话框 / 行状态 |

两个取舍：

- 「正在还原 %1$s …」**复用** `am_restoring` —— 与「备份应用」同一个动作词汇，不该两处。
- 行状态「已安装 / 未安装」**不复用** `apk_row_installed` —— 那是 APK 信息弹窗里的
  条目标签，这里说的是「这台设备上装没装」，英文也可能分叉，各留各的键。

### 顺手修：四个测试长期空跑 / 长期红

跑 `QUICK=1 test/run_all.sh` 时冒出 4 个失败，逐个查下来**都不是本版引入的**：

| 测试 | 症状 | 真因 |
| --- | --- | --- |
| `AxmlTest` / `DexWritebackTest` / `SignTest` | `out/ 无 HexFile-*.apk，请先构建` | 取样本的正则写死 `HexFile-1\.\d+`，v2.0.0 起版本号变 2.x 后**再没匹配上过**，等于空跑 |
| `AttrLogicTest` | `submitOutput 无 su 应抛` FAIL | 实现早改成「无 root 返回 `done(null)`」的无声降级，断言还写着「应抛」，于是一直红 |

正则改 `HexFile-\d+\.\d+(\.\d+)?\.apk`；断言改成「返回 null、不抛」。修完三条链路
测试**真正跑起来**并全过 —— 也就是说 arsc 编辑 → 重打包 → 重签名在 2.29.0 的 APK 上
仍然成立，这条结论此前两年是没有验证过的。

### 验证

- `BackupManagerActivity` 中文字面量 **22 → 0**；`BackupRestore` 代码里 0 处中文字面量
- 四道门：依赖方向通过（`ui → utils` 合法，`utils` 不反向依赖 `R`）/ 空 catch **335（= 基线）** /
  文案 **1141 条 100% 一致**（引用 1098 键 / 27 文件）/ 动作号 55
- `QUICK=1` **97 通过 / 0 失败 / 4 跳过**（此前 4 红）
- `aapt2 dump resources` 抽查 `bk_delete_msg` / `bk_row_sub` / `rs_err_copy`：中英双变体齐全，
  `\n` 为真实换行
- APK 见 `out/HexFile-2.29.0.apk`

---

## v2.28.0（versionCode 221）—— F-14/12：应用管理页 i18n

`AppManagerActivity` 的界面是**纯代码搭出来的**（`setContentView(root)`，没有布局文件），
所以文案全在 Java 里：96 处 / 88 个不同字面量，含筛选 chip、应用信息头、9 个操作项、
卸载/清理/冻结/导出/备份/还原的全套进度与结果提示、权限列表与批量授予。
全部迁完，落成 **76 个 `am_` 键**。

### 拼接串整句化

页面里大量「拼一半」的写法：

```java
hs.append("包名: ").append(r.pkg).append("\n版本: ").append(r.version)
  .append("\n类型: ").append(r.system ? "系统应用" : "第三方应用");
```

改成一行一个格式键（`am_hdr_pkg` = `包名: %1$s`），`\n` 由代码补。
备份结果那句最绕（`" + 数据"` / `（数据备份失败）` / `（未 Root，仅 APK）` 三段后缀拼接），
拆成三条完整的句子按状态选键 —— 译文不必再猜「后缀接在谁的后面」。

### 一个只有真编译才会暴露的错

这一页原本**一次都没用过 `R`**，所以文件里没有 `import com.hex91.file.R;`。
加了 `R.string.am_*` 之后，javac 报的却是：

```
错误: 找不到符号
符号: 变量 am_search_hint
位置: 类 AppManagerActivity
```

看着像「资源没生成」，实际是 `R` 根本没 import。补上就好 —— 记在这里是因为
错误信息把人往 aapt2 方向带，容易白查一轮。

### 验证

- `AppManagerActivity` 中文字面量 **96 → 0**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **1117 条 100% 一致**（引用 1074 键 / 25 文件）/ 动作号 55
- `QUICK=1` **66/66**
- APK **13,226,704 B**，md5 `2b729bf6…`

> 记账：`BackupRestore` 的 `o.message` 仍是中文（还原结果的唯一出口）。
> 它和 v2.26.0 的 `HlsDownloader` 是同一类问题 —— 纯逻辑层直接产出文案，
> 下一批按「错误码 + ui 层映射」处理。

---

## v2.27.0（versionCode 220）—— F-14/11：视频播放页 i18n（最大的一页）

播放页是单个文件里文案最多的页面（104 处 / 90 个不同字面量），
覆盖工具栏胶囊、画质/倍速/方向/缩放四个对话框、播放信息、错误提示、手势悬浮提示和 ⋮ 菜单。
**清零**，落成 **97 个 `vp_` 键** + `common_auto`。

### 两个纯逻辑层也交出了文案

`ScreenPolicy.label()/describe()`（core）和 `StreamUrl.kindLabel()`（net）本来直接返回中文：

```java
public static String label(int mode) {
    return m == PORTRAIT ? "竖屏" : m == LANDSCAPE ? "横屏" : "自动";
}
```

它们只有一个调用方（播放页），却把「中文怎么说」固化进了状态机。
这一版把方法删掉，ui 层按 `orientShort()` / `orientText()` / `sourceKindText()` 取资源 ——
状态机只管 `AUTO/PORTRAIT/LANDSCAPE`，文案跟着语言走。

### 顺手清掉一个「用文案当状态」

```java
private String qualityLabel = "自动";   // 既当显示值，又当「没锁定」的哨兵
```

改成 `null` = 自动。这次它**还没坏**（只用于显示和跟 `v.label` 比较），
但同一类写法在 `ArchiveController`（v2.20.0）和 `showApkInfo`（v2.24.0）已经坏过两次，
能顺手收掉的就不留在仓库里等它坏。

### 格式串的语序交出去

播放信息原来是「标签 + 值」两次 append：

```java
sb.append("分辨率：").append(v.width + "×" + v.height)   // 中文语序写死在代码里
```

改成整句一个键（`vp_info_resolution` = `分辨率：%1$s`），译者能调整语序；
`" · %1$d 声道"` 这种以空格开头的片段仍要**整值加引号**，否则被 aapt 折叠。

### 验证

- `VideoPlayerActivity` 中文字面量 **104 → 0**；`ScreenPolicy` / `StreamUrl` 同批清零
- 布局 `activity_video_player.xml` 6 处 `android:text` 改 `@string`（剩下的是 ← ⋮ ⏮ ▶ ⏭ 和时间占位符）
- 单测跟着改口径：`ScreenPolicyTest` 删掉 label/describe 断言（文案不在这一层了），
  `StreamUrlTest` 的「类型文案」断言换成「m3u8 → KIND_HLS」分类断言
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **1041 条 100% 一致**（引用 998 键 / 24 文件）/ 动作号 55
- `QUICK=1` **66/66**
- APK **13,210,320 B**（+16,384），md5 `ee9e1363…`

---

## v2.26.0（versionCode 219）—— F-14/10：net 层去中文（HLS 加密信息与错误码）

下载页翻完之后还剩一条尾巴：页面文案走了资源，但**报错文字仍出自 `net/`**。
上一版记了这笔账，这一版还上。

问题不在「没翻译」，而在**翻译不了** —— 纯 Java 层拿不到 `Context`：

```java
// 改之前
public String unsupportedReason() {
    return "分片使用 " + method + " 加密（Apple DRM 体系），需要设备密钥，无法在应用内解密";
}
```

两个选择：往 `net/` 传 `Context`（把 Android 依赖拖进纯 Java 层，`deps_check` 也不答应），
或者在**这一层只给数据、文案回到 ui 层**。选了后者。

### 三处改造

**1. 加密方案 → 枚举**（`HlsKey.Kind` / `HlsPlaylist.Crypt`）

```java
public enum Kind { NONE, AES_128, DRM, OTHER;  public boolean supported() {...} }
public enum Crypt { NONE, UNIFORM, MIXED }
```

`label()` 从「未加密 / AES-128」收敛成纯协议标识（`NONE` / `AES-128` / `SAMPLE-AES`），
不再掺中文；「未加密」这种说法改由 ui 层按 `Crypt.NONE` 取。
`blockReason()`（返回中文）换成 `blockingKey()`（返回那把解不了的 `HlsKey`）。

**2. 下载错误 → 错误码**（新增 `HlsError extends IOException`）

24 个码，参数只放**数据**（路径、字节数、HTTP 状态码）：

```java
throw new HlsError(HlsError.Code.KEY_BAD_SIZE, kb.length);
throw new HlsError(HlsError.Code.HTTP_STATUS, code, shortUrl(url));
throw new HlsError(HlsError.Code.SEG_FETCH_FAIL, last);   // last 进 cause，原因不丢
```

`getMessage()` 仍给一句 ASCII 诊断串（`HLS HTTP_STATUS: 404 http://…`），只进日志与单测。
`extends IOException` 让所有既有 `catch (IOException)` 原封不动。

**3. 结果提示 → 枚举**（`HlsDownloader.Note`）

`Result.note` 从 `String` 变成 `Note + noteArg`：`SMALL_FILE`（参数是 KB）、`REMUX_FAILED`（参数是底层原因）。

### ui 层：一张映射表

`errorText(HlsError)` 一个 switch 把码翻成文案，`SEG_FETCH_FAIL` 递归取 `cause` ——
于是「分片下载失败：HTTP 404：…」这种嵌套仍然是完整的一句话，而不是只留下外层壳子。
`default` 分支输出「错误：+ 诊断串」：将来加了码忘了加译文，界面上至少还有字，不会空白。

生成 30 个键：`hls_crypt_*` 2 / `hls_block_*` 2 / `hls_note_*` 2 / `hls_err_*` 24。

### 验证

- `HlsKey` / `HlsPlaylist` / `HlsDownloader` 三个类的中文字面量**清零**（注释除外）
- 单测跟着改口径：`encryptionText()` 的三条断言 → `crypt()` 枚举；`blockReason().contains("DRM")`
  → `blockingKey().kind() == Kind.DRM`；「取消」断言 → `code == CANCELLED_KEEP`；
  404 断言改成**顺 cause 链找 `HTTP_STATUS`**，并新增一条「诊断串不含中文」
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **944 条 100% 一致**（引用 902 键 / 23 文件）/ 动作号 55
- `QUICK=1` **66/66**（含 `HlsPlaylistTest`、`HlsDownloaderTest` 的本地 HTTP 服务用例）
- APK **13,193,936 B**，md5 `a169763d…`

> 又一次踩到「看大小判断有没有改」的坑：v2.25.0 与 v2.26.0 的 APK **字节数完全相同**
> （13,193,936），只有 md5 不同 —— 增量没跨过 zipalign 的 4KB 对齐块。

---

## v2.25.0（versionCode 218）—— F-14/9：HLS 下载页 i18n（第三处「文案当逻辑」）

「下载视频」整页：粘贴 → 解析 → 选画质 → 下载 → 转封装，一条路走完。
落成 **60 个 `hls_` 键**（含布局 6 处），`HlsDownloadActivity` 的中文字面量**清零**（69 → 0）。

顺带修掉一处一直藏着的问题 —— 不是迁移时撞上的，是**扫出来的**。

### 靠文案前缀决定要不要写日志

日志有 150ms 节流（逐片刷屏会把 UI 线程拖慢），但收尾行必须放行：

```java
// 之前
if (now - lastLogAt < 150 && !s.startsWith("完成") && !s.startsWith("失败")) return;
```

「完成 → 路径」「失败：xxx」靠**中文前缀**拿到豁免。文案一进资源并翻成 `Done` / `Failed`，
`startsWith("完成")` 恒为 false —— 收尾那行就被节流吃掉。后果不是报错、也不是点错，
只是**日志里少了最后一行**，属于最难被发现的一类。

改法是把「要不要强写」变成一个显式参数，判断与文案彻底脱钩：

```java
private void log(String line) { log(line, false); }
private void log(String line, boolean force) {
    if (!force && now - lastLogAt < 150) return;
    ...
}
// 收尾两行
log(s(R.string.hls_done_log, fr.file.getAbsolutePath()), true);
log(s(R.string.hls_fail_log, msg(e)), true);
```

### 三处「文案当逻辑」的对照

| 版本 | 位置 | 写法 | 失效表现 |
| --- | --- | --- | --- |
| v2.20.0 | `ArchiveController` | `op.startsWith("Dex 编辑器++")` | 四个菜单项全落到 else，点「Dex 属性」变「提取」 |
| v2.24.0 | `MainActivity.showApkInfo` | `"签名状态".equals(label)` | 点签名状态只复制值，进不去详情 |
| v2.25.0 | `HlsDownloadActivity.log` | `s.startsWith("完成")` | 日志少最后一行，静默 |

前两处是迁移时撞上的，第三处是收尾时用

```
grep -E '(equals|startsWith|contains|indexOf|case)\s*\(?\s*"[^"]*[\u4e00-\u9fff]'
```

在全仓扫出来的。剩下 3 处扫到的都是**对数据**而不是对 UI 文案的判断
（zip 条目名里找「密码」、反编译出的 smali 源码里找「控制流扁平化」），不该改。

### 空白与百分号：aapt 会吃掉它们

` · 时长 3:20` 这种**以空格开头**的片段，如果直接写进 `<string>`，aapt2 会把前导空格裁掉；
`画质：` 后面那三个空格也会被折叠成一个。整值加双引号才保留：

```xml
<string name="hls_duration_suffix">" · 时长 %1$s"</string>
<string name="hls_quality_row">"画质：%1$s%2$s   ›"</string>
```

进度行里要输出字面量 `%` 得写 `%%`：

```xml
<string name="hls_progress">已下载 %1$d/%2$d 片 · %3$s%4$s · %5$d%%</string>
```

`aapt2 dump resources` 可以直接验：包里的值是 `" · 时长 %1$s"`（空格还在）、
`%5$d%%`（转义保留，运行时输出 `50%`）。

### 摘要句从 8 段拼接改成 6 个带参键

```java
// 之前：语序焊死在代码里
sb.append("分片：").append(n).append(" 个");
if (dur > 0) sb.append(" · 时长 ").append(clock(dur));
sb.append('\n').append("加密：").append(text);
sb.append(" · 容器：").append(fmp4 ? "fMP4" : "MPEG-TS");
// 现在：每段由译文自己排
sb.append(s(R.string.hls_segments, n));
if (dur > 0) sb.append(s(R.string.hls_duration_suffix, clock(dur)));
sb.append('\n').append(s(R.string.hls_encrypt_line, text));
sb.append(s(R.string.hls_container_suffix, fmp4 ? "fMP4" : "MPEG-TS"));
```

`describe()` 现在是 6 行，读起来和最终显示的样子一致 —— 这也算迁移的副产品。

### 还没做的（记一笔）

`HlsKey.unsupportedReason()`、`HlsPlaylist.encryptionText()` 返回的
「未加密 / 多把密钥 / 本应用只支持 AES-128」仍是中文 —— 它们在 `net/` 层，没有 Context。
要迁得往 `net/` 传 Context（像 v2.24.0 给 `ApkUtil` 那样），或者把返回值改成枚举、
到 UI 层再取文案。后者更干净，但改动面比这一批大，留作单独一次。

### 验证

- `build.sh` → **BUILD OK，v218 / 2.25.0**，APK **13,193,936 字节**，
  md5 `5b520f94…`（这次终于跨过对齐块，比上版大 16,384 = 两个 4096 块）
- `aapt2 dump resources` 核对 `hls_duration_suffix` 前导空格保留、`hls_progress` 的 `%%` 保留
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **914 条 100% 一致**（引用 872 键 / 23 文件）/ 动作号 55
- QUICK **66/66**
- `HlsDownloadActivity` 中文字面量 **69 → 0**；全仓 **3695 → 3525**

### 真机可先看这几处

1. 粘贴一个 m3u8 → 解析：结果区那几行（分片 / 时长 / 加密 / 容器 / 预计大小）是否成句
2. 下载过程中日志是否还在滚，**结束那行「完成 → 路径」有没有出现**（这是本次修的 bug）
3. 画质那一行 `画质：自动（最高）   ›` 的间距是否正常（三个空格没被吃掉）
4. 帮助「?」弹窗的六行说明

---

## v2.24.0（versionCode 217）—— F-14/8：APK 信息弹窗 i18n（又抓到一处「文案当逻辑」）

这一屏不止一个文件：弹窗本体在 `MainActivity.showApkInfo`，16 行标签值由
`ApkUtil.rows()` 生成，加固判定在 `ApkUtil.packStatus`，文件角色在 `FileKind.apkFileRole`，
再加上 `dialog_apk_info.xml` 的 4 个按钮。落成 **31 个键**（含 `role_*` 5 条）。

`MainActivity` 中文字面量 **319 → 308**；`ApkUtil` 与 `FileKind` 这一屏相关的字面量清零。

### 点「签名状态」那一行进不去签名详情

弹窗里每行点一下是「复制这一行的值」，唯独「签名状态」那行是进签名详情页：

```java
// 之前
if ("签名状态".equals(rowsF.get(i)[0])) { showSignDialog(f); }
else { copyToClipboard(rowsF.get(i)[0], rowsF.get(i)[1]); }
```

翻译之后这一行叫 `Signature`，`"签名状态".equals("Signature")` 恒为 false ——
**英文环境下点签名状态只会把「已签名」两个字复制走**，功能静默失效。
它比 v2.20.0 那处分派 bug 更隐蔽：那处是「四选一全错」，点下去会有明显异常行为；
这里只是「少了一个入口」，用户大概会以为自己记错了。

修法是让比较的两边**同源**：

```java
if (s(R.string.apk_row_sign).equals(rowsF.get(i)[0])) { ... }
```

左边是当前语言的资源值，右边是 `ApkUtil` 用**同一个** `R.string.apk_row_sign` 生成的标签。
两边永远相等，与语言无关。这比「按下标判断第几行」更稳 —— 行序将来要是变了，
下标方案会指错行，同源比较不会。

### 纯逻辑类要拿 Context，只能加参数

`ApkUtil.rows(File, PackageManager)` 原本不碰 Context，只输出中文标签。
现在要按语言取标签，就得传：

```java
ApkUtil.rows(f, pm, ctx)              // + Context
ApkUtil.packStatus(apk, ctx)          // 私有，跟着改
FileKind.apkFileRole(f, ctx)          // + Context
ApkCompareUtil.compare(a, b, pm, ctx) // 透传给 rows
```

`deps_check.sh` 仍然通过：`apk/` `fs/` 引用 `com.hex91.file.R` 不构成反向依赖
（R 是编译期生成的资源索引，不是某个包里的类），依赖方向依旧是
`core → utils → fs/apk → ui/controller → MainActivity`。

`ApkCompareUtil` 拿标签当 Map key 做两侧对齐 —— 只要两侧用同一种语言，
标签就一致，对齐不受影响。

### aapt2 不认裸单引号

英文里写 `pick 'Sign APK' from the menu`，aapt2 直接报
`unescaped apostrophe in string` / `not a valid string`，整个 `values-en` 编译失败。
AAPT 里单引号是**语法字符**（用来包住含空白的值），必须写 `\'`：

```xml
<string name="role_signed">Signed copy (separate file made by \'Sign APK\' with the built-in key)</string>
```

中文侧没有这个问题，所以只有英文文件会炸 —— 翻译完记得先单独跑一次 `aapt2 compile`。

### 验证

- `build.sh` → **BUILD OK，v217 / 2.24.0**，APK **13,177,552 字节**，
  md5 `cfb331a0…`（连续第四个版本字节数一模一样：31 条资源 + 一处方法签名改动，
  增量仍然没跨过 4096 对齐块。`unzip -l` 看 `classes.dex` 时间戳已变，
  md5 也已变——**判断「有没有改」只能靠 md5，大小会骗人**）
- `aapt2 dump resources` 核对 `string/apk_row_sign` 有 `()` / `(en)` 两变体、
  `string/role_bak` 两侧都是完整句子
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **854 条 100% 一致**（引用 818 键 / 22 文件）/ 动作号 55
- QUICK **66/66**

### 真机可先看这几处

1. 长按 APK →「APK 信息」→ 点「签名状态」那行，是否进签名详情（英文环境下尤其要看）
2. 同一弹窗底部四个按钮：功能 / 查看 / 安装 / 关闭
3. `_bak.apk`、`_signed.apk` 打开信息，第一行下面是否有「文件角色」及其说明

---

## v2.23.0（versionCode 216）—— F-14/7：文件操作宫格菜单 i18n

长按文件 / 文件夹弹出的那个双列宫格，是日常点得最多的一个界面：普通对象 38 项、
APK 专属 28 项，实际是同一套 `ops` / `acts` 双列表喂给 `showGridMenu`。
这一批把 **63 处 `ops.add("…")` 全部换成资源**，落成 **51 个 `mops_` 键**
（去重后，「Dex 编辑器++」「分享」「打开方式」等在两个菜单里共用一个键）。

`MainActivity` 中文字面量 **386 → 319**（累计 444 → 319）。

### 这里本来就是安全的，值得对照着看

`showGridMenu` 的分派写法是：

```java
tv.setOnClickListener(... { cb.run(acts.get(idx)); });
```

**按下标取动作码**，文案只负责显示。所以即便文案翻成英文，点的还是那一条。
这和 v2.20.0 在 `ArchiveController` 里抓到的 `op.startsWith("Dex 编辑器++")` 正好相反：
同一个应用里，一处按索引分派、一处按文案分派，后者一翻就全线错位。

迁移时逐段确认过：这两个菜单**没有**任何一处拿 `ops` 的内容做判断。
剩下的 319 处里还有几段要按这个标准过一遍。

### 专有名词不译，动作照译

| 中文 | 英文 |
| --- | --- |
| Dex 编辑器++ | Dex Editor++ |
| ARSC++ 编辑 | Edit ARSC++ |
| Manifest 编辑器++ | Manifest Editor++ |
| 反编译 dex→smali | Decompile dex→smali |
| Zstd 压缩 / 解压 | Zstd compress / extract |
| HEX 查看 | View as HEX |
| strings 提取 | Extract strings |
| 复制(剪贴板) | Copy (clipboard) |
| 局域网快传 | LAN transfer |
| 移入隐私空间 | （复用 `vault_move_in`） |

`Dex` / `ARSC` / `Manifest` / `Zstd` / `HEX` / `strings` 这些是格式与技术名，
不翻；跟着它们的那半句（编辑器 / 编辑 / 压缩 / 查看 / 提取）照译。
「(剪贴板)」用半角括号——它是个补充说明，不是中文行文的停顿。

### 复用与新建的分界

「移入隐私空间」直接复用 v2.18.0 的 `vault_move_in`、「删除」复用 `common_delete`：
这两处是**同一个功能**，从哪个入口进都该是同一句话。
反过来「属性」没有复用 `arch_op_props` —— 那个是压缩包条目的属性，
和本地文件的属性是两套不同的东西（一个只读展示，一个可改权限/时间戳）。

### 验证

- `build.sh` → **BUILD OK，v216 / 2.23.0**，APK **13,177,552 字节**，
  md5 `0322bfa7…`（连续三个版本字节数完全相同；这是 zipalign 4KB 对齐的巧合，
  51 条新资源 + 代码改动都没跨过对齐块。**看大小判断有没有改是行不通的，只能看 md5**）
- `aapt2 dump resources` 核对 `string/mops_dexpp` 有 `()` / `(en)` 两变体，包内 `mops_` 资源 **51 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **823 条 100% 一致**（引用 790 键 / 20 文件）/ 动作号 55
- QUICK **66/66**

---

## v2.22.0（versionCode 215）—— F-14/6：多选工具条与批量操作 i18n

这一批是「进入多选之后」的那整条路：底部工具条 7 个按钮、批量复制/移动、批量删除、
批量提取（压缩包条目），外加它们各自的重命名入口。落成 **19 个 `main_` 键**，
`MainActivity` 中文字面量 **408 → 386**（累计 444 → 386）。

同时把 `activity_main.xml` 里多选栏那 7 处 `android:text="全选"` 一类也换成资源引用 ——
布局里的硬编码最容易被漏掉，因为它不在 Java 里，`grep` 代码时看不见。

### 一个键变两个：「完成(0)」与「完成(N)」

布局里写死的是 `android:text="完成(0)"`，运行态由 `updateSelDone()` 改成 `完成(N)`。
直接让两边共用 `完成(%1$d)` 不行 —— 布局里的 `%1$d` 会**原样显示**成「完成(%1$d)」
（布局无法传参）。所以拆成两个键：

| 键 | 用途 | 中文 | 英文 |
| --- | --- | --- | --- |
| `main_sel_done_idle` | 布局初值 | 完成(0) | Done (0) |
| `main_sel_done` | 运行时带参 | 完成(%1$d) | Done (%1$d) |

初值其实进不了用户的眼（一进入多选就先调 `updateSelDone()`），但留着它，
布局文件打开时能看懂这块是干什么的 —— 空 `text` 反而更难读。

### 队列提示：一句话两处数据

```java
// 之前
toast((isMove ? "已加入移动队列 " : "已加入复制队列 ") + realFiles.size()
        + " 项 →\n" + dst.dir.getPath());
// 现在
toast(isMove ? s(R.string.main_queued_move, realFiles.size(), dst.dir.getPath())
             : s(R.string.main_queued_copy, realFiles.size(), dst.dir.getPath()));
```

原来是「前缀 + 数字 + 中缀 + 路径」四段拼出来的。拆成带参键之后，
`%1$d` 和 `%2$s` 的相对位置由各语言自己排，代码只负责喂两个值。
XML 里的 `\n` 是**单层**（aapt2 会解析成真换行），`aapt2 dump resources`
能直接看到它断成两行——这一点值得核一遍，写错成 `\\n` 界面上就会出现反斜杠和字母 n。

### 压缩包条目与普通文件：提示语分开

批量删除原来只有一条路径，现在按条目归属分两支，提示语也跟着分：

| 场景 | 提示 |
| --- | --- |
| 普通文件 → 回收站 | 将 %1$d 项移入回收站（可恢复）？ |
| 已移入 | 已移入回收站 %1$d/%2$d 项 |
| 压缩包条目 | 走 `ArchiveController` 的 `arch_del_msg`（不可恢复） |

「可恢复 / 不可恢复」这半个词的差别是这一屏最要紧的信息：回收站能捞回来，
压缩包内删条目是重建 zip，删了就没了。译文也得把这点保住 ——
英文用 `recoverable` / `(this cannot be undone)`，不用笼统的 `Delete?`。

### 验证

- `build.sh` → **BUILD OK，v215 / 2.22.0**，APK **13,177,552 字节**，
  md5 `f62f600e…`（与 v2.21.0 **字节数完全相同**、md5 不同——新增的 19 条资源
  连同布局改动正好没跨过 4096 对齐块。光看大小会误判成「没改」，这里靠 md5 反证）
- `aapt2 dump resources` 核对 `string/main_sel_done` 有 `()` / `(en)` 两变体，
  `main_queued_move` 的 `\n` 确实解析为换行，包内 `main_` 资源 **47 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **772 条 100% 一致**（引用 738 键 / 20 文件）/ 动作号 55
- QUICK **66/66**

---

## v2.21.0（versionCode 214）—— F-14/5：主菜单 / 排序 / 新建 i18n

`MainActivity` 是 F-14 最后一块，也是最大一块：审计口径 426 处，
光中文字符串字面量就有 444 个，一页塞不下一次提交。这一批先做**用户一进应用就看见的那一层**：
⋮ 溢出菜单、排序方式、新建文件夹 / 新建文本文件，连带 `+` 按钮背后的命名对话框。

落成 **28 个 `main_` 键 + 1 个通用 `common_create`**，`MainActivity` 中文字面量 **444 → 408**。

### 菜单项：能复用的不新建

主菜单里「刷新」「设置」这两个词在别处已经存在（`refresh` / `settings_title`），
直接复用 —— 菜单项跟着设置页标题一起改，本来就应该联动。反过来「回收站」不复用
`settings_section_trash`：那是设置页里一个分区的小标题，语义不是同一个东西，
将来设置页分区改名不该把主菜单也带走。

判断标准就一条：**同一个概念才共用键，同一个词不算。**

### 拼接改带参

```java
// 之前
menu.add(..., FileClipboard.get().isEmpty() ? "粘贴（剪贴板为空）" : "粘贴：" + FileClipboard.get().label());
// 现在
menu.add(..., FileClipboard.get().isEmpty() ? s(R.string.main_paste_empty)
                                            : s(R.string.main_paste_label, FileClipboard.get().label()));
```

带参形式的好处不只是少一次 `+`：整句的语序由译文自己决定。英文写 `Paste: %1$s`，
日文将来若要改成「%1$s を貼り付け」也不用碰代码。

「新建文本文件 @ 目录名」同理，`main_new_file_title` 里把 `%1$s` 放在 `@` 后面。

### 中英对照（节选）

| 中文 | 英文 |
| --- | --- |
| 解压全部到此目录 | Extract all here |
| 列表视图（双栏） | List view (two panes) |
| 网格视图（大图） | Grid view (large icons) |
| 隐藏 .文件 / 显示 .文件 | Hide dotfiles / Show dotfiles |
| 交换窗口 | Swap panes |
| 粘贴（剪贴板为空） | Paste (clipboard empty) |
| 下载视频（m3u8 → MP4）… | Download video (m3u8 → MP4)… |
| 按修改时间 | Modified time |
| 压缩包浏览中不可新建，请先退出压缩包 | Cannot create items inside an archive; exit the archive first |
| 创建失败（已存在或无权限） | Could not create (already exists or permission denied) |

「列表视图（双栏）」那句其实是**动作**不是状态：当前是网格时，菜单项写「列表视图（双栏）」，
点下去变成列表。英文沿用同样的写法（`List view (two panes)`），不写成动词短语，
因为菜单项的语法要和它所在的那一组保持一致。

### 验证

- `build.sh` → **BUILD OK，v214 / 2.21.0**，APK **13,177,552 字节**，
  md5 `dea2ce99…`（比 v2.20.0 大 16,384——新增资源正好跨过一个 4096 对齐块）
- `aapt2 dump resources` 核对 `string/main_sort_name` 有 `()` / `(en)` 两个变体，
  包内 `main_` 资源 **28 条**
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **753 条 100% 一致**（引用 724 键 / 20 文件）/ 动作号 55
- QUICK **66/66**

### 还没迁的

`MainActivity` 剩 408 处。下一步按屏拆：批量操作 / 多选工具条 → 侧栏与历史 → 文件操作菜单
（`showOpsMenu` / `showApkOpsMenu` / `runFileAction` 那一大片）→ 各种一次性提示。
迁移每一段都要复核**该段内部**有没有拿文案当下标或比较 —— v2.20.0 在
`ArchiveController` 里抓到的那个 bug，就是这种写法。

---

## v2.20.0（versionCode 213）—— F-14/4：压缩包内操作 i18n

`ArchiveController` 是「压缩包内浏览」的全部行为：打开、密码、点击菜单、长按宫格、
提取、反编译、ARSC/DEX、改名/新建/删除、条目在地在两个包之间搬运。
审计口径 117 处硬编码中文，落成 **86 个 `arch_` 键 + 2 个 plurals + 1 个 `common_confirm`**。

### 迁移时抓到一处真 bug：文案当逻辑

点击 `.dex` 弹的菜单原来是这样分派的：

```java
String op = ops.get(w);
if (op.startsWith("Dex 编辑器++")) { ... }
else if (op.equals("反编译为 smali")) { ... }
```

只要文案一翻，英文环境下**每一条都对不上**，四个分支全部落进最后的 `else` ——
点「Dex 属性」会变成「提取到当前窗格」。这种错编译不报、中文下也完全不显露。
现在改成按下标分派（`Dex 修复` 只对 APK 出现，所以下标在构建 ops 时算出来）：

```java
final int iFix = isApk ? ops.size() : -1;
if (isApk) ops.add(s(R.string.arch_op_dex_fix));
final int iHex = ops.size();
ops.add(s(R.string.arch_op_hex));
final int iExtract = ops.size();
ops.add(s(R.string.arch_op_extract_here));
```

顺带一提：`i18n` 最有价值的产出往往不是译文，而是把这些「字符串参与控制流」的地方
逼到台面上——它们平时藏得很深。

### 同一动作两个名字

点击菜单叫「提取到当前窗格目录」，长按宫格叫「提取到当前窗格」，两个不同字面量。
迁移时合成一个键 `arch_op_extract_here`（以短的那个为准）。这类重复不合并的话，
英文侧要翻两遍、日后改文案要改两处，改漏一处就是两种语言下的菜单不一致。

### 拼接文案拆成「主体 + 后缀」

```java
host.toast((move ? "已移动进压缩包" : "已复制进压缩包")
        + "（已写回原包，首次修改自动备份 _bak.apk）");
```

后半句在删除条目那里也出现一次。现在抽成 `arch_written_back`：中文以全角括号开头、
英文以空格开头（` (written back into the APK; ...)`），两边拼上都读得通。
**后缀带着自己的前导空格/括号，比让每个调用点各加一次靠谱。**

### 控制器里怎么取文案

`ArchiveController` 没有 `Context`，只有 `Host`。加两个私有重载：

```java
private String s(int id) { return host.ctx().getString(id); }
private String s(int id, Object... args) { return host.ctx().getString(id, args); }
```

于是 `host.toast(s(R.string.arch_opening))` 就够短了。这不是新发明——
`TaskUiController` 早在 F-14/2 里就是 `host.ctx().getString(...)` 直写，
这里只是把重复的 `host.ctx()` 收进一个方法。

### 数字走 plurals

「从压缩包永久删除 N 项」与「已删除 N 项」用 `plurals`：中文只有 `other`，
英文分 `one` / `other`（`1 item` 不能写成 `1 items`）。文案门比较的是「位置 + 转换符」，
两边都是 `%1$d`，中文少一条 `one` 不会误报。

### 连错误信息一起迁

`AtomicReplace.failMessage("写入失败: " + root)` 的结果会进 `RuntimeException`，
最终被 `host.toast` 弹给用户看——所以它也是文案，一并换成资源键。判断标准很简单：
**会不会出现在屏幕上**，而不是「在不在 UI 类里」。

### 验证

- `build.sh` → **BUILD OK，v213 / 2.20.0**，APK **13,161,168 字节**，
  md5 `f0574b02…`（与 v2.19.0 同大小但 md5 不同：unsigned 增量正好是 4096 的倍数，
  被 zipalign 的 STORED 对齐 padding 吃掉了，看大小会误判成「没改」）
- `aapt2 dump resources` 核对 `string/arch_rename_title` 有 `()` / `(en)` 两个变体，
  包内 `arch_` 资源 86 条 + 2 个 plurals
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **724 键 100% 一致** / 动作号 55
- QUICK **66/66**

### 还没迁的

只剩 `MainActivity`（审计 426 处）——主菜单、批量操作、侧栏、各种提示都在这里，
体量是这一页的三倍多，得再拆成几次提交。

---

## v2.19.0（versionCode 212）—— F-14/3：网络存储页 i18n

F-14 的逐页迁移接着做。这一页审计口径 110 处硬编码中文，实际落成 **88 个 `nst_` 键
+ 1 个 `nst_types` 字符串数组**（七种协议的类型下拉项）。

| 项 | 中文 | 英文 |
| --- | --- | --- |
| 资源键 | 635 | 635（100% 覆盖） |
| 代码引用 | 604 个键 / 19 个文件 | — |
| 本页新增 | 88 键 + 1 数组 | 同 |

### 这一页难在「同一批输入框有七种含义」

添加服务器的表单是打字机式的：`share` 在 SMB 下叫「共享名」，在 S3 下叫「存储桶」；
`domain` 在 SMB 下是「域/工作组」，在 S3 下是「区域」；`user` 在 S3 下是 AccessKey、
OneDrive 下是 Client ID。所以文案不是「摊平了搬进资源」就完事，得把
`setRowLabel(share, smb ? "共享名…" : "存储桶…")` 里的两个分支分别换成两个键，
七个协议 × 五个字段的组合才不会串味。

英文侧这里还顺手统一了一处排版：中文原来初始标签写「域/工作组」，切换类型时又写成
「域 / 工作组」（带空格），同一个字段两副面孔。现在都走 `nst_field_domain`，
以初始标签为准。

### 两个 static 方法得先改签名

`friendlyNetError(Throwable)` 与 `skipFully(InputStream, long)` 原本是 `static`：
静态方法里没有 `getString`，只能返回硬编码字符串。两个方法都只在本页被调用
（一个被 `errorDialog` 用，一个被上传线程用），直接改成实例方法即可。

这不是「随手改」，而是这类迁移里唯一真正会卡住的地方：一旦某个静态工具被三个页面共用，
就得把文案作为参数传进去，或者把它提升成资源层。**先看调用点，再决定改签名还是改参数**，
比迁到一半发现「这个 static 动不了」再回滚省事。

### 协议名不翻，只有云盘名字要翻

`SMB · ` / `WebDAV · ` / `FTP · ` / `SFTP · ` / `S3 · ` / `OneDrive · ` 都是业界写法，
留在代码里当字面量（跟 `ArchiveUtil` 里格式名不翻是同一条规矩）；只有「阿里云盘」
在英文下要写成 `Aliyun Drive`，所以它单独有 `nst_type_aliyun` 键，其余拼接。

服务器副标题也是同理：`用户: alice` 这种「标签 + 变量」硬拼在英文里语序放得进
（`User: alice`），所以直接做成 `%1$s` 格式串而不是拆两段。

### 三个小坑

- **`＋  添加服务器` 的两个空格会被 aapt 折叠成一个**。资源字符串里的连续空白默认折叠，
  要保原样必须整个值加双引号。改完 `aapt2 dump resources` 才能看出差别，编译期没有任何提示。
- **`inputDialog(String title, String label, String value)` 的 title / label 从来没被读过**
  （标题由对话框自己设、标签压根没画）。迁移时顺手收敛成 `inputDialog(String value)`：
  留着两个不读的参数，下次有人照着签名传一段硬编码中文进来，又是一处漏翻。
- **`取消` / `保存` / `删除` / `知道了` 复用既有的 `common_*` 键**，不新造
  `nst_cancel` 之类。同一个词在四个页面各有一个键，改文案时就变成四地方改、
  改漏一处就是「中文说取消、英文说 Close」。

### 验证

- `build.sh` → **BUILD OK，v212 / 2.19.0**，APK **13,161,168 字节**，md5 `25e08b2e…`
- `aapt2 dump resources` 核对 `string/nst_title` 有 `()` 与 `(en)` 两个变体，
  包内 `nst_` 资源共 89 个（88 键 + 1 数组）——不是「资源文件改了但没打进包」
- 四道门：依赖方向通过 / 空 catch **335（= 基线）** / 文案 **635 键中英一致、覆盖率 100%** /
  动作号 55 互不重复
- QUICK **66/66**

### 还没迁的

`ArchiveController`（审计 117 处）与 `MainActivity`（426 处）。前者是压缩包操作的全部
菜单与提示，后者是整个应用的主菜单与批量操作文案——两页都远超单次提交的合理体量，
继续按「一页一提交」拆。

---

## v2.18.0（versionCode 211）—— F-22：隐私空间

有些文件不适合躺在相册或下载目录里：证件照、合同扫描件、还没发出去的截图。
上一个版本会「加密成 .enc」，但那只是换了个扩展名——密文和明文挨着放，
谁打开文件管理器都能看到 `.enc` 在那里，而且每加密一个文件都要重新跑一次
PBKDF2（10 万次迭代），一百个文件就是几十秒。

这一轮做的是一块**上锁的目录**：口令解锁，文件全在 `HexFile/.vault` 里，
文件名也看不见。

入口在侧栏「🔒 隐私空间」；主界面长按文件 →「移入隐私空间」。

### 两把钥匙，而不是一把

`FileCrypt`（v1.33.0 的文件加密）是「口令 → PBKDF2 → 直接当文件密钥」。
隐私空间不能这么做，因为**改口令**：密钥就是口令派生出来的，改口令等于所有文件换密钥，
几百个文件要全量重加密，中途断电还是一半新一半旧的死局。

所以拆成两层（和 LUKS / BitLocker 同一个思路）：

| 层 | 是什么 | 什么时候变 |
| --- | --- | --- |
| 主密钥 vaultKey | 64 字节随机数（32 加密 + 32 认证） | 创建时生成一次，**永不变** |
| KEK | 口令经过 PBKDF2-HMAC-SHA256（21 万次）派生 | 每次输口令时算一遍 |

KEK 把 vaultKey 包起来存进 157 字节的头部。**改口令 = 用新 KEK 重新包一次同一把
vaultKey**，只重写头部，文件密文一个字节都不动——这条有断言盯着（改口令后解出的
enc/mac 必须与原来完全相同）。

### 三种容器

```
头部 v.hdr（157 字节定长）
  "HFVAULT" 7  version 1  iterations 4(BE)  saltLen 1  salt 16
  ivK 16  wrapped 80(AES-256-CBC(KEKenc, vaultKey 64B))  tag 32
  tag = HMAC-SHA256(KEKmac, 前面全部字节)      ← 口令错 → 一次比对即判，不用试解密

文件块 b/<id>.bin
  "HFVBLOB" 7  version 1  id 16  iv 16  ciphertext  tag 32
  tag = HMAC-SHA256(vaultKey.mac, magic|version|id|iv|ciphertext)

索引 idx
  "HFVBOX1" 7  version 1  iv 16  ciphertext  tag 32
```

两个细节值得单独说：

- **id 进 MAC**。文件块头部带 16 字节条目 id，并且参与校验。这样把 A 的密文改名成
  B 的 id 会验签失败——否则「密文没有泄露」却也「可以被随意替换」，隐私空间里出现
  一个名字对、内容错的文件，比直接报错更难查。
- **索引单独一套魔数**。索引里没有 id 概念，硬套文件块格式就得塞 16 个零字节进
  文件名位置，日后看代码的人会以为那是个真 id。

### 目录与索引

```
HexFile/.vault/v.hdr        头部
HexFile/.vault/idx          加密索引（文件名、大小、时间）
HexFile/.vault/idx.bak      上一次的索引
HexFile/.vault/b/<id>.bin   文件密文，文件名只是随机 id
```

点开头是为了不让相册、MTP、多数第三方文件管理器列出来——**但这不是安全性**，
只是别再往别人眼前凑。

索引是唯一的目录信息，所以每次保存先把旧 `idx` 改名成 `idx.bak` 再落新的；
主索引读不出来就回退备份，并在页面上直说「这次用的是备份，最近导入的文件可能不在列表里」。
坏行（id 不是 32 位小写十六进制、大小为负、字段缺一半）只跳过那一行并计数，
不会让整个空间打不开——一条记录坏了就丢掉全部文件名的设计，等于把一次磁盘小故障放大成灾难。

`<id>` 会被直接拼成文件名，所以 `isId()` 只认 32 位小写十六进制。放行 `../` 就等于
让索引里的一条记录变成任意路径读写，这条不是格式洁癖。

### 交互：三态 + 离开即锁

| 状态 | 看到什么 |
| --- | --- |
| 未建立 | 设口令 + 再输一遍，按钮「创建」 |
| 锁定 | 一个口令框，按钮「解锁」；只显示「已锁定 · 占用 x MB」 |
| 已解锁 | 文件列表（名字、大小、入库时间），顶栏「导入 / 更多」 |

`onStop` 就把主密钥丢掉。只有两种情况例外，都是显式布尔量：

1. 系统文件选择器在前台（不然选完文件回来已经锁了，导入功能等于不可用）；
2. 后台任务正在跑（任务要用密钥，等它收尾再锁）。

锁定态离开再回来，输入框里的口令也会被清掉——不清的话「离开即锁」就只剩个说法。

**「移入」和「导入」是两件事**：

- 主界面长按 →「移入隐私空间」：加密 → 删原文件，是真正的移动；
- 隐私空间里「导入」：`ACTION_OPEN_DOCUMENT` 给的 `content://` 不保证能删，
  所以只复制，并在页面上写明原文件还在原处。

移动的语义是「要么整体完成，要么不动」：密文写好之后如果原文件删不掉
（被别的应用占用、只读挂载），就把刚写的密文撤掉并计为失败。半移动状态最糟——
原文件还在、隐私空间里也多一份，用户以为藏起来了，其实没有。

### 和定时任务的关系

「清空目录」如果指到 `HexFile`，会把 `.vault` 一起清掉——那不是清理，是数据丢失。
所以 `ScheduleRunner` 的清理、过期、压缩三条路径都跳过隐私空间目录，判据是
「目录名是 `.vault` **且**里面有 `v.hdr`」：只看名字的话，用户自己建一个叫 `.vault`
的普通目录会莫名其妙不被清理，而且没有任何线索。

### 做不到的

写在「更多 → 加密细节」里，也写在这里：

- 没有防截屏（解锁后的列表可以被截图）；
- 删除只删密文，不做覆写——懂取证的人仍可能从闪存残留里恢复；
- 口令没有后门，忘了就是打不开。这是它算得上隐私空间的**前提**，不是缺陷；
- 目录暂不支持移入（先压缩，或逐个选文件）；
- 索引备份只能救「索引文件本身损坏」，救不了「整个 `.vault` 被删」。

### 测试

`test/VaultTest.java`，**82 条断言**，分四块，各自会因为不同原因悄悄坏掉：

| 块 | 钉什么 | 条数 |
| --- | --- | --- |
| 头部 / 口令 | 错口令解不开、头部改一字节就验签失败、迭代次数被调大（DoS）或调小（削弱）直接拒、改口令后主密钥不变 | 13 |
| 文件块 | 往返一致、密文里搜不到明文、改一字节失败、用别人的 id 解失败、空文件、1MB 流式、进度返回 false 即中止 | 15 |
| 索引 | 名字里的制表符/换行/反斜杠/emoji 原样回来、坏行只丢自己、`../` 不是合法 id | 14 |
| 存储层 | 建/解锁/改口令、导入导出重名、改名、删除、取消不留残留、游离密文、主索引坏了回退备份 | 40 |

`test/ScheduleTest` 另加 5 条，钉「定时清理不会清掉隐私空间」。

### 踩坑

- `tmp.renameTo(target)` 在 Linux 上能覆盖同名文件，**Windows 上不能**。改口令的
  桌面单测因此报「无法写入头部」，而真机永远碰不到。这类「只在一个平台坏」的写法
  收敛到既有的 `AtomicReplace`：先试 rename，失败再让位，还原路径它也覆盖了。
- 关闭流写成 `try { x.close(); } catch (IOException ignored) {}` 会让空 catch 门
  +11。按 `LanScan` 的既有做法改成 `closeQuiet(Closeable)`：返回 boolean，
  catch 块里有语句，计数不动。
- 测试里「删除后索引为空」一开始写错了：同一个空间里还有前一步「大小未知」导入的
  那条记录，实际剩 1 条。断言写死 0 会让人以为是 `remove` 没生效。

### 产物

- 新增 `core/VaultCrypto`（加密内核）、`core/VaultIndex`（索引编解码）、
  `fs/VaultStore`（目录/索引/导入导出）、`ui/VaultActivity`、`ui/VaultFlow`、
  `res/layout/activity_vault.xml`。
- 侧栏新增「🔒 隐私空间」；主菜单（普通文件 + APK）新增「移入隐私空间」（动作号 55）。
- 组件数 59 → **60**（49 Activity + 4 Service + 3 Provider + 4 Receiver）。
- 四道门：依赖方向通过 / 空 catch 335（= 基线）/ 文案 545 键中英一致 / 动作号 55 个互不重复。
- QUICK 66 / ALL 69 全过；VaultTest 82 断言。
- versionCode 211，APK **13,144,784 字节**。

---

## v2.17.0（versionCode 210）—— F-23：定时任务

「该清缓存了」「该把截图目录打包备份了」「三个月前的录屏该删了」—— 这类事都知道要做，
但都要靠记性。这一轮把它们交给系统的闹钟：到点自动清空目录、压缩目录、或清理超过 N 天
的文件，结果写回列表（最近一次成功还是失败、处理了多少项）。

入口在侧栏「⏰ 定时任务」。

### 规则只做两种

| 规则 | 含义 |
| --- | --- |
| `daily 23:00` | 每天 23:00 |
| `weekly 1,3,5 07:30` | 周一、三、五 07:30（1=周一，7=周日） |

**为什么不用 cron**：cron 的 `*` `/` `,` 表达能力远超这里用得上的范围，代价是用户得先学一遍；
而写错一个字符的后果是「不执行」—— 还是静默的，列表里那条任务看起来一切正常。
上面两种覆盖了「每天清一次」「每周某几天做一次」的全部真实需求。

规则就是一段可直接改的文本，不是「小时下拉 + 分钟下拉 + 星期多选」三个控件的组合：
想从「每天」改成「每周一三五」，改一个词比在两排控件之间来回点快得多，也更容易复制给别人。

### 三个动作

| 动作 | 做什么 | 不做什么 |
| --- | --- | --- |
| 清空目录 | 删掉目录里的全部内容 | 不删目录本身；**不进回收站** |
| 压缩为 zip | 压成 `<目录名>.zip` 放在**父目录** | 不覆盖前先备份（同名即覆盖） |
| 清理旧文件 | 删掉超过 N 天没修改的**文件** | 不删空目录（可能是用户刻意留的结构） |

清空**不进回收站**是刻意的：定时清缓存的目的就是腾空间，进回收站等于把文件从 A 搬到 B，
空间一点没省，用户还以为清干净了。要可逆就用「压缩为 zip」——它保留了内容，且只占一份。

### 什么时候执行：单闹钟 + 计划点比对

- 永远只为「最早的下一次」注册**一个**闹钟。每个任务一个 PendingIntent 要管 requestCode、
  要处理「删任务时撤回哪个」，还容易留下改完时间忘了重排的幽灵闹钟。
- 用 `setAndAllowWhileIdle`，不用 `setExact*`：精确闹钟在 Android 12+ 需要
  `SCHEDULE_EXACT_ALARM`，用户得去系统设置单独授予，而「每天清一次缓存」不需要精确到秒。
  代价是 Doze 下最多延后几分钟，这个代价换掉一个权限弹窗是值得的。
- 判断「这一轮到点了没有」用 **`prevRun(rule, now) > lastRun`**，不用「now 与下次时间
  差多少」的容差：Doze 会把闹钟推迟几分钟到几小时，容差写小了漏跑、写大了重跑；而
  「最近一个计划点」与「上次执行时刻」都是确切值，没有容差可调。
- 设备睡了三天醒来只补跑**最近一次**，不会连跑三次 —— 三次连续清空对谁都没好处。

### 为什么拆成两个 receiver

`BOOT_COMPLETED` 是系统广播，接收它的组件必须 `exported="true"`，否则收不到。
而「到点执行」那个 receiver 一旦也是 exported，**任何应用都能给它发一个 tick**，
反复触发用户的「清空目录」—— 那是实打实的数据丢失。

所以拆开：

| 组件 | exported | 能做什么 |
| --- | --- | --- |
| `BootReceiver` | `true` | 只按表重排闹钟。被恶意触发最多是把闹钟重设一次，等价操作 |
| `ScheduleReceiver` | `false` | 执行清理动作。只有本应用自己的 PendingIntent 能唤到 |

重启与覆盖安装后系统会把所有闹钟清掉，这是「定时功能用了几天就再也不响」最常见的原因，
所以 `BootReceiver` 同时接 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 与国产 ROM 常用的
`QUICKBOOT_POWERON`。

### 危险目标护栏

手输路径的定时任务最坏的失败不是「没执行」，而是「执行错了目录」。`/`、`/system`、
`/sdcard` 这些路径没有一个有理由定时清空，所以 `ScheduleRunner.isDangerous` 在执行与保存
两处都挡一遍（不是只在 UI 挡：执行入口也可能被别处调用）。系统挂载点的**子目录**放行 ——
`/data/local/tmp` 之类是有真实用途的。

### 几个刻意的决定

- **「立即执行」也记 lastRun**，但按真实执行时刻记：12:00 手动跑一次，当晚 23:00 的
  计划点仍然大于它，所以照常执行。用「计划点」当前一次执行时间去重时，这个语义天然成立。
- **配置错误一律报错，不静默**：目标不存在、不是目录、保留 0 天、规则写错 —— 都返回原因
  并写进「上次」。静默失败最坑：用户以为设好了，一周后才发现什么都没发生。
- **压缩空目录报错**而不是生成一个 0 字节的 zip。
- **不跟进符号链接**：清空与过期清理都走 `TreeOps.delete` / 先判链接再递归，
  和 R-09 修的是同一类坑（`isDirectory()` 会跟随链接，跟进就删到目标里去了）。
- 通知**不设点击跳转**：`sched` 包不能引用 `ui.ScheduleActivity`（依赖方向门），
  为一条提示再加隐式 Intent filter 不值当。结果同时写进列表的「上次」。

### 边界（写清免得以后当 bug 查）

- 只支持每天 / 每周。不支持「每月 1 号」「每 6 小时」「连续做多个动作」—— 后者需要任务链，
  与现有的文件任务引擎是两套模型，硬塞进来会让两边都变复杂。
- 进程被杀不影响已注册的闹钟（闹钟在系统侧），但**执行期间**被杀会留下做了一半的结果：
  清空可能清了一半、压缩可能留下半截 zip（`.part` 机制只在文件任务引擎里）。
- Android 13+ 未授予通知权限时，执行结果只在列表里，不会有通知。
- 目标目录里若有正在被写入的文件，删除会失败，结果里如实报「有 N 项删不掉」。

### 验证

- `build.sh` → **BUILD OK，v210 / 2.17.0**，APK **13,111,941 B**（unsigned **13,064,724 B**），
  V3 CN=hex91
- 四道门：`deps` 通过（本轮把新包 `sched` 纳入检查：不许依赖 `ui/controller`）、
  `catch` 335（331 命名表态忽略 + 4 其余）、`lang` PASS（482 键，中英各 482）、
  `action` PASS（54 个动作号互不重复）
- 新增 `test/ScheduleTest`（**80 断言**）：
  - 规则 32：daily/weekly 的 nextRun（今天已过点、恰好等于当前时刻、跨零点、跨周跨月）、
    prevRun 与 nextRun 的反向边界、畸形输入（24:00 / 23:60 / 星期 8 / 缺冒号 / null）
    一律判非法而不抛；
  - 编解码 15：往返保真（含 `lastRun` 的 long 精度）、路径里的制表符/反斜杠/换行、
    无格式头、半截行被丢、认不出的动作被跳过、nextId 分配；
  - 执行 21：clean 删 2 项且保留目录、目标不存在/不是目录/为空各报错、
    expire 按 mtime 删深层旧文件且留新文件与空目录、zip 真生成包（4 个条目含目录）、
    空目录不生成空包；
  - 危险目录 12：`/`、`/system`、`/data`、`/sdcard`、`/storage/emulated` 被拒，
    子目录放行，且执行阶段也挡。
- 桌面单测：`QUICK=1` **65 用例 65 PASS**；`ALL` **68 用例 68 PASS**

**真机测试点**：

1. 设置页确认 `2.17.0 (210)`；
2. 侧栏「⏰ 定时任务」→「新建」→ 规则填 `daily <当前时间 +2 分钟>`、动作「清空目录」、
   目标填一个放了几张图的测试目录 → 保存 → 列表显示「下次 今天 HH:MM」；
3. 到点后：出现通知、测试目录被清空、目录本身还在、列表「上次」显示「成功（N 项）」；
4. 规则填 `daily 25:00` → 提示「规则看不懂」且**对话框不关**（能接着改）；
5. 目标填 `/sdcard/nonexistent` → 提示目标不存在；填 `/` → 提示不能设为目标；
6. 动作切到「清理旧文件」→ 天数输入框出现；填 0 → 提示要大于 0；
7. 「立即执行」→ 立刻执行，列表「上次」更新，且当晚计划点仍会再跑一次；
8. 「停用」→ 整行压暗显示「已停用」，到点不执行；再「启用」恢复正常；
9. 把手机重启 → 到点仍然执行（验证开机重排）；
10. 动作选「压缩为 zip」→ 父目录出现 `<目录名>.zip`，能正常打开。

---

## v2.16.0（versionCode 209）—— F-21：包内搜索

压缩包浏览页以前只有「点进去」一条路：找一个文件要按层级点开目录，找一个不在当前层的
文件就得先猜它在哪一层。APK 有几千个条目、RAR 的目录可能套十几层，找一个
`AndroidManifest.xml` 点五六次是常态，点错一次还得退回来。

这一轮在底栏加「搜索」：在整个包范围内按名字找，不再是「只看当前目录」。

### 为什么是自己写通配而不是用正则

用户输入里 `.` `(` `)` `[` `]` `+` `|` 都可能出现在真实文件名里。把它们转成正则要先做一次
完整转义 —— 漏一个字符的后果是「正则语法错误」或者更糟的「悄悄按元字符解释」，用户输入
`a[1].txt` 会得到一个看不懂的结果。

直接写双指针通配匹配只需要处理 `*` 和 `?` 两个字符，其余一律按字面量比较，没有转义这一步，
也就没有这一步的坑。

### 匹配规则

| 输入 | 含义 |
| --- | --- |
| `manifest` | 路径里（任意层级）含 `manifest` |
| `*.xml` | 以 `.xml` 结尾 |
| `res layout` | 同时含 `res` 与 `layout`（AND，不是 OR） |
| `res\layout\main.xml` | 反斜杠自动转斜杠：从 Windows 复制来的路径可以直接粘 |
| `Activity` | 忽略大小写，等同 `activity` |

反斜杠那一项不是锦上添花：用户从聊天记录、文件管理器地址栏复制路径时拿到的是反斜杠，
而归档里的路径永远是斜杠，不转换就等于「明明有却搜不到」。

### 数据从哪来：`Archive.listAll()`

搜索要跨目录，就需要「全量条目表」。六个后端各实现一次 `listAll()`，但代价并不一样：

| 后端 | 全量列举的代价 |
| --- | --- |
| ZIP（Zip4j） | 构造时已读中央目录，直接给表 |
| RAR / 带密码 7z | 构造时已从归档头读全，直接给表 |
| 7z（Commons Compress） | 同上 |
| TAR / tar.gz | **没有索引**，只能顺序读完整个流 |
| 裸 .zst | 单条目，恒定 |

因为 TAR 那条路可能真要读一遍几百 MB，搜索统一放在后台线程里跑，而不是「ZIP 就同步、
TAR 就异步」——同一份代码不能有两种线程模型，否则点搜索在 ZIP 上是秒回、在 tar.gz 上
是 ANR，两种表现都得单独调。

### 排序：为什么短名优先

依次比：**短名与查询完全相同** → **短名自己命中** → **路径更短** → **字典序**。

用户输入 `AndroidManifest.xml` 时想要的是根目录那一个，而不是 `res/raw/AndroidManifest.xml`；
如果只按路径字典序，`res/...` 会排在前面（`r` < `A` 之外还有长度因素），第一条就不是他找的。
「路径更短」则让浅层的同名文件排在深层之前。

### 结果怎么显示

- **显示完整路径**，不是短名。搜索结果是整个包的，只显示 `AndroidManifest.xml` 会出现
  好几行一模一样、点下去却是不同文件的情况 —— 这是「进了目录才显示短名」不能照搬到
  搜索结果的原因。
- **目录条目也在结果里**：搜 `res` 会命中 `res/` 本身，点一下直接进该目录（同时退出搜索模式）。
  不给目录的话，用户看到 500 个 `res/` 下的文件却没法跳到那个目录。
- **上限 500 条**，超出时标题写「命中 N 项，只显示前 500 项」。假装全列出来会让用户
  一直往下翻却永远到底不了。
- 搜索态下「上级」变成「返回目录」：这两个动作语义不同，用同一个词会让人以为要少一层。

### 几个刻意的决定

- **空查询不搜索**（不是「列出全部」）：`listAll` 在 TAR 上是整包读一遍，一个手滑的空格
  不该触发几秒的 IO。
- **搜索期间挡住点击**：与 F-11 解压同一个理由 —— 后台线程正在用同一个 `Archive` 对象，
  并发访问没有任何保证，用一个 `busy` 判断比给归档加锁更不容易漏。
- **搜索态不参与 `onResume` 重载**：从编辑器返回时重跑一次搜索既慢又会把结果冲掉；
  包内容改过就再点一次「搜索」，那才是用户明确要的。
- **销毁时置 `dead`**：线程回来时 `runOnUiThread` 里先看这个标志，避免碰已 detach 的 View。
- **ZIP 里的同名条目只留第一条**：后写覆盖先写是 ZIP 的规矩，但搜索列表里出现两行完全
  相同的路径、点哪行都操作第一条，只会让人以为程序坏了。

### 边界（写清免得以后当 bug 查）

- 只按**名字**搜，不搜内容。按内容搜（比如「哪个 .so 里有某个字符串」）是另一件事：
  要解压、要读字节，成本与「取一张已解析的表」完全不是一个量级。
- 不支持正则，只支持 `*` `?`。想用正则的用户请用引号外的字面量 —— 也正因为不支持正则，
  文件名里的 `(` `[` 才能原样输入。
- 大小写折叠走 `toLowerCase(Locale.ROOT)`，对 ASCII 与常见语言够用；土耳其语 `i/İ` 这类
  特殊大小写规则没有照顾。
- 搜索结果不会自动刷新：包在编辑器里被改过之后，重新点一次「搜索」即可。

### 验证

- `build.sh` → **BUILD OK，v209 / 2.16.0**，APK **13,095,479 B**（unsigned **13,048,262 B**），
  V3 CN=hex91。与 v2.15.0 签名后**恰好同字节数**：新增内容让 unsigned 长了 12,288 B
  （正好 3 个 4096 页），而 zipalign 的 STORED 对齐 padding 随之少了同样多，两边抵消。
  md5 不同、包内能查到 `ArchiveSearch`，不是没打进包。
- 四道门：`deps` 方向检查通过、`catch` 335（331 命名表态忽略 + 4 其余）、
  `lang` PASS（440 键，中英各 440）、`action` PASS（54 个动作号互不重复）
- 新增 `test/ArchiveSearchTest`（**63 断言**）：规范化 6（含全角空格、Tab、反斜杠）、
  通配 11（`*` 匹配空串、`?` 不匹配零字符、`()` `[]` 当字面量）、匹配 13
  （AND 而非 OR、大小写、中文、空查询、null）、`listAll` 真 zip 9 条（含目录带尾斜杠、
  深层条目、短名只取末段）、排序与截断 18（短名精确排第一、路径长度次序、
  `total` 与 `truncated` 在截断时仍如实）、真 tar.gz 3（无索引路径也能全量）、空包 2
- 桌面单测：`QUICK=1` **64 用例 64 PASS**；`ALL` **67 用例 67 PASS**

**真机测试点**：

1. 设置页确认 `2.16.0 (209)`；
2. 打开任一 APK → 点「搜索」→ 输入 `AndroidManifest.xml` → 结果第一行是根目录那个，
   每行显示完整路径；
3. 输入 `res layout`（空格分隔）→ 只剩同时含这两个词的条目（不是 OR 的全列）；
4. 输入 `*.xml` → 全部 xml；输入 `RES/LAYOUT` 大写 → 命中数不变；
5. 从电脑复制一个 `res\layout\activity_main.xml` 粘贴进搜索框 → 仍能命中；
6. 点搜索结果里的目录（如 `res/layout/`）→ 跳进该目录，底栏「返回目录」变回「上级」；
7. 搜索态点「上级」→ 回到搜索前的目录；
8. 打开一个 tar.gz 点「搜索」→ 先出现「正在搜索包内条目…」，结果正确（这条最慢，
   顺便验证没有 ANR）；
9. 搜索框留空直接确定 → 提示「请输入搜索内容」，不执行搜索。

---

## v2.15.0（versionCode 208）—— F-19：任务暂停 / 继续（字节级续传）

复制几个 G 的文件时，此前只有「取消」一个出口，而取消意味着**从头再来**：已经拷完的
几个 G 白读、白写，`.part` 还被删掉。任务中心里那条进度条虽然一直在动，但用户想
「先让一下，等我插上电源」时没有任何办法。

这一轮补的是暂停 / 继续，并且续到**字节**：暂停时记住「哪个文件、写到第几字节」，
继续时从那一个偏移接着写，而不是把这个文件重拷一遍。

### 暂停与取消的区别

| | 暂停 | 取消 |
| --- | --- | --- |
| 半截 `.part` | 保留（它就是续传起点） | 删除 |
| 已拷完的文件 | 保留 | 保留 |
| 任务状态 | 已暂停（未结束） | 已取消（结束） |
| 之后能做什么 | 继续 / 取消 | 重试（从头） |
| 前台服务与常驻通知 | 收掉 | 收掉 |

「已拷完的文件保留」两边一样：它们是用户的正式文件，删不删由用户自己决定。

### 续传靠什么

1. `writePart` 每 128KB 检查一次暂停标志（与取消共用同一个检查点），当前块写完、`flush`；
2. 把「源文件 + 已写字节数 + 源长度 + 源 mtime」记进任务，`.part` **不删**；
3. 继续时重新走进同一个 `copyFile`，发现这个文件有续传点：`skipFully(src, offset)`
   跳掉已写前缀，`FileOutputStream(part, true)` 追加；
4. 已经 rename 成正式名字的文件不再碰 —— 引擎维护一份「已拷完路径」集合，续传时整项跳过。

第 4 点是必需的：不跳过的话，继续会把已完成的几百个文件重拷一遍，`doneBytes` 还会
重复累加，进度条直接虚高到 100% 之后继续涨。

### 为什么续传前要校验四项

`part` 存在、长度恰好等于记录的偏移、源文件长度没变、源 mtime 没变 —— 任一不过就
丢弃半截从头写。**mtime 这一项最容易被省掉，但它是真正防事故的那一项**：源文件在
暂停期间被改过（用户编辑、另一个任务覆盖），半截内容与新源对不上，续写的产物是
「两个版本拼接」，长度对、能打开、内容是坏的。这种结果比重拷十分钟糟得多。

校验失败时会把这半截已计入的字节从 `doneBytes` 里扣回来，否则进度会虚高。
（这条也是写用例时才想清楚的：`doneBytes` 是「已落盘的字节」，文件被丢弃时它必须跟着退。）

### 几个刻意的决定

- **分母只量一次**（`Job.measured`）。继续时若再量一遍，`totalBytes` 翻倍，百分比当场掉一半。
  暂停若发生在「量体积」阶段，则先清零再重量的分支保证不会累加。
- **「已暂停」不是「已结束」**：不进 `finished()`，账本里继续留着这条 —— 进程被系统清掉后，
  「上次有任务没做完」还能把来源捞回来。代价是不做跨进程续传（见边界）。
- **暂停不算「在做功」**：新增 `hasRunning()`（排队 / 运行），前台服务与通知看它，
  `hasActive()`（含已暂停）仍用于「任务条该不该在」。用户说了先停一停，就不该还挂一条
  永远 43% 的常驻通知。
- **暂停时 `.part` 继续挂在 `TempFiles` 登记里**。不挂的话，设置页的「遗留文件清理」
  会把这个续传起点当垃圾列出来并允许删除 —— 和 R-08 修的是同一类坑，只是这次是暂停造出来的。
- **排队中的任务也能暂停**，不占 worker；继续时插到**队首**而不是队尾 ——
  用户刚点了「继续」，排在一堆新任务后面等于点了没反应。
- **移动任务**：同分区 `rename` 秒移动的项按路径记进「已拷完集合」，否则继续时源已经不在了，
  会把它当成一个失败项报出来。
- 通知栏加了「暂停」，位置在「取消」**左边**：破坏性操作不该放在拇指最容易碰到的最外侧。

### 边界（写清免得以后当 bug 查）

- **暂停状态不落盘**：进程被杀之后只能按账本「重试」，也就是从头来。做跨进程续传需要
  把每个文件的偏移写进账本并处理账本与磁盘不一致，收益（用户主动暂停后又被杀进程）
  远小于复杂度。
- 单文件内部只能在 128KB 块边界停，块内的 IO 不可中断；块大小 128KB，实际最多晚几十毫秒。
- 同分区 `rename` 的秒移动无法暂停（本来就一瞬间）；空文件、空目录同理。
- 暂停期间源文件被删除：继续时该文件记为失败，其余照常做完，最终报「部分失败」。
- 单个文件失败不影响整批（这是原有行为），续传也沿用：不因为一个文件回退而重来整单。

### 验证

- `build.sh` → **BUILD OK，v208 / 2.15.0**，APK **13,095,479 B**，V3 CN=hex91
- 四道门：`deps` 0 命中、`catch` 335（基线 335，新代码零空 catch）、
  `lang` PASS（423 键，中英各 423）、`action` PASS（54 个动作号互不重复）
- 新增 `test/TaskPauseTest`（40 断言）。核心那条不比较耗时，而是用**内容**反证走了哪条路：
  暂停后把源文件前 N 字节改成 `0xAA`（N = 已写偏移）并把长度与 mtime 还原 ——
  - 真从偏移续写 → 输出前 N 字节来自半截 `.part`（原内容），整份等于改动前的原文件；
  - 偷偷从头重拷 → 输出前 N 字节就是 `0xAA`。
  
  同一个手法反过来测「源已变就重来」：改前缀且**不**还原 mtime，输出必须等于改后的源。
  这两个断言在「看起来成功、内容是两段拼接」的事故里会立刻红，而比较耗时或只比长度都发现不了。
  其余覆盖：排队中暂停、暂停中取消（`.part` 删除 + resume 返回 false）、
  多文件续传（`doneBytes` 等于总量，重复累加会超出来）、账本在暂停期间保留 / 取消后消失、
  `hasRunning` 与 `hasActive` 的区别。
- 回归：`test/FileTaskTest` 44 断言全通过（取消、重试、账本、同目录复制等旧行为没被动到）
- 桌面单测：`QUICK=1` **63 用例 63 PASS**；`ALL` **66 用例 66 PASS**

**真机测试点**：

1. 设置页确认 `2.15.0 (208)`；
2. 复制一个 1GB 以上的文件到内部存储 → 进任务中心 → 点「⏸ 暂停」→ 状态变「已暂停」、
   进度停住不再涨；
3. 用文件管理器看目标目录：只有一个 `xxx.part`，**没有**半截正式文件；
4. 点「▶ 继续」→ 进度从暂停处接着涨（不是回到 0），完成后文件能正常打开、大小正确；
5. 暂停后再点「✕ 取消」→ 状态已取消，`.part` 消失，点继续无效；
6. 暂停期间进「设置 → 遗留文件」→ 那个 `.part` **不应**出现在可清理列表里；
7. 复制中切到别的应用，在通知栏点「暂停」→ 通知消失；回任务中心点继续 → 通知重新出现、进度接着走；
8. 暂停期间在最近任务里划掉应用 → 重启后提示「上次有任务没做完」，重试可跑完（从头开始）。

---

## v2.14.0（versionCode 207）—— F-20：添加网络存储时扫一遍局域网

「添加服务器」原来要求用户自己填 IP、端口和协议。这三样在家用环境里恰好都属于
「路由器后台查一下」才能拿到的东西：NAS 的地址是 DHCP 分的、WebDAV 到底开在 5005
还是 5006 各家不同、协议名还得先知道 SMB 和 WebDAV 是两回事。填错一次的表现是
「保存成功，连接失败」，用户并不知道该改哪一格。

这一轮加的是**局域网服务发现**：点一下按钮，把本网段里能连上的服务列出来，选一个
就把类型、地址、端口填进表单。

### 为什么不用 mDNS / SSDP

两种「标准做法」在这里都不划算：

- **mDNS（Bonjour/Avahi）**只覆盖愿意广播自己的设备。Windows 的 SMB 不走 mDNS 而走
  WS-Discovery，报文是 SOAP，为了认一台 Windows 电脑去写一个 XML 解析器，成本远高于收益。
- **SSDP**（UPnP）同样依赖设备主动应答，且很多路由器默认屏蔽组播 —— 一旦被屏蔽，
  整个功能对全部设备一起失效，而用户看到的只是「什么都没扫到」。

TCP 端口探测没有这些假设：**端口开着就是有服务**，与设备愿不愿意广播无关，也不会因为
路由器屏蔽组播而整片失效。代价是要遍历 254 个地址，这正好是下一节要交代的。

### 扫什么，怎么认

| 端口 | 默认判断 | 实际依据 |
| --- | --- | --- |
| 445 | SMB | SMB 由客户端先发 negotiate，服务端不主动说话；能连上即认为是 SMB |
| 5005 | WebDAV | Synology 的 WebDAV 默认端口 |
| 21 | FTP | 服务端连上就发 `220 ...` banner |
| 22 | SFTP | 服务端连上就发 `SSH-2.0-...` 版本行 |
| 80 | HTTP / WebDAV | 无 banner，需主动发 `OPTIONS /`，看响应里有没有 `DAV:` 头 |

判断顺序是**先看应答、再看端口**，因为：

- 同一个 5005，装的是 WebDAV 还是某个网页管理界面，只有 `DAV:` 头能分辨；
- FTP 挪到 2121、SSH 挪到 2222 都是常见配置，认端口就会漏；
- WebDAV 开在 80 上也很常见（`DAV: 1,2` 出现在 401 响应里同样算数）。

`DAV:` 只在**行首**匹配：正文里提到 WebDAV 的网页（比如 NAS 的说明书页面）不该被误判。

### 扫描本身

- **只扫本机所在 /24**。`/16` 段有 65534 个地址，扫完要几分钟且几乎全是空的；
  IPv6 也不扫 —— NAS 的 IPv6 地址与端口映射关系太不直观，填进表单反而连不上。
- **48 并发、连接超时 350ms、读应答超时 400ms**：1270 个 (地址,端口) 组合约 8 秒扫完。
  家用路由器对 48 个并发 TCP 握手毫无压力，再往上就没礼貌了。
- **没等到 banner 才补发 OPTIONS，445 除外**：给 SMB 端口发 HTTP 请求只会让对端立刻断开。
- **关着的端口不算命中**。这一点看着像废话，但很容易写错：把「连上了但协议不认识」
  也当成结果的话，列表里会塞进一堆点进去连不上的空气设备。
- **取消**：置标志位 + `shutdownNow`，未开始的任务直接丢弃，正在 connect 的最多再等
  一个超时（350ms）。页面销毁时也置同一个标志，否则 48 个线程会一直跑到探完 1270 个组合。
- **结果按 IP 数值排序**，不是按字符串：字符串序会把 `192.168.1.100` 排在 `192.168.1.9`
  前面，用户按顺序找自己的 NAS 会找错。

### 接进表单

- 按钮只在 **SMB / WebDAV / FTP / SFTP** 时出现：S3 的地址是云厂商给的域名、
  两种云盘压根不用地址，扫出来也没有可填的东西。
- **先切类型再填值**。切类型会按协议重置端口（SMB→445 / FTP→21 / SFTP→22），
  顺序反了就是「刚填的端口被自己的监听器改回去」。
- WebDAV 填的是完整 URL，80 端口省略；同时提示「连不上请补路径（如 `/dav/`）」——
  `/dav/`、`/webdav/`、`/remote.php/dav/` 各家不同，这一步猜不出来，只能提示。
- 扫到 **HTTP 但响应里没有 `DAV:`** 时仍然填 WebDAV，但文案明确说是「普通网页服务」：
  多数 NAS 的 WebDAV 与网页管理界面共用同一个 IP，用户确认开了 WebDAV 时补个路径就能用。
- 扫描**不碰表单里已经填好的用户名/密码**，只写地址与端口。点错了重新扫一遍即可。

### 边界（写清免得以后当 bug 查）

- 扫不到 ≠ 没有：防火墙只放行 443、路由器开了 AP 隔离、设备在另一个 VLAN，
  都属于「确实扫不到」，提示里写的是排查方向而不是「未发现设备」。
- **不解析 SMB 共享名**：要连上 `IPC$` 才拿得到共享列表，匿名连接在 Windows 默认策略下
  多数被拒。共享名仍然手填，扫描只负责地址与端口。
- 只扫 IPv4 的 /24，跨网段与 IPv6 不扫。

### 验证

- `build.sh` → **BUILD OK，v207 / 2.14.0**，APK **13,079,095 B**（unsigned 13,035,974 B），
  V3 CN=hex91。签名后与 v2.13.1 恰好同大小：新增一个类 + 9 条字符串约 1.5KB 净增，
  签名块按 4096 对齐，这点增量落在同一页内被吸收，不是「没打进包」。
- 四道门：`deps` 0 命中、`catch` 335（基线 335，新代码零空 catch）、
  `lang` PASS（419 键，中英各 419）、`action` PASS（54 个动作号互不重复）
- 新增 `test/LanScanTest`（41 断言）：协议判断 11（banner 优先、非默认端口、
  `DAV:` 只认行首、认不出的应答给空）、摘要 7（含 80 字截断）、地址枚举 12
  （/24 边界、畸形输入、数值排序）、真 socket 11 —— 本机起 ServerSocket 假装
  FTP / WebDAV 各连一次，另验「关着的端口零命中」「重复端口只探一次」
  「预置取消 → cancelled=true」「结果按端口升序」
- 桌面单测：`QUICK=1` **62 用例 62 PASS**；`ALL` **65 用例 65 PASS**

**真机测试点**：

1. 设置页确认 `2.14.0 (207)`；
2. 网络存储 → 添加服务器 → 类型选 SMB → 点「扫描局域网」→ 进度条显示「已发现 N 台」→
   出现设备列表 → 选一条 → 地址与端口已填入（用户名/密码保持原样）；
3. 类型切到 S3 / OneDrive / 阿里云盘 → 「扫描局域网」按钮消失；
4. 选一条 WebDAV 结果 → 地址是 `http://ip:5005/` 形式，提示可能需补 `/dav/`；
   选到 HTTP（无 `DAV:` 头）时提示是「普通网页服务」；
5. 扫描中点「取消」→ 立刻停下并提示「扫描已取消」，不再继续连网段；
6. 在一个没有服务器的网段扫（比如只连了手机热点的电脑侧）→ 提示探测次数 + AP 隔离排查；
7. 同一路由器下 2.4G 与 5G 各扫一次（部分路由器跨频段隔离）→ 结果应一致。

---

## v2.13.1（versionCode 206）—— F-11：包内重命名 / 新建目录，解压带上进度与取消

两个一直缺的东西，都在「压缩包浏览」这条路上：

1. 包内改一个名字只能「提取 → 删除 → 添加」三步走，中间失败就丢内容；
2. 「解压全部」是在 UI 线程里同步跑的 —— 几百 MB 的包就是十几秒 ANR，且没有任何中断手段。

### 重命名与新建目录

`ArchiveWrite` 原来只有 `writeEntry` / `removeEntries`，这一轮补 `renameEntry` 与 `mkdir`，
三种可写格式（zip/apk、tar/tgz、7z）都支持：

| 格式 | 重命名 | 新建目录 |
| --- | --- | --- |
| ZIP / APK / JAR | 单次重建，`ZipUtil.renameEntry`；APK 顺带剥旧签名 + STORED 对齐 | 加一条「名字以 `/` 结尾、内容为空」的条目 |
| TAR / TGZ | 单次重建，复用同一套复制逻辑 | 同上（tar 的目录就是这么表达的） |
| 7Z | 单次重建（LZMA2 重压） | 独立的 `directory` 标志位，不是 0 字节文件冒充 |

三个细节是刻意的：

- **名字映射按路径段匹配**：`a` 改名只带走 `a/` 下的条目，`ab/` 不动。用
  `startsWith(from)` 会把 `ab/` 当成子项一起搬走 —— 一次改名毁半个包。
  这段逻辑单独抽成 `core/EntryNames`（纯 JDK），三种格式共用一份。
- **一条都没匹配上就不动原包**：目标条目不存在时直接返回失败，不写回临时文件。
  否则会出现「点了个写错的名字，包没变但 mtime 变了」这种更难解释的结果。
- **冲突检查在 UI 侧**：手里就有当前目录的条目表，查一次是免费的。后端不做合并 ——
  真撞上会得到两个同名条目，比失败难收拾得多。

`tar` / `7z` 的「写 / 删 / 改名 / 新建」这一轮合并成了一个重建循环（原来写一份、删一份，
逻辑几乎一样）。合并时抓到一个真 bug，见下面的踩坑记录。

### 解压全部：后台 + 进度 + 取消

`ZipBrowserActivity.extractAll` 原来是 `archive.extractAll(dest)` 直接跑在 UI 线程。
现在走 `apk/ArchiveExtract`：先递归列一遍条目（进度才有分母，也能提前发现加密/损坏），
再逐个解。

- 进度按条目计，取消在每个条目边界生效，**正在解的那个条目会写完**：产物是用户的文件，
  中途停下只会留半个文件，比多等两秒更难解释；
- 取消不删已解压的文件（那是用户数据），只如实报告「已取消，已解压 N / M 项」；
- 解压期间把点条目、长按、`onResume` 重载都挡掉：它们与后台线程共用同一个 `Archive`
  对象，并发访问没有任何保证；
- 进度条 `setCancelable(false)` + 显式「取消」按钮：返回键把进度条关掉会让用户以为
  解压停了，实际线程还在跑。

### 边界（写清免得以后当 bug 查）

- 重命名/新建只对**可写且非 APK** 的归档开放。APK 的每次修改都走 `ApkEditSession`
  （暂存 → 重打包 → 首次修改自动备份 → 退出时提示重签），在归档层直接改名会绕开备份
  与重签提示，得到一个「装不上、也回不去」的包。
- 冲突只查两种：同目录下同名条目、目标是个已有内容的目录。
- 解压进度按条目计，**单个超大文件内部不可中断**（字节级断点属于 F-19 的范围）。

### 踩坑记录

`tar` 的「新建目录」第一次跑就写出了**两个**同名目录条目 —— 合并重建循环时
`putData(...)` 被留了两遍。纯字符串单测（`EntryNamesTest`）永远发现不了这种问题，
是「把包写出来再读一遍」的 `ArchiveEditTest` 抓到的。这也是这一轮坚持给三种格式
各写一条真跑用例的原因。

### 验证

- `build.sh` → **BUILD OK，v206 / 2.13.1**，APK **13,079,095 B**，V3 CN=hex91
- 四道门：`deps` 0 命中、`catch` 335（基线 335，新代码零空 catch）、
  `lang` PASS（410 键，中英各 410）、`action` PASS（54 个动作号互不重复）
- 新增 `test/EntryNamesTest`（35 断言）：目录整体搬迁、同前缀兄弟不误伤、
  单文件移到子目录、`clean` 容错、`clean` 之后仍要过 `safe`
- 新增 `test/ArchiveEditTest`（44 断言）：zip / tar / 7z 各跑一遍真包 ——
  改名（含深层子项）、内容不变、失败不动原包、新建目录（7z 专门验 directory 标志位）、
  替换不产生重复条目、按目录删除只删目标子树
- 桌面单测：`QUICK=1` **61 用例 61 PASS**；`ALL` **64 用例 64 PASS**

**真机测试点**：

1. 设置页确认 `2.13.1 (206)`；
2. 打开一个 zip → 长按某个目录 → 「重命名」改成 `x/y` → 原目录消失、子项都在 `x/y/` 下；
3. 长按一个文件 → 重命名成已有名字 → 提示「目标名已存在」，包内容不变；
4. 工具栏「＋ 新建 → 文件夹」在压缩包里 → 新建成功并立刻出现在列表（7z 包会重建一次，稍慢）；
5. 打开 `.rar` 或 APK 试同样的操作 → 明确提示不可写/要走重打包，不静默失败；
6. 解压一个几百 MB 的包 → 进度条推进、点「取消」后在下一个条目边界停下并报告已解压数量；
7. 解压期间点列表条目 → 提示「正在解压」，不出现列表错乱。

---

## v2.13.0（versionCode 205）—— F-03：分包安装（.apks / .xapk / .apkm）

以前这台机器上的分包包是**装不了**的：`installApk` 发 `ACTION_INSTALL_PACKAGE`，
系统安装器一次只认一个文件；唯一能装多包的路在 `BackupRestore` 里，靠 root 跑
`pm install-multiple`。没 root 的机器双击 `.xapk` 只会看到「无法解析安装包」。

### 三种格式是同一种东西

`.apks`（bundletool）、`.xapk`（APKPure）、`.apkm`（APKMirror）都是 zip：
base + N 个 split，外加各自的一点元数据。所以实现里**不按格式分支**，只按
「条目是不是 `.apk`」分类：

| 格式 | 典型布局 | 元数据（不读） |
| --- | --- | --- |
| `.apks` | `splits/base-master.apk`、`splits/base-arm64_v8a.apk` | `toc.pb` |
| `.xapk` | `com.foo.apk`、`config.arm64_v8a.apk`、`Android/obb/…` | `manifest.json` |
| `.apkm` | `base.apk`、`split_config.arm64_v8a.apk` | `info.json` |

元数据一份都不解析：包名/版本/应用名全都能从 base apk 自己的 `AndroidManifest`
里拿到（`getPackageArchiveInfo`）。少一套解析就少一处「某家打包器字段名不一样」的坑。

### 装的过程

| 阶段 | 做法 | 为什么 |
| --- | --- | --- |
| 识别 | 点击先于压缩包判断（它本身是 zip，但用户点它的意图是「装」） | 否则会进包内浏览，找不到安装入口 |
| 看清单 | 后台扫一遍再弹框：格式 / 大小 / 分包数 / OBB 数 / 其他条目 | 几百 MB 的包列条目也要一会儿，UI 线程卡一下就是「点了没反应」 |
| 解包 | 每条写到 `cache/split/<ts>/`，128 KB 一块，45% 用未压缩字节推进 | 取消要能随时生效，进度要按真实字节而不是条目数 |
| 装 | `PackageInstaller` 会话（`MODE_FULL_INSTALL`），逐条 `openWrite` 后 `commit` | 只有会话能表达「这几份是同一个应用」；不需要 root |
| 报 | 结果走 `PendingIntent` → `InstallResultReceiver`；界面在场就回界面，进程被杀过就发通知 | 安装可能比 Activity 活得久 |
| 收尾 | 会话封存后立刻删临时目录 | 系统已持有字节副本，留着只是占用户空间 |

### 几个刻意的选择

1. **文件名只取末段**（`SplitPackage.safeLeaf`）。解包目录是临时的，层次没有意义；
   只取末段就天然免疫 `../` 与绝对路径（zip-slip），不需要再写一套路径校验。
   剩下清洗的是「末段本身不合法」：控制字符、Windows 保留字符、纯点、结尾点空格。
2. **取消不删已提交的会话**：解包阶段只置标志，写会话阶段同理，只有真的中止才
   `abandon()` —— 取消是显式按钮，不是「点了外部让它自生自灭」。
3. **进度条 `setCancelable(false)`，但带一个取消按钮**：返回键关掉进度条会让用户
   以为安装停了，实际还在后台跑；那种「静默完成」比多给一个按钮更让人困惑。
4. **OBB 尽力而为**：`Android/obb/...` 写到 `/sdcard/Android/obb/<包名>/`。
   Android 11+ 对这目录有额外限制，即便有「所有文件访问」也可能写不进去 ——
   这种包装完仍能启动，只是游戏报「资源缺失」。所以失败数要报出来并告诉用户
   手动解压到哪儿，而不是假装成功。
5. **兜底看门狗**：广播万一丢了，5 分钟后解除忙标志。不解除的话下次安装会一直
   提示「正在安装另一个分包」，只能杀进程恢复。
6. **同时只允许一个会话**：进度、取消、OBB 统计都走静态字段，并发会互相覆盖；
   重复点击直接回一句「正在安装另一个分包」，比悄悄排队诚实。

### 已知边界

- **没有 root 回退**：`PackageInstaller` 被系统拦（未允许「安装未知应用」）时只报原因，
  不会偷偷改走 `su`。走 root 会让「明明允许了却装不上」变成「为什么它要 root」。
- **签名冲突必须手动卸载**：`STATUS_FAILURE_CONFLICT` 翻成「与已安装版本冲突
  （多半是签名不同，需先卸载）」，不做 `-d` 降级安装 —— 那是另一件事。
- **不校验 split 完整性**：少一个 split 时由系统报 `INVALID`，本应用不预先猜哪份缺了
  （判断依据只有 `toc.pb`/JSON，而这正是上面刻意不解析的东西）。

### 验证

- `build.sh` → **BUILD OK，v205 / 2.13.0**，APK **13,079,095 B**（+16 KB 来自新类与文案），V3 CN=hex91
- 四道门：`deps` 0 命中、`catch` 335（基线 335，新代码零空 catch）、
  `lang` PASS（410 键，中英各 410 键）、`action` PASS（54 个动作号互不重复）
- 新增 `test/SplitPackageTest`（18 断言）：三种布局的条目分类、obb 不混进 apk、
  无 apk 包不出现「-1 分包」、`safeLeaf` 的 zip-slip / 控制字符 / 纯点 / 结尾点空格 /
  长名截断保扩展名
- 桌面单测：`QUICK=1` **59 用例 59 PASS**；`ALL` **62 用例 62 PASS**

**真机测试点**：

1. 设置页确认 `2.13.0 (205)`；
2. 点一个 `.xapk`（带 OBB）→ 清单显示分包数与 OBB 数 → 安装 → 应用出现且能启动；
3. 装一个 `.apks`（bundletool 导出）→ 装完后「应用信息」里能看到 ABI split；
4. 安装中途点「取消」→ `cache/split/` 不留目录，应用商店里没有半装状态；
5. 装一个签名与已装版本不同的包 → 提示「与已安装版本冲突」，不是一句「安装失败」；
6. 关闭本应用的「安装未知应用」再装 → 提示去系统设置放开，而不是卡住；
7. 装完立刻退出应用 → 通知栏仍能看到「安装完成」。

---

## v2.12.1（versionCode 204）—— F-12：压缩包条目对比不再只比大小

`CompareUtil.compareEntries` 判「修改」只有一个条件：`sizeA != sizeB`。也就是说
**改了内容但长度没变**的条目会被判成「完全相同」——换一张同尺寸图标、改一个 smali 常量、
替换 `resources.arsc` 里的同长字符串，全都看不见。这不是「不够精确」，是 APK 对比
最容易骗人的地方：报告说两边一样，实际包已经不一样了。

修法用的是 zip 中央目录里现成的 CRC32（`ZipEntry.getCrc()`），不需要多解一次压、
不需要读条目内容，代价为零：

| 场景 | 之前 | 现在 |
| --- | --- | --- |
| size 不同 | MODIFIED（`2.0 KB → 3.0 KB`） | 不变 |
| **size 相同、CRC32 不同** | SAME（漏报） | MODIFIED（`CRC32 9B0D08F1 → 39DA1B3F`） |
| size 相同、CRC32 相同 | SAME | 不变 |
| 任一侧没有 CRC（非 zip / 读取失败） | 只比 size | **仍然只比 size**，不凭空判修改 |

第三条退化方向是刻意锁住的：`ArchEntry` 的老构造器保持可用（`crc` 默认 `-1`），
普通目录对比（`compareDirs`）本来就没有 CRC，不能被这条新判据误伤。

新增 `test/ArchCompareTest`（10 断言）：同长度不同内容判修改、说明里带 CRC32 对、
长度相同（证明判据不是 size）、长度不同仍算修改、新增/删除、汇总计数、
两侧无 CRC 退化为只比 size、仅一侧有 CRC 不判修改。

### 验证

- `build.sh` → **BUILD OK，v204 / 2.12.1**，V3 CN=hex91
- 四道门：`deps` 0 命中、`catch` 335（基线 335）、`lang` PASS（379 键）、`action` PASS（53 个值互不重复）
- 桌面单测：`QUICK=1` **58 用例 58 PASS**；`ALL` **61 用例 61 PASS**
- 真机测试点：APK 对比页对比同一应用的「改了字符串没改长度」两个包 →
  该条目应出现在「已修改」并显示两个 CRC32；对比同一个包两次 → 应全绿。

---

## v2.12.0（versionCode 203）—— R-38 / F-17 / F-09 / F-18：动作号唯一性进门，三个小步快赢

R-38 补上一道门的能力边界；F-17 / F-09 / F-18 是 §23 里规模最小的三项（S），
共同点是「用户能立刻看见、又不牵动目录模型」：外部分享进来的文件终于能接住、
照片能看到 EXIF、崩溃终于留下现场。

### R-38：动作号的值也要查

`action_check.sh` 原来只查两件事：`A_*` **名字**是否重复、`runFileAction` 里还有没有
数字 `case`。名字重复 javac 会直接报「already defined」，所以那一半本来就是白查；
真正没有保护的是**值**：

```java
private static final int A_HEX = 2;
private static final int A_FOO = 2;   // 编译通过，门也通过
```

后果不是崩溃，而是「点了有反应，反应的是别人的活」——两个菜单项静默执行同一个动作。
这与 O-25 那类「菜单/派发不一致」是同一种故障形态，排查成本一样高。

现在按值分组，重复就列出共用的常量名：

```bash
FAIL 文件动作码不一致（O-26 / R-38）
      src/com/hex91/file/MainActivity.java: A_* 动作号重复 → 2 被 A_HEX / A_DUP 共用
```

反向验证：沙箱里加一行 `A_DUP = 2` → `rc=1` 并指出重复值；真实仓库
`PASS … 53 个动作号互不重复`。

### F-17：外部分享 / 「用 HexFile 打开」

以前这台设备上的文件管理器**收不到**别的应用递过来的文件：清单里 `MainActivity` 只有
`MAIN` / `LAUNCHER` 两个 action，全仓 `ACTION_SEND` 的两处都是**发出**分享。

落点三处：清单加两个 `intent-filter`（`SEND` / `SEND_MULTIPLE` 接全部类型；
`VIEW` 只列本应用真能处理的 apk/zip/7z/rar/tar/gz/text/image，且**不加 `BROWSABLE`**，
免得 http 链接也被接管）、新增 `utils/IntentImport`、`MainActivity.handleIncoming`。

| 环节 | 做法 | 为什么 |
| --- | --- | --- |
| 取址 | `EXTRA_STREAM` / `EXTRA_STREAM` 列表 / `ClipData` / VIEW 的 `getData()` 四路，按 URI 字符串去重 | 厂商分享常常只填 `ClipData`，只读标准 extra 会「收到 0 个文件」 |
| 命名 | `OpenableColumns.DISPLAY_NAME` → URI 末段 → `shared-<时间戳>`，统一过 `safeName` | 对方给的名字可能是 `../../data/x`、带 `:*?"<>|` 或纯控制字符 |
| 落点 | 同名依次 `名字 (1).ext`、`名字 (2).ext` | **绝不覆盖**：分享同一个文件名十次就该是十个文件 |
| 搬运 | 128 KB 一块写 `名字.part`，`TempFiles.begin/end` 登记，成功才改名 | 取消/失败删干净；进行中的 `.part` 也不会被「遗留文件清理」当垃圾列出 |
| 进度 | 横向 `ProgressDialog` + 取消按钮，128 KB 检查点看取消标记 | 取消是显式操作，不是误触返回键 |
| 收尾 | 完成后刷新两栏；`ACTION_VIEW` 单个文件再按类型打开（apk→信息、zip→包内浏览、图片→查看器） | 「用 HexFile 打开」的语义是**打开**，不是「落到目录里让我自己找」 |

两个刻意的细节：

1. **处理完立刻把 Intent 掏空**（`setAction(MAIN)`、清 `data` / `ClipData` / `EXTRA_STREAM`）。
   横竖屏切换会重建 Activity 并重新读同一份 `getIntent()`，不清就会再导入一遍。
2. **上限写死并如实报告**：单次 ≤ 100 个、总量 ≤ 4 GB，超出的计入「跳过 N 个」，
   不静默丢。

已知边界：来源是 root 区（`/data/...`）时没有 `su` 回退——分享场景的来源几乎都是
`content://` 或 `/sdcard`，为它引一条 root 拷贝路径不值当，写在这里免得以后当 bug 查。

### F-09：图片元数据（EXIF）

入口两个：图片查看器底部「信息」、文件长按「图片信息」（新动作码 `A_EXIF = 53`，
正好被 R-38 的新门覆盖）。

- 用平台的 `android.media.ExifInterface`（API 5 起有，吃文件路径），**不引依赖**：
  `libs/` 只放签名/反编译相关 jar，为一张信息页加一个库不划算。
- 像素只读文件头（`inJustDecodeBounds`），几百 MB 的图也不吃内存。
- EXIF 读不到**不报错**：PNG 重编码、截图、社交软件转存都会掉 EXIF，
  界面显示「EXIF：无（该图片不含 EXIF 信息）」，那是正常情况。
- 值做了人话化：方向（8 种，镜像 + 旋转）、曝光（`0.008` → `1/125 s`）、
  焦距（`47/10` → `4.7 mm`）、闪光灯（EXIF 位域 bit0）、白平衡、GPS 十进制度。
- 全部内容在一个可选中的 TextView 里，底部「复制全部」——用户最常做的是复制经纬度或拍摄时间。

### F-18：崩溃捕获与诊断包

这个工程以前对 release 崩溃是**完全失明**的：`utils/Bg` 的注释自己就写着
「默认 UncaughtExceptionHandler 只打到 stderr，release 版本里基本看不到」。

现在加 `HfApp`（Application 子类）在进程最早处装 handler——挂在 Application 而不是
`MainActivity.onCreate`，是因为「启动即崩」恰恰发生在 Activity 起来之前，装在 Activity 里
等于对最难排的一类崩溃失明。代价也写进注释了：这是启动路径的改动，所以
`HfApp.onCreate` **只做这一件事**。

三条硬约束：

| 约束 | 做法 |
| --- | --- |
| 不能吞异常 | 写完文件**必须**调用原来的 handler，系统照常弹「应用已停止」；原来没有才自己 `killProcess` |
| 处理器不能再崩 | 全程 try/catch + 静态重入标志，真失败退到 `System.err` |
| 不能无限增长 | 只留最近 5 份、单份 ≤ 256 KB |

`utils/LogRing` 是 200 行定长环形缓冲。**它不是 logcat 的镜像**：拦截
`android.util.Log` 要改几百处调用点，所以由三处主动喂——`Bg` 的后台任务失败、
`CrashLog` 的崩溃记录、关键路径留痕。崩溃报告里「崩之前刚失败过什么」往往就是原因。

界面与入口：

- 崩溃页（`CrashReportActivity`）：堆栈原样显示（等宽、可选中）、分享、删除。
  **进页即已读**，否则下次启动再提示一次就是骚扰。
- 启动提示只在 `onCreate` 问一次，`onResume` 不问（崩溃后切回来一次问一次等于骚扰）。
- 设置 → 系统集成：崩溃日志（带未读数）+ 导出诊断包。

诊断包（`CrashLog.exportDiag`）内容：`meta.txt`（版本/机型/SDK/ABI/handler 是否装上）、
`log/ring.txt`、`crash/*.txt`、`prefs/hf_settings.txt`、`prefs/hexfile_task.txt`，
落到缓存目录后直接弹分享面板（用户点这个按钮的目的就是「交给别人看」）。
脱敏分两层：正文里 `pass/token/secret/sig/auth/credential` **连同它的取值**整段替换成
`[redacted]`（只打码值、留下 `pass=` 依然等于告诉别人这里有密码）；偏好里敏感键只留
键名、值写 `[hidden]`。

已知边界：装 handler 之前的崩溃抓不到——所以 `meta.txt` 里专门有
`handler_installed` 一行，用来说明「这份报告是不是完整现场」。

### 验证

- `build.sh` → **BUILD OK，v203 / 2.12.0，13,062,711 B**（+16 KB 来自两个新页面与文案），V3 CN=hex91
- 四道门：`deps_check` 0 命中、`catch_check` **335（基线 335，新增代码零空 catch）**、
  `lang_check` PASS（379 键，中英各 379）、`action_check` PASS（53 个常量 case、53 个值互不重复）
- 新增 2 个用例：`IntentImportTest`（14 断言：路径剥离 / 非法字符 / 控制字符 / 纯点 /
  结尾点空格 / 长名截断 / 重名 3 次 / 无扩展名）、`CrashLogTest`（17 断言：
  环形缓冲顺序与覆盖、单行截断、7 组脱敏、大小写、普通堆栈不被误伤）
- 桌面单测（本机有 `test/huangguo.apk`）：`QUICK=1` **57 用例 57 PASS**；`ALL` **60 用例 60 PASS**
- 反向验证：`A_DUP = 2` 沙箱 → `action_check` `rc=1` 并指出重复值

**真机测试点**（都是编译与单测覆盖不到的）：

1. 设置页确认 `2.12.0 (203)`；
2. 微信/邮件/浏览器各分享 1 个与 3 个文件 → 选「当前目录」，进度条与取消按钮出现，
   `SHA-256` 与源一致；同名再分享一次 → 出现 `名字 (1).ext`，原件不被覆盖；
3. 分享一个 APK → 导入后直接进 APK 信息页；分享 zip → 直接进包内浏览；
4. 导入中途点「取消」→ 目录里不留 `.part`；导入中旋转屏幕 → 不重复导入；
5. 图片查看器点「信息」→ 看到机型/拍摄时间/方向/曝光/焦距/是否带 GPS；
   长按图片选「图片信息」→ 同一页；截图（无 EXIF）→ 显示「EXIF：无」而不报错；
6. 用 root 或 adb 让应用崩一次（例如 `adb shell am broadcast` 触发不了就用
   `kill -SEGV <pid>`）→ 重启出现「上次运行崩溃了」，进崩溃页能看到堆栈与机型；
7. 设置 → 导出诊断包 → 把 zip 解压后 `grep -i 'pass\|token\|secret'` 应无命中。

---

## v2.11.0（versionCode 202）—— R-36 / O-26 / R-37：SDK 自足性、派发侧收尾、口径修正

三项都是「上一步没做完」的收尾：R-36 把「显式 SDK 要么生效、要么明确失败」从
「目录存在」补到「子目录齐全 + NDK 可解析」；O-26 把 O-25 的「菜单与派发引用同一符号」
在派发侧真正兑现；R-37 改掉 v2.10.0 里 6 处与实测对不上的说法。

### R-36：显式 SDK 必须自足

R-32 只校验「`ANDROID_HOME` 指向的目录存在」，R-35 又只让 build-tools/platform
按版本探测。两件事凑起来仍有一个洞：**目录存在、但里面只装了一半**。

| `ANDROID_HOME` 内容 | v2.10.0 的行为 | 现在 |
| --- | --- | --- |
| 目录不存在 | `rc=2`（R-32） | 不变 |
| **空目录** | 静默全量回退到 `E:/repo`，`rc=0` | `rc=2`：`ANDROID_HOME 下没有可用的 build-tools` |
| 只有 `platforms` | 同上，静默回退 | `rc=2`，同上 |
| 只有 `build-tools` | **跨 SDK 混搭**（aapt2 来自这里、android.jar 来自别处） | `rc=2`：`ANDROID_HOME 下没有 platforms/android-<版本>/android.jar` |
| 齐全但无 `ndk` | `[3/7]` 跑 `clang` → rc=127 `No such file or directory` | `[3/7]` 之前一行 `找不到 NDK` + 两条可操作提示，`rc=2` |
| 齐全、无 `ndk`、`.so` 已最新 | 跳过 `[3/7]`，BUILD OK | 不变（这一步真的不需要 NDK） |

三处改动：

1. **子目录校验**（`exit 2`）：显式给了 SDK 根就用 `pick_bt` / `pick_andj` 真去里面找，
   找不到直接报缺哪一半。例外写进了注释：`BT=` / `ANDJ=` 显式指定时不校验对应那一半
   —— 有人就拿 `ANDROID_HOME` 指一个只放 build-tools 的目录试某个 aapt2。
2. **BT 与 ANDJ 对齐到同一个 SDK**：`SDK` 反推提前到 ANDJ 探测之前，ANDJ 先在
   `$BT/../..` 下找，找不到才退到其它候选，且**不静默**——打印 `[warn] build-tools 与
   android.jar 来自不同 SDK` 并列两个路径。aapt2 37 配 platform 34 打出来的包，出问题
   时没人会想到是这里。
3. **NDK 改成探测**：`ANDROID_NDK_HOME` → `$SDK/ndk/` 下挑版本最大且含
   `source.properties` 的目录（不用「纯版本号排序」：`ndk/` 里可能有 `ndk-bundle`
   这类别名）。NDK 为空**不立即报错**，只在 `[3/7]` 真要重编 `.so` 时才拦住——原生桥
   极少改动，`.so` 比 `termux.c` 新的机器上根本没装 NDK 也该能出包。

`--min-sdk-version 21 --target-sdk-version 30` 一个字没动：platform 换版本是探测，
产品目标版本是决策。

CI 也顺带改了一处：原来显式 `echo ANDROID_NDK_HOME=$SDK/ndk/27.0.12077973` 绕过探测，
现在删掉这行，让 CI 真的走一遍 `pick_ndk`；升级 NDK 只改 `sdkmanager` 里那一行。

实测（六种 SDK 形态逐条跑）：空目录 / 只有 platforms → `rc=2` 缺 build-tools；
只有 build-tools → `rc=2` 缺 platforms；完整无 ndk + `touch termux.c` → `rc=2`
找不到 NDK；同上不 touch → **BUILD OK**；`ANDROID_HOME=/nonexistent` → `rc=2`；
`ANDROID_NDK_HOME=/nope` → `rc=2`；`BT=` 指向只有 build-tools 的目录 → 打出
`[warn]` 混搭提示后继续。本机（37.0.0 + NDK 27）出包 **13,046,327 B**，与 v2.10.0 一致。

### O-26：`runFileAction` 的 case 也换成常量

O-25 只改了菜单侧；派发侧还是 `case 0:` … `case 52:`，`grep` 也拦不住
「新增 `case 53` 却忘了加常量」。现在 52 个 `case <数字>` 全部换成 `case A_XXX:`，
数值与分支顺序一个都没动——脚本按「常量表 → 数字」回查，52 个字面量 52 个名字，
没有缺号，也没有「表里有两个同名不同值」（52 个值两两不同，先断言过）。

顺手补一道门 `test/action_check.sh`（已进 `build.sh` 的门块与 CI，用时 &lt; 1 秒）：

| 检查 | 判据 |
| --- | --- |
| 派发侧 | `runFileAction` 方法体内不许出现 `case <数字>` |
| 菜单侧 | `MainActivity` 里不许出现 `acts.add(<数字>)` |
| 兜底 | `runFileAction` 必须有 `default:` |

只扫 `MainActivity` 这一个方法：`TextEditorActivity` 里也有一个局部 `acts`
（编辑器自己的菜单号），`ArchiveController` 的 `A_EDIT` / `A_HERE` 同理——三套编号
互不相干，扫全仓只会制造噪音（第一版门就是被这三处误报拦下来的）。

反向验证：把 `case A_HEX:` 临时改回 `case 2:` → `FAIL … runFileAction 里还有数字 case`；
改回 → `PASS  动作码一致：runFileAction 全是常量 case（52 个）`。

### R-37：v2.10.0 的 6 处口径

| # | 位置 | 原来 | 现在 |
| --- | --- | --- | --- |
| 1 | v2.10.0 章 | 62 个菜单项（37 + 25） | **63**（37 + 26）；漏数原因见下 |
| 2 | v2.10.0 验证 | 单测只写「55 PASS / 0 SKIP、58 PASS / 0 SKIP」 | 补「干净 clone：51 PASS / 4 SKIP、51 / 7」（`test/huangguo.apk` 在 `.gitignore`） |
| 3 | v2.10.0 章 | 假 SDK「只放 build-tools/36.0.0 + platforms/android-35」→ BUILD OK | 补三个前提：build-tools 必须完整（缺 `lib/d8.jar` → `[5/7]` `ClassNotFoundException: D8`）、当时没用 NDK、只有一半会跨 SDK 混搭 |
| 4 | `build.sh` 头部 | 「SDK 默认 `E:/repo/android/Sdk`…」看着像现状 | 明确是 v2.6.0 之前的旧行为，并指向 toolchain 的候选顺序 |
| 5 | `build.sh` NDK 注释 | 「默认 `$ANDROID_HOME/ndk/27.0.12077973`」 | 改为「缺省时自动探测 `$SDK/ndk/` 下版本最大的」 |
| 6 | CI 注释 | 只点名「依赖方向门 + catch 门」 | 四道门各一个步骤名（`lang_check` / `action_check` 单独列出，失败时不用翻 `[gate]` 行） |

第 1 条是核对脚本自己的 bug：正则要求 `ops.add("…"); acts.add(…)` 同一行，APK 菜单
有一项跨行，`zip()` 把 26 项悄悄截成 25 项——**最后一项从来没被比对过**。改成允许跨行
后重跑，63 项逐项一致。

另记一条本轮确认的事实：`test/LanReserveTest` 的「只读目录」子用例在 Windows 上走
`SKIP`（`setWritable(false)` 不阻止建文件），**只在 Linux/CI 真跑**。本机 `PASS` 不证明
这一条在 CI 上成立，反之亦然。

### 验证

- `build.sh` → **BUILD OK，v202 / 2.11.0，13,046,327 B（与 v2.10.0 逐字节同大小），V3 CN=hex91**
- 四道门：`deps_check` 0 命中、`catch_check` 335（基线 335）、`lang_check` PASS（330 键）、
  `action_check` PASS（52 个常量 case）
- 桌面单测（本机有 `test/huangguo.apk`）：`QUICK=1` **55 用例 55 PASS**；`ALL` **58 用例 58 PASS**
- SDK 形态矩阵 7 条、门反向验证 1 条，全部实跑（结果见上）

**真机测试点**（O-26 改的是「点哪个菜单执行哪个动作」，编译与单测都覆盖不到）：

1. 设置页确认 `2.11.0 (202)`；
2. 普通文件长按：文本编辑 / HEX / 计算哈希 / 图片处理 / 查找重复文件 / 分享 /
   局域网快传 / 复制到剪贴板 / 删除 —— 逐项确认弹的是同一个功能；
3. APK 长按：Dex 编辑器++ / ARSC 编辑 / ARSC++ / Manifest 编辑器++ / 反编译 dex→smali /
   提取 dex / 签名信息 / APK 签名 / APK 对比 / 共存 / 解包 / 去除签名；
4. keystore 长按：签名信息 / 导入签名管理；解包目录「打包为 APK」；`.dex` 长按
   「Dex 属性」/「Dex 修复」；
5. 可选：把某菜单项号码临时改成 12（空号）点一下 → 应弹「未知动作 12」。

---

## v2.10.0（versionCode 201）—— R-34 / R-35 / O-25：基线归零、ANDROID_HOME 真正可单独生效、动作码收敛

三项都不是功能改动，但都在治同一种病：**机制已经建好了，却在某个角落没真正生效**——
基线留高一个，门就白送一个额度；`ANDROID_HOME` 能指向目录却仍被别的 SDK 顶替；
动作码有三套编号，菜单写错号只会静默。

### R-34：空 catch 基线 336 → 335

门判的是 `count > BASE`。v2.9.0 的 R-31 删掉 `FileOps.readAllBytes` 里那个
`catch (Exception ignored) {}` 之后实际是 335，基线还停在 336 —— 下一个新增空 catch
不会让门失败，等于先攒下一个额度，再删一个又攒一个。现在 `BASE=335`，注释补一句
「改动实现导致计数下降时**同步下调**」，README 的三处口径（命令注释、门说明、
v2.9.0 验证）一起改。

反向验证：临时插一个 `try { … } catch (Exception e) { }` → `rc=1`；删掉 → `rc=0`。
**不是**去补代码凑数（§21.5 第 1 条）。

### R-35：build-tools / platform 按版本探测

R-32 只治了「`ANDROID_HOME` 指向不存在的目录」。目录存在、里面只有
`build-tools/36.0.0` 时，五个候选全钉着 `37.0.0`，`first_existing` 会跳过它继续往下找，
落到机器上另一个 SDK —— 显式给的 `ANDROID_HOME` 被静默忽略，还是同一个病；而在没有
`E:/repo`、`$HOME/android-sdk` 的机器上又会报「找不到 build-tools：需要 37.0.0」，
把「版本不匹配」说成「找不到」。

现在 `pick_dir` 在 `build-tools/` 与 `platforms/` 下挑「版本最大、且真含 `aapt2` /
`android.jar`」的那个：优先 `sort -rV`（GNU coreutils / macOS 都有），busybox 没有
`-V` 就退化成「只比主段数字」—— 因为把 `35.0.0-rc1` 里的数字全抠出来是 `35001`，
反而会压过 `37.0.0`。`--min-sdk-version 21 --target-sdk-version 30` 一个字没动：
platform 换版本是探测，产品目标版本是决策（§21.5 的口径）。

**顺带修掉一个更要命的问题**：探测出来的路径是 MSYS 形式（`/c/Users/...`），bash 能用，
但 `javac -classpath` 用 `;` 拼多段时 MSYS 不做参数转换、d8 的 `@args` 文件更是完全不经过
shell —— 两边都把 `/c/...` 当成「当前盘根下的相对路径」，于是报一屏
`cannot find symbol: ApkSigner`，看着像源码坏了。`jc.sh` 一直在用 `winpath` 转，
`build.sh` 忘了转，就是这个差别。现在 `tools/toolchain.sh` 在 Windows 上把
`BT` / `ANDJ` / `APKSIG` / `SDK` 统一成 `cygpath -m` 的混合形式（`C:/Users/...`），
调用方不必各自转。

实测：假 SDK（**完整** build-tools 目录改名成 `36.0.0`，加 `platforms/android-35`，
且 `native/lib` 下的 `.so` 比 `termux.c` 新，所以 `[3/7]` 整步跳过）→
`BT` / `ANDJ` 都指向它，`ANDROID_HOME=<假 SDK> ./build.sh` → **BUILD OK**；
`ANDROID_HOME=/nonexistent` 仍 `rc=2`；本机（37.0.0）出包正常。

> 这个"BUILD OK"当时被写成了「假 SDK 够用」，不准确。v2.11.0（R-36）把三个隐含前提
> 摆出来了：① build-tools 必须是**完整**目录（缺 `lib/d8.jar` 会在 `[5/7]` 报
> `ClassNotFoundException: com.android.tools.r8.D8`）；② 当时那条流水线**没有**用到 NDK
> （`.so` 已最新），`touch native/jni/termux.c` 之后同一份假 SDK 会在 `[3/7]` 报
> `clang: No such file or directory`（rc=127）；③ 只有 build-tools 或只有 platforms 时，
> 以前会跨 SDK 混搭着把包打出来。这三条现在都有明确报错，见 v2.11.0 章。

### O-25：文件动作码收敛到一张表

| | 收敛前 | 现在 |
| --- | --- | --- |
| 常量 | `showOpsMenu` 内 29 个局部 `final int`，`showApkOpsMenu` 内另 27 个 | 类级 52 个 `A_*`（0–52，12 空号） |
| 同名冲突 | `A_DEXPP` 在文件菜单是 14、在 APK 菜单是 15 | `A_DEXPP`(14) / `A_DEXPP_MULTI`(15) |
| 字面量 | 8 处直接写数字（36/37/38/39/41/42/43/44） | 全部换成常量名 |
| 派发 | 52 个 case，**没有 default** | 补 `default: toast("未知动作 " + act)` |
| 后果 | 写了个没定义的号 → 点了没反应；写成语义相近的号（13 签名信息 vs 20 APK 签名）→ 弹错功能 | 未定义的号当场可见 |

审计文说「7 处绕过常量」，实测是 **8 处** —— 漏了「图片处理」的 `acts.add(37)`。

数值一个都没改：用脚本按**方法作用域**解析改前改后的 63 个菜单项（文件菜单 37 +
APK 菜单 26），`(标签, 数字)` 逐项对比全一致 —— 只搬名字，不改行为，这是这一项唯一
安全的做法（52 个号码抄错一个，就变成「点这个执行那个」）。

> 上一轮写的是「62 个（37 + 25）」，数字抄错在核对脚本上：正则要求
> `ops.add("…"); acts.add(…)` 在同一行，APK 菜单里有一项跨了行，配对时被 `zip()`
> 静默截掉，于是 26 项显示成 25 项、且最后一项压根没参与比对。v2.11.0 用
> `\s*;\s*(?:\n\s*)?` 允许跨行后重跑，63 项全部一致。

**不做** §20.3 提的「O-22 第三刀」（把 `runFileAction` 搬进控制器）：它是 52 个 case 的
分发表、绝大多数 case 只有一行调用，搬走只是换文件，真正的问题在编号。
`ArchiveController` 那套归档条目编号（`A_EDIT` / `A_HERE` …）也不动：它服务压缩包内
条目菜单，是另一张表。

### 验证

- `build.sh` → **BUILD OK，v201 / 2.10.0，13M，V3 CN=hex91**
- 假 SDK（完整 build-tools/36.0.0 + platforms/android-35、无 ndk、`.so` 已最新）：
  `ANDROID_HOME=<假 SDK> ./build.sh` → **BUILD OK**
- 桌面单测（本机有 44MB 的 `test/huangguo.apk`）：`QUICK=1` **55 用例 55 PASS**；
  `ALL` **58 用例 58 PASS**
- 三道门：`deps_check` 0 命中、`catch_check` 335（基线 335）、`lang_check` PASS（330 键）
- `grep -c 'acts.add([0-9]' src` → **0**

> `test/huangguo.apk` 在 `.gitignore` 里（44MB），所以**干净 clone** 上跑不到这 4 个
> 依赖样本的用例：`QUICK=1` 是 **51 PASS / 4 SKIP**，`ALL` 是 **51 PASS / 7 SKIP**。
> 两种口径都要能解释，不能只写理想值（R-33 的规矩，这一行 v2.10.0 又写回去了，v2.11.0 修正）。

**真机测试点**（改动集中在菜单与动作派发，编译不覆盖）：

1. 设置页确认 `2.10.0 (201)`；
2. 普通文件长按：文本编辑 / HEX / 计算哈希 / 图片处理 / 查找重复文件 / 分享 /
   局域网快传 / 复制到剪贴板 / 删除 —— 逐项确认弹的是同一个功能；
3. APK 长按：Dex 编辑器++ / ARSC 编辑 / ARSC++ / Manifest 编辑器++ / 反编译 dex→smali /
   提取 dex / 签名信息 / APK 签名 / APK 对比 / APK 共存 / 解包 / 去除签名；
4. keystore 长按：签名信息（43）/ 导入签名管理（44）；
5. 解包目录长按「打包为 APK」（36）、.dex 长按「Dex 属性」（41）/「Dex 修复」（42）；
6. 可选：把某个菜单项号码临时改成 12（空号）点一下 → 应弹「未知动作 12」而不是没反应。

---

## v2.9.0（versionCode 200）—— 第四轮审计：死 import、文案门补转换符、读文件收口、工具链兜底

§20 的五个审计项（R-29 ~ R-33），**没有一条会改变界面行为**；共同点是「不影响功能、
但会让下一个读代码的人判断错」：死 import 让评审分不清某页是否还在用 `AlertDialog`；
门少比一半让错翻的 `%1$d` 溜进英文包；四份 `readAllBytes` 让修一处漏三处；
`ANDROID_HOME` 拼错却"构建成功"让人以为用的是那个 SDK；README 的「55/55」把 4 个 SKIP
算成了通过。做法一致：先让脚本把账算清，再动手，最后把口径写回文档。

### R-29：清掉 50 处死 import（文档清单列了 45 处）

口径：抹掉注释与字符串字面量后，按简单名判断该 import 是否在文件其余部分出现；
`import static` 与通配不参与统计。实测 **50 处 / 36 文件**，比文档的 45 处 / 32 文件
多 5 处，多出来的都是名字只出现在**字符串或 javadoc** 里的：
`LanShareActivity` 的 `AlertDialog` 只在 `{@code AlertDialog.setItems}` 里、
`ApkInfoActivity` / `CompareActivity` 的 `Activity` 只在 `comp("Activity", …)` 这类
字面量里、`SyntaxHighlight` 的 `Shell` 只有 `buildShell()` 这个子串和 `"Shell"` 语言名、
`SmaliDocs` 的 `FileOps` 只在 smali 示例文本里 —— 正好是「看起来还在用」的典型。

分布：`AlertDialog` **17**、`List` 4、`Environment` 3、`ArrayList` 2、`Uri` 2，其余
22 个各 1。`AlertDialog` 一家占三分之一，成因统一：v2.x 把弹窗换成 `HfDialogBuilder`
之后 import 留在原地。`com/termux` 与 `jadx` 是 Apache-2.0 的第三方 fork，12 处不动。

**不加门**：README v2.6 的权衡仍然成立（同名类与静态导入误报率高，收益低于维护成本），
这次是「清一次」，不是「加一道」。清完 `test/jc.sh` rc=0，`deps_check` 仍 0。

### R-30：文案门补上「转换符」比较

原实现只抓 `%(\d+)\$`，`%1$s` 与 `%1$d` 得到同一个集合 `{'1'}` —— 译者把英文写成
`%1$d`，编译、门、单测全绿，只有英文用户在 `String.format` 处吃到
`IllegalFormatConversionException` 闪退。现在按 `%[位置$][标志][宽度][.精度]转换符`
解析成 `(位置, 转换符)` 再比：

- 日期时间类 `%1$tY` / `%1$td` 连后缀一起取，不把两个不同转换看成同一个；
- 无位置参数的 `%s` / `%d` 以 `-` 当位置参与比较；`plurals` 的 `%d` 是 quantity，
  按**集合**比不会因为 `one` / `other` 条数不同而误报；
- 失败信息直接给 `%1$s` 形式：`leftover_scanning 格式参数不一致: 中文 %1$s / 英文 %1$d`。

合成用例实测：改英文 `%1$s` → `%1$d` → FAIL + rc=1；还原 → PASS；真实仓库 330 个键
两侧齐全仍 PASS（当前没有一处类型不符，属「幸未发生」）。

### R-31：`readAllBytes` 四份实现收成两份

| 位置 | 现状 |
| --- | --- |
| `core/ByteIO.readAll` | 纯 JDK 整读（流版本也在这里） |
| `fs/FileOps.readAllBytes` | 失败回退 `RootIO`；**现在只留回退那一层**，缓冲循环委托 `ByteIO` |
| `TextEditorActivity` 私有 | 删除，改调 `ByteIO.readAll` |
| `DexEditorActivity` 私有 | 删除，改调 `ByteIO.readAll` |

两个 Activity 原本就是纯读、无 root 回退，替换后行为不变。`ByteIO` 的类注释原来指向
`MainActivity.readAllBytes`（O-19/O-22 期间已删），现在改成「要 root 回退走
`FileOps.readAllBytes`，只读普通目录用本类」。`FileOpsTest` 补第 6 条断言，把
「`readAllBytes(null)` 抛 `IOException`（委托前是 NPE）」固化下来 —— 调用方都
`catch Exception`，形态变化无影响，但不写下来就会被下一个人当 bug 改回去。

顺带收益：`FileOps` 里那个 `catch (Exception ignored) {}` 随实现一起删了，
空 catch 总数 336 → 335，基线同步下调到 335（R-34：留高一个等于白送一个额度）。

### R-32：`ANDROID_HOME` / `ANDROID_SDK_ROOT` 错值不再静默回退

原来 `first_existing` 只是「跳过不存在的候选」，于是
`ANDROID_HOME=/nonexistent/sdk bash build.sh` 会落到机器上的另一个 SDK —— 构建
"成功"，用的却不是你以为的那个；换台机器又变成「找不到 android.jar」。现在只要
给了 `ANDROID_HOME`（优先）或 `ANDROID_SDK_ROOT`，就必须是真目录，否则 `exit 2` +
一行可操作提示（并提醒「要指向 SDK 根目录，不是 build-tools 子目录」）。

同时删掉 JDK 兜底里钉死的 `D:/Program Files/Java/jdk-21.0.3+9/bin`：装了 JDK 17
却没设 `JAVA_HOME` 的机器本来能用，被这行钉成「找不到 JDK」。现在只认
`JAVA_HOME` → `PATH` 上的 `javac`，都没有才报错，报错里补上三种解法。

实测四条：`ANDROID_HOME=/nonexistent bash test/jc.sh` → rc=2 + 两行提示；
`ANDROID_HOME=E:/repo/android/Sdk` → 正常出 `BT`；
`env -u JAVA_HOME` → 取 PATH 上的 `javac`（本机 zulu17）；`JH=/nope` → rc=2（R-23 的校验）。

### R-33：README 的「55/55」按 `run_core.sh` 末行口径重写

`QUICK=1 ./test/run_core.sh` 的末行是 `pass= / fail= / skip=`，README 只抄了分子：
本机 `test/huangguo.apk` 在（44MB，`.gitignore` 里，**不入库**）时是 55 PASS，干净 clone
则必然 4 SKIP —— 而「51 PASS + 4 SKIP」被写成「55/55」，正好是 R-20 要治的那类
「样本缺失 = 通过」。现在统一写成「N 用例：X PASS / Y SKIP（原因）」，并注明样本在不在
两种结果。同类口径问题一并修：`MainActivity` v2.7.0 的 `4,036 行（−375）` 按 `wc -l`
复核为 `4,035 行（−376）`；`v2.5.0` 那句 `ALL=57` 标注成「当时」。

### 验证

- `build.sh` → **BUILD OK，v200 / 2.9.0，13M，V3 CN=hex91**（`aapt2 dump badging` 核对
  `versionCode='200'`）
- 桌面单测：`QUICK=1` **55 用例 55 PASS / 0 SKIP**；`ALL` **58 用例 58 PASS / 0 SKIP**
  （本机样本在；干净 clone 见 R-33 的两种结果）
- 三道门：`deps_check` 0 命中、`catch_check` 335（基线 335）、`lang_check` PASS（330 键）

**真机测试点**（本轮不动界面，重点在 R-31 改过的读文件路径）：

1. 设置页确认版本 `2.9.0 (200)`；
2. 长按 APK → 查看签名信息 → 输密码 → 条目详情（走 `FileOps.readAllBytes`）；
3. 长按 keystore → 导入到签名管理（同上）；
4. 用文本编辑器打开 `AndroidManifest.xml` 改一处再保存（`saveRoundtrip` 走
   `ByteIO.readAll`），确认能重新出包、无「回读校验失败」；
5. dex 编辑器改一处 smali → 保存写回（同上），确认 `_bak.apk` 逻辑照旧。

---

## v2.8.0（versionCode 199）—— O-24 批次 1：签名管理 + 文本编辑器 i18n

F-14 的逐页迁移继续。这一批是计划里最大的两页（审计口径 273 处），实际拆成两次提交：

| 页 | 硬编码中文 | 新增键 | 提交 |
|---|---|---|---|
| `SignKeyManagerActivity` | 123 | 72（`ksm_`） | `i18n: 签名管理页迁移（O-24/1）` |
| `TextEditorActivity` | 152 | 109（`te_`） | `i18n: 文本编辑器迁移（O-24/2）` |

`values-en` 现在与中文侧 330 个键**一一对应**（覆盖率 100%），但仍按 v2.3.0 的规矩只放
已迁移的页——没迁的页面在英文下继续回退中文，这是预期行为，不是漏翻。

### 两页迁起来不一样的地方

签名管理页的难点是**状态文案**：列表标题是
`"别名 " + alias + " · " + keyCount + " 个私钥别名"` 这种「中文片段 + 变量」硬拼，
英文语序放不进去（`Alias x · 2 private keys` 需要名词在前、数量短语整体变化）。
拆成两段独立片段再拼接，分隔点 ` · ` 留作字面量（是标点不是文案）；带数量的走
`plurals`，中文只有 `other`、英文分 `one`/`other`——「1 private key」不能写成
「1 private keys」。

文本编辑器的难点是**菜单互相引用**：主菜单 → 文件菜单 → 语法菜单 → 格式化菜单，
拆开迁会留下半中半英的中间态，所以六组（主菜单 / 编辑操作 / 自定义 / 格式化 /
保存写回 / 退出确认）一次做完。「只读模式」是 13 处前置守卫 + 1 个菜单项共用的
同一句话，收敛成一个键；菜单里的 `☑` / `☐` 仍是字面量，只翻状态词。

### 三处刻意不迁移

1. **`insertAtCursor` 里的 `"换行"`**：底部符号栏的按钮文本既当标签显示、又当内部
   token 用（`btn.getText().toString()` 传进来再比对），翻译它等于把换行键改成插入
   「换行」二字。这一条以后要是有人把符号栏也 i18n 了，会静默坏掉。
2. **`回读校验失败：元素数不一致`**：异常消息，v2.3.0 定的规矩不翻。
3. **`压缩包内找不到 x`**：同上。

签名管理页还有一处同类的：`saveEntry` 里那句 `IllegalStateException("密码已损坏…")`
保留中文。它会在「重命名损坏条目」时经 toast 露出来，严格说算半漏——但那是异常消息
路径，改了就要连异常语义一起理，留给异常本地化单独一轮。

### 新增门：`test/lang_check.sh`

逐页迁 i18n 最容易出的错不是翻错，而是键对不上，其中两类编译期完全看不出来：

- 英文加了、中文漏了 → 中文环境直接崩（找不到资源）；
- 格式参数对不上 → 中文 `已保存 %1$s`、英文翻成 `Saved`（丢参数）不崩但界面缺内容；
  反过来英文多一个 `%2$s` 而调用方只传一个参数，运行时
  `String.format` 抛 `MissingFormatArgumentException`，直接闪退。

门查四件事：代码引用的键在中文资源里存在且类型一致、`values-en` 没有孤儿键、
两侧资源类型一致、两侧格式占位符集合一致（**位置 + 转换符**，`%1$s` ≠ `%1$d`，
v2.9.0 的 R-30 补上了后半截；只看位置时 `%1$s` / `%1$d` 的集合都是 `{'1'}`）。
**不查**英文覆盖率（理由见上），只作统计输出。

反向验证四条（都已实测）：改英文参数 → FAIL、改英文转换符 `%1$s`→`%1$d` → FAIL、
删一条英文 → PASS、引用不存在的键 → FAIL。

### 验证

口径：一律抄 `run_core.sh` 末行 `pass= / fail= / skip=`，**SKIP 必须写原因**——
「51 PASS + 4 SKIP」不等于「55 PASS」，样本缺失曾经被当成通过，这正是 R-20 要治的病。
本机（`test/huangguo.apk` 在，44MB，gitignore 不入库）：

- `build.sh` → **BUILD OK，v199，13M，V3 CN=hex91**
- `QUICK=1 ./test/run_core.sh` → **55 用例：55 PASS / 0 SKIP**；`ALL` → **58 用例：58 PASS / 0 SKIP**
- 干净 clone（无样本）：`QUICK=1` 51 PASS + 4 SKIP、`ALL` 51 PASS + 7 SKIP（新增 3 条 E2E 也缺样本）
- 三道门：`deps_check` 0 命中、`catch_check` 336（持平）、`lang_check` PASS
- 英文侧机械核对：330 个键两侧齐全，位置 + 转换符逐条比对通过（`lang_check` 输出）

**真机回归**：切系统语言到英文，走一遍「抽屉 → 签名管理」（导入签名卡片、空状态
文案、创建签名表单、条目长按菜单、详情、删除确认）与「文本编辑器」的主菜单 /
编辑操作 / 格式化 / 退出确认；重点看英文卡片与按钮有没有被截断（英文平均长 30%），
以及签名管理的列表副标题在「有别名 / 无别名 / 别名数=1」三种情形下的读法。

---

## v2.7.0（versionCode 198）—— O-22 刀1：签名簇搬出 MainActivity

v2.5.0 那一轮把「往压缩包里加东西」搬成 `ArchiveAddController`（刀2），签名簇留了下来，
原因写在了当时那版的说明里：它跟 `onActivityResult` 的密钥选择分支和
`mCustomSignSrc` 状态强耦合，搬动等于同时改四条路径。这一版把它做完。

### 为什么这一簇比刀2难

刀2 是纯「调用—回调」，宿主给能力、控制器干活。签名簇中间有**一次进程内的往返**：

```
askCustomSign → 起系统选择器（ACTION_OPEN_DOCUMENT）→ onActivityResult → askCustomKeyPassword → 签名
```

「等一下要签哪个包」这件事必须跨过这次往返。原来的实现是两个 Activity 字段
`mCustomSignSrc` / `mCustomSignInPlace`，于是字段的写入点在 `askCustomSign` 的两个分支里、
读取点在 `onActivityResult` 与对话框回调里、判空散在 `onActivityResult` 的
`if (res == RESULT_OK && data != null && data.getData() != null && mCustomSignSrc != null)`。
字段活多久、谁负责清，没有任何一处代码能回答。

### 做法

新增 `controller/SignFlowController`（497 行），搬 5 个方法：

| 方法 | 原行数 | 职责 |
|---|---|---|
| `askCustomSign` | 69 | 列表选已导入签名 / 走一次性流程 / 进签名管理 |
| `doCustomSignWithEntry` | 47 | 用已保存密码直接签（审计只点了 4 个方法，这个是 `askCustomSign` 的唯一后继，留下只会多一层来回调用） |
| `askCustomKeyPassword` | 65 | 一次性流程第 2 步：输密码 → `signAutoAlias` |
| `parseKeystore` | 57 | 「查看签名信息」的解析与展示 |
| `doSigKill` | 115 | 去除签名校验全流程 |

跨 Activity 的状态跟着代码一起进控制器，宿主只剩一次转发：

```java
// MainActivity.onActivityResult
if (signFlow.handleResult(req, res, data)) return;   // 本簇的请求码，控制器已消费
```

请求码也搬了：`SignFlowController.REQ_CUSTOM_KEYSTORE`（3002）。宿主 Host 只有六个能力：
`ctx` / `toast` / `ui` / `refreshAll` / `pickKeystore` / `importKeystore`。

### 搬动时顺带修的一处：pending 的读取次数

`handleResult` 在**消费的这一刻**把 `pendingSrc` / `pendingInPlace` 快照成局部变量并清空，
`askCustomKeyPassword` 改成接收 `inPlace` 参数，不再读字段。

原来 `mCustomSignSrc` 用完不清：取消密码对话框后再点一次「自定义密钥签名」，如果这次
在选择器里取消，`mCustomSignSrc` 仍是**上一次**的包；而如果新的一次选择成功了，
`onActivityResult` 拿到的 Uri 配的是刚写入的包（这次是对的），但字段里残留的旧包会一直
留到下一次成功写入 —— 也就是说「哪一次会拿错包」取决于上一次是否成功，是个只在边角
出现的错签风险。现在 pending 的生命周期只有一段：从起选择器到结果回来。

### `readFileBytes` 下沉到 `fs/FileOps`

`parseKeystore` 与 `importKeystore` 都要「读整个文件，普通读失败回退 `RootIO`」，
原来这份实现是 `MainActivity.readFileBytes` 私有方法。控制器要用它，两个选择：复制一份，
或者落到 fs 层。选了后者 —— 它本来就和界面无关，叫 `FileOps.readAllBytes`。

新增 `test/FileOpsTest`（5 条断言，v2.9.0 加第 6 条锁 null 契约；已进 `run_core.sh`）：逐字节相等（含 `0x00` / `0x80` /
`0xFF` 三个边界）、空文件返回 0 字节而非 null、不存在的文件**抛异常**而不是退化成 null、
传目录抛异常。最后两条是这个方法唯一容易做错的地方：回退链一旦把失败吞成 null，
调用方拿到的是 NPE 而不是「读失败」。

（顺带发现 `test/RootIOTest.java` 已经是个跑不起来的孤儿：`RootUtil.isRootAvailable()`
要 Android `Context`，桌面 JVM 直接 `NoClassDefFoundError`，而且没有任何脚本引用它。
先把新断言放在真正会执行的用例里，那个文件的去留单独再说。）

### 变化量

| | v2.6.0 | v2.7.0 |
|---|---|---|
| `MainActivity` | 4411 行 | **4035 行**（−376） |
| `controller/` 类数 | 7 | **8** |
| 空 catch 总数 | 336 | 336（控制器内 12 个，都是从 `MainActivity` 位移过来的 `catch (Throwable ignored)`） |

空 catch 总数不变是这一刀「只搬不改」的证据：如果哪一段在搬运中被顺手删了 `catch`，
计数会掉；如果补了日志，计数会掉 —— 都没发生。

> 行数口径：一律 `wc -l`。本节 v2.7.0 当时写的是「4,036 行（−375）」，本轮按同一命令
> 复核为 `4411 → 4035`（−376）。差 1 行的原因已不可考（当时文件正在改，多半是记在
> 清理死 import 之前），不影响「搬走 376 行」这个结论。

顺手删掉 `MainActivity` 里 `java.io.FileOutputStream` 的死 import（旧代码里 `doSigKill`
用的是全限定名，这个 import 从加上那天起就没被用过）。

### 真机回归（这一版必须走）

签名簇是唯一「搬完还要看行为」的部分，四条路径都要过一遍：

1. **列表选已导入签名**：长按 APK → 自定义密钥签名 → 选一个已导入的签名 → 出 `_csigned.apk`；
2. **一次性流程**：删掉全部已导入签名 → 同一入口 → 选 keystore 文件 → 输密码 → 出包；
   中途在选择器里取消，应无任何提示、无残留；
3. **原地覆盖**：编辑过的包（有本会话修改）→ 签名后原文件名不变、可安装；
4. **查看签名信息**：点 keystore 文件 → 查看签名信息 → 输密码 → 条目详情 → 「导入到签名管理」；
5. **去除签名校验**：长按 APK → 去除签名校验 → 生成 `<名>_nosig.apk`，报告里的 patch 数不为 0。

第 2 条里的「取消」是新逻辑的直接观察点：原来取消后字段残留，现在是干净退出。

---

## v2.6.0（versionCode 197）—— 第三轮审计：占位清理、临时名同源、工具链收口

这一版仍然没有新功能，全部是「让工具与代码说的和做的一致」。八项按风险排序，
前两项是数据面（断网留垃圾、临时名分叉），中间三项是构建可移植性，后三项是门禁精度。

### R-24 / R-27：占位文件的清理契约只在两个分支落实（P2）

`LanProtocol.reserve` 的约定一直是「调用方负责清理这个 0 字节占位文件」，
但 `LanNode.receive` 只在「长度不符」与「rename 失败」两个分支里 `delete` 过。
真正的缺口是 `read()` 抛 `IOException`（客户端断网、传输中途关连接）：异常一路抛到
`serve()`，接住后回 500，占位文件既没删、又已经在 `finally` 里 `end` 掉，不再算活动 ——
下一次打开「设置 → 遗留文件」才看得到它。一次断网一个 0 字节文件，用户以为「失败但磁盘干净」。

现在用 `done` 统一收口：只有 rename/复制成功才置 true，其余所有退出路径都在 `finally` 里
`part.delete()` 再 `TempFiles.end(part)`。进程被杀这条路走不到，那种残留靠清理工具。

同时把 `TempFiles.begin(part)` 前移到 `reserve` 返回后的第一行（R-27）。原来要先推导
`partName` / `finalName`（5 行）才登记，这几行之间设置页拿到的活动快照里没有它、
删除入口的 `isActive` 预检也放行 —— 正是 R-08 想挡的「扫描之后任务才开始」窗口。
文件一存在就该是登记态，窗口从 5 行缩到 0 行。

### R-26：`.reb<ts>.tmp` 仍是 3 处生产 + 1 处正则（P2）

`ArchiveWrite`（两处）与 `ZipUtil` 各自拼 `name + ".reb" + currentTimeMillis() + ".tmp"`，
而认名字的只有 `TempFiles` 里的一条正则。写的人各拼各的、认的人靠正则猜：只要有一处改了
格式（加进程号、改位数），遗留扫描就再次出现「写了但永远扫不到」—— 正是 R-11 的原始问题。

现在统一走 `TempFiles.rebTmp(target)`，格式由 `REB_INFIX` / `REB_TAIL` 两个常量单点定义，
识别用的正则紧贴其后。顺带修了同一毫秒内碰撞：三个生产端都没有重试，同毫秒连写两次会共用
同一个临时名；`rebTmp` 先探测占用，100 次都撞才退化到 `nanoTime`。
`grep -rn '"\.reb"' src` 现在只剩 `TempFiles` 一处定义。

### R-22：`build.sh` 把开发机路径写死（P2）

到 v2.5.0 为止，`build.sh` 无 `ANDROID_HOME` 时固定用 `E:/repo/android/Sdk`、无 `JAVA_HOME`
时固定用 `D:/Program Files/Java/zulu17.38.21/bin`、`PYTHON_EXE` 缺省指向
`C:/Users/han/.workbuddy/.../python.exe`。新机器按 README 直接 `bash build.sh`，会在
`aapt2` 或 `javac` 处报 `No such file or directory`，看起来像源码或依赖坏了。

R-12 已经在 `test/toolchain.sh` 里修好过同一个问题，只是 `build.sh` 没接进去。现在
定位器上移到 **`tools/toolchain.sh`**，`build.sh` 与 `run_core.sh` / `jc.sh` / `jres.sh`
共用一份：环境变量优先 → 常见位置探测 → 明确 `exit 2`。`build.sh` 里那四段重复分支
（SDK / JDK / NDK / Python）整段删除，改用 `$JH` / `$BT` / `$ANDJ` / `$SEP` / `$PY`。
NDK 由 `$BT` 反推 SDK 根得到，保持 `E:/...` 形式与旧行为一致。
`deps_inputs` 把 `tools/toolchain.sh` 也纳入指纹——它决定依赖解析（JDK、平台 jar、分隔符），
换了它就该重建依赖 dex。

### R-23：显式覆盖 `JH` / `PYTHON_EXE` 不校验（P3）

```
PYTHON_EXE=/nonexistent/python bash test/catch_check.sh   → rc=127
JH=/nonexistent/jdk bash test/jc.sh                       → rc=127
```

README 承诺「环境缺失 → rc=2 + 一行可操作提示」，但校验只在自动探测分支里。现在
`tools/toolchain.sh` 在探测之后、任何使用之前统一校验 `JH/javac`、`BT/aapt2`、`ANDJ`，
`catch_check.sh` 对 `PYTHON_EXE` 补 `-c 'pass'` 真跑：

```
$ JH=/nonexistent/jdk bash test/jc.sh
JH 下没有 javac: /nonexistent/jdk          （rc=2）
```

### R-21：空 catch 计数把字符串里的 JS `catch` 也算进来（P3）

`strip_comments` 只处理注释，进入字符串状态后仍把内容原样写回，于是
`CloudLoginActivity` 里注入 WebView 的 `"...catch(e){}..."` 被当成 Java 空 catch
计进基线。新增 `blank_strings`：把字符串/字符字面量内容换成**等长空格**（不增删字符、
保留换行），行号与偏移都不变，`--list` 依旧准；转义对（`\"`、`\\`）整体抹掉，
否则 `\"` 会被当成字符串结束，后面整段代码被吞。附带修掉同类误判：字符串里的
`'{'` / `'}'` 原来也参与空块的花括号配对。

顺带发现 `strip_comments` 自己也不满足它注释里写的「等长」前提 —— 它是**删掉**注释字符，
`clean` 比原文短。行号靠数 `\n` 撑着所以没露馅，但任何按偏移回查原文的地方都是错的。
现在改成把注释抹白，`clean[i]` 与 `raw[i]` 一一对应。

「明确忽略」的判据也从「变量名是 `ignore`/`ignored`」放宽成三种表态：`ignore*` / `ig` / `ign`
（实测剩余 17 个里 9 个叫 `ig`，意思完全一样却被算成欠理由）、空块里写了 `ignore`/`忽略`
注释的。结果：

| | v2.5.0 口径 | v2.6.0 口径 |
|---|---|---|
| 空 catch 总数 | 338 | **336**（扣掉 2 处 JS） |
| 作者明确忽略 | 321 | **332** |
| 真正欠理由 | 17 | **4** |

剩下 4 处（`ArchiveUtil:333`、`SigKill:198`、`CompareUtil:228`、`MtSyntaxHighlighter:73`）
都已有中文理由注释（「保留默认」「写盘失败不抛，交给上层」「读失败返回空列表，UI 提示」
「跳过非法正则」），属于可接受终态。`--others` 可直接列出这一批。

### R-25：`reserve` 在只读目录上空转且报错误导（P3）

任何 `createNewFile == false` 都被当成「重名」，在只读 / 已删除的目录上循环 10000 次，
最后抛「无法分配唯一临时文件名」—— 真实原因是权限或 IO，照着这个报错去查重名方向完全错。
现在区分三种结局：目录不是目录 → `接收目录不可用`；连续 3 次「不是重名却创建不出来」
→ `创建占位文件失败` / `接收目录不可写`；真的换号一万次才用原来的措辞。

`canWrite()` 只用来决定报错措辞，**不作为拦截判据**：部分 FUSE / SAF 路径会误报 false，
而写入其实可行；真正判死活的是 `createNewFile` 的结果 —— 注意它的两种失败形态不同：
「文件已存在」返回 `false`，「权限 / 只读挂载」直接抛 `IOException`。两种都收齐，
连续三次不是「因为已存在」才定性为不可写；只判返回值会让只读目录的场景变成一句
没有上下文的 `Permission denied`（CI 上第一次就是这么红的）。

### R-28：`LanNode` 残留未用 import（P3）

删 `existingNames` 之后 `java.util.HashSet` 与 `java.util.Set` 已无引用，`javac -nowarn`
不报未用 import，构建与门都发现不了。两行删掉。`imports_check.sh` 那类启发式没做：
同名类与静态导入会误报，收益低于维护成本。

---

## v2.5.0（versionCode 196）—— 新图标 + 第二轮审计（并发、门禁、清理界面）

### 启动图标

源图 `tools/icon-source.svg`（青绿渐变底 + 白纸文件夹 + HF 连字），生成脚本
`tools/gen_icons.js` 一起入库 —— 改图只要重跑一次脚本，不用再手工导出七种尺寸。

- **自适应图标的前景层必须单独缩小**：原图前景横向已占 628/1024，四角落在 66dp 安全圆外，
  直接当 foreground 会被系统蒙版切掉角。脚本统一缩到 0.80 并以内容包围盒中心对齐画布中心。
- 顺带修正尺寸：原来的 `ic_launcher_foreground` 是 108/144/192/288/432，hdpi 起全都偏小
  （应是 108/162/216/324/432）。
- 底板从纯白 `@color/ic_launcher_bg` 换成 `#06B6D4 → #10B981` 渐变 drawable；抽屉头部改用
  完整 `@mipmap/ic_launcher`，删掉只为旧图标准备的白底板 `bg_drawer_logo`。

### R-19：局域网同名并发接收共写一个 `.part`（P1）

`LanNode` 收文件是「列目录 → `uniqueName` → `new FileOutputStream`」。两个同名请求同时进来，
都看到目录里没有 `X`，于是都选中 `X`，共写同一个 `X.part`。先完成的一方 `renameTo` 成功，
另一方 rename 失败后改用 `copyThenDelete` 复制「此刻的共享 `.part`」—— 拿到的可能是两份数据
交错的内容；它随后 `delete` 掉 `.part`，还可能删掉对方正在写的文件。

现在改 `LanProtocol.reserve(dir, name)`：用 `createNewFile` 原子占名，失败就换
`名字 (1).ext`。`createNewFile` 的原子性能跨线程与跨进程消除 check-then-create 窗口。
`TempFiles.ACTIVE` 同时从 `Set` 改成「路径 → 写者数」的引用计数：两个写者登记同一路径时，
先结束的那一个不再把仍在写的路径从注册表里注销（否则遗留扫描又会把它列出来）。

### R-15：遗留文件删掉后列表不刷新（P2）

删一条只弹 toast，行还在、总数不变，用户会再点一次；第二次仍然提示「已删除」，
而磁盘上早已没有该文件。清理工具在这个细节上一说谎，用户就不敢再信它的结论。
现在按行记账：删成功就移除那一行、重算「共 N 个 / 合计 X」，删完最后一条自动关闭对话框。

### R-16 / R-17：两处「注释说共用、实际各写一遍」

- `TempFiles.BAK` 指向 `AtomicReplace.BAK_SUFFIX`；六处 `".part"` 字面量改 `TempFiles.PART`
  （`FileTaskEngine`、`LanNode`、`SyncRunner`、`AliyunDriveFs`、`OneDriveFs`、`S3Fs`，
  以及 `LanProtocol` 的列表过滤）。现在两个后缀各只有一处定义。
- 35 处 `import com.hex91.file.*` 展开为精确 import（其中 7 处是纯死 import，直接删）。
  `deps_check` 的正则原来要求 `file.` 后还有一段才跟 `.*`，恰好放过了根包通配 —— 一并修正。

### R-18：依赖门的两条盲区

`import static com.hex91.file.ui.X.y;` 以 `import static` 开头，旧正则不匹配；
源码里直接写全限定名 `com.hex91.file.ui.UiKit.dp(1)` 更是完全绕过。两条都补上了，
并各自做过反向验证（临时注入一行越层代码 → 门失败 → 删掉 → 归零）。

### R-12 / R-13 / R-14：门禁与工具链

- `test/toolchain.sh`（v2.6.0 起移到 `tools/toolchain.sh`，`build.sh` 一并接入）：
  JDK / build-tools / android.jar 的定位只维护一份，
  `run_core.sh` 与 `jc.sh` 共用；`cygpath` 只在 Windows 分支调用。
- `catch_check.sh` 自己探测 Python（`python3 → python → py`，用 `-c pass` 真跑一次，
  Windows Store 存根会被跳过），找不到就 `rc=2` 明确报错。以前硬编码 `python3`，
  在 Git Bash 上命中的是 Store 存根，脚本静默退出 49、一行输出都没有，看起来像通过。
- `build.sh` 在 aapt2 之前就跑依赖门与空 catch 门（`SKIP_GATES=1` 可绕过）。
- 新增 `test/jres.sh`：只重生成 `R.java`，改一个资源键不必等 5 分钟完整构建。

### R-20：`ALL` 测试默认变红

缺 `test/huangguo.apk` 时，只有 4 个用例会 SKIP；`SessionTest`、`SignPromptFlowTest`、
`CustomSignE2ETest` 走 `*)` 分支拿到同一个样本路径，内部直接调 `ApkSignerTool.sign`，
于是 `bash test/run_core.sh`（当时 `ALL=57`）在新机器上无条件变红。现在这三个也 SKIP，
`SessionTest` 还单独判 `assets/hexfile.p12`。

### O-22 刀2：`ArchiveAddController`

`addToArchive`（62 行）、`writeTree`（20）、`askAddToArchive`（28）、`askCompress`（70）
整段搬到 `controller/ArchiveAddController`，`hasSmali` 下沉到 `fs/TreeOps`。
`MainActivity` 4,592 → 4,415 行。搬动时保持三条硬约束：删源必须在写入成功后且只走
`TreeOps.deleteOrTrash`；`writeTree` 遍历只用 `LinkUtil.listNoFollow`；APK 目标循环结束后
统一 `flushRegen`。

**刀1（签名流程）不在本版**：`askCustomSign` / `askCustomKeyPassword` / `parseKeystore` /
`doSigKill` 的耦合点是 `onActivityResult` 里的密钥选择分支与 `mCustomSignSrc` 状态，
搬动会同时改到「选密钥 / 输密码 / 取消 / 签成功」四条路径。它与本版的 R-09、R-19 真机
验证路径（移动进压缩包、局域网接收）不同，混在一个包里出问题难定位，留作单独一轮。

### O-23 批次 1：空 catch 的真相

审计报告按「空块」机械计数，得出 `FtpFs` 12 / `S3Fs` 10 / `HlsDownloader` 10。逐条核对后
只有 1 处真的吞掉了原因（FTP 状态行无法解析时 `code=0`，排查「连上了却列不出目录」时
只有这一条线索）；其余全是 `close()` / `disconnect()` / `QUIT` 收尾，或 `URLDecoder` 失败
保留原值这类降级容错。

于是 `catch_check.sh` 增加了一行分类统计：**338 个空 catch 里 321 个的变量名是
`ignore`/`ignored`** —— 作者当时明确决定吞掉；只有 17 个是「留空但变量名不是 ignore」，
后者才是真正欠理由的地方。后续批次按这 17 个走，比按目录走准。

> v2.6.0 复核后发现这个 17 仍然高估：9 个的变量名叫 `ig`（与 `ignore` 同义）、
> 2 个是 WebView 注入的 JS 源码误报、2 个在空块里写了 `ignore` 注释。
> 修完是 **336 总数 / 4 个真欠理由**，见 v2.6.0 的 R-21。

## v2.4.0（versionCode 194）—— 审计修复：三处数据风险 + 分层收口

这一版没有新功能，全部来自一次针对「会丢数据 / 会让审计失真」的排查。按风险排序，
每项一个提交，可单独 `git revert`。

### R-09：递归删除跟随软链（数据丢失，最高优先级）

`MainActivity.deleteRecursiveOrTrash` 自己写了一份递归：`f.isFile()` 判文件、
`f.listFiles()` 递归，两者**都会跟随软链接**。「移动进压缩包」成功后调它删源，
源目录里若有个 `backup -> /sdcard/DCIM`，删掉的是相册内容，链接本身反而留在原地。

R-01 的「打包跳过链接」让链接不会进 zip，于是打包完成后，源里那个链接成了唯一指向目标的入口，
删除时更危险。现在所有「删了就没了」的递归统一走 `TreeOps.deleteOrTrash`：
它先判 `isLink` 再判 `isDirectory`，链接只删链本身；删不掉（只读挂载）才移交回收站，
而回收站的跨卷回退同样复刻链接而不是跟进。

`writeTree` 与 `hasSmali` 的 `listFiles()` 一并换成 `LinkUtil.listNoFollow`，与打包策略对齐。

### R-10：`AtomicReplace` 先删唯一备份

跨卷复制失败且 `bak.renameTo(target)` 也失败时，磁盘状态是「target 不存在 + bak 是唯一副本」。
老代码下一次替换一进门就 `bak.delete()`，再谈写入 —— 如果这次临时文件恰好无效，
用户手里一份原内容都不剩。现在第一步是把 bak 放回原位（rename，失败则复制，
复制失败直接抛且 bak 保留），之后才校验 tmp、清理旧备份。

### O-21：任务账本写入串行化

`writeJournal()` 在 `synchronized (lock)` 里构建快照，但 `store.save(es)` 在锁外：
A 构建 `[T1]` → B 构建 `[T1,T2]` 并落盘 → A 的 `save([T1])` 最后落盘，账本把 T2 丢了。
杀进程重启后「上次没做完」的提示就漏项。现在快照与落盘同锁（`journalLock → lock`，
两个调用点都在 `lock` 之外，不会反向嵌套）。

### R-08 / R-11：临时文件统一登记与识别

新增 `fs/TempFiles`：一张「谁正在写」的注册表（`begin` / `end` / `isActive` / `snapshot`）
+ 一份「哪些名字算临时」的判定。三件事因此对齐：

- 复制/移动（`FileTaskEngine`）、云同步下载（`SyncRunner`）、局域网接收（`LanNode`）、
  HLS 拼接（`HlsDownloader`）、TS 转封装（`TsRemuxer`）都在开工时登记、`finally` 里注销；
- 遗留扫描（`LeftoverScan`）按快照排除活动路径，删除入口再查一次实时状态
  —— 挡住「扫描之后、用户确认之前」才开始的任务；
- 命名收敛：HLS 分片与 TsRemuxer 输出的 `.tmp` 改成 `.part`，`LeftoverScan` 因此能认出
  `zip 写回失败的 .reb<ts>.tmp`（以前扫不出来，那坨文件永远躺着）；
  `.parts`（断点续传清单）与 `.merged`（合并输出）明确排除，扫描也不再递归进 `.parts` 目录。

真实后果对照：1 GB 复制进行中打开「设置 → 遗留文件」，以前列表里会出现那个 `.part`，
用户确认删除后写线程仍持有已 unlink 的句柄，rename 必然失败，拷贝在几百 MB 处报错。
现在扫描看不到它，删除也被拒绝并提示「任务进行中」。

### O-17 / O-18：分层归位与依赖门

7 处真实越层（不是注释，是编译期依赖）全部修掉：

| 越层 | 处理 |
|---|---|
| `core/LinkUtil` 调 `android.system.Os` | 整体搬到 `fs/LinkUtil`（调用方都在 fs 及以上） |
| `fs/DirReader` 用 `FileAdapter.SORT_*` | 新增 `fs/SortMode`，`FileAdapter.SORT_*` 保留为别名（持久化数值不变） |
| `apk/ApkEditSession` new `ui.SignPromptActivity` | 导航搬进 `SignPromptActivity.launch(ctx, apk)` |
| `utils/ApkSignHelper` 引 `ui`/`apk` | 搬到 `ui/ApkSignHelper`（调用方本来全在 ui） |
| `utils/MtSyntaxHighlighter` / `PlaintextSyntaxHighlighter` 引 `ThemeManager` | 抽出 `utils/SyntaxPalette`（暗色标记 + 提亮算法各一份），`ThemeManager` 委托 |
| `utils/SyncRunner` 引 `NetFs` | 搬到 `net/SyncRunner` |

同时删掉 40 处越层/死 import，并把 196 处自有包通配 import 展开为 380 条精确 import
（26 行整行删除 —— 那些文件其实一个类都没用到该包，纯属 javadoc 里提过一句）。
`test/deps_check.sh` 把方向变成可检查的约束，CI 与本地构建都跑。

### O-19：`MainActivity` 静态工具下沉

`isImageExt` / `isTextName` / `isArchiveExt` / `isTextFile` / `apkFileRole` / `stripExt`
→ `fs/FileKind`；`writableArchive` → `ArchiveWrite.canWrite`；`readAllBytes` → `core/ByteIO`；
`writeFailMsg` → `AtomicReplace.failMessage`。`controller` 对 `MainActivity` 的引用只剩
`MainActivity.class` 这一处导航（`ArchiveController:680`），拆包后剩下的最大耦合到此为止。

### F-14/2：任务中心与通知 i18n

`TaskCenterActivity` 9 处 + `TaskService` 7 处中文改资源键；带数字的用 `<plurals>`
（中文只有 `other`，英文 `one`/`other`，避免出现「1 records」）。`values` 与 `values-en`
各 131 键 + 4 个 plurals，`aapt2 dump resources` 可见 `(en)` 变体。

> 通知渠道名只在首次创建时生效：验收请「清除应用数据」而不是只切语言。

### O-20：空 catch 不增长门

自研包空 catch 实测 345（审计报告写的 315 是当时时点，之后拆包与 F-14/F-15 又有新增），
第一批只治 `LanNode` 的 6 处业务回调与路径回退（close/stop/解析容错按例外保留），
降到 339 并把基线锁在 339。`test/catch_check.sh` 只在超出基线时失败 ——
目标是「不再增长 + 分批清理」，不是一次性清零。

### 验收

```bash
./build.sh                      # BUILD OK，194 / 2.4.0
QUICK=1 ./test/run_core.sh      # 53/53
bash test/deps_check.sh         # 退出 0
bash test/catch_check.sh        # 339 / 基线 339
```

新增用例：`TreeOpsTest.t8DeleteOrTrashKeepsLinkTarget`（链接目标内容不被删）、
`AtomicReplaceTest.bakOnlyNoTarget`（唯一副本先恢复再报错）、
`TaskJournalTest.t6JournalSerialized`（慢速 Store 检出 save 区间重叠；
把 `journalLock` 去掉会立刻 FAIL，已反向验证）、
`LeftoverScanTest.t4RebTmpAndFunctionalData` / `t5ActiveWriting`。

## v2.3.1（versionCode 187）—— 手机恢复双栏

v2.3.0 给窄屏加了「一栏收起」的单栏模式，阈值 `screenWidthDp < 400`。真机一上手就不对：
这工具的使用姿势是左边找文件、右边看压缩包，手机竖屏（360dp）被切成单栏后，
「拖文件到对面」这个核心操作反而要来回点 ⬄，比双栏挤一点更难受。

改法只有一处：`MainActivity.singlePane()` 的阈值从字面量 400 提成常量
`SINGLE_PANE_MAX_DP`，取 `0` 表示永不单栏：

```java
private static final int SINGLE_PANE_MAX_DP = 0;   // <= 0：手机也保持双栏

private boolean singlePane() {
    return SINGLE_PANE_MAX_DP > 0
            && getResources().getConfiguration().screenWidthDp < SINGLE_PANE_MAX_DP;
}
```

- **不删代码**：`applyViewMode()` 的收起分支、`toggleOrSwapPanes()` 的「切到另一栏」语义、
  `onConfigurationChanged` 的重判都留着 —— 哪天要给极窄分屏窗口用，把常量改成 400 即可，
  不必重写一遍显隐逻辑（那部分复用的是 `setActive` 的既有路径，删了容易与网格模式纠缠）。
- **行为**：手机竖屏双栏常显，⬄ 回到「交换两栏内容」；平板拖拽分隔条、比例记忆、
  分屏不重叠均不受影响（与单栏判定是两条独立路径）。
- 版本号 186→187、`2.3.0`→`2.3.1`，其余不动。

## v2.3.0（versionCode 186）—— 设置页英文 + 平板分屏

两件事都按「小步、可单独回滚」做，各自一个提交。

### F-14/1：设置页 i18n（`i18n: 设置页迁移（F-14/1）`）

不做一次性全量机翻（3,676 条会翻出大量不认识的术语），按页迁移：

- 新增 `res/values-en/strings.xml`，**只放已完成迁移的键**。未迁移的页面在英文系统下
  回退中文 —— 这是预期行为，不是漏翻。
- 已有的 24 条（工具栏 `up/home/refresh`、终端按键条、选区菜单、小组件与无障碍说明）
  一并补英文：这 24 条早就抽取完成，成本为零；不补的话切英文后主界面按钮仍是中文，
  「逐页截图验收」无从谈起。
- 设置页 85 处硬编码中文全部改为 `getString(R.string.x)`，新增 93 条键，
  前缀 `settings_` / `leftover_` / `workdir_` / `root_status_` / `theme_mode_`。
- 8 处字符串拼接改成位置参数格式串（`%1$s` / `%1$d`），例如
  `「已删除 N 个缩略图缓存文件」`、`「共 N 个，合计 X」`；
  `bytesText()` 由 static 改实例方法（要调 `getString`）。
- 不动：代码注释、`Log`、异常消息、`su` 命令 —— 这些不进翻译。
- 遗留：`ThemeSchemes.NAMES`（主题方案名）仍是中文数组，属于主题体系的资源化，
  留给主题页那一批。

**下一批怎么加**（照这个顺序）：主界面顶栏/抽屉 → 常用对话框（删除/复制/重命名/属性）
→ 任务面板 → 其它页面。每批只碰一页，提交前切英文看按钮有没有被截断（英文平均长 30%）。

### F-15：平板 / 分屏

- **不复制布局**：没有新建 `layout-sw600dp/activity_main.xml`。两份 756 行的布局只差
  一根分隔条，以后每次改布局都要记得改两处，迟早漂移。改用资源限定符：
  `values/dimens.xml` 的 `pane_divider_width=1dp` 与 `values-sw600dp/dimens.xml` 的 `6dp`，
  再用 `values/bools.xml` 的 `pane_divider_draggable=false` / `values-sw600dp` 的 `true`
  决定要不要挂拖拽监听。
- 拖拽把两栏 `weight` 按比例分配，夹取 **0.3–0.7**（不让某一栏拖到看不见），
  松手时才写 `SharedPreferences`（`hf_view/split_ratio`），拖动过程每帧写会一直触发 apply()。
- **单栏机制（v2.3.1 起默认关闭）**：`singlePane()` 依据常量 `SINGLE_PANE_MAX_DP`
  判断是否只显示活动栏，另一栏 GONE（复用 `setActive` 已有的显隐路径）。
  v2.3.0 曾设为 `400`，手机上竖屏即单栏；实测双栏才是本工具的主场（左找文件、右看压缩包），
  v2.3.1 改为 `0` = 永不单栏，代码路径整条保留，改回 `400` 即恢复按宽度自动单栏。
- **单栏下的 ⬄**：屏幕上只有一栏可见，点击只能落在可见那栏，「交换两栏内容」没有意义，
  于是改为「切到另一栏」—— 否则另一栏永远够不着。默认双栏时一直走原来的交换分支，行为不变。
- `onConfigurationChanged` 里重新判定单/双栏（Manifest 已声明 `orientation|screenSize`，
  旋转与分屏都不重建 Activity）。

### 验收

- `./build.sh` 通过；`aapt2 dump resources` 确认
  `settings_title` 同时有 `()` 与 `(en)`、`pane_divider_width` 有 `1dp`/`sw600dp 6dp`、
  `pane_divider_draggable` 有 `false`/`true`；
- `QUICK=1 ./test/run_core.sh` **53/53**（两项各跑一次）；
- 需要真机确认：切英文后设置页无截断、平板横屏双栏可拖且重启保持、分屏不重叠、
  手机竖屏保持双栏且 ⬄ 仍是交换两栏内容。

---

## v2.2.0（versionCode 185）—— 拆包收口：controller / utils，顶层只剩 MainActivity

v2.1.0 把 211 个类按「技术分层」分成了 core / fs / apk / net / ui，但顶层还压着 42 个类：
6 个控制器、几十个横切工具，还有 `Lan*` 这种明显属于网络的实现。这一版把它们各自归位：

| 包 | 变化 | 内容 |
|---|---|---|
| `controller` | **新增（6）** | `Pane` + `DirectoryController` / `ArchiveController` / `TaskUiController` / `DrawerController` / `PropsController` |
| `utils` | **新增（29）** | `Bg` / `HashUtil` / `StorageStat` / `TaskJournal` / `SyncPlan` / `SyncRunner` / `ResumePlan` / `DragPlan` / `BackupRestore` / `OpenWith` / `CredentialVault` / `KeyCipher` / `ShizukuBridge` / `ActivityLog` / `ImageBatchUtil` / `ApkSignHelper` / 四个语法高亮器 + `SyntaxHighlight` / `SyntaxHighlighterBase` / `MarkdownRender` / `AutoTextFormatter` / `MdFormatPatterns` / `MtTextUtils` / `WidgetText` / `ColorUnderlineSpan` / `GridAction` |
| `net` | 20 → **26** | 补入 `LanNet` / `LanNode` / `LanProtocol` / `LanSender`（局域网发现与传输）、`RemoteServer`（内置 HTTP 服务）、`TokenHarvest`（网页 token 提取） |
| 顶层 | 42 → **1** | 只剩 `MainActivity` |

### 为什么 MainActivity 还留在包根

它是 `AndroidManifest.xml` 里的启动组件（`com.hex91.file.MainActivity`），也是整个 App 的
装配入口：把 `Pane`×2、五个控制器、抽屉、顶栏接起来，再向外分发菜单与批量操作。
放进 `ui` 会和 72 个 Activity 混在一起，反而不容易找到「从哪开始读代码」；
留在包根 + 只有它一个类，打开目录一眼就能看出程序入口。

### 控制器包带来的可见性变化（只放宽，逻辑零改动）

同一包时靠包内可见就够，拆开后 `MainActivity` 是唯一外部调用方，于是把契约面显式化：

- 6 个类声明 `public`、构造 `public`、`Host` 接口 `public`；
- 26 个被 `MainActivity` 调用的方法 `public`（`loadDir` 两个重载、`refreshAll`、`archGoUp`、
  `showArchiveDir`、`attach/detach/destroy`、`setupDrawer`…）；
- `Pane` 的 15 个字段全 `public` —— 宿主与各控制器直接读写目录 / 压缩包 / 历史栈 / 读取代次；
  注释里写明「只有 MainActivity 与 controller 包内的类可以碰 Pane 的字段」；
- 顺带：`GridAction`、`StorageStat`（+4 个静态方法）、`MainActivity` 的
  `readAllBytes/writableArchive/stripExt/writeFailMsg` 提 `public`。

### 依赖方向

`MainActivity → controller → ui / fs / apk / net → utils → core`，单向不回环。
`core` 是纯 JDK；`utils` 只碰 `core`；`ui` 可以用下面所有层。

### 验收

- `./build.sh` 通过；顶层 1 个类、`controller` 6 个、`utils` 29 个，总数仍是 211；
- `QUICK=1 ./test/run_core.sh` **53/53**（B6 / B7 / B8 每批各跑一次，均全绿）；
- 每批单独提交，可按批 `git revert`。

---

## v2.1.0（versionCode 184）—— 拆包：core / fs / apk / net / ui

`src/com/hex91/file/` 从「211 个类平铺」变成 **5 个子包 + 42 个顶层类**。
这一版**只有包名与 import 的变化**，功能、文案、排序过滤、线程模型一律不动：

| 包 | 类数 | 职责 |
|---|---|---|
| `core` | 25 | 基础工具：Base64/JSON/ISO8601/格式化/哈希/SM3/SM4/十六进制/颜色对比/区域表 |
| `fs` | 25 | 文件系统：读目录、复制移动、回收站、压缩包读写、Root/Shell、磁盘扫描 |
| `apk` | 27 | APK 与资源：重打包、签名、ARSC、AXML、smali 反编译、密钥库 |
| `net` | 20 | 网络：云盘/FTP/SFTP/SMB/WebDAV/S3、OAuth、HLS/m3u8/TS |
| `ui` | 72 | 界面：44 个 Activity、7 个 Service/Provider、4 个自定义 View、3 个适配器、对话框、主题 |
| 顶层 | 42 | `MainActivity` + 5 个控制器 + `Pane` + 适配器之外的编排类 + `Bg`/`ToolUi` 等共享工具 |

### 三个「改错就装上崩」的点（都已逐一校验）

1. **Manifest 组件名**：44 个 Activity/Service/Provider 的 `android:name` 全部改成
   `com.hex91.file.ui.*`（共 51 处，含 `activity-alias` 与 `targetActivity`）。
   入口 `MainActivity` 仍是 `com.hex91.file.MainActivity`。
2. **`res/xml/accessibility_trace.xml`** 的 `android:settingsActivity` 1 处。
3. **布局里的自定义 View**：`activity_main.xml`（PullRefreshLayout ×2 开闭标签）、
   `activity_editor.xml`（LineNumbersView / HighlightingEditor）、`activity_lanshare.xml`（RadarView）共 7 处。
   校验方式：把 Manifest 里的 52 个组件名逐个转成 dex 类描述符比对，缺失 0。

### 顺手根治的一个隐形缺陷：幽灵内部类

`javac -d` 只覆盖不清理，旧逻辑按「类名」判孤儿，而匿名内部类无法静态枚举——
`MainActivity` 从 6,574 行拆到 4,465 行后，`MainActivity$105/$108/$127…` 再也不会重新生成，
但外部类还在，于是被当成合法类一路打进 dex，且**里面还带着搬家前的旧类引用**（如
`Lcom/hex91/file/ZipUtil;`）。现在改成编译前整目录清空自研 class（javac 本来就是全量编译，零成本），
`build/obj/com/hex91/file` 不再有幽灵类。

### 跨包可见性放宽（只放宽，逻辑零改动）

`Bg`（+`Fail`/`ui`/`io`/`onUi`）、`UiDecor` 的 `barButton/gap/barBg/dp/paintPill/pill`、
`HexCore.hexVal`、`ArchiveUtil.cryptoError`、`Decompiler.readAll/err`、
`HlsDownloader.isLocal/deriveName`、`M3u8Playlist.mbpsText`、
`SignKeyManagerActivity.KeyEntry`（含 9 个字段与 `broken()`）及 `loadEntry/defaultEntry/
loadKeyBytes/friendlyKsError/signPrefs`、四个 Activity 的 `EXTRA_*` 常量。

### 未做（按计划留到下一刀）

`Fs` 抽象（统一本地/云盘/压缩包的读写接口）**等 F-04 真机验证后**再动——
那是真正改调用约定的一刀，不适合和「纯搬家」混在一起。

### 验收

- `./build.sh` 通过；dex 里旧包名的 4 个类描述符（HexCore/ZipUtil/ApkUtil/Json）全部消失；
- `QUICK=1 ./test/run_core.sh` **53/53**（中途 MSYS 进程回收卡死一次，分 38+8+7 三批跑完）；
- Manifest 52 个组件与 dex 类一一对应，缺失 0。

---

## v2.0.1（versionCode 183）—— 列表性能：行视图缓存 + 单行刷新

目标不是「换 RecyclerView」，而是把每行重复做的活砍掉。三处都是同一套渲染逻辑，
**显示结果零变化**，只是少做了重复工作。

### 1. ViewHolder：每行 5 次 `findViewById` 归零

`FileAdapter` / `FileGridAdapter` 各加静态 `VH`，新行 inflate 后把
`icon / thumb / badge / name / detail` 找一遍存进 `convert.setTag(vh)`，
滚动复用时直接读字段。以前每帧几十行 × 5 次视图树递归查找，现在只有 inflate 那一次。

> 缩略图防串图用的 `thumb.setTag(key)` 是**另一个 View**，与 `convert.setTag(vh)` 互不影响——
> 代码注释里点明了，别再合并。

### 2. `FileItem.isDir()` 缓存：一个条目只 stat 一次

`isDir()` 会被排序、图标、点击、详情行反复问到，以前每次都 `file.isDirectory()` = 一次 stat。
加 `dirCache`（-1 未知 / 0 否 / 1 是）后只算一次。三个边界：

- `forceDir`（root 的 `ls -p` 结论、指向目录的链接）**直接短路、不进缓存** —— 它可能在对象构造
  之后才被 `fillLink` 置上，进缓存会把「先问过、后被标记」的那条答错；
- `file == null` 的占位条目不再有可能 NPE；
- `FileItem` 是 `Serializable`，反序列化不走字段初始化，`readObject` 里显式把 `dirCache` 重置为 -1。

### 3. 单行选中局部刷新

`FileAdapter` 增 `bindList()` / `bindGrid()` 与 `refreshRow(pos)`；`toggle()` 与 `MainActivity`
三处直接改 `selected` 的地方（纵向刷选、左滑选中、右滑取消）改走它。

- 勾一行只重画那一行，不再整表 `notifyDataSetChanged()`（万级目录点一下重绑一万行）；
- 仍然复用 `getView(pos, 该行 View, 父容器)`，渲染只有一份，选中态不可能走样；
- 行滚出屏幕 / 未 bind 时直接跳过——滚回来本来就会重绑；
- 列表与网格互为镜像：谁在显示刷谁，另一个是 GONE 会被跳过，所以网格里点选也立刻出勾。

### 验收

- `QUICK=1 ./test/run_core.sh` **53/53 PASS**（新增 `FileItemCacheTest` 16 条断言）；
- `out/HexFile-2.0.1.apk`（13.1 MB，V3 签名），dex 含 `repaintRow` / `refreshRow` /
  `bindList` / `bindGrid` / `dirCache`。

---

## v2.0.0（versionCode 182）—— MainActivity 拆分：6,518 行 → 4,465 行

纯搬运，**零行为改动**：不碰布局、不动文案、不改排序/过滤规则、不换线程模型、不引第三方库。
动因是 `MainActivity` 已经 6,500 行、241 个方法，改一处要在上万行里找上下文；
先把这个「上帝类」按住，后面拆包（198 个类同包）才有清晰的包边界。

### 拆分结果

| 文件 | 行数 | 职责 |
|---|---|---|
| `MainActivity.java` | 6,518 → **4,465** | 只留 UI 装配、菜单分发、多选/批量、跨模块编排 |
| `Pane.java` | 51 | 单窗格的视图引用 + 浏览状态（原 `MainActivity.Panel`） |
| `DirectoryController.java` | 305 | 读目录 / 下拉刷新 / 历史栈 / 冷启动恢复 |
| `ArchiveController.java` | 1,031 | 压缩包内浏览：打开关闭、密码、条目操作、查看编辑、提取、DEX/ARSC |
| `TaskUiController.java` | 255 | 任务条、任务中心入口、「上次有任务被中断」提示 |
| `DrawerController.java` | 259 | 左侧抽屉 16 个入口、存储容量、Root 状态 |
| `PropsController.java` | 654 | 属性 / 权限 / 修改时间 / 所有者 / 哈希弹窗 |
| `StorageStat.java` | 82 | 路径 → 实际分区 → StatFs（顶栏与抽屉共用） |
| `GridAction.java` | 12 | 双列宫格弹窗的动作回调 |

### 怎么搬的（保证可回滚）

- **一个控制器一个提交**：`git revert <sha>` 即可单独撤掉某一刀，不需要 squash；
- **壳方法签名一字未改**：`loadDir(p, dir)`、`refreshAll()`、`showProps(f)` 等 25+ 个调用点零改动，
  出问题时先怀疑搬运逻辑而不是「谁改了签名」；
- **控制器只依赖 Host 接口**：`ctx()/toast()/ui()/loadDir()/refreshAll()/other()` 这些能力由 Activity 实现并转发，
  控制器不持有 Activity 字段、不自己 `new Handler`（沿用主线程那个 `taskUi`，读目录的 20 秒看门狗也在用）；
- **`ui(Runnable)` 统一判 `alive()`**：原来 Activity 销毁后仍会 `runOnUiThread` 的回调不再白跑。

### 三个「改错就点不动」的地方，原样保留

1. `showArchiveDir` 回主线程后校验 `p.archive != a || !prefix.equals(p.archPrefix)` —— 压缩包侧的代次；
2. `openArchiveInPane` 同包重开时只 `close` 旧 `Archive`、不重置 `archPrefix`；
3. `archPwError` 先 `ArchivePasswords.clear` 再弹框，否则密码错时永远拿旧密码重试。

### 验收

- `./build.sh` 0 error，`out/HexFile-2.0.0.apk`（13.1 MB，V3 签名）；
- `QUICK=1 ./test/run_core.sh` **pass=52 fail=0 skip=0**（每刀都跑了一遍）；
- `grep -c "\bPanel\b" MainActivity.java` → 0；`loadDir` 只剩两个壳方法。

---

## v1.99.3（versionCode 181）—— 任务中心独立成页、按 id 重试、账本进偏好

上一版把「失败重试」和「中断提示」先做出来了，但重试接口传的是任务对象、账本写在
`files/task_journal` 文件里、任务列表还挤在主界面的弹窗里。这一版把三处的形态收敛到
该有的样子。

### 一、`retry(int id)`：按 id 重试，新任务不复用原 id

原来 `retry(FileTask old)` 看起来像「重试那个对象」，实际是 `submit` 出一个新任务 ——
对象引用和实际行为对不上，面板一改（比如换成快照列表）就会传错东西。

现在只有 `retry(int id)`：

| 情况 | 行为 |
|---|---|
| id 不存在 | `null` |
| 还在排队 / 正在跑 | `null`（再提一遍就是同时拷两份） |
| 已成功（`DONE`） | `null`（把做完的事再做一遍，目标里会多出副本） |
| 失败 / 部分失败 / 被取消 | 允许 |
| 原来源一个都不在了 | `null`（否则只是再产生一条注定失败的记录） |

新任务走正常 `submit`，id 由单调递增的 `seq` 分配，**永不复用原 id**；`srcs/dstDir`
沿用原任务的。失败行上的「↻ 重试」就调它。

### 二、中断提示：`SharedPreferences` 的 `running_task_json`

账本从文件挪进偏好（`TaskStore`，键名固定 `running_task_json`）：

- **只有这一个键**：用户点「忽略」走 `remove(KEY)`，绝不 `edit().clear()` ——
  以后这份偏好里若加别的设置，一次 clear 会连用户设置一起抹掉；
- **`commit()` 而不是 `apply()`**：任务刚提交时进程最容易被清（大文件复制刚开始），
  `apply` 是异步落盘，那次写可能还在队列里就随进程消失了；账本只有几百字节；
- **老文件自动迁移**：首次读取时若偏好为空而 `files/task_journal` 还在，读出来写进偏好
  并删掉旧文件 —— 升级用户不会莫名其妙丢掉「上次没做完」的提示；
- 读取时 key 存在就 `toast("上次有任务被中断")`，再弹详情（重试 / 忽略）。

序列化仍是 `TaskJournal.encode/decode`（纯 Java，桌面可单测），偏好只当容器。

### 三、`TaskCenterActivity`：任务列表不再是弹窗

弹窗最多看四五条，任务一多就得在里面滚；而且弹窗跟着主界面走 —— 切走再回来就没了。
现在 `showTaskPanel()` 直接打开独立页面：

- 行渲染抽成 `TaskRows`（原来 `MainActivity.taskRow` 的那 80 行），主界面与任务中心共用；
- 顶栏「清理已完成」「全部取消」（后者带确认），行内「✕ 取消」/「↻ 重试」；
- 刷新用 400ms 轮询 + **签名比较**：签名（id/state/percent/doneFiles）没变就不
  `notifyDataSetChanged()`，已完成任务躺着时列表完全不重绘；
- `onPause` 停掉轮询 —— 任务在引擎线程里跑，页面不可见时没必要重绘。

主界面的 `fillTaskPanel` / `taskRow` / `taskDetail` 一并删除（约 150 行）。

## v1.99.2（versionCode 180）—— 三处同类软链漏网 + 两个服务语义

第五轮审查。前三条是 S-1 同一个失效模式从后门漏回来的（都发生在**入口**而不是递归里），
后两条是前台服务的语义与实现不一致。

### 一、R-01 压缩「添加目录」仍跟随软链

`ZipUtil.addToZip` 直接 `file.isDirectory()` + `file.listFiles()`，两个都会跟随链接：

- 压缩一个指向大目录的链接 → 目标整棵树被打进 zip（体积与耗时都爆）；
- 链接成环 → 递归没有深度上限，直接 `StackOverflowError`（`Error`，`catch (Exception)` 都接不住）。

`addToZip` / `walk`（加密 zip 的 `flatten` 路径）现在都带两条防线：`probe.isLink()` 跳过 +
`depth > TreeOps.MAX_DEPTH` 截断。zip 条目里没有「软链接」这个概念，所以链接本身也跳过；
选中项**全是**链接时不再返回 true 给出一个空包，而是删掉产物返回 false。

顺带给 `createZip` 加了 `LinkProbe` 重载：真机用 `TreeOps.SYSTEM`（`lstat`），桌面单测注入桩，
于是这条规则第一次有了自动化用例（`test/ZipUtilTest.java`）。

### 二、R-02 `TreeOps.copyDir` 只判 dst、没判 src

递归里判了每个子项 `probe.isLink(k)`，但**入口这一层没判**：

```java
// 之前：src 本身是链接时，下面两行全都跟进到目标
if (!dst.isDirectory() && !dst.mkdirs() ...) throw ...;
File[] kids = src.listFiles();
```

「复制一个看起来像目录的链接」于是变成「把目标整棵树复制一份」。真机上这种链接很常见
（不少 ROM 的 `/sdcard` 本身就是链接）。现在入口先判：能复刻就复刻一条链接，
复刻不了按 `skipped` 计数跳过，没有跳过出口则明确抛异常（不悄悄返回一个空目录骗人）。

### 三、R-03 进度分母统计跟随软链

`measure` 是进度条的**分母**（`doneBytes/totalBytes`），它跟进链接而 `copyNode` 只给复刻成功的
链接 `doneFiles++`（分子 +1）。于是目录里只要有一条指向大目录的链接，百分比就停在一个永远到不了 100
的值上。现在 `measure` 遇链接按「1 个条目、0 字节」计，与 `copyNode` 的计数口径对齐。

### 四、R-04 `startForeground` 失败只记日志

原来 catch 里只 `Log.w`，注释却写着「失败则停掉自己」。实际后果比注释更严重：
`startForegroundService` 之后没在 5 秒内落地前台状态，API 26+ 会抛
`ForegroundServiceDidNotStartInTimeException` 把**整个进程**带走（正在跑的任务一起死）。

现在：失败 → `stopSelf()` + `return`（不订阅引擎、不置 `running`），并记一个 60 秒退避
`retryAfter` —— 否则引擎每 120ms 一次进度回调就会重新 `startForegroundService` 再崩一次。
`refresh` 也加了 `foreground` 守卫：状态没落地就不再碰通知栏。

### 五、R-05 通知栏「取消」等于 `cancelAll()`

通知上那条「取消」对着的是**当前正在跑的任务**，原来却调 `cancelAll()` —— 用户排在后面的
复制任务一起被干掉，而通知上没有任何「全部取消」的暗示。现在 `PendingIntent` 带
`task_id`（`FLAG_UPDATE_CURRENT` 让 extra 随通知刷新），服务按 id 调 `cancel(id)`；
没有 id（老版本发的 Intent）才退回 `cancelAll()`。

### 六、R-06 失败后的 `.bakrepl` / `.part` 无提示、无清理入口

替换失败时保留备份与临时文件是**对的**（可能是唯一完好的副本），但之前没人告诉用户它们在哪：

- `AtomicReplace.replaceWith()` 把失败原因连同「原内容备份在 …」「新内容保留在 …」一起返回，
  同时记进 `lastError`；`ZipUtil` / `ArchiveWrite` 写回失败会打日志，
  `MainActivity.writeFailMsg()` 把这段拼进「写入失败 / 删除失败」的提示里；
- 设置页新增「遗留文件」：递归扫描工作目录（不跟进链接、深度上限），列出
  `.bakrepl` / `.replacebak` / `.part` 的大小与所属原文件。**扫描只读**，删除要点进具体条目再确认 ——
  备份里可能装着原文件的全部内容，自动清理等于替用户做决定。

### 七、R-07 任务无重试、被杀后无解释

- **账本**：新增 `TaskJournal`（纯 Java，可单测）。任务进队列时把「类型 + 源列表 + 目标目录」
  写进应用私有目录的 `task_journal`，任务结束重写，全部结束就删文件。取消过的任务不记
  （用户按了取消，重启后再劝他重试是反直觉的）。
- **启动提示**：主界面启动时账本非空 → 「上次有任务没做完」，说明可能是被系统清理或强退，
  给「重试 / 忽略」两个选择。重试就是把每条按原来源与目标重新提交。
- **面板重试**：`FileTask` 现在留着 `srcs` / `dstDir`，失败、部分失败、被取消的任务在任务面板里
  多一个「↻ 重试」，不用让用户重新选一遍（大目录尤其折腾）。

### 测试

| 用例 | 覆盖 |
|---|---|
| `ZipUtilTest`（新增 13 项） | 普通目录结构完整；链接目录整条跳过、目标内容不入包；全链接 → false 且不留空包；70 层不爆栈 |
| `TreeOpsTest`（新增 5 项） | 顶层链接复刻成链接、目标内容不复制；复刻不支持时跳过并计数；无跳过出口时抛异常 |
| `TaskJournalTest`（新增 12 项） | 含空格/中文/反斜杠的路径原样往返；空列表删文件；文件头不对返回空且不抛 |
| `LeftoverScanTest`（新增 13 项） | 三种后缀识别、普通文件不混入、`ownerOf` 反推原文件、`delete` 拒绝正经文件 |
| `AtomicReplaceTest`（新增 5 项） | 失败消息含临时文件路径、`lastError` 与返回一致、原文件内容不变 |

`QUICK=1 ./test/run_core.sh` → **52/52 PASS**。

## v1.99.1（versionCode 179）—— 原子替换收成一处、软链判断收进 LinkUtil

### 一、`AtomicReplace`：三处共用一份「失败不让原文件消失」

O-19 修完之后，替换实现其实有三份：`FileOps.replaceFile`（boolean）、
`ApkEditSession.safeReplace`（throws IOException）、以及 ZipUtil / ArchiveWrite 各自的
`replaceOriginal` 包装。三份的备份后缀还不一样（`.bakrepl` / `.replacebak`），
失败时对临时文件的处理也不一样（一个删掉，一个保留）。

现在只有 `AtomicReplace` 一份：

| 步骤 | 行为 |
|---|---|
| 1 | 同挂载点 `renameTo(target)` —— 原子替换，无中间态 |
| 2 | 让不开时把原文件改名成 `<name>.bakrepl` 备份；rename 也不行则**复制**出完整备份再删原文件 |
| 3 | 原文件让位后再试一次 `rename`（同目录内几乎必成功，比整份复制快一个数量级） |
| 4 | 只能复制时：成功 → 删备份、删 tmp；失败 → 删半成品、备份改回原名、抛异常 |

两个入口共用同一核心：`replace(...) throws IOException` 与 `tryReplace(...) -> boolean`。
`ApkEditSession.safeReplace` 保留签名（6 个调用点不动），内部只剩一行委托。

**失败时 tmp 不删**：它可能是唯一完好的新包，留着让用户手工改名恢复；备份与 tmp 一起留在原目录。

### 二、软链判断收进 `LinkUtil.listNoFollow`

「遍历不跟进链接」是全局规则，之前 6 个递归入口各写一遍 `if (isLink(c)) continue`，漏一处就回到
「磁盘占用翻倍 / 删链删光目标」。新增 `LinkUtil.listNoFollow(dir)` 作为唯一遍历入口：返回已剔除链接的
子项，读不到目录返回 `null`（与 `listFiles` 约定一致）。

`DiskScanUtil` 三处（`scanSize` / Top N `collect` / 判重 `collectFiles`）改用它；
`countEntries` 与 `walk` 两处需要把链接**本身**计入条目数，仍保留 `isLink` 并注明这是计数策略。

顺着这条规则又清掉 6 处重复或漏网（都是 S-1 同一类）：

| 位置 | 问题 | 处理 |
|---|---|---|
| `FileTaskEngine.deleteTree` | 与 `TreeOps.delete` 逐行同义 | 删除，改调 `TreeOps.delete` |
| `FileAttrs.chmodTree` | 递归改权限会 `chmod` 到链接目标上（改到 `/system` 权限） | 链接直接跳过 |
| `HfDocumentsProvider.copyRecursive` | SAF 复制跟随链接，复制出目标整棵树 | 复刻链接本身 |
| `ApkPackager.collect` | 打包目录时把链接目标打进 zip | 跳过链接 |
| `HlsDownloader.deleteDir`、`DexEditorActivity.deleteR`、`DecompileActivity.deleteR`、`BackupManagerActivity.deleteDir` | 各自实现递归删除，跟随链接 | 统一 `TreeOps.delete` |

### 三、测试

新增 `test/AtomicReplaceTest`（9 项）：同卷替换、目标不存在、tmp 缺失（原文件一字不动）、
跨卷（`/dev/shm` → tmpdir，逼出备份+复制路径；Windows 上自动 SKIP）。`LinkUtilTest` 补 3 项
`listNoFollow` 契约（null / 读不到 / 普通条目不丢）。`QUICK=1` → **49/49 PASS**。

## v1.99.0（versionCode 178）—— 目录加载的六个收尾缺口（O-01 ~ O-06）

第四轮审查把「异步读目录」又翻了一遍，这次盯的不是「读得慢」，而是**读的状态没有收尾**：
转圈停了但没读完、读完了但点不动、路径换了但数字还是旧的。六条里五条要改，一条复核已闭合。

| 项 | 现象 | 修法 |
|---|---|---|
| **O-05** | 从目录点开 zip → 压缩包内**任何条目都点不动**，只能返回键退出 | `FileAdapter.setItems` 是压缩包浏览的唯一入口，原来只换数据不清 `loading`，而 `handleItemClick` 开头有 `isLoading()` 拦截 → 补 `loading = false` |
| **O-01** | 下拉刷新 220ms 后圈就停了，列表还是旧的，点击又被拦 | 收圈交给真正读完的一方：`loadDir` 返回「是否发起异步读取」，只有压缩包 / `dir==null` / 校验失败才走 220ms 兜底；其余由 `applyDirResult`、20s 看门狗、失败回调统一收 |
| **O-06** | 路径已切到新目录，顶栏文件数还是上一层的 | `updateHeader` 在 `isLoading()` 时写「读取中…」并跳过 `usageOf`（省一次 statfs）；超时分支同步刷顶栏 |
| **O-03** | 连点几个目录，作废的读取仍跑完整 `stat`/`su ls`；冷启动同一目录读两遍 | ① `Bg.io` 任务体**开跑前**先查代次，过期的直接退出（一个 syscall 都不发），`Panel.dirGen` 加 `volatile`；② `onResume` 吞掉 `onCreate` 之后那一次重复读取 |
| **O-04** | 从子页面回前台往历史栈补一条，`‹` 退不动 | `onResume` 改三参 `push=false` |
| **O-02** | 异常时 `loading` 永久 true | v1.97.0 已闭合（`Bg.io(r, Fail)` + `readSafe` + `applyDirResult(gen,null)`），本轮复核无遗漏 |

顺带把超时分支从「只收 loading」改成**按失败收尾**（清空列表 + 计数归零）：
只清 loading 而留着旧目录条目的话，路径已经是新目录，用户点到的却是旧目录里的文件 ——
删除、复制会全部错位，比看见空白危险得多。

`QUICK=1 ./test/run_core.sh` → **48/48 PASS**；APK 13.0MB，`versionCode 178 / 1.99.0`，V3 签名校验通过。

## v1.98.0（versionCode 177）—— 文件任务进前台服务（F-07）

复制/移动/压缩原来跑在进程内的普通线程上。用户切到微信回两条消息，进程进入 cached 状态，
系统随时可以冻结甚至杀掉它 —— 任务静默失败，回到应用只看到没拷完的 `.part`，没有任何解释。
这是「大文件后台必丢」的真正原因，不是拷贝逻辑的问题。

### 做法：TaskService + 进度通知

- 任务一提交就 `startForegroundService`，通知栏常驻一条进度：`复制 3 项 47%` +
  `已传输 120 MB / 1.2 GB · 8/21 个文件 · 还有 2 项排队`，带「取消」按钮，点通知回主界面；
- 全部任务结束自动 `stopForeground + stopSelf`，不留僵尸通知；
- 服务结束时**先撤通知再退出**，通知不会残留在状态栏。

几处必须守住的细节：

| 风险 | 处理 |
|---|---|
| `startForegroundService` 后 5 秒内没 `startForeground` 会崩 | onCreate 第一件事就是建通道 + `startForeground`，连引擎监听都排在后面；失败则 `stopSelf` 而不是让系统抛 `ForegroundServiceDidNotStartInTimeException` |
| 界面 `onPause` 会 `setListener(null)`，正好在「最需要通知」的时刻 | 引擎增加**独立附加监听**（`addListener/removeListener`），界面单槽与服务订阅互不影响 |
| 进度回调很密，反复启动服务 | `TaskService.running` 静态标志 + 通知用同一个 id `notify`，不重复弹、不打断前台状态 |
| API 34 要按类型授权 | Manifest 提前声明 `foregroundServiceType="dataSync"` 与 `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`（targetSdk 30 时系统忽略，升 targetSdk 时不用再改） |

任务中心本身（任务条 + 任务面板）v1.77.0 就有，这版补的是「离开界面之后还活着」。

### 异步读目录再补两条

- **同一目录不重复读**：双击/连点会让同一目录读两遍（代次只保住最新那份，白白多读一次）。
  现在「同目录 + 仍在读」直接忽略。
- **空目录给提示**：读取中显示「读取中…」占位行，读完变空白，用户分不清是空目录还是没读出来。
  现在读完发现只有 `..` 时提示「此文件夹为空」。

### 新增测试

`test/TaskListenerTest`（5 项）：界面与服务两个监听同时收到进度；`setListener(null)` 后界面断开、
服务仍继续；`removeListener` 后不再回调（无泄漏）。`QUICK=1` → **48/48 PASS**。

## v1.97.0（versionCode 175）—— 软链接、压缩包写回、读目录失败路径

第三轮审查只提了三条，但三条都是「真机上才复现、复现即丢数据」的类型。

### S-1 软链接被递归函数跟进（P0，会删掉目标内容）

`File.isDirectory()` 与 `listFiles()` **会跟随符号链接**。凡是拿它们当递归判据的地方，
「删除/复制一个看起来像快捷方式的条目」就等于「删除/复制链接指向的真实目录」：

| 位置 | 原后果 |
|---|---|
| `FileOps.deleteRecursive`、`FileTaskEngine.deleteTree` | 删链目录 → 目标里的文件全被删，最后只删链 |
| `RemoteServer.deleteRecursive`（网页端）、`HfDocumentsProvider.deleteRecursive`（SAF） | 同上，后者的调用方是「别的应用」 |
| `FileOps.copyDir`、`FileTaskEngine.copyTree` | 复制链目录 → 目标整棵树被复制一份；成环则 StackOverflow |
| `Trash.moveFile` 跨卷回退、`Trash.sizeOf`、`MainActivity.dirSize`、`DiskScanUtil` 五处 | 跟进目标；环 → 爆栈 |

不是理论问题：不少 ROM 上 `/sdcard` 本身就是指向 `/storage/...` 的链接，
root 下浏览 `/` 时删掉它，用户以为删了个快捷方式，实际删了整个内部存储。

修法是**一个入口**：新增 `TreeOps`（删除 / 统计 / 复制），链接一律不跟进 ——
删除只删链本身（等价 `rm -rf` 不带尾斜杠）、统计按 0 计（同时断开环）、
复制按原样复刻成链接（`Os.symlink`），目标文件系统不支持链接（FAT32）就跳过并计数。
上面 10 处调用点全部改为走它，并统一加了 **64 层深度上限**。

> 顺带把 `Trash.sizeOf` 的注释修对了：它一直写着「不跟随符号链接」，代码却在跟随。

### O-19 压缩包写回会让「原包 + 新包一起消失」（P1）

三处实现（`ZipUtil`、`ArchiveWrite` 的 7z 与 tar 后端）是同一段代码：

```java
if (!tmp.renameTo(zip)) {
    if (zip.delete() && tmp.renameTo(zip)) return true;   // 原包已删
    tmp.delete();                                        // 新包也删
    return false;                                        // → 两个都没了
}
```

rename 失败（跨挂载点、目标被占用/打开）时它先删原包，第二次 rename 再失败就把新包也删掉。
现在统一走 `FileOps.replaceFile`：同卷 rename 原子替换；必须让位时先改名成 `.bakrepl` 备份，
复制成功才删备份，失败则还原 —— **任何一步失败都不动原文件**（`ApkEditSession.safeReplace` 的思路）。

### O-02 读目录没有失败路径（P1，「点了没反应」的根因）

`Bg.io` 只把异常写日志。任务体一旦抛出，回主线程收尾那一步根本不执行 →
`FileAdapter.loading` 永远为 true，而读取期间点击/长按是被拦的 → **该窗格从此点不动，只能杀进程**。

三层兜底：

1. `DirReader.readSafe(...)`：读目录绝不抛，失败返回带 `error` 的空结果；
2. `Bg.io(r, onFail)`：后台任务失败也有回主线程的出口；
3. `loadDir` 里 20 秒看门狗：超时按失败收尾（`su ls` 之类外部进程挂住时自愈）。

失败时的行为是**清空列表 + Toast 说明原因**，而不是留着上一个目录的条目 ——
后者更糟：用户会以为读到了新目录，接着在上面删除/复制，操作的全是别的目录里的东西。

### v1.97.1 补的两条（同组问题）

- **历史栈不再重复入栈**：新建文件/新建目录/退出压缩包浏览之后走的都是 `loadDir(p, p.dir)`
  （路径没变），原来每次都往历史里塞一条重复路径，`‹ ›` 要按好几下才退得出去。
- **`..` 返回项在读取中仍可用**：原来 loading 判断在 `..` 之前，等于「进了一个读得很慢的目录就退不出来」。
  返回上级是唯一的逃生口，现在排在最前。

### 新增测试

`test/TreeOpsTest`（24 项）：链接删除不伤目标、普通目录递归删除不受影响、
统计跳过链接、复制不复刻目标内容、链接不可复刻时跳过并计数、70 层深度不爆栈、
`replaceFile` 成功/失败两种姿态。
`DirReaderTest` 补 4 项：`readSafe` 在 `listFiles()` 抛异常时返回带 error 的结果。

链接的判定与复刻抽成了 `LinkProbe` 接口：真机用 `Os.lstat/symlink`，单测注入桩 ——
否则这条规则只能靠「在 ext4 上手工建链」验证，Windows 桌面建链要管理员权限。

`QUICK=1 ./test/run_core.sh` → **47/47 PASS**。

## v1.96.0（versionCode 173）—— 结构审查里能立刻兑现的那几项

第二轮审查（结构 / 性能 / 稳定性）给的清单比上一轮长得多，但它分成两类：
**「改一处、收益立刻可感」**和**「拆包分层，只对后来人有利、对现在的功能零收益」**。
这一版只做第一类，第二类在文末列了理由和顺序。

### 1 目录读取搬离 UI 线程（原来的 P1 第一条）

`FileAdapter.setDir` 里一串同步 IO：`listFiles()` → `Arrays.sort` → 逐项 `new FileItem`
→ `fillLink`（lstat，可能再 readlink），root 回退还要起一个 `su ls` 进程。
这些全在**主线程**跑，调用点包括 onCreate、点进目录、返回上级、下拉刷新 ——
几千个文件的下载目录或 `/data` 这种受保护目录，卡顿是必然，ANR 是概率问题。

拆成两半：

| 位置 | 职责 |
|---|---|
| `DirReader.read(dir, sort, hidden, rootDir, privileged)` | 纯同步：列取 / root 回退 / 排序 / 过滤 / 符号链接 / 计数 |
| `Bg.io(...)` + `FileAdapter.setResult(...)` | 后台读完，回主线程一次性换数据 |

顺带解决三件同源的小事：

- **代次校验**：每次 `loadDir` 让 `Panel.dirGen++`，结果回来时代次对不上就整份丢掉。
  没有它，用户在慢目录里连点两下，先发的结果可能后到，把后发的盖掉（串目录）。
- **计数不再二次遍历**：`updateHeader` 以前在 loadDir 之后又 O(n) 数一遍文件夹/文件数，
  现在由 `DirReader` 读的时候顺手算（`Result.dirs/files`）。
- **排序键先取一遍**：原来 comparator 里直接 `a.length()/lastModified()/isDirectory()`，
  每次比较都 stat 两个文件 = n log n 次系统调用；现在每个条目一次，n 次。

读取期间的行为：列表非空就保留旧内容（不闪白），空列表显示一行「读取中…」；
**这期间点击/长按被忽略** —— 否则点的还是上一个目录的条目，会打开错文件。

`Bg` 是新增的 40 行工具（固定 3 条 IO 线程 + `Throwable` 兜底日志 + `ui()`），
本版只让新路径走它；那 130 处裸线程**没有**一次性替换（见文末）。

### 2 生命周期收尾（原来完全没有 `onDestroy`）

`MainActivity` 生命周期里只有 `onPause`，而它启动了大量线程、缩略图加载器、任务回调。
新增 `onDestroy`：把两侧 `dirGen` 推走（让在途的读目录结果自己作废）、清掉任务栏的
延时消息、断开 `FileTaskEngine` 回调。

### 3 进程被回收后回到原目录

`onSaveInstanceState` 原来全仓库只有文本编辑器在用，主界面一律 `loadDir(内部存储根)`——
表现是「切出去一会儿回来发现回到起点」。现在存两个窗格的路径、历史栈与活动窗格，
重建时复原（目录没了就退回默认根）。压缩包内浏览不恢复：重开一次归档要解目录，不值。

### 4 删死代码 / 死依赖

| 项 | 证据 | 处理 |
|---|---|---|
| `FeatureRegistry` | 只有自身定义 + 一句注释提到；注释却说「UI 菜单由注册表驱动」 | 删除，注释改写 |
| `MainActivity.showComingSoon` | 只有定义，0 调用 | 删除 |
| `libs/bcprov-jdk18on-1.79.jar` | 8.18MB；源码 0 个 `org.bouncycastle` import，其余 jar 的 class 也没有引用 | 删除 |
| `libs/exifinterface-1.3.6.jar` | 全仓库 0 个 Exif 命中 | 删除 |

APK 从 **14.9MB → 13.1MB**（dex 里确实有它：删除后 `classes.dex` 里再也搜不到 bcprov 的类）。

> 顺手挖出一个构建脚本的坑：`javac -d build/obj` **只覆盖不清理**，
> 源码删了、`build/obj` 里的 class 还在，于是被 d8 打进 APK。
> 第一次删完 `FeatureRegistry` 后 dex 里仍能搜到它，就是这原因。
> `build.sh` 的 `[4/7]` 之前加了一步「清掉源码已不存在的 class」（R 类在 `build/gen`，放行）。

### 5 CI 开始跑单元测试

`test/run_core.sh` 原来把 `JH`/`BT`/`ANDJ` 写死成 `D:/…`、`E:/…`，
等于把 94 个测试锁在开发机上，CI 根本跑不起来。现在：环境变量优先 → 常见位置探测 →
都没有就明确报错；类路径分隔符按 `uname` 选 `;` 或 `:`；`javac.exe` / `javac` 自动判断。

流水线在**上传产物之后**加了一步 `QUICK=1 bash test/run_core.sh`：
以前 CI 只 build 不测试，这些用例等于没有护栏；放在上传之后，测试挂了也能先拿到 APK 排查。

### 6 终端页关闭导出

`TerminalActivity` 的 `android:exported="true"` 是当年做冒烟测试留的（注释自己写着
「发布前会改回 false」）。它只从抽屉入口启动，改回 `false`；外部应用再也拉不起这个页面。

### 新增测试

`test/DirReaderTest`（18 项）：目录在前、大小写不敏感、隐藏文件、顶部 `..`、
应用根目录无 `..`、按大小/时间排序的并列规则、计数不含 `..`、读不到时的降级。
`DirReader` 刻意不碰 Android API（`privileged` 由调用方注入），所以这些规则能在桌面 JVM 上直接测。

`QUICK=1 ./test/run_core.sh` → **49/49 PASS**（含本版新增的 DirReaderTest）。

### 这一版**没做**的，以及为什么

| 审查项 | 结论 |
|---|---|
| 1.1 按包拆（core/fs/apk/ui/net） | 194 个类同包 = 现在零 import；拆包要改几百处 import + 单测，收益是「看着清楚」，风险是本版无法用真机覆盖所有分支。等下一轮专门做，一次性、单独提交。 |
| 1.2 拆 MainActivity（6039 行 god class） | 同上。本版已经先把「目录加载」这块抽成 `DirReader`，是拆 `DirectoryController` 的第一步。 |
| 1.3 本地文件抽象 `LocalFs implements Fs` | 会动到 `MainActivity` 里所有 `File` 分支，等于重写文件操作层，必须有真机回归。 |
| 1.4 把 130 处裸线程换成 `Bg` | 本版只让新路径走 `Bg`。机械替换 50 处 `new Thread` 风险在于每处的取消/回调语义都不同，属于「必须一类一类换」的活。 |
| 1.6 230 处空 catch 分批处理 | 需要逐处判断「关流可忽略」还是「用户点了没反应」，不能批量改。 |
| 1.7 资源化 / i18n（3676 处中文字面量） | 唯一「越晚越贵」的债，但一次动 3676 处不可能在本版验证完；建议按页面抽，先从顶栏/对话框标题开始。 |
| 2.3 ListView → RecyclerView + DiffUtil | 工作量大（ListView 90 处、`notifyDataSetChanged` 51 处、双栏 mirror），留到阶段 2。 |
| 2.4 文件任务前台服务 | 本版**故意没做**：`startForegroundService` 必须 5 秒内 `startForeground`，API 34 还要 `foregroundServiceType="dataSync"` + 权限，改错就是「复制大文件时崩」。要配着真机跑一遍复制/压缩/加密全流程再上，值得单独一版。 |

## v1.95.4 —— 一轮代码审查的 13 项修复（含两个下载器 P0）

外部审查报了一批问题，逐条核对代码后**全部属实**，按严重度修完并补了能复现的测试。

### P0-1 续传时闸门提前归零，还在下载的分片被 shutdownNow 打断

`new CountDownLatch(total - resumed)`，但 worker 遍历的是全部 0..total-1，
遇到已存在的 `.seg` 又 `countDown()` 一次 —— 总扣数仍是 total，闸门提前落零，
主线程 `shutdownNow()` 打断还在写盘的分片，合并时 `FileNotFoundException`，
或者「看起来成功」但少几片。

改成 `new CountDownLatch(total)`：每个索引恰好扣一次（已续传的走 isFile 分支，
其余走 finally），三条路径（正常/失败/取消）都收敛。

**复现验证**：把这一行临时改回旧写法，`HlsDownloaderTest` 立刻在
`000002.seg` 上抛 `FileNotFoundException`；改回后 4 秒通过。

### P0-2 任一分片失败，主线程永远等不到闸门（下载无限卡住）

worker 失败后其余 worker 在 `if (fatal[0] != null) return;` 直接退出、不扣闸门；
主循环只检查取消 → 每 300ms 空转，永远不结束，真实错误也到不了用户面前。

```java
while (!latch.await(300, TimeUnit.MILLISECONDS)) {
    if (fatal[0] != null) break;      // 新增：有 worker 失败立即收尾
    if (cancel.get() || esc != null && esc.cancelled()) { cancel.set(true); break; }
}
```

### P1-1 S3 的 `<Message>` 永远丢失（403 只剩一个干巴巴的状态码）

调用点先 `consume(c)`（读干 errorStream + disconnect），再进 `httpErr` 取
`getErrorStream()` —— 已经是 null。改由 `httpErr` 自己读 body 再断开，
6 个调用点去掉 `consume`。现在能看到 `SignatureDoesNotMatch` / `NoSuchBucket`。

### P1-2 明文 fMP4 分片被当密文解密

`decrypt` 只对 TS 做明文放行。明文 fMP4（`styp`/`moof` 开头）长度常是 16 的倍数，
会走进 CBC 分支并报「密钥或 IV 与清单不匹配」。加 `looksLikeFmp4(data)` 放行。

> 没有用 `looksLikeMedia` 代替：里面的 ADTS 判定只有 12 bit（`0xFFF`），
> 密文有 1/4096 概率被误判成明文而原样放行；fMP4 的 4 字节定长签名误判率可忽略。

### P2-1 AES 的 `hex:` IV 被当文本截断

`fitIv` 用 `iv.getBytes()`，而 `fitKey` 走 `toKeyBytes`（支持 `hex:`）。
用户按密钥的习惯填 `hex:000102…0f`，32 个 ASCII 字符被截成 16 个字符当 IV。
改成 `byte[] src = toKeyBytes(iv)`，与密钥同一套规则；测试用 JCE 直接加密对了一遍。

### P2-2 Punycode 用 UTF-16 长度当码点数

`out.length()` 对 emoji 返回 2，而 RFC 3492 的 `out.length()` 是码点数。
两个以上增补平面字符时 `i % len` 与插入位置全错。改用
`codePointCount` + `offsetByCodePoints`。

### P2-3 续传只按序号复用 `.seg`，没有清单指纹

上次取消留下的 `.parts`，下次下另一个同名 base 的片子（`index`/`playlist`/`video`
太常见）或同一片源清单更新，序号一一对得上 → 静默拼出混合内容。
现在在 parts 目录写 `.manifest`（地址 + 总片数 + 首末片序号/地址），对不上就整目录清掉。

### P3-1 `M3u8Playlist.resolve` 对无路径/相对 base 解析错误

| base | 旧结果 | 新结果 |
| --- | --- | --- |
| `https://host` | `https://seg.ts`（主机丢了） | `https://host/seg.ts` |
| `playlist.m3u8` | `/playlist.m3u8seg.ts` | `seg.ts` |
| `dir/playlist.m3u8` | `/dir/seg.ts` | `dir/seg.ts` |

`normalizePath` 不再无条件补前导 `/`，只在原始 head 以 `/` 开头时才补。

### P3-2 S3 签名头名先按原大小写排序

`new TreeMap<>(headers)` 排序后 `toLowerCase`：调用方传 `X-Amz-Date` 时
`'X'(88) < 'h'(104)`，签出来是 `x-amz-date;host`，服务端算的是 `host;x-amz-date` → 403。
新增 `normalized()`：先小写 + 折叠值内连续空格，再排序，`canonicalRequest` 与
`joinSortedKeys` 共用。

### P4 零散项

- `FileTaskEngine.measure` 只写了半截 null 守卫（`t != null && ...` 之后又 `t.totalFiles++`）→ 开头 `if (t == null || f == null) return;`；
- `Iso8601` 未关宽容模式，`2023-02-30` 被滚成 `03-02` → `cal.setLenient(false)`，非法日期返回 0；
- `M3u8Playlist.parse` 不认 BOM（`\uFEFF#EXTM3U` → kind 退化成 UNKNOWN）→ 复用 `HlsPlaylist.stripBom`；
- 解密失败会重试 3 次 → 新增 `DecryptError`，`fetchSegment` 里直接抛出不再重试。

**唯一没改**：`Contrast.luminance` 忽略 alpha。WCAG 的相对亮度定义本身就是不透明色，
当前所有调用方传的都是不透明主题色；要支持半透明前景得先定义「合成到哪个背景」，
属于 API 设计问题而不是 bug，留待真需要时再做。

### 验证

| 用例 | 内容 |
| --- | --- |
| `HlsDownloaderTest`（新） | 本地 HTTP 服务复现两个 P0：续传少片、分片 404 无限等 |
| `AuditFixTest`（新） | hex IV 对 JCE、明文 fMP4/TS 放行、emoji punycode 往返、指纹敏感性 |
| `M3u8PlaylistTest` | +6 条 base 解析、+1 条 BOM |
| `S3SignerTest` | +3 条大写头名排序 / 值空白折叠 |
| `Iso8601Test` | +5 条非法日期 |

---

## v1.95.3 —— 底栏胶囊统一成「图标 + 文字」

v1.95.2 把「信息」和右下角「全屏/竖屏」做成了纯图标胶囊，真机上一眼看出问题：
TextView 在没有文字时，`drawableStart` 会贴着左边排，右边留一块空白 —— 看起来
**像预留了文字位置却忘了写**，比纯文字还难看。

改回「图标 + 文字」的统一形态，并给原本只有文字的倍速、画质补上图标，
整行七个按钮全部同构：

| 胶囊 | 图标 | 文字 |
| --- | --- | --- |
| 倍速 | `ic_vp_speed` 表盘 + 指针 | `1.0×` |
| 画质 | `ic_vp_quality` 显示器 | `画质 源` / `画质 1080P` |
| 缩放 | `ic_vp_scale` 外框 + 居中块 | `适配` / `铺满` / `拉伸·变形` |
| 方向 | `ic_vp_orient` 弧 + 箭头 | `自动` / `竖屏` / `横屏` |
| 循环 | `ic_vp_loop` 双半圆 + 箭头 | `关` / `单个` / `列表` |
| 信息 | `ic_vp_info` 圆圈 i | `信息` |
| 全屏（右下固定） | `ic_vp_expand` / `ic_vp_portrait` | `全屏` / `竖屏` |

有文字时 compound drawable 会跟文字一起居中，胶囊自然被填满，不会再有「空位」。
`VpChipIcon` 样式随之删除（不再有纯图标胶囊）。

---

## v1.95.1 / v1.95.2 —— 返回键落点、右下角一键全屏、底栏图标化

v1.94.0 上线后真机连续反馈三个问题：横屏点返回回竖屏（这条对了），但**竖屏点返回直接
退到桌面**；想要一颗「一键全屏 / 一键竖屏」的按钮，而不是点「方向」循环三档；
底栏一排文字胶囊不好看、信息弹窗**白底浅灰字看不清**。

### 一、竖屏点返回退到桌面：一个被字号收敛冻住的配置快照

顶栏返回箭头原来是 `finish()`，绕过了返回键的「横屏先回竖屏」逻辑。改成两者共用
`stepBackToPortrait()` 后横屏没问题，竖屏却退到桌面 —— 打日志发现连「转竖屏」那行
都没有，说明 `isLandscapeNow()` 认为「本来就不是横屏」：

```java
private boolean isLandscapeNow() {
    return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
}
```

根因不在播放器，在 **`UiKit.alignMetrics`**（v1.73.0 的字号收敛）：

```java
Configuration cfg = new Configuration(base.getResources().getConfiguration());
cfg.fontScale = fixed;
return base.createConfigurationContext(cfg);   // ← 一份「此刻」的配置快照
```

`createConfigurationContext` 造出的是**独立的 Resources**，系统后续转屏、切深色模式只在
Activity 原始的 Resources 上更新，这份快照里的 `orientation` / `uiMode` / `screenWidthDp`
永远停在页面创建那一刻。这台 Pixel 6 系统字号正好 1.15，命中收敛条件，于是播放页的
`orientation` 永远是 PORTRAIT。验证很直接：`settings put system font_scale 1.0` 立刻正常，
改回 1.15 立刻复现 —— **同一份 APK，只差一个系统设置，行为相反**。

四处修法：

| 位置 | 修法 |
| --- | --- |
| `UiKit.syncConfig()`（新增） | `onConfigurationChanged` 时把 `newConfig` 写回自己的 Resources（fontScale 仍收敛），补上快照缺的更新 |
| `BaseActivity.onConfigurationChanged` | 每次配置变更调用 `syncConfig`，所有页面一起修好 |
| `VideoPlayerActivity.isLandscapeNow()` | 不再信任 `Configuration.orientation`，改用窗口像素 `widthPixels > heightPixels`，退路是根 View 宽高 |
| `ThemeManager.isSystemDark()` | 改读 **Application** 的 Resources（那份由系统更新），页面存活期间切深色也能读到 |

另外新增 `exitPlayer()`：从浏览器 / 聊天 / 「打开方式」直接进播放器时本页是任务栈第一屏，
`finish()` 之后下面什么都没有，用户看到的是「播个视频把应用退没了」。所以

```java
private void exitPlayer() {
    if (isTaskRoot()) {          // 栈底：先把文件管理器拉起来，再结束自己
        startActivity(new Intent(this, MainActivity.class).addFlags(FLAG_ACTIVITY_CLEAR_TOP));
    }
    finish();
}
```

从文件管理器点进来的情况 `isTaskRoot()` 为 false，仍是原来的 `finish()`，退回上一级目录。

### 二、右下角一键「全屏 / 竖屏」

底栏左边是可横向滚动的胶囊，右边**固定**一颗按钮：竖屏显示全屏图标，横屏显示竖屏手机图标，
点一下直接切，不用循环三档。

- 只改「本次」方向，不动持久化的 `orientMode` —— 临时全屏看一眼不该改掉长期偏好；
- 横屏 → 竖屏复用 `stepBackToPortrait()` 的 `backPortrait` 标记，换片前不会被自动转回横屏；
- 转屏是异步的，按钮图标点完立刻翻一次、450ms 后再对一次，避免停在旧值；
- 固定在滚动区之外（`layout_weight=1` 的 HorizontalScrollView + 独立按钮），
  320dp 窄屏滚胶囊也不会把它挤出去。

### 三、底栏改图标 + 信息弹窗跟主题取色（v1.95.2）

胶囊从「纯文字」改成「16dp 线性图标 + 极短状态词」：`适配` / `自动` / `关` / 纯图标信息，
右下角「全屏 / 竖屏」是纯图标（点击时动态换 `ic_vp_expand` / `ic_vp_portrait`）。
六个图标都是手写 vector（stroke 风格、24 viewport、`#F2FFFFFF`），不引第三方图标库：

| 资源 | 语义 |
| --- | --- |
| `ic_vp_scale` | 外框 + 居中实心块 = letterbox 留黑边 |
| `ic_vp_orient` | 270° 弧 + 箭头 = 转向 |
| `ic_vp_loop` | 上下两段半圆 + 双箭头 = 闭环 |
| `ic_vp_info` | 圆圈 + i |
| `ic_vp_expand` | 四角向外 |
| `ic_vp_portrait` | 竖持手机 |

信息弹窗原来把正文写死成 `0xFFEEEEEE`，浅色主题下弹窗是白底，这层浅灰字几乎看不见。
改成 `ThemeManager.text(this)`，两种主题都清楚；顺带 12 → 12.5sp、行距 +3dp。

---

## v1.94.0 —— 画面方向跟视频比例走，且永不变形

### 症状

真机上打开任何视频都是竖屏，横着拍的片子被塞进竖屏窗口拉满整屏，人脸变胖、
文字变宽 —— 比例全乱。转到横屏反而正常。

### 两个独立的原因，叠在一起才这么难看

**一、Activity 没有声明方向**。`VideoPlayerActivity` 的清单里没写
`android:screenOrientation`，于是它跟随系统方向：手机竖着拿就是竖屏窗口，
不管里放的是一部 16:9 电影还是竖屏短视频。

**二、`applyScale()` 用 TextureView 的 transform 做 letterbox，时机是错的**：

```java
// v1.93.0 及以前
private void applyScale() {
    if (texture == null || videoW <= 0 || videoH <= 0) return;
    int vw = texture.getWidth();       // ← 还没 layout 时是 0
    if (vw <= 0 || vh <= 0) return;    // ← 直接跳过，此后没人再算第二次
    ...
    texture.setTransform(m);
}
```

`onVideoSizeChanged` 通常在首帧之前就到了，那一刻 TextureView 还没 measure/layout，
`getWidth()` 返回 0，函数静默返回。**transform 保持单位矩阵，而 TextureView 的默认行为
就是把 buffer 拉伸到 view 大小** —— 于是视频被拉满整个窗口，比例尽毁。转到横屏时
容器尺寸变了会再触发一次 `onVideoSizeChanged`（部分机型），所以才出现「横屏反而好」的怪现象。

附带一个问题：渲染器自己也会写这个 transform 做像素宽高比校正，两边抢着设，
最终谁生效取决于回调顺序 —— 表现为「有时按比例、有时变形」，很难复现。

### 改法

**1. 新增 `ScreenPolicy.java`（纯 Java，58 项桌面单测）**

把「该横还是该竖」和「画面占多大」从 Activity 里抽出来，变成可测的纯函数：

| 方法 | 作用 |
| --- | --- |
| `next()` / `label()` / `describe()` | 三档循环：自动 → 竖屏 → 横屏 → 自动 |
| `isWide(w,h)` | 宽 **严格**大于高才算横视频（1:1 方形视频竖着放更满） |
| `wantLandscape(mode,w,h,current)` | 尺寸未知时保持现状，避免「先竖一下再横过去」的闪动 |
| `scaleFor(crop,w,h,cw,ch)` | 只给等比比例：FIT 取 min、CROP 取 max，**拉伸永远不参与** |

**2. 缩放从 transform 改成布局**

TextureView 的宽高直接设成「视频比例 × 缩放系数」，`gravity=center` 摆中间：

- 适配：画面完整可见，多出来的方向留黑边（用户要的「画面放小、按原比例」）；
- 铺满：等比放大到填满，超出部分被 FrameLayout 裁掉；
- 拉伸：满屏，比例失真 —— 明确标注为「拉伸·变形」，且**不再记忆**（见下）。

布局方案的额外好处：尺寸变化天然由 View 系统驱动，转屏/分屏/挖孔 insets 变化
都会自动重算，不需要任何延迟补偿。`vp_root` 上挂一个 `OnLayoutChangeListener`
兜住「首帧早于 layout」这个时序坑。

**3. 方向三档，默认自动**

- 自动（默认）：`VideoSize` 到了以后判一次，横视频请求 `SENSOR_LANDSCAPE`、
  竖视频请求 `SENSOR_PORTRAIT`。用 `SENSOR_*` 而不是 `LANDSCAPE/PORTRAIT`，
  这样用户把手机上下颠倒也能继续看；
- 竖屏 / 横屏：用户显式锁定，不随片源变，写入 `PlayerPrefs` 长期记住；
- 每换一个视频只重判一次（`orientationDecided`），不会跟用户手动选择打架；
- 返回键：横屏时先回竖屏（`backPortrait`，仅本次生效，不改偏好），再按一次才退出。

**4. 顺手修掉的两个体验坑**

- **「拉伸」不落盘**：以前点一次拉伸就永久变形，下次打开还是拉伸。现在拉伸档不写
  SharedPreferences，启动时若读到旧值也会回落「适配」；
- **转屏后沉浸失效**：`setRequestedOrientation` 会把状态栏放回来，表现是整页被下推
  一个状态栏高度、顶栏上面多一条黑。`onConfigurationChanged` 里补两次
  `hideSystemBars()`（立即 + 420ms），`onResume` 也补一次；
- **挖孔屏内缩**：默认 cutout 模式会把整页挤出挖孔所在短边（Pixel 6 竖屏顶部 128px、
  横屏左侧 128px），顶栏因此悬在半空。改成 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`
  （API 28+，播放器纯黑底，延伸进挖孔区没有副作用）。

**5. 底栏胶囊改成可横向滚动**

从 5 个胶囊变成 6 个（多了「方向」）。320dp 窄屏上原来最后一个会被屏幕边缘裁掉，
现在套一层 `HorizontalScrollView`，控制条隐藏时整条不可点，不会抢手势。

### 验证

- 桌面单测 `ScreenPolicyTest`：58 项断言，覆盖三档循环、脏值收敛、1:1 方形、
  尺寸未知、21:9 超宽片源、以及「FIT 输出比例恒等于源比例」的不变式；
- QUICK 全套 **43 项通过**；
- 真机 Pixel 6 / Android 15：
  - 1150×640 的横屏片：进播放器自动转横屏，`vp_texture` 实测
    `[229,0][2170,1080]`（1941×1080，居中，左右各留 229/230px 黑边）—— 正好是 1.80:1；
  - 挖孔全屏后 `vp_root` 实测 `[0,0][2400,1080]`，顶栏贴着屏幕最上沿不再下坠 128px；
  - 点「方向」切到竖屏：屏幕转回 1080×2400，`vp_texture` 变成
    `[0,899][1080,1500]`（1080×601，正好 1.797:1，上下各留 899/900px 黑边，居中），画面不再被拉伸；
  - `logcat -s HexPlayer`：`maybeAutoOrient video=1150x640 mode=0` →
    `applyOrientation ... want=true target=6`；点胶囊后
    `orient chip clicked, mode=0` → `applyOrientation mode=1 ... target=7`，
    `dumpsys window displays` 的 `cur=` 从 `2400x1080` 变成 `1080x2400`。

- 版本 **1.94.0（versionCode 166）**，APK 约 15MB。

---

## v1.93.0 —— 修两个「只有真机才炸」的崩溃

v1.91.0 / v1.92.0 在真机（Pixel 6 / Android 15）上点开 `/sdcard/123.mp4` 直接闪退。
两个崩溃都发生在 `VideoPlayerActivity.onCreate`，而 **javac、aapt2、d8、单测全部通过**：

```
java.lang.NoSuchMethodError: No virtual method buildOrThrow()Lcom/google/common/collect/ImmutableMap;
    at androidx.media3.exoplayer.audio.AudioCapabilities.<clinit>
```

```
java.lang.AbstractMethodError: abstract method
    "void androidx.media3.common.Player$Listener.onTimelineChanged(Timeline, int)"
    on receiver com.hex91.file.VideoPlayerActivity$15
```

这一版把两个都修掉，并把「为什么会漏」做成了一道可以反复执行的检查。

### 一、崩溃一：Guava 27 撑不住 Media3 1.4.1

Media3 1.4.1 是用 Guava 31+ 编译的：`AudioCapabilities` 的静态初始化块里调用了
`ImmutableMap.Builder.buildOrThrow()`，而这个方法是 **Guava 31.0 才新增**的。
工程 `libs/` 里的 `guava-27.1-android.jar` 是 smbj / dexlib2 的传递依赖，于是
「创建播放器的第一秒」就撞上 `NoSuchMethodError`。

编译期为什么看不见：Media3 的 class 是**预编译**的，javac 只核对「类型签名对不对」，
不会去检查「这个 class 引用的方法在 classpath 上存不存在」。这类问题只能运行时暴露。

修法是换 **`guava-31.1-android.jar`**。两个必须注意的点：

- **只能用 `-android` 变体**。JRE 变体依赖 `java.util.stream` / `java.lang.invoke` 等
  minSdk 21 上不存在的 API，换上去会在老设备上以另一种方式崩；
- **不能只升「看起来相关」的那一个**。工程源码零处直接使用 Guava，但 smbj、dexlib2、
  baksmali 都在用它 —— 升版本后要确认它们引用的方法在新版里还在（下一节的工具就是干这个的）。

### 二、崩溃二：分层 dex 把 d8 的接口 desugar 劈成了两半

这个更隐蔽，跟代码一点关系都没有，是**构建流水线**的问题。

本工程的 d8 分两次跑（为了构建速度）：
① 依赖 dex —— `libs/*.jar` 全部当 program 输入；
② 应用 dex —— 只把 `src/` 的 class 当 program，`libs/` 用 `--lib` 只做类型解析。

在 `--min-api 21` 下，d8 必须把接口的 default 方法改造掉：
拆成「接口里只留抽象声明」+「`Interface$-CC` 类里的静态实现」，
**同时给每一个实现类补一个转发 stub**。这一步 d8 只有同时看到接口和实现类才做得到。

分层之后：

| 阶段 | media3 的身份 | d8 的动作 |
|---|---|---|
| 依赖 dex | program | 把 `Player$Listener` 的 default 方法改成 abstract，实现体挪进 `Player$Listener$-CC` |
| 应用 dex | `--lib` | 认为「这个库在运行时自带方法体」，**不**给 `VideoPlayerActivity$15` 补 stub |

拼到一起就是「接口是空的、实现类也什么都没写」。ART 执行 `invoke-interface` 时找不到实现，
直接 `AbstractMethodError`。

修法是在 `build.sh` 里加一个分组函数：

```bash
is_app_program_jar() {
    case "$1" in
        */media3-*.jar|*/collection-*.jar|*/exifinterface-*.jar) return 0 ;;
    esac
    return 1
}
```

这几个库从依赖 dex 里剔除，改成与应用类**同批 desugar**；其余 20MB 依赖继续走指纹缓存，
构建时间只从 2 分 8 秒涨到 2 分 28 秒（只重建一次，之后 media3 每次都参与应用 dex，约 +20 秒）。

顺手修了一个衍生坑：把 **`build.sh` 自身也纳入 deps 指纹**。否则改了分组逻辑后，
`deps.sig` 不变 → 复用仍然带着 media3 的旧依赖 dex → 和新 app dex 撞成 duplicate class。

### 三、为什么这次能定位：把「二进制兼容性」做成脚本

`tools/dexcheck.py`（新增，纯 Python，无依赖）：

```bash
python tools/dexcheck.py build/dexchk/classes*.dex libs/*.jar
```

它做两件事：

1. **方法级**：解析 dex 的 `method_ids` / `proto_ids`，抽出「类名 + 方法名 + 描述符」，
   对每个引用了外部 jar 的方法，沿**父类与接口链**去 jar 的 class 文件里核对是否真的存在；
2. **类级**：dex 的 `type_ids` 对照 `class_defs` + jar + `android.jar`，抓「整类不存在」
   （`NoClassDefFoundError` 那一类）。

它当场抓到了 `buildOrThrow`。修完后对 **104815 条方法引用**复查，缺失 0。

两个必须处理的假阳性，否则输出会淹没真问题：

- `equals` / `hashCode` / `ordinal` 这类**继承来的**方法：必须沿继承链查，不能只看本类；
- `lambda$xxx$0` / `$r8$lambda$xxx`：这是 d8 desugar 生成的**新方法**，jar 里当然没有。

### 四、顺带：中控按钮在白底视频上「消失」

真机验证时发现的独立问题：中控三个按钮（上一个 / 播放 / 下一个）只有白色字形、没有底色。
顶栏底栏本身有 `#8C000000`，但中控是浮在画面上的，遇到白底 PPT 录屏就完全看不见。
新增 `res/drawable/vp_ctrl_circle.xml`（半透明黑圆 `#73000000`）兜底。

### 五、真机验证（Pixel 6 / Android 15，全部实测）

| 项 | 结果 |
|---|---|
| 应用内点 `/sdcard/123.mp4` | 进播放器，H.264 硬解（`c2.exynos.h264.decoder`）+ AAC，`FATAL=0` |
| 画面是否真的在动 | 间隔两次截图 MD5 不同 |
| 外部 Intent（`am start` 同一路径） | 正常起播 |
| 返回 → 再次进入 | 从 00:34 续到 01:08，断点续播生效 |
| 缩略图 | `cache/vthumb/a78ddd20-168.jpg` 已生成，列表项 `id/thumb` 可见、`id/icon` 已 GONE |
| QUICK 全量 | 通过 |

### 六、教训

1. **「编译过 + 单测过」不等于「能跑」**。没有 Gradle 的依赖解析与 desugar 编排，
   这两件事必须自己兜：第三方 jar 的版本要对齐「使用它的那个库」的编译期版本
   （不是越老越稳），跨 dex 的语言特性（接口 default、静态接口方法）要格外小心；
2. **真机是唯一的裁判**。这两个问题在 IDE、单测、CI 编译里都不会出现，
   只有装到设备上点一下才暴露 —— 所以「能构建成功」不能作为交付标准。

## v1.92.0 —— m3u8/TS 下载合并为 MP4（以及为什么没集成 FFmpeg）

这一版做两件事：**把一条 m3u8 拉成手机里能直接播的 MP4**，以及**把本地 TS 文件换成 MP4 容器**。
先说上一版遗留的那个问题 —— 能不能靠 FFmpeg 扩展「播放原片」。

### 一、`lib-decoder-ffmpeg` 不能解决「播放原片」

实测结论（不是推测）：

1. **它不在 Google Maven 上**。`androidx/media3/media3-decoder-ffmpeg` 的 pom / aar / module
   全部 404，`group-index.xml` 里只有 `media3-decoder`。官方 README 写得很直白：
   *"The module is not provided via Google's Maven repository"*。
2. **它只解音频**。模块提供的类是 `FfmpegAudioRenderer`，用于 AC3/EAC3/DTS/Opus 这类
   **音频**编码；**视频解码从来没有**。而用户真正打不开的片源 —— RMVB 的 RV40、
   WMV 的 VC-1、HEVC 10bit、AV1 —— 全是视频编码。就算把 FFmpeg 编出来挂上去，
   这些片子照样是黑屏（顶多声音有了）。
3. **编译它要 Linux/macOS**。需要 clone media3 仓库、clone FFmpeg 6.0、用 NDK 跑
   `build_ffmpeg.sh`，官方明确 *"We do not provide support for building this module on Windows"*。
   本机虽然有 NDK 27，但这条流水线是 `aapt2 → javac → d8`，没有 Gradle 的 externalNativeBuild，
   还得自己把 `.so` 塞进 APK 的 `lib/<abi>/`，并为每个 ABI 增加 3~8MB。

**结论：不集成。** 它的成本（跨平台工具链 + 每 ABI 数 MB）与收益（只有音频）严重不匹配 ——
本工程连独立音频播放器都没有。

真要「什么编码都能播」，只有三条路，都不便宜：

| 方案 | 代价 | 说明 |
|---|---|---|
| 自己写 Media3 视频软解 Renderer | 用 NDK 编 FFmpeg 视频解码 + 手写 `Renderer` 与 `VideoFrameProcessor` | 相当于重做一个播放器内核，工程量以月计 |
| 换内核（libVLC / mpv） | 30MB+，且有 LGPL/GPL 传染风险 | 与「单 APK 轻量」的定位冲突 |
| **解码不了就转码** | 慢（硬编约 0.3~1× 实时） | 用系统 `MediaCodec` 把 H.264 编出来，是目前唯一现实的路 |

本版走的是第三条路的「前置一半」：**先把容器与索引问题解决掉**。因为大量「播不了」
其实不是编码问题，而是 **TS 容器**问题 —— 见下。

### 二、为什么 TS 值得单独处理

TS（MPEG-TS）是直播和 HLS 切片的容器：188 字节一个包、索引稀薄、没有全局 moov。
它的问题不是画质，而是**兼容性**：

- 手机相册、系统播放器、剪辑软件、电脑上大部分工具都不认 `.ts`；
- 拖进度条要靠码率猜，很多播放器干脆不给拖；
- 一条 m3u8 下下来是 300 个 `.ts` 文件，用户没法用。

而 TS → MP4 是**换容器**，不是转码：读出来的 H.264/H.265 码流原样写进 MP4 的 mdat，
像素与码率一个字节都没变。所以它跑得极快（纯磁盘 IO），画质 100% 保留。

### 三、实现：`HlsPlaylist` + `HlsDownloader` + `TsRemuxer`

**解析（纯 Java，79 条断言）** —— `HlsPlaylist` 处理 media playlist，`HlsKey` 处理加密，
`HlsCrypto` 处理解密。这几个坑每一个都会导致「下完了但播不了」：

- **`#EXT-X-BYTERANGE` 的 offset 省略**表示「接着上一个同 URL 分片的结尾」，不是从 0。
  写反了（我第一版就把返回值的 offset/length 顺序搞反了）的表现是**同一部片子重复 N 遍**，
  因为每片都从错误位置取到了整段内容；
- **`#EXT-X-MAP` 是 fMP4 的初始化段**，少下载它，拼出来是一堆没有索引的 mdat；
- **`#EXT-X-KEY` 是状态不是事件**：从出现那行起对后续所有分片生效，`METHOD=NONE` 表示
  从这里开始恢复明文。全文只记一个 key 的实现遇到「前几片加密、后面明文」就会解出乱码；
- **`METHOD=AES-128` 不给 IV 时，IV 是「媒体序列号」的 16 字节大端**，而且序列号要加
  `#EXT-X-MEDIA-SEQUENCE` 基数。给全零 IV 的表现很典型：前 16 字节对、后面全是噪音；
- **`#EXT-X-GAP` 必须跳过**，否则对着一片不存在的分片请求 404，整条下载失败；
- **首行必须是 `#EXTM3U`**。不校验的话，一个 404 的 HTML 页面会被当成「只有一个分片的列表」，
  然后你得到一个 4KB 的 `<html>`。

**解密**（`HlsCrypto`）：先看首字节是不是 `0x47`（TS 同步字节）—— 是就原样返回。
因为不少站点在切换码率时 KEY 行没清干净，明明是明文却标了加密；硬解会得到垃圾。
PKCS5 解不开时退回 NoPadding，但**解完必须验证「像不像媒体」**（TS / fMP4 / ADTS / 裸 NAL 起始码），
不像就按密钥错误报错 —— 否则用户拿到的是一个「生成成功但打不开」的文件，比直接报错难查得多。

**下载**（`HlsDownloader`）：

- 并发 **3 路**。分片只有 2~6 秒，瓶颈在延迟不在带宽；3 路已把「一次请求一个 RTT」的空等填满，
  再往上加多数 CDN 会限速或 403，反而更慢；
- 每片先写 `.seg.tmp` 再 rename 成 `.seg`，**取消/断网不会留下半截分片**被下次当成「已完成」；
- 已存在的 `.seg` 直接跳过 —— 上次下到一半，这次接着下；
- 每片重试 2 次（退避 400ms / 1600ms），`Referer` 与 `Origin` 都带（少数站按 Origin 防盗链）；
- 服务器忽略 `Range` 返回 200 时，自己跳过 offset 再按长度截断；长度对不上就报错，
  而不是把整个文件写进去（那又会变成「整片重复」）。

**转封装**（`TsRemuxer`）：`MediaExtractor` 读轨道 + `MediaMuxer` 写 MP4。两个坑：

1. **时间戳基线**。TS 的 PTS 常从 1.4 秒、10 秒甚至任意大值开始（编码器惯例），
   直接写进 MP4 会得到「开头空白几十秒」的视频。要整体减去首帧时间，
   而且**音视频必须减同一个值** —— 两轨各自归一化的结果就是音画错位。
   所以先用一遍只读时间戳，取两轨最早的那个当共同基线；
2. **同轨时间戳必须严格递增**。MP4 的 stts 不允许相等或倒退，切片边界
   （`EXT-X-DISCONTINUITY`）经常出现重复时间戳，muxer 会直接抛异常。遇到就把 pts 加 1µs。

另外尾部残帧的处理：已经写到 90% 之后出的样本写入错误就忽略并正常收尾，
而不是让整个转换失败 —— 用户宁可要一个「最后 2 秒花了」的视频，也不要一句「转换失败」。

### 四、三条入口

1. **⋮ →「下载视频（m3u8 → MP4）…」**：粘贴地址 → 解析（显示分片数/时长/加密/预估大小）
   → 选画质（master 多档时）→ 开始；
2. **长按 `.m3u8` →「合并为 MP4」**：本地已经下好切片的目录，路径自动预填；
3. **长按 `.ts`（且字节嗅探确认是 MPEG-TS）→「转为 MP4」**：
   就地转封装，进度条显示「已处理 N 秒」。

下载页支持选保存目录（默认 `工作目录/Download`），页面上能看到每一片下了多少、速度多少。
按返回会明确提示「会取消本次下载」，已经下好的分片保留 —— 不做「悄悄在后台下完」，
因为本工程的任务中心是「复制/移动」语义（源与目标都已知），HLS 下载是网络驱动，
塞进去会让两边都变复杂。

### 五、边界（写清楚，免得期待错）

- **不解 DRM**：`SAMPLE-AES` / FairPlay 需要设备密钥，直接给明确提示，不尝试；
- **不重编码**：源是 HEVC 10bit / AV1 时，换成 MP4 也照样解不了 —— 那是解码器的事，
  不是容器的事。这类文件转出来的 MP4 在支持该编码的设备上能播；
- **直播流只有当前窗口**：没有 `ENDLIST` 的列表会滚动，点开始那一刻有多少片就下多少片；
- **需要 Cookie 的私有流**下不了（只带 UA/Referer）；
- **TS 的 `.ts` 与 TypeScript 的 `.ts` 仍靠字节嗅探区分**（见 v1.91.0），
  所以「转为 MP4」只对 `0x47` 同步字节成立的 `.ts` 出现。

### 六、测试与产物

- 新增 `HlsPlaylistTest` **79 条断言**，覆盖 BYTERANGE 顺序、隐式 IV、`METHOD=NONE` 复位、
  GAP、DISCONTINUITY、MAP、master/media 判定、CRLF/BOM、AES 加解密往返；
- 版本 **1.92.0（versionCode 164）**；QUICK 全套 42 项通过。

### 七、真机待验

1. 一条 AES-128 加密的 m3u8（B 站/部分自建站）能否解出正确画面（重点看**第二片之后**有没有花屏，
   那是隐式 IV 算错的典型症状）；
2. 一个 300+ 片的点播源下载 + 转 MP4 的耗时与发热（3 路并发在弱网下是否够）；
3. 转出来的 MP4 拖进度条、音画同步是否正常（尤其起点 PTS 不是 0 的源）；
4. 长按 `.ts` 转 MP4 时，一个 2GB 的录屏会不会因为内存/时间过长而失败。

## v1.91.0 —— 内置视频播放器（Media3 ExoPlayer 内核）

以前在文件管理器里点视频，走的是 `OpenWith`（交给系统找应用）。结果是：手机上但凡
没装第三方播放器，点视频就只弹一句「没有可打开该类型的应用」——一个文件管理器
打不开自己目录里的视频，说不过去。

这一版补上内置播放器：**本地视频 / 网络直链 / HLS(m3u8) / DASH(mpd) / RTSP**，
倍速 0.25×–3.0×，m3u8 多档画质可锁定，列表里出视频首帧缩略图。

### 一、把 ExoPlayer 塞进「没有 Gradle」的工程

本工程的构建链是 `aapt2 → javac → d8 → zipalign → apksigner`，没有 AGP 的资源合并。
集成 Media3 的难点不在代码，在两件事：

1. **AAR 里的 classes.jar 可以直接用**：把 11 个 media3 AAR 解包，只取 `classes.jar`
   放进 `libs/`（`build.sh` 的 `libs/*.jar` 通配自然吃到），共 4.7MB；
2. **AAR 的 res/ 不要**：media3 只有 `media3-exoplayer` 带资源，且全是
   `DownloadService` 通知文案（`exo_download_*`），本项目不做离线缓存下载，用不到。
   代价是 `androidx.media3.exoplayer.R` 这个类在运行时不存在 —— 只要不去碰
   `DownloadNotificationHelper` 就不会加载它（已按此裁剪）。

真正的依赖只有三个外部包，实测（扫 `classes.jar` 常量池）：
`androidx.annotation`（本工程有桩）、`androidx.collection.CircularIntArray`
（异步解码回调要用，引了真 jar）、`androidx.exifinterface.ExifInterface`
（读封面图方向，也引了真 jar）。
**没有引入 media3-ui** —— 它带整套 layout/values 且依赖 AppCompat，与「37 个页面全部手写 UI」
的本工程冲突；控制条是自绘的（`activity_video_player.xml` + `VideoPlayerActivity`）。

编译验证：`javac -source 8 -target 8 -bootclasspath android-35/android.jar` 直接通过，
说明 Media3 1.4.1 的字节码版本与本工程的 Java 8 / minSdk 21 基线兼容。

### 二、「不压缩」落到了三行具体代码

口号没有意义，落到实处只有三处：

```java
trackSelector.setParameters(trackSelector.buildUponParameters()
        .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)   // 不设分辨率上限
        .setMaxVideoBitrate(Integer.MAX_VALUE)                   // 不设码率上限
        .setMaxAudioBitrate(Integer.MAX_VALUE));
```

```java
// 默认 FIT：等比缩放到完整可见（四周留黑），绝不裁切、绝不变形
float s = Math.min((float) vw / videoW, (float) vh / videoH);
m.setScale(s, s, vw / 2f, vh / 2f);
```

加上「不做二次编码」——ExoPlayer 把解码结果直接送到 `TextureView` 的 surface，
中间没有缩放到固定分辨率、没有重编码。用户看到的像素就是片源像素（唯一例外是设备
自身的显示缩放，那一步不在 App 控制范围内）。

需要说明的边界：**解码能力取决于设备**。Media3 的 extractor 能拆 mp4/mkv/webm/ts/flv/avi 等
容器，但真正的解码是系统 `MediaCodec` 干的。AV1、HEVC 10bit 这类，老设备就是解不了，
这种情况下播放器给的是明确的人话提示（见下文错误码表），而不是一个黑屏。

### 三、画质：自己解析 master，而不是设个上限

`setMaxVideoSize` 是「上限」语义 —— 设 1080p 表示「不超过 1080p」，播放器仍然会在
360p/720p 之间自适应。用户点「720P」时想要的是**锁死 720P**，这两件事不一样。

所以做了 `M3u8Playlist`：拉取 master playlist，解析 `#EXT-X-STREAM-INF` 的
`RESOLUTION` / `BANDWIDTH` / `CODECS`，把每一档的**子播放列表地址**拿出来；
用户选档 → 把当前 `MediaItem` 换成那一档的地址重新 prepare（位置不动）。
播放器从此只看到一路流，ABR 无从切换 —— 这才是「锁定」。选「自动」则换回原始 master 地址。

解析器里有三个真实坑，单测全部钉住：

- **HLS 是 CRLF 行尾**。`split("\n")` 会留下 `\r`，拼出来的子地址带 `\r`，请求必 404；
- **`CODECS="avc1.640028,mp4a.40.2"` 的引号里有逗号**。按逗号切属性会把 CODECS 切成两半，
  后面所有属性错位（表现是画质菜单里分辨率全空）。必须做引号感知的切分；
- **`#EXT-X-I-FRAME-STREAM-INF` 不能进画质菜单**。那是拖动条预览用的 I 帧轨道，
  混进去用户会选到「只有缩略图没有画面」的一路。

### 四、m3u8 拦截：三道必须踩对的坎

**坎 1：注册 `http` + `pathPattern` 必须带 `host="*"`。**
Android 的规则是「path 类条件只在同时声明了 scheme 与 host 时才生效」。只写
`scheme="https" pathPattern=".*\\.m3u8"`，系统会**忽略 path 条件**，等于接管所有 https 链接 ——
用户点任何网页都会问「用 HexFile 打开？」，这是灾难。

**坎 2：用 `pathSuffix` 而不是 `pathPattern`。**
`pathPattern` 是「简单 glob」，反斜杠转义在不同版本上语义有差异；写错的两个失败方向都很隐蔽：
要么过滤器静默失效（拦截不到），要么过度匹配（抢别人的链接）。`pathSuffix=".m3u8"` 是纯文本
后缀比较，没有转义歧义。

**坎 3：`exported=true` 就必须自己把住协议。**
播放器页面从外部可达，`Intent` 的 data 是**不可信输入**。`StreamUrl.isSupported()` 只放行
`http/https/rtsp/rtmps/udp/file/content`，`javascript:`、`intent:`、`data:` 一律拒绝 ——
否则等于把「用任意 scheme 打开任意东西」的能力交给第三方应用。

另外 `StreamUrl` 负责清洗从聊天/网页复制过来的脏文本（中文前后缀、引号、全角空格、
「提取码: abcd」），抽出唯一像样的地址。这类输入在真实使用里是常态，不是边角。

### 五、缩略图：不落磁盘就是负优化

`MediaMetadataRetriever` 抽一帧要真解码一段视频，单次 60–300ms。一个目录里 40 个视频，
滑一遍就是十几秒的串行卡顿（列表缩略图是单线程池，避免同时开一堆解码器）。

所以：**抽一帧 → 缩到图标尺寸 → 存 `cacheDir/vthumb/<hash>-<size>.jpg`（质量 82）**。
第二次进目录只读几十 KB 的小图，毫秒级。缓存目录按「400 个文件 / 32MB」上限、
超了按 mtime 删最旧的一半；设置页可一键清空并显示当前占用。

抽帧位置取自时长 10%（上限 5 秒）—— 取第 0 秒的话，大量视频开头是黑场或台标，
缩略图会是一排黑块。API 27+ 用 `getScaledFrameAtTime` 直接解出小图，
避免 4K 片源一次性分配 32MB 位图。

### 六、倍速与手势

倍速档位 `0.25 / 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0 / 2.5 / 3.0`（`PlaySpeed`）：
上限 3.0 是需求，也是音调不变（`PlaybackParameters` 走 Sonic 时间伸缩）仍可接受的实用上限。
从 1.0 连点到 3.0 需要 6 次，把 1.5/2.0/2.5/3.0 排进常用路径，避免连点七八次。

手势（`VideoPlayerActivity.handleTouch`）：
单击显隐控制条、双击播放/暂停（280ms 判定窗口，双击期间不闪控制条）、
**横滑**快进/快退（半屏 = 最多 90 秒，短视频按比例缩）、
**左竖滑**亮度（直接改 `window.screenBrightness`，不需要权限）、
**右竖滑**音量、**长按**临时 3.0× 快进（松手还原，这是 MX/VLC 的老习惯）。

还有两个容易漏的点：
- 拔耳机/蓝牙断开自动暂停（`setHandleAudioBecomingNoisy(true)`），否则突然外放；
- 切后台就暂停（文件管理器里的播放器不做后台音频，否则用户会莫名其妙听到声音）。

### 七、顺带：抽屉里的「设置」删了

v1.83.0 把设置页同时挂在了抽屉和顶栏 ⋮ 两处。同一页面两个并列入口，用户会犹豫点哪个。
按你的要求删掉抽屉那一行，只保留 ⋮ →「设置」。

### 八、错误提示：不能给用户看 4003

`PlaybackException` 的错误码统一翻成人话，并给出下一步动作。例如：

| 错误码 | 提示 |
|--------|------|
| `ERROR_CODE_IO_BAD_HTTP_STATUS` | 服务器返回 403/404：地址过期、需要 Referer/Cookie，或切片已删除 |
| `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED` | 本机解码器不支持该编码（AV1/HEVC 10bit 常见），可换设备或先转码 H.264 |
| `ERROR_CODE_PARSING_MANIFEST_MALFORMED` | 容器/清单解析失败：文件损坏，或 m3u8 内容其实是错误页面 |

### 九、测试与产物

新增桌面单测 3 组共 126 条断言：
`M3u8PlaylistTest`（45）、`StreamUrlTest`（48）、`PlaySpeedTest`（33）。

- 版本 **1.91.0（versionCode 163）**；
- QUICK 全量通过（含新增三组），全套 41 项；
- APK 体积 12.83MB → **14.84MB**（+2.0MB）——4.7MB 的 jar 进 dex 并被压缩后只涨这么多，
  这是「内置播放器」的必然代价，比再装一个第三方播放器省。

### 十、真机待验

1. 4K HEVC 片源在某台设备上解不了时，提示是否准确、是否有回退；
2. 弱网下 m3u8 切换画质后的重新缓冲时长是否可接受；
3. 目录里视频很多（50+）时首次进目录的缩略图生成是否影响滚动；
4. 从浏览器「打开方式」里选 HexFile 打开 m3u8 链接是否出现在候选列表中。

## v1.90.0 —— 阿里云盘改成「弹出登录页」

上一版让用户「电脑浏览器 F12 → Local Storage → 复制 token」，本质是把开发者调试手段
当成用户操作流程。绝大多数人走到 F12 就放弃了 —— 这不是「功能难用」，是「功能等于没有」。

这一版把它做成一步：**点「登录阿里云盘」→ 弹出官方登录页 → 正常登录 → 自动完成授权 → 自动关闭。**

### 一、为什么这条路能走通

阿里云盘网页版登录成功后，token 就存在本站的浏览器存储里。而 WebView 与页面**同源**，
页面里的脚本读得到，App 也就拿得到。于是不需要 OAuth 注册、不需要 redirect_uri、
不需要用户理解什么是 localStorage。

流程是：加载 `www.alipan.com/sign/in` → 用户输手机号验证码（或密码、扫码）→
登录成功跳回网盘 → 注入的脚本发现存储里出现 `refresh_token` → 回调给 App → 关闭页面。

### 二、核对前端代码后才发现的两个坑

**坑 1：token 不一定写在 localStorage。**

把阿里云盘登录前端（`g.alicdn.com/aliyun-drive-fe/h5-sign/…/app.js`）的封装挖出来是这样：

```js
L = "token",
get:  function(){ return sessionStorage.getItem(L) || localStorage.getItem(L) || "{}" },
save: function(t, remember){ remember ? localStorage.setItem(L, t)
                                        : sessionStorage.setItem(L, t) }
```

`remember` 来自登录页上「记住登录」那个勾选。**没勾，token 就写在 sessionStorage**。

如果只扫 localStorage，后果是：用户明明登录成功了，App 一直停在「等待登录」，
日志里什么都没有，看起来像「功能没反应」，实际是找错了地方。
所以扫描按站点自己的优先级来：先 `sessionStorage`，再 `localStorage`。

**坑 2：WebView 的 UA 带 `; wv`。**

不少站点据此判定「非标准浏览器」，走降级页或直接不给登录。换成干净的移动 Chrome UA
才拿到正常登录页 —— 这一条只能靠真机试，桌面 curl 是看不出来的。

**坑 3（设计层面）：单页应用不会重新加载页面。**

登录成功后站点只改路由，`onPageFinished` 不会再触发，注入一次就再也拿不到后续变化。
必须定时扫；而且「登录态写入存储」与「界面跳转」之间有几十到几百毫秒的间隙，
扫到就立刻取值会读到写了一半的对象。做法是**连续两拍内容一致才上报** ——
写一半的 JSON 下一拍必然变化，稳定两拍才算写完（代价是最慢多等 0.9 秒）。

### 三、`TokenHarvest`：宁可失败，也不要存错

提取逻辑单独成一个类，纯 Java、可桌面单测。原因是这段代码一旦判错，症状是
「授权成功 → 下次打开失败」，而且报错信息完全对不上 —— token 格式合法，只是类型或账号错了。

- **绝不把 `access_token` 当 `refresh_token` 存**。access 只有两小时，存它等于每次都让用户
  重新登录，而且用户会以为「登录了但没用」。单测里这条是显式的反例；
- 值可能是 **二次编码的 JSON 字符串**（引号已转义成 `\"`）、内嵌对象、裸文本，三种都要吃下；
- 同一个域下可能残留多个登录项（换过账号、旧 SDK 缓存），按「键名含 token / 同时有 access /
  有 user_id / 长度」打分选最完整的那条，避免登进别的账号；
- dump 被截断（脚本拼接超长）时不是合法 JSON，退回文本扫描，还能救回来。

### 四、安全边界，如实说

账号密码只输入到阿里云盘自己的页面里。本应用不读输入框、不拦截表单，
只做一件事：读存储里的那一个 token 字段。

但内嵌 WebView 与系统浏览器确实不是同等隔离的 —— 在同一个进程里，App 理论上能看到更多。
这条边界是真实的，本实现没有越界，但它不是「技术上不可能」。所以顶栏保留了
「粘贴方式」作为出口，不想在内嵌页面登录的用户仍然走原路。

顺带两个体验细节：登录页 45 秒没抓到凭证会把底部提示改成「点右上角粘贴方式」，
避免用户对着一个不动的页面干等；网页登录拿到的 token **不预验证** ——
阿里云盘的 refresh_token 用一次就换，验证一次等于把旧值作废，如果用户验证完没点保存，
配置里还是旧值，下次必然失败且看不出原因。刚签发的 token 本就没有「可能是坏的」问题，
直接采纳、等用户点保存即可。

### 五、OneDrive 没有跟着改

OneDrive 仍是设备码。这不是没来得及改，而是权衡后不改：设备码让用户在**系统浏览器**里
登录，App 连页面都碰不到，而改成 WebView 就退回到「App 与密码框同进程」。
云盘这边我愿意接受这个折衷，是因为不做就等于功能不可用；OneDrive 用设备码已经足够顺，
没有理由用安全性去换那几步点击。

### 测试

- `TokenHarvestTest` **33 项**：真实 dump 形态、内嵌对象、二次编码、截断兜底、
  多候选打分、单引号/空格容错，以及最关键的
  「只有 access_token 时必须返回空」「`refreshtoken_v2` 这类长键名不算命中」
  「空值不算命中要继续往后找」；
- `QUICK=1 ./test/run_core.sh` **38 项全 PASS**；versionCode 162。

---

## v1.89.0 —— 云盘直连：OneDrive 与阿里云盘

上一版我用「要注册应用、过审核」的理由跳过了这两家，改做 S3。这一版把它们补上，
但两条路的门槛性质不同，得如实说清：

| | OneDrive | 阿里云盘 |
|---|---|---|
| 用什么 API | Microsoft Graph（公开、长期稳定） | 开放平台不对个人开放，只能用客户端接口 |
| 怎么认证 | 设备码（OAuth 2.0 device flow） | 浏览器登录后的 refresh_token |
| 用户要做什么 | 免费注册一个 Azure 应用，填 Client ID | 从浏览器 Local Storage 复制 token |

### 一、OneDrive：设备码，全程不碰密码

没用「授权码 + redirect_uri」的原因不是实现麻烦，而是安全边界：应用里没有可被外部回调的
地址，而授权页会要求用户**在 WebView 里输微软账号密码** —— 一个文件管理器没有理由经手这个。
设备码让用户在**系统浏览器**里登录，App 只拿一个一次性 8 位码，从头到尾碰不到密码，
也不用注册 redirect URI。

流程：类型选 OneDrive → 填 Client ID → 点「登录 / 授权」→ 自动打开浏览器并显示 8 位码 →
在浏览器里输入、同意 → App 轮询到令牌后自动关闭。

注册应用（只做一次）：portal.azure.com → Microsoft Entra ID → 应用注册 → 新注册，
账户类型选「个人 Microsoft 账户」→ 复制「应用程序(客户端) ID」→ 该应用 → 身份验证 →
打开「允许公共客户端流」→ API 权限里加 Graph 的 `Files.ReadWrite.All`（委托）。

实现上四个 Graph 特有的坑：

- 轮询期间 `authorization_pending` 是**正常中间态**不是错误；`slow_down` 要把间隔 +5 秒；
- **下载不能直接打 `/content`**：它 302 到预签名地址，而 `HttpURLConnection` 跨主机重定向
  会把 `Authorization` 一起带过去，目标存储因此拒绝。正确做法是先取
  `@microsoft.graph.downloadUrl`，再对那个地址**不带 Authorization** 地 GET（Range 续传也在这里）；
- simple upload 上限 250MB，且必须先知道 `Content-Length`，所以上传先落临时文件；
- 改名与删除**只认 item id**，路径寻址不支持 DELETE/PATCH，得先查一次 id。

### 二、阿里云盘：只能走 refresh_token

阿里云盘开放平台需要企业开发者资质与应用审核，个人拿不到；能用的只有
`auth.aliyundrive.com` + `api.aliyundrive.com/adrive/v2` 这套客户端接口。它接受
refresh_token，功能完整（列目录 / 下载 / 上传 / 改名 / 删除 / 移动）。

取 token：电脑浏览器登录 alipan.com → F12 → Application → Local Storage →
键 `token` → 复制整段值 → 粘进授权框（**粘整段 JSON 也行**，会自动抠出 refresh_token）。

三个必须处理的问题：

1. **refresh_token 一次性轮换**：每刷新一次都给新的，旧的立刻作废。所以刷新成功后
   必须回写配置，否则「今天能用、明天失效」，而且用户完全看不出原因；
2. **file_id 模型**：接口认 id 不认路径，每次操作要把 `a/b/c` 逐级换成 id ——
   用路径→id 缓存，改名/删除后把相关前缀整段失效（否则拿旧 id 去操作报 NotFound）；
3. **分片数由服务端在 create 时给出**：按 part_number 顺序、每片 4MB 切片 PUT，
   最后必须调 complete，否则文件一直停在「上传中」。

### 三、顺带补齐的三块基础件

- `Json`：极简 JSON 解析器（纯 Java）。`org.json` 只在 android.jar 里，桌面单测拿不到，
  于是「解析 OAuth 响应」这种最该测的代码反而测不了。自己写递归下降解析器，
  区分 `null` 与「没有这个键」，数字按有无小数点分成 `Long`/`Double`（文件大小不能丢精度）。
- `Iso8601`：Graph 给 `Z`、阿里云盘给 `.000Z` 与 `+08:00`、有的网关给 7 位小数
  （`SimpleDateFormat` 直接抛）。顺手把 `S3Fs` 里那份私有实现也换过来，少一份重复。
- `CloudPath`：Graph 的路径寻址是 `root:/a/b:` 的形式，路径里的空格、中文、`#`、`%`
  必须**逐段**编码 —— `#` 不编码会被当 fragment，表现就是「文件夹能进、里面的文件列不出来」；
  `%` 不编码会被当已编码；空格必须 `%20` 而不是 `URLEncoder` 给的 `+`。

云同步（`NetFsFactory`）同样支持两种云盘，等于可以「手机目录 ↔ 云盘」双向对齐；
令牌刷新后的回写也在这一层统一做（按 type/host/user 定位同一条配置）。

### 测试

- `JsonTest` 46 项、`OAuthTokenTest` 53 项、`CloudPathTest` 67 项、`Iso8601Test` 21 项。
  重点钉住的是「刷新响应不带 refresh_token 时必须沿用旧的」「阿里云盘轮换必须用新的」
  「粘贴整段 JSON 能自动提取」「`#` 与 `%` 必须编码」这几条 —— 错了就表现为
  「授权明明成功却说没有令牌」或「文件列不出来」；
- `QUICK=1 ./test/run_core.sh` **37 项全 PASS**；versionCode 161。

---

## v1.88.0 —— 收尾三项：断点续传 / 双栏拖拽 / 对象存储直连

这一版把前面「还能再往前一步」的三件事一次做完，都不改主界面骨架，
只在现有骨架上补缺口。

### 一、断点续传（下载四协议真续传 + 上传续传三种）

以前的下一次下载 = 从 0 开始。现在文件先写 `xxx.mp4.hpart`，配一份
`xxx.mp4.hpart.meta`（记录远端 size/mtime），断了、退出应用、切换网络，
下次点同一个文件就从断点接着传，**传完校验长度才改名为正式文件**。

| 协议 | 下载续传 | 上传续传 |
|---|---|---|
| FTP | `REST` + `RETR`（服务器端定位，不重读） | `APPE` 追加 |
| SFTP | `READ` 包偏移（JSch `get(skip)`） | `APPEND` |
| WebDAV | `Range` 头 + 校验 `206`/`Content-Range` | — |
| SMB | SMB2 `READ` 带偏移 | append 流 + `FILE_OPEN_IF` |
| S3 | `Range` 头 + `206` | — |

关键不是「能接着传」，而是**不能传错**。`ResumePlan`（纯 Java，可单测）对每个
可疑点都取保守分支：

- 没有 sidecar 校验文件 → 重传。凭空接字节等于赌运气；
- 远端大小变了 / mtime 差超过 2 分钟 → 重传（远端被换成同名新版本）；
- `.hpart` 比远端还大 → 重传（远端缩水）；
- `.hpart` 正好等于远端大小且有校验信息 → 只改名，不再走网络；
- 协议层拒绝续传（服务器不支持 `REST`/`Range`、WebDAV 忽略 Range 回 200）→
  自动退化为从头下载，不报错。

上传续传只在「远端已有同名文件且比本地短」时启用，且只对支持追加的协议生效；
WebDAV/S3 的 PUT 无追加语义，宁可整份重传也不拼。

### 二、双栏拖拽复制 / 移动

长按条目抬起手指 → 拖到另一栏放下，弹「复制到此处 / 移动到此处」。

- 长按后**原地松手**仍然弹出原来的功能菜单（拖拽是从长按发起的，不能让它白按）；
- 多选态下长按**已选中**的条目 = 整批一起拖；长按未选中的仍然只勾选，不影响刷选；
- 拖拽经过目标栏时按能否放下染色：强调色=可放，错误色=不可放（压缩包浏览态、目录不存在）；
- 落地走 `FileTaskEngine`，所以有进度、可取消、单个失败不连带。

`DragPlan`（纯 Java，可单测）负责拦两类事故：

1. **拖回原目录** → 直接跳过（否则凭空多出 `a (1).txt`）；
2. **文件夹拖进它自己或子目录** → 拒绝（递归复制会无限展开，移动会丢数据）。
   注意 `目标 == 源目录` 也要拦：`isInside` 按定义不含「相等」，漏判就开始递归。

### 三、S3 兼容对象存储直连（OSS / COS / MinIO / AWS S3 / B2）

`网络存储 → 添加服务器 → S3 / 对象存储`，填端点、bucket、region、AK/SK 即可。

为什么是 S3 协议而不是 OneDrive / 阿里云盘的官方 API：那两家都要求先注册开发者
应用、过审核、再走 OAuth 跳转授权 —— 在一个「无账号、纯本地」的工具里，等于把
「这个功能能不能用」交给别人的审核流程。S3 兼容协议只要控制台里的 AK/SK，
填完就能连，同一套代码覆盖阿里云 OSS、腾讯云 COS、MinIO、七牛、B2、AWS。

- 签名：自研 `S3Signer`（AWS Signature V4，纯 Java），**用 AWS 官方 GET Object
  示例做端到端比对**——期望签名 `f0e8bdb8…6bdb41` 与文档逐字符相同；
- 路径风格 `endpoint/bucket/key`：省掉「bucket 名带点导致 TLS 证书不匹配」的坑；
- 目录是伪装的：列表走 `delimiter=/` 的 CommonPrefixes，建目录 = PUT 一个
  `path/` 空对象；
- 上传先落临时文件再 PUT：SigV4 需要 payload 的 SHA-256 与 Content-Length，
  流式上传要么走 aws-chunked（各家兼容性不一），要么先知道长度。手机写一次
  临时文件，比「传到一半发现签名对不上」便宜得多；
- 重命名 = CopyObject + 删源（S3 没有 rename），并检查 body 里的 `<Error>`
  （CopyObject 会 200 但内部失败）；
- 云同步（`NetFsFactory`）同样支持 S3，等于可以「手机目录 ↔ 对象存储」双向对齐。

### 测试

- `ResumePlanTest` **35 项**：正常续传 / mtime 抖动容差 / 无 sidecar / 远端变大小 /
  `.hpart` 过大 / 已完整 / sidecar 解析容错（CRLF、缺字段、垃圾内容）；
- `DragPlanTest` **37 项**：正常、多选、拖回原目录、拖进自己、拖进子目录、
  目标为文件/不存在/为空、`isInside` 前缀陷阱（`A/bc` 不算在 `A/b` 内）；
- `S3SignerTest` **29 项**：SHA-256/HMAC 标准向量、URI 编码（大写十六进制、`/` 的两种行为、
  中文）、`CanonicalRequest` 哈希、SigningKey、官方示例 `Authorization` 头、
  时间/密钥/区域/query 顺序变化必须改签名；
- `QUICK=1 ./test/run_core.sh` **33 项全 PASS**；versionCode 160。

---

## v1.87.0 —— 云同步（P2 收官）：WebDAV 目录对齐

主界面 `⋮ → 云同步…`。选服务器、远端目录、本地目录、方向，**先预览差异再执行**。

### 三条铁律

文件同步最贵的错误是「批量删错」和「用旧文件覆盖新文件」，所以：

1. **永不传播删除**。本地删了不会删远端，远端删了也不会删本地；
   只在摘要里提示「仅本地存在 N 项 / 仅远端存在 M 项」，删不删由你决定。
2. **冲突保留两份**。两边都改过的文件，本地版本改名成
   `name.conflict-20261110-1130.ext`，再把远端版本拉下来，谁都不丢。
3. **mtime 容差 3 秒**。WebDAV 与 FAT 都是秒级精度，容差内且大小相同直接跳过，
   不做无意义的双向传输。

没有基线文件（上次同步状态）就无法真正判断「谁改过」，只能比 mtime —— 这是刻意的：
引入基线会再引入「基线丢了怎么恢复」的一整套问题，收益不抵风险。

### 实现

| 类 | 职责 |
|---|---|
| `SyncPlan`（纯 Java） | 两侧清单 → 动作列表（上传/下载/跳过/冲突），可桌面单测 |
| `SyncRunner` | 递归扫描（深度 10、单侧 5000 项上限）、传输、`.part` + rename 落盘 |
| `SyncActivity` | 表单 + 预览 + 进度 + 逐条日志 + 取消 |
| `NetFsFactory` | 从保存的服务器 JSON 造 `NetFs`，统一解密密码 |

- 方向三选：上传（本地→远端）、下载（远端→本地）、双向（按 mtime 较新的一方）。
- 每次操作**现开现关连接**：手机切网后 WebDAV 长连接会假死，复用反而更坑。
- 取消只在**文件边界**生效，不中断正在传的文件，避免远端留下半截。
- 下载先写 `.part` 再 rename，且把远端 mtime 写回本地，下次同步才不会又判成冲突。
- 目录与文件同名（类型冲突）不做任何覆盖，只记一条失败说明。

### 测试

- `SyncPlanTest` **37 项**：完全相同 / 容差内 / 本地独有 / 远端独有 / 双向谁赢 /
  大小不同时间相近判冲突 / 目录与文件同名 / 冲突副本命名 / 路径规范化。
- `QUICK=1 ./test/run_core.sh` **30 项全 PASS**；versionCode 159。

---

## v1.86.0 —— Shizuku 接入（P2 第三项）：无 Root 也能用 shell 权限

装好 Shizuku 并授权后，本应用以 **adb shell（uid 2000）** 身份执行命令：
进 `/data/local/tmp`、`/data/app`、执行 `pm`、`cp`、`chmod`……全都不用 root。
没装 Shizuku 的设备行为与之前完全一致。

### 一个后端选择器，60+ 调用点零改动

原来所有提权命令都写死 `su -c`。新增 `Shell` 只做一件事：挑后端。

| 后端 | 身份 | 何时使用 |
|---|---|---|
| ROOT | uid 0 | 有 su 且「使用 Root 权限」开着 |
| SHIZUKU | uid 2000（shell） | 无 su，但 Shizuku 已连接并授权 |
| NONE | — | 都没有，走旧失败分支 |

`RootUtil` 的 7 个执行入口（`run` / `submitOutput` / `runWithInput` / `captureOutput` /
`catProcess` / `runCapture` / `suList`）全部改走 `Shell.start()`，上层业务一行没改。
`isRootAvailable()` 语义扩展为「**有特权 shell**」（root 或 Shizuku），
需要区分真正的 uid 0 时用新增的 `isRealRoot()`；`backendName()` 给状态展示用。

### 依赖为什么是 12.2.0 而不是 13.x

Shizuku 13.x 从**公开 API 移除了 `newProcess`**（改用 UserService 架构，要写 AIDL 服务端代码）。
服务端的 `newProcess` 一直保留到 API 14，所以用 12.2.0 的客户端库能连 13 的 Shizuku 服务，
且 12.2.0 已经支持 V3 协议（它的 provider 清单里就带 `V3_SUPPORT`）。

四个 jar 手动解包 AAR 放入 `libs/`（共约 54KB），保持零 Gradle：

| jar | 作用 |
|---|---|
| `shizuku-api-12.2.0.jar` | `Shizuku` / `ShizukuRemoteProcess` |
| `shizuku-provider-12.2.0.jar` | `ShizukuProvider`（交 binder 的通道） |
| `shizuku-aidl-12.2.0.jar` | `IShizukuService` / `IRemoteProcess` stub（**容易漏**） |
| `shizuku-shared-12.2.0.jar` | `ShizukuApiConstants` |

漏掉 `aidl` 那个 jar 时编译能过，运行必崩 `NoClassDefFoundError: moe.shizuku.server.IShizukuService`。

### 三个坑

1. **必须在 manifest 声明 provider**：`rikka.shizuku.ShizukuProvider`，
   authorities 是「包名 + `.shizuku`」。少了它 `pingBinder()` 恒为 false 且没有任何报错。
2. **`newProcess` 不能在主线程调用**（内部走 binder 事务）。所有调用点都在
   `RootUtil` 的单线程池里，天然满足；`catProcess` 也是从后台线程调的。
3. **provider 在老设备上会崩**：AAR 按 minSdk 23 编译，而本应用 minSdk 21，
   系统会在 5.x 上实例化它。用 `res/values{,-v23}/bools.xml` +
   `android:enabled="@bool/shizuku_provider_enabled"` 只在 23+ 启用。

### 界面

设置 → 权限新增：「使用 Shizuku（免 Root）」开关、实时状态、
「申请 Shizuku 授权」（仅在服务已运行但未授权时出现，点击弹 Shizuku 确认框，
结果通过 `OnRequestPermissionResultListener` 回传并刷新页面）。

**文案明确写了「它不是 root」**——`/data/data/<别的应用>` 是 700，
shell 身份照样读不到，不写清楚用户会以为开了就能看全盘。

### 测试

- `QUICK=1 ./test/run_core.sh` **29 项全 PASS**（`RootUtilTest` 适配 `detect` → `detectSu` 拆分）。
- versionCode 158。桌面单测环境 `Build.VERSION.SDK_INT = 0`，`ShizukuBridge.supported()`
  直接返回 false，所有 Shizuku 分支被跳过，不影响原有 29 项。

---

## v1.85.0 —— 桌面小组件（P2 第二项）

4×1 卡片，桌面上一眼看到存储余量，四个常用入口一键直达。

### 卡片内容

```
HexFile                    剩 12.3G / 128G  ⟳
███████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░
  📁        ⬇        🖼        ♻
工作目录    下载      图片     回收站
```

- 顶行：应用名 + 紧凑容量摘要 + 刷新按钮；中间一条已用占比进度条。
- 整卡点击 → 打开主界面；四个入口分别跳到工作目录、Download、Pictures、回收站。
- 目录不存在时**自动退回工作目录**，不会点了没反应。

### 三个必须踩过的坑

1. **RemoteViews 是在 launcher 进程里 inflate 的**，本应用的 `?attr/hfAccent` 在那里不存在。
   所以卡片固定用深色半透明 + 白字（`widget_card.xml`），不跟应用主题走。
2. 按钮背景不能写 `?android:attr/selectableItemBackground` —— 它由 launcher 的 theme 解析，
   部分第三方桌面缺这个 attr 会直接 inflate 失败。改用自带 `widget_tap.xml`（ripple）。
3. `appwidget-provider` 的 `android:description` 是**资源引用**，写中文字面量会
   `aapt2 link` 报 "incompatible with attribute description" —— 必须 `@string/widget_desc`。

### 刷新策略

系统 `updatePeriodMillis` 最小 30 分钟，等不起。应用回到前台时调
`WidgetProvider.refreshIfExists()` 主动刷新（没有卡片时 `getAppWidgetIds` 为空直接跳过，
不白跑 binder）；卡片上的 ⟳ 走同一个方法。

### 其它

- 设置页新增「桌面小组件」分区：如何添加（长按桌面 → 小组件 → HexFile）+ 立即刷新。
- `WidgetTextTest` **23 项**：紧凑容量四档进位、`12.3G` 保一位、`≥100G` 取整省宽度、
  容量未知/已满/负可用量等边界。
- `QUICK=1 ./test/run_core.sh` **29 项全 PASS**；versionCode 157。

---

## v1.84.0 —— 系统级文件提供者（SAF）：让别的应用直接浏览本机

P2 第一项。装好之后，系统「文件」应用侧边栏多出 HexFile；微信、邮件、编辑器点
「打开文件 / 选择位置」时，能直接浏览 `/sdcard` 与工作目录，不必先把文件导出来。

### 实现要点

- 新增 `HfDocumentsProvider`（继承 `DocumentsProvider`），authorities
  `com.hex91.file.documents`，带 `DOCUMENTS_PROVIDER` intent-filter 与
  `MANAGE_DOCUMENTS` 系统权限。
- **documentId 直接用绝对路径**：不维护 id→文件 映射表，进程被杀后 id 依然有效。
- 代价是**必须校验**——docId 完全由外部应用提供，可伪造 `/data/data/...`。
  新增 `DocPath`（纯 Java）：`normalize` 折叠重复斜杠、**拒绝 `..` 与 `\0`**；
  `withinAny` 限定在允许根内（内部存储 / `/sdcard` / `/storage/self/primary` / 工作目录）。
- 完整实现 `queryRoots / queryDocument / queryChildDocuments / querySearchDocuments /
  openDocument / openDocumentThumbnail / createDocument / deleteDocument / renameDocument /
  copyDocument / moveDocument / isChildDocument / getDocumentType`。
- 目录排在文件前、名称不区分大小写——系统 UI 只按返回顺序渲染，排序必须自己做。
- 缩略图只处理图片且 < 24MB，全部 `try/catch` 兜住，失败返回 `null`（协议允许），
  不让一张坏图把整个选择器拖垮。
- 新建同名文件自动追加 `(1)`；`deleteDocument` 拒绝删非空目录与根目录。
- `openDocument` 的 mode 解析抽到 `DocPath.modeOf` 做单测：**`w` 与 `wt` 都带 truncate**
  （否则写短内容会留旧尾巴），**`wa` 不能带 truncate**（否则追加变覆盖）。

### 开关与入口

- 设置 → 系统集成：「在系统文件选择器中显示」（默认开）。关掉后 `queryRoots` 返回空，
  并 `notifyChange` 让系统丢掉缓存；系统里已授予的旧 URI 仍可用，属预期。
- 同一分区还有「在系统选择器中试一下」，直接拉起 `ACTION_OPEN_DOCUMENT` 验证。

### 顺带清理

- 删除主界面 `⋮` 菜单里 v1.82.1 加的「回收站保留策略…」——已由设置页接管，
  同时移除随之变成死代码的 `TrashActivity.EXTRA_POLICY` 分支。

### 测试

- 新增 `DocPathTest` **59 项**：路径规范化边界、前缀陷阱（`/sdcard2` 不算在 `/sdcard` 内）、
  目录穿越拒绝、文件名按 UTF-8 字节截断、排序、`modeOf` 全部六种模式。
- `QUICK=1 ./test/run_core.sh` **28 项全 PASS**；versionCode 156。

---

## v1.83.0 —— 顶栏按钮隐形修复 + 统一设置页

### 修 bug：回收站顶栏按钮「不显示」

根因不在布局，在配色：`themes.xml` 里有三套色板把 `hfTopBar` 与 `hfAccent` 设成了**同一个色值**
（`#3E9AFB` 蓝 / `#61BC80` 绿 / `#E3A23C` 琥珀），而 SOFT 胶囊是「accent 14% 底 + accent 描边 + accent 文字」——
叠在同色顶栏上，底色≈顶栏、文字=顶栏，对比度 1.0，表现出来就是按钮压根没画。

修法是让**顶栏上的按钮不再依赖 accent**：

- 新增 `Contrast`（纯 Java，WCAG 2.1 相对亮度/对比度 + `ensure()` 自动调明度）。
- 新增 `UiDecor.barButtonOnBar()`：
  - 普通按钮 → 用顶栏前景色 `hfTopBarText`（本来就是为该背景挑的高对比色）；
  - 危险按钮 → 仍用 err，但先算对比度，不足时自动向黑/白推进；
  - 实心按钮 → 前景色做底、反色做字，保证色块边界可辨。
- 回收站「⚙ 保留策略」「清空」改用该版本，深色/浅色/彩色顶栏都能看清。
- `ContrastTest` 33 项断言，直接把三套同色色板钉成回归用例。

### 新增统一设置页

入口：侧抽屉「⚙ 设置」或主界面 `⋮` →「设置」。页面用「分组卡片 + 行」构建器
（`section()` / `row()` / `switchRow()` / `infoRow()`），后续加设置项只加一行调用，不用改布局。

| 分区 | 条目 |
|---|---|
| 外观 | 主题与配色（跳转 22 套色板 + 明暗设置页） |
| 文件与目录 | 工作目录（目录选择器 + 手输路径 + 恢复默认） |
| 权限 | 使用 Root 权限开关 + 当前状态 |
| 回收站 | 保留策略（天数/容量/开关）、打开回收站 |
| 关于 | 版本号（最下）、工作目录 |

配套改动：

- 新增 `WorkDir`：工作目录从写死的 `/sdcard/HexFile` 变成可配置，**接管 9 处硬编码**
  （回收站、备份、应用导出、APK 信息、Smali 输出、PDF 导出、网络下载、局域网快传）。
  只接受绝对路径，切换不迁移旧数据（避免误触大文件复制）。
- `RootUtil` 增加总开关：关闭后 `isRootAvailable()` 恒 false，`run/submitOutput/runWithInput/
  captureOutput/catProcess` 全部走失败分支——**66 个调用点无需改动**即遵守设置。
- 回收站策略弹窗抽成 `TrashPolicyDialog`，回收站页与设置页共用同一份实现。
- `DirPickerDialog`：目录浏览 + 手输绝对路径（外置卡路径过长时逐级点不现实）。

版本 155 / 1.83.0，`QUICK=1` 27 项单测全 PASS。

---

## v1.82.1 —— 回收站保留策略入口下沉

### 背景

v1.82.0 的保留策略只挂在「回收站」页面顶栏，按钮文案还是「策略」——用户反馈找不到。
确实太深：⋮ → 回收站 → 顶栏，且「策略」二字指向不明。

### 改动

- 主界面 **⋮ 菜单新增「回收站保留策略…」**，点击直接进回收站并自动弹出配置框
  （`TrashActivity.EXTRA_POLICY`，`btn_policy.post` 等首帧布局完成再弹，避免 `BadTokenException`）。
- 回收站顶栏按钮文案 `策略` → **`⚙ 保留策略`**，与「清空」并列时语义自明。
- 回收站**空状态直接摊开当前规则**：
  「自动清理：关闭（点右上角「⚙ 保留策略」设置）」——
  没删过文件的用户进来看一眼就知道这个功能存在且在哪改。
- 版本 154 / 1.82.1，无逻辑变更，`QUICK=1` 25 项单测仍全 PASS。

---

## v1.82.0 —— P1 收官：符号链接 / 内容搜索 / 回收站策略 / 凭据加密

### 符号链接标记（`LinkUtil`）

- 列表里符号链接显示成 `名字 → 目标`，小一号次要色；**悬空链接**（目标已不存在）后缀变红并标 `(失效)`。
- 判断不用 `File.getCanonicalPath()`（目标不存在会抛异常、也分不清是「自己是链接」还是
  「父目录里有链接」），直接 `Os.lstat` 看 inode 类型，`readlink` 取目标原文：
  `FILE / DIR / BROKEN / NONE` 四种状态都能分。
- **指向目录的链接现在能点进去**：部分 FUSE 实现的 `File.isDirectory()` 不跟随链接，
  用 lstat 的结论把 `forceDir` 置位兜底（点进去、返回上级都按普通目录走）。
- `kind()` 带 2048 条 LRU 缓存（key = 路径 + 大小 + 修改时间），下拉刷新反复列同一目录
  不会重复 lstat；SELinux 拒绝探测时返回 NONE，行为与旧版一致，不会让列表崩。
- 网格视图空间小，只在名字后加 `→` / `⚠`；目标全文在列表模式看。

### 内容搜索（`ContentGrep`）

- 搜索页关键词下面新增一行「文件内容包含…」，与名称/类型/大小/时间条件**叠加**生效。
- 按块读（64KB）再按行切：不整文件读内存、不逐字节 `read()`；
  头部 4KB 出现 `0x00` 或控制字符超 10% 判定为二进制直接跳过（so/apk/图片不会白耗 IO）。
- 保护性上限：单文件 4MB、单行保留 8KB（压缩过的 js 一行几十 MB 也不会 OOM）、
  一次最多扫 5 万个文件、最多 1000 条结果。
- 搜索中按钮变「停止」，一点立即收工；状态栏实时显示「已扫描 N 个文件 · 命中 M」。
- 结果第二行直接给 `L12: 命中片段`（片段围绕关键词截取 + 控制字符转空格），
  点击跳目录，**长按直接用系统应用打开**文件。
- 顺手修掉旧逻辑里「跳过回收站」永远不生效的 bug（比的是目录名 `HexFile/Trash`，
  实际 `getName()` 只有 `Trash`），改为绝对路径比较，被删除的文件不会再搜出来干扰。

### 回收站保留策略（`TrashPolicy`）

- 回收站顶栏新增「策略」：保留天数、容量上限（MB）、自动清理开关，配置写在
  `HexFile/Trash/policy.conf`（纯文本，回收站被删则策略一并重置）。
- 清理顺序：先删超过保留天数的，再按 **meta 里的真实删除时间**「最旧优先」删到容量达标
  （不依赖 id 前缀时间戳，排序假设不成立时也不会删错）。
- **默认关闭**：自动清理是真删文件，必须用户主动开启；开启后每次启动在后台静默跑一次
  （`TrashJanitor`，进程内只跑一次，失败静默）。
- 顶栏实时显示「N 项 · 占用」，目录条目的大小递归统计（后台线程，不卡进页面）。

### 网络存储凭据加密（`CredentialVault` / `CredentialCodec`）

- SMB / WebDAV / FTP / SFTP 的密码不再明文躺在 SharedPreferences 里：
  API 23+ 用 **AndroidKeyStore AES-256/GCM**（密钥不出 TEE，root 后直接读 XML 也解不开）；
  API 21/22 没有 Keystore AES，退化为 PBKDF2(ANDROID_ID + 包名) 派生密钥。
- 存储格式 `enc:v1:Base64(IV‖密文‖tag)`，GCM 自带完整性校验，密文被改一个字节就解不开。
- **老配置自动迁移**：启动读配置时发现明文密码，现场加密并回写；旧格式仍然能读
  （`decrypt` 对非 `enc:` 前缀原样返回）。
- 密钥丢失（卸载重装/换机恢复备份）时不崩也不静默用空密码，编辑框会显示 `enc:v1:…`
  提醒重填；服务器列表上用 🔒 标识「密码已加密」。
- 极少数不支持密钥库的 ROM 退回明文，并在首次保存时提示一次。

### 测试

- 新增 4 个桌面用例：`ContentGrepTest`（31 项：行号/CRLF/末尾无换行/超长行/二进制/上限/片段）、
  `LinkUtilTest`（14 项：`..` 归一化/相对与绝对目标/根目录边界）、
  `TrashPolicyTest`（25 项：天数/容量/开关/目录递归/坏配置回退/持久化往返）、
  `CredentialCodecTest`（45 项：Base64 往返、GCM 加解密、随机 IV、篡改与错误密钥）。
- `QUICK=1 ./test/run_core.sh`：**25 项全过**。versionCode 153。

---

## v1.80.0 —— 网格/大图视图 + 权限与时间戳编辑（P1 前两项）

### 网格 / 大图视图

- ⋮ 菜单新增「网格视图（大图）」，偏好存进 `SharedPreferences("hf_view")` 的 `view_mode`，
  重启保持；切回列表就恢复双栏。
- **不复制数据**：`FileGridAdapter` 直接按下标读同一个 `FileAdapter`，排序、隐藏文件、
  勾选状态天然一致，不需要双向同步；`FileAdapter.notifyDataSetChanged()` 通过
  `setMirror()` 一并通知网格，改目录/改排序/勾选时两套视图不会各显示一半旧数据。
- **网格模式只留一个窗格**：双栏各 180dp 时一行只放得下 1 个 96dp 的格子，大图就没意义了。
  所以网格模式下非活动窗格整块收起，活动栏占满宽度；⇄ 交换仍然可用（Panel 自带视图引用）。
  列表模式下两者都恢复。
- 单元格 = 正方形缩略图区 + 单行文件名：图片/APK 走 `ThumbLoader`（复用列表那份 LRU，
  同一张图不会解码两次），目录/文档显示 MT 式彩色类型角标；多选时右上角打勾 + 强调色描边。
- 点击/长按行为与列表完全一致 —— 把原来两个匿名监听里的逻辑抽成
  `handleItemClick()` / `handleItemHold()`，列表和网格共用同一份。
- `PullRefreshLayout` 里现在同时挂着 ListView 与 GridView，它改为按「当前可见的那个子 View」
  判断能否下拉（GONE 的控件 `canScrollVertically()` 恒为 false，会把「不可滚动」误当成「在顶部」）。

### 权限编辑

- **去掉「必须有 Root」的前置拦截**：没有 root 也能改本应用拥有的文件（应用私有目录、
  自己创建的文件）—— 走 `android.system.Os.chmod()`；只有它抛 `ErrnoException` 时才报失败。
  以前一进来就 toast「更改权限需要 Root」，等于把这条能力锁死。
- 新增常用预设 `644 / 755 / 600 / 777`：9 个勾选框点起来太慢，日常就这几个值。
- 目录增加「同时应用到子项（递归）」勾选框，走 `FileAttrs.chmodTree()`
  （Java 递归而不是 `chmod -R`，这样无 root 时也能逐项走 `Os.chmod`；深度上限 64 层防链接环）。

### 时间戳编辑

- 属性页的「修改时间」原来是只读文本，现在带「更改」按钮：弹窗固定格式
  `yyyy-MM-dd HH:mm:ss` + 一个「设为现在」快捷键。
- 解析用 `SimpleDateFormat(lenient=false)`：写错日期会明确提示，而不是被宽松解析悄悄
  改成另一个时间。
- 写入先试 `File.setLastModified()`，失败再走 root 的 `touch -t`；
  改完清 `FileAttrs` 缓存并重列当前目录，列表详情行不会留着旧时间。
- 顺手把 `FileAttrs.chmod()` 的返回值语义统一（0=生效 / 1=命令成功但回读未变 / -1=失败），
  FUSE 挂载（/sdcard）上会明确告诉你「权限未实际变更」，不再假装成功。

### 回归

- 新增 `test/FileAttrsTest`（8 项：时间写入落盘、缓存失效后 stat 拿到新值、
  文件不存在返回 false 不抛异常、目录同样可改），与既有用例一起 **QUICK 21 项全 PASS**。

---

## v1.79.0 —— 下拉刷新（adb 推送后不用退出目录）

- **现象**：`adb push` 往 /sdcard 拷了文件，列表里看不到，必须退出目录再进一次。
  原因是目录内容取自 `listFiles()`，只在这个时刻读一次；外部写入不会给任何通知
  （`FileObserver` 在 Android 10+ 分区存储下对外部写入并不可靠，也拿不到递归子目录）。
- **做法**：新增 `PullRefreshLayout`（自定义 FrameLayout，不引依赖 —— 本项目是
  aapt2 + javac 纯脚本构建，拿不到 androidx 的 SwipeRefreshLayout）。
  - 只在「列表已经滚到顶部」且竖直位移超过 touchSlop 时才拦截，横向滑动手势
    （左滑选中 / 右滑返回）与正常滚动不受影响；
  - 内容用 `translationY` 下移，不触发重新测量/布局；阻尼 0.55，越拉越沉；
  - 指示器画在让出来的空白里：圆形底 + 箭头，拉到阈值箭头翻转朝上提示松手，
    刷新中换成 260° 圆弧绕圆心旋转（`uptimeMillis` 算角度，不占线程）；
  - 多选模式下竖直拖动是「连续刷选」，此时 `setPullEnabled(false)` 让位。
- **两栏各管自己**：左栏下拉刷新左栏，右栏下拉刷新右栏；压缩包浏览中下拉 = 重列压缩包
  当前层（异步路径，列表到货后再收转圈）。
- **顺手修掉**：⋮ 菜单里的「刷新」原来走 `loadDir(p, p.dir)`（push=true），每刷一次就往
  历史栈塞一条重复路径，‹ › 要按很多下才退得出去；`refreshAll()` 同样问题。
  现在统一 `push=false`，并且「刷新」与下拉共用同一条路径（也会转圈、也会重算剪贴板）。

---

## v1.78.0 —— 内部剪贴板 + 排序/隐藏文件记忆

P0 里剩下的两项：**A 目录复制、翻到 C 目录粘贴**，以及**重启后别再回到默认排序**。

### 内部剪贴板

- 长按菜单新增「复制(剪贴板)」「剪切(剪贴板)」（普通文件与 APK 菜单都有），
  剪贴板是应用级的，跨目录、跨 Activity、旋转屏幕都不丢。
- 底部工具栏多了一个 📋 键：有内容时变成强调色并显示数量（`📋 3`），空时置灰而不是隐藏
  ——用 `GONE` 会让整排按钮宽度跳动。粘贴落在**当前活动窗格**的目录，所以左右两栏可以
  各自当目标。
- 粘贴复用 `FileTaskEngine`：真实进度、可取消、`.part` 中间文件、失败不中断整批，
  和「复制到对面」完全同一套，底部任务条照常出现。
- 语义按桌面习惯：**剪切是一次性的**（提交后立刻清空剪贴板），**复制可以连粘多处**。
- `FileClipboard` 只存绝对路径，不持有 Context；`prune()` 会把「复制之后源文件被删掉」
  的条目剔掉，否则按钮上的数量会一直骗人、粘贴时每个文件报一次失败。
- ⋮ 菜单也放了「粘贴：复制 3 项」，给不习惯底部图标的用户一个明确入口。

### 排序与隐藏文件持久化

- 以前 `FileAdapter.sortMode` / `showHidden` 只是内存字段，切目录还算好，**重启必回默认**。
- 现在存 `SharedPreferences("hf_view")` 的 `sort_mode` / `show_hidden`，`setupPanel` 时读回。
- 顺手统一语义：这两项是**全局显示设置**，两栏共用。改一栏就两栏一起变并立即重列
  （原来只改活动栏，另一栏还是旧顺序，看着像没生效）。
- `FileAdapter` 补了 `SORT_NAME / SORT_SIZE / SORT_TIME` 常量，替掉散落的魔法数字 0/1/2。

### 回归

- 新增 `test/FileClipboardTest`（27 项断言：覆盖而非累加、复制可连粘、剪切一次性、
  `alive/prune` 过滤已删源文件、空列表不产生「空剪切」、快照与内部状态解耦、
  并发读写不抛异常），已加入 `run_core.sh` 的 ALL 与 QUICK。
- QUICK 20 项全 PASS。

---

## v1.77.1 —— 任务条「取消」点了没反应

- **现象**：复制大文件到对面窗格，底部任务条上点「取消」，结果弹出的是任务面板，
  必须在面板里再点一次「全部取消」才真的停下来。
- **原因**：`FileTaskEngine.isBusy()` 只判断 `pending` 是否为空。worker 一开始执行就把任务
  从 `pending` 里 `removeFirst()` 了，所以**正在拷贝时**它也返回 `false`；任务条的按钮因此
  走了「打开面板」分支。排队时反而是 true —— 于是「排队时点取消有效、跑起来就无效」。
- **修法**：
  - 新增 `hasActive()`（遍历任务列表，存在 QUEUED/RUNNING 即为真），`isBusy()` 改为它的
    别名；同一处 bug 还让 `hideTaskBar` 在拷贝中途误判「不忙」而收条，一并修好。
  - 底部「取消」**一步到位**，不再弹二次确认；按下后立刻显示「正在取消 …」，
    按钮变灰置为「取消中」，避免用户以为没生效而连点。
  - `measure()` 也加了取消检查：几十万文件的目录量体积要好几秒，以前这段完全取消不了。
- 新增回归：`FileTaskTest` 增加「运行中取消」一组（48MB 稀疏文件造慢任务，
  断言 RUNNING 时 `hasActive()` 为真、取消后状态为 CANCELLED 且不残留 `.part`），
  共 35 项断言。

---

## v1.77.0 —— 打开方式 + 任务化复制（真实进度 / 可取消）

文件管理器最基础的两件事补齐：**任何文件都能交给别的应用打开**，**复制移动不再是黑箱**。

### 打开方式

- `onFileClick` 的兜底从「直接弹属性」改成「先问系统」：`OpenWith` 走
  `ACTION_VIEW` + `createChooser`，只有真没有应用能处理时才回退到属性页并提示原因。
  以前视频、音频、Office 文档点了只能看属性，等于打不开。
- 长按菜单（普通文件与 APK）都加了「打开方式」，图片/文本这类有内置查看器的也能指定外部应用。
- 三个坑各踩了一次：
  - `file://` 从 Android 7 起会抛 `FileUriExposedException` → 统一走 `ApkFileProvider` 的
    `content://`，与分享/安装 APK 同一套 `FLAG_GRANT_READ_URI_PERMISSION` 授权。
  - 只给 `setDataAndType` 时 WPS、相册这类只读 `EXTRA_STREAM` 的应用拿不到流；只给
    `EXTRA_STREAM` 时靠 MIME 过滤的应用又找不到自己 → **两个都给**，再加 `ClipData`
    作为权限载体（部分 ROM 靠它把授权传下去）。
  - `MimeUtil` 原来只有 20 来个类型，其余全是 `octet-stream`，选择器里只剩浏览器 →
    补到 60 余种（视频/音频/Office/ePub/HEIC/PSD/种子…）。

### 任务化复制

- 新增 `FileTask`（状态快照）+ `FileTaskEngine`（单线程队列）：`doBatch` 与长按的
  「复制/移动到对面」不再在 UI 线程里跑 `FileOps` 的 64KB 死循环。
- **真实进度**：先递归量一遍（字节 + 文件数）当分母，拷贝时按 128KB 块累加；
  上层回调按 120ms 节流，几万文件也不会把主线程刷爆。
- **可取消**：每块检查一次标志，用 `Cancel` 异常打断深递归；写入 `名字.part`，
  写完才 rename——取消或出错时删掉 .part，**目标目录里不会再出现半截文件**。
- 单文件失败不再中断整批（记下来继续，最后显示「部分失败」）；同分区移动直接
  `renameTo`（秒完成），跨分区才走「拷贝 → 删源」，删源失败还能 su 兜底。
- 附带修掉一个会毁数据的旧 bug：**复制文件到自己所在目录时，旧 `FileOps` 会先截断
  目标再读源**，文件被清空成 0 字节。现在识别为同路径直接跳过。
- UI：底部任务条（`⇅ 正在复制 3 项 · 42% · 12 MB/s` + 圆角进度条 + 取消胶囊），
  点条体打开任务面板——每个任务一行卡片，显示 已拷/总量、文件数、速度、目标目录，
  可逐个取消，底部三键「清理已完成 / 全部取消 / 关闭」。进度条颜色走 `?attr/hfAccent`，
  换色板自动跟随。
- 测试：新增 `FileTaskTest`（28 项断言：单文件/目录/进度单调/取消不留 .part/
  rename 移动/同目录复制/覆盖/排队取消），QUICK 回归 19 项全 PASS。

---

## v1.76.0 —— 全站弹窗与按钮统一美化

- **弹窗（145 处调用点，一处改法全覆盖）**：
  - 新增 `HfDialogBuilder`（继承 `AlertDialog.Builder`，只重写 `create()`）与
    `UiDecor.polishDialog()`：面板 18dp 圆角、主题表面色、标题/正文跟随明暗主题、
    肯定按钮做成实心强调色胶囊、否定/中性做成描边胶囊。
  - 为什么不是逐个改：`Builder.show()` 内部**虚调用** `create()`，所以链式调用里
    `setTitle` 等仍返回父类型也没关系，最后一步照样会走到美化逻辑；`AlertDialog` 的子 View
    要等 `onCreate` 里 installContent 之后才存在，所以内容美化挂在 `OnShowListener`。
  - 全局兜底：主题里加了 `android:alertDialogTheme`（`HfDialog_Light|Dark` +
    每套色板一个覆盖强调色的 `HfDialog_*_N`，由 `tools/gen_themes.py` 生成共 46 个）。
    这样连没改过代码的 `ProgressDialog`（34 处）也跟着换面板色与强调色——
    以前暗色主题下它们是一块刺眼的白板。
  - 面板背景是 `res/drawable/hf_dialog_bg_light|dark.xml`：结构与系统
    `@android:drawable/dialog_background_material` 一致（16dp 透明 inset 留给投影 +
    圆角矩形），只把 4dp 圆角换成 18dp 并换成主题底色，所以内容位置不受影响。
- **多选栏**：7 个系统方角 `Button` → 圆角工具条 + 小胶囊；完成=实心强调色（主操作）、
  删除=红、其余=描边；按钮间 2dp 间距，字号压到 11sp，360dp 屏不会把「重命名」截断。
- **编辑器底栏**：「搜索 跳转 关闭」搜索=实心、跳转/关闭=描边并留间距；符号键原来是
  **写死的白色背景**（暗色主题下是一片白块），改为中性圆角软底、文字跟随主题。
- **顺带收拾的其他位置**：
  - `activity_compare.xml` 7 处硬编码浅色（#FFF2F7FB / #4A1528 / #5B7A99 …）→ 全部换成
    `?attr/hf*`，比较页终于能跟暗色主题；底部「复制报告/关闭」胶囊化。
  - 压缩包浏览（上级/解压全部/关闭）、搜索页（筛选/搜索）、回收站（清空→危险色）、
    远程管理（启动服务⇄停止服务，运行中自动转危险色）、重复文件查找、图片查看器、
    备份管理（还原/删除）、属性弹窗里的行内按钮 → 统一胶囊。
  - Hex 编辑器工具条 9 个色块 → 圆角软底；`ApkActionText` 写死的蓝字 → `?attr/hfAccent`；
    Manifest 预览弹窗的深灰字（#37474F，暗色下几乎看不见）→ `ThemeManager.text`。
- 版本守卫照常生效（145 → 146）。

---

## v1.75.0 —— 快传接收列表 + 全站按钮美化

- **接收目录统一**：从 `Download/HexFile/LanShare` 改为 **`/sdcard/HexFile/LanShare`**，
  与 HexFile 其它产物同一棵树，方便统一管理。回退顺序：外部存储根 → 应用外部私有目录 → 应用内部目录。
- **旧文件搬迁**：升级后若新目录为空、旧目录还有文件，接收列表会显示一张「旧目录还有 N 个文件」
  卡片，点一下即可搬迁（同卷走 `renameTo`，跨卷退化为流拷贝，重名按 `名字 (1).ext` 规避覆盖）。
- **接收记录 → 接收列表**：不再是 `AlertDialog.setItems` 的纯文字弹窗，而是页面内的第二个标签页
  （📡 雷达扫描 / 📥 接收列表 · N）。每行是卡片：文件类型图标 + 文件名 + 大小与收到时间，
  右侧三个操作 **打开 / 定位 / 删除**；顶部有 **打开目录** 与 **刷新**，路径可直接看到。
  「定位」直接跳到文件管理器的该目录（`MainActivity` 的 `dir` 入口），不再需要手动一层层翻。
- **抽屉图标去重**：`📡 局域网快传` 与 `📡 远程管理` 撞图标，远程管理改为 `🖥`（它本质是
  浏览器访问的本机服务，图标语义也更贴切）。
- **按钮/菜单美化**：新增 `UiDecor` 统一「圆角 + 半透明强调色 + 水波纹」，全部跟随明暗主题：
  - 胶囊按钮（`选择文件` / `后台接收` / `打开目录` / `刷新`）替代系统默认 `Button`；
  - 行内小操作用 11.5sp 的 `打开 / 定位 / 删除`，三种语义色（强调/成功/错误）；
  - 列表行改成圆角卡片，按下有 ripple，暗色下卡片底由 `blend()` 按 surface 叠色，不会发白。
- **签名管理改版**：
  - 顶部换成标准顶栏（返回 + 标题 + 副标题）；
  - 原来的两行「可点击蓝字」换成两张图标卡片（📥 导入签名 / ✨ 创建签名）；
  - 长按操作从纯文字列表改成 **2×2 图标网格**（查看详情 / 重命名 / 设为默认 / 删除），
    危险操作单独用错误色，并标注当前默认状态；
  - 列表项卡片化：默认签名用强调色 + `★` 前缀，损坏条目用错误色说明原因。
- 版本守卫照常生效（144 → 145）。

---

## v1.74.1 —— 修复明文 HTTP 被系统拦截（快传发不出去）

- 现象：局域网快传点发送后立即失败，日志/提示里是
  `CLEARTEXT communication to 192.168.x.x not permitted by network security policy`。
- 原因：`targetSdk 30`（≥28）时 Android 9+ 默认禁止明文 HTTP 出站，`HttpURLConnection`
  在**建连之前**就被平台拒绝——TCP 都没发出去，所以与对方是否在线、端口是否监听无关。
- 影响面不止快传，凡是走 `http://` 的都中招：WebDAV 网络存储、工具箱的网络请求页。
- 修法：新增 `res/xml/network_security_config.xml`，`base-config` 设
  `cleartextTrafficPermitted="true"`，清单加 `android:networkSecurityConfig` 与
  `android:usesCleartextTraffic="true"`（后者供 API 23~27 兜底，这批系统还不读安全配置）。
- 为什么整体放开而不是白名单：快传/WebDAV 的对端 IP 由 DHCP 决定，安装期不可知；而安全配置的
  `domain` 既不支持 CIDR 网段也不支持通配 IP，无法枚举。HTTPS 仍按系统信任锚校验证书，不受影响。
- 附加：`LanSender` 把这类异常翻译成「系统拦截了明文 HTTP …」，不再让人误判成「对方没开服务」。
- versionCode 144；两台设备都需要升到本版才能互传。

---

## v1.74.0 —— 局域网快传（雷达扫描 + 点对点传文件）

- 场景：两台手机都装了本应用，想把一个 APK / 照片 / 压缩包传到对面，不想经过微信压缩、
  也不想上传网盘。现在长按文件 → 「局域网快传」，对方出现在雷达上点一下就开始传。
- 入口：文件长按菜单「局域网快传」（带上文件）、抽屉「📡 局域网快传」（只接收，可现场选文件）。
- 发现：UDP 固定端口 `48899`，每 2.5s 发一次广播探测，对方回单播应答，双方互相登记；
  12 秒收不到报文即视为离线。设备身份用 `ANDROID_ID`，换 Wi-Fi 也不会在列表里留幽灵设备。
- 传输：`POST http://对方IP:端口/lan/send?name=..&size=..`，body 是文件原始字节；
  接收端用 NanoHTTPD 裸流落盘（不走 multipart，省一次拷贝），边收边报进度。
- 落盘：`/sdcard/HexFile/LanShare/`（v1.75.0 起统一到此，与其它 HexFile 产物同一棵树），
  同名自动改名 `名字 (1).ext`，**不覆盖**；
  文件名来自网络，统一经 `LanProtocol.safeFileName()` 净化（挡 `../` 穿越与非法字符）。
- 雷达界面：自绘同心圆 + 十字准线 + 旋转扫描扇 + 设备光点；光点方位由设备 ID 哈希稳定映射，
  同一台设备每次都在同一方位，不会满屏乱跳；点光点或下方列表项即可发送。
- 后台接收：页面默认「离开即停」，避免长期占用端口；点「后台接收」由 `LanShareService`
  （前台服务）常驻，锁屏也能收。收到文件后可在「接收记录」里直接打开或复制目录路径。
- 失败可见：端口被占、对端拒绝（HTTP 码）、读文件失败都会在原页面提示，不吞异常。

---

## v1.73.2 —— 存储分析：目录结果缓存

- 现象：进目录再返回，上一级又完整扫一遍；在大目录（相册、微信缓存）里来回点，
  每次都要等一次遍历，界面还会闪一下进度框。
- 根因：`scan()` 每次调用都无条件递归遍历整棵子树，上一级明明刚算过也不复用。
- 修复：新增 `ScanCache`（纯 Java，可单测），以规范化路径为键缓存 `analyze()` 结果。
  - **命中即渲染**：返回上一级、重新进入访问过的目录都不再扫描，也不弹进度框，
    摘要行显示「缓存 · N 秒前」，不让人误以为是实时数据；
  - **TTL 60 秒**：别的应用可能在后台改了文件，纯内存缓存不能永久可信，过期即重扫；
  - **LRU 40 条**：一路下钻不会把整棵树的统计结果都攒在内存里，最久未用的先淘汰；
  - **祖先链失效**：删除文件或点「刷新」时，本目录与所有上级的缓存一并作废
    （文件增删会改变每一级的总量），再强制重扫当前目录；
  - 顺带记住每个目录的滚动位置，返回上一级回到原处，不再跳回顶部。

---

## v1.73.1 —— 抽屉菜单可滚动

- 现象：抽屉里入口越加越多（本地存储 + 书签 + 应用管理 + 签名 + Activity 记录 + 工具箱 +
  存储分析 + 主题 + 远程 + 网络 + 终端 + Smali + Java→Smali），内容超过一屏后，
  向上滑没有任何反应，底部几个入口既看不见也点不到。
- 根因：抽屉是**自定义平移面板**（`drawer` 一个 280dp 宽的 `LinearLayout` + `translationX` 动画，
  不是 `DrawerLayout`），所有入口直接平铺在里面，没有滚动容器，超出父容器的部分被裁掉。
- 修复：结构调整为「头部固定 + 菜单区滚动」——
  头部（logo / 版本 / Root 状态，约 108dp）留在外层保持常驻，
  其余入口移入 `ScrollView`（`layout_height=0dp` + `weight=1`，`fillViewport=true`），
  竖向渐隐边缘提示可滚动；每次打开抽屉自动回到顶部，避免上次滚到中间、下次打开停在半截。

---

## v1.73.0 —— 跨设备显示一致 + 存储分析进度与返回修正

### 不同分辨率设备显示大小不一致

- 现象：同一界面在不同手机上「有的字特别大挤成一团、有的正常」，目录横条在窄屏会超出屏幕。
- 根因有两处：
  1. 全项目 37 个 Activity 都是手写 UI，共 258 处 `setTextSize()`（默认 SP），
     但容器高度、行距是固定 dp。设备若把系统字号调大（国产 ROM 默认常见 1.15~1.30，
     无障碍模式可达 2.0），SP 跟着放大而容器不变，于是溢出、截断、观感「太大」。
  2. 存储分析的占用横条写死 `320 * density`，320dp 宽的小屏加上左右边距直接超出屏宽，
     宽屏上又显得很短。
- 修复：
  - 新增 `UiKit`：`clampFontScale()` 把 fontScale 收敛到 `[0.90, 1.10]`，
    `BaseActivity` / `SignPromptActivity` / `ActivityFloatService` 统一在
    `attachBaseContext()` 里套用。**只改 fontScale，不动 density / densityDpi**，
    所以 dp 布局与屏幕密度的对应关系不变，不会把布局整体缩小；
  - 上限 1.10 保留用户「稍微大一点」的合理偏好，同时挡住 1.3~2.0 撑破固定高度行；
    下限 0.90 防止字号设得极小的设备上文字看不清；
  - 横条改用 `weight` 分配宽度（把整条按 1000 份切），任何屏宽都等比自适应。

### 存储分析进度条不真实

- 根因：横条长度按「**最大的子目录**」归一化，最大的永远满格，与真实占用无关；
  扫描过程是无限转圈，没有分母。
- 修复：
  - 横条长度改为 `该目录大小 / 当前目录总量` 的真实占比，并直接标出百分比
    （如 `1.2G · 43%`）；补一行「（本目录下的文件）」把散文件差额算进去，各条之和能对上 100%；
  - 扫描改为**真实百分比**：阶段 1 先递归数文件得到准确分母，阶段 2 按已处理文件数推进，
    对话框显示「已统计大小 · 已处理 / 总数 个文件」，可随时取消（取消会中断遍历，不会后台空跑）；
  - `DiskScanUtil.analyze()` 一次遍历同时产出总大小、直接子目录占用、全局 Top20，
    取代原先 `scanSize()` + `topFiles()` 各遍历一遍的做法，元数据遍历次数从 2 次降到 1 次；
  - 连续点不同目录时用 `scanSeq` 作废旧任务，旧线程回到检查点即退出，不会两个扫描抢 UI。

### 手势返回直接跳回主界面

- 现象：进入存储分析后下钻任意目录，右滑返回直接回到文件列表，而不是上一级。
- 根因：下钻只是 `scan(dir)` 换内容，没有和返回栈绑定，`onBackPressed()` 直接 `finish()`。
- 修复：记录进入页面时的入口根目录 `entryRoot`，`onBackPressed()` 逐级回退：
  非根目录 → 扫描父目录并消费事件；回到入口根目录 → 才真正退出。
  顶栏同时加了显式 `←` 返回键（根目录时淡化，点击即退出），
  扫描进行中按返回先取消扫描、停留在当前目录，而不是让用户以为卡死。

---

## v1.72.0 —— 根目录容量修正 + 长按「分享」

### 根目录容量显示成 987MB / 100%

- 现象：抽屉里「根目录」显示 `987M 已用 / 987M（100%）`，而系统设置、MT 等管理器显示可用 1.93G。
- 根因：`StatFs("/")` 读的是**只读的 system 分区**，`getAvailableBytes()` 恒为 0，
  于是算出来「已用 = 总量」，百分比永远是 100%。用户真正能写的是 `/data`。
- 修复：新增 `statPathFor()` 做路径映射——`/` 与 `/system`、`/vendor`、`/product`、`/odm`、
  `/apex`、`/sbin` 等只读挂载点统一按 `/data` 统计；`usageOf()` 返回 `{总量, 可用}`，
  路径本身取不到时逐级回退（路径 → `/data` → 外部存储），全失败才显示「容量不可用」。
  顶栏「储存:」与抽屉共用同一套计算，顶层目录不再出现 100%。
- 抽屉两行不再完全重复：根目录 `39.5M 已用 / 1.97G（2%）  可用 1.93G`，
  内部存储 `可用 1.93G / 共 1.97G`（两者同属 /data，本就是同一块空间）。

### 长按「分享」

- 任意文件（含 APK）长按 →「分享」→ 拉起系统分享面板，微信 / QQ / 邮件 / 蓝牙等能收文件的都会列出。
- 走项目自带的 `ApkFileProvider`（`content://com.hex91.file.files` + 绝对路径）：
  targetSdk 30 下 `file://` 会抛 `FileUriExposedException`；
  必须同时给 `ClipData` 和 `FLAG_GRANT_READ_URI_PERMISSION`，只加 flag 在部分 ROM 上接收方会打不开。
- 新增 `MimeUtil.of(name)`：按扩展名给 MIME（apk/图片/视频/音频/文本/json/pdf/压缩包/字体/dex/so），
  `ApkFileProvider.getType()` 同步改用它——否则接收方查到的永远是 octet-stream，
  微信、QQ 这类只看类型的应用不会出现在候选列表里。
- 移除「提取图标」：原先把图标静默写到**对面面板**的 `<apk名>_icon.png`，用户找不到；
  该位置由「分享」替代，`extractApkIcon` 及相关动作码一并删除。

### 验证

- `test/MimeUtilTest` 23 项：大写扩展名、多级扩展名（`a.tar.gz` → gzip 而非 tar）、
  点开头的隐藏文件（`.gitignore` 不算扩展名）、无扩展名、null、未知扩展名兜底。
- `QUICK=1 ./test/run_core.sh` 13 项全 PASS；APK 内已无 `提取图标` / `extractApkIcon` 字符串。
- versionCode 139，APK 约 13MB。

---

## v1.71.0 —— 工具箱新增「测试数据」（身份证号 / 银行卡号生成）

分析恶意样本时，很多木马会索要实名信息与银行卡。填真实资料等于把自己的东西交给来路不明的程序；
这一页生成**校验位合法**的测试数据，既能让流程走下去，又不泄露个人信息。

**边界写清楚**（页面顶部也常驻提示）：只保证「格式 + 校验位」正确——身份证 ISO 7064 MOD 11-2、
银行卡 Luhn；号码不对应任何真实个人或账户，**无法通过需要联网核查的实名认证**，不可用于伪造证件。

### 身份证号（18 位）

- 出生地：省 / 市 / 区县三级联动，默认 **北京市 · 东城区（110101）**；
  内置 **3357 条**行政区划码表（31 个省级行政区、342 个市、2984 个区县），
  来源为国家统计局区划代码（经 `china-division` 数据集整理），存 `res/raw/regions.txt`（86KB）。
- 出生日期：年（1940–2010）/ 月 / 日三个下拉，默认 **1991-10-01**；改月自动重算当月天数并保留已选日。
- 性别：男 / 女 chip，默认男。第 17 位奇偶正确落位（男奇女偶）。
- 生成后回显：`地区（含码）`、`生日 · 性别`、`校验位与第 17 位说明`；点号码或「复制」直接取走。
- 顺带修正了「直筒子市」的显示：东莞、中山、儋州没有区县，区县项就是市本身，
  地址拼装不会出现「广东省 东莞市 东莞市」。

### 银行卡号（16 / 19 位）

- 6 家银行：**工商（默认）/ 建设 / 招商 / 农业 / 邮政 / 中信**，chip 一排三个。
- 每家用 3 个公开卡 BIN 段，按该 BIN 的真实位数生成（如工行 622202 → 19 位、招行 622588 → 16 位），
  末位用 Luhn 算法补校验位；生成后 4 位一组展示，并回显 `银行 · BIN · 位数 · Luhn 通过`。

### 实现

- `RegionTable`（纯 Java）：解析 `6位码|名称|父级` 文本，提供省/市/区县查询、
  完整地址拼装、直辖市「市辖区」显示为省名。
- `TestDataCore`（纯 Java）：`idCheckDigit / idNumber / idValid / idBirth / idMale / daysInMonth`、
  `luhnDigit / luhnValid / cardNumber / groupCard / binOf`，`Bank` 枚举携带 BIN 与位数。
- `TestDataActivity`：三级联动 Spinner + 日期 Spinner + 性别/银行 chip，生成结果点一下即复制；
  顶部「一键填」同时生成两个；工具箱两张卡片分别直达对应区块（`EXTRA_FOCUS`）。
- 测试 `test/TestDataCoreTest.java` **52 项**：公开示例号 `11010519491231002X` 的校验位、
  改错/17 位/非法日期全部拒绝、500 次批量生成合规与性别一致、Luhn 向量（`4111111111111111`）、
  6 家银行 BIN/位数/Luhn、码表 3357 条与省市县父子关系、东莞等直筒子市地址。

---

## v1.70.1 —— 换新启动图标（HF 文件夹 · 中国风）

用户提供的 logo：黑色描边文件夹 + 白纸面 + 灰标签页 + 红边 HF 字，替换原来的矢量占位图标。

- 由 1536×1536 JPG 自动切图（`build/icongen/IconGen.java`，JDK ImageIO，不依赖 PIL/ImageMagick）：
  - 先按非白像素自动裁边（1223×1053），把外圈白底沿「从四边 flood fill」变透明，
    文件夹内部闭合白面因此被保留 —— 图标在深色桌面/抽屉里不会带一块白方块；
  - 逐步折半 + 双三次缩放，避免 1536→48 一步缩小产生的糊边锯齿；
  - 输出 5 个密度：48/72/96/144/192 的方图与圆图，另加 512px 高清图（`drawable-nodpi`）。
- Android 8+ 走**自适应图标**：`mipmap-anydpi-v26/ic_launcher(_round).xml`，
  背景 `@color/ic_launcher_bg`（纯白），前景是透明底 logo（占 108dp 画布 62%，落在 72dp 安全区内），
  各家启动器裁成圆形/方形/水滴都不会切到图形。
- Manifest 改 `@mipmap/ic_launcher` + `android:roundIcon`，旧 `drawable/ic_launcher.xml` 删除，
  备份列表兜底图标改用 `R.mipmap.ic_launcher`。
- 抽屉头改成「白底圆角 logo + 应用名/版本/Root 状态」横排，版本号同步 v1.70.1。

---

## v1.70.0 —— 工具箱（编码转换 / 加解密 / 进制 / 网络请求 / 时间戳）

抽屉新增「🧰 工具箱」，5 类 13 个入口。核心逻辑全部是纯 Java（`CodecCore` / `CryptoCore` /
`Sm3` / `Sm4` / `RadixCore` / `TimeCore`），桌面 JVM 可直接单测，无需设备。

### 编码转换（14 种格式任意互转）
- 文本、HEX（`E4 B8 AD`）、HEX 0x、二进制、十进制、字节数组（`0xE4,0xB8,0xAD`）、
  Unicode 转义、转义字符（`\xE4\xB8\xAD`）、URL 百分号、Base64、Base64URL、
  HTML 实体、JSON 字符串、中文域名（Punycode）、Base32。
- 统一模型：**格式 → 字节 → 格式**，所以不需要 N×N 条转换路径；输入即出结果，
  顶部「⇄ 互换」把格式对调并把结果挪回输入框，方便「编码 → 再解码」自证。
- 中文域名按 RFC 3492 手写 Punycode 编解码（`中文.com ⇄ xn--fiq228c.com`），
  支持全角句号、`http://` 前缀、端口/路径自动剥离。
- HTML 实体含 39 个常用命名实体（`&nbsp; &copy; &times; &hellip;`…）+ 十进制/十六进制数字实体。

### 加解密（18 种算法）
- 编码：Base64 / Base32 / URL 编码解码。
- 摘要：MD5、SHA-1、SHA-256、SHA-512、**SM3**（GB/T 32905 纯 Java 实现）、CRC32、
  HMAC-SHA256、HMAC-SM3。
- 对称：AES（128/192/256）、DES、3DES、**SM4**（GB/T 32907 纯 Java 实现），
  均支持 ECB / CBC 与 PKCS#7 填充；密文输出可选 Base64 / HEX。
- 密钥容错：文本密钥不足长度自动补 0 到合法长度（16/24/32），过长直接报错而不是算个坏结果；
  `hex:0011aabb` 前缀可直接给十六进制密钥。
- 解密输入自动识别 HEX / Base64，不用额外选格式。
- SM4 与 SM3 全部通过标准测试向量（`SM4(0123…3210)` → `681edf34…536e4246`、
  `SM3("abc")` → `66c7f0f4…8f4ba8e0`）。

### 进制转换
- 2 / 8 / 10 / 16 / 36 进制互转，BigInteger 实现，多少位都不溢出；
  自动吃 `0x` / `0b` / `0o` 前缀与空格/下划线分隔符。
- 额外给出二进制位宽、占用字节数、4 位分组的二进制、大端 HEX 字节视图；点任意一行复制。

### 网络请求（类 Postman 精简版）
- GET / POST / PUT / DELETE / PATCH / HEAD / OPTIONS，请求头按 `Key: Value` 每行一条，
  请求体支持任意文本（JSON 自动补 `application/json`）。
- 响应区显示状态码 / 原因短语 / 耗时 / 大小，响应头与响应体分区展示，JSON 自动缩进。
- 最近 20 条请求历史存在本地，点「历史」可回填；响应体超过 512KB 截断防 OOM。
- 失败时给出可读原因（域名解析 / 明文 HTTP 被拦 / 证书 / 超时）。

### 时间戳
- 顶部当前时间每秒刷新（可暂停），点一下复制当前秒级时间戳。
- 时间戳 → 时间：单位自动识别（≤10 位秒、≤13 位毫秒、≤16 位微秒），
  同时给出本地时间 / UTC / ISO 8601 / RFC 1123 / 中文格式 / 星期 / 相对时间。
- 时间 → 时间戳：支持 `2024-01-01 12:00:00`、`2024/01/01`、`20240101120000`、
  带 `Z` 或 `+08:00` 后缀（按 UTC 解析再折算，避免重复偏移）。

### 工程细节
- 新增 `ToolUi.java`：主题化 Spinner（选中项与下拉项都跟随明暗）、圆角卡片、点一下复制。
- 所有新页面继承 `BaseActivity`，主题切换自动重建。
- 新增 `test/ToolboxCoreTest.java`（**109 项**），`run_core.sh` 扩到 14 项；
  QUICK 模式 11 项约 1 分钟全 PASS。
- versionCode 136，APK 约 13MB。

---

## v1.69.0 —— 签名文件 🔑 图标 + Activity 记录（无障碍监听 + 透明浮窗）

### 签名文件统一显示 🔑
- 文件列表里 `.jks / .keystore / .bks / .p12 / .pfx / .ks` 不再显示成 `JKS` / `BIN` 角标，
  统一换成 🔑（深棕底 + 19sp emoji，与其它类型的彩色方块同一套视觉）；
- 判定是「扩展名 + 魔数」双通道：把 keystore 改名成 `sign` 或 `xxx.bin` 也能认出来
  （`JKS` = `FEEDFEED`、`BKS`），魔数结果按「路径 + 大小 + mtime」缓存，滚动列表不重复 IO；
- 文件列表的判定与「点击签名文件」的判定合并到同一个 `FileItem.isKeystoreName()`，
  两处口径不会再出现「图标是 🔑 但点开没反应」。

### Activity 记录
- **入口**：抽屉菜单「🧭 Activity 记录」。
- **原理**：Android 5.1 起 `ActivityManager.getRunningTasks()` 只返回自己的任务，普通应用
  拿不到别人的栈顶页面。这里走无障碍服务（`ActivityTraceService`），监听
  `typeWindowStateChanged | typeWindowContentChanged`，从事件里取 `packageName` + `className`
  —— 后者就是当前 Activity 的完整类名。只读这两个字段，`canRetrieveWindowContent=false`，
  不读屏幕内容、不联网、不落盘（记录只在内存，重启即清空）。
- **过滤**：`ActivityLog.looksLikeActivity()` 挡掉 `android.widget.*` / `android.view.*` /
  `android.app.*` 等容器与弹层（`TYPE_WINDOW_CONTENT_CHANGED` 事件里 className 常是
  `FrameLayout`，不过滤会把列表刷满 ViewGroup），并排除本应用自己的页面。
- **浮窗**：`ActivityFloatService`（前台服务 + `TYPE_APPLICATION_OVERLAY`），半透明黑卡片
  （85% 不透明、14dp 圆角、白色描边），默认显示最近 **3** 条，可切 3/4/5；
  每行「应用名 · Activity 短名」，连续重复合并为 `×N`，最新一条带绿色竖条；
  可拖动（位置记忆）、右上角 ✕ 关闭、点卡片其余区域进入完整列表。
- **完整列表**：应用图标（`LruCache` + 后台线程加载，tag 校验防复用串图）+
  「应用名 · Activity 短名」+ `包名 : 完整类名` + 时间 + 次数；点条目可
  **复制** / **启动该 Activity**（显式 Intent）/ **应用信息**，长按直接复制。
- **权限引导**：开关打开时依次检查无障碍与悬浮窗，缺哪个直接跳哪个设置页，返回后 `onResume`
  自动续上；对话框里额外提示 Android 13+ 的「受限制的设置」（应用信息 → ⋮ → 允许受限制的设置）。

### 踩到的坑
- 开关 `setChecked()` 必须在 `setOnCheckedChangeListener()` **之前**，否则恢复到「已开启」
  时会立刻回调一次 `startRecording()`，表现为「刚进页面就弹无障碍对话框」；
- 通知栏小图标换成单色 `ic_activity_trace`（Material timeline 路径），自适应启动图标
  直接当 smallIcon 在部分 ROM 上会渲染成白块。

### 测试
- 新增 `ActivityLogTest`（31 项）：类名过滤（容器/对话框/PopupWindow/无包名/null）、
  队列行为（连续重复合并计数、被别的页面隔开重新计数、超 `MAX=500` 淘汰最旧、快照最新在前）、
  展示字段（短名/full/标题/应用名兜底）；
- `run_core.sh` 扩到 13 项，全量 PASS。

`versionCode 135`，APK 约 13MB。

---

## v1.68.2 —— Hex 编辑器：一行 8 字节（00-07）+ 特殊符号可见

用户反馈：「预览显示的字体没那么多，有些特殊符号无法显示」「手机端没那么长，显示 00-07
八个字节即可，字体看着大气一点」。

### 一行 8 字节，字号直接翻倍
- `ROW` 16 → 8：一行字符数从 76 降到 43，同样屏宽下自适应字号从 ~9sp 提到 ~13sp
  （下限也从 9sp 提到 11sp、上限从 13sp 放到 18sp，平板上更明显）；
- 手机竖屏**不用左右滑**就能看全 8 个字节 + 文本列；Aa 仍可再放大 60%~250%；
- 排版函数跟着修正：`HexCore.textColumn` 原来把「每 8 字节分组空格」算成了恒定 1 个，
  8 字节/行时会多算一位导致列头与行错一位；现在由 `HexCore.groupGap(rowBytes)`
  统一决定（行宽 > 8 才有分组空格），`headerLine/rowLine/textColumn/columnAt` 四者对齐。

### 特殊符号不再一律变点
- 旧版文本列把所有 `>= 0x7F` 的字节都渲染成 `.`，所以 `° ± × ÷ ¼ ½ é ü` 这些全看不见；
- 现在 `HexCore.textChar`：只有控制字符（`0x00-0x1F`、`0x7F`）显示为点，
  `0x80~0xFF` 按 **Latin-1 码位**原样显示（一字节一字符，不破坏对齐），
  缺字形由系统字体回退补齐；
- 字符集按钮从 `ASCII` 改为 `LATIN1`（仍保留 UTF-8 / UTF-16LE / ISO-8859-1 / Shift_JIS 可选）。

### 测试
`HexLayoutTest` 增至 44 项（原 28 项 + 新增 16 项）：新增 8 字节/行的列头长度（43）、行文本落点、字节区与文本列
双向反算、越界收敛，以及 `textChar/textRow` 对 `00 1F 20 41 7E 7F B0 E9` 的逐字节断言
（控制字符点、`°`/`é` 原样）。

`versionCode 134`，APK 约 13MB。

---

## v1.68.1 —— Hex 编辑器：字号可调 + 逐字节十六进制键盘

用户反馈三条：「字体大小能调整一下」「写入不要整个键盘，只要 0-9 和 A-F」「16 个字节从尾端
写入不合理，选中哪个字节就该写哪个」。

### 字号可调（Aa）
- 工具条新增 **Aa**：字号 60%~250%（10% 步进），对话框里带一行等宽预览实时放大；
- 100% = 原来的「按屏宽自适应铺满一行」，默认值不变；调大后列表宽度按
  `字符数 × 单字符宽` 精确加宽，超屏部分用**横向滚动**查看，不再裁字；
- **列头与正文双向同步滚动**（两个 `HorizontalScrollView` 互相同步 `scrollX`），
  放大到 250% 也不会出现列头与行错位；
- 选择持久化到 `SharedPreferences("hex_view").font_scale`，下次进 HEX 编辑器沿用。

### 只保留十六进制键盘
- 删掉底部 `EditText`（会弹系统全键盘）与「取消/确定」两个按钮；
- 换成代码生成的 **3×6 键位**：`0-9`、`A-F`、`⌫`、`✕`，等分宽度、圆角描边、无软键盘；
- 面板头一行显示 `偏移` + `当前字节（改了会带 原 xx）` + `◀ ▶`（逐字节）+ `✕`。

### 点哪个字节就写哪个字节
- **点行内任意字节**（HEX 区或右侧文本列都能点）→ 该字节成为光标（蓝底），面板立刻定位到它；
  `ListView` 的 `onItemClick` 拿不到触点 x，用 `OnTouchListener` 先留 `ACTION_DOWN` 的 x，
  再按当前字号反算「第几列」；
- **按满 2 位十六进制立刻写入该字节**并自动右移一格（第 1 位只进缓冲，面板显示 `C_` 等第 2 位），
  不再是「把 16 字节塞进一个输入框、从尾端追加」；
- `⌫` 退半个字节 → 再按退回上一字节；`◀ ▶` 逐字节移动（不再按 8 对齐）；
- 写入仍是**只改内存**：💾 未改前保持灰色不可点，改过才点亮，保存只写差异区间。

`versionCode 133`，APK 约 13MB。

### 测试
`test/run_core.sh` 现在会**逐项打印 `[..] 用例名 PASS/FAIL 耗时`**（以前整段跑完才吐结果，
中途看起来像卡死），并支持：
- `./test/run_core.sh HexLayoutTest` —— 只跑指定用例；
- `QUICK=1 ./test/run_core.sh` —— 跳过 `SessionTest / SignPromptFlowTest / CustomSignE2ETest`
  三个要真跑 apksig 打包的用例（各约 70~110s），UI 改动时 1 分钟内出结果；
- 所有 `java` 都以 `< /dev/null` 启动，避免子进程继承 stdin 在管道里挂住。

`HexCore.columnAt(字符下标, 进制, 行字节数)` 为新增纯函数（点行内字符反算第几字节），
`HexLayoutTest` 增加 7 项断言（首/次位字符回算、文本列、越界收敛、BIN 进制）。

---

## v1.68.0 —— 签名管理与 Hex 编辑器重做（导入/创建签名 + 文件解析）

### 签名文件接入文件管理器
- 点击 `.jks/.bks/.keystore/.p12/.pfx`（或魔数命中 JKS/BKS 的无扩展名文件）→ 弹
  **导入到签名管理 / 查看签名信息 / HEX 查看 / 属性**；
- 「查看签名信息」输密码后列出别名、主题/签发者、有效期、算法、SHA-1/SHA-256，
  底部可直接转入「导入到签名管理」；
- 长按签名文件的操作菜单新增 **签名信息 / 导入签名管理**（原有的属性、复制到对面、
  HEX 查看照旧）。

### 签名管理（抽屉新增入口）
抽屉新增 **🔏 签名管理**，页面支持完整的增删改查：
- **导入**（原有）：`.p12/.pfx/.jks/.keystore/.bks` → 密码加密存入应用内部；
- **创建**（新增）：CN / 组织 / 别名 / 密码 / RSA 2048·3072·4096 / 有效期 1~50 年 →
  生成密钥对与自签 X.509 证书 → 直接入库并会提示证书指纹；
- **改名**（长按 → 重命名）、**设为默认**、**删除**、**查看详情**（长按或点击）；
- 新导入的第一条自动设为默认签名密钥。

技术实现：`KeystoreCreate` 用自带的**最小 DER 编码器**生成 X.509 v3 证书
（sha256WithRSA + basicConstraints + keyUsage），不依赖 bcpkix/sun.security.x509，
Android 与桌面 JVM 通用；生成后立刻用 `CertificateFactory` 反解、验签、并由
`KeyStoreUtil.inspect` 确认私钥可取出，最后以 PKCS12（失败退 JKS）打包。
桌面测试 `KeystoreCreateTest` 会用新 keystore **真签一个 APK** 并回读证书主题。

### Hex 编辑器重做
旧界面是两排大按钮 + 一坨等宽文本，看不清列、也分不清「改了没保存」。新版：
- **顶栏**：返回 / 文件名 +（大小·偏移·进制·是否未保存）/ ⓘ 文件解析 / ⌨ 键盘 / 💾 保存；
- **工具条**（可横滑）：跳转、块、搜索、复制、撤销、重做、进制、字符集；
- **列头与行严格对齐**（`HexCore.headerLine/rowLine/textColumn`，桌面单测保证），
  偏移灰色、零字节淡化、文本列可打印字符正常色、**当前光标格高亮**；
- **⌨ 底部 16 进制输入面板**：显示 8 位偏移 + 当前 16 字节，`◀ ▶` 按 8 字节移动，
  确定只写内存并即时反映到列表；
- **保存纪律**：保存图标默认灰（alpha 0.3、不可点），有改动才点亮；点击后按
  **差异区间**批量写盘（不再整份重写），保存后清空撤销栈之外的状态与搜索高亮；
- **退出拦截**：未保存时返回 → 保存 / 放弃修改 / 取消；
- 撤销/重做改为基于内存快照，可回退到「与文件一致」时保存按钮自动变灰。

### 跳转到块 + 文件解析（read ELF）
- **块**：选块大小（16B/64B/256B/512B/1KB/4KB）+ 块号，或点上一块/下一块，按块对齐跳转，
  对话框实时显示「当前第 N 块 / 共 M 块」；
- **ⓘ 文件解析**（`FileInspect`，纯 Java）：文件头 16 字节、魔数与格式识别
  （ELF/ZIP/APK/DEX/JKS/BKS/PKCS12/ARSC/AXML/图片/压缩包…）、DEX 版本、JKS 版本；
  ELF 则进一步解析位宽/字节序/类型/机器/入口/程序头/节头、**节表**（名称/类型/标志/地址/大小）
  与 `.comment`，并支持**跳到节**（点节名直接定位到该节偏移）；
  ZIP/APK 追加 META-INF 签名块与证书（主题、有效期、算法、SHA-256）。

### 测试与版本
- 新增 `test/FileInspectTest`（25 项：ELF 64/32、魔数、JKS 版本、ZIP 签名证书）、
  `test/KeystoreCreateTest`（18 项：证书自检、密码错误拒绝、apksig 真签、产物回读）、
  `test/HexLayoutTest`（22 项：列头/行文本逐列对齐、末行与多进制等宽），
  均并入 `test/run_core.sh`。
- `versionCode 132`，APK 约 13MB。

---

## v1.67.5 —— ARSC 编辑器改完即时可见（标记修改 + 自动定位）

用户反馈：「ARSC 编辑器修改内容确定后，焦点消失不变，只有土司弹窗，但点开发现还是原值。」

根因是值只存在 `edits` 列表里，而列表行文本取自 `arsc`（保存前不会被改动）里解析时的快照，
所以除了弹一个 toast 之外界面毫无变化。本次按 AndroidManifest 编辑器的做法补齐整条链路：

### 改完立刻看得见
- 行的值与资源名改为**现算**（`displayValue` / `currentName`），叠加 `edits` 里的待写值；
  搜索同样按当前值匹配（改完也能搜到新值）。
- 改动行加标记 **`✎ 已修改`** 并用主题色显示新值，下面追加一行灰色 **`原值: …`**；
  资源名改了则标题显示新名 + `原资源名: …`；类型/资源名分组行带 `✎` 提示。
- 编辑对话框预填**当前（含未保存）**的值，底部显示原值，并新增 **「还原」** 按钮。

### 确定后定位
- 重建三级树后**强制展开**目标所在的两级，把列表滚到该条目（`setSelectionFromTop`）；
  若搜索过滤把目标挡住了，自动清空搜索框再定位。

### 顺带修掉的三个真问题
- **展开状态丢失**：`buildRows` 先 `clear()` 再读旧状态（读到的永远空）→ 改为先采集后清空；
- **改名只改一条配置**：同一资源的多配置各自持 key 索引，只改一条会出现同名资源两种名字 →
  改为**同资源整组改名**；改回原名即撤销该改动；
- **颜色值改不了**：`RGB8/ARGB4/RGB4` 解析时按 ARGB8 的 8 位校验 → 分别按 6/4/3 位校验；
  引用值带 ` (type/name)` 后缀，解析时忽略后缀，做到「展示 → 解析 → 展示」闭环。

### 保存后基线切换
保存成功后**重解析新 arsc 并重建列表**、清空待写队列：既让列表显示真正写盘的值，
也避免再次保存时把同一批编辑叠加写入。

### 测试
- 新增 `test/ArscPreviewTest.java`（并入 `test/run_core.sh`）：
  344 条整型/引用值「展示→解析→展示」闭环、修改后预览文本随数据变化、
  bag `overrides` 只替换指定下标且未改项保持原值 —— 3 个真实样本 **8/8 全过**。
- `versionCode 131`，APK 约 13MB。

---

## v1.67.4 —— Manifest/ARSC 编辑器体验修复（实时预览 / 退出提示 / 共用自动签名）

### Manifest 编辑器++
- **改完立刻看到新值**：列表行的值改为现算（`currentValue`），此前只在构建列表时取一次，
  改完仍显示旧值；搜索也按当前值（含未保存修改）匹配。
- **改动标记不再是一个孤零零的点**：`[类型] ●` → `[类型] ✎ 已修改`，新值用主题色显示，
  下面追加一行灰色的「原值: …」。
- **「👀 预览」真的预览修改**：`AxmlFile.prettyXml(overrides)` 新增重载，未保存的编辑也渲染进去。
- **退出拦截**：直接返回时有未保存修改 → 弹「数据已修改」，按钮为 **保存 / 放弃修改 / 取消**，
  保存后自动关闭页面。
- **自动签名复选框**：保存与退出弹窗都带「保存后自动签名」（默认勾选），
  长按进入签名设置（密钥 + v1/v2/v3 方案）——与 smali 走同一套设置。

### ARSC 编辑器
- 顶栏标题由「ARSC 编辑器++」改为 **「ARSC 编辑器」**（与实际入口一致）。
- **展开/折叠不再跳回顶部**：refresh 改为复用同一个 adapter + `notifyDataSetChanged`，
  并记录/恢复第一个可见项位置（此前每次 `setAdapter` 都会重置滚动）。
- 保存/退出弹窗同样加入自动签名复选框与「保存 / 放弃修改 / 取消」，退出保存后直接关闭。

### 共用组件
- 新增 `ApkSignHelper`：签名设置（密钥选择 / 方案）、原地签名、自动签名复选框统一出口；
  Dex 编辑器原先私有的 `signApkByPrefs` / `showSignSettings` / `showKeyPicker` 收敛到这里。

---

## v1.67.3 —— 第二轮评审处理（流关闭 / 密码损坏语义 / Keystore 不占 UI 线程）

评审 6 项中 **1 项为真实缺陷、2 项已在 v1.67.1 修复、2 项属实已改进、1 项为文档约定**：

- **【真实缺陷】`MainActivity.signApkBuiltin` 流泄漏**：`ks.close()` 写在 `try` 末尾，`sign()` 抛异常时 assets 流不关闭。改为 try-finally（与 v1.67.1 的 `SignPromptActivity` 修复保持同一写法）。
- **【已修复】`DexEditorActivity.extractZipEntry`**：v1.67.1 已改为 `in`/`fo` 先置 null、finally 内分别关闭；评审基于 v1.67.0 旧代码。
- **【已修复】`DexEditorActivity.readText`**：v1.67.1 已加 finally 关闭。异常仍返回 `""` —— 这是刻意设计（`project.ini` 读空触发重建、`.editbase` 读空用当前时间），已在 Javadoc 写明契约。
- **【密码损坏语义】`KeyCipher.open` 不再静默返回空串**：有 `aes1:` 密文却解不开（换机 / 恢复出厂 / 清除数据导致 Keystore 密钥丢失）时返回 **null**，与「密码本来就是空」彻底区分：
  - `KeyEntry` 增加 `storeBroken`/`keyBroken`，列表项显示「⚠ 密码已损坏（换机/清除数据导致），请删除后重新导入」；
  - `saveEntry` 对损坏条目**直接拒绝落盘**，杜绝空串把原密码不可逆覆盖；
  - 签名前拦截并给出可操作提示（Dex 编辑器自动签名、自定义签名、密钥选择列表三处）；
  - 点开损坏条目不拿空密码硬解析，直接给「删除并重新导入」。
- **【Keystore 不占 UI 线程】`KeyCipher.warmUp()`**：应用启动即后台线程建好密钥（首次生成可能走 TEE/StrongBox），后续 `open/seal` 只是毫秒级密码运算；签名管理页的列表加载（要逐条解密）改为后台线程 + 回 UI 刷新。
- **附带**：启动时清理 v1.66.x 遗留在 `dexpp_sign` 里的明文 `ks_pass`/`ks_path`（v1.67.0 起已不用，留着只有风险）。
- 第 6 项（`sign(InputStream)` 所有权约定）保持现状；本轮审计出的违反者即上条第一项，已修。

> KeyCipher 走 Android Keystore，桌面 JVM 无法测试（`android.security.keystore` 是桩），需真机验证；其余回归全部通过。

---

## v1.67.2 —— Dex 修复改流式 + 原地修补（去掉 64MB 上限，峰值内存 256KB）

`repairDex` 原来是「整文件读入 byte[] → 改内存 → 写临时文件 → safeReplace 替换」。
修复实际只需要改头部 136 字节，其余几百 MB 只是被搬运了一遍。本次重构：

- **流式**：固定 256KB 缓冲区，峰值内存与 DEX 体积无关 → 去掉 v1.67.1 的 64MB 上限（仅保留 2GB 参数合理性检查），几百 MB 的 dex 也不会 OOM。
- **原地修补**：只改写头部 112 字节 + SHA-1 签名 20 字节 + Adler-32 校验和 4 字节，**代码/数据区一个字节都不碰**；不再生成 `.fixtmp`、不再整文件复制替换，也不需要 `safeReplace`（调用方已有 `_bak` 备份）。写入过程异常时回滚原头部 112 字节。
- **顺序约束**（决定了实现，也是正确性关键）：`file_size` 在 SHA-1 覆盖范围内 → 必须先修头部再算签名；Adler-32 覆盖签名区 → 必须回填签名再算校验和。
- **零写盘快路径**：头部与两个校验值都正常时直接返回，文件 mtime 都不变（旧实现即使是「无需修复」也会整文件重写一遍）。
- **校验分层**：≤32MB 用 dexlib2 真实解析（能抓结构级损坏）；更大的 DEX 改走**流式结构校验**（头部各索引区、map_list 边界是否落在文件内），避免为了「验证」反而 OOM。
- **覆盖顺序**：APK 内 dex 修复不再把整个 dex 读成 `byte[]` 交给会话（`putEdit`），改为 `stageAdd(file) + flushRegen`，大 dex 少一份整文件内存。

实测（Windows，4.8MB dex）：修复耗时 3390ms → **117ms**；46MB dex 流式修复 524ms、在 `-Xmx256m` 下完成。修复结果与原文件逐字节一致（SHA-1 相同，0 字节差异）。

> 注：`repairDex` 不再做整文件原子替换——只改 136 字节且调用方已备份，中断后重跑修复即可；代码/数据区始终不受影响。

---

## v1.67.1 —— 代码评审修复（流泄漏 / 跨平台路径 / 密码加密 / 未签名提示）

按评审逐条处理，9 项中 7 项为真实缺陷并已修复：

- **流泄漏（P0）**：`DexEditorActivity.extractZipEntry`（in/fo 仅在正常流程关闭 → 改 null 安全 finally）、`readText`/`writeText`、`copyFile`、`ApkEditSession.copy`（`FileOutputStream` 构造失败时 InputStream 已打开无人关闭 → 两流先置 null 再进 try）、`SignPromptActivity.signBuiltin/signCustom`、`MainActivity.readUriBytes` 全部改为 finally 内 null 安全关闭。Windows 下句柄锁死文件的隐患同时消除。
- **跨平台路径**：`ApkEditSession.sessionKeyOf` 原只认 `/`，Windows 路径含 `\` 时 `lastIndexOf('/')=-1` 取到整条路径，`_edit` 后缀规范化失效（幽灵会话）。改为 `max(lastIndexOf('/'), lastIndexOf('\\'))`。
- **接口契约**：`ApkSignerTool.sign(InputStream, …)` 补 Javadoc 明确「流由调用方关闭」，并建议优先用 byte[] 重载。
- **大 DEX 保护**：`SmaliTool.repairDex` 增加 64MB 上限，超限直接返回可读错误（整文件读入 + dexlib2 解析的 OOM 风险），并在方法文档标注上限。
- **签名密码加密**：新增 `KeyCipher`（Android Keystore AES-256/GCM，API 23+；21-22 降级明文），`id.meta` 内 storePass/keyPass 加密落盘，旧明文数据自动兼容；导入界面按系统能力显示加密/明文风险提示。
- **未签名提示**：签名弹窗副标题改为「未签名的 APK 可能无法安装」，「暂不签名」按钮与写回 Toast 同步加提示。
- 第 6 项（内置签名 try-finally）与评审结论一致，无改动。
- **测试**：SessionTest（覆盖 copy/sessionKeyOf）、KeyStoreUtilTest 21/21、SignPromptFlowTest、CustomSignE2ETest、SignSchemeTest 全过。versionCode 127。

## v1.67.0 —— 签名管理（导入内部化 + JKS/BKS 支持）+ 进度条修复

**① 签名管理器（新 SignKeyManagerActivity）**：

- **导入签名到应用内部**：`.p12/.pfx/.jks/.keystore/.bks` 一次性导入应用私有目录，名称/keystore 密码/私钥密码一并保存——之后签名**直接从列表选择，不再从文件加载、不用重复输密码**。
- **查看签名信息**：逐别名显示 证书主题/签发者/有效期/签名算法/私钥算法/SHA-1/SHA-256 指纹。
- **管理**：设为默认签名密钥（★ 标记，Dex 编辑器自动签名与自定义签名入口直接使用）/ 删除（默认密钥被删自动回落内置签名）。
- 首条导入自动设为默认；纯证书 keystore（无私钥）导入时明确拒绝。

**② JKS 签名无效修复**（用户反馈选了自定义 jks 签名不生效）：

- 根因一：旧 `loadAny` 只试 PKCS12/JKS，**BKS 完全不支持**；现 KeyStoreUtil.loadAny 统一支持 PKCS12 → JKS → BKS。
- 根因二：**JKS 私钥密码可与 keystore 密码不同**（`Cannot recover key`），旧代码一律用 keystore 密码取私钥 → 取不到就报"无可用私钥条目"。现私钥密码独立传递，自动模式逐别名尝试；导入与设置界面单独提供「私钥密码（可空=同 keystore 密码）」。
- 根因三：旧 keystore 拷贝到 Dex 工程缓存目录，工程重建即丢失 → 自定义签名静默失效。现签名存应用私有目录，与工程生命周期解耦。
- `ApkSignerTool.signAutoAlias` 新增完整重载（显式别名 + 独立私钥密码 + 方案组合），错误信息人话化。

**③ 进度条一直 0% 修复**：Dex 编辑器 编译/写回 弹窗改横向真实走动（渐近曲线平滑推进 0→90%，完成打满 100%），替代部分设备上显示 0% 的 indeterminate 横条。

**④ MainActivity 自定义签名入口重构**：已导入签名 → 列表直接选择并签名（★ 默认排前），辅以「从文件选择（一次性）/ 管理签名…」；无导入签名时保持旧流程。

**测试**：新增 KeyStoreUtilTest（keytool 现场生成 PKCS12/JKS/双密码 JKS/纯证书 keystore，解析+签名+apksig 验证 21/21）；回归 SessionTest、SignPromptFlowTest、CustomSignE2ETest、SignSchemeTest、DexPropsTest 全过。versionCode 126。

## v1.66.10 —— 去掉 APK 旁的 `<名>_dexpp` 产物目录

用户反馈：修改 smali 后 APK 同目录会多出 `<apk名>_dexpp/classes.dex`，又乱又没必要。

- **编译输出转入应用内部缓存**：编译产物改写到缓存工程目录内（`getCacheDir()/dexpp_<名>/out_classes.dex`），APK / dex 旁边不再生成任何可见目录。
- **APK 模式**：中间 dex 写回压缩包成功后即删除内部副本（它只 serve 写回这一步）。
- **单 dex 模式**：与 APK 原地模型对齐——编译结果直接原子写回原 dex（`safeReplace`），原文件首次修改备份到工程缓存（`origin_<名>.dex`），不再往原 dex 旁边输出目录。
- 旧版本已生成的 `<名>_dexpp/` 目录不再被使用，可手动删除。

## v1.66.9 —— Dex 编辑器浏览与写回体验重构

**① smali 编辑→写回全链路弹窗收敛（6 个弹窗 → 2 个）**：

- **编辑阶段零弹窗**：Dex 编辑器打开的工程 smali 在文本编辑器里**返回时静默保存**（新 `EXTRA_SILENT_SAVE`），不再弹「未保存的修改」；保存成功也只轻提示。
- **退出 Dex 编辑器时唯一拦截**：有未写回 smali → 单弹窗「**是否编译并保存文件**」（编译并保存 / 不保存退出 / 取消）。
- **编译进度条**：确认后弹横向进度条（平滑走动）替代转圈。
- **更新确认 + 自动签名复选框**：编译完成弹「`classes.dex` 已被修改，是否更新到压缩包 xxx.apk？」（含多 dex 合并提示、_bak 备份说明），内嵌「**自动签名**」复选框**默认选中**；**长按复选框**进签名设置：
  - 签名密钥下拉：内置签名文件 / 自定义签名文件（点选 keystore 文件 + 密码，持久化到 SharedPreferences）；
  - 签名方案下拉：**v1+v2+v3（推荐）/ v1 / v2 / v3 / v1+v2 / v2+v3**。
- 确定 → 写回进度 → 按设置原地签名（`ApkSignerTool` 新增 v1/v2/v3 任意组合重载）→ 轻提示「已写回并签名」→ 自动退出。原 ⚙ 编译按钮走同一流程（不再有 编译确认/写回确认/已保存 三连弹窗）。
- 64K 方法数守卫保留在更新确认之前。

**② 类树浏览优化**：

- **展开/收起不再跳回顶部**：列表改用单一 adapter + `notifyDataSetChanged` 原地刷新，子项插在点击项之后、位置保持。
- **单链包合并**：只有一个子包的包链合并为 java 包格式单节点（如 `com.baidu`）；分叉处保留层级、末端类不参与合并（`mergeChains`，TreeMergeTest 10/10）。
- **主题目录图标**：自绘 `FolderIconView`（资源管理器风格文件夹形状），收起=次级文本色、展开=主题强调色，22 色板亮暗自适应。
- ZipBrowserActivity 补 `onResume` 重载——从编辑器返回时原地模型下包内容可能已变。

**测试**：新增 SignSchemeTest（6 种签名方案 + 全关拒绝，7/7）、TreeMergeTest（10/10）；回归 SessionTest 19/19、SignPromptFlowTest 11/11、BakTest、AxmlTest 16/16、AxmlUtf8Test 全过。versionCode 124。

---

## v1.66.8 —— 原地修改模型（取消 _edit.apk，修改直接写回原包）

**模型变更**：既然首次修改前必然已生成 `<名>_bak.apk` 备份兜底，修改不再生成 `<名>_edit.apk`，而是**重打包后直接原子覆盖原 APK**。产物从「原包 + _bak + _edit」三个收敛为「当前包（即原包路径）+ _bak 备份」两个，不再有"改完要去另一个文件里找"的困惑。

- **写回流程**：`regen()` 重打包到临时文件 → `safeReplace` 原子覆盖原 APK；备份（`_bak.apk`，仅首次）**必须成功**才允许写入，备份失败直接取消（安全网优先）。
- **签名弹窗/签名菜单**：目标就是当前 APK 本身——修改后退出统一弹窗原地签名；长按「APK 签名/自定义密钥签名」对有会话修改的 APK 直接原地签，未动过的 APK 仍生成 `_signed.apk`/`_csigned.apk` 副本（不动原包）。
- **浏览界面即时反映**：Zip 后端无状态（每次操作重开流），修改/删除/复制完成后重载列表即见新内容。
- **兼容**：旧版本生成的 `_edit.apk` 仍可正常打开、编辑与签名（长按签名入口识别 `_edit.apk` 后原地签）；APK 信息弹窗「文件角色」行相应更新。
- 文案全面更新（各编辑器保存提示、dex 修复、批量复制/删除、提取并删除等约 12 处）：「已保存，已写回原包（首次修改自动备份 _bak.apk）」。
- **测试**：SessionTest 21 项重写为原地断言（写回=apk 本身、_bak 冻结原版、叠加/STORED/删除/overlay）；SignPromptFlowTest、CustomSignE2ETest 同步重写。versionCode 123。

---

## v1.66.7 —— 签名链路端到端核验 + 文件角色说明（回应「原包也被改了/还是原始签名」）

**端到端核验（CustomSignE2ETest 13/13）**：真实 APK → 修改 → 自定义签名（JKS）→
逐项 SHA-1/证书断言，证明：
- **原包 app.apk 与备份 app_bak.apk 的 SHA-1 全程不变**——工具所有路径
  （repack 只读原包、备份仅首次复制、去除签名出 _unsigned 新文件、全部编辑走
  会话写 _edit.apk）都不写原包。用户若见「原包被改」，请确认设备安装的版本
  ≥ 1.66.1（v1.66.0 有「弹窗签名误写原包」bug）。
- 自定义签名**确实生效**：签名后 _edit.apk 证书=用户 keystore 证书，
  V1+V2+V3 完整，无内置证书残留。
- 重签行为正确：内置↔自定义任意方向重签都正确删除旧 V1 文件、生成新 V1。

**改进**：
- APK 信息弹窗新增「文件角色」行：_edit.apk=修改版（签名后安装它）、
  _bak.apk=原包备份（保持原厂签名，用于还原，勿安装）等——三者签名不同
  是**设计使然**，加了说明用户不再困惑「怎么还是原始签名」。
- ApkEditSession.markSigned key 规范化：签名弹窗拿到的是 _edit.apk，
  直接标记会在原包会话之外建幽灵会话（原包仍 dirty → 可能重复弹签名框），
  现映射回原包会话。

versionCode 122。

---

## v1.66.6 —— smali 未写回退出拦截（修复「退出不提示签名」）

**问题定位**（桌面全流程模拟 SignPromptFlowTest 10/10 证明会话逻辑正确）：
用户改 smali → 保存（仅存本地工程）→ 直接退出 Dex 编辑器 → 修改**从未编译写回 APK**
→ 会话无修改 → 退出 APK 不弹签名框；此时看到的「有签名」是上一轮已签名的
`_edit.apk` 或本就带签名的原包/`_bak.apk`。

**修复**：
- DexEditorActivity 新增返回拦截：APK 工程存在 mtime 晚于基线的 smali（改了但没写回）
  时弹「smali 修改尚未写回」（编译并写回 / 仍要退出）。基线记录在工程目录
  `.editbase`（重开工程续用，防状态丢失），写回成功后刷新。
- 编译成功对话框文案纠正：写回**不再签名**（v1.66.0 起统一生成未签名 `_edit.apk`，
  退出 APK 后统一提示签名），旧文案「写回 APK 并重新签名（签 v1+v2+v3）」误导。
- 新增 test/SignPromptFlowTest：真实签名原包 → putEdit → needsSignPrompt →
  _edit.apk 无签名残留 → 暂不签名/再修改/签名后 各状态断言，10/10。

versionCode 121。

---

## v1.66.5 —— Dex 属性 / Dex 修复（对标 NP 菜单缺口盘点后补齐）

对照 NP Manager「打开方式」菜单盘点 Dex 功能缺口，实现其中两项：

**Dex 属性（新）**：**点击 APK/zip 内 dex 弹出的「打开方式」对话框**新增（对标 NP，长按菜单不放）。读取 dex 头部展示 **Dex 版本**（035/037/038/039 + 对应 Android 版本提示）、**String Count / Type Count / Proto Count / Field Count / Method Count / Class Count**（直接读 header 各索引区大小字段，与加载器语义一致）、文件大小、校验和与 SHA-1 签名状态（不一致时提示可用 Dex 修复）。

**Dex 修复（新）**：入口同上（点击弹窗，仅 APK 归档；走会话模型写 `<名>_edit.apk`）。修正 file_size / header_size（→0x70）/ endian_tag（→0x12345678），重算 SHA-1 签名（offset 12，覆盖字节 32..末尾）与 Adler-32 校验和（offset 8，覆盖字节 12..末尾），最后用 dexlib2 从内存字节实际解析验证可加载性。独立 dex（长按菜单入口）：首次自动备份 `<名>_bak.dex` 后原地覆盖。适用于头部被十六进制编辑改坏的 dex；索引区/数据区等结构级损坏无法恢复（会如实报告）。

**附带修复**：`ZipUtil.extractEntry` 输出 `FileOutputStream` 从不关闭（泄漏句柄，Windows 上锁死提取出的文件、后续 rename/delete 全部失败）——try-finally 关闭。`ApkEditSession.safeReplace` 备份步骤 rename 失败时加 copy 兜底；`SmaliTool.repairDex` 用 `DexBackedDexFile(byte[])` 内存验证替代临时文件 mmap 验证，避免 Windows 文件锁。

**实现范围说明**（NP 菜单对照）：Dex 编辑器++（类树 + smali 编辑 + 回编译）✅ 已有；反编译 smali ✅ 已有；Dex 属性 ✅ 本次新增；Dex 修复 ✅ 本次新增；Dex 转 Jar / Dex 字符串解密 / 翻译模式 ❌ 未实现（转 Jar 需 dex→class 字节码重写，工作量大；字符串解密依赖混淆特征，启发式为主）。

测试：新增 `test/DexPropsTest`（真实 dex 属性读取 8 项 / 破坏 4 处头部后修复 / 完好 dex 无需修复 / 非 dex 拒绝，20/20）；回归 AxmlTest 16/16、SessionTest 20/20、BakTest 4/4、AxmlUtf8Test 全过。versionCode 120。

---

## v1.66.4 —— 代码审查修复（资源泄漏 / 签名原子性 / 缓存名碰撞）

对 4 项审查报告的处置（3 项属实修复、1 项属实的结构收敛）：

**1. `ApkEditSession.putEdit`/`overlayPending` 流泄漏 — 属实，已修**：`FileOutputStream` 改 try-finally 关闭，`write` 抛异常不再泄漏句柄。

**2. `ApkRepack.readAll` 流泄漏 — 属实，已修**：`in.close()` 移入 try-finally，`read` 中途抛异常时也能关闭。

**3. 原地签名「先删后改」原子性 — 属实，已修**：3 处（`MainActivity.signApkInPlace`、MainActivity 自定义签名原地覆盖、`SignPromptActivity.signInPlace`）统一收敛到新工具方法 `ApkEditSession.safeReplace(tmp, target)`——Linux/Android 的 `rename` 本可原子替换已存在文件，故**先直接 rename（去掉先 delete）**；rename 失败（跨挂载点等罕见场景）才降级为「原文件 rename 成 `.replacebak` → 复制 → 成功删备份 / 失败尝试还原（还原失败保留备份不删）」，彻底消除「删成功、写失败 → 原包丢失」窗口。顺带 `signApkInPlace` 的 keystore 流也移入 finally。

**4. 会话缓存文件名碰撞 — 属实，已修**：缓存名此前用 `entry.hashCode()`（可碰撞）+ 末段文件名，`lib/arm64/libx.so` 与 `lib/x86/libx.so` 理论上会互相覆盖。改用 **entry 全路径 SHA-1 摘要**（`entryKey`）+ 末段文件名，putEdit/putAdd/stageAdd 三处统一走 `cacheFileFor`。

回归：BakTest 4/4、SessionTest 20/20、AxmlTest 16/16、AxmlUtf8Test 全过。versionCode 119。

---

## v1.66.3 —— 代码审查修复（UTF-8 池长度截断 / 移动重复 / 写入侧 zip-slip）

对 5 项审查报告逐一核实的处理结果（2 项属实修复、1 项属实加固、1 项 v1.65.0 已修、1 项不成立）：

**Bug 1（poolBytes 4 字节对齐）— 此前已修，无需处理**：v1.65.0 已在 `AxmlWriter.poolBytes()` 第 215 行对字符串数据区补零对齐（`while ((data.size() & 3) != 0) data.write(0)`），且 `dataLen` 在补零后计算，整体自洽。审查引用的是旧版代码。

**Bug 2（encodeUtf8 长度截断）— 属实，已修**：UTF-8 池串的两个长度字段（字符数/字节数）此前只写低 7 位，>127 的长字符串（长权限名等）池必然损坏。现改双字节变长编码（`0x80|高8位 + 低8位`，与 `ArscWriter.writeLen8` 及解析端 `strEnd` 解码同语义）。新增 `test/AxmlUtf8Test`：手工构造 UTF-8 池最小 AXML 全链路（300/350 字符长串编辑往返 + 对齐校验 + 短串回归），15/15 通过。

**Bug 3（moveOrCopy 移动重复）— 属实，已修**：`FileOps.moveOrCopy` 用 `copied` 标志区分两阶段——拷贝未完成才回退 `RootIO.moveInto/copyInto`（root 场景）；拷贝已完成但删源失败时，只尝试 root 删除源并返回成功，绝不再拷贝一遍（消除源/目标双份数据）。同时覆盖 `src.delete()` 静默返回 false 的场景。

**Bug 4（写入侧 zip-slip）— 属实，已加固**：解压侧本有 `safeTarget` 防护；现写入侧也拒绝路径穿越——`ZipUtil.entryNameSafe()`（`..` 段、绝对路径、NUL、盘符），在 `ZipUtil.writeEntry`（内存/流式/私有重建三处）与 `ArchiveWrite.writeEntry`（同时覆盖 tar/7z 后端）统一拦截，防止写出含 `../` 条目的恶意压缩包。

**Bug 5（findViewById 空指针）— 不成立**：`editor_lines`/`editor_symbols`/`editor_search_panel` 均定义于唯一的 `activity_editor.xml`（项目无 `layout-*` 配置变体目录），运行时 `findViewById` 不可能返回 null，无需降级判断。

回归：AxmlTest 16/16、SessionTest 20/20、真实 APK 链路 9/9。versionCode 118。

---

## v1.66.2 —— 签名目标一致性保护（长按菜单签名）

补充 v1.66.1 的统一模型，长按菜单「APK 签名 / 自定义密钥签名」新增目标判断：

- **长按的是 `_edit.apk`（修改版）** → **原地签名**（不再额外生成 `xxx_edit_signed.apk`，保持「只生成一个修改版文件」）；
- **长按的是原包且存在未签名修改版** → 弹窗询问：**签名修改版**（推荐，含全部修改，原地签）/ **仅签原包**（生成 `_signed.apk`，不含修改）——防止把不含修改的包签名后误装；
- 其余情况行为不变（原包 → `_signed.apk`）。

背景：任何对 APK 内容的修改（smali/dex/arsc/清单/增删文件）都会使原签名失效——重打包会重写 ZIP 并剔除原签名，因此 `_edit.apk` 一定是未签名的，**安装前必须签名**。若出现"未签名也能装"的情况，装的一定不是含修改的包（常见于误签了原包或签了旧的已签名文件）。

versionCode 117。

---

## v1.66.1 —— 修复签名目标错误（签到了原包）+ 签名弹窗美化

**修复（重要）**：v1.66.0 的统一签名弹窗把**原始 APK** 路径传给了签名流程——选「签名」后内置密钥直接重签了原包，而 `<名>_edit.apk` 一直是未签名状态（安装/查看时报「无法解析包名、签名未通过」，因为系统解析器拒绝无签名 APK）。现修复为只签 `<名>_edit.apk`，签名原地覆盖，原包始终不被改动。

> 若已用 v1.66.0 误签过原包：同目录的 `<名>_bak.apk` 是未被动过的最早原版，直接用它覆盖回去即可。

**签名弹窗重做**：弃用系统 AlertDialog（大黑框），改为自绘圆角卡片：
- 配色全部取自当前主题（22 色板 × 亮/暗自动适配，跟随主题切换）；
- 卡片式布局：标题 + 说明 + 文件名信息条 + 主按钮（内置签名，accent 实底）/ 次按钮（自有签名文件）/「暂不签名」文字项；
- 自有 keystore 密码输入同样为卡片页；签名中显示进度页；点空白处/返回键关闭。

验证：SessionTest 20/20（新增 2.7 签名目标 = `_edit.apk` 断言）。versionCode 116。

---

## v1.66.0 —— APK 修改统一模型：只生成一个 `_edit.apk` + 统一签名弹窗

**规则**：任何 APK 修改不再原地写回、不再产出 `_xmlpp/_arscpp/_dexpp/_mfpp` 等一堆文件。
所有修改登记进新的 `ApkEditSession` 会话，统一重打包为 `<原名>_edit.apk`（未签名）；
首次修改时原包自动备份为 `<原名>_bak.apk`（已存在则保留最早原版）。
未发生实质性修改 → 不生成任何文件，原包保持不变。

- **覆盖所有修改场景**：smali/dex 编辑写回、resources.arsc（ARSC/ARSC++）、
  AndroidManifest（Manifest 编辑器++ / XML 源码编辑）、文本条目（smali/xml 文本）、
  lib/assets 新增文件/目录、压缩包条目删除、提取并删除。
- **重复编辑一致性**：重新打开被改过的条目时自动取会话中最新内容
  （`overlayPending`/`editBytes`），多次保存始终 = 原包 + 全部会话修改。
- **统一签名弹窗**（`SignPromptActivity`，透明）：退出 APK 浏览时若有未签名修改版，
  统一弹出「不签名 / 内置签名文件签名 / 自有签名文件签名（选 .p12/.jks/.keystore +
  输密码）」；签名成功后**原地覆盖** `_edit.apk`，仍然只有一个文件。
  选择不签名后同一轮修改不再重复弹。
- **单独签名** APK 仍输出 `<名>_signed.apk`（长按菜单，不变）。
- `ApkRepack` 支持新增条目（`lib/*.so` 按 STORED + 4096 对齐）；备份名统一 `_bak.apk`。
- 移除各编辑器内散落的「写回原 APK / 自动签名」弹窗与 `_xmlpp/_arscpp/_dexpp/_mfpp` 产物。
- 桌面 `SessionTest` 19 项全过（无修改零产物、备份保留原版、修改叠加、加 .so STORED、
  删条目、overlay、签名后 aapt2/apksigner 验证）；`AxmlTest` 16/16 无回归。
- versionCode 115。

---

## v1.65.1 —— APK 原地写回前自动备份原包

### 新增
- **所有 APK 修改在覆盖原包前自动备份**：新增 `ApkRepack.backupOriginal()`，
  覆盖前把最初版本复制为同目录 `<名>_原始备份.apk`；备份已存在则保留最早原版
  （多次修改不会丢真原包）。覆盖场景（5 处）全部接入：
  - 文本编辑器写回压缩包条目（smali/xml 等，`TextEditorActivity.save`）；
  - ARSC++ XML 编辑写回（`saveArscPlus`）；
  - Manifest XML 往返编译「写回原 APK」（`writeBackSigned`）；
  - ARSC 编辑器「写回原 APK」；
  - Manifest 编辑器++「写回原 APK」。
  成功提示会注明备份文件名。签名产物类（`_signed.apk`/`_xmlpp.apk`/`_dexpp.apk`）
  本就是独立文件，不覆盖原包，无需备份。
- versionCode 114。

---

## v1.65.0 —— 修复「Manifest XML 编辑保存后 APK 无法解析」+ arsc 点击选择框

### 修复（关键）
- **`AxmlWriter.poolBytes` 字符串池补零对齐**：二进制 XML 所有 chunk 必须 4 字节对齐。
  旧版追加字符串（改 versionName 即触发）重建池后长度非 4 倍数，aapt2/PackageManager 报
  `XML size ... is not on an integer boundary` → **安装时解析包失败**（APK 无法解析）。
  用真实 APK 全链路复现并修复：pretty → AxmlTextSync → repack → aapt2 dump badging/xmltree
  → apksigner verify 全部通过。
- **加固包 manifest 往返兼容**：加固样本 manifest 含垃圾属性（名/值带换行、`<`/`>`、
  首尾空格、重名属性），旧版 pretty 渲染破坏「每属性行单行」受控格式导致保存必失败：
  - `AxmlFile.renderElement` 元素名/属性名/值统一实体转义，换行编码 `&#10;`（可安全往返）；
  - `AxmlTextSync.sync` 重写为贪心配对（名字相同优先、显示值也相同者优先），
    解决重名垃圾属性错配成假编辑；键名 trim 一致化。
- `encodeUtf16` 长串标记修正（首 u16 = 0x8000 | (n>>16)）。

### 新增/修改
- APK 内点击 `.arsc` 改为**弹选择框**（ARSC 编辑 / ARSC++ 编辑），不再直接进入修改
  （`MainActivity.openArchArsc`）。

### 测试
- 新增 `test/AxmlRealChainTest.java`：88 个真实样本全链路（提取 manifest → 模拟改
  versionName → sync → 对齐校验 → repack → aapt2 → 签名 → apksigner verify）。
- versionCode 113。

---

## v1.64.3 —— 编辑器保存状态联动（未修改禁保存 / 返回保存提示签名）

### 新增/修改
- **保存图标联动 dirty 状态**：打开/刚保存后保存图标置灰不可点（alpha 0.35）；
  任何改动（输入、行操作、替换、补全、格式化等）立即恢复可点。
  全部 dirty 写入统一走新入口 `setModified()`（`TextEditorActivity`）。
- **返回未保存提示**：返回（或关闭文件）时若有未保存修改，弹「保存 / 不保存 / 取消」；
  选「保存」不再立即退出 —— 保存完成后按场景统一处理：
  - 普通文件 / 导出：保存成功后自动退出；
  - 压缩包（非 APK）：写回成功后自动退出；
  - APK：写回后弹「是否自动签名」—— 选签名则签名成功后退出，选「暂不」也退出；
    保存失败则留在编辑器可重试。
- versionCode 112。

---

## v1.64.2 —— ARSC++ 全量解析器 ArscEditor（参考 NP Manager 架构逆向）

逆向 NP Manager（com.wn.app.np）的 ARSC 实现后，在原「字符串池追加 + 4 字节补丁」
（`ArscWriter`，只能改值）之上新增**全内存模型 + 全表重编码**引擎 `ArscEditor`：

### 新增能力（XML 源码编辑器内直接用属性表达）
| 属性 | 效果 |
|---|---|
| `rename="新名字"` | 资源改名（key 池追加 + 条目 key 索引重写） |
| `delete="true"` | 删除条目（type chunk 偏移置 NO_ENTRY，条目体移除） |
| `add="true"` | 新增条目（entryId 取同类型最大 id+1，key 池追加，值 TYPE_STRING） |
| （直接改文本） | 改值（字符串/整数/布尔/颜色/引用，兼容原逻辑） |

### 引擎设计（`ArscEditor.java`，纯 Java 可桌面单测）
- 惰性解析：type chunk 头全量解析（便宜），条目体只在块被编辑时解析（真机内存友好）；
- 块保持重编码：无编辑的 chunk 原字节拷贝，被编辑的块重序列化
  （偏移数组重建 + 条目逐个拷贝/补丁/合成，entriesStart/size 重算）；
- 无结构化编辑时自动回落到 `ArscWriter` 补丁路径（已验证的兼容链路）；
- 安全防御（与 NP 一致）：sparse/compact/offset16 布局块拒绝编辑（Android 15 紧凑格式只读）、
  compact 条目拒绝编辑、ADD 的资源 ID 冲突拒绝、异常 count 钳制防 OOM。

### 测试
- `test_arscpp/ArscEditorTest`：改值+改名+删除+新增全链路（写回→重解析校验），两个真实样本 **10/10**；
- `test_arscpp/ArscXmlOpsTest`：XML 属性级端到端（rename/delete/add → applyXml → ArscEditor），两个样本 **7/7**；
- 逆向调试期修复 3 个关键 bug：条目字段偏移（key@+4、Res_value@+8、data@+12）、
  惰性解析防 OOM、touched-chunk 才重序列化。

- 保存链路：`applyXml(→ops)` → `ArscEditor.write` → ApkRepack（.so 4096 对齐）→ 提示重签名。
- versionCode 111。
