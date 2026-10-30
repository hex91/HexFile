package com.hex91.file;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.61.0 Smali 语法 / 操作码查询页：抽屉菜单入口。
 *
 * <p>三个标签页：语法（{@link SmaliDocs#E}）、操作码（{@link SmaliDocs#OP}，0x00~0xFF 全表）、
 * ★ 收藏（两类混合，SharedPreferences 持久化）。顶部搜索框实时过滤：
 * 语法类匹配「分类+语法+解析」，操作码类匹配「操作码+助记符+说明」。</p>
 *
 * <p>条目卡片三层：红色等宽语法 → {@code "#"} 注释形式的中文解析 → 等宽示例代码块。
 * 卡片右上角 ★ 切换收藏；点击卡片主体复制语法到剪贴板。</p>
 *
 * <p>颜色全部走主题属性（22 套色板 + 明暗模式），语法红按背景亮度自适应深浅。</p>
 */
public class SmaliDocActivity extends BaseActivity {

    private static final String PREFS = "smalidoc";
    private static final String KEY_FAVS = "favs";
    /** 收藏键前缀：E=语法条目，O=操作码。 */
    private static final String PK_E = "E:";
    private static final String PK_O = "O:";

    /** 0=语法 1=操作码 2=收藏 */
    private int mMode;

    private LinearLayout mContainer;
    private EditText mSearch;
    private TextView mCount;
    private TextView[] mTabViews = new TextView[3];

    /** 收藏键集合（有序，保持加入顺序）。 */
    private final LinkedHashSet<String> mFavs = new LinkedHashSet<>();

    private int mRed, mSub, mCardBg, mCodeBg, mText, mDivider, mAccent;
    private Typeface mMono;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRed = isDarkTheme() ? Color.parseColor("#FF8A80") : Color.parseColor("#D32F2F");
        mSub = attr(R.attr.hfTextSub, Color.GRAY);
        mCardBg = attr(R.attr.hfSurface, Color.WHITE);
        mCodeBg = attr(R.attr.hfSurfaceAlt, 0xFFF0F0F0);
        mText = attr(R.attr.hfText, Color.BLACK);
        mAccent = attr(R.attr.hfAccent, Color.BLUE);
        mDivider = attr(R.attr.hfDivider, 0xFFDDDDDD);
        try { mMono = Typeface.MONOSPACE; } catch (Exception ignore) {}

        loadFavs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(attr(R.attr.hfBg, Color.WHITE));
        setContentView(root);
        root.addView(header());
        root.addView(tabs());
        root.addView(searchBar());
        root.addView(divider(1));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        mContainer = new LinearLayout(this);
        mContainer.setOrientation(LinearLayout.VERTICAL);
        mContainer.setPadding(dp(12), dp(4), dp(12), dp(24));
        sv.addView(mContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        render(null);
        refreshTabs();
    }

    // ---------- 头部 / 标签 / 搜索 ----------

    /** 头部：返回 + 标题 + 条目计数。 */
    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(attr(R.attr.hfTopBar, mCardBg));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setTextColor(mText);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(16), 0, dp(16), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Smali 语法查询");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(mText);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mCount = new TextView(this);
        mCount.setTextSize(12);
        mCount.setTextColor(mSub);
        mCount.setPadding(0, 0, dp(14), 0);
        bar.addView(mCount);
        return bar;
    }

    /** 三标签：语法 / 操作码 / ★ 收藏。 */
    private View tabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(mCardBg);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));

        final String[] names = {"语法", "操作码", "★ 收藏"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(names[i]);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(12), dp(7), dp(12), dp(7));
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mMode = idx;
                    refreshTabs();
                    render(mSearch.getText().toString());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            row.addView(tv, lp);
            mTabViews[i] = tv;
        }
        return row;
    }

    /** 刷新标签外观：选中态反色 + 收藏数。 */
    private void refreshTabs() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == mMode;
            mTabViews[i].setBackgroundColor(on ? mAccent : Color.TRANSPARENT);
            mTabViews[i].setTextColor(on ? Color.WHITE : mSub);
            mTabViews[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            if (i == 2) mTabViews[i].setText("★ 收藏 (" + mFavs.size() + ")");
            else if (i == 0) mTabViews[i].setText("语法 (" + SmaliDocs.E.length + ")");
            else mTabViews[i].setText("操作码 (" + SmaliDocs.OP.length + ")");
        }
    }

    /** 顶部搜索框（实时过滤）。 */
    private View searchBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(12), dp(8), dp(12), dp(8));

        mSearch = new EditText(this);
        mSearch.setHint("搜索指令 / 操作码 / 关键字，如 move、0x90、invoke、跳转…");
        mSearch.setTextSize(14);
        mSearch.setTextColor(mText);
        mSearch.setHintTextColor(mSub);
        mSearch.setSingleLine(true);
        mSearch.setBackgroundColor(mCardBg);
        mSearch.setPadding(dp(10), dp(8), dp(10), dp(8));
        wrap.addView(mSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { render(s.toString()); }
        });
        return wrap;
    }

    // ---------- 渲染 ----------

    /** 按当前标签 + 查询渲染列表。 */
    private void render(String query) {
        mContainer.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int hits = 0;

        if (mMode == 2) {
            // 收藏：两类混合，按加入顺序
            String lastCat = null;
            for (String key : mFavs) {
                if (key.startsWith(PK_E)) {
                    String[] e = findE(key.substring(PK_E.length()));
                    if (e == null || (q != null && !q.isEmpty() && !match(e, q))) continue;
                    mContainer.addView(sectionHeader("语法 · " + e[0]));
                    mContainer.addView(entryCard(e, false));
                } else {
                    String[] o = findOp(key.substring(PK_O.length()));
                    if (o == null || (q != null && !q.isEmpty() && !matchOp(o, q))) continue;
                    mContainer.addView(sectionHeader("操作码"));
                    mContainer.addView(opCard(o));
                }
                mContainer.addView(spacer(dp(8)));
                hits++;
                lastCat = null;
            }
            if (hits == 0) {
                mContainer.addView(emptyView(mFavs.isEmpty()
                        ? "还没有收藏。在任意条目卡片右上角点 ☆ 即可收藏，重启 App 仍保留。"
                        : "当前关键词没有命中收藏条目。"));
            }
            mCount.setText(hits + " / 收藏 " + mFavs.size());
            return;
        }

        if (mMode == 1) {
            // 操作码全表
            for (String[] o : SmaliDocs.OP) {
                if (!q.isEmpty() && !matchOp(o, q)) continue;
                mContainer.addView(opCard(o));
                mContainer.addView(spacer(dp(6)));
                hits++;
            }
            if (hits == 0) mContainer.addView(emptyView("没有匹配的操作码，试试 0x90、invoke、const…"));
            mCount.setText(q.isEmpty() ? SmaliDocs.OP.length + " 条" : hits + "/" + SmaliDocs.OP.length);
            return;
        }

        // 语法条目（按分类分节）
        String lastCat = null;
        for (String[] e : SmaliDocs.E) {
            if (!q.isEmpty() && !match(e, q)) continue;
            if (!e[0].equals(lastCat)) {
                lastCat = e[0];
                mContainer.addView(sectionHeader(e[0]));
            }
            mContainer.addView(entryCard(e, false));
            mContainer.addView(spacer(dp(8)));
            hits++;
        }
        if (hits == 0) mContainer.addView(emptyView("没有匹配的条目，换个关键词试试（如 add-int、invoke-static、跳转、字段）"));
        mCount.setText(q.isEmpty() ? SmaliDocs.E.length + " 条" : hits + "/" + SmaliDocs.E.length);
    }

    private static boolean match(String[] e, String q) {
        return e[1].toLowerCase().contains(q)
                || e[2].toLowerCase().contains(q)
                || e[0].toLowerCase().contains(q);
    }

    private static boolean matchOp(String[] o, String q) {
        return o[0].toLowerCase().contains(q)
                || o[1].toLowerCase().contains(q)
                || o[2].toLowerCase().contains(q);
    }

    private View sectionHeader(String cat) {
        TextView tv = new TextView(this);
        tv.setText("◆ " + cat);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(mAccent);
        tv.setPadding(dp(2), dp(14), dp(2), dp(6));
        return tv;
    }

    private View emptyView(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(14);
        tv.setTextColor(mSub);
        tv.setPadding(dp(8), dp(40), dp(8), 0);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    /** 语法条目卡片：语法（红）+ ★ + 注释解析 + 示例。 */
    private View entryCard(final String[] e, boolean compact) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(mCardBg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copy(e[1]); }
        });

        // 标题行：语法（红，左） + 收藏星（右）
        final String key = PK_E + e[1];
        card.addView(syntaxRow(e[1], key, mFavs.contains(key)));

        card.addView(spacer(dp(4)));

        TextView desc = new TextView(this);
        desc.setText(commentize(e[2]));
        desc.setTypeface(mMono);
        desc.setTextSize(12);
        desc.setTextColor(mSub);
        desc.setLineSpacing(dp(2), 1f);
        desc.setTextIsSelectable(true);
        card.addView(desc);

        if (e[3] != null && !e[3].isEmpty()) {
            card.addView(spacer(dp(6)));
            card.addView(codeBlock(e[3]));
        }
        return card;
    }

    /** 操作码卡片：操作码徽标 + 助记符（红）+ ★ + 中文说明。 */
    private View opCard(final String[] o) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(mCardBg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copy(o[1]); }
        });

        final String key = PK_O + o[0];
        boolean fav = mFavs.contains(key);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        // 操作码徽标
        TextView badge = new TextView(this);
        badge.setText(o[0]);
        badge.setTypeface(mMono, Typeface.BOLD);
        badge.setTextSize(12);
        badge.setTextColor(Color.WHITE);
        badge.setBackgroundColor(mRed);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        row1.addView(badge);

        TextView syn = new TextView(this);
        syn.setText(" " + o[1]);
        syn.setTypeface(mMono, Typeface.BOLD);
        syn.setTextSize(14);
        syn.setTextColor(o[1].contains("unused") ? mSub : mRed);
        syn.setTextIsSelectable(true);
        row1.addView(syn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row1.addView(star(key, fav));
        card.addView(row1);

        card.addView(spacer(dp(4)));
        TextView desc = new TextView(this);
        desc.setText(commentize(o[2]));
        desc.setTypeface(mMono);
        desc.setTextSize(12);
        desc.setTextColor(mSub);
        desc.setTextIsSelectable(true);
        card.addView(desc);
        return card;
    }

    /** 语法行的标题行（语法 + ★）。 */
    private View syntaxRow(final String syntax, final String key, boolean fav) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView syn = new TextView(this);
        syn.setText(syntax);
        syn.setTypeface(mMono, Typeface.BOLD);
        syn.setTextSize(14);
        syn.setTextColor(mRed);
        syn.setTextIsSelectable(true);
        row.addView(syn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(star(key, fav));
        return row;
    }

    /** 收藏星：点击切换并持久化。 */
    private View star(final String key, boolean fav) {
        TextView s = new TextView(this);
        s.setText(fav ? "★" : "☆");
        s.setTextSize(18);
        s.setTextColor(fav ? mAccent : mSub);
        s.setPadding(dp(10), dp(2), dp(2), dp(6));
        s.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now;
                if (mFavs.contains(key)) { mFavs.remove(key); now = false; }
                else { mFavs.add(key); now = true; }
                saveFavs();
                ((TextView) v).setText(now ? "★" : "☆");
                ((TextView) v).setTextColor(now ? mAccent : mSub);
                Toast.makeText(SmaliDocActivity.this,
                        now ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                refreshTabs();
                if (mMode == 2) render(mSearch.getText().toString());
            }
        });
        return s;
    }

    /** 示例代码块：等宽 + 次级背景。 */
    private View codeBlock(String code) {
        TextView ex = new TextView(this);
        ex.setText(code);
        ex.setTypeface(mMono);
        ex.setTextSize(12);
        ex.setTextColor(mText);
        ex.setBackgroundColor(mCodeBg);
        ex.setPadding(dp(10), dp(8), dp(10), dp(8));
        ex.setTextIsSelectable(true);
        return ex;
    }

    /** 解析文本逐行以 "# " 注释呈现（行首已有 # 的不再重复；空行显示裸 #）。 */
    private static String commentize(String desc) {
        String[] lines = desc.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            sb.append(line.isEmpty() ? "#" : line.startsWith("#") ? line : "# " + line);
        }
        return sb.toString();
    }

    // ---------- 收藏持久化 ----------

    private void loadFavs() {
        mFavs.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString(KEY_FAVS, "");
        if (raw != null && !raw.isEmpty()) {
            for (String k : raw.split("\u0001")) if (!k.isEmpty()) mFavs.add(k);
        }
    }

    private void saveFavs() {
        StringBuilder sb = new StringBuilder();
        for (String k : mFavs) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(k);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_FAVS, sb.toString()).apply();
    }

    /** 按语法文本定位语法条目。 */
    private static String[] findE(String syntax) {
        for (String[] e : SmaliDocs.E) if (e[1].equals(syntax)) return e;
        return null;
    }

    /** 按操作码定位（如 0x90）。 */
    private static String[] findOp(String op) {
        for (String[] o : SmaliDocs.OP) if (o[0].equals(op)) return o;
        return null;
    }

    /** 复制语法到剪贴板。 */
    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("smali", text));
            Toast.makeText(this, "已复制：" + text, Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {}
    }

    // ---------- 小工具 ----------

    private View divider(int h) {
        View v = new View(this);
        v.setBackgroundColor(mDivider);
        v.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, h));
        return v;
    }

    /** 解析主题属性色；失败用 fallback（minSdk 21，避免 API 23 的 getColor(int)）。 */
    private int attr(int attrId, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attrId, tv, true)) {
            try {
                if (tv.resourceId != 0) return getResources().getColor(tv.resourceId);
                return tv.data;
            } catch (Exception ignore) { return tv.data; }
        }
        return fallback;
    }

    /** hfBg 亮度判断（<0.5 视为暗色主题）。 */
    private boolean isDarkTheme() {
        int bg = attr(R.attr.hfBg, Color.WHITE);
        double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
        return lum < 0.5;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
