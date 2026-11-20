package com.hex91.file.utils;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.hex91.file.fs.TempFiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * v2.12.0（F-17）：把外部应用递进来的文件落到本应用目录里。
 *
 * <p><b>为什么必须「先复制进来」</b>：微信/邮件/浏览器给的地址是 {@code content://}，
 * 它们的读权限只在本进程收到 Intent 的那一刻有效，而且生命周期由对方控制（对方进程被杀、
 * 或授时到期，流就打不开了）。所以本类只做一件事——把流搬成工作目录里的真实文件，
 * 之后编辑/删除/回收站/压缩全部按普通路径处理，不必再考虑「外部 URI 能不能写」。</p>
 *
 * <p>顺带解决三件容易漏的事：</p>
 * <ul>
 *   <li><b>重名不覆盖</b>：同名文件依次落到 {@code 名字 (1).ext}，绝不覆盖用户已有文件；</li>
 *   <li><b>不留半成品</b>：写入走 {@code 名字.part} + {@link TempFiles} 登记，成功才改名，
 *       取消/失败删干净——遗留扫描也因此不会把进行中的导入当垃圾列出来；</li>
 *   <li><b>URL 解码后的名字</b>：优先 {@code DISPLAY_NAME}（对方给的人类可读名），
 *       取不到才退回 URI 末段，两者都过一遍 {@link #safeName(String)}（去掉路径分隔符、
 *       控制字符、FAT 非法字符，防止 {@code ../../} 这种名字写到目录外面）。</li>
 * </ul>
 *
 * <p>纯逻辑 + {@link File}，不碰 UI：进度与取消通过 {@link Watch} 回调，
 * 便于桌面单测（{@code test/IntentImportTest.java} 覆盖命名与去重）。</p>
 */
public final class IntentImport {

    /** 单次导入的文件数上限：再多就不是「分享一个文件」，更像是误操作（微信多选上限 9） */
    public static final int MAX_FILES = 100;
    /** 单次导入总量上限（4 GB）；超出的项跳过并在结果里报告，不是静默丢弃 */
    public static final long MAX_TOTAL = 4L * 1024L * 1024L * 1024L;

    private static final int BUF = 128 * 1024;
    /** 进度回调节流：128 KB 一块，1 GB 文件有 8000 块，逐块回主线程会把 UI 消息队列刷满 */
    private static final long TICK_MS = 200;

    private IntentImport() {}

    /** 一个待导入项（来源 + 期望文件名 + 已知大小，大小未知时 -1） */
    public static final class Item {
        public final Uri uri;
        public final String name;
        public final long size;

        public Item(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    /** 进度与取消（在**后台线程**回调；实现方自己切主线程） */
    public interface Watch {
        /** @param total 来源声明的大小，未知时 &lt;= 0 */
        void onProgress(String name, long done, long total);

        boolean cancelled();
    }

    /** 导入结果：成功/失败/跳过计数 + 落地文件，供 UI 拼一句可读的结论 */
    public static final class Result {
        public int ok;
        public int failed;
        public int skipped;
        public boolean cancelled;
        public long bytes;
        /** 成功落地的目标文件（按导入顺序），VIEW 场景用它决定导入后打开哪个 */
        public final List<File> files = new ArrayList<File>();
        public final List<String> errors = new ArrayList<String>();

        public String summary() {
            return "IMPORT:" + ok + ":" + skipped + ":" + failed + ":" + (cancelled ? 1 : 0);
        }
    }

    /** 取消信号：用异常跳出拷贝循环，比每层返回 boolean 干净（同 FileTaskEngine.Cancel） */
    static final class Cancelled extends RuntimeException {
        Cancelled() { super("cancelled"); }
    }

    // ------------------------------------------------------------------ 入口判定

    /** 这个 Intent 是不是「别人把文件递给我们」（分享 / 用 HexFile 打开） */
    public static boolean isIncoming(Intent it) {
        if (it == null) return false;
        String a = it.getAction();
        if (Intent.ACTION_SEND.equals(a) || Intent.ACTION_SEND_MULTIPLE.equals(a)) return true;
        return Intent.ACTION_VIEW.equals(a) && it.getData() != null;
    }

    /** 是否是「用 HexFile 打开」（导入后应直接打开，而不是只落到目录里） */
    public static boolean isView(Intent it) {
        return it != null && Intent.ACTION_VIEW.equals(it.getAction());
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 从 Intent 里取出去重后的待导入项。
     *
     * <p>四路来源都要看：{@code EXTRA_STREAM}（标准）、{@code ClipData}（不少厂商分享只填这个）、
     * 多选的 {@code EXTRA_STREAM} 列表、以及 VIEW 的 {@code getData()}。去重按 URI 字符串，
     * 因为同一份内容常常既在 EXTRA_STREAM 又在 ClipData 里。</p>
     */
    public static List<Item> collect(Context c, Intent it) {
        List<Item> out = new ArrayList<Item>();
        if (c == null || it == null) return out;
        Set<String> seen = new LinkedHashSet<String>();

        String action = it.getAction();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            // 个别应用塞进来的不是 Uri 列表（Bitmap / CharSequence），所以逐个 instanceof 判，
            // 而不是先强转再 catch ClassCastException
            ArrayList<android.os.Parcelable> list =
                    it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (android.os.Parcelable p : list) {
                    if (p instanceof Uri) add(c, out, seen, (Uri) p);
                }
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            android.os.Parcelable p = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) add(c, out, seen, (Uri) p);
        } else {
            add(c, out, seen, it.getData());
        }

        ClipData clip = it.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item ci = clip.getItemAt(i);
                if (ci != null) add(c, out, seen, ci.getUri());
            }
        }
        return out;
    }

    private static void add(Context c, List<Item> out, Set<String> seen, Uri u) {
        if (u == null) return;
        String key = u.toString();
        if (key.length() == 0 || !seen.add(key)) return;
        String name = null;
        long size = -1;
        // 只对 content:// 查 OpenableColumns：file:// 走别的解析，查 resolver 只会白跑一次
        if ("content".equals(u.getScheme())) {
            try (Cursor cur = c.getContentResolver().query(u,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = cur.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                    if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
                }
            } catch (Throwable t) {
                // 对方 provider 不支持查询（或授权已失效）：名字退回 URI 末段，大小未知。
                // 这不是错误路径上的常见分支，但要留痕 —— 否则「文件名变成一串数字」
                // 这种反馈永远查不到原因。
                LogRing.add("IntentImport", "query failed for " + u + ": " + t);
            }
        }
        if (name == null) name = u.getLastPathSegment();
        name = safeName(name);
        if (name == null) name = "shared-" + System.currentTimeMillis();
        out.add(new Item(u, name, size));
    }

    // ------------------------------------------------------------------ 命名（可单测）

    /**
     * 把「对方给的名字」收敛成一个安全的文件名；无法救活时返回 null（调用方再兜底）。
     *
     * <p>挡的是三类东西：路径片段（{@code ../../data/x} 会写到目标目录之外）、
     * 文件系统非法字符（FAT/EXFAT 上是真的创建失败）、以及全空/纯点（{@code .} / {@code ..}）。
     * 长度按 UTF-8 直觉截到 120 字符——ext4 上限 255 字节，中文名 120 字符已到 360 字节，
     * 所以这里按「字符数」保守截断并保留扩展名。</p>
     */
    public static String safeName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (cut >= 0) s = s.substring(cut + 1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7f) continue;                       // 控制字符
            if ("\\/:*?\"<>|".indexOf(ch) >= 0) { sb.append('_'); continue; }
            sb.append(ch);
        }
        s = sb.toString().trim();
        // 结尾的点和空格在 FAT 上会被吞掉，等于换个名字，干脆自己先去掉
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.length() == 0 || s.equals(".") || s.equals("..")) return null;
        if (s.length() > 120) {
            int dot = s.lastIndexOf('.');
            String ext = (dot > 0 && s.length() - dot <= 12) ? s.substring(dot) : "";
            int keep = Math.max(1, 120 - ext.length());
            s = s.substring(0, keep) + ext;
        }
        return s;
    }

    /**
     * 在 dir 里给 name 找一个不冲突的目标：{@code a.txt} → {@code a (1).txt} → {@code a (2).txt}…
     *
     * <p>不覆盖是硬要求：分享进来的 {@code photo.jpg} 十次就该是十个不同文件，
     * 而不是把上一次的悄悄替换掉。</p>
     */
    public static File uniqueTarget(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    // ------------------------------------------------------------------ 复制

    /** 逐项复制进 dstDir；单项失败不影响其余项（部分成功也要如实报告） */
    public static Result copyInto(Context c, List<Item> items, File dstDir, Watch watch) {
        Result r = new Result();
        if (items == null || items.isEmpty()) return r;
        if (dstDir == null) {
            r.failed = items.size();
            r.errors.add("没有目标目录");
            return r;
        }
        if (!dstDir.isDirectory() && !dstDir.mkdirs()) {
            r.failed = items.size();
            r.errors.add("无法创建目录: " + dstDir.getAbsolutePath());
            return r;
        }

        long totalSoFar = 0;
        int n = 0;
        for (Item it : items) {
            if (n >= MAX_FILES || totalSoFar >= MAX_TOTAL) { r.skipped++; continue; }
            if (watch != null && watch.cancelled()) { r.cancelled = true; break; }
            if (it.size > 0 && totalSoFar + it.size > MAX_TOTAL) { r.skipped++; continue; }
            n++;
            File target = uniqueTarget(dstDir, it.name);
            File tmp = new File(dstDir, target.getName() + TempFiles.PART);
            TempFiles.begin(tmp);
            boolean done = false;
            try {
                long got = pipe(c, it, tmp, watch);
                if (!tmp.renameTo(target)) throw new IOException("改名失败: " + target.getName());
                done = true;
                r.ok++;
                r.bytes += got;
                totalSoFar += got;
                r.files.add(target);
            } catch (Cancelled cancel) {
                r.cancelled = true;
            } catch (Throwable t) {
                r.failed++;
                r.errors.add(it.name + ": " + t);
            } finally {
                TempFiles.end(tmp);
                if (!done && tmp.exists()) tmp.delete();   // 取消/失败不留半成品
            }
            if (r.cancelled) break;
        }
        return r;
    }

    /** 真正搬字节；返回写入量（用于对账，不依赖来源声明的大小） */
    private static long pipe(Context c, Item it, File tmp, Watch watch) throws IOException {
        InputStream src = open(c, it.uri);
        if (src == null) throw new FileNotFoundException(String.valueOf(it.uri));
        // try-with-resources：关闭异常由编译器生成的 catch 处理，不需要在业务代码里
        // 写两行「关不上也算了」，也就不会新增空 catch（test/catch_check.sh）
        long done = 0;
        long last = 0;
        try (InputStream in = src; OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[BUF];
            int got;
            while ((got = in.read(buf)) > 0) {
                out.write(buf, 0, got);
                done += got;
                if (watch != null) {
                    long now = System.currentTimeMillis();
                    if (now - last >= TICK_MS) {
                        last = now;
                        watch.onProgress(it.name, done, it.size);
                    }
                    if (watch.cancelled()) throw new Cancelled();
                }
            }
            out.flush();
        }
        return done;
    }

    /** 打开来源流：content:// 走 resolver，file:// 走 FileInputStream（root 区不在分享范围内） */
    private static InputStream open(Context c, Uri u) {
        try {
            if (u == null) return null;
            if ("file".equals(u.getScheme())) {
                String p = u.getPath();
                return p == null ? null : new FileInputStream(p);
            }
            ContentResolver cr = c.getContentResolver();
            return cr.openInputStream(u);
        } catch (Throwable t) {
            return null;
        }
    }
}
