import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * 完整复现真机 saveArscPlus 链路（v1.64.1 保存问题排查）：
 *   buildXml → 模拟用户改值 → applyXml → ArscWriter.write
 *   → ApkRepack.repack(替换 resources.arsc) → 从重打包后的 APK 重新读 arsc 校验
 */
public class SaveChainTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        String apkPath = args[0];
        byte[] arscBytes = extractArsc(apkPath);
        com.hex91.file.ArscFile file = com.hex91.file.ArscFile.parse(arscBytes);
        System.out.println("package=" + file.appPackage() + " entries=" + file.entryCount());

        for (String type : new String[]{"string", "color", "integer", "bool", "dimen"}) {
            List<ResourceEntry> entries = com.hex91.file.ArscPlus.entriesOf(file, type, "");
            if (entries.isEmpty()) { System.out.println("-- 跳过 " + type + " (无条目)"); continue; }

            // 1) 生成 XML（真机编辑器里展示的内容）
            String xml = com.hex91.file.ArscPlus.buildXml(file, type, "");
            // 2) 模拟用户：把第一个非空值后面加 XX
            String tag = "<" + type + " ";
            Pattern p = Pattern.compile("(" + Pattern.quote(tag) + "name=\"[^\"]+\">)([^<]{1,200})(</" + type + ">)");
            Matcher m = p.matcher(xml);
            if (!m.find()) { System.out.println("-- 跳过 " + type + " (无可文本编辑的值)"); continue; }
            String name = attrName(m.group(1));
            String oldVal = m.group(2);
            // 数值类型用合法变换（模拟用户正常编辑）；string/color 追加 XX
            String newVal;
            if (type.equals("integer") || type.equals("dimen") || type.equals("bool")) {
                String v = oldVal.trim();
                try {
                    if (type.equals("bool")) {
                        newVal = v.equals("true") ? "false" : "true";
                    } else if (type.equals("dimen")) {
                        java.util.regex.Matcher dm = Pattern.compile("^([0-9.]+)(\\S*)$").matcher(v);
                        if (!dm.find()) { System.out.println("-- 跳过 " + type + " (无法构造合法值)"); continue; }
                        newVal = (Float.parseFloat(dm.group(1)) + 1) + dm.group(2);
                    } else {
                        newVal = String.valueOf(Integer.decode(v) + 1);
                    }
                } catch (Exception num) { System.out.println("-- 跳过 " + type + " (值不可解析: " + v + ")"); continue; }
            } else {
                newVal = oldVal + "XX";
            }
            String edited = m.replaceFirst(Matcher.quoteReplacement(m.group(1) + newVal + m.group(3)));

            // 3) 真机保存链路
            List<com.hex91.file.ArscWriter.Edit> edits =
                    com.hex91.file.ArscPlus.applyXml(file, type, "", edited);
            check(edits.size() == 1, type + ": 改值产生 1 条修改 (" + edits.size() + ")  name=" + name);

            if (edits.size() == 1) {
                byte[] newArsc = com.hex91.file.ArscWriter.write(file, edits);

                // 4) ApkRepack 重打包（写临时 apk，模拟真机 cacheDir/arscpp_save.apk）
                File tmp = File.createTempFile("arscpp_save", ".apk");
                tmp.delete();
                Map<String, byte[]> replace = new HashMap<String, byte[]>();
                replace.put("resources.arsc", newArsc);
                com.hex91.file.ApkRepack.repack(new File(apkPath), replace, tmp);

                // 5) 从重打包后的 APK 重新读 arsc 校验
                byte[] reread = extractArsc(tmp.getAbsolutePath());
                com.hex91.file.ArscFile f2 = com.hex91.file.ArscFile.parse(reread);
                boolean updated = false;
                int others = 0;
                for (ResourceEntry e2 : com.hex91.file.ArscPlus.entriesOf(f2, type, "")) {
                    if (e2.getKeyName().equals(name)) {
                        updated = ArcsHelper_never(e2, f2, newVal);
                    } else others++;
                }
                check(updated, type + ": 重打包后 APK 内值已更新 " + name + " → " + newVal);
                check(others > 0, type + ": 其余条目仍在 (" + others + " 条)");
                check(f2.entryCount() == file.entryCount(), "条目总数不变 "
                        + f2.entryCount() + "/" + file.entryCount());
                tmp.delete();
            }
        }

        System.out.println("\n===== " + pass + " passed, " + fail + " failed =====");
        if (fail > 0) System.exit(1);
    }

    static boolean ArcsHelper_never(ResourceEntry e, com.hex91.file.ArscFile f2, String oldVal) {
        return com.hex91.file.ArscValues.valueDisplay(f2, e).equals(oldVal + "XX");
    }

    static String attrName(String openTag) {
        int a = openTag.indexOf("name=\"") + 6;
        return openTag.substring(a, openTag.indexOf('"', a));
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
        throw new IllegalStateException("no resources.arsc in " + apk);
    }
}
