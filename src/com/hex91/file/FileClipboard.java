package com.hex91.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用内文件剪贴板（v1.78.0）。
 *
 * <p>补齐文件管理器最基本的一条路径：<b>A 目录复制 → 一路翻到 C 目录 → 粘贴</b>。
 * 以前只有「复制到对面窗格」，想去第三个目录就必须先在两栏里倒一手。</p>
 *
 * <p>只存绝对路径而不是 {@link File} 对象，也不持有任何 Context：跨 Activity、
 * 跨配置变更（旋转）都不会丢，进程被杀就自然清空 —— 这符合剪贴板「临时」的语义。</p>
 *
 * <p>{@link #alive()} 负责过滤「复制之后源文件被删掉了」的条目，避免粘贴时
 * 每个文件都报一次失败。纯 Java，可桌面单测。</p>
 */
public final class FileClipboard {

    private static final FileClipboard INSTANCE = new FileClipboard();

    public static FileClipboard get() { return INSTANCE; }

    private final List<String> paths = new ArrayList<String>();
    /** true = 剪切：粘贴成功后源文件应当消失，粘完即清空 */
    private boolean move;
    private long stamp;

    private FileClipboard() {}

    /** 放入一批文件（覆盖旧的）。move=true 表示剪切 */
    public synchronized void put(List<File> files, boolean move) {
        paths.clear();
        if (files != null) {
            for (File f : files) {
                if (f != null && f.getAbsolutePath() != null) paths.add(f.getAbsolutePath());
            }
        }
        this.move = move && !paths.isEmpty();
        this.stamp = paths.isEmpty() ? 0 : System.currentTimeMillis();
    }

    public synchronized int size() { return paths.size(); }

    public synchronized boolean isEmpty() { return paths.isEmpty(); }

    public synchronized boolean isMove() { return move; }

    public synchronized long stamp() { return stamp; }

    public synchronized void clear() {
        paths.clear();
        move = false;
        stamp = 0;
    }

    /** 剪切是「一次性」的：粘贴提交后立即清空；复制则保留，可以连粘多处 */
    public synchronized void clearIfMove() {
        if (move) clear();
    }

    /** 全部路径的快照（可能含已不存在的文件） */
    public synchronized List<File> files() {
        List<File> out = new ArrayList<File>(paths.size());
        for (String p : paths) out.add(new File(p));
        return out;
    }

    /** 只返回仍然存在的条目：源文件被删/被移走时不该让粘贴整批失败 */
    public synchronized List<File> alive() {
        List<File> out = new ArrayList<File>(paths.size());
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) out.add(f);
        }
        return out;
    }

    /** 顺手把不存在的条目从剪贴板里剔掉，避免按钮一直显示过期的数量 */
    public synchronized void prune() {
        for (int i = paths.size() - 1; i >= 0; i--) {
            if (!new File(paths.get(i)).exists()) paths.remove(i);
        }
        if (paths.isEmpty()) {
            move = false;
            stamp = 0;
        }
    }

    /** 「复制 3 项」/「剪切 1 项」，按钮与提示共用 */
    public synchronized String label() {
        if (paths.isEmpty()) return "剪贴板为空";
        return (move ? "剪切 " : "复制 ") + paths.size() + " 项";
    }
}
