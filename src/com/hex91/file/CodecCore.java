package com.hex91.file;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * v1.70.0 工具箱：编码/解码核心（纯 Java，可在桌面单测，不依赖 Android）。
 *
 * <p>统一模型：任何格式先解析成字节数组（{@link #toBytes}），再从字节数组渲染成目标格式
 * （{@link #fromBytes}），因此「任意两种格式互转」天然支持，不需要 N×N 个转换函数。
 *
 * <p>两处例外：{@link #F_PUNYCODE}（中文域名）与 {@link #F_HTML}/{@link #F_UNICODE}
 * 属于「文本级」格式，按 UTF-8 文本而非裸字节处理 —— 域名编码本来就是对字符做 punycode，
 * 对字节做没有意义。
 */
public final class CodecCore {

    // ---------------- 格式常量 ----------------
    public static final int F_TEXT      = 0;   // 文本（UTF-8）
    public static final int F_HEX       = 1;   // E4 B8 AD
    public static final int F_HEX0X     = 2;   // 0xE4 0xB8 0xAD
    public static final int F_BIN       = 3;   // 11100100 ...
    public static final int F_DEC       = 4;   // 228 184 173
    public static final int F_BYTES     = 5;   // 0xE4,0xB8,0xAD / 228,184,173
    public static final int F_UNICODE   = 6;   // \u4e2d\u6587
    public static final int F_ESCAPE    = 7;   // \xE4\xB8\xAD
    public static final int F_URL       = 8;   // %E4%B8%AD
    public static final int F_BASE64    = 9;   // 5Lit
    public static final int F_BASE64URL = 10;  // 5Lit（URL 安全、无填充）
    public static final int F_HTML      = 11;  // &#20013;
    public static final int F_JSON      = 12;  // "\u4e2d"
    public static final int F_PUNYCODE  = 13;  // xn--fiq228c
    public static final int F_BASE32    = 14;  // 4PAY

    public static final String[] NAMES = {
            "文本 UTF-8", "HEX", "HEX 0x", "二进制", "十进制", "字节数组",
            "Unicode", "转义字符", "URL 编码", "Base64", "Base64URL",
            "HTML 实体", "JSON 字符串", "中文域名", "Base32"
    };

    public static String name(int f) {
        return (f >= 0 && f < NAMES.length) ? NAMES[f] : "未知";
    }

    private CodecCore() {
    }

    /** 任意两种格式互转；失败抛 {@link IllegalArgumentException}（message 为中文可读原因）。 */
    public static String convert(String in, int from, int to) {
        if (in == null) in = "";
        if (from == to) return in;
        byte[] b = toBytes(in, from);
        return fromBytes(b, to);
    }

    // ============================================================ 解析：格式 → 字节

    public static byte[] toBytes(String s, int fmt) {
        switch (fmt) {
            case F_TEXT:    return s.getBytes(utf8());
            case F_HEX:     return parseHex(s, false);
            case F_HEX0X:   return parseHex(s, true);
            case F_BIN:     return parseBin(s);
            case F_DEC:     return parseDec(s);
            case F_BYTES:   return parseBytes(s);
            case F_ESCAPE:  return parseEscape(s);
            case F_UNICODE: return unescapeUnicode(s).getBytes(utf8());
            case F_HTML:    return decodeHtml(s).getBytes(utf8());
            case F_JSON:    return decodeJson(s).getBytes(utf8());
            case F_PUNYCODE:return punyDecodeDomain(s).getBytes(utf8());
            case F_URL:     return percentDecode(s);
            case F_BASE64:
            case F_BASE64URL: return base64Decode(s);
            case F_BASE32:  return base32Decode(s);
            default: throw new IllegalArgumentException("不支持的格式：" + name(fmt));
        }
    }

    // ============================================================ 渲染：字节 → 格式

    public static String fromBytes(byte[] d, int fmt) {
        if (d == null) d = new byte[0];
        switch (fmt) {
            case F_TEXT:    return new String(d, utf8());
            case F_HEX:     return hexDump(d, " ", "", false);
            case F_HEX0X:   return hexDump(d, " ", "0x", false);
            case F_BIN:     return binDump(d);
            case F_DEC:     return decDump(d);
            case F_BYTES:   return hexDump(d, ",", "0x", false);
            case F_ESCAPE:  return escapeBytes(d);
            case F_UNICODE: return unicodeEscape(new String(d, utf8()));
            case F_HTML:    return encodeHtml(new String(d, utf8()));
            case F_JSON:    return encodeJson(new String(d, utf8()));
            case F_PUNYCODE:return punyEncodeDomain(new String(d, utf8()));
            case F_URL:     return percentEncode(d);
            case F_BASE64:  return base64Encode(d, false, true);
            case F_BASE64URL:return base64Encode(d, true, false);
            case F_BASE32:  return base32Encode(d);
            default: throw new IllegalArgumentException("不支持的格式：" + name(fmt));
        }
    }

    // ============================================================ HEX / BIN / DEC

    /** 吃掉空白、逗号、分号、冒号、短横线、{@code 0x}/{@code \x} 前缀，剩下的必须是偶数位十六进制 */
    private static byte[] parseHex(String s, boolean has0x) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '0' && i + 1 < s.length() && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                i++; // 跳过 0x 前缀
                continue;
            }
            if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                i++;
                continue;
            }
            if (hexVal(c) >= 0) sb.append(c);
            else if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ';'
                    || c == ':' || c == '-' || c == '_' || c == '|') continue;
            else throw new IllegalArgumentException("HEX 里有非法字符：" + c);
        }
        if (sb.length() % 2 == 1) {
            throw new IllegalArgumentException("HEX 位数是奇数（" + sb.length() + " 位），无法按字节切分");
        }
        byte[] out = new byte[sb.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((hexVal(sb.charAt(i * 2)) << 4) | hexVal(sb.charAt(i * 2 + 1)));
        }
        return out;
    }

    private static byte[] parseBin(String s) {
        String t = s.replace("0b", "").replace("0B", "").replace(" ", "")
                .replace("\n", "").replace("\r", "").replace("\t", "").replace("_", "");
        if (t.isEmpty()) return new byte[0];
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '0' && c != '1') throw new IllegalArgumentException("二进制里有非法字符：" + c);
        }
        int n = (t.length() + 7) / 8;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int start = i * 8, end = Math.min(start + 8, t.length());
            int v = 0;
            for (int k = start; k < end; k++) v = (v << 1) | (t.charAt(k) == '1' ? 1 : 0);
            // 末段不足 8 位时左对齐补齐（高位为 0）
            v <<= (8 - (end - start));
            out[i] = (byte) v;
        }
        return out;
    }

    private static byte[] parseDec(String s) {
        StringBuilder cur = new StringBuilder();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : ' ';
            if (c >= '0' && c <= '9') {
                cur.append(c);
            } else if (cur.length() > 0) {
                int v;
                try {
                    v = Integer.parseInt(cur.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("十进制数值过大：" + cur);
                }
                if (v < 0 || v > 255) throw new IllegalArgumentException("十进制字节必须在 0~255：" + v);
                bo.write(v);
                cur.setLength(0);
            } else if (!(c == ' ' || c == ',' || c == ';' || c == '\n' || c == '\r' || c == '\t')) {
                throw new IllegalArgumentException("十进制里有非法字符：" + c);
            }
        }
        return bo.toByteArray();
    }

    /** 字节数组：{@code 0x48,0x65} 与 {@code 72,101} 都吃；含 a-f 的 token 按十六进制 */
    private static byte[] parseBytes(String s) {
        String[] parts = s.split("[,;\\s]+");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            String t = p;
            boolean hex = false;
            if (t.startsWith("0x") || t.startsWith("0X")) {
                t = t.substring(2);
                hex = true;
            } else if (t.startsWith("\\x") || t.startsWith("\\X")) {
                t = t.substring(2);
                hex = true;
            }
            if (!hex) {
                for (int i = 0; i < t.length(); i++) {
                    char c = t.charAt(i);
                    if (hexVal(c) >= 0 && !(c >= '0' && c <= '9')) {
                        hex = true;
                        break;
                    }
                }
            }
            int v;
            if (hex) {
                for (int i = 0; i < t.length(); i++) {
                    if (hexVal(t.charAt(i)) < 0) throw new IllegalArgumentException("非法十六进制：" + p);
                }
                v = t.isEmpty() ? 0 : Integer.parseInt(t, 16);
            } else {
                try {
                    v = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("非法数值：" + p);
                }
            }
            if (v < 0 || v > 255) throw new IllegalArgumentException("字节越界（0~255）：" + p);
            bo.write(v);
        }
        return bo.toByteArray();
    }

    public static String hexDump(byte[] d, String sep, String prefix, boolean lower) {
        StringBuilder sb = new StringBuilder(d.length * (2 + sep.length() + prefix.length()));
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(prefix);
            String h = String.format(Locale.US, "%02X", d[i] & 0xFF);
            sb.append(lower ? h.toLowerCase(Locale.US) : h);
        }
        return sb.toString();
    }

    private static String binDump(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 9);
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(' ');
            int v = d[i] & 0xFF;
            for (int b = 7; b >= 0; b--) sb.append((v >> b) & 1);
        }
        return sb.toString();
    }

    private static String decDump(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 4);
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(d[i] & 0xFF);
        }
        return sb.toString();
    }

    // ============================================================ Unicode / 转义

    /** 支持反斜杠 + u + 4 位十六进制，也支持反斜杠 + U + 8 位（emoji）；未知转义原样保留 */
    public static String unescapeUnicode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'u' && i + 6 <= s.length()) {
                    String h = s.substring(i + 2, i + 6);
                    int v = hex4(h);
                    if (v < 0) throw new IllegalArgumentException("\\u 后面不是 4 位十六进制：" + h);
                    sb.append((char) v);
                    i += 5;
                    continue;
                }
                if (n == 'U' && i + 10 <= s.length()) {
                    String h = s.substring(i + 2, i + 10);
                    try {
                        int cp = Integer.parseInt(h, 16);
                        sb.appendCodePoint(cp);
                        i += 9;
                        continue;
                    } catch (Exception ignore) {
                        throw new IllegalArgumentException("\\U 后面不是 8 位十六进制：" + h);
                    }
                }
                if ("nrtbf0\\\"'/" .indexOf(n) >= 0 || n == 'n') {
                    sb.append(escChar(n));
                    i++;
                    continue;
                }
                if (n == 'x' && i + 4 <= s.length()) {
                    int v = hex2(s.substring(i + 2, i + 4));
                    if (v >= 0) {
                        sb.append((char) v);
                        i += 3;
                        continue;
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 每个 UTF-16 单元写成 u+XXXX 的转义形式（BMP 外的 emoji 由代理对自然拼出） */
    public static String unicodeEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 6);
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format(Locale.US, "\\u%04X", (int) s.charAt(i)));
        }
        return sb.toString();
    }

    private static char escChar(char n) {
        switch (n) {
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'b': return '\b';
            case 'f': return '\f';
            case '0': return '\0';
            default:  return n; // \\ \" \' \/
        }
    }

    /** 逐字节转义：可打印 ASCII 原样，其余 {@code \xNN}；反解析回落为 Latin-1（保证字节级往返） */
    public static String escapeBytes(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 4);
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            if (v == '\\') sb.append("\\\\");
            else if (v == '"') sb.append("\\\"");
            else if (v == '\n') sb.append("\\n");
            else if (v == '\r') sb.append("\\r");
            else if (v == '\t') sb.append("\\t");
            else if (v >= 0x20 && v < 0x7F) sb.append((char) v);
            else sb.append(String.format(Locale.US, "\\x%02X", v));
        }
        return sb.toString();
    }

    private static byte[] parseEscape(String s) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                // 非转义字符：<=0xFF 按 Latin-1 落 1 字节，更大的字符按 UTF-8 展开
                if (c <= 0xFF) bo.write(c);
                else {
                    byte[] u = String.valueOf(c).getBytes(utf8());
                    bo.write(u, 0, u.length);
                }
                continue;
            }
            if (i + 1 >= s.length()) { bo.write('\\'); break; }
            char n = s.charAt(++i);
            if (n == 'x' || n == 'X') {
                if (i + 2 >= s.length()) throw new IllegalArgumentException("\\x 后面缺少 2 位十六进制");
                int v = hex2(s.substring(i + 1, i + 3));
                if (v < 0) throw new IllegalArgumentException("\\x 后面不是 2 位十六进制");
                bo.write(v);
                i += 2;
            } else if (n == 'u') {
                if (i + 4 >= s.length()) throw new IllegalArgumentException("\\u 后面缺少 4 位十六进制");
                int v = hex4(s.substring(i + 1, i + 5));
                if (v < 0) throw new IllegalArgumentException("\\u 后面不是 4 位十六进制");
                byte[] u = String.valueOf((char) v).getBytes(utf8());
                bo.write(u, 0, u.length);
                i += 4;
            } else if (n == '0') {
                bo.write(0); // \0 之后的数字不当作八进制续写，避免 0123 这种歧义
            } else {
                bo.write(escChar(n));
            }
        }
        return bo.toByteArray();
    }

    // ============================================================ URL 百分号编码

    public static String percentEncode(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 3);
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z') || (v >= '0' && v <= '9')
                    || v == '-' || v == '_' || v == '.' || v == '~') {
                sb.append((char) v);
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", v));
            }
        }
        return sb.toString();
    }

    /** {@code +} 视作空格（表单/查询串习惯），{@code %2B} 才是真正的加号 */
    public static byte[] percentDecode(String s) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) throw new IllegalArgumentException("% 后面缺少 2 位十六进制");
                int v = hex2(s.substring(i + 1, i + 3));
                if (v < 0) throw new IllegalArgumentException("非法百分号编码：" + s.substring(i, Math.min(i + 3, s.length())));
                bo.write(v);
                i += 2;
            } else if (c == '+') {
                bo.write(' ');
            } else if (c <= 0xFF) {
                bo.write(c);
            } else {
                byte[] u = String.valueOf(c).getBytes(utf8());
                bo.write(u, 0, u.length);
            }
        }
        return bo.toByteArray();
    }

    // ============================================================ Base64 / Base32

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    public static String base64Encode(byte[] d, boolean urlSafe, boolean pad) {
        char[] t = urlSafe ? B64URL : B64;
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < d.length) {
            int v = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
            sb.append(t[(v >> 18) & 63]).append(t[(v >> 12) & 63])
              .append(t[(v >> 6) & 63]).append(t[v & 63]);
            i += 3;
        }
        int rest = d.length - i;
        if (rest == 1) {
            int v = (d[i] & 0xFF) << 16;
            sb.append(t[(v >> 18) & 63]).append(t[(v >> 12) & 63]);
            if (pad) sb.append("==");
        } else if (rest == 2) {
            int v = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8);
            sb.append(t[(v >> 18) & 63]).append(t[(v >> 12) & 63]).append(t[(v >> 6) & 63]);
            if (pad) sb.append('=');
        }
        return sb.toString();
    }

    /** 自动兼容标准/URL 安全字母表，自动补 '='，忽略空白与换行 */
    public static byte[] base64Decode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            if (c == '=') continue;
            sb.append(c == '-' ? '+' : (c == '_' ? '/' : c));
        }
        if (sb.length() % 4 == 1) throw new IllegalArgumentException("Base64 长度非法（余 1 位）");
        ByteArrayOutputStream bo = new ByteArrayOutputStream(sb.length() * 3 / 4 + 3);
        int buf = 0, bits = 0;
        for (int i = 0; i < sb.length(); i++) {
            int v = b64Val(sb.charAt(i));
            if (v < 0) throw new IllegalArgumentException("Base64 里有非法字符：" + sb.charAt(i));
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bo.write((buf >> bits) & 0xFF);
            }
        }
        return bo.toByteArray();
    }

    private static int b64Val(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+' || c == '-') return 62;
        if (c == '/' || c == '_') return 63;
        return -1;
    }

    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    public static String base32Encode(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 4) / 5 * 8);
        int buf = 0, bits = 0;
        for (int i = 0; i < d.length; i++) {
            buf = (buf << 8) | (d[i] & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(B32[(buf >> bits) & 31]);
            }
        }
        if (bits > 0) sb.append(B32[(buf << (5 - bits)) & 31]);
        while (sb.length() % 8 != 0) sb.append('=');
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c == '=' || c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            int v = B32_INDEX(c);
            if (v < 0) throw new IllegalArgumentException("Base32 里有非法字符：" + s.charAt(i));
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bo.write((buf >> bits) & 0xFF);
            }
        }
        return bo.toByteArray();
    }

    private static int B32_INDEX(char c) {
        for (int i = 0; i < B32.length; i++) if (B32[i] == c) return i;
        return -1;
    }

    // ============================================================ HTML 实体

    private static final String[][] HTML_MAP = {
            {"&", "amp"}, {"<", "lt"}, {">", "gt"}, {"\"", "quot"}, {"'", "apos"},
            {"\u00A0", "nbsp"}, {"©", "copy"}, {"®", "reg"}, {"™", "trade"},
            {"€", "euro"}, {"£", "pound"}, {"¥", "yen"}, {"¢", "cent"},
            {"°", "deg"}, {"±", "plusmn"}, {"×", "times"}, {"÷", "divide"},
            {"µ", "micro"}, {"·", "middot"}, {"«", "laquo"}, {"»", "raquo"},
            {"—", "mdash"}, {"–", "ndash"}, {"…", "hellip"}, {"•", "bull"},
            {"†", "dagger"}, {"‰", "permil"}, {"¼", "frac14"}, {"½", "frac12"},
            {"¾", "frac34"}, {"²", "sup2"}, {"³", "sup3"}, {"§", "sect"}, {"¶", "para"},
            {"←", "larr"}, {"→", "rarr"}, {"↑", "uarr"}, {"↓", "darr"}, {"∞", "infin"},
    };

    public static String encodeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String named = htmlNameOf(c);
            if (named != null) {
                sb.append('&').append(named).append(';');
            } else if (c < 0x20 || c > 0x7E) {
                sb.append("&#").append((int) c).append(';');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String htmlNameOf(char c) {
        for (String[] e : HTML_MAP) if (e[0].charAt(0) == c) return e[1];
        return null;
    }

    public static String decodeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {   // 不是实体，原样输出
                sb.append(c);
                i++;
                continue;
            }
            String body = s.substring(i + 1, semi);
            String rep = null;
            if (body.startsWith("#x") || body.startsWith("#X")) {
                try {
                    rep = new String(Character.toChars(Integer.parseInt(body.substring(2), 16)));
                } catch (Exception ignore) {
                    rep = null;
                }
            } else if (body.startsWith("#")) {
                try {
                    rep = new String(Character.toChars(Integer.parseInt(body.substring(1))));
                } catch (Exception ignore) {
                    rep = null;
                }
            } else {
                for (String[] e : HTML_MAP) if (e[1].equals(body)) { rep = e[0]; break; }
            }
            if (rep == null) {
                sb.append(c);
                i++;
            } else {
                sb.append(rep);
                i = semi + 1;
            }
        }
        return sb.toString();
    }

    // ============================================================ JSON 字符串

    public static String encodeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04X", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String decodeJson(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1);
        }
        return unescapeUnicode(t.replace("\\/", "/"));
    }

    // ============================================================ Punycode（RFC 3492）

    private static final int PUNY_BASE = 36, PUNY_TMIN = 1, PUNY_TMAX = 26,
            PUNY_SKEW = 38, PUNY_DAMP = 700, PUNY_INITIAL_BIAS = 72, PUNY_INITIAL_N = 128;

    /** 整个域名编码：{@code 中文.com → xn--fiq228c.com}；纯 ASCII 段原样保留 */
    public static String punyEncodeDomain(String domain) {
        String d = normalizeDomain(domain);
        String[] labels = d.split("\\.", -1);
        StringBuilder sb = new StringBuilder(d.length() + 8);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(punyEncodeLabel(labels[i]));
        }
        return sb.toString();
    }

    /** 整个域名解码：{@code xn--fiq228c.com → 中文.com} */
    public static String punyDecodeDomain(String domain) {
        String d = normalizeDomain(domain);
        String[] labels = d.split("\\.", -1);
        StringBuilder sb = new StringBuilder(d.length() + 8);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(punyDecodeLabel(labels[i]));
        }
        return sb.toString();
    }

    private static String normalizeDomain(String s) {
        return s.trim().replace('。', '.').replace('．', '.').replace("\u3002", ".")
                .replaceAll("^[a-zA-Z]+://", "")      // http:// 前缀
                .replaceAll("/.*$", "");              // 路径
    }

    private static String punyEncodeLabel(String label) {
        if (label.isEmpty() || isAscii(label)) return label;
        StringBuilder out = new StringBuilder();
        int b = 0;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            if (cp < 0x80) { out.append((char) cp); b++; }
            i += Character.charCount(cp);
        }
        int h = b;
        if (b > 0) out.append('-');
        int n = PUNY_INITIAL_N, delta = 0, bias = PUNY_INITIAL_BIAS;
        int total = label.codePointCount(0, label.length());
        while (h < total) {
            int m = Integer.MAX_VALUE;
            for (int i = 0; i < label.length(); ) {
                int cp = label.codePointAt(i);
                if (cp >= n && cp < m) m = cp;
                i += Character.charCount(cp);
            }
            if (m == Integer.MAX_VALUE) break;
            if ((long) (m - n) * (h + 1) > Integer.MAX_VALUE - delta) {
                throw new IllegalArgumentException("域名过长，无法编码");
            }
            delta += (m - n) * (h + 1);
            n = m;
            for (int i = 0; i < label.length(); ) {
                int cp = label.codePointAt(i);
                i += Character.charCount(cp);
                if (cp < n) {
                    if (++delta == 0) throw new IllegalArgumentException("域名过长，无法编码");
                }
                if (cp == n) {
                    int q = delta;
                    for (int k = PUNY_BASE; ; k += PUNY_BASE) {
                        int t = k <= bias ? PUNY_TMIN : (k >= bias + PUNY_TMAX ? PUNY_TMAX : k - bias);
                        if (q < t) break;
                        out.append(punyDigit(t + (q - t) % (PUNY_BASE - t)));
                        q = (q - t) / (PUNY_BASE - t);
                    }
                    out.append(punyDigit(q));
                    bias = punyAdapt(delta, h + 1, h == b);
                    delta = 0;
                    h++;
                }
            }
            delta++;
            n++;
        }
        return "xn--" + out;
    }

    private static String punyDecodeLabel(String label) {
        if (!label.regionMatches(true, 0, "xn--", 0, 4)) return label;
        String in = label.substring(4);
        StringBuilder out = new StringBuilder();
        int dash = in.lastIndexOf('-');
        int pos = 0;
        if (dash > 0) {
            for (int i = 0; i < dash; i++) {
                char c = in.charAt(i);
                if (c >= 0x80) throw new IllegalArgumentException("Punycode 基础段含非 ASCII：" + label);
                out.append(c);
            }
            pos = dash + 1;
        } else if (dash == 0) {
            pos = 1;
        }
        int n = PUNY_INITIAL_N, i = 0, bias = PUNY_INITIAL_BIAS;
        while (pos < in.length()) {
            int oldi = i, w = 1;
            for (int k = PUNY_BASE; ; k += PUNY_BASE) {
                if (pos >= in.length()) throw new IllegalArgumentException("Punycode 截断：" + label);
                int digit = punyDigitVal(in.charAt(pos++));
                if (digit < 0) throw new IllegalArgumentException("Punycode 非法字符：" + label);
                if (digit > (Integer.MAX_VALUE - i) / w) throw new IllegalArgumentException("Punycode 溢出：" + label);
                i += digit * w;
                int t = k <= bias ? PUNY_TMIN : (k >= bias + PUNY_TMAX ? PUNY_TMAX : k - bias);
                if (digit < t) break;
                if (w > Integer.MAX_VALUE / (PUNY_BASE - t)) throw new IllegalArgumentException("Punycode 溢出：" + label);
                w *= (PUNY_BASE - t);
            }
            // RFC 3492 里的 out.length() 是「码点数」，不是 Java String 的 UTF-16 长度。
            // 用 length() 时，标签里一旦有两个以上增补平面字符（emoji 等），
            // len 偏大 → i % len 与插入位置全错，解出来的域名是乱的。
            int len = out.codePointCount(0, out.length()) + 1;
            bias = punyAdapt(i - oldi, len, oldi == 0);
            if (i / len > Integer.MAX_VALUE - n) throw new IllegalArgumentException("Punycode 溢出：" + label);
            n += i / len;
            i %= len;
            if (n > 0x10FFFF) throw new IllegalArgumentException("Punycode 码点越界：" + label);
            out.insert(out.offsetByCodePoints(0, i), new String(Character.toChars(n)));
            i++;
        }
        return out.toString();
    }

    private static int punyAdapt(int delta, int numPoints, boolean first) {
        delta = first ? delta / PUNY_DAMP : delta / 2;
        delta += delta / numPoints;
        int k = 0;
        while (delta > ((PUNY_BASE - PUNY_TMIN) * PUNY_TMAX) / 2) {
            delta /= PUNY_BASE - PUNY_TMIN;
            k += PUNY_BASE;
        }
        return k + (PUNY_BASE - PUNY_TMIN + 1) * delta / (delta + PUNY_SKEW);
    }

    private static char punyDigit(int d) {
        return (char) (d < 26 ? ('a' + d) : ('0' + d - 26));
    }

    private static int punyDigitVal(char c) {
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= '0' && c <= '9') return c - '0' + 26;
        return -1;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x80) return false;
        return true;
    }

    // ============================================================ 小工具

    public static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** 2 位十六进制（百分号编码用） */
    private static int hex2(String s) {
        if (s.length() != 2) return -1;
        int a = hexVal(s.charAt(0)), b = hexVal(s.charAt(1));
        if (a < 0 || b < 0) return -1;
        return (a << 4) | b;
    }

    private static int hex4(String s) {
        if (s.length() != 4) return -1;
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = hexVal(s.charAt(i));
            if (d < 0) return -1;
            v = (v << 4) | d;
        }
        return v;
    }

    private static java.nio.charset.Charset utf8() {
        return java.nio.charset.Charset.forName("UTF-8");
    }
}
