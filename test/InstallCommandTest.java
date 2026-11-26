import com.hex91.file.apk.InstallCommand;

/**
 * v2.121.0：特权安装命令与结果分类的桌面用例。
 *
 * <p>这两块是安装功能里唯一能脱离 Android 验证的部分，也是最容易在真机上
 * 「静默失败」的部分：文件名带引号、pm 输出关键字顺序错，都会让界面显示错误的
 * 结论。所以重点测转义、路径清理和分类优先级，不测「能不能装上」。</p>
 */
public class InstallCommandTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) {
        quote();
        commands();
        tempPaths();
        classify();
        pendingAction();
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void quote() {
        ok("'abc'".equals(InstallCommand.shellQuote("abc")), "普通参数加单引号");
        ok("'/sdcard/my app.apk'".equals(InstallCommand.shellQuote("/sdcard/my app.apk")),
                "空格在单引号内不被拆分");
        ok("'/sdcard/a'\\''b.apk'".equals(InstallCommand.shellQuote("/sdcard/a'b.apk")),
                "单引号转义为 '\\''");
        ok("'$HOME'".equals(InstallCommand.shellQuote("$HOME")),
                "美元符在单引号内不展开");
        ok("''".equals(InstallCommand.shellQuote(null)), "null 参数变成空串");
    }

    static void commands() {
        String cp = InstallCommand.copyCommand("/sdcard/a.apk", "/data/local/tmp/x.apk");
        ok(cp.equals("cp '/sdcard/a.apk' '/data/local/tmp/x.apk' 2>&1"), "cp 命令格式");
        ok(InstallCommand.chmodCommand("/data/local/tmp/x.apk")
                .equals("chmod 644 '/data/local/tmp/x.apk' 2>/dev/null"), "chmod 命令格式");
        ok(InstallCommand.removeCommand("/data/local/tmp/x.apk")
                .equals("rm -f '/data/local/tmp/x.apk' 2>/dev/null"), "清理命令格式");

        String ins = InstallCommand.installCommand("/data/local/tmp/x.apk", "com.hex91.file");
        ok(ins.contains("pm install -r -i 'com.hex91.file'"), "pm install 带 -r 与 -i");
        ok(ins.endsWith("'/data/local/tmp/x.apk' 2>&1"), "安装路径被引号包住");
        String def = InstallCommand.installCommand("/data/local/tmp/x.apk", null);
        ok(def.contains("'com.hex91.file'"), "空安装来源回退到自身包名");
    }

    static void tempPaths() {
        String a = InstallCommand.tempPath("/sdcard/Download/我的 应用.apk", "123");
        ok(a.startsWith("/data/local/tmp/hf-install-123-"), "临时目录与 token 前缀");
        ok(a.endsWith(".apk"), "始终以 .apk 结尾");
        ok(!a.contains("我") && !a.contains(" "), "非 ASCII 与空格被替换");

        String b = InstallCommand.tempPath("C:\\tmp\\app.apk", "t/1");
        ok(b.endsWith("-app.apk"), "反斜杠路径只取文件名");
        ok(b.startsWith("/data/local/tmp/hf-install-t1-"), "token 里的斜杠被剔除");

        String c = InstallCommand.tempPath(null, null);
        ok(c.contains("hf-install-") && c.endsWith("app.apk"), "空输入有稳定回退");
    }

    static void classify() {
        ok(InstallCommand.classify(0, "Success") == InstallCommand.OK, "退出码 0 为成功");
        ok(InstallCommand.classify(1, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: sig")
                == InstallCommand.ERR_CONFLICT, "签名/版本冲突优先于通用失败");
        ok(InstallCommand.classify(1, "INSTALL_PARSE_FAILED_NO_CERTIFICATES")
                == InstallCommand.ERR_INVALID, "解析失败归类为无效包");
        ok(InstallCommand.classify(1, "INSTALL_FAILED_MISSING_SPLIT")
                == InstallCommand.ERR_INVALID, "缺失分包归类为无效包");
        ok(InstallCommand.classify(1, "INSTALL_FAILED_INSUFFICIENT_STORAGE")
                == InstallCommand.ERR_STORAGE, "空间不足");
        ok(InstallCommand.classify(1, "INSTALL_FAILED_INCOMPATIBLE")
                == InstallCommand.ERR_INCOMPATIBLE, "不兼容优先于通用失败");
        ok(InstallCommand.classify(1, "INSTALL_FAILED_ABORTED")
                == InstallCommand.ERR_ABORTED, "中止");
        ok(InstallCommand.classify(1, "INSTALL_FAILED_BLOCKED")
                == InstallCommand.ERR_BLOCKED, "系统拦截");
        ok(InstallCommand.classify(1, "INSTALL_FAILED_TEST_ONLY")
                == InstallCommand.ERR_UNKNOWN, "认不出的失败保留为未知");
        ok(InstallCommand.classify(1, null) == InstallCommand.ERR_UNKNOWN, "空输出为未知");
        ok(InstallCommand.ok(InstallCommand.OK), "ok(OK) 为真");
        ok(!InstallCommand.ok(InstallCommand.ERR_BUSY), "ok(BUSY) 为假");
    }

    static void pendingAction() {
        ok(InstallCommand.PI_STATUS_PENDING_USER_ACTION == -1,
                "待用户确认状态值与 PackageInstaller 常量一致");
        ok(InstallCommand.isPendingUserAction(-1), "待确认状态需要启动系统确认页");
        ok(!InstallCommand.isPendingUserAction(0), "成功状态不算待确认");
        ok(!InstallCommand.isPendingUserAction(1), "失败状态不算待确认");
    }
}
