package com.hex91.file.fs;

import java.io.File;

/**
 * v2.4.0（O-19）：文件类型与文件名的纯判断。
 *
 * <p><b>为什么从 {@code MainActivity} 搬出来</b>：这些方法原本是宿主类上的
 * {@code public static}，被 {@code ArchiveController}（7 处）与
 * {@code CompareActivity}（2 处）直接调用 —— 拆分之后 controller 仍然反向依赖
 * {@code MainActivity}，是 O-16 留下的最大一处耦合。它们不碰任何 Android API,
 * 只做「这个名字像不像图片 / 文本 / 压缩包」的判断，天然属于 fs 层。</p>
 *
 * <p>判据只有扩展名（加一条「无扩展名的小文件当文本」），不做字节嗅探：
 * 压缩包条目没有 File 可读，界面上要的是「点了会进哪个查看器」，
 * 而不是「内容到底是什么」。真正的嗅探在 {@code looksLikeMpegTs} 那种必要位置。</p>
 */
public final class FileKind {

    private FileKind() {}

    /** 常见图片扩展名 → 点击进图片查看器 */
    public static boolean isImageExt(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    /** 是否压缩包类扩展名（ZIP/7z/TAR/RAR/Zstd 等，交给内嵌多格式浏览器） */
    public static boolean isArchiveExt(String lower) {
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")
                || lower.endsWith(".tar") || lower.endsWith(".tgz") || lower.endsWith(".tar.gz")
                || lower.endsWith(".tar.zst") || lower.endsWith(".tzst") || lower.endsWith(".zst");
    }

    /** 常见文本扩展名 → 点击直接进编辑器 */
    public static boolean isTextFile(File f, String lower) {
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

    /**
     * APK 派生文件的中文说明（{@code _bak} / {@code _signed} / {@code _edit} …）；
     * 不是派生文件返回 null。
     *
     * <p>这几个后缀是本应用自己的产物，说明文字必须写清「能不能装、能不能删」——
     * 用户看到 {@code xxx_bak.apk} 最容易直接点安装，装上去就是旧版本。</p>
     */
    public static String apkFileRole(File f) {
        if (f == null) return null;
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

    /** 去掉文件名扩展名（{@code a.tar.gz} → {@code a.tar}，只剥最后一段） */
    public static String stripExt(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
