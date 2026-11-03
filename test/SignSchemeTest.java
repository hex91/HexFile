import com.hex91.file.ApkSignCheck;
import com.hex91.file.ApkSignerTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * v1.66.9 签名方案组合测试：v1 / v2 / v3 / v1+v2 / v1+v2+v3 / v2+v3
 * 逐一用内置密钥签名，用 apksig 验证实际生效的方案组合。
 */
public class SignSchemeTest {
    static int pass = 0, fail = 0;

    static void chk(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name + (detail != null ? "  " + detail : "")); }
        else { fail++; System.out.println("[FAIL] " + name + (detail != null ? "  " + detail : "")); }
    }

    public static void main(String[] args) throws Exception {
        File sample = new File(args[0]);
        File work = new File("test/scheme_tmp");
        rmrf(work); work.mkdirs();

        // {label, v1, v2, v3, expectContains, expectNotContains}
        Object[][] cases = {
                {"v1",        true,  false, false, "V1", "V2"},
                {"v2",        false, true,  false, "V2", "V1"},
                {"v3",        false, false, true,  "V3", "V1"},
                {"v1+v2",     true,  true,  false, "V2", "V3"},
                {"v1+v2+v3",  true,  true,  true,  "V3", null},
                {"v2+v3",     false, true,  true,  "V3", "V1"},
        };
        for (Object[] c : cases) {
            String label = (String) c[0];
            boolean v1 = (Boolean) c[1], v2 = (Boolean) c[2], v3 = (Boolean) c[3];
            String expect = (String) c[4], notExpect = (String) c[5];
            File out = new File(work, "s_" + label.replace("+", "_") + ".apk");
            InputStream ks = new FileInputStream("assets/hexfile.p12");
            ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, sample, out, 21, v1, v2, v3);
            ks.close();
            String sc = ApkSignCheck.schemes(out);
            boolean ok = sc.contains(expect) && (notExpect == null || !sc.contains(notExpect));
            chk("方案 " + label, ok, sc);
        }

        // 非法参数：全 false 应抛异常
        try {
            InputStream ks = new FileInputStream("assets/hexfile.p12");
            ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, sample,
                    new File(work, "bad.apk"), 21, false, false, false);
            ks.close();
            chk("全关方案拒绝", false, "未抛异常");
        } catch (IllegalArgumentException e) {
            chk("全关方案拒绝", true, e.getMessage());
        }

        System.out.println("\nPASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) rmrf(c);
        f.delete();
    }
}
