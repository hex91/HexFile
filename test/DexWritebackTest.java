import com.hex91.file.ApkRepack;
import com.hex91.file.ApkSignerTool;
import com.hex91.file.SmaliTool;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * DEX 编辑回写闭环测试（v1.10）：
 *   构造 mini APK（classes.dex + classes2.dex + 附加条目）
 *   → baksmali classes.dex → smali → 汇编回 dex
 *   → ApkRepack(replace classes.dex, remove classes2.dex) → 签名
 *   → apksigner 校验 v1+v2+v3 → 产物内 classes.dex 可被 dexlib2 加载且 classes2.dex 已移除
 *   + dexMethodCount 守卫（合法 dex / 伪 dex）
 */
public class DexWritebackTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File tmp = new File("test/dwb_tmp");
        rmR(tmp); tmp.mkdirs();

        // ---- 1. 准备一个最小 smali 源，汇编成 dex ----
        File smaliDir = new File(tmp, "smali");
        new File(smaliDir, "com/test").mkdirs();
        write(".class public Lcom/test/Hello;\n"
                + ".super Ljava/lang/Object;\n\n"
                + ".method public constructor <init>()V\n"
                + "    .registers 1\n"
                + "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
                + "    return-void\n"
                + ".end method\n\n"
                + ".method public static getMessage()Ljava/lang/String;\n"
                + "    .registers 1\n"
                + "    const-string v0, \"DEX_WRITEBACK_V110\"\n"
                + "    return-object v0\n"
                + ".end method\n",
                new File(smaliDir, "com/test/Hello.smali"));
        File dex1 = new File(tmp, "classes.dex");
        String err = SmaliTool.assemble(smaliDir, dex1, 2);
        check("汇编 mini smali → dex", err == null, err == null ? "" : err);
        int mcnt = SmaliTool.dexMethodCount(dex1);
        check("dexMethodCount 合法 dex > 0", mcnt > 0, "mcnt=" + mcnt);

        File fakeDex = new File(tmp, "fake.dex");
        write(fakeFake(fakeDex).getBytes("UTF-8"), fakeDex);
        check("dexMethodCount 伪 dex = -1", SmaliTool.dexMethodCount(fakeDex) == -1,
                String.valueOf(SmaliTool.dexMethodCount(fakeDex)));

        // ---- 2. 构造 mini APK：manifest(借用现成产物) + classes.dex + classes2.dex + assets/x.txt ----
        File mf = new File(tmp, "AndroidManifest.xml");
        extract(new File("out/MTClone-1.11.0.apk"), "AndroidManifest.xml", mf);
        File miniApk = new File(tmp, "mini.apk");
        try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(miniApk))) {
            put(zo, "AndroidManifest.xml", readAll(mf));
            put(zo, "classes.dex", readAll(dex1));
            put(zo, "classes2.dex", "OLD_CLASSES2".getBytes("UTF-8"));
            put(zo, "assets/x.txt", "hello".getBytes("UTF-8"));
        }

        // ---- 3. baksmali → smali → 汇编（模拟用户编辑后回编译） ----
        File smaliOut = new File(tmp, "baksmali_out");
        err = SmaliTool.decompile(dex1, smaliOut, 2);
        check("baksmali 反编译", err == null, err == null ? "" : err);
        File dex2 = new File(tmp, "classes_new.dex");
        err = SmaliTool.assemble(smaliOut, dex2, 2);
        check("smali 回编译", err == null, err == null ? "" : err);

        // ---- 4. 写回：replace classes.dex，remove classes2.dex ----
        Map<String, byte[]> replace = new HashMap<String, byte[]>();
        replace.put("classes.dex", readAll(dex2));
        Set<String> remove = new HashSet<String>();
        remove.add("classes2.dex");
        File unsigned = new File(tmp, "unsigned.apk");
        ApkRepack.repack(miniApk, replace, remove, unsigned);

        // ---- 5. 签名 ----
        File signed = new File(tmp, "mini_signed.apk");
        ApkSignerTool.sign(new FileInputStream("assets/HexFile.p12"), ApkSignerTool.KEY_PASSWORD,
                unsigned, signed, 21);
        check("签名产出", signed.exists() && signed.length() > 0, "size=" + signed.length());

        // ---- 6. 校验（签名方案由外部 apksigner CLI 验证，见测试脚本） ----

        ZipFile zf = new ZipFile(signed);
        check("产物含 classes.dex", zf.getEntry("classes.dex") != null, "");
        check("classes2.dex 已移除", zf.getEntry("classes2.dex") == null, "");
        check("附加条目保留", zf.getEntry("assets/x.txt") != null, "");
        zf.close();
        // 从 signed 中提取 classes.dex 并加载验证
        File outDex = new File(tmp, "from_signed.dex");
        extract(signed, "classes.dex", outDex);
        DexBackedDexFile loaded = DexFileFactory.loadDexFile(outDex, Opcodes.forApi(34));
        boolean found = false;
        for (org.jf.dexlib2.iface.ClassDef cd : loaded.getClasses())
            if (cd.getType().equals("Lcom/test/Hello;")) found = true;
        check("产物 dex 可被 dexlib2 加载且含 Hello 类", found, "");

        // ---- 7. 单 dex 替换（无删除）兼容旧 API ----
        Map<String, byte[]> replace2 = new HashMap<String, byte[]>();
        replace2.put("classes.dex", readAll(dex2));
        File unsigned2 = new File(tmp, "unsigned2.apk");
        ApkRepack.repack(miniApk, replace2, unsigned2);
        ZipFile zf2 = new ZipFile(unsigned2);
        boolean keep2 = zf2.getEntry("classes2.dex") != null;
        zf2.close();
        check("旧 API（无 remove）保留 classes2.dex", keep2, "");

        System.out.println();
        System.out.println("DexWritebackTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);

    }

    static String findKeystore() {
        for (String p : new String[]{"out/HexFile.p12", "src/bin/HexFile/assets", "."}) {
            File dir = new File(p);
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".p12")) return f.getPath();
        }
        throw new RuntimeException("keystore not found");
    }

    static String fakeFake(File f) { return "NOT_A_DEX_FILE_AT_ALL_PADDING_PADDING"; }

    static void extract(File zip, String name, File out) throws Exception {
        ZipFile zf = new ZipFile(zip);
        InputStream in = zf.getInputStream(zf.getEntry(name));
        OutputStream fo = new FileOutputStream(out);
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) fo.write(b, 0, n);
        fo.close(); in.close(); zf.close();
    }

    static void put(ZipOutputStream zo, String name, byte[] data) throws Exception {
        zo.putNextEntry(new ZipEntry(name));
        zo.write(data);
        zo.closeEntry();
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536]; int n;
        while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
        fi.close(); bo.close();
        return bo.toByteArray();
    }

    static void write(byte[] data, File f) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data); fo.close();
    }

    static void write(String s, File f) throws Exception { write(s.getBytes("UTF-8"), f); }

    static void rmR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) rmR(c);
        f.delete();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  [PASS] " + name); }
        else { failed++; System.out.println("  [FAIL] " + name + "  " + detail); }
    }
}
