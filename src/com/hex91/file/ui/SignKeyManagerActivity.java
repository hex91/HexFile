package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.utils.KeyCipher;
import com.hex91.file.apk.KeyStoreUtil;
import com.hex91.file.apk.KeystoreCreate;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * v1.67.0 签名管理：
 *  - 导入 keystore（.p12/.pfx/.jks/.keystore/.bks）到应用内部私有目录（导入一次永久可用，
 *    密码一并保存，签名时不再从文件加载、不用重复输密码）
 *  - 查看签名信息：别名、证书主题/签发者/有效期/算法/SHA-1/SHA-256 指纹
 *  - 管理：设为默认签名密钥（Dex 编辑器自动签名、自定义签名入口直接用）/ 删除
 */
public class SignKeyManagerActivity extends BaseActivity {

    private static final int REQ_PICK_KS = 4201;

    /** 一条已导入的签名 */
    public static class KeyEntry {
        public String id;
        public String name;
        public String storePass;
        public String keyPass;   // null = 同 keystore 密码
        public String alias;     // null = 自动选择
        public String sha1;      // 首个私钥条目证书指纹（列表展示用）
        public int keyCount;     // 含私钥的别名数
        /** v1.67.3：密码解不开（换机 / Keystore 被重置 / 数据被拷走）——必须重新导入，不能用空密码覆盖 */
        public boolean storeBroken;
        public boolean keyBroken;

        public boolean broken() { return storeBroken || keyBroken; }
    }

    private final List<KeyEntry> entries = new ArrayList<KeyEntry>();
    private final android.os.Handler ui = new android.os.Handler();
    private ListView listView;
    private KeyAdapter adapter;
    private TextView emptyView;

    /** 签名设置专用 SharedPreferences（与 DexEditorActivity 共用） */
    public static SharedPreferences signPrefs(android.content.Context ctx) {
        return ctx.getSharedPreferences("dexpp_sign", MODE_PRIVATE);
    }

    private static File keyDir(android.content.Context ctx) {
        File d = new File(ctx.getFilesDir(), "signkeys");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    // ---------------- 存储（id.ks + id.meta） ----------------

    static void saveEntry(android.content.Context ctx, KeyEntry e, byte[] ksBytes) throws Exception {
        // v1.67.3：密码损坏时绝不允许落盘——否则空串会把原密码不可逆覆盖掉
        if (e.broken()) throw new IllegalStateException(
                "密码已损坏（可能换机或清除过数据），请删除该条目后重新导入签名文件");
        File dir = keyDir(ctx);
        FileOutputStream fo = new FileOutputStream(new File(dir, e.id + ".ks"));
        try { fo.write(ksBytes); } finally { fo.close(); }
        Properties p = new Properties();
        p.setProperty("name", e.name);
        // v1.67.1：密码加密落盘（Android Keystore AES/GCM；21-22 降级明文）
        p.setProperty("storePass", KeyCipher.seal(e.storePass));
        if (e.keyPass != null) p.setProperty("keyPass", KeyCipher.seal(e.keyPass));
        if (e.alias != null) p.setProperty("alias", e.alias);
        if (e.sha1 != null) p.setProperty("sha1", e.sha1);
        p.setProperty("keyCount", String.valueOf(e.keyCount));
        FileOutputStream fo2 = new FileOutputStream(new File(dir, e.id + ".meta"));
        try { p.store(fo2, "HexFile sign key"); } finally { fo2.close(); }
    }

    static void deleteEntry(android.content.Context ctx, String id) {
        new File(keyDir(ctx), id + ".ks").delete();
        new File(keyDir(ctx), id + ".meta").delete();
    }

    /** 读回一条签名（含 keystore 字节），不存在返回 null */
    public static KeyEntry loadEntry(android.content.Context ctx, String id) {
        try {
            File meta = new File(keyDir(ctx), id + ".meta");
            File ks = new File(keyDir(ctx), id + ".ks");
            if (!meta.isFile() || !ks.isFile()) return null;
            Properties p = new Properties();
            FileInputStream fi = new FileInputStream(meta);
            try { p.load(fi); } finally { fi.close(); }
            KeyEntry e = new KeyEntry();
            e.id = id;
            e.name = p.getProperty("name", id);
            // v1.67.3：解不开（null）与「密码就是空」必须区分开，否则会静默用空密码签名/覆盖
            String sp = KeyCipher.open(p.getProperty("storePass", ""));
            e.storeBroken = sp == null;
            e.storePass = sp != null ? sp : "";
            String kp = p.getProperty("keyPass");
            if (kp != null) {
                String dk = KeyCipher.open(kp);
                e.keyBroken = dk == null;
                e.keyPass = dk;                       // null 表示「同 keystore 密码」
            }
            e.alias = p.getProperty("alias");
            e.sha1 = p.getProperty("sha1");
            e.keyCount = Integer.parseInt(p.getProperty("keyCount", "0"));
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    /** keystore 字节 */
    public static byte[] loadKeyBytes(android.content.Context ctx, String id) throws Exception {
        return readAll(new File(keyDir(ctx), id + ".ks"));
    }

    /** 当前默认签名的 KeyEntry；未设置或已失效返回 null */
    public static KeyEntry defaultEntry(android.content.Context ctx) {
        String id = signPrefs(ctx).getString("key_id", null);
        return id != null ? loadEntry(ctx, id) : null;
    }

    /** 已导入签名数量 */
    static int count(android.content.Context ctx) {
        File[] fs = keyDir(ctx).listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".meta")) n++;
        return n;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            fi.close();
        }
    }

    // ---------------- 页面 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));
        setContentView(root);

        root.addView(topBar());

        // v1.75.0：两个主要动作从「一行可点击的蓝字」改成图标卡片，可点范围与视觉都明确
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setPadding(dp(12), dp(12), dp(12), dp(2));
        root.addView(acts);

        View impCard = actionCard("📥", getString(R.string.ksm_import_title),
                ".p12 / .jks / .keystore", ThemeManager.accent(this),
                new View.OnClickListener() {
                    public void onClick(View v) { startPick(); }
                });
        View createCard = actionCard("✨", getString(R.string.ksm_create_title),
                getString(R.string.ksm_create_desc), ThemeManager.ok(this),
                new View.OnClickListener() {
                    public void onClick(View v) { askCreate(); }
                });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(6);
        acts.addView(impCard, lp1);
        acts.addView(createCard, lp2);

        TextView section = UiDecor.sectionTitle(this, getString(R.string.ksm_section_keys));
        section.setPadding(dp(16), dp(12), dp(16), dp(2));
        root.addView(section);

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setPadding(dp(12), dp(4), dp(12), dp(8));
        listView.setClipToPadding(false);
        listView.setBackgroundColor(ThemeManager.bg(this));

        emptyView = new TextView(this);
        emptyView.setText(getString(R.string.ksm_empty));
        emptyView.setTextSize(14);
        emptyView.setTextColor(ThemeManager.textSub(this));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), dp(28), dp(16), dp(16));
        root.addView(emptyView);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        adapter = new KeyAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                showDetail(entries.get(pos));
            }
        });
        // v1.68.0：长按 = 增删改查的「改/删/设为默认」入口
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                showItemActions(entries.get(pos));
                return true;
            }
        });
    }

    // ---------------- 视觉组件（v1.75.0） ----------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 顶栏：返回 + 标题 + 副标题（当前签名数量） */
    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ThemeManager.topBar(this));
        bar.setPadding(dp(6), 0, dp(12), 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        bar.setLayoutParams(blp);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setGravity(Gravity.CENTER);
        back.setTextColor(ThemeManager.topBarText(this));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(4), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText(R.string.ksm_title);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ThemeManager.topBarText(this));
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.ksm_subtitle);
        sub.setTextSize(11);
        sub.setTextColor(ThemeManager.topBarTextSub(this));
        texts.addView(sub);
        bar.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    /** 图标操作卡片：左侧图标 + 标题 + 说明，整卡可点 */
    private View actionCard(String icon, String title, String desc, int color,
                            View.OnClickListener l) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(UiDecor.pressable(
                UiDecor.round(this, UiDecor.alpha(color, 0x1C), UiDecor.alpha(color, 0x55),
                        0.8f, 12f), color));
        card.setOnClickListener(l);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(19);
        ic.setGravity(Gravity.CENTER);
        card.addView(ic, new LinearLayout.LayoutParams(dp(30),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(14);
        t1.setTextColor(ThemeManager.text(this));
        texts.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextSize(10.5f);
        t2.setTextColor(ThemeManager.textSub(this));
        texts.addView(t2);
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    /** 操作格：竖向「图标 + 文字」，圆角半透明底，用于长按面板的网格 */
    private View actionCell(String icon, String label, int color, View.OnClickListener l) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(8), dp(12), dp(8), dp(12));
        cell.setBackground(UiDecor.pressable(
                UiDecor.round(this, UiDecor.alpha(color, 0x1A), UiDecor.alpha(color, 0x4D),
                        0.8f, 12f), color));
        cell.setOnClickListener(l);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        ic.setTextColor(color);
        cell.addView(ic);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(ThemeManager.text(this));
        tv.setPadding(0, dp(6), 0, 0);
        cell.addView(tv);
        return cell;
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    /**
     * v1.67.3：解密要走 Android Keystore（首次生成密钥可能走 TEE/StrongBox，耗时不可控），
     * 全部放到后台线程，读完再回 UI —— 避免列表页在低端机/锁屏状态下卡住主线程。
     */
    private void reload() {
        new Thread(new Runnable() { public void run() {
            final List<KeyEntry> loaded = loadAll(SignKeyManagerActivity.this);
            ui.post(new Runnable() { public void run() {
                entries.clear();
                entries.addAll(loaded);
                emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
            }});
        }}, "signkey-load").start();
    }

    /** 枚举并解密全部已导入签名（含 Keystore 运算，须在后台线程调用） */
    static List<KeyEntry> loadAll(android.content.Context ctx) {
        List<KeyEntry> out = new ArrayList<KeyEntry>();
        File[] fs = keyDir(ctx).listFiles();
        if (fs == null) return out;
        String defId = signPrefs(ctx).getString("key_id", null);
        List<String> ids = new ArrayList<String>();
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".meta")) ids.add(n.substring(0, n.length() - 5));
        }
        java.util.Collections.sort(ids);
        // 默认密钥排最前
        if (defId != null && ids.remove(defId)) ids.add(0, defId);
        for (String id : ids) {
            KeyEntry e = loadEntry(ctx, id);
            if (e != null) out.add(e);
        }
        return out;
    }

    private class KeyAdapter extends BaseAdapter {
        public int getCount() { return entries.size(); }
        public Object getItem(int position) { return entries.get(position); }
        public long getItemId(int position) { return position; }

        /**
         * v1.75.0：列表项从「两行裸文字」改成圆角卡片 + 右侧箭头，
         * 默认密钥用强调色标题 + ★ 前缀区分，损坏条目用错误色提示。
         * 结构固定为 shell[ card[ top[t1, ›], t2 ] ]，因此可以安全复用 convertView。
         */
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout shell = (convertView instanceof LinearLayout)
                    ? (LinearLayout) convertView : newRowShell();
            KeyEntry e = entries.get(position);
            String defId = signPrefs(SignKeyManagerActivity.this).getString("key_id", null);
            boolean isDefault = e.id.equals(defId);

            LinearLayout card = (LinearLayout) shell.getChildAt(0);
            LinearLayout top = (LinearLayout) card.getChildAt(0);
            TextView t1 = (TextView) top.getChildAt(0);
            TextView t2 = (TextView) card.getChildAt(1);

            t1.setText((isDefault ? "★ " : "") + e.name);
            t1.setTextColor(e.broken() ? ThemeManager.err(SignKeyManagerActivity.this)
                    : (isDefault ? ThemeManager.accent(SignKeyManagerActivity.this)
                    : ThemeManager.text(SignKeyManagerActivity.this)));
            // i18n：把「别名 + 别名数 + SHA1」拆成三段可翻译片段，不再用「中文片段 + 变量」硬拼
            String aliasPart = e.alias != null
                    ? getString(R.string.ksm_alias_prefix, e.alias) : "";
            String countPart = getResources().getQuantityString(
                    R.plurals.ksm_keys_count, e.keyCount, e.keyCount);
            t2.setText(e.broken()
                    ? getString(R.string.ksm_broken_short)
                    : aliasPart + countPart
                        + (e.sha1 != null && e.sha1.length() >= 19
                            ? " · SHA1 " + e.sha1.substring(0, 19) + "…" : ""));
            t2.setTextColor(e.broken() ? ThemeManager.err(SignKeyManagerActivity.this)
                    : ThemeManager.textSub(SignKeyManagerActivity.this));
            return shell;
        }

        private LinearLayout newRowShell() {
            LinearLayout shell = new LinearLayout(SignKeyManagerActivity.this);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(0, 0, 0, dp(8));

            LinearLayout card = new LinearLayout(SignKeyManagerActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(UiDecor.cardBg(SignKeyManagerActivity.this));

            LinearLayout top = new LinearLayout(SignKeyManagerActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView t1 = new TextView(SignKeyManagerActivity.this);
            t1.setTextSize(15);
            t1.setSingleLine(true);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(t1, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView chevron = new TextView(SignKeyManagerActivity.this);
            chevron.setText("›");
            chevron.setTextSize(20);
            chevron.setTextColor(UiDecor.alpha(
                    ThemeManager.textSub(SignKeyManagerActivity.this), 0xCC));
            chevron.setPadding(dp(6), 0, 0, 0);
            top.addView(chevron);

            card.addView(top);

            TextView t2 = new TextView(SignKeyManagerActivity.this);
            t2.setTextSize(11.5f);
            t2.setPadding(0, dp(3), 0, 0);
            card.addView(t2);

            shell.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return shell;
        }
    }

    // ---------------- 导入 ----------------

    private void startPick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_KS);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.ksm_err_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_KS && res == RESULT_OK && data != null && data.getData() != null)
            importFromUri(data.getData());
    }

    /** 读 Uri 字节（后台）→ 弹导入对话框 */
    private void importFromUri(final Uri uri) {
        final ProgressDialog pd = ProgressDialog.show(this,
                getString(R.string.ksm_import_title), getString(R.string.ksm_reading),
                true, false);
        new Thread() { public void run() {
            byte[] bytes = null;
            String err = null;
            try { bytes = readUri(uri); } catch (Throwable t) { err = t.getMessage(); }
            final byte[] fb = bytes;
            final String fe = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (fb == null) {
                    Toast.makeText(SignKeyManagerActivity.this,
                            getString(R.string.ksm_err_read, fe),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                promptImport(SignKeyManagerActivity.this, fb, uriName(uri),
                        new Runnable() { public void run() { reload(); } });
            }});
        }}.start();
    }

    /**
     * v1.68.0：供文件管理器直接调用——弹出「导入签名」对话框（命名 + 密码 → 校验 → 存入内部）。
     * 导入完成后 onDone 在主线程回调（不需要可传 null）。
     */
    public static void promptImport(final android.app.Activity act, final byte[] ksBytes,
            final String defaultName, final Runnable onDone) {
        if (act == null || act.isFinishing() || ksBytes == null) return;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        float den = act.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * den);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(act);
        name.setHint(R.string.ksm_hint_name);
        name.setText(defaultName == null ? "" : defaultName);
        box.addView(name);
        final EditText storePass = new EditText(act);
        storePass.setHint(R.string.ksm_hint_store_pass);
        storePass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(storePass);
        final EditText keyPass = new EditText(act);
        keyPass.setHint(R.string.ksm_hint_key_pass);
        keyPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(keyPass);
        TextView tip = new TextView(act);
        tip.setText(act.getString(R.string.ksm_pass_tip, act.getString(KeyCipher.encrypted()
                ? R.string.ksm_enc_on : R.string.ksm_enc_off)));
        tip.setTextSize(12);
        tip.setTextColor(ThemeManager.textSub(act));
        tip.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(tip);

        new HfDialogBuilder(act)
                .setTitle(R.string.ksm_import_title)
                .setView(box)
                .setPositiveButton(R.string.ksm_import_action, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doImport(act, ksBytes, name.getText().toString().trim(),
                                storePass.getText().toString().toCharArray(),
                                keyPass.getText().toString(), onDone);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 导入实现（校验 → 落盘 → 首个自动设默认），结束后主线程回调 onDone */
    static void doImport(final android.app.Activity act, final byte[] ksBytes, final String name,
            final char[] storePass, final String keyPassStr, final Runnable onDone) {
        final ProgressDialog pd = ProgressDialog.show(act,
                act.getString(R.string.ksm_import_title), act.getString(R.string.ksm_parsing_ks),
                true, false);
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread() { public void run() {
            String err = null;
            KeyStoreUtil.AliasInfo first = null;
            int keyCount = 0;
            try {
                List<KeyStoreUtil.AliasInfo> list =
                        KeyStoreUtil.inspect(ksBytes, storePass,
                                keyPassStr.length() > 0 ? keyPassStr.toCharArray() : null);
                for (KeyStoreUtil.AliasInfo i : list) if (i.keyEntry) keyCount++;
                if (keyCount > 0) {
                    String signable = KeyStoreUtil.firstSignableAlias(list);
                    for (KeyStoreUtil.AliasInfo i : list)
                        if (i.alias != null && i.alias.equals(signable)) first = i;
                }
                if (keyCount == 0) err = act.getString(R.string.ksm_err_no_key);
            } catch (Throwable t) {
                err = friendlyKsError(act, t);
            }
            final String ferr = err;
            final int fcount = keyCount;
            final KeyEntry fe = new KeyEntry();
            fe.id = UUID.randomUUID().toString();
            fe.name = name != null && name.length() > 0 ? name
                    : act.getString(R.string.ksm_auto_name, count(act) + 1);
            fe.storePass = new String(storePass);
            fe.keyPass = keyPassStr.length() > 0 ? keyPassStr : null;
            fe.keyCount = keyCount;
            fe.sha1 = first != null ? first.sha1 : null;
            h.post(new Runnable() { public void run() {
                pd.dismiss();
                if (ferr != null) {
                    new HfDialogBuilder(act)
                            .setTitle(R.string.ksm_import_failed).setMessage(ferr)
                            .setPositiveButton(R.string.common_got_it, null).show();
                    return;
                }
                try {
                    saveEntry(act, fe, ksBytes);
                } catch (Throwable t) {
                    Toast.makeText(act, act.getString(R.string.ksm_err_save, t.getMessage()),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                // 第一条导入的签名自动设为默认
                if (signPrefs(act).getString("key_id", null) == null) {
                    signPrefs(act).edit().putString("key_id", fe.id).putInt("ks_mode", 1).apply();
                    Toast.makeText(act, R.string.ksm_imported_default, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(act, act.getResources().getQuantityString(
                            R.plurals.ksm_imported_count, fcount, fcount),
                            Toast.LENGTH_LONG).show();
                }
                if (onDone != null) onDone.run();
            }});
        }}.start();
    }

    /** keystore 相关异常转人话 */
    public static String friendlyKsError(android.content.Context ctx, Throwable t) {
        String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        String s = String.valueOf(m);
        if (s.contains("password") || s.contains("Password")
                || s.contains("given final block") || s.contains("Integrity check"))
            return ctx.getString(R.string.ksm_err_bad_pass);
        return s;
    }

    // ---------------- 创建 / 重命名（v1.68.0） ----------------

    /** 创建签名：CN/组织/别名/密码/密钥长度/有效期 → 自签 RSA → 存入签名管理 */
    private void askCreate() {
        float den = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * den);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        final EditText name = new EditText(this);
        name.setHint(R.string.ksm_hint_name2);
        name.setText(getString(R.string.ksm_default_name, count(this) + 1));
        box.addView(label(getString(R.string.ksm_label_name)));
        box.addView(name);

        final EditText cn = new EditText(this);
        cn.setHint(R.string.ksm_hint_cn);
        cn.setText("HexFile Signing " + (count(this) + 1));
        box.addView(label(getString(R.string.ksm_label_cn)));
        box.addView(cn);

        final EditText org = new EditText(this);
        org.setHint(R.string.ksm_hint_org);
        org.setText("HexFile");
        box.addView(label(getString(R.string.ksm_label_org)));
        box.addView(org);

        final EditText alias = new EditText(this);
        alias.setHint(R.string.ksm_hint_alias);
        alias.setText("hexfile");
        box.addView(label(getString(R.string.ksm_label_alias)));
        box.addView(alias);

        final EditText pass = new EditText(this);
        pass.setHint(R.string.ksm_hint_pass);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(label(getString(R.string.ksm_label_pass)));
        box.addView(pass);

        final int[] bits = {2048};
        final int[] years = {30};
        TextView bitTv = new TextView(this);
        bitTv.setText(R.string.ksm_bits_line_default);
        bitTv.setTextSize(14);
        bitTv.setTextColor(ThemeManager.text(this));
        bitTv.setPadding(0, (int) (12 * den), 0, 0);
        bitTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new HfDialogBuilder(SignKeyManagerActivity.this)
                        .setTitle(R.string.ksm_bits_title)
                        .setItems(new String[] { getString(R.string.ksm_bits_2048),
                                        getString(R.string.ksm_bits_3072),
                                        getString(R.string.ksm_bits_4096) },
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        bits[0] = w == 0 ? 2048 : (w == 1 ? 3072 : 4096);
                                        ((TextView) v).setText(
                                                getString(R.string.ksm_bits_line, bits[0]));
                                    }
                                }).show();
            }
        });
        box.addView(bitTv);

        TextView yearTv = new TextView(this);
        yearTv.setText(R.string.ksm_years_line_default);
        yearTv.setTextSize(14);
        yearTv.setTextColor(ThemeManager.text(this));
        yearTv.setPadding(0, (int) (8 * den), 0, (int) (8 * den));
        yearTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new HfDialogBuilder(SignKeyManagerActivity.this)
                        .setTitle(R.string.ksm_years_title)
                        .setItems(new String[] { getString(R.string.ksm_years_1),
                                        getString(R.string.ksm_years_10),
                                        getString(R.string.ksm_years_30),
                                        getString(R.string.ksm_years_50) },
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        years[0] = w == 0 ? 1 : (w == 1 ? 10 : (w == 2 ? 30 : 50));
                                        ((TextView) v).setText(
                                                getString(R.string.ksm_years_line, years[0]));
                                    }
                                }).show();
            }
        });
        box.addView(yearTv);

        TextView tip = new TextView(this);
        tip.setText(R.string.ksm_create_tip);
        tip.setTextSize(12);
        tip.setTextColor(ThemeManager.textSub(this));
        box.addView(tip);

        new HfDialogBuilder(this)
                .setTitle(R.string.ksm_create_title)
                .setView(sv)
                .setPositiveButton(R.string.ksm_create_action, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        String c = cn.getText().toString().trim();
                        String o = org.getText().toString().trim();
                        String a = alias.getText().toString().trim();
                        String p = pass.getText().toString();
                        if (n.length() == 0)
                            n = getString(R.string.ksm_auto_name, count(SignKeyManagerActivity.this) + 1);
                        if (c.length() == 0) c = n;
                        if (p.length() < 6) {
                            Toast.makeText(SignKeyManagerActivity.this,
                                    R.string.ksm_err_pass_short, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        doCreate(n, c, o, a, p, bits[0], years[0] * 365);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void doCreate(final String name, final String cn, final String org,
            final String alias, final String pass, final int bits, final int days) {
        final ProgressDialog pd = ProgressDialog.show(this,
                getString(R.string.ksm_create_title),
                getString(R.string.ksm_generating, bits), true, false);
        new Thread() { public void run() {
            String err = null;
            KeystoreCreate.Result r = null;
            try {
                r = KeystoreCreate.create(cn, org, "CN", alias, pass, bits, days);
            } catch (Throwable t) {
                err = friendlyKsError(SignKeyManagerActivity.this, t);
            }
            final String ferr = err;
            final KeystoreCreate.Result fr = r;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (ferr != null || fr == null) {
                    new HfDialogBuilder(SignKeyManagerActivity.this)
                            .setTitle(R.string.ksm_create_failed).setMessage(String.valueOf(ferr))
                            .setPositiveButton(R.string.common_got_it, null).show();
                    return;
                }
                KeyEntry e = new KeyEntry();
                e.id = UUID.randomUUID().toString();
                e.name = name;
                e.storePass = fr.storePass;
                e.keyPass = null;                 // 同一密码
                e.alias = fr.alias;
                e.sha1 = fr.sha1;
                e.keyCount = 1;
                try {
                    saveEntry(SignKeyManagerActivity.this, e, fr.store);
                } catch (Throwable t) {
                    Toast.makeText(SignKeyManagerActivity.this,
                            getString(R.string.ksm_err_save, t.getMessage()),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (signPrefs(SignKeyManagerActivity.this).getString("key_id", null) == null) {
                    signPrefs(SignKeyManagerActivity.this).edit()
                            .putString("key_id", e.id).putInt("ks_mode", 1).apply();
                }
                reload();
                new HfDialogBuilder(SignKeyManagerActivity.this)
                        .setTitle(R.string.ksm_created)
                        .setMessage(getString(R.string.ksm_created_msg, fr.summary(),
                                fr.storePass))
                        .setPositiveButton(R.string.common_got_it, null)
                        .show();
            }});
        }}.start();
    }

    /**
     * 长按条目：查看 / 重命名 / 设为默认 / 删除。
     *
     * <p>v1.75.0 从 {@code AlertDialog.setItems} 的纯文字列表改成 2×2 图标网格：
     * 原来四个操作挤在系统列表里，既看不出哪些是危险操作，也没有任何视觉锚点。</p>
     */
    private void showItemActions(final KeyEntry e) {
        if (e.broken()) {
            showDetail(e);      // 密码损坏时只能删除重导
            return;
        }
        final String defId = signPrefs(this).getString("key_id", null);
        final boolean isDefault = e.id.equals(defId);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), dp(2));

        TextView title = new TextView(this);
        title.setText(e.name);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ThemeManager.text(this));
        box.addView(title);

        TextView sub = new TextView(this);
        // i18n：状态词与「别名 / 别名数」分开取，中间的分隔点用字面量（不是文案）
        String defPart = getString(isDefault
                ? R.string.ksm_default_current : R.string.ksm_default_none);
        String tailPart = e.alias != null ? e.alias
                : getResources().getQuantityString(R.plurals.ksm_keys_count,
                        e.keyCount, e.keyCount);
        sub.setText(defPart + " · " + tailPart
                + (e.sha1 != null && e.sha1.length() >= 19
                    ? "\nSHA1 " + e.sha1.substring(0, 19) + "…" : ""));
        sub.setTextSize(11);
        sub.setTextColor(ThemeManager.textSub(this));
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row2.setLayoutParams(rlp);

        final AlertDialog[] holder = new AlertDialog[1];

        View viewCell = actionCell("🔍", getString(R.string.ksm_view_detail), ThemeManager.accent(this),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (holder[0] != null) holder[0].dismiss();
                        showDetail(e);
                    }
                });
        View renameCell = actionCell("✏️", getString(R.string.ksm_rename), ThemeManager.accent(this),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (holder[0] != null) holder[0].dismiss();
                        askRename(e);
                    }
                });
        View defCell = actionCell(isDefault ? "★" : "☆",
                getString(isDefault ? R.string.ksm_is_default : R.string.ksm_set_default), ThemeManager.ok(this),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (holder[0] != null) holder[0].dismiss();
                        if (isDefault) {
                            Toast.makeText(SignKeyManagerActivity.this,
                                    R.string.ksm_already_default_toast, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        signPrefs(SignKeyManagerActivity.this).edit()
                                .putString("key_id", e.id).putInt("ks_mode", 1).apply();
                        Toast.makeText(SignKeyManagerActivity.this,
                                R.string.ksm_set_default_toast, Toast.LENGTH_SHORT).show();
                        reload();
                    }
                });
        View delCell = actionCell("🗑", getString(R.string.common_delete), ThemeManager.err(this),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (holder[0] != null) holder[0].dismiss();
                        confirmDelete(e);
                    }
                });

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams halfL = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(5);
        halfL.leftMargin = dp(5);
        row1.addView(viewCell, half);
        row1.addView(renameCell, halfL);
        row2.addView(defCell, half);
        row2.addView(delCell, halfL);
        box.addView(row1);
        box.addView(row2);

        TextView tip = new TextView(this);
        tip.setText(R.string.ksm_tip_default_usage);
        tip.setTextSize(10.5f);
        tip.setTextColor(ThemeManager.textSub(this));
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(12), 0, 0);
        box.addView(tip);

        holder[0] = new HfDialogBuilder(this)
                .setView(box)
                .setNegativeButton(R.string.common_close, null)
                .create();
        holder[0].show();
    }

    private void askRename(final KeyEntry e) {
        final EditText et = new EditText(this);
        et.setText(e.name);
        et.setSelection(et.getText().length());
        new HfDialogBuilder(this)
                .setTitle(R.string.ksm_rename_title)
                .setView(et)
                .setPositiveButton(R.string.common_save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String n = et.getText().toString().trim();
                        if (n.length() == 0) {
                            Toast.makeText(SignKeyManagerActivity.this,
                                    R.string.ksm_err_name_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new Thread() { public void run() {
                            String err = null;
                            try {
                                byte[] bytes = loadKeyBytes(SignKeyManagerActivity.this, e.id);
                                e.name = n;
                                saveEntry(SignKeyManagerActivity.this, e, bytes);
                            } catch (Throwable t) {
                                err = t.getMessage();
                            }
                            final String ferr = err;
                            ui.post(new Runnable() { public void run() {
                                if (ferr != null) {
                                    Toast.makeText(SignKeyManagerActivity.this,
                                            getString(R.string.ksm_err_rename, ferr),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    reload();
                                }
                            }});
                        }}.start();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(ThemeManager.accent(this));
        tv.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        return tv;
    }

    // ---------------- 详情 / 默认 / 删除 ----------------

    private void showDetail(final KeyEntry e) {
        // v1.67.3：密码解不开时不再拿空密码去解析（只会得到一个无意义的报错），直接给可操作提示
        if (e.broken()) {
            new HfDialogBuilder(this)
                    .setTitle(e.name)
                    .setMessage(R.string.ksm_broken_detail)
                    .setPositiveButton(R.string.ksm_delete_reimport, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { confirmDelete(e); }
                    })
                    .setNegativeButton(R.string.common_close, null)
                    .show();
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(this,
                getString(R.string.ksm_info_title), getString(R.string.ksm_parsing), true, false);
        new Thread() { public void run() {
            String text;
            try {
                byte[] bytes = loadKeyBytes(SignKeyManagerActivity.this, e.id);
                List<KeyStoreUtil.AliasInfo> list = KeyStoreUtil.inspect(bytes,
                        e.storePass.toCharArray(),
                        e.keyPass != null ? e.keyPass.toCharArray() : null);
                StringBuilder sb = new StringBuilder();
                sb.append(getString(R.string.ksm_info_name, e.name));
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(list.get(i).detail());
                }
                text = sb.toString();
            } catch (Throwable t) {
                text = getString(R.string.ksm_err_parse, friendlyKsError(SignKeyManagerActivity.this, t));
            }
            final String ftext = text;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                android.widget.ScrollView sv = new android.widget.ScrollView(SignKeyManagerActivity.this);
                TextView tv = new TextView(SignKeyManagerActivity.this);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                sv.setPadding(pad, pad / 2, pad, 0);
                tv.setText(ftext);
                tv.setTextIsSelectable(true);
                tv.setTextSize(13);
                tv.setTextColor(ThemeManager.text(SignKeyManagerActivity.this));
                sv.addView(tv);
                String defId = signPrefs(SignKeyManagerActivity.this).getString("key_id", null);
                new HfDialogBuilder(SignKeyManagerActivity.this)
                        .setTitle(R.string.ksm_info_title)
                        .setView(sv)
                        .setPositiveButton(e.id.equals(defId)
                                ? R.string.ksm_is_default_sig : R.string.ksm_set_default_sig,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        signPrefs(SignKeyManagerActivity.this).edit()
                                                .putString("key_id", e.id)
                                                .putInt("ks_mode", 1).apply();
                                        Toast.makeText(SignKeyManagerActivity.this,
                                                R.string.ksm_set_default_toast, Toast.LENGTH_SHORT).show();
                                        reload();
                                    }
                                })
                        .setNegativeButton(R.string.common_delete, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { confirmDelete(e); }
                        })
                        .setNeutralButton(R.string.common_close, null)
                        .show();
            }});
        }}.start();
    }

    private void confirmDelete(final KeyEntry e) {
        new HfDialogBuilder(this)
                .setTitle(R.string.ksm_delete_title)
                .setMessage(getString(R.string.ksm_delete_msg, e.name))
                .setPositiveButton(R.string.common_delete, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        deleteEntry(SignKeyManagerActivity.this, e.id);
                        SharedPreferences sp = signPrefs(SignKeyManagerActivity.this);
                        if (e.id.equals(sp.getString("key_id", null)))
                            sp.edit().remove("key_id").putInt("ks_mode", 0).apply();
                        Toast.makeText(SignKeyManagerActivity.this, R.string.ksm_deleted,
                                Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private String uriName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst() && c.getString(idx) != null)
                        return c.getString(idx);
                } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        String s = uri.getLastPathSegment();
        return s != null ? s : getString(R.string.ksm_ks_file_fallback);
    }

    private byte[] readUri(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
