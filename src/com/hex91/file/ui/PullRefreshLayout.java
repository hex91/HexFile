package com.hex91.file.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * 下拉刷新容器（v1.79.0）。
 *
 * 为什么要自己写：本项目是 aapt2 + javac 的纯脚本构建，没有 Gradle 依赖，
 * 拿不到 androidx 的 SwipeRefreshLayout；系统也没有等价控件（API 21 起只有
 * EdgeEffect 的拉伸光晕，不能触发动作）。所以这里用 FrameLayout 包一层，
 * 逻辑只有三件事：列表已经在顶部时接管竖直下拉、把内容整体下移、松手过阈值就回调。
 *
 * 关键约束：
 *  - 只在「列表滚不到上面」时才拦截，否则会抢掉 ListView 的正常滚动；
 *  - 用 ViewConfiguration 的 touchSlop 判断，避免横向轻扫（左右滑动选择/返回）被误判为下拉；
 *  - 子 View 用 translationY 下移，不改 layout，所以不会触发重新测量/布局，44dp 列表依旧流畅。
 */
public class PullRefreshLayout extends FrameLayout {

    public interface OnRefreshListener {
        void onRefresh();
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_PULL = 1;      // 手指按住拖动中
    private static final int STATE_REFRESH = 2;   // 已松手，正在刷新（转圈）

    private View content;
    private OnRefreshListener listener;

    private final int touchSlop;
    private final float trigger;      // 松手触发刷新需要的位移（dp 62）
    private final float maxOffset;    // 最大下拉位移，再拉也不动了（dp 96）
    private final float headerHeight; // 刷新中内容停留的位置（dp 46）

    private int state = STATE_IDLE;
    private float downY;
    private float offset;             // 当前内容下移量 px
    private boolean dragging;         // 已越过 slop，开始真正下拉
    private boolean pullEnabled = true;   // 多选时关掉，免得下拉被当成刷选
    private ValueAnimator anim;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final RectF arcRect = new RectF();

    public PullRefreshLayout(Context c) {
        this(c, null);
    }

    public PullRefreshLayout(Context c, AttributeSet attrs) {
        super(c, attrs);
        float d = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        trigger = 62 * d;
        maxOffset = 96 * d;
        headerHeight = 46 * d;

        bgPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1 * d);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(2.2f * d);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------ 对外接口

    public void setOnRefreshListener(OnRefreshListener l) {
        listener = l;
    }

    /** 多选模式里竖直拖动是「连续刷选」，此时不能让下拉刷新抢事件 */
    public void setPullEnabled(boolean on) {
        pullEnabled = on;
    }

    public boolean isRefreshing() {
        return state == STATE_REFRESH;
    }

    /** 代码主动进入「刷新中」状态（不回调 listener，调用方自己负责去刷新） */
    public void setRefreshing(boolean on) {
        if (on == isRefreshing()) return;
        cancelAnim();
        if (on) {
            state = STATE_REFRESH;
            offset = headerHeight;
            applyOffset(offset);
            invalidate();
        } else {
            state = STATE_IDLE;
            animateTo(0, null);
        }
    }

    /** 刷新结束：转圈收起，内容回位 */
    public void finishRefresh() {
        if (state != STATE_REFRESH) return;
        state = STATE_IDLE;
        animateTo(0, null);
    }

    // ------------------------------------------------------------ 手势

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) content = getChildAt(0);
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (content == null) content = child;
    }

    /** 列表是否已经滚到最上面（含空列表）；能往上滚就说明这一下拉该给 ListView */
    private boolean atTop() {
        View v = content();
        return v == null || !v.canScrollVertically(-1);
    }

    /**
     * 当前生效的内容视图。v1.80.0 起同一窗格里同时挂着 ListView 与 GridView，
     * 同一时刻只有一个 VISIBLE —— 以可见的那个为准（GONE 的那个滚不动，会误判成「在顶部」）。
     */
    private View content() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == VISIBLE) return v;
        }
        return content != null ? content : (getChildCount() > 0 ? getChildAt(0) : null);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (state == STATE_REFRESH || listener == null || !isEnabled() || !pullEnabled) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging) return true;
                float dy = ev.getY() - downY;
                // 竖直向下 + 已超 slop + 列表在顶部 → 开始下拉；横向滑动手势不受影响
                if (dy > touchSlop && atTop() && Math.abs(dy) > 0) {
                    dragging = true;
                    state = STATE_PULL;
                    offset = 0;
                    if (anim != null) anim.cancel();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (listener == null || state == STATE_REFRESH) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float dy = ev.getY() - downY;
                if (dy <= 0) {                       // 又推回去了：直接归零，避免内容悬在半空
                    applyOffset(0);
                    return true;
                }
                // 0.55 的阻尼：手指走 100px 内容只走 55px，越拉越沉，和原生手感接近
                applyOffset(Math.min(maxOffset, (dy - touchSlop) * 0.55f));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                if (ev.getActionMasked() == MotionEvent.ACTION_UP && offset >= trigger) {
                    state = STATE_REFRESH;
                    animateTo(headerHeight, null);
                    final OnRefreshListener l = listener;
                    // 延后一帧再回调：先让转圈画出来，回调里即使是同步读目录也不会看起来「没反应」
                    post(new Runnable() {
                        public void run() {
                            if (state == STATE_REFRESH) l.onRefresh();
                        }
                    });
                } else {
                    state = STATE_IDLE;
                    animateTo(0, null);
                }
                return true;
            }
        }
        return dragging;
    }

    private void applyOffset(float y) {
        offset = y;
        View v = content();
        if (v != null) v.setTranslationY(y);
        invalidate();
    }

    private void animateTo(float target, Runnable end) {
        cancelAnim();
        final float from = offset;
        anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration((long) (180 + Math.abs(target - from) * 0.6f));
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                applyOffset((Float) a.getAnimatedValue());
            }
        });
        if (end != null) anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { end.run(); }
        });
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // 内容下移后，顶部让出来的那块空白就是指示器区域，画在子 View 之前
        if (offset > 0.5f) drawIndicator(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawIndicator(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float progress = Math.min(1f, offset / trigger);          // 0~1：拉动进度
        float cx = getWidth() / 2f;
        float cy = offset / 2f;
        float r = (8 + 7 * progress) * d;                        // 8dp → 15dp，跟着拉动变大

        bgPaint.setColor(ThemeManager.surface(getContext()));
        strokePaint.setColor(UiDecor.alpha(ThemeManager.divider(getContext()), 0xCC));
        strokePaint.setStrokeWidth(1 * d);       // 画箭头时改过粗细，这里要还原
        canvas.drawCircle(cx, cy, r, bgPaint);
        canvas.drawCircle(cx, cy, r, strokePaint);

        if (state == STATE_REFRESH) {
            // 转圈：1/4 圆弧绕圆心匀速旋转（用 uptimeMillis 算角度，不占额外线程）
            float angle = (SystemClock.uptimeMillis() % 900) / 900f * 360f;
            arcPaint.setColor(ThemeManager.accent(getContext()));
            arcRect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f);
            canvas.drawArc(arcRect, angle, 260, false, arcPaint);
            postInvalidateOnAnimation();
            return;
        }

        // 箭头：拉到阈值前朝下，过阈值翻转朝上（提示「松手就刷新」）
        float flip = progress >= 1f ? 180f : 0f;
        canvas.save();
        canvas.rotate(flip, cx, cy);
        float s = r * 0.42f;
        arrow.reset();
        arrow.moveTo(cx - s, cy - s * 0.25f);
        arrow.lineTo(cx, cy + s * 0.42f);
        arrow.lineTo(cx + s, cy - s * 0.25f);
        strokePaint.setColor(ThemeManager.accent(getContext()));
        strokePaint.setStrokeWidth(2.2f * d);
        canvas.drawPath(arrow, strokePaint);
        canvas.restore();
    }
}
