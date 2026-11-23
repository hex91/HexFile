package com.hex91.file.fs;

import com.hex91.file.core.PathCore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * v2.114.0（O-27）：文件系统最小操作面 —— 本地（{@link LocalFs}）与远程
 * （{@code net.NetFsAdapter}）共用同一套签名。
 *
 * <p>为什么现在才抽：双栏浏览器、搜索、压缩包解压、定时任务里的读写全都直接
 * 对 {@code java.io.File} 编程，想接一个「远程目录也能像本地一样浏览」的视图，
 * 就得把每个调用点都改一遍。先定一个 7 个方法的最小面，把 {@code File} 快路径
 * 留在 {@link LocalFs} 内部（不做层层转发），后续按调用点逐个切换，
 * 比一次性大改可控得多。</p>
 *
 * <h3>路径约定</h3>
 * <p>统一用 {@code '/'} 分隔，字符串由实现解释：</p>
 * <ul>
 *   <li>{@link LocalFs}：设备上的绝对路径（{@code /sdcard/a.txt}）；
 *       Windows 桌面单测可用 {@code C:/tmp/a.txt}。</li>
 *   <li>{@code NetFsAdapter}：相对各协议根的路径，{@code ""} 表示根目录
 *       （与 {@code NetFs} 的历史约定一致）。</li>
 * </ul>
 * <p>拼接 / 取父 / 折叠 {@code ..} 一律走 {@link PathCore}，两边规则不会走偏。</p>
 *
 * <h3>返回约定</h3>
 * <ul>
 *   <li>{@link #stat} 用 {@code null} 表示「确定不存在」；查不了（权限 / 网络）
 *       必须抛 {@link FsError} —— 这两种情况调用方的处理完全不同，不能混。</li>
 *   <li>{@link #list} 不存在或不可读时抛 {@link FsError}，不返回空列表：
 *       空目录与「读失败」在界面上是两种状态。</li>
 *   <li>{@link #openWrite} 自动补齐父目录（与 {@code NetFs.openWrite} 一致），
 *       但只补一层以外的部分不做事务保证。</li>
 *   <li>{@link #delete} 的 {@code recursive} 为 false 时，非空目录必须失败而不是静默半删。</li>
 *   <li>{@link #rename} 的 {@code to} 是完整目标路径；目标已存在时抛
 *       {@link FsError.Code#TARGET_EXISTS}，不覆盖 —— 覆盖语义交给调用方显式删除，
 *       避免「改名顺手覆盖掉同名文件」。</li>
 * </ul>
 */
public interface Fs {

    /** 列目录（不含 "." ".."；目录排前、名称忽略大小写升序）。 */
    List<Entry> list(String path) throws IOException;

    /** 取元数据；确定不存在返回 {@code null}。 */
    Entry stat(String path) throws IOException;

    /** 打开读流，调用方负责 close。 */
    InputStream openRead(String path) throws IOException;

    /** 打开写流；append 为 true 时追加到末尾，否则截断重写。 */
    OutputStream openWrite(String path, boolean append) throws IOException;

    /** 创建一级目录；已存在且是目录视为成功（幂等）。 */
    void mkdir(String path) throws IOException;

    /** 删除；目录需 recursive 才递归，否则非空目录失败。 */
    void delete(String path, boolean recursive) throws IOException;

    /** 改名 / 移动；to 为完整目标路径，目标存在则失败。 */
    void rename(String from, String to) throws IOException;

    /**
     * v2.117.0（O-27 续）：复制；{@code to} 是完整目标路径。
     *
     * <p>语义与 {@code FileOps.copyOverwrite} 一致：文件按覆盖写、目录递归合并
     * （同名文件覆盖、目标里多出来的文件保留），链接按原样复刻而不跟进。</p>
     *
     * <p>v2.119.0 起「覆盖写」是<b>原子替换</b>而不是原地截断：新内容先写到
     * {@code 名字.part}，完整写完才换上去。所以覆盖一个已存在的大文件时，
     * 新旧内容会短暂共存（空间需求翻倍）—— 这是刻意的，赌「写到一半不会失败」
     * 的代价是用户的原文件跟着消失。</p>
     *
     * <p><b>覆盖而非失败</b>：文件管理器里的「复制到」在目标已存在时是要覆盖的，
     * 这与 {@link #rename} 的「目标存在就失败」相反 —— 改名覆盖是静默丢文件，
     * 复制覆盖是用户明确要求的结果。两个方法的差异是刻意保留的。</p>
     *
     * <p><b>不做进度与取消</b>：需要进度时用 {@link #copy(String, String, Progress)}；
     * 本方法等价于传 {@code null}。</p>
     *
     * @throws FsError {@code SRC_MISSING} 源不存在 / {@code SELF_NEST} 目标在自己的子树里 /
     *                 {@code LINK_UNSUPPORTED} 目标文件系统不支持链接 /
     *                 {@code RENAME_FAIL} 内容写完了但换不上去（此时 {@code .part} 保留）
     */
    default CopyResult copy(String from, String to) throws IOException {
        return copy(from, to, null);
    }

    /**
     * v2.118.0（O-27 续）：带进度与取消的复制。
     *
     * <p><b>两趟走</b>：先量体积（不读内容，只 lstat），再复制。为什么要多走一趟：
     * 没有分母的进度条只能显示「已复制 3.2 GB」，用户无法判断还要多久；而 50 万文件
     * 量一遍也就是几十秒，远小于复制本身。量体积这一趟同样会检查取消，否则
     * 用户在「扫描中」按取消会没反应。</p>
     *
     * <p><b>取消留下什么</b>：已经复制完的文件保留，正在写的那个<b>不会留下半截</b> ——
     * 它在写完之前叫 {@code 名字.part}，取消时被删掉，目标位置的旧文件一个字节都没动。
     * 只有替换那一步失败时 {@code .part} 才会留下，那时它已经是完整的新内容，
     * 「设置 → 遗留文件」会把它列出来。</p>
     *
     * <p>已完成的文件不回滚是刻意的 —— 回滚要删掉目标里「本次新建」的条目，
     * 但同名文件可能在复制前就存在（copy 是覆盖语义），删掉等于把用户原有数据一起清了。
     * 调用方拿到 {@link CopyResult#cancelled} 后应当提示「已复制 N 个文件，剩余未完成」，
     * 而不是当作什么都没发生。</p>
     *
     * <p><b>进度按文件粒度回调</b>，不按字节回调：一个 4 GB 视频如果按块回调，
     * 每秒上千次跨线程更新会把主线程压死，而界面上的百分比该跳就跳。
     * 取消检查在块边界也做（每 64 KB），所以按取消能立刻响应。</p>
     *
     * @param p 进度回调；{@code null} 表示不需要进度，此时与 {@link #copy(String, String)} 等价
     */
    CopyResult copy(String from, String to, Progress p) throws IOException;

    /**
     * v2.117.0（O-27 续）：移动；{@code to} 是完整目标路径，目标存在则失败。
     *
     * <p>先尝试原子改名；跨卷时退化为「复制 + 删除源」。跨卷且源删不掉时抛
     * {@code SRC_LEFT} —— 此时目标已有完整数据，调用方应当告诉用户「已移动，但源还在」，
     * 不要报成「移动失败」让用户再试一次（那会变成源/目标双份）。</p>
     *
     * <p>命名保持 {@link #rename} 的「不覆盖」语义：目标存在直接失败。</p>
     */
    void move(String from, String to) throws IOException;

    /** 释放实现持有的资源（本地实现无状态，空实现）。可重复调用。 */
    default void close() {}

    // ---------------------------------------------------------------- 递归遍历

    /**
     * v2.116.0（O-27 续）：递归遍历 —— 把「怎么走」从每个调用点收回到一处。
     *
     * <p>为什么值得收：磁盘分析、查重、遗留文件扫描、全盘搜索原先各自写了一遍递归，
     * 于是「不跟进软链接」「深度上限」「被取消时怎么停」这三条规则要重复实现四遍，
     * 每漏一处就是重复计数或爆栈。这个接口只负责走，不负责统计 —— 累计口径留在调用方。</p>
     *
     * <p><b>顺序</b>：不保证。同一目录内也不保证按名称排序，需要顺序的调用方自己排。
     * 遍历实现自己决定用栈还是队列，这既是性能留白，也免得调用方依赖某一种顺序。</p>
     *
     * <p><b>链接</b>：子项里绝不进入链接目标。指向目录的链接按 {@code onFile} 回调并置
     * {@link Entry#link} 为 true —— 它「看起来是目录但不能走」，用 {@code onDir}
     * 回调会让调用方以为返回 true 就能进得去。根路径按调用方原样解释
     * （{@code /sdcard} 在部分 ROM 上本身就是链接，拒绝扫描等于让分析入口失效）。</p>
     *
     * <p><b>失败</b>：某个子目录读不出来不会中断整趟，走 {@link Visitor#onError}；
     * 根路径不存在或不是目录则直接抛 {@link FsError} —— 这是调用方写错了路径，
     * 不该伪装成「空目录」。</p>
     *
     * @param maxDepth 最大深度，根的直接子项为 1；小于 1 按 1 处理
     * @param cancel   每个条目前检查一次；{@code null} 表示不取消
     * @return true 走完；false 被取消（已回调的条目有效，未访问的不保证）
     */
    default boolean walk(String root, int maxDepth, Cancel cancel, Visitor v) throws IOException {
        return FsWalk.run(this, root, maxDepth, cancel, v);
    }

    /** {@link #walk(String, int, Cancel, Visitor)} 的默认深度版本。 */
    default boolean walk(String root, Visitor v) throws IOException {
        return walk(root, TreeOps.MAX_DEPTH, null, v);
    }

    /** 取消信号：遍历每处理一个条目查一次，实现方可以放心做长任务。 */
    interface Cancel {
        boolean isCancelled();
    }

    // ---------------------------------------------------------------- 复制进度

    /**
     * v2.118.0：复制进度回调。三个方法都可能被后台线程调用，实现方自己切主线程。
     *
     * <p>刻意不给「百分比」而是给原始数字：总量未知时（远程、权限受限目录）
     * {@link #onPlan} 的 {@code totalBytes} 会是 {@code -1}，此时界面该显示「已复制 X」
     * 而不是「50%」—— 算百分比的规则留给调用方，接口不预设。</p>
     */
    interface Progress {
        /**
         * 量完体积后回调一次，之后才开始真正复制。
         *
         * @param totalBytes 预计要写的字节数；{@code -1} 表示无法预知
         * @param totalFiles 预计要复制的文件数；{@code -1} 表示无法预知
         */
        void onPlan(long totalBytes, long totalFiles);

        /**
         * 每写完一个文件回调一次。
         *
         * <p>此时该文件已经完整落盘，{@code doneBytes} / {@code doneFiles} 可以安全地
         * 用来显示进度。正在写的文件不会回调 —— 否则进度条会显示一个永远不会到达的
         * 「99%」，用户以为卡住了。</p>
         *
         * @return {@code false} 表示取消；已完成的文件保留（见 {@link #copy(String, String, Progress)}）
         */
        boolean onFile(String path, long size, long doneBytes, long doneFiles);

        /**
         * 取消轮询：量体积阶段与复制阶段都会调，实现方可以做长任务。
         *
         * <p>与 {@link #onFile} 返回 false 的区别只是时机：这里是块边界（每 64 KB），
         * 用来让「复制一个 4 GB 文件」也能立刻停下。</p>
         */
        default boolean isCancelled() {
            return false;
        }
    }

    /** 复制结果。取消时 {@code cancelled} 为 true，其余字段是已完成的部分。 */
    final class CopyResult {
        public final boolean cancelled;
        /** 已写入的字节数（含被中断那个文件写入的部分） */
        public final long bytes;
        /** 已完整写完的文件数 */
        public final long files;
        /** 因目标文件系统不支持链接而跳过的条目数 */
        public final int skipped;

        public CopyResult(boolean cancelled, long bytes, long files, int skipped) {
            this.cancelled = cancelled;
            this.bytes = bytes;
            this.files = files;
            this.skipped = skipped;
        }

        @Override
        public String toString() {
            return (cancelled ? "copy(cancelled)" : "copy(done)")
                    + " files=" + files + " bytes=" + bytes + " skipped=" + skipped;
        }
    }

    /** 遍历回调。 */
    interface Visitor {
        /**
         * 遇到真实目录（不是链接）。
         *
         * @return false 表示不进入这个目录 —— 剪枝。已剪掉的子树不会再有任何回调。
         */
        boolean onDir(Entry dir, int depth);

        /** 遇到文件；{@link Entry#link} 为 true 表示这是链接（不会进入，也不会再展开）。 */
        void onFile(Entry file, int depth);

        /** 某个目录读不出来（权限 / 已被删除 / 网络失败）；默认忽略，继续走其余的。 */
        default void onError(Entry dir, IOException e) {}
    }

    /** 目录项元数据；{@code mtime} 为 epoch 毫秒，未知为 0。 */
    final class Entry {
        public final String name;
        /** 实现侧规范路径（本地为 {@code /a/b}，远程为协议相对路径） */
        public final String path;
        public final boolean dir;
        public final long size;
        public final long mtime;
        /** 是否为符号链接（远程协议一般恒 false） */
        public final boolean link;

        public Entry(String name, String path, boolean dir, long size, long mtime, boolean link) {
            this.name = name;
            this.path = path;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
            this.link = link;
        }

        @Override
        public String toString() {
            return (dir ? "d " : "- ") + path + (dir ? "/" : " (" + size + ")");
        }
    }
}
