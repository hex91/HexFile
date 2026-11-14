package com.hex91.file.controller;

import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.ApkRepack;
import com.hex91.file.apk.ApkSignerTool;
import com.hex91.file.apk.FileInspect;
import com.hex91.file.apk.KeyStoreUtil;
import com.hex91.file.apk.SigKill;
import com.hex91.file.apk.SmaliTool;
import com.hex91.file.core.ByteIO;
import com.hex91.file.fs.FileKind;
import com.hex91.file.fs.FileOps;
import com.hex91.file.ui.HfDialogBuilder;
import com.hex91.file.ui.SignKeyManagerActivity;
import com.hex91.file.ui.ThemeManager;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「APK 签名」这一簇（v2.7.0，O-22 刀1）。
 *
 * <p>从 {@code MainActivity} 整段搬来，逻辑一行未改；只把「取上下文 / 回主线程 / 起选择器 /
 * 通知宿主刷新」换成 {@link Host}。搬的是五个方法：{@link #askCustomSign}、
 * {@link #doCustomSignWithEntry}、{@link #askCustomKeyPassword}、{@link #parseKeystore}、
 * {@link #doSigKill}（审计给的 306 行是前四个，{@code doCustomSignWithEntry} 是
 * {@code askCustomSign} 的唯一后继，留在 MainActivity 只会多一层来回调用，一并搬来）。</p>
 *
 * <p><b>与刀2（{@code ArchiveAddController}）最大的不同：这一簇有跨 Activity 的状态。</b>
 * 「选 keystore 文件」要起系统选择器，结果从 {@code onActivityResult} 回来；原来靠
 * {@code MainActivity.mCustomSignSrc} / {@code mCustomSignInPlace} 两个字段跨过这个缝，
 * 于是「谁在什么时候读这两个字段」散在三处。现在状态收进本类，宿主只需在
 * {@code onActivityResult} 里转发给 {@link #handleResult}：</p>
 *
 * <pre>
 * protected void onActivityResult(int req, int res, Intent data) {
 *     super.onActivityResult(req, res, data);
 *     if (signFlow.handleResult(req, res, data)) return;   // 本簇的请求码，已消费
 *     ...
 * }
 * </pre>
 *
 * <p>结果消费时<b>当场快照并清空</b> pending（{@code src} / {@code inPlace} 作为参数往下传），
 * 不再让下游去读字段——原来的写法里，取消对话框后再返回一次会命中上一次的
 * {@code mCustomSignSrc}，是个只在边角出现的错签风险。</p>
 *
 * <p>搬动的硬约束（改错就是「原地签名覆盖错包」或「改完的包丢了」）：</p>
 * <ol>
 *   <li>{@code inPlace} 必须先写 {@code <名>.signtmp} 副本，再 {@link ApkEditSession#safeReplace}
 *       原子覆盖；写失败时原包必须完好；</li>
 *   <li>{@code signAutoAlias} 的密钥库来源有两条路（已导入签名读 {@code signkeys/<id>}、
 *       一次性流程读用户选的 Uri），私钥密码与 keystore 密码可能不同，别合并；</li>
 *   <li>{@code doSigKill} 全程在缓存目录干活，{@code finally} 里必须
 *       {@link FileOps#deleteRecursive} 清掉，失败也要清。</li>
 * </ol>
 */
public final class SignFlowController {

    /** 选 keystore 文件（一次性流程）的请求码；宿主 onActivityResult 转发前比对用 */
    public static final int REQ_CUSTOM_KEYSTORE = 3002;

    /** 控制器只通过这组能力与宿主打交道 */
    public interface Host {
        Context ctx();

        void toast(String s);

        /** runOnUiThread + 存活判断（原 runOnUiThread 的直接替代） */
        void ui(Runnable r);

        void refreshAll();

        /** 起系统文件选择器选 keystore；结果回到 {@link #handleResult} */
        void pickKeystore(Intent chooser);

        /** 把已确认的 keystore 文件交给「导入到签名管理」流程（原 importKeystore） */
        void importKeystore(File f);
    }

    private final Host host;

    /** 等待 onActivityResult 的待签 APK（原 mCustomSignSrc / mCustomSignInPlace） */
    private File pendingSrc;
    private boolean pendingInPlace;

    public SignFlowController(Host host) {
        this.host = host;
    }

    // ============================================================ 一次性流程（选文件 + 输密码）

    /**
     * 自定义密钥签名（v1.57；v1.67.0 重构）：
     * 已导入签名 → 列表直接选择并签名（密码已在导入时保存，无需再从文件加载/输密码）；
     * 没有导入 → 走旧版「选文件 + 输密码」一次性流程；两个入口都可进「签名管理」。
     */
    public void askCustomSign(final File apk, final boolean inPlace) {
        List<SignKeyManagerActivity.KeyEntry> keys = new ArrayList<SignKeyManagerActivity.KeyEntry>();
        File dir = new File(host.ctx().getFilesDir(), "signkeys");
        File[] fs = dir.listFiles();
        List<String> ids = new ArrayList<String>();
        if (fs != null) for (File f : fs)
            if (f.getName().endsWith(".meta"))
                ids.add(f.getName().substring(0, f.getName().length() - 5));
        java.util.Collections.sort(ids);
        String defId = SignKeyManagerActivity.signPrefs(host.ctx()).getString("key_id", null);
        if (defId != null && ids.remove(defId)) ids.add(0, defId);
        for (String id : ids) {
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.loadEntry(host.ctx(), id);
            if (e != null) keys.add(e);
        }

        if (keys.isEmpty()) {
            // 无导入签名 → 旧版一次性流程
            pickKeystoreOnce(apk, inPlace);
            return;
        }

        List<String> items = new ArrayList<String>();
        for (SignKeyManagerActivity.KeyEntry e : keys)
            items.add((e.id.equals(defId) ? "★ " : "") + e.name
                    + (e.broken() ? "  ⚠ 密码已损坏"
                                  : (e.keyCount > 0 ? "（" + e.keyCount + " 个私钥别名）" : "")));
        items.add("从文件选择（一次性）…");
        items.add("管理签名…（导入 / 查看证书信息 / 删除）");
        new HfDialogBuilder(host.ctx())
                .setTitle("自定义密钥签名 — " + apk.getName())
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w < keys.size()) {
                            doCustomSignWithEntry(keys.get(w), apk, inPlace);
                        } else if (w == keys.size()) {
                            pickKeystoreOnce(apk, inPlace);
                        } else {
                            host.ctx().startActivity(new Intent(host.ctx(),
                                    SignKeyManagerActivity.class));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 起系统选择器选 keystore，并记住「选完要签哪个包」 */
    private void pickKeystoreOnce(final File apk, final boolean inPlace) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // 先登记再起选择器：原来的写法是 startActivityForResult 之后才写字段，
        // 虽然进程内结果不可能同步回来，但「先有状态再发起」不需要论证为什么安全
        pendingSrc = apk;
        pendingInPlace = inPlace;
        try {
            host.pickKeystore(Intent.createChooser(i, "选择 keystore（.p12/.jks/.keystore/.bks）"));
        } catch (Throwable t) {
            pendingSrc = null;
            host.toast("无法打开文件选择器: " + t.getMessage());
        }
    }

    /**
     * 消费 {@code onActivityResult}。返回 true 表示请求码属于本簇（宿主不必再处理）。
     *
     * <p>快照 + 清空是这次搬动顺带修的地方：原来 {@code mCustomSignSrc} 在结果用完以后
     * 仍然留着，取消密码对话框、再进一次选择器又取消，就会出现「拿到的新 Uri 配的是旧 APK」。
     * 现在 pending 只在这一刻被读一次。</p>
     */
    public boolean handleResult(int req, int res, Intent data) {
        if (req != REQ_CUSTOM_KEYSTORE) return false;
        File src = pendingSrc;
        boolean inPlace = pendingInPlace;
        pendingSrc = null;
        pendingInPlace = false;
        if (res == Activity.RESULT_OK && data != null && data.getData() != null && src != null) {
            askCustomKeyPassword(data.getData(), src, inPlace);
        }
        return true;
    }

    /** 自定义签名第 2 步：输入 keystore 密码后执行 signAutoAlias */
    private void askCustomKeyPassword(final Uri keystoreUri, final File apk,
            final boolean inPlace) {
        final EditText pass = new EditText(host.ctx());
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("keystore 密码");
        new HfDialogBuilder(host.ctx())
                .setTitle("自定义密钥签名")
                .setMessage("keystore: " + (keystoreUri.getLastPathSegment() != null
                        ? keystoreUri.getLastPathSegment() : "已选择")
                        + "\nAPK: " + apk.getName())
                .setView(pass)
                .setPositiveButton("签名", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String pwd = pass.getText().toString();
                        if (pwd.isEmpty()) { host.toast("密码不能为空"); return; }
                        final ProgressDialog pd = ProgressDialog.show(host.ctx(),
                                "自定义签名", apk.getName(), true, false);
                        new Thread(new Runnable() {
                            public void run() {
                                String err = null;
                                // inPlace（_edit.apk）→ 临时文件原地覆盖；否则 <名>_csigned.apk
                                File out = inPlace
                                        ? new File(apk.getParentFile(), apk.getName() + ".signtmp")
                                        : new File(apk.getParentFile(),
                                                FileKind.stripExt(apk.getName()) + "_csigned.apk");
                                try {
                                    byte[] ksBytes = readUriBytes(keystoreUri);
                                    ApkSignerTool.signAutoAlias(ksBytes, pwd.toCharArray(),
                                            apk, out, 21);
                                    if (inPlace) {
                                        // v1.66.4 / v1.99.1：AtomicReplace 先 rename，失败才
                                        // 备份+复制，不再先 delete 防写失败丢原包
                                        ApkEditSession.safeReplace(out, apk);
                                        try { ApkEditSession.markSigned(apk); }
                                        catch (Throwable ignored) {}
                                    }
                                } catch (Throwable t) {
                                    err = t.getMessage() != null
                                            ? t.getMessage()
                                            : t.getClass().getSimpleName();
                                }
                                final String ferr = err;
                                final File fout = apk;
                                final boolean finPlace = inPlace;
                                host.ui(new Runnable() {
                                    public void run() {
                                        pd.dismiss();
                                        if (ferr != null) host.toast("签名失败: " + ferr);
                                        else {
                                            host.toast("已签名 → " + (finPlace
                                                    ? fout.getName() + "（原地覆盖）"
                                                    : fout.getName()));
                                            host.refreshAll();
                                        }
                                    }
                                });
                            }
                        }, "apk-csign").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 用已导入签名直接签（密码已保存）；inPlace = 原地覆盖，否则 <名>_csigned.apk */
    private void doCustomSignWithEntry(final SignKeyManagerActivity.KeyEntry entry,
            final File apk, final boolean inPlace) {
        if (entry.broken()) {
            host.toast("该签名的密码已损坏（换机/清除数据导致），请到「签名管理」重新导入");
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(host.ctx(),
                "自定义签名", entry.name + " → " + apk.getName(), true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = inPlace
                        ? new File(apk.getParentFile(), apk.getName() + ".signtmp")
                        : new File(apk.getParentFile(),
                                FileKind.stripExt(apk.getName()) + "_csigned.apk");
                try {
                    byte[] ksBytes = SignKeyManagerActivity.loadKeyBytes(host.ctx(), entry.id);
                    ApkSignerTool.signAutoAlias(ksBytes,
                            entry.storePass.toCharArray(),
                            entry.keyPass != null ? entry.keyPass.toCharArray() : null,
                            entry.alias, apk, out, 21);
                    if (inPlace) {
                        ApkEditSession.safeReplace(out, apk);
                        try { ApkEditSession.markSigned(apk); }
                        catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                }
                final String ferr = err;
                final File fout = apk;
                final boolean finPlace = inPlace;
                host.ui(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) host.toast("签名失败: " + ferr);
                        else {
                            host.toast("已签名（" + entry.name + "）→ "
                                    + (finPlace ? fout.getName() + "（原地覆盖）" : fout.getName()));
                            host.refreshAll();
                        }
                    }
                });
            }
        }, "apk-csign").start();
    }

    // ============================================================ 查看签名信息

    /** 解析 keystore 并展示条目详情（调用方已问过密码） */
    public void parseKeystore(final File f, final String pass) {
        final ProgressDialog pd = new ProgressDialog(host.ctx());
        pd.setMessage("解析 keystore…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final StringBuilder sb = new StringBuilder();
                try {
                    byte[] bytes = FileOps.readAllBytes(f);
                    sb.append("文件: ").append(f.getName()).append("（")
                            .append(FileOps.formatSize(f.length())).append("）\n");
                    sb.append("格式: ").append(FileInspect.typeLabel(
                            FileInspect.readHead(f, 16), f.getName())).append('\n');
                    sb.append("文件头: ").append(FileInspect.hexHead(
                            FileInspect.readHead(f, 16), 8)).append("\n\n");
                    List<KeyStoreUtil.AliasInfo> list =
                            KeyStoreUtil.inspect(bytes, pass.toCharArray(), null);
                    if (list.isEmpty()) sb.append("（没有任何条目）");
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append(list.get(i).detail());
                    }
                } catch (Throwable t) {
                    sb.setLength(0);
                    sb.append("解析失败: ").append(
                            SignKeyManagerActivity.friendlyKsError(host.ctx(), t));
                }
                host.ui(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        ScrollView sv = new ScrollView(host.ctx());
                        TextView tv = new TextView(host.ctx());
                        int pad = (int) (20 * host.ctx().getResources()
                                .getDisplayMetrics().density);
                        sv.setPadding(pad, pad / 2, pad, 0);
                        tv.setText(sb.toString());
                        tv.setTextIsSelectable(true);
                        tv.setTextSize(13);
                        tv.setTextColor(ThemeManager.text(host.ctx()));
                        sv.addView(tv);
                        new HfDialogBuilder(host.ctx())
                                .setTitle("签名信息")
                                .setView(sv)
                                .setPositiveButton("导入到签名管理",
                                        new android.content.DialogInterface.OnClickListener() {
                                            public void onClick(android.content.DialogInterface d, int w) {
                                                host.importKeystore(f);
                                            }
                                        })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }, "ks-parse").start();
    }

    // ============================================================ 去除签名校验（v1.34.0）

    /**
     * SigKill 入口：反编译全部 dex → 启发式 patch 签名校验点 → 回编译 → 重打包 → v1+v2+v3 重签名。
     * 输出与原 APK 同目录：<名>_nosig.apk。
     */
    public void doSigKill(final File f) {
        final ProgressDialog pd = new ProgressDialog(host.ctx());
        pd.setMessage("去除签名校验…");
        pd.setCancelable(false);
        pd.show();
        String base = f.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final File out = new File(f.getParentFile(), base + "_nosig.apk");
        new Thread(new Runnable() {
            public void run() {
                String err = null, info = null;
                File work = new File(host.ctx().getCacheDir(), "sigkill_tmp");
                try {
                    FileOps.deleteRecursive(work);
                    work.mkdirs();

                    // 1) 抽取全部 dex → 反编译 → patch → 回编译
                    java.util.Map<String, byte[]> replace =
                            new java.util.LinkedHashMap<String, byte[]>();
                    List<SigKill.Patch> patches = new ArrayList<SigKill.Patch>();
                    java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
                    try {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                        int dexIdx = 0;
                        while (en.hasMoreElements()) {
                            java.util.zip.ZipEntry ze = en.nextElement();
                            String nm = ze.getName();
                            if (!nm.toLowerCase().endsWith(".dex")) continue;
                            File dex = new File(work, "src_" + dexIdx + ".dex");
                            InputStream in = zf.getInputStream(ze);
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(dex);
                            byte[] buf = new byte[65536]; int n;
                            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                            fo.close(); in.close();

                            File smaliDir = new File(work, "smali_" + dexIdx);
                            String e = SmaliTool.decompile(dex, smaliDir, 4);
                            if (e != null) throw new Exception("反编译 " + nm + " 失败: " + e);
                            patches.addAll(SigKill.run(smaliDir));
                            File outDex = new File(work, "out_" + dexIdx + ".dex");
                            e = SmaliTool.assemble(smaliDir, outDex, 4);
                            if (e != null) throw new Exception("回编译 " + nm + " 失败: " + e);

                            byte[] data = ByteIO.readAll(outDex);
                            if (data != null) replace.put(nm, data);
                            dexIdx++;
                        }
                    } finally {
                        try { zf.close(); } catch (Throwable ig) {}
                    }

                    if (patches.isEmpty()) {
                        err = "未发现可去除的签名校验点（没有 PackageManager.checkSignatures 调用、"
                                + "Signature.verify/equals 调用点或自带 Signature 副本），原 APK 未修改。";
                    } else {
                        // 2) 重打包（替换 dex）→ 重签名
                        File unsigned = new File(work, "unsigned.apk");
                        ApkRepack.repack(f, replace, unsigned);
                        if (out.exists()) out.delete();
                        InputStream ksIn = host.ctx().getAssets().open("hexfile.p12");
                        ApkSignerTool.sign(ksIn, ApkSignerTool.KEY_PASSWORD, unsigned, out, 21);
                        ksIn.close();

                        // 3) 汇总报告
                        int cs = 0, call = 0, body = 0;
                        for (SigKill.Patch p : patches) {
                            if (p.kind.equals("checkSignatures→0")) cs++;
                            else if (p.kind.equals("Signature调用→true")) call++;
                            else body++;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("共 patch ").append(patches.size()).append(" 处\n");
                        if (cs > 0) sb.append("· checkSignatures 返回值强置 0: ").append(cs).append(" 处\n");
                        if (call > 0) sb.append("· Signature.verify/equals 调用点强 true: ").append(call).append(" 处\n");
                        if (body > 0) sb.append("· 自带 Signature 副本方法体重写: ").append(body).append(" 处\n");
                        sb.append("\n输出: ").append(out.getAbsolutePath())
                                .append("\n已用内置密钥 v1+v2+v3 重签名");
                        info = sb.toString();
                    }
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Throwable c = t.getCause();
                    while (c != null) {
                        err += "\n↳ " + c.getClass().getSimpleName() + ": " + c.getMessage();
                        c = c.getCause();
                    }
                } finally {
                    FileOps.deleteRecursive(work);
                }
                final String e = err, ok = info;
                host.ui(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (e != null) {
                            new HfDialogBuilder(host.ctx())
                                    .setTitle("去除签名校验").setMessage(e)
                                    .setPositiveButton("知道了", null).show();
                        } else {
                            host.refreshAll();
                            new HfDialogBuilder(host.ctx())
                                    .setTitle("已生成 <名>_nosig.apk")
                                    .setMessage(ok)
                                    .setPositiveButton("关闭", null)
                                    .show();
                        }
                    }
                });
            }
        }, "sigkill").start();
    }

    // ============================================================ 内部

    private byte[] readUriBytes(Uri uri) throws Exception {
        InputStream in = host.ctx().getContentResolver().openInputStream(uri);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }
}
