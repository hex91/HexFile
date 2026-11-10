import com.hex91.file.WorkDir;

import java.io.File;

/**
 * v1.83.0 工作目录路径规范化。
 *
 * <p>{@link WorkDir#normalize(String)} 是用户手输路径的唯一入口，
 * 必须严格：只收绝对路径、去尾斜杠、空串视为「恢复默认」。
 * 放错一个字符，回收站/备份就会被写到意料之外的位置。</p>
 */
public class WorkDirTest {

    static int pass = 0, fail = 0;

    static void isTrue(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++;
            System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    /** Windows 上 File 会用反斜杠，断言前统一成正斜杠再比 */
    static String slash(File f) {
        return f == null ? null : f.getPath().replace('\\', '/');
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- 拒绝非法输入
        isTrue(WorkDir.normalize(null) == null, "null → 恢复默认");
        isTrue(WorkDir.normalize("") == null, "空串 → 恢复默认");
        isTrue(WorkDir.normalize("   ") == null, "纯空白 → 恢复默认");
        isTrue(WorkDir.normalize("sdcard/HexFile") == null, "相对路径拒绝");
        isTrue(WorkDir.normalize("./HexFile") == null, "点开头相对路径拒绝");
        isTrue(WorkDir.normalize("~/HexFile") == null, "~ 路径拒绝（不做 shell 展开）");

        // ---------------------------------------------------------- 正常路径
        eqs("HexFile", WorkDir.normalize("/sdcard/HexFile").getName(), "标准绝对路径");
        eqs("/sdcard/HexFile", slash(WorkDir.normalize("/sdcard/HexFile")),
                "标准路径原样保留");
        eqs("/storage/1A2B-3C4D/HexFile",
                slash(WorkDir.normalize("/storage/1A2B-3C4D/HexFile")),
                "外置卡路径保留（不受大小写/短横线影响）");

        // ---------------------------------------------------------- 尾斜杠与空白
        eqs("/sdcard/HexFile", slash(WorkDir.normalize("/sdcard/HexFile/")),
                "去掉 1 个尾斜杠");
        eqs("/sdcard/HexFile", slash(WorkDir.normalize("/sdcard/HexFile////")),
                "去掉多个尾斜杠");
        eqs("/sdcard/HexFile", slash(WorkDir.normalize("  /sdcard/HexFile  ")),
                "前后空白被裁掉");
        eqs("/", slash(WorkDir.normalize("/")), "根目录保持 /（不能被削成空）");
        eqs("/", slash(WorkDir.normalize("///")), "多个 / 削到根");

        // ---------------------------------------------------------- 中文 / 空格路径
        eqs("/sdcard/我的 文件", slash(WorkDir.normalize("/sdcard/我的 文件")),
                "中文与空格路径可用");

        // ---------------------------------------------------------- isCustom 默认值
        // 未调用 init/set 时应为 false（桌面环境没有 SharedPreferences）
        isTrue(!WorkDir.isCustom(), "未初始化时不是自定义目录");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
