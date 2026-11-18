package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.VaultCrypto;
import com.hex91.file.core.VaultIndex;
import com.hex91.file.fs.VaultStore;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「移入隐私空间」的共用流程（v2.18.0，F-22）。
 *
 * <p>为什么单独一个类而不是写在 {@link VaultActivity} 里：入口在主界面（长按文件），
 * 而主界面不该为了问一次口令就跳进隐私空间页面 —— 用户想的是「把这个文件藏起来」，
 * 不是「去看看隐私空间里有什么」。跳转过去再回来，连选中的文件都得再找一遍。</p>
 *
 * <p>移动的语义是「要么整体完成，要么不动」：密文写好之后如果原文件删不掉
 * （被别的应用占用、只读挂载），就把刚写的密文也删掉并计为失败。
 * 半移动状态最糟：原文件还在、隐私空间里也多一份，用户以为藏起来了，其实没有。</p>
 */
public final class VaultFlow {

    private VaultFlow() {}

    /**
     * @param files 待移入的本地文件（目录会被跳过并计入失败）
     * @param after 结束回调，主线程执行（成功、失败、取消都会调）
     */
    public static void moveIn(final Activity a, final List<File> files, final Runnable after) {
        if (a == null) return;
        if (files == null || files.isEmpty()) {
            toast(a, a.getString(R.string.vault_move_none));
            return;
        }
        final File dir = VaultStore.defaultDir();
        if (!VaultStore.exists(dir)) {
            toast(a, a.getString(R.string.vault_move_need_setup));
            return;
        }

        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setHint(R.string.vault_pw_hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        final AlertDialog dlg = new HfDialogBuilder(a)
                .setTitle(R.string.vault_move_title)
                .setView(et)
                .setPositiveButton(R.string.vault_btn_unlock, null)
                .setNegativeButton(R.string.common_cancel, null)
                .create();
        // 覆写确定：口令为空时不关框，否则用户点了确定、框没了、什么也没发生
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String p = et.getText().toString();
                                if (p.isEmpty()) {
                                    toast(a, a.getString(R.string.vault_pw_short));
                                    return;
                                }
                                dlg.dismiss();
                                run(a, dir, files, p, after);
                            }
                        });
            }
        });
        dlg.show();
    }

    private static void run(final Activity a, final File dir, final List<File> files,
            final String password, final Runnable after) {
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage(a.getString(R.string.vault_move_running));
        pd.setCanceledOnTouchOutside(false);
        pd.setCancelable(true);
        final AtomicBoolean cancel = new AtomicBoolean(false);
        pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { cancel.set(true); }
        });
        pd.show();

        new Thread(new Runnable() {
            public void run() {
                VaultCrypto.Master m = null;
                String fatal = null;
                try {
                    m = VaultStore.unlock(dir, password);
                } catch (SecurityException e) {
                    fatal = a.getString(R.string.vault_wrong_pw);
                } catch (Exception e) {
                    fatal = msg(e);
                }
                if (m == null) {
                    final String f = fatal;
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            dismiss(pd);
                            toast(a, f);
                            if (after != null) after.run();
                        }
                    });
                    return;
                }

                int ok = 0, failed = 0;
                String firstErr = null;
                for (int i = 0; i < files.size(); i++) {
                    if (cancel.get()) break;
                    File f = files.get(i);
                    if (f == null || !f.isFile()) {
                        failed++;
                        if (firstErr == null) firstErr = a.getString(R.string.vault_move_dir_skip);
                        continue;
                    }
                    InputStream in = null;
                    VaultIndex.Entry entry = null;
                    try {
                        in = new FileInputStream(f);
                        entry = VaultStore.add(dir, m, in, f.length(), f.getName(),
                                f.lastModified(), new VaultCrypto.Progress() {
                                    public boolean on(long d, long t) { return !cancel.get(); }
                                });
                        VaultStore.closeQuiet(in);
                        in = null;
                        if (!f.delete()) {
                            // 删不掉原文件：把刚写进去的密文撤掉，别留一份「双份文件」的假象
                            VaultStore.remove(dir, m, entry.id);
                            throw new IOException(a.getString(R.string.vault_move_keep_fail));
                        }
                        ok++;
                    } catch (VaultCrypto.Cancelled c) {
                        if (entry != null) {
                            try { VaultStore.remove(dir, m, entry.id); } catch (Exception ignored) {
                                firstErr = firstErr == null ? msg(ignored) : firstErr;
                            }
                        }
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
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        dismiss(pd);
                        if (fok > 0 && ffailed == 0) toast(a, a.getString(R.string.vault_move_ok, fok));
                        else if (fok > 0) toast(a, a.getString(R.string.vault_move_partial, fok, ffailed));
                        else if (fcancel) toast(a, a.getString(R.string.vault_cancel));
                        else toast(a, a.getString(R.string.vault_move_fail,
                                ferr == null ? "" : ferr));
                        if (after != null) after.run();
                    }
                });
            }
        }, "vault-move").start();
    }

    private static void dismiss(ProgressDialog pd) {
        try {
            pd.dismiss();
        } catch (Exception e) {
            // Activity 已经销毁：dismiss 会抛 WindowLeaked，这里没有补救手段
            return;
        }
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }

    private static String msg(Throwable t) {
        if (t == null) return "";
        String s = t.getMessage();
        return s == null || s.isEmpty() ? t.getClass().getSimpleName() : s;
    }
}
