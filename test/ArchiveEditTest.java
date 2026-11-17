import com.hex91.file.apk.ArchiveWrite;
import com.hex91.file.fs.ZipUtil;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * v2.13.1（F-11）：归档原地重命名 / 新建目录的真跑用例（三种可写格式各来一遍）。
 *
 * <p>为什么必须真跑而不是只测字符串：{@link com.hex91.file.core.EntryNames} 只保证
 * 「名字算得对」，而重命名真正的风险在后端 —— 目录条目跟着改名、旧名残留、目标名撞车、
 * 7z 的 directory 标志位写错。这些只有把包写出来再读一遍才看得见。</p>
 *
 * <p>能桌面跑的原因：{@code TempFiles} 与 {@code AtomicReplace} 都是纯 JDK，
 * {@code ZipUtil}/{@code ArchiveWrite} 里唯一的 Android 依赖是失败路径上的
 * {@code android.util.Log.w}（测试全程不触发）。</p>
 */
public class ArchiveEditTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(Object want, Object got, String msg) {
        boolean v = want == null ? got == null : want.equals(got);
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    public static void main(String[] args) throws Exception {
        File base = new File("test/_archedit_t");
        deleteAll(base);
        base.mkdirs();

        zipRename(base);
        zipMkdirAndMiss(base);
        tarRename(base);
        sevenZRename(base);

        System.out.println("ArchiveEditTest: " + pass + " PASS / " + fail + " FAIL");
        deleteAll(base);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- ZIP

    static void zipRename(File base) throws Exception {
        File z = new File(base, "a.zip");
        writeZip(z, new String[]{"a/1.txt", "a/deep/2.txt", "ab/3.txt", "b.txt"},
                new String[]{"one", "two", "three", "four"});

        ok(ZipUtil.renameEntry(z, "a", "x/y"), "zip：目录 a 重命名为 x/y");
        eq("[ab/3.txt, b.txt, x/y/1.txt, x/y/deep/2.txt]", names(zipNames(z)),
                "zip：目录连同子项一起搬走，同前缀兄弟 ab/ 不动");
        eq("one", readZip(z, "x/y/1.txt"), "zip：重命名后内容不变");
        eq("two", readZip(z, "x/y/deep/2.txt"), "zip：深层子项内容不变");

        // 单文件改名（同时移到子目录）
        ok(ArchiveWrite.renameEntry(z, "b.txt", "c/d.txt"), "zip：单文件改名并移到子目录");
        ok(zipNames(z).contains("c/d.txt") && !zipNames(z).contains("b.txt"),
                "zip：旧名 b.txt 不残留");
        eq("four", readZip(z, "c/d.txt"), "zip：移动后的内容不变");

        // 目录里只剩一个条目时也要能改名（a 已经走了，用 x/y 再改一次）
        ok(ArchiveWrite.renameEntry(z, "x/y", "x/z"), "zip：二次改名（子项已迁移过的目录）");
        eq("[ab/3.txt, c/d.txt, x/z/1.txt, x/z/deep/2.txt]", names(zipNames(z)),
                "zip：二次改名后子项仍成组");
    }

    static void zipMkdirAndMiss(File base) throws Exception {
        File z = new File(base, "a.zip");
        List<String> before = zipNames(z);

        // 不存在的条目：必须报失败，且原包一个字节都不动
        ok(!ArchiveWrite.renameEntry(z, "nope", "zzz"), "zip：重命名不存在的条目 → 失败");
        eq(names(before), names(zipNames(z)), "zip：失败时原包条目表不变");
        ok(!ArchiveWrite.renameEntry(z, "../evil", "x"), "zip：源名带 .. → 直接拒绝");
        ok(!ArchiveWrite.renameEntry(z, "ab/3.txt", "../../evil"), "zip：目标名带 .. → 直接拒绝");

        ok(ArchiveWrite.mkdir(z, "new/dir"), "zip：新建目录 new/dir");
        ok(zipNames(z).contains("new/dir/"), "zip：目录条目以 / 结尾（解压工具才认它是目录）");
        eq(0L, zipSizes(z).get("new/dir/"), "zip：目录条目长度 0");
        List<String> keep = new ArrayList<String>(zipNames(z));
        keep.remove("new/dir/");
        eq(names(before), names(keep), "zip：新建目录不影响已有条目");
        ok(ArchiveWrite.mkdir(z, "new/dir2/"), "zip：名字带结尾斜杠也能建");
        ok(zipNames(z).contains("new/dir2/"), "zip：dir2 条目存在且没有出现双斜杠");
        ok(!zipNames(z).contains("new/dir2//"), "zip：没有 new/dir2// 这种脏条目");
    }

    // ---------------------------------------------------------------- TAR

    static void tarRename(File base) throws Exception {
        File t = new File(base, "a.tar");
        writeTar(t, new String[]{"a/1.txt", "a/deep/2.txt", "ab/3.txt"}, new String[]{"one", "two", "三"});

        ok(ArchiveWrite.renameEntry(t, "a", "x/y"), "tar：目录 a 重命名为 x/y");
        eq("[ab/3.txt, x/y/1.txt, x/y/deep/2.txt]", names(tarNames(t)),
                "tar：目录连同子项一起搬走，同前缀兄弟不动");
        eq("two", readTar(t, "x/y/deep/2.txt"), "tar：重命名后内容不变（含深层）");

        ok(ArchiveWrite.mkdir(t, "new/dir"), "tar：新建目录 new/dir");
        ok(tarNames(t).contains("new/dir/"), "tar：目录条目以 / 结尾");
        ok(!ArchiveWrite.renameEntry(t, "gone", "here"), "tar：重命名不存在的条目 → 失败");
        eq("[ab/3.txt, new/dir/, x/y/1.txt, x/y/deep/2.txt]", names(tarNames(t)),
                "tar：失败后条目表不变");

        // 重建循环是这一轮改过的（写/删/改名共用一份），替换与删除都要回归
        ok(ArchiveWrite.writeEntry(t, "x/y/1.txt", "ONE".getBytes("UTF-8")), "tar：替换已有条目");
        eq("ONE", readTar(t, "x/y/1.txt"), "tar：替换后内容变了");
        eq("[ab/3.txt, new/dir/, x/y/1.txt, x/y/deep/2.txt]", names(tarNames(t)),
                "tar：替换不产生重复条目");
        ok(ArchiveWrite.removeEntries(t, java.util.Collections.singletonList("x/y")),
                "tar：按目录删除其下全部子项");
        eq("[ab/3.txt, new/dir/]", names(tarNames(t)), "tar：目录条目与子项一起消失");

    }

    // ---------------------------------------------------------------- 7Z

    static void sevenZRename(File base) throws Exception {
        File s = new File(base, "a.7z");
        write7z(s, new String[]{"a/1.txt", "a/deep/2.txt", "ab/3.txt"}, new String[]{"one", "two", "three"});

        ok(ArchiveWrite.renameEntry(s, "a", "x/y"), "7z：目录 a 重命名为 x/y");
        eq("[ab/3.txt, x/y/1.txt, x/y/deep/2.txt]", names(szNames(s)),
                "7z：目录连同子项一起搬走，同前缀兄弟不动");
        eq("two", read7z(s, "x/y/deep/2.txt"), "7z：重命名后内容不变");

        ok(ArchiveWrite.mkdir(s, "new/dir"), "7z：新建目录 new/dir");
        List<String> ns = szNames(s);
        boolean found = false, dirFlag = false;
        for (String n : ns) {
            if (n.equals("new/dir/") || n.equals("new/dir")) {
                found = true;
                dirFlag = szIsDir(s, n);
            }
        }
        ok(found, "7z：新建目录条目存在");
        ok(dirFlag, "7z：新建条目带 directory 标志（不是 0 字节文件冒充目录）");
        ok(!ArchiveWrite.renameEntry(s, "gone", "here"), "7z：重命名不存在的条目 → 失败");

        // 同 tar：重建循环三种操作共用一个，替换/删除各回归一次
        ok(ArchiveWrite.writeEntry(s, "x/y/1.txt", "ONE".getBytes("UTF-8")), "7z：替换已有条目");
        eq("ONE", read7z(s, "x/y/1.txt"), "7z：替换后内容变了");
        eq("[ab/3.txt, new/dir, x/y/1.txt, x/y/deep/2.txt]", names(stripDirs(szNames(s))),
                "7z：替换不产生重复条目");
        ok(ArchiveWrite.removeEntries(s, java.util.Collections.singletonList("x/y")),
                "7z：按目录删除其下全部子项");
        ok(!szNames(s).contains("x/y/1.txt") && szNames(s).contains("ab/3.txt"),
                "7z：只删目标子树，兄弟条目保留");
    }

    // ---------------------------------------------------------------- 读写工具

    static void writeZip(File out, String[] names, String[] data) throws Exception {
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

    static List<String> zipNames(File z) throws Exception {
        List<String> out = new ArrayList<String>();
        try (ZipFile zf = new ZipFile(z)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) out.add(en.nextElement().getName());
        }
        Collections.sort(out);
        return out;
    }

    static java.util.Map<String, Long> zipSizes(File z) throws Exception {
        java.util.Map<String, Long> out = new java.util.HashMap<String, Long>();
        try (ZipFile zf = new ZipFile(z)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                out.put(e.getName(), e.getSize());
            }
        }
        return out;
    }

    static String readZip(File z, String name) throws Exception {
        try (ZipFile zf = new ZipFile(z)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            InputStream in = zf.getInputStream(e);
            return new String(readAll(in), "UTF-8");
        }
    }

    static void writeTar(File out, String[] names, String[] data) throws Exception {
        TarArchiveOutputStream t = new TarArchiveOutputStream(new FileOutputStream(out));
        try {
            t.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (int i = 0; i < names.length; i++) {
                byte[] b = data[i].getBytes("UTF-8");
                TarArchiveEntry e = new TarArchiveEntry(names[i]);
                e.setSize(b.length);
                t.putArchiveEntry(e);
                t.write(b);
                t.closeArchiveEntry();
            }
        } finally {
            t.close();
        }
    }

    static List<String> tarNames(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new FileInputStream(f))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) out.add(e.getName());
        }
        Collections.sort(out);
        return out;
    }

    static String readTar(File f, String name) throws Exception {
        try (TarArchiveInputStream in = new TarArchiveInputStream(new FileInputStream(f))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                if (e.getName().equals(name)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    return new String(bo.toByteArray(), "UTF-8");
                }
            }
        }
        return null;
    }

    static void write7z(File out, String[] names, String[] data) throws Exception {
        SevenZOutputFile s = new SevenZOutputFile(out);
        try {
            for (int i = 0; i < names.length; i++) {
                byte[] b = data[i].getBytes("UTF-8");
                SevenZArchiveEntry e = new SevenZArchiveEntry();
                e.setName(names[i]);
                e.setSize(b.length);
                s.putArchiveEntry(e);
                s.write(b);
                s.closeArchiveEntry();
            }
        } finally {
            s.close();
        }
    }

    static List<String> szNames(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        try (SevenZFile s = new SevenZFile(f)) {
            for (SevenZArchiveEntry e : s.getEntries()) out.add(e.getName());
        }
        Collections.sort(out);
        return out;
    }

    static boolean szIsDir(File f, String name) throws Exception {
        try (SevenZFile s = new SevenZFile(f)) {
            for (SevenZArchiveEntry e : s.getEntries()) {
                if (e.getName().equals(name)) return e.isDirectory();
            }
        }
        return false;
    }

    static String read7z(File f, String name) throws Exception {
        try (SevenZFile s = new SevenZFile(f)) {
            for (SevenZArchiveEntry e : s.getEntries()) {
                if (!e.getName().equals(name)) continue;
                InputStream in = s.getInputStream(e);
                return new String(readAll(in), "UTF-8");
            }
        }
        return null;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static List<String> stripDirs(List<String> l) {
        List<String> out = new ArrayList<String>();
        for (String n : l) out.add(n.endsWith("/") ? n.substring(0, n.length() - 1) : n);
        Collections.sort(out);
        return out;
    }

    static String names(List<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        return sb.append(']').toString();
    }

    static void deleteAll(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
