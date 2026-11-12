package com.hex91.file.ui;

import com.hex91.file.fs.*;
import com.hex91.file.core.*;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 播放器偏好与断点续播记录 —— v1.91.0。
 *
 * <p>单独一文件而不是塞进设置页的 WorkDir 那套：播放器偏好是「随手改、随手存」的东西
 * （倍速、循环、缩放），和设置页里那些「改一次影响全局」的开关不是一类，
 * 混在一个 keys 空间里容易互相踩。</p>
 *
 * <p>断点续播按「文件绝对路径」存位点。视频文件改名/移动后位点自然失效，
 * 这点可以接受 —— 比按文件名存（不同目录同名文件夹会串）安全得多。</p>
 */
public final class PlayerPrefs {

    private static final String FILE = "hf_player";

    /** 缩放模式：等比完整显示（默认，不裁切不拉伸） */
    public static final int SCALE_FIT = 0;
    /** 铺满裁切（保持比例，切掉多余边） */
    public static final int SCALE_CROP = 1;
    /** 拉伸填满（比例会变形，仅用于特殊片源） */
    public static final int SCALE_STRETCH = 2;

    private PlayerPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------- 倍速 / 循环 / 缩放

    public static float speed(Context c) {
        // 默认 1.0；存盘值一律过一遍 clamp，防止老版本写进 4.0 这种越界值
        return PlaySpeed.snap(sp(c).getFloat("speed", 1f));
    }

    public static void setSpeed(Context c, float s) {
        sp(c).edit().putFloat("speed", PlaySpeed.snap(s)).apply();
    }

    /** 循环模式，取值直接用 Player.REPEAT_MODE_* */
    public static int repeat(Context c) {
        return sp(c).getInt("repeat", 0);
    }

    public static void setRepeat(Context c, int mode) {
        sp(c).edit().putInt("repeat", mode).apply();
    }

    public static int scale(Context c) {
        int v = sp(c).getInt("scale", SCALE_FIT);
        return v < 0 || v > SCALE_STRETCH ? SCALE_FIT : v;
    }

    public static void setScale(Context c, int mode) {
        sp(c).edit().putInt("scale", mode).apply();
    }

    /**
     * 画面方向偏好，取值用 {@link ScreenPolicy#AUTO}/{@code PORTRAIT}/{@code LANDSCAPE}。
     * 默认 AUTO：横视频自动横屏，竖视频自动竖屏 —— 用户第一次用就该是对的，
     * 不该先去设置里翻一遍。
     */
    public static int orientation(Context c) {
        return ScreenPolicy.normalize(sp(c).getInt("orient", ScreenPolicy.AUTO));
    }

    public static void setOrientation(Context c, int mode) {
        sp(c).edit().putInt("orient", ScreenPolicy.normalize(mode)).apply();
    }

    public static boolean thumbsEnabled(Context c) {
        return sp(c).getBoolean("thumbs", true);
    }

    public static void setThumbsEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("thumbs", on).apply();
    }

    // ---------------------------------------------------------- 断点续播

    public static long position(Context c, String key) {
        if (key == null) return 0;
        return sp(c).getLong("pos:" + key, 0);
    }

    public static void setPosition(Context c, String key, long ms) {
        if (key == null) return;
        SharedPreferences.Editor e = sp(c).edit();
        if (ms <= 0) e.remove("pos:" + key);
        else e.putLong("pos:" + key, ms);
        e.apply();
    }

    /** 清掉所有续播位点（设置页「清除播放记录」用） */
    public static void clearPositions(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : sp(c).getAll().keySet()) {
            if (k.startsWith("pos:")) e.remove(k);
        }
        e.apply();
    }
}
