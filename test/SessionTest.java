import com.hex91.file.ApkEditSession;
import com.hex91.file.ApkRepack;

import java.io.*;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v1.66.0 统一修改模型测试（桌面）：
 *  - putEdit → <名>_edit.apk 生成、<名>_bak.apk 保留原版、原包不动
 *  - 二次 putEdit 叠加；putAdd（含 .so）→ 条目存在；putRemove → 条目移除
 *  - 无修改时不生成文件（未调用则无产物，隐式验证）
 *  - 产物可被 aapt2 解析（外部验证脚本跑）
 */
public class SessionTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        File work = new File("test/session_tmp");
        rm(work); work.mkdirs();
        File src = new File(args[0]);
        File apk = new File(work, "myapp.apk");
        copy(src, apk);
        byte[] origAll = readAll(apk);
        String base = "myapp";

        // 1) 初始状态：无修改 → 无产物
        chk("1.1 无修改无 _edit", !new File(work, base + "_edit.apk").exists());
        chk("1.2 无修改无 _bak", !new File(work, base + "_bak.apk").exists());
        chk("1.3 hasEdits=false", !ApkEditSession.hasEdits(apk));

        // 2) 修改 manifest → _edit/_bak 生成，原包不动
        byte[] manifest = zipEntryBytes(apk, "AndroidManifest.xml");
        byte[] manifest2 = Arrays.copyOf(manifest, manifest.length); // 同内容（保证可解析）
        File e1 = ApkEditSession.putEdit(null, apk, "AndroidManifest.xml", manifest2);
        chk("2.1 _edit.apk 生成", e1.exists() && e1.getName().equals("myapp_edit.apk"));
        File bak = new File(work, base + "_bak.apk");
        chk("2.2 _bak.apk 生成", bak.exists());
        chk("2.3 _bak=原版", Arrays.equals(readAll(bak), origAll));
        chk("2.4 原包不动", Arrays.equals(readAll(apk), origAll));
        chk("2.5 _edit 内含新 manifest",
                Arrays.equals(zipEntryBytes(e1, "AndroidManifest.xml"), manifest2));
        chk("2.6 needsSignPrompt=true", ApkEditSession.needsSignPrompt(apk));
        // v1.66.1：签名弹窗目标必须是 _edit.apk（此前 bug 签到了原包）
        File signTarget = ApkEditSession.editApkOf(apk);
        chk("2.7 签名目标=_edit.apk",
                signTarget.equals(e1) && !signTarget.equals(apk));

        // 3) 再改一个条目 → 叠加（_edit 同时包含两处修改）
        byte[] dex = zipEntryBytes(apk, "classes.dex");
        byte[] dex2 = new byte[dex.length]; System.arraycopy(dex, 0, dex2, 0, dex.length);
        ApkEditSession.putEdit(null, apk, "classes.dex", dex2);
        File e2 = new File(work, base + "_edit.apk");
        chk("3.1 manifest 仍在", Arrays.equals(zipEntryBytes(e2, "AndroidManifest.xml"), manifest2));
        chk("3.2 classes.dex 已替换", Arrays.equals(zipEntryBytes(e2, "classes.dex"), dex2));
        chk("3.3 editBytes 命中", ApkEditSession.editBytes(apk, "classes.dex") != null);

        // 4) 新增 .so（lib/assets 加文件）→ STORED
        File so = new File(work, "libtest.so");
        FileOutputStream fo = new FileOutputStream(so);
        fo.write(new byte[300]); fo.close();
        ApkEditSession.putAdd(null, apk, "lib/arm64-v8a/libtest.so", so);
        ZipEntry ze = findEntry(e2, "lib/arm64-v8a/libtest.so");
        chk("4.1 .so 条目存在", ze != null);
        chk("4.2 .so 为 STORED", ze != null && ze.getMethod() == ZipEntry.STORED);

        // 5) 删除条目 → 移除
        ApkEditSession.putRemove(null, apk, java.util.Collections.singletonList("lib/arm64-v8a/libtest.so"));
        chk("5.1 删除后条目不存在", findEntry(e2, "lib/arm64-v8a/libtest.so") == null);
        chk("5.2 其他修改保留", zipEntryBytes(e2, "classes.dex") != null);

        // 6) overlayPending：编辑器重开取最新
        File target = new File(work, "cache_manifest");
        boolean ov = ApkEditSession.overlayPending(apk, "AndroidManifest.xml", target);
        chk("6.1 overlay 命中", ov && Arrays.equals(readAll(target), manifest2));

        // 7) 清理会话（不删产物）
        ApkEditSession.clear(apk);
        chk("7.1 清理后 hasEdits=false", !ApkEditSession.hasEdits(apk));

        // 8) 签名 _edit.apk（模拟统一弹窗签名后的原地覆盖）→ 对齐保持
        File signed = new File(work, "signed_out.apk");
        InputStream ks = new FileInputStream(args[1]);
        com.hex91.file.ApkSignerTool.sign(ks, com.hex91.file.ApkSignerTool.KEY_PASSWORD, e2, signed, 21);
        ks.close();
        chk("8.1 签名产物生成", signed.exists() && signed.length() > 0);

        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    static ZipEntry findEntry(File apk, String name) throws IOException {
        ZipFile zf = new ZipFile(apk);
        try { return zf.getEntry(name); } finally { zf.close(); }
    }

    static byte[] zipEntryBytes(File apk, String name) throws IOException {
        ZipFile zf = new ZipFile(apk);
        try {
            ZipEntry ze = zf.getEntry(name);
            if (ze == null) return null;
            InputStream in = zf.getInputStream(ze);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return bo.toByteArray();
        } finally { zf.close(); }
    }

    static void chk(String name, boolean ok) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name);
        if (ok) pass++; else fail++;
    }

    static void rm(File f) {
        if (f.isDirectory()) for (File k : f.listFiles()) rm(k);
        f.delete();
    }

    static void copy(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close(); out.close();
    }

    static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
