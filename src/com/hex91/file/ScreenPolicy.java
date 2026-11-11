package com.hex91.file;

/**
 * 画面方向与显示比例策略 —— v1.94.0。
 *
 * <p>纯 Java、不依赖 android.*，可以在桌面 JVM 上直接单测
 * （见 test/ScreenPolicyTest.java）。Activity 只负责把这里的结论翻译成
 * ActivityInfo 的屏幕方向常量与 FrameLayout.LayoutParams，本身不做判断 ——
 * 判断散落在 Activity 里就会出现「横视频在竖屏窗口被拉满变形」这类问题。</p>
 *
 * <p><b>方向三档</b></p>
 * <ul>
 *   <li>{@link #AUTO}：横视频（宽 &gt; 高）横屏、竖视频（高 ≥ 宽）竖屏。
 *       这是默认档 —— 竖着拿手机看 16:9 电影，画面只占屏幕中间一条，
 *       不转屏就等于强迫用户扭脖子。</li>
 *   <li>{@link #PORTRAIT} / {@link #LANDSCAPE}：用户显式锁定，不随片源变。
 *       画中画式地「就想竖屏看完这一部」也靠它。</li>
 * </ul>
 *
 * <p><b>比例</b>：{@link #scaleFor} 只给等比比例（FIT 取 min、CROP 取 max），
 * 两个轴乘同一个系数 → 永远不变形。真正的「拉伸」不由本类负责，
 * 那是用户显式选择的一个独立档位（会让比例失真），不该混进等比计算。</p>
 */
public final class ScreenPolicy {

    /** 跟随视频比例（默认） */
    public static final int AUTO = 0;
    /** 锁定竖屏 */
    public static final int PORTRAIT = 1;
    /** 锁定横屏 */
    public static final int LANDSCAPE = 2;

    private ScreenPolicy() {
    }

    /** 越界值一律收敛到 AUTO（防止老版本/脏数据写进 7 这种值） */
    public static int normalize(int mode) {
        return mode == PORTRAIT || mode == LANDSCAPE ? mode : AUTO;
    }

    /** 三档循环：自动 → 竖屏 → 横屏 → 自动 */
    public static int next(int mode) {
        int m = normalize(mode);
        if (m == AUTO) return PORTRAIT;
        if (m == PORTRAIT) return LANDSCAPE;
        return AUTO;
    }

    /** chip 上的短文案 */
    public static String label(int mode) {
        int m = normalize(mode);
        return m == PORTRAIT ? "竖屏" : m == LANDSCAPE ? "横屏" : "自动";
    }

    /** 更完整的说明（对话框/提示用） */
    public static String describe(int mode) {
        int m = normalize(mode);
        if (m == PORTRAIT) return "竖屏（锁定）";
        if (m == LANDSCAPE) return "横屏（锁定）";
        return "自动（按视频比例）";
    }

    /**
     * 是否「横视频」。宽严格大于高才算 —— 1:1 方形视频（抖音同款）
     * 塞进横屏会左右两条大黑边，竖屏反而更合适；宽高相等或未知都不算横。
     */
    public static boolean isWide(int w, int h) {
        return w > 0 && h > 0 && w > h;
    }

    /**
     * 目标方向是否为横屏。
     *
     * @param mode             方向档位
     * @param w                视频宽（已按像素宽高比校正）
     * @param h                视频高
     * @param currentLandscape 当前窗口是不是横的（尺寸还没解析出来时用它保持现状，
     *                         避免「刚进播放器先竖一下再横过去」的闪烁）
     */
    public static boolean wantLandscape(int mode, int w, int h, boolean currentLandscape) {
        int m = normalize(mode);
        if (m == LANDSCAPE) return true;
        if (m == PORTRAIT) return false;
        if (w <= 0 || h <= 0) return currentLandscape;
        return w > h;
    }

    /** 等比完整显示的比例（min）：画面全可见，多出来的方向留黑 */
    public static float fitScale(int vw, int vh, int cw, int ch) {
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return 1f;
        return Math.min((float) cw / vw, (float) ch / vh);
    }

    /** 等比铺满的比例（max）：画面填满窗口，超出部分被父容器裁掉 */
    public static float coverScale(int vw, int vh, int cw, int ch) {
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return 1f;
        return Math.max((float) cw / vw, (float) ch / vh);
    }

    /**
     * 按缩放模式取等比比例。返回 0 表示「不做等比」（参数非法，调用方保持满屏）。
     *
     * @param crop true = 铺满裁切，false = 完整显示适配
     */
    public static float scaleFor(boolean crop, int vw, int vh, int cw, int ch) {
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return 0f;
        float sx = (float) cw / vw;
        float sy = (float) ch / vh;
        return crop ? Math.max(sx, sy) : Math.min(sx, sy);
    }
}
