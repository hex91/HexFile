import java.io.*;

public class ChunkCheck {
    public static void main(String[] a) throws Exception {
        byte[] d = readAll(a[0]);
        int p = Integer.decode(a[1]);
        int hdr = u16(d, p+2);
        int cnt = u32(d, p+12);
        int es = u32(d, p+16);
        int size = u32(d, p+4);
        int present = 0, bad = 0, noEntry = 0;
        for (int i = 0; i < cnt; i++) {
            int off = u32(d, p + hdr + i*4);
            if (off == 0xFFFFFFFF) { noEntry++; continue; }
            present++;
            int ep = p + es + off;
            if (ep + 16 > p + size) { System.out.println("  id=" + i + " OOB off=" + off); bad++; continue; }
            int eSz = u16(d, ep);
            int eFl = u16(d, ep+2);
            if (eSz != 8) { System.out.println("  id=" + i + " entrySize=" + eSz + " (expect 8) flags=0x" + Integer.toHexString(eFl)); bad++; continue; }
            int vSz = u16(d, ep+8);
            if (vSz != 8) { System.out.println("  id=" + i + " valSize=" + vSz + " (expect 8) @abs 0x" + Integer.toHexString(ep)); bad++; }
        }
        System.out.println("chunk @0x" + Integer.toHexString(p) + " hdr=" + hdr + " count=" + cnt
                + " entriesStart=" + es + " size=" + size + " present=" + present + " noEntry=" + noEntry + " bad=" + bad);
        // entries 区是否连续 16 字节步进
        int prev = -16;
        int breaks = 0;
        for (int i = 0; i < cnt; i++) {
            int off = u32(d, p + hdr + i*4);
            if (off == 0xFFFFFFFF) continue;
            if (off != prev + 16) breaks++;
            prev = off;
        }
        System.out.println("non-contiguous offsets: " + breaks);
    }
    static int u16(byte[] d, int p) { return (d[p]&0xFF) | ((d[p+1]&0xFF)<<8); }
    static int u32(byte[] d, int p) { return (d[p]&0xFF) | ((d[p+1]&0xFF)<<8) | ((d[p+2]&0xFF)<<16) | ((d[p+3]&0xFF)<<24); }
    static byte[] readAll(String f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
