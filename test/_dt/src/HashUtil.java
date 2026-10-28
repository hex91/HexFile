
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * 文件哈希工具（v1.17.0 对比工具集）：
 *  - MD5 / SHA-1 / SHA-256 / CRC32 四种算法，流式计算（64KB 缓冲，大文件不进内存）
 *  - 摘要输出小写 HEX；CRC32 输出 8 位大写 HEX（与 MT/常见工具一致）
 *  - hashAll() 一次算出全部算法，供「哈希对比」模式使用
 */
public final class HashUtil {

    public static final String[] ALGS = {"MD5", "SHA-1", "SHA-256", "CRC32"};

    private HashUtil() {}

    /** 计算单个文件哈希；失败返回 null。v1.44.0：File API 打不开时回退 su cat 流式（root 区） */
    public static String hash(File f, String alg) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            return hash(in, alg);
        } catch (Exception e) {
            Process p = null;
            try {
                p = RootIO.catProcess(f);   // 非 root 内部抛异常 → 返回 null
                String r = hash(p.getInputStream(), alg);
                p.waitFor();
                return r;
            } catch (Exception e2) {
                return null;
            } finally {
                if (p != null) p.destroy();
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** 流式哈希（不关闭传入流） */
    public static String hash(InputStream in, String alg) {
        try {
            byte[] buf = new byte[65536];
            if ("CRC32".equals(alg)) {
                CRC32 crc = new CRC32();
                int n;
                while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
                return String.format(Locale.US, "%08X", crc.getValue());
            }
            MessageDigest md = MessageDigest.getInstance(alg);
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 一次算出全部算法（保持 ALGS 顺序） */
    public static Map<String, String> hashAll(File f) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String alg : ALGS) out.put(alg, hash(f, alg));
        return out;
    }

    /** 展示用短哈希（前 12 位 + …） */
    public static String brief(String h) {
        if (h == null) return "(失败)";
        return h.length() > 12 ? h.substring(0, 12) + "…" : h;
    }
}
