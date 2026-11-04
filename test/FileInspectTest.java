import java.io.File;

import com.hex91.file.FileInspect;

/**
 * FileInspect 桌面回归（v1.68.0）：魔数识别 + ELF 头/节表解析 + ZIP 签名信息。
 * 样本用仓库内真实文件（NDK 产出的 libtermux.so、apk、p12、jks）。
 */
public class FileInspectTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. ELF 64 位（arm64-v8a） ----
        File so64 = new File("native/lib/arm64-v8a/libtermux.so");
        if (so64.isFile()) {
            byte[] d = FileInspect.readHead(so64, (int) so64.length());
            check("ELF".equals(FileInspect.magicName(d)), "魔数识别 ELF");
            FileInspect.Elf e = FileInspect.parseElf(d);
            check(e != null, "ELF 解析非空");
            if (e != null) {
                check(e.bits == 64, "位宽 64（实际 " + e.bits + "）");
                check(e.little, "小端");
                check(e.machine == 183, "机器 AArch64（实际 0x" + Integer.toHexString(e.machine) + "）");
                check(e.type == 3, "类型 DYN（共享库/ PIE）");
                check(e.sections.size() > 10, "节表非空（" + e.sections.size() + " 节）");
                boolean hasText = false, hasDynstr = false, named = false;
                for (FileInspect.Section s : e.sections) {
                    if (".text".equals(s.name)) hasText = true;
                    if (".dynstr".equals(s.name)) hasDynstr = true;
                    if (s.name != null && s.name.length() > 0) named = true;
                }
                check(hasText, "含 .text 节");
                check(hasDynstr, "含 .dynstr 节");
                check(named, "节名已从 shstrtab 解析");
                String desc = FileInspect.describe(d, so64.getName(), so64.length());
                check(desc.contains("AArch64") && desc.contains("节表"), "描述含机器与节表");
                check(!e.sections.isEmpty() && e.sections.get(0).type == 0, "首节为 NULL 节");
            }
        } else {
            System.out.println("SKIP 无 arm64 libtermux.so");
        }

        // ---- 2. ELF 32 位（armeabi-v7a） ----
        File so32 = new File("native/lib/armeabi-v7a/libtermux.so");
        if (so32.isFile()) {
            byte[] d = FileInspect.readHead(so32, (int) so32.length());
            FileInspect.Elf e = FileInspect.parseElf(d);
            check(e != null && e.bits == 32, "32 位 ELF 位宽识别");
            check(e != null && e.machine == 40, "机器 ARM（实际 "
                    + (e == null ? "-" : "0x" + Integer.toHexString(e.machine)) + "）");
        } else {
            System.out.println("SKIP 无 armeabi-v7a libtermux.so");
        }

        // ---- 3. 非 ELF 魔数 ----
        byte[] apk = FileInspect.readHead(new File("test/huangguo.apk"), 4096);
        check("ZIP".equals(FileInspect.magicName(apk)), "APK 魔数为 ZIP");
        check(FileInspect.typeLabel(apk, "a.apk").startsWith("APK"), "扩展名细化 APK");

        File p12 = new File("assets/hexfile.p12");
        byte[] kb = FileInspect.readHead(p12, (int) p12.length());
        check("DER".equals(FileInspect.magicName(kb)), "PKCS12 魔数为 DER");
        check(FileInspect.typeLabel(kb, "hexfile.p12").contains("PKCS#12"), "扩展名细化 PKCS12");

        File jks = new File("test/custom_test.jks");
        if (jks.isFile()) {
            byte[] jb = FileInspect.readHead(jks, (int) jks.length());
            check("JKS".equals(FileInspect.magicName(jb)), "JKS 魔数识别");
            check(FileInspect.jksVersion(jb) > 0, "JKS 版本可读（" + FileInspect.jksVersion(jb) + "）");
        } else {
            System.out.println("SKIP 无 custom_test.jks");
        }

        // ---- 4. 空/超短数据不崩 ----
        check(FileInspect.magicName(new byte[0]) == null, "空数据返回 null 魔数");
        check(FileInspect.parseElf(new byte[]{0x7F, 'E'}) == null, "短数据 parseElf 返回 null");
        check(FileInspect.describe(new byte[0], "e", 0).contains("空文件"), "空文件描述");

        // ---- 5. ZIP 签名信息 ----
        String sig = FileInspect.signatureInfo(new File("test/huangguo.apk"));
        check(sig != null && sig.length() > 0, "签名信息非空");
        check(sig.contains("META-INF") || sig.contains("签名"), "签名信息含 META-INF/说明");
        System.out.println("---- 签名信息片段 ----");
        System.out.println(sig.length() > 600 ? sig.substring(0, 600) : sig);

        System.out.println();
        System.out.println("RESULT  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
