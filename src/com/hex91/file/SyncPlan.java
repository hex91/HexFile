package com.hex91.file;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.87.0 云同步的差异计算（纯 Java，可桌面单测）。
 *
 * <h3>设计取向：宁可少做事，不可做错事</h3>
 * 文件同步最贵的错误是「批量删错 / 用旧文件覆盖新文件」。所以这里：
 * <ul>
 *   <li><b>永不传播删除</b>——只统计「仅远端存在 / 仅本地存在」，不自动删任何一边；</li>
 *   <li><b>冲突保留两份</b>——两边都改过时，本地文件改名加 {@code .conflict-时间戳}，
 *       再把远端版本拉下来，谁都不丢；</li>
 *   <li><b>mtime 容差</b>——WebDAV 与 FAT 只精确到秒，甚至有的服务器给本地时间，
 *       容差内且大小相同视为「同一份」，不做无意义的双向传输。</li>
 * </ul>
 *
 * <p>没有基线（上次同步状态）就无法判断「谁改过」，只能靠 mtime 比较；
 * 这是刻意为之：引入基线文件会再引入「基线丢失后怎么恢复」的一整套问题。</p>
 */
public final class SyncPlan {

    public static final int MODE_UP = 0;      // 本地 → 远端
    public static final int MODE_DOWN = 1;    // 远端 → 本地
    public static final int MODE_BOTH = 2;    // 双向

    public static final int ACTION_UPLOAD = 1;
    public static final int ACTION_DOWNLOAD = 2;
    public static final int ACTION_SKIP = 3;
    public static final int ACTION_CONFLICT = 4;

    private SyncPlan() {}

    /** 一侧的文件元数据（path 为相对路径，'/' 分隔，无前导斜杠） */
    public static final class Entry {
        public final String path;
        public final boolean dir;
        public final long size;
        public final long mtime;

        public Entry(String path, boolean dir, long size, long mtime) {
            this.path = norm(path);
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
        }
    }

    /** 一条待执行动作 */
    public static final class Item {
        public final String path;
        public final int action;
        public final boolean dir;
        public final long localSize, remoteSize, localMtime, remoteMtime;

        Item(String path, int action, boolean dir,
             long localSize, long remoteSize, long localMtime, long remoteMtime) {
            this.path = path;
            this.action = action;
            this.dir = dir;
            this.localSize = localSize;
            this.remoteSize = remoteSize;
            this.localMtime = localMtime;
            this.remoteMtime = remoteMtime;
        }
    }

    /** 计划结果 */
    public static final class Plan {
        public final List<Item> items = new ArrayList<Item>();
        public int upload, download, skip, conflict, localOnly, remoteOnly;

        /** 真正需要传输的条数（skip 不算） */
        public int work() {
            return upload + download + conflict;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("上传 ").append(upload)
              .append(" · 下载 ").append(download)
              .append(" · 冲突 ").append(conflict)
              .append(" · 跳过 ").append(skip);
            if (localOnly > 0) sb.append("\n仅本地存在 ").append(localOnly).append(" 项（不会删除远端）");
            if (remoteOnly > 0) sb.append("\n仅远端存在 ").append(remoteOnly).append(" 项（不会删除本地）");
            return sb.toString();
        }
    }

    /**
     * 计算差异。
     *
     * @param local     本地清单（目录与文件都要有，目录用于类型冲突判断）
     * @param remote    远端清单
     * @param mode      {@link #MODE_UP} / {@link #MODE_DOWN} / {@link #MODE_BOTH}
     * @param tolerance mtime 容差（毫秒），建议 3000
     */
    public static Plan build(List<Entry> local, List<Entry> remote, int mode, long tolerance) {
        Plan plan = new Plan();
        Map<String, Entry> rmap = index(remote);
        Map<String, Entry> lmap = index(local);
        long tol = Math.max(0, tolerance);

        for (Entry l : local) {
            Entry r = rmap.get(l.path);
            if (r == null) {
                if (l.dir) {
                    // 本地目录远端没有：上传模式下建目录；下载/双向模式下不主动建（等里面的文件触发）
                    if (mode == MODE_UP) {
                        plan.items.add(item(l, ACTION_UPLOAD));
                        plan.upload++;
                    } else {
                        plan.localOnly++;
                    }
                } else if (mode == MODE_DOWN) {
                    plan.localOnly++;      // 下载模式不动本地独有文件
                } else {
                    plan.items.add(item(l, ACTION_UPLOAD));
                    plan.upload++;
                }
                continue;
            }
            // 两边都有
            if (l.dir && r.dir) { plan.skip++; continue; }
            if (l.dir != r.dir) {
                plan.items.add(new Item(l.path, ACTION_CONFLICT, l.dir,
                        l.size, r.size, l.mtime, r.mtime));
                plan.conflict++;
                continue;
            }
            boolean sameSize = l.size == r.size;
            boolean sameTime = Math.abs(l.mtime - r.mtime) <= tol;
            if (sameSize && sameTime) { plan.skip++; continue; }

            int action;
            if (mode == MODE_UP) {
                action = ACTION_UPLOAD;
            } else if (mode == MODE_DOWN) {
                action = ACTION_DOWNLOAD;
            } else if (r.mtime > l.mtime + tol) {
                action = ACTION_DOWNLOAD;
            } else if (l.mtime > r.mtime + tol) {
                action = ACTION_UPLOAD;
            } else if (sameSize) {
                // 大小相同、时间在容差内——已在上面的 skip 分支处理，这里不会到
                action = ACTION_SKIP;
            } else {
                action = ACTION_CONFLICT;
            }
            if (action == ACTION_SKIP) { plan.skip++; continue; }
            plan.items.add(new Item(l.path, action, false, l.size, r.size, l.mtime, r.mtime));
            if (action == ACTION_UPLOAD) plan.upload++;
            else if (action == ACTION_DOWNLOAD) plan.download++;
            else plan.conflict++;
        }

        for (Entry r : remote) {
            if (lmap.containsKey(r.path)) continue;
            if (r.dir) { plan.remoteOnly++; continue; }
            if (mode == MODE_UP) {
                plan.remoteOnly++;         // 上传模式不删远端
                continue;
            }
            plan.items.add(item(r, ACTION_DOWNLOAD));
            plan.download++;
        }
        return plan;
    }

    private static Item item(Entry e, int action) {
        boolean up = action == ACTION_UPLOAD;
        return new Item(e.path, action, e.dir,
                up ? e.size : -1, up ? -1 : e.size,
                up ? e.mtime : -1, up ? -1 : e.mtime);
    }

    private static Map<String, Entry> index(List<Entry> list) {
        Map<String, Entry> m = new HashMap<String, Entry>();
        if (list == null) return m;
        for (Entry e : list) {
            if (e != null && e.path != null && !e.path.isEmpty()) m.put(e.path, e);
        }
        return m;
    }

    /** 统一相对路径：反斜杠转 /、去掉前导与末尾 /、折叠重复 */
    public static String norm(String p) {
        if (p == null) return "";
        String s = p.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        while (s.contains("//")) s = s.replace("//", "/");
        return s;
    }

    /** 冲突副本的文件名：a.txt → a.conflict-20261110-1130.txt */
    public static String conflictName(String path, long now) {
        String name = path;
        String dir = "";
        int i = path.lastIndexOf('/');
        if (i >= 0) {
            dir = path.substring(0, i + 1);
            name = path.substring(i + 1);
        }
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        return dir + base + ".conflict-" + stamp(now) + ext;
    }

    /** 20261110-1130（本地时区，只用于区分副本，不做业务判断） */
    public static String stamp(long now) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now);
        return String.format(java.util.Locale.US, "%04d%02d%02d-%02d%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
    }
}
