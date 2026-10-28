import com.hex91.file.AxmlFile;
import com.hex91.file.AxmlWriter;
import com.hex91.file.ApkRepack;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MfSaveDbg6 {
    static byte[] readZipEntry(File zip, String n) throws IOException {
        ZipFile zf = new ZipFile(zip);
        try {
            ZipEntry ze = zf.getEntry(n);
            if (ze == null) return null;
            InputStream in = zf.getInputStream(ze);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            in.close();
            return bo.toByteArray();
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

        // 复现 doSave 的设备侧路径：缓存目录下的【子目录】/unsigned.apk
        File cacheDir = new File(System.getProperty("java.io.tmpdir"), "mfpp_test_cache");
        File unsigned = new File(cacheDir, "mfpp_MTClone-1.11.1/unsigned.apk");
        System.out.println("目标文件(父目录尚未创建): " + unsigned);
        System.out.println("父目录是否存在: " + unsigned.getParentFile().exists());
        Map<String,byte[]> replace = new HashMap<String,byte[]>();
        replace.put("AndroidManifest.xml", newAxml);
        try {
            ApkRepack.repack(apk, replace, unsigned);
            System.out.println("repack 成功（意外）");
        } catch (Throwable t) {
            System.out.println(">>> 抛异常: " + t.getClass().getName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            while (c != null) { System.out.println("    caused by: " + c.getClass().getName() + ": " + c.getMessage()); c = c.getCause(); }
        }
    }
}
