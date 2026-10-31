import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.core.xmlgen.entry.ResourceEntry;

/** XML 属性级端到端：rename/delete/add 属性 → applyXml → ArscEditor.write → 重解析校验 */
public class ArscXmlOpsTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        byte[] arscBytes = extractArsc(args[0]);
        com.hex91.file.ArscFile file = com.hex91.file.ArscFile.parse(arscBytes);

        List<ResourceEntry> strings = com.hex91.file.ArscPlus.entriesOf(file, "string", "");
        ResourceEntry e0 = strings.get(0);
        ResourceEntry e1 = strings.get(1);
        String n0 = e0.getKeyName(), n1 = e1.getKeyName();
        String v0 = com.hex91.file.ArscValues.valueDisplay(file, e0);

        // 1) 生成 XML（带用法提示头）
        String xml = com.hex91.file.ArscPlus.buildXml(file, "string", "");
        check(xml.contains("ARSC++ 编辑"), "buildXml 含用法提示注释");

        // 2) 用户编辑：改值 + 改名 + 删除 + 新增
        xml = xml.replaceFirst("(name=\"" + Pattern.quote(n0) + "\">)" + Pattern.quote(v0) + "(</string>)",
                "$1" + v0 + "XML$2");
        xml = xml.replaceFirst("name=\"" + Pattern.quote(n0) + "\"",
                "name=\"" + n0 + "\" rename=\"renamed_key_x\"");
        xml = xml.replaceFirst("    <string name=\"" + Pattern.quote(n1) + "\">[^<]*</string>\n",
                "    <string name=\"" + n1 + "\" delete=\"true\">x</string>\n");
        xml = xml.replace("</resources>",
                "    <string name=\"brand_new_key\" add=\"true\">added via xml</string>\n</resources>");

        // 3) applyXml → ArscEditor
        List<com.hex91.file.ArscEditor.Op> ops = new ArrayList<com.hex91.file.ArscEditor.Op>();
        List<com.hex91.file.ArscWriter.Edit> edits =
                com.hex91.file.ArscPlus.applyXml(file, "string", "", xml, ops);
        check(edits.size() == 2, "XML → 2 条值级编辑 (实际 " + edits.size() + ")"); // 改值 + 改名
        check(ops.size() == 2, "XML → 2 条结构化操作 (实际 " + ops.size() + ")");    // 删除 + 新增

        byte[] out = com.hex91.file.ArscEditor.write(file, edits, ops);

        // 4) 重解析校验
        com.hex91.file.ArscFile f2 = com.hex91.file.ArscFile.parse(out);
        boolean valOk = false, renOk = false, delOk = true, addOk = false;
        for (ResourceEntry e : f2.storage.getResources()) {
            if (!"string".equals(e.getTypeName()) || !"".equals(e.getConfig())) continue;
            String k = e.getKeyName();
            if (k.equals("renamed_key_x")) {
                renOk = true;
                valOk = com.hex91.file.ArscValues.valueDisplay(f2, e).equals(v0 + "XML");
            } else if (k.equals(n1)) delOk = false;
            else if (k.equals("brand_new_key")) addOk = com.hex91.file.ArscValues.valueDisplay(f2, e).equals("added via xml");
        }
        check(renOk, "rename 属性生效");
        check(valOk, "改值与改名共存生效: " + v0 + "XML");
        check(delOk, "delete 属性生效: " + n1 + " 已移除");
        check(addOk, "add 属性生效: brand_new_key");

        System.out.println("\n===== " + pass + " passed, " + fail + " failed =====");
        if (fail > 0) System.exit(1);
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
