import com.hex91.file.fs.DiskScanUtil;
import com.hex91.file.fs.VaultStore;

import java.io.File;
import java.io.FileOutputStream;

/**
 * R-39：隐私空间从搜索 / 存储分析 / 查重里排除。
 *
 * <p>为什么这条要单独钉：隐私空间的目录名是 {@code .vault}，位置就在工作目录下，
 * 遍历代码只要少一个判断，用户搜 {@code *.bin} 就能看到一堆 {@code b/<id>.bin}。
 * 密文不泄露明文名，但结果行能被点开、能被删 —— 删掉一个 blob 就是一条记录永久丢失。</p>
 *
 * <p>同时钉住「不能只看目录名」：用户自己建一个叫 {@code .vault} 的普通目录，
 * 那棵树该照常被统计和搜索，否则没有任何线索能解释它为什么消失了。</p>
 */
public class VaultExcludeTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        File root = mkTempDir();
        try {
            // ---- 一棵真的隐私空间：.vault + v.hdr + b/<id>.bin ----
            File vault = new File(root, ".vault");
            mkdirs(vault);
            File hdr = write(new File(vault, "v.hdr"), new byte[157]);
            File blob = write(new File(vault, "b/one.bin"), new byte[4096]);

            // ---- 同名但不是隐私空间：没有 v.hdr，必须照常对待 ----
            File fake = new File(root, "fake/.vault");
            mkdirs(fake);
            File fakeFile = write(new File(fake, "note.txt"), new byte[64]);

            File docs = new File(root, "docs");
            mkdirs(docs);
            File doc = write(new File(docs, "readme.txt"), new byte[128]);

            // ---- 判据 ----
            ok(VaultStore.isVaultDir(vault), "isVaultDir: 目录名 .vault + v.hdr 为真");
            ok(!VaultStore.isVaultDir(fake), "isVaultDir: 只有目录名、没有 v.hdr 为假");
            ok(!VaultStore.isVaultDir(docs), "isVaultDir: 普通目录为假");
            ok(!VaultStore.isVaultDir(doc), "isVaultDir: 普通文件为假");
            ok(VaultStore.isVaultDir(vault), "isVaultDir: 入参是根目录本身也算");
            ok(VaultStore.isInsideVault(blob), "isInsideVault: 密文 blob 在隐私空间内");
            ok(VaultStore.isInsideVault(hdr), "isInsideVault: 头部文件也在内");
            ok(VaultStore.isInsideVault(vault), "isInsideVault: 根目录自身算在内");
            ok(!VaultStore.isInsideVault(doc), "isInsideVault: 普通文件不在内");
            ok(!VaultStore.isInsideVault(fakeFile), "isInsideVault: 同名伪目录的子项不在内");

            // ---- 存储分析：容量照算，但不列子项 ----
            DiskScanUtil.Result r = DiskScanUtil.analyze(root, 20, null);
            long vaultBytes = hdr.length() + blob.length();
            ok(r.total >= vaultBytes + doc.length() + fakeFile.length(),
                    "analyze: 总容量包含隐私空间（否则总占用与真实剩余对不上）");
            boolean leaked = false;
            for (int i = 0; i < r.top.size(); i++) {
                if (VaultStore.isInsideVault(r.top.get(i).file)) leaked = true;
            }
            ok(!leaked, "analyze: Top N 里没有隐私空间内的文件");
            boolean fakeListed = false;
            for (int i = 0; i < r.top.size(); i++) {
                if (r.top.get(i).file.equals(fakeFile)) fakeListed = true;
            }
            ok(fakeListed, "analyze: 同名伪目录里的普通文件照常参与排行");

            // ---- 最大文件排行（另一条独立入口） ----
            java.util.List<DiskScanUtil.FileSize> top = DiskScanUtil.topFiles(root, 50, null);
            boolean topLeak = false, topFake = false;
            for (int i = 0; i < top.size(); i++) {
                File f = top.get(i).file;
                if (VaultStore.isInsideVault(f)) topLeak = true;
                if (f.equals(fakeFile)) topFake = true;
            }
            ok(!topLeak, "topFiles: 隐私空间内的文件不出现");
            ok(topFake, "topFiles: 同名伪目录里的文件照常出现");

            // ---- 查重：密文不参与（否则等同把整库密文读一遍） ----
            java.util.List<DiskScanUtil.DupGroup> dups =
                    DiskScanUtil.findDuplicates(root, 1, null);
            boolean dupLeak = false;
            for (int i = 0; i < dups.size(); i++) {
                for (int j = 0; j < dups.get(i).files.size(); j++) {
                    if (VaultStore.isInsideVault(dups.get(i).files.get(j))) dupLeak = true;
                }
            }
            ok(!dupLeak, "findDuplicates: 隐私空间内的密文不参与查重");
        } finally {
            deleteTree(root);
        }

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 工具

    static File mkTempDir() throws Exception {
        File f = File.createTempFile("hf_vault_excl", "");
        if (!f.delete() || !f.mkdirs()) throw new IllegalStateException("无法建临时目录");
        return f;
    }

    static void mkdirs(File f) {
        if (!f.isDirectory() && !f.mkdirs()) throw new IllegalStateException("mkdirs: " + f);
    }

    static File write(File f, byte[] data) throws Exception {
        File p = f.getParentFile();
        if (p != null) mkdirs(p);
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(data); } finally { out.close(); }
        return f;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (int i = 0; i < cs.length; i++) deleteTree(cs[i]);
        f.delete();
    }
}
