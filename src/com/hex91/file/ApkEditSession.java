package com.hex91.file;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v1.66.0 APK 修改会话（统一「只生成一个 _edit.apk」模型）：
 *
 *  - 所有对 APK 的修改（smali/dex、arsc、AndroidManifest、文本条目、lib/assets 加文件、
 *    删条目）不再原地写回、不再各产一个 _xmlpp/_arscpp/_dexpp/_mfpp 文件，
 *    而是登记到本会话后统一重打包为 <原名>_edit.apk（未签名）；
 *  - 首次修改时把最初原包备份为 <原名>_bak.apk（已存在则保留最早原版）；
 *  - 未发生实质性修改时不生成任何文件，原包保持不变；
 *  - 退出 APK 浏览时检查：有修改且未签名 → 统一弹窗（不签名 / 内置签名文件签名 /
 *    自有签名文件签名），签名成功后原地覆盖 _edit.apk（仍然只有一个文件）。
 *
 *  会话按 APK 绝对路径隔离，多次修改叠加；重打包始终以「原包 + 全部会话修改」为准，
 *  因此多次保存结果一致，且重新打开被改过的条目时能读到最新内容（editBytes/overlayPending）。
 */
public final class ApkEditSession {

    /** 每个 APK 一份会话状态 */
    public static final class Session {
        public final File apk;
        /** 条目 → 修改后内容缓存文件（含新增条目） */
        public final LinkedHashMap<String, File> edits = new LinkedHashMap<String, File>();
        /** 需从产物剔除的条目（如多 dex 合并后的多余 classesN.dex、删除的条目） */
        public final LinkedHashSet<String> removes = new LinkedHashSet<String>();
        /** true = 修改后已重新生成过 _edit.apk（未签名状态） */
        public boolean dirty = false;
        /** 统一签名弹窗已处理过（选择不签名后不再重复弹） */
        public boolean prompted = false;
        Session(File apk) { this.apk = apk; }
    }

    private static final Map<String, Session> MAP = new LinkedHashMap<String, Session>();

    private ApkEditSession() {}

    public static boolean isApk(File f) {
        return f != null && f.getName().toLowerCase().endsWith(".apk");
    }

    private static String key(File apk) {
        return apk.getAbsolutePath();
    }

    public static synchronized Session get(File apk) {
        String k = key(apk);
        Session s = MAP.get(k);
        if (s == null) { s = new Session(apk); MAP.put(k, s); }
        return s;
    }

    /** 是否有未落地的修改（_edit.apk 需要存在/更新） */
    public static synchronized boolean hasEdits(File apk) {
        Session s = MAP.get(key(apk));
        return s != null && (!s.edits.isEmpty() || !s.removes.isEmpty());
    }

    /** 退出浏览时是否需要弹统一签名框：有修改、未签名、本次修改尚未询问过 */
    public static synchronized boolean needsSignPrompt(File apk) {
        Session s = MAP.get(key(apk));
        return s != null && s.dirty && !s.prompted;
    }

    public static synchronized void markPrompted(File apk) {
        get(apk).prompted = true;
    }

    public static synchronized void markSigned(File apk) {
        Session s = get(apk);
        s.dirty = false;
        s.prompted = false;
    }

    /** 丢弃会话（不删 _edit/_bak 产物），下次打开 APK 重新开始 */
    public static synchronized void clear(File apk) {
        MAP.remove(key(apk));
    }

    private static File cacheRoot(Context ctx, File apk) {
        File base = null;
        if (ctx != null) {
            try { base = ctx.getCacheDir(); } catch (Throwable ignored) {}
        }
        // ctx 不可用时（桌面测试）退回 APK 同目录隐藏文件夹
        if (base == null) base = new File(apk.getParentFile(), ".hexfile_edit");
        File root = new File(base, "apkedit/"
                + Integer.toHexString(apk.getAbsolutePath().hashCode()));
        root.mkdirs();
        return root;
    }

    /** 条目的最新内容：会话中未落地的修改优先，否则返回 null（调用方自行读原包） */
    public static synchronized byte[] editBytes(File apk, String entry) {
        Session s = MAP.get(key(apk));
        if (s == null) return null;
        File f = s.edits.get(entry);
        if (f == null || !f.isFile()) return null;
        try { return readAll(f); } catch (IOException e) { return null; }
    }

    /** 把会话中该条目的修改内容覆盖到 target（编辑器打开缓存文件前调用，保证拿到最新内容） */
    public static boolean overlayPending(File apk, String entry, File target) {
        byte[] b = editBytes(apk, entry);
        if (b == null) return false;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(b);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    /** entry 全路径 SHA-1 摘要（缓存文件名用；String.hashCode 可碰撞，同名不同目录会互覆） */
    private static String entryKey(String entry) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(entry.hashCode()); // 理论上不可达
        }
    }

    /** 缓存文件名：entry 全路径 SHA-1 + 末段文件名（保留可读性） */
    private static File cacheFileFor(Context ctx, File apk, String entry) {
        return new File(cacheRoot(ctx, apk), entryKey(entry) + "_" + new File(entry).getName());
    }

    /**
     * 登记「修改/新增条目」并立即重生成 <base>_edit.apk。
     * @return 生成的 _edit.apk；失败抛 IOException
     */
    public static File putEdit(Context ctx, File apk, String entry, byte[] data)
            throws IOException {
        Session s = get(apk);
        File cache = cacheFileFor(ctx, apk, entry);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(cache);
            out.write(data);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
        s.edits.put(entry, cache);
        return regen(ctx, apk);
    }

    /** 登记新增文件（来自磁盘文件）并重生成。entry 为压缩包内路径（如 lib/arm64/libx.so） */
    public static File putAdd(Context ctx, File apk, String entry, File srcFile)
            throws IOException {
        Session s = get(apk);
        File cache = cacheFileFor(ctx, apk, entry);
        copy(srcFile, cache);
        s.edits.put(entry, cache);
        return regen(ctx, apk);
    }

    /** 批量场景用：仅登记新增/修改（不重生成），随后调 flushRegen 一次性重打包 */
    public static void stageAdd(Context ctx, File apk, String entry, File srcFile)
            throws IOException {
        Session s = get(apk);
        File cache = cacheFileFor(ctx, apk, entry);
        File parent = cache.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        copy(srcFile, cache);
        s.edits.put(entry, cache);
    }

    /** 批量登记完成后统一重生成 */
    public static File flushRegen(Context ctx, File apk) throws IOException {
        return regen(ctx, apk);
    }

    /** 登记删除条目并重生成 */
    public static File putRemove(Context ctx, File apk, Iterable<String> entries)
            throws IOException {
        Session s = get(apk);
        for (String e : entries) {
            s.removes.add(e);
            File f = s.edits.remove(e);
            if (f != null) f.delete();
        }
        return regen(ctx, apk);
    }

    /**
     * 统一重生成：<base>_bak.apk（仅首次，保留最早原版）+ <base>_edit.apk（未签名）。
     * 内容 = 原包 + 会话全部修改。
     */
    public static File regen(Context ctx, File apk) throws IOException {
        Session s = get(apk);
        File parent = apk.getParentFile();
        String base = stripExt(apk.getName());

        // 1) 原包备份（只备份一次，保留最早版本）
        File bak = new File(parent, base + "_bak.apk");
        if (!bak.exists()) copy(apk, bak);

        // 2) 重打包 → 临时文件 → 原子覆盖 _edit.apk
        File edit = new File(parent, base + "_edit.apk");
        File tmpOut = new File(cacheRoot(ctx, apk), "out.apk");
        if (tmpOut.exists()) tmpOut.delete();

        Map<String, byte[]> replace = new LinkedHashMap<String, byte[]>();
        Map<String, File> add = new LinkedHashMap<String, File>();
        Set<String> inSrc = new LinkedHashSet<String>();
        ZipFile zf = new ZipFile(apk);
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) inSrc.add(en.nextElement().getName());
        } finally {
            zf.close();
        }
        for (Map.Entry<String, File> e : s.edits.entrySet()) {
            byte[] b = readAll(e.getValue());
            if (inSrc.contains(e.getKey())) replace.put(e.getKey(), b);
            else add.put(e.getKey(), e.getValue());
        }

        ApkRepack.repack(apk, replace, s.removes, add, tmpOut);
        if (edit.exists()) edit.delete();
        if (!tmpOut.renameTo(edit)) {
            copy(tmpOut, edit);
            tmpOut.delete();
        }
        s.dirty = true;
        s.prompted = false;
        return edit;
    }

    /**
     * 启动统一签名弹窗（透明 Activity，不阻塞调用方关闭浏览页）。
     * v1.66.1 修复：必须把 <名>_edit.apk 传给弹窗签名——
     * 此前误传原始 APK，导致签名写到原包上、_edit.apk 一直是未签名状态。
     */
    public static void launchSignPrompt(Context ctx, File apk) {
        Intent i = new Intent(ctx, SignPromptActivity.class);
        i.putExtra("apk", editApkOf(apk).getAbsolutePath());
        if (!(ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        markPrompted(apk);
    }

    /** 修改版文件：<同目录>/<名>_edit.apk */
    public static File editApkOf(File apk) {
        return new File(apk.getParentFile(), editName(apk));
    }

    public static String editName(File apk) {
        return stripExt(apk.getName()) + "_edit.apk";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void copy(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    /**
     * 用 tmp 安全替换 target（签名原地覆盖用）：
     * Linux/Android 的 rename 本可【原子替换】已存在文件，所以先直接 rename——
     * 不要「先 delete 后 rename」：delete 成功而 rename 失败会丢原文件。
     * rename 失败（跨挂载点等）才降级为 备份→复制→失败恢复：
     * 原文件先 rename 成 .replacebak，复制失败时尝试还原，还原失败保留备份不删。
     */
    public static void safeReplace(File tmp, File target) throws IOException {
        if (tmp.renameTo(target)) return;
        File bak = new File(target.getParentFile(), target.getName() + ".replacebak");
        if (bak.exists()) bak.delete();
        boolean hasOld = target.exists();
        if (hasOld) {
            if (!target.renameTo(bak)) {
                // rename 失败（目标被锁/跨卷等）→ copy 兜底，仍保住原文件内容
                copy(target, bak);
                if (!target.delete())
                    throw new IOException("无法备份或删除原文件: " + target.getName());
            }
        }
        try {
            copy(tmp, target);
            bak.delete();                       // 成功 → 备份使命完成
        } catch (IOException e) {
            if (hasOld && !target.exists() && bak.exists()) bak.renameTo(target);
            throw e;                            // 还原失败时保留 .replacebak 供手工恢复
        } finally {
            if (tmp.exists()) tmp.delete();     // 可能被 mmap 锁住删不掉，容忍
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            in.close();
        }
        return bo.toByteArray();
    }
}
