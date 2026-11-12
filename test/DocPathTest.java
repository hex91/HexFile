import com.hex91.file.fs.DocPath;

/**
 * v1.84.0 SAF documentId 的路径逻辑。
 *
 * <p>docId 完全由外部应用提供（{@code content://com.hex91.file.documents/...}），
 * 可以伪造 {@code /data/data/...} 或 {@code /sdcard/../../../data}。
 * 所以这里的每一条边界都是安全边界，不只是「好不好用」。</p>
 */
public class DocPathTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++;
            System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    static void eq(int expect, int got, String msg) {
        if (expect == got) {
            pass++;
            System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    public static void main(String[] args) {
        // ------------------------------------------------------------ normalize
        eqs("/sdcard/DCIM", DocPath.normalize("/sdcard/DCIM"), "普通绝对路径不变");
        eqs("/sdcard/DCIM", DocPath.normalize("/sdcard//DCIM/"), "重复斜杠与尾斜杠被折叠");
        eqs("/sdcard/DCIM", DocPath.normalize("  /sdcard/DCIM  "), "前后空白被裁掉");
        eqs("/", DocPath.normalize("/"), "根目录");
        eqs("/", DocPath.normalize("///"), "多个斜杠削到根");
        eqs("/", DocPath.normalize("/././"), "只有 . 段 → 根");
        ok(DocPath.normalize(null) == null, "null → null");
        ok(DocPath.normalize("") == null, "空串 → null");
        ok(DocPath.normalize("   ") == null, "纯空白 → null");
        ok(DocPath.normalize("sdcard/DCIM") == null, "相对路径 → null");
        ok(DocPath.normalize("/sdcard/../data/data") == null, "含 .. → 拒绝（目录穿越）");
        ok(DocPath.normalize("/sdcard/a/../../etc") == null, "中间含 .. → 拒绝");
        ok(DocPath.normalize("/sdcard/a\0b") == null, "含 \\0 → 拒绝（截断攻击）");

        // --------------------------------------------------------------- within
        ok(DocPath.within("/sdcard", "/sdcard"), "自身在范围内");
        ok(DocPath.within("/sdcard", "/sdcard/DCIM/a.jpg"), "子文件在范围内");
        ok(!DocPath.within("/sdcard", "/sdcard2/a.jpg"), "前缀相同但不是子目录 → 拒绝");
        ok(!DocPath.within("/sdcard", "/data/data/com.x"), "完全无关路径 → 拒绝");
        ok(DocPath.within("/", "/data/data/com.x"), "根为范围时任何绝对路径都算在内");
        ok(!DocPath.within("/sdcard/DCIM", "/sdcard"), "父目录不在子目录范围内");
        ok(!DocPath.within(null, "/sdcard"), "root 为 null → 拒绝");
        ok(!DocPath.within("/sdcard", null), "path 为 null → 拒绝");

        java.util.List<String> roots = new java.util.ArrayList<String>();
        roots.add("/sdcard");
        roots.add("/sdcard/HexFile");
        ok(DocPath.withinAny(roots, "/sdcard/HexFile/Trash/a.bin"), "多根：命中第二个根");
        ok(DocPath.withinAny(roots, "/sdcard/Music/x.mp3"), "多根：命中第一个根");
        ok(!DocPath.withinAny(roots, "/data/local/tmp/x"), "多根：都不命中 → 拒绝");
        ok(!DocPath.withinAny(roots, "/storage/1A2B/HexFile/x"), "多根：外置卡未列入 → 拒绝");
        ok(!DocPath.withinAny(null, "/sdcard/x"), "根列表为 null → 全部拒绝");

        // ------------------------------------------------------- parent/name/child
        eqs("/sdcard", DocPath.parent("/sdcard/DCIM"), "一级父路径");
        eqs("/", DocPath.parent("/sdcard"), "顶层父路径是根");
        eqs("/", DocPath.parent("/"), "根的父还是根（不越界）");
        eqs("DCIM", DocPath.name("/sdcard/DCIM"), "取末段");
        eqs("", DocPath.name("/"), "根没有名字");
        eqs("/sdcard/DCIM/a.jpg", DocPath.child("/sdcard/DCIM", "a.jpg"), "拼子项");
        eqs("/a.jpg", DocPath.child("/", "a.jpg"), "根下拼子项不多出斜杠");

        // --------------------------------------------------------------- sanitize
        eqs("a_b.jpg", DocPath.sanitize("a/b.jpg"), "斜杠被替换，不会造出子目录");
        eqs("a_b.jpg", DocPath.sanitize("a\\b.jpg"), "反斜杠同样替换");
        eqs("abc", DocPath.sanitize("  abc  "), "首尾空白被裁掉");
        ok(DocPath.sanitize("") == null, "空名 → null");
        ok(DocPath.sanitize("   ") == null, "空白名 → null");
        ok(DocPath.sanitize(".") == null, "「.」→ null");
        ok(DocPath.sanitize("..") == null, "「..」→ null");
        ok(DocPath.sanitize(null) == null, "null → null");
        eqs("a_b", DocPath.sanitize("a\0b"), "\\0 被替换");

        StringBuilder longCn = new StringBuilder();
        for (int i = 0; i < 200; i++) longCn.append('文');
        String cut = DocPath.sanitize(longCn.toString());
        ok(cut != null && cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= DocPath.MAX_NAME_BYTES, "超长中文名按 UTF-8 字节截断到 240 以内");
        ok(cut.length() > 0 && cut.length() < 200, "确实截短了");

        StringBuilder longAscii = new StringBuilder();
        for (int i = 0; i < 400; i++) longAscii.append('a');
        String cut2 = DocPath.sanitize(longAscii.toString());
        eq(240, cut2.length(), "ASCII 名截到 240 字符（1 字节 = 1 字符）");

        // ---------------------------------------------------------------- compare
        ok(DocPath.compare(true, "z", false, "a") < 0, "目录排在文件前（即使名字更大）");
        ok(DocPath.compare(false, "apple", false, "Banana") < 0,
                "同类型按名称不区分大小写（apple 在 Banana 前）");
        ok(DocPath.compare(false, "zebra", false, "Apple") > 0, "z 在 A 之后");
        ok(DocPath.compare(false, "a", false, "A") > 0, "忽略大小写相等时用原串兜底（a > A）");
        ok(DocPath.compare(false, "中文", false, "abc") > 0, "中文与 ASCII 可比");
        eq(0, DocPath.compare(false, "same.txt", false, "same.txt"), "同名同类型为 0");

        // ---------------------------------------------------------------- modeOf
        eq(DocPath.M_READ, DocPath.modeOf(null), "null → 只读");
        eq(DocPath.M_READ, DocPath.modeOf("r"), "r → 只读");
        eq(DocPath.M_READ, DocPath.modeOf("x"), "未知模式 → 保守只读");
        eq(DocPath.M_WRITE, DocPath.modeOf("w"), "w → 写+截断");
        eq(DocPath.M_WRITE, DocPath.modeOf("wt"), "wt → 写+截断");
        eq(DocPath.M_APPEND, DocPath.modeOf("wa"), "wa → 追加（不能截断）");
        eq(DocPath.M_RW, DocPath.modeOf("rw"), "rw → 读写");
        eq(DocPath.M_RWT, DocPath.modeOf("rwt"), "rwt → 读写+截断");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
