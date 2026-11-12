import com.hex91.file.utils.ActivityLog;

import java.util.List;

/**
 * v1.69.0 Activity 记录仓库回归。
 *
 * <p>重点两件事：
 * <ol>
 *   <li>{@link ActivityLog#looksLikeActivity} 必须把 {@code android.widget.FrameLayout} 这类
 *       容器挡掉 —— 无障碍 {@code TYPE_WINDOW_CONTENT_CHANGED} 事件量极大，不加过滤列表会被
 *       ViewGroup 刷满，真正想看的 Activity 反而被挤掉；</li>
 *   <li>队列行为：连续重复合并、超上限淘汰最旧、快照顺序最新在前。</li>
 * </ol>
 */
public class ActivityLogTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    public static void main(String[] args) {
        // ---------------- 类名过滤 ----------------
        check(ActivityLog.looksLikeActivity("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
                "微信 LauncherUI 识别为 Activity");
        check(ActivityLog.looksLikeActivity("com.android.settings",
                        "com.android.settings.Settings$WifiSettingsActivity"),
                "系统设置子页面识别为 Activity");
        check(ActivityLog.looksLikeActivity("com.example", "com.example.MainActivity"),
                "普通 Activity 识别成功");

        check(!ActivityLog.looksLikeActivity("com.example", "android.widget.FrameLayout"),
                "android.widget.* 容器被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", "android.view.View"),
                "android.view.* 被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", "android.app.AlertDialog"),
                "android.app.* 对话框被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", "android.webkit.WebView"),
                "android.webkit.* 被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", "com.example.PopupWindow"),
                "PopupWindow 结尾被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", "com.example.Toast"),
                "Toast 结尾被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", "com.android.internal.policy.DecorView"),
                "com.android.internal.* 被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", "MainActivity"),
                "无包名的裸类名被过滤");
        check(!ActivityLog.looksLikeActivity("com.example", null),
                "null 类名被过滤");
        check(!ActivityLog.looksLikeActivity(null, "com.example.MainActivity"),
                "空包名被过滤");

        // ---------------- 队列行为 ----------------
        ActivityLog.clear();
        check(ActivityLog.size() == 0, "clear 后为空");

        ActivityLog.add(null, "com.a", "com.a.AActivity", null);
        ActivityLog.add(null, "com.b", "com.b.BActivity", null);
        ActivityLog.add(null, "com.c", "com.c.CActivity", null);
        check(ActivityLog.size() == 3, "三条不同记录 → size=3（实际 " + ActivityLog.size() + "）");

        List<ActivityLog.Entry> snap = ActivityLog.snapshot();
        check(snap.get(0).pkg.equals("com.c"), "快照最新在前（首条是 com.c）");
        check(snap.get(2).pkg.equals("com.a"), "快照末尾是最旧的 com.a");
        check(snap.get(0).count == 1, "首次出现计数为 1");

        // 连续相同：合并而非新增
        ActivityLog.add(null, "com.c", "com.c.CActivity", null);
        ActivityLog.add(null, "com.c", "com.c.CActivity", null);
        check(ActivityLog.size() == 3, "连续重复不新增（size 仍 3）");
        check(ActivityLog.snapshot().get(0).count == 3,
                "连续重复累加计数（实际 " + ActivityLog.snapshot().get(0).count + "）");

        // 隔开后再回来：算新的一条（同一页面的两次访问应分别可见）
        ActivityLog.add(null, "com.d", "com.d.DActivity", null);
        ActivityLog.add(null, "com.c", "com.c.CActivity", null);
        check(ActivityLog.size() == 5, "被别的页面隔开后重新计为新记录（size=5，实际 "
                + ActivityLog.size() + "）");

        // ---------------- 展示字段 ----------------
        ActivityLog.Entry e = ActivityLog.snapshot().get(0);
        check(e.shortName().equals("CActivity"), "短名取最后一段：" + e.shortName());
        check(e.full().equals("com.c/com.c.CActivity"), "full = 包名/类名：" + e.full());
        check(e.title().contains("CActivity"), "标题含 Activity 短名：" + e.title());
        check(e.title().contains("com.c"), "label 为空时用包名兜底：" + e.title());

        // 显式给 label
        ActivityLog.add(null, "com.x", "com.x.XActivity", "某应用");
        check("某应用".equals(ActivityLog.snapshot().get(0).label),
                "显式 label 生效");
        check(ActivityLog.snapshot().get(0).title().startsWith("某应用"),
                "标题以应用名开头");

        // ---------------- 上限 ----------------
        ActivityLog.clear();
        for (int i = 0; i < ActivityLog.MAX + 120; i++) {
            ActivityLog.add(null, "p" + i, "p" + i + ".A" + i, null);
        }
        check(ActivityLog.size() == ActivityLog.MAX,
                "超上限后截断到 MAX=" + ActivityLog.MAX + "（实际 " + ActivityLog.size() + "）");
        check(ActivityLog.snapshot().get(0).pkg.equals("p" + (ActivityLog.MAX + 119)),
                "保留的是最新的那条");
        check(!ActivityLog.snapshot().get(ActivityLog.MAX - 1).pkg.equals("p0"),
                "最旧的已被淘汰");

        ActivityLog.clear();
        check(ActivityLog.size() == 0, "再次 clear 归零");

        System.out.println();
        System.out.println("RESULT  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
