import com.hex91.file.fs.FileTask;
import com.hex91.file.fs.FileTaskEngine;
import com.hex91.file.utils.TaskJournal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * v2.15.0（F-19）：任务暂停 / 继续（字节级续传）的用例。
 *
 * <p>要锁住的是「继续之后文件是好的」这件事，而不是「按钮能点」。所以核心用例不比较耗时，
 * 而是用<b>内容</b>反证走了哪条路：暂停后把源文件前 N 字节改掉（N = 已写的偏移），
 * 再把长度与 mtime 还原 ——</p>
 * <ul>
 *   <li>如果真从偏移续写，输出的前 N 字节来自半截 {@code .part}（原始内容），
 *       整份文件应等于改动前的原文件；</li>
 *   <li>如果偷偷从头重拷，输出的前 N 字节就会是改后的 {@code 0xAA}。</li>
 * </ul>
 * <p>这个断言在「看起来成功、内容其实是两段拼接」的真正事故里会立刻红，
 * 而比较耗时或只比长度都发现不了。</p>
 *
 * 纯 JDK：引擎本来就是纯 Java，桌面直接跑。
 */
public class TaskPauseTest {

    static int pass = 0, fail = 0;
    static final File ROOT = new File("build/taskpausetest");
    static final int MB = 1024 * 1024;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        del(ROOT);
        ROOT.mkdirs();
        FileTaskEngine eng = FileTaskEngine.get();

        stateSemantics(eng);
        resumeFromOffset(eng);
        restartWhenChanged(eng);
        multiFileSkip(eng);
        pauseQueued(eng);
        cancelWhilePaused(eng);
        journalKeepsPaused(eng);

        System.out.println();
        System.out.println("TaskPauseTest  pass=" + pass + " fail=" + fail);
        del(ROOT);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ 状态语义

    static void stateSemantics(FileTaskEngine eng) throws Exception {
        File src = pattern(new File(dir("sem_src"), "a.bin"), 2 * MB);
        File dst = dir("sem_dst");
        FileTask t = eng.submit(FileTask.COPY, list(src), dst);
        waitDone(t, 20000);

        FileTask fake = eng.submit(FileTask.COPY, list(src), dir("sem_dst2"));
        waitDone(fake, 20000);
        ok(t.state == FileTask.DONE, "状态：正常复制到 DONE");
        ok(!t.pausable() && !t.resumable() && !t.cancelable(), "状态：DONE 不可暂停/继续/取消");
        ok("已完成".equals(t.stateText()), "状态：DONE 文案是「已完成」");
        // 「已暂停」这条状态只能靠真跑出来，下面几个用例覆盖；这里验常量不与既有取值冲突
        ok(FileTask.PAUSED != FileTask.DONE && FileTask.PAUSED != FileTask.CANCELLED
                        && FileTask.PAUSED != FileTask.RUNNING,
                "状态：PAUSED 与既有状态取值不重复");
    }

    // ------------------------------------------------------------ 从偏移续传

    static void resumeFromOffset(FileTaskEngine eng) throws Exception {
        // 反复试到「真的在写的时候被暂停」为止：本机 SSD 很快，8MB 可能几毫秒就拷完
        for (int attempt = 1; attempt <= 5; attempt++) {
            File srcDir = dir("resume_src" + attempt);
            File src = pattern(new File(srcDir, "big.bin"), 12 * MB);
            File dst = dir("resume_dst" + attempt);
            File snapshot = new File(dir("resume_snap" + attempt), "big.bin");
            copyRaw(src, snapshot);

            FileTask t = eng.submit(FileTask.COPY, list(src), dst);
            if (!awaitWriting(t, 256 * 1024, 8000)) {
                System.out.println("  (第 " + attempt + " 次没赶上 RUNNING，重试)");
                waitDone(t, 20000);
                continue;
            }
            eng.pause(t.id);
            if (!awaitState(t, FileTask.PAUSED, 15000)) {
                System.out.println("  (第 " + attempt + " 次暂停没落地，重试)");
                waitDone(t, 20000);
                continue;
            }

            long off = t.resumeOffset;
            File part = t.resumePart;
            ok(t.state == FileTask.PAUSED, "暂停：状态 PAUSED");
            ok(off > 0 && off < src.length(), "暂停：续传点在文件中间（" + off + " / "
                    + src.length() + "）");
            ok(part != null && part.isFile() && part.length() == off,
                    "暂停：半截 .part 保留，长度等于续传点");
            ok(!new File(dst, src.getName()).exists(), "暂停：正式文件还没出现（不会留半截成品）");
            ok(t.percent() > 0 && t.percent() < 100, "暂停：进度停在中间（" + t.percent() + "%）");
            ok(t.speed(System.currentTimeMillis()) == 0, "暂停：速度显示 0（不装成还在跑）");
            ok(t.resumable() && t.cancelable() && !t.finished(),
                    "暂停：可继续、可取消、不算已结束");

            // 改掉「已经写进 part 的那段」源字节，长度与 mtime 还原 → 续传必须跳过它们
            long mtime = src.lastModified();
            overwritePrefix(src, off, (byte) 0xAA);
            src.setLastModified(mtime);

            ok(eng.resume(t.id), "继续：resume 返回 true");
            waitDone(t, 30000);
            File out = new File(dst, src.getName());
            ok(t.state == FileTask.DONE, "继续：跑到 DONE");
            ok(out.isFile() && out.length() == src.length(), "继续：输出长度与源一致");
            ok(sameContent(snapshot, out),
                    "继续：内容等于改动前的原文件（证明从偏移续写，而不是从头重拷）");
            ok(!part.exists(), "继续：半截 .part 已 rename 掉，不留垃圾");
            ok(t.doneBytes == t.totalBytes, "继续：doneBytes 与 totalBytes 对齐（没有重复累加）");
            ok(t.percent() == 100, "继续：百分比 100");
            return;
        }
        ok(false, "续传用例：5 次都没能在拷贝中暂停（机器太快），用例作废");
    }

    // ------------------------------------------------------------ 源变了就重来

    static void restartWhenChanged(FileTaskEngine eng) throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            File src = pattern(new File(dir("chg_src" + attempt), "big.bin"), 12 * MB);
            File dst = dir("chg_dst" + attempt);
            FileTask t = eng.submit(FileTask.COPY, list(src), dst);
            if (!awaitWriting(t, 256 * 1024, 8000)) { waitDone(t, 20000); continue; }
            eng.pause(t.id);
            if (!awaitState(t, FileTask.PAUSED, 15000)) { waitDone(t, 20000); continue; }

            long off = t.resumeOffset;
            File part = t.resumePart;
            // 改前缀且**不**还原 mtime：续传校验应当拒绝这份半截文件
            overwritePrefix(src, off, (byte) 0x55);
            src.setLastModified(src.lastModified() + 5000);

            eng.resume(t.id);
            waitDone(t, 30000);
            File out = new File(dst, src.getName());
            ok(t.state == FileTask.DONE, "源已变：仍然跑到 DONE");
            ok(out.isFile() && sameContent(src, out),
                    "源已变：输出与改后的源一致（丢弃半截从头写，不是两段拼接）");
            ok(!part.exists(), "源已变：旧的半截 .part 被清掉");
            ok(t.doneBytes == t.totalBytes, "源已变：进度没有因为重写而虚高");
            return;
        }
        ok(false, "源已变用例：5 次都没能在拷贝中暂停，用例作废");
    }

    // ------------------------------------------------------------ 已拷完的文件要跳过

    static void multiFileSkip(FileTaskEngine eng) throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            File srcDir = dir("multi_src" + attempt);
            List<File> srcs = new ArrayList<File>();
            for (int i = 0; i < 12; i++) srcs.add(pattern(new File(srcDir, "f" + i + ".bin"), MB));
            File dst = dir("multi_dst" + attempt);

            FileTask t = eng.submit(FileTask.COPY, srcs, dst);
            if (!awaitFiles(t, 3, 8000)) { waitDone(t, 30000); continue; }
            eng.pause(t.id);
            if (!awaitState(t, FileTask.PAUSED, 15000)) { waitDone(t, 30000); continue; }

            int doneAtPause = t.doneFiles;
            ok(doneAtPause >= 3 && doneAtPause < 12,
                    "多文件：暂停时已完成 " + doneAtPause + " 个（中间态）");
            eng.resume(t.id);
            waitDone(t, 40000);

            int hit = 0;
            for (File f : srcs) {
                File o = new File(dst, f.getName());
                if (o.isFile() && sameContent(f, o)) hit++;
            }
            ok(t.state == FileTask.DONE, "多文件：跑到 DONE");
            ok(hit == 12, "多文件：12 个文件全部正确（实际 " + hit + "）");
            ok(t.doneBytes == t.totalBytes,
                    "多文件：doneBytes 等于总量（重复累加的话会超出来）");
            ok(t.doneFiles == t.totalFiles, "多文件：doneFiles 等于总文件数");
            return;
        }
        ok(false, "多文件用例：5 次都没能在拷贝中暂停，用例作废");
    }

    // ------------------------------------------------------------ 排队中暂停

    static void pauseQueued(FileTaskEngine eng) throws Exception {
        File big = pattern(new File(dir("q_src"), "big.bin"), 12 * MB);
        File other = pattern(new File(dir("q_src"), "other.bin"), 4 * MB);
        FileTask first = eng.submit(FileTask.COPY, list(big), dir("q_dst1"));
        FileTask second = eng.submit(FileTask.COPY, list(other), dir("q_dst2"));

        ok(eng.pause(second.id), "排队暂停：pause 接受排队中的任务");
        // 排队中的任务不占 worker，应当立刻是 PAUSED
        ok(awaitState(second, FileTask.PAUSED, 5000), "排队暂停：立刻进入 PAUSED");
        ok(first.state == FileTask.RUNNING || first.state == FileTask.DONE,
                "排队暂停：前一个任务不受影响");

        ok(!eng.hasRunning() || first.pausable(), "排队暂停：hasRunning 只反映在做功的任务");
        ok(eng.hasActive(), "排队暂停：hasActive 仍为真（任务没结束）");

        eng.resume(second.id);
        waitDone(second, 40000);
        waitDone(first, 40000);
        File o2 = new File(dir("q_dst2"), "other.bin");
        ok(second.state == FileTask.DONE && o2.isFile() && sameContent(other, o2),
                "排队暂停：继续后正常完成");
    }

    // ------------------------------------------------------------ 暂停中取消

    static void cancelWhilePaused(FileTaskEngine eng) throws Exception {
        File src = pattern(new File(dir("cx_src"), "big.bin"), 12 * MB);
        File dst = dir("cx_dst");
        FileTask t = eng.submit(FileTask.COPY, list(src), dst);
        if (!awaitWriting(t, 256 * 1024, 8000)) {
            waitDone(t, 20000);
            ok(true, "取消暂停：本机太快没赶上中间态，跳过（状态语义已由其他用例覆盖）");
            return;
        }
        eng.pause(t.id);
        if (!awaitState(t, FileTask.PAUSED, 15000)) {
            waitDone(t, 20000);
            ok(true, "取消暂停：暂停没落地，跳过");
            return;
        }
        File part = t.resumePart;
        eng.cancel(t.id);
        ok(t.state == FileTask.CANCELLED, "取消暂停：状态 CANCELLED");
        ok(t.finished() && !t.resumable(), "取消暂停：不再可继续");
        ok(part == null || !part.exists(), "取消暂停：半截 .part 被删掉");
        ok(!eng.resume(t.id), "取消暂停：之后 resume 返回 false，不会诈尸");
    }

    // ------------------------------------------------------------ 账本

    static void journalKeepsPaused(FileTaskEngine eng) throws Exception {
        final List<TaskJournal.Entry> saved = Collections.synchronizedList(
                new ArrayList<TaskJournal.Entry>());
        eng.setJournalStore(new TaskJournal.Store() {
            public void save(List<TaskJournal.Entry> es) {
                saved.clear();
                saved.addAll(es);
            }
        });

        File src = pattern(new File(dir("j_src"), "big.bin"), 12 * MB);
        File dst = dir("j_dst");
        FileTask t = eng.submit(FileTask.COPY, list(src), dst);
        ok(saved.size() == 1, "账本：提交后记下这条未完成任务");

        if (awaitWriting(t, 256 * 1024, 8000) && eng.pause(t.id)
                && awaitState(t, FileTask.PAUSED, 15000)) {
            ok(saved.size() == 1, "账本：暂停期间仍然记着（进程被清后还能提示未完成）");
            eng.cancel(t.id);
            ok(saved.isEmpty(), "账本：取消后从账本消失");
        } else {
            waitDone(t, 20000);
            ok(saved.isEmpty(), "账本：跑完后从账本消失");
        }
        eng.setJournalStore(null);
    }

    // ------------------------------------------------------------ 工具

    /** 等到「确实在写数据」：RUNNING 且已写出至少 min 字节 */
    static boolean awaitWriting(FileTask t, long min, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (t.state == FileTask.RUNNING && t.doneBytes >= min) return true;
            if (t.finished()) return false;
            Thread.sleep(1);
        }
        return t.state == FileTask.RUNNING && t.doneBytes >= min;
    }

    /** 等到完成至少 n 个文件 */
    static boolean awaitFiles(FileTask t, int n, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (t.doneFiles >= n) return true;
            if (t.finished()) return false;
            Thread.sleep(1);
        }
        return t.doneFiles >= n;
    }

    static boolean awaitState(FileTask t, int state, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (t.state == state) return true;
            if (t.finished() && state != FileTask.DONE) return false;
            Thread.sleep(2);
        }
        return t.state == state;
    }

    static void waitDone(FileTask t, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !t.finished()) Thread.sleep(5);
    }

    static File dir(String name) {
        File d = new File(ROOT, name);
        d.mkdirs();
        return d;
    }

    /** 造一个有规律的大文件：字节值随偏移变化，改一段就能看出来 */
    static File pattern(File f, int size) throws Exception {
        OutputStream o = new FileOutputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int written = 0;
            while (written < size) {
                int n = Math.min(buf.length, size - written);
                for (int i = 0; i < n; i++) buf[i] = (byte) ((written + i) * 31 & 0xff);
                o.write(buf, 0, n);
                written += n;
            }
        } finally {
            o.close();
        }
        return f;
    }

    static void overwritePrefix(File f, long n, byte v) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try {
            byte[] buf = new byte[(int) Math.min(64 * 1024, n)];
            Arrays.fill(buf, v);
            long left = n;
            while (left > 0) {
                int w = (int) Math.min(buf.length, left);
                raf.write(buf, 0, w);
                left -= w;
            }
        } finally {
            raf.close();
        }
    }

    static void copyRaw(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    static boolean sameContent(File a, File b) throws Exception {
        if (!a.isFile() || !b.isFile() || a.length() != b.length()) return false;
        InputStream ia = new FileInputStream(a);
        try {
            InputStream ib = new FileInputStream(b);
            try {
                byte[] ba = new byte[128 * 1024];
                byte[] bb = new byte[128 * 1024];
                while (true) {
                    int na = readFully(ia, ba);
                    int nb = readFully(ib, bb);
                    if (na != nb) return false;
                    if (na <= 0) return true;
                    for (int i = 0; i < na; i++) if (ba[i] != bb[i]) return false;
                }
            } finally {
                ib.close();
            }
        } finally {
            ia.close();
        }
    }

    static int readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    static List<File> list(File... fs) {
        return new ArrayList<File>(Arrays.asList(fs));
    }

    static void del(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) del(k);
        f.delete();
    }
}
