import com.hex91.file.apk.AxmlFile;
import com.hex91.file.apk.AxmlWriter;
import com.hex91.file.core.ArscDefs;
import com.hex91.file.fs.ZipUtil;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * v1.66.3 修复验证：
 *  1) AxmlWriter.encodeUtf8 长度字段 >127 时须写双字节变长（此前低 7 位截断 → 池损坏）；
 *  2) 池数据区 4 字节对齐保持（v1.65.0 回归）；
 *  3) ZipUtil.entryNameSafe 写入侧穿越防护。
 * 用手工构造的 UTF-8 字符串池最小 AXML 做全链路：parse → 编辑追加长串 → write → 回读校验。
 */
public class AxmlUtf8Test {

    static int fail;

    public static void main(String[] args) throws Exception {
        // ---------- 构造 UTF-8 池 AXML：manifest 元素，1 个字符串属性 ----------
        String longVal = repeat("值AbC中文", 60);          // 300 chars / UTF-8 >127 字节
        String name1 = "manifest", name2 = "package";
        int[] ids = {0, 1, 2};                             // 池：manifest / package / longVal
        byte[] pool = utf8Pool(new String[]{name1, name2, longVal});

        byte[] elem = startElement(0, 1, 2);
        byte[] endEl = endElement();

        int total = 8 + pool.length + elem.length + endEl.length;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        put16(all, 0x0003); put16(all, 8); put32(all, total);
        all.write(pool); all.write(elem); all.write(endEl);
        byte[] axml = all.toByteArray();
        ArscDefs.putU32(axml, 4, axml.length);

        // ---------- 1) 解析校验 ----------
        AxmlFile f = AxmlFile.parse(axml);
        chk("1.1 池识别为 UTF-8", f.poolUtf8);
        chk("1.2 串数=3", f.strings.size() == 3, f.strings.size());
        AxmlFile.XAttr a = f.elements.get(0).attrs.get(0);
        chk("1.3 长串原值读回", longVal.equals(f.valueDisplay(a)));

        // ---------- 2) 编辑：换另一个 >127 的长串（触发池追加 + writeLen8） ----------
        String newVal = repeat("新XyZ测试", 70);           // 350 chars
        byte[] out = AxmlWriter.write(f, Arrays.asList(new AxmlWriter.Edit(a, newVal)));
        chk("2.1 输出整体 4 字节对齐", (out.length & 3) == 0, out.length);
        AxmlFile f2 = AxmlFile.parse(out);
        AxmlFile.XAttr a2 = f2.elements.get(0).attrs.get(0);
        chk("2.2 新长串读回一致", newVal.equals(f2.valueDisplay(a2)),
                String.valueOf(f2.valueDisplay(a2)).length() + " vs " + newVal.length());
        chk("2.3 未改的池串仍在", "manifest".equals(f2.strings.get(0))
                && "package".equals(f2.strings.get(1)));
        chk("2.4 池 chunk size 对齐且自洽",
                (ArscDefs.u32(out, f2.poolStart + 4) & 3) == 0);

        // ---------- 3) 短串编辑回归 ----------
        String shortNew = "com.example.short";
        byte[] out3 = AxmlWriter.write(f, Arrays.asList(new AxmlWriter.Edit(a, shortNew)));
        AxmlFile f3 = AxmlFile.parse(out3);
        chk("3.1 短串编辑正常", shortNew.equals(f3.valueDisplay(f3.elements.get(0).attrs.get(0))));

        // ---------- 4) entryNameSafe ----------
        chk("4.1 ../ 拒绝", !ZipUtil.entryNameSafe("../evil.so"));
        chk("4.2 中段 .. 拒绝", !ZipUtil.entryNameSafe("lib/../../evil.so"));
        chk("4.3 段等于 .. 拒绝", !ZipUtil.entryNameSafe("a/.."));
        chk("4.4 绝对路径拒绝", !ZipUtil.entryNameSafe("/system/x"));
        chk("4.5 null 拒绝", !ZipUtil.entryNameSafe(null));
        chk("4.6 正常路径放行", ZipUtil.entryNameSafe("lib/arm64-v8a/libx.so"));
        chk("4.7 反斜杠归一后放行", ZipUtil.entryNameSafe("assets\\sub\\a.txt"));

        System.out.println(fail == 0 ? "AxmlUtf8Test 全部通过" : ("失败 " + fail + " 项"));
        if (fail > 0) System.exit(1);
    }

    // ---------------- 手工构造 chunk ----------------

    /** UTF-8 字符串池 chunk（flags=UTF8_FLAG，无 styles） */
    static byte[] utf8Pool(String[] strings) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offs[i] = data.size();
            byte[] b = strings[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeLen8(data, strings[i].length());   // charLen
            writeLen8(data, b.length);              // byteLen
            data.write(b, 0, b.length);
            data.write(0);
        }
        while ((data.size() & 3) != 0) data.write(0);
        int stringsStart = 28 + strings.length * 4;
        int total = stringsStart + data.size();

        ByteArrayOutputStream po = new ByteArrayOutputStream();
        put16(po, 0x0001); put16(po, 28); put32(po, total);
        put32(po, strings.length); put32(po, 0);
        put32(po, ArscDefs.UTF8_FLAG); put32(po, stringsStart); put32(po, 0);
        for (int off : offs) put32(po, off);
        try { po.write(data.toByteArray()); } catch (Exception e) { throw new RuntimeException(e); }
        return po.toByteArray();
    }

    static void writeLen8(ByteArrayOutputStream bo, int len) {
        if (len > 0x7F) bo.write(0x80 | ((len >> 8) & 0x7F));
        bo.write(len & 0xFF);
    }

    /** START_ELEMENT：1 个字符串属性（name=attrName, value=attrVal 池索引） */
    static byte[] startElement(int nameIdx, int attrNameIdx, int attrValIdx) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        put16(bo, 0x0102); put16(bo, 16); put32(bo, 36 + 20);
        put32(bo, 1); put32(bo, 0xFFFFFFFF);          // line / comment
        put32(bo, 0xFFFFFFFF); put32(bo, nameIdx);    // ns / name
        put16(bo, 20); put16(bo, 20); put16(bo, 1);   // attrStart / attrSize / count
        put16(bo, 0); put16(bo, 0); put16(bo, 0);     // id / class / style idx
        put32(bo, 0xFFFFFFFF); put32(bo, attrNameIdx);
        put32(bo, attrValIdx);                        // rawValue
        put16(bo, 8); bo.write(0); bo.write(ArscDefs.TYPE_STRING);
        put32(bo, attrValIdx);                        // typed data
        return bo.toByteArray();
    }

    static byte[] endElement() {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        put16(bo, 0x0103); put16(bo, 16); put32(bo, 16);
        put32(bo, 1); put32(bo, 0xFFFFFFFF);
        return bo.toByteArray();
    }

    static void put16(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF); b.write((v >>> 8) & 0xFF);
    }

    static void put32(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF); b.write((v >>> 8) & 0xFF);
        b.write((v >>> 16) & 0xFF); b.write((v >>> 24) & 0xFF);
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static void chk(String name, boolean ok) { chk(name, ok, ""); }

    static void chk(String name, boolean ok, Object info) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name
                + (ok ? "" : "  << " + info));
        if (!ok) fail++;
    }
}
