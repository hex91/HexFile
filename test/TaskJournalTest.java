import com.hex91.file.FileTask;
import com.hex91.file.TaskJournal;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v1.99.2（R-07）：未完成任务账本。
 *
 * <p>要守住的规则：能被原样读回来（路径里有空格/中文/反斜杠都不能串行）、
 * 没有未完成任务时文件必须消失（否则每次启动都弹「上次没做完」）、
 * 文件被改坏时当成「没有账本」而不是让应用起不来。</p>
 */
public class TaskJournalTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqi(long a, long b, String msg) {
        if (a == b) { pass++; System.out.println("PASS  " + msg + " = " + b); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + a + " 实际 " + b); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "hexfile_journal_" + System.nanoTime());
        base.mkdirs();
        try {
            t1RoundTrip(base);
            t2EmptyDeletes(base);
            t3BadFile(base);
            t4Missing(base);
        } finally {
            deleteTree(base);
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 写进去的路径必须原样读回来（含空格、中文、反斜杠） */
    static void t1RoundTrip(File base) throws Exception {
        File jf = new File(base, "task_journal");
        List<File> srcs = new ArrayList<File>(Arrays.asList(
                new File(base, "我的 文件.txt"),
                new File(base, "sub/a.zip")));
        List<TaskJournal.Entry> es = new ArrayList<TaskJournal.Entry>();
        es.add(new TaskJournal.Entry(FileTask.COPY, new File(base, "目标 目录"), srcs));
        es.add(new TaskJournal.Entry(FileTask.MOVE, new File(base, "dst2"),
                Arrays.asList(new File(base, "one"))));

        ok(TaskJournal.save(jf, es), "账本写入成功");
        List<TaskJournal.Entry> back = TaskJournal.load(jf);
        eqi(2, back.size(), "任务条数");
        eqi(FileTask.COPY, back.get(0).kind, "第一个任务类型");
        ok(back.get(0).dstDir.getPath().endsWith("目标 目录"), "目标目录路径原样保留");
        eqi(2, back.get(0).srcs.size(), "来源条数");
        ok(back.get(0).srcs.get(0).getName().equals("我的 文件.txt"), "含空格与中文的文件名");
        ok(back.get(0).srcs.get(0).getAbsolutePath()
                .equals(srcs.get(0).getAbsolutePath()), "绝对路径完全一致");
        eqi(FileTask.MOVE, back.get(1).kind, "第二个任务类型");
    }

    /** 全部完成时必须删掉文件：留着就会每次启动都问「上次没做完」 */
    static void t2EmptyDeletes(File base) throws Exception {
        File jf = new File(base, "empty_journal");
        TaskJournal.save(jf, new ArrayList<TaskJournal.Entry>(
                Arrays.asList(new TaskJournal.Entry(FileTask.COPY,
                        new File(base, "d"), Arrays.asList(new File(base, "s"))))));
        ok(jf.isFile(), "先写入一条");
        ok(TaskJournal.save(jf, new ArrayList<TaskJournal.Entry>()), "写空列表返回成功");
        ok(!jf.exists(), "空列表把账本文件删掉（不留痕）");
    }

    /** 文件头不对（旧版本 / 被改坏）：当成没有账本，不能抛 */
    static void t3BadFile(File base) throws Exception {
        File jf = new File(base, "bad_journal");
        FileOutputStream out = new FileOutputStream(jf);
        try {
            out.write("不是账本\nT\t0\t/tmp\nS\t/tmp/a\n".getBytes("UTF-8"));
        } finally {
            out.close();
        }
        eqi(0, TaskJournal.load(jf).size(), "格式不认识时返回空列表");
        ok(true, "损坏的账本没有抛异常");
    }

    static void t4Missing(File base) {
        eqi(0, TaskJournal.load(new File(base, "nope")).size(), "文件不存在返回空列表");
        eqi(0, TaskJournal.load(null).size(), "null 返回空列表");
        ok(!TaskJournal.save(null, null), "null 路径写入返回 false");
    }

    static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
