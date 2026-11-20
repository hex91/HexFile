package com.hex91.file.controller;

import com.hex91.file.R;
import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.ArchiveWrite;
import com.hex91.file.core.AtomicReplace;
import com.hex91.file.fs.LinkUtil;
import com.hex91.file.fs.TreeOps;
import com.hex91.file.fs.ZipUtil;
import com.hex91.file.ui.ArchiveText;
import com.hex91.file.ui.HfDialogBuilder;
import com.hex91.file.ui.ThemeManager;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * 「往压缩包里加东西」这一簇（v2.5.0，O-22 刀2）。
 *
 * <p>从 {@code MainActivity} 整段搬来，逻辑一行未改；只把「取上下文 / 回主线程 / 调宿主能力」
 * 换成 {@link Host}。搬的是五个方法：{@link #addToArchive}、{@link #askAddToArchive}、
 * {@link #writeTree}、{@link #askCompress}，以及 {@code hasSmali}（已下沉到
 * {@link com.hex91.file.fs.TreeOps#hasSmali}，它纯粹是目录遍历，与界面无关）。</p>
 *
 * <p>搬动的三条硬约束（改错就是「加进去的东西出现两次 / 源文件被删」）：</p>
 * <ol>
 *   <li>{@code move=true} 时删源必须在写入成功之后，且只能走
 *       {@link TreeOps#deleteOrTrash}（先判 isLink）—— R-09 的修复点在这里；</li>
 *   <li>{@code writeTree} 遍历必须用 {@link LinkUtil#listNoFollow}，链接不进包；</li>
 *   <li>APK 目标走 {@link ApkEditSession#stageAdd} 登记，循环结束后统一 {@code flushRegen}
 *       才能写回原包。</li>
 * </ol>
 */
public final class ArchiveAddController {

    /** 控制器只通过这组能力与宿主打交道 */
    public interface Host {
        Context ctx();

        void toast(String s);

        /** runOnUiThread + 存活判断（原 runOnUiThread 的直接替代） */
        void ui(Runnable r);

        /** 当前活动窗格（原 {@code active()}） */
        Pane active();

        void showArchiveDir(Pane p);

        void refreshAll();

        boolean ensureArchiveWritable(File f);
    }

    private final Host host;

    public ArchiveAddController(Host host) {
        this.host = host;
    }

    /** 资源文案：控制器没有 Context，统一从 host 取（与 {@link ArchiveController} 同款） */
    private String s(int id) {
        return host.ctx().getString(id);
    }

    private String s(int id, Object... args) {
        return host.ctx().getString(id, args);
    }

    /**
     * 写文件/目录进压缩包。
     *
     * @param nameOverride 非空 = 单源自定义条目名/目录前缀
     * @param move         成功后删源
     */
    public void addToArchive(final File[] sources, final Pane archPane,
                             final String nameOverride, final boolean move) {
        if (!host.ensureArchiveWritable(archPane.archiveFile)) return;
        host.toast(s(R.string.arch_writing));
        final boolean apkMode = ApkEditSession.isApk(archPane.archiveFile);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                for (File f : sources) {
                    String root = sources.length == 1 && nameOverride != null
                            ? nameOverride : f.getName();
                    try {
                        if (f.isDirectory()) {
                            // 目录整树写入：root 作为压缩包内前缀
                            writeTree(host.ctx(), f, root, archPane.archiveFile, apkMode);
                        } else if (apkMode) {
                            // v1.66.8：APK → 会话登记（加 lib/assets 文件等），重打包写回原包
                            ApkEditSession.stageAdd(host.ctx(),
                                    archPane.archiveFile, root, f);
                        } else {
                            if (!ArchiveWrite.writeEntry(archPane.archiveFile, root, f)) {
                                err = AtomicReplace.failMessage(s(R.string.arch_write_fail, root));
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        err = s(R.string.arch_err_with_root, root, ArchiveText.errorText(host.ctx(), t));
                        break;
                    }
                }
                if (apkMode && err == null) {
                    try {
                        ApkEditSession.flushRegen(host.ctx(), archPane.archiveFile);
                    } catch (Throwable t) {
                        err = ArchiveText.errorText(host.ctx(), t);
                    }
                }
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            host.toast(s(R.string.arch_fail, ferr));
                            return;
                        }
                        if (apkMode) {
                            host.toast((move ? s(R.string.arch_moved_in) : s(R.string.arch_copied_in))
                                    + s(R.string.arch_written_back));
                        } else {
                            host.toast(move ? s(R.string.arch_moved_in) : s(R.string.arch_copied_in));
                        }
                        if (move) {
                            // R-09：必须走 TreeOps —— 它先判 isLink 再判 isDirectory，
                            // 源目录里的软链只删链本身，不会顺着链接删掉目标内容
                            for (File f : sources) TreeOps.deleteOrTrash(f);
                        }
                        host.showArchiveDir(archPane);
                        host.refreshAll();
                    }
                });
            }
        }, "arch-add").start();
    }

    /**
     * 把目录 dir 整树写入归档，条目前缀 = prefix（如 "myfolder" → myfolder/a.txt）。
     *
     * @param apkMode APK 目标：条目登记进 {@link ApkEditSession}（调用方随后 flushRegen），
     *                否则原地写入
     */
    public static void writeTree(Context ctx, File dir, String prefix, File archive,
                                 boolean apkMode) throws java.io.IOException {
        // R-09：与 R-01 的打包策略一致 —— 链接不进压缩包，遍历时直接过滤
        File[] kids = LinkUtil.listNoFollow(dir);
        if (kids == null) return;
        for (File k : kids) {
            String entry = prefix + "/" + k.getName();
            if (k.isDirectory()) {
                writeTree(ctx, k, entry, archive, apkMode);
            } else if (apkMode) {
                ApkEditSession.stageAdd(ctx, archive, entry, k);
            } else {
                if (!ArchiveWrite.writeEntry(archive, entry, k)) {
                    // static 方法里没有 host，直接问传进来的 ctx 要文案
                    throw new RuntimeException(AtomicReplace.failMessage(
                            ctx.getString(R.string.arch_write_fail, entry)));
                }
            }
        }
    }

    /** 复制/移动文件或目录进压缩包：可自定义目标条目名（含后缀，支持子目录路径） */
    public void askAddToArchive(final File src, final Pane archPane, final boolean move) {
        if (!host.ensureArchiveWritable(archPane.archiveFile)) return;
        final Context ctx = host.ctx();
        final EditText et = new EditText(ctx);
        et.setText(src.getName());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(ctx)
                .setTitle(s(R.string.arch_xfer_title,
                        move ? s(R.string.arch_word_move) : s(R.string.arch_word_copy),
                        archPane.archiveFile.getName()))
                .setMessage(s(R.string.arch_xfer_msg))
                .setView(et)
                .setPositiveButton(move ? s(R.string.arch_word_move) : s(R.string.arch_word_copy),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                String target = et.getText().toString().trim()
                                        .replace("\\", "/");
                                while (target.startsWith("/")) target = target.substring(1);
                                while (target.endsWith("/")) {
                                    target = target.substring(0, target.length() - 1);
                                }
                                if (target.isEmpty()) {
                                    host.toast(s(R.string.arch_name_empty));
                                    return;
                                }
                                addToArchive(new File[]{src}, archPane, target, move);
                            }
                        })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 压缩为 ZIP（可选 AES / 兼容 ZipCrypto 加密） */
    public void askCompress(final File f) {
        final Context ctx = host.ctx();
        final File out = new File(host.active().dir, f.getName() + ".zip");
        final float den = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * den), (int) (8 * den), (int) (24 * den), (int) (8 * den));
        final EditText pw = new EditText(ctx);
        pw.setHint(s(R.string.arch_zip_pw_hint));
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pw);
        // 加密方式选择：AES（需 7-Zip/ZArchiver）或 兼容 ZipCrypto（系统可直接解）
        final String[] modes = {
                s(R.string.arch_zip_aes256),
                "AES-192",
                "AES-128",
                s(R.string.arch_zip_compat)};
        final int[] sel = {0};
        final TextView stv = new TextView(ctx);
        stv.setText(s(R.string.arch_zip_method, modes[sel[0]]));
        stv.setTextSize(15);
        stv.setTextColor(ThemeManager.text(ctx));
        stv.setPadding(0, (int) (10 * den), 0, (int) (4 * den));
        stv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new HfDialogBuilder(ctx)
                        .setTitle(s(R.string.arch_zip_method_title))
                        .setSingleChoiceItems(modes, sel[0],
                                new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        sel[0] = which;
                                        stv.setText(s(R.string.arch_zip_method, modes[which]));
                                        d.dismiss();
                                    }
                                })
                        .show();
            }
        });
        root.addView(stv);
        new HfDialogBuilder(ctx)
                .setTitle(s(R.string.mops_zip))
                .setMessage(s(R.string.arch_zip_msg, f.getName(), out.getPath()))
                .setView(root)
                .setPositiveButton(s(R.string.arch_zip_go), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        java.util.List<File> src = new java.util.ArrayList<File>();
                        src.add(f);
                        String p = pw.getText().toString();
                        boolean ok;
                        if (p.isEmpty()) {
                            ok = ZipUtil.createZip(out, src);
                        } else if (sel[0] == 3) {
                            ok = ZipUtil.createZipEncryptedCompat(out, src, p);
                        } else {
                            ok = ZipUtil.createZipEncrypted(out, src, p, 3 - sel[0]); // 0→256,1→192,2→128
                        }
                        if (ok) {
                            host.toast(s(R.string.arch_zip_done, out.getName()));
                        } else {
                            // 压缩是「新建文件」，不经过原子替换，所以这里没有 lastError 可用；
                            // 最常见的两种失败：源全是软链接（会被跳过，不写进包）、目标不可写
                            host.toast(s(R.string.arch_zip_fail));
                        }
                        host.refreshAll();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }
}
