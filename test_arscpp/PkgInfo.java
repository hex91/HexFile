import java.util.*;
import java.util.zip.*;
import java.io.*;
import jadx.core.xmlgen.entry.ResourceEntry;

public class PkgInfo {
    public static void main(String[] a) throws Exception {
        byte[] arsc = extractArsc(a[0]);
        com.hex91.file.ArscFile f = com.hex91.file.ArscFile.parse(arsc);
        System.out.println("appPackage=" + f.appPackage() + " pkgCount=" + f.packages.size());
        for (jadx.core.xmlgen.ResTableBinaryParser.PkgSpan p : f.packages) {
            System.out.println("  pkg id=" + p.id + " name=" + p.name + " @" + p.start + " hdr=" + p.headerSize);
        }
        List<ResourceEntry> ss = com.hex91.file.ArscPlus.entriesOf(f, "string", "");
        for (int i = 0; i < Math.min(3, ss.size()); i++) {
            ResourceEntry e = ss.get(i);
            System.out.println("string[" + i + "] pkg=" + e.getPkgName() + " key=" + e.getKeyName()
                    + " id=0x" + Integer.toHexString(e.getId()));
        }
    }
    static com.hex91.file.ArscFile ArxscTmpP(byte[] d) throws Exception { return com.hex91.file.ArscFile.parse(d); }
    static java.util.List<ResourceEntry> ArxscTmpE(com.hex91.file.ArscFile f, String t, String c) {
        return com.hex91.file.ArscPlus.entriesOf(f, t, c);
    }
    static byte[] extractArsc(String apk) throws Exception {
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.getName().equals("resources.arsc")) {
                    InputStream in = zf.getInputStream(ze);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    return bo.toByteArray();
                }
            }
        } finally { zf.close(); }
        throw new IllegalStateException("no arsc");
    }
}
