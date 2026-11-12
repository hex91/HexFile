import com.hex91.file.core.Base64Codec;
import com.hex91.file.core.CredentialCodec;

import java.util.Arrays;

import javax.crypto.spec.SecretKeySpec;

/**
 * v1.83.0 凭据加密（桌面 JVM，纯 Java）。
 *
 * <p>这段代码只在「保存网络存储密码」时跑，一旦写错就是用户连不上 NAS 或密码泄露，
 * 所以把 Base64、GCM 往返、篡改检测、密钥错误、IV 随机性全测一遍。</p>
 */
public class CredentialCodecTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void eqs(String expect, String got, String msg) {
        ok(expect == null ? got == null : expect.equals(got),
                msg + "（期望 " + expect + "，实际 " + got + "）");
    }

    public static void main(String[] args) throws Exception {
        // ---------------------------------------------------------- Base64
        eqs("", Base64Codec.encode(new byte[0]), "Base64：空输入");
        eqs("YWJj", Base64Codec.encode("abc".getBytes("UTF-8")), "Base64：abc");
        eqs("YWI=", Base64Codec.encode("ab".getBytes("UTF-8")), "Base64：2 字节带填充");
        eqs("YQ==", Base64Codec.encode("a".getBytes("UTF-8")), "Base64：1 字节带填充");

        for (int n = 0; n <= 17; n++) {
            byte[] src = new byte[n];
            for (int i = 0; i < n; i++) src[i] = (byte) (i * 31 + 7);
            byte[] back = Base64Codec.decode(Base64Codec.encode(src));
            ok(back != null && Arrays.equals(src, back), "Base64 往返：" + n + " 字节");
        }
        ok(Base64Codec.decode("!!!!") == null, "Base64：非法字符返回 null");
        ok(Base64Codec.decode("YWJjZ") == null, "Base64：长度 mod 4 == 1 非法返回 null");

        // ---------------------------------------------------------- 加解密
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) rawKey[i] = (byte) i;
        SecretKeySpec key = new SecretKeySpec(rawKey, "AES");

        String[] samples = {"", "p@ssw0rd", "中文密码测试", "a b c #$%^&*()",
                repeat('x', 3000)};
        for (String s : samples) {
            String enc = CredentialCodec.encrypt(s, key);
            ok(CredentialCodec.isEncrypted(enc), "加密：" + label(s) + " 带 enc:v1: 前缀");
            if (!s.isEmpty() && s.length() < 20)
                ok(!enc.contains(s), "加密：" + label(s) + " 密文不含明文");
            eqs(s, CredentialCodec.decrypt(enc, key), "解密：" + label(s) + " 往返一致");
        }

        // 非密文原样返回（旧版本明文密码）
        eqs("plain-old", CredentialCodec.decrypt("plain-old", key), "旧明文：decrypt 原样返回");
        ok(!CredentialCodec.isEncrypted("plain-old"), "旧明文：不误判为密文");

        // IV 随机：同一明文两次密文不同
        String e1 = CredentialCodec.encrypt("same", key);
        String e2 = CredentialCodec.encrypt("same", key);
        ok(!e1.equals(e2), "随机 IV：两次加密结果不同");
        eqs("same", CredentialCodec.decrypt(e1, key), "随机 IV：仍能解回");
        eqs("same", CredentialCodec.decrypt(e2, key), "随机 IV：第二条也能解回");

        // ---------------------------------------------------------- 篡改检测
        String enc = CredentialCodec.encrypt("secret", key);
        char[] cs = enc.toCharArray();
        int idx = CredentialCodec.PREFIX.length() + 20;
        cs[idx] = cs[idx] == 'A' ? 'B' : 'A';
        String tampered = new String(cs);
        boolean threw = false;
        try {
            CredentialCodec.decrypt(tampered, key);
        } catch (Exception e) {
            threw = true;
        }
        ok(threw, "篡改密文：GCM 校验失败抛异常");

        // ---------------------------------------------------------- 错误密钥
        byte[] otherKey = new byte[32];
        Arrays.fill(otherKey, (byte) 9);
        threw = false;
        try {
            CredentialCodec.decrypt(enc, new SecretKeySpec(otherKey, "AES"));
        } catch (Exception e) {
            threw = true;
        }
        ok(threw, "错误密钥：解密失败抛异常");

        // ---------------------------------------------------------- 空 key 防护
        ok(CredentialCodec.encrypt("x", new SecretKeySpec(new byte[16], "AES")).startsWith("enc:v1:"),
                "16 字节密钥：也能工作（AES-128）");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static String label(String s) {
        if (s.isEmpty()) return "空串";
        if (s.length() > 12) return s.substring(0, 8) + "…(" + s.length() + ")";
        return s;
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
