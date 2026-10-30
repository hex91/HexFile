package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.56.0 网络存储：SMB / WebDAV 客户端，手机直连电脑共享。
 *
 * <p>SMB 走 {@link SmbFs}（smbj 0.14.0，SMB2/SMB3），WebDAV 走 {@link WebDavFs}
 * （HttpURLConnection + Basic 认证），FTP 走 {@link FtpFs}（纯 Socket 零依赖），
 * SFTP 走 {@link SftpFs}（JSch SSH2）。服务器配置以 JSON 存于 SharedPreferences。</p>
 *
 * <p>功能：服务器增删改测、远程浏览、下载到本地（文件/目录递归）、
 * 上传本地文件（系统选择器）、新建目录、重命名、删除。</p>
 */
public final class NetStorageActivity extends BaseActivity {

    private static final String TAG = "NetStorage";
    private static final String PREFS = "netstorage";
    private static final String KEY_SERVERS = "servers";
    private static final int REQ_PICK_UPLOAD = 5001;

    private static final String TYPE_SMB = "smb";
    private static final String TYPE_WEBDAV = "webdav";
    private static final String TYPE_FTP = "ftp";        // v1.60.0
    private static final String TYPE_SFTP = "sftp";      // v1.60.0

    // ---- UI ----
    private View mServersPanel, mBrowsePanel;
    private LinearLayout mServerContainer;
    private TextView mTitle, mPath;
    private ListView mList;

    // ---- 状态 ----
    /** 服务器配置：JSONObject 列表（type/host/port/share/url/domain/user/pass） */
    private final List<org.json.JSONObject> mServers = new ArrayList<>();
    private NetFs mFs;
    private String mCur = "";                     // 当前远程目录（"" = 根）
    private final List<NetFs.NetEntry> mEntries = new ArrayList<>();
    private NetAdapter mAdapter;
    private org.json.JSONObject mCurServer;       // 当前连接的服务器配置
    private Uri mUploadUri;                       // 待上传文件（onActivityResult → 线程）
    private String mUploadName;

    private ProgressDialog mProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_net_storage);

        mServersPanel = findViewById(R.id.net_servers_panel);
        mBrowsePanel = findViewById(R.id.net_browse_panel);
        mServerContainer = findViewById(R.id.net_server_container);
        mTitle = findViewById(R.id.net_title);
        mPath = findViewById(R.id.net_path);
        mList = findViewById(R.id.net_list);
        mAdapter = new NetAdapter();
        mList.setAdapter(mAdapter);

        findViewById(R.id.net_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onBackPressed(); }
        });
        findViewById(R.id.net_add).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editServer(null); }
        });
        findViewById(R.id.net_up).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mFs == null) return;
                browse(NetFs.parentOf(mCur));
            }
        });
        findViewById(R.id.net_mkdir).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doMkdir(); }
        });
        findViewById(R.id.net_upload).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickUpload(); }
        });
        findViewById(R.id.net_disconnect).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { disconnect(); }
        });

        mList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                NetFs.NetEntry e = mEntries.get(pos);
                if (e.dir) browse(NetFs.join(mCur, e.name));
                else entryMenu(e);
            }
        });
        mList.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                entryMenu(mEntries.get(pos));
                return true;
            }
        });

        loadServers();
        showServers();
    }

    // =================================================================
    // 服务器列表（面板一）
    // =================================================================

    private void loadServers() {
        mServers.clear();
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVERS, "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) mServers.add(arr.getJSONObject(i));
        } catch (Exception e) {
            android.util.Log.e(TAG, "loadServers", e);
        }
    }

    private void saveServers() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (org.json.JSONObject o : mServers) arr.put(o);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVERS, arr.toString()).apply();
        } catch (Exception e) {
            android.util.Log.e(TAG, "saveServers", e);
        }
    }

    private void showServers() {
        mTitle.setText("网络存储");
        mServersPanel.setVisibility(View.VISIBLE);
        mBrowsePanel.setVisibility(View.GONE);
        mServerContainer.removeAllViews();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < mServers.size(); i++) {
            final org.json.JSONObject s = mServers.get(i);
            mServerContainer.addView(serverRow(s, serverLabel(s), serverSub(s)));
        }
        if (mServers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（还没有服务器，点下方「添加服务器」）");
            empty.setTextSize(13);
            empty.setTextColor(ThemeManager.textSub(this));
            empty.setPadding(dp(18), dp(24), dp(18), dp(8));
            mServerContainer.addView(empty);
        }
    }

    private String serverLabel(org.json.JSONObject s) {
        try {
            String t = s.optString("type");
            if (TYPE_WEBDAV.equals(t)) return "WebDAV · " + s.optString("url");
            if (TYPE_FTP.equals(t)) return "FTP · " + s.optString("host")
                    + (s.optInt("port", 21) == 21 ? "" : ":" + s.optInt("port"));
            if (TYPE_SFTP.equals(t)) return "SFTP · " + s.optString("host")
                    + (s.optInt("port", 22) == 22 ? "" : ":" + s.optInt("port"));
            return "SMB · " + s.optString("host") + (s.optInt("port", 445) == 445 ? "" : ":" + s.optInt("port")) + "/" + s.optString("share");
        } catch (Exception e) {
            return "（配置损坏）";
        }
    }

    private String serverSub(org.json.JSONObject s) {
        String u = s.optString("user");
        return (u == null || u.isEmpty()) ? "访客登录" : "用户: " + u;
    }

    private LinearLayout serverRow(final org.json.JSONObject s, String label, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        // attr 不能直接当资源 ID 用 → 先 resolve 成 drawable 资源
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
        } catch (Exception ignore) {}

        TextView t1 = new TextView(this);
        t1.setText(label);
        t1.setTextSize(15);
        t1.setTextColor(ThemeManager.text(this));
        t1.setSingleLine(true);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextSize(11);
        t2.setTextColor(ThemeManager.textSub(this));
        row.addView(t1);
        row.addView(t2);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { connectServer(s); }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { serverMenu(s); return true; }
        });
        return row;
    }

    private void serverMenu(final org.json.JSONObject s) {
        final int idx = mServers.indexOf(s);
        new AlertDialog.Builder(this)
                .setTitle(serverLabel(s))
                .setItems(new String[]{"连接", "编辑", "删除"}, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) connectServer(s);
                        else if (which == 1) editServer(s);
                        else if (which == 2 && idx >= 0) {
                            mServers.remove(idx);
                            saveServers();
                            showServers();
                            toast("已删除");
                        }
                    }
                })
                .show();
    }

    // ----------------------------------------------------------------
    // 添加 / 编辑服务器对话框（布局代码构建）
    // ----------------------------------------------------------------

    private void editServer(final org.json.JSONObject editing) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final Spinner type = addSpinnerRow(box, "类型",
                new String[]{"SMB (Windows 共享/NAS)", "WebDAV", "FTP", "SFTP (SSH)"});
        final EditText host = addEditRow(box, "地址（SMB/FTP/SFTP: IP或主机名 / WebDAV: 完整URL）", "");
        final EditText port = addEditRow(box, "端口", "445");
        final EditText share = addEditRow(box, "共享名（SMB，如 public）", "");
        final EditText domain = addEditRow(box, "域/工作组（SMB，可留空）", "");
        final EditText user = addEditRow(box, "用户名（FTP/SFTP 匿名/留空视协议而定）", "");
        final EditText pass = addEditRow(box, "密码", "");
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                // 0=SMB 1=WebDAV 2=FTP 3=SFTP
                boolean smb = pos == 0;
                boolean http = pos == 1;
                setRowVisible(port, !http);
                setRowVisible(share, smb);
                setRowVisible(domain, smb);
                host.setHint(smb ? "192.168.1.100"
                        : http ? "http://192.168.1.100:5005/dav/"
                        : "192.168.1.100");
                port.setText(smb ? "445" : http ? "" : pos == 2 ? "21" : "22");
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        if (editing != null) {
            String et = editing.optString("type");
            type.setSelection(TYPE_WEBDAV.equals(et) ? 1 : TYPE_FTP.equals(et) ? 2 : TYPE_SFTP.equals(et) ? 3 : 0);
            host.setText(editing.optString("host"));
            port.setText(String.valueOf(editing.optInt("port",
                    TYPE_FTP.equals(et) ? 21 : TYPE_SFTP.equals(et) ? 22 : 445)));
            share.setText(editing.optString("share"));
            domain.setText(editing.optString("domain"));
            user.setText(editing.optString("user"));
            pass.setText(editing.optString("pass"));
        }

        new AlertDialog.Builder(this)
                .setTitle(editing == null ? "添加服务器" : "编辑服务器")
                .setView(wrapScroll(box))
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int pos = type.getSelectedItemPosition(); // 0=SMB 1=WebDAV 2=FTP 3=SFTP
                            boolean smb = pos == 0, http = pos == 1;
                            String h = host.getText().toString().trim();
                            if (smb && h.isEmpty()) { toast("地址不能为空"); return; }
                            if (http && !h.startsWith("http")) { toast("WebDAV 地址需以 http(s):// 开头"); return; }
                            if (!http && h.isEmpty()) { toast("地址不能为空"); return; }
                            int defPort = smb ? 445 : pos == 2 ? 21 : pos == 3 ? 22 : 80;
                            org.json.JSONObject o = editing == null ? new org.json.JSONObject() : editing;
                            o.put("type", smb ? TYPE_SMB : http ? TYPE_WEBDAV : pos == 2 ? TYPE_FTP : TYPE_SFTP);
                            o.put("host", h);
                            o.put("port", parseInt(port.getText().toString(), defPort));
                            o.put("share", share.getText().toString().trim());
                            o.put("domain", domain.getText().toString().trim());
                            o.put("user", user.getText().toString().trim());
                            o.put("pass", pass.getText().toString());
                            if (editing == null) mServers.add(o);
                            saveServers();
                            showServers();
                        } catch (Exception e) {
                            toast("保存失败: " + e.getMessage());
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 对话框内容超出屏幕时包一层 ScrollView。 */
    private android.widget.ScrollView wrapScroll(LinearLayout box) {
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(box);
        return sc;
    }

    private TextView addLabel(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(ThemeManager.textSub(this));
        t.setPadding(0, dp(10), 0, dp(2));
        box.addView(t);
        return t;
    }

    private EditText addEditRow(LinearLayout box, String label, String value) {
        // 标签与输入框包进同一行容器，显隐时整体切换（只藏 EditText 会留下孤零零的标签）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        addLabel(row, label);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(14);
        row.addView(e);
        e.setTag(row);
        box.addView(row);
        return e;
    }

    /** 显示/隐藏一个输入行（含标签）。 */
    private void setRowVisible(EditText e, boolean visible) {
        Object tag = e.getTag();
        View target = tag instanceof View ? (View) tag : e;
        target.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private Spinner addSpinnerRow(LinearLayout box, String label, String[] items) {
        addLabel(box, label);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        box.addView(sp);
        return sp;
    }

    // =================================================================
    // 连接与浏览（面板二）
    // =================================================================

    private void connectServer(final org.json.JSONObject s) {
        showSpin("连接 " + serverLabel(s) + " …");
        bg(new Runnable() {
            public void run() {
                try {
                    NetFs fs;
                    String t = s.optString("type");
                    if (TYPE_WEBDAV.equals(t)) {
                        fs = new WebDavFs(s.optString("url").isEmpty() ? s.optString("host") : s.optString("url"),
                                s.optString("user"), s.optString("pass"));
                    } else if (TYPE_FTP.equals(t)) {
                        fs = FtpFs.connect(s.optString("host"), s.optInt("port", 21),
                                s.optString("user"), s.optString("pass"));
                    } else if (TYPE_SFTP.equals(t)) {
                        fs = SftpFs.connect(s.optString("host"), s.optInt("port", 22),
                                s.optString("user"), s.optString("pass"));
                    } else {
                        fs = SmbFs.connect(s.optString("host"), s.optInt("port", 445),
                                s.optString("share"), s.optString("domain"),
                                s.optString("user"), s.optString("pass"));
                    }
                    // 试列根目录，尽早在 UI 上报连接错误
                    fs.list("");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            if (mFs != null) mFs.close();
                            mFs = fs;
                            mCurServer = s;
                            browse("");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            errorDialog("连接失败", friendlyNetError(e));
                        }
                    });
                }
            }
        });
    }

    private void browse(final String path) {
        showSpin("加载 " + (path.isEmpty() ? "/" : path) + " …");
        bg(new Runnable() {
            public void run() {
                try {
                    final List<NetFs.NetEntry> list = mFs.list(path);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            mCur = path;
                            mEntries.clear();
                            mEntries.addAll(list);
                            mAdapter.notifyDataSetChanged();
                            // 进入浏览面板
                            mServersPanel.setVisibility(View.GONE);
                            mBrowsePanel.setVisibility(View.VISIBLE);
                            mTitle.setText(serverLabel(mCurServer));
                            mPath.setText("/" + mCur);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            errorDialog("加载失败", friendlyNetError(e));
                        }
                    });
                }
            }
        });
    }

    private void disconnect() {
        final NetFs fs = mFs;
        mFs = null;
        mCurServer = null;
        mEntries.clear();
        mAdapter.notifyDataSetChanged();
        showServers();
        // 断连可能涉及网络 I/O（FTP QUIT / SMB 树断开 / SFTP DISCONNECT），必须离开主线程
        if (fs != null) {
            bg(new Runnable() { public void run() { fs.close(); } });
        }
    }

    private void entryMenu(final NetFs.NetEntry e) {
        List<String> ops = new ArrayList<>();
        ops.add("下载到本地");
        ops.add("重命名");
        ops.add("删除");
        new AlertDialog.Builder(this)
                .setTitle((e.dir ? "📁 " : "📄 ") + e.name
                        + (e.dir ? "" : " · " + FileOps.formatSize(e.size)))
                .setItems(ops.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        String path = NetFs.join(mCur, e.name);
                        if (which == 0) download(e, path);
                        else if (which == 1) doRename(e, path);
                        else doDelete(e, path);
                    }
                })
                .show();
    }

    // ----------------------------------------------------------------
    // 下载
    // ----------------------------------------------------------------

    private void download(final NetFs.NetEntry e, final String path) {
        showSpin("准备下载 " + e.name + " …");
        bg(new Runnable() {
            public void run() {
                try {
                    // 统一落在 NetStorage/<条目名>：目录为递归根，文件为完整目标路径
                    File destDir = new File(android.os.Environment.getExternalStorageDirectory(),
                            "HexFile/NetStorage/" + e.name);
                    final File baseDir = destDir;
                    runOnUiThread(new Runnable() {
                        public void run() { mProgress.setMessage("下载中…"); }
                    });
                    if (e.dir) {
                        downloadDir(path, baseDir);
                        doneOnUiThread("目录已下载到 " + baseDir.getAbsolutePath());
                    } else {
                        baseDir.getParentFile().mkdirs();
                        final File dest = uniqueLocal(baseDir.getParentFile(), baseDir.getName());
                        copyRemote(mFs.openRead(path), dest, e.name, e.size);
                        doneOnUiThread("已下载到 " + dest.getAbsolutePath());
                    }
                } catch (final Exception ex) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            errorDialog("下载失败", friendlyNetError(ex));
                        }
                    });
                }
            }
        });
    }

    /** 递归下载目录。 */
    private void downloadDir(String remote, File local) throws IOException {
        local.mkdirs();
        for (NetFs.NetEntry c : mFs.list(remote)) {
            File child = new File(local, c.name);
            setMsgOnUiThread("下载 " + c.name);
            if (c.dir) downloadDir(NetFs.join(remote, c.name), child);
            else {
                child.getParentFile().mkdirs();
                copyRemote(mFs.openRead(NetFs.join(remote, c.name)), uniqueLocal(child.getParentFile(), child.getName()),
                        c.name, c.size);
            }
        }
    }

    /** 远程流 → 本地文件，带进度回调（已知 total 时用水平进度条）。 */
    private void copyRemote(InputStream in, File dest, final String name, long total) throws IOException {
        final ProgressDialog pd = mProgress;
        OutputStream out = new FileOutputStream(dest);
        long done = 0;
        try {
            if (pd != null && total > 0) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (mProgress != null && !isFinishing()) {
                            mProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                            mProgress.setMax((int) Math.min(total, Integer.MAX_VALUE));
                            mProgress.setProgress(0);
                        }
                    }
                });
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                final long d = done;
                if (pd != null && total > 0) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (mProgress != null && !isFinishing())
                                mProgress.setProgress((int) Math.min(d, Integer.MAX_VALUE));
                        }
                    });
                }
            }
        } finally {
            try { in.close(); } catch (IOException ignore) {}
            try { out.close(); } catch (IOException ignore) {}
        }
    }

    private static File uniqueLocal(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base, ext;
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        else { base = name; ext = ""; }
        for (int i = 1; i < 1000; i++) {
            File t = new File(dir, base + " (" + i + ")" + ext);
            if (!t.exists()) return t;
        }
        return f;
    }

    // ----------------------------------------------------------------
    // 上传
    // ----------------------------------------------------------------

    private void pickUpload() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_PICK_UPLOAD);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_UPLOAD || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        mUploadUri = data.getData();
        mUploadName = queryDisplayName(mUploadUri);
        if (mUploadName == null || mUploadName.isEmpty()) mUploadName = "upload_" + System.currentTimeMillis() + ".bin";
        String dest = NetFs.join(mCur, mUploadName);
        showSpin("上传 " + mUploadName + " …");
        bg(new Runnable() {
            public void run() {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(mUploadUri);
                    OutputStream out = mFs.openWrite(NetFs.join(mCur, mUploadName));
                    copyPlain(in, out, 64 * 1024);
                    doneOnUiThread("已上传到 " + NetFs.join(mCur, mUploadName));
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            errorDialog("上传失败", friendlyNetError(e));
                        }
                    });
                } finally {
                    try { if (in != null) in.close(); } catch (IOException ignore) {}
                }
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ----------------------------------------------------------------
    // 新建目录 / 重命名 / 删除
    // ----------------------------------------------------------------

    private void doMkdir() {
        final EditText e = inputDialog("新建目录", "目录名", "");
        new AlertDialog.Builder(this)
                .setTitle("新建目录")
                .setView(wrapScroll(inputBox(e)))
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String name = e.getText().toString().trim();
                        if (name.isEmpty()) return;
                        showSpin("创建目录 …");
                        bg(new Runnable() {
                            public void run() {
                                try {
                                    mFs.mkdir(NetFs.join(mCur, name));
                                    runOnUiThread(new Runnable() {
                                        public void run() { hideProgress(); browse(mCur); }
                                    });
                                } catch (final Exception ex) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            hideProgress();
                                            errorDialog("创建失败", friendlyNetError(ex));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 简易输入行容器（标签省略，仅 EditText）。 */
    private LinearLayout inputBox(EditText e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        return box;
    }

    private EditText inputDialog(String title, String label, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextSize(14);
        return e;
    }

    private void doRename(final NetFs.NetEntry en, final String path) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(en.name);
        e.setTextSize(14);
        LinearLayout box = inputBox(e);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(wrapScroll(box))
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String name = e.getText().toString().trim();
                        if (name.isEmpty() || name.equals(en.name)) return;
                        showSpin("重命名 …");
                        bg(new Runnable() {
                            public void run() {
                                try {
                                    mFs.rename(path, name);
                                    runOnUiThread(new Runnable() {
                                        public void run() { hideProgress(); browse(mCur); }
                                    });
                                } catch (final Exception ex) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            hideProgress();
                                            errorDialog("重命名失败", friendlyNetError(ex));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(final NetFs.NetEntry en, final String path) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + en.name)
                .setMessage(en.dir ? "目录及其全部内容将被删除，且不可恢复。" : "文件将被删除，且不可恢复。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        showSpin("删除 …");
                        bg(new Runnable() {
                            public void run() {
                                try {
                                    mFs.delete(path, en.dir);
                                    runOnUiThread(new Runnable() {
                                        public void run() { hideProgress(); browse(mCur); }
                                    });
                                } catch (final Exception ex) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            hideProgress();
                                            errorDialog("删除失败", friendlyNetError(ex));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // =================================================================
    // 通用工具
    // =================================================================

    /** 后台执行；未捕获异常兜底上报（正常路径各自 catch）。 */
    private void bg(final Runnable r) {
        new Thread(new Runnable() {
            public void run() {
                try { r.run(); } catch (Throwable t) {
                    android.util.Log.e(TAG, "bg", t);
                }
            }
        }, TAG).start();
    }

    private void doneOnUiThread(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                hideProgress();
                toast(msg);
                if (mFs != null && mBrowsePanel.getVisibility() == View.VISIBLE) browse(mCur);
            }
        });
    }

    private void setMsgOnUiThread(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() { if (mProgress != null && !isFinishing()) mProgress.setMessage(msg); }
        });
    }

    private void showSpin(String msg) {
        hideProgress();
        mProgress = new ProgressDialog(this);
        mProgress.setMessage(msg);
        mProgress.setIndeterminate(true);
        mProgress.setCancelable(false);
        mProgress.show();
    }

    private void hideProgress() {
        if (mProgress != null) {
            try { mProgress.dismiss(); } catch (Throwable ignore) {}
            mProgress = null;
        }
    }

    private void errorDialog(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 网络错误信息人性化。 */
    private static String friendlyNetError(Throwable e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (m.contains("ECONNREFUSED")) return "连接被拒绝：目标端口没有服务在监听（检查 IP/端口）";
        if (m.contains("ENETUNREACH") || m.contains("EHOSTUNREACH")) return "网络不可达：手机与设备是否在同一局域网？";
        if (m.contains("ETIMEDOUT") || m.contains("timeout") || m.contains("Timed out")) return "连接超时：检查 IP/端口，或设备防火墙设置";
        if (m.contains("Connection refused")) return m + "（检查服务是否开启）";
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static long copyPlain(InputStream in, OutputStream out, int bufSize) throws IOException {
        byte[] buf = new byte[bufSize];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        out.close();
        return total;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // =================================================================
    // 列表适配器
    // =================================================================

    private final class NetAdapter extends BaseAdapter {
        public int getCount() { return mEntries.size(); }
        public Object getItem(int position) { return mEntries.get(position); }
        public long getItemId(int position) { return position; }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_net_file, parent, false);
            }
            NetFs.NetEntry e = mEntries.get(position);
            ((TextView) v.findViewById(R.id.net_icon)).setText(e.dir ? "📁" : "📄");
            ((TextView) v.findViewById(R.id.net_name)).setText(e.name);
            String sub = e.dir ? "目录" : FileOps.formatSize(e.size);
            if (e.mtime > 0) {
                sub += " · " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(e.mtime));
            }
            ((TextView) v.findViewById(R.id.net_sub)).setText(sub);
            return v;
        }
    }

    // =================================================================
    // 生命周期
    // =================================================================

    @Override
    public void onBackPressed() {
        if (mFs != null) {
            disconnect(); // 浏览态 → 返回服务器列表（断开连接）
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        final NetFs fs = mFs;
        mFs = null;
        if (fs != null) {
            bg(new Runnable() { public void run() { fs.close(); } });
        }
        hideProgress();
    }
}
