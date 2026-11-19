import com.hex91.file.fs.CompareUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v2.12.0（F-12）：归档条目对比的 CRC32 用例。
 *
 * <p>要锁住的是一个很容易「看起来没问题」的退化：只比 size。同长度不同内容的条目
 * （换一张同尺寸图标、改一个 smali 常量、替换 resources.arsc 里的同长字符串）
 * 在旧实现里被判成「完全相同」，而这恰恰是 APK 对比最需要看出来的改动。</p>
 *
 * <p>同时锁住退化方向：两侧都没有 CRC 信息时（构造 {@code ArchEntry} 的老写法，
 * 默认 crc = -1）必须**退回只比 size**，不能因为「crc 不同」把本该相同的判成修改。</p>
 */
public class ArchCompareTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File("test/_archcmp_t");
        deleteAll(base);
        base.mkdirs();

        // A 与 B：same.txt 内容相同、change.txt 同长度不同内容、size.bin 长度不同
        File a = new File(base, "a.zip");
        File b = new File(base, "b.zip");
        zip(a, new String[]{"same.txt", "change.txt", "size.bin", "onlyA.txt"},
                new String[]{"hello", "AAAA", "0123456789", "gone"});
        zip(b, new String[]{"same.txt", "change.txt", "size.bin", "onlyB.txt"},
                new String[]{"hello", "BBBB", "01234", "new"});

        CompareUtil.Result r = CompareUtil.compareEntries(
                CompareUtil.readZipEntries(a), CompareUtil.readZipEntries(b));

        // Result.items 只装「有差异的项」，所以相同项应该查不到
        ok(item(r, "same.txt") == null && r.nSame == 1,
                "same.txt 内容相同 → SAME（不进差异列表）");
        CompareUtil.Item ch = item(r, "change.txt");
        ok(ch != null && ch.status == CompareUtil.MODIFIED,
                "change.txt 同长度不同内容 → MODIFIED（旧实现会判 SAME）");
        // v2.38.0：说明文案移到 ui.CompareReport，逻辑层只留类型码
        ok(ch != null && ch.noteKind == CompareUtil.NOTE_CRC,
                "change.txt 的说明类型是 NOTE_CRC → " + (ch == null ? "null" : ch.noteKind));
        ok(ch != null && ch.sizeA == ch.sizeB,
                "change.txt 两侧 size 相同（证明判据不是 size）");

        CompareUtil.Item sz = item(r, "size.bin");
        ok(sz != null && sz.status == CompareUtil.MODIFIED, "size.bin 长度不同 → MODIFIED");

        CompareUtil.Item oa = item(r, "onlyA.txt");
        CompareUtil.Item ob = item(r, "onlyB.txt");
        ok(oa != null && oa.status == CompareUtil.DELETED, "onlyA.txt 只在 A → DELETED");
        ok(ob != null && ob.status == CompareUtil.ADDED, "onlyB.txt 只在 B → ADDED");
        ok(r.nSame == 1 && r.nModified == 2 && r.nAdded == 1 && r.nDeleted == 1,
                "汇总计数 1/2/1/1（实际 " + r.nSame + "/" + r.nModified + "/"
                        + r.nAdded + "/" + r.nDeleted + "）");

        // 退化方向：没有 CRC 信息时必须只比 size，不能凭空判修改
        List<CompareUtil.ArchEntry> la = new java.util.ArrayList<CompareUtil.ArchEntry>();
        List<CompareUtil.ArchEntry> lb = new java.util.ArrayList<CompareUtil.ArchEntry>();
        la.add(new CompareUtil.ArchEntry("x.txt", 5, false));          // 老构造器 → crc = -1
        lb.add(new CompareUtil.ArchEntry("x.txt", 5, false));
        CompareUtil.Result r2 = CompareUtil.compareEntries(la, lb);
        ok(r2.nSame == 1 && r2.nModified == 0, "两侧无 CRC → 退化为只比 size（判 SAME）");

        // 只有一侧有 CRC 时同样不能判修改（另一侧不知道）
        la.clear();
        lb.clear();
        la.add(new CompareUtil.ArchEntry("y.txt", 5, false, 0x12345678L));
        lb.add(new CompareUtil.ArchEntry("y.txt", 5, false));
        CompareUtil.Result r3 = CompareUtil.compareEntries(la, lb);
        ok(r3.nSame == 1 && r3.nModified == 0, "仅一侧有 CRC → 不判修改");

        deleteAll(base);
        System.out.println();
        System.out.println("==== ArchCompareTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static CompareUtil.Item item(CompareUtil.Result r, String name) {
        for (CompareUtil.Item it : r.items) {
            if (name.equals(it.relPath)) return it;
        }
        return null;
    }

    /** 造一个 zip：names 与 data 一一对应（data 直接当 UTF-8 内容写） */
    static void zip(File out, String[] names, String[] data) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out));
        try {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i]));
                z.write(data[i].getBytes("UTF-8"));
                z.closeEntry();
            }
        } finally {
            z.close();
        }
    }

    static void deleteAll(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
