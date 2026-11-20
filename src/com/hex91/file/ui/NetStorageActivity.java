package com.hex91.file.ui;

import com.hex91.file.utils.CredentialVault;
import com.hex91.file.utils.ResumePlan;
import com.hex91.file.R;
import com.hex91.file.net.AliyunDriveFs;
import com.hex91.file.net.FtpFs;
import com.hex91.file.net.LanScan;
import com.hex91.file.net.NetFs;
import com.hex91.file.net.OAuthClient;
import com.hex91.file.net.OAuthToken;
import com.hex91.file.net.OneDriveFs;
import com.hex91.file.net.S3Fs;
import com.hex91.file.net.SftpFs;
import com.hex91.file.net.SmbFs;
import com.hex91.file.net.WebDavFs;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.WorkDir;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
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
 * （HttpURLConnection + Basic 认证，明文 http 需 network_security_config 放行），
 * FTP 走 {@link FtpFs}（纯 Socket 零依赖），
 * SFTP 走 {@link SftpFs}（JSch SSH2）。服务器配置以 JSON 存于 SharedPreferences。</p>
 *
 * <p>v1.88.0 新增 S3 兼容对象存储（{@link S3Fs}，覆盖 OSS / COS / MinIO / AWS S3），
 * 并给下载/上传加上断点续传：文件先写 {@code .hpart}，按 {@link ResumePlan}
 * 校验远端 size/mtime 后从断点继续，传完校验长度再改名为正式文件。</p>
 *
 * <p>v1.89.0 新增两种云盘：OneDrive（{@link OneDriveFs}，Graph + 设备码授权）与
 * 阿里云盘（{@link AliyunDriveFs}，refresh_token）。两者都用 OAuth 凭据而不是
 * 账号密码，凭据经 {@link CredentialVault} 加密后与服务器配置一起保存，
 * 运行中刷新出的新令牌会回写（见 {@link #tokenSink}）。</p>
 *
 * <p>功能：服务器增删改测、远程浏览、下载到本地（文件/目录递归，支持续传）、
 * 上传本地文件（系统选择器，FTP/SFTP/SMB 支持断点追加）、新建目录、重命名、删除。</p>
 */
public final class NetStorageActivity extends BaseActivity {

    private static final String TAG = "NetStorage";
    private static final String PREFS = "netstorage";
    private static final String KEY_SERVERS = "servers";
    private static final int REQ_PICK_UPLOAD = 5001;
    private static final int REQ_CLOUD_LOGIN = 5002;   // v1.90.0 云盘网页登录

    private static final String TYPE_SMB = "smb";
    private static final String TYPE_WEBDAV = "webdav";
    private static final String TYPE_FTP = "ftp";        // v1.60.0
    private static final String TYPE_SFTP = "sftp";      // v1.60.0
    private static final String TYPE_S3 = "s3";          // v1.88.0 对象存储（OSS/COS/MinIO）
    private static final String TYPE_ONEDRIVE = "onedrive";  // v1.89.0 微软云盘（Graph + 设备码）
    private static final String TYPE_ALIYUN = "aliyun";      // v1.89.0 阿里云盘（refresh_token）

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
    /** v1.90.0：网页登录回来后把凭证交还给打开登录页的那次表单 */
    private CloudAuthDialog.Callback mAuthCb;

    private ProgressDialog mProgress;
    /** v1.83.0：不支持加密的设备只提示一次，避免每次保存都弹 */
    private static boolean warnedPlaintext;

    /** v2.14.0（F-20）：添加/编辑表单里的「扫描局域网」按钮（按类型显隐） */
    private Button scan;
    /** v2.14.0（F-20）：扫描取消标志；离开页面时置 true，后台线程下一次探测前退出 */
    private final java.util.concurrent.atomic.AtomicBoolean mScanStop =
            new java.util.concurrent.atomic.AtomicBoolean(true);

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

        CredentialVault.init(this);   // v1.83.0：密码加密（AndroidKeyStore）
        loadServers();
        migrateCredentials();
        showServers();
    }

    /**
     * v1.83.0：把旧版本留下的明文密码现场加密。
     * 只在能加密时改写，避免在极少数不支持 Keystore 的设备上把配置写坏。
     */
    private void migrateCredentials() {
        boolean changed = false;
        for (org.json.JSONObject o : mServers) {
            String p = o.optString("pass");
            if (p == null || p.isEmpty() || CredentialVault.isEncrypted(p)) continue;
            String enc = CredentialVault.encrypt(p);
            if (!CredentialVault.isEncrypted(enc)) continue;
            try {
                o.put("pass", enc);
                changed = true;
            } catch (Exception ignored) {
            }
        }
        if (changed) {
            saveServers();
            android.util.Log.i(TAG, "migrated plaintext credentials -> encrypted");
        }
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
        mTitle.setText(R.string.nst_title);
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
            empty.setText(R.string.nst_no_servers);
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
            if (TYPE_S3.equals(t)) return "S3 · " + stripScheme(s.optString("host")) + "/" + s.optString("share");
            if (TYPE_ONEDRIVE.equals(t)) return "OneDrive · " + (hasToken(s)
                    ? getString(R.string.nst_authorized) : getString(R.string.nst_unauthorized));
            if (TYPE_ALIYUN.equals(t)) return getString(R.string.nst_type_aliyun) + " · " + (hasToken(s)
                    ? getString(R.string.nst_authorized) : getString(R.string.nst_unauthorized));
            return "SMB · " + s.optString("host") + (s.optInt("port", 445) == 445 ? "" : ":" + s.optInt("port")) + "/" + s.optString("share");
        } catch (Exception e) {
            return getString(R.string.nst_cfg_broken);
        }
    }

    private String serverSub(org.json.JSONObject s) {
        String t = s.optString("type");
        if (TYPE_ONEDRIVE.equals(t) || TYPE_ALIYUN.equals(t)) {
            // 云盘没有「用户名/密码」，副标题用来显示授权状态，否则会显示成「访客登录」误导人
            String tok = s.optString("token");
            if (tok.isEmpty()) return getString(R.string.nst_sub_unauthorized);
            return getString(R.string.nst_sub_authorized)
                    + (CredentialVault.isEncrypted(tok) ? "  \uD83D\uDD12" : "");
        }
        String u = s.optString("user");
        String p = s.optString("pass");
        String lock = CredentialVault.isEncrypted(p) ? "  \uD83D\uDD12" : "";   // 🔒 密码已加密存储
        return ((u == null || u.isEmpty()) ? getString(R.string.nst_guest)
                : getString(R.string.nst_user_fmt, u)) + lock;
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
        new HfDialogBuilder(this)
                .setTitle(serverLabel(s))
                .setItems(new String[]{getString(R.string.nst_menu_connect),
                        getString(R.string.nst_menu_edit), getString(R.string.common_delete)},
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) connectServer(s);
                        else if (which == 1) editServer(s);
                        else if (which == 2 && idx >= 0) {
                            mServers.remove(idx);
                            saveServers();
                            showServers();
                            toast(getString(R.string.nst_deleted));
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

        final Spinner type = addSpinnerRow(box, getString(R.string.nst_field_type),
                getResources().getStringArray(R.array.nst_types));
        final EditText host = addEditRow(box, getString(R.string.nst_field_addr), "");
        final EditText port = addEditRow(box, getString(R.string.nst_field_port), "445");
        final EditText share = addEditRow(box, getString(R.string.nst_field_share), "");
        final EditText domain = addEditRow(box, getString(R.string.nst_field_domain), "");
        final EditText user = addEditRow(box, getString(R.string.nst_field_user), "");
        final EditText pass = addEditRow(box, getString(R.string.nst_field_pass), "");
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // v1.89.0：云盘用 OAuth，密码框在这里无意义；凭据（refresh_token）单独存，
        // 用 final 数组跨「授权回调 → 保存」传递（回调可能来自后台线程）
        final String[] token = {editing == null ? "" : CredentialVault.decrypt(editing.optString("token"))};
        final Button auth = new Button(this);
        auth.setText(R.string.nst_auth_btn);
        auth.setVisibility(View.GONE);
        box.addView(auth);
        auth.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final int pos = type.getSelectedItemPosition();
                if (pos != 5 && pos != 6) return;
                // 两条路都收敛到同一个回调：把 token 写进数组，等用户点保存
                final CloudAuthDialog.Callback cb = new CloudAuthDialog.Callback() {
                    public void onAuthorized(final String rt) {
                        // 回调可能在后台线程（设备码轮询/网络验证），回到主线程再改 UI
                        runOnUiThread(new Runnable() {
                            public void run() {
                                token[0] = rt;
                                toast(getString(R.string.nst_auth_ok));
                            }
                        });
                    }
                };
                if (pos == 5) {
                    // OneDrive：设备码，用户在系统浏览器里登录
                    CloudAuthDialog.show(NetStorageActivity.this, TYPE_ONEDRIVE,
                            user.getText().toString().trim(), token[0], cb);
                } else {
                    // 阿里云盘：直接在应用内弹官方登录页，登录完自动取回凭证
                    mAuthCb = cb;
                    startActivityForResult(CloudLoginActivity.intent(
                            NetStorageActivity.this, CloudLoginActivity.SITE_ALIYUN),
                            REQ_CLOUD_LOGIN);
                }
            }
        });

        // v2.14.0（F-20）：把「IP / 端口 / 协议」三样从路由器后台搬进一次点击
        scan = new Button(this);
        scan.setText(R.string.lan_scan_btn);
        box.addView(scan);
        scan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scanLan(type, host, port);
            }
        });

        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                // 0=SMB 1=WebDAV 2=FTP 3=SFTP 4=S3 5=OneDrive 6=阿里云盘
                boolean smb = pos == 0;
                boolean http = pos == 1;
                boolean s3 = pos == 4;
                boolean od = pos == 5;          // OneDrive：只要 Client ID + 授权
                boolean ali = pos == 6;         // 阿里云盘：只要授权（没有可填的账号密码）
                boolean cloud = od || ali;
                // v2.14.0（F-20）：扫描只管「填 IP 和端口」这一类的四种协议；
                // S3 的地址是云厂商给的域名、云盘压根不用地址，扫出来也没有可填的东西
                scan.setVisibility(pos <= 3 ? View.VISIBLE : View.GONE);
                setRowVisible(host, !cloud);
                setRowVisible(port, !http && !s3 && !cloud);
                setRowVisible(share, smb || s3);
                setRowVisible(domain, smb || s3);
                setRowVisible(user, !ali);
                setRowVisible(pass, !cloud);
                auth.setVisibility(cloud ? View.VISIBLE : View.GONE);
                auth.setText(ali ? getString(R.string.nst_auth_aliyun)
                        : getString(R.string.nst_auth_onedrive));
                host.setHint(smb ? "192.168.1.100"
                        : http ? "http://192.168.1.100:5005/dav/"
                        : s3 ? "https://oss-cn-hangzhou.aliyuncs.com"
                        : getString(R.string.nst_hint_cloud));
                if (!http && !s3 && !cloud) port.setText(smb ? "445" : pos == 2 ? "21" : "22");
                // 同一组输入框在不同类型下含义不同，标签要跟着换，否则「共享名」下面填 bucket 很容易填错
                setRowLabel(share, smb ? getString(R.string.nst_field_share)
                        : getString(R.string.nst_field_bucket));
                setRowLabel(domain, smb ? getString(R.string.nst_field_domain)
                        : getString(R.string.nst_field_region));
                setRowLabel(user, s3 ? getString(R.string.nst_field_accesskey)
                        : od ? getString(R.string.nst_field_clientid)
                        : getString(R.string.nst_field_user));
                setRowLabel(pass, s3 ? getString(R.string.nst_field_secretkey)
                        : getString(R.string.nst_field_pass));
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        if (editing != null) {
            String et = editing.optString("type");
            type.setSelection(TYPE_WEBDAV.equals(et) ? 1 : TYPE_FTP.equals(et) ? 2
                    : TYPE_SFTP.equals(et) ? 3 : TYPE_S3.equals(et) ? 4
                    : TYPE_ONEDRIVE.equals(et) ? 5 : TYPE_ALIYUN.equals(et) ? 6 : 0);
            host.setText(editing.optString("host"));
            port.setText(String.valueOf(editing.optInt("port",
                    TYPE_FTP.equals(et) ? 21 : TYPE_SFTP.equals(et) ? 22 : 445)));
            share.setText(editing.optString("share"));
            domain.setText(editing.optString("domain"));
            user.setText(editing.optString("user"));
            // v1.83.0：编辑框里放解密后的明文，保存时再加密（对旧明文同样适用）
            pass.setText(CredentialVault.decrypt(editing.optString("pass")));
        }

        new HfDialogBuilder(this)
                .setTitle(editing == null ? R.string.nst_add_title : R.string.nst_edit_title)
                .setView(wrapScroll(box))
                .setPositiveButton(R.string.common_save, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int pos = type.getSelectedItemPosition(); // 0=SMB 1=WebDAV 2=FTP 3=SFTP 4=S3 5=OneDrive 6=阿里云盘
                            boolean smb = pos == 0, http = pos == 1, s3 = pos == 4;
                            boolean od = pos == 5, ali = pos == 6, cloud = od || ali;
                            String h = host.getText().toString().trim();
                            if (smb && h.isEmpty()) { toast(getString(R.string.nst_err_addr_empty)); return; }
                            if (http && !h.startsWith("http")) { toast(getString(R.string.nst_err_addr_http)); return; }
                            if (!http && !s3 && !cloud && h.isEmpty()) {
                                toast(getString(R.string.nst_err_addr_empty)); return;
                            }
                            if (s3 && share.getText().toString().trim().isEmpty()) {
                                toast(getString(R.string.nst_err_bucket_empty)); return;
                            }
                            if (od && user.getText().toString().trim().isEmpty()) {
                                toast(getString(R.string.nst_err_clientid)); return;
                            }
                            if (cloud && token[0].isEmpty()) {
                                toast(getString(R.string.nst_err_no_auth)); return;
                            }
                            int defPort = smb ? 445 : pos == 2 ? 21 : pos == 3 ? 22 : 0;
                            org.json.JSONObject o = editing == null ? new org.json.JSONObject() : editing;
                            o.put("type", smb ? TYPE_SMB : http ? TYPE_WEBDAV
                                    : pos == 2 ? TYPE_FTP : pos == 3 ? TYPE_SFTP
                                    : s3 ? TYPE_S3 : od ? TYPE_ONEDRIVE : TYPE_ALIYUN);
                            o.put("host", h);
                            o.put("port", parseInt(port.getText().toString(), defPort));
                            o.put("share", share.getText().toString().trim());
                            o.put("domain", domain.getText().toString().trim());
                            o.put("user", user.getText().toString().trim());
                            // v1.83.0：密码加密后落盘；设备不支持时退回明文并提示一次
                            String rawPass = pass.getText().toString();
                            o.put("pass", CredentialVault.encrypt(rawPass));
                            o.put("token", CredentialVault.encrypt(cloud ? token[0] : ""));
                            if (!rawPass.isEmpty() && !CredentialVault.available()
                                    && !warnedPlaintext) {
                                warnedPlaintext = true;
                                toast(getString(R.string.nst_warn_plaintext));
                            }
                            if (editing == null) mServers.add(o);
                            saveServers();
                            showServers();
                        } catch (Exception e) {
                            toast(getString(R.string.nst_save_fail, String.valueOf(e.getMessage())));
                        }
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /**
     * v2.14.0（F-20）：扫本机所在 /24 网段，把能连上的服务列出来让用户点一个。
     *
     * <p>为什么值得做：SMB/FTP/SFTP 的端口是固定的，但 NAS 的 IP、以及「这台机器的
     * WebDAV 到底开在 5005 还是 5006」只有路由器后台知道。让用户先去查一遍再回来填，
     * 是这一页最容易半途放弃的地方；而对本网段做一次 TCP connect 只要几秒。</p>
     *
     * <p>扫描不碰表单里已经填好的用户名/密码：选中结果只是把地址与端口写进去，
     * 点错了重新扫一遍即可，不会清掉别的输入。</p>
     */
    private void scanLan(final Spinner type, final EditText host, final EditText port) {
        final List<String> hosts = LanScan.hostsToScan();
        if (hosts.isEmpty()) {
            toast(getString(R.string.lan_scan_nonet));
            return;
        }
        mScanStop.set(false);
        final int[] found = {0};
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.lan_scan_scanning, 0));
        pd.setCancelable(true);
        pd.setCanceledOnTouchOutside(false);
        pd.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            public void onCancel(android.content.DialogInterface d) {
                mScanStop.set(true);       // 只置标志，工作线程在下一次 connect 前退出
            }
        });
        pd.show();
        bg(new Runnable() {
            public void run() {
                LanScan.Result r = LanScan.scan(hosts, LanScan.PORTS, LanScan.CONNECT_MS,
                        new LanScan.Cancel() {
                            public boolean cancelled() {
                                return mScanStop.get();
                            }
                        },
                        new LanScan.Sink() {
                            public void onHit(LanScan.Hit h) {
                                // 进度文字只报「已发现几台」：扫到的东西此刻还没确认协议，
                                // 边扫边弹列表反而会让人以为结果一直在变
                                found[0]++;
                                final int n = found[0];
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (!isFinishing()) {
                                            pd.setMessage(getString(R.string.lan_scan_scanning, n));
                                        }
                                    }
                                });
                            }

                            public void onDone(LanScan.Result res) {
                            }
                        });
                final LanScan.Result res = r;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (pd.isShowing()) pd.dismiss();
                        if (res.cancelled) {
                            toast(getString(R.string.lan_scan_stopped));
                            return;
                        }
                        if (res.hits.isEmpty()) {
                            toast(getString(R.string.lan_scan_none, res.probed));
                            return;
                        }
                        showScanHits(res.hits, type, host, port);
                    }
                });
            }
        });
    }

    /** 扫描结果选择框：一行一台（同一台机器的不同协议各占一行，因为可填的东西不一样） */
    private void showScanHits(final List<LanScan.Hit> hits, final Spinner type,
                              final EditText host, final EditText port) {
        final String[] labels = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            LanScan.Hit h = hits.get(i);
            labels[i] = h.host + ":" + h.port + "  ·  " + scanTypeLabel(h.type)
                    + (h.detail.isEmpty() ? "" : "\n" + h.detail);
        }
        android.app.AlertDialog.Builder b = new HfDialogBuilder(this);
        b.setTitle(getString(R.string.lan_scan_pick, hits.size()));
        b.setItems(labels, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int which) {
                applyHit(hits.get(which), type, host, port);
            }
        });
        b.setNegativeButton(R.string.common_cancel, null);
        b.show();
    }

    /** 把一条扫描结果写进表单；先切类型再填值（切类型会重置端口，顺序反了就白填） */
    private void applyHit(LanScan.Hit h, Spinner type, EditText host, EditText port) {
        if (LanScan.T_SMB.equals(h.type)) {
            type.setSelection(0);
            host.setText(h.host);
            port.setText(String.valueOf(h.port));
            toast(getString(R.string.lan_scan_filled));
        } else if (LanScan.T_FTP.equals(h.type)) {
            type.setSelection(2);
            host.setText(h.host);
            port.setText(String.valueOf(h.port));
            toast(getString(R.string.lan_scan_filled));
        } else if (LanScan.T_SFTP.equals(h.type)) {
            type.setSelection(3);
            host.setText(h.host);
            port.setText(String.valueOf(h.port));
            toast(getString(R.string.lan_scan_filled));
        } else {
            // webdav / http：WebDAV 要的是完整 URL，80 端口写不写都行，省掉更干净
            type.setSelection(1);
            host.setText("http://" + h.host + (h.port == 80 ? "" : ":" + h.port) + "/");
            toast(getString(LanScan.T_WEBDAV.equals(h.type)
                    ? R.string.lan_scan_dav_hint : R.string.lan_scan_http_hint));
        }
    }

    /** 协议名直接给业界通用写法（SMB/WebDAV/FTP/SFTP），不翻译 */
    private static String scanTypeLabel(String type) {
        if (LanScan.T_SMB.equals(type)) return "SMB";
        if (LanScan.T_WEBDAV.equals(type)) return "WebDAV";
        if (LanScan.T_HTTP.equals(type)) return "HTTP";
        if (LanScan.T_FTP.equals(type)) return "FTP";
        if (LanScan.T_SFTP.equals(type)) return "SFTP";
        return type;
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

    /**
     * 改一行的标签文字（v1.88.0）。
     *
     * <p>打字机式的表单：同一批输入框在 SMB 下是「共享名/域」，在 S3 下是「bucket/region」，
     * 标签不跟着变的话，用户会在「共享名」下面填 bucket 名——虽然也是同一个字段，
     * 但没人能确定自己填对没有。</p>
     */
    private void setRowLabel(EditText e, String text) {
        Object tag = e.getTag();
        if (!(tag instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) tag;
        if (row.getChildCount() == 0) return;
        View first = row.getChildAt(0);
        if (first instanceof TextView) ((TextView) first).setText(text);
    }

    /** 配置里是否已存有云盘凭据（加密后非空即算） */
    private static boolean hasToken(org.json.JSONObject s) {
        String t = s.optString("token");
        return t != null && !t.isEmpty();
    }

    /** 去掉 URL 的协议前缀，服务器列表里显示短一点 */
    private static String stripScheme(String url) {
        if (url == null) return "";
        if (url.startsWith("https://")) return url.substring(8);
        if (url.startsWith("http://")) return url.substring(7);
        return url;
    }

    /** S3 上传缓冲目录（SigV4 需要先知道长度与哈希，所以先落盘再 PUT） */
    private File s3TempDir() {
        File d = new File(getCacheDir(), "s3up");
        d.mkdirs();
        return d;
    }

    /**
     * v1.89.0 令牌回写：云盘在运行中会刷新 token，阿里云盘的 refresh_token 还会轮换
     * （旧的用一次就作废）。不回写的话「今天能用、明天失效」，且用户完全不知道为什么。
     */
    private OAuthClient.TokenSink tokenSink(final org.json.JSONObject s) {
        return new OAuthClient.TokenSink() {
            public void onToken(OAuthToken t) {
                if (t == null || !t.hasRefresh()) return;
                try {
                    s.put("token", CredentialVault.encrypt(t.refresh));
                    saveServers();
                } catch (Exception ignore) {}
            }
        };
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
        showSpin(getString(R.string.nst_connecting, serverLabel(s)));
        bg(new Runnable() {
            public void run() {
                try {
                    NetFs fs;
                    String t = s.optString("type");
                    // v1.83.0：配置里存的是密文，连接前解密（旧明文原样返回）
                    String pw = CredentialVault.decrypt(s.optString("pass"));
                    if (TYPE_WEBDAV.equals(t)) {
                        fs = new WebDavFs(s.optString("url").isEmpty() ? s.optString("host") : s.optString("url"),
                                s.optString("user"), pw);
                    } else if (TYPE_FTP.equals(t)) {
                        fs = FtpFs.connect(s.optString("host"), s.optInt("port", 21),
                                s.optString("user"), pw);
                    } else if (TYPE_SFTP.equals(t)) {
                        fs = SftpFs.connect(s.optString("host"), s.optInt("port", 22),
                                s.optString("user"), pw);
                    } else if (TYPE_S3.equals(t)) {
                        fs = S3Fs.connect(s.optString("host"), s.optString("share"),
                                s.optString("domain"), s.optString("user"), pw, s3TempDir());
                    } else if (TYPE_ONEDRIVE.equals(t)) {
                        fs = OneDriveFs.connect(s.optString("user"),
                                CredentialVault.decrypt(s.optString("token")),
                                tokenSink(s), s3TempDir());
                    } else if (TYPE_ALIYUN.equals(t)) {
                        fs = AliyunDriveFs.connect(CredentialVault.decrypt(s.optString("token")),
                                tokenSink(s), s3TempDir());
                    } else {
                        fs = SmbFs.connect(s.optString("host"), s.optInt("port", 445),
                                s.optString("share"), s.optString("domain"),
                                s.optString("user"), pw);
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
                            errorDialog(getString(R.string.nst_connect_fail), friendlyNetError(e));
                        }
                    });
                }
            }
        });
    }

    private void browse(final String path) {
        showSpin(getString(R.string.nst_loading, path.isEmpty() ? "/" : path));
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
                            errorDialog(getString(R.string.nst_load_fail), friendlyNetError(e));
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
        // v1.88.0：本地有未完成的 .hpart 时，菜单直接写成「继续下载（已有 x）」，让续传可见
        File probe = new File(new File(WorkDir.base(), "NetStorage"), e.name);
        File partProbe = ResumePlan.partOf(probe);
        boolean canResume = !e.dir && partProbe.exists() && partProbe.length() > 0 && !probe.exists();

        List<String> ops = new ArrayList<>();
        ops.add(canResume
                ? getString(R.string.nst_menu_resume, FileOps.formatSize(partProbe.length()))
                : getString(R.string.nst_menu_download));
        ops.add(getString(R.string.nst_menu_rename));
        ops.add(getString(R.string.common_delete));
        new HfDialogBuilder(this)
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
        showSpin(getString(R.string.nst_preparing, e.name));
        bg(new Runnable() {
            public void run() {
                try {
                    // 统一落在 NetStorage/<条目名>：目录为递归根，文件为完整目标路径
                    File destDir = new File(WorkDir.base(), "NetStorage/" + e.name);
                    final File baseDir = destDir;
                    runOnUiThread(new Runnable() {
                        public void run() { mProgress.setMessage(getString(R.string.nst_downloading)); }
                    });
                    if (e.dir) {
                        downloadDir(path, baseDir);
                        doneOnUiThread(getString(R.string.nst_dl_dir_done, baseDir.getAbsolutePath()));
                    } else {
                        baseDir.getParentFile().mkdirs();
                        final File dest = pickDest(baseDir.getParentFile(), baseDir.getName());
                        downloadFile(path, dest, e.name, e.size, e.mtime);
                        doneOnUiThread(getString(R.string.nst_dl_done, dest.getAbsolutePath()));
                    }
                } catch (final Exception ex) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            errorDialog(getString(R.string.nst_dl_fail), friendlyNetError(ex));
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
            setMsgOnUiThread(getString(R.string.nst_dl_progress, c.name));
            if (c.dir) downloadDir(NetFs.join(remote, c.name), child);
            else {
                child.getParentFile().mkdirs();
                downloadFile(NetFs.join(remote, c.name), pickDest(child.getParentFile(), child.getName()),
                        c.name, c.size, c.mtime);
            }
        }
    }

    /**
     * 选目标文件：有未完成临时文件就沿用它的名字（才能续传），否则目标已存在就换名。
     *
     * <p>这里的分支顺序很关键：{@code .hpart} 存在但同名目标文件也存在时，
     * 说明临时文件已经和现有文件「撞名」了（用户自己拷了个同名的进来）。
     * 这时若还沿用这个名字续传，完成时的改名会把用户那个文件覆盖掉，
     * 所以先把残留临时文件清掉，改用新名字从头下载。</p>
     */
    private static File pickDest(File dir, String name) {
        File dest = new File(dir, name);
        File part = ResumePlan.partOf(dest);
        if (part.exists()) {
            if (!dest.exists()) return dest;
            deleteQuiet(part);
            deleteQuiet(ResumePlan.metaOf(dest));
        }
        return uniqueLocal(dir, name);
    }

    /**
     * v1.88.0 单文件下载：写入 {@code dest.hpart}，按 {@link ResumePlan} 自动续传，
     * 校验大小后原子改名为 {@code dest}。
     *
     * <p>三处保护：</p>
     * <ol>
     *   <li>只往 .hpart 写——中途断网/取消不会留下「看起来正常、其实缺半截」的目标文件；</li>
     *   <li>续传前比对 sidecar 里的 size/mtime，远端变过就丢弃重传（见 ResumePlan）；</li>
     *   <li>完成前校验长度，最后一步才 rename，失败也不污染原目标。</li>
     * </ol>
     */
    private void downloadFile(String remotePath, File dest, final String name,
                              long remoteSize, long remoteMtime) throws IOException {
        File part = ResumePlan.partOf(dest);
        File meta = ResumePlan.metaOf(dest);
        long metaSize = 0, metaMtime = 0;
        long[] m = ResumePlan.parseMeta(readSmall(meta));
        if (m != null) { metaSize = m[0]; metaMtime = m[1]; }

        ResumePlan.Plan plan = ResumePlan.decide(part.exists(), part.length(), remoteSize,
                metaSize, metaMtime, remoteMtime);

        if (plan.isComplete()) {                        // 上次已传完，只差改名
            finishPart(part, meta, dest, remoteSize);
            return;
        }
        if (!plan.isResume()) {                        // 从头来：清掉残留，重写校验信息
            deleteQuiet(part);
            writeSmall(meta, ResumePlan.metaText(remoteSize, remoteMtime));
        }

        long offset = plan.isResume() ? plan.offset : 0;
        if (offset > 0) {
            setMsgOnUiThread(getString(R.string.nst_resuming, name, FileOps.formatSize(offset)));
        }

        InputStream in = null;
        try {
            in = offset > 0 ? mFs.openReadFrom(remotePath, offset) : mFs.openRead(remotePath);
        } catch (IOException first) {
            // 协议层拒绝续传（服务器不支持 REST/Range、远端被替换等）→ 退回从头下载，不当失败处理
            if (offset == 0) throw first;
            deleteQuiet(part);
            writeSmall(meta, ResumePlan.metaText(remoteSize, remoteMtime));
            offset = 0;
            setMsgOnUiThread(getString(R.string.nst_no_resume, name));
            in = mFs.openRead(remotePath);
        }

        OutputStream out = new FileOutputStream(part, offset > 0);   // append = 续传
        try {
            copyStream(in, out, name, offset, remoteSize);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
            try { out.close(); } catch (IOException ignore) {}
        }

        long got = part.length();
        if (remoteSize > 0 && got != remoteSize)
            throw new IOException(getString(R.string.nst_incomplete, name,
                    FileOps.formatSize(got), FileOps.formatSize(remoteSize)));
        finishPart(part, meta, dest, remoteSize);
    }

    /** 校验通过：.hpart → 目标文件，并清掉校验文件。 */
    private void finishPart(File part, File meta, File dest, long size) throws IOException {
        if (size > 0 && part.length() != size)
            throw new IOException(getString(R.string.nst_part_mismatch,
                    String.valueOf(part.length()), String.valueOf(size)));
        if (dest.exists() && !dest.delete())
            throw new IOException(getString(R.string.nst_dest_locked, dest.getAbsolutePath()));
        if (!part.renameTo(dest)) {
            // 极端情况（跨挂载点/FUSE 不支持 rename）退化为复制
            InputStream in = new java.io.FileInputStream(part);
            OutputStream out = new FileOutputStream(dest);
            try {
                copyStream(in, out, dest.getName(), 0, size);
            } finally {
                try { in.close(); } catch (IOException ignore) {}
                try { out.close(); } catch (IOException ignore) {}
            }
            deleteQuiet(part);
        }
        deleteQuiet(meta);
    }

    /**
     * 复制流 + 进度回调。
     *
     * @param base  已完成的字节数（续传起点），进度条从 base 开始
     * @param total 总字节数（<=0 表示未知，用不确定进度条）
     */
    private void copyStream(InputStream in, OutputStream out, String name, long base, long total) throws IOException {
        final ProgressDialog pd = mProgress;
        if (total > 0) {
            final int max = (int) Math.min(total, Integer.MAX_VALUE);
            final int start = (int) Math.min(base, max);
            runOnUiThread(new Runnable() {
                public void run() {
                    if (mProgress != null && !isFinishing()) {
                        mProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                        mProgress.setMax(max);
                        mProgress.setProgress(start);
                    }
                }
            });
        }
        byte[] buf = new byte[64 * 1024];
        long done = base;
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
    }

    private static void deleteQuiet(File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (Throwable ignore) {}
    }

    /** 读小文本文件（sidecar 只有几个字节）；不存在/失败返回 null。 */
    private static String readSmall(File f) {
        if (f == null || !f.isFile() || f.length() > 4096) return null;
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignore) {}
        }
    }

    private static void writeSmall(File f, String text) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignore) {
            // 写不进去只影响「下次能不能续传」，不该让本次下载失败
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignore) {}
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
            toast(getString(R.string.nst_picker_fail));
        }
    }

    /**
     * v1.90.0 网页登录回来。
     *
     * <p>这里<b>故意不做「先 refresh 验证再保存」</b>。阿里云盘的 refresh_token 是一次性轮换的：
     * 验证一次就把旧的作废了，如果用户验证完没点「保存」就退出，凭证已经废掉而配置里还是旧的 ——
     * 下次连接必然失败，且用户完全看不出原因。网页登录刚拿到的 token 本身就是刚签发的，
     * 没有「可能是坏 token」的问题，直接采纳、等用户点保存即可。</p>
     */
    private void onCloudLogin(int resultCode, Intent data) {
        if (resultCode == RESULT_FIRST_USER) {
            // 用户点了顶栏「粘贴方式」：回到原来那套手动流程
            if (mAuthCb != null) CloudAuthDialog.show(this, TYPE_ALIYUN, "", "", mAuthCb);
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        final String rt = OAuthToken.extractRefresh(data.getStringExtra(CloudLoginActivity.EXTRA_REFRESH));
        if (rt.isEmpty()) {
            errorDialog(getString(R.string.nst_no_token_title), getString(R.string.nst_no_token_msg));
            return;
        }
        toast(getString(R.string.nst_aliyun_logged));
        if (mAuthCb != null) mAuthCb.onAuthorized(rt);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CLOUD_LOGIN) {
            onCloudLogin(resultCode, data);
            return;
        }
        if (requestCode != REQ_PICK_UPLOAD || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        mUploadUri = data.getData();
        mUploadName = queryDisplayName(mUploadUri);
        if (mUploadName == null || mUploadName.isEmpty()) mUploadName = "upload_" + System.currentTimeMillis() + ".bin";

        // v1.88.0 上传续传：远端已有同名文件且比本地短 → 从断点追加（仅 FTP/SFTP/SMB 支持 APPE/APPEND）
        long localSize = querySize(mUploadUri);
        long remoteSize = -1;
        for (NetFs.NetEntry en : mEntries) {
            if (!en.dir && en.name.equals(mUploadName)) { remoteSize = en.size; break; }
        }
        final long resumeFrom = (remoteSize > 0 && localSize > 0 && remoteSize < localSize
                && mFs != null && mFs.supportsAppend()) ? remoteSize : 0;
        final long total = localSize;

        showSpin(resumeFrom > 0
                ? getString(R.string.nst_ul_resuming, mUploadName, FileOps.formatSize(resumeFrom))
                : getString(R.string.nst_ul_uploading, mUploadName));
        bg(new Runnable() {
            public void run() {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(mUploadUri);
                    if (resumeFrom > 0) skipFully(in, resumeFrom);
                    String destPath = NetFs.join(mCur, mUploadName);
                    OutputStream out = resumeFrom > 0
                            ? mFs.openWriteFrom(destPath, resumeFrom)
                            : mFs.openWrite(destPath);
                    copyStream(in, out, mUploadName, resumeFrom, total);
                    doneOnUiThread(getString(R.string.nst_ul_done, destPath));
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            hideProgress();
                            errorDialog(getString(R.string.nst_ul_fail), friendlyNetError(e));
                        }
                    });
                } finally {
                    try { if (in != null) in.close(); } catch (IOException ignore) {}
                }
            }
        });
    }

    /** 跳过 n 字节（InputStream.skip 对部分 content:// 流会提前返回，这里循环补齐）。 */
    private void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] waste = null;
        while (left > 0) {
            long s = in.skip(left);
            if (s > 0) { left -= s; continue; }
            if (waste == null) waste = new byte[64 * 1024];
            int r = in.read(waste, 0, (int) Math.min(waste.length, left));
            if (r <= 0) throw new IOException(getString(R.string.nst_src_short, String.valueOf(left)));
            left -= r;
        }
    }

    /** 查询 content:// 的大小；未知返回 -1。 */
    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
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
        final EditText e = inputDialog("");
        new HfDialogBuilder(this)
                .setTitle(R.string.nst_mkdir_title)
                .setView(wrapScroll(inputBox(e)))
                .setPositiveButton(R.string.nst_create, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String name = e.getText().toString().trim();
                        if (name.isEmpty()) return;
                        showSpin(getString(R.string.nst_mkdir_running));
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
                                            errorDialog(getString(R.string.nst_mkdir_fail), friendlyNetError(ex));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
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

    /** 单行输入框。原来还带 title / label 两个参数，但两者从未被读过（标题由对话框自己设） */
    private EditText inputDialog(String value) {
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
        new HfDialogBuilder(this)
                .setTitle(R.string.nst_menu_rename)
                .setView(wrapScroll(box))
                .setPositiveButton(R.string.nst_ok, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String name = e.getText().toString().trim();
                        if (name.isEmpty() || name.equals(en.name)) return;
                        showSpin(getString(R.string.nst_rename_running));
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
                                            errorDialog(getString(R.string.nst_rename_fail), friendlyNetError(ex));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void doDelete(final NetFs.NetEntry en, final String path) {
        new HfDialogBuilder(this)
                .setTitle(getString(R.string.nst_delete_title, en.name))
                .setMessage(getString(en.dir ? R.string.nst_delete_dir_msg
                        : R.string.nst_delete_file_msg))
                .setPositiveButton(R.string.common_delete, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        showSpin(getString(R.string.nst_delete_running));
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
                                            errorDialog(getString(R.string.nst_delete_fail), friendlyNetError(ex));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
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
        new HfDialogBuilder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.common_got_it, null)
                .show();
    }

    /** 网络错误信息人性化。NetFsError 只带码，交给 NetText 映射成文案。 */
    private String friendlyNetError(Throwable e) {
        if (e instanceof com.hex91.file.net.NetFsError) {
            return NetText.errorText(this, e);
        }
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (m.contains("ECONNREFUSED")) return getString(R.string.nst_err_refused);
        if (m.contains("ENETUNREACH") || m.contains("EHOSTUNREACH")) {
            return getString(R.string.nst_err_unreach);
        }
        if (m.contains("ETIMEDOUT") || m.contains("timeout") || m.contains("Timed out")) {
            return getString(R.string.nst_err_timeout);
        }
        if (m.contains("Connection refused")) return getString(R.string.nst_err_refused2, m);
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
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
            String sub = e.dir ? getString(R.string.nst_dir) : FileOps.formatSize(e.size);
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
        // v2.14.0（F-20）：扫描线程可能还在连网段里最后几个地址，页面没了就该停，
        // 否则 48 个线程会一直跑到 1270 个组合探完
        mScanStop.set(true);
        final NetFs fs = mFs;
        mFs = null;
        if (fs != null) {
            bg(new Runnable() { public void run() { fs.close(); } });
        }
        hideProgress();
    }
}
