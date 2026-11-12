package com.hex91.file.net;

/**
 * HLS 分片加密信息（{@code #EXT-X-KEY}）—— v1.92.0，纯 Java。
 *
 * <p>下载合并必须先把这件事理清，否则下下来的切片全是乱码，而「乱码」的表现是
 * 转 MP4 时报「解析失败」，很容易误判成转封装代码有 bug。</p>
 *
 * <p>三个真实的坑：</p>
 * <ol>
 *   <li><b>IV 不给就自己算</b>：规范规定 IV 缺省时取「媒体序列号」的 16 字节大端表示。
 *       大量站点的 m3u8 只写 {@code METHOD=AES-128,URI="key"}，IV 全靠这个默认规则。
 *       如果这里给全零 IV，解密出来前 16 字节正确、后面全是垃圾 —— AAC 解码出的杂音就是这么来的。</li>
 *   <li><b>序列号要加 MEDIA-SEQUENCE</b>：{@code #EXT-X-MEDIA-SEQUENCE} 不为 0 时，
 *       第 i 片的 IV 是 {@code mediaSequence + i}，不是 i。</li>
 *   <li><b>METHOD=NONE 是「取消加密」而不是无效行</b>：有的列表前几片加密、后面明文，
 *       必须把这个状态记到分片粒度（见 {@link HlsPlaylist.Segment#key}），不能全文一个 key。</li>
 * </ol>
 */
public final class HlsKey {

    public static final String METHOD_NONE = "NONE";
    public static final String METHOD_AES_128 = "AES-128";
    /** Apple 的 DRM 方案：只有流内样本加密，需要设备密钥，本工程不做 */
    public static final String METHOD_SAMPLE_AES = "SAMPLE-AES";
    /** FairPlay，绝对解不了 */
    public static final String METHOD_SAMPLE_AES_CTR = "SAMPLE-AES-CTR";

    /** 明文（不加密） */
    public static final HlsKey NONE = new HlsKey(METHOD_NONE, null, null);

    public final String method;
    /** 绝对地址；METHOD=NONE 时为 null */
    public final String uri;
    /** 原始 IV 串（含 0x 前缀），未声明为 null */
    public final String ivHex;

    HlsKey(String method, String uri, String ivHex) {
        this.method = method;
        this.uri = uri;
        this.ivHex = ivHex;
    }

    public boolean isNone() {
        return METHOD_NONE.equals(method);
    }

    /** AES-128 整段加密 —— 唯一能在 App 内自行解开的方案 */
    public boolean isAes128() {
        return METHOD_AES_128.equals(method);
    }

    /** 能自己解密（AES-128）；SAMPLE-AES 之类需要 DRM，返回 false */
    public boolean supported() {
        return isNone() || isAes128();
    }

    /** 有加密但不能自行解密时的原因（给用户看的人话），能处理返回 null */
    public String unsupportedReason() {
        if (supported()) return null;
        if (METHOD_SAMPLE_AES.equals(method) || METHOD_SAMPLE_AES_CTR.equals(method)) {
            return "分片使用 " + method + " 加密（Apple DRM 体系），需要设备密钥，无法在应用内解密";
        }
        return "分片使用 " + method + " 加密，本应用只支持 AES-128";
    }

    /**
     * 取本片解密用的 IV（16 字节）。
     *
     * @param sequence 该分片的媒体序列号（已含 MEDIA-SEQUENCE 基数）
     */
    public byte[] ivFor(long sequence) {
        byte[] explicit = parseIv(ivHex);
        if (explicit != null) return explicit;
        byte[] iv = new byte[16];
        long v = sequence;
        // 大端写入低 8 字节，高 8 字节保持 0：规范要求的就是「序列号作为 128 位整数」
        for (int i = 15; i >= 8 && v != 0; i--) {
            iv[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return iv;
    }

    /** 解析 {@code 0x...} 形式的 IV；长度不足 32 个十六进制位时左补 0 */
    public static byte[] parseIv(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        // 有的站点写成 0X0123 带引号已被 attrValue 去掉，这里再兜一层
        h = h.replace("\"", "").trim();
        if (h.isEmpty()) return null;
        if (h.length() > 32) h = h.substring(h.length() - 32);
        byte[] out = new byte[16];
        int pad = 32 - h.length();
        for (int i = 0; i < 16; i++) {
            int hi = nibble(h, i * 2 - pad);
            int lo = nibble(h, i * 2 + 1 - pad);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int nibble(String h, int idx) {
        if (idx < 0 || idx >= h.length()) return 0;
        char c = h.charAt(idx);
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return 0;
    }

    /**
     * 解析一行 {@code #EXT-X-KEY:} 的属性部分。
     *
     * @param attrs   冒号后的属性串（不是整行）
     * @param baseUrl m3u8 自身地址，用于把 KEY 的相对 URI 补成绝对地址
     */
    public static HlsKey parse(String attrs, String baseUrl) {
        if (attrs == null) return NONE;
        String m = M3u8Playlist.attrValue(attrs, "METHOD");
        if (m == null || m.isEmpty()) return NONE;
        m = m.toUpperCase();
        if (METHOD_NONE.equals(m)) return NONE;
        String uri = M3u8Playlist.attrValue(attrs, "URI");
        String abs = uri == null ? null : M3u8Playlist.resolve(baseUrl, uri);
        String iv = M3u8Playlist.attrValue(attrs, "IV");
        return new HlsKey(m, abs, iv);
    }

    /** 两把 key 是否等价（同 URL + 同 IV 才能复用解密结果 / 合并请求） */
    public boolean sameAs(HlsKey o) {
        if (o == null) return false;
        if (!method.equals(o.method)) return false;
        if (uri == null ? o.uri != null : !uri.equals(o.uri)) return false;
        return ivHex == null ? o.ivHex == null : ivHex.equals(o.ivHex);
    }

    /** 界面展示用：AES-128 / 未加密 / 其他方案名 */
    public String label() {
        if (isNone()) return "未加密";
        return method;
    }

    @Override
    public String toString() {
        return label() + (uri == null ? "" : " (" + uri + ")");
    }
}
