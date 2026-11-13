import com.hex91.file.fs.LinkUtil;

/**
 * v1.81.0 符号链接目标解析（桌面 JVM）。
 *
 * <p>lstat 那部分必须在设备上跑（android.system.Os），这里只测纯 Java 的路径规范化：
 * 悬空链接、相对目标、多级 .. 这些手写解析最容易出错，而错了会把用户带到别的目录。</p>
 */
public class LinkUtilTest {

    static int pass = 0, fail = 0;

    static void eq(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + "（期望 " + expect + "，实际 " + got + "）");
        }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- normalize
        eq("/a/c", LinkUtil.normalize("/a/b/../c"), "规范化：消掉 ..");
        eq("/a/b", LinkUtil.normalize("/a/./b/"), "规范化：消掉 . 与尾部斜杠");
        eq("/a/b", LinkUtil.normalize("//a///b"), "规范化：合并重复斜杠");
        eq("/", LinkUtil.normalize("/"), "规范化：根目录");
        eq("/", LinkUtil.normalize("/../../.."), "规范化：.. 不会越过根");
        eq("/x/y", LinkUtil.normalize("/x/y/../y"), "规范化：先退再进");

        // ---------------------------------------------------------- resolve 相对目标
        eq("/sdcard/real/file", LinkUtil.resolve("/sdcard", "real/file"),
                "相对目标：拼到链接所在目录");
        eq("/sdcard/real/file", LinkUtil.resolve("/sdcard/sub", "../real/file"),
                "相对目标：允许 ../ 回退");
        eq("/sdcard/dir", LinkUtil.resolve("/sdcard", "dir/"), "相对目标：尾部斜杠不影响");

        // ---------------------------------------------------------- resolve 绝对目标
        eq("/system/bin/sh", LinkUtil.resolve("/sdcard", "/system/bin/sh"),
                "绝对目标：忽略链接所在目录");
        eq("/x/y", LinkUtil.resolve("/sdcard", "/x/./y"), "绝对目标：同样做规范化");

        // ---------------------------------------------------------- listNoFollow（v1.99.1）
        // 桌面 JVM 上 Os.lstat 是桩，isLink 恒为 false —— 这里只验证「不该丢条目」的契约：
        // 真机上被剔除的是链接，普通文件/目录一个都不能少。
        eq("true", String.valueOf(LinkUtil.listNoFollow(null) == null),
                "listNoFollow：null 目录返回 null");
        eq("true", String.valueOf(
                        LinkUtil.listNoFollow(new java.io.File("/no/such/dir/hexfile")) == null),
                "listNoFollow：读不到返回 null");
        java.io.File d = new java.io.File(System.getProperty("java.io.tmpdir"),
                "hexfile_linkutil_" + System.nanoTime());
        d.mkdirs();
        try {
            new java.io.File(d, "a.txt").createNewFile();
            new java.io.File(d, "sub").mkdirs();
            java.io.File[] kids = LinkUtil.listNoFollow(d);
            eq("2", String.valueOf(kids == null ? -1 : kids.length),
                    "listNoFollow：普通条目全部保留");
        } catch (Exception e) {
            eq("no-exception", "exception:" + e, "listNoFollow：建目录出错");
        } finally {
            java.io.File[] kids = d.listFiles();
            if (kids != null) for (java.io.File k : kids) {
                java.io.File[] sub = k.listFiles();
                if (sub != null) for (java.io.File x : sub) x.delete();
                k.delete();
            }
            d.delete();
        }

        // ---------------------------------------------------------- 边界
        eq(null, LinkUtil.resolve("/sdcard", null), "空目标：返回 null");
        eq(null, LinkUtil.resolve("/sdcard", ""), "空串目标：返回 null");
        eq("/tmp/a", LinkUtil.resolve("", "tmp/a"), "空父目录：按根目录处理");

        // ---------------------------------------------------------- 自己指向自己（悬空）
        eq("/sdcard/loop", LinkUtil.resolve("/sdcard", "loop"), "自指链接：解析成同级同名");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
