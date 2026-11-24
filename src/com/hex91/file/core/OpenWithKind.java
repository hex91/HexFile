package com.hex91.file.core;

/**
 * 「打开方式」对话框里的内置打开方式（v2.122.0）。
 *
 * <p>只描述「有哪一种方式」和「对某个文件是否适用」，不碰 Android API、不产文案：
 * 资源 id 与 Activity 启动都在 ui 层的 OpenWithDialog 完成。这样这层是纯 JDK，
 * 适用性判定可以脱离设备做单测，也符合 core 层不依赖 android 的依赖门。</p>
 *
 * <p>为什么要有「适用性」：MT 式打开方式把所有功能都列出来，但对一个 .png 点
 * 「Arsc 功能」只会得到一个失败提示。这里在列表项上先判一次，避免用户误点；
 * 通用方式（十六进制、文本、字符串）对任何文件都成立，永远保留。</p>
 */
public enum OpenWithKind {
    /** 十六进制查看/编辑 */
    HEX,
    /** 文本编辑器 */
    TEXT,
    /** 压缩包浏览 */
    ARCHIVE,
    /** 图片查看 */
    IMAGE,
    /** 视频播放 */
    VIDEO,
    /** APK 信息 */
    APK_INFO,
    /** Dex 编辑器 */
    DEX,
    /** Arsc 编辑器 */
    ARSC,
    /** 字体预览 */
    FONT,
    /** PDF 预览 */
    PDF,
    /** 二进制字符串 */
    STRINGS,
    /** 系统已安装应用（chooser） */
    SYSTEM;

    /** 内置页的展示顺序，与枚举声明顺序一致 */
    public static final OpenWithKind[] BUILT_INS = {
        HEX, TEXT, ARCHIVE, IMAGE, VIDEO, APK_INFO, DEX, ARSC, FONT, PDF, STRINGS
    };

    /** 系统应用页的分类，顺序与标签栏一致 */
    public static final String[] APP_CATEGORIES = { "text/*", "image/*", "video/*", "audio/*" };

    /**
     * 该方式对某个文件是否适用。
     *
     * @param kind 打开方式
     * @param name 文件名（可为 null，此时只保留通用方式）
     * @return true = 可显示；false = 点开只会失败，不该出现在列表里
     */
    public static boolean applies(OpenWithKind kind, String name) {
        if (kind == null) return false;
        if (kind == HEX || kind == TEXT || kind == STRINGS || kind == SYSTEM) return true;
        String e = ext(name);
        switch (kind) {
            case ARCHIVE:
                return e.equals("zip") || e.equals("rar") || e.equals("7z")
                        || e.equals("tar") || e.equals("gz");
            case IMAGE:
                return e.equals("png") || e.equals("jpg") || e.equals("jpeg")
                        || e.equals("gif") || e.equals("webp") || e.equals("bmp")
                        || e.equals("svg") || e.equals("heic") || e.equals("heif")
                        || e.equals("avif") || e.equals("tiff") || e.equals("tif")
                        || e.equals("psd") || e.equals("ico");
            case VIDEO:
                return MimeUtil.isVideo(name);
            case APK_INFO:
            case ARSC:
                return e.equals("apk");
            case DEX:
                return e.equals("dex");
            case FONT:
                return e.equals("ttf") || e.equals("otf");
            case PDF:
                return e.equals("pdf");
            default:
                return false;
        }
    }

    /** 该分类对应的 MIME 前缀，用于 queryIntentActivities 的系统应用筛选 */
    public static String mimeOfCategory(String category) {
        if (category == null) return "*/*";
        if (category.startsWith("text")) return "text/*";
        if (category.startsWith("image")) return "image/*";
        if (category.startsWith("video")) return "video/*";
        if (category.startsWith("audio")) return "audio/*";
        return "*/*";
    }

    private static String ext(String name) {
        if (name == null) return "";
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1) : "";
    }
}
