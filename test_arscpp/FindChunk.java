import java.io.*;

public class FindChunk {
    public static void main(String[] a) throws Exception {
        FileInputStream in = new FileInputStream(a[0]);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        byte[] d = bo.toByteArray();
        int wantSize = Integer.parseInt(a[1]);
        for (int p = 12; p + 8 <= d.length; p++) {
            int type = (d[p] & 0xFF) | ((d[p+1] & 0xFF) << 8);
            int size = (d[p+4] & 0xFF) | ((d[p+5] & 0xFF) << 8) | ((d[p+6] & 0xFF) << 16) | ((d[p+7] & 0xFF) << 24);
            if (type == 0x0201 && size == wantSize) {
                int hdr = (d[p+2] & 0xFF) | ((d[p+3] & 0xFF) << 8);
                int cnt = (d[p+12] & 0xFF) | ((d[p+13] & 0xFF) << 8) | ((d[p+14] & 0xFF) << 16) | ((d[p+15] & 0xFF) << 24);
                int es = (d[p+16] & 0xFF) | ((d[p+17] & 0xFF) << 8) | ((d[p+18] & 0xFF) << 16) | ((d[p+19] & 0xFF) << 24);
                System.out.println("0x0201 @0x" + Integer.toHexString(p) + " id=" + (d[p+8]&0xFF)
                        + " hdr=" + hdr + " count=" + cnt + " entriesStart=" + es + " size=" + size);
            }
        }
    }
}
