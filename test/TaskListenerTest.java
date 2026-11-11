import com.hex91.file.FileTask;
import com.hex91.file.FileTaskEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.98.0：任务引擎的多监听者（F-07 的前台服务靠它刷通知）。
 *
 * <p>要守住的规则只有一条：界面在 {@code onPause} 里会 {@code setListener(null)} 主动断开引用，
 * 但那正是「切到后台、最需要通知栏进度」的时刻 —— 服务那份订阅必须独立存在，不能被一起清掉。</p>
 */
public class TaskListenerTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "hexfile_tasklistener_" + System.nanoTime());
        File src = new File(base, "src");
        File dst = new File(base, "dst");
        src.mkdirs();
        dst.mkdirs();
        write(new File(src, "a.txt"), "hello");
        write(new File(src, "b.txt"), "world");

        final AtomicInteger uiCount = new AtomicInteger();
        final AtomicInteger svcCount = new AtomicInteger();
        FileTaskEngine.Listener ui = new FileTaskEngine.Listener() {
            public void onTasksChanged() { uiCount.incrementAndGet(); }
        };
        FileTaskEngine.Listener svc = new FileTaskEngine.Listener() {
            public void onTasksChanged() { svcCount.incrementAndGet(); }
        };

        FileTaskEngine e = FileTaskEngine.get();
        e.setListener(ui);
        e.addListener(svc);

        e.submit(FileTask.COPY, one(src), dst);
        waitIdle(e);

        ok(uiCount.get() > 0, "界面监听收到进度（" + uiCount.get() + " 次）");
        ok(svcCount.get() > 0, "服务监听也收到进度（" + svcCount.get() + " 次）");

        // 关键：界面 onPause 断开后，服务仍要能收到
        e.setListener(null);
        int uiBefore = uiCount.get();
        int svcBefore = svcCount.get();
        e.submit(FileTask.COPY, one(new File(src, "b.txt")), dst);
        waitIdle(e);
        ok(uiCount.get() == uiBefore, "setListener(null) 后界面不再收到（引用已断开）");
        ok(svcCount.get() > svcBefore, "服务监听不受影响，继续收到进度");

        // 服务销毁后注销，不能残留
        e.removeListener(svc);
        int svcAfterRemove = svcCount.get();
        e.submit(FileTask.COPY, one(new File(src, "a.txt")), dst);
        waitIdle(e);
        ok(svcCount.get() == svcAfterRemove, "removeListener 之后不再回调（无泄漏）");

        deleteTree(base);
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static List<File> one(File f) {
        List<File> l = new ArrayList<File>();
        l.add(f);
        return l;
    }

    /** 等队列跑空（最多 10 秒，超时算不通过由后续断言体现） */
    static void waitIdle(FileTaskEngine e) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            if (!e.hasActive()) {
                Thread.sleep(30);   // 让最后一次回调落地
                return;
            }
            Thread.sleep(20);
        }
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
