package com.hex91.file.fs;

import com.hex91.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link HfDocumentsProvider} 的纯 Java 路径逻辑（不碰 Android，可在桌面单测）。
 *
 * <p>documentId 直接用绝对路径，好处是 {@code queryDocument} 不必维护 id→文件 映射表，
 * 进程被杀重启后 id 依然有效。代价是 <b>id 来自外部应用，必须校验</b>：
 * 任何不在允许根下的路径都要拒绝，否则别的 app 能借我们的 provider 读 /data。</p>
 */
public final class DocPath {

    /** 单段文件名上限（ext4 是 255 字节，中文 UTF-8 三字节，留点余量） */
    public static final int MAX_NAME_BYTES = 240;

    private DocPath() {}

    /**
     * 规范化：折叠重复的 /、去掉末尾的 /（根目录除外）、拒绝含 .. 的段。
     *
     * @return 规范路径；输入非法（空、相对路径、含 ..）返回 null
     */
    public static String normalize(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty() || p.charAt(0) != '/') return null;
        // 拒绝 \0 与 ..：docId 可以伪造，这两个是目录穿越的入口
        if (p.indexOf('\0') >= 0) return null;

        List<String> segs = new ArrayList<String>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || ".".equals(s)) continue;
            if ("..".equals(s)) return null;
            segs.add(s);
        }
        if (segs.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : segs) sb.append('/').append(s);
        return sb.toString();
    }

    /** path 是否落在 root 之下（含 root 自身）；两者都需已 normalize */
    public static boolean within(String root, String path) {
        if (root == null || path == null) return false;
        if (path.equals(root)) return true;
        if ("/".equals(root)) return true;
        return path.length() > root.length()
                && path.startsWith(root)
                && path.charAt(root.length()) == '/';
    }

    /** 任一允许根之下则放行 */
    public static boolean withinAny(List<String> roots, String path) {
        if (path == null) return null == roots;
        if (roots == null) return false;
        for (String r : roots) if (within(r, path)) return true;
        return false;
    }

    /** 父路径；根目录的父为自己（provider 不会向上越界） */
    public static String parent(String path) {
        if (path == null || "/".equals(path)) return path;
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    /** 末段文件名；根目录返回空串 */
    public static String name(String path) {
        if (path == null || "/".equals(path)) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /**
     * 子项 docId。这里不用 {@code new File(parent, name)}：Windows 上分隔符是 \，
     * 单测会挂；且 provider 只在 Android 跑，手拼更可控。
     */
    public static String child(String parent, String name) {
        if (parent == null) return null;
        if ("/".equals(parent)) return "/" + name;
        return parent + "/" + name;
    }

    /**
     * 清洗外部传进来的文件名：去掉分隔符与控制字符，裁剪超长。
     *
     * @return 可用的名字；完全为空返回 null
     */
    public static String sanitize(String displayName) {
        if (displayName == null) return null;
        String s = displayName.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return null;
        // 按 UTF-8 字节截断，避免超长中文名落盘失败
        while (utf8Len(s) > MAX_NAME_BYTES && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    private static int utf8Len(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c)) {
                n += 4;
                i++;
            } else n += 3;
        }
        return n;
    }

    /**
     * 目录优先 + 名称不区分大小写比较（与主界面列表排序观感一致）。
     * provider 里必须自己排：系统 UI 只按返回顺序展示。
     */
    public static int compare(boolean aDir, String aName, boolean bDir, String bName) {
        if (aDir != bDir) return aDir ? -1 : 1;
        int c = aName.compareToIgnoreCase(bName);
        return c != 0 ? c : aName.compareTo(bName);
    }

    // ------------------------------------------------------------- 打开模式

    /** 只读 */
    public static final int M_READ = 0;
    /** 只写 + 截断（"w" / "wt"） */
    public static final int M_WRITE = 1;
    /** 只写 + 追加（"wa"） */
    public static final int M_APPEND = 2;
    /** 读写（"rw"） */
    public static final int M_RW = 3;
    /** 读写 + 截断（"rwt"） */
    public static final int M_RWT = 4;

    /**
     * 解析 SAF 的 mode 字符串。
     *
     * <p>易错点：{@code "w"} 与 {@code "wt"} 都必须带 truncate，否则写短内容会在文件尾部
     * 留下上一次的残余；{@code "wa"} 是 append 不能带 truncate，否则追加变覆盖。</p>
     */
    public static int modeOf(String mode) {
        if (mode == null) return M_READ;
        if ("w".equals(mode) || "wt".equals(mode)) return M_WRITE;
        if ("wa".equals(mode)) return M_APPEND;
        if ("rw".equals(mode)) return M_RW;
        if ("rwt".equals(mode)) return M_RWT;
        return M_READ;
    }
}
