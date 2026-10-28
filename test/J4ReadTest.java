package bin.HexFile;

import java.io.*;
import java.util.*;

/** 桌面读取回归：验证 ArchiveUtil 的 Zip4j 后端（J4Archive）能正确列出+提取
 *  普通 ZIP / AES ZIP / ZipCrypto ZIP / 伪装 .apk 的 ZIP；加密包无密码时抛 NeedsPassword。 */
public class J4ReadTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static File mkSrc() throws Exception {
        File dir = new File("test/_j4src");
        del(dir); dir.mkdirs();
        File rm = new File(dir, "readme.txt");
        write(rm, "hello plain\n");
        File sub = new File(dir, "sub"); sub.mkdirs();
        byte[] bin = new byte[100]; for (int i=0;i<100;i++) bin[i]=(byte)i;
        write(new File(sub, "payload.bin"), bin);
        return dir;
    }
    static void write(File f, String s) throws Exception { write(f, s.getBytes("UTF-8")); }
    static void write(File f, byte[] b) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }
    static void del(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) for (File c : f.listFiles()) del(c);
        f.delete();
    }
    static byte[] read(File f) throws Exception {
        try (FileInputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n=i.read(buf))>0) o.write(buf,0,n);
            return o.toByteArray();
        }
    }
    /** 递归收集目录下所有文件 */
    static List<File> allFiles(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        for (File c : dir.listFiles()) {
            if (c.isDirectory()) out.addAll(allFiles(c));
            else out.add(c);
        }
        return out;
    }
    /** 在解压目录里找内容等于 target 的文件；返回是否存在 */
    static boolean hasContent(File outDir, byte[] target) throws Exception {
        for (File f : allFiles(outDir)) {
            byte[] b = read(f);
            if (b.length == target.length) {
                boolean eq = true;
                for (int i=0;i<b.length;i++) if (b[i]!=target[i]) { eq=false; break; }
                if (eq) return true;
            }
        }
        return false;
    }
    /** 在解压目录里找名为 suffix 结尾且长度==len 的文件 */
    static boolean hasFileSize(File outDir, String suffix, long len) {
        for (File f : allFiles(outDir)) {
            if (f.getName().endsWith(suffix) && f.length()==len) return true;
        }
        return false;
    }
    static Set<String> listNames(ArchiveUtil.Archive a, String prefix) throws Exception {
        Set<String> s = new HashSet<>();
        for (ArchiveUtil.Entry e : a.listChildren(prefix)) s.add(e.full);
        return s;
    }
    /** 递归收集全部条目（模拟逐级进入浏览），命名无关 */
    static Set<String> allEntries(ArchiveUtil.Archive a) throws Exception {
        Set<String> out = new HashSet<>();
        java.util.Deque<String> stack = new ArrayDeque<>();
        stack.push("");
        while (!stack.isEmpty()) {
            String p = stack.pop();
            for (ArchiveUtil.Entry e : a.listChildren(p)) {
                out.add(e.full);
                if (e.dir) stack.push(e.full);
            }
        }
        return out;
    }
    static boolean containsSuffix(Set<String> names, String suffix) {
        for (String n : names) if (n.toLowerCase().endsWith(suffix)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        File src = mkSrc();
        List<File> sources = new ArrayList<>(); sources.add(src);

        // ---- 构造四种归档 ----
        File plain = new File("test/_j4out/plain.zip");
        File aes   = new File("test/_j4out/aes.zip");
        File zc    = new File("test/_j4out/zc.zip");
        File apk   = new File("test/_j4out/fake.apk");
        plain.getParentFile().mkdirs();
        ZipUtil.createZip(plain, sources);
        ZipUtil.createZipEncrypted(aes, sources, "secret123", 3);          // AES-256
        ZipUtil.createZipEncryptedCompat(zc, sources, "secret123");        // ZipCrypto
        ZipUtil.createZip(apk, sources);                                   // 普通 zip，但扩展名 .apk

        String pw = "secret123";

        // ---- 普通 ZIP：无密码即可列出+提取 ----
        System.out.println("[plain.zip]");
        try (ArchiveUtil.Archive a = ArchiveUtil.open(plain, null)) {
            check("format=ZIP", "ZIP".equals(a.format()));
            Set<String> names = allEntries(a);
            check("列出 >0 条目", !names.isEmpty());
            check("列出含 .txt 条目", containsSuffix(names, ".txt"));
            check("列出含 .bin 条目", containsSuffix(names, ".bin"));
            File out = new File("test/_j4out/plain_ext"); del(out); out.mkdirs();
            a.extractAll(out);
            check("提取 readme 内容正确", hasContent(out, "hello plain\n".getBytes("UTF-8")));
            check("提取 payload.bin 长度=100", hasFileSize(out, ".bin", 100));
        } catch (Throwable t) { check("plain open 不应抛异常", false); t.printStackTrace(); }

        // ---- 伪装 .apk（普通 zip）：无密码即可浏览 ----
        System.out.println("[fake.apk]");
        try (ArchiveUtil.Archive a = ArchiveUtil.open(apk, null)) {
            Set<String> names = allEntries(a);
            check("apk 列出 >0 条目", !names.isEmpty());
            check("apk 列出含 .txt 条目", containsSuffix(names, ".txt"));
            File out = new File("test/_j4out/apk_ext"); del(out); out.mkdirs();
            a.extractAll(out);
            check("apk 提取 readme 内容正确", hasContent(out, "hello plain\n".getBytes("UTF-8")));
        } catch (Throwable t) { check("apk open 不应抛异常", false); t.printStackTrace(); }

        // ---- AES ZIP：无密码应抛 NeedsPassword；有密码可列出+提取 ----
        System.out.println("[aes.zip]");
        boolean threw = false;
        try { ArchiveUtil.open(aes, null); }
        catch (ArchiveUtil.NeedsPasswordException e) { threw = true; }
        catch (Throwable t) { threw = false; t.printStackTrace(); }
        check("AES 无密码抛 NeedsPassword", threw);
        try (ArchiveUtil.Archive a = ArchiveUtil.open(aes, pw)) {
            Set<String> names = allEntries(a);
            check("AES 列出含 .txt 条目", containsSuffix(names, ".txt"));
            check("AES 列出含 .bin 条目", containsSuffix(names, ".bin"));
            File out = new File("test/_j4out/aes_ext"); del(out); out.mkdirs();
            a.extractAll(out);
            check("AES 提取 readme 内容正确", hasContent(out, "hello plain\n".getBytes("UTF-8")));
            check("AES 提取 payload.bin 长度=100", hasFileSize(out, ".bin", 100));
            // 单条目提取（AES 写入器条目名为 readme.txt）
            File one = new File("test/_j4out/aes_one"); del(one); one.mkdirs();
            a.extractEntry("readme.txt", one);
            check("AES 单条目提取正确", hasContent(one, "hello plain\n".getBytes("UTF-8")));
        } catch (Throwable t) { check("AES 有密码 open 不应抛异常", false); t.printStackTrace(); }

        // ---- ZipCrypto ZIP：同样验证 ----
        System.out.println("[zc.zip]");
        threw = false;
        try { ArchiveUtil.open(zc, null); }
        catch (ArchiveUtil.NeedsPasswordException e) { threw = true; }
        catch (Throwable t) { threw = false; t.printStackTrace(); }
        check("ZipCrypto 无密码抛 NeedsPassword", threw);
        try (ArchiveUtil.Archive a = ArchiveUtil.open(zc, pw)) {
            Set<String> names = allEntries(a);
            check("ZipCrypto 列出含 .txt 条目", containsSuffix(names, ".txt"));
            File out = new File("test/_j4out/zc_ext"); del(out); out.mkdirs();
            a.extractAll(out);
            check("ZipCrypto 提取 readme 内容正确", hasContent(out, "hello plain\n".getBytes("UTF-8")));
        } catch (Throwable t) { check("ZipCrypto 有密码 open 不应抛异常", false); t.printStackTrace(); }

        // ---- AES 错密码：打开阶段即抛 NeedsPasswordException（而非看到列表、解压才报错） ----
        System.out.println("[aes.zip 错密码 - 打开即验证]");
        threw = false;
        try { ArchiveUtil.open(aes, "wrong"); }
        catch (ArchiveUtil.NeedsPasswordException e) { threw = true; }
        catch (Throwable t) { threw = false; t.printStackTrace(); }
        check("AES 错密码打开即抛 NeedsPassword", threw);

        // ---- ZipCrypto 错密码：同样打开阶段即抛 ----
        System.out.println("[zc.zip 错密码 - 打开即验证]");
        threw = false;
        try { ArchiveUtil.open(zc, "wrong"); }
        catch (ArchiveUtil.NeedsPasswordException e) { threw = true; }
        catch (Throwable t) { threw = false; t.printStackTrace(); }
        check("ZipCrypto 错密码打开即抛 NeedsPassword", threw);

        System.out.println("\n==== J4ReadTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
