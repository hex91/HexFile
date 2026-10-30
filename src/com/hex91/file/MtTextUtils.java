package com.hex91.file;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Selection;
import android.text.TextUtils;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文本工具集合（HexFile 自研 + Markor 移植）。
 *
 * 来源：
 *  - 行号/换行符语义工具（isBreakChar/countBreaks/logicalLineOf/offsetOfLogicalLine/splitLines）
 *    为 HexFile 自研（v1.27，统一 '\n' / '\r' / "\r\n" 语义）；
 *  - getLineStart/getLineEnd/getFirstNonWhitespace/getSelection/getLineSelection/toString/
 *    makeDebounced/addFilter/removeFilter/findDiff/ChunkedEditable/isNewLine/isValidSelection/
 *    inRange/tryParseInt 移植自 Markor（gsantner/markor，Apache 2.0）的 TextViewUtils 与 GsTextUtils。
 */
public final class MtTextUtils {

    private MtTextUtils() {
    }

    // ============================================================ 换行符语义（自研，v1.27）

    /** 判断字符是否为换行符（与 Android Layout 行边界语义一致）。'\r' 单独出现也是硬换行；"\r\n" 是一对只算一次。 */
    public static boolean isBreakChar(char c) {
        return c == '\n' || c == '\r';
    }

    /** 统计 text[from, to) 区间内的换行符数量（"\r\n" 计 1 次）。 */
    public static int countBreaks(CharSequence text, int from, int to) {
        int n = 0;
        for (int j = from; j < to; j++) {
            char c = text.charAt(j);
            if (c == '\n') {
                n++;
            } else if (c == '\r') {
                n++;
                if (j + 1 < to && text.charAt(j + 1) == '\n') j++; // CRLF 计 1 次
            }
        }
        return n;
    }

    /** offset 处所在的逻辑行号（1-based）。 */
    public static int logicalLineOf(CharSequence text, int offset) {
        int off = Math.max(0, Math.min(offset, text.length()));
        return countBreaks(text, 0, off) + 1;
    }

    /** 第 lineNo 个逻辑行（1-based）的起始字符偏移，用于"跳转到行"；超出总行数返回文本末尾。 */
    public static int offsetOfLogicalLine(CharSequence text, int lineNo) {
        if (lineNo <= 1) return 0;
        int len = text.length();
        int cur = 0, seen = 1;
        while (cur < len && seen < lineNo) {
            char c = text.charAt(cur);
            if (c == '\n') { seen++; cur++; }
            else if (c == '\r') {
                seen++;
                if (cur + 1 < len && text.charAt(cur + 1) == '\n') cur += 2; else cur++;
            } else cur++;
        }
        return Math.min(cur, len);
    }

    /** 按统一换行符语义切行（'\n' / '\r' / "\r\n"），对 LF 文本与 String.split("\n", -1) 结果一致（保留尾部空行）。 */
    public static String[] splitLines(String text) {
        List<String> out = new ArrayList<String>();
        int start = 0, len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(text.substring(start, i));
                start = i + 1;
            } else if (c == '\r') {
                out.add(text.substring(start, i));
                if (i + 1 < len && text.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
        }
        if (start < len) out.add(text.substring(start));
        else if (len == 0 || isBreakChar(text.charAt(len - 1))) out.add("");
        return out.toArray(new String[0]);
    }

    /**
     * 视觉行 -> 逻辑行号映射。lineStarts[i] = layout.getLineStart(i)。
     * 折行续行与首行同号（调用方据此判断是否画行号）。
     */
    public static int[] buildVisualMap(CharSequence text, int[] lineStarts) {
        int n = lineStarts.length;
        int[] map = new int[n];
        int logical = 1;
        int prev = 0;
        for (int i = 0; i < n; i++) {
            logical += countBreaks(text, prev, lineStarts[i]);
            prev = lineStarts[i];
            map[i] = logical;
        }
        return map;
    }

    // ============================================================ Markor 移植（Apache 2.0）

    public static int getLineStart(final CharSequence s, final int sel) {
        int i = sel;
        if (isValidSelection(s, i)) {
            for (; i > 0; i--) {
                if (s.charAt(i - 1) == '\n') break;
            }
        }
        return i;
    }

    public static int getLineEnd(final CharSequence s, final int sel) {
        int i = sel;
        if (isValidSelection(s, i)) {
            for (; i < s.length(); i++) {
                if (s.charAt(i) == '\n') break;
            }
        }
        return i;
    }

    public static int getNextNonWhitespace(final CharSequence s, final int start) {
        if (s != null && start >= 0) {
            final int length = s.length();
            for (int i = start; i < length; i++) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t') return i;
            }
        }
        return -1;
    }

    public static int getFirstNonWhitespace(final CharSequence s) {
        return getNextNonWhitespace(s, 0);
    }

    public static int[] getSelection(final CharSequence text) {
        final int[] sel = new int[]{Selection.getSelectionStart(text), Selection.getSelectionEnd(text)};
        if (sel[0] >= 0 && sel[1] >= 0) return sel;
        return new int[]{0, 0};
    }

    public static int[] getSelection(final TextView text) {
        final int[] sel = new int[]{text.getSelectionStart(), text.getSelectionEnd()};
        if (sel[0] >= 0 && sel[1] >= 0) return sel;
        return new int[]{0, 0};
    }

    public static String toString(final CharSequence source, int start, int end) {
        if (source instanceof String) {
            return ((String) source).substring(start, end);
        }
        final char[] buf = new char[end - start];
        TextUtils.getChars(source, start, end, buf, 0);
        return new String(buf);
    }

    public static Runnable makeDebounced(final Handler handler, final long delayMs, final Runnable callback) {
        return new Runnable() {
            private Runnable runnable;

            @Override
            public void run() {
                // handler 可能为 null：View 未 attach 到窗口时 getHandler() 返回 null
                // （字段初始化阶段必然如此）。惰性兜底到主 Looper，保证任何时机都不会 NPE。
                final Handler h = (handler != null) ? handler : new Handler(Looper.getMainLooper());
                if (runnable != null) {
                    h.removeCallbacks(runnable);
                }
                runnable = new Runnable() {
                    @Override
                    public void run() {
                        callback.run();
                    }
                };
                h.postDelayed(runnable, delayMs);
            }
        };
    }

    public static boolean addFilter(final TextView view, final InputFilter filter) {
        if (view == null || filter == null) return false;
        final List<InputFilter> filters = Arrays.asList(view.getFilters());
        if (filters.contains(filter)) return false;
        final List<InputFilter> filterList = new ArrayList<>(filters);
        filterList.add(filter);
        view.setFilters(filterList.toArray(new InputFilter[0]));
        return true;
    }

    public static boolean removeFilter(final TextView view, final InputFilter filter) {
        if (view == null || filter == null) return false;
        final List<InputFilter> filters = Arrays.asList(view.getFilters());
        if (!filters.contains(filter)) return false;
        final List<InputFilter> filterList = new ArrayList<>(filters);
        filterList.remove(filter);
        view.setFilters(filterList.toArray(new InputFilter[0]));
        return true;
    }

    public static boolean isNewLine(CharSequence source, int start, int end) {
        return isValidIndex(source, start, end - 1) && (source.charAt(start) == '\n' || source.charAt(end - 1) == '\n');
    }

    public static boolean isValidIndex(final CharSequence s, final int... indices) {
        return s != null && indices != null && inRange(0, s.length() - 1, indices);
    }

    public static boolean isValidSelection(final CharSequence s, final int... indices) {
        return s != null && indices != null && inRange(0, s.length(), indices);
    }

    public static boolean inRange(final int min, final int max, final int... values) {
        for (final int i : values) {
            if (i < min || i > max) return false;
        }
        return true;
    }

    public static int tryParseInt(final String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NullPointerException | NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int countChar(final CharSequence s, final int start, final int end, final char c) {
        int count = 0;
        for (int i = Math.max(start, 0); i < Math.min(end, s.length()); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    public static int[] countChars(final CharSequence s, final int start, final int end, final char... chars) {
        final int[] counts = new int[chars.length];
        for (int i = Math.max(start, 0); i < Math.min(end, s.length()); i++) {
            final char c = s.charAt(i);
            for (int j = 0; j < chars.length; j++) {
                if (c == chars[j]) counts[j]++;
            }
        }
        return counts;
    }

    /**
     * 找 source -> dest 的最小 diff。
     *
     * @return { a, b, c } s.t. setting dest[a:b] = source[a:c] makes dest == source
     */
    public static int[] findDiff(final CharSequence dest, final CharSequence source, final int startSkip, final int endSkip) {
        final int[] diff = findDiff(dest, startSkip, dest.length() - endSkip, source, startSkip, source.length() - endSkip);
        return new int[]{diff[0], diff[1], diff[3]};
    }

    /**
     * @return { a, b, c, d } s.t. setting dest[a:b] = source[c:d] will make dest[ds:dn] == source[ss:sn]
     */
    public static int[] findDiff(final CharSequence dest, final int ds, final int dn, final CharSequence source, final int ss, final int sn) {
        final int dl = Math.max(dn - ds, 0), sl = Math.max(sn - ss, 0);
        final int minLength = Math.min(dl, sl);

        int start = 0;
        while (start < minLength && source.charAt(start + ss) == dest.charAt(start + ds)) start++;

        if (sl == dl && start == sl) {
            return new int[]{dn, dn, sn, sn};
        } else if (sl < dl && start == sl) {
            return new int[]{ds + start, dn, sn, sn};
        } else if (dl < sl && start == dl) {
            return new int[]{dn, dn, start + ss, sn};
        }

        int end = 0;
        final int maxEnd = minLength - start;
        while (end < maxEnd && source.charAt(sn - end - 1) == dest.charAt(dn - end - 1)) end++;

        return new int[]{ds + start, dn - end, ss + start, sn - end};
    }

    /**
     * 允许对 Editable 分批操作：先在 copy 上批量改，applyChanges() 时做最小 diff 一次性应用。
     * 移植自 Markor TextViewUtils.ChunkedEditable（Apache 2.0）。
     */
    public static class ChunkedEditable implements Editable {

        private final Editable original;
        private StringBuilder copy;
        private int _startSkip = 0;
        private int _endSkip = 0;
        private int _selStart = -1, _selEnd = -1;
        private boolean _selChanged = false;
        private int _depth = 0;

        public static ChunkedEditable wrap(final Editable e) {
            final ChunkedEditable ret;
            if (e instanceof ChunkedEditable) {
                ret = (ChunkedEditable) e;
                ret._depth++;
            } else {
                ret = new ChunkedEditable(e);
            }
            return ret;
        }

        private ChunkedEditable(final Editable e) {
            original = e;
            _selStart = Selection.getSelectionStart(original);
            _selEnd = Selection.getSelectionEnd(original);
        }

        public void applyChanges() {
            if (_depth > 0) {
                _depth--;
                return;
            }
            if (copy == null) return;
            final int[] diff = MtTextUtils.findDiff(original, copy, _startSkip, Math.max(_endSkip, 0));
            final boolean hasDiff = diff[0] != diff[1] || diff[0] != diff[2];
            if (hasDiff) {
                original.replace(diff[0], diff[1], copy.subSequence(diff[0], diff[2]));
            }
            if (_selChanged) {
                Selection.setSelection(original, _selStart, _selEnd);
            }
            copy = null;
        }

        @Override
        public Editable replace(int st, int en, CharSequence source, int start, int end) {
            final int[] diff = findDiff((copy != null ? copy : original), st, en, source, start, end);
            if (diff[0] != diff[1] || diff[2] != diff[3]) {
                if (copy == null) {
                    copy = new StringBuilder(original);
                    _startSkip = _endSkip = copy.length();
                }
                _startSkip = Math.min(_startSkip, diff[0]);
                _endSkip = Math.min(_endSkip, copy.length() - diff[1] - 1);
                copy.replace(diff[0], diff[1], MtTextUtils.toString(source, diff[2], diff[3]));
            }
            return this;
        }

        @Override
        public Editable replace(int st, int en, CharSequence text) {
            return replace(st, en, text, 0, text.length());
        }

        @Override
        public Editable insert(int where, CharSequence text, int start, int end) {
            return replace(where, where, text, start, end);
        }

        @Override
        public Editable insert(int where, CharSequence text) {
            return replace(where, where, text, 0, text.length());
        }

        @Override
        public Editable delete(int st, int en) {
            return replace(st, en, "", 0, 0);
        }

        @Override
        public Editable append(CharSequence text) {
            return replace(length(), length(), text, 0, text.length());
        }

        @Override
        public android.text.InputFilter[] getFilters() {
            return original.getFilters();
        }

        @Override
        public void setFilters(android.text.InputFilter[] filters) {
            original.setFilters(filters);
        }

        @Override
        public Editable append(char text) {
            return append(String.valueOf(text));
        }

        @Override
        public Editable append(CharSequence text, int start, int end) {
            return replace(length(), length(), text, start, end);
        }

        @Override
        public void clear() {
            original.clear();
        }

        @Override
        public void clearSpans() {
            original.clearSpans();
        }

        @Override
        public void setSpan(Object what, int start, int end, int flags) {
            original.setSpan(what, start, end, flags);
        }

        @Override
        public void removeSpan(Object what) {
            original.removeSpan(what);
        }

        @Override
        public <T> T[] getSpans(int start, int end, Class<T> type) {
            return original.getSpans(start, end, type);
        }

        @Override
        public int getSpanStart(Object tag) {
            return original.getSpanStart(tag);
        }

        @Override
        public int getSpanEnd(Object tag) {
            return original.getSpanEnd(tag);
        }

        @Override
        public int getSpanFlags(Object tag) {
            return original.getSpanFlags(tag);
        }

        @Override
        public int nextSpanTransition(int start, int limit, Class type) {
            return original.nextSpanTransition(start, limit, type);
        }

        @Override
        public int length() {
            return original.length();
        }

        @Override
        public char charAt(int index) {
            return original.charAt(index);
        }

        @Override
        public void getChars(int start, int end, char[] dest, int destoff) {
            original.getChars(start, end, dest, destoff);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return original.subSequence(start, end);
        }

        @Override
        public String toString() {
            return original.toString();
        }
    }
}
