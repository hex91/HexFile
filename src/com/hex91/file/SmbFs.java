package com.hex91.file;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileRenameInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * v1.56.0 SMB 网络存储实现：基于 smbj 0.14.0（Apache-2.0，libs/smbj-0.14.0.jar）。
 *
 * <p>支持 SMB2 / SMB2.1 / SMB3（含 3.1.1 协商）；不支持 SMB1（如需访问极老设备请
 * 在路由器/NAS 侧开启 SMB2，或使用 WebDAV 通道）。</p>
 *
 * <p>会话生命周期：{@link #connect} 建链认证 → 各操作复用同一连接 → {@link #close()}
 * 断开。所有方法非线程安全（调用方在单一后台线程串行使用）。</p>
 */
public final class SmbFs implements NetFs {

    private SMBClient client;
    private Connection conn;
    private DiskShare share;

    private SmbFs() {}

    /**
     * 连接 SMB 服务器并挂载共享。
     *
     * @param host      主机名或 IP
     * @param port      端口（一般 445）
     * @param shareName 共享名（如 public，不含路径）
     * @param domain    Windows 域/工作组（可为空）
     * @param user      用户名；为空按匿名/访客（guest）登录
     * @param pass      密码
     */
    public static SmbFs connect(String host, int port, String shareName,
                                String domain, String user, String pass) throws IOException {
        SmbConfig cfg = SmbConfig.builder()
                .withTimeout(60, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build();
        SMBClient client = new SMBClient(cfg);
        Connection conn = null;
        try {
            conn = client.connect(host, port > 0 ? port : 445);
            AuthenticationContext ac;
            if (user == null || user.trim().isEmpty()) {
                ac = AuthenticationContext.guest(); // 访客共享（Windows 免密共享 / NAS guest）
            } else {
                String d = (domain == null || domain.trim().isEmpty()) ? null : domain.trim();
                ac = new AuthenticationContext(user.trim(), pass == null ? new char[0] : pass.toCharArray(), d);
            }
            Session sess = conn.authenticate(ac);
            DiskShare share = (DiskShare) sess.connectShare(shareName);
            SmbFs fs = new SmbFs();
            fs.client = client;
            fs.conn = conn;
            fs.share = share;
            return fs;
        } catch (IOException e) {
            if (conn != null) try { conn.close(); } catch (IOException ignore) {}
            try { client.close(); } catch (Throwable ignore) {}
            throw e;
        }
    }

    @Override
    public List<NetEntry> list(String path) throws IOException {
        List<FileIdBothDirectoryInformation> raw = share.list(path == null ? "" : path);
        List<NetEntry> dirs = new ArrayList<>(), files = new ArrayList<>();
        for (FileIdBothDirectoryInformation i : raw) {
            String name = i.getFileName();
            if (name == null || name.equals(".") || name.equals("..")) continue;
            boolean isDir = (i.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
            long mtime = i.getLastWriteTime() == null ? 0 : i.getLastWriteTime().toEpochMillis();
            (isDir ? dirs : files).add(new NetEntry(name, isDir, i.getEndOfFile(), mtime));
        }
        sortByName(dirs);
        sortByName(files);
        List<NetEntry> out = new ArrayList<>(dirs.size() + files.size());
        out.addAll(dirs);
        out.addAll(files);
        return out;
    }

    private static void sortByName(List<NetEntry> l) {
        Collections.sort(l, new java.util.Comparator<NetEntry>() {
            public int compare(NetEntry a, NetEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        return new SmbRangeIn(openFile(path, AccessMask.GENERIC_READ), 0);
    }

    /** v1.88.0 续传：SMB2 READ 带偏移，直接在远端定位，不重读已下载字节。 */
    @Override
    public InputStream openReadFrom(String path, long offset) throws IOException {
        return new SmbRangeIn(openFile(path, AccessMask.GENERIC_READ), offset <= 0 ? 0 : offset);
    }

    @Override
    public boolean supportsResume() {
        return true;
    }

    @Override
    public boolean supportsAppend() {
        return true;
    }

    private com.hierynomus.smbj.share.File openFile(String path, AccessMask mask) throws IOException {
        return share.openFile(path, EnumSet.of(mask), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
    }

    /**
     * v1.88.0：按偏移读取的 InputStream。
     *
     * <p>smbj 的 File.read(buf, offset, bufOff, len) 在 EOF 返回 -1、其余返回实际字节数，
     * 正好能直接映射成 InputStream 的语义（返回 0 会造成调用方死循环，统一折成 -1）。</p>
     */
    private static final class SmbRangeIn extends InputStream {
        private final com.hierynomus.smbj.share.File f;
        private long pos;
        private boolean closed;

        SmbRangeIn(com.hierynomus.smbj.share.File f, long start) {
            this.f = f;
            this.pos = start;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("流已关闭");
            if (len == 0) return 0;
            int n = f.read(b, pos, off, len);
            if (n <= 0) return -1;
            pos += n;
            return n;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try { f.close(); } catch (Exception ignore) {} // 底层句柄由 smbj 释放
        }
    }

    @Override
    public OutputStream openWrite(String path) throws IOException {
        ensureParents(path);
        com.hierynomus.smbj.share.File f = share.openFile(path,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
        return f.getOutputStream();
    }

    /**
     * v1.88.0 上传续传：FILE_OPEN_IF（保留已有内容）+ append 流。
     *
     * <p>用 FILE_OPEN_IF 而不是 FILE_OVERWRITE_IF——后者会先把文件截断成 0，
     * 追加就成了「从 0 开始再写一遍」。</p>
     */
    @Override
    public OutputStream openWriteFrom(String path, long offset) throws IOException {
        ensureParents(path);
        com.hierynomus.smbj.share.File f = share.openFile(path,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN_IF, null);
        return f.getOutputStream(true); // append=true
    }

    /** 逐级补齐父目录（SMB openFile 不会自动创建父目录）。 */
    private void ensureParents(String path) throws IOException {
        String parent = NetFs.parentOf(path);
        if (parent.isEmpty()) return;
        String[] segs = parent.split("/");
        String cur = "";
        for (String s : segs) {
            if (s.isEmpty()) continue;
            cur = cur.isEmpty() ? s : cur + "/" + s;
            try {
                if (!share.folderExists(cur)) share.mkdir(cur);
            } catch (com.hierynomus.mssmb2.SMBApiException e) {
                throw new IOException("创建目录失败: " + cur + " (" + e.getStatusCode() + ")", e);
            }
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        share.mkdir(path);
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        if (dir) share.rmdir(path, true); // recursive=true：递归删子树
        else share.rm(path);
    }

    @Override
    public void rename(String path, String newName) throws IOException {
        // SMB2 FILE_RENAME_INFORMATION 的 FileName 为共享内完整路径
        String parent = NetFs.parentOf(path);
        String target = NetFs.join(parent, newName);
        share.setFileInformation(path, new FileRenameInformation(false, 0L, target));
    }

    @Override
    public void close() {
        if (share != null) { try { share.close(); } catch (IOException ignore) {} share = null; }
        if (conn != null) { try { conn.close(); } catch (IOException ignore) {} conn = null; }
        if (client != null) { try { client.close(); } catch (Throwable ignore) {} client = null; }
    }
}
