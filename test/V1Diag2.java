import com.android.apksig.ApkVerifier;

import java.io.File;

/**
 * 用默认设置（minSdk 从 APK manifest 读取）验证，打印各 scheme 结果与 v1 详细错误。
 * 用法: java V1Diag2 <apk>
 */
public class V1Diag2 {

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
        System.out.println("verified=" + r.isVerified() + " v1=" + r.isVerifiedUsingV1Scheme()
                + " v2=" + r.isVerifiedUsingV2Scheme() + " v3=" + r.isVerifiedUsingV3Scheme());
        System.out.println("v1 signers: " + r.getV1SchemeSigners().size());
        for (ApkVerifier.IssueWithParams e : r.getErrors()) {
            System.out.println("ERROR: " + e);
        }
        for (ApkVerifier.Result.V1SchemeSignerInfo si : r.getV1SchemeSigners()) {
            for (ApkVerifier.IssueWithParams e : si.getErrors()) System.out.println("  V1 ERROR: " + e);
            for (ApkVerifier.IssueWithParams w : si.getWarnings()) System.out.println("  V1 WARNING: " + w);
        }
    }
}
