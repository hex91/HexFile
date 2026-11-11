import com.hex91.file.DirReader;
import com.hex91.file.FileAdapter;
import com.hex91.file.FileItem;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * v1.96.0 目录读取（DirReader）。
 *
 * <p>这段逻辑原来在 {@code FileAdapter.setDir} 里、跑在 UI 线程上。搬到后台线程只是换了
 * 执行位置，规则一条没变 —— 所以测试盯的正是「搬的时候别把规则搬坏」：
 * 目录在前、大小写不敏感、隐藏文件、顶部 ".."、计数不含 ".."、按大小/时间排序的并列规则。</p>
 *
 * <p>能这样直接测，是因为 DirReader 刻意不碰 Android API（{@code privileged} 由调用方注入）。</p>
 */
public class DirReaderTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect.equals(got)) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 [" + expect + "] 实际 [" + got + "]"); }
    }

    static void eqi(int expect, int got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    /** 条目名拼接，便于一眼看出顺序 */
    static String names(DirReader.Result r) {
        StringBuilder sb = new StringBuilder();
        for (FileItem it : r.items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(it.name());
        }
        return sb.toString();
    }

    static File write(File dir, String name, int bytes, long mtime) throws Exception {
        File f = new File(dir, name);
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(new byte[bytes]); } finally { out.close(); }
        f.setLastModified(mtime);
        return f;
    }

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "hexfile_dirreader_" + System.nanoTime());
        File adir = new File(base, "adir");
        File bdir = new File(base, "Bdir");
        File hdir = new File(base, ".hiddendir");
        if (!adir.mkdirs() || !bdir.mkdirs() || !hdir.mkdirs()) {
            System.out.println("FAIL  无法创建临时目录 " + base);
            System.exit(1);
        }

        long now = System.currentTimeMillis();
        // 时间差用分钟级：某些文件系统（FAT / 网络盘）时间戳只有 2 秒粒度，用毫秒会飘
        write(base, "alpha.TXT", 5, now - 600000);
        write(base, "Beta.txt", 20, now - 300000);
        write(base, ".hidden", 1, now - 900000);
        adir.setLastModified(now - 1200000);
        bdir.setLastModified(now - 900000);
        hdir.setLastModified(now - 1500000);

        // ---------- 1) 默认：按名称，目录在前，大小写不敏感，隐藏项隐藏 ----------
        DirReader.Result r = DirReader.read(base, FileAdapter.SORT_NAME, false, null, false);
        eqs("..,adir,Bdir,alpha.TXT,Beta.txt", names(r), "按名称排序 + 目录在前 + 大小写不敏感");
        eqi(2, r.dirs, "目录计数不含 ..");
        eqi(2, r.files, "文件计数");
        eqi(5, r.items.size(), "条目数含顶部 ..");

        FileItem dotdot = r.items.get(0);
        // .. 条目的 file 存的是「上级目录」（点击直接 loadDir 它），不是当前目录
        ok(dotdot.upEntry && dotdot.file != null && base.getParentFile() != null
                && dotdot.file.getAbsolutePath()
                        .equals(base.getParentFile().getAbsolutePath()),
                "顶部 .. 指向上级目录");

        // ---------- 2) 显示隐藏文件 ----------
        r = DirReader.read(base, FileAdapter.SORT_NAME, true, null, false);
        eqs("..,.hiddendir,adir,Bdir,.hidden,alpha.TXT,Beta.txt", names(r),
                "显示隐藏文件（.hiddendir 按目录排在前面）");
        eqi(3, r.dirs, "隐藏目录计入目录数");
        eqi(3, r.files, "隐藏文件计入文件数");

        // ---------- 3) 按大小：目录仍然在前，文件小的在前 ----------
        r = DirReader.read(base, FileAdapter.SORT_SIZE, false, null, false);
        eqs("..,adir,Bdir,alpha.TXT,Beta.txt", names(r), "按大小升序（5 字节在 20 字节之前）");

        // ---------- 4) 按时间：新的在前 ----------
        r = DirReader.read(base, FileAdapter.SORT_TIME, false, null, false);
        eqs("..,Bdir,adir,Beta.txt,alpha.TXT", names(r), "按修改时间倒序");

        // ---------- 5) rootDir：应用根目录不显示 .. ----------
        r = DirReader.read(base, FileAdapter.SORT_NAME, false, base, false);
        eqs("adir,Bdir,alpha.TXT,Beta.txt", names(r), "到达应用根目录时没有 .. 返回项");
        eqi(4, r.items.size(), "根目录条目数");

        // ---------- 6) 非空但读不到（模拟 SELinux 拒绝 + 无 root）----------
        File missing = new File(base, "no_such_dir");
        r = DirReader.read(missing, FileAdapter.SORT_NAME, false, null, false);
        eqs("..", names(r), "读不到内容时只留 .. 返回项（不给假空目录）");
        eqi(0, r.dirs, "读不到时目录数为 0");
        eqi(0, r.files, "读不到时文件数为 0");

        // ---------- 7) 计数与 adapter 的 loading 占位行互不干扰 ----------
        FileItem loading = FileItem.loading();
        ok(loading.loadingRow, "占位行标记生效");
        eqs("读取中…", loading.name(), "占位行文案");
        ok(!loading.isDir(), "占位行不是目录（避免被当成 DIR 统计）");

        // ---------- 收尾 ----------
        deleteTree(base);

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
