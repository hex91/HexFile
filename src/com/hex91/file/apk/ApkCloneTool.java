package com.hex91.file;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 共存：修改 AndroidManifest.xml 的 package 属性 + 同步替换 authorities、
 * permission 等引用了原包名的字符串值 → 输出新清单字节，由调用方 repack + 签名。
 */
public final class ApkCloneTool {

    public static class Result {
        public byte[] newManifest;   // 修改后的 AndroidManifest.xml 字节
        public String oldPkg, newPkg;
        public int replaced;         // 替换的属性数（含 package 本身）
    }

    /** 读取 APK 当前包名（失败返回 null） */
    public static String currentPackage(File apk) {
        try {
            AxmlFile axml = AxmlFile.parse(readZipEntry(apk, "AndroidManifest.xml"));
            AxmlFile.XAttr a = pkgAttr(axml);
            return a == null ? null : axml.valueDisplay(a);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 生成共存清单：newPkg 为新包名 */
    public static Result buildClonedManifest(File apk, String newPkg) throws Exception {
        byte[] data = readZipEntry(apk, "AndroidManifest.xml");
        if (data == null) throw new IllegalStateException("APK 内未找到 AndroidManifest.xml");
        AxmlFile axml = AxmlFile.parse(data);

        AxmlFile.XAttr pkgAttr = pkgAttr(axml);
        if (pkgAttr == null) throw new IllegalStateException("manifest 缺少 package 属性");
        String oldPkg = axml.valueDisplay(pkgAttr);
        if (oldPkg == null || oldPkg.isEmpty())
            throw new IllegalStateException("package 属性值读取失败");
        if (newPkg.equals(oldPkg)) throw new IllegalStateException("新包名与原包名相同");

        List<AxmlWriter.Edit> edits = new ArrayList<AxmlWriter.Edit>();
        edits.add(new AxmlWriter.Edit(pkgAttr, newPkg));
        int replaced = 1;
        // authorities / permission / meta-data 等字符串值中含原包名 → 同步替换
        for (AxmlFile.XElem el : axml.elements) {
            if (el == pkgAttr.owner) continue;
            for (AxmlFile.XAttr a : el.attrs) {
                if (!a.isString()) continue;
                String v = axml.valueDisplay(a);
                if (v != null && v.contains(oldPkg)) {
                    edits.add(new AxmlWriter.Edit(a, v.replace(oldPkg, newPkg)));
                    replaced++;
                }
            }
        }

        byte[] newAxml = AxmlWriter.write(axml, edits);

        // 回读校验：写回不改结构，元素/属性顺序不变 → 按原索引取新文件同位属性。
        // 注意不能用 contains(oldPkg) 判残留——新包名通常以旧包名为前缀，子串必然存在。
        AxmlFile check = AxmlFile.parse(newAxml);
        AxmlFile.XAttr cpkg = pkgAttr(check);
        if (cpkg == null || !newPkg.equals(check.valueDisplay(cpkg)))
            throw new IllegalStateException("回读校验失败: package 未生效");
        for (int i = 1; i < edits.size(); i++) {
            AxmlWriter.Edit ed = edits.get(i);
            int ei = axml.elements.indexOf(ed.attr.owner);
            int ai = axml.elements.get(ei).attrs.indexOf(ed.attr);
            AxmlFile.XAttr fresh = check.elements.get(ei).attrs.get(ai);
            String now = check.valueDisplay(fresh);
            if (!ed.newValue.equals(now))
                throw new IllegalStateException("回读校验失败: " + axml.attrDisplayName(ed.attr)
                        + " 期望 " + ed.newValue + " 实际 " + now);
        }
        // 残留检查：不存在任何「精确等于原包名」的属性值（完整值替换才算替换成功）
        for (AxmlFile.XElem el : check.elements) {
            for (AxmlFile.XAttr a : el.attrs) {
                String v = check.valueDisplay(a);
                if (oldPkg.equals(v))
                    throw new IllegalStateException("残留未替换: " + check.attrDisplayName(a));
            }
        }

        Result r = new Result();
        r.newManifest = newAxml;
        r.oldPkg = oldPkg;
        r.newPkg = newPkg;
        r.replaced = replaced;
        return r;
    }

    /** manifest 根元素的 package 属性（ns 为空、name == "package"） */
    private static AxmlFile.XAttr pkgAttr(AxmlFile axml) {
        for (AxmlFile.XElem el : axml.elements) {
            if (!"manifest".equals(el.name)) continue;
            for (AxmlFile.XAttr a : el.attrs)
                if ("package".equals(a.name) && a.ns == null) return a;
            return null;
        }
        return null;
    }

    public static byte[] readZipEntry(File zip, String entryName) throws Exception {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) return null;
            InputStream in = zf.getInputStream(ze);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
    }

    private ApkCloneTool() {}
}
