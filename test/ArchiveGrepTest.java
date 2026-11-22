import com.hex91.file.apk.ArchiveGrep;
import com.hex91.file.apk.ArchiveUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v2.112.0（F-24）：包内内容搜索用例。
 *
 * <p>这类功能出错不会崩，只会「悄悄多读了几百 MB」或「把不该解压的东西解了」，
 * 所以三条都要钉：</p>
 * <ol>
 *   <li><b>命中与行号</b>：内容命中要给出正确的行号，同一文件多行命中要逐条返回（由 UI 聚合）；</li>
 *   <li><b>三道闸</b>：白名单外的条目不解压、声明超限的条目跳过、解压后实际超限的也跳过；</li>
 *   <li><b>可取消</b>：取消在每个条目边界生效，不留半截统计。</li>
 * </ol>
 */
public class ArchiveGrepTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int want, int got, String msg) {
        boolean v = want == got;
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    public static void main(String[] args) throws Exception {
        // ---------- 白名单（纯字符串，先测，不碰归档） ----------
        ok(ArchiveGrep.isTextName("a.txt"), "isTextName: .txt");
        ok(ArchiveGrep.isTextName("res/values/strings.XML"), "isTextName: 扩展名大小写不敏感");
        ok(ArchiveGrep.isTextName("classes.dex.smali"), "isTextName: .smali");
        ok(ArchiveGrep.isTextName("build.gradle"), "isTextName: .gradle");
        ok(!ArchiveGrep.isTextName("lib/arm64-v8a/libx.so"), "isTextName: .so 不在白名单");
        ok(!ArchiveGrep.isTextName("res/drawable/ic.png"), "isTextName: .png 不在白名单");
        ok(!ArchiveGrep.isTextName("classes.dex"), "isTextName: .dex 不在白名单");
        ok(!ArchiveGrep.isTextName(null), "isTextName: null → 否");

        File tmp = mkTempDir();
        try {
            File zip = new File(tmp, "s.zip");
            makeZip(zip);
            File tmpBase = new File(tmp, "grepbase");
            tmpBase.mkdirs();

            ArchiveUtil.Archive a = ArchiveUtil.open(zip);
            try {
                List<ArchiveUtil.Entry> all = a.listAll();

                // ---------- 命中与行号 ----------
                ArchiveGrep.Result r = ArchiveGrep.search(a, all, "needle", true, tmpBase, null, null);
                eq(2, r.hits.size(), "search: 同一文件两行命中各一条");
                int l1 = r.hits.size() > 0 ? r.hits.get(0).line : -1;
                int l2 = r.hits.size() > 1 ? r.hits.get(1).line : -1;
                eq(2, l1, "search: 第一条命中在第 2 行");
                eq(3, l2, "search: 第二条命中在第 3 行");
                ok(r.hits.size() > 0 && r.hits.get(0).text.contains("needle"),
                        "search: 命中行带原文");
                ok(r.error == null, "search: 正常路径没有诊断错误");

                // ---------- 白名单与体积闸 ----------
                ok(r.skippedType >= 1, "search: 二进制条目（.so）未参与解压");
                ok(r.skippedBig >= 1, "search: 声明超 4MB 的条目被跳过");
                ok(r.scanned >= 2, "search: 至少扫描了 strings.xml 与 note.txt");

                // ---------- 大小写 ----------
                ArchiveGrep.Result ru = ArchiveGrep.search(a, all, "NEEDLE", true, tmpBase, null, null);
                eq(2, ru.hits.size(), "search: ignoreCase=true 时大小写不敏感");
                ArchiveGrep.Result rc = ArchiveGrep.search(a, all, "NEEDLE", false, tmpBase, null, null);
                eq(0, rc.hits.size(), "search: ignoreCase=false 时不命中");

                // ---------- 无命中 ----------
                ArchiveGrep.Result rn = ArchiveGrep.search(a, all, "zzz-not-here", true, tmpBase, null, null);
                eq(0, rn.hits.size(), "search: 无命中返回空列表而不是报错");
                ok(rn.error == null, "search: 无命中不算错误");

                // ---------- 取消 ----------
                ArchiveGrep.Result rx = ArchiveGrep.search(a, all, "needle", true, tmpBase,
                        new ArchiveGrep.Cancel() {
                            public boolean cancelled() { return true; }
                        }, null);
                ok(rx.cancelled, "search: 取消标志立即生效");
                eq(0, rx.hits.size(), "search: 取消后没有命中");

                // ---------- 临时目录清理 ----------
                String[] left = tmpBase.list();
                eq(0, left == null ? 0 : left.length, "search: 结束后临时目录清空");
            } finally {
                a.close();
            }
        } finally {
            deleteTree(tmp);
        }

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 造样本

    static File makeZip(File f) throws Exception {
        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(f));
        try {
            put(zo, "res/values/strings.xml",
                    "<resources>\n  <string name=\"hello\">world</string>\n</resources>\n");
            put(zo, "assets/note.txt", "line1\nneedle line2\nneedle line3\n");
            // 白名单外：内容里有 needle，也不该被读到
            byte[] so = new byte[512];
            so[0] = 0;
            String s = "needle in binary";
            for (int i = 0; i < s.length(); i++) so[i + 1] = (byte) s.charAt(i);
            ZipEntry e = new ZipEntry("lib/arm64-v8a/libx.so");
            zo.putNextEntry(e);
            zo.write(so);
            zo.closeEntry();
            // 声明 5MB 的文本：必须在解压前就被体积闸拦住
            ZipEntry big = new ZipEntry("assets/big.txt");
            zo.putNextEntry(big);
            byte[] chunk = new byte[64 * 1024];
            for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) 'a';
            for (int i = 0; i < 80; i++) zo.write(chunk);   // 5MB
            zo.closeEntry();
        } finally {
            zo.close();
        }
        return f;
    }

    static void put(ZipOutputStream zo, String name, String text) throws Exception {
        zo.putNextEntry(new ZipEntry(name));
        zo.write(text.getBytes("UTF-8"));
        zo.closeEntry();
    }

    static File mkTempDir() throws Exception {
        File f = File.createTempFile("hf_archgrep", "");
        if (!f.delete() || !f.mkdirs()) throw new IllegalStateException("无法建临时目录");
        return f;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (int i = 0; i < cs.length; i++) deleteTree(cs[i]);
        f.delete();
    }
}
