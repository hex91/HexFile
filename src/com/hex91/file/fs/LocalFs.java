package com.hex91.file.fs;

import com.hex91.file.core.PathCore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * v2.114.0（O-27）：{@link Fs} 的本地实现 —— 直接落在 {@code java.io.File} 上。
 *
 * <p>刻意不做「每个方法都再过一层」的包装：本地路径是主路径，性能与行为必须和
 * 现有 {@code FileOps} / {@code DirReader} 一致，多一层抽象只会让「为什么这里慢」
 * 更难查。{@link Fs} 只负责统一签名与错误码。</p>
 *
 * <p>无状态：{@link #close()} 是空实现，关闭后仍可继续调用。桌面单测与真机跑的是
 * 同一份代码（不含 {@code android.*}）。</p>
 */
public final class LocalFs implements Fs {

    @Override
    public List<Entry> list(String path) throws IOException {
        String p = require(path);
        File dir = new File(p);
        File[] arr = dir.listFiles();
        if (arr == null) {
            // 不存在与无权限在 File 上都是 null：对调用方是同一件事「这个目录列不出来」
            throw FsError.of(FsError.Code.LIST_FAIL, p);
        }

        // 排序键先取一遍：comparator 里直接 isDirectory()/length() 会对每对元素各 stat 一次
        Item[] items = new Item[arr.length];
        for (int i = 0; i < arr.length; i++) {
            File f = arr[i];
            int k = LinkUtil.kind(f);
            // 指向目录的链接按目录排（与 DirReader.fillLink 的 forceDir 一致）
            boolean isDir = f.isDirectory() || k == LinkUtil.DIR;
            items[i] = new Item(f, isDir, isDir ? 0L : f.length(), f.lastModified(),
                    k != LinkUtil.NONE);
        }
        Arrays.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                int r = a.file.getName().compareToIgnoreCase(b.file.getName());
                if (r != 0) return r;
                // 忽略大小写相等的两个名字（README / readme）靠区分大小写定序，避免顺序不稳
                return a.file.getName().compareTo(b.file.getName());
            }
        });

        List<Entry> out = new ArrayList<Entry>(items.length);
        for (Item it : items) {
            out.add(new Entry(it.file.getName(), PathCore.join(p, it.file.getName()),
                    it.dir, it.size, it.mtime, it.link));
        }
        return out;
    }

    /** 排序用的临时载体：避免 Integer 装箱与 comparator 里的重复 stat */
    private static final class Item {
        final File file;
        final boolean dir;
        final long size;
        final long mtime;
        final boolean link;

        Item(File file, boolean dir, long size, long mtime, boolean link) {
            this.file = file;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
            this.link = link;
        }
    }

    @Override
    public Entry stat(String path) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (!f.exists()) return null;
        int k = LinkUtil.kind(f);
        boolean dir = f.isDirectory() || k == LinkUtil.DIR;
        return new Entry(f.getName(), p, dir, dir ? 0L : f.length(), f.lastModified(),
                k != LinkUtil.NONE);
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (!f.exists()) throw FsError.of(FsError.Code.SRC_MISSING, p);
        if (f.isDirectory()) throw FsError.of(FsError.Code.READ_FAIL, p);
        try {
            return new FileInputStream(f);
        } catch (IOException e) {
            throw FsError.of(FsError.Code.READ_FAIL, p);
        }
    }

    @Override
    public OutputStream openWrite(String path, boolean append) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (f.isDirectory()) throw FsError.of(FsError.Code.WRITE_FAIL, p);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw FsError.of(FsError.Code.MKDIR_FAIL, parent.getPath());
        }
        try {
            return new FileOutputStream(f, append);
        } catch (IOException e) {
            throw FsError.of(FsError.Code.CREATE_FAIL, p);
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (f.isDirectory()) return;
        if (f.exists()) throw FsError.of(FsError.Code.MKDIR_FAIL, p);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            // 契约是「仅一级」：父不存在时明确失败，不偷偷 mkdirs
            throw FsError.of(FsError.Code.MKDIR_FAIL, p);
        }
        if (!f.mkdir()) throw FsError.of(FsError.Code.MKDIR_FAIL, p);
    }

    @Override
    public void delete(String path, boolean recursive) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (!f.exists() && LinkUtil.kind(f) == LinkUtil.NONE) {
            throw FsError.of(FsError.Code.SRC_MISSING, p);
        }
        // 链接只删链接本身：跟随进去会把目标内容一起删掉
        if (!f.isDirectory() || LinkUtil.kind(f) != LinkUtil.NONE) {
            if (!f.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, p);
            return;
        }
        if (!recursive) {
            if (!f.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, p);
            return;
        }
        deleteTree(p);
    }

    /** 显式栈递归：几万层深目录也不会把调用栈打爆 */
    private void deleteTree(String root) throws IOException {
        Deque<File> stack = new ArrayDeque<File>();
        List<File> dirs = new ArrayList<File>();
        stack.push(new File(root));
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] arr = dir.listFiles();
            if (arr == null) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, dir.getPath());
            for (File f : arr) {
                boolean isLink = LinkUtil.kind(f) != LinkUtil.NONE;
                if (f.isDirectory() && !isLink) {
                    stack.push(f);
                    dirs.add(f);
                } else if (!f.delete()) {
                    throw FsError.of(FsError.Code.DELETE_SRC_FAIL, f.getPath());
                }
            }
        }
        // 后序删除目录本身
        for (int i = dirs.size() - 1; i >= 0; i--) {
            File d = dirs.get(i);
            if (!d.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, d.getPath());
        }
        File r = new File(root);
        if (!r.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, r.getPath());
    }

    @Override
    public void rename(String from, String to) throws IOException {
        String a = require(from);
        String b = require(to);
        if (a.equals(b)) return;
        File src = new File(a);
        if (!src.exists() && LinkUtil.kind(src) == LinkUtil.NONE) {
            throw FsError.of(FsError.Code.SRC_MISSING, a);
        }
        File dst = new File(b);
        if (dst.exists()) {
            throw FsError.of(FsError.Code.TARGET_EXISTS, b);
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            throw FsError.of(FsError.Code.RENAME_FAIL, b);
        }
        if (!src.renameTo(dst)) throw FsError.of(FsError.Code.RENAME_FAIL, b);
    }

    /** 统一入口检查：null / 空串是调用方的编程错误，这里给稳定错误码 */
    private static String require(String path) throws FsError {
        if (path == null || path.trim().isEmpty()) {
            throw FsError.of(FsError.Code.BAD_PATH);
        }
        return PathCore.normalize(path);
    }
}
