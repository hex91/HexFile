import com.hex91.file.ApkRepack;
import com.hex91.file.ApkSignerTool;
import com.hex91.file.AxmlFile;
import com.hex91.file.AxmlTextSync;
import com.hex91.file.AxmlWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 真实 APK 全链路复现（v1.64.4 排查「AndroidManifest.xml XML 编辑保存后 APK 无法解析」）：
 *   1. 从真实 APK 提取 AndroidManifest.xml（二进制 AXML）
 *   2. prettyXml → 模拟用户编辑 versionName（字符串池追加路径）→ AxmlTextSync.sync（真实保存路径）
 *   3. 校验新 AXML：文件 size %4、字符串池 chunk size %4、后续 chunk 起始偏移 %4
 *   4. ApkRepack 写回真实 APK → aapt2 dump badging / xmltree 独立验证
 *   5. 签名 → apksigner verify
 * 用法: java AxmlRealChainTest <真实apk>
 */
public class AxmlRealChainTest {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String info) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name + (ok ? "" : "  << " + info));
        if (ok) passed++; else failed++;
    }

    public static void main(String[] args) throws Exception {
        // 支持目录模式：遍历目录下所有 apk，逐个跑链路（遇 Throwable 记录继续）
        File dir = new File(args.length > 0 ? args[0] : "../apk_samples");
        if (dir.isDirectory()) {
            File[] apks = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".apk"));
            java.util.Arrays.sort(apks);
            int limit = args.length > 1 ? Integer.parseInt(args[1]) : apks.length;
            int bad = 0;
            for (int i = 0; i < Math.min(limit, apks.length); i++) {
                System.out.println("==== " + apks[i].getName());
                try {
                    one(apks[i]);
                } catch (Throwable t) {
                    bad++;
                    System.out.println("  [EXC] " + t.getClass().getSimpleName() + ": "
                            + String.valueOf(t.getMessage()).replace('\n', ' '));
                }
            }
            System.out.println("==== 完成: " + (Math.min(limit, apks.length) - bad) + " ok, " + bad + " 异常");
            return;
        }
        one(new File(args[0]));
    }

    static void one(File apk) throws Exception {
        File tmp = new File("test/axml_real_tmp");
        rmR(tmp); tmp.mkdirs();
        System.out.println("样本: " + apk.getName() + " (" + (apk.length() / 1024) + " KB)");

        // ---- 1. 提取 manifest ----
        byte[] mfBytes;
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry ze = zf.getEntry("AndroidManifest.xml");
            if (ze == null) throw new IllegalStateException("无 AndroidManifest.xml");
            mfBytes = readAll(zf.getInputStream(ze));
        }
        AxmlFile f = AxmlFile.parse(mfBytes);
        System.out.println("AXML 解析 OK: " + f.elements.size() + " 元素, " + f.strings.size() + " 池串");
        String pretty = f.prettyXml();

        // ---- 2. 模拟用户编辑 versionName（字符串池追加）----
        AxmlFile.XElem man = null;
        for (AxmlFile.XElem e : f.elements) if ("manifest".equals(e.name)) { man = e; break; }
        AxmlFile.XAttr verName = null, verCode = null;
        for (AxmlFile.XAttr a : man.attrs) {
            if ("versionName".equals(a.name)) verName = a;
            if ("versionCode".equals(a.name)) verCode = a;
        }
        String oldV = f.valueDisplay(verName);
        String newV = oldV + "_X9";
        String editedPretty = replaceAttrLine(pretty, "versionName", newV);
        check("pretty 文本可回解析", AxmlTextSync.parse(editedPretty).size() == f.elements.size(), "");

        byte[] newAxml = AxmlTextSync.sync(f, editedPretty);
        check("sync 产出", newAxml != null && newAxml.length > 0, "");
        AxmlFile f2 = AxmlFile.parse(newAxml);
        for (AxmlFile.XAttr a : f2.elements.get(0).attrs)
            if ("versionName".equals(a.name)) check("versionName 已改", newV.equals(f2.valueDisplay(a)), f2.valueDisplay(a));

        // ---- 3. 二进制结构校验：chunk 4 字节对齐 ----
        check("AXML 文件 size %4 == 0", newAxml.length % 4 == 0, "len=" + newAxml.length);
        int poolSize = u32(newAxml, 8 + 4);           // 池 chunk 在文件 offset 8
        check("字符串池 chunk size %4 == 0", poolSize % 4 == 0, "poolSize=" + poolSize);
        int pos = 8 + poolSize;
        boolean chunkAlign = true; String bad = "";
        while (pos + 8 <= newAxml.length) {
            if (pos % 4 != 0) { chunkAlign = false; bad = "chunk @" + pos; break; }
            int sz = u32(newAxml, pos + 4);
            if (sz < 8) break;
            pos += sz;
        }
        check("后续所有 chunk 起始 4 对齐", chunkAlign, bad);

        // ---- 4. 真实 APK repack → aapt2 独立验证 ----
        File unsigned = new File(tmp, "real_unsigned.apk");
        Map<String, byte[]> replace = new HashMap<String, byte[]>();
        replace.put("AndroidManifest.xml", newAxml);
        ApkRepack.repack(apk, replace, unsigned);
        System.out.println("repack 产出: " + unsigned.length() + " bytes");

        String bt = "E:/repo/android/Sdk/build-tools/37.0.0";
        int rc = proc(new String[]{bt + "/aapt2.exe", "dump", "badging", unsigned.getAbsolutePath()}, "aapt2-badging");
        check("aapt2 dump badging 退出码 0", rc == 0, "rc=" + rc);
        rc = proc(new String[]{bt + "/aapt2.exe", "dump", "xmltree", "--file", "AndroidManifest.xml",
                unsigned.getAbsolutePath()}, "aapt2-xmltree");
        check("aapt2 dump xmltree 退出码 0", rc == 0, "rc=" + rc);

        // ---- 5. 签名 + apksigner verify ----
        File signed = new File(tmp, "real_signed.apk");
        ApkSignerTool.sign(new FileInputStream("assets/HexFile.p12"), ApkSignerTool.KEY_PASSWORD,
                unsigned, signed, 21);
        rc = proc(new String[]{bt + "/apksigner.bat", "verify", "--min-sdk-version", "21",
                "--print-certs", signed.getAbsolutePath()}, "apksigner");
        check("apksigner verify 退出码 0", rc == 0, "rc=" + rc);

        System.out.println();
        System.out.println("AxmlRealChainTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- utils ----
    static String replaceAttrLine(String pretty, String attrName, String newVal) {
        String[] lines = pretty.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean done = false;
        for (String ln : lines) {
            if (!done && ln.contains(attrName + "=\"")) {
                int q1 = ln.indexOf(attrName + "=\"") + attrName.length() + 2;
                int q2 = ln.indexOf('"', q1);
                ln = ln.substring(0, q1) + newVal + ln.substring(q2);
                done = true;
            }
            sb.append(ln).append('\n');
        }
        return sb.toString();
    }

    static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    static int proc(String[] cmd, String tag) throws Exception {
        System.out.println("  >> " + tag + ": " + cmd[0].replaceFirst(".*/", "") + " ...");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192]; int n;
        boolean head = true;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (head) { System.out.println("     " + new String(buf, 0, Math.min(n, 300)).split("\n")[0]); head = false; }
        }
        in.close();
        int rc = p.waitFor();
        if (rc != 0) System.out.println("     输出尾部:\n     " + new String(bo.toByteArray()).substring(Math.max(0, bo.size() - 800)));
        return rc;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static void rmR(File f) {
        if (!f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) rmR(c);
        f.delete();
    }
}
