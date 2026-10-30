package bin.HexFile;

import com.hex91.file.ArchiveUtil;

import java.io.File;
import java.util.List;

/** tar.zst / 裸 zst 浏览（ArchiveUtil 后端）桌面测试——用 _zsamples 真样本 */
public class ArchZstTest {

    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) throws Exception {
        File dirZst = new File("_zsamples/sample_dir.tar.zst");
        File bigZst = new File("_zsamples/sample_big.zst");
        if (!dirZst.isFile() || !bigZst.isFile()) {
            System.out.println("缺少样本，请先运行 bin.HexFile.MakeSample");
            System.exit(2);
        }

        // ===== 1. tar.zst 浏览 =====
        ArchiveUtil.Archive a = ArchiveUtil.open(dirZst);
        check("tar.zst 打开", a != null);
        List<ArchiveUtil.Entry> root = a.listChildren("");
        check("根=1 个顶层目录 sample_dir", root.size() == 1 && root.get(0).dir
                && root.get(0).full.replaceFirst("/$", "").equals("sample_dir"));
        List<ArchiveUtil.Entry> lv1 = a.listChildren("sample_dir/");
        boolean hasCn = false, hasNoise = false, hasEmpty = false;
        for (ArchiveUtil.Entry e : lv1) {
            if (e.name.startsWith("模块_") && e.dir) hasCn = true;
            if (e.name.equals("随机大文件") && e.dir) hasNoise = true;
            if (e.name.equals("空 目录") && e.dir) hasEmpty = true;
        }
        check("中文模块目录可见", hasCn);
        check("随机大文件目录可见", hasNoise);
        check("空目录可见", hasEmpty);
        check("format=TAR", "TAR".equals(a.format()));

        // 提取单个深层文件（模拟「查看/编辑」先提取到缓存的路径）
        List<ArchiveUtil.Entry> sub = a.listChildren("sample_dir/模块_1/资源/");
        check("深层文件列出", sub.size() == 50 && !sub.get(0).dir);
        File cache = new File("_archzst_cache"); delR(cache); cache.mkdirs();
        a.extractEntry(sub.get(0).full, cache);
        File got = new File(cache, sub.get(0).full);
        check("单条目提取落盘", got.isFile() && got.length() == sub.get(0).size);

        // 提取整个目录（模拟「提取到窗格」）
        File out1 = new File("_archzst_out1"); delR(out1); out1.mkdirs();
        a.extractEntry("sample_dir/模块_2", out1);
        check("目录提取=50 文件", countFiles(new File(out1, "sample_dir/模块_2")) == 50);

        // 解压全部
        File out2 = new File("_archzst_out2"); delR(out2); out2.mkdirs();
        a.extractAll(out2);
        check("解压全部=401 文件", countFiles(out2) == 401);
        check("空目录解出", new File(out2, "sample_dir/空 目录").isDirectory());
        a.close();

        // ===== 2. 裸 .zst 单条目伪归档 =====
        ArchiveUtil.Archive b = ArchiveUtil.open(bigZst);
        check("裸 zst 打开", b != null);
        check("format=ZST", "ZST".equals(b.format()));
        List<ArchiveUtil.Entry> single = b.listChildren("");
        check("单条目名=sample_big", single.size() == 1 && single.get(0).full.equals("sample_big")
                && !single.get(0).dir);
        check("子前缀无条目", b.listChildren("sample_big").isEmpty());
        File out3 = new File("_archzst_out3"); delR(out3); out3.mkdirs();
        b.extractAll(out3);
        File f3 = new File(out3, "sample_big");
        check("还原 300MB", f3.isFile() && f3.length() == 300L * 1024 * 1024);
        // extractEntry 带条目名（编辑器预览路径）
        File out4 = new File("_archzst_out4"); delR(out4); out4.mkdirs();
        b.extractEntry("sample_big", out4);
        check("按条目名提取一致", new File(out4, "sample_big").length() == 300L * 1024 * 1024);
        b.close();

        System.out.println("\n== ArchZstTest: " + pass + " pass, " + fail + " fail ==");
        delR(cache); delR(out1); delR(out2); delR(out3); delR(out4);
        System.exit(fail == 0 ? 0 : 1);
    }

    static int countFiles(File d) {
        int c = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) c += f.isDirectory() ? countFiles(f) : 1;
        return c;
    }

    static void delR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) delR(x);
        f.delete();
    }
}
