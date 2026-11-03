import java.util.*;

/**
 * v1.66.9 树合并算法桌面镜像测试（与 DexEditorActivity 的
 * Node/insertClass/sortNode/mergeChains 逻辑一致）：
 *  - 单链包合并为 java 包格式（com.baidu）
 *  - 分叉处保留层级、类节点永不合并
 *  - 合并后排序正确
 */
public class TreeMergeTest {
    static int pass = 0, fail = 0;

    static class Node {
        String name; String path; boolean isClass; boolean expanded; int depth;
        List<Node> children = new ArrayList<Node>();
    }

    static void insertClass(Node root, String cls) {
        Node n = root;
        String[] seg = cls.split("/");
        for (int i = 0; i < seg.length; i++) {
            boolean last = i == seg.length - 1;
            Node child = null;
            for (Node c : n.children) if (c.name.equals(seg[i])) { child = c; break; }
            if (child == null) {
                child = new Node();
                child.name = seg[i]; child.isClass = last; child.path = cls;
                n.children.add(child);
            }
            n = child;
        }
    }

    static final Comparator<String> CI = String.CASE_INSENSITIVE_ORDER;
    static void sortNode(Node n) {
        Collections.sort(n.children, new Comparator<Node>() {
            public int compare(Node a, Node b) {
                int c = CI.compare(a.name, b.name);
                return c != 0 ? c : a.name.compareTo(b.name);
            }
        });
        for (Node c : n.children) sortNode(c);
    }

    static void mergeChains(Node n) {
        for (Node c : n.children) mergeChains(c);
        for (int i = 0; i < n.children.size(); i++) {
            Node c = n.children.get(i);
            while (!c.isClass && c.children.size() == 1 && !c.children.get(0).isClass) {
                Node only = c.children.get(0);
                c.name = c.name + "." + only.name;
                c.children = only.children;
            }
        }
    }

    static List<String> flatten(Node n, int depth, List<String> out) {
        for (Node c : n.children) {
            c.depth = depth;
            out.add((c.isClass ? "[C]" : "[P]") + c.name);
            if (!c.isClass && c.expanded) flatten(c, depth + 1, out);
        }
        return out;
    }

    static Node build(String[] classes) {
        Node root = new Node();
        for (String cls : classes) insertClass(root, cls);
        sortNode(root);
        mergeChains(root);
        sortNode(root);
        return root;
    }

    static Node find(Node n, String name) {
        for (Node c : n.children) if (c.name.equals(name)) return c;
        return null;
    }

    static void chk(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name + (detail != null ? "  " + detail : "")); }
        else { fail++; System.out.println("[FAIL] " + name + (detail != null ? "  " + detail : "")); }
    }

    static void chk(String name, boolean ok) { chk(name, ok, null); }

    public static void main(String[] a) {
        // 1) 单链合并：com/baidu/Class1 + com/baidu/Class2 → 顶层只有 com.baidu
        Node t1 = build(new String[]{"com/baidu/Class1", "com/baidu/Class2"});
        chk("1.1 顶层单节点合并为 com.baidu", t1.children.size() == 1
                && "com.baidu".equals(t1.children.get(0).name), flatten(t1, 0, new ArrayList<String>()).toString());
        chk("1.2 com.baidu 展开直接见两个类", t1.children.get(0).children.size() == 2
                && t1.children.get(0).children.get(0).isClass);

        // 2) 部分单链：com/only/Deep/Alone 与 com/baidu/X、com/other/{Y,Z} 并存
        //    com 下有 3 个子包 → com 本身不合并；only→Deep 是包链 → 合并为 only.Deep
        Node t2 = build(new String[]{
                "com/only/Deep/Alone", "com/baidu/X", "com/other/Y", "com/other/Z"});
        Node com2 = find(t2, "com");
        chk("2.1 com 保留且含 3 个子包", t2.children.size() == 1 && com2 != null
                && com2.children.size() == 3,
                flatten(t2, 0, new ArrayList<String>()).toString());
        chk("2.2 only→Deep 包链合并为 only.Deep（类 Alone 在内）",
                find(com2, "only.Deep") != null
                && find(com2, "only.Deep").children.size() == 1
                && find(com2, "only.Deep").children.get(0).isClass);
        chk("2.3 baidu 保留（唯一子项是类，不与 com 合并）", find(com2, "baidu") != null);
        chk("2.4 分叉包 other 不合并", find(com2, "other") != null
                && find(com2, "other").children.size() == 2);

        // 3) 包-类混合链不合并：com/single/Class（末端是类）
        Node t3 = build(new String[]{"com/single/Class"});
        chk("3.1 末端类不合并", t3.children.size() == 1 && "com.single".equals(t3.children.get(0).name)
                && t3.children.get(0).children.size() == 1
                && t3.children.get(0).children.get(0).isClass,
                flatten(t3, 0, new ArrayList<String>()).toString());

        // 4) 根下直接类不受影响 + 深链合并
        Node t4 = build(new String[]{"MainActivity", "a/b/c/d/E", "a/b/c/d/F"});
        chk("4.1 根类保留", find(t4, "MainActivity") != null);
        chk("4.2 深单链合并 a.b.c.d", find(t4, "a.b.c.d") != null
                && find(t4, "a.b.c.d").children.size() == 2,
                flatten(t4, 0, new ArrayList<String>()).toString());

        // 5) 展开状态模拟：展开 com.baidu → flatten 位置测试（子项在父项之后）
        Node t5 = build(new String[]{"com/baidu/A1", "com/baidu/A2", "top/Z"});
        t5.expanded = true;   // root 展开
        find(t5, "com.baidu").expanded = true;
        List<String> f5 = flatten(t5, 0, new ArrayList<String>());
        int p1 = f5.indexOf("[P]com.baidu");
        chk("5.1 展开后子项紧跟父项", p1 == 0 && f5.get(1).startsWith("[C]") && f5.get(2).startsWith("[C]")
                && f5.get(3).equals("[P]top"),
                f5.toString());

        System.out.println("\nPASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
