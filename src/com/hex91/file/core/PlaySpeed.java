package com.hex91.file;

import java.util.Locale;

/**
 * 播放倍速档位表 —— v1.91.0。
 *
 * <p>约束来自需求：最高 3 倍。低于 0.25 倍在手机上已经完全听不出内容，
 * 上限 3 倍则是 ExoPlayer 的 {@code SonicAudioProcessor} 仍能保持音调不失真的实用区间
 * （再高会有明显「金属声」，且很多片源的音轨时间戳会追不上视频）。</p>
 *
 * <p>档位做成等差的「常用值」而不是 0.25 的整数倍：真实用户点「加速」是为了跳过片头，
 * 1.5 / 2.0 / 2.5 / 3.0 这四个整数档最常被点，把它们排在默认 1.0 的右侧几步之内，
 * 避免要连点七八次才能到 2 倍。</p>
 */
public final class PlaySpeed {

    /** 全部档位（升序）。UI 的「加速/减速」按此表前后移动 */
    public static final float[] STEPS = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f};

    /** 需求上限：3 倍 */
    public static final float MAX = 3.0f;
    /** 需求下限 */
    public static final float MIN = 0.25f;
    /** 长按「临时快进」用的倍率 —— 松手恢复原速 */
    public static final float BOOST = 3.0f;

    private PlaySpeed() {
    }

    /** 收敛到 [MIN, MAX]；非法值（NaN/0/负数）按 1.0 处理 */
    public static float clamp(float s) {
        if (Float.isNaN(s) || s <= 0f) return 1.0f;
        if (s < MIN) return MIN;
        if (s > MAX) return MAX;
        return s;
    }

    /** 最接近的档位下标（用于把存盘里的小数值吸附回档位） */
    public static int indexOf(float s) {
        float v = clamp(s);
        int best = 3;   // 1.0 的下标
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < STEPS.length; i++) {
            float d = Math.abs(STEPS[i] - v);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    /** 吸附到最近的合法档位 */
    public static float snap(float s) {
        return STEPS[indexOf(s)];
    }

    /**
     * 按方向取下一档。
     *
     * @param dir 正数 = 加速，负数 = 减速
     * @return 新倍速；已在端点时返回端点值（不循环，避免「一直点又回到 0.25」的困惑）
     */
    public static float step(float s, int dir) {
        int i = indexOf(s) + (dir > 0 ? 1 : dir < 0 ? -1 : 0);
        if (i < 0) i = 0;
        if (i >= STEPS.length) i = STEPS.length - 1;
        return STEPS[i];
    }

    /** 显示文本："1.0×"、"1.25×"、"3.0×" —— 固定一位小数，避免 1.0 显示成 "1" */
    public static String label(float s) {
        float v = clamp(s);
        return String.format(Locale.US, "%.2f", v).replaceAll("0$", "") + "×";
    }

    /**
     * 倍速是否「生效」——1.0 时不该显示成「1.0× 加速中」，
     * 界面据此决定是否高亮倍速按钮。
     */
    public static boolean isModified(float s) {
        return Math.abs(clamp(s) - 1.0f) > 0.001f;
    }
}
