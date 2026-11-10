package com.hex91.file;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.89.0 云盘路径工具（纯 Java，可在桌面单测）。
 *
 * <p>两条云盘走的都是「HTTP + 路径里的名字」：</p>
 * <ul>
 *   <li>OneDrive（Graph）用 <b>路径寻址</b>：{@code /me/drive/root:/a b/c.txt:/children}，
 *       路径里的空格、中文、{@code :}、{@code #}、{@code %} 必须逐段百分号编码，
 *       否则会被当成 URL 结构的一部分（{@code #} 之后全被当 fragment，文件名带 # 就列不出东西）；</li>
 *   <li>阿里云盘用 <b>file_id 寻址</b>，但同样要把「用户看到的路径」规范化成层级，
 *       才能逐级换 id。</li>
 * </ul>
 *
 * <p>所以这里的 {@link #encodeSegment} 比 {@code URLEncoder} 更严格：后者会把空格编成
 * {@code +}（在 URL 路径里 {@code +} 是字面量加号，不是空格），也会把 {@code ~} 编掉。
 * 只保留 RFC 3986 的 unreserved 集合，其余一律 {@code %HH}（大写十六进制）。</p>
 */
public final class CloudPath {

    private CloudPath() {}

    /**
     * 规范化远程路径：去首尾斜杠、折叠连续斜杠、丢掉 "."、拒绝 ".."。
     *
     * <p>拒绝 {@code ..} 而不是静默折叠：云盘没有「上级」的概念可以穿越，
     * 出现 {@code ..} 只可能是拼接逻辑写错了，静默修正会让错误藏起来。</p>
     */
    public static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        List<String> out = new ArrayList<String>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) throw new IllegalArgumentException("路径不允许包含 .. : " + path);
            out.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : out) {
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    /** 拆段（已规范化，不含空段） */
    public static List<String> segments(String path) {
        List<String> out = new ArrayList<String>();
        String p = normalize(path);
        if (p.isEmpty()) return out;
        for (String seg : p.split("/")) out.add(seg);
        return out;
    }

    /** 单段百分号编码：保留 A-Z a-z 0-9 - _ . ~，其余按 UTF-8 编成 %HH */
    public static String encodeSegment(String s) {
        if (s == null || s.isEmpty()) return "";
        byte[] b;
        try {
            b = s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            b = s.getBytes();                  // 不可能发生；退化为平台编码
        }
        StringBuilder sb = new StringBuilder(b.length + 8);
        for (byte x : b) {
            int c = x & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(HEX[c >> 4]);
                sb.append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 逐段编码后重新拼起来（斜杠保留为分隔符） */
    public static String encodePath(String path) {
        List<String> segs = segments(path);
        StringBuilder sb = new StringBuilder();
        for (String seg : segs) {
            if (sb.length() > 0) sb.append('/');
            sb.append(encodeSegment(seg));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- OneDrive

    /**
     * Graph 的路径主键：根目录是 {@code root}，其余是 {@code root:/a/b:c} 的前半段。
     *
     * <p>返回的字符串不含结尾的 {@code :}，由调用方按端点拼接（children / content / 空）。</p>
     */
    public static String graphKey(String path) {
        String p = normalize(path);
        return p.isEmpty() ? "root" : "root:/" + encodePath(p);
    }

    /** {@code /me/drive/root/children} 或 {@code /me/drive/root:/a/b:/children} */
    public static String graphChildren(String path) {
        String p = normalize(path);
        return p.isEmpty() ? "/me/drive/root/children" : "/me/drive/root:/" + encodePath(p) + ":/children";
    }

    /** 单个条目：{@code /me/drive/root:/a/b} 或 {@code /me/drive/root} */
    public static String graphItem(String path) {
        String p = normalize(path);
        return p.isEmpty() ? "/me/drive/root" : "/me/drive/root:/" + encodePath(p);
    }

    /** 内容直链：{@code /me/drive/root:/a/b:/content} */
    public static String graphContent(String path) {
        String p = normalize(path);
        return p.isEmpty() ? "/me/drive/root/content" : "/me/drive/root:/" + encodePath(p) + ":/content";
    }

    /** 父路径（用于「在 parent 下建目录」）；根目录的父仍是根 */
    public static String parent(String path) {
        String p = normalize(path);
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    /** 末段名字（根目录返回空串） */
    public static String name(String path) {
        String p = normalize(path);
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /**
     * 云盘文件名/目录名合法性（两边都拦，早报比传到一半报好）。
     *
     * <p>OneDrive 的非法字符是 {@code " * : < > ? / \ |}；阿里云盘还会拦 {@code # % & { } $ ' @ + `}
     * 里的一部分，这里取两者的交集之外最保守的一档：只拦所有云盘都不会放行的那些。</p>
     */
    public static String illegalNameReason(String name) {
        if (name == null || name.trim().isEmpty()) return "名称不能为空";
        if (name.length() > 255) return "名称过长（超过 255 字符）";
        String bad = "\"*:<>?/\\|";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (bad.indexOf(c) >= 0) return "名称不能包含 " + c;
            if (c < 0x20) return "名称不能包含控制字符";
        }
        if (".".equals(name) || "..".equals(name)) return "名称不能是 . 或 ..";
        return null;                            // null = 合法
    }
}
