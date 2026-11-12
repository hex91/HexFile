import com.hex91.file.core.ArscDefs;
import java.io.*;
import java.util.zip.*;
public class PoolProbe {
    public static void main(String[] a) throws Exception {
        ZipFile zf = new ZipFile(new File(a[0]));
        InputStream in = zf.getInputStream(zf.getEntry("AndroidManifest.xml"));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[1 << 20]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        byte[] raw = bo.toByteArray();
        System.out.println("manifest size=" + raw.length);
        int start = 8;
        int stringCount = ArscDefs.u32(raw, start + 8);
        int styleCount = ArscDefs.u32(raw, start + 12);
        int flags = ArscDefs.u32(raw, start + 16);
        int stringsStartField = ArscDefs.u32(raw, start + 20);
        int stylesStartField = ArscDefs.u32(raw, start + 24);
        int poolSize = ArscDefs.u32(raw, start + 4);
        int end = start + poolSize;
        System.out.println("poolSize=" + poolSize + " strings=" + stringCount + " styles=" + styleCount
                + " flags=0x" + Integer.toHexString(flags) + " stringsStart=" + stringsStartField
                + " stylesStart=" + stylesStartField);
        boolean utf8 = (flags & 0x100) != 0;
        long total = 0, maxW = 0;
        for (int i = 0; i < stringCount; i++) {
            int relOff = ArscDefs.u32(raw, start + 28 + i * 4);
            int sStart = start + stringsStartField + relOff;
            int sEnd;
            if (i + 1 < stringCount) {
                int nextRel = ArscDefs.u32(raw, start + 28 + (i + 1) * 4);
                sEnd = (nextRel > relOff) ? start + stringsStartField + nextRel
                        : scan(raw, sStart, utf8, end);
            } else {
                sEnd = (styleCount > 0 && stylesStartField > 0) ? start + stylesStartField
                        : scan(raw, sStart, utf8, end);
            }
            if (sStart < start + stringsStartField || sEnd > end) sEnd = sStart;
            long w = (long) sEnd - sStart;
            if (w > 0) { total += w; if (w > maxW) { maxW = w; if (maxW > 100000) System.out.println("  big write @" + i + ": " + w); } }
        }
        System.out.println("totalWrite=" + total + " maxWrite=" + maxW);
    }
    static int scan(byte[] raw, int from, boolean utf8, int limit) {
        int i = from; int end = Math.min(limit, raw.length);
        if (utf8) { while (i < end && raw[i] != 0) i++; return Math.min(i + 1, end); }
        while (i + 1 < end && (raw[i] != 0 || raw[i + 1] != 0)) i += 2;
        return Math.min(i + 2, end);
    }
}
