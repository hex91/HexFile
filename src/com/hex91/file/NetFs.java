package com.hex91.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * v1.56.0 网络存储抽象接口：SMB（SmbFs/smbj）与 WebDAV（WebDavFs）的统一文件操作面。
 *
 * <p>路径约定：以 '/' 分隔、相对各协议根的路径；空串 "" 表示根目录
 * （SMB 为已连接共享的根，WebDAV 为服务器 base URL）。</p>
 */
public interface NetFs {

    /** 列目录（不含 "." ".."；目录排前、按名称排序）。path 为空串表示根目录。 */
    List<NetEntry> list(String path) throws IOException;

    /** 打开远程文件的读流（调用方负责 close，close 即释放远端句柄）。 */
    InputStream openRead(String path) throws IOException;

    /** 打开远程文件的写流（不存在则创建，存在则覆盖；自动补齐父目录）。 */
    OutputStream openWrite(String path) throws IOException;

    /** 创建目录（仅一级，父目录须已存在）。 */
    void mkdir(String path) throws IOException;

    /** 删除文件或目录（目录递归删除）。 */
    void delete(String path, boolean dir) throws IOException;

    /** 同目录重命名（newName 为新名称，不含路径）。 */
    void rename(String path, String newName) throws IOException;

    /** 断开连接并释放资源（可安全重复调用）。 */
    void close();

    /** 目录项元数据。 */
    final class NetEntry {
        public String name;
        public boolean dir;
        public long size;
        public long mtime;   // epoch millis；未知为 0

        public NetEntry(String name, boolean dir, long size, long mtime) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
        }
    }

    // ----------------------------------------------------------------
    // 静态工具：路径拼接 / 拆分
    // ----------------------------------------------------------------

    /** 拼接相对路径："a" + "b.txt" → "a/b.txt"；parent 为空返回 name 本身。 */
    static String join(String parent, String name) {
        if (parent == null || parent.isEmpty()) return name;
        return parent + "/" + name;
    }

    /** 取路径最后一段（文件名）；根目录返回空串。 */
    static String baseName(String path) {
        if (path == null || path.isEmpty()) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** 取父路径；无父（一级路径）返回空串。 */
    static String parentOf(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
