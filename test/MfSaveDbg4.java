import com.hex91.file.AxmlFile;
import com.hex91.file.AxmlWriter;
import com.hex91.file.ApkRepack;
import com.hex91.file.ApkSignerTool;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MfSaveDbg4 {
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

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        byte[] data = readZipEntry(apk, "AndroidManifest.xml");
        AxmlFile axml = AxmlFile.parse(data);
        System.out.println("元素=" + axml.elements.size() + " 池=" + axml.strings.size()
                + " poolUtf8=" + axml.poolUtf8);

        int tested = 0, failed = 0;
        for (AxmlFile.XElem e : axml.elements) {
            for (AxmlFile.XAttr a : e.attrs) {
                String disp = axml.attrDisplayName(a);
                String cur = axml.valueDisplay(a);
                String newVal;
                if (a.isString()) {
                    if (cur == null || cur.isEmpty()) newVal = "ZZtest";
                    else newVal = cur + "ZZ";
                } else {
                    switch (a.dataType) {
                        case 0x12: /* ref */ newVal = "@0x7f010001"; break;
                        case 0x1b: /* bool */ newVal = (a.dataValue == 0) ? "true" : "false"; break;
                        case 0x11: /* hex */ newVal = "0x1234"; break;
                        case 0x1c: /* color */ newVal = "#112233"; break;
                        case 0x10: /* dec int */ newVal = "12345"; break;
                        case 0x1a: /* float */ newVal = "3.5"; break;
                        default: newVal = "1"; break;
                    }
                }
                tested++;
                // 跳过若新旧值相同
                if (newVal.equals(cur)) { continue; }
                try {
                    List<AxmlWriter.Edit> list = new ArrayList<AxmlWriter.Edit>();
                    list.add(new AxmlWriter.Edit(a, newVal));
                    byte[] newAxml = AxmlWriter.write(axml, list);
                    AxmlFile check = AxmlFile.parse(newAxml);
                    int ei = axml.elements.indexOf(a.owner);
                    int ai = axml.elements.get(ei).attrs.indexOf(a);
                    AxmlFile.XAttr fresh = check.elements.get(ei).attrs.get(ai);
                    String got = check.valueDisplay(fresh);
                    if (!newVal.equals(got)) {
                        failed++;
                        System.out.println("  [FAIL-VERIFY] " + disp + " type=0x"
                                + Integer.toHexString(a.dataType) + " cur=" + cur
                                + " -> new=" + newVal + " got=" + got);
                    }
                } catch (Throwable t) {
                    failed++;
                    System.out.println("  [FAIL-EXCEPTION] " + disp + " type=0x"
                            + Integer.toHexString(a.dataType) + " cur=" + cur
                            + " -> new=" + newVal + "  " + t.getClass().getSimpleName() + ": "
                            + t.getMessage());
                }
            }
        }
        System.out.println("共测试 " + tested + " 个属性, 失败 " + failed + " 个");
    }
}
