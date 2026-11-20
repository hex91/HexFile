package com.hex91.file.controller;

import com.hex91.file.core.AtomicReplace;
import com.hex91.file.core.ByteIO;
import com.hex91.file.core.EntryNames;
import com.hex91.file.fs.FileKind;
import com.hex91.file.utils.GridAction;
import com.hex91.file.R;
import com.hex91.file.MainActivity;
import com.hex91.file.ui.ArchiveText;
import com.hex91.file.ui.AxmlSyncText;
import com.hex91.file.ui.ArscEditorActivity;
import com.hex91.file.ui.ArscPlusActivity;
import com.hex91.file.ui.DexEditorActivity;
import com.hex91.file.ui.HexViewerActivity;
import com.hex91.file.ui.HfDialogBuilder;
import com.hex91.file.ui.ImageViewerActivity;
import com.hex91.file.ui.ManifestEditorActivity;
import com.hex91.file.ui.SignPromptActivity;
import com.hex91.file.ui.SmaliReport;
import com.hex91.file.ui.TextEditorActivity;
import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.ArchivePasswords;
import com.hex91.file.apk.ArchiveUtil;
import com.hex91.file.apk.ArchiveWrite;
import com.hex91.file.apk.AxmlFile;
import com.hex91.file.apk.SmaliTool;
import com.hex91.file.fs.FileItem;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.WorkDir;
import com.hex91.file.fs.ZipUtil;
import com.hex91.file.core.AtomicReplace;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 压缩包内浏览控制器（v2.0.0，O-15 刀3）：打开/关闭、密码、条目操作、查看编辑、提取、DEX/ARSC。
 *
 * <p>方法体整段从 MainActivity 搬来，逻辑一行未改；只把「取上下文 / 回主线程 / 调宿主能力」
 * 三类换成 {@link Host}：{@code this} → {@code host.ctx()}、{@code runOnUiThread} →
 * {@code host.ui}（内部统一判 alive）、{@code startActivity} → {@code host.open}。
 * MainActivity 只留 11 个同名壳方法，给目录控制器、批量操作和长按菜单调用。</p>
 *
 * <p>三个必须原样保留的行为（改错就会「包内点不动」）：</p>
 * <ol>
 *   <li>{@code showArchiveDir} 回来时校验 {@code p.archive != a || !prefix.equals(p.archPrefix)}；</li>
 *   <li>{@code openArchiveInPane} 同包重开时只 close 旧 Archive、不重置 {@code archPrefix}；</li>
 *   <li>{@code archPwError} 先 {@code ArchivePasswords.clear} 再弹框。</li>
 * </ol>
 */
public final class ArchiveController {

    /** 控制器只通过这组能力与宿主打交道 */
    public interface Host {
        android.content.Context ctx();

        boolean alive();

        void toast(String s);

        /** runOnUiThread + alive 检查（原 runOnUiThread 的直接替代） */
        void ui(Runnable r);

        void open(Intent i);

        Pane other();

        /** push=true 等价原来的两参 loadDir */
        void loadDir(Pane p, File dir, boolean push);

        void refreshAll();

        void updateHeader();

        void exitSelection(Pane p);

        void showTextDialog(String title, String text);

        void showGridMenu(String title, java.util.List<String> ops,
                java.util.List<Integer> acts, GridAction cb);

        boolean ensureArchiveWritable(File f);

        void convertRarToZip(File f);
    }

    private final Host host;

    /** 当前显示中的密码弹窗（防叠加：新弹窗顶掉旧的） */
    private AlertDialog mPwDialog;

    public ArchiveController(Host host) {
        this.host = host;
    }

    /** 资源文案。控制器没有 Context，统一从 host 取；带参重载只为省掉一长串 getString(id, args) */
    private String s(int id) {
        return host.ctx().getString(id);
    }

    private String s(int id, Object... args) {
        return host.ctx().getString(id, args);
    }

    public void closeArchiveQuiet(Pane p) {
        // v1.66.0：退出 APK 浏览时，有未签名修改版 → 统一弹窗（不签名/内置签名文件/自有签名文件）
        if (p.archiveFile != null && ApkEditSession.isApk(p.archiveFile)
                && ApkEditSession.needsSignPrompt(p.archiveFile)) {
            SignPromptActivity.launch(host.ctx(), p.archiveFile);
        }
        if (p.archive != null) {
            try { p.archive.close(); } catch (Throwable ignored) {}
        }
        p.archive = null;
        p.archiveFile = null;
        p.archPrefix = "";
    }

    /** 在指定窗格内打开压缩包（替代全屏 ZipBrowserActivity）；加密时弹密码框重试 */
    public void openArchiveInPane(final Pane p, final File f) {
        host.toast(s(R.string.arch_opening));
        new Thread(new Runnable() {
            public void run() {
                ArchiveUtil.Archive a = null;
                Throwable err = null;
                boolean needPw = false;
                try {
                    a = ArchiveUtil.open(f, ArchivePasswords.get(f));
                } catch (ArchiveUtil.NeedsPasswordException np) {
                    needPw = true;
                    android.util.Log.w("HexFile", "arch-open NEED-PW " + f.getName() + " pw="
                            + (ArchivePasswords.get(f) != null) + " : " + np.getMessage());
                } catch (Throwable t) {
                    err = t;
                    android.util.Log.w("HexFile", "arch-open ERR " + f.getName() + " : " + t);
                }
                if (a != null) {
                    android.util.Log.w("HexFile", "arch-open OK " + f.getName()
                            + " fmt=" + a.format() + " pw=" + (ArchivePasswords.get(f) != null));
                }
                final ArchiveUtil.Archive fa = a;
                final boolean fNeedPw = needPw;
                final String ferr = err == null ? null
                        : err.getClass().getSimpleName() + ": " + err.getMessage();
                host.ui(new Runnable() {
                    public void run() {
                        if (fNeedPw) {
                            // 之前已存过密码还失败 = 密码错误；清掉并提示重输
                            boolean wrong = ArchivePasswords.get(f) != null;
                            ArchivePasswords.clear(f);
                            promptArchivePassword(p, f, wrong);
                            return;
                        }
                        if (fa == null) { host.toast(s(R.string.arch_open_fail, ferr)); return; }
                        boolean same = p.archiveFile != null
                                && p.archiveFile.getAbsolutePath().equals(f.getAbsolutePath());
                        if (!same) closeArchiveQuiet(p);
                        else if (p.archive != null && p.archive != fa) {
                            try { p.archive.close(); } catch (Throwable ignored) {}
                        }
                        p.archiveFile = f;
                        p.archive = fa;
                        p.archPrefix = "";
                        if (p.adapter.selectionMode) host.exitSelection(p);
                        showArchiveDir(p);
                    }
                });
            }
        }, "arch-open").start();
    }

    /** 加密归档密码输入框（无后续动作重载） */
    void promptArchivePassword(final Pane p, final File f, final boolean wrong) {
        promptArchivePassword(p, f, wrong, null);
    }

    /** 只记密码并自动重试转存（转存流程用） */
    public void promptArchivePasswordSave(final File f) {
        promptArchivePassword(null, f, ArchivePasswords.get(f) != null, new Runnable() {
            public void run() { host.convertRarToZip(f); }   // 密码记住后自动重试转存
        });
    }

    /** 提取/编辑等后台操作密码类失败 → 弹密码框重开归档，返回 true 表示已处理 */
    public boolean archPwError(final Pane p, Throwable t) {
        if (t == null) return false;
        if (ArchiveUtil.cryptoError(t)) {
            final boolean wrong = p.archiveFile != null && ArchivePasswords.get(p.archiveFile) != null;
            if (p.archiveFile != null) ArchivePasswords.clear(p.archiveFile);
            host.ui(new Runnable() {
                public void run() {
                    if (p.archiveFile != null) promptArchivePassword(p, p.archiveFile, wrong);
                }
            });
            return true;
        }
        return false;
    }

    /** 刷新窗格当前压缩包子级列表（后台列条目，回主线程渲染） */
    public void showArchiveDir(final Pane p) {
        final ArchiveUtil.Archive a = p.archive;
        final File zf = p.archiveFile;
        final String prefix = p.archPrefix == null ? "" : p.archPrefix;
        if (a == null || zf == null) return;
        new Thread(new Runnable() {
            public void run() {
                List<ArchiveUtil.Entry> entries = null;
                String err = null;
                try { entries = a.listChildren(prefix); }
                catch (Throwable t) { err = ArchiveText.errorText(host.ctx(), t); }
                final List<ArchiveUtil.Entry> fe = entries;
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (p.archive != a || !prefix.equals(p.archPrefix)) return; // 已切走
                        if (p.adapter.selectionMode) host.exitSelection(p);
                        List<FileItem> list = new ArrayList<FileItem>();
                        if (!prefix.isEmpty()) list.add(FileItem.archUp());
                        if (fe != null) {
                            for (ArchiveUtil.Entry e : fe) {
                                if (!p.adapter.showHidden && e.name.startsWith(".")) continue;
                                list.add(new FileItem(e.full, e.name, e.dir, e.size));
                            }
                        }
                        p.adapter.setItems(list);
                        host.updateHeader();
                        // v1.79.0：下拉刷新走的是这条异步路径，列表真正拿到数据后收起转圈
                        if (p.pull != null && p.pull.isRefreshing()) p.pull.finishRefresh();
                        if (ferr != null && fe == null) host.toast(s(R.string.arch_list_fail, ferr));
                    }
                });
            }
        }, "arch-list").start();
    }

    /** 压缩包内返回上级；已到根则退回普通目录模式 */
    public void archGoUp(Pane p) {
        String pre = p.archPrefix == null ? "" : p.archPrefix;
        if (pre.isEmpty()) {
            closeArchiveQuiet(p);
            host.loadDir(p, p.dir, true);
            return;
        }
        int cut = pre.lastIndexOf('/', pre.length() - 2);
        p.archPrefix = cut < 0 ? "" : pre.substring(0, cut + 1);
        showArchiveDir(p);
    }

    /** 加密归档密码输入框：确定后记住密码并重开窗格或执行 afterOk；取消则清除密码。
     *  wrong=true 表示上一轮密码已验证失败，标题与提示变为"密码错误，请重输" */
    void promptArchivePassword(final Pane p, final File f, final boolean wrong,
            final Runnable afterOk) {
        android.util.Log.w("HexFile", "pw-dialog show wrong=" + wrong + " file=" + f.getName()
                + " pane=" + (p != null));
        if (mPwDialog != null) { try { mPwDialog.dismiss(); } catch (Throwable ignored) {} }
        final EditText et = new EditText(host.ctx());
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // 右侧眼睛图标：点击切换密码显示/隐藏
        final android.graphics.drawable.Drawable eyeOn =
                host.ctx().getResources().getDrawable(R.drawable.ic_eye);
        final android.graphics.drawable.Drawable eyeOff =
                host.ctx().getResources().getDrawable(R.drawable.ic_eye_off);
        int iconSize = Math.max(eyeOn.getIntrinsicWidth(), 48);
        eyeOn.setBounds(0, 0, iconSize, iconSize);
        eyeOff.setBounds(0, 0, iconSize, iconSize);
        et.setCompoundDrawables(null, null, eyeOn, null);
        et.setCompoundDrawablePadding(20);
        et.setPadding(et.getPaddingLeft(), et.getPaddingTop(),
                iconSize + 40, et.getPaddingBottom());
        final boolean[] pwVisible = {false};
        et.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP) return false;
                int dw = iconSize + 20;
                if (e.getX() < et.getWidth() - et.getPaddingRight() - dw) return false;
                pwVisible[0] = !pwVisible[0];
                int sel = et.getSelectionEnd();
                if (pwVisible[0]) {
                    et.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    et.setCompoundDrawables(null, null, eyeOff, null);
                } else {
                    et.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    et.setCompoundDrawables(null, null, eyeOn, null);
                }
                et.setSelection(sel < 0 ? et.getText().length() : sel);
                return true;
            }
        });
        AlertDialog dlg = new HfDialogBuilder(host.ctx())
                .setTitle(wrong ? R.string.arch_pw_wrong_title : R.string.arch_pw_title)
                .setMessage(wrong ? s(R.string.arch_pw_wrong_msg, f.getName())
                        : s(R.string.arch_pw_msg, f.getName()))
                .setView(et)
                .setPositiveButton(R.string.common_confirm, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String pw = et.getText().toString();
                        if (pw.isEmpty()) { host.toast(s(R.string.arch_pw_empty)); ArchivePasswords.clear(f); return; }
                        ArchivePasswords.put(f, pw);
                        if (p != null) openArchiveInPane(p, f);     // 带密码重开；密码错会再次弹框提示
                        else if (afterOk != null) afterOk.run();
                    }
                })
                .setNegativeButton(R.string.common_cancel, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        ArchivePasswords.clear(f);
                        host.toast(s(R.string.arch_pw_cancel));
                    }
                })
                .create();
        mPwDialog = dlg;
        dlg.show();
    }

    /** 点击压缩包内条目：目录进入 / 文本直接编辑 / 其它弹提取菜单 */
    public void onArchItemClick(final Pane p, final FileItem it) {
        if (it.archDir) {
            p.archPrefix = it.archFull;
            showArchiveDir(p);
            return;
        }
        // 专属路由优先于 isTextName，否则 .xml 会被文本编辑截胡（如 AndroidManifest.xml 进不了编辑菜单）
        String low = it.archShort.toLowerCase();
        if (low.equals("androidmanifest.xml")) { askArchManifestAction(p, it); return; }
        if (low.endsWith(".dex")) { askArchDexAction(p, it); return; }
        if (low.endsWith(".arsc")) { openArchArsc(p, it); return; }
        if (FileKind.isTextName(low, it.archSize)) {
            editArchEntry(p, it);
            return;
        }
        if (FileKind.isImageExt(low)) {
            viewArchEntry(p, it);
            return;
        }
        final boolean canToArch = host.other().archiveFile != null;
        java.util.List<String> ops = new ArrayList<String>();
        ops.add(s(R.string.arch_op_extract_here));
        ops.add(s(R.string.arch_op_extract_other));
        if (canToArch) ops.add(s(R.string.arch_op_write_other));
        new HfDialogBuilder(host.ctx())
                .setTitle(it.archShort)
                .setItems(ops.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) extractArchEntry(p, it.archPath(), p);
                                else if (w == 1) extractArchEntry(p, it.archPath(), host.other());
                                else askAddEntriesToArchive(p,
                                        java.util.Collections.singletonList(it), host.other(), false);
                            }
                        })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** APK 内点击 AndroidManifest.xml：改属性走 Manifest 编辑器++，文本编辑走往返编译 */
    void askArchManifestAction(final Pane p, final FileItem it) {
        new HfDialogBuilder(host.ctx())
                .setTitle(it.archShort)
                .setItems(new String[] {
                        s(R.string.arch_op_manifest_pp),
                        s(R.string.arch_op_xml_edit),
                        s(R.string.arch_op_xml_view),
                        s(R.string.arch_op_hex),
                        s(R.string.arch_op_extract_here) },
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    host.open(new Intent(host.ctx(),
                                            ManifestEditorActivity.class)
                                            .putExtra(ManifestEditorActivity.EXTRA_APK,
                                                    p.archiveFile.getAbsolutePath()));
                                } else if (w == 1) {
                                    editArchEntryXml(p, it);
                                } else if (w == 2) {
                                    editArchEntry(p, it);
                                } else if (w == 3) {
                                    hexArchEntry(p, it);
                                } else {
                                    extractArchEntry(p, it.archPath(), p);
                                }
                            }
                        })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /**
     * Manifest 文本编辑（往返编译）：提取二进制 AXML → 转 pretty 文本 → 编辑器打开；
     * 保存时 TextEditorActivity 走 EXTRA_RT_* 往返编译写回 APK。
     */
    void editArchEntryXml(final Pane p, final FileItem it) {
        final File zip = p.archiveFile;
        final File tmpDir = new File(host.ctx().getCacheDir(), "arch_edit_xml");
        host.toast(s(R.string.arch_reading));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : s(R.string.arch_extract_fail));
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(s(R.string.arch_read_fail, ferr)); return; }
                        if (!isAxml(tmpFile)) {
                            host.toast(s(R.string.arch_not_axml));
                            return;
                        }
                        try {
                            AxmlFile ax = AxmlFile.parse(ByteIO.readAll(tmpFile));
                            File pretty = new File(tmpDir, it.archPath() + ".pretty.xml");
                            java.io.PrintWriter pw = new java.io.PrintWriter(
                                    new java.io.OutputStreamWriter(
                                            new FileOutputStream(pretty), "UTF-8"));
                            pw.print(ax.prettyXml());
                            pw.close();
                            Intent i = new Intent(host.ctx(), TextEditorActivity.class);
                            i.putExtra(TextEditorActivity.EXTRA_FILE, pretty.getAbsolutePath());
                            i.putExtra(TextEditorActivity.EXTRA_RT_AXML, tmpFile.getAbsolutePath());
                            i.putExtra(TextEditorActivity.EXTRA_RT_APK, zip.getAbsolutePath());
                            host.open(i);
                        } catch (Throwable t) {
                            host.toast(s(R.string.arch_parse_fail,
                                    AxmlSyncText.errorText(host.ctx(), t)));
                        }
                    }
                });
            }
        }, "arch-edit-xml").start();
    }

    /** 提取压缩包内条目（含子树）到目标窗格目录（后台） */
    void extractArchEntry(final Pane p, final String entryName, final Pane dst) {
        host.toast(s(R.string.arch_extracting));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try { p.archive.extractEntry(entryName, dst.dir); }
                catch (Throwable t) {
                    if (archPwError(p, t)) return;      // 密码问题 → 弹框重开，用户重试
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr == null) {
                            host.toast(s(R.string.arch_extracted, dst.dir.getPath()));
                            if (dst.archiveFile == null) host.loadDir(dst, dst.dir, true);
                        } else host.toast(s(R.string.arch_extract_fail_detail, ferr));
                    }
                });
            }
        }, "arch-extract").start();
    }

    /** 解压整个压缩包到当前窗格目录（压缩包所在文件夹，即"此目录"），一步到位 */
    public void extractArchAll(final Pane p) {
        if (p.archive == null || p.archiveFile == null) return;
        final File outDir = p.dir;
        host.toast(s(R.string.arch_extract_all_running));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try { p.archive.extractAll(outDir); }
                catch (Throwable t) {
                    if (archPwError(p, t)) return;   // 密码问题 → 弹框重开，用户重试
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr == null) {
                            host.toast(s(R.string.arch_extract_all_done, outDir.getPath()));
                        } else {
                            host.toast(s(R.string.arch_extract_all_fail, ferr));
                        }
                    }
                });
            }
        }, "arch-extract-all").start();
    }

    /** 压缩包内文本条目 → 解到缓存后进编辑器（zip/apk 可写回） */
    void editArchEntry(final Pane p, final FileItem it) {
        final File zip = p.archiveFile;
        final File tmpDir = new File(host.ctx().getCacheDir(), "arch_edit");
        host.toast(s(R.string.arch_reading));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : s(R.string.arch_extract_fail));
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(s(R.string.arch_read_fail, ferr)); return; }
                        File openFile = tmpFile;
                        boolean converted = false;
                        // AXML 二进制（APK 内 AndroidManifest / res 下编译过的 xml）→ 转 pretty 文本查看
                        if (isAxml(tmpFile)) {
                            try {
                                AxmlFile ax = AxmlFile.parse(ByteIO.readAll(tmpFile));
                                File pretty = new File(tmpDir,
                                        it.archPath() + ".pretty.xml");
                                java.io.PrintWriter pw = new java.io.PrintWriter(
                                        new java.io.OutputStreamWriter(
                                                new FileOutputStream(pretty), "UTF-8"));
                                pw.print(ax.prettyXml());
                                pw.close();
                                openFile = pretty;
                                converted = true;
                            } catch (Throwable ignored) { }  // 解析失败按普通文本打开
                        }
                        Intent i = new Intent(host.ctx(), TextEditorActivity.class);
                        i.putExtra(TextEditorActivity.EXTRA_FILE, openFile.getAbsolutePath());
                        if (converted) {
                            // pretty 文本只落缓存、强制只读（文本编辑会破坏二进制 AXML；
                            // 要改 Manifest 请用「Manifest 编辑器++」）
                            i.putExtra(TextEditorActivity.EXTRA_READONLY, true);
                        } else if (ArchiveWrite.canWrite(zip)) {
                            i.putExtra(TextEditorActivity.EXTRA_ARCHIVE, zip.getAbsolutePath());
                            i.putExtra(TextEditorActivity.EXTRA_ENTRY, it.archPath());
                        } else {
                            i.putExtra(TextEditorActivity.EXTRA_EXPORT, p.dir.getAbsolutePath());
                        }
                        host.open(i);
                    }
                });
            }
        }, "arch-edit").start();
    }

    /** AXML 二进制判定：.xml 扩展名 + 文件头类型字 0x0002/0x0003（RES_XML_TYPE，LE） */
    private static boolean isAxml(File f) {
        if (!f.getName().toLowerCase().endsWith(".xml")) return false;
        FileInputStream in = null;
        try {
            byte[] h = new byte[4];
            in = new FileInputStream(f);
            int n = 0;
            while (n < 4) {
                int r = in.read(h, n, 4 - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 4) return false;
            int t = (h[0] & 0xFF) | ((h[1] & 0xFF) << 8);
            return t == 0x0002 || t == 0x0003;
        } catch (Throwable e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** 压缩包内点击 .dex → 操作菜单（MT 风格） */
    void askArchDexAction(final Pane p, final FileItem it) {
        // v1.66.5：属性/修复放点击弹窗（对标 NP 打开方式），长按菜单不再重复
        final java.util.List<String> ops = new ArrayList<String>();
        ops.add(s(R.string.arch_op_dex_editor));
        ops.add(s(R.string.arch_op_smali));
        ops.add(s(R.string.arch_op_dex_props));
        final boolean isApk = ApkEditSession.isApk(p.archiveFile);
        // 分派用下标，不再比对文案：i18n 之后 ops 里装的是当前语言的字串，
        // 原来的 op.startsWith("Dex 编辑器++") 在英文下一条都对不上
        final int iFix = isApk ? ops.size() : -1;
        if (isApk) ops.add(s(R.string.arch_op_dex_fix));
        final int iHex = ops.size();
        ops.add(s(R.string.arch_op_hex));
        final int iExtract = ops.size();
        ops.add(s(R.string.arch_op_extract_here));
        new HfDialogBuilder(host.ctx())
                .setTitle(it.archShort)
                .setItems(ops.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    Intent i = new Intent(host.ctx(),
                                            DexEditorActivity.class);
                                    i.putExtra(DexEditorActivity.EXTRA_APK,
                                            p.archiveFile.getAbsolutePath());
                                    i.putExtra(DexEditorActivity.EXTRA_DEXES,
                                            new String[]{it.archPath()});
                                    host.open(i);
                                } else if (w == 1) {
                                    decompileArchDex(p, it);
                                } else if (w == 2) {
                                    showArchDexProps(p, it);
                                } else if (w == iFix) {
                                    askArchDexFix(p, it);
                                } else if (w == iHex) {
                                    hexArchEntry(p, it);
                                } else if (w == iExtract) {
                                    extractArchEntry(p, it.archPath(), p);
                                }
                            }
                        })
                .show();
    }

    /** v1.65.0：压缩包内点击 .arsc → 弹选择框（用哪个编辑器打开），不直接进入修改 */
    void openArchArsc(final Pane p, final FileItem it) {
        new HfDialogBuilder(host.ctx())
                .setTitle(it.archShort)
                .setItems(new String[] {
                        s(R.string.arch_op_arsc),
                        s(R.string.arch_op_arscpp) },
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    host.open(new Intent(host.ctx(),
                                            ArscEditorActivity.class)
                                            .putExtra(ArscEditorActivity.EXTRA_APK,
                                                    p.archiveFile.getAbsolutePath())
                                            .putExtra(ArscEditorActivity.EXTRA_ENTRY,
                                                    it.archPath()));
                                } else {
                                    openArchArscPlus(p, it);
                                }
                            }
                        })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** v1.64.0：压缩包内 .arsc → ARSC++ 编辑（树状列表 + values XML 源码编辑） */
    void openArchArscPlus(final Pane p, final FileItem it) {
        Intent i = new Intent(host.ctx(), ArscPlusActivity.class);
        i.putExtra(ArscPlusActivity.EXTRA_APK, p.archiveFile.getAbsolutePath());
        i.putExtra(ArscPlusActivity.EXTRA_ENTRY, it.archPath());
        host.open(i);
    }

    /** 压缩包内单个 dex → 反编译为 smali（/sdcard/HexFile/Smali/<apk名>/<dex名>/）并跳转 */
    void decompileArchDex(final Pane p, final FileItem it) {
        final File apk = p.archiveFile;
        final File cacheTmp = new File(host.ctx().getCacheDir(), "arch_dex_tmp");
        final String base = FileKind.stripExt(new File(it.archPath()).getName());
        final File outRoot = new File(WorkDir.base(),
                "Smali/" + FileKind.stripExt(apk.getName()) + "/" + base);
        host.toast(s(R.string.arch_decompiling));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(cacheTmp);
                    cacheTmp.mkdirs();
                    if (!ZipUtil.extractEntry(apk, it.archPath(), cacheTmp))
                        throw new IllegalStateException(s(R.string.arch_extract_fail_detail, it.archPath()));
                    File tmpDex = new File(cacheTmp, it.archPath());
                    err = SmaliTool.decompile(tmpDex, outRoot, 4);
                } catch (Throwable t) {
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                FileOps.deleteRecursive(cacheTmp);
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(s(R.string.arch_decompile_fail, SmaliReport.err(host.ctx(), ferr))); return; }
                        host.toast(s(R.string.arch_decompiled, outRoot.getPath()));
                        Intent i = new Intent(host.ctx(), MainActivity.class);
                        i.putExtra("dir", outRoot.getAbsolutePath());
                        host.open(i);
                    }
                });
            }
        }, "arch-dex-dec").start();
    }

    /** 压缩包内 dex → 提取到缓存后查看属性（v1.66.5） */
    void showArchDexProps(final Pane p, final FileItem it) {
        final File apk = p.archiveFile;
        final File tmpDir = new File(host.ctx().getCacheDir(), "arch_dexprops");
        host.toast(s(R.string.arch_reading));
        new Thread(new Runnable() {
            public void run() {
                String info = null, err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    if (!ZipUtil.extractEntry(apk, it.archPath(), tmpDir))
                        throw new IllegalStateException(s(R.string.arch_extract_fail_detail, it.archPath()));
                    info = SmaliTool.dexProps(new File(tmpDir, it.archPath()));
                } catch (Throwable t) {
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                FileOps.deleteRecursive(tmpDir);
                final String fInfo = info, fErr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (fErr != null) { host.toast(SmaliReport.err(host.ctx(), fErr)); return; }
                        if (fInfo == null) { host.toast(s(R.string.arch_not_dex)); return; }
                        host.showTextDialog(new File(it.archPath()).getName(),
                                SmaliReport.props(host.ctx(), fInfo));
                    }
                });
            }
        }, "arch-dex-props").start();
    }

    /** 压缩包内 dex 修复（v1.66.5，仅 APK 归档）：提取 → 修复 → 会话写回原包 */
    void askArchDexFix(final Pane p, final FileItem it) {
        final File apk = p.archiveFile;
        new HfDialogBuilder(host.ctx())
                .setTitle(R.string.arch_op_dex_fix)
                .setMessage(s(R.string.arch_dexfix_msg, it.archShort))
                .setPositiveButton(R.string.arch_dexfix_btn, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        host.toast(s(R.string.arch_dexfix_running));
                        new Thread(new Runnable() {
                            public void run() {
                                final File tmpDir = new File(host.ctx().getCacheDir(), "arch_dexfix");
                                String err = null;
                                StringBuilder rep = new StringBuilder();
                                try {
                                    FileOps.deleteRecursive(tmpDir);
                                    tmpDir.mkdirs();
                                    if (!ZipUtil.extractEntry(apk, it.archPath(), tmpDir))
                                        throw new IllegalStateException(s(R.string.arch_extract_fail_detail, it.archPath()));
                                    File dex = new File(tmpDir, it.archPath());
                                    err = SmaliTool.repairDex(dex, rep);
                                    if (err == null) {
                                        // v1.67.2：登记为「文件型修改」再统一重打包——
                                        // 不再把整个 dex 读成 byte[]（大 dex 的额外一份内存占用）
                                        ApkEditSession.stageAdd(host.ctx(), apk,
                                                it.archPath(), dex);
                                        ApkEditSession.flushRegen(host.ctx(), apk);
                                    }
                                } catch (Throwable t) {
                                    err = ArchiveText.errorText(host.ctx(), t);
                                }
                                FileOps.deleteRecursive(tmpDir);
                                final String msg = err != null
                                        ? s(R.string.arch_dexfix_fail, SmaliReport.err(host.ctx(), err))
                                        : s(R.string.arch_dexfix_done,
                                                SmaliReport.repairReport(host.ctx(), rep.toString()));
                                host.ui(new Runnable() {
                                    public void run() {
                                        host.toast(msg);
                                        host.refreshAll();
                                    }
                                });
                            }
                        }, "arch-dex-fix").start();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 压缩包内任意条目 → 提取到缓存后 HEX 查看 */
    void hexArchEntry(final Pane p, final FileItem it) {
        final File tmpDir = new File(host.ctx().getCacheDir(), "arch_hex");
        host.toast(s(R.string.arch_reading));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : s(R.string.arch_extract_fail));
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(s(R.string.arch_read_fail, ferr)); return; }
                        Intent i = new Intent(host.ctx(), HexViewerActivity.class);
                        i.putExtra(HexViewerActivity.EXTRA_FILE, tmpFile.getAbsolutePath());
                        host.open(i);
                    }
                });
            }
        }, "arch-hex").start();
    }

    /** 压缩包内图片条目 → 解到缓存后进查看器（只读展示，不写回） */
    void viewArchEntry(final Pane p, final FileItem it) {
        final File tmpDir = new File(host.ctx().getCacheDir(), "arch_view");
        host.toast(s(R.string.arch_reading));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    FileOps.deleteRecursive(tmpDir);
                    tmpDir.mkdirs();
                    p.archive.extractEntry(it.archPath(), tmpDir);
                } catch (Throwable t) {
                    if (archPwError(p, t)) return;
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final File tmpFile = new File(tmpDir, it.archPath());
                // v1.66.0：会话中有该条目未落地修改则取最新内容
                ApkEditSession.overlayPending(p.archiveFile, it.archPath(), tmpFile);
                final String ferr = (err == null && tmpFile.isFile()) ? null
                        : (err != null ? err : s(R.string.arch_extract_fail));
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(s(R.string.arch_read_fail, ferr)); return; }
                        Intent i = new Intent(host.ctx(), ImageViewerActivity.class);
                        i.putExtra(ImageViewerActivity.EXTRA_FILE, tmpFile.getAbsolutePath());
                        host.open(i);
                    }
                });
            }
        }, "arch-view").start();
    }

    /** 压缩包条目长按菜单（MT 式双列宫格） */
    public void showArchOpsMenu(final Pane p, final FileItem it) {
        final boolean canWrite = ArchiveWrite.canWrite(p.archiveFile);
        final boolean isText = FileKind.isTextName(it.archShort.toLowerCase(), it.archSize);
        final boolean canToArch = host.other().archiveFile != null;
        final int A_EDIT = 0, A_HERE = 1, A_OTHER = 2, A_DEL = 3, A_PROP = 4,
                A_TOARCH_C = 5, A_TOARCH_M = 6, A_DEXPP = 7, A_SMALI = 8, A_HEX = 9, A_ARSCPP = 10, A_MANIFEST = 11,
                A_ARSCPP2 = 12, A_RENAME = 13;
        String low = it.archShort.toLowerCase();
        final boolean isDex = low.endsWith(".dex");
        final boolean isArs = low.endsWith(".arsc");
        final boolean isManifest = low.equals("androidmanifest.xml");
        final java.util.List<String> ops = new ArrayList<String>();
        final java.util.List<Integer> acts = new ArrayList<Integer>();
        if (isManifest) { ops.add(s(R.string.arch_grid_manifest)); acts.add(A_MANIFEST); }
        if (isDex) { ops.add(s(R.string.arch_grid_dex)); acts.add(A_DEXPP); }
        if (isDex) { ops.add(s(R.string.arch_grid_smali)); acts.add(A_SMALI); }
        if (isArs) { ops.add(s(R.string.arch_grid_arsc)); acts.add(A_ARSCPP); }
        if (isArs) { ops.add(s(R.string.arch_grid_arscpp)); acts.add(A_ARSCPP2); }
        if (isText) { ops.add(s(R.string.arch_grid_edit)); acts.add(A_EDIT); }
        if (isDex || isArs) { ops.add(s(R.string.arch_op_hex)); acts.add(A_HEX); }
        ops.add(s(R.string.arch_op_extract_here)); acts.add(A_HERE);
        ops.add(s(R.string.arch_op_extract_other)); acts.add(A_OTHER);
        if (canToArch) { ops.add(s(R.string.arch_op_copy_to_arch)); acts.add(A_TOARCH_C); }
        // 移动到对面压缩包需要源也可写（写完后要删原条目）
        if (canToArch && canWrite) { ops.add(s(R.string.arch_op_move_to_arch)); acts.add(A_TOARCH_M); }
        if (canWrite && !ApkEditSession.isApk(p.archiveFile)) {
            ops.add(s(R.string.arch_op_rename)); acts.add(A_RENAME);   // v2.13.1（F-11）
        }
        if (canWrite) { ops.add(s(R.string.common_delete)); acts.add(A_DEL); }
        ops.add(s(R.string.arch_op_props)); acts.add(A_PROP);
        host.showGridMenu(it.archShort, ops, acts, new GridAction() {
            public void run(int act) {
                if (act == A_MANIFEST) {
                    host.open(new Intent(host.ctx(), ManifestEditorActivity.class)
                            .putExtra(ManifestEditorActivity.EXTRA_APK,
                                    p.archiveFile.getAbsolutePath()));
                } else if (act == A_DEXPP) {
                    Intent i = new Intent(host.ctx(), DexEditorActivity.class);
                    i.putExtra(DexEditorActivity.EXTRA_APK, p.archiveFile.getAbsolutePath());
                    i.putExtra(DexEditorActivity.EXTRA_DEXES, new String[]{it.archPath()});
                    host.open(i);
                } else if (act == A_SMALI) {
                    decompileArchDex(p, it);
                } else if (act == A_ARSCPP) {
                    openArchArsc(p, it);
                } else if (act == A_ARSCPP2) {
                    openArchArscPlus(p, it);
                } else if (act == A_HEX) {
                    hexArchEntry(p, it);
                } else if (act == A_EDIT) editArchEntry(p, it);
                else if (act == A_HERE) extractArchEntry(p, it.archPath(), p);
                else if (act == A_OTHER) extractArchEntry(p, it.archPath(), host.other());
                else if (act == A_RENAME) archRename(p, it);
                else if (act == A_DEL) archDelete(p, java.util.Collections.singletonList(it));
                else if (act == A_PROP) host.toast(s(R.string.arch_entry_info,
                        it.archFull, FileOps.formatSize(it.archSize)));
                else if (act == A_TOARCH_C) askAddEntriesToArchive(p,
                        java.util.Collections.singletonList(it), host.other(), false);
                else if (act == A_TOARCH_M) askAddEntriesToArchive(p,
                        java.util.Collections.singletonList(it), host.other(), true);
            }
        });
    }

    /**
     * 压缩包条目 → 对面压缩包（v1.15）：先把条目（含子树）提取到缓存，再写入目标归档。
     * nameOverride 非空 = 单条目自定义目标名；move = 写入后从源归档删除（需源可写）。
     */
    void askAddEntriesToArchive(final Pane src, final List<FileItem> entries,
            final Pane dst, final boolean move) {
        if (!host.ensureArchiveWritable(dst.archiveFile)) return;
        if (move && !ArchiveWrite.canWrite(src.archiveFile)) {
            host.toast(s(R.string.arch_readonly_move));
            addEntriesToArchive(src, entries, dst, null, false);
            return;
        }
        if (entries.size() == 1) {
            final FileItem it = entries.get(0);
            final EditText et = new EditText(host.ctx());
            et.setText(it.archShort);
            et.setInputType(InputType.TYPE_CLASS_TEXT);
            new HfDialogBuilder(host.ctx())
                    .setTitle(s(R.string.arch_xfer_title,
                            s(move ? R.string.arch_word_move : R.string.arch_word_copy),
                            dst.archiveFile.getName()))
                    .setMessage(R.string.arch_xfer_msg)
                    .setView(et)
                    .setPositiveButton(move ? R.string.arch_word_move : R.string.arch_word_copy,
                            new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int w) {
                                    String target = et.getText().toString().trim()
                                            .replace("\\", "/");
                                    while (target.startsWith("/")) target = target.substring(1);
                                    while (target.endsWith("/")) {
                                        target = target.substring(0, target.length() - 1);
                                    }
                                    if (target.isEmpty()) { host.toast(s(R.string.arch_name_empty)); return; }
                                    addEntriesToArchive(src, entries, dst, target, move);
                                }
                            })
                    .setNegativeButton(R.string.common_cancel, null)
                    .show();
        } else {
            addEntriesToArchive(src, entries, dst, null, move);
        }
    }

    /** 执行「压缩包条目 → 对面压缩包」：提取到缓存 → 逐文件写入 → 可选删源条目 */
    public void addEntriesToArchive(final Pane src, final List<FileItem> entries,
            final Pane dst, final String nameOverride, final boolean move) {
        AtomicReplace.lastError = null;      // R-06：旧失败原因不能安到这次头上
        host.toast(s(R.string.arch_writing));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                final File tmp = new File(host.ctx().getCacheDir(), "arch_xfer");
                try {
                    FileOps.deleteRecursive(tmp);
                    tmp.mkdirs();
                    // 1) 提取全部选中条目到缓存
                    for (FileItem it : entries) {
                        src.archive.extractEntry(it.archPath(), tmp);
                    }
                } catch (Throwable t) {
                    if (archPwError(src, t)) return;
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                if (err == null) {
                    // 2) 逐文件写入目标归档（APK → 会话重打包写回原包；其他压缩包原地写）
                    final boolean dstApk = ApkEditSession.isApk(dst.archiveFile);
                    try {
                        for (FileItem it : entries) {
                            String root = entries.size() == 1 && nameOverride != null
                                    ? nameOverride : it.archShort;
                            File extracted = new File(tmp, it.archPath());
                            if (extracted.isDirectory()) {
                                ArchiveAddController.writeTree(host.ctx(), extracted, root,
                                        dst.archiveFile, dstApk);
                            } else if (extracted.isFile()) {
                                if (dstApk) {
                                    ApkEditSession.stageAdd(host.ctx(), dst.archiveFile, root, extracted);
                                } else if (!ArchiveWrite.writeEntry(dst.archiveFile, root, extracted)) {
                                    throw new RuntimeException(AtomicReplace.failMessage(s(R.string.arch_write_fail, root)));
                                }
                            } else {
                                throw new RuntimeException(s(R.string.arch_extract_fail_detail, it.archPath()));
                            }
                        }
                        // 批量登记完成 → 一次性重打包写回
                        if (dstApk && err == null)
                            ApkEditSession.flushRegen(host.ctx(), dst.archiveFile);
                        // 3) move：从源归档删除原条目
                        if (move) {
                            List<String> names = new ArrayList<String>();
                            for (FileItem it : entries) names.add(it.archPath());
                            if (ApkEditSession.isApk(src.archiveFile)) {
                                ApkEditSession.putRemove(host.ctx(), src.archiveFile, names);
                            } else if (!ArchiveWrite.removeEntries(src.archiveFile, names)) {
                                throw new RuntimeException(
                                        AtomicReplace.failMessage(s(R.string.arch_src_del_fail)));
                            }
                        }
                    } catch (Throwable t) {
                        if (archPwError(dst, t)) return;
                        err = ArchiveText.errorText(host.ctx(), t);
                    }
                }
                FileOps.deleteRecursive(tmp);
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(s(R.string.arch_fail, ferr)); return; }
                        if (dst.archiveFile != null && ApkEditSession.isApk(dst.archiveFile)
                                && ApkEditSession.hasEdits(dst.archiveFile)) {
                            host.toast((move ? s(R.string.arch_moved_in) : s(R.string.arch_copied_in))
                                    + s(R.string.arch_written_back));
                        } else {
                            host.toast(move ? s(R.string.arch_moved_in) : s(R.string.arch_copied_in));
                        }
                        showArchiveDir(dst);
                        if (src.archiveFile != null) {
                            showArchiveDir(src);
                        } else if (src.dir != null) {
                            host.loadDir(src, src.dir, false);
                        }
                    }
                });
            }
        }, "arch-xfer").start();
    }

    /**
     * 归档能否原地改（v2.13.1，F-11）：可写格式，且不是 APK。
     *
     * <p>APK 单独拦下的理由不是「技术做不到」而是「两套写路径不能混」：APK 的每次修改都
     * 走 {@code ApkEditSession}（会话内暂存 → 重打包 → 首次修改自动备份 → 退出时提示重签）。
     * 直接在归档层改名会绕开备份与重签提示，得到的是一个「装不上、也回不去」的包。</p>
     */
    private boolean archEditable(Pane p) {
        if (p.archiveFile == null) return false;
        if (ApkEditSession.isApk(p.archiveFile)) {
            host.toast(s(R.string.arch_apk_rename_hint));
            return false;
        }
        if (!ArchiveWrite.canWrite(p.archiveFile)) {
            host.toast(s(R.string.arch_readonly_format));
            return false;
        }
        return true;
    }

    /** 重命名归档条目（v2.13.1，F-11）：目录条目连同其下全部子项一起改名 */
    public void archRename(final Pane p, final FileItem it) {
        if (!archEditable(p)) return;
        final String prefix = p.archPrefix == null ? "" : p.archPrefix;
        final EditText et = new EditText(host.ctx());
        et.setText(it.archShort);
        et.setSelection(et.getText().length());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(host.ctx())
                .setTitle(s(R.string.arch_rename_title, it.archShort))
                .setMessage(R.string.arch_rename_msg)
                .setView(et)
                .setPositiveButton(R.string.arch_op_rename,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String input = EntryNames.clean(et.getText().toString());
                        if (input.isEmpty()) { host.toast(s(R.string.arch_name_empty)); return; }
                        String to = prefix + input;
                        if (EntryNames.stripSlash(to).equals(EntryNames.stripSlash(it.archFull))) {
                            return;      // 名字没改，不必重建一遍整包
                        }
                        doRename(p, it.archFull, to);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void doRename(final Pane p, final String from, final String to) {
        if (!ZipUtil.entryNameSafe(to)) { host.toast(s(R.string.arch_name_invalid, to)); return; }
        host.toast(s(R.string.arch_renaming));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    String hit = collide(p, from, to);
                    if (hit != null) err = s(R.string.arch_name_exists, hit);
                    else if (!ArchiveWrite.renameEntry(p.archiveFile, from, to)) {
                        err = AtomicReplace.failMessage(s(R.string.arch_rename_fail));
                    }
                } catch (Throwable t) {
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(ferr); return; }
                        host.toast(s(R.string.arch_renamed, EntryNames.lastSeg(to)
                                + (isDirName(to) ? "/" : "")));
                        showArchiveDir(p);
                    }
                });
            }
        }, "arch-rename").start();
    }

    /**
     * 改名后的目标是否撞车，返回撞上的条目名（null = 没撞）。
     *
     * <p>两种撞法都要查：① 同一目录下已有同名的文件或目录条目；② 目标是个已有内容的目录
     * —— 那等于把两个目录合并，用户想的几乎不可能是这个。</p>
     */
    private String collide(Pane p, String from, String to) throws Exception {
        String parent = EntryNames.parentOf(to);
        String t = EntryNames.stripSlash(to);
        String f = EntryNames.stripSlash(from);
        for (ArchiveUtil.Entry e : p.archive.listChildren(parent)) {
            String n = EntryNames.stripSlash(e.full);
            if (n.equals(f)) continue;       // 自己不算撞
            if (n.equals(t)) return e.name;
        }
        if (!p.archive.listChildren(EntryNames.dir(to)).isEmpty()) return EntryNames.lastSeg(to) + "/";
        return null;
    }

    private static boolean isDirName(String name) {
        return name != null && name.endsWith("/");
    }

    /**
     * 在归档当前目录新建文件夹（v2.13.1，F-11）。
     *
     * <p>只是加一个空目录条目，不解包重压 —— 所以 zip 上是一瞬间的事，7z 上要重建一次
     * （7z 的格式没办法原地追加），这也如实写在进度提示里。</p>
     */
    public void archNewFolder(final Pane p) {
        if (!archEditable(p)) return;
        final String prefix = p.archPrefix == null ? "" : p.archPrefix;
        final EditText et = new EditText(host.ctx());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new HfDialogBuilder(host.ctx())
                .setTitle(s(R.string.arch_newfolder_title, prefix))
                .setMessage(R.string.arch_newfolder_msg)
                .setView(et)
                .setPositiveButton(R.string.arch_create,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = EntryNames.clean(et.getText().toString());
                        if (name.isEmpty()) { host.toast(s(R.string.arch_name_empty)); return; }
                        if (name.indexOf('/') >= 0) { host.toast(s(R.string.arch_name_slash)); return; }
                        createArchDir(p, prefix + name);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void createArchDir(final Pane p, final String dirPath) {
        host.toast(s(R.string.arch_creating));
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    String hit = collide(p, null, dirPath);
                    if (hit != null) err = s(R.string.arch_exists, hit);
                    else if (!ArchiveWrite.mkdir(p.archiveFile, dirPath)) {
                        err = AtomicReplace.failMessage(s(R.string.arch_create_fail));
                    }
                } catch (Throwable t) {
                    err = ArchiveText.errorText(host.ctx(), t);
                }
                final String ferr = err;
                host.ui(new Runnable() {
                    public void run() {
                        if (ferr != null) { host.toast(ferr); return; }
                        host.toast(s(R.string.arch_created, EntryNames.stripSlash(dirPath) + "/"));
                        showArchiveDir(p);
                    }
                });
            }
        }, "arch-mkdir").start();
    }

    /** 从压缩包删除条目（重建 zip；APK 删除后询问自动签名） */
    public void archDelete(final Pane p, final List<FileItem> items) {
        final List<String> names = new ArrayList<String>();
        for (FileItem it : items) names.add(it.archPath());
        new HfDialogBuilder(host.ctx())
                .setTitle(R.string.arch_del_title)
                .setMessage(host.ctx().getResources().getQuantityString(
                        R.plurals.arch_del_msg, names.size(), names.size()))
                .setPositiveButton(R.string.common_delete,
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        host.toast(s(R.string.arch_deleting));
                final boolean apkMode = ApkEditSession.isApk(p.archiveFile);
                new Thread(new Runnable() {
                    public void run() {
                        String err = null;
                        try {
                            if (apkMode) {
                                // v1.66.8：APK 删除条目 → 会话登记 + 重打包写回原包
                                ApkEditSession.putRemove(host.ctx(), p.archiveFile, names);
                            } else if (!ArchiveWrite.removeEntries(p.archiveFile, names)) {
                                err = AtomicReplace.failMessage(s(R.string.arch_del_fail));
                            }
                        } catch (Throwable t) {
                            err = ArchiveText.errorText(host.ctx(), t);
                        }
                        final String ferr = err;
                        host.ui(new Runnable() {
                            public void run() {
                                if (ferr != null) { host.toast(ferr); return; }
                                host.toast(host.ctx().getResources().getQuantityString(
                                        R.plurals.arch_deleted, names.size(), names.size())
                                        + (apkMode ? s(R.string.arch_written_back) : ""));
                                showArchiveDir(p);
                            }
                        });
                    }
                }, "arch-del").start();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }
}
