import com.hex91.file.AxmlFile;
import com.hex91.file.AxmlWriter;
import com.hex91.file.ApkRepack;
import com.hex91.file.ApkSignerTool;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MfSaveDbg3 {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        String editAttr = args.length > 1 ? args[1] : "android:versionCode";
        String newVal = args.length > 2 ? args[2] : "99";
        File p12 = new File(args.length > 3 ? args[3] : "HexFile.p12");

        System.out.println("=== 输入 APK: " + apk + " ===");
        byte[] data = readZipEntry(apk, "AndroidManifest.xml");
        if (data == null) { System.out.println("APK 内无 AndroidManifest.xml"); return; }
        System.out.println("manifest 字节数=" + data.length);

        AxmlFile axml = AxmlFile.parse(data);
        System.out.println("解析元素数=" + axml.elements.size() + " 字符串池=" + axml.strings.size()
                + " poolUtf8=" + axml.poolUtf8);

        AxmlFile.XAttr target = null;
        for (AxmlFile.XElem e : axml.elements)
            for (AxmlFile.XAttr a : e.attrs)
                if (editAttr.equals(axml.attrDisplayName(a))) { target = a; }
        if (target == null) { System.out.println("未找到属性 " + editAttr); return; }
        System.out.println("找到 " + editAttr + " | 类型=" + axml.typeName(target)
                + " 0x" + Integer.toHexString(target.dataType)
                + " | 当前值=" + axml.valueDisplay(target)
                + " | owner.depth=" + target.owner.depth
                + " | rawValueIdx=" + target.rawValueIdx
                + " | dataValue=" + target.dataValue);

        List<AxmlWriter.Edit> list = new ArrayList<AxmlWriter.Edit>();
        list.add(new AxmlWriter.Edit(target, newVal));

        System.out.println("\n[1] AxmlWriter.write ...");
        byte[] newAxml = AxmlWriter.write(axml, list);
        System.out.println("    写回成功, 新 AXML 字节=" + newAxml.length);

        System.out.println("[2] 回读校验（doSave 逻辑）...");
        AxmlFile check = AxmlFile.parse(newAxml);
        int ei = axml.elements.indexOf(target.owner);
        int ai = axml.elements.get(ei).attrs.indexOf(target);
        System.out.println("    ei=" + ei + " ai=" + ai);
        AxmlFile.XAttr fresh = check.elements.get(ei).attrs.get(ai);
        String got = check.valueDisplay(fresh);
        System.out.println("    回读值=" + got + " 期望=" + newVal);
        if (!newVal.equals(got))
            throw new IllegalStateException("回读校验失败: " + editAttr + " 期望 " + newVal + " 实际 " + got);

        System.out.println("[3] ApkRepack.repack ...");
        File unsigned = new File("mfpp_unsigned.apk");
        Map<String, byte[]> replace = new HashMap<String, byte[]>();
        replace.put("AndroidManifest.xml", newAxml);
        ApkRepack.repack(apk, replace, unsigned);
        System.out.println("    repack 成功, 未签名=" + unsigned.length() + " 字节");

        System.out.println("[4] ApkSignerTool.sign (v1+v2+v3) ...");
        File outApk = new File("mfpp_out.apk");
        if (outApk.exists()) outApk.delete();
        try (InputStream ks = new FileInputStream(p12)) {
            ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, unsigned, outApk, 21);
        }
        System.out.println("    签名成功, 产物=" + outApk.length() + " 字节");
        System.out.println("\n=== 全部 OK ===");
    }

    static byte[] readZipEntry(File zip, String entryName) throws IOException {
        ZipFile zf = new ZipFile(zip);
        try {
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) return null;
            InputStream in = zf.getInputStream(ze);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally { zf.close(); }
    }
}
