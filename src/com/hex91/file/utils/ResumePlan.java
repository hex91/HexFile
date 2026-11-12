package com.hex91.file.utils;

import com.hex91.file.fs.*;
import java.io.File;

/**
 * v1.88.0 断点续传决策（纯 Java，可在桌面单测）：判断一个 {@code .hpart} 临时文件
 * 能不能接着传、从哪个偏移接着传，还是必须丢掉重来。
 *
 * <p>为什么要有独立的决策层：续传最怕的不是「传不动」，而是「传错」——
 * 远端文件在这期间被替换/改写过，而我们照着旧的 .part 长度往后接，
 * 拼出来的是一个大小对得上、内容却错位的文件，用户很可能几个月后才发现。
 * 所以这里对每个可疑点都取「宁可重传」的保守分支：</p>
 *
 * <ul>
 *   <li>没有 sidecar 校验信息（size/mtime）→ 重传。凭空接字节等于赌运气。</li>
 *   <li>远端大小变了 → 重传（文件被替换成同名的新版本）。</li>
 *   <li>远端 mtime 变了（超出容差）→ 重传。容差默认 2 分钟，因为 FTP 的
 *       LIST 时间只到分钟、SMB 与 WebDAV 到秒，同一文件在两协议间探测可能差几十秒。</li>
 *   <li>.part 比远端还大 → 重传（远端缩水，剩下的字节没有任何意义）。</li>
 *   <li>.part 正好等于远端大小 → 只差改名（上次其实已经传完，只是没来得及改名）。</li>
 * </ul>
 */
public final class ResumePlan {

    /** 从头下载（丢弃 .part） */
    public static final int FRESH = 0;
    /** 从 offset 续传 */
    public static final int RESUME = 1;
    /** .part 已完整，只需改名成目标文件 */
    public static final int COMPLETE = 2;

    /** 未完成文件后缀：{@code movie.mp4} → {@code movie.mp4.hpart} */
    public static final String PART_SUFFIX = ".hpart";
    /** 校验信息后缀：记录远端 size/mtime，决定能不能续 */
    public static final String META_SUFFIX = ".hpart.meta";

    /** 远端 mtime 容差（2 分钟） */
    public static final long TOLERANCE_MS = 2 * 60 * 1000L;

    private ResumePlan() {}

    /** 决策结果 */
    public static final class Plan {
        public final int mode;
        /** 续传起点（mode==RESUME 时有意义） */
        public final long offset;
        /** 人话原因（日志/提示用） */
        public final String reason;

        Plan(int mode, long offset, String reason) {
            this.mode = mode;
            this.offset = offset;
            this.reason = reason;
        }

        public boolean isResume() { return mode == RESUME; }
        public boolean isComplete() { return mode == COMPLETE; }

        public String describe() {
            if (mode == RESUME) return "续传（已有 " + FileOps.formatSize(offset) + "，" + reason + "）";
            if (mode == COMPLETE) return "临时文件已完整，直接完成（" + reason + "）";
            return "从头下载（" + reason + "）";
        }
    }

    /**
     * 判定续传方案。
     *
     * @param partExists  .hpart 是否存在
     * @param partLen     .hpart 长度
     * @param remoteSize  远端文件大小；<0 表示未知
     * @param metaSize    sidecar 里记录的远端大小；0 表示没有 sidecar
     * @param metaMtime   sidecar 里记录的远端 mtime；0 表示未知
     * @param remoteMtime 本次探测到的远端 mtime；0 表示未知
     */
    public static Plan decide(boolean partExists, long partLen, long remoteSize,
                              long metaSize, long metaMtime, long remoteMtime) {
        return decide(partExists, partLen, remoteSize, metaSize, metaMtime, remoteMtime, TOLERANCE_MS);
    }

    public static Plan decide(boolean partExists, long partLen, long remoteSize,
                              long metaSize, long metaMtime, long remoteMtime, long tol) {
        if (remoteSize < 0) return new Plan(FRESH, 0, "远端大小未知");
        if (!partExists || partLen <= 0) return new Plan(FRESH, 0, "没有未完成的临时文件");
        if (partLen > remoteSize) return new Plan(FRESH, 0, "临时文件比远端大");
        if (metaSize <= 0) return new Plan(FRESH, 0, "缺少上次的校验信息");
        if (metaSize != remoteSize) return new Plan(FRESH, 0, "远端大小已变");
        if (metaMtime > 0 && remoteMtime > 0 && Math.abs(metaMtime - remoteMtime) > tol)
            return new Plan(FRESH, 0, "远端文件已更新");
        if (partLen == remoteSize) return new Plan(COMPLETE, partLen, "大小一致");
        return new Plan(RESUME, partLen, "远端未变");
    }

    // ----------------------------------------------------------------
    // 临时文件 / 校验文件的路径与内容
    // ----------------------------------------------------------------

    /** {@code a/b.zip} → {@code a/b.zip.hpart} */
    public static File partOf(File dest) {
        return new File(dest.getPath() + PART_SUFFIX);
    }

    /** {@code a/b.zip} → {@code a/b.zip.hpart.meta} */
    public static File metaOf(File dest) {
        return new File(dest.getPath() + META_SUFFIX);
    }

    /** sidecar 内容：「v1\\n<size>\\n<mtime>」，纯文本便于用户自己看/删 */
    public static String metaText(long size, long mtime) {
        return "v1\n" + size + "\n" + mtime + "\n";
    }

    /**
     * 解析 sidecar；格式不符返回 null。
     * 容错：多空行、Windows CRLF、尾部无换行都能读。
     */
    public static long[] parseMeta(String text) {
        if (text == null) return null;
        String[] lines = text.replace("\r", "").split("\n");
        if (lines.length < 3 || !"v1".equals(lines[0].trim())) return null;
        try {
            long size = Long.parseLong(lines[1].trim());
            long mtime = Long.parseLong(lines[2].trim());
            return new long[]{size, mtime};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
