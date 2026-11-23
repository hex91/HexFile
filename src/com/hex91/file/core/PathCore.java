package com.hex91.file.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v2.114.0（O-27）：路径字符串工具 —— 纯 JDK，无 android / 无 java.nio.file。
 *
 * <p>为什么单独抽出来：{@code fs.Fs}（本地）与 {@code net.NetFs}（远程）对
 * 「拼接 / 取父 / 取末段 / 折叠 . 与 ..」的规则必须完全一致，否则同一个路径在
 * 本地与远程会落到不同位置。此前这套规则散在 {@code NetFs} 的静态方法里，
 * 本地侧则依赖 {@code java.io.File} 的隐式解析，两边没人保证一致。</p>
 *
 * <p>为什么不用 {@code java.nio.file.Paths#normalize}：minSdk 21，
 * {@code java.nio.file} 要到 API 26 才有；而且桌面 JVM 上 {@code Paths.get("/x")}
 * 会被解析成「当前盘根」，与 Android 结论不同 —— 单测通过、真机出错是最难查的那类问题。
 * 这里用纯字符串栈折叠，两个平台逐字符一致。</p>
 *
 * <p>分隔符统一为 {@code '/'}；输入里的 {@code '\'} 视为分隔符（Windows 桌面单测会用到）。
 * 输出保留前导 {@code '/'}（绝对路径）或盘符前缀 {@code "C:/"}，不做「相对转绝对」。</p>
 */
public final class PathCore {

    private PathCore() {}

    /**
     * 规范化路径：统一分隔符、折叠 {@code .} 与 {@code ..}、压缩重复分隔符、去掉尾斜杠。
     *
     * <p>边界约定（均有单测）：</p>
     * <ul>
     *   <li>{@code ""} / {@code null} → {@code ""}</li>
     *   <li>{@code "/"} → {@code "/"}；{@code "/.."} → {@code "/"}（绝对路径不会越过根）</li>
     *   <li>{@code "C:\\a\\..\\b"} → {@code "C:/b"}</li>
     *   <li>{@code "a/../../b"} → {@code "../b"}（相对路径保留前导 ..）</li>
     * </ul>
     */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path.replace('\\', '/');

        // 前缀：绝对路径的 "/" 或 Windows 盘符 "C:/"
        String prefix = "";
        if (p.startsWith("/")) {
            prefix = "/";
            p = p.substring(1);
        } else if (p.length() >= 2 && p.charAt(1) == ':'
                && Character.isLetter(p.charAt(0))) {
            prefix = p.substring(0, 2) + "/";
            p = p.length() > 2 ? p.substring(2) : "";
        }

        Deque<String> stack = new ArrayDeque<String>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!stack.isEmpty() && !"..".equals(stack.peekLast())) {
                    stack.removeLast();
                } else if (prefix.isEmpty()) {
                    // 相对路径的 .. 无处可折，原样保留（否则 "a/../../b" 会变成 "b"）
                    stack.addLast(seg);
                }
                // 绝对路径上 "/.." 折叠为 "/"：与 File.getAbsolutePath 的行为一致
            } else {
                stack.addLast(seg);
            }
        }

        StringBuilder sb = new StringBuilder(prefix);
        boolean first = true;
        for (String seg : stack) {
            if (!first) sb.append('/');
            sb.append(seg);
            first = false;
        }
        // 绝对路径折叠到根时保留 "/"；"C:/" 同理
        return sb.length() == 0 ? "" : sb.toString();
    }

    /** 拼接：{@code "a" + "b.txt"} → {@code "a/b.txt"}；parent 为空返回 name 本身。 */
    public static String join(String parent, String name) {
        if (parent == null || parent.isEmpty()) return name == null ? "" : name;
        if (name == null || name.isEmpty()) return parent;
        if (parent.endsWith("/")) return parent + name;
        return parent + "/" + name;
    }

    /** 取最后一段；根目录（{@code "/"} 或 {@code "C:/"}）返回空串。 */
    public static String baseName(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path.replace('\\', '/');
        int end = p.length();
        while (end > 1 && p.charAt(end - 1) == '/') end--;
        String q = p.substring(0, end);
        int i = q.lastIndexOf('/');
        if (i < 0) return q;
        return q.substring(i + 1);
    }

    /** 取父路径；无父（一级相对路径 / 根）返回空串，绝对路径的根返回 {@code "/"}。 */
    public static String parentOf(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = normalize(path);
        int end = p.length();
        while (end > 1 && p.charAt(end - 1) == '/') end--;
        String q = p.substring(0, end);
        int i = q.lastIndexOf('/');
        if (i < 0) return "";
        if (i == 0) return "/";
        String parent = q.substring(0, i);
        // "C:/a" → "C:/"（盘符根不能退成 "C:"）
        if (parent.length() == 2 && parent.charAt(1) == ':') return parent + "/";
        return parent;
    }

    /** 是否是某个根（{@code "/"} 或 {@code "C:/"}）。 */
    public static boolean isRoot(String path) {
        if (path == null) return false;
        String p = normalize(path);
        if ("/".equals(p)) return true;
        return p.length() == 3 && p.charAt(1) == ':' && p.charAt(2) == '/';
    }

    /**
     * 远程相对路径的父目录：根是空串（不是 {@code "/"}）。
     *
     * <p>为什么不能直接用 {@link #parentOf}：远程协议（FTP/SFTP/SMB/WebDAV/S3）的根
     * 是空串，{@code "a"} 的父是 {@code ""}；而 {@code parentOf("/a")} 会返回 {@code "/"}，
     * 那是文件系统语义。两者共用同一套折叠规则，只在「根怎么表示」上有区别，
     * 所以拆成两个方法而不是让调用方到处补 {@code startsWith("/")}。</p>
     *
     * <p>顺带把前导 / 尾随斜杠都吃掉：WebDAV 的 href 会带前导 {@code /}，
     * 目录项可能带尾随 {@code /}，协议层不该把这些形态差异漏给上层。</p>
     */
    public static String parentOfRelative(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = normalize(path);
        int start = 0;
        while (start < p.length() && p.charAt(start) == '/') start++;
        p = p.substring(start);
        int end = p.length();
        while (end > 0 && p.charAt(end - 1) == '/') end--;
        p = p.substring(0, end);
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }
}
