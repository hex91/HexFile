package com.hex91.file.fs;

import com.hex91.file.core.PathCore;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * v2.116.0（O-27 续）：{@link Fs#walk} 的通用实现 —— 只用 {@link Fs#list}，不碰 {@code java.io.File}。
 *
 * <p>存在的理由是「让远程实现免费拿到遍历能力」：远程目录没有 {@code File} 快路径，
 * 但遍历逻辑（不跟进链接、深度上限、剪枝、取消）与本地完全一样，没有理由再写一遍。
 * {@link LocalFs} 不继承这条路 —— 它按 {@code File} 直接走，省掉每层 {@code list()}
 * 里的排序与相对路径拼接。</p>
 *
 * <p>用什么栈：显式栈而非递归。目录深度上限虽有 64，但「上限被写错/被改成很大」
 * 之后递归会直接 StackOverflow 把进程带走，显式栈最坏只是慢。</p>
 */
final class FsWalk {

    private FsWalk() {}

    static boolean run(Fs fs, String root, int maxDepth, Fs.Cancel cancel, Fs.Visitor v)
            throws IOException {
        if (fs == null) throw FsError.of(FsError.Code.NULL_PATH);
        if (v == null) throw new IllegalArgumentException("visitor == null");
        String base = PathCore.normalize(root);
        // 不在这里判空：远程实现的根就是 ""（与 NetFs 的历史约定一致），
        // 「空串是否合法」交给具体实现的 stat/list 决定

        Fs.Entry rootEntry = fs.stat(base);
        if (rootEntry == null) throw FsError.of(FsError.Code.SRC_MISSING, base);
        if (!rootEntry.dir) throw FsError.of(FsError.Code.NOT_DIR, base);

        int limit = maxDepth < 1 ? 1 : maxDepth;
        Deque<Frame> stack = new ArrayDeque<Frame>();
        stack.push(new Frame(rootEntry, 0));

        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (cancel != null && cancel.isCancelled()) return false;

            List<Fs.Entry> kids;
            try {
                kids = fs.list(f.entry.path);
            } catch (IOException e) {
                // 根是调用方明确要扫的目录：读不出来必须报，不能当空目录
                if (f.depth == 0) throw e;
                v.onError(f.entry, e);
                continue;
            }

            for (Fs.Entry e : kids) {
                if (cancel != null && cancel.isCancelled()) return false;
                if (e.dir && !e.link) {
                    int d = f.depth + 1;
                    // 先问是否剪枝，再决定要不要放栈里 —— 剪掉的子树不进栈，也不会回调
                    if (v.onDir(e, d) && d < limit) stack.push(new Frame(e, d));
                } else {
                    // 文件与链接同一条路：链接不展开，link=true 让调用方自己决定要不要
                    v.onFile(e, f.depth + 1);
                }
            }
        }
        return true;
    }

    /** 待处理的目录 + 它的深度（根为 0，直接子项为 1） */
    private static final class Frame {
        final Fs.Entry entry;
        final int depth;

        Frame(Fs.Entry entry, int depth) {
            this.entry = entry;
            this.depth = depth;
        }
    }
}
