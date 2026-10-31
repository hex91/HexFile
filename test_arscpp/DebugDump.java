import java.io.*;
import java.util.*;
import java.util.zip.*;
import jadx.core.xmlgen.entry.ResourceEntry;

/** 调试：对比原始/编辑后 arsc 中 string 默认配置 type chunk 的结构 */
public class DebugDump {

    public static void main(String[] args) throws Exception {
        byte[] orig = extractArsc(args[0]);
        byte[] mod = args.length > 1 ? readFile(args[1]) : null;

        com.hex91.file.ArscFile f = com.hex91.file.ArscFile.parse(orig);
        // 找 string 默认配置的 jadx TypeChunk
        jadx.core.xmlgen.ResTableBinaryParser.TypeChunk target = null;
        for (ResourceEntry re : f.storage.getResources()) {
            if ("string".equals(re.getTypeName()) && "".equals(re.getConfig())) {
                target = f.spanOf(re).typeChunk;
                break;
            }
        }
        System.out.println("== ORIG string(default) chunk @0x" + Long.toHexString(target.start)
                + " .. 0x" + Long.toHexString(target.end));
        dumpChunk(orig, (int) target.start, (int) target.end, 5);
        System.out.println("jadx entries in map: " + target.entries.size());

        if (mod != null) {
            // 在新文件里重新定位（重新解析）
            com.hex91.file.ArscFile f2 = null;
            try {
                f2 = com.hex91.file.ArscFile.parse(mod);
            } catch (Exception e) {
                System.out.println("parse FAILED: " + e.getMessage());
            }
            if (f2 != null) {
                jadx.core.xmlgen.ResTableBinaryParser.TypeChunk t2 = null;
                for (ResourceEntry re : f2.storage.getResources()) {
                    if ("string".equals(re.getTypeName()) && "".equals(re.getConfig())) {
                        t2 = f2.spanOf(re).typeChunk;
                        break;
                    }
                }
                if (t2 != null) {
                    System.out.println("== MOD string(default) chunk @0x" + Long.toHexString(t2.start)
                            + " .. 0x" + Long.toHexString(t2.end));
                    dumpChunk(mod, (int) t2.start, (int) t2.end, 5);
                }
            } else {
                // 解析失败：手动扫描包 chunk 找 0x0201（按 type 池第 N 项 = string）
                scanDump(mod, orig);
            }
        }
    }

    static void dumpChunk(byte[] d, int s, int end, int showN) {
        int headerSize = ArcsDefsX.u16(d, s + 2);
        int flags = d[s + 10] & 0xFF;
        int count = ArcsDefsX.u32(d, s + 12);
        int entriesStart = ArcsDefsX.u32(d, s + 16);
        int size = ArcsDefsX.u32(d, s + 4);
        System.out.println("headerSize=" + headerSize + " flags=0x" + Integer.toHexString(flags)
                + " entryCount=" + count + " entriesStart=" + entriesStart
                + " chunkSize=" + size + " chunkLen=" + (end - s));
        System.out.println("expect entriesStart>= " + (headerSize + count * 4));
        for (int i = 0; i < Math.min(showN, count); i++) {
            int off = ArcsDefsX.u32(d, s + headerSize + i * 4);
            System.out.println("  offs[" + i + "]=" + (off == 0xFFFFFFFFL ? "NO_ENTRY" : off)
                    + (off == 0xFFFFFFFFL ? "" : dumpEntry(d, s, entriesStart, off)));
        }
    }

    static String dumpEntry(byte[] d, int chunkStart, int entriesStart, int off) {
        int p = chunkStart + entriesStart + off;
        if (p + 8 > d.length) return "  <OOB>";
        int esz = ArcsDefsX.u16(d, p);
        int eflags = ArcsDefsX.u16(d, p + 2);
        int key = ArcsDefsX.u32(d, p + 4);
        StringBuilder sb = new StringBuilder("  entry{size=" + esz + " flags=0x" + Integer.toHexString(eflags)
                + " key=" + key);
        if ((eflags & 1) == 0 && p + 16 <= d.length) {
            int vsz = ArcsDefsX.u16(d, p + 8);
            int dt = d[p + 11] & 0xFF;
            int data = ArcsDefsX.u32(d, p + 12);
            sb.append(" val{size=").append(vsz).append(" dataType=0x").append(Integer.toHexString(dt))
                    .append(" data=").append(data).append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    /** 解析失败时的兜底：顺序扫描 chunk（table 头 12 字节起，每 chunk: type u16 size u32） */
    static void scanDump(byte[] mod, byte[] orig) throws Exception {
        int tpIdx = 16; // string（上面已打印）
        scanRange(mod, 12, mod.length, tpIdx);
    }

    static void scanRange(byte[] d, int from, int end, int tpIdx) {
        int pos = from;
        while (pos + 8 <= end) {
            int type = ArcsDefsX.u16(d, pos);
            int size = ArcsDefsX.u32(d, pos + 4);
            if (size <= 0 || pos + size > end) {
                System.out.println("bad chunk at 0x" + Integer.toHexString(pos) + " type=0x"
                        + Integer.toHexString(type) + " size=" + size);
                break;
            }
            if (type == 0x0201) {
                int id = d[pos + 8] & 0xFF;
                if (id == tpIdx + 1) {
                    System.out.println("== MOD-SCAN string chunk @0x" + Integer.toHexString(pos));
                    dumpChunk(d, pos, pos + size, 8);
                }
            } else if (type == 0x0200) {
                int headerSize = ArcsDefsX.u16(d, pos + 2);
                int tsOff = ArcsDefsX.u32(d, pos + 268);
                int ksOff = ArcsDefsX.u32(d, pos + 276);
                int tsSize = tsOff > 0 ? ArcsDefsX.u32(d, pos + tsOff + 4) : 0;
                int ksSize = ksOff > 0 ? ArcsDefsX.u32(d, pos + ksOff + 4) : 0;
                int innerFrom = pos + Math.max(headerSize,
                        Math.max(tsOff + tsSize, ksOff + ksSize));
                scanRange(d, innerFrom, pos + size, tpIdx);
            }
            pos += size;
        }
    }

    static com.hex91.file.ArscFile ArxscTmpParse(byte[] d) throws Exception {
        return com.hex91.file.ArscFile.parse(d);
    }

    static byte[] readFile(String p) throws Exception {
        FileInputStream in = new FileInputStream(p);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
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
        throw new IllegalStateException("no resources.arsc");
    }
}

class ArxscD0 {
    static int u16(byte[] d, int p) { return ArcsDefsX.u16(d, p); }
    static int u32(byte[] d, int p) { return ArcsDefsX.u32(d, p); }
}

class ArcsDefsX {
    static int u16(byte[] d, int p) { return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8); }
    static int u32(byte[] d, int p) { return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24); }
}
