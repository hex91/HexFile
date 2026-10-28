import com.hex91.file.ArchiveUtil;
import com.hex91.file.ArchiveUtil.Archive;
import com.hex91.file.ArchiveUtil.Entry;
import com.hex91.file.ZipUtil;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ArchiveTest {
    static File WORK;
    static int pass = 0, fail = 0;

    static Map<String, String> sampleFiles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("readme.txt", "hello HexFile archive test\n");
        m.put("lib/code.smali", ".method public foo()V\n  return-void\n.end method\n");
        m.put("assets/config.xml", "<root><item>1</item></root>\n");
        m.put("doc/deep/nested/file.md", "# title\ncontent here\n");
        return m;
    }

    static void writeFile(File base, String name, String content) throws IOException {
        File f = new File(base, name);
        f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write(content);
        }
    }

    static void makeZip(File out, Map<String,String> files) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String,String> e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes("UTF-8"));
                zos.closeEntry();
            }
        }
    }

    static void make7z(File out, Map<String,String> files) throws IOException {
        try (SevenZOutputFile sz = new SevenZOutputFile(out)) {
            for (Map.Entry<String,String> e : files.entrySet()) {
                SevenZArchiveEntry en = new SevenZArchiveEntry();
                en.setName(e.getKey());
                sz.putArchiveEntry(en);
                sz.write(e.getValue().getBytes("UTF-8"));
                sz.closeArchiveEntry();
            }
        }
    }

    static void makeTar(File out, Map<String,String> files, boolean gzip) throws IOException {
        OutputStream raw = new FileOutputStream(out);
        OutputStream wrapped = gzip ? new GZIPOutputStream(raw) : raw;
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(wrapped)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String,String> e : files.entrySet()) {
                byte[] data = e.getValue().getBytes("UTF-8");
                TarArchiveEntry en = new TarArchiveEntry(e.getKey());
                en.setSize(data.length);
                tos.putArchiveEntry(en);
                tos.write(data);
                tos.closeArchiveEntry();
            }
        }
    }

    /** 递归读取解压产物为 name->content 映射 */
    static Map<String,String> readTree(File dir) throws IOException {
        Map<String,String> m = new LinkedHashMap<>();
        readTree(dir, dir, m);
        return m;
    }
    static void readTree(File root, File cur, Map<String,String> out) throws IOException {
        File[] kids = cur.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) readTree(root, f, out);
            else {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                out.put(rel, new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            }
        }
    }

    static void testOne(String label, File archive, Map<String,String> expected) {
        try {
            Archive a = ArchiveUtil.open(archive);
            String fmt = a.format();
            List<Entry> top = a.listChildren("");
            // 解压全量
            File outDir = new File(WORK, "out_" + label);
            outDir.mkdirs();
            a.extractAll(outDir);
            a.close();
            Map<String,String> got = readTree(outDir);
            boolean ok = got.equals(expected);
            System.out.println((ok ? "PASS" : "FAIL") + " [" + label + "] format=" + fmt
                    + " topEntries=" + top.size() + " files=" + got.size());
            if (!ok) {
                fail++;
                System.out.println("   GOT KEYS: " + new ArrayList<>(got.keySet()));
                for (String k : expected.keySet())
                    if (!got.containsKey(k) || !got.get(k).equals(expected.get(k)))
                        System.out.println("   MISMATCH: " + k + "  gotHas=" + got.containsKey(k));
            } else pass++;
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL [" + label + "] EXCEPTION: " + e);
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        WORK = new File("C:/Users/han/Desktop/hans/workbuddy/worktool/HexFile/test/archtest");
        WORK.mkdirs();
        Map<String,String> files = sampleFiles();
        // 写入一个真实目录树（仅供 zip 用 ZipUtil 场景）
        File tree = new File(WORK, "tree");
        for (Map.Entry<String,String> e : files.entrySet()) writeFile(tree, e.getKey(), e.getValue());

        File zip = new File(WORK, "sample.zip");
        File seven = new File(WORK, "sample.7z");
        File tar = new File(WORK, "sample.tar");
        File tgz = new File(WORK, "sample.tgz");

        makeZip(zip, files);
        make7z(seven, files);
        makeTar(tar, files, false);
        makeTar(tgz, files, true);

        testOne("zip", zip, files);
        testOne("7z", seven, files);
        testOne("tar", tar, files);
        testOne("tgz", tgz, files);

        System.out.println("\n==== RESULT: PASS=" + pass + " FAIL=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
