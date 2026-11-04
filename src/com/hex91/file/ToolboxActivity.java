package com.hex91.file;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v1.70.0 工具箱首页。
 *
 * <p>卡片按用途分区：编码转换 / 加解密 / 进制 / 网络 / 时间。前两区的卡片带「预设」——
 * 点「中文域名」直接以「文本 → Punycode」打开转换页，点「国密 SM4」直接选中 SM4，
 * 省掉进页面后再翻下拉框的一步。
 */
public class ToolboxActivity extends BaseActivity {

    /** 传给 {@link CodecActivity} 的预设格式 */
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_TO = "to";
    /** 传给 {@link CipherActivity} 的预设算法 */
    public static final String EXTRA_ALG = "alg";

    private static final class Tool {
        final String emoji, title, desc;
        final Class<?> cls;
        final int from, to, alg;

        Tool(String emoji, String title, String desc, Class<?> cls, int from, int to, int alg) {
            this.emoji = emoji;
            this.title = title;
            this.desc = desc;
            this.cls = cls;
            this.from = from;
            this.to = to;
            this.alg = alg;
        }
    }

    private static Tool codec(String e, String t, String d, int from, int to) {
        return new Tool(e, t, d, CodecActivity.class, from, to, -1);
    }

    private static Tool cipher(String e, String t, String d, int alg) {
        return new Tool(e, t, d, CipherActivity.class, -1, -1, alg);
    }

    private static Tool plain(String e, String t, String d, Class<?> c) {
        return new Tool(e, t, d, c, -1, -1, -1);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_toolbox);

        findViewById(R.id.tb_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        LinearLayout box = (LinearLayout) findViewById(R.id.tb_box);

        section(box, "编码转换", "文本与各种编码之间任意互转");
        grid(box,
                codec("🔤", "文本 ⇄ HEX", "E4 B8 AD / 0xE4…", CodecCore.F_TEXT, CodecCore.F_HEX),
                codec("🔗", "URL 编解码", "%E4%B8%AD / 表单", CodecCore.F_TEXT, CodecCore.F_URL),
                codec("🧮", "Unicode", "\\u4E2D 转义", CodecCore.F_TEXT, CodecCore.F_UNICODE),
                codec("🀄", "中文域名", "中文.com ⇄ xn--", CodecCore.F_TEXT, CodecCore.F_PUNYCODE),
                codec("📦", "字节数组", "0xE4,0xB8,0xAD", CodecCore.F_TEXT, CodecCore.F_BYTES),
                codec("🧰", "任意格式互转", "14 种格式任选", CodecCore.F_TEXT, CodecCore.F_HEX));

        section(box, "加解密", "对称加密、摘要、国密、Base64");
        grid(box,
                cipher("🔐", "AES 加解密", "ECB / CBC", CryptoCore.A_AES),
                cipher("🛡️", "国密 SM4", "ECB / CBC · 128 位", CryptoCore.A_SM4),
                cipher("🏷️", "哈希 / 摘要", "MD5 · SHA · SM3", CryptoCore.A_MD5),
                cipher("🔑", "Base64", "编码 / 解码", CryptoCore.A_B64_ENC));

        section(box, "换算与调试", "");
        grid(box,
                plain("🔢", "进制转换", "2 / 8 / 10 / 16 / 36", RadixActivity.class),
                plain("🕐", "时间戳", "秒 ↔ 日期时间", TimeToolActivity.class),
                plain("🌐", "网络请求", "类 Postman 调试", HttpToolActivity.class));
    }

    // ============================================================ 视图构建

    private void section(LinearLayout box, String title, String desc) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = ToolUi.dp(this, 14);
        hp.leftMargin = ToolUi.dp(this, 4);
        hp.bottomMargin = ToolUi.dp(this, 4);
        box.addView(head, hp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ThemeManager.text(this));
        head.addView(t);

        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextSize(11f);
            d.setTextColor(ThemeManager.textSub(this));
            d.setPadding(0, ToolUi.dp(this, 2), 0, 0);
            head.addView(d);
        }
    }

    private void grid(LinearLayout box, Tool... tools) {
        LinearLayout row = null;
        for (int i = 0; i < tools.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                box.addView(row, rp);
            }
            row.addView(card(tools[i]));
            if (i == tools.length - 1 && tools.length % 2 == 1) {
                View spacer = new View(this);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
                sp.setMargins(ToolUi.dp(this, 4), 0, ToolUi.dp(this, 4), 0);
                row.addView(spacer, sp);
            }
        }
    }

    private View card(final Tool t) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(ToolUi.dp(this, 4), ToolUi.dp(this, 4), ToolUi.dp(this, 4), ToolUi.dp(this, 4));
        v.setLayoutParams(lp);
        v.setMinimumHeight(ToolUi.dp(this, 84));
        v.setPadding(ToolUi.dp(this, 12), ToolUi.dp(this, 11), ToolUi.dp(this, 12), ToolUi.dp(this, 12));
        v.setBackground(ToolUi.card(this, 12));
        v.setClickable(true);

        TextView icon = new TextView(this);
        icon.setText(t.emoji);
        icon.setTextSize(20f);
        v.addView(icon);

        TextView title = new TextView(this);
        title.setText(t.title);
        title.setTextSize(13.5f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ThemeManager.text(this));
        title.setPadding(0, ToolUi.dp(this, 6), 0, 0);
        v.addView(title);

        TextView desc = new TextView(this);
        desc.setText(t.desc);
        desc.setTextSize(10.5f);
        desc.setTextColor(ThemeManager.textSub(this));
        desc.setPadding(0, ToolUi.dp(this, 2), 0, 0);
        desc.setMaxLines(2);
        v.addView(desc);

        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                Intent it = new Intent(ToolboxActivity.this, t.cls);
                if (t.from >= 0) {
                    it.putExtra(EXTRA_FROM, t.from);
                    it.putExtra(EXTRA_TO, t.to);
                }
                if (t.alg >= 0) it.putExtra(EXTRA_ALG, t.alg);
                startActivity(it);
            }
        });
        return v;
    }
}
