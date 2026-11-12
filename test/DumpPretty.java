import com.hex91.file.apk.AxmlFile;
import com.hex91.file.apk.AxmlTextSync;
import java.io.*;
import java.util.zip.*;
public class DumpPretty {
    public static void main(String[] a) throws Exception {
        ZipFile zf = new ZipFile(new File(a[0]));
        ZipEntry ze = zf.getEntry("AndroidManifest.xml");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = zf.getInputStream(ze);
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        AxmlFile f = AxmlFile.parse(bo.toByteArray());
        String[] lines = f.prettyXml().split("\n", -1);
        for (int i = 0; i < Math.min(14, lines.length); i++)
            System.out.printf("%2d| %s%n", i + 1, lines[i].replace("\r", "\\r"));
        System.out.println("---- parse keys ----");
        java.util.List<AxmlTextSync.ParsedElem> ps = AxmlTextSync.parse(f.prettyXml());
        for (String[] pa : ps.get(0).attrs) System.out.println("KEY=[" + pa[0] + "] V=[" + pa[1] + "]");
        System.out.println("---- orig keys ----");
        for (AxmlFile.XAttr oa : f.elements.get(0).attrs)
            System.out.println("ORIG=[" + AxmlFile.escLine(f.attrDisplayName(oa)).replace("\r","\\r") + "] V=[" + f.valueDisplay(oa) + "]");
    }
}
