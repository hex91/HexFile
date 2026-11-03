import com.hex91.file.ApkEditSession;
import com.hex91.file.FileOps;
import com.hex91.file.SmaliTool;
import com.hex91.file.ZipUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Dex 属性 / Dex 修复 测试（v1.66.5）：
 *  1. 真实 APK 提取 classes.dex → dexProps 读出全部统计字段；
 *  2. dexProps 校验和/签名状态为「正常」；
 *  3. 人为破坏头部（file_size、SHA-1 签名、Adler-32 校验和、endian_tag）
 *     → repairDex 全部修回 → dexProps 恢复「正常」，dexlib2 可解析；
 *  4. 对完好 dex 再跑一次修复 → 报告「无需修复」。
 */
public class DexPropsTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        File work = new File("test/dexfix_tmp");
        rm(work); work.mkdirs();
        if (!ZipUtil.extractEntry(apk, "classes.dex", work)) {
            System.out.println("  [SKIP] 样本无 classes.dex: " + apk.getName());
            return;
        }
        File dex = new File(work, "classes.dex");

        // 1) 属性读取
        String props = SmaliTool.dexProps(dex);
        chk("1.1 props 非空", props != null, String.valueOf(props));
        chk("1.2 版本行", props.contains("Dex 版本: 0"), firstLine(props, "Dex 版本"));
        chk("1.3 String 数", props.contains("String 数: "), firstLine(props, "String 数"));
        chk("1.4 Type 数", props.contains("Type 数:"), firstLine(props, "Type 数"));
        chk("1.5 Proto 数", props.contains("Proto 数:"), firstLine(props, "Proto 数"));
        chk("1.6 Field 数", props.contains("Field 数:"), firstLine(props, "Field 数"));
        chk("1.7 Method 数", props.contains("Method 数:"), firstLine(props, "Method 数"));
        chk("1.8 Class 数", props.contains("Class 数:"), firstLine(props, "Class 数"));

        // 2) 完好 dex 校验状态
        chk("2.1 校验和正常", props.contains("校验和:    正常"), firstLine(props, "校验和"));
        chk("2.2 签名正常", props.contains("SHA-1 签名: 正常"), firstLine(props, "SHA-1"));

        // 3) 破坏 → 修复
        byte[] orig = readAll(dex);
        byte[] bad = orig.clone();
        // file_size 改小
        bad[32] = 0x10; bad[33] = 0x00; bad[34] = 0x00; bad[35] = 0x00;
        // endian_tag 改坏
        bad[40] = 0x11;
        // 签名区涂改
        bad[12] ^= 0x5A; bad[15] ^= 0xA5;
        // 校验和区涂改
        bad[8] ^= 0x33;
        writeAll(dex, bad);
        StringBuilder rep = new StringBuilder();
        String err = SmaliTool.repairDex(dex, rep);
        chk("3.1 修复成功", err == null, String.valueOf(err));
        chk("3.2 修复了 4 处", countLines(rep.toString()) >= 4, rep.toString());
        String props2 = SmaliTool.dexProps(dex);
        chk("3.3 修后校验和正常", props2.contains("校验和:    正常"), firstLine(props2, "校验和"));
        chk("3.4 修后签名正常", props2.contains("SHA-1 签名: 正常"), firstLine(props2, "SHA-1"));
        chk("3.5 修后 dexlib2 可解析（repairDex 内含）",
                rep.toString().contains("dexlib2 解析校验: 通过"), rep.toString());
        chk("3.6 file_size 修正", rep.toString().contains("file_size"), rep.toString());

        // 4) 完好 dex 无需修复
        writeAll(dex, orig);
        StringBuilder rep2 = new StringBuilder();
        String err2 = SmaliTool.repairDex(dex, rep2);
        chk("4.1 完好 dex 修复成功(无动作)", err2 == null, String.valueOf(err2));
        chk("4.2 报告无需修复", rep2.toString().contains("无需修复"), rep2.toString());

        // 5) 非 dex 文件
        File notDex = new File(work, "not.dex");
        writeAll(notDex, "hello world this is not a dex".getBytes("UTF-8"));
        chk("5.1 非 dex props=null", SmaliTool.dexProps(notDex) == null, "-");
        chk("5.2 非 dex 修复报错", SmaliTool.repairDex(notDex, new StringBuilder()) != null, "-");

        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }


    static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n + 1;
    }

    static String firstLine(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return "<缺 " + key + ">";
        int e = s.indexOf('\n', i);
        return e < 0 ? s.substring(i) : s.substring(i, e);
    }

    static void chk(String name, boolean ok, String info) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name + (ok ? "" : " << " + info));
        if (ok) pass++; else fail++;
    }

    static void rm(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) rm(k);
        f.delete();
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream i = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int n = 0, r;
        while (n < b.length && (r = i.read(b, n, b.length - n)) > 0) n += r;
        i.close();
        return b;
    }

    static void writeAll(File f, byte[] b) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(b);
        o.close();
    }
}
