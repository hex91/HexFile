package com.hex91.file;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.99.3：未完成任务的账本容器（{@code SharedPreferences}）。
 *
 * <p><b>为什么从文件换成偏好</b>：账本只有「有没有 / 几条路径」这点信息，启动时要读一次，
 * 而偏好本来就随应用启动被系统加载，读它不需要额外 I/O；也不会在用户目录里多出一个
 * 需要解释的文件。键名固定为 {@link #KEY}（{@code running_task_json}）。</p>
 *
 * <p><b>只碰自己那一个键</b>：{@link #clear()} 用 {@code remove(KEY)}，绝不
 * {@code edit().clear()} —— 这份偏好以后若与别的设置共用，一次 clear 会把用户设置一起抹掉。</p>
 *
 * <p><b>写入必须同步</b>：任务提交那一刻进程随时可能被系统清掉（大文件复制刚开始时最危险）。
 * {@code apply()} 是异步落盘的，进程直接被杀时那次写可能还在队列里 —— 所以这里用
 * {@code commit()}。账本只有几百字节，提交时写一次的开销可以忽略。</p>
 *
 * <p><b>升级迁移</b>：v1.99.2 及以前把账本写在 {@code files/task_journal}。首次读取时若偏好
 * 还没有内容、而旧文件存在，就读进来写进偏好并删掉旧文件 —— 否则升级用户的
 * 「上次没做完」提示会凭空消失。</p>
 */
public final class TaskStore implements TaskJournal.Store {

    /** 偏好文件名（与主界面其它设置分开，避免互相干扰） */
    static final String PREF = "hexfile_task";
    /** 用户要求的键名 */
    static final String KEY = "running_task_json";

    private final SharedPreferences sp;
    private final File legacy;

    private TaskStore(SharedPreferences sp, File legacy) {
        this.sp = sp;
        this.legacy = legacy;
    }

    /**
     * @param c 任意 Context（内部转 ApplicationContext，不持有 Activity）
     */
    public static TaskStore get(Context c) {
        Context app = c.getApplicationContext();
        File legacy = new File(app.getFilesDir(), TaskJournal.FILE_NAME);
        TaskStore s = new TaskStore(
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE), legacy);
        s.migrateLegacy();
        return s;
    }

    /** v1.99.2 的文件账本 → 偏好。只做一次，且只在偏好为空时做（偏好更新） */
    private void migrateLegacy() {
        try {
            if (legacy == null || !legacy.isFile()) return;
            if (sp.contains(KEY)) {           // 偏好已有更新内容，旧文件只是残留
                legacy.delete();
                return;
            }
            List<TaskJournal.Entry> old = TaskJournal.load(legacy);
            if (!old.isEmpty()) {
                String json = TaskJournal.encode(old);
                if (json != null) sp.edit().putString(KEY, json).commit();
            }
            // 迁移完就删：留着的话每次启动都会读一遍，而且下次它已经是「旧格式」了
            legacy.delete();
        } catch (Throwable ignored) {
            // 迁移失败不影响启动：旧提示丢了也就丢了，不能因此进不了应用
        }
    }

    /**
     * 读回未完成任务。偏好里没有、被改坏、格式不认识都返回空列表（不抛）。
     */
    public List<TaskJournal.Entry> load() {
        try {
            return TaskJournal.decode(sp.getString(KEY, null));
        } catch (Throwable t) {
            return new ArrayList<TaskJournal.Entry>();
        }
    }

    /** 覆盖写入；空列表则移除该键 */
    public void save(List<TaskJournal.Entry> es) {
        try {
            String json = TaskJournal.encode(es);
            SharedPreferences.Editor e = sp.edit();
            if (json == null) e.remove(KEY);
            else e.putString(KEY, json);
            e.commit();                       // 见类注释：必须同步落盘
        } catch (Throwable ignored) {}
    }

    /** 只清掉账本这一个键（用户点了「忽略」）；不动同文件里的其它键 */
    public void clear() {
        try {
            sp.edit().remove(KEY).commit();
        } catch (Throwable ignored) {}
    }
}
