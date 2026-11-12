import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.hex91.file.core.ArscDefs;
import com.hex91.file.apk.ArscFile;
import com.hex91.file.apk.ArscValues;
import jadx.core.xmlgen.entry.RawNamedValue;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * ARSC 编辑器「未保存修改预览」的桌面回归（v1.67.5）。
 *
 * <p>编辑器列表不再直接读 arsc（保存前不会被改动），而是用
 * {@link ArscValues#displayTyped} / {@link ArscValues#bagDisplay} 渲染待写值。
 * 这里保证：展示文本 → parseInput → displayTyped 是闭环，
 * 即「列表上看到的新值」就是「保存后读回来的值」。
 */
public class ArscPreviewTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        byte[] data = extractArsc(args.length > 0 ? args[0] : "test/huangguo.apk");
        ArscFile file = ArscFile.parse(data);
        System.out.println("package=" + file.appPackage());

        // ---- 1. 整数类值：展示 → 解析 → 再展示，必须闭环 ----
        int rtOk = 0, rtCheck = 0;
        List<ResourceEntry> all = new ArrayList<ResourceEntry>();
        for (ResourceEntry e : file.storage.getResources()) all.add(e);
        for (ResourceEntry e : all) {
            RawValue v = e.getSimpleValue();
            if (v == null || e.getNamedValues() != null) continue;
            int t = v.getDataType();
            if (!isIntLike(t)) continue;
            String shown = ArscValues.valueDisplay(file, e);
            Integer back = ArscValues.parseInput(t, shown, file);
            rtCheck++;
            if (back == null) {
                System.out.println("  解析失败 t=0x" + Integer.toHexString(t) + " 显示=[" + shown + "]");
                continue;
            }
            String shown2 = ArscValues.displayTyped(file, t, back.intValue());
            if (shown2.equals(shown)) rtOk++;
            else if (rtCheck <= 5) System.out.println("  不闭环 [" + shown + "] → " + back + " → [" + shown2 + "]");
        }
        check(rtCheck > 0 && rtOk == rtCheck, "整数/引用值 展示↔解析 闭环 (" + rtOk + "/" + rtCheck + ")");

        // ---- 2. 修改后的预览值 == 写盘后读回的值 ----
        ResourceEntry target = null;
        for (ResourceEntry e : all) {
            RawValue v = e.getSimpleValue();
            if (v != null && e.getNamedValues() == null && isIntLike(v.getDataType())) { target = e; break; }
        }
        check(target != null, "找到可编辑的整数条目");
        if (target != null) {
            int t = target.getSimpleValue().getDataType();
            int orig = target.getSimpleValue().getData();
            String shown = ArscValues.displayTyped(file, t, orig);
            Integer parsed = ArscValues.parseInput(t, shown, file);
            check(parsed != null
                            && ArscValues.displayTyped(file, t, parsed.intValue()).equals(shown),
                    "原值 展示→解析→展示 一致 [" + shown + "]");
            // 换一个值，确认预览文本随数据变化（不是恒读旧值）
            String alt = ArscValues.displayTyped(file, t, orig == 0 ? 1 : 0);
            check(!alt.equals(shown), "不同数据 → 不同预览文本 [" + alt + "]");
        }

        // ---- 3. bag 条目：overrides 只替换指定下标 ----
        ResourceEntry bag = null;
        int bagIdx = -1;
        for (ResourceEntry e : all) {
            List<RawNamedValue> nvs = e.getNamedValues();
            if (nvs == null || nvs.size() < 2) continue;
            for (int i = 0; i < nvs.size(); i++) {
                if (nvs.get(i).getRawValue().getDataType() == ArscDefs.TYPE_STRING) {
                    bag = e;
                    bagIdx = i;
                    break;
                }
            }
            if (bag != null) break;
        }
        if (bag == null) {
            System.out.println("SKIP 无可用 bag 样式条目");
        } else {
            List<RawNamedValue> nvs = bag.getNamedValues();
            String before = ArscValues.bagDisplay(file, bag);
            Map<Integer, String> ov = new HashMap<Integer, String>();
            ov.put(Integer.valueOf(bagIdx), "PREVIEW_XYZ");
            String after = ArscValues.bagDisplay(file, bag, ov);
            check(!after.equals(before), "bag 预览随 overrides 变化 (idx=" + bagIdx + ")");
            check(after.contains("PREVIEW_XYZ"), "bag 预览显示新值");
            int other = bagIdx == 0 ? nvs.size() - 1 : 0;
            if (other != bagIdx) {
                String untouched = ArscValues.rawDisplay(file, nvs.get(other).getRawValue());
                check(after.contains(untouched), "bag 未改项保持原值显示");
            }
            check(ArscValues.bagDisplay(file, bag, new HashMap<Integer, String>()).equals(before),
                    "空 overrides 与原始展示一致");
        }

        System.out.println();
        System.out.println("RESULT  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static boolean isIntLike(int t) {
        switch (t) {
            case ArscDefs.TYPE_INT_DEC:
            case ArscDefs.TYPE_INT_HEX:
            case ArscDefs.TYPE_INT_BOOLEAN:
            case ArscDefs.TYPE_INT_COLOR_ARGB8:
            case ArscDefs.TYPE_INT_COLOR_RGB8:
            case ArscDefs.TYPE_INT_COLOR_ARGB4:
            case ArscDefs.TYPE_INT_COLOR_RGB4:
            case ArscDefs.TYPE_FLOAT:
            case ArscDefs.TYPE_REFERENCE:
            case ArscDefs.TYPE_ATTRIBUTE:
            case ArscDefs.TYPE_DYNAMIC_REFERENCE:
            case ArscDefs.TYPE_DYNAMIC_ATTRIBUTE:
                return true;
            default:
                return false;
        }
    }

    private static byte[] extractArsc(String apk) throws Exception {
        ZipFile zf = new ZipFile(apk);
        try {
            ZipEntry ze = zf.getEntry("resources.arsc");
            InputStream in = zf.getInputStream(ze);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            zf.close();
        }
    }
}
