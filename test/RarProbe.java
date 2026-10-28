import java.io.*;
import java.util.*;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

public class RarProbe {
    public static void main(String[] a) throws Exception {
        File f = new File(a[0]);
        System.out.println("=== probe: " + f + " size=" + f.length());
        com.github.junrar.Archive ar = null;
        try {
            ar = new com.github.junrar.Archive(f);
            List<FileHeader> hs = ar.getFileHeaders();
            System.out.println("entries=" + hs.size());
            for (FileHeader h : hs) {
                System.out.println("  name=[" + h.getFileNameString() + "] dir=" + h.isDirectory() + " size=" + h.getUnpSize());
                if (!h.isDirectory()) {
                    InputStream in = ar.getInputStream(h);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    System.out.println("    content=" + bo.size() + "B");
                    in.close();
                }
            }
            System.out.println("=== READ OK ===");
        } catch (Throwable t) {
            System.out.println("=== FAILED: " + t);
            t.printStackTrace();
        } finally {
            if (ar != null) try { ar.close(); } catch (Throwable t) {}
        }
        System.exit(0);
    }
}
