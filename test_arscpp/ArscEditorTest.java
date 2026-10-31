import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * ArscEditor 桌面全链路测试：
 * 解析真实 APK 的 resources.arsc → 改值 + 改名 + 删除 + 新增 → 全量写回
 * → 重新解析校验（值/名字/删除/新增/总数/其余条目完好）。
 */
public class ArscEditorTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        byte[] arscBytes = extractArsc(args[0]);
        com.hex91.file.ArscFile file = com.hex91.file.ArscFile.parse(arscBytes);
        System.out.println("package=" + file.appPackage() + " entries=" + file.entryCount());

        // ---- 选目标条目 ----
        List<ResourceEntry> strings = com.hex91.file.ArscPlus.entriesOf(file, "string", "");
        check(!strings.isEmpty(), "string 条目存在 (" + strings.size() + ")");
        ResourceEntry victim = strings.get(0);          // 改值 + 改名都用它
        ResourceEntry victim2 = strings.size() > 1 ? strings.get(1) : null;
        String oldVal = com.hex91.file.ArscValues.valueDisplay(file, victim);
        String oldName = victim.getKeyName();

        // ---- 编辑计划（模式参数: 1=改值 2=+改名 3=+删除 4=+新增, 默认全部）----
        int mode = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        List<com.hex91.file.ArscWriter.Edit> edits = new ArrayList<com.hex91.file.ArscWriter.Edit>();
        List<com.hex91.file.ArscEditor.Op> ops = new ArrayList<com.hex91.file.ArscEditor.Op>();
        if (mode >= 1) edits.add(new com.hex91.file.ArscWriter.Edit(victim, oldVal + "EDIT"));            // 改值
        if (mode >= 2) edits.add(new com.hex91.file.ArscWriter.Edit(victim, "hex_edit_test_key", true));  // 改名
        if (victim2 != null && mode >= 3) {
            ops.add(com.hex91.file.ArscEditor.Op.delete(victim2));                          // 删除
        }
        String newKey = "hex_added_key_zz";
        String newVal = "hello from HexFile ARSC++ editor";
        if (mode >= 4) ops.add(com.hex91.file.ArscEditor.Op.addString("string", "", newKey, newVal));      // 新增

        // ---- 写回 ----
        byte[] out = com.hex91.file.ArscEditor.write(file, edits, ops);
        check(out != null && out.length > 0, "写回字节非空 (" + out.length + " bytes)");
        if (args.length > 2) {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(args[2]);
            fo.write(out); fo.close();
        }

        // ---- 重解析校验 ----
        com.hex91.file.ArscFile f2 = com.hex91.file.ArscFile.parse(out);
        int expectTotal = file.entryCount() + (mode >= 4 ? 1 : 0) - (victim2 != null && mode >= 3 ? 1 : 0);
        check(f2.entryCount() == expectTotal, "条目数符合 (" + f2.entryCount() + " / 期望 " + expectTotal + ")");

        boolean valOk = false, nameOk = false, oldGone = true, delOk = true, addOk = false;
        Set<String> seen = new HashSet<String>();
        int others = 0;
        for (ResourceEntry e : com.hex91.file.ArscPlus.entriesOf(f2, "string", "")) {
            String k = e.getKeyName();
            if (mode >= 2 && k.equals("hex_edit_test_key")) {
                nameOk = true;
                valOk = com.hex91.file.ArscValues.valueDisplay(f2, e).equals(oldVal + "EDIT");
            } else if (mode < 2 && k.equals(oldName)) {
                nameOk = true;
                valOk = com.hex91.file.ArscValues.valueDisplay(f2, e).equals(oldVal + "EDIT");
            }
            if (k.equals(oldName) && mode >= 2) {
                oldGone = false;
            }
            if (mode >= 4 && k.equals(newKey)) {
                addOk = ArxscValues0(f2, e).equals(newVal);
            }
            if (victim2 != null && mode >= 3 && k.equals(victim2.getKeyName())) {
                delOk = false;
            }
            if (!k.equals(oldName) && (victim2 == null || !k.equals(victim2.getKeyName()))
                    && !k.equals(newKey) && !k.equals("hex_edit_test_key")) {
                others++;
            }
            seen.add(k);
        }
        if (mode >= 2) check(nameOk, "改名生效: " + oldName + " → hex_edit_test_key");
        check(valOk, "改值生效: " + oldVal + " → " + oldVal + "EDIT");
        if (mode >= 2) check(oldGone, "旧名已消失");
        if (victim2 != null && mode >= 3) {
            check(delOk, "删除生效: " + victim2.getKeyName() + " 已移除");
        }
        if (mode >= 4) check(addOk, "新增生效: " + newKey + " = " + newVal);
        check(others > 0, "其余条目完好 (" + others + " 条)");

        // ---- 其余类型的条目总数不变（回归）----
        List<ResourceEntry> colors = com.hex91.file.ArscPlus.entriesOf(f2, "color", "");
        List<ResourceEntry> colors0 = com.hex91.file.ArscPlus.entriesOf(file, "color", "");
        check(colors.size() == colors0.size(), "color 条目数不变 (" + colors0.size() + ")");

        System.out.println("\n===== " + pass + " passed, " + fail + " failed =====");
        if (fail > 0) System.exit(1);
    }

    static String ArxscValues0(com.hex91.file.ArscFile f, ResourceEntry e) {
        return com.hex91.file.ArscValues.valueDisplay(f, e);
    }

    static byte[] extractArsc(String apk) throws Exception {
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.getName().equals("resources.arsc")) {
                    InputStream in = zf.getInputStream(ze);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    return bo.toByteArray();
                }
            }
        } finally { zf.close(); }
        throw new IllegalStateException("no resources.arsc");
    }
}
