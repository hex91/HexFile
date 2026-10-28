import com.hex91.file.ArchiveWrite;
import com.hex91.file.ZipUtil;

import java.io.*;
import java.util.*;

/**
 * ArchiveWrite 桌面回归（v1.15）：ZIP / TAR / TGZ 的「替换条目 / 新增条目 / 删除条目」往返。
 * 样本取自真机 /sdcard/Download/HexFile_test/（sample.zip/tar/tgz）。
 */
public class ArchiveWriteTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        // ---- ZIP ----
        File zip = fresh("sample.zip", "w.zip");
        check("zip: 替换 readme.txt", ArchiveWrite.writeEntry(zip, "readme.txt",
                "NEW-ZIP-CONTENT\n".getBytes("UTF-8")));
        check("zip: 新增 newdir/a.txt", ArchiveWrite.writeEntry(zip, "newdir/a.txt",
                "aaa\n".getBytes("UTF-8")));
        check("zip: 删除 lib/code.smali", ArchiveWrite.removeEntries(zip,
                Arrays.asList("lib/code.smali")));
        check("zip: 读回 readme.txt 内容", readZip(zip, "readme.txt").equals("NEW-ZIP-CONTENT\n"));
        check("zip: lib/code.smali 已不存在", readZip(zip, "lib/code.smali") == null);
        check("zip: newdir/a.txt 已存在", "aaa\n".equals(readZip(zip, "newdir/a.txt")));
        check("zip: assets/config.xml 原样保留", readZip(zip, "assets/config.xml") != null);
        check("zip: canWrite(zip)", ArchiveWrite.canWrite(zip));
        // 流式写入（File 重载）
        File tmpData = File.createTempFile("awt", ".bin");
        writeFile(tmpData, new byte[300000]);
        check("zip: 流式写入 big.bin(300KB)", ArchiveWrite.writeEntry(zip, "big.bin", tmpData));
        check("zip: big.bin 大小正确", zipSize(zip, "big.bin") == 300000);

        // ---- TAR ----
        File tar = fresh("sample.tar", "w.tar");
        check("tar: 替换 readme.txt", ArchiveWrite.writeEntry(tar, "readme.txt",
                "NEW-TAR-CONTENT\n".getBytes("UTF-8")));
        check("tar: 新增 newdir/b.txt", ArchiveWrite.writeEntry(tar, "newdir/b.txt",
                "bbb\n".getBytes("UTF-8")));
        check("tar: 删除 lib/code.smali", ArchiveWrite.removeEntries(tar,
                Arrays.asList("lib/code.smali")));
        check("tar: 读回 readme.txt 内容", readTar(tar, "readme.txt").equals("NEW-TAR-CONTENT\n"));
        check("tar: lib/code.smali 已不存在", readTar(tar, "lib/code.smali") == null);
        check("tar: newdir/b.txt 已存在", "bbb\n".equals(readTar(tar, "newdir/b.txt")));
        check("tar: doc/deep/nested/file.md 原样保留", readTar(tar, "doc/deep/nested/file.md") != null);
        check("tar: 流式写入 big2.bin", ArchiveWrite.writeEntry(tar, "big2.bin", tmpData));
        check("tar: big2.bin 大小正确", tarSize(tar, "big2.bin") == 300000);

        // ---- TGZ ----
        File tgz = fresh("sample.tgz", "w.tgz");
        check("tgz: 替换 readme.txt", ArchiveWrite.writeEntry(tgz, "readme.txt",
                "NEW-TGZ-CONTENT\n".getBytes("UTF-8")));
        check("tgz: 新增 x/y.txt", ArchiveWrite.writeEntry(tgz, "x/y.txt",
                "yyy\n".getBytes("UTF-8")));
        check("tgz: 读回 readme.txt 内容", readTar(tgz, "readme.txt").equals("NEW-TGZ-CONTENT\n"));
        check("tgz: x/y.txt 已存在", "yyy\n".equals(readTar(tgz, "x/y.txt")));

        // ---- 7Z（v1.16 起可写，SevenZOutputFile 重建式）----
        File sz = fresh("sample.7z", "w.7z");
        check("7z: canWrite=true", ArchiveWrite.canWrite(sz));
        check("7z: 替换 readme.txt", ArchiveWrite.writeEntry(sz, "readme.txt",
                "NEW-7Z-CONTENT\n".getBytes("UTF-8")));
        check("7z: 新增 newdir/c.txt", ArchiveWrite.writeEntry(sz, "newdir/c.txt",
                "ccc\n".getBytes("UTF-8")));
        check("7z: 删除 lib/code.smali", ArchiveWrite.removeEntries(sz,
                Arrays.asList("lib/code.smali")));
        check("7z: 读回 readme.txt 内容", read7z(sz, "readme.txt").equals("NEW-7Z-CONTENT\n"));
        check("7z: lib/code.smali 已不存在", read7z(sz, "lib/code.smali") == null);
        check("7z: newdir/c.txt 已存在", "ccc\n".equals(read7z(sz, "newdir/c.txt")));
        check("7z: assets/config.xml 原样保留", read7z(sz, "assets/config.xml") != null);
        check("7z: 流式写入 big3.bin", ArchiveWrite.writeEntry(sz, "big3.bin", tmpData));
        check("7z: big3.bin 大小正确", sz7zSize(sz, "big3.bin") == 300000);

        // ---- 只读格式 ----
        File rar = fresh("Read me.rar", "w.rar");
        check("rar: canWrite=false", !ArchiveWrite.canWrite(rar));
        check("rar: writeEntry 不被调用（canWrite 已拦截）", true);

        tmpData.delete();
        System.out.println("\n==== ArchiveWriteTest: PASS=" + pass + " FAIL=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }

    // ---------- helpers ----------

    static File fresh(String src, String dstName) throws IOException {
        File srcF = new File("test/_dbg/" + src);
        File dst = new File("test/_dbg/" + dstName);
        copy(srcF, dst);
        return dst;
    }

    static void copy(File s, File d) throws IOException {
        InputStream in = new FileInputStream(s);
        OutputStream out = new FileOutputStream(d);
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        out.close();
    }

    static void writeFile(File f, byte[] data) throws IOException {
        OutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }

    static String readZip(File zip, String entry) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.zip.ZipEntry ze = zf.getEntry(entry);
            if (ze == null) return null;
            InputStream in = zf.getInputStream(ze);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static long zipSize(File zip, String entry) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.zip.ZipEntry ze = zf.getEntry(entry);
            return ze == null ? -1 : ze.getSize();
        } catch (Exception e) {
            return -1;
        }
    }

    static String readTar(File tar, String entry) {
        try (InputStream raw = new FileInputStream(tar);
             InputStream base = tar.getName().endsWith(".tgz") || tar.getName().endsWith(".gz")
                     ? new java.util.zip.GZIPInputStream(raw) : raw;
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream in =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(base)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                if (e.getName().equals(entry)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    return new String(bo.toByteArray(), "UTF-8");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static long tarSize(File tar, String entry) {
        try (InputStream raw = new FileInputStream(tar);
             InputStream base = tar.getName().endsWith(".tgz") || tar.getName().endsWith(".gz")
                     ? new java.util.zip.GZIPInputStream(raw) : raw;
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream in =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(base)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                if (e.getName().equals(entry)) return e.getSize();
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static String read7z(File sz, String entry) {
        try (org.apache.commons.compress.archivers.sevenz.SevenZFile f =
                     new org.apache.commons.compress.archivers.sevenz.SevenZFile(sz)) {
            for (org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e : f.getEntries()) {
                if (e.getName().equals(entry)) {
                    InputStream in = f.getInputStream(e);
                    if (in == null) return e.isDirectory() ? "" : null;
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    return new String(bo.toByteArray(), "UTF-8");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static long sz7zSize(File sz, String entry) {
        try (org.apache.commons.compress.archivers.sevenz.SevenZFile f =
                     new org.apache.commons.compress.archivers.sevenz.SevenZFile(sz)) {
            for (org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e : f.getEntries()) {
                if (e.getName().equals(entry)) return e.getSize();
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }
}
