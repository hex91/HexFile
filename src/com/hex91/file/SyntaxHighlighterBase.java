package com.hex91.file;

/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *   移植自 Markor（gsantner/markor）frontend/textview/SyntaxHighlighterBase。
 *   改动：剥离 AppSettings/GsContextUtils/GsTextUtils/GsCallback 依赖——
 *   配置改为字段 + configure(paint)；工具函数改调 MtTextUtils；
 *   GsCallback.r1 替换为内部函数接口 MatcherFn。
 *
 *   设计要点（原作者注释）：
 *   Android EditText 用 SpannableStringBuilder + DynamicLayout，大量 span 时性能差。
 *   本类做法：1) 计算全文所有 span；2) 只应用视口内的 span；3) 滚动时更新（移除+重应用）。
 *   span 分动态/静态两类：动态 span 随滚动更新；静态 span（影响布局，如字号）只应用一次。
 *   编辑时对 span 做 fixup 平移修正；span 计算放后台线程，防卡 UI。
 *
#########################################################*/

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UpdateAppearance;
import android.text.style.UpdateLayout;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class SyntaxHighlighterBase {

    protected final static int LONG_HIGHLIGHTING_DELAY = 2400;

    private static final Pattern PATTERN_TAB = Pattern.compile("\t");

    /**
     * Url pattern with required http/https protocol. Case-sensitive.
     */
    public static final Pattern URL = Pattern.compile("\\bhttps?://(?:(?:[-;:&=+$,\\w]+@)?[A-Za-z0-9.-]+|(?:www\\.|[-;:&=+$,\\w]+@)[A-Za-z0-9.-]+)(?:/[+~%/.\\w_-]*\\??[-+=&;%@.\\w_]*#?[.!/\\\\\\w]*)?");

    /** Matcher -> span 生成函数（替代 Markor 的 GsCallback.r1） */
    public interface MatcherFn {
        Object callback(Matcher m);
    }

    // Derived classes should override this to generate all spans
    protected abstract void generateSpans();

    public int getHighlightingDelay() {
        return _delay;
    }

    // Configuration - to be set before calling configure
    protected int _delay = LONG_HIGHLIGHTING_DELAY;
    protected int _tabSize = 1;
    protected boolean _isDarkMode = false;
    protected int _textColor = Color.BLACK;
    protected String _fontFamily = "";

    public SyntaxHighlighterBase configure() {
        return configure(null);
    }

    /**
     * Configure this highlighter. Call before doing any highlighting.
     *
     * @param paint Optional paint to pass in - used for text parameters
     * @return Highlighter
     */
    public SyntaxHighlighterBase configure(final Paint paint) {
        if (paint != null && _fontMetricsWidth > 0f) {
            _tabSize = (int) (_tabWidthChars * paint.measureText(" "));
        }
        return this;
    }

    /** tab 宽度（字符数），默认 1；<=0 表示不替换 tab */
    protected float _tabWidthChars = 1f;
    /** 空格宽度缓存（由 configure(paint) 使用）；>0 时才替换 tab */
    protected float _fontMetricsWidth = -1f;

    // Instance
    // ---------------------------------------------------------------------------------------------

    /**
     * A class holding any span
     */
    public static class SpanGroup implements Comparable<SpanGroup> {
        public int start, end;
        public final Object span;
        final boolean isStatic;
        final boolean needsReflow;
        final int type;

        SpanGroup(Object span, int start, int end, int type) {
            this.span = span;
            this.start = start;
            this.end = end;
            this.type = type;

            needsReflow = span instanceof StaticSpan;
            isStatic = needsReflow || span instanceof UpdateLayout;
        }

        @Override
        public int compareTo(final SpanGroup o) {
            return start - o.start;
        }
    }

    private static class ForceUpdateLayout implements UpdateLayout {
        // Empty class - just implements UpdateLayout
    }

    public interface StaticSpan extends UpdateAppearance {
    }

    private final ForceUpdateLayout _layoutUpdater;

    private final List<SpanGroup> _groups, _groupBuffer;
    private final NavigableSet<Integer> _appliedDynamic;
    private boolean _staticApplied = false;
    private int _fixupAfter = -1, _fixupDelta = 0;

    protected Spannable _spannable;

    public SyntaxHighlighterBase() {
        _groups = new ArrayList<>();
        _groupBuffer = new ArrayList<>();
        _appliedDynamic = new TreeSet<>();
        _layoutUpdater = new ForceUpdateLayout();
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Removes all dynamic spans applied by this highlighter to the currently set spannable
     *
     * @return this
     */
    public SyntaxHighlighterBase clearDynamic() {
        if (_spannable == null) {
            return this;
        }

        final Iterator<Integer> it = _appliedDynamic.descendingIterator();
        while (it.hasNext()) {
            _spannable.removeSpan(_groups.get(it.next()).span);
        }
        _appliedDynamic.clear();

        return this;
    }

    /**
     * Removes all static spans applied by this highlighter to the currently set spannable
     */
    public SyntaxHighlighterBase clearStatic(final boolean reflow) {
        if (_spannable == null) {
            return this;
        }

        boolean needsReflow = false;
        for (int i = _groups.size() - 1; i >= 0; i--) {
            final SpanGroup group = _groups.get(i);
            if (group != null && group.isStatic) {
                needsReflow |= group.needsReflow;
                _spannable.removeSpan(group.span);
            }
        }

        if (reflow && needsReflow) {
            reflow();
        }

        _staticApplied = false;

        return this;
    }

    public SyntaxHighlighterBase clearComputed() {
        _groups.clear();
        return this;
    }

    /**
     * Change the currently attached spannable.
     * Caller is responsible for clearing spans attached to existing spannable
     */
    public SyntaxHighlighterBase setSpannable(final Spannable spannable) {
        if (spannable != _spannable) {
            _groups.clear();
            _appliedDynamic.clear();
            _spannable = spannable;
        }

        return this;
    }

    // Get currently attached spannable
    public Spannable getSpannable() {
        return _spannable;
    }

    public boolean hasSpans() {
        return _spannable != null && !_groups.isEmpty();
    }

    /**
     * Helper to change spans in 'onTextChanged'
     */
    public SyntaxHighlighterBase fixup(final int start, final int before, final int count) {
        return fixup(start + before, count - before);
    }

    /**
     * Adjust all currently computed spans so that the spans are still valid after text changes
     * We internally buffer / batch these fixes for increased performance
     */
    public SyntaxHighlighterBase fixup(final int after, final int delta) {
        if (_fixupAfter == -1) {
            _fixupAfter = after;
            _fixupDelta = delta;
        } else if (isFixupOverlap(after, delta)) {
            _fixupAfter = Math.min(_fixupAfter, after);
            _fixupDelta += delta;
        } else {
            applyFixup();
        }
        return this;
    }

    // Test if fixup region overlaps with the current fixup
    private boolean isFixupOverlap(final int after, final int delta) {
        return (after >= _fixupAfter && after <= _fixupAfter + Math.abs(_fixupDelta)) ||
                (_fixupAfter >= after && _fixupAfter <= after + Math.abs(delta));
    }

    private SyntaxHighlighterBase applyFixup() {
        if (_fixupAfter >= 0 && _fixupDelta != 0) {
            for (int i = _groups.size() - 1; i >= 0; i--) {
                final SpanGroup group = _groups.get(i);
                // Very simple fixup. If the group is entirely after 'after', adjust it's region
                if (group.start <= _fixupAfter) {
                    break;
                } else {
                    group.start += _fixupDelta;
                    group.end += _fixupDelta;
                }
            }
            clearFixup();
        }
        return this;
    }

    private void clearFixup() {
        _fixupAfter = -1;
        _fixupDelta = 0;
    }

    public SyntaxHighlighterBase applyDynamic() {
        return applyDynamic(new int[]{0, _spannable.length()});
    }

    /**
     * Apply spans which intersect region [start, end)
     *
     * @return this
     */
    public SyntaxHighlighterBase applyDynamic(final int[] range) {
        if (MtTextUtils.isValidSelection(_spannable, range) && range.length >= 2) {
            applyFixup();
            final int length = _spannable.length();
            for (int i = 0; i < _groups.size(); i++) {
                final SpanGroup group = _groups.get(i);

                if (group.isStatic) {
                    continue;
                }

                if (group.start >= range[1]) {
                    break;
                }

                final boolean valid = group.start >= 0 && group.end > range[0] && group.end <= length;
                if (valid && !_appliedDynamic.contains(i)) {
                    _spannable.setSpan(group.span, group.start, group.end, group.type);
                    _appliedDynamic.add(i);
                }
            }
        }
        return this;
    }

    public SyntaxHighlighterBase applyStatic() {
        if (_spannable != null && !_staticApplied) {
            applyFixup();

            boolean needsReflow = false;
            for (final SpanGroup group : _groups) {
                if (group != null && group.isStatic) {
                    needsReflow |= group.needsReflow;
                    _spannable.setSpan(group.span, group.start, group.end, group.type);
                }
            }

            if (needsReflow) {
                reflow();
            }

            _staticApplied = true;
        }

        return this;
    }

    public final SyntaxHighlighterBase reflow() {
        return reflow(new int[]{0, _spannable.length()});
    }

    // Reflow selected region's lines
    public final SyntaxHighlighterBase reflow(final int[] range) {
        if (MtTextUtils.isValidSelection(_spannable, range) && range.length >= 2) {
            _spannable.setSpan(_layoutUpdater, range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            _spannable.removeSpan(_layoutUpdater);
        }
        return this;
    }

    public final SyntaxHighlighterBase recompute() {
        return compute().setComputed();
    }

    /**
     * Make computed spans current. References to existing spans will be lost.
     * Caller is responsible for calling 'clear()' before this, if necessary
     */
    public final SyntaxHighlighterBase setComputed() {
        _groups.clear();
        _appliedDynamic.clear();
        _staticApplied = false;
        _groups.addAll(_groupBuffer);
        _groupBuffer.clear();
        clearFixup();
        return this;
    }

    /**
     * Compute all highlighting spans to a buffer.
     * The buffer is not made current until one calls 'setComputed'
     */
    public final SyntaxHighlighterBase compute() {
        _groupBuffer.clear();

        if (TextUtils.isEmpty(_spannable)) {
            return this;
        }

        // Highlighting cannot generate exceptions!
        try {
            generateSpans();
            Collections.sort(_groupBuffer); // Dramatically improves performance
        } catch (Exception ex) {
            Log.w(getClass().getName(), ex);
        } catch (Error er) {
            Log.w(getClass().getName(), er);
        }

        return this;
    }

    //
    // Helpers for creating spans
    //

    protected final void addSpanGroup(final Object span, final int start, final int end) {
        if (end >= start && span != null) {
            _groupBuffer.add(new SpanGroup(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE));
        }
    }

    protected final void addSpanGroup(final Object span, final int start, final int end, final int type) {
        if (end >= start && span != null) {
            _groupBuffer.add(new SpanGroup(span, start, end, type));
        }
    }

    protected final void createSpanForMatches(final Pattern pattern, final MatcherFn creator, int... groupsToMatch) {
        if (groupsToMatch == null || groupsToMatch.length < 1) {
            groupsToMatch = new int[]{0};
        }
        final Matcher m = pattern.matcher(_spannable);

        while (m.find()) {
            final Object span = creator.callback(m);
            if (span != null) {
                for (final int g : groupsToMatch) {
                    final int start = m.start(g);
                    final int end = m.end(g);
                    if ((g == 0 || g <= m.groupCount()) && Math.abs(end - start) > 0) {
                        addSpanGroup(span, start, end);
                    }
                }
            }
        }
    }

    protected final void createStyleSpanForMatches(final Pattern pattern, final int style, int... groupsToMatch) {
        createSpanForMatches(pattern, new HighlightSpan().setTypeface(style), groupsToMatch);
    }

    protected final void createColorSpanForMatches(final Pattern pattern, final int color, int... groupsToMatch) {
        createSpanForMatches(pattern, new HighlightSpan().setForeColor(color), groupsToMatch);
    }

    protected final void createColorBackgroundSpan(final Pattern pattern, final int color, int... groupsToMatch) {
        createSpanForMatches(pattern, new HighlightSpan().setBackColor(color), groupsToMatch);
    }

    protected final void createStrikeThroughSpanForMatches(final Pattern pattern, int... groupsToMatch) {
        createSpanForMatches(pattern, new HighlightSpan().setStrike(true), groupsToMatch);
    }

    protected final void createTypefaceSpanForMatches(Pattern pattern, final String typeface, int... groupsToMatch) {
        createSpanForMatches(pattern, new MatcherFn() {
            @Override
            public Object callback(Matcher matcher) {
                return new TypefaceSpan(typeface);
            }
        }, groupsToMatch);
    }

    protected final void createRelativeSizeSpanForMatches(final Pattern pattern, float relativeSize, int... groupsToMatch) {
        createSpanForMatches(pattern, new MatcherFn() {
            @Override
            public Object callback(Matcher matcher) {
                return new RelativeSizeSpan(relativeSize);
            }
        }, groupsToMatch);
    }

    protected final void createReplacementSpanForMatches(final Pattern pattern, final int charWidth, int... groupsToMatch) {
        createSpanForMatches(pattern, new MatcherFn() {
            @Override
            public Object callback(Matcher matcher) {
                return new ReplacementSpan() {
                    @Override
                    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
                        return charWidth;
                    }

                    @Override
                    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
                    }
                };
            }
        }, groupsToMatch);
    }

    protected final void createMonospaceSpanForMatches(final Pattern pattern, int... groupsToMatch) {
        createTypefaceSpanForMatches(pattern, "monospace", groupsToMatch);
    }

    protected final void createColoredUnderlineSpanForMatches(final Pattern pattern, int color, int... groupsToMatch) {
        createSpanForMatches(pattern, new MatcherFn() {
            @Override
            public Object callback(Matcher matcher) {
                return new ColorUnderlineSpan(color, null);
            }
        }, groupsToMatch);
    }

    protected final void createColoredUnderlineSpanForMatches(final Pattern pattern, final MatcherFn creator, int... groupsToMatch) {
        createSpanForMatches(pattern, creator, groupsToMatch);
    }

    protected final void createSuperscriptStyleSpanForMatches(final Pattern pattern, final int... groupsToMatch) {
        createSpanForMatches(pattern, new MatcherFn() {
            @Override
            public Object callback(Matcher matcher) {
                return new SuperscriptSpan();
            }
        }, groupsToMatch);
    }

    protected final void createSubscriptStyleSpanForMatches(final Pattern pattern, final int... groupsToMatch) {
        createSpanForMatches(pattern, new MatcherFn() {
            @Override
            public Object callback(Matcher matcher) {
                return new SubscriptSpan();
            }
        }, groupsToMatch);
    }

    protected final void createTabSpans(final int tabWidth) {
        if (tabWidth > 0) {
            createReplacementSpanForMatches(PATTERN_TAB, tabWidth);
        }
    }

    protected final void createSmallBlueLinkSpans() {
        createSpanForMatches(URL, new HighlightSpan().setForeColor(0xff1ea3fd).setItalic(true).setTextScale(0.85f));
    }

    protected final void createUnderlineHexColorsSpans() {
        createColoredUnderlineSpanForMatches(ColorUnderlineSpan.HEX_CODE_UNDERLINE_PATTERN, new MatcherFn() {
            @Override
            public Object callback(Matcher m) {
                return new ColorUnderlineSpan(parseHexColor(m.group(1)), 3f);
            }
        }, 1);
    }

    /** 解析 #RRGGBB / #AARRGGBB 颜色字符串 */
    public static int parseHexColor(final String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return 0xFF000000;
        }
    }

    // We _do not_ implement UpdateLayout or Parcelable for performance reasons
    public static class HighlightSpan extends CharacterStyle implements UpdateAppearance, MatcherFn {

        public Boolean bold = null;
        public Boolean italic = null;
        public Boolean underline = null;
        public Boolean strikethrough = null;
        public Float textScale = null;
        public Integer foregroundColor = null;
        public Integer backgroundColor = null;

        // Setters. Use null (default) to indicate "don't change this value"
        public HighlightSpan setForeColor(final Integer color) {
            foregroundColor = color;
            return this;
        }

        public HighlightSpan setBackColor(final Integer color) {
            backgroundColor = color;
            return this;
        }

        public HighlightSpan setTextScale(final Float scale) {
            textScale = scale;
            return this;
        }

        public HighlightSpan setStrike(final Boolean val) {
            strikethrough = val;
            return this;
        }

        public HighlightSpan setUnderline(final Boolean val) {
            underline = val;
            return this;
        }

        public HighlightSpan setBold(final Boolean val) {
            bold = val;
            return this;
        }

        public HighlightSpan setItalic(final Boolean val) {
            italic = val;
            return this;
        }

        public HighlightSpan setTypeface(final int tf) {
            return setBold((tf & Typeface.BOLD) != 0).setItalic((tf & Typeface.ITALIC) != 0);
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            if (bold != null) {
                tp.setFakeBoldText(bold);
            }

            if (strikethrough != null) {
                tp.setStrikeThruText(strikethrough);
            }

            if (underline != null) {
                tp.setUnderlineText(underline);
            }

            if (italic != null && italic) {
                tp.setTextSkewX(-0.25f); // This is what android uses
            }

            if (foregroundColor != null) {
                tp.setColor(foregroundColor);
            }

            if (backgroundColor != null) {
                tp.bgColor = backgroundColor;
            }

            if (textScale != null) {
                tp.setTextSize(tp.getTextSize() * textScale);
            }
        }

        // The class is a callback which returns a clone of itself
        // So we can construct an instance and use it as a span generator
        @Override
        public HighlightSpan callback(Matcher m) {
            return new HighlightSpan()
                    .setForeColor(foregroundColor)
                    .setBackColor(backgroundColor)
                    .setBold(bold)
                    .setItalic(italic)
                    .setUnderline(underline)
                    .setStrike(strikethrough)
                    .setTextScale(textScale);
        }
    }

    /**
     * Inject additional spans into the current set of computed spans
     * <p>
     * These are added to the list of computed spans. Therefore:
     * 1. They are subject to modification by fixup
     * 2. They will need to be re-added after `setComputed()` is called
     */
    public SyntaxHighlighterBase addAdditional(final Collection<SpanGroup> additionalSpans) {
        if (!additionalSpans.isEmpty()) {
            _groups.addAll(additionalSpans);
            Collections.sort(_groups);
        }
        return this;
    }

    public SyntaxHighlighterBase addAdditional(final SpanGroup additionalSpan) {
        if (additionalSpan != null) {
            _groups.add(additionalSpan);
            Collections.sort(_groups);
        }
        return this;
    }

    public SyntaxHighlighterBase clearAdditional(final Collection<SpanGroup> additionalSpans) {
        _groups.removeAll(additionalSpans);
        return this;
    }

    public SyntaxHighlighterBase clearAdditional(SpanGroup additionalSpan) {
        if (additionalSpan != null) {
            _groups.remove(additionalSpan);
        }
        return this;
    }

    public static SpanGroup createBackgroundHighlight(final int start, final int end, final int color) {
        return new SpanGroup(new HighlightSpan().setBackColor(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
