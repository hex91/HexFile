package com.hex91.file.controller;

import com.hex91.file.utils.*;
import com.hex91.file.*;
import com.hex91.file.ui.*;
import com.hex91.file.fs.*;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * 文件属性 / 权限 / 时间 / 所有者 / 哈希弹窗（v2.0.0，O-15 刀6）。
 *
 * <p>从 MainActivity 整段搬来（含 {@code mPropsDialog} 这个「重开前先关旧」的引用），
 * 逻辑一行未改：只有 {@code this} → {@link Host#ctx()}、{@code toast/refreshAll/copyToClipboard}
 * → Host 转发。<b>不带任何行为改动</b> —— chmod/chown/mtime 的取值、八进制换算、
 * root 回退路径全部原样。</p>
 */
public final class PropsController {

    public interface Host {
        android.content.Context ctx();

        void toast(String s);

        /** 权限/时间改完要把两栏刷新（内容可能变了） */
        void refreshAll();

        /** 复制到系统剪贴板（文件名/哈希值那几行用） */
        void copyToClipboard(String label, String value);

        /** 主线程执行（原 runOnUiThread） */
        void ui(Runnable r);

        void askRename(File f);

        void confirmDelete(File f);
    }

    private final Host host;

    public PropsController(Host host) {
        this.host = host;
    }

    /** v1.43.0：MT 式属性弹窗——名称/目录/类型/大小/修改时间 + 权限·所有者·用户组（可更改）+ 更多/校验/关闭 */
    AlertDialog mPropsDialog;   // v1.43.1：当前属性弹窗引用（重开前先关旧的，避免叠两层）

    public void showProps(final File f) {
        final boolean isDir = f.isDirectory();
        final float d = host.ctx().getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(host.ctx());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), 0);

        propRow(root, "名称", f.getName() + (isDir ? "/" : ""));
        propRow(root, "目录", f.getParent() == null ? "/" : f.getParent());
        propRow(root, "类型", isDir ? "文件夹" : "文件");
        propRow(root, "大小", isDir ? "—"
                : FileOps.formatSize(f.length()) + " (" + f.length() + ")");

        final FileAttrs.Attr cached = FileAttrs.get(f);
        // v1.80.0：修改时间可编辑（root 区 File API 拿不到时用 stat 回读的 mtime）
        final long shownMtime = cached != null && cached.mtime > 0 ? cached.mtime : f.lastModified();
        propRowBtn(root, "修改时间", FileOps.formatTime(shownMtime), "更改",
                new View.OnClickListener() {
                    public void onClick(View v) { showTimeEditor(f, shownMtime); }
                });

        final TextView tvPerm = propRowBtn(root, "权限",
                cached != null ? permText(cached) : "…",
                "更改", new View.OnClickListener() {
                    public void onClick(View v) { showPermEditor(f); }
                });
        final TextView tvOwner = propRowBtn(root, "所有者",
                cached != null ? (cached.owner == null ? "—" : cached.owner) : "…",
                "更改", new View.OnClickListener() {
                    public void onClick(View v) { showOwnerEditor(f); }
                });
        final TextView tvGroup = propRowBtn(root, "用户组",
                cached != null ? (cached.group == null ? "—" : cached.group) : "…",
                "更改", new View.OnClickListener() {
                    public void onClick(View v) { showOwnerEditor(f); }
                });

        final AlertDialog dlg = new HfDialogBuilder(host.ctx())
                .setTitle("属性")
                .setView(root)
                .setNeutralButton("更多", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        showPropsMore(f);
                    }
                })
                .setNegativeButton("校验", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        showHashDialog(f);
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
        mPropsDialog = dlg;   // v1.43.1：记录引用，供更改成功后先关旧弹窗再重开

        // 异步取属性刷新（未缓存时）
        FileAttrs.load(f, new FileAttrs.Callback() {
            public void onAttr(FileAttrs.Attr a) {
                if (a == null) return;
                if (tvPerm.isAttachedToWindow()) {
                    tvPerm.setText(permText(a));
                    tvOwner.setText(a.owner == null ? "—" : a.owner);
                    tvGroup.setText(a.group == null ? "—" : a.group);
                }
            }
        });
    }

    /** "rw-r--r--(644)"；无 root 无八进制时只显示权限串 */
    static String permText(FileAttrs.Attr a) {
        return a.perm + (a.octal >= 0 ? "(" + Integer.toOctalString(a.octal) + ")" : "");
    }

    /** 属性「更多」：常用操作入口 */
    void showPropsMore(final File f) {
        String[] ops = {"重命名", "删除", "复制路径"};
        new HfDialogBuilder(host.ctx())
                .setTitle(f.getName())
                .setItems(ops, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        if (w == 0) host.askRename(f);
                        else if (w == 1) host.confirmDelete(f);
                        else host.copyToClipboard("路径", f.getPath());
                    }
                }).show();
    }

    // ---------- v1.43.0 属性弹窗布局辅助 ----------

    /** 标签 + 值（+可选右侧按钮）一行；返回值 TextView 供后续刷新 */
    TextView propRowBtn(LinearLayout root, String label, String value,
                                String btnText, View.OnClickListener btnClick) {
        float d = host.ctx().getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(host.ctx());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (9 * d);
        row.setLayoutParams(rlp);

        TextView lv = new TextView(host.ctx());
        lv.setText(label);
        lv.setTextColor(ThemeManager.textSub(host.ctx()));
        lv.setTextSize(14);
        lv.setWidth((int) (80 * d));
        row.addView(lv);

        TextView vv = new TextView(host.ctx());
        vv.setText(value);
        vv.setTextColor(ThemeManager.text(host.ctx()));
        vv.setTextSize(15);
        vv.setTextIsSelectable(true);
        vv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                host.copyToClipboard("值", ((TextView) v).getText().toString());
                return true;
            }
        });
        row.addView(vv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (btnText != null) {
            Button b = new Button(host.ctx());
            b.setText(btnText);
            // v1.76.0：属性弹窗里行内按钮改为小胶囊（原来是系统方角块）
            UiDecor.barButton(b, host.ctx(), UiDecor.KIND_SOFT, 11.5f);
            b.setOnClickListener(btnClick);
            row.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        root.addView(row);
        return vv;
    }

    TextView propRow(LinearLayout root, String label, String value) {
        return propRowBtn(root, label, value, null, null);
    }

    /**
     * v1.43.0：更改权限——勾选矩阵 + 八进制实时显示，OK 走 su chmod。
     * v1.43.1：布局转置为横排（行=拥有者/用户组/其他，列=读/写/执行，与 MT 一致）；
     *          chmod 结果回读校验（FUSE 存储不支持 chmod 时如实提示）；
     *          成功后先关旧属性弹窗再重开，消除双层弹窗。
     */
    void showPermEditor(final File f) {
        FileAttrs.Attr a = FileAttrs.stat(f);
        // v1.80.0：没有 root 也允许尝试 —— 应用私有目录里的文件本应用就能 chmod，
        // 以前一上来就 toast「需要 Root」把入口堵死了
        final boolean hasRoot = RootUtil.isRootAvailable();
        final float d = host.ctx().getResources().getDisplayMetrics().density;
        int o = (a != null && a.octal >= 0) ? a.octal : 0644;

        LinearLayout root = new LinearLayout(host.ctx());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), 0);

        final TextView oct = new TextView(host.ctx());
        oct.setTextColor(ThemeManager.accent(host.ctx()));
        oct.setTextSize(15);
        root.addView(oct);

        // 表头：读 / 写 / 执行（横排三列）
        LinearLayout head = new LinearLayout(host.ctx());
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView corner = new TextView(host.ctx());
        corner.setWidth((int) (60 * d));
        head.addView(corner);
        String[] permNames = {"读", "写", "执行"};
        for (int p = 0; p < 3; p++) {
            TextView t = new TextView(host.ctx());
            t.setText(permNames[p]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            head.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(head);

        final CheckBox[][] cbs = new CheckBox[3][3];
        String[] whoNames = {"拥有者", "用户组", "其他"};
        CompoundButton.OnCheckedChangeListener listen = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean s) { recalc(); }
            void recalc() {
                int v = 0;
                for (int w = 0; w < 3; w++)
                    for (int p = 0; p < 3; p++)
                        if (cbs[w][p].isChecked()) v |= 1 << (8 - w * 3 - p);
                oct.setText("八进制: " + Integer.toOctalString(v));   // v1.43.0 修复：按八进制显示
            }
        };
        for (int w = 0; w < 3; w++) {
            LinearLayout row = new LinearLayout(host.ctx());
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView t = new TextView(host.ctx());
            t.setText(whoNames[w]);
            t.setTextSize(13);
            t.setWidth((int) (60 * d));
            t.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(t);
            for (int p = 0; p < 3; p++) {
                CheckBox c = new CheckBox(host.ctx());
                c.setChecked(((o >> (8 - w * 3 - p)) & 1) == 1);
                c.setOnCheckedChangeListener(listen);
                c.setGravity(Gravity.CENTER);
                cbs[w][p] = c;
                row.addView(c, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            root.addView(row);
        }
        oct.setText("八进制: " + Integer.toOctalString(o));   // 初值（v1.43.0 八进制修复）

        if (!hasRoot) {
            TextView tip = new TextView(host.ctx());
            tip.setText("未 Root：只能改本应用拥有的文件；/sdcard 这类 FUSE 挂载通常改不动。");
            tip.setTextSize(11.5f);
            tip.setTextColor(ThemeManager.textSub(host.ctx()));
            tip.setPadding(0, (int) (8 * d), 0, 0);
            root.addView(tip);
        }

        // v1.80.0：常用权限预设 —— 644/755 这些天天用，9 个勾选框点起来太慢
        LinearLayout presets = new LinearLayout(host.ctx());
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, (int) (12 * d), 0, 0);
        TextView pLabel = new TextView(host.ctx());
        pLabel.setText("常用");
        pLabel.setTextSize(12);
        pLabel.setTextColor(ThemeManager.textSub(host.ctx()));
        pLabel.setWidth((int) (60 * d));
        pLabel.setGravity(Gravity.CENTER_VERTICAL);
        presets.addView(pLabel);
        final int[] PRESET = {0644, 0755, 0600, 0777};
        for (final int pv : PRESET) {
            TextView chip = UiDecor.pill(host.ctx(), null, Integer.toOctalString(pv), false);
            chip.setTextSize(11.5f);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { applyOctalToBoxes(cbs, pv); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * d);
            presets.addView(chip, lp);
        }
        root.addView(presets);

        // 目录才有「递归」的意义
        final CheckBox rec = new CheckBox(host.ctx());
        rec.setText("同时应用到子项（递归）");
        rec.setTextSize(12);
        if (f.isDirectory()) {
            rec.setPadding(0, (int) (4 * d), 0, 0);
            root.addView(rec);
        }

        new HfDialogBuilder(host.ctx())
                .setTitle("更改权限 — " + f.getName())
                .setView(root)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        int v = 0;
                        for (int w2 = 0; w2 < 3; w2++)
                            for (int p = 0; p < 3; p++)
                                if (cbs[w2][p].isChecked()) v |= 1 << (8 - w2 * 3 - p);
                        final int fv = v;
                        final boolean deep = rec.isChecked() && f.isDirectory();
                        new Thread(new Runnable() {
                            public void run() {
                                final int rc = deep ? FileAttrs.chmodTree(f, fv)
                                        : FileAttrs.chmod(f, fv);
                                host.ui(new Runnable() {
                                    public void run() {
                                        if (rc == 0) {
                                            host.toast("权限已更改为 " + Integer.toOctalString(fv)
                                                    + (deep ? "（含子项）" : ""));
                                            reopenProps(f);   // v1.43.1：关旧开新，不再叠层
                                        } else if (rc == 1) {
                                            host.toast("该存储区不支持 chmod（FUSE 挂载），权限未实际变更");
                                            reopenProps(f);
                                        } else host.toast(hasRoot
                                                ? "chmod 失败，请检查路径是否可写"
                                                : "chmod 失败：没有 Root，只能改本应用拥有的文件");
                                    }
                                });
                            }
                        }, "chmod").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** v1.43.1：更改成功后刷新属性——先关旧属性弹窗（否则压在底下要关两次），再重开取新值 */
    void reopenProps(File f) {
        if (mPropsDialog != null) {
            try { mPropsDialog.dismiss(); } catch (Throwable ignored) {}
            mPropsDialog = null;
        }
        showProps(f);
    }

    /** 把八进制权限位灌进 9 个勾选框（勾选监听会自己刷新「八进制」显示） */
    void applyOctalToBoxes(CheckBox[][] cbs, int v) {
        for (int w = 0; w < 3; w++)
            for (int p = 0; p < 3; p++)
                cbs[w][p].setChecked(((v >> (8 - w * 3 - p)) & 1) == 1);
    }

    /**
     * v1.80.0：修改时间编辑器。
     * 只提供一个固定格式的输入框 + 「设为现在」；解析用 lenient=false，
     * 写错会明确报错，而不是被「宽松解析」悄悄改成别的日期。
     */
    void showTimeEditor(final File f, long cur) {
        final float d = host.ctx().getResources().getDisplayMetrics().density;
        final java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
        fmt.setLenient(false);

        LinearLayout root = new LinearLayout(host.ctx());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (12 * d), (int) (22 * d), 0);

        TextView hint = new TextView(host.ctx());
        hint.setText("格式：yyyy-MM-dd HH:mm:ss（本机时区）");
        hint.setTextSize(11.5f);
        hint.setTextColor(ThemeManager.textSub(host.ctx()));
        root.addView(hint);

        final EditText et = new EditText(host.ctx());
        et.setSingleLine(true);
        et.setText(fmt.format(new java.util.Date(cur)));
        et.setSelection(et.getText().length());
        root.addView(et);

        new HfDialogBuilder(host.ctx())
                .setTitle("修改时间 — " + f.getName())
                .setView(root)
                .setNeutralButton("设为现在", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        applyMtime(f, System.currentTimeMillis());
                    }
                })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        try {
                            long ms = fmt.parse(et.getText().toString().trim()).getTime();
                            applyMtime(f, ms);
                        } catch (Exception e) {
                            host.toast("时间格式不正确，应为 2026-11-09 16:30:00");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void applyMtime(final File f, final long ms) {
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = FileAttrs.setMtime(f, ms);
                host.ui(new Runnable() {
                    public void run() {
                        host.toast(ok ? "修改时间已更新" : "修改失败：无权限（root 区需要 Root）");
                        reopenProps(f);
                        if (ok) host.refreshAll();
                    }
                });
            }
        }, "touch").start();
    }

    /** v1.43.0：更改所有者/用户组——su chown */
    void showOwnerEditor(final File f) {
        if (!RootUtil.isRootAvailable()) { host.toast("更改所有者需要 Root"); return; }
        FileAttrs.Attr a = FileAttrs.stat(f);
        if (a == null || a.owner == null) { host.toast("无法获取当前所有者（需要 Root）"); return; }
        final float d = host.ctx().getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(host.ctx());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (12 * d), (int) (22 * d), 0);

        final EditText etOwner = new EditText(host.ctx());
        etOwner.setHint("所有者 (如 root)");
        etOwner.setText(a.owner);
        etOwner.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(etOwner);
        final EditText etGroup = new EditText(host.ctx());
        etGroup.setHint("用户组 (如 root)");
        etGroup.setText(a.group == null ? "" : a.group);
        etGroup.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = (int) (6 * d);
        root.addView(etGroup, glp);

        new HfDialogBuilder(host.ctx())
                .setTitle("更改所有者 — " + f.getName())
                .setView(root)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        final String ow = etOwner.getText().toString().trim();
                        final String gp = etGroup.getText().toString().trim();
                        if (ow.isEmpty()) { host.toast("所有者不能为空"); return; }
                        new Thread(new Runnable() {
                            public void run() {
                                final int rc = FileAttrs.chown(f, ow,
                                        gp.isEmpty() ? null : gp);
                                host.ui(new Runnable() {
                                    public void run() {
                                        if (rc == 0) {
                                            host.toast("所有者已更改");
                                            reopenProps(f);   // v1.43.1：关旧开新
                                        } else if (rc == 1) {
                                            host.toast("该存储区不支持 chown，所有者未实际变更");
                                            reopenProps(f);
                                        } else host.toast("chown 失败（需要 Root 授权）");
                                    }
                                });
                            }
                        }, "chown").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** v1.43.0：校验弹窗——MD5/SHA1/SHA256/CRC32 + 复制 + 粘贴并校验 + 大写字母；关闭回属性 */
    void showHashDialog(final File f) {
        final float d = host.ctx().getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(host.ctx());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), 0);

        // 文件名（点击复制）
        propRowBtn(root, "文件名", f.getName(), "复制", new View.OnClickListener() {
            public void onClick(View v) { host.copyToClipboard("文件名", f.getName()); }
        });

        final String[] algs = {"MD5", "SHA1", "SHA256", "CRC32"};
        final String[] hashKeys = {"MD5", "SHA-1", "SHA-256", "CRC32"};
        final TextView[] values = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            values[i] = propRowBtn(root, algs[i], "计算中…", "复制",
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            String s = values[idx].getText().toString();
                            if (!s.isEmpty() && !"计算中…".equals(s))
                                host.copyToClipboard(algs[idx], s);
                        }
                    });
        }

        // 粘贴并校验
        TextView pasteLabel = new TextView(host.ctx());
        pasteLabel.setText("粘贴并校验");
        pasteLabel.setTextColor(ThemeManager.textSub(host.ctx()));
        pasteLabel.setTextSize(14);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = (int) (14 * d);
        root.addView(pasteLabel, plp);

        LinearLayout pasteRow = new LinearLayout(host.ctx());
        pasteRow.setOrientation(LinearLayout.HORIZONTAL);
        pasteRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etPaste = new EditText(host.ctx());
        etPaste.setHint("粘贴校验值…");
        pasteRow.addView(etPaste, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button pasteBtn = new Button(host.ctx());
        pasteBtn.setText("粘贴");
        UiDecor.barButton(pasteBtn, host.ctx(), UiDecor.KIND_SOFT, 12f);
        pasteRow.addView(pasteBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(pasteRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView resultV = new TextView(host.ctx());
        resultV.setTextSize(14);
        resultV.setPadding(0, (int) (6 * d), 0, 0);
        root.addView(resultV);

        // 大写字母 + 关闭
        LinearLayout bottom = new LinearLayout(host.ctx());
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        final boolean[] upper = {false};
        CheckBox upperCk = new CheckBox(host.ctx());
        upperCk.setText("大写字母");
        upperCk.setTextSize(14);
        bottom.addView(upperCk, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button closeBtn = new Button(host.ctx());
        closeBtn.setText("关闭");
        UiDecor.barButton(closeBtn, host.ctx(), UiDecor.KIND_SOFT, 12.5f);
        bottom.addView(closeBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) (8 * d);
        root.addView(bottom, blp);

        final java.util.Map<String, String> hashCache =
                new java.util.HashMap<String, String>();
        final AlertDialog dlg = new HfDialogBuilder(host.ctx())
                .setTitle("校验")
                .setView(root)
                .show();

        final Runnable render = new Runnable() {
            public void run() {
                for (int i = 0; i < 4; i++) {
                    String h = hashCache.get(hashKeys[i]);
                    if (h == null) values[i].setText("计算中…");
                    else if (h.isEmpty()) values[i].setText("—");
                    else values[i].setText(upper[0]
                            ? h.toUpperCase(java.util.Locale.CHINA)
                            : h.toLowerCase(java.util.Locale.CHINA));
                }
            }
        };

        upperCk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean s) {
                upper[0] = s;
                render.run();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {          // 关闭校验 → 回属性弹窗
                dlg.dismiss();
                showProps(f);
            }
        });
        pasteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager)
                        host.ctx().getSystemService(Context.CLIPBOARD_SERVICE);
                String text = "";
                if (cm != null && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0)
                    text = String.valueOf(cm.getPrimaryClip()
                            .getItemAt(0).coerceToText(host.ctx())).trim();
                etPaste.setText(text);
                if (text.isEmpty()) {
                    resultV.setTextColor(ThemeManager.textSub(host.ctx()));
                    resultV.setText("剪贴板为空");
                    return;
                }
                boolean matched = false;
                for (int i = 0; i < 4; i++) {
                    String h = hashCache.get(hashKeys[i]);
                    if (h == null || h.isEmpty()) continue;
                    if (h.equalsIgnoreCase(text)) {
                        resultV.setTextColor(ThemeManager.ok(host.ctx()));
                        resultV.setText("✓ 与 " + algs[i] + " 校验值一致");
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    resultV.setTextColor(ThemeManager.err(host.ctx()));
                    resultV.setText("✗ 与任何校验值都不匹配");
                }
            }
        });

        // 后台计算四算法
        if (!f.isFile()) {
            for (int i = 0; i < 4; i++) hashCache.put(hashKeys[i], "");
            resultV.setTextColor(ThemeManager.textSub(host.ctx()));
            resultV.setText("文件夹无校验值");
            render.run();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                java.util.Map<String, String> all = HashUtil.hashAll(f);
                for (int i = 0; i < 4; i++) {
                    String h = all.get(hashKeys[i]);
                    hashCache.put(hashKeys[i], h == null ? "" : h);
                }
                host.ui(new Runnable() {
                    public void run() { render.run(); }
                });
            }
        }, "hash-all").start();
    }

    /** MT 式长按菜单：双列宫格弹窗 */
}
