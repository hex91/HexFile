package com.hex91.file.net;
import com.hex91.file.fs.TempFiles;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 局域网快传的报文格式与文件名规则（v1.74.0）。
 *
 * <p>纯 Java、无 Android 依赖，可以在桌面 JVM 里直接单测，避免「只有真机两台才能验证」
 * 的尴尬。Android 侧只负责把字节发出去/收进来，规则全部收敛在这里。</p>
 *
 * <p>发现报文（UDP，一行 UTF-8 文本，用 {@code |} 分隔）：</p>
 * <pre>
 *   HEXFILE_LAN/1|P|设备ID|HTTP端口|设备名(百分号编码)
 *   HEXFILE_LAN/1|H|设备ID|HTTP端口|设备名(百分号编码)
 * </pre>
 * <p>{@code P}=探测（我需要知道有谁在线），{@code H}=应答（我在这里）。
 * 设备名可能含中文、空格甚至 {@code |}，所以必须编码；只保留 RFC 3986 的 unreserved
 * 字符，其余按 UTF-8 字节编码成 {@code %XX}。</p>
 *
 * <p>设计上刻意不引入 JSON/Protobuf：报文只有 5 个字段，手写解析零依赖、可读性好，
 * 出问题时用 tcpdump 抓到包也能直接看懂。</p>
 */
public final class LanProtocol {

    /** 协议标识（含版本），不匹配的一律丢弃，避免和别的 App 的广播互相干扰 */
    public static final String MAGIC = "HEXFILE_LAN/1";

    /** 发现用的固定 UDP 端口：两端都监听这个端口，才能互相收到广播 */
    public static final int DISCOVERY_PORT = 48899;

    public static final String TYPE_PROBE = "P";
    public static final String TYPE_HERE = "H";

    /** 设备有效期：超过这个时间没再收到报文，就从列表里剔除 */
    public static final long TTL_MS = 12000L;

    /** 设备 ID 最大长度（防止畸形包撑大内存） */
    public static final int MAX_ID = 64;

    /** 接收文件名的最大长度（按字符计，保留扩展名） */
    public static final int MAX_FILE_NAME = 120;

    private LanProtocol() {
    }

    /** 在线设备 */
    public static final class Device {
        /** 稳定标识：同一台设备换 IP 也认得出（用 Android ID / 持久化 UUID） */
        public final String id;
        /** 展示名，通常是手机型号 */
        public final String name;
        public final String host;
        public final int port;
        public final long lastSeen;
        /** P=探测包带出来的，H=应答包带出来的；仅用于调试展示 */
        public final String kind;

        public Device(String id, String name, String host, int port, long lastSeen, String kind) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.host = host == null ? "" : host;
            this.port = port;
            this.lastSeen = lastSeen;
            this.kind = kind == null ? "" : kind;
        }

        public boolean isStale(long now) {
            return now - lastSeen > TTL_MS;
        }

        public Device refresh(long now) {
            return new Device(id, name, host, port, now, kind);
        }

        public String label() {
            return name + " · " + host;
        }

        @Override
        public String toString() {
            return name + "(" + host + ":" + port + ")";
        }
    }

    /** 组装一条发现报文 */
    public static String encode(String type, String id, int port, String name) {
        return MAGIC + "|" + type + "|" + normalizeId(id) + "|" + port + "|"
                + percentEncode(name == null ? "" : name);
    }

    /**
     * 解析发现报文。任何不合规字段都返回 null（不抛异常）——广播端口上什么人都有，
     * 解析器必须对垃圾数据免疫。
     */
    public static Device parse(String msg, String host, long now) {
        if (msg == null) return null;
        String s = msg.trim();
        if (!s.startsWith(MAGIC + "|")) return null;
        String[] p = s.split("\\|", 5);
        if (p.length != 5) return null;
        String kind = p[1];
        if (!TYPE_PROBE.equals(kind) && !TYPE_HERE.equals(kind)) return null;
        String id = p[2];
        if (id.length() == 0 || id.length() > MAX_ID) return null;
        int port;
        try {
            port = Integer.parseInt(p[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535) return null;
        String name = percentDecode(p[4]);
        if (name.length() == 0) name = "未命名设备";
        if (name.length() > 40) name = name.substring(0, 40);
        return new Device(id, name, host, port, now, kind);
    }

    /** 报文类型（P/H），畸形包返回空串 */
    public static String kindOf(String msg) {
        if (msg == null) return "";
        String s = msg.trim();
        if (!s.startsWith(MAGIC + "|")) return "";
        int a = s.indexOf('|', MAGIC.length() + 1);
        return a < 0 ? "" : s.substring(MAGIC.length() + 1, a);
    }

    /** ID 里不允许出现分隔符与空白，否则会把报文结构拆坏 */
    public static String normalizeId(String id) {
        if (id == null) return "";
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length() && sb.length() < MAX_ID; i++) {
            char c = id.charAt(i);
            if (c == '|' || c <= ' ') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 百分号编码

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String percentEncode(String s) {
        if (s == null || s.length() == 0) return "";
        byte[] raw;
        try {
            raw = s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            raw = s.getBytes();
        }
        StringBuilder sb = new StringBuilder(raw.length + 16);
        for (int i = 0; i < raw.length; i++) {
            int c = raw[i] & 0xFF;
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (keep) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    /** 解码；{@code +} 按表单惯例当空格（编码端不会产出裸 +，所以不会歧义） */
    public static String percentDecode(String s) {
        if (s == null || s.length() == 0) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = hexVal(s.charAt(i + 1));
                int lo = hexVal(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            if (c == '+') {
                out.write(' ');
                continue;
            }
            byte[] b;
            try {
                b = String.valueOf(c).getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                b = new byte[]{(byte) c};
            }
            out.write(b, 0, b.length);
        }
        try {
            return new String(out.toByteArray(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(out.toByteArray());
        }
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // ---------------------------------------------------------------- 文件名

    /**
     * 净化对方发来的文件名：这是**不可信输入**，必须挡住目录穿越和非法字符。
     *
     * <ul>
     *   <li>{@code ../../etc/passwd} → {@code passwd}（只取最后一段）</li>
     *   <li>{@code a/b\c:d*e?f"g<h>i|j} → 非法字符换 {@code _}</li>
     *   <li>控制字符直接丢弃；空名 / {@code .} / {@code ..} → {@code file.bin}</li>
     *   <li>过长时保留扩展名截断主体</li>
     * </ul>
     */
    public static String safeFileName(String raw) {
        if (raw == null) return "file.bin";
        String s = raw.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127) continue;
            if (c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>'
                    || c == '|' || c == '\\' || c == '/') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        s = sb.toString().trim();
        // 全是点的名字（".", "..", "..."）在文件系统里有特殊含义，一律拒绝
        boolean onlyDots = s.length() > 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '.') {
                onlyDots = false;
                break;
            }
        }
        if (s.length() == 0 || onlyDots) return "file.bin";
        if (s.length() > MAX_FILE_NAME) {
            int dot = s.lastIndexOf('.');
            String ext = (dot > 0 && dot > s.length() - 16) ? s.substring(dot) : "";
            int keep = MAX_FILE_NAME - ext.length();
            if (keep < 1) keep = MAX_FILE_NAME;
            s = s.substring(0, Math.min(keep, s.length())) + ext;
        }
        return s;
    }

    /**
     * 同名文件不覆盖，改成 {@code 名字 (1).ext}。
     * 传照片、传安装包时重名极常见，静默覆盖会直接丢数据。
     */
    public static String uniqueName(String name, Set<String> existing) {
        if (name == null || name.length() == 0) name = "file.bin";
        if (existing == null || !existing.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!existing.contains(cand)) return cand;
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    /**
     * 原子占名（v2.5.0 R-19）：给正在接收的文件抢一个不与任何人冲突的临时名。
     *
     * <p>原来走的是「列目录 → {@link #uniqueName} → new FileOutputStream」：两个同名请求
     * 同时看到目录里没有 {@code X}，于是都选中 {@code X}，共写同一个 {@code X.part}。
     * 先完成的一方 rename 走文件，另一方 rename 失败后改为复制「此刻的共享 .part」——
     * 拿到的可能是两份数据交错的内容；它随后 delete 掉 .part，还可能删掉对方正在写的文件。</p>
     *
     * <p>{@code createNewFile} 是原子的，能跨线程与跨进程消除 check-then-create 窗口；
     * 占名失败就换下一个编号。<b>调用方负责清理</b>：请求失败/中断时必须删掉这个 0 字节占位文件。</p>
     *
     * @return 已创建的 0 字节临时文件，名字形如 {@code 名字.part} 或 {@code 名字 (1).part}
     */
    public static File reserve(File dir, String rawName) throws IOException {
        String name = safeFileName(rawName);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i < 10000; i++) {
            String cand = i == 0 ? name : base + " (" + i + ")" + ext;
            File part = new File(dir, cand + TempFiles.PART);
            if (part.createNewFile()) return part;
        }
        throw new IOException("无法分配唯一临时文件名: " + name);
    }

    /**
     * 解析 HTTP 的 query（也接受整条 URI）。用于 {@code /send?name=..&size=..}。
     * 值里可能有中文文件名，同样按 UTF-8 百分号解码。
     */
    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (raw == null || raw.length() == 0) return out;
        String q = raw;
        int qm = q.indexOf('?');
        if (qm >= 0) q = q.substring(qm + 1);
        int hash = q.indexOf('#');
        if (hash >= 0) q = q.substring(0, hash);
        String[] pairs = q.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            if (pair.length() == 0) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(percentDecode(k), percentDecode(v));
        }
        return out;
    }

    /**
     * 接收目录里可以展示的文件（v1.75.0）。
     *
     * <p>几条规则都是为了「列表好看且不会误导」：</p>
     * <ul>
     *   <li>跳过 {@code .part}：那是正在写入的临时文件，半截数据出现在列表里会让人以为收到了坏文件；</li>
     *   <li>跳过子目录：本目录只存收到的文件，出现目录说明是用户自己放的，不该混进接收列表；</li>
     *   <li>按修改时间倒序：最近收到的排最前，符合「我要找刚传的那个」这一实际诉求。</li>
     * </ul>
     * 目录不可读时返回空表，不抛异常（权限被撤销是很常见的状态）。
     */
    public static java.util.List<java.io.File> receivedFiles(java.io.File dir) {
        java.util.List<java.io.File> out = new java.util.ArrayList<java.io.File>();
        if (dir == null) return out;
        java.io.File[] fs;
        try {
            fs = dir.listFiles();
        } catch (Throwable t) {
            return out;
        }
        if (fs == null) return out;
        for (int i = 0; i < fs.length; i++) {
            java.io.File f = fs[i];
            if (f == null || !f.isFile()) continue;
            String n = f.getName();
            if (n.endsWith(TempFiles.PART) || n.startsWith(".")) continue;
            out.add(f);
        }
        java.util.Collections.sort(out, new java.util.Comparator<java.io.File>() {
            public int compare(java.io.File a, java.io.File b) {
                long byTime = b.lastModified() - a.lastModified();
                if (byTime != 0) return byTime > 0 ? 1 : -1;
                return b.getName().compareTo(a.getName());
            }
        });
        return out;
    }
}
