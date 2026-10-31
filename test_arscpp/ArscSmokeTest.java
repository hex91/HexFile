import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.core.xmlgen.entry.ResourceEntry;

/** ARSC++ 桌面冒烟测试：真 APK 的 resources.arsc → buildXml → 改值 → applyXml → 校验编辑列表 */
public class ArscSmokeTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        byte[] arscBytes = extractArsc(args[0]);
        com.hex91.file.ArscFile file = com.hex91.file.ArscFile.parse(arscBytes);
        System.out.println("package=" + file.appPackage() + " entries=" + file.entryCount());

        // 1) string 默认配置：buildXml 行数与条目数一致
        List<ResourceEntry> entries = com.hex91.file.ArscPlus.entriesOf(file, "string", "");
        String xml = com.hex91.file.ArscPlus.buildXml(file, "string", "");
        int tags = count(xml, "<string ");
        System.out.println("string(default) entries=" + entries.size() + " xmlTags=" + tags);
        check(entries.size() > 0 && tags == entries.size(),
                "buildXml 输出与条目数一致 (" + tags + "/" + entries.size() + ")");

        // 2) 原样回读：0 修改
        List<com.hex91.file.ArscWriter.Edit> e0 =
                com.hex91.file.ArscPlus.applyXml(file, "string", "", xml);
        check(e0.isEmpty(), "未编辑的 XML 回读 0 修改 (实际 " + e0.size() + ")");

        // 3) 改第一个字符串值 → 1 条 Edit，且指向正确条目、值正确
        Pattern p = Pattern.compile("(<string name=\"[^\"]+\">)([^<]*)(</string>)");
        Matcher m = p.matcher(xml);
        check(m.find(), "找到可改的 string 条目");
        String name = attrName(m.group(1));
        String oldVal = m.group(2);
        String xml2 = m.replaceFirst(m.group(1) + oldVal + "XX" + m.group(3));
        List<com.hex91.file.ArscWriter.Edit> e1 =
                com.hex91.file.ArscPlus.applyXml(file, "string", "", xml2);
        check(e1.size() == 1, "改 1 个值 → 1 条修改 (实际 " + e1.size() + ")");
        if (e1.size() == 1) {
            ResourceEntry re = e1.get(0).entry;
            check(re.getKeyName().equals(name), "修改指向条目 " + re.getKeyName() + " (期望 " + name + ")");
            // 端到端：真实写回 arsc → 重新解析 → 值已更新
            byte[] newArsc = com.hex91.file.ArscWriter.write(file, e1);
            com.hex91.file.ArscFile f2 = com.hex91.file.ArscFile.parse(newArsc);
            boolean updated = false;
            for (ResourceEntry e2 : com.hex91.file.ArscPlus.entriesOf(f2, "string", "")) {
                if (e2.getKeyName().equals(name)) {
                    updated = com.hex91.file.ArscValues.valueDisplay(f2, e2).equals(oldVal + "XX");
                }
            }
            check(updated, "写回后重解析值已更新: " + name + " → " + oldVal + "XX");
        }

        // 4) 资源名写错 → 报错
        String xml3 = xml.replaceFirst("name=\"" + Pattern.quote(name) + "\"", "name=\"no_such_key\"");
        boolean threw = false;
        try {
            com.hex91.file.ArscPlus.applyXml(file, "string", "", xml3);
        } catch (Exception ex) {
            threw = ex.getMessage().contains("找不到资源");
        }
        check(threw, "未知资源名报「找不到资源」");

        // 5) bag 类型（style）出现注释占位
        String sx = com.hex91.file.ArscPlus.buildXml(file, "style", "");
        check(sx.contains("bag/复杂条目"), "style 类型含 bag 注释占位");

        // 6) 其他常见类型回读 0 修改（integer/bool/color/id 若存在）
        for (String t : new String[]{"integer", "bool", "color", "id", "dimen"}) {
            List<ResourceEntry> es = com.hex91.file.ArscPlus.entriesOf(file, t, "");
            if (es.isEmpty()) continue;
            String tx = com.hex91.file.ArscPlus.buildXml(file, t, "");
            List<com.hex91.file.ArscWriter.Edit> te =
                    com.hex91.file.ArscPlus.applyXml(file, t, "", tx);
            check(te.isEmpty(), t + " 回读 0 修改 (" + es.size() + " 条)");
        }

        System.out.println("\n===== " + pass + " passed, " + fail + " failed =====");
        if (fail > 0) System.exit(1);
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
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
