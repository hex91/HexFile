package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.RegionTable;
import com.hex91.file.core.TestDataCore;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * v1.71.0 工具箱「测试数据」：生成校验位合法的身份证号与银行卡号。
 *
 * <p>为什么需要：分析恶意样本时，很多应用会索要实名信息与银行卡。填真实信息等于把
 * 自己的资料交给来路不明的程序；填自造的测试数据既能走完流程，又不会泄露个人信息。
 *
 * <p>身份证支持自定义：出生地（省/市/区县三级，取自 3357 条行政区划码表）、
 * 出生日期（年/月/日）、性别；银行卡支持 6 家银行（工商默认 / 建设 / 招商 / 农业 / 邮政 / 中信）。
 *
 * <p>注意：只保证「格式 + 校验位」正确，号码不对应任何真实个人或账户，
 * 无法通过需要联网核查的实名认证。见页面顶部说明。
 */
public class TestDataActivity extends BaseActivity {

    /** 从工具箱点「银行卡号」进来时直接滚到银行卡区块（0=身份证，1=银行卡） */
    public static final String EXTRA_FOCUS = "focus";

    private RegionTable regions;

    private Spinner spProv, spCity, spDist, spYear, spMonth, spDay;
    private TextView chipMale, chipFemale;
    private TextView idOut, idMeta, cardOut, cardMeta;

    private final List<RegionTable.Region> provList = new ArrayList<RegionTable.Region>();
    private final List<RegionTable.Region> cityList = new ArrayList<RegionTable.Region>();
    private final List<RegionTable.Region> distList = new ArrayList<RegionTable.Region>();

    private boolean male = true;
    private TestDataCore.Bank bank = TestDataCore.Bank.ICBC;
    /** 搭建下拉框期间屏蔽级联监听（否则设初始值时会连触发几次重建） */
    private boolean building = true;

    private final Random rnd = new Random();
    private String idValue = "";
    private String cardValue = "";

    private static final int YEAR_FROM = 1940;
    private static final int YEAR_TO = 2010;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_testdata);

        findViewById(R.id.td_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        TextView notice = (TextView) findViewById(R.id.td_notice);
        notice.setText("⚠️ 生成的是测试用假数据：只保证格式与校验位正确（身份证 ISO 7064 MOD 11-2、"
                + "银行卡 Luhn），不对应任何真实个人或账户，也无法通过需要联网核查的实名认证。\n"
                + "用途是分析恶意样本时代替你的真实信息，请勿用于伪造证件或其他欺骗用途。");
        notice.setTextColor(ThemeManager.textSub(this));

        ((View) findViewById(R.id.td_id_card)).setBackground(ToolUi.card(this, 12));
        ((View) findViewById(R.id.td_card_card)).setBackground(ToolUi.card(this, 12));

        spProv = (Spinner) findViewById(R.id.sp_prov);
        spCity = (Spinner) findViewById(R.id.sp_city);
        spDist = (Spinner) findViewById(R.id.sp_dist);
        spYear = (Spinner) findViewById(R.id.sp_year);
        spMonth = (Spinner) findViewById(R.id.sp_month);
        spDay = (Spinner) findViewById(R.id.sp_day);
        chipMale = (TextView) findViewById(R.id.chip_male);
        chipFemale = (TextView) findViewById(R.id.chip_female);
        idOut = (TextView) findViewById(R.id.id_out);
        idMeta = (TextView) findViewById(R.id.id_meta);
        cardOut = (TextView) findViewById(R.id.card_out);
        cardMeta = (TextView) findViewById(R.id.card_meta);

        idOut.setBackground(outBg());
        cardOut.setBackground(outBg());

        loadRegions();
        buildProvince();
        buildDate();
        buildGender();
        buildBanks();

        findViewById(R.id.btn_id).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                genId();
            }
        });
        findViewById(R.id.btn_card).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                genCard();
            }
        });
        findViewById(R.id.id_new).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                genId();
            }
        });
        findViewById(R.id.card_new).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                genCard();
            }
        });
        findViewById(R.id.id_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolUi.copy(TestDataActivity.this, idValue, "身份证号");
            }
        });
        findViewById(R.id.card_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolUi.copy(TestDataActivity.this, cardValue, "银行卡号");
            }
        });
        ToolUi.copyOnClick(idOut, this, "身份证号");
        ToolUi.copyOnClick(cardOut, this, "银行卡号");

        findViewById(R.id.td_all).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                genId();
                genCard();
                ToolUi.toast(TestDataActivity.this, "已生成身份证号与银行卡号");
            }
        });

        stylePrimary((TextView) findViewById(R.id.btn_id), "生成身份证号");
        stylePrimary((TextView) findViewById(R.id.btn_card), "生成银行卡号");
        styleSecondary((TextView) findViewById(R.id.id_copy), "复制");
        styleSecondary((TextView) findViewById(R.id.id_new), "换一个");
        styleSecondary((TextView) findViewById(R.id.card_copy), "复制");
        styleSecondary((TextView) findViewById(R.id.card_new), "换一个");

        building = false;
        genId();
        genCard();

        if (getIntent() != null && getIntent().getIntExtra(EXTRA_FOCUS, 0) == 1) {
            // 等首次布局完成再滚，否则 getTop() 还是 0
            final View cardBox = findViewById(R.id.td_card_card);
            findViewById(R.id.td_scroll).post(new Runnable() {
                public void run() {
                    android.widget.ScrollView sv =
                            (android.widget.ScrollView) findViewById(R.id.td_scroll);
                    sv.smoothScrollTo(0, cardBox.getTop());
                }
            });
        }
    }

    // ============================================================ 数据准备

    private void loadRegions() {
        String text = readRaw(R.raw.regions);
        regions = RegionTable.parse(text);
        if (regions.size() < 100) {
            // 极端情况（资源被裁）：退化成最小可用集合，保证页面不白屏
            regions = RegionTable.parse("110000|北京市|\n110100|市辖区|110000\n110101|东城区|110100\n");
        }
    }

    private String readRaw(int id) {
        InputStream in = null;
        try {
            in = getResources().openRawResource(id);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(100 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ignore) {
            }
        }
    }

    // ============================================================ 三级地区

    private void buildProvince() {
        provList.clear();
        provList.addAll(regions.provinces());
        List<String> names = new ArrayList<String>();
        for (RegionTable.Region r : provList) names.add(r.name);
        spProv.setAdapter(ToolUi.adapter(this, names.toArray(new String[0])));

        int sel = indexOf(provList, "110000");
        spProv.setSelection(sel < 0 ? 0 : sel);

        spProv.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (building) return;
                buildCity(pos, null);
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        buildCity(spProv.getSelectedItemPosition(), "110100");
    }

    private void buildCity(int provPos, String preferCode) {
        cityList.clear();
        if (provPos >= 0 && provPos < provList.size()) {
            cityList.addAll(regions.children(provList.get(provPos).code));
        }
        List<String> names = new ArrayList<String>();
        for (RegionTable.Region r : cityList) names.add(regions.cityLabel(r.code));
        spCity.setAdapter(ToolUi.adapter(this, names.toArray(new String[0])));

        int sel = preferCode == null ? 0 : indexOf(cityList, preferCode);
        spCity.setSelection(sel < 0 ? 0 : sel);

        spCity.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (building) return;
                buildDistrict(pos, null);
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        buildDistrict(spCity.getSelectedItemPosition(), preferCode == null ? null : "110101");
    }

    private void buildDistrict(int cityPos, String preferCode) {
        distList.clear();
        if (cityPos >= 0 && cityPos < cityList.size()) {
            distList.addAll(regions.children(cityList.get(cityPos).code));
        }
        if (distList.isEmpty() && cityPos >= 0 && cityPos < cityList.size()) {
            distList.add(cityList.get(cityPos));    // 直筒子市：区县就是市本身
        }
        List<String> names = new ArrayList<String>();
        for (RegionTable.Region r : distList) names.add(r.name);
        spDist.setAdapter(ToolUi.adapter(this, names.toArray(new String[0])));
        int sel = preferCode == null ? 0 : indexOf(distList, preferCode);
        spDist.setSelection(sel < 0 ? 0 : sel);
    }

    /** 按码值找下标；distList 里东莞/中山是「市自己」，所以还要按名称兜底 */
    private int indexOf(List<RegionTable.Region> list, String code) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).code.equals(code)) return i;
        }
        return -1;
    }

    // ============================================================ 日期

    private void buildDate() {
        List<String> years = new ArrayList<String>();
        for (int y = YEAR_FROM; y <= YEAR_TO; y++) years.add(y + " 年");
        spYear.setAdapter(ToolUi.adapter(this, years.toArray(new String[0])));
        spYear.setSelection(1991 - YEAR_FROM);

        List<String> months = new ArrayList<String>();
        for (int m = 1; m <= 12; m++) months.add(m + " 月");
        spMonth.setAdapter(ToolUi.adapter(this, months.toArray(new String[0])));
        spMonth.setSelection(9);   // 10 月

        rebuildDays();
        spDay.setSelection(0);     // 1 日

        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (building) return;
                rebuildDays();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        spYear.setOnItemSelectedListener(l);
        spMonth.setOnItemSelectedListener(l);
    }

    /** 月份/年份变了要重算当月天数，尽量保住已选的「日」 */
    private void rebuildDays() {
        int y = YEAR_FROM + Math.max(0, spYear.getSelectedItemPosition());
        int m = 1 + Math.max(0, spMonth.getSelectedItemPosition());
        int max = TestDataCore.daysInMonth(y, m);
        int keep = spDay.getSelectedItemPosition();
        if (keep == AdapterView.INVALID_POSITION) keep = 0;

        List<String> days = new ArrayList<String>();
        for (int d = 1; d <= max; d++) days.add(d + " 日");
        spDay.setAdapter(ToolUi.adapter(this, days.toArray(new String[0])));
        spDay.setSelection(Math.min(Math.max(keep, 0), max - 1));
    }

    // ============================================================ 性别 / 银行

    private void buildGender() {
        chipMale.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                male = true;
                paintGender();
            }
        });
        chipFemale.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                male = false;
                paintGender();
            }
        });
        paintGender();
    }

    private void paintGender() {
        ToolUi.chipState(chipMale, male, this);
        ToolUi.chipState(chipFemale, !male, this);
    }

    private void buildBanks() {
        LinearLayout box = (LinearLayout) findViewById(R.id.td_banks);
        TestDataCore.Bank[] all = TestDataCore.Bank.values();
        LinearLayout row = null;
        final List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < all.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.topMargin = i == 0 ? 0 : ToolUi.dp(this, 6);
                box.addView(row, rp);
            }
            final TestDataCore.Bank b = all[i];
            final TextView chip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ToolUi.dp(this, 36), 1f);
            lp.setMargins(i % 3 == 0 ? 0 : ToolUi.dp(this, 6), 0, 0, 0);
            chip.setLayoutParams(lp);
            chip.setGravity(Gravity.CENTER);
            chip.setText(b.shortName);
            chip.setTextSize(13f);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    bank = b;
                    for (TextView c : chips) ToolUi.chipState(c, c == chip, TestDataActivity.this);
                    genCard();
                }
            });
            chips.add(chip);
            row.addView(chip);
        }
        for (TextView c : chips) ToolUi.chipState(c, c == chips.get(0), this);
    }

    // ============================================================ 生成

    private void genId() {
        int distPos = spDist.getSelectedItemPosition();
        if (distPos < 0 || distPos >= distList.size()) {
            ToolUi.toast(this, "请先选择出生地");
            return;
        }
        RegionTable.Region d = distList.get(distPos);
        int y = YEAR_FROM + Math.max(0, spYear.getSelectedItemPosition());
        int m = 1 + Math.max(0, spMonth.getSelectedItemPosition());
        int day = 1 + Math.max(0, spDay.getSelectedItemPosition());
        try {
            idValue = TestDataCore.idNumber(d.code, y, m, day, male, rnd);
        } catch (IllegalArgumentException e) {
            ToolUi.toast(this, "生成失败：" + e.getMessage());
            return;
        }
        idOut.setText(idValue);

        String regionName = regions.fullName(d.code);
        if (regionName.length() == 0) regionName = d.name;

        StringBuilder sb = new StringBuilder(96);
        sb.append("地区  ").append(regionName).append("  (").append(d.code).append(")\n");
        sb.append("生日  ").append(TestDataCore.idBirth(idValue))
                .append(male ? "  男" : "  女").append('\n');
        sb.append("校验  ISO 7064 MOD 11-2 通过 · 末位 ")
                .append(idValue.charAt(17)).append(" · 第 17 位 ")
                .append(idValue.charAt(16)).append(male ? "（奇=男）" : "（偶=女）");
        idMeta.setText(sb.toString());
    }

    private void genCard() {
        cardValue = TestDataCore.cardNumber(bank, rnd);
        cardOut.setText(TestDataCore.groupCard(cardValue));
        String bin = TestDataCore.binOf(cardValue);
        StringBuilder sb = new StringBuilder(80);
        sb.append(bank.full).append(" · BIN ").append(bin).append('\n');
        sb.append(cardValue.length()).append(" 位 · Luhn 校验通过 · 仅测试数据，非真实账户");
        cardMeta.setText(sb.toString());
    }

    // ============================================================ 样式

    private android.graphics.drawable.GradientDrawable outBg() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(ThemeManager.bg(this));
        g.setCornerRadius(ToolUi.dp(this, 10));
        g.setStroke(Math.max(1, ToolUi.dp(this, 0.6f)), ThemeManager.divider(this));
        return g;
    }

    private void stylePrimary(TextView v, String text) {
        v.setText(text);
        v.setBackground(ToolUi.accentBg(this, 10));
        v.setTextColor(ThemeManager.onAccent(this));
    }

    private void styleSecondary(TextView v, String text) {
        v.setText(text);
        v.setBackground(ToolUi.card(this, 10));
        v.setTextColor(ThemeManager.text(this));
    }
}
