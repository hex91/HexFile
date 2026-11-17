package com.hex91.file.core;

/**
 * 归档条目名工具（纯 JDK，v2.13.1 / F-11）。
 *
 * <p>为什么单独一个类：重命名一个目录时要把「它自己 + 它下面全部子项」一起改前缀，
 * 这段映射 zip / tar / 7z 三个后端都要用，写三份迟早会出现「zip 能改、7z 改歪」。
 * 而且它必须能在桌面单测里跑（{@code test/EntryNamesTest}），所以只放纯字符串逻辑，
 * 不碰 android.*（见 {@code test/deps_check.sh}：core 必须是纯 JDK）。</p>
 */
public final class EntryNames {

    private EntryNames() {}

    /**
     * 条目名安全：非空、无 NUL、非绝对路径、无盘符、无 {@code ..} 段。
     *
     * <p>写入侧防护（v1.66.3 起）：本应用自己解压时有 zip-slip 防护，但写出的包里若带
     * {@code ../} 条目，别的解压工具可能中招。</p>
     */
    public static boolean safe(String entryName) {
        if (entryName == null || entryName.isEmpty()) return false;
        if (entryName.indexOf('\0') >= 0) return false;
        String n = entryName.replace('\\', '/');
        if (n.startsWith("/") || n.contains(":")) return false;
        for (String seg : n.split("/")) {
            if (seg.equals("..")) return false;
        }
        return true;
    }

    /** 去掉一个结尾的 '/'（{@code a/b/} → {@code a/b}；空串保持空串） */
    public static String stripSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    /** 规范成目录条目名：末尾补一个 '/'；空串仍是空串（表示归档根） */
    public static String dir(String path) {
        if (path == null) return "";
        String s = path.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty()) return "";
        return s.endsWith("/") ? s : s + "/";
    }

    /** 父目录前缀：{@code a/b/c.txt} → {@code a/b/}；顶层条目 → {@code ""} */
    public static String parentOf(String name) {
        if (name == null) return "";
        String s = stripSlash(name);
        int i = s.lastIndexOf('/');
        return i < 0 ? "" : s.substring(0, i + 1);
    }

    /** 最后一段：{@code a/b/c.txt} → {@code c.txt} */
    public static String lastSeg(String name) {
        if (name == null) return "";
        String s = stripSlash(name);
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    /**
     * 重命名映射：{@code from} 及其全部子项改到 {@code to} 下，其它条目原样返回。
     *
     * <table>
     *   <tr><th>条目</th><th>renamed(name, "a", "x/y")</th></tr>
     *   <tr><td>a</td><td>x/y</td></tr>
     *   <tr><td>a/</td><td>x/y/</td></tr>
     *   <tr><td>a/1.txt</td><td>x/y/1.txt</td></tr>
     *   <tr><td>ab/1.txt</td><td>ab/1.txt（不是 a 的子项，不动）</td></tr>
     * </table>
     *
     * <p>特意按「{@code from + "/"} 前缀」判断而不是 {@code startsWith(from)}：
     * 后者会把 {@code ab/} 当成 {@code a} 的子项一起改名，一次重命名毁半个包。</p>
     */
    public static String renamed(String name, String from, String to) {
        if (name == null || from == null || to == null) return name;
        String f = stripSlash(from);
        String t = stripSlash(to);
        if (f.isEmpty() || f.equals(t)) return name;
        if (name.equals(f)) return t;
        if (name.equals(f + "/")) return t + "/";
        if (name.startsWith(f + "/")) return t + name.substring(f.length());
        return name;
    }

    /**
     * 用户输入 → 条目名：反斜杠转正斜杠、去首尾 '/'、合并连续 '/'、去掉 {@code ./} 段。
     * 只做规范化，不做合法性判断（那是 {@link #safe} 的事）。
     */
    public static String clean(String input) {
        if (input == null) return "";
        String s = input.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        StringBuilder sb = new StringBuilder(s.length());
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }
}
