package com.hex91.file;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 雷达扫描视图（v1.74.0）。
 *
 * <p>自绘：同心圆 + 十字准线 + 旋转扫描扇 + 设备光点。设备位置由 ID 的哈希稳定映射到
 * 极坐标（角度/半径），所以同一个设备每次刷新都停在同一个方位，不会满屏乱跳——
 * 这是「雷达」看起来可信的关键，纯随机位置会像抽奖。</p>
 *
 * <p>配色刻意不用主题色：无论浅色还是深色主题，雷达都是深空底色 + 青绿光点，
 * 保持「仪器面板」的观感；外框圆角卡片由外层布局负责。</p>
 */
public class RadarView extends View {

    public interface OnDeviceTap {
        void onTap(LanProtocol.Device device);
    }

    private static final int C_DEEP = 0xFF060C12;
    private static final int C_MID = 0xFF0B1A24;
    private static final int C_RING = 0xFF2BE0C8;
    private static final int C_BLIP = 0xFF5CFFC8;
    private static final int C_TEXT = 0xFFD6EEF5;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<LanProtocol.Device> devices = new ArrayList<LanProtocol.Device>();
    private final float[] xs = new float[32];
    private final float[] ys = new float[32];
    private int plotted;

    private float angle;
    private float cx, cy, radius;
    private float shaderCx = -1, shaderCy = -1;
    private String emptyText = "正在搜索设备…";
    private OnDeviceTap tapListener;
    private ValueAnimator animator;
    private final Matrix sweepMatrix = new Matrix();

    public RadarView(Context c) {
        this(c, null);
    }

    public RadarView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        float den = getResources().getDisplayMetrics().density;

        bgPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.2f * den);
        ringPaint.setColor(C_RING);
        ringPaint.setAlpha(70);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * den);
        gridPaint.setColor(C_RING);
        gridPaint.setAlpha(32);

        blipPaint.setStyle(Paint.Style.FILL);
        blipPaint.setColor(C_BLIP);

        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(C_BLIP);

        textPaint.setColor(C_TEXT);
        textPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);

        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(C_RING);
    }

    public void setOnDeviceTap(OnDeviceTap l) {
        this.tapListener = l;
    }

    public void setDevices(List<LanProtocol.Device> list) {
        devices.clear();
        if (list != null) devices.addAll(list);
        invalidate();
    }

    public void setEmptyText(String text) {
        if (text != null) emptyText = text;
        invalidate();
    }

    public void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(3200L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                Object v = a.getAnimatedValue();
                angle = v instanceof Float ? ((Float) v).floatValue() : 0f;
                invalidate();
            }
        });
        animator.start();
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        int size = Math.min(w, h);
        if (size <= 0) size = (int) (240 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(size, wSpec), resolveSize(size, hSpec));
    }

    private void computeGeometry() {
        float pad = 10f * getResources().getDisplayMetrics().density;
        cx = getWidth() / 2f;
        cy = getHeight() / 2f;
        radius = Math.min(getWidth(), getHeight()) / 2f - pad;
        if (radius <= 0) radius = 1f;
        if (bgPaint.getShader() == null || Math.abs(cx - shaderCx) > 1f
                || Math.abs(cy - shaderCy) > 1f) {
            // 尺寸/旋转变化后圆心会移动，shader 必须重建，否则扫描扇和暗角整体偏位
            shaderCx = cx;
            shaderCy = cy;
            bgPaint.setShader(new RadialGradient(cx, cy, Math.max(radius, 1f),
                    new int[]{C_MID, C_DEEP}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            SweepGradient g = new SweepGradient(cx, cy,
                    new int[]{0x0000E5C8, 0xCC2BE0C8, 0x332BE0C8, 0x002BE0C8},
                    new float[]{0f, 0.05f, 0.18f, 0.30f});
            sweepPaint.setShader(g);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        computeGeometry();
        final float den = getResources().getDisplayMetrics().density;

        canvas.drawCircle(cx, cy, radius, bgPaint);

        // 同心圆（4 环）+ 8 条辐条
        for (int i = 1; i <= 4; i++) {
            canvas.drawCircle(cx, cy, radius * i / 4f, ringPaint);
        }
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float ex = cx + (float) Math.cos(a) * radius;
            float ey = cy + (float) Math.sin(a) * radius;
            canvas.drawLine(cx, cy, ex, ey, gridPaint);
        }
        canvas.drawCircle(cx, cy, radius, ringPaint);

        // 旋转扫描扇：整圆画一遍，只有前 30% 的扇区有颜色
        sweepMatrix.reset();
        sweepMatrix.postRotate(angle, cx, cy);
        if (sweepPaint.getShader() != null) {
            sweepPaint.getShader().setLocalMatrix(sweepMatrix);
        }
        canvas.drawCircle(cx, cy, radius, sweepPaint);

        // 中心：本机
        canvas.drawCircle(cx, cy, 3.2f * den, centerPaint);
        glowPaint.setAlpha(40);
        canvas.drawCircle(cx, cy, 8f * den, glowPaint);

        plotted = 0;
        final int n = Math.min(devices.size(), xs.length);
        final long t = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < n; i++) {
            LanProtocol.Device d = devices.get(i);
            int h = d.id.hashCode();
            double deg = Math.abs(h % 360);
            float rf = 0.42f + (Math.abs((h / 7919) % 50)) / 100f;   // 0.42 ~ 0.91
            double rad = Math.toRadians(deg);
            float x = cx + (float) Math.cos(rad) * radius * rf;
            float y = cy + (float) Math.sin(rad) * radius * rf;
            xs[i] = x;
            ys[i] = y;
            plotted++;

            // 呼吸光圈：每个设备相位错开，看起来像在「持续被探测到」
            float pulse = (float) ((Math.sin(t / 420.0 + i * 1.7) + 1) / 2);
            glowPaint.setAlpha((int) (30 + 55 * pulse));
            canvas.drawCircle(x, y, (11f + 5f * pulse) * den, glowPaint);
            glowPaint.setAlpha(28);
            canvas.drawCircle(x, y, 19f * den, glowPaint);

            canvas.drawCircle(x, y, 4.2f * den, blipPaint);
            textPaint.setAlpha(235);
            String label = d.name.length() > 9 ? d.name.substring(0, 9) + "…" : d.name;
            float ty = y + 18f * den;
            if (ty > getHeight() - 4 * den) ty = y - 12f * den;
            float tx = Math.max(radius * 0.5f, Math.min(getWidth() - radius * 0.5f, x));
            canvas.drawText(label, tx, ty, textPaint);
        }

        if (plotted == 0) {
            textPaint.setAlpha(150);
            canvas.drawText(emptyText, cx, cy + 26f * den, textPaint);
            textPaint.setAlpha(235);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float hit = 38f * getResources().getDisplayMetrics().density;
            int best = -1;
            float bestD = hit;
            for (int i = 0; i < plotted; i++) {
                float dx = e.getX() - xs[i];
                float dy = e.getY() - ys[i];
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best >= 0 && best < devices.size() && tapListener != null) {
                performClick();
                tapListener.onTap(devices.get(best));
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** 供无障碍/测试用：当前绘制的光点数量 */
    public int blipCount() {
        return plotted;
    }

    /** 状态色，给外部小圆点用 */
    public static int accentColor() {
        return C_RING;
    }

    /** 深空底色，外层卡片背景可复用，避免出现白色缝隙 */
    public static int panelColor() {
        return Color.rgb(6, 12, 18);
    }
}
