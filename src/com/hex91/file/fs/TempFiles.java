package com.hex91.file.fs;

import com.hex91.file.core.AtomicReplace;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    /** 覆盖写回留下的临时名：{@code 名字.reb<数字>.tmp}（ZipUtil / ArchiveWrite） */
    private static final Pattern REB = Pattern.compile("\\.reb\\d+\\.tmp$");
    /** 上一条正则的两端（v2.6.0 R-26）：生成与识别必须同源，改格式只改这两个常量 */
    private static final String REB_INFIX = ".reb";
    private static final String REB_TAIL = ".tmp";

    /**
     * 写回备份后缀。真定义在 {@link AtomicReplace#BAK_SUFFIX}（生产端是它），
     * 这里只是同包外的引用别名 —— 改后缀只改那一处。
     */
    public static final String BAK = AtomicReplace.BAK_SUFFIX;
    /** 旧版 ApkEditSession 的备份后缀 */
    public static final String BAK_LEGACY = ".replacebak";
    /** 流式写入的半成品后缀（复制、下载、接收、分片统一用它） */
    public static final String PART = ".part";

    /**
     * 正在写的临时文件（规范化绝对路径 → 写者数量）。
     *
     * <p>v2.5.0（R-19）：以前是 {@code Set}，同名路径被两个写者登记时没有计数 ——
     * 先结束的一方直接 remove，仍在写的那一个从注册表里消失，遗留扫描又能把它列出来删掉。
     * 现在改成引用计数：{@code begin} 自增、{@code end} 自减到 0 才移除。</p>
     *
     * <p>登记/注销只发生在文件级起止处（一次拷贝一次），不在每个块上，加锁代价可忽略。</p>
     */
    private static final Map<String, Integer> ACTIVE = new ConcurrentHashMap<String, Integer>();

    private TempFiles() {}

    // ------------------------------------------------------------------ 活动登记

    /** 开始写 f（f 是临时路径）。调用方必须保证配对的 {@link #end}，最好放在 finally 里 */
    public static void begin(File f) {
        if (f == null) return;
        String k = key(f);
        synchronized (ACTIVE) {
            Integer n = ACTIVE.get(k);
            ACTIVE.put(k, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
    }

    /**
     * 结束写 f。
     *
     * <p>必须在 rename 成正式名字<b>之前</b>调用：改名后该路径已不存在，
     * 留在注册表里只会挡住下一次同名写入（同一目标连拷两次）。</p>
     */
    public static void end(File f) {
        if (f == null) return;
        String k = key(f);
        synchronized (ACTIVE) {
            Integer n = ACTIVE.get(k);
            if (n == null || n.intValue() <= 1) ACTIVE.remove(k);
            else ACTIVE.put(k, Integer.valueOf(n.intValue() - 1));
        }
    }

    /** 这个路径是否正被本进程写入（还有至少一个写者） */
    public static boolean isActive(File f) {
        if (f == null) return false;
        Integer n = ACTIVE.get(key(f));
        return n != null && n.intValue() > 0;
    }

    /** 当前活动集合的快照（传给扫描器，避免扫描过程中集合变动导致误判） */
    public static Set<String> snapshot() {
        synchronized (ACTIVE) {
            return new HashSet<String>(ACTIVE.keySet());
        }
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
     * 给 {@code target} 挑一个写回用的临时名（v2.6.0 / R-26）。
     *
     * <p>原来 {@code ArchiveWrite}（两处）与 {@code ZipUtil} 各自拼
     * {@code name + ".reb" + currentTimeMillis() + ".tmp"}，而认名字的只有这里的一条正则。
     * 写的人各拼各的、认的人靠正则猜，只要有一处改了格式（加进程号、改位数），
     * 遗留扫描就再次出现「写了但永远扫不到」—— 正是 R-11 的原始问题。</p>
     *
     * <p>只负责<b>选名字</b>，不创建文件：真正的写入方沿用原有语义（覆盖写），
     * 冲突代价只是覆盖掉一个临时名，不会碰到目标文件。</p>
     *
     * <p>同一毫秒内的两次调用要拿到不同名字（原来三个生产端都没有重试，
     * 同毫秒连写两次会共用同一个临时名）；100 次仍冲突就退化到 {@code nanoTime}。</p>
     */
    public static File rebTmp(File target) {
        File dir = target.getParentFile();
        String base = target.getName();
        for (int i = 0; i < 100; i++) {
            File f = new File(dir, base + REB_INFIX + System.currentTimeMillis() + REB_TAIL);
            if (!f.exists()) return f;
        }
        return new File(dir, base + REB_INFIX + System.nanoTime() + REB_TAIL);
    }

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
