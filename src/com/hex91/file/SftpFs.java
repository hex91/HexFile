package com.hex91.file;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v1.60.0 SFTP 网络存储实现：基于 JSch 0.1.55（SSH2/SFTP，密码认证）。
 *
 * <p>主机密钥校验设为 no（局域网场景首次连接免交互；公网敏感环境请自行评估）。
 * 远程路径约定与 {@link NetFs} 一致：'/' 分隔、空串表示登录用户的 home 目录。
 * 连接超时 10s、读超时由 JSch 内部 socket 管理（ServerAliveInterval 心跳保活）。</p>
 *
 * <p>支持：列目录（排除 . ..）、get/put 流式收发（put 自动补齐父目录）、
 * mkdir/rm/rmdir、目录递归删除、rename（目标已存在时先删目标，SFTP v3 无原子覆盖）。</p>
 */
public final class SftpFs implements NetFs {

    private Session session;
    private ChannelSftp ch;

    private SftpFs() {}

    /** 连接并打开 SFTP 通道。user 为空时按 root 处理（SFTP 无匿名概念）。 */
    public static SftpFs connect(String host, int port, String user, String pass) throws IOException {
        SftpFs fs = new SftpFs();
        try {
            JSch jsch = new JSch();
            String u = (user == null || user.isEmpty()) ? "root" : user;
            fs.session = jsch.getSession(u, host, port);
            fs.session.setPassword(pass == null ? "" : pass);
            fs.session.setConfig("StrictHostKeyChecking", "no");
            fs.session.setConfig("PreferredAuthentications",
                    "publickey,password,keyboard-interactive");
            fs.session.setTimeout(30000);
            fs.session.connect(10000);
            fs.ch = (ChannelSftp) fs.session.openChannel("sftp");
            fs.ch.connect(10000);
            return fs;
        } catch (Exception e) {
            fs.close();
            throw new IOException("SFTP 连接失败: " + e.getMessage()
                    + "（检查地址/端口 22、用户名密码，以及服务器 SSH 服务是否开启）", e);
        }
    }

    /** SftpException → IOException（附常用错误提示）。 */
    private static IOException err(String op, SftpException e) {
        String hint;
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) hint = "（路径不存在）";
        else if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) hint = "（无权限）";
        else if (e.id == 11) hint = "（连接已断开）";
        else hint = "";
        return new IOException("SFTP " + op + " 失败: " + e.getMessage() + hint, e);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<NetEntry> list(String path) throws IOException {
        String target = path == null || path.isEmpty() ? "." : path;
        List<NetEntry> dirs = new ArrayList<>(), files = new ArrayList<>();
        try {
            for (ChannelSftp.LsEntry e : (java.util.Vector<ChannelSftp.LsEntry>) ch.ls(target)) {
                String name = e.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                SftpATTRS a = e.getAttrs();
                (a.isDir() ? dirs : files).add(new NetEntry(name, a.isDir(), a.getSize(), a.getMTime() * 1000L));
            }
        } catch (SftpException se) {
            throw err("列目录 " + target, se);
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
            public int compare(NetEntry a, NetEntry b) { return a.name.compareToIgnoreCase(b.name); }
        });
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        try {
            return ch.get(path);
        } catch (SftpException e) {
            throw err("读取 " + path, e);
        }
    }

    @Override
    public OutputStream openWrite(String path) throws IOException {
        try {
            ensureParents(path);
            return ch.put(path, ChannelSftp.OVERWRITE); // close 时由 JSch 完成传输确认
        } catch (SftpException e) {
            throw err("写入 " + path, e);
        }
    }

    /** 逐级补齐父目录（与 WebDavFs.ensureParents 语义一致）。 */
    private void ensureParents(String path) throws IOException {
        String parent = NetFs.parentOf(path);
        if (parent.isEmpty()) return;
        String[] segs = parent.split("/");
        String cur = "";
        for (String s : segs) {
            if (s.isEmpty()) continue;
            cur = cur.isEmpty() ? s : cur + "/" + s;
            try {
                ch.stat(cur);
            } catch (SftpException e) {
                try { ch.mkdir(cur); } catch (SftpException me) {
                    if (me.id != ChannelSftp.SSH_FX_FAILURE) throw err("创建目录 " + cur, me);
                }
            }
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        try {
            ch.mkdir(path);
        } catch (SftpException e) {
            // 已存在则容忍（与 WebDavFs 405 语义一致）
            if (isExistsErr(e)) return;
            throw err("创建目录 " + path, e);
        }
    }

    private static boolean isExistsErr(SftpException e) {
        // 11 = SSH_FX_FILE_ALREADY_EXISTS（JSch 0.1.55 未定义该常量，用字面值）
        return e.id == 11
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("exist"));
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        try {
            if (dir) {
                for (NetEntry c : list(path)) delete(NetFs.join(path, c.name), c.dir);
                ch.rmdir(path);
            } else {
                ch.rm(path);
            }
        } catch (SftpException e) {
            throw err("删除 " + path, e);
        }
    }

    @Override
    public void rename(String path, String newName) throws IOException {
        String target = NetFs.join(NetFs.parentOf(path), newName);
        try {
            try { ch.rename(path, target); }
            catch (SftpException first) {
                if (!isExistsErr(first)) throw first;
                ch.rm(target);          // SFTP v3 无原子覆盖 → 先删目标再重试
                ch.rename(path, target);
            }
        } catch (SftpException e) {
            throw err("重命名 " + path, e);
        }
    }

    @Override
    public void close() {
        if (ch != null && ch.isConnected()) ch.disconnect();
        if (session != null && session.isConnected()) session.disconnect();
        ch = null;
        session = null;
    }
}
