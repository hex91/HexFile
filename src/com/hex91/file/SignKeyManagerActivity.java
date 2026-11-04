package com.hex91.file;

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
    static class KeyEntry {
        String id;
        String name;
        String storePass;
        String keyPass;   // null = 同 keystore 密码
        String alias;     // null = 自动选择
        String sha1;      // 首个私钥条目证书指纹（列表展示用）
        int keyCount;     // 含私钥的别名数
    }

    private final List<KeyEntry> entries = new ArrayList<KeyEntry>();
    private final android.os.Handler ui = new android.os.Handler();
    private ListView listView;
    private KeyAdapter adapter;
    private TextView emptyView;

    /** 签名设置专用 SharedPreferences（与 DexEditorActivity 共用） */
    static SharedPreferences signPrefs(android.content.Context ctx) {
        return ctx.getSharedPreferences("dexpp_sign", MODE_PRIVATE);
    }

    private static File keyDir(android.content.Context ctx) {
        File d = new File(ctx.getFilesDir(), "signkeys");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    // ---------------- 存储（id.ks + id.meta） ----------------

    static void saveEntry(android.content.Context ctx, KeyEntry e, byte[] ksBytes) throws Exception {
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
    static KeyEntry loadEntry(android.content.Context ctx, String id) {
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
            e.storePass = KeyCipher.open(p.getProperty("storePass", ""));
            e.keyPass = p.getProperty("keyPass");
            if (e.keyPass != null) e.keyPass = KeyCipher.open(e.keyPass);
            e.alias = p.getProperty("alias");
            e.sha1 = p.getProperty("sha1");
            e.keyCount = Integer.parseInt(p.getProperty("keyCount", "0"));
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    /** keystore 字节 */
    static byte[] loadKeyBytes(android.content.Context ctx, String id) throws Exception {
        return readAll(new File(keyDir(ctx), id + ".ks"));
    }

    /** 当前默认签名的 KeyEntry；未设置或已失效返回 null */
    static KeyEntry defaultEntry(android.content.Context ctx) {
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
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("签名管理");
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        title.setPadding(pad, pad, pad, pad / 2);
        root.addView(title);

        TextView imp = new TextView(this);
        imp.setText("＋ 导入签名文件（.p12 / .pfx / .jks / .keystore / .bks）");
        imp.setTextSize(15);
        imp.setTextColor(ThemeManager.accent(this));
        imp.setPadding(pad, pad / 2, pad, pad / 2);
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startPick(); }
        });
        root.addView(imp);

        listView = new ListView(this);
        emptyView = new TextView(this);
        emptyView.setText("还没有导入签名。\n\n导入后签名时会直接从列表选择，\n不再需要每次从文件加载。");
        emptyView.setTextSize(14);
        emptyView.setTextColor(ThemeManager.textSub(this));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(pad, pad * 3, pad, pad);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        entries.clear();
        File[] fs = keyDir(this).listFiles();
        String defId = signPrefs(this).getString("key_id", null);
        if (fs != null) {
            List<String> ids = new ArrayList<String>();
            for (File f : fs) {
                String n = f.getName();
                if (n.endsWith(".meta")) ids.add(n.substring(0, n.length() - 5));
            }
            java.util.Collections.sort(ids);
            // 默认密钥排最前
            if (defId != null && ids.remove(defId)) ids.add(0, defId);
            for (String id : ids) {
                KeyEntry e = loadEntry(this, id);
                if (e != null) entries.add(e);
            }
        }
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private class KeyAdapter extends BaseAdapter {
        public int getCount() { return entries.size(); }
        public Object getItem(int position) { return entries.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (convertView instanceof LinearLayout)
                    ? (LinearLayout) convertView : twoLineRow();
            KeyEntry e = entries.get(position);
            String defId = signPrefs(SignKeyManagerActivity.this).getString("key_id", null);
            TextView t1 = (TextView) row.getChildAt(0);
            TextView t2 = (TextView) row.getChildAt(1);
            t1.setText((e.id.equals(defId) ? "★ " : "") + e.name);
            t2.setText((e.alias != null ? "别名 " + e.alias + " · " : "")
                    + e.keyCount + " 个私钥别名"
                    + (e.sha1 != null && e.sha1.length() >= 19
                        ? " · SHA1 " + e.sha1.substring(0, 19) + "…" : ""));
            return row;
        }

        private LinearLayout twoLineRow() {
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            LinearLayout row = new LinearLayout(SignKeyManagerActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(pad, pad / 2, pad, pad / 2);
            TextView t1 = new TextView(SignKeyManagerActivity.this);
            t1.setTextSize(16);
            t1.setTextColor(ThemeManager.text(SignKeyManagerActivity.this));
            TextView t2 = new TextView(SignKeyManagerActivity.this);
            t2.setTextSize(13);
            t2.setTextColor(ThemeManager.textSub(SignKeyManagerActivity.this));
            row.addView(t1);
            row.addView(t2);
            return row;
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
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_KS && res == RESULT_OK && data != null && data.getData() != null)
            askImportInfo(data.getData());
    }

    /** 导入第 2 步：命名 + 密码 → 后台解析校验 */
    private void askImportInfo(final Uri uri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float den = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * den);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(this);
        name.setHint("名称（用于列表展示）");
        name.setText(uriName(uri));
        box.addView(name);
        final EditText storePass = new EditText(this);
        storePass.setHint("keystore 密码");
        storePass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(storePass);
        final EditText keyPass = new EditText(this);
        keyPass.setHint("私钥密码（可空 = 与 keystore 密码相同）");
        keyPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(keyPass);
        TextView tip = new TextView(this);
        tip.setText("导入时会解析并校验，成功后签名信息可在列表中查看。\n"
                + (KeyCipher.encrypted()
                    ? "密码以系统 Keystore 密钥加密保存（AES-256/GCM），仅本机本应用可解。"
                    : "⚠ 当前系统（Android 5.x）不支持 Keystore 加密，密码为明文存储，"
                      + "请勿在共用设备上导入。"));
        tip.setTextSize(12);
        tip.setTextColor(ThemeManager.textSub(this));
        tip.setPadding(0, (int) (8 * den), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("导入签名")
                .setView(box)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doImport(uri, name.getText().toString().trim(),
                                storePass.getText().toString().toCharArray(),
                                keyPass.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImport(final Uri uri, final String name,
            final char[] storePass, final String keyPassStr) {
        final ProgressDialog pd = ProgressDialog.show(this, "导入签名", "解析 keystore…", true, false);
        new Thread() { public void run() {
            String err = null;
            KeyStoreUtil.AliasInfo first = null;
            int keyCount = 0;
            byte[] ksBytes = null;
            try {
                ksBytes = readUri(uri);
                List<KeyStoreUtil.AliasInfo> list =
                        KeyStoreUtil.inspect(ksBytes, storePass,
                                keyPassStr.length() > 0 ? keyPassStr.toCharArray() : null);
                for (KeyStoreUtil.AliasInfo i : list) if (i.keyEntry) keyCount++;
                first = KeyStoreUtil.firstSignableAlias(list) != null ? list.get(0) : null;
                if (keyCount == 0) err = "keystore 中没有私钥条目（只有证书不能用于签名）";
            } catch (Throwable t) {
                err = friendlyKsError(t);
            }
            final String ferr = err;
            final byte[] fbytes = ksBytes;
            final int fcount = keyCount;
            final KeyEntry fe = new KeyEntry();
            fe.id = UUID.randomUUID().toString();
            fe.name = name.length() > 0 ? name : "签名 " + (count(SignKeyManagerActivity.this) + 1);
            fe.storePass = new String(storePass);
            fe.keyPass = keyPassStr.length() > 0 ? keyPassStr : null;
            fe.keyCount = keyCount;
            fe.sha1 = first != null ? first.sha1 : null;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (ferr != null) {
                    new AlertDialog.Builder(SignKeyManagerActivity.this)
                            .setTitle("导入失败").setMessage(ferr)
                            .setPositiveButton("知道了", null).show();
                    return;
                }
                try {
                    saveEntry(SignKeyManagerActivity.this, fe, fbytes);
                } catch (Throwable t) {
                    Toast.makeText(SignKeyManagerActivity.this,
                            "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    return;
                }
                // 第一条导入的签名自动设为默认
                if (signPrefs(SignKeyManagerActivity.this).getString("key_id", null) == null) {
                    signPrefs(SignKeyManagerActivity.this).edit()
                            .putString("key_id", fe.id).putInt("ks_mode", 1).apply();
                    Toast.makeText(SignKeyManagerActivity.this,
                            "已导入并设为默认签名密钥", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(SignKeyManagerActivity.this,
                            "已导入（" + fcount + " 个私钥别名）", Toast.LENGTH_LONG).show();
                }
                reload();
            }});
        }}.start();
    }

    /** keystore 相关异常转人话 */
    static String friendlyKsError(Throwable t) {
        String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        String s = String.valueOf(m);
        if (s.contains("password") || s.contains("Password")
                || s.contains("given final block") || s.contains("Integrity check"))
            return "密码错误或格式不支持（支持 .p12/.pfx/.jks/.keystore/.bks）";
        return s;
    }

    // ---------------- 详情 / 默认 / 删除 ----------------

    private void showDetail(final KeyEntry e) {
        final ProgressDialog pd = ProgressDialog.show(this, "签名信息", "解析中…", true, false);
        new Thread() { public void run() {
            String text;
            try {
                byte[] bytes = loadKeyBytes(SignKeyManagerActivity.this, e.id);
                List<KeyStoreUtil.AliasInfo> list = KeyStoreUtil.inspect(bytes,
                        e.storePass.toCharArray(),
                        e.keyPass != null ? e.keyPass.toCharArray() : null);
                StringBuilder sb = new StringBuilder();
                sb.append("名称: ").append(e.name).append('\n');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(list.get(i).detail());
                }
                text = sb.toString();
            } catch (Throwable t) {
                text = "解析失败: " + friendlyKsError(t);
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
                new AlertDialog.Builder(SignKeyManagerActivity.this)
                        .setTitle("签名信息")
                        .setView(sv)
                        .setPositiveButton(e.id.equals(defId) ? "已是默认签名" : "设为默认签名",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        signPrefs(SignKeyManagerActivity.this).edit()
                                                .putString("key_id", e.id)
                                                .putInt("ks_mode", 1).apply();
                                        Toast.makeText(SignKeyManagerActivity.this,
                                                "已设为默认签名密钥", Toast.LENGTH_SHORT).show();
                                        reload();
                                    }
                                })
                        .setNegativeButton("删除", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { confirmDelete(e); }
                        })
                        .setNeutralButton("关闭", null)
                        .show();
            }});
        }}.start();
    }

    private void confirmDelete(final KeyEntry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除签名")
                .setMessage("删除「" + e.name + "」？（只删除应用内导入的副本，不影响原文件）")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        deleteEntry(SignKeyManagerActivity.this, e.id);
                        SharedPreferences sp = signPrefs(SignKeyManagerActivity.this);
                        if (e.id.equals(sp.getString("key_id", null)))
                            sp.edit().remove("key_id").putInt("ks_mode", 0).apply();
                        Toast.makeText(SignKeyManagerActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
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
        return s != null ? s : "签名文件";
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
