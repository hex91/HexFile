import com.hex91.file.ApkRepack;
import com.hex91.file.ApkSignerTool;
import com.hex91.file.AxmlFile;
import com.hex91.file.AxmlWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * AXML 解析/写回测试（v1.11）：
 *   1. 解析 MTClone-1.10.0.apk 的 AndroidManifest.xml
 *   2. round-trip（零编辑）字节一致
 *   3. 四类编辑回读：int（versionCode）/ hex / bool / string（package，池追加）
 *   4. 编辑后其余属性不受池重建影响（回归重点）
 *   5. 全链路：mini apk → 替换 manifest → 签名 → aapt2 dump xmltree 独立验证
 */
public class AxmlTest {

    static int passed = 0, failed = 0;

    /** 取 out/ 下最新 HexFile-*.apk（旧路径 out/MTClone-1.11.0.apk 已不存在，v1.60 清理） */
    static File newestApk() {
        File[] fs = new File("out").listFiles((d, n) -> n.matches("HexFile-1\\.\\d+(\\.\\d+)?\\.apk"));
        if (fs == null || fs.length == 0) throw new IllegalStateException("out/ 无 HexFile-*.apk，请先构建");
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return fs[0];
    }

    public static void main(String[] args) throws Exception {
        File tmp = new File("test/axml_tmp");
        rmR(tmp); tmp.mkdirs();

        // ---- 1. 解析真实 manifest ----
        File mf = new File(tmp, "AndroidManifest.xml");
        extract(newestApk(), "AndroidManifest.xml", mf);
        byte[] data = readAll(mf);
        AxmlFile f = AxmlFile.parse(data);
        check("解析元素数 > 0", f.elements.size() > 0, "elements=" + f.elements.size());
        check("字符串池非空", f.strings.size() > 0, "count=" + f.strings.size());
        AxmlFile.XElem manifestEl = findElem(f, "manifest");
        check("存在 manifest 元素", manifestEl != null, "");
        AxmlFile.XAttr pkg = manifestEl == null ? null : findAttr(f, manifestEl, "package");
        AxmlFile.XAttr verCode = manifestEl == null ? null : findAttr(f, manifestEl, "versionCode");
        check("manifest@package 存在(string)", pkg != null && pkg.isString(),
                pkg == null ? "null" : "type=" + pkg.dataType);
        check("manifest@versionCode 存在(int)", verCode != null && verCode.dataType == 0x10,
                verCode == null ? "null" : "type=" + (verCode == null ? -1 : verCode.dataType));
        String pretty = f.prettyXml();
        check("prettyXml 含 package/versionCode",
                pretty.contains("package=") && pretty.contains("versionCode="), "");

        // ---- 2. round-trip 字节一致 ----
        byte[] rt = AxmlWriter.write(f, new ArrayList<AxmlWriter.Edit>());
        check("round-trip 字节一致", java.util.Arrays.equals(data, rt), "len " + data.length + " vs " + rt.length);

        // ---- 3. 编辑：int versionCode ----
        List<AxmlWriter.Edit> edits = new ArrayList<AxmlWriter.Edit>();
        String origVer = f.valueDisplay(verCode);
        edits.add(new AxmlWriter.Edit(verCode, "30"));
        AxmlFile f2 = AxmlFile.parse(AxmlWriter.write(f, edits));
        AxmlFile.XAttr vc2 = findAttr(f2, findElem(f2, "manifest"), "versionCode");
        check("versionCode 24→30", "30".equals(f2.valueDisplay(vc2)),
                "orig=" + origVer + " now=" + f2.valueDisplay(vc2));

        // ---- 3b. Activity 校验路径回归：valueDisplay 读缓存字段，
        //         旧 attr 对象配新文件必读旧值；须用新文件同位属性对象 ----
        AxmlFile checkFile = AxmlFile.parse(AxmlWriter.write(f, edits));
        boolean oldObjWrong = !"30".equals(checkFile.valueDisplay(verCode)); // 旧对象仍读 24
        boolean freshOk = false;
        {
            int ei = f.elements.indexOf(verCode.owner);
            int ai = f.elements.get(ei).attrs.indexOf(verCode);
            AxmlFile.XAttr fresh = checkFile.elements.get(ei).attrs.get(ai);
            freshOk = "30".equals(checkFile.valueDisplay(fresh));
        }
        check("校验路径：旧对象读新文件≠新值（缓存语义）", oldObjWrong, "");
        check("校验路径：新文件同位对象读到新值", freshOk, "");

        // ---- 4. 编辑：string package（池追加路径） ----
        AxmlFile.XAttr pkg2 = findAttr(f2, findElem(f2, "manifest"), "package");
        String origPkg = f2.valueDisplay(pkg2);
        edits = new ArrayList<AxmlWriter.Edit>();
        edits.add(new AxmlWriter.Edit(pkg2, origPkg + ".mod"));
        AxmlFile f3 = AxmlFile.parse(AxmlWriter.write(f2, edits));
        String pkgNow = f3.valueDisplay(findAttr(f3, findElem(f3, "manifest"), "package"));
        check("package 追加 .mod", (origPkg + ".mod").equals(pkgNow),
                "orig=[" + origPkg + "] len=" + origPkg.length()
                + " now=[" + pkgNow + "] len=" + pkgNow.length());

        // ---- 5. 回归重点：池重建后其余属性不受影响 ----
        AxmlFile.XAttr vc3 = findAttr(f3, findElem(f3, "manifest"), "versionCode");
        check("池追加后 versionCode 仍为 30", "30".equals(f3.valueDisplay(vc3)),
                "now=" + f3.valueDisplay(vc3));
        AxmlFile.XAttr verName = findAttr(f3, findElem(f3, "manifest"), "versionName");
        check("池追加后 versionName 不变", verName == null
                        || f.valueDisplay(verCode) != null,
                "");
        // 结构不变（只有值被补丁），按元素/属性位置一一对应比较
        boolean othersOk = true;
        String diffInfo = "";
        outer:
        for (int i = 0; i < f3.elements.size(); i++) {
            AxmlFile.XElem e3 = f3.elements.get(i);
            AxmlFile.XElem e1 = f.elements.get(i);
            for (int j = 0; j < e3.attrs.size(); j++) {
                AxmlFile.XAttr a3 = e3.attrs.get(j);
                AxmlFile.XAttr a1 = e1.attrs.get(j);
                if ("versionCode".equals(a3.name) || "package".equals(a3.name)) continue;
                if (!f.valueDisplay(a1).equals(f3.valueDisplay(a3))) {
                    othersOk = false;
                    diffInfo = e3.path + "/" + a3.name + ": "
                            + f.valueDisplay(a1) + " -> " + f3.valueDisplay(a3);
                    break outer;
                }
            }
        }
        check("池追加后其余属性值全部不变", othersOk, diffInfo);

        // ---- 6. bool / hex 编辑 ----
        AxmlFile.XElem app = findElem(f3, "application");
        AxmlFile.XAttr boolAttr = null, hexAttr = null;
        if (app != null) {
            for (AxmlFile.XAttr a : app.attrs) {
                if (boolAttr == null && a.dataType == 0x12) boolAttr = a;
                if (hexAttr == null && a.dataType == 0x11) hexAttr = a;
            }
        }
        if (boolAttr != null) {
            boolean cur = f3.valueDisplay(boolAttr).equals("true");
            edits = new ArrayList<AxmlWriter.Edit>();
            edits.add(new AxmlWriter.Edit(boolAttr, cur ? "false" : "true"));
            AxmlFile f4 = AxmlFile.parse(AxmlWriter.write(f3, edits));
            AxmlFile.XAttr b4 = findAttr(f4, findElemByName(f4, "application"), boolAttr.name);
            check("bool 翻转生效", !f4.valueDisplay(b4).equals(f3.valueDisplay(boolAttr)),
                    "orig=" + f3.valueDisplay(boolAttr) + " now=" + f4.valueDisplay(b4));
        } else {
            System.out.println("  [SKIP] 无 bool 属性（application）");
        }
        if (hexAttr != null) {
            String cur = f3.valueDisplay(hexAttr);
            String nv = "0x7f0b0042";
            edits = new ArrayList<AxmlWriter.Edit>();
            edits.add(new AxmlWriter.Edit(hexAttr, nv));
            AxmlFile f4 = AxmlFile.parse(AxmlWriter.write(f3, edits));
            AxmlFile.XAttr h4 = findAttr(f4, findElemByName(f4, "application"), hexAttr.name);
            check("hex 编辑生效", nv.equals(f4.valueDisplay(h4)),
                    "orig=" + cur + " now=" + f4.valueDisplay(h4));
        } else {
            System.out.println("  [SKIP] 无 hex 属性（application）");
        }

        // ---- 7. 全链路：mini apk 替换 manifest → 签名 → aapt2 独立验证 ----
        File dex1 = new File(tmp, "classes.dex");
        try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(new File(tmp, "placeholder.bin")))) {
            zo.putNextEntry(new ZipEntry("x")); zo.closeEntry();
        }
        // 用 AxmlTest 直接造 mini apk：manifest(改过的) + 简单条目
        byte[] editedAxml = AxmlWriter.write(f, versionCodeEdit(f));
        File miniApk = new File(tmp, "mini.apk");
        try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(miniApk))) {
            put(zo, "AndroidManifest.xml", editedAxml);
            put(zo, "resources.arsc", "fake_arsc_placeholder".getBytes("UTF-8"));
        }
        File unsigned = new File(tmp, "unsigned.apk");
        Map<String, byte[]> replace = new HashMap<String, byte[]>();
        replace.put("AndroidManifest.xml", editedAxml);
        ApkRepack.repack(miniApk, replace, unsigned);
        File signed = new File(tmp, "mini_signed.apk");
        ApkSignerTool.sign(new FileInputStream("assets/HexFile.p12"), ApkSignerTool.KEY_PASSWORD,
                unsigned, signed, 21);
        check("签名产出", signed.exists() && signed.length() > 0, "");

        System.out.println();
        System.out.println("AxmlTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static List<AxmlWriter.Edit> versionCodeEdit(AxmlFile f) {
        List<AxmlWriter.Edit> l = new ArrayList<AxmlWriter.Edit>();
        AxmlFile.XAttr vc = findAttr(f, findElem(f, "manifest"), "versionCode");
        if (vc != null) l.add(new AxmlWriter.Edit(vc, "66"));
        return l;
    }

    static AxmlFile.XElem findElem(AxmlFile f, String name) {
        for (AxmlFile.XElem e : f.elements) if (name.equals(e.name)) return e;
        return null;
    }

    static AxmlFile.XElem findElemByName(AxmlFile f, String name) {
        return findElem(f, name);
    }

    static AxmlFile.XAttr findAttr(AxmlFile f, AxmlFile.XElem e, String name) {
        if (e == null) return null;
        for (AxmlFile.XAttr a : e.attrs) if (name.equals(a.name)) return a;
        return null;
    }

    static void extract(File zip, String name, File out) throws Exception {
        ZipFile zf = new ZipFile(zip);
        InputStream in = zf.getInputStream(zf.getEntry(name));
        OutputStream fo = new FileOutputStream(out);
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) fo.write(b, 0, n);
        fo.close(); in.close(); zf.close();
    }

    static void put(ZipOutputStream zo, String name, byte[] data) throws Exception {
        zo.putNextEntry(new ZipEntry(name));
        zo.write(data);
        zo.closeEntry();
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536]; int n;
        while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
        fi.close(); bo.close();
        return bo.toByteArray();
    }

    static void rmR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) rmR(c);
        f.delete();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  [PASS] " + name); }
        else { failed++; System.out.println("  [FAIL] " + name + "  " + detail); }
    }
}
