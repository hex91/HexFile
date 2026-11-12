import com.hex91.file.net.CloudPath;

/**
 * v1.89.0 云盘路径规范化与 Graph 路径构造。
 *
 * <p>这里错一个字符，表现是「目录能进、里面的文件打不开」：
 * 文件名带 {@code #} 时后面全被当 fragment，带 {@code %} 时被当已编码，
 * 带空格时 {@code +} 和 {@code %20} 的区别能让服务端找不到文件。</p>
 */
public class CloudPathTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++; System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    static void eql(int expect, int got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    static boolean throwsIAE(String path) {
        try {
            CloudPath.normalize(path);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- 规范化
        eqs("", CloudPath.normalize(""), "空路径 → 空");
        eqs("", CloudPath.normalize("/"), "根 → 空");
        eqs("", CloudPath.normalize("///"), "多斜杠根 → 空");
        eqs("a/b", CloudPath.normalize("/a/b/"), "去首尾斜杠");
        eqs("a/b", CloudPath.normalize("a//b"), "折叠连续斜杠");
        eqs("a/b", CloudPath.normalize("a/./b"), "丢掉 .");
        eqs("a/b", CloudPath.normalize("  a/b  "), "去首尾空白");
        eqs("a/b", CloudPath.normalize("a\\b"), "反斜杠归一（Windows 路径粘贴进来）");
        ok(CloudPath.normalize(null).isEmpty(), "null → 空");
        ok(throwsIAE("a/../b"), ".. 抛异常（不静默折叠）");
        ok(throwsIAE("../etc"), "开头 .. 抛异常");

        eql(0, CloudPath.segments("").size(), "根没有段");
        eql(2, CloudPath.segments("/a/b/").size(), "两段");
        eqs("b", CloudPath.segments("/a/b/").get(1), "末段");

        // ---------------------------------------------------------- 单段编码
        eqs("abc", CloudPath.encodeSegment("abc"), "普通字符原样");
        eqs("a-b_c.d~e", CloudPath.encodeSegment("a-b_c.d~e"), "RFC3986 unreserved 原样保留");
        eqs("a%20b", CloudPath.encodeSegment("a b"), "空格 → %20（不是 +）");
        eqs("a%2Bb", CloudPath.encodeSegment("a+b"), "加号编码成 %2B（避免被网关当空格）");
        eqs("%E4%B8%AD%E6%96%87", CloudPath.encodeSegment("中文"), "中文按 UTF-8 百分号编码");
        eqs("a%23b", CloudPath.encodeSegment("a#b"), "# 必须编码（否则被当 fragment）");
        eqs("a%25b", CloudPath.encodeSegment("a%b"), "% 必须编码（否则被当已编码）");
        eqs("a%3Ab", CloudPath.encodeSegment("a:b"), "冒号必须编码（Graph 路径用 : 作分隔符）");
        eqs("a%3Fb", CloudPath.encodeSegment("a?b"), "问号必须编码");
        eqs("a%26b", CloudPath.encodeSegment("a&b"), "& 编码");
        eqs("", CloudPath.encodeSegment(""), "空段");
        eqs("%F0%9F%93%81", CloudPath.encodeSegment("📁"), "4 字节 emoji 正确编码");
        eqs("A", CloudPath.encodeSegment("A"), "大写字母保留");
        ok(CloudPath.encodeSegment("中").matches("%[0-9A-F]{2}.*"), "十六进制大写");

        // ---------------------------------------------------------- 整路径编码
        eqs("a/b", CloudPath.encodePath("/a/b"), "逐段编码，斜杠保留");
        eqs("a%20b/c%23d", CloudPath.encodePath("/a b/c#d"), "段内特殊字符编码，分隔符不动");
        eqs("", CloudPath.encodePath(""), "根编码为空");

        // ---------------------------------------------------------- Graph 路径
        eqs("/me/drive/root/children", CloudPath.graphChildren(""), "根的 children 用 root 形式");
        eqs("/me/drive/root:/a/b:/children", CloudPath.graphChildren("/a/b/"), "子目录 children");
        eqs("/me/drive/root:/a%20b:/children", CloudPath.graphChildren("a b"), "路径里的空格编码");
        eqs("/me/drive/root:/a%23b:/children", CloudPath.graphChildren("a#b"), "路径里的 # 编码");

        eqs("/me/drive/root", CloudPath.graphItem(""), "根的 item");
        eqs("/me/drive/root:/a/b", CloudPath.graphItem("/a/b"), "子路径的 item");
        eqs("/me/drive/root/content", CloudPath.graphContent(""), "根 content");
        eqs("/me/drive/root:/a/b:/content", CloudPath.graphContent("/a/b"), "子路径 content");

        eqs("root", CloudPath.graphKey(""), "根的 key");
        eqs("root:/a/b", CloudPath.graphKey("/a/b/"), "子路径 key（不含结尾冒号）");

        // ---------------------------------------------------------- 父子
        eqs("", CloudPath.parent("a"), "一级路径无父 → 空");
        eqs("a", CloudPath.parent("a/b"), "父路径");
        eqs("a/b", CloudPath.parent("a/b/c"), "多级父路径");
        eqs("", CloudPath.parent(""), "根的父仍是根");
        eqs("b", CloudPath.name("a/b"), "末段名");
        eqs("c.txt", CloudPath.name("a/b/c.txt"), "文件名");
        eqs("a", CloudPath.name("a"), "一级路径名");
        eqs("", CloudPath.name(""), "根无名");

        // ---------------------------------------------------------- 名称合法性
        ok(CloudPath.illegalNameReason("正常.txt") == null, "正常名合法");
        ok(CloudPath.illegalNameReason("带 空格.txt") == null, "空格允许");
        ok(CloudPath.illegalNameReason("中文名") == null, "中文允许");
        ok(CloudPath.illegalNameReason("") != null, "空名不合法");
        ok(CloudPath.illegalNameReason("   ") != null, "全空白不合法");
        ok(CloudPath.illegalNameReason(null) != null, "null 不合法");
        ok(CloudPath.illegalNameReason("a/b") != null, "斜杠不合法（会被当路径分隔）");
        ok(CloudPath.illegalNameReason("a:b") != null, "冒号不合法（OneDrive）");
        ok(CloudPath.illegalNameReason("a*b") != null, "星号不合法");
        ok(CloudPath.illegalNameReason("a?b") != null, "问号不合法");
        ok(CloudPath.illegalNameReason("a\"b") != null, "双引号不合法");
        ok(CloudPath.illegalNameReason("a<b") != null, "尖括号不合法");
        ok(CloudPath.illegalNameReason("a|b") != null, "竖线不合法");
        ok(CloudPath.illegalNameReason(".") != null, ". 不合法");
        ok(CloudPath.illegalNameReason("..") != null, ".. 不合法");
        ok(CloudPath.illegalNameReason("a\tb") != null, "控制字符不合法");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 256; i++) longName.append('a');
        ok(CloudPath.illegalNameReason(longName.toString()) != null, "超长名称不合法");
        ok(CloudPath.illegalNameReason(longName.substring(0, 255)) == null, "255 字符刚好合法");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
