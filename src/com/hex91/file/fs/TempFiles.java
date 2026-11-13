package com.hex91.file.fs;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * v2.4.0（R-08 / R-11）：临时文件的两件事——「谁正在写」和「哪些名字算临时」。
 *
 * <p><b>为什么需要一张注册表</b>：复制/移动、云同步下载、局域网接收、HLS 分片都在写
 * {@code 名字.part}，而「设置 → 遗留文件」按后缀认名字、不看是否活动。用户在 1 GB
 * 复制进行中去点一下清理，那个 {@code .part} 就会被列出来并允许删除 —— 写线程手里
 * 还握着已 unlink 的句柄，拷完 rename 必然失败，任务在几百 MB 处报错。
 * 对局域网接收而言 {@code .part} 还是唯一进度载体，删掉等于把已接收的字节全丢了。</p>
 *
 * <p>所以生产者开工时 {@link #begin}、收尾时 {@link #end}（必须 try/finally），
 * 扫描时传入 {@link #snapshot()}，删除入口再查一次 {@link #isActive} ——
 * 挡住「扫描之后、用户确认之前」才开始的任务。</p>
 *
 * <p><b>为什么判定也放这里</b>：{@code LeftoverScan} 原来自己认三种后缀，
 * 而压缩包写回失败留下的是 {@code 名字.reb<ts>.tmp}、HLS/TS 失败留下 {@code .tmp}，
 * 全都识别不了，永远不会出现在清理列表里。命名与识别收敛到一处，
 * 生产者改了名字就自动被清理逻辑认出（R-11）。</p>
 *
 * <p>纯 JDK：不引用 {@code android.*}，桌面单测直接跑。</p>
 */
public final class TempFiles {

    /** 覆盖写回留下的临时名：{@code 名字.reb<时间戳>.tmp}（ZipUtil / ArchiveWrite） */
    private static final Pattern REB = Pattern.compile("\\.reb\\d+\\.tmp$");

    /** 备份后缀（与 {@link com.hex91.file.core.AtomicReplace} 共用，避免两处各写一遍字符串） */
    public static final String BAK = ".bakrepl";
    /** 旧版 ApkEditSession 的备份后缀 */
    public static final String BAK_LEGACY = ".replacebak";
    /** 流式写入的半成品后缀（复制、下载、接收、分片统一用它） */
    public static final String PART = ".part";

    /**
     * 正在写的临时文件（规范化绝对路径）。
     *
     * <p>用 {@code ConcurrentHashMap} 的 keySet 做并发集合：注册/注销只发生在文件级
     * 起止处（一次拷贝一次），不在每个块上，代价可忽略。</p>
     */
    private static final Set<String> ACTIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private TempFiles() {}

    // ------------------------------------------------------------------ 活动登记

    /** 开始写 f（f 是临时路径）。调用方必须保证配对的 {@link #end}，最好放在 finally 里 */
    public static void begin(File f) {
        if (f != null) ACTIVE.add(key(f));
    }

    /**
     * 结束写 f。
     *
     * <p>必须在 rename 成正式名字<b>之前</b>调用：改名后该路径已不存在，
     * 留在注册表里只会挡住下一次同名写入（同一目标连拷两次）。</p>
     */
    public static void end(File f) {
        if (f != null) ACTIVE.remove(key(f));
    }

    /** 这个路径是否正被本进程写入 */
    public static boolean isActive(File f) {
        return f != null && ACTIVE.contains(key(f));
    }

    /** 当前活动集合的快照（传给扫描器，避免扫描过程中集合变动导致误判） */
    public static Set<String> snapshot() {
        return new HashSet<String>(ACTIVE);
    }

    /** 规范化路径作为键；canonical 失败（路径不存在、权限）时退回绝对路径 */
    public static String key(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        } catch (Throwable e) {
            return f.getPath();
        }
    }

    // ------------------------------------------------------------------ 命名判定

    /**
     * 名字是否是「遗留临时文件」。
     *
     * <p>认四类：{@code .bakrepl}（写回备份）、{@code .replacebak}（旧版备份）、
     * {@code .part}（半成品）、{@code .reb<数字>.tmp}（覆盖写回的临时文件）。</p>
     *
     * <p><b>刻意不认</b> {@code .parts}（HLS 恢复清单目录）与 {@code .merged}
     * （文件合并的输出）—— 它们是功能数据，删掉就得重新下载/重新合并。</p>
     */
    public static boolean isLeftover(String name) {
        if (name == null || name.length() == 0) return false;
        if (name.endsWith(BAK) || name.endsWith(BAK_LEGACY) || name.endsWith(PART)) return true;
        return REB.matcher(name).find();
    }

    public static boolean isLeftover(File f) {
        return f != null && isLeftover(f.getName());
    }

    /** {@code a.zip.bakrepl} → {@code a.zip}；{@code a.zip.reb1699999999999.tmp} → {@code a.zip} */
    public static String ownerOf(String name) {
        if (name == null) return null;
        for (String s : new String[]{BAK, BAK_LEGACY, PART}) {
            if (name.endsWith(s) && name.length() > s.length()) {
                return name.substring(0, name.length() - s.length());
            }
        }
        String stripped = REB.matcher(name).replaceFirst("");
        return stripped.length() > 0 ? stripped : name;
    }
}
