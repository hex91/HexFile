import com.hex91.file.ApkSignCheck;

import java.io.File;
import java.util.List;

/**
 * 桌面验证 ApkSignCheck 的 v1 展示修复：
 *   - minSdk>=24 且带 v2/v3 的重打包产物 → schemes() 应含 V1，v1 行显示"有签名(平台跳过校验)"
 *   - MTClone 自身 (minSdk=21) → schemes() 含 V1，v1 行"✅ 已校验"
 *   - 未签名 apk → "未签名"
 */
public class SignCheckTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        // 1. huangguo 重打包+重签名产物（minSdk=24, v1+v2+v3）
        check("huangguo repack (minSdk=24)",
                "test/sign_signed.apk", true, "V1+V2+V3");
        // 2. MTClone 自身产物（minSdk=21, v1+v2+v3）
        check("MTClone self (minSdk=21)",
                "out/MTClone-1.9.1.apk", true, "V1+V2+V3");
        // 3. 未签名 apk（重打包后未签名）
        check("unsigned repack", "test/sign_unsigned.apk", false, null);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void check(String label, String path, boolean expectSigned, String expectSchemes)
            throws Exception {
        File apk = new File(path);
        if (!apk.exists()) { System.out.println("SKIP (missing): " + path); return; }
        String s = ApkSignCheck.schemes(apk);
        boolean ok;
        if (expectSigned) {
            ok = expectSchemes == null || s.equals(expectSchemes);
        } else {
            ok = s.equals("未签名") || s.equals("检测失败");
        }
        System.out.println((ok ? "PASS" : "FAIL") + "  " + label + " -> schemes=" + s);
        List<String[]> rows = ApkSignCheck.detailRows(apk);
        for (String[] row : rows) {
            if (row[0].startsWith("v1") || row[0].equals("校验结果")) {
                System.out.println("      " + row[0] + " = " + row[1]);
            }
        }
        if (ok) pass++; else fail++;
    }
}
