import com.hex91.file.apk.SplitPackage;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v2.13.0（F-03）：分包安装包的解析用例。
 *
 * <p>要锁住的都是「真机上一句话说不清、事后极难复现」的那种：
 * 同一个包在 bundletool / APKPure / APKMirror 三家打包器下条目位置不同
 * （{@code splits/} 子目录、平铺、带 obb），解析必须一律认得；
 * 以及解包时的文件名清洗 —— 只要漏一条，解包目录里就会出现
 * {@code ../} 或控制字符，而这类问题在 Android 上表现为「安装器报无效包」，
 * 根本看不出是文件名的问题。</p>
 */
public class SplitPackageTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File("test/_split_t");
        deleteAll(dir);
        dir.mkdirs();

        // ---- 扩展名
        ok(SplitPackage.isSplitName("a.apks") && SplitPackage.isSplitName("b.xapk")
                        && SplitPackage.isSplitName("c.apkm"),
                "isSplitName 认 .apks / .xapk / .apkm");
        ok(!SplitPackage.isSplitName("a.apk") && !SplitPackage.isSplitName("a.zip")
                        && !SplitPackage.isSplitName("apks"),
                "isSplitName 不误认 .apk / .zip / 无扩展名");
        ok("XAPK".equals(SplitPackage.formatName("x.xapk"))
                        && "APKS".equals(SplitPackage.formatName("x.apks"))
                        && "APKM".equals(SplitPackage.formatName("x.apkm"))
                        && SplitPackage.formatName("x.apk") == null,
                "formatName 只给分包格式命名");

        // ---- bundletool 风格：splits/ 子目录 + toc.pb
        File apks = new File(dir, "bundle.apks");
        zip(apks, new String[]{
                "toc.pb",
                "splits/base-master.apk",
                "splits/base-arm64_v8a.apk",
                "splits/base-xxhdpi.apk",
        }, new String[]{"toctoc", "BASEMASTER", "ARM64", "XXHDPI"});
        SplitPackage.Contents c1 = SplitPackage.scan(apks);
        ok(c1.apks.size() == 3 && c1.splitCount() == 2,
                "bundletool .apks：3 个 apk → 2 个分包（实得 " + c1.apks.size() + "）");
        ok(c1.other == 1 && c1.obb.isEmpty(),
                "toc.pb 计进「其他条目」，不误算成 apk");
        ok(c1.apkBytes == 10L + 5L + 6L,   // BASEMASTER + ARM64 + XXHDPI
                "apkBytes 累加未压缩字节（实得 " + c1.apkBytes + "）");
        ok(c1.fileBytes == apks.length(), "fileBytes 记录包自身大小");

        // ---- xapk 风格：平铺 + obb + manifest.json
        File xapk = new File(dir, "game.xapk");
        zip(xapk, new String[]{
                "manifest.json",
                "com.demo.game.apk",
                "config.arm64_v8a.apk",
                "Android/obb/com.demo.game/main.1.com.demo.game.obb",
        }, new String[]{"{}", "BASE", "ARM", "OBB"});
        SplitPackage.Contents c2 = SplitPackage.scan(xapk);
        ok(c2.apks.size() == 2 && c2.splitCount() == 1, "xapk 平铺结构：2 个 apk、1 个分包");
        ok(c2.obb.size() == 1, "obb 单独归类（不混进 apk、不混进 other）");
        ok(c2.other == 1, "manifest.json 计进 other");

        // ---- 没有 apk 的 zip
        File empty = new File(dir, "noapk.apkm");
        zip(empty, new String[]{"info.json", "icon.png"}, new String[]{"{}", "PNG"});
        SplitPackage.Contents c3 = SplitPackage.scan(empty);
        ok(!c3.hasApks() && c3.splitCount() == 0, "没有 apk 的包 → hasApks=false，不出现 -1 分包");

        // ---- safeLeaf：zip-slip 与非法字符
        ok("evil.apk".equals(SplitPackage.safeLeaf("../../evil.apk")),
                "safeLeaf 剥掉 ../ → " + SplitPackage.safeLeaf("../../evil.apk"));
        ok("x.apk".equals(SplitPackage.safeLeaf("/abs/path/x.apk")),
                "safeLeaf 剥掉绝对路径");
        ok("c.apk".equals(SplitPackage.safeLeaf("a/b\\c.apk")),
                "safeLeaf 同时认 / 与 \\ 两种分隔符 → " + SplitPackage.safeLeaf("a/b\\c.apk"));
        ok(!SplitPackage.safeLeaf("bad\u0001na\u0002me.apk").contains("\u0001")
                        && !SplitPackage.safeLeaf("a:b*c?d.apk").contains(":"),
                "safeLeaf 去掉控制字符与 Windows 保留字符");
        ok("split.apk".equals(SplitPackage.safeLeaf("..")),
                "safeLeaf 纯点 → 兜底名（实得 " + SplitPackage.safeLeaf("..") + "）");
        ok(SplitPackage.safeLeaf("trail.apk").endsWith(".apk")
                        && !SplitPackage.safeLeaf("trail. ").endsWith(" "),
                "safeLeaf 去掉结尾点/空格（Windows 会吃掉）");
        String longName = SplitPackage.safeLeaf(repeat("a", 300) + ".apk");
        ok(longName.length() <= 100 && longName.endsWith(".apk"),
                "safeLeaf 长名截到 100 且保住扩展名（实得 " + longName.length() + "）");

        deleteAll(dir);
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static void zip(File f, String[] names, String[] bodies) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        try {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i]));
                z.write(bodies[i].getBytes("UTF-8"));
                z.closeEntry();
            }
        } finally {
            z.close();
        }
    }

    static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
