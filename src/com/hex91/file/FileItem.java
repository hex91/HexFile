package com.hex91.file;

import java.io.File;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** 文件条目模型：类型图标(标签+颜色) + 名称 + 时间/大小（对标 MT 条目样式） */
public class FileItem implements Serializable {

    private static final SimpleDateFormat TF =
            new SimpleDateFormat("yy-MM-dd HH:mm", Locale.CHINA);

    public File file;
    /** 多选模式下是否被勾选 */
    public boolean selected;
    /** v1.40.0：普通目录列表顶部的「..」返回上级虚拟条目（file = 上级目录） */
    public boolean upEntry;
    /** v1.42.0：root 列取的条目，ls -p 已确认是目录（stat 可能被 SELinux 拒绝，以此为准） */
    public boolean forceDir;

    // ---------- 压缩包条目模式（archFull 非空时生效，v1.14） ----------
    /** 完整条目名（目录以 / 结尾）；".. " 为返回上级虚拟项 */
    public String archFull;
    /** 列表显示短名 */
    public String archShort;
    public boolean archDir;
    public long archSize;

    public FileItem(File f) {
        this.file = f;
    }

    /** 压缩包条目构造 */
    public FileItem(String full, String shortName, boolean isDir, long size) {
        this.archFull = full;
        this.archShort = shortName;
        this.archDir = isDir;
        this.archSize = size;
    }

    /** 返回上级虚拟项（..，压缩包内浏览用） */
    public static FileItem archUp() {
        return new FileItem("..", "..", true, 0);
    }

    /** 普通目录列表顶部的 .. 返回项（v1.40.0，file 指向上级目录） */
    public static FileItem up(File parentDir) {
        FileItem it = new FileItem(parentDir);
        it.upEntry = true;
        return it;
    }

    public boolean isArchEntry() { return archFull != null; }
    public boolean isArchUp() { return "..".equals(archFull); }

    public String name() {
        if (upEntry) return "..";
        if (isArchEntry()) return archShort;
        return file.getName().isEmpty() ? file.getPath() : file.getName();
    }

    public boolean isDir() {
        if (isArchEntry()) return archDir;
        return forceDir || file.isDirectory();
    }

    /** 压缩包内条目路径 = 完整条目名（去尾部 /） */
    public String archPath() {
        String n = archFull;
        return (n != null && n.endsWith("/")) ? n.substring(0, n.length() - 1) : n;
    }

    /** 图标角标文字：DIR / APK / DEX / TXT / SO…（MT 式类型缩写） */
    public String typeLabel() {
        return typeLabel(name(), isDir());
    }

    /** 同上，静态版（供压缩包条目等非 File 场景复用） */
    public static String typeLabel(String name, boolean isDir) {
        if (isDir) return "DIR";
        String n = name.toLowerCase(Locale.CHINA);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        if (ext.isEmpty() || ext.length() > 4) return "BIN";
        return ext.toUpperCase(Locale.CHINA);
    }

    /** 图标方块底色：按类型分组（MT 配色感觉） */
    public int typeColor() {
        return typeColor(name(), isDir());
    }

    /** 同上，静态版（供压缩包条目等非 File 场景复用） */
    public static int typeColor(String name, boolean isDir) {
        if (isDir) return 0xFF263238;                       // 深灰黑：文件夹
        String e = name.toLowerCase(Locale.CHINA);
        if (e.endsWith(".apk"))       return 0xFF43A047;    // 绿：APK
        if (e.endsWith(".dex") || e.endsWith(".jar"))
                                      return 0xFF2E7D32;    // 深绿：dex/jar
        if (e.endsWith(".zip") || e.endsWith(".7z") || e.endsWith(".rar")
                || e.endsWith(".tar") || e.endsWith(".gz") || e.endsWith(".hap"))
                                      return 0xFF8D6E63;    // 棕：压缩包
        if (e.endsWith(".so"))        return 0xFF546E7A;    // 灰蓝：so
        if (e.endsWith(".jpg") || e.endsWith(".jpeg") || e.endsWith(".png")
                || e.endsWith(".gif") || e.endsWith(".webp") || e.endsWith(".bmp"))
                                      return 0xFF7E57C2;    // 紫：图片
        if (e.endsWith(".mp4") || e.endsWith(".mkv") || e.endsWith(".avi")
                || e.endsWith(".mov") || e.endsWith(".flv"))
                                      return 0xFFE53935;    // 红：视频
        if (e.endsWith(".mp3") || e.endsWith(".flac") || e.endsWith(".wav")
                || e.endsWith(".ogg") || e.endsWith(".m4a"))
                                      return 0xFFFB8C00;    // 橙：音频
        if (e.endsWith(".pdf") || e.endsWith(".doc") || e.endsWith(".docx")
                || e.endsWith(".xls") || e.endsWith(".xlsx") || e.endsWith(".ppt")
                || e.endsWith(".pptx"))
                                      return 0xFF1E88E5;    // 蓝：文档
        if (e.endsWith(".txt") || e.endsWith(".md") || e.endsWith(".log")
                || e.endsWith(".xml") || e.endsWith(".html") || e.endsWith(".htm")
                || e.endsWith(".json") || e.endsWith(".js") || e.endsWith(".ts")
                || e.endsWith(".java") || e.endsWith(".kt") || e.endsWith(".py")
                || e.endsWith(".c") || e.endsWith(".cpp") || e.endsWith(".h")
                || e.endsWith(".sh") || e.endsWith(".bat") || e.endsWith(".smali")
                || e.endsWith(".yml") || e.endsWith(".yaml") || e.endsWith(".toml")
                || e.endsWith(".csv") || e.endsWith(".ini") || e.endsWith(".conf")
                || e.endsWith(".properties") || e.endsWith(".gradle")
                || e.endsWith(".sql") || e.endsWith(".css"))
                                      return 0xFF039BE5;    // 浅蓝：文本/代码
        return 0xFF90A4AE;                                  // 灰：其他二进制
    }

    /** 副行：MT 式「yy-MM-dd HH:mm  大小」（文件夹只有时间） */
    public String detail() {
        if (upEntry) return "返回上级";
        if (isArchUp()) return "返回上级";
        if (isArchEntry()) return archDir ? "文件夹" : FileOps.formatSize(archSize);
        // v1.44.1：root 区 File API 拿不到 mtime/length（全 0）→ 用 su stat 缓存值
        if (file.lastModified() <= 0) {
            FileAttrs.Attr a = FileAttrs.get(file);
            if (a != null && a.mtime > 0) {
                String t = TF.format(new java.util.Date(a.mtime));
                return isDir() ? t : t + "  " + FileOps.formatSize(a.size);
            }
            return isDir() ? "" : "";
        }
        String t = TF.format(new java.util.Date(file.lastModified()));
        if (isDir()) return t;
        return t + "  " + FileOps.formatSize(file.length());
    }
}
