package com.hex91.file.fs;

import com.hex91.file.core.AtomicReplace;
import com.hex91.file.core.VaultCrypto;
import com.hex91.file.core.VaultCrypto.Code;
import com.hex91.file.core.VaultCrypto.Fail;
import com.hex91.file.core.VaultCrypto.IoFail;
import com.hex91.file.core.VaultCrypto.StateFail;
import com.hex91.file.core.VaultIndex;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 隐私空间存储层（v2.18.0，F-22）：目录布局、索引读写、导入导出。
 *
 * <p>目录布局（默认 {@code HexFile/.vault}）：</p>
 * <pre>
 * .vault/v.hdr          157 字节定长头部（salt + 被口令包起来的主密钥）
 * .vault/idx            加密索引（文件名、大小、时间）
 * .vault/b/&lt;id&gt;.bin     文件密文，文件名只是随机 id
 * </pre>
 *
 * <p>目录名带前导点：系统相册、MTP、多数第三方文件管理器默认不列点开头目录，
 * 隐私空间里装的是什么，不该从别的 App 的文件列表里一眼看出来。</p>
 *
 * <p><b>索引为什么留 .bak</b>：索引是唯一的目录信息，写坏了等于所有文件都变成无名密文。
 * 每次保存先把旧 idx 改名成 idx.bak 再落新的；读的时候主索引打不开就回退备份，
 * 并在 {@link Table#backup} 里告诉上层「这次用的是备份，可能有新增文件没在表里」。</p>
 *
 * <p>全部方法都以 {@code File dir} 显式传入目录，不读静态配置 —— 这样整个存储层
 * 能在桌面 JVM 里用临时目录跑真文件测试，不必依赖 Android 的存储路径。</p>
 */
public final class VaultStore {

    /** 默认目录名（相对工作目录） */
    public static final String DIR_NAME = ".vault";

    private static final String F_HDR = "v.hdr";
    private static final String F_IDX = "idx";
    private static final String F_IDX_BAK = "idx.bak";
    private static final String F_IDX_TMP = "idx.tmp";
    private static final String D_BLOB = "b";

    private VaultStore() {}

    public static File defaultDir() {
        return new File(WorkDir.base(), DIR_NAME);
    }

    public static File headerFile(File dir) {
        return new File(dir, F_HDR);
    }

    public static File blobFile(File dir, String id) {
        return new File(new File(dir, D_BLOB), id + ".bin");
    }

    /** 目录里是否已经有一个隐私空间（只看头部在不在） */
    public static boolean exists(File dir) {
        return dir != null && headerFile(dir).isFile();
    }

    // ------------------------------------------------------------------ 建立 / 解锁

    /**
     * 新建隐私空间。
     *
     * <p>先落头部再建目录：头部写成功才算「建成」，避免中途失败留下一个空目录，
     * 下次进来 {@link #exists} 为假、又不给重复创建（目录已存在）的死循环。</p>
     */
    public static VaultCrypto.Master create(File dir, String password) throws Exception {
        if (dir == null) throw new Fail(Code.DIR_NULL);
        if (password == null || password.length() < 6) {
            throw new Fail(Code.PASSWORD_SHORT);
        }
        if (exists(dir)) throw new StateFail(Code.ALREADY_EXISTS);
        File b = new File(dir, D_BLOB);
        if (!b.isDirectory() && !b.mkdirs()) throw new IoFail(Code.MKDIR_FAIL, b.toString());

        VaultCrypto.Header h = VaultCrypto.newHeader(password);
        writeAtomic(new File(dir, F_HDR), h.bytes);
        save(dir, h.master, new ArrayList<VaultIndex.Entry>());
        return h.master;
    }

    /** 口令解锁。口令不对抛 {@link SecurityException}（由 UI 翻成「口令不对」） */
    public static VaultCrypto.Master unlock(File dir, String password) throws Exception {
        byte[] h = readAll(headerFile(dir));
        return VaultCrypto.openHeader(password, h);
    }

    /** 改口令：只重写头部，文件密文一个字节都不动 */
    public static void changePassword(File dir, VaultCrypto.Master m, String newPassword)
            throws Exception {
        if (newPassword == null || newPassword.length() < 6) {
            throw new Fail(Code.PASSWORD_SHORT);
        }
        writeAtomic(headerFile(dir), VaultCrypto.rewrap(m, newPassword));
    }

    /**
     * 原子写一个小文件（头部 / 索引）。
     *
     * <p>直接 {@code tmp.renameTo(target)} 在 Android（Linux）能覆盖同名文件，在 Windows 上不行——
     * 桌面单测里改口令就会报「无法写入头部」，而真机上永远碰不到。这类「只在一个平台坏」的写法
     * 必须收敛到 {@link AtomicReplace}：它先试 rename，失败再让位，还原路径也覆盖了。</p>
     */
    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(data);
            fo.flush();
            fo.getFD().sync();
        } catch (IOException e) {
            closeQuiet(fo);
            tmp.delete();
            throw e;
        } finally {
            closeQuiet(fo);
        }
        AtomicReplace.Result r = AtomicReplace.replaceWith(tmp, target);
        if (!r.ok) throw new IoFail(Code.WRITE_FAIL, AtomicReplace.failMessage(target.getName()));
    }

    // ------------------------------------------------------------------ 索引

    /** 读出来的索引表 */
    public static final class Table {
        public final List<VaultIndex.Entry> entries;
        /** 坏行数（跳过的记录） */
        public final int bad;
        /** true = 主索引打不开，这份来自 idx.bak */
        public final boolean backup;

        Table(List<VaultIndex.Entry> entries, int bad, boolean backup) {
            this.entries = entries;
            this.bad = bad;
            this.backup = backup;
        }
    }

    public static Table load(File dir, VaultCrypto.Master m) throws Exception {
        File idx = new File(dir, F_IDX);
        if (idx.isFile()) {
            try {
                VaultIndex.Read r = VaultIndex.decode(VaultCrypto.open(readAll(idx), m));
                return new Table(r.entries, r.bad, false);
            } catch (SecurityException e) {
                throw e;   // 密钥不对是真错误，不能悄悄换备份掩盖
            } catch (Exception first) {
                File bak = new File(dir, F_IDX_BAK);
                if (!bak.isFile()) throw first;
                VaultIndex.Read r = VaultIndex.decode(VaultCrypto.open(readAll(bak), m));
                return new Table(r.entries, r.bad, true);
            }
        }
        File bak = new File(dir, F_IDX_BAK);
        if (bak.isFile()) {
            VaultIndex.Read r = VaultIndex.decode(VaultCrypto.open(readAll(bak), m));
            return new Table(r.entries, r.bad, true);
        }
        return new Table(new ArrayList<VaultIndex.Entry>(), 0, false);
    }

    public static void save(File dir, VaultCrypto.Master m, List<VaultIndex.Entry> list)
            throws Exception {
        byte[] box = VaultCrypto.seal(VaultIndex.encode(list), m);
        File tmp = new File(dir, F_IDX_TMP);
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(box);
            fo.flush();
        } finally {
            closeQuiet(fo);
        }
        File idx = new File(dir, F_IDX);
        if (idx.isFile()) {
            File bak = new File(dir, F_IDX_BAK);
            bak.delete();
            if (!idx.renameTo(bak)) {
                // 备份失败不致命：宁可少一层兜底，也不能因此写不进索引
                idx.delete();
            }
        }
        if (!tmp.renameTo(idx)) {
            tmp.delete();
            throw new IoFail(Code.INDEX_WRITE_FAIL);
        }
    }

    // ------------------------------------------------------------------ 导入 / 导出

    /**
     * 导入：把流加密成新条目并落索引。
     *
     * <p>顺序是「先密文、后索引」：密文写完才登记。中途取消或失败只留一个没登记的密文，
     * 由 {@link #orphans} 下次清掉；反过来先登记后写密文，一旦失败索引里就有一个点开报错的幽灵条目。</p>
     */
    public static VaultIndex.Entry add(File dir, VaultCrypto.Master m, InputStream in, long size,
            String name, long mtime, VaultCrypto.Progress p) throws Exception {
        String safe = safeName(name);
        String id = VaultIndex.newId();
        File blob = blobFile(dir, id);
        File bdir = new File(dir, D_BLOB);
        if (!bdir.isDirectory() && !bdir.mkdirs()) throw new IoFail(Code.MKDIR_FAIL, bdir.toString());

        boolean ok = false;
        try {
            long plain;
            FileOutputStream out = new FileOutputStream(blob);
            try {
                plain = VaultCrypto.encryptStream(in, size, out, VaultIndex.unhex(id), m, p);
            } finally {
                closeQuiet(out);
            }
            Table t = load(dir, m);
            VaultIndex.Entry e = new VaultIndex.Entry(id, safe, plain, mtime,
                    System.currentTimeMillis());
            t.entries.add(e);
            save(dir, m, t.entries);
            ok = true;
            return e;
        } finally {
            if (!ok) blob.delete();
        }
    }

    /** 导出（解密）到 destDir，重名自动加 " (1)"。返回落地的文件 */
    public static File export(File dir, VaultCrypto.Master m, String id, File destDir,
            VaultCrypto.Progress p) throws Exception {
        // 重新读一遍索引：列表可能停在几分钟前，名字以索引为准
        Table t = load(dir, m);
        VaultIndex.Entry e = find(t.entries, id);
        if (e == null) throw new Fail(Code.ENTRY_MISSING);
        File blob = blobFile(dir, id);
        if (!blob.isFile()) throw new Fail(Code.BLOB_MISSING, e.name);

        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IoFail(Code.MKDIR_FAIL, destDir.toString());
        }
        File out = unique(destDir, e.name);
        boolean ok = false;
        try {
            VaultCrypto.decryptFile(blob, out, VaultIndex.unhex(id), m, p);
            out.setLastModified(e.mtime > 0 ? e.mtime : System.currentTimeMillis());
            ok = true;
            return out;
        } finally {
            if (!ok) out.delete();
        }
    }

    public static void remove(File dir, VaultCrypto.Master m, String id) throws Exception {
        Table t = load(dir, m);
        for (int i = t.entries.size() - 1; i >= 0; i--) {
            if (t.entries.get(i).id.equals(id)) t.entries.remove(i);
        }
        save(dir, m, t.entries);
        blobFile(dir, id).delete();
    }

    public static void rename(File dir, VaultCrypto.Master m, String id, String newName)
            throws Exception {
        String n = safeName(newName);
        if (n.isEmpty()) throw new Fail(Code.NAME_EMPTY);
        Table t = load(dir, m);
        VaultIndex.Entry e = find(t.entries, id);
        if (e == null) throw new Fail(Code.ENTRY_MISSING);
        e.name = n;
        save(dir, m, t.entries);
    }

    /**
     * 名字净化：导入时的名字来自别的 App（{@code OpenableColumns}），不能假设它是干净的。
     *
     * <p>去掉路径分隔符与控制字符，是因为它最终会作为导出时的文件名（{@code new File(dir, name)}）；
     * 放行 {@code ../} 就等于让一条索引记录把文件写到目录外去。</p>
     */
    public static String safeName(String name) {
        if (name == null) return "Untitled";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\') sb.append('_');
            else if (c < 0x20 || c == 0x7F) continue;
            else sb.append(c);
        }
        String s = sb.toString().trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "Untitled";
        if (s.length() > 120) {
            s = s.substring(0, 120);
            if (Character.isHighSurrogate(s.charAt(s.length() - 1))) {
                s = s.substring(0, s.length() - 1);   // 别把一个 emoji 劈成半个
            }
        }
        return s;
    }

    public static VaultIndex.Entry find(List<VaultIndex.Entry> list, String id) {
        if (list == null || id == null) return null;
        for (VaultIndex.Entry e : list) {
            if (id.equals(e.id)) return e;
        }
        return null;
    }

    /** 占用字节数（头部 + 索引 + 全部密文） */
    public static long usedBytes(File dir) {
        long n = 0;
        File h = headerFile(dir);
        if (h.isFile()) n += h.length();
        for (String f : new String[]{F_IDX, F_IDX_BAK}) {
            File x = new File(dir, f);
            if (x.isFile()) n += x.length();
        }
        File[] bs = new File(dir, D_BLOB).listFiles();
        if (bs != null) {
            for (File b : bs) {
                if (b.isFile() && !b.getName().endsWith(".tmp")) n += b.length();
            }
        }
        return n;
    }

    /**
     * 游离密文：{@code b/} 里存在、索引里没有的 id（导入中途取消、索引回退到备份都会留下）。
     * 这些文件的名字已经无从得知，只能删。
     */
    public static List<String> orphans(File dir, VaultCrypto.Master m) {
        List<String> out = new ArrayList<String>();
        File[] bs = new File(dir, D_BLOB).listFiles();
        if (bs == null) return out;
        List<VaultIndex.Entry> known;
        try {
            known = load(dir, m).entries;
        } catch (Exception e) {
            return out;   // 索引都读不出来时不要报「全都是游离」，那会诱导用户全删
        }
        for (File b : bs) {
            String n = b.getName();
            if (!n.endsWith(".bin")) continue;
            String id = n.substring(0, n.length() - 4);
            if (!VaultIndex.isId(id)) continue;
            if (find(known, id) == null) out.add(id);
        }
        return out;
    }

    public static int deleteOrphans(File dir, VaultCrypto.Master m) {
        int n = 0;
        for (String id : orphans(dir, m)) {
            if (blobFile(dir, id).delete()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 小工具

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    /** 同 {@code VaultCrypto.closeQuiet}：收尾失败不掩盖主结果，也不留空 catch */
    public static boolean closeQuiet(Closeable c) {
        if (c == null) return true;
        try {
            c.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        if (!f.isFile()) throw new IoFail(Code.FILE_MISSING, f.getName());
        long len = f.length();
        if (len > 4L * 1024 * 1024) throw new IoFail(Code.FILE_TOO_BIG, f.getName());
        byte[] buf = new byte[(int) len];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return off == buf.length ? buf : Arrays.copyOf(buf, off);
        } finally {
            closeQuiet(in);
        }
    }
}
