package com.hex91.file;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v1.61.0 Smali 语法查询页：抽屉菜单入口。
 *
 * <p>顶部搜索框实时过滤（语法 + 中文解析 + 分类均参与匹配）；正文按分类分节，
 * 每条目一张卡片：红色等宽语法 → {@code "#"} 注释形式的中文解析 → 等宽示例代码块。
 * 点击卡片复制语法到剪贴板。数据源 {@link SmaliDocs#E}，追加条目即自动收录。</p>
 *
 * <p>红色适配明暗主题：解析 hfBg 亮度，暗色用亮红、浅色用深红。</p>
 */
public class SmaliDocActivity extends BaseActivity {

    private LinearLayout mContainer;
    private EditText mSearch;
    private TextView mCount;

    /** 语法红色（onCreate 按主题亮度确定）。 */
    private int mRed;
    /** 解析注释色（hfTextSub）。 */
    private int mSub;
    /** 卡片背景（hfSurface）。 */
    private int mCardBg;
    /** 示例代码块背景（hfSurfaceAlt）。 */
    private int mCodeBg;
    /** 主文字色（hfText）。 */
    private int mText;
    /** 分隔线色（hfDivider）。 */
    private int mDivider;

    /** 等宽字体（Typeface.createFromFile 可能失败，回落 MONOSPACE）。 */
    private Typeface mMono;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ---- 主题色解析 ----
        mRed = isDarkTheme() ? Color.parseColor("#FF8A80") : Color.parseColor("#D32F2F");
        mSub = attr(R.attr.hfTextSub, Color.GRAY);
        mCardBg = attr(R.attr.hfSurface, Color.WHITE);
        mCodeBg = attr(R.attr.hfSurfaceAlt, 0xFFF0F0F0);
        mText = attr(R.attr.hfText, Color.BLACK);
        mDivider = attr(R.attr.hfDivider, 0xFFDDDDDD);
        try { mMono = Typeface.MONOSPACE; } catch (Exception ignore) {}

        // ---- 整体框架：竖排 = 头部 / 搜索框 / 滚动内容 ----
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(attr(R.attr.hfBg, Color.WHITE));
        setContentView(root);
        root.addView(header());
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
    }

    /** 头部：返回 + 标题 + 条目计数。 */
    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(attr(R.attr.hfTopBar, mCardBg));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setTextColor(mText);
        back.setGravity(android.view.Gravity.CENTER);
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
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tp);

        mCount = new TextView(this);
        mCount.setTextSize(12);
        mCount.setTextColor(mSub);
        mCount.setPadding(0, 0, dp(14), 0);
        mCount.setText(SmaliDocs.E.length + " 条");
        bar.addView(mCount);
        return bar;
    }

    /** 顶部搜索框（实时过滤）。 */
    private View searchBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(12), dp(8), dp(12), dp(8));

        mSearch = new EditText(this);
        mSearch.setHint("搜索指令 / 关键字，如 move、invoke、字段、跳转…");
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

    /**
     * 渲染条目。query 为空显示全部（按分类分节）；
     * 非空时只显示匹配条目（语法 / 解析 / 分类任一包含即命中，忽略大小写）。
     */
    private void render(String query) {
        mContainer.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int hits = 0;
        String lastCat = null;
        for (String[] e : SmaliDocs.E) {
            if (!q.isEmpty() && !match(e, q)) continue;
            if (!e[0].equals(lastCat)) {
                lastCat = e[0];
                mContainer.addView(sectionHeader(e[0]));
            }
            mContainer.addView(entryCard(e));
            mContainer.addView(spacer(dp(8)));
            hits++;
        }
        if (hits == 0) {
            TextView empty = new TextView(this);
            empty.setText("没有匹配的条目，换个关键词试试（如 add-int、invoke-static、跳转、字段）");
            empty.setTextSize(14);
            empty.setTextColor(mSub);
            empty.setPadding(dp(8), dp(40), dp(8), 0);
            empty.setGravity(android.view.Gravity.CENTER);
            mContainer.addView(empty);
        }
        mCount.setText(q.isEmpty() ? SmaliDocs.E.length + " 条" : hits + "/" + SmaliDocs.E.length);
    }

    /** 过滤匹配：语法 / 中文解析 / 分类。 */
    private static boolean match(String[] e, String qLower) {
        return e[1].toLowerCase().contains(qLower)
                || e[2].toLowerCase().contains(qLower)
                || e[0].toLowerCase().contains(qLower);
    }

    /** 分类节头。 */
    private View sectionHeader(String cat) {
        TextView tv = new TextView(this);
        tv.setText("◆ " + cat);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(attr(R.attr.hfAccent, mRed));
        tv.setPadding(dp(2), dp(14), dp(2), dp(6));
        return tv;
    }

    /** 条目卡片：语法（红） + 注释解析 + 示例代码块。点击复制语法。 */
    private View entryCard(final String[] e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(mCardBg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copy(e[1]); }
        });

        // 1) 语法：红色等宽
        TextView syn = new TextView(this);
        syn.setText(e[1]);
        syn.setTypeface(mMono, Typeface.BOLD);
        syn.setTextSize(14);
        syn.setTextColor(mRed);
        syn.setTextIsSelectable(true);
        card.addView(syn);

        card.addView(spacer(dp(4)));

        // 2) 中文解析：以 "# " 注释形式存在，灰色
        TextView desc = new TextView(this);
        desc.setText(commentize(e[2]));
        desc.setTypeface(mMono);
        desc.setTextSize(12);
        desc.setTextColor(mSub);
        desc.setLineSpacing(dp(2), 1f);
        desc.setTextIsSelectable(true);
        card.addView(desc);

        // 3) 示例代码块：等宽 + 次级背景
        if (e[3] != null && !e[3].isEmpty()) {
            card.addView(spacer(dp(6)));
            TextView ex = new TextView(this);
            ex.setText(e[3]);
            ex.setTypeface(mMono);
            ex.setTextSize(12);
            ex.setTextColor(mText);
            ex.setBackgroundColor(mCodeBg);
            ex.setPadding(dp(10), dp(8), dp(10), dp(8));
            ex.setTextIsSelectable(true);
            card.addView(ex);
        }
        return card;
    }

    /** 解析文本逐行以 "# " 注释呈现（行首已有 # 的不再重复；空行只显示裸 #）。 */
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
