import com.hex91.file.apk.ArchiveSearch;
import com.hex91.file.apk.ArchiveUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v2.16.0（F-21）：包内搜索用例。
 *
 * <p>搜索出错不会崩，只会「搜不到」或「把不该排前的排前面」，所以三类都要钉住：</p>
 * <ol>
 *   <li><b>匹配语义</b>：多词是 AND 不是 OR、{@code * ?} 是通配而不是字面量、
 *       大小写不敏感、Windows 反斜杠路径要能用；</li>
 *   <li><b>数据来源</b>：{@code listAll} 必须给出「全量且带目录」的条目表 ——
 *       漏了目录就没法从结果跳转，漏了深层条目就等于只能在根目录搜；</li>
 *   <li><b>排序与截断</b>：短名精确命中要排第一，截断要如实报告「总共多少条」。</li>
 * </ol>
 */
public class ArchiveSearchTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int want, int got, String msg) {
        boolean v = want == got;
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    static void eq(String want, String got, String msg) {
        boolean v = want == null ? got == null : want.equals(got);
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    public static void main(String[] args) throws Exception {
        // ---------- normalize ----------
        eq("a b", ArchiveSearch.normalize("  a   b  "), "normalize: 首尾空白与连续空格");
        eq("a b", ArchiveSearch.normalize("a\tb"), "normalize: Tab 当空白");
        eq("a b", ArchiveSearch.normalize("a\u3000b"), "normalize: 全角空格当空白（中文输入法常见）");
        eq("res/layout/a.xml", ArchiveSearch.normalize("res\\layout\\a.xml"),
                "normalize: 反斜杠转斜杠（粘贴 Windows 路径）");
        eq("", ArchiveSearch.normalize(null), "normalize: null → 空串");
        eq("", ArchiveSearch.normalize("   "), "normalize: 全空白 → 空串");

        // ---------- wild ----------
        ok(ArchiveSearch.wild("abc", "*"), "wild: * 匹配任意串");
        ok(ArchiveSearch.wild("", "*"), "wild: * 匹配空串");
        ok(ArchiveSearch.wild("abc", "a?c"), "wild: ? 匹配单个字符");
        ok(!ArchiveSearch.wild("ac", "a?c"), "wild: ? 不能匹配零个字符");
        ok(ArchiveSearch.wild("a/b/c.txt", "*b/c*"), "wild: * 可跨 / 匹配");
        ok(ArchiveSearch.wild("abcabc", "*abc"), "wild: 末尾星号（多段回退）");
        ok(ArchiveSearch.wild("xabc", "*abc*"), "wild: 前后星号");
        ok(!ArchiveSearch.wild("abc", "abc?"), "wild: 模式更长 → 不匹配");
        ok(ArchiveSearch.wild("abc", "abc"), "wild: 纯字面量相等");
        ok(!ArchiveSearch.wild("abd", "abc"), "wild: 字面量不等");
        // 文件名里常见的正则元字符必须当字面量
        ok(ArchiveSearch.wild("a(b)[c].txt", "*a(b)[c].txt*"), "wild: ()[] 当字面量而非正则");

        // ---------- matches ----------
        ok(ArchiveSearch.matches("res/layout/activity_main.xml", "activity"),
                "matches: 子串命中");
        ok(ArchiveSearch.matches("res/layout/activity_main.xml", "Activity"),
                "matches: 大写查询也命中（两边都转小写）");
        ok(!ArchiveSearch.matches("res/layout/activity_main.xml", "C++"),
                "matches: C++ 按字面量比较（不当正则），不命中也不抛");
        ok(ArchiveSearch.matches("res/layout/activity_main.xml", "res main"),
                "matches: 多词全命中（AND）");
        ok(!ArchiveSearch.matches("res/layout/activity_main.xml", "res zzz"),
                "matches: 有一个词不中 → 整条不中（不是 OR）");
        ok(ArchiveSearch.matches("res/layout/activity_main.xml", "*.xml"),
                "matches: 通配 *.xml");
        ok(!ArchiveSearch.matches("res/layout/activity_main.xml", "*.png"),
                "matches: 通配不匹配其他扩展名");
        ok(ArchiveSearch.matches("a/b/c.txt", "a\\b\\c.txt"),
                "matches: 粘贴的反斜杠路径可搜");
        ok(!ArchiveSearch.matches("a/b/c.txt", ""), "matches: 空查询不命中一切");
        ok(!ArchiveSearch.matches("", "a"), "matches: 空路径不命中");
        ok(!ArchiveSearch.matches(null, "a"), "matches: null 路径不崩");
        ok(ArchiveSearch.matches("中文/文件.txt", "文件"),
                "matches: 中文子串");

        // ---------- 真 zip：listAll 必须全量 ----------
        File dir = new File("test/tmp_search");
        deleteTree(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("无法建目录 " + dir);
        File zip = new File(dir, "sample.zip");
        makeZip(zip);

        ArchiveUtil.Archive a = ArchiveUtil.open(zip);
        List<ArchiveUtil.Entry> all = a.listAll();
        eq(9, all.size(), "listAll: 全量 9 条（含显式目录条目）");
        ArchiveUtil.Entry dirRes = find(all, "res/");
        ok(dirRes != null, "listAll: 含目录条目 res/");
        ok(dirRes != null && dirRes.dir, "listAll: res/ 标记为目录");
        ok(dirRes != null && "res".equals(dirRes.name), "listAll: 目录短名去掉尾斜杠");
        ArchiveUtil.Entry deep = find(all, "res/layout/activity_main.xml");
        ok(deep != null, "listAll: 含深层条目（不是只有根层）");
        ok(deep != null && !deep.dir, "listAll: 深层条目是文件");
        ok(deep != null && "activity_main.xml".equals(deep.name), "listAll: 深层短名只取最后一段");

        // ---------- filter：命中 / 排序 / 截断 ----------
        ArchiveSearch.Result r = ArchiveSearch.filter(all, "androidmanifest.xml", 0);
        eq(2, r.total, "filter: AndroidManifest.xml 两处（根 + res/raw）");
        ok(!r.truncated, "filter: 未被截断");
        ok(r.items.size() == 2, "filter: 返回两条");
        if (r.items.size() == 2) {
            eq("AndroidManifest.xml", r.items.get(0).full,
                    "filter: 短名精确命中的根条目排第一");
            eq("res/raw/AndroidManifest.xml", r.items.get(1).full,
                    "filter: 深层的同名文件排后");
        }

        r = ArchiveSearch.filter(all, "*.xml", 0);
        eq(4, r.total, "filter: *.xml 命中 4 个文件");
        if (r.items.size() == 4) {
            eq("AndroidManifest.xml", r.items.get(0).full, "filter: *.xml 第一仍是短名精确的根文件");
            eq("res/layout/main2.xml", r.items.get(1).full, "filter: 同级命中按路径长度排（短的在前）");
            eq("res/layout/activity_main.xml", r.items.get(3).full, "filter: 最长路径排最后");
        }

        r = ArchiveSearch.filter(all, "res layout", 0);
        eq(3, r.total, "filter: 多词 AND（res 且 layout）");
        ok(find(r.items, "res/layout/") != null, "filter: 目录本身也出现在结果里（能跳转）");

        r = ArchiveSearch.filter(all, "RES/LAYOUT", 0);
        eq(3, r.total, "filter: 大写查询同样命中");

        r = ArchiveSearch.filter(all, "res\\layout\\main2.xml", 0);
        eq(1, r.total, "filter: 反斜杠路径只命中一条");

        r = ArchiveSearch.filter(all, "*.db", 0);
        eq(1, r.total, "filter: 扩展名通配命中 assets/data/Search.db");

        r = ArchiveSearch.filter(all, "zzz-no-such", 0);
        eq(0, r.total, "filter: 无命中时 total=0");
        ok(r.items.isEmpty(), "filter: 无命中时列表为空");

        r = ArchiveSearch.filter(all, "  ", 0);
        eq(0, r.total, "filter: 全空白查询视为空");

        r = ArchiveSearch.filter(all, "*.xml", 2);
        eq(4, r.total, "filter: 截断时 total 仍是真实命中数");
        eq(2, r.items.size(), "filter: 截断后只留 2 条");
        ok(r.truncated, "filter: truncated 为真");

        r = ArchiveSearch.filter(all, "lib", 0);
        eq(1, r.total, "filter: 单段目录名可命中");
        ok(r.items.size() == 1 && "lib/armeabi-v7a/libfoo.so".equals(r.items.get(0).full),
                "filter: 命中带 / 的深层路径");

        // ---------- TAR：没有索引，靠顺序读一遍 ----------
        File tgz = new File(dir, "sample.tar.gz");
        makeTarGz(tgz);
        ArchiveUtil.Archive t = ArchiveUtil.open(tgz);
        List<ArchiveUtil.Entry> tall = t.listAll();
        eq(2, tall.size(), "tar.gz listAll: 全量 2 条");
        ok(find(tall, "sub/inner.txt") != null, "tar.gz listAll: 含子目录条目");
        ArchiveSearch.Result tr = ArchiveSearch.filter(tall, "inner", 0);
        eq(1, tr.total, "tar.gz 搜索: 命中子目录里的文件");
        t.close();

        // ---------- 空包 / 单条目 zst ----------
        File empty = new File(dir, "empty.zip");
        FileOutputStream fo = new FileOutputStream(empty);
        ZipOutputStream zo = new ZipOutputStream(fo);
        zo.close();
        ArchiveUtil.Archive ea = ArchiveUtil.open(empty);
        eq(0, ea.listAll().size(), "listAll: 空包给 0 条，不抛");
        eq(0, ArchiveSearch.filter(ea.listAll(), "a", 0).total, "filter: 空表安全");
        ea.close();

        a.close();

        deleteTree(dir);
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static ArchiveUtil.Entry find(List<ArchiveUtil.Entry> list, String full) {
        for (int i = 0; i < list.size(); i++) {
            if (full.equals(list.get(i).full)) return list.get(i);
        }
        return null;
    }

    static void makeZip(File f) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        put(z, "AndroidManifest.xml", "<?xml version=\"1.0\"?><manifest/>");
        putDir(z, "res/");
        putDir(z, "res/layout/");
        put(z, "res/layout/activity_main.xml", "<LinearLayout/>");
        put(z, "res/layout/main2.xml", "<FrameLayout/>");
        put(z, "res/raw/AndroidManifest.xml", "<manifest/>");
        put(z, "assets/data/Search.db", "SQLite format 3");
        put(z, "lib/armeabi-v7a/libfoo.so", "\u007fELF");
        put(z, "deep/target.txt", "deep");
        z.close();
    }

    static void put(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes("UTF-8"));
        z.closeEntry();
    }

    static void putDir(ZipOutputStream z, String name) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.closeEntry();
    }

    static void makeTarGz(File f) throws Exception {
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream t =
                new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
                        new java.util.zip.GZIPOutputStream(new FileOutputStream(f)));
        byte[] data = "tar".getBytes("UTF-8");
        org.apache.commons.compress.archivers.tar.TarArchiveEntry e1 =
                new org.apache.commons.compress.archivers.tar.TarArchiveEntry("top.txt");
        e1.setSize(data.length);
        t.putArchiveEntry(e1);
        t.write(data);
        t.closeArchiveEntry();
        org.apache.commons.compress.archivers.tar.TarArchiveEntry e2 =
                new org.apache.commons.compress.archivers.tar.TarArchiveEntry("sub/inner.txt");
        e2.setSize(data.length);
        t.putArchiveEntry(e2);
        t.write(data);
        t.closeArchiveEntry();
        t.close();
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) System.out.println("  (清理失败: " + f + ")");
    }
}
