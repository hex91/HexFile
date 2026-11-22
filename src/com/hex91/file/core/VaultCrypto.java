package com.hex91.file.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 隐私空间加密内核（v2.18.0，F-22）。纯 JDK，不碰 Android，桌面可跑全流程。
 *
 * <h3>为什么要两把钥匙</h3>
 * <p>最省事的做法是「口令直接当文件密钥」（{@code FileCrypt} 就是这套）：PBKDF2 派生一次，
 * 拿结果去加密文件。但隐私空间有一个 {@code FileCrypt} 没有的需求——<b>改口令</b>。
 * 密钥就是口令派生出来的，改口令等于所有文件换密钥，几百个文件要全量重加密一遍，
 * 中途断电还会留下一半新一半旧的死局。</p>
 *
 * <p>所以这里拆成两层（和 LUKS / BitLocker 同一个思路）：</p>
 * <ul>
 *   <li><b>主密钥 vaultKey</b>：64 字节随机数，创建时生成一次，<b>永不改变</b>，所有文件都用它加密；</li>
 *   <li><b>口令</b>：只用来派生 KEK（key encryption key），KEK 把 vaultKey 包起来存进头部。
 *       改口令 = 用新 KEK 重新包一次同一把 vaultKey，只重写 157 字节的头部。</li>
 * </ul>
 *
 * <h3>三种容器</h3>
 * <pre>
 * 头部 header（vault.hdr，157 字节定长）
 *   "HFVAULT" 7  version 1  iterations 4(BE)  saltLen 1  salt 16
 *   ivK 16  wrapped 80(AES-256-CBC(KEKenc, vaultKey 64B))  tag 32
 *   tag = HMAC-SHA256(KEKmac, 前面全部字节)          ← 口令错 → tag 不符，一次比对即判
 *
 * 文件块 blob（b/&lt;id&gt;.bin）
 *   "HFVBLOB" 7  version 1  id 16  iv 16  ciphertext  tag 32
 *   tag = HMAC-SHA256(vaultKey.mac, magic|version|id|iv|ciphertext)
 *                                                        ← id 进 tag：把 A 的密文改名成 B 的
 *                                                          名字会验签失败，防止张冠李戴
 * 索引块 box（idx）
 *   "HFVBOX1" 7  version 1  iv 16  ciphertext  tag 32    ← 文件名也加密，索引里看不到明文
 * </pre>
 *
 * <p>全部流式（8KB 缓冲），任意大小文件不进内存；进度回调返回 {@code false} 即中止，
 * 抛 {@link Cancelled}，由调用方删掉半截产物。</p>
 *
 * <p><b>诚实说明</b>：这是 CBC + HMAC 的经典组合，不是 AEAD；同主密钥下每个文件用独立随机 IV，
 * 且 id 参与 MAC，所以「换 IV 重放」「块互换」都过不了验签。但 CBC 不提供「部分内容可验证」，
 * 解密要全量验签后再解（本实现的加密是一次写、解密是一遍读，验签在流式读取中同步累积，
 * 最后统一比对，因此解密开始前不会白写一个字节的明文到目标路径——
 * 代价是必须读两遍）。</p>
 */
public final class VaultCrypto {

    /** 进度回调。done/total 为字节；total &lt;= 0 表示总量未知。返回 false 表示中止。 */
    public interface Progress {
        boolean on(long done, long total);
    }

    /** 用户中止（与 IO 异常区分开：中止不是错误，不该弹「失败」） */
    public static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Cancelled() {
            super("cancelled");
        }
    }

    /**
     * 错误码：逻辑层只抛码 + ASCII 诊断串，文案统一由 ui/VaultReport 按码翻译。
     * 码名是稳定契约，改文案不动码。
     */
    public enum Code {
        MASTER_LEN, PASSWORD_EMPTY, SALT_EMPTY, ITER_RANGE,
        HEADER_LEN, HEADER_MAGIC, HEADER_ITER, HEADER_SALT_LEN, WRONG_PASSWORD, HEADER_BROKEN,
        ENC_SRC_MISSING, DEC_SRC_MISSING, NO_MASTER, FILE_LEN_BAD, FILE_DAMAGED,
        FILE_VERIFY_FAIL, BLOB_MAGIC, BLOB_VERSION, FILE_ID_MISMATCH,
        ID_MISSING, ID_NOT_HEX, ID_LEN_BAD,
        PLAIN_EMPTY, BOX_LEN_BAD, BOX_MAGIC, BOX_VERSION, BOX_VERIFY_FAIL,
        ID_FORMAT, INDEX_EMPTY, INDEX_MAGIC,
        DIR_NULL, PASSWORD_SHORT, ALREADY_EXISTS, MKDIR_FAIL, WRITE_FAIL,
        INDEX_WRITE_FAIL, ENTRY_MISSING, BLOB_MISSING, NAME_EMPTY,
        FILE_MISSING, FILE_TOO_BIG, READ_URI_FAIL
    }

    /** 带码异常的统一契约：ui 层按 code + arg 翻译 */
    public interface VaultCoded {
        Code code();
        String arg();
    }

    private static String diag(Code code, String arg) {
        return arg == null ? code.name() : code.name() + ": " + arg;
    }

    /** 参数/状态类错误（保留 IllegalArgumentException 类型） */
    public static final class Fail extends IllegalArgumentException implements VaultCoded {
        private static final long serialVersionUID = 1L;
        private final Code code;
        private final String arg;

        public Fail(Code code) { this(code, null); }

        public Fail(Code code, String arg) {
            super(diag(code, arg));
            this.code = code;
            this.arg = arg;
        }

        public Code code() { return code; }
        public String arg() { return arg; }
    }

    /** 口令/校验类错误（保留 SecurityException 类型：UI 与测试按类型分支） */
    public static final class AuthFail extends SecurityException implements VaultCoded {
        private static final long serialVersionUID = 1L;
        private final Code code;
        private final String arg;

        public AuthFail(Code code) { this(code, null); }

        public AuthFail(Code code, String arg) {
            super(diag(code, arg));
            this.code = code;
            this.arg = arg;
        }

        public Code code() { return code; }
        public String arg() { return arg; }
    }

    /** 状态冲突（保留 IllegalStateException 类型） */
    public static final class StateFail extends IllegalStateException implements VaultCoded {
        private static final long serialVersionUID = 1L;
        private final Code code;
        private final String arg;

        public StateFail(Code code) { this(code, null); }

        public StateFail(Code code, String arg) {
            super(diag(code, arg));
            this.code = code;
            this.arg = arg;
        }

        public Code code() { return code; }
        public String arg() { return arg; }
    }

    /** IO 类错误（保留 IOException 类型） */
    public static final class IoFail extends IOException implements VaultCoded {
        private static final long serialVersionUID = 1L;
        private final Code code;
        private final String arg;

        public IoFail(Code code) { this(code, null); }

        public IoFail(Code code, String arg) {
            super(diag(code, arg));
            this.code = code;
            this.arg = arg;
        }

        public Code code() { return code; }
        public String arg() { return arg; }
    }

    /** 主密钥：enc 用于加密，mac 用于认证。两者永不相同，避免一套密钥两种用途。 */
    public static final class Master {
        public final byte[] enc;
        public final byte[] mac;

        public Master(byte[] enc, byte[] mac) {
            if (enc == null || mac == null || enc.length != KEY_LEN || mac.length != KEY_LEN) {
                throw new Fail(Code.MASTER_LEN, String.valueOf(KEY_LEN));
            }
            this.enc = enc;
            this.mac = mac;
        }

        /**
         * 清零密钥（R-46）：锁定后 64 字节主密钥不该继续留在堆里等 GC。
         *
         * <p>GC 不保证覆写，能读到进程内存的途径（调试器、root 后的 heap dump、系统转储）
         * 都能拿到「已经锁了」的密钥 —— 那等于没锁。数组是 {@code final}，置空引用由
         * 调用方做（{@code master = null}），这里负责把内容抹掉。</p>
         */
        public void destroy() {
            Arrays.fill(enc, (byte) 0);
            Arrays.fill(mac, (byte) 0);
        }
    }

    public static final int ITERATIONS = 210_000;
    /** 迭代次数的合法区间：低于下限说明头部被改松了（暴力破解成本被削），高于上限是 DoS */
    public static final int MIN_ITER = 10_000;
    public static final int MAX_ITER = 2_000_000;

    private static final int SALT_LEN = 16;
    private static final int KEY_LEN = 32;
    private static final int TAG_LEN = 32;
    private static final int IV_LEN = 16;
    private static final int BUF = 8192;

    private static final int VERSION = 1;
    private static final byte[] M_HDR = ascii("HFVAULT");
    private static final byte[] M_BLOB = ascii("HFVBLOB");
    private static final byte[] M_BOX = ascii("HFVBOX1");

    /** 头部定长：7+1+4+1+16+16+80+32 */
    public static final int HEADER_LEN = 157;
    /** 文件块固定前缀：magic+version+id+iv */
    public static final int BLOB_HEAD = 7 + 1 + 16 + 16;
    /** 索引块固定前缀：magic+version+iv */
    public static final int BOX_HEAD = 7 + 1 + 16;

    private static final String AES = "AES";
    private static final String TRANSFORM = "AES/CBC/PKCS5Padding";
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String MAC_ALGO = "HmacSHA256";

    private VaultCrypto() {}

    // ------------------------------------------------------------------ 密钥派生

    public static byte[] newSalt() {
        byte[] s = new byte[SALT_LEN];
        new SecureRandom().nextBytes(s);
        return s;
    }

    /** PBKDF2-HMAC-SHA256(口令, salt, iter) → 64 字节（前 32 加密，后 32 认证） */
    public static Master derive(String password, byte[] salt, int iterations) throws Exception {
        if (password == null || password.isEmpty()) throw new Fail(Code.PASSWORD_EMPTY);
        if (salt == null || salt.length == 0) throw new Fail(Code.SALT_EMPTY);
        if (iterations < MIN_ITER || iterations > MAX_ITER) {
            throw new Fail(Code.ITER_RANGE);
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 64 * 8);
        SecretKeyFactory skf = SecretKeyFactory.getInstance(KDF);
        byte[] dk = skf.generateSecret(spec).getEncoded();
        try {
            return new Master(Arrays.copyOfRange(dk, 0, 32), Arrays.copyOfRange(dk, 32, 64));
        } finally {
            Arrays.fill(dk, (byte) 0);
        }
    }

    // ------------------------------------------------------------------ 头部

    /** 创建新头部（内部生成随机主密钥）。返回 8+1：header 与 master 一起给出。 */
    public static Header newHeader(String password) throws Exception {
        byte[] vaultKey = new byte[KEY_LEN * 2];
        try {
            new SecureRandom().nextBytes(vaultKey);
            Master m = new Master(Arrays.copyOfRange(vaultKey, 0, KEY_LEN),
                    Arrays.copyOfRange(vaultKey, KEY_LEN, KEY_LEN * 2));
            byte[] salt = newSalt();
            byte[] h = wrapHeader(m, password, salt, ITERATIONS);
            return new Header(h, m);
        } finally {
            // R-46：主密钥已经复制进 Master，这份中间缓冲不必留
            Arrays.fill(vaultKey, (byte) 0);
        }
    }

    /** {@link #newHeader} 的返回体：头部字节 + 主密钥 */
    public static final class Header {
        public final byte[] bytes;
        public final Master master;

        Header(byte[] bytes, Master master) {
            this.bytes = bytes;
            this.master = master;
        }
    }

    /** 用新口令重新包同一个主密钥（改口令只动这 157 字节） */
    public static byte[] rewrap(Master master, String newPassword) throws Exception {
        return wrapHeader(master, newPassword, newSalt(), ITERATIONS);
    }

    private static byte[] wrapHeader(Master m, String password, byte[] salt, int iter)
            throws Exception {
        Master kek = derive(password, salt, iter);
        byte[] vaultKey = null;
        try {
            byte[] ivK = new byte[IV_LEN];
            new SecureRandom().nextBytes(ivK);

            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek.enc, AES), new IvParameterSpec(ivK));
            vaultKey = concat(m.enc, m.mac);
            byte[] wrapped = c.doFinal(vaultKey);

            ByteArrayOutputStream bo = new ByteArrayOutputStream(HEADER_LEN);
            bo.write(M_HDR);
            bo.write(VERSION);
            writeIntBE(bo, iter);
            bo.write(salt.length);
            bo.write(salt);
            bo.write(ivK);
            bo.write(wrapped);
            byte[] body = bo.toByteArray();

            Mac mac = Mac.getInstance(MAC_ALGO);
            mac.init(new SecretKeySpec(kek.mac, MAC_ALGO));
            mac.update(body);
            byte[] tag = mac.doFinal();

            return concat(body, tag);
        } finally {
            // R-46：KEK 与拼接出的明文密钥都只在这一方法里需要，返回前清零
            if (vaultKey != null) Arrays.fill(vaultKey, (byte) 0);
            kek.destroy();
        }
    }

    /** 是否像本工具的头部（只看魔数，给「目录里已经有别的东西」这种场合用） */
    public static boolean isHeader(byte[] b) {
        if (b == null || b.length != HEADER_LEN) return false;
        for (int i = 0; i < M_HDR.length; i++) {
            if (b[i] != M_HDR[i]) return false;
        }
        return b[M_HDR.length] == VERSION;
    }

    /**
     * 用口令解头部，拿到主密钥。
     *
     * @throws SecurityException 口令不对（tag 不符）
     * @throws IllegalArgumentException 头部损坏/长度不对/迭代次数异常
     */
    public static Master openHeader(String password, byte[] header) throws Exception {
        if (header == null || header.length != HEADER_LEN) {
            throw new Fail(Code.HEADER_LEN, String.valueOf(HEADER_LEN));
        }
        if (!isHeader(header)) throw new Fail(Code.HEADER_MAGIC);
        int iter = readIntBE(header, M_HDR.length + 1);
        if (iter < MIN_ITER || iter > MAX_ITER) {
            throw new Fail(Code.HEADER_ITER, String.valueOf(iter));
        }
        int saltLen = header[M_HDR.length + 5] & 0xFF;
        if (saltLen < 8 || saltLen > 32) throw new Fail(Code.HEADER_SALT_LEN);
        int p = M_HDR.length + 6;
        byte[] salt = Arrays.copyOfRange(header, p, p + saltLen);
        p += saltLen;
        byte[] ivK = Arrays.copyOfRange(header, p, p + IV_LEN);
        p += IV_LEN;
        int wrappedLen = HEADER_LEN - TAG_LEN - p;
        byte[] wrapped = Arrays.copyOfRange(header, p, p + wrappedLen);
        byte[] tag = Arrays.copyOfRange(header, HEADER_LEN - TAG_LEN, HEADER_LEN);

        Master kek = derive(password, salt, iter);
        byte[] vaultKey = null;
        try {
            Mac mac = Mac.getInstance(MAC_ALGO);
            mac.init(new SecretKeySpec(kek.mac, MAC_ALGO));
            mac.update(header, 0, HEADER_LEN - TAG_LEN);
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) {
                throw new AuthFail(Code.WRONG_PASSWORD);
            }

            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek.enc, AES), new IvParameterSpec(ivK));
            vaultKey = c.doFinal(wrapped);
            if (vaultKey.length != KEY_LEN * 2) throw new Fail(Code.HEADER_BROKEN);
            return new Master(Arrays.copyOfRange(vaultKey, 0, KEY_LEN),
                    Arrays.copyOfRange(vaultKey, KEY_LEN, KEY_LEN * 2));
        } finally {
            // R-46：口令不对的路径同样会派生 KEK，异常分支不清理等于每次输错都留一份
            if (vaultKey != null) Arrays.fill(vaultKey, (byte) 0);
            kek.destroy();
        }
    }

    // ------------------------------------------------------------------ 文件块

    public static void encryptFile(File src, File dst, byte[] id) throws Exception {
        encryptFile(src, dst, id, null, null);
    }

    /** 加密一个文件到 dst。id 为 16 字节条目 id（进 MAC，防止密文被改名挪用）。返回明文字节数 */
    public static long encryptFile(File src, File dst, byte[] id, Master m, Progress p)
            throws Exception {
        if (src == null || !src.isFile()) throw new Fail(Code.ENC_SRC_MISSING);
        if (m == null) throw new Fail(Code.NO_MASTER);
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                return encryptStream(in, src.length(), out, id, m, p);
            } finally {
                closeQuiet(out);
            }
        } finally {
            closeQuiet(in);
        }
    }

    /** 流式加密。返回实际吃进的明文字节数（调用方拿它记大小，不必自己再数一遍） */
    public static long encryptStream(InputStream in, long total, OutputStream out, byte[] id,
            Master m, Progress p) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        byte[] blobId = ids(id);

        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(m.enc, AES), new IvParameterSpec(iv));
        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(m.mac, MAC_ALGO));

        ByteArrayOutputStream head = new ByteArrayOutputStream(BLOB_HEAD);
        head.write(M_BLOB);
        head.write(VERSION);
        head.write(blobId);
        head.write(iv);
        byte[] hb = head.toByteArray();
        out.write(hb);
        mac.update(hb);

        byte[] buf = new byte[BUF];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            byte[] enc = c.update(buf, 0, n);
            if (enc != null && enc.length > 0) {
                out.write(enc);
                mac.update(enc);
            }
            done += n;
            if (p != null && !p.on(done, total)) throw new Cancelled();
        }
        byte[] fin = c.doFinal();
        out.write(fin);
        mac.update(fin);
        out.write(mac.doFinal());
        out.flush();
        return done;
    }

    public static void decryptFile(File src, File dst, byte[] expectId) throws Exception {
        decryptFile(src, dst, expectId, null, null);
    }

    /**
     * 解密文件块。expectId 为 null 时不校验条目 id（游离文件恢复用）。
     *
     * <p>两遍读：第一遍只算 MAC，验过才写目标文件。这样口令/密钥不对或密文被改时，
     * 目标路径上不会留下一个「看起来正常但内容是垃圾」的半截文件。</p>
     */
    public static void decryptFile(File src, File dst, byte[] expectId, Master m, Progress p)
            throws Exception {
        if (src == null || !src.isFile()) throw new Fail(Code.DEC_SRC_MISSING);
        if (m == null) throw new Fail(Code.NO_MASTER);
        long total = src.length();

        // 第一遍：验签
        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(m.mac, MAC_ALGO));
        FileInputStream vf = new FileInputStream(src);
        try {
            byte[] head = new byte[BLOB_HEAD];
            readFully(vf, head);
            checkBlobHead(head, expectId);
            mac.update(head);
            long left = total - BLOB_HEAD - TAG_LEN;
            if (left < 0) throw new Fail(Code.FILE_LEN_BAD);
            byte[] buf = new byte[BUF];
            long done = 0;
            while (left > 0) {
                int n = vf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new Fail(Code.FILE_DAMAGED);
                mac.update(buf, 0, n);
                left -= n;
                done += n;
                if (p != null && !p.on(done / 2, total)) throw new Cancelled();
            }
            byte[] tag = new byte[TAG_LEN];
            if (readFully(vf, tag) != TAG_LEN) throw new Fail(Code.FILE_DAMAGED);
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) {
                throw new AuthFail(Code.FILE_VERIFY_FAIL);
            }
        } finally {
            closeQuiet(vf);
        }

        // 第二遍：解密
        FileInputStream in = new FileInputStream(src);
        try {
            byte[] head = new byte[BLOB_HEAD];
            readFully(in, head);
            byte[] iv = Arrays.copyOfRange(head, BLOB_HEAD - IV_LEN, BLOB_HEAD);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(m.enc, AES), new IvParameterSpec(iv));

            FileOutputStream out = new FileOutputStream(dst);
            try {
                long left = total - BLOB_HEAD - TAG_LEN, done = 0;
                byte[] buf = new byte[BUF];
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    byte[] dec = c.update(buf, 0, n);
                    if (dec != null && dec.length > 0) out.write(dec);
                    left -= n;
                    done += n;
                    if (p != null && !p.on(total / 2 + done / 2, total)) {
                        throw new Cancelled();
                    }
                }
                out.write(c.doFinal());
            } finally {
                closeQuiet(out);
            }
        } finally {
            closeQuiet(in);
        }
    }

    private static void checkBlobHead(byte[] head, byte[] expectId) {
        for (int i = 0; i < M_BLOB.length; i++) {
            if (head[i] != M_BLOB[i]) throw new Fail(Code.BLOB_MAGIC);
        }
        if (head[M_BLOB.length] != VERSION) {
            throw new Fail(Code.BLOB_VERSION, String.valueOf(head[M_BLOB.length]));
        }
        if (expectId == null) return;
        byte[] want = ids(expectId);
        for (int i = 0; i < 16; i++) {
            if (head[8 + i] != want[i]) throw new AuthFail(Code.FILE_ID_MISMATCH);
        }
    }

    /** 只读文件块头里的条目 id（不解密，遍历目录找游离文件用）。返回 16 字节或 null */
    public static byte[] blobId(File blob) {
        InputStream in = null;
        try {
            in = new FileInputStream(blob);
            byte[] head = new byte[BLOB_HEAD];
            if (readFully(in, head) != BLOB_HEAD) return null;
            for (int i = 0; i < M_BLOB.length; i++) {
                if (head[i] != M_BLOB[i]) return null;
            }
            return Arrays.copyOfRange(head, 8, 24);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuiet(in);
        }
    }

    /** id 归一化：允许 16 字节原始值或 32 位十六进制串；其它一律拒绝（防路径穿越） */
    static byte[] ids(byte[] id) {
        if (id == null) throw new Fail(Code.ID_MISSING);
        if (id.length == 16) return id;
        if (id.length == 32) {
            byte[] out = new byte[16];
            for (int i = 0; i < 16; i++) {
                int hi = hexVal(id[i * 2]);
                int lo = hexVal(id[i * 2 + 1]);
                if (hi < 0 || lo < 0) throw new Fail(Code.ID_NOT_HEX);
                out[i] = (byte) ((hi << 4) | lo);
            }
            return out;
        }
        throw new Fail(Code.ID_LEN_BAD);
    }

    private static int hexVal(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }

    // ------------------------------------------------------------------ 索引块

    /**
     * 加密任意小数据（索引）：自包含的 box，含随机 IV 与 MAC。
     *
     * <p>单独一套魔数而不是复用文件块格式：索引里没有任何 id 概念，
     * 硬塞 16 个零字节进文件名位置，日后看代码的人会以为那是个真 id。</p>
     */
    public static byte[] seal(byte[] plain, Master m) throws Exception {
        if (plain == null) throw new Fail(Code.PLAIN_EMPTY);
        if (m == null) throw new Fail(Code.NO_MASTER);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        ByteArrayOutputStream head = new ByteArrayOutputStream(BOX_HEAD);
        head.write(M_BOX);
        head.write(VERSION);
        head.write(iv);
        byte[] hb = head.toByteArray();

        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(m.enc, AES), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(plain);

        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(m.mac, MAC_ALGO));
        mac.update(hb);
        mac.update(ct);

        ByteArrayOutputStream out = new ByteArrayOutputStream(hb.length + ct.length + TAG_LEN);
        out.write(hb);
        out.write(ct);
        out.write(mac.doFinal());
        return out.toByteArray();
    }

    /** 解开 {@link #seal} 的产物 */
    public static byte[] open(byte[] box, Master m) throws Exception {
        if (box == null || box.length < BOX_HEAD + TAG_LEN) {
            throw new Fail(Code.BOX_LEN_BAD);
        }
        if (m == null) throw new Fail(Code.NO_MASTER);
        for (int i = 0; i < M_BOX.length; i++) {
            if (box[i] != M_BOX[i]) throw new Fail(Code.BOX_MAGIC);
        }
        if (box[M_BOX.length] != VERSION) throw new Fail(Code.BOX_VERSION);
        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(m.mac, MAC_ALGO));
        mac.update(box, 0, box.length - TAG_LEN);
        byte[] tag = Arrays.copyOfRange(box, box.length - TAG_LEN, box.length);
        if (!MessageDigest.isEqual(mac.doFinal(), tag)) {
            throw new AuthFail(Code.BOX_VERIFY_FAIL);
        }
        byte[] iv = Arrays.copyOfRange(box, M_BOX.length + 1, BOX_HEAD);
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(m.enc, AES), new IvParameterSpec(iv));
        return c.doFinal(box, BOX_HEAD, box.length - BOX_HEAD - TAG_LEN);
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 关闭失败不影响结果（数据已落盘，或已读到末尾），但也不留空 catch：
     * 空块会被 O-20 的空 catch 门计进基线，而这类收尾正是「基线越养越大」的主要来源。
     */
    static boolean closeQuiet(Closeable c) {
        if (c == null) return true;
        try {
            c.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] ascii(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void writeIntBE(OutputStream o, int v) throws IOException {
        o.write((v >>> 24) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }
}
