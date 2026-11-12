package com.hex91.file.fs;

import com.hex91.file.*;
import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * 工作目录（应用数据根）。
 *
 * <p>默认 {@code /sdcard/HexFile}，回收站、备份、Smali 输出、APK 导出、网络存储下载等
 * 全部挂在这下面。用户可在「设置 → 工作目录」改到别处，例如外置卡
 * {@code /storage/1A2B-3C4D/HexFile}。</p>
 *
 * <p>为什么用静态覆盖值而不是到处传 Context：{@link Trash}、{@link BackupRestore}
 * 这类工具类是纯静态的，且其中一部分逻辑要在桌面单测里跑（不能碰
 * {@code Environment}）。启动时 {@link #init(Context)} 读一次偏好即可，
 * 之后所有调用点只认 {@link #base()}。</p>
 *
 * <p>切换目录不会迁移旧数据——旧目录原样保留，避免在设置页误触触发大文件复制。</p>
 */
public final class WorkDir {

    /** 默认目录名 */
    public static final String DEFAULT_NAME = "HexFile";

    /** 设置偏好文件名（与视图偏好区分开） */
    public static final String PREF = "hf_settings";

    /** 偏好键：自定义工作目录绝对路径，空串表示使用默认 */
    public static final String KEY = "work_dir";

    /** null = 未自定义，走默认（静态字段而非 SharedPreferences 读取，避免热路径反复读盘） */
    private static volatile File override;

    private WorkDir() {}

    /** 启动时调用一次（Application/主界面均可，重复调用无害） */
    public static void init(Context c) {
        if (c == null) return;
        try {
            override = normalize(c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, null));
        } catch (Throwable t) {
            override = null;
        }
    }

    /** 当前工作目录根 */
    public static File base() {
        File o = override;
        if (o != null) return o;
        return new File(Environment.getExternalStorageDirectory(), DEFAULT_NAME);
    }

    /** 当前工作目录的绝对路径（展示用） */
    public static String path() {
        return base().getAbsolutePath();
    }

    /** 工作目录下的子项，如 {@code sub("Trash")} */
    public static File sub(String name) {
        return new File(base(), name);
    }

    /** 是否自定义过（默认目录时返回 false，设置页用来决定要不要显示「恢复默认」） */
    public static boolean isCustom() {
        return override != null;
    }

    /**
     * 保存新的工作目录并立即生效。
     *
     * @param absPath 绝对路径；null / 空串 / 非绝对路径 = 恢复默认
     * @return 偏好写入是否成功
     */
    public static boolean set(Context c, String absPath) {
        File f = normalize(absPath);
        override = f;
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY, f == null ? "" : f.getAbsolutePath())
                    .commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 恢复默认工作目录 */
    public static boolean reset(Context c) {
        return set(c, null);
    }

    /** 默认工作目录的绝对路径（提示语用） */
    public static String defaultPath() {
        return new File(Environment.getExternalStorageDirectory(), DEFAULT_NAME).getAbsolutePath();
    }

    /**
     * 规范化用户输入：只接受以 / 开头的绝对路径；去掉末尾多余的 /。
     * 纯函数（public 便于桌面单测与设置页预校验）。
     */
    public static File normalize(String p) {
        if (p == null) return null;
        String s = p.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("/")) return null;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return new File(s);
    }
}
