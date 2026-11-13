package com.hex91.file.apk;

import com.hex91.file.fs.*;
import com.hex91.file.core.*;
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
 * v1.66.8 APK 修改会话（原地修改模型）：
 *
 *  - 所有对 APK 的修改（smali/dex、arsc、AndroidManifest、文本条目、lib/assets 加文件、
 *    删条目）登记到本会话后统一重打包，直接【原地覆盖原 APK】；
 *  - 首次修改时先把原包备份为 <原名>_bak.apk（已存在则保留最早原版），备份成功才允许写入；
 *  - 未发生实质性修改时不做任何写入；
 *  - 退出 APK 浏览时检查：有修改且未签名 → 统一弹窗（不签名 / 内置签名文件签名 /
 *    自有签名文件签名），签名成功后同样原地覆盖（仍只有一个文件）。
 *
 *  历史版本说明：v1.66.0~v1.66.7 的产物是 <名>_edit.apk；v1.66.8 起不再生成
 *  _edit.apk（旧的 _edit 文件仍可正常打开/签名，长按签名入口兼容它）。
 *
 *  会话按 APK 绝对路径隔离，多次修改叠加；重打包始终以「备份时的原版 + 全部会话修改」为准，
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
        /** true = 修改后已重新生成过（APK 当前为未签名状态） */
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

    /**
     * v1.66.7：markSigned 的 key 规范化——签名弹窗/原地签名拿到的是
     * <名>_edit.apk，但会话状态（dirty/prompted）挂在【原包】路径上。
     * 直接 markSigned(_edit.apk) 会写到幽灵会话上，原包会话仍 dirty=true，
     * 下次退出可能重复弹签名框。传 _edit.apk 时映射回原包。
     */
    private static String sessionKeyOf(String path) {
        // v1.67.1：同时兼容 '/' 与 '\'（桌面测试在 Windows 上跑，File.getAbsolutePath 用反斜杠，
        // 原实现只找 '/' 会取到整条路径，_edit 后缀判断失效）
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(sep + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.endsWith("_edit")) {
            String origName = base.substring(0, base.length() - 5) + ".apk";
            return path.substring(0, path.length() - name.length()) + origName;
        }
        return path;
    }

    public static synchronized Session get(File apk) {
        String k = key(apk);
        Session s = MAP.get(k);
        if (s == null) { s = new Session(apk); MAP.put(k, s); }
        return s;
    }

    /** 是否有未落地的修改（需要重打包写入） */
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
        Session s = get(new File(sessionKeyOf(apk.getAbsolutePath())));
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
     * 登记「修改/新增条目」并立即重打包写回。
     * @return 写入结果（即 apk 本身，v1.66.8 原地模型）；失败抛 IOException
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
     * 统一重生成（v1.66.8 原地模型）：
     * 1) <base>_bak.apk 备份（仅首次，保留最早原版）——备份成功才允许继续；
     * 2) 重打包到临时文件 → 原子覆盖原 APK。
     * 内容 = 备份基线上的原包 + 会话全部修改。
     */
    public static File regen(Context ctx, File apk) throws IOException {
        Session s = get(apk);
        File parent = apk.getParentFile();
        String base = stripExt(apk.getName());

        // 1) 原包备份（只备份一次，保留最早版本）——这是原地写入的安全网，必须成功
        File bak = new File(parent, base + "_bak.apk");
        if (!bak.exists()) copy(apk, bak);
        if (!bak.exists() || bak.length() <= 0)
            throw new IOException("原包备份失败，已取消写入: " + bak.getName());

        // 2) 重打包 → 临时文件 → 原子覆盖原 APK
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
        safeReplace(tmpOut, apk);
        s.dirty = true;
        s.prompted = false;
        return apk;
    }

    /**
     * 兼容保留（v1.66.0~v1.66.7 语义：修改版文件）。
     * v1.66.8 起修改原地写回，修改版就是 apk 本身。
     */
    public static File editApkOf(File apk) {
        return apk;
    }

    public static String editName(File apk) {
        return stripExt(apk.getName()) + "_edit.apk";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void copy(File src, File dst) throws IOException {
        // v1.67.1：两个流都先置 null——FileOutputStream 构造抛异常时 InputStream 已打开，
        // 不进 try 就没人关（原实现此处泄漏句柄）
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 用 tmp 安全替换 target（签名 / 重打包原地覆盖用）。
     *
     * <p>v1.99.1：实现移到 {@link AtomicReplace} —— 压缩包写回（ZipUtil / ArchiveWrite）
     * 原来各有一份同源实现，三处的失败姿态还不一致（有的失败会顺手删掉临时文件，
     * 有的备份后缀不同）。现在只有一份代码、一个备份后缀 {@code .bakrepl}。</p>
     *
     * <p>语义保持不变：<b>任何一步失败都不让原文件消失</b>；成功时才消费 tmp，
     * 失败时 tmp 与备份一起留着供人工恢复。</p>
     */
    public static void safeReplace(File tmp, File target) throws IOException {
        AtomicReplace.replace(tmp, target);
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
