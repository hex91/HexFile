package com.hex91.file.net;

import com.hex91.file.fs.TempFiles;
import com.hex91.file.utils.SyncPlan;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.87.0 云同步执行器：扫描两侧 → 出计划 → 按计划传文件。
 *
 * <p>必须跑在后台线程（网络 IO）。UI 通过 {@link Callback} 拿进度，
 * 取消用 {@link AtomicBoolean}——不中断连接，只在文件边界退出，
 * 避免留下写了一半的远端文件（上传失败的半截由 WebDAV 的 PUT 语义兜住）。</p>
 */
public final class SyncRunner {

    /** 单侧最多扫这么多项，防止误选根目录把手机扫穿 */
    public static final int MAX_FILES = 5000;
    /** 递归深度上限 */
    public static final int MAX_DEPTH = 10;

    /** mtime 容差：WebDAV / FAT 都是秒级，3 秒足够吃掉舍入误差 */
    public static final long TOLERANCE_MS = 3000;

    public interface Callback {
        void onProgress(int done, int total, String path);

        void onLog(String msg);

        void onFinish(Report r);
    }

    /** 一次同步的结果 */
    public static final class Report {
        public int uploaded, downloaded, conflicts, failed;
        public final List<String> errors = new ArrayList<String>();
        public boolean cancelled;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("上传 ").append(uploaded)
              .append(" · 下载 ").append(downloaded)
              .append(" · 冲突处理 ").append(conflicts);
            if (failed > 0) sb.append(" · 失败 ").append(failed);
            if (cancelled) sb.append("\n已取消");
            return sb.toString();
        }
    }

    private SyncRunner() {}

    // ------------------------------------------------------------------ 扫描

    /** 递归列出本地目录（相对路径 + 大小 + mtime） */
    public static List<SyncPlan.Entry> scanLocal(File root) {
        List<SyncPlan.Entry> out = new ArrayList<SyncPlan.Entry>();
        if (root != null && root.isDirectory()) walkLocal(root, "", out, 0);
        return out;
    }

    private static void walkLocal(File dir, String rel, List<SyncPlan.Entry> out, int depth) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_FILES) return;
            String path = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            boolean isDir = f.isDirectory();
            out.add(new SyncPlan.Entry(path, isDir, isDir ? 0 : f.length(),
                    f.lastModified()));
            if (isDir) walkLocal(f, path, out, depth + 1);
        }
    }

    /** 递归列出远端目录 */
    public static List<SyncPlan.Entry> scanRemote(NetFs fs, String root) throws IOException {
        List<SyncPlan.Entry> out = new ArrayList<SyncPlan.Entry>();
        walkRemote(fs, root, "", out, 0);
        return out;
    }

    private static void walkRemote(NetFs fs, String root, String rel,
                                   List<SyncPlan.Entry> out, int depth) throws IOException {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        String abs = join(root, rel);
        List<NetFs.NetEntry> kids = fs.list(abs);
        if (kids == null) return;
        for (NetFs.NetEntry e : kids) {
            if (out.size() >= MAX_FILES) return;
            if (e == null || e.name == null || e.name.isEmpty()) continue;
            String path = rel.isEmpty() ? e.name : rel + "/" + e.name;
            out.add(new SyncPlan.Entry(path, e.dir, e.dir ? 0 : e.size, e.mtime));
            if (e.dir) walkRemote(fs, root, path, out, depth + 1);
        }
    }

    // ------------------------------------------------------------------ 计划

    public static SyncPlan.Plan preview(NetFs fs, String remoteRoot, File localRoot,
                                        int mode) throws IOException {
        List<SyncPlan.Entry> local = scanLocal(localRoot);
        List<SyncPlan.Entry> remote = scanRemote(fs, remoteRoot);
        return SyncPlan.build(local, remote, mode, TOLERANCE_MS);
    }

    // ------------------------------------------------------------------ 执行

    public static Report run(NetFs fs, String remoteRoot, File localRoot,
                             SyncPlan.Plan plan, Callback cb, AtomicBoolean cancel) {
        Report rep = new Report();
        int total = plan.work();
        int done = 0;
        for (SyncPlan.Item it : plan.items) {
            if (cancel != null && cancel.get()) {
                rep.cancelled = true;
                break;
            }
            if (it.action == SyncPlan.ACTION_SKIP) continue;
            done++;
            if (cb != null) cb.onProgress(done, total, it.path);
            try {
                if (it.action == SyncPlan.ACTION_UPLOAD) {
                    File src = new File(localRoot, it.path);
                    if (it.dir) {
                        safeMkdir(fs, join(remoteRoot, it.path));
                    } else {
                        upload(fs, src, join(remoteRoot, it.path));
                        rep.uploaded++;
                        log(cb, "↑ " + it.path);
                    }
                } else if (it.action == SyncPlan.ACTION_DOWNLOAD) {
                    File dst = new File(localRoot, it.path);
                    download(fs, join(remoteRoot, it.path), dst, it.remoteMtime);
                    rep.downloaded++;
                    log(cb, "↓ " + it.path);
                } else if (it.action == SyncPlan.ACTION_CONFLICT) {
                    if (it.dir) {
                        // 一边是目录一边是文件：不做任何覆盖，留给用户判断
                        rep.failed++;
                        rep.errors.add(it.path + "：本地与远端类型不同（目录/文件）");
                        log(cb, "⚠ 跳过类型冲突 " + it.path);
                    } else {
                        File dst = new File(localRoot, it.path);
                        File backup = new File(localRoot,
                                SyncPlan.conflictName(it.path, System.currentTimeMillis()));
                        if (dst.exists() && !dst.renameTo(backup)) {
                            throw new IOException("无法重命名本地副本");
                        }
                        // 重命名后原路径已空出，直接把远端版本拉下来
                        download(fs, join(remoteRoot, it.path), dst, it.remoteMtime);
                        rep.conflicts++;
                        log(cb, "⚡ " + it.path + " → 本地副本另存为 "
                                + backup.getName());
                    }
                }
            } catch (Throwable e) {
                rep.failed++;
                String msg = e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage();
                rep.errors.add(it.path + "：" + msg);
                log(cb, "✗ " + it.path + "：" + msg);
            }
        }
        if (cb != null) cb.onFinish(rep);
        return rep;
    }

    // ------------------------------------------------------------------ 传输

    private static void upload(NetFs fs, File src, String remote) throws IOException {
        if (!src.isFile()) throw new IOException("本地文件不存在");
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = fs.openWrite(remote);
            try {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                // close 里会等服务器响应码，上传失败在这里才暴露
                out.close();
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void download(NetFs fs, String remote, File dst, long mtime)
            throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建本地目录");
        }
        File part = new File(dst.getAbsolutePath() + TempFiles.PART);
        // v2.4.0（R-08）：下载期间登记为活动临时文件，遗留扫描不会把它列出来
        // 让用户删掉 —— .part 是这个任务唯一的进度载体，删了等于丢已下载的字节
        TempFiles.begin(part);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = fs.openRead(remote);
            out = new FileOutputStream(part);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            if (!part.renameTo(dst)) {
                // 极少数情况 rename 失败（目标已存在且被占用）→ 退化为覆盖写
                if (!dst.delete() || !part.renameTo(dst)) {
                    throw new IOException("保存失败");
                }
            }
            if (mtime > 0) dst.setLastModified(mtime);
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ignored) {
            }
            if (out != null) try {
                out.close();
            } catch (IOException ignored) {
            }
            // 先注销再删：即便这次下载失败、.part 要删掉，注册表里也不能留残留
            TempFiles.end(part);
            if (part.exists()) part.delete();
        }
    }

    private static void safeMkdir(NetFs fs, String remote) {
        try {
            fs.mkdir(remote);
        } catch (Throwable ignored) {
            // 已存在时不同实现返回不同错误码，这里统一忽略
        }
    }

    /** 远端根 + 相对路径；NetFs 的路径不带前导 '/' */
    static String join(String root, String rel) {
        String r = SyncPlan.norm(root);
        String p = SyncPlan.norm(rel);
        if (p.isEmpty()) return r;
        return r.isEmpty() ? p : r + "/" + p;
    }

    private static void log(Callback cb, String msg) {
        if (cb != null) cb.onLog(msg);
    }
}
