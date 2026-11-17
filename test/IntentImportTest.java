import com.hex91.file.utils.IntentImport;

import java.io.File;
import java.io.FileOutputStream;

/**
 * v2.12.0（F-17）：{@link IntentImport} 的命名与去重用例。
 *
 * <p>复制本身必须在设备上验（要真的 {@code content://} 提供方），但最容易出事的两步
 * 是纯 {@link File} 逻辑，桌面就能锁住：</p>
 * <ul>
 *   <li><b>名字收敛</b>：分享进来的 DISPLAY_NAME 是对方给的，可能是
 *       {@code ../../data/x}、可能带 {@code : * ?} 、可能只有 {@code ..}。
 *       漏掉任何一个，轻则创建失败，重则在目标目录之外写文件。</li>
 *   <li><b>重名不覆盖</b>：同名十次必须是十个文件。这一条如果退化成覆盖，
 *       用户丢的是上一次的原件，而且没有任何提示。</li>
 * </ul>
 */
public class IntentImportTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        // 1) 路径片段必须被剥掉：只留最后一段，不能拼出「往上一级」
        ok("x.apk".equals(IntentImport.safeName("../../system/x.apk")),
                "safeName 剥掉路径片段 → " + IntentImport.safeName("../../system/x.apk"));
        ok("x.apk".equals(IntentImport.safeName("C:\\Windows\\x.apk")),
                "safeName 剥掉 Windows 路径 → " + IntentImport.safeName("C:\\Windows\\x.apk"));

        // 2) 非法字符替换成下划线（FAT/EXFAT 上真会失败）
        String bad = IntentImport.safeName("a:b*c?d\"e<f>g|h.txt");
        ok(bad != null && bad.indexOf(':') < 0 && bad.indexOf('*') < 0 && bad.indexOf('?') < 0
                        && bad.indexOf('"') < 0 && bad.indexOf('<') < 0 && bad.indexOf('>') < 0
                        && bad.indexOf('|') < 0,
                "safeName 清掉 :*?\"<>| → " + bad);

        // 3) 控制字符（含 0x00 与换行）不能带进文件名
        String ctl = IntentImport.safeName("a\u0000b\nc.txt");
        ok(ctl != null && ctl.indexOf('\u0000') < 0 && ctl.indexOf('\n') < 0,
                "safeName 清掉控制字符 → " + ctl);

        // 4) 纯点/空名字救不活 → null（调用方兜底成 shared-<时间戳>）
        ok(IntentImport.safeName("..") == null, "safeName(\"..\") = null");
        ok(IntentImport.safeName("   ") == null, "safeName(空白) = null");
        ok(IntentImport.safeName(null) == null, "safeName(null) = null");

        // 5) 结尾的点/空格要去掉（FAT 会吞掉它们，等于悄悄改名）
        String tail = IntentImport.safeName("report.txt. ");
        ok("report.txt".equals(tail), "safeName 去掉结尾的点与空格 → " + tail);

        // 6) 长名字截断但仍保留扩展名
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) longName.append('测');
        longName.append(".apk");
        String cut = IntentImport.safeName(longName.toString());
        ok(cut != null && cut.length() <= 120 && cut.endsWith(".apk"),
                "safeName 截断到 ≤120 字符且保留扩展名（实际 " + (cut == null ? -1 : cut.length()) + "）");

        // 7) 重名不覆盖：连着建 3 个同名文件，名字必须各不相同
        File base = new File("_intentimport_probe");
        base.mkdirs();
        File a1 = IntentImport.uniqueTarget(base, "photo.jpg");
        ok("photo.jpg".equals(a1.getName()), "uniqueTarget 首次用原名 → " + a1.getName());
        touch(a1);
        File a2 = IntentImport.uniqueTarget(base, "photo.jpg");
        ok("photo (1).jpg".equals(a2.getName()), "uniqueTarget 第二次 → " + a2.getName());
        touch(a2);
        File a3 = IntentImport.uniqueTarget(base, "photo.jpg");
        ok("photo (2).jpg".equals(a3.getName()), "uniqueTarget 第三次 → " + a3.getName());
        touch(a3);

        // 8) 没有扩展名的文件也要能去重（点在第 0 位的名字不能被当成「有扩展名」）
        File b1 = IntentImport.uniqueTarget(base, "noext");
        touch(b1);
        File b2 = IntentImport.uniqueTarget(base, "noext");
        ok("noext (1)".equals(b2.getName()), "uniqueTarget 无扩展名 → " + b2.getName());

        a1.delete(); a2.delete(); a3.delete(); b1.delete(); b2.delete();
        base.delete();

        System.out.println();
        System.out.println("==== IntentImportTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static void touch(File f) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(new byte[]{1});
        fo.close();
    }
}
