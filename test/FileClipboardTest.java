import com.hex91.file.fs.FileClipboard;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v1.78.0 剪贴板回归（桌面 JVM，纯 Java）。
 *
 * <p>盯的是几件「用户会立刻发现」的事：</p>
 * <ul>
 *   <li>复制连粘多处时不被清空，剪切粘一次就得清；</li>
 *   <li>源文件在粘贴前被删掉时，不能整批报错、按钮上的数量也不能一直骗人；</li>
 *   <li>重复复制要覆盖而不是累加（否则点两次「复制」会变成 2 份）。</li>
 * </ul>
 */
public class FileClipboardTest {

    static int pass = 0, fail = 0;
    static final File ROOT = new File("build/cliptest");

    static void ok(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void eq(long expect, long got, String msg) {
        ok(expect == got, msg + "（期望 " + expect + "，实际 " + got + "）");
    }

    public static void main(String[] args) throws Exception {
        del(ROOT);
        ROOT.mkdirs();
        FileClipboard cb = FileClipboard.get();

        // ---------------------------------------------------------- 初始态
        cb.clear();
        ok(cb.isEmpty(), "初始：剪贴板为空");
        eq(0, cb.size(), "初始：size 为 0");
        ok(!cb.isMove(), "初始：不是剪切");
        ok("剪贴板为空".equals(cb.label()), "初始：label 为「剪贴板为空」");

        // ---------------------------------------------------------- 复制
        File a = file(new File(ROOT, "a.txt"), "aaa");
        File b = file(new File(ROOT, "b.txt"), "bbb");
        List<File> two = new ArrayList<File>();
        two.add(a);
        two.add(b);
        cb.put(two, false);
        eq(2, cb.size(), "复制：size 为 2");
        ok(!cb.isMove(), "复制：不是剪切");
        ok("复制 2 项".equals(cb.label()), "复制：label 为「复制 2 项」");
        ok(cb.stamp() > 0, "复制：记录了时间戳");

        // 复制后可以连粘：clearIfMove 不该动它
        cb.clearIfMove();
        eq(2, cb.size(), "复制：clearIfMove 后仍然保留（可连粘多处）");

        // ---------------------------------------------------------- 覆盖而非累加
        cb.put(Collections.singletonList(a), false);
        eq(1, cb.size(), "再次复制：覆盖旧内容，不累加");

        // ---------------------------------------------------------- 剪切
        cb.put(two, true);
        ok(cb.isMove(), "剪切：isMove 为 true");
        ok("剪切 2 项".equals(cb.label()), "剪切：label 为「剪切 2 项」");
        cb.clearIfMove();
        ok(cb.isEmpty(), "剪切：粘贴后 clearIfMove 清空（一次性）");
        ok(!cb.isMove(), "剪切：清空后 move 标记复位");

        // ---------------------------------------------------------- 空输入
        cb.put(new ArrayList<File>(), true);
        ok(cb.isEmpty(), "空列表：不会变成空的剪切");
        ok(!cb.isMove(), "空列表：move 被复位");
        eq(0, cb.stamp(), "空列表：时间戳为 0");

        // ---------------------------------------------------------- alive 过滤已删除
        File gone = file(new File(ROOT, "gone.txt"), "x");
        List<File> mixed = new ArrayList<File>();
        mixed.add(a);
        mixed.add(gone);
        cb.put(mixed, false);
        eq(2, cb.size(), "alive：放入 2 项");
        gone.delete();
        eq(1, cb.alive().size(), "alive：剔除已被删除的源文件");
        eq(2, cb.size(), "alive：不改变剪贴板本身");

        // ---------------------------------------------------------- prune 清掉过期项
        cb.prune();
        eq(1, cb.size(), "prune：过期项被剔除，数量同步");
        ok("复制 1 项".equals(cb.label()), "prune：label 也随之更新");

        b.delete();
        a.delete();
        cb.prune();
        ok(cb.isEmpty(), "prune：全部失效后剪贴板为空");
        ok(!cb.isMove(), "prune：全部失效后 move 复位");

        // ---------------------------------------------------------- files() 返回绝对路径
        File c = file(new File(ROOT, "c.txt"), "ccc");
        cb.put(Collections.singletonList(c), false);
        List<File> snap = cb.files();
        eq(1, snap.size(), "files：快照数量正确");
        ok(snap.get(0).getAbsolutePath().equals(c.getAbsolutePath()), "files：返回同一路径");

        // 快照与内部状态解耦：改快照不影响剪贴板
        snap.clear();
        eq(1, cb.size(), "files：清空快照不影响剪贴板");

        // ---------------------------------------------------------- 并发放取（不抛异常即可）
        final FileClipboard fcb = cb;
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 200; i++) {
                    fcb.put(Collections.singletonList(new File(ROOT, "x" + i)), false);
                    fcb.prune();
                    fcb.size();
                    fcb.label();
                }
            }
        });
        t.start();
        for (int i = 0; i < 200; i++) {
            cb.alive();
            cb.isMove();
        }
        t.join();

        cb.clear();
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 工具

    static File file(File f, String text) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        out.write(text.getBytes("UTF-8"));
        out.close();
        return f;
    }

    static void del(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) del(k);
        }
        f.delete();
    }
}
