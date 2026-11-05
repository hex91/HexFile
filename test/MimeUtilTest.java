import com.hex91.file.MimeUtil;

/**
 * v1.72.0 分享功能的 MIME 映射回归。
 *
 * <p>分享面板能不能列出微信/QQ，取决于 {@code Intent.setType()} 给的 MIME；
 * 接收方还会通过 {@code ContentResolver.getType()} 再查一次（走 ApkFileProvider）。
 * 两边都调 {@link MimeUtil#of}，所以这里只要保证映射本身对。
 *
 * <p>重点是几个容易写错的边界：大写扩展名、以点开头的隐藏文件、多级扩展名
 * （{@code a.tar.gz} 取 gz 而不是 tar）、无扩展名、null。
 */
public class MimeUtilTest {

    static int pass = 0, fail = 0;

    static void check(String input, String expect, String msg) {
        String got = MimeUtil.of(input);
        if (expect.equals(got)) {
            pass++;
            System.out.println("PASS  " + msg + " → " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    public static void main(String[] args) {
        check("base.apk", "application/vnd.android.package-archive", "APK");
        check("BASE.APK", "application/vnd.android.package-archive", "大写 APK");
        check("photo.jpg", "image/jpeg", "jpg");
        check("Photo.JPEG", "image/jpeg", "大写 JPEG");
        check("shot.png", "image/png", "png");
        check("anim.webp", "image/webp", "webp");
        check("readme.txt", "text/plain", "txt");
        check("data.json", "application/json", "json");
        check("AndroidManifest.xml", "text/xml", "xml（按 text/xml 给，别给 octet-stream）");
        check("index.html", "text/html", "html");
        check("pkg.zip", "application/zip", "zip");
        check("old.rar", "application/x-rar-compressed", "rar");
        check("backup.7z", "application/x-7z-compressed", "7z");
        check("classes.dex", "application/x-dex", "dex");
        check("libtermux.so", "application/x-sharedlib", "so");
        check("font.ttf", "font/ttf", "ttf");

        check("archive.tar.gz", "application/gzip", "多级扩展名取最后一段");
        check("a.b.c.APK", "application/vnd.android.package-archive", "多级扩展名 + 大写");

        check("noext", "application/octet-stream", "无扩展名兜底");
        check(".gitignore", "application/octet-stream", "点开头的隐藏文件不算扩展名");
        check("", "application/octet-stream", "空名字兜底");
        check(null, "application/octet-stream", "null 兜底");
        check("weird.zzz", "application/octet-stream", "未知扩展名兜底");

        System.out.println();
        System.out.println("MimeUtilTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
