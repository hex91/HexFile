import com.hex91.file.core.VaultCrypto;
import com.hex91.file.core.VaultIndex;
import com.hex91.file.fs.VaultStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * v2.18.0（F-22）：隐私空间用例。
 *
 * <p>这一层出错的后果都不是「功能不好用」，而是「东西没了或没锁住」，所以分四块钉死：</p>
 * <ol>
 *   <li><b>头部与口令</b>：口令错必须解不开、头部被改必须报错、改口令后主密钥不变
 *       （否则改了密码等于把所有文件锁死）；</li>
 *   <li><b>文件块</b>：往返一致、密文里不含明文、改一个字节就验签失败、
 *       把 A 的密文塞到 B 的位置也要失败（id 进 MAC 的意义）；</li>
 *   <li><b>索引</b>：文件名里的制表符/换行/反斜杠要能原样回来；坏行只丢那一行；</li>
 *   <li><b>存储层</b>：临时目录跑真文件——导入/导出/重名/改名/删除/取消/游离密文/索引回退。</li>
 * </ol>
 */
public class VaultTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int want, int got, String msg) {
        boolean v = want == got;
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    static void eq(long want, long got, String msg) {
        boolean v = want == got;
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    static void eq(String want, String got, String msg) {
        boolean v = want == null ? got == null : want.equals(got);
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    /** 允许抛受检异常的动作 */
    interface Act {
        void run() throws Exception;
    }

    /** 期望抛某类异常（且是「原样」抛出，不含包装） */
    static void throwsIt(Class<?> want, Act a, String msg) {
        try {
            a.run();
            ok(false, msg + "（没有抛异常）");
        } catch (Throwable t) {
            ok(want.isInstance(t), msg + (want.isInstance(t) ? ""
                    : "（抛的是 " + t.getClass().getSimpleName() + "：" + t.getMessage() + "）"));
        }
    }

    public static void main(String[] args) throws Exception {
        header();
        blobs();
        index();
        store();
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 头部 / 口令

    static void header() throws Exception {
        VaultCrypto.Header h = VaultCrypto.newHeader("correct horse");
        eq(VaultCrypto.HEADER_LEN, h.bytes.length, "头部: 定长 157 字节");
        ok(VaultCrypto.isHeader(h.bytes), "头部: 魔数可识别");
        ok(!VaultCrypto.isHeader(new byte[VaultCrypto.HEADER_LEN]), "头部: 全零字节不算");
        ok(!VaultCrypto.isHeader(Arrays.copyOf(h.bytes, 40)), "头部: 长度不足不算");

        final VaultCrypto.Master m = VaultCrypto.openHeader("correct horse", h.bytes);
        ok(Arrays.equals(h.master.enc, m.enc), "头部: 正确口令解出的 enc 与创建时一致");
        ok(Arrays.equals(h.master.mac, m.mac), "头部: 正确口令解出的 mac 与创建时一致");

        final byte[] hb = h.bytes;
        throwsIt(SecurityException.class, new Act() {
            public void run() throws Exception { VaultCrypto.openHeader("wrong horse", hb); }
        }, "头部: 错误口令 → SecurityException");

        // 改 salt 区（偏移 13 起）一个字节：MAC 覆盖了它，必须验不过
        final byte[] tampered = h.bytes.clone();
        tampered[14] ^= 0x5A;
        throwsIt(SecurityException.class, new Act() {
            public void run() throws Exception { VaultCrypto.openHeader("correct horse", tampered); }
        }, "头部: salt 被改一个字节 → 验签失败");

        // 迭代次数被调大到 2^31-1：不拦就等于让攻击者（或一次误改）把解锁变成分钟级卡死
        final byte[] bigIter = h.bytes.clone();
        bigIter[11] = (byte) 0x7F;
        bigIter[12] = (byte) 0xFF;
        bigIter[13] = (byte) 0xFF;
        bigIter[14] = (byte) 0xFF;
        throwsIt(IllegalArgumentException.class, new Act() {
            public void run() throws Exception { VaultCrypto.openHeader("correct horse", bigIter); }
        }, "头部: 迭代次数 2^31-1 → 直接拒绝（防 DoS）");

        // 迭代次数被调小到 1000（削弱暴力破解成本）
        byte[] lowIter = h.bytes.clone();
        lowIter[11] = 0;
        lowIter[12] = 0;
        lowIter[13] = 0x03;
        lowIter[14] = (byte) 0xE8;
        final byte[] lowF = lowIter;
        throwsIt(IllegalArgumentException.class, new Act() {
            public void run() throws Exception { VaultCrypto.openHeader("correct horse", lowF); }
        }, "头部: 迭代次数 1000 → 直接拒绝（防削弱）");

        // 改口令：新口令能解、旧口令不能解、解出来的主密钥还是同一把
        final byte[] h2 = VaultCrypto.rewrap(h.master, "another pass");
        VaultCrypto.Master m2 = VaultCrypto.openHeader("another pass", h2);
        ok(Arrays.equals(m.enc, m2.enc) && Arrays.equals(m.mac, m2.mac),
                "改口令: 主密钥不变（文件不用重加密）");
        throwsIt(SecurityException.class, new Act() {
            public void run() throws Exception { VaultCrypto.openHeader("correct horse", h2); }
        }, "改口令: 旧口令解不开新头部");

        ok(VaultCrypto.ITERATIONS >= 200_000,
                "头部: 默认迭代次数 ≥ 20 万（" + VaultCrypto.ITERATIONS + "）");
    }

    // ---------------------------------------------------------------- 文件块

    static void blobs() throws Exception {
        File tmp = mkTemp("vblob");
        try {
            byte[] data = new byte[3000];
            new Random(7).nextBytes(data);
            byte[] text = "隐私空间测试标记".getBytes("UTF-8");
            System.arraycopy(text, 0, data, 100, text.length);
            File src = write(tmp, "a.bin", data);

            final VaultCrypto.Master m = VaultCrypto.newHeader("pass1234").master;
            final String id = VaultIndex.newId();
            final File blob = new File(tmp, "a.enc");
            long n = VaultCrypto.encryptFile(src, blob, VaultIndex.unhex(id), m, null);
            eq(3000, n, "文件块: 返回明文字节数");
            // 3000 → PKCS5 补到 3008，尾部再挂 32 字节 tag
            eq(VaultCrypto.BLOB_HEAD + 3008 + 32, (int) blob.length(), "文件块: 密文长度 = 填充后明文 + tag");
            ok(!contains(blob, text), "文件块: 密文里搜不到明文标记");
            ok(Arrays.equals(VaultIndex.unhex(id), VaultCrypto.blobId(blob)),
                    "文件块: 能从头部读回 id");

            File back = new File(tmp, "a.out");
            VaultCrypto.decryptFile(blob, back, VaultIndex.unhex(id), m, null);
            ok(Arrays.equals(data, read(back)), "文件块: 往返内容一致");

            // 改密文（改 tag 之前的一个密文字节）
            byte[] raw = read(blob);
            raw[raw.length - 40] ^= 0x01;
            final File bad = write(tmp, "a.bad", raw);
            throwsIt(SecurityException.class, new Act() {
                public void run() throws Exception {
                    VaultCrypto.decryptFile(bad, new File(bad.getParentFile(), "x.out"),
                            VaultIndex.unhex(id), m, null);
                }
            }, "文件块: 密文改一个字节 → 验签失败");

            // 张冠李戴：拿 A 的密文当 B 的 id 解
            throwsIt(SecurityException.class, new Act() {
                public void run() throws Exception {
                    VaultCrypto.decryptFile(blob, new File(tmp, "y.out"),
                            VaultIndex.unhex(VaultIndex.newId()), m, null);
                }
            }, "文件块: 用别人的 id 解 → 拒绝（防密文挪用）");

            // 空文件
            File empty = write(tmp, "empty.bin", new byte[0]);
            String id2 = VaultIndex.newId();
            File eblob = new File(tmp, "empty.enc");
            VaultCrypto.encryptFile(empty, eblob, VaultIndex.unhex(id2), m, null);
            File eout = new File(tmp, "empty.out");
            VaultCrypto.decryptFile(eblob, eout, VaultIndex.unhex(id2), m, null);
            eq(0L, eout.length(), "文件块: 空文件往返");

            // 1MB 走流式
            byte[] big = new byte[1024 * 1024];
            new Random(11).nextBytes(big);
            final File bsrc = write(tmp, "big.bin", big);
            String id3 = VaultIndex.newId();
            File bblob = new File(tmp, "big.enc");
            final long[] seen = new long[1];
            VaultCrypto.encryptFile(bsrc, bblob, VaultIndex.unhex(id3), m,
                    new VaultCrypto.Progress() {
                        public boolean on(long done, long total) {
                            seen[0] = Math.max(seen[0], done);
                            return true;
                        }
                    });
            eq(1024 * 1024L, seen[0], "文件块: 进度累计到全量");
            File bout = new File(tmp, "big.out");
            VaultCrypto.decryptFile(bblob, bout, VaultIndex.unhex(id3), m, null);
            ok(Arrays.equals(big, read(bout)), "文件块: 1MB 往返一致");

            // 取消：进度返回 false 必须中止
            final File cancelOut = new File(tmp, "cancel.enc");
            throwsIt(VaultCrypto.Cancelled.class, new Act() {
                public void run() throws Exception {
                    VaultCrypto.encryptFile(bsrc, cancelOut, VaultIndex.unhex(VaultIndex.newId()),
                            m, new VaultCrypto.Progress() {
                                public boolean on(long d, long t) { return false; }
                            });
                }
            }, "文件块: 进度返回 false → Cancelled");

            ok(VaultCrypto.blobId(write(tmp, "junk.bin", "hello".getBytes("UTF-8"))) == null,
                    "文件块: 非密文读不出 id");

            // 索引块
            byte[] plain = "id1\t1\t2\t3\t名字\n".getBytes("UTF-8");
            byte[] box = VaultCrypto.seal(plain, m);
            ok(Arrays.equals(plain, VaultCrypto.open(box, m)), "索引块: seal/open 往返");
            byte[] box2 = box.clone();
            box2[box2.length - 40] ^= 0x01;
            final byte[] box2f = box2;
            throwsIt(SecurityException.class, new Act() {
                public void run() throws Exception { VaultCrypto.open(box2f, m); }
            }, "索引块: 改一个字节 → 验签失败");
            throwsIt(IllegalArgumentException.class, new Act() {
                public void run() throws Exception {
                    VaultCrypto.open("short".getBytes("UTF-8"), m);
                }
            }, "索引块: 长度不足 → 报错而不是数组越界");
        } finally {
            deleteTree(tmp);
        }
    }

    // ---------------------------------------------------------------- 索引

    static void index() {
        // id 生成与校验
        Set<String> ids = new HashSet<String>();
        boolean allOk = true;
        for (int i = 0; i < 500; i++) {
            String id = VaultIndex.newId();
            if (!VaultIndex.isId(id)) allOk = false;
            ids.add(id);
        }
        ok(allOk, "索引: newId 全部是 32 位小写十六进制");
        eq(500, ids.size(), "索引: 500 次生成无重复");
        ok(!VaultIndex.isId(null), "索引: null 不是 id");
        ok(!VaultIndex.isId("../" + VaultIndex.newId().substring(3)), "索引: 带 ../ 的不是 id");
        ok(!VaultIndex.isId(VaultIndex.newId().toUpperCase()), "索引: 大写十六进制不认");
        ok(!VaultIndex.isId(VaultIndex.newId().substring(1)), "索引: 31 位不认");

        // 转义往返
        List<VaultIndex.Entry> list = new ArrayList<VaultIndex.Entry>();
        list.add(new VaultIndex.Entry(VaultIndex.newId(), "普通.txt", 10, 1000, 2000));
        list.add(new VaultIndex.Entry(VaultIndex.newId(), "带\t制表符.bin", 0, 0, 0));
        list.add(new VaultIndex.Entry(VaultIndex.newId(), "带\n换行", 1, 1, 1));
        list.add(new VaultIndex.Entry(VaultIndex.newId(), "带\\反斜杠\\和\\t", 2, 2, 2));
        list.add(new VaultIndex.Entry(VaultIndex.newId(), "表情 😀 与空格 ", 3, 3, 3));
        VaultIndex.Read r = VaultIndex.decode(VaultIndex.encode(list));
        eq(5, r.entries.size(), "索引: 往返条数不变");
        eq(0, r.bad, "索引: 没有坏行");
        boolean same = true;
        for (int i = 0; i < list.size() && i < r.entries.size(); i++) {
            if (!list.get(i).name.equals(r.entries.get(i).name)) {
                same = false;
                System.out.println("  (名字不符: " + list.get(i).name + " → " + r.entries.get(i).name + ")");
            }
            if (!list.get(i).id.equals(r.entries.get(i).id)) same = false;
            if (list.get(i).size != r.entries.get(i).size) same = false;
        }
        ok(same, "索引: id/名字/大小 原样回来（含制表符、换行、反斜杠、emoji）");

        // 魔数不对
        throwsIt(IllegalArgumentException.class, new Act() {
            public void run() {
                VaultIndex.decode("NOTMAGIC\na\t1\t2\t3\tx\n".getBytes());
            }
        }, "索引: 魔数不符 → IllegalArgumentException");

        // 坏行只丢那一行
        String good = VaultIndex.newId();
        String text = "HFVIDX1\n"
                + good + "\t5\t6\t7\t好文件\n"
                + "BADID\t1\t2\t3\t坏 id\n"
                + VaultIndex.newId().toUpperCase() + "\t1\t2\t3\t大写 id\n"
                + VaultIndex.newId() + "\t-1\t2\t3\t负大小\n"
                + VaultIndex.newId() + "\t1\t2\n"
                + VaultIndex.newId() + "\tx\t2\t3\t非数字\n";
        VaultIndex.Read r2 = VaultIndex.decode(text.getBytes());
        eq(1, r2.entries.size(), "索引: 坏行被跳过，好行留下");
        eq(5, r2.bad, "索引: 坏行计数 = 5");
        eq("好文件", r2.entries.get(0).name, "索引: 好行的名字正确");

        // 空表
        VaultIndex.Read r3 = VaultIndex.decode(VaultIndex.encode(new ArrayList<VaultIndex.Entry>()));
        eq(0, r3.entries.size(), "索引: 空表往返");
    }

    // ---------------------------------------------------------------- 存储层

    static void store() throws Exception {
        File root = mkTemp("vault");
        try {
            final File dir = new File(root, ".vault");
            final VaultCrypto.Master m = VaultStore.create(dir, "secret123");
            ok(VaultStore.exists(dir), "存储: create 之后 exists 为真");
            ok(new File(dir, "b").isDirectory(), "存储: 密文子目录已建");
            ok(VaultStore.headerFile(dir).length() == VaultCrypto.HEADER_LEN,
                    "存储: 头部文件 157 字节");

            throwsIt(IllegalArgumentException.class, new Act() {
                public void run() throws Exception {
                    VaultStore.create(new File(root, "another"), "12345");
                }
            }, "存储: 口令不足 6 位 → IllegalArgumentException");

            throwsIt(SecurityException.class, new Act() {
                public void run() throws Exception { VaultStore.unlock(dir, "wrongpass"); }
            }, "存储: 错误口令解锁 → SecurityException");
            VaultCrypto.Master m2 = VaultStore.unlock(dir, "secret123");
            ok(Arrays.equals(m.enc, m2.enc), "存储: 正确口令解锁拿到同一把主密钥");

            // 导入
            byte[] body = "隐私空间里的内容\nline2\n".getBytes("UTF-8");
            File srcFile = write(root, "note.txt", body);
            InputStream in = new FileInputStream(srcFile);
            VaultIndex.Entry e;
            try {
                e = VaultStore.add(dir, m, in, srcFile.length(), "note.txt",
                        srcFile.lastModified(), null);
            } finally {
                in.close();
            }
            eq("note.txt", e.name, "存储: 导入后名字正确");
            eq((long) body.length, e.size, "存储: 记录的大小是明文大小");
            ok(VaultStore.blobFile(dir, e.id).isFile(), "存储: 密文文件已生成");
            ok(!contains(VaultStore.blobFile(dir, e.id), "隐私空间里的内容".getBytes("UTF-8")),
                    "存储: 密文里搜不到明文");

            VaultStore.Table t = VaultStore.load(dir, m);
            eq(1, t.entries.size(), "存储: 索引里有一条");
            eq(0, t.bad, "存储: 索引没有坏行");
            ok(!t.backup, "存储: 用的是主索引");

            // 导出
            File outDir = mkdirs(new File(root, "out"));
            File out1 = VaultStore.export(dir, m, e.id, outDir, null);
            ok(Arrays.equals(body, read(out1)), "存储: 导出内容一致");
            eq("note.txt", out1.getName(), "存储: 导出用原名");
            File out2 = VaultStore.export(dir, m, e.id, outDir, null);
            eq("note (1).txt", out2.getName(), "存储: 同名导出自动加 (1)");

            // 改名
            VaultStore.rename(dir, m, e.id, "改名后.txt");
            VaultStore.Table t2 = VaultStore.load(dir, m);
            eq("改名后.txt", t2.entries.get(0).name, "存储: 改名写回索引");
            eq("改名后.txt", VaultStore.export(dir, m, e.id, mkdirs(new File(root, "out2")),
                    null).getName(), "存储: 改名后导出用新名");

            // safeName
            eq(".._.._etc_passwd", VaultStore.safeName("../../etc/passwd"),
                    "存储: 名字里的路径分隔符被替换");
            eq("Untitled", VaultStore.safeName(".."), "存储: \"..\" 视作空名字");
            eq("Untitled", VaultStore.safeName("   "), "存储: 纯空白视作空名字");
            eq("ab", VaultStore.safeName("a\u0001b"), "存储: 控制字符被剔掉");

            // 游离密文：手工塞一个索引里没有的 id
            String ghostId = VaultIndex.newId();
            File ghost = VaultStore.blobFile(dir, ghostId);
            write(ghost.getParentFile(), ghost.getName(), new byte[]{1, 2, 3});
            List<String> orphans = VaultStore.orphans(dir, m);
            eq(1, orphans.size(), "存储: 能认出游离密文");
            eq(ghostId, orphans.get(0), "存储: 游离密文的 id 正确");
            eq(1, VaultStore.deleteOrphans(dir, m), "存储: 清理游离密文 1 个");
            ok(!ghost.isFile(), "存储: 游离密文已删除");

            // 取消导入不留残留
            final File dir2 = mkdirs(new File(root, "v2"));
            final VaultCrypto.Master m3 = VaultStore.create(dir2, "abcdef");
            final InputStream bigIn = new FileInputStream(write(root, "big2.bin", new byte[200000]));
            try {
                throwsIt(VaultCrypto.Cancelled.class, new Act() {
                    public void run() throws Exception {
                        VaultStore.add(dir2, m3, bigIn, 200000, "big2.bin", 0,
                                new VaultCrypto.Progress() {
                                    public boolean on(long d, long t) { return d < 100; }
                                });
                    }
                }, "存储: 导入中途取消 → Cancelled");
            } finally {
                bigIn.close();
            }
            eq(0, VaultStore.load(dir2, m3).entries.size(), "存储: 取消后索引没有幽灵条目");
            eq(0, VaultStore.orphans(dir2, m3).size(), "存储: 取消后半截密文已清掉");

            // 大小未知（-1）时按实际加密量记
            File ns = write(root, "nosize.bin", "1234567890".getBytes("UTF-8"));
            InputStream nsIn = new FileInputStream(ns);
            try {
                VaultIndex.Entry en = VaultStore.add(dir, m, nsIn, -1, "nosize.bin", 0, null);
                eq(10L, en.size, "存储: 大小未知时记录真实明文字节数");
            } finally {
                nsIn.close();
            }

            // 占用字节数
            ok(VaultStore.usedBytes(dir) > VaultCrypto.HEADER_LEN, "存储: usedBytes 大于头部长度");

            // 删除（注意：上面「大小未知」那条也在同一个空间里，删完还剩它）
            VaultStore.remove(dir, m, e.id);
            eq(1, VaultStore.load(dir, m).entries.size(), "存储: 删除后索引只剩另一条");
            ok(VaultStore.find(VaultStore.load(dir, m).entries, e.id) == null,
                    "存储: 被删的那条已不在索引里");
            ok(!VaultStore.blobFile(dir, e.id).isFile(), "存储: 删除后密文也删了");

            // 改口令后同一把主密钥
            VaultStore.changePassword(dir, m, "brandnew");
            VaultCrypto.Master m5 = VaultStore.unlock(dir, "brandnew");
            ok(Arrays.equals(m.enc, m5.enc), "存储: 改口令后同一把主密钥");
            throwsIt(SecurityException.class, new Act() {
                public void run() throws Exception { VaultStore.unlock(dir, "secret123"); }
            }, "存储: 改口令后旧口令失效");

            // 索引损坏 → 回退 .bak（放在最后：这一步会把主索引写成垃圾，之后的读取都会失败）
            File idx = new File(dir, "idx");
            File bak = new File(dir, "idx.bak");
            ok(bak.isFile(), "存储: 每次保存都留了一份 idx.bak");
            write(dir, "idx", "garbage-not-a-box".getBytes("UTF-8"));
            VaultStore.Table t3 = VaultStore.load(dir, m);
            ok(t3.backup, "存储: 主索引损坏 → 回退备份");
            ok(t3.entries.size() >= 1, "存储: 备份里仍有上一条记录（" + t3.entries.size() + " 条）");
            bak.delete();
            throwsIt(IllegalArgumentException.class, new Act() {
                public void run() throws Exception { VaultStore.load(dir, m); }
            }, "存储: 主索引坏了且没备份 → 报错而不是静默清空");
        } finally {
            deleteTree(root);
        }
    }

    // ---------------------------------------------------------------- 工具

    static boolean contains(File f, byte[] needle) throws Exception {
        byte[] all = read(f);
        outer:
        for (int i = 0; i + needle.length <= all.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (all[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    static File mkTemp(String tag) {
        File d = new File(System.getProperty("java.io.tmpdir"),
                "hfv-" + tag + "-" + System.nanoTime());
        return mkdirs(d);
    }

    static File mkdirs(File d) {
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            throw new IllegalStateException("建目录失败: " + d);
        }
        return d;
    }

    static File write(File dir, String name, byte[] data) throws Exception {
        mkdirs(dir);
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        return f;
    }

    static byte[] read(File f) throws Exception {
        long len = f.length();
        byte[] b = new byte[(int) len];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return off == b.length ? b : Arrays.copyOf(b, off);
        } finally {
            in.close();
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) System.out.println("  (清理失败: " + f + ")");
    }
}
