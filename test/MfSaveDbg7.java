import com.hex91.file.AxmlFile;
import com.hex91.file.AxmlWriter;
import com.hex91.file.ApkRepack;
import com.hex91.file.ApkSignerTool;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MfSaveDbg7 {
    static byte[] readZipEntry(File zip, String n) throws IOException {
        ZipFile zf = new ZipFile(zip);
        try {
            ZipEntry ze = zf.getEntry(n);
            if (ze == null) return null;
            InputStream in = zf.getInputStream(ze);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int k; while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            in.close(); return bo.toByteArray();
        } finally { zf.close(); }
    }
    public static void main(String[] a) throws Exception {
        File apk = new File(a[0]);
        byte[] data = readZipEntry(apk, "AndroidManifest.xml");
        AxmlFile axml = AxmlFile.parse(data);
        AxmlFile.XAttr vc = null;
        for (AxmlFile.XElem e : axml.elements)
            for (AxmlFile.XAttr at : e.attrs)
                if (axml.attrDisplayName(at).equals("android:versionCode")) vc = at;
        List<AxmlWriter.Edit> list = new ArrayList<AxmlWriter.Edit>();
        list.add(new AxmlWriter.Edit(vc, "99"));
        byte[] newAxml = AxmlWriter.write(axml, list);

        // 完全模拟 doSave：缓存目录/mfpp_<基名>/unsigned.apk + mkdirs（v1.11.2 修复点）
        File cacheDir = new File(System.getProperty("java.io.tmpdir"), "mfpp_dev_cache");
        String base = apk.getName().replaceAll("\\.[^.]+$", "");
        File unsigned = new File(cacheDir, "mfpp_" + base + "/unsigned.apk");
        File up = unsigned.getParentFile();
        if (up != null && !up.exists()) up.mkdirs();
        System.out.println("父目录已创建? " + up.exists());
        Map<String,byte[]> replace = new HashMap<String,byte[]>();
        replace.put("AndroidManifest.xml", newAxml);
        ApkRepack.repack(apk, replace, unsigned);
        System.out.println("repack OK, unsigned=" + unsigned.length());
        File outApk = new File("mfpp_dev_out.apk");
        if (outApk.exists()) outApk.delete();
        try (InputStream ks = new FileInputStream("HexFile.p12")) {
            ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, unsigned, outApk, 21);
        }
        System.out.println("sign OK, out=" + outApk.length());
        System.out.println("=== v1.11.2 修复验证：完整 doSave 链路成功 ===");
    }
}
