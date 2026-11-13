package com.hex91.file.ui;

import com.hex91.file.net.TokenHarvest;
import com.hex91.file.*;
import com.hex91.file.R;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * v1.90.0 云盘网页登录：直接弹出登录界面，登录完自动把凭证取回来。
 *
 * <p>之前阿里云盘只能让用户「电脑浏览器 F12 → Local Storage → 复制 token」，
 * 这是把开发者调试手段当成用户操作流程 —— 绝大多数人到这里就放弃了。
 * 实际上换个思路就能做成一步：阿里云盘网页版登录成功后，token 就写在本站
 * 的 localStorage 里，而 WebView 与页面<b>同源</b>，能直接读到。</p>
 *
 * <p>于是流程变成：点「登录」→ 弹出阿里云盘自己的登录页 → 用户正常输手机号/验证码
 * （或密码、扫码）→ 登录成功跳回网盘 → 页面脚本发现 localStorage 里出现
 * refresh_token → 回调给 App → 自动关闭。</p>
 *
 * <p><b>安全边界</b>：账号密码只输入到阿里云盘自己的页面里，本应用既不读取输入框、
 * 也不注入任何拦截逻辑，只做一件事：读 localStorage 里那个 token 字段。
 * 这与「用系统浏览器登录再复制」在信任模型上是等价的，只是把复制粘贴省掉了。</p>
 *
 * <p><b>为什么必须伪装 UA</b>：WebView 默认 UA 带 {@code ; wv} 标记，不少站点据此
 * 判定「非标准浏览器」，走降级页或直接拒绝登录。换成干净的 Chrome UA 才拿到正常登录页。</p>
 *
 * <p><b>为什么用轮询而不是只靠页面加载完成</b>：网盘是单页应用，登录成功后不重新加载页面
 * （只改 history 与视图），{@code onPageFinished} 不会再触发。必须定时扫，
 * 且登录态写入 localStorage 与界面跳转之间有几十到几百毫秒间隙，
 * 所以扫到之后还要等一拍再取值，否则可能读到写了一半的对象。</p>
 */
public final class CloudLoginActivity extends Activity {

    /** 回传 refresh_token */
    public static final String EXTRA_REFRESH = "hf_refresh";
    /** 回传 user_id（仅用于提示「登的是哪个账号」） */
    public static final String EXTRA_USER = "hf_user";

    /** 支持的站点（目前只有阿里云盘走网页登录；OneDrive 用设备码，不需要这里） */
    public static final String SITE_ALIYUN = "aliyun";

    private static final String URL_ALIYUN = "https://www.alipan.com/sign/in";

    /** 伪装成标准移动 Chrome：去掉 WebView 的 "; wv" 标记 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Mobile Safari/537.36";

    /**
     * 注入脚本：把浏览器存储里所有含 refresh_token 的项 dump 出来。
     *
     * <p>不写死键名（现在叫 {@code token}，将来可能改），而是按内容找 ——
     * 只要值里出现 refresh_token 字样就交上去，具体哪条最可信交给 {@link TokenHarvest} 打分。</p>
     *
     * <p><b>必须同时扫 sessionStorage</b>：核对过阿里云盘登录前端（h5-sign 的 app.js），
     * 它的封装是 {@code get(){ return sessionStorage.getItem("token") || localStorage.getItem("token") }}
     * 而 {@code save()} 按「是否勾选记住登录」决定写哪一个 —— <b>没勾就写 sessionStorage</b>。
     * 只扫 localStorage 的话，用户在没勾「记住登录」时明明登录成功了，App 却一直停在等待状态，
     * 而且日志里什么都看不到。所以按站点自己的优先级来：先 sessionStorage，再 localStorage。</p>
     */
    private static final String SCAN_JS =
            "(function(){"
            + "if(window.__hfScan)return;"
            + "function grab(st){var o={},n=0;try{"
            + "for(var i=0;i<st.length;i++){var k=st.key(i),v=st.getItem(k);"
            + "if(v&&v.indexOf('refresh_token')>=0){o[k]=v;n++;}}}catch(e){}return n?o:null;}"
            + "var last='';"
            + "function send(){try{"
            + "var o=grab(window.sessionStorage)||grab(window.localStorage);"
            + "if(!o)return;"
            + "var s=JSON.stringify(o);"
            + "if(s!==last){last=s;return;}"      // 还在变：记下来，等下一拍确认写完
            + "window.HF.onToken(s);"
            + "}catch(e){}}"
            + "window.__hfScan=setInterval(send,900);"
            + "})();";

    private WebView mWeb;
    private ProgressBar mBar;
    private TextView mStatus;
    private String mSite = SITE_ALIYUN;
    private boolean mDone;

    private int mBg, mTopBg, mTopText, mAccent, mTextSub;

    /** 打开登录页（调用方用 startActivityForResult） */
    public static Intent intent(Context c, String site) {
        Intent it = new Intent(c, CloudLoginActivity.class);
        it.putExtra("site", site);
        return it;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getIntent() != null && getIntent().getStringExtra("site") != null) {
            mSite = getIntent().getStringExtra("site");
        }
        mBg = attr(R.attr.hfBg, 0xFFFFFFFF);
        mTopBg = attr(R.attr.hfTopBar, 0xFFEEEEEE);
        mTopText = attr(R.attr.hfTopBarText, 0xFF222222);
        mAccent = attr(R.attr.hfAccent, 0xFF3E9AFB);
        mTextSub = attr(R.attr.hfTextSub, 0xFF888888);

        // 登录页里有输入框：别让软键盘把页面顶得乱跳，交给系统 adjustResize
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mBg);
        setContentView(root);

        root.addView(header());
        root.addView(progressLine());
        root.addView(web(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(statusBar());

        load();
    }

    // ---------------------------------------------------------------- UI

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(mTopBg);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setTextColor(mTopText);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(16), 0, dp(12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(SITE_ALIYUN.equals(mSite) ? "登录阿里云盘" : "登录");
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(mTopText);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 兜底出口：万一站点改版/风控导致抓不到，用户还能回到粘贴方式，不至于卡死
        TextView paste = new TextView(this);
        paste.setText("粘贴方式");
        paste.setTextSize(13);
        paste.setTypeface(null, Typeface.BOLD);
        paste.setTextColor(mTopText);
        paste.setGravity(Gravity.CENTER);
        paste.setPadding(dp(12), dp(8), dp(16), dp(8));
        UiDecor.barButtonOnBar(paste, this, UiDecor.KIND_SOFT, 13f);
        paste.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 用 FIRST_USER 区分「主动想改用粘贴」和「直接返回」：
                // 后者不该再弹一个框，前者应该
                setResult(RESULT_FIRST_USER);
                finish();
            }
        });
        bar.addView(paste, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return bar;
    }

    private View progressLine() {
        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(100);
        mBar.setProgress(0);
        mBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
        return mBar;
    }

    private View web() {
        mWeb = new WebView(this);
        WebSettings s = mWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage 不开就什么都读不到
        s.setDatabaseEnabled(true);
        s.setUserAgentString(UA);              // 必须在 loadUrl 之前
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);    // 弹窗在当前 WebView 打开，别开新窗口
        s.setAllowFileAccess(false);           // 登录页不需要本地文件，关掉减小攻击面
        s.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        mWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                inject();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                // 站内继续在 WebView 里走；唤起支付宝之类的外部 scheme 交给系统
                if (url.startsWith("http://") || url.startsWith("https://")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignore) {
                    // 没装对应 App：留在当前页，不弹错误页
                }
                return true;
            }
        });

        mWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (mBar != null) {
                    mBar.setProgress(p);
                    mBar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }
        });

        mWeb.addJavascriptInterface(new Bridge(), "HF");
        return mWeb;
    }

    private View statusBar() {
        mStatus = new TextView(this);
        mStatus.setText("在下方登录后会自动完成授权，不用手动复制任何东西");
        mStatus.setTextSize(11.5f);
        mStatus.setTextColor(mTextSub);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        mStatus.setPadding(pad, dp(9), pad, dp(9));
        mStatus.setBackgroundColor(UiDecor.alpha(mTopBg, 0x66));
        return mStatus;
    }

    private void load() {
        mWeb.loadUrl(URL_ALIYUN);
        // 登录成功但抓不到（站点改版 / 授权走了外部 App）时给一条明确的出路，
        // 否则用户只会看到「登录了但没反应」
        mStatus.postDelayed(mSlowHint, 45000);
    }

    private final Runnable mSlowHint = new Runnable() {
        public void run() {
            if (mDone || mStatus == null) return;
            mStatus.setText("还没检测到登录状态。若页面上已经显示登录成功，"
                    + "可能是网盘改了存储方式：点右上角「粘贴方式」用原办法。");
        }
    };

    // ---------------------------------------------------------------- 注入与回传

    private void inject() {
        if (mDone || mWeb == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 19) {
                mWeb.evaluateJavascript(SCAN_JS, null);
            } else {
                mWeb.loadUrl("javascript:" + SCAN_JS);
            }
        } catch (Exception ignore) {
            // 页面正在跳转时注入可能失败，下一次 onPageFinished 会再来一次
        }
    }

    /** 页面脚本回调（在 WebView 的 JS 线程） */
    private final class Bridge {
        @JavascriptInterface
        public void onToken(final String dump) {
            runOnUiThread(new Runnable() {
                public void run() { handle(dump); }
            });
        }
    }

    private void handle(String dump) {
        if (mDone) return;
        // 只有官方域名有权回传：addJavascriptInterface 是全局可见的，
        // 若被导航到别的站点，那里也能调用 window.HF
        if (!trusted()) return;

        TokenHarvest.Hit hit = TokenHarvest.fromStorageDump(dump);
        if (hit == null || hit.refresh.isEmpty()) return;   // 还没写完整，继续轮询

        mDone = true;
        if (mStatus != null) mStatus.setText(hit.describe());
        Intent out = new Intent();
        out.putExtra(EXTRA_REFRESH, hit.refresh);
        out.putExtra(EXTRA_USER, hit.userId);
        setResult(RESULT_OK, out);
        finish();
    }

    private boolean trusted() {
        String u = mWeb == null ? null : mWeb.getUrl();
        if (u == null) return false;
        String h;
        try {
            h = Uri.parse(u).getHost();
        } catch (Exception e) {
            return false;
        }
        if (h == null) return false;
        h = h.toLowerCase(java.util.Locale.US);
        return hostOk(h, "alipan.com") || hostOk(h, "aliyundrive.com");
    }

    /** 精确到子域：{@code evilalipan.com} 不能算 alipan.com */
    private static boolean hostOk(String host, String base) {
        return host.equals(base) || host.endsWith("." + base);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onDestroy() {
        if (mStatus != null) mStatus.removeCallbacks(mSlowHint);
        if (mWeb != null) {
            try {
                mWeb.removeJavascriptInterface("HF");
                mWeb.stopLoading();
                mWeb.loadUrl("about:blank");
                mWeb.destroy();
            } catch (Exception ignore) {
                // 部分 ROM 在 destroy 时会抛，忽略即可
            }
            mWeb = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mWeb != null && mWeb.canGoBack()) mWeb.goBack();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- 小工具

    private int attr(int id, int def) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(id, tv, true)) return tv.data;
        return def;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
