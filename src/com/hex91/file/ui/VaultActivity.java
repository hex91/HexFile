package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.VaultCrypto;
import com.hex91.file.core.VaultIndex;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.VaultStore;
import com.hex91.file.fs.WorkDir;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 隐私空间（v2.18.0，F-22）。
 *
 * <p>三态：<b>未建立</b>（设口令 + 再输一遍）、<b>锁定</b>（输口令）、<b>已解锁</b>（文件列表）。
 * 三态共用一个 Activity 而不是拆成「设置页 + 列表页」：锁定态本来就没有内容可显示，
 * 跳转会多出一次返回栈，而用户想做的事（输口令）在每一态都是同一件。</p>
 *
 * <p><b>离开即锁</b>：{@code onStop} 就把主密钥丢掉。只有两种情况例外：系统文件选择器
 * 在前台（不然选完文件回来已经被锁，等于导入功能不可用）、后台任务正在跑（任务要用密钥，
 * 等它收尾再锁）。这两个例外都是显式布尔量，不靠「大概还没走」的猜测。</p>
 *
 * <p>为什么「导入」是复制而不是移动：{@code ACTION_OPEN_DOCUMENT} 给的 {@code content://}
 * 不保证能被删除（很多 provider 不允许），承诺「移动」却删不掉源文件，是最糟的体验。
 * 真正的移动在主界面长按文件 →「移入隐私空间」，那是本地 {@code File}，删得掉。</p>
 */
public class VaultActivity extends BaseActivity {

    private static final int REQ_PICK = 0x7511;

    private File dir;
    /** null = 锁定。丢掉引用就是「锁」，没有额外的 locked 标志位要维护 */
    private VaultCrypto.Master master;
    private final List<VaultIndex.Entry> data = new ArrayList<VaultIndex.Entry>();
    private Adapter adapter;

    private TextView info, empty, hint, err, lockTitle;
    private LinearLayout lockPanel;
    private EditText pw, pw2;
    private Button go, importBtn, moreBtn;
    private ListView list;

    private boolean setupMode;
    /** 系统选择器在前台：这段 onStop 不算「离开」，不锁 */
    private boolean picking;
    private boolean jobRunning;
    private boolean lockAfterJob;
    private boolean leftForeground;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_vault);

        dir = VaultStore.defaultDir();

        info = (TextView) findViewById(R.id.vt_info);
        empty = (TextView) findViewById(R.id.vt_empty);
        hint = (TextView) findViewById(R.id.vt_hint);
        err = (TextView) findViewById(R.id.vt_err);
        err.setTextColor(ThemeManager.err(this));
        lockTitle = (TextView) findViewById(R.id.vt_lock_title);
        lockPanel = (LinearLayout) findViewById(R.id.vt_lock);
        pw = (EditText) findViewById(R.id.vt_pw);
        pw2 = (EditText) findViewById(R.id.vt_pw2);
        go = (Button) findViewById(R.id.vt_go);
        importBtn = (Button) findViewById(R.id.vt_import);
        moreBtn = (Button) findViewById(R.id.vt_more);
        list = (ListView) findViewById(R.id.vt_list);

        UiDecor.barButtonOnBar(importBtn, this, UiDecor.KIND_PRIMARY, 11.5f);
        UiDecor.gap(importBtn, this, 4f);
        UiDecor.barButtonOnBar(moreBtn, this, UiDecor.KIND_SOFT, 11.5f);
        UiDecor.gap(moreBtn, this, 4f);
        Button close = (Button) findViewById(R.id.vt_close);
        UiDecor.barButtonOnBar(close, this, UiDecor.KIND_SOFT, 11.5f);
        UiDecor.gap(close, this, 4f);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        UiDecor.barButtonOnBar(go, this, UiDecor.KIND_PRIMARY, 13f);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { submit(); }
        });

        importBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pick(); }
        });
        moreBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMore(); }
        });

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                showOps(data.get(i));
            }
        });

        if (VaultStore.exists(dir)) enterLockMode();
        else enterSetupMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        leftForeground = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        leftForeground = true;
        if (picking) return;
        if (jobRunning) { lockAfterJob = true; return; }
        lock();
    }

    // ---------------------------------------------------------------- 三态切换

    private void enterSetupMode() {
        setupMode = true;
        master = null;
        data.clear();
        adapter.notifyDataSetChanged();
        lockTitle.setText(R.string.vault_setup_title);
        pw2.setVisibility(View.VISIBLE);
        go.setText(R.string.vault_btn_create);
        hint.setText(R.string.vault_setup_hint);
        importBtn.setEnabled(false);
        moreBtn.setEnabled(false);
        list.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        lockPanel.setVisibility(View.VISIBLE);
        info.setText(getString(R.string.vault_info, 0, FileOps.formatSize(0)));
        clearErr();
    }

    private void enterLockMode() {
        setupMode = false;
        master = null;
        data.clear();
        adapter.notifyDataSetChanged();
        lockTitle.setText(R.string.vault_unlock_title);
        pw2.setVisibility(View.GONE);
        pw2.setText("");
        go.setText(R.string.vault_btn_unlock);
        hint.setText(R.string.vault_lock_hint);
        importBtn.setEnabled(false);
        moreBtn.setEnabled(false);
        list.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        lockPanel.setVisibility(View.VISIBLE);
        long n = VaultStore.usedBytes(dir);
        info.setText(getString(R.string.vault_info_locked, FileOps.formatSize(n)));
        clearErr();
        pw.requestFocus();
    }

    private void lock() {
        if (master == null && !setupMode) {
            // 已经锁着：不清输入框的话，用户输了一半走到别的页面再回来，
            // 口令还留在框里——「离开即锁」就成了摆设
            pw.setText("");
            pw2.setText("");
            clearErr();
            return;
        }
        master = null;
        lockAfterJob = false;
        if (VaultStore.exists(dir)) enterLockMode();
        else enterSetupMode();
    }

    private void showList() {
        lockPanel.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        hint.setText(R.string.vault_import_hint);
        importBtn.setEnabled(true);
        moreBtn.setEnabled(true);
        pw.setText("");
        pw2.setText("");
        clearErr();
        reload();
    }

    private void reload() {
        if (master == null) return;
        try {
            VaultStore.Table t = VaultStore.load(dir, master);
            data.clear();
            data.addAll(t.entries);
            Collections.sort(data, new Comparator<VaultIndex.Entry>() {
                public int compare(VaultIndex.Entry a, VaultIndex.Entry b) {
                    if (a.addedAt != b.addedAt) return a.addedAt > b.addedAt ? -1 : 1;
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            adapter.notifyDataSetChanged();
            empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
            long total = 0;
            for (VaultIndex.Entry e : data) total += Math.max(0, e.size);
            info.setText(getString(R.string.vault_info, data.size(), FileOps.formatSize(total)));
            if (t.backup || t.bad > 0) {
                // 备份索引能读但可能漏掉最近导入的文件：说出来，别让用户以为文件丢了是自己的错觉
                StringBuilder sb = new StringBuilder(getString(R.string.vault_import_hint));
                if (t.backup) sb.append('\n').append(getString(R.string.vault_index_backup));
                if (t.bad > 0) sb.append('\n').append(getString(R.string.vault_index_bad, t.bad));
                hint.setText(sb.toString());
            }
        } catch (Exception e) {
            error(getString(R.string.vault_load_fail, msg(e)));
        }
    }

    // ---------------------------------------------------------------- 设置 / 解锁

    private void submit() {
        final String p = pw.getText().toString();
        if (setupMode) {
            if (p.length() < 6) { showErr(R.string.vault_pw_short); return; }
            if (!p.equals(pw2.getText().toString())) { showErr(R.string.vault_pw_mismatch); return; }
            clearErr();
            start(new Job(R.string.vault_creating) {
                Object work() throws Exception { return VaultStore.create(dir, p); }
                void done(Object r, Exception e) {
                    if (e != null) { showErrText(msg(e)); return; }
                    master = (VaultCrypto.Master) r;
                    toast(getString(R.string.vault_created));
                    showList();
                }
            });
        } else {
            if (p.isEmpty()) { showErr(R.string.vault_pw_short); return; }
            clearErr();
            start(new Job(R.string.vault_unlocking) {
                Object work() throws Exception { return VaultStore.unlock(dir, p); }
                void done(Object r, Exception e) {
                    if (e != null) {
                        if (e instanceof SecurityException) {
                            showErr(R.string.vault_wrong_pw);
                            pw.setText("");
                        } else {
                            showErrText(msg(e));
                        }
                        return;
                    }
                    master = (VaultCrypto.Master) r;
                    toast(getString(R.string.vault_unlocked));
                    showList();
                }
            });
        }
    }

    // ---------------------------------------------------------------- 导入

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picking = true;
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            picking = false;
            toast(getString(R.string.vault_import_fail, msg(t)));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data2) {
        super.onActivityResult(req, res, data2);
        if (req != REQ_PICK) return;
        picking = false;
        if (res != RESULT_OK || data2 == null) return;
        final List<Uri> uris = new ArrayList<Uri>();
        ClipData cd = data2.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data2.getData() != null) {
            uris.add(data2.getData());
        }
        if (uris.isEmpty()) {
            toast(getString(R.string.vault_import_none));
            return;
        }
        importUris(uris);
    }

    private void importUris(final List<Uri> uris) {
        if (master == null) return;
        final VaultCrypto.Master m = master;
        final int n = uris.size();
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.vault_importing, 0, n));
        pd.setCanceledOnTouchOutside(false);
        pd.setCancelable(true);
        final AtomicBoolean cancel = new AtomicBoolean(false);
        pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { cancel.set(true); }
        });
        pd.show();

        jobRunning = true;
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, failed = 0;
                String firstErr = null;
                for (int k = 0; k < n; k++) {
                    if (cancel.get()) break;
                    final int idx = k;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pd.setMessage(getString(R.string.vault_importing, idx + 1, n));
                        }
                    });
                    Uri u = uris.get(k);
                    InputStream in = null;
                    try {
                        long size = sizeOf(u);
                        String name = displayName(u);
                        in = getContentResolver().openInputStream(u);
                        if (in == null) throw new VaultCrypto.IoFail(VaultCrypto.Code.READ_URI_FAIL);
                        VaultStore.add(dir, m, in, size, name, System.currentTimeMillis(),
                                new VaultCrypto.Progress() {
                                    public boolean on(long d, long t) { return !cancel.get(); }
                                });
                        ok++;
                    } catch (VaultCrypto.Cancelled c) {
                        break;
                    } catch (Exception e) {
                        failed++;
                        if (firstErr == null) firstErr = msg(e);
                    } finally {
                        VaultStore.closeQuiet(in);
                    }
                }
                final int fok = ok, ffailed = failed;
                final String ferr = firstErr;
                final boolean fcancel = cancel.get();
                runOnUiThread(new Runnable() {
                    public void run() {
                        dismissQuiet(pd);
                        jobDone();
                        reload();
                        if (fcancel) toast(getString(R.string.vault_cancel));
                        else if (ffailed == 0) toast(getString(R.string.vault_import_ok, fok));
                        else if (fok > 0) toast(getString(R.string.vault_import_partial, fok, ffailed));
                        else toast(getString(R.string.vault_import_fail, ferr == null ? "" : ferr));
                    }
                });
            }
        }, "vault-import").start();
    }

    // ---------------------------------------------------------------- 单条操作

    private void showOps(final VaultIndex.Entry e) {
        String[] ops = new String[]{
                getString(R.string.vault_menu_export),
                getString(R.string.vault_menu_rename),
                getString(R.string.vault_menu_delete)
        };
        new HfDialogBuilder(this)
                .setTitle(e.name + "\n" + FileOps.formatSize(e.size) + " · "
                        + FileOps.formatTime(e.addedAt))
                .setItems(ops, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) chooseExportDir(e);
                        else if (which == 1) renameDialog(e);
                        else confirmDelete(e);
                    }
                })
                .show();
    }

    private void chooseExportDir(final VaultIndex.Entry e) {
        final File start = WorkDir.base();
        DirPickerDialog.show(this, start, new DirPickerDialog.OnPick() {
            public void onPick(File d) { exportTo(e, d); }
        });
    }

    private void exportTo(final VaultIndex.Entry e, final File destDir) {
        if (master == null) return;
        final VaultCrypto.Master m = master;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.vault_exporting));
        pd.setCanceledOnTouchOutside(false);
        pd.setCancelable(true);
        final AtomicBoolean cancel = new AtomicBoolean(false);
        pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { cancel.set(true); }
        });
        pd.show();
        jobRunning = true;
        new Thread(new Runnable() {
            public void run() {
                File out = null;
                Exception err = null;
                try {
                    out = VaultStore.export(dir, m, e.id, destDir, new VaultCrypto.Progress() {
                        public boolean on(long d, long t) { return !cancel.get(); }
                    });
                } catch (Exception x) {
                    err = x;
                }
                final File fout = out;
                final Exception ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        dismissQuiet(pd);
                        jobDone();
                        if (fout != null) toast(getString(R.string.vault_exported, fout.getAbsolutePath()));
                        else if (ferr instanceof VaultCrypto.Cancelled) toast(getString(R.string.vault_cancel));
                        else toast(getString(R.string.vault_export_fail, msg(ferr)));
                    }
                });
            }
        }, "vault-export").start();
    }

    private void renameDialog(final VaultIndex.Entry e) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(e.name);
        et.setSelection(e.name.length());
        final AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle(R.string.vault_rename_title)
                .setView(et)
                .setPositiveButton(R.string.common_save, null)
                .setNegativeButton(R.string.common_cancel, null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                if (master == null) { dlg.dismiss(); return; }
                                try {
                                    VaultStore.rename(dir, master, e.id, et.getText().toString());
                                    dlg.dismiss();
                                    reload();
                                } catch (Exception x) {
                                    toast(msg(x));
                                }
                            }
                        });
            }
        });
        dlg.show();
    }

    private void confirmDelete(final VaultIndex.Entry e) {
        new HfDialogBuilder(this)
                .setTitle(R.string.vault_menu_delete)
                .setMessage(getString(R.string.vault_delete_msg, e.name))
                .setPositiveButton(R.string.common_delete, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (master == null) return;
                        try {
                            VaultStore.remove(dir, master, e.id);
                            toast(getString(R.string.vault_deleted));
                            reload();
                        } catch (Exception x) {
                            toast(msg(x));
                        }
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- 更多

    private void showMore() {
        String[] ops = new String[]{
                getString(R.string.vault_change_pw),
                getString(R.string.vault_clean_orphans),
                getString(R.string.vault_about),
                getString(R.string.vault_lock)
        };
        new HfDialogBuilder(this)
                .setItems(ops, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) changePwDialog();
                        else if (which == 1) cleanOrphans();
                        else if (which == 2) aboutDialog();
                        else lock();
                    }
                })
                .show();
    }

    private void changePwDialog() {
        final EditText oldEt = new EditText(this);
        oldEt.setHint(R.string.vault_change_pw_old);
        oldEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        oldEt.setSingleLine(true);
        final EditText newEt = new EditText(this);
        newEt.setHint(R.string.vault_change_pw_new);
        newEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        newEt.setSingleLine(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ToolUi.dp(this, 16);
        box.setPadding(pad, 0, pad, 0);
        box.addView(oldEt);
        box.addView(newEt);

        final AlertDialog dlg = new HfDialogBuilder(this)
                .setTitle(R.string.vault_change_pw)
                .setView(box)
                .setPositiveButton(R.string.common_save, null)
                .setNegativeButton(R.string.common_cancel, null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                if (master == null) { dlg.dismiss(); return; }
                                final String o = oldEt.getText().toString();
                                final String nn = newEt.getText().toString();
                                if (nn.length() < 6) { toast(getString(R.string.vault_pw_short)); return; }
                                final VaultCrypto.Master m = master;
                                start(new Job(R.string.vault_creating) {
                                    Object work() throws Exception {
                                        // 先验当前口令：改口令不需要知道旧口令是设计上的漏洞，
                                        // 手机被临时借用时「能进列表」不等于「能换掉主人的口令」
                                        VaultStore.unlock(dir, o);
                                        VaultStore.changePassword(dir, m, nn);
                                        return null;
                                    }
                                    void done(Object r, Exception e) {
                                        if (e == null) {
                                            dlg.dismiss();
                                            toast(getString(R.string.vault_pw_changed));
                                        } else if (e instanceof SecurityException) {
                                            toast(getString(R.string.vault_wrong_pw));
                                        } else {
                                            toast(msg(e));
                                        }
                                    }
                                });
                            }
                        });
            }
        });
        dlg.show();
    }

    private void cleanOrphans() {
        if (master == null) return;
        final VaultCrypto.Master m = master;
        final List<String> orphans = VaultStore.orphans(dir, m);
        if (orphans.isEmpty()) {
            toast(getString(R.string.vault_orphans_none));
            return;
        }
        new HfDialogBuilder(this)
                .setTitle(R.string.vault_clean_orphans)
                .setMessage(getString(R.string.vault_orphans_msg, orphans.size()))
                .setPositiveButton(R.string.common_delete, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (String id : orphans) {
                            if (VaultStore.blobFile(dir, id).delete()) n++;
                        }
                        toast(getString(R.string.vault_orphans_done, n));
                        reload();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void aboutDialog() {
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(R.string.vault_about_msg);
        tv.setTextSize(12.5f);
        tv.setTextColor(ThemeManager.textSub(this));
        int pad = ToolUi.dp(this, 16);
        tv.setPadding(pad, pad, pad, pad);
        sc.addView(tv);
        new HfDialogBuilder(this)
                .setTitle(R.string.vault_about)
                .setView(sc)
                .setPositiveButton(R.string.common_got_it, null)
                .show();
    }

    // ---------------------------------------------------------------- 后台任务

    /** 一次后台动作：work() 在子线程跑，done() 回主线程；吞掉异常会让 UI 卡在「正在…」 */
    private abstract class Job {
        final int msgRes;

        Job(int msgRes) { this.msgRes = msgRes; }

        abstract Object work() throws Exception;

        abstract void done(Object result, Exception error);
    }

    private void start(final Job job) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(job.msgRes));
        pd.setCanceledOnTouchOutside(false);
        pd.setCancelable(false);
        pd.show();
        jobRunning = true;
        new Thread(new Runnable() {
            public void run() {
                Object r = null;
                Exception e = null;
                try {
                    r = job.work();
                } catch (Exception x) {
                    e = x;
                }
                final Object fr = r;
                final Exception fe = e;
                runOnUiThread(new Runnable() {
                    public void run() {
                        dismissQuiet(pd);
                        jobDone();
                        job.done(fr, fe);
                    }
                });
            }
        }, "vault-job").start();
    }

    /** 后台任务收尾：如果这期间用户已经离开，就地把密钥丢掉 */
    private void jobDone() {
        jobRunning = false;
        if (lockAfterJob && leftForeground) {
            lockAfterJob = false;
            lock();
        }
    }

    // ---------------------------------------------------------------- 小工具

    private String displayName(Uri u) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
            }
        } catch (Throwable ignored) {
            name = null;   // 少数 provider 不支持 query：退回 URI 末段，导入照常进行
        } finally {
            VaultStore.closeQuiet(c);
        }
        if (name == null || name.trim().isEmpty()) {
            String last = u.getLastPathSegment();
            name = last == null || last.trim().isEmpty() ? "imported.bin" : last;
        }
        return name;
    }

    private long sizeOf(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (si >= 0 && !c.isNull(si)) return c.getLong(si);
            }
        } catch (Throwable ignored) {
            return -1;     // 拿不到大小就传 -1，由存储层按实际加密量记
        } finally {
            VaultStore.closeQuiet(c);
        }
        return -1;
    }

    private void showErr(int res) {
        showErrText(getString(res));
    }

    private void showErrText(String s) {
        err.setText(s);
        err.setVisibility(View.VISIBLE);
    }

    private void clearErr() {
        err.setText("");
        err.setVisibility(View.GONE);
    }

    private void error(String s) {
        new HfDialogBuilder(this)
                .setTitle(R.string.vault_title)
                .setMessage(s)
                .setPositiveButton(R.string.common_got_it, null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static void dismissQuiet(ProgressDialog pd) {
        try {
            pd.dismiss();
        } catch (Exception e) {
            // 页面已经销毁时 dismiss 会抛「WindowLeaked」类异常，这里没有可做的补救
            return;
        }
    }

    private String msg(Throwable t) {
        return VaultReport.msg(this, t);
    }

    // ---------------------------------------------------------------- 列表

    private class Adapter extends BaseAdapter {
        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = android.view.LayoutInflater.from(VaultActivity.this)
                        .inflate(R.layout.item_result, parent, false);
            }
            VaultIndex.Entry e = data.get(i);
            TextView name = (TextView) convert.findViewById(R.id.name);
            TextView detail = (TextView) convert.findViewById(R.id.detail);
            name.setText(e.name);
            detail.setText(FileOps.formatSize(e.size) + " · " + FileOps.formatTime(e.addedAt));
            return convert;
        }
    }
}
