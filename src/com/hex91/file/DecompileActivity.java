package com.hex91.file;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Java 源码查看器（v1.63.0）：把「Dex 编辑器++」里的单个 smali 类还原成 Java 源码。
 *
 * <p>流水线（全离线、后台线程）：</p>
 * <ol>
 *   <li>用 smali 汇编器把<b>当前 smali</b>（含你在编辑器里的改动）编成临时 dex；</li>
 *   <li>dex2jar 把该 dex 转成 .class；</li>
 *   <li>必要时先用 dex2jar 把整个工程 dex 转成 jar（<b>缓存一次</b>），作为 CFR 的类型库，
 *       这样跨类调用能解析出真实签名而不是 Unknown；</li>
 *   <li>CFR 反编译 → Java 源码 → 语法高亮展示。</li>
 * </ol>
 *
 * <p>另提供 Smali 对照（同一份源码的 smali 原样，带高亮），点顶部标签切换。</p>
 */
public class DecompileActivity extends BaseActivity {

    public static final String EXTRA_SMALI = "smali"; // smali 文件绝对路径
    public static final String EXTRA_CLS = "cls";     // 类名，如 com/foo/Bar
    public static final String EXTRA_DEX = "dex";     // 工程 dex（用于生成类型库），可为空

    private static final Pattern WHILE_TRUE = Pattern.compile("while\\s*\\(\\s*true\\s*\\)");
    /** switch 的选择子变量名：switch (n3) → n3 */
    private static final Pattern SWITCH_VAR = Pattern.compile("switch\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*\\)");

    private String smaliPath;
    private String cls;
    private String dexPath;

    private TextView mCode;
    private TextView mStatus;
    private TextView mTabJava;
    private TextView mTabSmali;
    private ProgressDialog mPd;

    /** 代码字号（sp），双指缩放调整，持久化 */
    private float mTextSize = 13f;
    private ScaleGestureDetector mScale;

    private String javaSrc;
    private String smaliSrc;
    private boolean showJava = true;

    private int mText, mSub, mCardBg, mCodeBg, mBg, mAccent;
    private Typeface mMono = Typeface.MONOSPACE;
    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        smaliPath = getIntent().getStringExtra(EXTRA_SMALI);
        cls = getIntent().getStringExtra(EXTRA_CLS);
        dexPath = getIntent().getStringExtra(EXTRA_DEX);
        if (smaliPath == null || cls == null) {
            finish();
            return;
        }

        mText = attr(R.attr.hfText, Color.BLACK);
        mSub = attr(R.attr.hfTextSub, Color.GRAY);
        mCardBg = attr(R.attr.hfSurface, Color.WHITE);
        mCodeBg = attr(R.attr.hfSurfaceAlt, 0xFFF0F0F0);
        mBg = attr(R.attr.hfBg, Color.WHITE);
        mAccent = attr(R.attr.hfAccent, Color.BLUE);
        mTextSize = getSharedPreferences("dec_view", Context.MODE_PRIVATE)
                .getFloat("font", 13f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mBg);
        setContentView(root);
        root.addView(header());
        root.addView(tabs());
        root.addView(codePane(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(statusBar());

        load();
    }

    // ---------------- UI ----------------

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(attr(R.attr.hfTopBar, mCardBg));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        int topText = attr(R.attr.hfTopBarText, mText);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setTextColor(topText);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(16), 0, dp(14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(cls.substring(cls.lastIndexOf('/') + 1) + ".java");
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(topText);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = new TextView(this);
        copy.setText("复制");
        copy.setTextSize(15);
        copy.setTypeface(null, Typeface.BOLD);
        copy.setTextColor(topText);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(14), dp(6), dp(16), dp(6));
        android.graphics.drawable.GradientDrawable cbg =
                new android.graphics.drawable.GradientDrawable();
        cbg.setCornerRadius(dp(6));
        cbg.setColor((topText & 0x00FFFFFF) | 0x26000000);
        copy.setBackgroundDrawable(cbg);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyCode(); }
        });
        bar.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private View tabs() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(mCardBg);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        mTabJava = tab("Java 源码", true);
        mTabSmali = tab("Smali 对照", false);
        bar.addView(mTabJava);
        bar.addView(mTabSmali);
        return bar;
    }

    private TextView tab(String text, boolean on) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(on ? mAccent : 0x00000000);
        t.setBackgroundDrawable(g);
        t.setTextColor(on ? contrast(mAccent) : mSub);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showJava = t == mTabJava;
                refreshTabs();
                show();
            }
        });
        return t;
    }

    private void refreshTabs() {
        android.graphics.drawable.GradientDrawable gj = new android.graphics.drawable.GradientDrawable();
        gj.setCornerRadius(dp(14));
        gj.setColor(showJava ? mAccent : 0x00000000);
        mTabJava.setBackgroundDrawable(gj);
        mTabJava.setTextColor(showJava ? contrast(mAccent) : mSub);
        android.graphics.drawable.GradientDrawable gs = new android.graphics.drawable.GradientDrawable();
        gs.setCornerRadius(dp(14));
        gs.setColor(showJava ? 0x00000000 : mAccent);
        mTabSmali.setBackgroundDrawable(gs);
        mTabSmali.setTextColor(showJava ? mSub : contrast(mAccent));
    }

    private View codePane() {
        HorizontalScrollView h = new HorizontalScrollView(this);
        ScrollView v = new ScrollView(this);
        // v1.63.2：背景铺在滚动容器上（而不是 TextView），内容再短也铺满整屏
        h.setBackgroundColor(mCodeBg);
        v.setBackgroundColor(mCodeBg);
        v.setFillViewport(true);
        mCode = new TextView(this);
        mCode.setTypeface(mMono);
        mCode.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, mTextSize);
        mCode.setTextColor(mText);
        mCode.setPadding(dp(8), dp(8), dp(8), dp(8));
        mCode.setTextIsSelectable(true);
        mCode.setHorizontallyScrolling(true);
        // v1.63.2：双指缩放调字号
        mScale = new ScaleGestureDetector(this, new ScaleListener());
        mCode.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View vw, MotionEvent ev) {
                mScale.onTouchEvent(ev);
                return false; // 不消费，选择/滚动手势照常
            }
        });
        v.addView(mCode);
        h.addView(v, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return h;
    }

    /** 双指捏合缩放代码字号（9-30sp），松手后持久化。 */
    private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {
        public boolean onScaleBegin(ScaleGestureDetector d) { return true; }
        public boolean onScale(ScaleGestureDetector d) {
            setTextSize(mTextSize * d.getScaleFactor());
            return true;
        }
        public void onScaleEnd(ScaleGestureDetector d) {
            getSharedPreferences("dec_view", Context.MODE_PRIVATE)
                    .edit().putFloat("font", mTextSize).apply();
        }
    }

    private void setTextSize(float sp) {
        float ns = Math.max(9f, Math.min(30f, sp));
        if (ns == mTextSize) return;
        mTextSize = ns;
        mCode.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, mTextSize);
    }

    private View statusBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(mCardBg);
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));
        mStatus = new TextView(this);
        mStatus.setTextSize(12);
        mStatus.setTextColor(mSub);
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(mStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    private void show() {
        String src = showJava ? javaSrc : smaliSrc;
        if (src == null) {
            mCode.setText("");
            return;
        }
        // 超长文本不做正则高亮（引擎有 768KB 上限），直接显示保证可用
        CharSequence cs = src.length() > SyntaxHighlight.MAX_LEN
                ? src : SyntaxHighlight.highlightById(showJava ? "java" : "smali", src);
        mCode.setText(cs);
    }

    private void copyCode() {
        String src = showJava ? javaSrc : smaliSrc;
        if (src == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("code", src));
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 反编译 ----------------

    private void load() {
        mPd = new ProgressDialog(this);
        mPd.setMessage("准备反编译引擎…");
        mPd.setCancelable(false);
        mPd.show();
        new Thread() {
            public void run() {
                final String[] res = new String[2]; // [0]=java, [1]=error
                try {
                    res[0] = doWork(new Progress() {
                        public void onProgress(final String msg) {
                            ui.post(new Runnable() {
                                public void run() { if (mPd != null) mPd.setMessage(msg); }
                            });
                        }
                    });
                } catch (Throwable t) {
                    res[1] = Decompiler.err(t);
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (mPd != null) { mPd.dismiss(); mPd = null; }
                        if (res[1] != null) {
                            javaSrc = "反编译失败：\n\n" + res[1];
                            mStatus.setText("失败");
                        } else {
                            javaSrc = res[0] == null ? "" : res[0];
                            mStatus.setText("dex2jar + CFR 0.152 · " + lines(javaSrc) + " 行"
                                    + (javaSrc.contains("控制流扁平化") ? " · ⚠ 疑似扁平化" : "")
                                    + " · 双指缩放调字号");
                        }
                        show();
                    }
                });
            }
        }.start();
    }

    private interface Progress {
        void onProgress(String msg);
    }

    /** 完整流水线，返回 Java 源码。 */
    private String doWork(Progress p) throws Exception {
        smaliSrc = readText(new File(smaliPath));

        p.onProgress("加载反编译引擎…");
        String e = Decompiler.ensure(this);
        if (e != null) throw new Exception(e);

        File tmp = new File(getCacheDir(), "dec_" + Math.abs(cls.hashCode()));
        deleteR(tmp);
        tmp.mkdirs();

        // 1) smali（当前内容，含改动）→ dex
        p.onProgress("汇编 smali…");
        File asmDir = new File(tmp, "asm");
        File dst = new File(asmDir, cls + ".smali");
        dst.getParentFile().mkdirs();
        copyFile(new File(smaliPath), dst);
        File dex = new File(tmp, "cls.dex");
        e = SmaliTool.assemble(asmDir, dex, 1);
        if (e != null) throw new Exception("smali 汇编失败（smali 语法可能有误）：\n" + e);

        // 2) dex → jar
        p.onProgress("dex2jar 转换…");
        File jar = new File(tmp, "cls.jar");
        e = Decompiler.dexToJar(dex, jar);
        if (e != null) throw new Exception("dex2jar 失败：\n" + e);

        // 3) 工程级类型库（缓存一次，供 CFR 解析跨类签名）
        File cpJar = null;
        if (dexPath != null && new File(dexPath).exists()) {
            File cache = new File(getCacheDir(), "dec_jars");
            cache.mkdirs();
            cpJar = new File(cache, new File(dexPath).getName() + ".jar");
            if (!cpJar.exists() || cpJar.length() == 0) {
                p.onProgress("首次准备类型库（整个 dex，需 10-60 秒）…");
                String e2 = Decompiler.dexToJar(new File(dexPath), cpJar);
                if (e2 != null) {
                    cpJar.delete();
                    cpJar = null; // 类型库失败不致命，降级继续
                }
            }
        }

        // 4) 取出目标 class
        p.onProgress("提取类文件…");
        File clsDir = new File(tmp, "classes");
        clsDir.mkdirs();
        File clsFile = extractClass(jar, cls, clsDir);
        if (clsFile == null) throw new Exception("dex2jar 输出中找不到 " + cls + ".class");

        // 5) CFR 反编译（走 API 直出源码，不走会 System.exit 的 Main.main）
        p.onProgress("CFR 反编译…");
        String src = Decompiler.decompileSource(clsFile, cpJar);
        return annotate(src);
    }

    /** 从 jar 里提取某个类到目录，返回文件。 */
    private static File extractClass(File jar, String cls, File outDir) throws Exception {
        ZipFile zf = new ZipFile(jar);
        try {
            String want1 = cls + ".class";
            String want2 = cls.replace('/', '.') + ".class";
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (!n.equals(want1) && !n.equals(want2)) continue;
                File out = new File(outDir, cls + ".class");
                out.getParentFile().mkdirs();
                InputStream is = zf.getInputStream(ze);
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                int r;
                while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
                fos.close();
                is.close();
                return out;
            }
        } finally {
            zf.close();
        }
        return null;
    }

    /**
     * 控制流扁平化（CFF）辅助：CFR 遇到被扁平化的方法会输出
     * {@code while(true) { switch(state) { case N: ...; state = M; continue; } }}。
     * 这里不改动代码结构（避免破坏可读性），只做两件事：
     * 把状态赋值标注成「跳到哪个 case」，并在文件头提示存在分发器。
     */
    private static String annotate(String src) {
        if (src == null) return src;
        if (!WHILE_TRUE.matcher(src).find()) return src;
        // 取所有 switch 的选择子作为「状态变量」候选
        java.util.LinkedHashSet<String> states = new java.util.LinkedHashSet<String>();
        Matcher sw = SWITCH_VAR.matcher(src);
        while (sw.find()) states.add(sw.group(1));
        if (states.isEmpty()) return src;

        String out = src;
        for (String st : states) {
            // CFR 把「状态赋值」和 continue/break 分成两行输出，所以只匹配赋值行本身，
            // 行尾原有内容（含 continue 标签）用 $4 原样保留
            Pattern p = Pattern.compile(
                    "^([ \\t]*)(" + Pattern.quote(st) + ")\\s*=\\s*(-?\\d+);(.*)$",
                    Pattern.MULTILINE);
            String repl = "$1" + Matcher.quoteReplacement(st) + " = $3;  /* → case $3 */$4";
            out = p.matcher(out).replaceAll(repl);
        }
        if (out.equals(src)) return src; // 没抓到状态赋值，不加提示避免误导
        return "/* ⚠ 检测到疑似控制流扁平化（while(true) + switch 状态分发）。\n"
                + " * 状态变量的赋值已标注为「→ case N」，顺着编号读即可还原原始顺序。 */\n"
                + out;
    }

    // ---------------- 小工具 ----------------

    private static int lines(String s) {
        if (s == null) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static void copyFile(File src, File dst) throws Exception {
        InputStream is = new java.io.FileInputStream(src);
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int r;
        while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
        os.close();
        is.close();
    }

    private static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteR(c);
        }
        f.delete();
    }

    private static String readText(File f) throws Exception {
        byte[] b = Decompiler.readAll(f);
        return new String(b, "UTF-8");
    }

    /** 主题色上的可读文字色（亮度 > 0.6 用黑，否则用白）。 */
    private static int contrast(int bg) {
        double l = (0.299 * ((bg >> 16) & 0xFF) + 0.587 * ((bg >> 8) & 0xFF) + 0.114 * (bg & 0xFF)) / 255;
        return l > 0.6 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int attr(int attrId, int def) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attrId, tv, true)) {
            if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
            if (tv.resourceId != 0) return getResources().getColor(tv.resourceId);
        }
        return def;
    }
}
