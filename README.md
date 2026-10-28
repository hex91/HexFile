# HexFile —— 安卓文件管理器（Android APK）

一款**纯本地**的安卓文件管理与逆向辅助工具集：**无网络、无账号/VIP、无需联网权限**，所有功能在设备本地完成。


## 快速开始

```bash
bash build.sh                       # 产出 out/HexFile-<version>.apk（已签名，可直接安装）
adb install -r out/HexFile-*.apk
```

构建工具链（本机已配）：`aapt2 + javac 17 + d8 + zipalign + apksigner`（Android SDK build-tools 37.0.0 / platforms android-35 / NDK 27）。
首次构建若本地无 `hexfile.keystore`，会自动生成调试密钥（storepass/keypass 均为 `android`）。

> **CI / Release**：推送到 `main` 会自动编译并把 APK 发布到 Release 的 `continuous`（预发布）标签；打 `v*` 标签则发布正式 Release。
> 签名密钥通过仓库 **Secrets**（`HEXFILE_KEYSTORE_BASE64` / `HEXFILE_KEYSTORE_PASS`）注入，**不入库**。

## 工程结构

```
/
├── AndroidManifest.xml          # minSdk 21 / targetSdk 30 / 存储权限 / 4 Activity
├── build.sh                     # 一键构建（6 步：aapt2→link→javac→d8→zipalign→sign）
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
    └── libs/                    # baksmali 2.5.2 + dexlib2 + guava-27.1-android + jsr305 + failureaccess
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
| baksmali 引擎内嵌（org.smali 2.5.2：dexlib2+guava-27.1-android，纯 Java 零 native）；multidex 就绪的构建链（d8 自动拆分 classesN.dex）；manifest 开启 largeHeap | 完成 |
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
  - 提取图标：当前分辨率位图存为 `<apk名>_icon.png` 到对面面板
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
- **⚙ 编译**：整个工程（含多 dex 合并）smali → 单个 `classes.dex`，输出到 APK 同目录 `<apk名>_dexpp/`；失败弹窗提示检查最近改过的类
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
