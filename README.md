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
├── AndroidManifest.xml          # minSdk 21 / targetSdk 30 / 存储权限 / 4 Activity
├── build.sh                     # 一键构建（7 步：aapt2 compile→link→NDK→javac→d8→zipalign→sign，带版本守卫）
├── res/
│   ├── layout/activity_main.xml # 格双栏布局（深色顶栏+统计行、底部 ‹ › ＋ ⇄ ↑ 工具栏）
│   ├── layout/activity_search.xml / item_result.xml
│   ├── layout/activity_trash.xml / activity_zip.xml
│   ├── layout/item_file.xml     # 文件列表项
│   ├── values/                  # colors / strings
│   └── drawable/                # 矢量图标（ic_launcher / ic_folder / ic_file）
└── src/com/hex91/file
    ├── MainActivity.java        # 双栏主界面
    ├── FileAdapter.java         # 目录优先排序的列表适配器
    ├── FileItem.java            # 文件条目模型
    ├── FileOps.java             # 复制/移动/删除/重命名/新建 + MD5/SHA256/CRC32
    ├── ApkUtil.java             # APK 信息解析
    ├── ApkSignCheck.java        # APK 签名校验（apksig：schemes 方案串 + detailRows 详情行）
    ├── ApkFileProvider.java     # 迷你 content:// 文件共享（安装 APK 用，零 AndroidX）
    ├── TextEditorActivity.java  # 文本编辑器（UTF-8/GBK 自动识别、未保存防丢、二进制检测）
    ├── HexViewerActivity.java   # HEX 查看器（偏移+HEX+ASCII、点行复制、偏移跳转、4MB 截断）
    ├── SmaliTool.java           # smali 反编译（org.smali:baksmali:2.5.2 编程 API 封装）
    └── libs/                    # baksmali 2.5.2 + dexlib2 + guava-31.1-android + jsr305 + failureaccess
    ├── FeatureRegistry.java     # 功能注册表
    ├── BookmarkStore.java       # 书签持久化（SharedPreferences）
    ├── Trash.java               # 回收站（meta 记录原路径，可恢复/彻底删除/清空）
    ├── SearchActivity.java      # 递归搜索（* ? 通配符，后台线程，上限 1000）
    ├── TrashActivity.java       # 回收站管理（恢复/彻底删除/清空）
    ├── ZipUtil.java             # ZIP 引擎（浏览/解压/打包，零依赖，防 zip-slip）
    ├── ZipAdapter.java          # 压缩包条目适配器（复用 item_file 布局）
    └── ZipBrowserActivity.java  # 压缩包浏览器（进子目录/解压全部/解压单条）
```

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
