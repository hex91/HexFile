import com.hex91.file.ArscDefs;
import com.hex91.file.ArscFile;
import com.hex91.file.ArscWriter;
import com.hex91.file.ArscWriter.Edit;
import com.hex91.file.ApkRepack;
import com.hex91.file.ApkSignerTool;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * 全链路桌面测试（jadx 引擎）：arsc 编辑 → 重打包 → 重签名 → 回读校验
 * 用法: SignTest [源apk路径]
 */
public class SignTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "out/MTClone-1.9.1.apk";
        File apk = new File(src);
        System.out.println("源: " + apk + " (" + apk.length() + "B)");

        // 1. 提取并解析 arsc，找第一个非空字符串条目
        byte[] arscOrig = extract(apk, "resources.arsc");
        ArscFile a = ArscFile.parse(arscOrig);
        ResourceEntry target = null;
        for (ResourceEntry e : a.storage.getResources()) {
            RawValue v = e.getSimpleValue();
            if (v != null && v.getDataType() == ArscDefs.TYPE_STRING) {
                String s = a.globalPool.get(v.getData());
                if (s != null && !s.isEmpty()) {
                    target = e;
                    break;
                }
            }
        }
        check("找到字符串条目", target != null);
        if (target == null) {
            return;
        }
        String newVal = "SIGNTEST_" + System.currentTimeMillis();
        String keyName = target.getKeyName();
        System.out.println("编辑 " + keyName + " -> " + newVal);
        List<Edit> edits = new ArrayList<Edit>();
        edits.add(new Edit(target, newVal));
        byte[] arscNew = ArscWriter.write(a, edits);
        write(new File("test/sign_edited.arsc"), arscNew);

        // 2. 重打包（替换 resources.arsc）
        File unsigned = new File("test/sign_unsigned.apk");
        Map<String, byte[]> replace = new HashMap<String, byte[]>();
        replace.put("resources.arsc", arscNew);
        ApkRepack.repack(apk, replace, unsigned);
        System.out.println("重打包: " + unsigned.length() + "B");
        check("重打包产物非空", unsigned.length() > 0);

        // 3. 重签名（minSdk 21 → v1+v2+v3）
        File signed = new File("test/sign_signed.apk");
        ApkSignerTool.sign(readAll(new File("HexFile.keystore")),
                ApkSignerTool.KEY_PASSWORD, unsigned, signed, 21);
        System.out.println("签名: " + signed.length() + "B");

        // 4. 回读：arsc 编辑应生效、旧签名应已剥离、新签名文件应在
        byte[] arscBack = extract(signed, "resources.arsc");
        write(new File("test/sign_back.arsc"), arscBack);
        ArscFile b = ArscFile.parse(arscBack);
        boolean found = false;
        for (ResourceEntry e : b.storage.getResources()) {
            RawValue v = e.getSimpleValue();
            if (v == null || v.getDataType() != ArscDefs.TYPE_STRING) {
                continue;
            }
            if (keyName.equals(e.getKeyName()) && newVal.equals(b.globalPool.get(v.getData()))) {
                found = true;
            }
        }
        check("签名后 arsc 编辑仍生效", found);
        check("旧签名文件已剥离", !hasEntry(signed, "META-INF/MTCLONE.SF")
                && !hasEntry(signed, "META-INF/MTCLONE.RSA"));
        check("新 v1 签名文件存在", hasEntry(signed, "META-INF/MT_CLONE.SF")
                && hasEntry(signed, "META-INF/MT_CLONE.RSA")
                && hasEntry(signed, "META-INF/MANIFEST.MF"));
        System.out.println("\n==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static byte[] extract(File zip, String entry) throws Exception {
        ZipFile zf = new ZipFile(zip);
        ZipEntry ze = zf.getEntry(entry);
        if (ze == null) {
            throw new FileNotFoundException(entry);
        }
        InputStream in = zf.getInputStream(ze);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
        }
        in.close();
        zf.close();
        return bo.toByteArray();
    }

    static boolean hasEntry(File zip, String entry) throws Exception {
        ZipFile zf = new ZipFile(zip);
        boolean r = zf.getEntry(entry) != null;
        zf.close();
        return r;
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = fi.read(b)) > 0) {
            bo.write(b, 0, n);
        }
        fi.close();
        return bo.toByteArray();
    }

    static void write(File f, byte[] data) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data);
        fo.close();
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
