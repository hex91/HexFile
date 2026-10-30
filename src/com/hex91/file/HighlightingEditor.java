package com.hex91.file;

/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *   移植自 Markor（gsantner/markor）frontend/textview/HighlightingEditor。
 *   改动（适配 HexFile 无 AndroidX / 无 AppSettings 环境）：
 *   - extends EditText（原 AppCompatEditText）
 *   - 去掉 AppSettings：构造不再读设置，颜色/开关走 setter / 高亮器自带
 *   - 去掉 R / MainActivity / GsContextUtils / GsCallback / GsTextWatcherAdapter /
 *     TextViewUtils 依赖（工具改调 MtTextUtils，回调改内部接口）
 *   - 保留全部高亮机制：视口内动态应用 span、后台线程计算、编辑 fixup、
 *     搜索高亮注入（setSearchMatches）、自动格式化、滚动位置保存
 *
 *   用法注意：本编辑器设计为「自身不滚动」（isScrollContainer=false /
 *   scrollbars=none），由外层滚动容器滚动，配合 LineNumbersView 平级摆放，
 *   行号与文本天然对齐。布局见 res/layout/activity_editor.xml。
 *
#########################################################*/

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcelable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 编辑器核心 EditText：超长文本的异步语法高亮（后台分词 + 节流刷新，
 * 避免主线程卡顿）、与 LineNumbersView 行号联动、撤销栈管理。文本编辑相关 Activity 共用。
 */
public class HighlightingEditor extends EditText {

    final static int HIGHLIGHT_SHIFT_LINES = 8;              // Lines to scroll before hl updated
    final static float HIGHLIGHT_REGION_SIZE = 0.75f;        // Minimum extra screens to highlight (should be > 0.5 to cover screen)

    public final static String PLACE_CURSOR_HERE_TOKEN = "%%PLACE_CURSOR_HERE%%";
    public final static String INSERT_SELECTION_HERE_TOKEN = "%%INSERT_SELECTION_HERE%%";

    /** 滚动位置获取回调（外层滚动容器实现） */
    public interface ScrollGetter {
        int[] get();
    }

    /** 滚动位置应用回调（外层滚动容器实现） */
    public interface ScrollApplier {
        void apply(int x, int y);
    }

    private boolean _accessibilityEnabled = true;
    private boolean _isSpellingRedUnderline = true;
    private SyntaxHighlighterBase _hl;
    private Runnable _hlDebounced;        // Debounced runnable which recomputes highlighting
    private boolean _hlEnabled;           // Whether highlighting is enabled
    private final Rect _oldHlRect;        // Rect highlighting was previously applied to
    private final Rect _hlRect;           // Current rect
    private int _hlShiftThreshold = -1;   // How much to scroll before re-apply highlight
    private InputFilter _autoFormatFilter;
    private TextWatcher _autoFormatModifier;
    private boolean _autoFormatEnabled;
    private boolean _saveInstanceState = true;
    private final ExecutorService executor = new ThreadPoolExecutor(0, 3, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final AtomicBoolean _textUnchangedWhileHighlighting = new AtomicBoolean(true);
    private int _textChangedNumber;
    private final Runnable _textChangedRecorder = MtTextUtils.makeDebounced(getHandler(), 1000, new Runnable() {
        @Override
        public void run() {
            _textChangedNumber++;
        }
    });
    private StaticCursorDrawer _staticCursorDrawer;
    private int _cursorColor = 0xFF6200EE;
    private ScrollGetter _getScrollCallback;
    private ScrollApplier _applyScrollCallback;
    private int[] _savedScrollPosition;

    public HighlightingEditor(Context context, AttributeSet attrs) {
        super(context, attrs);

        setAutoFormatters(null, null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setFallbackLineSpacing(false);
        }

        _hlEnabled = false;
        _oldHlRect = new Rect();
        _hlRect = new Rect();

        addTextChangedListener(new TextWatcher() {

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (_hlEnabled && _hl != null) {
                    _textUnchangedWhileHighlighting.set(false);
                    _hl.fixup(start, before, count);
                }
            }

            @Override
            public void afterTextChanged(final Editable s) {
                if (_hlEnabled && _hl != null && _hlDebounced != null) {
                    _hlDebounced.run();
                }
                _textChangedRecorder.run();
            }
        });

        // Listen to and update highlighting
        final ViewTreeObserver observer = getViewTreeObserver();
        observer.addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                updateHighlighting();
            }
        });
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                updateHighlighting();
            }
        });
    }

    // Highlighting
    // ---------------------------------------------------------------------------------------------

    // Batch edit spans (or anything else, really)
    // This triggers a reflow which will bring focus back to the cursor.
    // Therefore, it cannot be used for updating the highlighting as one scrolls
    private void batch(final Runnable runnable) {
        try {
            beginBatchEdit();
            runnable.run();
        } finally {
            endBatchEdit(); // This can trigger reflow which will bring focus back to the cursor and reset scroll position
        }
    }

    public void setScrollCallbacks(final ScrollGetter get, final ScrollApplier apply) {
        _getScrollCallback = get;
        _applyScrollCallback = apply;
    }

    private void saveScrollPositionForLayout() {
        if (_savedScrollPosition == null && _getScrollCallback != null) {
            _savedScrollPosition = _getScrollCallback.get();
        }
    }

    private void applySavedScrollPosition() {
        final int[] position = _savedScrollPosition;
        _savedScrollPosition = null;
        if (position != null && position.length >= 2 && _applyScrollCallback != null) {
            _applyScrollCallback.apply(position[0], position[1]);
        }
    }

    private boolean isScrollSignificant() {
        return Math.abs(_oldHlRect.top - _hlRect.top) > _hlShiftThreshold ||
                Math.abs(_hlRect.bottom - _oldHlRect.bottom) > _hlShiftThreshold;
    }

    // The order of tests here is important
    private boolean runHighlight(final boolean recompute) {
        return _hl != null && getLayout() != null &&
                (getLocalVisibleRect(_hlRect) || recompute) &&
                (recompute || _hl.hasSpans()) &&
                (recompute || isScrollSignificant());
    }

    private void updateHighlighting() {
        if (runHighlight(false)) {
            // Do not batch as we do not want to reflow
            _hl.clearDynamic().applyDynamic(hlRegion());
            _oldHlRect.set(_hlRect);
        }
    }

    public void recomputeHighlighting() {
        if (_hlEnabled && runHighlight(true)) {
            this.saveScrollPositionForLayout();
            batch(new Runnable() {
                @Override
                public void run() {
                    _hl.clearDynamic()
                            .clearStatic(false)
                            .recompute()
                            .addAdditional(_matches)
                            .applyStatic()
                            .applyDynamic(hlRegion());
                }
            });
            this.applySavedScrollPosition();
        }
    }

    /**
     * Computing the highlighting spans for a lot of text can be slow so we do it async
     */
    private void recomputeHighlightingAsync() {
        if (_hlEnabled && runHighlight(true)) {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        _recomputeHighlightingWorker();
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    private synchronized void _recomputeHighlightingWorker() {
        _textUnchangedWhileHighlighting.set(true);
        _hl.compute();
        post(new Runnable() {
            @Override
            public void run() {
                if (_textUnchangedWhileHighlighting.get()) {
                    batch(new Runnable() {
                        @Override
                        public void run() {
                            _hl.clearStatic(false)
                                    .clearDynamic()
                                    .setComputed()
                                    .addAdditional(_matches)
                                    .applyStatic()
                                    .applyDynamic(hlRegion());
                        }
                    });
                }
            }
        });
    }

    public void setHighlighter(final SyntaxHighlighterBase newHighlighter) {
        if (_hl != null) {
            _hl.clearDynamic().clearStatic(true);
        }

        _hl = newHighlighter;

        if (_hl != null) {
            initHighlighter();
            _hlDebounced = MtTextUtils.makeDebounced(getHandler(), _hl.getHighlightingDelay(), new Runnable() {
                @Override
                public void run() {
                    recomputeHighlightingAsync();
                }
            });
            recomputeHighlighting();
        } else {
            _hlDebounced = null;
        }
    }

    public void initHighlighter() {
        final Paint paint = getPaint();
        _hlShiftThreshold = Math.round(paint.getTextSize() * HIGHLIGHT_SHIFT_LINES);
        if (_hl != null) {
            _hl.setSpannable(getText()).configure(paint);
        }
    }

    public SyntaxHighlighterBase getHighlighter() {
        return _hl;
    }

    public boolean getHighlightingEnabled() {
        return _hlEnabled;
    }

    public boolean setHighlightingEnabled(final boolean enable) {
        final boolean prev = _hlEnabled;
        if (enable && !_hlEnabled) {
            _hlEnabled = true;
            initHighlighter();
            if (_hlDebounced != null) {
                _hlDebounced.run();
            }
        } else if (!enable && _hlEnabled) {
            _hlEnabled = false;
            if (_hl != null) {
                _hl.clearDynamic().clearStatic(true).clearComputed();
            }
        }
        return prev;
    }

    // Region to highlight
    private int[] hlRegion() {
        final int hlSize = Math.round(HIGHLIGHT_REGION_SIZE * _hlRect.height()) + _hlShiftThreshold;
        final int startY = _hlRect.centerY() - hlSize;
        final int endY = _hlRect.centerY() + hlSize;
        return new int[]{rowStart(startY), rowEnd(endY)};
    }

    private int rowStart(final int y) {
        final Layout layout = getLayout();
        return layout == null ? 0 : layout.getLineStart(layout.getLineForVertical(y));
    }

    private int rowEnd(final int y) {
        final Layout layout = getLayout();
        return layout == null ? 0 : layout.getLineEnd(layout.getLineForVertical(y));
    }

    // Additional highlight for search / replace etc
    // ---------------------------------------------------------------------------------------------

    // for highlight text search matches/occurrences
    private final List<SyntaxHighlighterBase.SpanGroup> _matches = new ArrayList<SyntaxHighlighterBase.SpanGroup>();

    public void setSearchMatches(List<SyntaxHighlighterBase.SpanGroup> spanGroups) {
        if (_hl != null) {
            _hl.clearAdditional(_matches);
        }
        _matches.clear();
        if (spanGroups != null) {
            _matches.addAll(spanGroups);
        }
        if (_hl != null) {
            _hl.addAdditional(_matches);
        }
    }

    public void removeSearchMatch(SyntaxHighlighterBase.SpanGroup spanGroup) {
        if (_hl != null) {
            _hl.clearDynamic().clearAdditional(spanGroup);
        }
        _matches.remove(spanGroup);
    }

    public void clearSearchMatches() {
        if (_hl != null) {
            _hl.clearDynamic().clearAdditional(_matches).applyDynamic(hlRegion());
        }
        _matches.clear();
    }

    public void applyDynamicHighlight() {
        if (_hl != null) {
            _hl.clearDynamic().applyDynamic(hlRegion());
        }
    }

    // Various overrides
    // ---------------------------------------------------------------------------------------------
    public void setSaveInstanceState(final boolean save) {
        _saveInstanceState = save;
    }

    @Override
    public boolean onPreDraw() {
        try {
            return super.onPreDraw();
        } catch (OutOfMemoryError ignored) {
            return false; // return false to cancel current drawing pass/round
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            super.onDraw(canvas);

            if (_staticCursorDrawer != null && hasFocus()) {
                _staticCursorDrawer.draw(canvas);
            }
        } catch (Exception e) {
            // Hinder drawing from crashing the app
            Log.e(getClass().getName(), "HighlightingEditor onDraw->super.onDraw crash" + e);
            Toast.makeText(getContext(), e.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        // Call is always required
        final Parcelable state = super.onSaveInstanceState();
        return _saveInstanceState ? state : null;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == View.VISIBLE) {
            recomputeHighlighting();
        }
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        initHighlighter();
        if (_hlDebounced != null) {
            _hlDebounced.run();
        }
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        if (_staticCursorDrawer != null) {
            _staticCursorDrawer.notifyTextSizeChanged();
        }
    }

    @Override
    public void setText(final CharSequence text, final BufferType type) {
        super.setText(text, type);
        initHighlighter();
        if (_hlDebounced != null) {
            _hlDebounced.run();
        }
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        // Copy-paste fix by bad rich-text pasting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && id == android.R.id.paste) {
            id = android.R.id.pasteAsPlainText;
        }
        try {
            // i.e. DeadSystemRuntimeException can happen here
            return super.onTextContextMenuItem(id);
        } catch (Exception ignored) {
            return true;
        }
    }

    // Accessibility code is blocked during rapid update events
    // such as highlighting and some actions.
    @Override
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        if (_accessibilityEnabled && length() < 10000) {
            super.sendAccessibilityEventUnchecked(event);
        }
    }

    @Override
    public boolean isSuggestionsEnabled() {
        return _isSpellingRedUnderline && super.isSuggestionsEnabled();
    }

    public void setSpellingRedUnderline(final boolean enabled) {
        _isSpellingRedUnderline = enabled;
    }

    @Override
    public void setSelection(int index) {
        if (indexesValid(index)) {
            super.setSelection(index);
        }
    }

    @Override
    public void setSelection(int start, int stop) {
        if (indexesValid(start, stop)) {
            super.setSelection(start, stop);
        } else if (indexesValid(start, stop - 1)) {
            super.setSelection(start, stop - 1);
        } else if (indexesValid(start + 1, stop)) {
            super.setSelection(start + 1, stop);
        }
    }

    public boolean indexesValid(int... indexes) {
        return MtTextUtils.inRange(0, length(), indexes);
    }

    /**
     * Get a number representing the current text changed state.
     * This number will increase by 1 every time the text is changed.
     */
    public int getTextChangedNumber() {
        return _textChangedNumber;
    }

    // Auto-format
    // ---------------------------------------------------------------------------------------------

    public void setAutoFormatters(final InputFilter inputFilter, final TextWatcher modifier) {
        final boolean state = getAutoFormatEnabled();
        setAutoFormatEnabled(false); // Remove any existing modifiers if applied
        _autoFormatFilter = inputFilter;
        _autoFormatModifier = modifier;
        setAutoFormatEnabled(state); // Restore state
    }

    public boolean getAutoFormatEnabled() {
        return _autoFormatEnabled;
    }

    public void setAutoFormatEnabled(final boolean enable) {
        if (enable && !_autoFormatEnabled) {
            MtTextUtils.addFilter(this, _autoFormatFilter);

            if (_autoFormatModifier != null) {
                addTextChangedListener(_autoFormatModifier);
            }

        } else if (!enable && _autoFormatEnabled) {
            MtTextUtils.removeFilter(this, _autoFormatFilter);

            if (_autoFormatModifier != null) {
                removeTextChangedListener(_autoFormatModifier);
            }
        }
        _autoFormatEnabled = enable;
    }

    // Run some code with auto formatters and accessibility disabled
    public void withAutoFormatDisabled(final Runnable callback) {
        final boolean enabled = getAutoFormatEnabled();
        try {
            _accessibilityEnabled = false;
            if (enabled) {
                setAutoFormatEnabled(false);
            }
            callback.run();
        } finally {
            setAutoFormatEnabled(enabled);
            _accessibilityEnabled = true;
        }
    }

    // Utility functions for interaction
    // ---------------------------------------------------------------------------------------------

    public void selectLines() {
        final int[] sel = MtTextUtils.getSelection(this);
        setSelection(sel[0], sel[1]);
    }

    public void simulateKeyPress(int keyEvent_KEYCODE_SOMETHING) {
        dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyEvent_KEYCODE_SOMETHING, 0));
        dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyEvent_KEYCODE_SOMETHING, 0));
    }

    public void insertOrReplaceTextOnCursor(final String newText) {
        final Editable edit = getText();
        if (edit != null && newText != null) {

            // Fill in any instances of selection
            final int[] sel = MtTextUtils.getSelection(this);
            final CharSequence selected = MtTextUtils.toString(edit, sel[0], sel[1]);
            String expanded = newText.replace(INSERT_SELECTION_HERE_TOKEN, selected);

            // Determine where to place the cursor
            final int newCursorPos = expanded.indexOf(PLACE_CURSOR_HERE_TOKEN);
            final String finalText = expanded.replace(PLACE_CURSOR_HERE_TOKEN, "");

            sel[0] = Math.max(sel[0], 0);

            // Needed to prevent selection of whole of inserted text after replace
            // if we want a cursor position instead
            if (newCursorPos >= 0) {
                setSelection(sel[0]);
            }

            withAutoFormatDisabled(new Runnable() {
                @Override
                public void run() {
                    edit.replace(sel[0], sel[1], finalText);
                }
            });

            if (newCursorPos >= 0) {
                setSelection(sel[0] + newCursorPos);
            }
        }
    }

    public int moveCursorToEndOfLine(int offset) {
        simulateKeyPress(KeyEvent.KEYCODE_MOVE_END);
        setSelection(getSelectionStart() + offset);
        return getSelectionStart();
    }

    public int moveCursorToBeginOfLine(int offset) {
        simulateKeyPress(KeyEvent.KEYCODE_MOVE_HOME);
        setSelection(getSelectionStart() + offset);
        return getSelectionStart();
    }

    // Static cursor (redraw cursor)
    // ---------------------------------------------------------------------------------------------

    /**
     * Static cursor drawer for EditText.
     */
    static class StaticCursorDrawer {

        private final Paint paint = new Paint();
        private final EditText editText;

        private float lineHeight;
        private float offsetY;
        private final float offsetYBase;
        private boolean paused;

        public StaticCursorDrawer(final EditText editText, final int cursorColor) {
            this.editText = editText;
            paint.setColor(cursorColor);
            offsetYBase = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, editText.getResources().getDisplayMetrics());
            notifyTextSizeChanged(); // Initialize textSize, lineHeight, offsetY and the cursor width
        }

        /**
         * Draw static cursor.
         */
        public void draw(final Canvas canvas) {
            if (paused) {
                return;
            }

            final Layout layout = editText.getLayout();
            if (layout == null) {
                return;
            }

            // Draw static cursor
            final int selectionStart = editText.getSelectionStart();
            final int line = layout.getLineForOffset(selectionStart);
            final float x = layout.getPrimaryHorizontal(selectionStart) + editText.getPaddingStart() + 1;
            final float y = layout.getLineBaseline(line) + offsetY;

            canvas.drawLine(x, y, x, y + lineHeight, paint);
        }

        /**
         * Call when the text size of the EditText has changed when the static cursor is enabled.
         */
        public void notifyTextSizeChanged() {
            float textSize = editText.getTextSize();
            lineHeight = editText.getLineHeight();
            offsetY = offsetYBase - textSize;

            // Set the stroke width (cursor width)
            final DisplayMetrics displayMetrics = editText.getResources().getDisplayMetrics();
            if (textSize < TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, displayMetrics)) {
                paint.setStrokeWidth(2);
            } else if (textSize < TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, displayMetrics)) {
                paint.setStrokeWidth(4);
            } else if (textSize < TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 25, displayMetrics)) {
                paint.setStrokeWidth(5);
            } else {
                paint.setStrokeWidth(6);
            }
        }

        /**
         * Call when the selection of the EditText has changed when the static cursor is enabled.
         */
        public void notifySelectionChanged(int selStart, int selEnd) {
            if (selStart == selEnd) {
                if (editText.isCursorVisible()) {
                    editText.setCursorVisible(false);
                }
                if (paused) {
                    paused = false;
                }
            } else if (!paused) {
                paused = true; // Pause drawing the cursor when selecting text
            }
        }
    }

    public void setStaticCursorEnabled(boolean staticCursorEnabled) {
        if (staticCursorEnabled) {
            if (_staticCursorDrawer == null) {
                _staticCursorDrawer = new StaticCursorDrawer(this, _cursorColor);
                setCursorVisible(false);
            }
        } else if (_staticCursorDrawer != null) {
            _staticCursorDrawer = null;
        }
    }

    public void setCursorColor(final int color) {
        _cursorColor = color;
        if (_staticCursorDrawer != null) {
            _staticCursorDrawer = new StaticCursorDrawer(this, _cursorColor);
        }
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);

        if (_staticCursorDrawer != null) {
            _staticCursorDrawer.notifySelectionChanged(selStart, selEnd);
        }
    }
}
