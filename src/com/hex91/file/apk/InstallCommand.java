package com.hex91.file.apk;

/**
 * v2.121.0：特权安装的命令构造与结果分类（纯 JDK，桌面可测）。
 *
 * <p><b>为什么单独拆出这个类</b>：安装这件事里唯一能在桌面验证的部分就是「拼命令」和
 * 「读 pm 的输出判成败」。这两块一旦出错，在真机上表现为「明明装上了却报失败」或
 * 「文件名带空格就装不了」，排查成本远高于写代码的成本。所以把它们放进纯 JDK 类，
 * 由 {@code InstallCommandTest} 覆盖；真正依赖 Android 的部分留在
 * {@link AppInstaller} 里，只做「执行命令 → 把结果交给这里分类 → 回调」。</p>
 *
 * <p><b>层次约束</b>：本类不产用户文案。所有返回值都是错误码或系统原文
 * （{@code detail}），中文/英文由 ui 层的 {@code UtilText} 映射。这样加一种语言
 * 不需要碰这里，也符合「逻辑层不产用户文案」的既定规矩。</p>
 *
 * <h3>为什么 APK 要先落到 /data/local/tmp</h3>
 * Android 11 起 {@code system_server} 通过 FUSE 看到的 {@code /sdcard} 与 shell
 * 看到的不一致，直接把 {@code content://} 或 {@code /sdcard/Download/x.apk} 交给
 * {@code pm install} 会报 {@code INVALID_URI}。先由特权 shell 把文件拷到
 * {@code /data/local/tmp}（shell 可写、system_server 可读），再从那里安装，
 * 是 Root / Shizuku 两条路径都成立的唯一稳妥做法。
 */
public final class InstallCommand {

    /** 成功 */
    public static final int OK = 0;
    /** 用户主动取消（解包/拷贝检查点） */
    public static final int ERR_CANCELLED = 1;
    /** 命令超时；注意超时不杀进程，安装可能在后台继续完成 */
    public static final int ERR_TIMEOUT = 2;
    /** 签名不同 / 版本降级 / 同名包已存在且签名不匹配 */
    public static final int ERR_CONFLICT = 3;
    /** 包损坏 / 分包缺失 / 解析失败 */
    public static final int ERR_INVALID = 4;
    /** ABI、minSdk、targetSdk 与设备不兼容 */
    public static final int ERR_INCOMPATIBLE = 5;
    /** 存储空间不足 */
    public static final int ERR_STORAGE = 6;
    /** 系统策略拦截（未知来源、设备管理器、ROM 纯净模式） */
    public static final int ERR_BLOCKED = 7;
    /** 安装被系统或用户中止 */
    public static final int ERR_ABORTED = 8;
    /** 本地 I/O 失败（拷贝、权限、目录创建） */
    public static final int ERR_IO = 9;
    /** 认不出的失败：保留 pm 原文交给上层展示 */
    public static final int ERR_UNKNOWN = 10;
    /** 另一个安装正在进行（同一进程只允许一个会话，避免进度与结果互相覆盖） */
    public static final int ERR_BUSY = 11;

    private InstallCommand() {}

    /**
     * POSIX shell 单引号转义。任何出现在参数里的 {@code '} 都写成 {@code '\''}，
     * 这样文件名里带空格、引号、美元符、反引号都不会被 shell 解释。
     *
     * <p>不能简单用双引号：{@code $} 和 backtick 在双引号里仍然展开，而 APK 文件名
     * 来自外部存储，属于不可信输入。</p>
     */
    public static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 把源文件拷到特权 shell 能稳定读到的临时目录 */
    public static String copyCommand(String src, String dst) {
        return "cp " + shellQuote(src) + " " + shellQuote(dst) + " 2>&1";
    }

    /** 给临时文件补上 system_server 可读的权限 */
    public static String chmodCommand(String path) {
        return "chmod 644 " + shellQuote(path) + " 2>/dev/null";
    }

    /** 安装结束后清理临时副本（成功失败都要清） */
    public static String removeCommand(String path) {
        return "rm -f " + shellQuote(path) + " 2>/dev/null";
    }

    /**
     * 构造 pm install 命令。使用 {@code -r} 保留数据（覆盖安装不丢配置），
     * {@code -i} 写明安装来源，便于在应用信息里看到是 HexFile 装的。
     */
    public static String installCommand(String tmpPath, String installerPkg) {
        String pkg = (installerPkg == null || installerPkg.length() == 0)
                ? "com.hex91.file" : installerPkg;
        return "pm install -r -i " + shellQuote(pkg) + " " + shellQuote(tmpPath) + " 2>&1";
    }

    /**
     * 生成不与其他安装冲突的临时文件名。只保留安全字符，避免 APK 原文件名里的
     * 引号/换行进入 shell 命令（虽然 {@link #shellQuote} 已经挡住了，但双保险）。
     */
    public static String tempPath(String apkName, String token) {
        String leaf = (apkName == null || apkName.length() == 0) ? "app.apk" : apkName;
        int slash = Math.max(leaf.lastIndexOf('/'), leaf.lastIndexOf('\\'));
        if (slash >= 0) leaf = leaf.substring(slash + 1);
        leaf = leaf.replaceAll("[^A-Za-z0-9._-]", "_");
        if (leaf.length() == 0) leaf = "app.apk";
        if (!leaf.endsWith(".apk")) leaf = leaf + ".apk";
        String safeToken = (token == null || token.length() == 0)
                ? String.valueOf(System.currentTimeMillis()) : token.replaceAll("[^A-Za-z0-9_-]", "");
        return "/data/local/tmp/hf-install-" + safeToken + "-" + leaf;
    }

    /**
     * 把 pm 的退出码 + 合并输出翻译成错误码。
     *
     * <p>pm 的失败原因全在文本里，退出码几乎恒为非零，所以关键字匹配是必需而非偷懒。
     * 顺序有讲究：签名冲突优先于通用 parse 失败，否则 {@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}
     * 会被 {@code install_failed} 吞掉，用户就看不到「需要先卸载旧版」这个真正有用的提示。</p>
     */
    public static int classify(int exit, String out) {
        String s = (out == null) ? "" : out.toLowerCase();
        if (s.contains("update_incompatible") || s.contains("signatures do not match")
                || s.contains("signature mismatch") || s.contains("already exists")) {
            return ERR_CONFLICT;
        }
        if (s.contains("missing_split") || s.contains("split") || s.contains("parse")
                || s.contains("invalid_uri") || s.contains("bad ")) {
            return ERR_INVALID;
        }
        if (s.contains("insufficient_storage") || s.contains("storage")) {
            return ERR_STORAGE;
        }
        if (s.contains("incompatible") || s.contains("cpu_abi") || s.contains("sdk")
                || s.contains("requires newer")) {
            return ERR_INCOMPATIBLE;
        }
        if (s.contains("aborted")) return ERR_ABORTED;
        if (s.contains("blocked") || s.contains("not allowed") || s.contains("unknown sources")) {
            return ERR_BLOCKED;
        }
        if (exit == 0) return OK;
        if (s.contains("install_failed")) return ERR_UNKNOWN;
        return ERR_UNKNOWN;
    }

    /** 错误码是否代表成功（避免调用方到处写 {@code == OK}） */
    public static boolean ok(int code) {
        return code == OK;
    }
}
