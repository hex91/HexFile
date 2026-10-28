import com.hex91.file.AxmlFile;
import com.hex91.file.AxmlWriter;
import com.hex91.file.ArscDefs;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MfSaveDbg5 {
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

    static String typeName(int t) {
        switch (t) {
            case ArscDefs.TYPE_REFERENCE: return "ref";
            case ArscDefs.TYPE_ATTRIBUTE: return "attr";
            case ArscDefs.TYPE_STRING: return "string";
            case ArscDefs.TYPE_FLOAT: return "float";
            case ArscDefs.TYPE_INT_DEC: return "int";
            case ArscDefs.TYPE_INT_HEX: return "hex";
            case ArscDefs.TYPE_INT_BOOLEAN: return "bool";
            case ArscDefs.TYPE_INT_COLOR_ARGB8:
            case ArscDefs.TYPE_INT_COLOR_RGB8:
            case ArscDefs.TYPE_INT_COLOR_ARGB4:
            case ArscDefs.TYPE_INT_COLOR_RGB4: return "color";
            case ArscDefs.TYPE_NULL: return "null";
            default: return "0x" + Integer.toHexString(t);
        }
    }

    static String correctNewValue(AxmlFile f, AxmlFile.XAttr a) {
        switch (a.dataType) {
            case ArscDefs.TYPE_STRING: {
                String cur = f.valueDisplay(a);
                return (cur == null || cur.isEmpty()) ? "ZZtest" : cur + "ZZ";
            }
            case ArscDefs.TYPE_REFERENCE: return "@0x7f0a0002";
            case ArscDefs.TYPE_ATTRIBUTE: return "@0x7f010001";
            case ArscDefs.TYPE_INT_DEC: return "12345";
            case ArscDefs.TYPE_INT_HEX: return "0x1234";
            case ArscDefs.TYPE_INT_BOOLEAN: return a.dataValue == 0 ? "true" : "false";
            case ArscDefs.TYPE_FLOAT: return "3.5";
            case ArscDefs.TYPE_INT_COLOR_ARGB8:
            case ArscDefs.TYPE_INT_COLOR_RGB8:
            case ArscDefs.TYPE_INT_COLOR_ARGB4:
            case ArscDefs.TYPE_INT_COLOR_RGB4: return "#112233";
            default: return null;
        }
    }

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        byte[] data = readZipEntry(apk, "AndroidManifest.xml");
        if (data == null) { System.out.println("无 manifest"); return; }
        AxmlFile axml = AxmlFile.parse(data);
        System.out.println("[APK " + apk.getName() + "] poolUtf8=" + axml.poolUtf8
                + " 元素=" + axml.elements.size() + " 池=" + axml.strings.size());

        int tested = 0, failed = 0;
        for (AxmlFile.XElem e : axml.elements) {
            for (AxmlFile.XAttr a : e.attrs) {
                String newVal = correctNewValue(axml, a);
                if (newVal == null) continue;
                String cur = axml.valueDisplay(a);
                if (newVal.equals(cur)) continue;
                tested++;
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
                        System.out.println("  [FAIL-VERIFY] " + axml.attrDisplayName(a)
                                + " (" + typeName(a.dataType) + ") cur=" + cur
                                + " -> " + newVal + " got=" + got);
                    }
                } catch (Throwable t) {
                    failed++;
                    System.out.println("  [FAIL-EXCEPTION] " + axml.attrDisplayName(a)
                            + " (" + typeName(a.dataType) + ") cur=" + cur
                            + " -> " + newVal + "  " + t.getClass().getSimpleName() + ": "
                            + t.getMessage());
                }
            }
        }
        System.out.println("  测试 " + tested + " 处, 失败 " + failed + "\n");
    }
}
