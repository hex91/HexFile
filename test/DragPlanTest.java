import com.hex91.file.DragPlan;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.88.0 双栏拖放判定。
 *
 * <p>用真实临时目录跑：{@link DragPlan#check} 依赖 exists()/isDirectory()，
 * 全部用假路径测会漏掉「目标不是目录」这类分支。</p>
 */
public class DragPlanTest {

    static int pass = 0, fail = 0;
    static File root;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int expect, int got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    static List<File> l(File... fs) {
        List<File> out = new ArrayList<File>();
        for (File f : fs) out.add(f);
        return out;
    }

    static File mk(String rel, boolean dir) {
        File f = new File(root, rel);
        if (dir) f.mkdirs();
        else {
            f.getParentFile().mkdirs();
            try {
                FileOutputStream o = new FileOutputStream(f);
                o.write(new byte[]{1, 2, 3});
                o.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return f;
    }

    public static void main(String[] args) throws Exception {
        root = new File("build/chk/dragtest");
        deleteAll(root);
        root.mkdirs();

        File srcdir = mk("A", true);
        File other = mk("B", true);
        File a1 = mk("A/a.txt", false);
        File a2 = mk("A/b", true);
        File inner = mk("A/b/inner", true);          // A 的子目录
        File b1 = mk("B/x.txt", false);
        mk("B/c.txt", false);

        // ---------------------------------------------------------- 正常
        DragPlan.Check c1 = DragPlan.check(l(a1), other);
        ok(c1.ok(), "普通文件拖到另一目录：可放");
        eq(1, c1.items.size(), "普通文件：1 项待传");
        eq(0, c1.skippedSame, "普通文件：无跳过");
        ok(c1.canDrop(), "canDrop() 与 ok() 一致");

        DragPlan.Check c2 = DragPlan.check(l(a1, a2), other);
        eq(2, c2.items.size(), "多文件/目录一起拖");

        // ---------------------------------------------------------- 拖回原目录
        DragPlan.Check c3 = DragPlan.check(l(a1), srcdir);
        ok(!c3.ok(), "拖回原目录：不可放");
        eq(1, c3.skippedSame, "拖回原目录：计一次跳过");
        ok(c3.reason.contains("已经在目标目录"), "拖回原目录：原因文案");

        // 混合：一个在目标目录里，一个不在 → 只传后者
        DragPlan.Check c4 = DragPlan.check(l(b1, a1), other);
        ok(c4.ok(), "混合：仍然可放");
        eq(1, c4.items.size(), "混合：只传不在目标目录的那一个");
        eq(1, c4.skippedSame, "混合：跳过 1 项");
        ok(c4.items.get(0).equals(a1), "混合：留下的是 a1");

        // ---------------------------------------------------------- 拖进自己
        DragPlan.Check c5 = DragPlan.check(l(a2), a2);
        ok(!c5.ok(), "文件夹拖进它自己：不可放");
        eq(1, c5.skippedSelf, "文件夹拖进它自己：计一次自我嵌套");
        ok(c5.reason.contains("它自己"), "自我嵌套：原因文案");

        DragPlan.Check c6 = DragPlan.check(l(a2), inner);
        ok(!c6.ok(), "文件夹拖进自己的子目录：不可放（否则递归爆炸）");
        eq(1, c6.skippedSelf, "子目录：计一次自我嵌套");

        // 反向（父目录拖进子目录里的文件所在目录）仍合法
        DragPlan.Check c7 = DragPlan.check(l(a1), inner);
        ok(c7.ok(), "文件拖进子目录：可放");

        // ---------------------------------------------------------- 非法目标
        DragPlan.Check c8 = DragPlan.check(l(a1), null);
        ok(!c8.ok(), "目标为 null（压缩包模式）：不可放");
        ok(c8.reason.contains("压缩包") || c8.reason.contains("文件夹"), "null 目标：原因文案");

        DragPlan.Check c9 = DragPlan.check(l(a1), b1);
        ok(!c9.ok(), "目标是文件而不是目录：不可放");

        DragPlan.Check c10 = DragPlan.check(l(a1), new File(root, "not-exist"));
        ok(!c10.ok(), "目标目录不存在：不可放");

        // ---------------------------------------------------------- 空源
        DragPlan.Check c11 = DragPlan.check(l(), other);
        ok(!c11.ok(), "空源：不可放");
        ok(c11.reason.contains("没有可拖动"), "空源：原因文案");

        // 源文件已被删除 → 静默忽略，不能抛异常
        DragPlan.Check c12 = DragPlan.check(l(new File(root, "ghost.txt")), other);
        ok(!c12.ok(), "源不存在：不可放");
        ok(c12.items.isEmpty(), "源不存在：清单为空");

        // ---------------------------------------------------------- isInside 前缀陷阱
        File bc = mk("A/bc", false);                 // 名字以 b 开头但不在 A/b 里
        ok(!DragPlan.isInside(bc, a2), "A/bc 不在 A/b 内（前缀不算包含）");
        ok(DragPlan.isInside(inner, a2), "A/b/inner 在 A/b 内");
        ok(!DragPlan.isInside(a2, a2), "自己不算在自己内");
        ok(!DragPlan.isInside(null, a2), "null 安全");
        ok(!DragPlan.isInside(inner, null), "父为 null 安全");

        // ---------------------------------------------------------- samePath
        ok(DragPlan.samePath(srcdir, new File(root, "A")), "同路径判定");
        ok(!DragPlan.samePath(a1, a2), "不同路径");
        ok(!DragPlan.samePath(null, a1), "null 安全");

        // ---------------------------------------------------------- 标题
        DragPlan.Check c13 = DragPlan.check(l(a1), other);
        ok(DragPlan.title(c13, other).contains("a.txt"), "单项标题含文件名");
        ok(DragPlan.title(c13, other).contains("B"), "单项标题含目标目录名");
        ok(DragPlan.title(c2, other).contains("2 项"), "多项标题含数量");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        deleteAll(root);
        if (fail > 0) System.exit(1);
    }

    static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteAll(c);
        f.delete();
    }
}
