package com.hex91.file.apk;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 归档整包解压（带进度与取消，v2.13.1 / F-11）。
 *
 * <p>为什么不在 {@link ArchiveUtil.Archive} 接口上加「带进度」的 extractAll：那要给
 * 四五个后端各改一遍，而进度只对 <b>一次解压整个包</b> 这件事有意义。这里用后端已经
 * 暴露的 {@code listChildren} + {@code extractEntry} 自己走一遍，格式无关，
 * 也不动任何既有实现（既有实现里 zip 的快速路径、7z 的密码重试都保持原样）。</p>
 *
 * <p>进度的粒度是「一个条目一次」：取消在条目边界生效，正在解的那个条目会写完。
 * 之所以不做单条目内的字节级断点，是因为解压产物是用户的文件，中途停下只会留下
 * 半个文件 —— 那比多等两秒更难解释。</p>
 */
public final class ArchiveExtract {

    /** 进度回调（在调用线程触发，UI 自己转主线程） */
    public interface Listener {
        void onProgress(int done, int total, String name);

        /** 返回 true = 取消；每个条目开始前问一次 */
        boolean cancelled();
    }

    /** 解压结果：完成数 / 总数 / 是否取消 / 失败原因（null = 全部成功） */
    public static final class Result {
        public final int done;
        public final int total;
        public final boolean cancelled;
        public final String error;

        Result(int done, int total, boolean cancelled, String error) {
            this.done = done;
            this.total = total;
            this.cancelled = cancelled;
            this.error = error;
        }
    }

    private ArchiveExtract() {}

    /**
     * 解压归档全部内容到 outDir。
     *
     * <p>先递归列一遍再逐个解：这样进度百分比才有分母。列目录比解压便宜得多，
     * 而且能提前发现「条目表读不出来」（加密、损坏），不用解了一半才失败。</p>
     */
    public static Result run(ArchiveUtil.Archive a, File outDir, Listener l) {
        List<ArchiveUtil.Entry> files = new ArrayList<ArchiveUtil.Entry>();
        try {
            List<String> dirs = new ArrayList<String>();
            dirs.add("");
            // 迭代式广度优先：深目录不会爆栈，也不会重复访问（每个目录只列一次）
            for (int i = 0; i < dirs.size(); i++) {
                for (ArchiveUtil.Entry e : a.listChildren(dirs.get(i))) {
                    if (e.dir) dirs.add(e.full);
                    else files.add(e);
                }
            }
        } catch (Throwable t) {
            return new Result(0, 0, false, ArchiveUtil.brief(t));
        }

        int total = files.size();
        int done = 0;
        for (ArchiveUtil.Entry e : files) {
            if (l != null && l.cancelled()) return new Result(done, total, true, null);
            try {
                a.extractEntry(e.full, outDir);
            } catch (Throwable t) {
                return new Result(done, total, false, ArchiveUtil.brief(t));
            }
            done++;
            if (l != null) l.onProgress(done, total, e.full);
        }
        return new Result(done, total, false, null);
    }
}
