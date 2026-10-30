package com.hex91.file;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.62.0 Java → Smali 转换页（抽屉入口「☕ Java→Smali」）。
 *
 * <p>手机端离线完成三段流水线，无需联网、无需 root：</p>
 * <ol>
 *   <li><b>nb-javac</b>（NetBeans 版 OpenJDK javac，Java 8 字节码）把 Java 源码编译成 .class；</li>
 *   <li><b>dx</b>（SDK build-tools 30.0.2）把 .class 打包成 classes.dex；</li>
 *   <li><b>baksmali API</b>（{@link SmaliTool#decompile}）把 dex 反汇编成 smali。</li>
 * </ol>
 *
 * <p>编译器选型说明：早期采用 ECJ 3.21，但它引用 {@code java.lang.Module}（Java 9+ API），
 * 而 ART 禁止应用 ClassLoader 定义 {@code java.*} 包下的类，真机上必然失败；
 * 改用的 nb-javac 为纯 Java 8 字节码、无 java.* 依赖，可安全转 dex 后按需加载。</p>
 *
 * <p>javac 与 dx 体积大（合计数万方法），主 dex 方法数已接近 64K 上限，
 * 因此二者以 <b>dex 插件</b>形式随 assets 分发（javac-plugin.zip / dx-plugin.zip），
 * 首次使用时释放到私有目录并通过 {@link dalvik.system.DexClassLoader} 按需加载，
 * 反射调用入口——不进主 dex，不影响启动与其他功能。</p>
 *
 * <p>javac 编译需要 java.* 类定义，Android 设备的 bootclasspath 是 dex 无法直接读取，
 * 故附带 jre-stubs.jar（android.jar 中的 java/** 桩，约 0.8MB）作为编译期 bootclasspath。</p>
 *
 * <p>依赖面：javac 内部使用 {@code java.nio.file}（Android 8.0 / API 26 才提供），
 * 故本功能要求 <b>Android 8.0 及以上</b>；低于该版本会给出明确提示而非崩溃。</p>
 */
public class Java2SmaliActivity extends BaseActivity {

    private static final String DIR = "j2s";
    private static final String JAVAC_ZIP = "javac-plugin.zip";
    private static final String DX_ZIP = "dx-plugin.zip";
    private static final String STUBS = "jre-stubs.jar";

    /** 默认示例（与 Java2Smali 工具默认模板一致）。 */
    private static final String SAMPLE =
            "/**\n"
          + " *  Java2Smali —— 学习 Smali 的辅助工具\n"
          + " *  编写简单 Java 代码，即可看到对应的 Smali 实现\n"
          + " */\n"
          + "import java.io.*;\n"
          + "import java.text.*;\n"
          + "import java.util.*;\n"
          + "\n"
          + "public class Main {\n"
          + "    public static void main(String[] args) {\n"
          + "        System.out.println(\"Hello World!\");\n"
          + "    }\n"
          + "}\n";

    private EditText mJava;
    private TextView mSmali;
    private LinearLayout mClassBar;
    private TextView mStatus;
    private ProgressDialog mProgress;

    /** 本次转换产出的所有类 smali（类名 → 内容），第一个为主类。 */
    private final Map<String, String> mClasses = new LinkedHashMap<>();
    private String mCurClass;

    private int mText, mSub, mCardBg, mCodeBg, mBg, mAccent, mDivider;
    private Typeface mMono = Typeface.MONOSPACE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mText = attr(R.attr.hfText, Color.BLACK);
        mSub = attr(R.attr.hfTextSub, Color.GRAY);
        mCardBg = attr(R.attr.hfSurface, Color.WHITE);
        mCodeBg = attr(R.attr.hfSurfaceAlt, 0xFFF0F0F0);
        mBg = attr(R.attr.hfBg, Color.WHITE);
        mAccent = attr(R.attr.hfAccent, Color.BLUE);
        mDivider = attr(R.attr.hfDivider, 0xFFDDDDDD);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mBg);
        setContentView(root);
        root.addView(header());
        root.addView(divider(1));
        root.addView(inputPane(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(mClassBar = classBar());
        root.addView(outputPane(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f));
        root.addView(actionBar());

        mJava.setText(SAMPLE);
        mStatus.setText("就绪：点击右上角「转换」开始（首次会释放编译器插件，稍慢）");
    }

    // ---------------- UI ----------------

    /** 头部：返回 + 标题 + 右上角转换按钮。 */
    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(attr(R.attr.hfTopBar, mCardBg));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // 顶栏文字必须用 hfTopBarText：22 套彩色主题中 hfTopBar 与 hfAccent 是同色，
        // 若顶栏按钮沿用 mAccent 会与背景完全同色而「隐身」（真机验证发现的可见性问题）。
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
        title.setText("Java → Smali");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(topText);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView conv = new TextView(this);
        conv.setText("转换 ⚙");
        conv.setTextSize(15);
        conv.setTypeface(null, Typeface.BOLD);
        conv.setTextColor(topText);
        conv.setGravity(Gravity.CENTER);
        conv.setPadding(dp(14), dp(6), dp(14), dp(6));
        // 同色 15% 透明底，深浅顶栏下都能看出是可点区域
        android.graphics.drawable.GradientDrawable cbg =
                new android.graphics.drawable.GradientDrawable();
        cbg.setCornerRadius(dp(6));
        cbg.setColor((topText & 0x00FFFFFF) | 0x26000000);
        conv.setBackgroundDrawable(cbg);
        conv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { convert(); }
        });
        bar.addView(conv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    /** Java 输入区。 */
    private View inputPane() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(4));

        TextView l = new TextView(this);
        l.setText("Java 源码（public class 名决定输出的类）");
        l.setTextSize(12);
        l.setTextColor(mSub);
        box.addView(l);

        mJava = new EditText(this);
        mJava.setTypeface(mMono);
        mJava.setTextSize(12);
        mJava.setTextColor(mText);
        mJava.setHintTextColor(mSub);
        mJava.setBackgroundColor(mCodeBg);
        mJava.setPadding(dp(8), dp(8), dp(8), dp(8));
        mJava.setGravity(Gravity.TOP);
        mJava.setHorizontallyScrolling(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        box.addView(mJava, p);
        return box;
    }

    /** 类切换条（多类编译结果）。 */
    private LinearLayout classBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(10), dp(4), dp(10), dp(2));
        mStatus = new TextView(this);
        mStatus.setTextSize(12);
        mStatus.setTextColor(mSub);
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bar.addView(mStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    /** Smali 输出区。 */
    private View outputPane() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(4), dp(10), dp(4));

        TextView l = new TextView(this);
        l.setText("Smali 输出（长按可选中复制，或点底部「复制」）");
        l.setTextSize(12);
        l.setTextColor(mSub);
        box.addView(l);

        ScrollView sv = new ScrollView(this);
        mSmali = new TextView(this);
        mSmali.setTypeface(mMono);
        mSmali.setTextSize(12);
        mSmali.setTextColor(mText);
        mSmali.setBackgroundColor(mCodeBg);
        mSmali.setPadding(dp(8), dp(8), dp(8), dp(8));
        mSmali.setTextIsSelectable(true);
        sv.addView(mSmali);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    /** 底部操作：示例 / 复制 / 清空。 */
    private View actionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(mCardBg);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        bar.addView(btn("载入示例", new View.OnClickListener() {
            public void onClick(View v) { mJava.setText(SAMPLE); mStatus.setText("已载入示例"); }
        }));
        bar.addView(btn("复制结果", new View.OnClickListener() {
            public void onClick(View v) { copyResult(); }
        }));
        bar.addView(btn("清空", new View.OnClickListener() {
            public void onClick(View v) { mJava.setText(""); mSmali.setText(""); mClasses.clear(); }
        }));
        return bar;
    }

    private View btn(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(mAccent);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return b;
    }

    // ---------------- 转换流程 ----------------

    private void convert() {
        final String src = mJava.getText().toString();
        if (src.trim().isEmpty()) { toast("请先输入 Java 代码"); return; }
        mProgress = ProgressDialog.show(this, "转换中", "编译 Java → dex → smali…", true, false);
        new Thread(new Runnable() {
            public void run() {
                final String res = doConvert(src);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { mProgress.dismiss(); } catch (Exception ignore) {}
                        if (res != null) { mSmali.setText(res); mStatus.setText("编译失败，见下方错误信息"); }
                        else showClasses();
                    }
                });
            }
        }).start();
    }

    /** 展示转换结果（默认主类），并生成类切换按钮。 */
    private void showClasses() {
        if (mClasses.isEmpty()) { mSmali.setText("（未生成任何类）"); return; }
        // 类切换按钮放在类切换条里（先移除旧的）
        for (int i = mClassBar.getChildCount() - 1; i >= 1; i--) mClassBar.removeViewAt(i);
        if (mClasses.size() > 1) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            for (final String cn : mClasses.keySet()) {
                TextView t = new TextView(this);
                t.setText(cn);
                t.setTextSize(12);
                t.setTextColor(mAccent);
                t.setPadding(dp(10), dp(2), dp(10), dp(2));
                t.setBackgroundColor(mCardBg);
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { showClass(cn); }
                });
                inner.addView(t);
            }
            hs.addView(inner);
            mClassBar.addView(hs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        showClass(mCurClass != null ? mCurClass : mClasses.keySet().iterator().next());
    }

    private void showClass(String name) {
        String s = mClasses.get(name);
        if (s == null) return;
        mCurClass = name;
        mSmali.setText(s);
        mStatus.setText("已生成 " + mClasses.size() + " 个类 · 当前 " + name
                + "（" + s.split("\n").length + " 行）");
    }

    /**
     * 完整流水线：写源码 → javac 编译 → dx 打包 → baksmali 反汇编。
     * @return 成功返回 null（结果进 {@link #mClasses}）；失败返回错误信息
     */
    private String doConvert(String src) {
        File base = null;
        try {
            if (android.os.Build.VERSION.SDK_INT < 26)
                return "Java→Smali 需要 Android 8.0（API 26）及以上，当前系统版本过低";
            base = new File(getFilesDir(), DIR);
            File work = new File(base, "work");
            File srcDir = new File(work, "src");
            File clsDir = new File(work, "classes");
            File dex = new File(work, "classes.dex");
            File smaliDir = new File(work, "smali");
            rmrf(work);
            srcDir.mkdirs(); clsDir.mkdirs(); smaliDir.mkdirs();

            // 1) 主类名（优先 public class）
            String main = guessClassName(src);
            if (main == null) return "未在源码中找到 class / interface / enum 定义";
            writeFile(new File(srcDir, main + ".java"), src);

            // 2) 释放插件 + 构建 ClassLoader
            java.io.File stubJar = ensureAsset(base, STUBS);
            File javacDir = ensureAssetDir(base, JAVAC_ZIP);
            String javacPath = new File(javacDir, "classes.dex").getAbsolutePath();
            String dxPath = ensureAsset(base, DX_ZIP).getAbsolutePath();
            File opt = new File(getCodeCacheDir(), "j2s_odex");
            opt.mkdirs();
            ClassLoader pcl = Java2SmaliActivity.class.getClassLoader();
            // dexPath 三段：classes.dex（类）→ 解压目录（javac 的 *.properties 语言包，
            // DexPathList 的目录元素只提供资源查找、不加载 dex）→ dx 插件。
            // javac 启动时通过 ResourceBundle 读 com/sun/tools/javac/resources/compiler*.properties，
            // 缺了会抛 InternalError: Cannot find requested resource bundle。
            dalvik.system.DexClassLoader cl = new dalvik.system.DexClassLoader(
                    javacPath + File.pathSeparator + javacDir.getAbsolutePath()
                            + File.pathSeparator + dxPath,
                    opt.getAbsolutePath(), null, pcl);

            // 3) javac：Java → .class
            //    nb-javac 为 JDK17 langtools 编译的 Java 8 字节码版本；
            //    -XDstringConcat=inline 强制字符串拼接走 StringBuilder 而非
            //    invokedynamic（旧版 dx 不支持 invokedynamic，否则打 dex 会失败）。
            Class<?> javac = cl.loadClass("com.sun.tools.javac.Main");
            Method compile = javac.getMethod("compile", String[].class, PrintWriter.class);
            StringWriter errW = new StringWriter();
            String[] jargs = new String[]{
                    "-source", "8",
                    "-target", "8",
                    "-nowarn",
                    "-XDstringConcat=inline",
                    "-bootclasspath", stubJar.getAbsolutePath(),
                    "-classpath", stubJar.getAbsolutePath(),
                    "-d", clsDir.getAbsolutePath(),
                    new File(srcDir, main + ".java").getAbsolutePath()
            };
            Integer rc = (Integer) compile.invoke(null, jargs, new PrintWriter(errW));
            String errs = errW.toString().trim();
            if (rc == null || rc != 0) {
                return "Java 编译失败（code=" + rc + "）：\n"
                        + (errs.isEmpty() ? "（无详细错误）" : errs);
            }
            if (!new File(clsDir, main + ".class").exists() && firstClassFile(clsDir) == null) {
                // 类文件可能带包名路径
                return "编译通过但未产出 class 文件：\n" + errs;
            }

            // 4) dx：.class → dex
            Class<?> dxMain = cl.loadClass("com.android.dx.command.dexer.Main");
            Class<?> dxArgs = cl.loadClass("com.android.dx.command.dexer.Main$Arguments");
            Object args = dxArgs.getDeclaredConstructor().newInstance();
            Method parseFlags = dxArgs.getMethod("parseFlags", String[].class);
            // 注意两点（真机实测踩坑）：
            // 1) "--dex" 是外层 com.android.dx.command.Main 的子命令，dexer.Main 的
            //    parseFlags 不认识它，会抛 UsageException: unknown option: --dex；
            // 2) parseFlags 也不会把末尾的输入目录填进 fileNames（实测为 null），
            //    需手动设置，否则 run() 内部 Arrays.sort(fileNames) 空指针。
            parseFlags.invoke(args, (Object) new String[]{
                    "--no-strict", "--output=" + dex.getAbsolutePath(),
                    clsDir.getAbsolutePath()});
            java.lang.reflect.Field fn = dxArgs.getField("fileNames");
            if (fn.get(args) == null) fn.set(args, new String[]{clsDir.getAbsolutePath()});
            Method run = dxMain.getMethod("run", dxArgs);
            Integer dxRc = (Integer) run.invoke(null, args);
            if (dxRc == null || dxRc != 0) return "dx 打包 dex 失败（code=" + dxRc + "）";
            if (!dex.exists()) return "dx 未产出 dex 文件";

            // 5) baksmali：dex → smali
            String err = SmaliTool.decompile(dex, smaliDir, 4);
            if (err != null) return "baksmali 反汇编失败：" + err;

            // 6) 收集全部类（主类排最前）
            mClasses.clear();
            mCurClass = null;
            List<File> smalis = new ArrayList<>();
            collectSmali(smaliDir, smalis);
            Collections.sort(smalis);
            Map<String, String> tmp = new LinkedHashMap<>();
            File mainSmali = new File(smaliDir, main + ".smali");
            if (mainSmali.exists()) {
                tmp.put(main, compact(readFile(mainSmali)));
                mCurClass = main;
            }
            for (File f : smalis) {
                String cn = relName(smaliDir, f);
                if (tmp.containsKey(cn)) continue;
                tmp.put(cn, compact(readFile(f)));
            }
            if (tmp.isEmpty()) return "反汇编完成但未找到 smali 文件";
            mClasses.putAll(tmp);
            if (mCurClass == null) mCurClass = mClasses.keySet().iterator().next();
            return null;
        } catch (Throwable t) {
            String detail = describe(t);
            android.util.Log.e("J2S", detail, t);
            return "转换异常：\n" + detail;
        }
    }

    /** 递归展开异常链（含栈顶几行），便于定位 javac/dx 反射失败的真实原因。 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth++ < 5) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
            StackTraceElement[] st = c.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++)
                sb.append("    at ").append(st[i].toString()).append('\n');
            c = c.getCause();
            if (c != null && c != t) sb.append("  Caused by: ");
        }
        return sb.toString();
    }

    /** 取首个 public class 名；没有则取任意 class 名。 */
    private static String guessClassName(String src) {
        String noComment = src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        Pattern p = Pattern.compile(
                "(?:public\\s+|final\\s+|abstract\\s+|strictfp\\s+)*(?:class|interface|enum)\\s+([A-Za-z_$][\\w$]*)");
        Matcher m = p.matcher(noComment);
        String any = null;
        while (m.find()) {
            String name = m.group(1);
            boolean isPublic = m.group(0).contains("public");
            if (isPublic) return name;
            if (any == null) any = name;
        }
        return any;
    }

    /** 首个 .class 文件（含包路径）。 */
    private static File firstClassFile(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) { File r = firstClassFile(f); if (r != null) return r; }
            else if (f.getName().endsWith(".class")) return f;
        }
        return null;
    }

    private static void collectSmali(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectSmali(f, out);
            else if (f.getName().endsWith(".smali")) out.add(f);
        }
    }

    private static String relName(File root, File f) {
        String p = f.getAbsolutePath();
        String r = root.getAbsolutePath();
        String n = p.startsWith(r) ? p.substring(r.length() + 1) : f.getName();
        return n.substring(0, n.length() - 6).replace('/', '.');
    }

    /** 去掉 baksmali 输出的多余空行，贴近常见工具展示风格。 */
    private static String compact(String smali) {
        String[] lines = smali.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            sb.append(l.replaceAll("\\s+$", "")).append('\n');
        }
        return sb.toString();
    }

    // ---------------- 插件释放 ----------------

    /** 确保 asset 已释放到目录（大小一致则跳过）。 */
    private File ensureAsset(File dir, String name) throws java.io.IOException {
        dir.mkdirs();
        File out = new File(dir, name);
        long assetSize = -1;
        android.content.res.AssetManager am = getAssets();
        InputStream in = null;
        try {
            in = am.open(name);
            assetSize = in.available();
        } finally { close(in); }
        if (out.exists() && out.length() == assetSize && assetSize > 0) return out;
        InputStream i2 = null; OutputStream o = null;
        try {
            i2 = am.open(name);
            o = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = i2.read(buf)) > 0) o.write(buf, 0, n);
        } finally { close(i2); close(o); }
        return out;
    }

    /**
     * 释放 zip 插件并解压到同名目录。
     *
     * <p>为何要解压而非直接给 DexClassLoader 传 zip：zip 元素既能提供 dex 也能提供资源，
     * 但实测 javac 的 ResourceBundle 拿不到其中的 properties；改为「dex 文件 + 解压目录」
     * 双元素后，目录元素负责资源、dex 文件负责类，两者互不干扰。</p>
     */
    private File ensureAssetDir(File dir, String zipName) throws java.io.IOException {
        File zip = ensureAsset(dir, zipName);
        String stem = zipName.substring(0, zipName.lastIndexOf('.'));
        File out = new File(dir, stem);
        File dex = new File(out, "classes.dex");
        if (dex.exists() && dex.length() > 0 && new File(out, "com").isDirectory()) return out;
        rmrf(out);
        out.mkdirs();
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            byte[] buf = new byte[8192];
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                File f = new File(out, e.getName());
                f.getParentFile().mkdirs();
                java.io.InputStream in = null; java.io.OutputStream o = null;
                try {
                    in = zf.getInputStream(e);
                    o = new java.io.FileOutputStream(f);
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                } finally { close(in); close(o); }
            }
        } finally { zf.close(); }
        return out;
    }

    // ---------------- 小工具 ----------------

    private void copyResult() {
        String s = mSmali.getText().toString();
        if (s.isEmpty()) { toast("没有可复制的内容"); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("smali", s));
            toast("已复制 " + s.length() + " 字符");
        } catch (Exception ignore) {}
    }

    private static void writeFile(File f, String content) throws java.io.IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(content.getBytes("UTF-8"));
        } finally { close(o); }
    }

    private static String readFile(File f) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        InputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        try { while ((n = in.read(buf)) > 0) b.write(buf, 0, n); } finally { close(in); }
        return new String(b.toByteArray(), "UTF-8");
    }

    private static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) rmrf(c);
        }
        f.delete();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (java.io.IOException ignore) {}
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

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

    private View divider(int h) {
        View v = new View(this);
        v.setBackgroundColor(mDivider);
        v.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h == 0 ? h : dp(h)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
