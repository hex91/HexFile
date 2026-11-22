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

    /** 释放实现持有的资源（本地实现无状态，空实现）。可重复调用。 */
    default void close() {}

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
