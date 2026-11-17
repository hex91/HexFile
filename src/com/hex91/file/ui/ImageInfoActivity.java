package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.utils.LogRing;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 图片元数据页（v2.12.0，F-09）。
 *
 * <p>对标 MT 的「图片信息」：文件本身（名字/路径/大小/时间/像素）加 EXIF。
 * 常见用途是「这张图是哪台手机拍的 / 什么时候拍的 / 有没有带 GPS」——
 * 前两个决定照片来源，最后一个决定能不能直接发出去。</p>
 *
 * <p>三个实现选择：</p>
 * <ul>
 *   <li>用平台的 {@link ExifInterface}（API 5 起有，吃文件路径）：工程里没有
 *       androidx.exifinterface 依赖，而 {@code libs/} 只放签名/反编译相关的 jar，
 *       为一张信息页引一个依赖不划算；JPEG/PNG/WebP/HEIF 的平台实现够用。</li>
 *   <li>EXIF 全部读失败时**不报错**，只显示「无 EXIF」：PNG 重编码、截图、
 *       社交软件转存都会掉 EXIF，那是正常情况，不是错误。</li>
 *   <li>值全部拼进一个可选中的 TextView：用户最常做的是「复制经纬度」或
 *       「复制拍摄时间」，一行一个 {@code 标签：值} 比一行一个控件更好选、更好复制。</li>
 * </ul>
 */
public class ImageInfoActivity extends BaseActivity {

    public static final String EXTRA_FILE = "file";

    /** 一次读取的全部行（复制全部时直接用它，不再重新读一遍 EXIF） */
    private final List<String> lines = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String path = getIntent() != null ? getIntent().getStringExtra(EXTRA_FILE) : null;
        final File f = path != null ? new File(path) : null;
        if (f == null || !f.isFile()) {
            Toast.makeText(this, R.string.img_info_missing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        float den = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));

        // ---- 标题栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(ThemeManager.surface(this));
        top.setPadding((int) (16 * den), (int) (10 * den), (int) (16 * den), (int) (10 * den));
        TextView title = new TextView(this);
        title.setText(getString(R.string.img_info_title) + " · " + f.getName());
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.text(this));
        title.setSingleLine(true);
        top.addView(title);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 正文
        collectLines(f);
        TextView body = new TextView(this);
        body.setText(join());
        body.setTextSize(13);
        body.setTextIsSelectable(true);
        body.setTextColor(ThemeManager.text(this));
        body.setLineSpacing((int) (3 * den), 1f);
        body.setPadding((int) (16 * den), (int) (12 * den), (int) (16 * den), (int) (12 * den));
        ScrollView sc = new ScrollView(this);
        sc.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部：复制全部 / 关闭
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ThemeManager.surface(this));
        bar.setPadding((int) (8 * den), (int) (6 * den), (int) (8 * den), (int) (6 * den));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        Button bCopy = mkBtn(bar, blp, getString(R.string.img_info_copy_all));
        bCopy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText(f.getName(), join()));
                Toast.makeText(ImageInfoActivity.this, R.string.img_info_copied,
                        Toast.LENGTH_SHORT).show();
            }
        });
        Button bClose = mkBtn(bar, blp, getString(R.string.common_close));
        bClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private Button mkBtn(LinearLayout bar, LinearLayout.LayoutParams lp, String text) {
        Button b = new Button(this);
        b.setText(text);
        UiDecor.barButton(b, this, UiDecor.KIND_SOFT, 12f);
        bar.addView(b, lp);
        return b;
    }

    private String join() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 读取

    /** 先落文件信息（一定读得到），再叠 EXIF（可能整段没有） */
    private void collectLines(File f) {
        row(R.string.img_info_name, f.getName());
        row(R.string.img_info_path, f.getAbsolutePath());
        row(R.string.img_info_size, FileOps.formatSize(f.length()));
        row(R.string.img_info_mtime, FileOps.formatTime(f.lastModified()));

        // 像素：只读头部（inJustDecodeBounds），不解码整张图，几百 MB 的图也不吃内存
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            // 解不出就当未知：这行留痕是为了区分「真不是图片」与「平台解码器崩了」
            LogRing.add("ImageInfo", "bounds failed: " + t);
        }
        if (o.outWidth > 0 && o.outHeight > 0) {
            row(R.string.img_info_dims, o.outWidth + " × " + o.outHeight);
        }
        String lower = f.getName().toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        if (dot > 0 && dot < lower.length() - 1) {
            row(R.string.img_info_format, lower.substring(dot + 1).toUpperCase(Locale.US));
        }

        ExifInterface exif = null;
        try {
            exif = new ExifInterface(f.getAbsolutePath());
        } catch (Throwable t) {
            // 不是图片、或平台实现不支持该容器：下面统一走「无 EXIF」
            LogRing.add("ImageInfo", "exif open failed: " + t);
        }
        List<String> ex = new ArrayList<String>();
        if (exif != null) {
            addExif(ex, R.string.img_info_make, attr(exif, ExifInterface.TAG_MAKE));
            addExif(ex, R.string.img_info_model, attr(exif, ExifInterface.TAG_MODEL));
            // 拍摄时间优先 DateTimeOriginal（快门那一刻），退回 DateTime（最后修改）
            String taken = attr(exif, ExifInterface.TAG_DATETIME_ORIGINAL);
            if (taken == null) taken = attr(exif, ExifInterface.TAG_DATETIME);
            addExif(ex, R.string.img_info_taken, taken);
            addExif(ex, R.string.img_info_orientation, orientation(exif));
            String exp = attr(exif, ExifInterface.TAG_EXPOSURE_TIME);
            if (exp != null) {
                exp = prettyExposure(exp);
            }
            addExif(ex, R.string.img_info_exposure, exp);
            String fnum = attr(exif, ExifInterface.TAG_F_NUMBER);
            if (fnum == null) fnum = attr(exif, ExifInterface.TAG_APERTURE);   // 老 API 的标签名
            addExif(ex, R.string.img_info_aperture, fnum == null ? null : "f/" + fnum);
            addExif(ex, R.string.img_info_iso, attr(exif, ExifInterface.TAG_ISO_SPEED_RATINGS));
            String focal = attr(exif, ExifInterface.TAG_FOCAL_LENGTH);
            addExif(ex, R.string.img_info_focal, focal == null ? null : prettyRatio(focal) + " mm");
            String flash = attr(exif, ExifInterface.TAG_FLASH);
            if (flash != null) {
                addExif(ex, R.string.img_info_flash,
                        getString(flashBit(flash) ? R.string.img_info_flash_on
                                : R.string.img_info_flash_off));
            }
            String wb = attr(exif, ExifInterface.TAG_WHITE_BALANCE);
            if (wb != null) {
                addExif(ex, R.string.img_info_white_balance,
                        getString("1".equals(wb.trim()) ? R.string.img_info_wb_manual
                                : R.string.img_info_wb_auto));
            }
            addExif(ex, R.string.img_info_location, location(exif));
        }
        if (ex.isEmpty()) {
            lines.add("");
            lines.add(getString(R.string.img_info_row, getString(R.string.img_info_exif),
                    getString(R.string.img_info_no_exif)));
        } else {
            lines.add("");
            lines.add(getString(R.string.img_info_exif));
            lines.addAll(ex);
        }
    }

    private void row(int labelRes, String value) {
        lines.add(getString(R.string.img_info_row, getString(labelRes), value));
    }

    private void addExif(List<String> out, int labelRes, String value) {
        if (value == null) return;
        String v = value.trim();
        if (v.length() == 0 || "unknown".equalsIgnoreCase(v)) return;
        out.add(getString(R.string.img_info_row, getString(labelRes), v));
    }

    /** getAttribute 在标签不存在时返回 null；个别 ROM 的实现会抛，这里一并吞成 null */
    private static String attr(ExifInterface exif, String tag) {
        try {
            return exif.getAttribute(tag);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 方向 → 人话。镜像的四种在 EXIF 里是 2/4/5/7，值本身就是「镜像 + 旋转」 */
    private String orientation(ExifInterface exif) {
        String raw = attr(exif, ExifInterface.TAG_ORIENTATION);
        if (raw == null) return null;
        int o;
        try {
            o = Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return null;
        }
        switch (o) {
            case 1: return getString(R.string.img_info_orient_normal);
            case 3: return getString(R.string.img_info_orient_rot, 180);
            case 6: return getString(R.string.img_info_orient_rot, 90);
            case 8: return getString(R.string.img_info_orient_rot, 270);
            case 2: return getString(R.string.img_info_orient_flip, 0);
            case 4: return getString(R.string.img_info_orient_flip, 180);
            case 5: return getString(R.string.img_info_orient_flip, 90);
            case 7: return getString(R.string.img_info_orient_flip, 270);
            default: return null;
        }
    }

    /** 曝光时间：EXIF 里是「1/100」这种分数串，长的十进制（0.008）收拾成 1/125 */
    static String prettyExposure(String raw) {
        String s = raw.trim();
        try {
            if (s.indexOf('/') >= 0) return s + " s";
            double v = Double.parseDouble(s);
            if (v > 0 && v < 0.5) return "1/" + Math.round(1 / v) + " s";
            if (v > 0) return String.format(Locale.US, "%.1f s", v);
        } catch (Throwable t) {
            return s;    // 不是数字就原样显示，别把值弄丢
        }
        return s;
    }

    /** 焦距在 EXIF 里是「47/10」这种有理数，换成 4.7 */
    static String prettyRatio(String raw) {
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash <= 0) return s;
        try {
            double num = Double.parseDouble(s.substring(0, slash));
            double den = Double.parseDouble(s.substring(slash + 1));
            if (den == 0) return s;
            return String.format(Locale.US, "%.1f", num / den);
        } catch (Throwable t) {
            return s;
        }
    }

    /** EXIF Flash 是位域：bit0 = 本次是否闪光 */
    static boolean flashBit(String raw) {
        try {
            return (Integer.parseInt(raw.trim()) & 1) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** GPS：平台实现会替我们算好十进制度；没有定位信息时返回 null（不是 0,0） */
    private String location(ExifInterface exif) {
        float[] latlon = new float[2];
        try {
            if (!exif.getLatLong(latlon)) return null;
        } catch (Throwable t) {
            return null;
        }
        if (latlon[0] == 0f && latlon[1] == 0f) return null;
        return String.format(Locale.US, "%.6f, %.6f", latlon[0], latlon[1]);
    }
}
