package com.hex91.file.controller;

import com.hex91.file.utils.Bg;
import com.hex91.file.fs.DirReader;
import com.hex91.file.fs.FileItem;
import com.hex91.file.fs.RootUtil;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;

/**
 * 目录控制器（v2.0.0，O-15 刀2）：读目录 / 刷新 / 历史栈。
 *
 * <p>从 MainActivity 整段搬来的方法体，一行逻辑都没有改；区别只有三处取值换了来源：
 * {@code taskUi} → {@link Host#ui()}、{@code isFinishing()||isDestroyed()} →
 * {@link Host#alive()}、直接调用的宿主方法 → {@link Host} 上的同名转发。
 * MainActivity 仍保留同名壳方法（签名一字未改），所以 25 处调用点零改动。</p>
 *
 * <p>为什么只搬这几个：{@code updateHeader()} 是纯 View 渲染，搬走反而要把
 * headerPath/headerStats/usageOf 全部透传；{@code setupPanel()} 是 UI 装配，靠 Activity 的生命周期。</p>
 */
public final class DirectoryController {

    /** 控制器只通过这组能力与宿主打交道（也方便以后脱离 Activity 做单测） */
    public interface Host {
        android.content.Context ctx();

        Pane[] panes();

        Pane active();

        /** !isFinishing() && !isDestroyed() */
        boolean alive();

        void toast(String s);

        void exitSelection(Pane p);

        void updateHeader();

        void updatePasteButton();

        void setActive(Pane p);

        // ---- 下面三个属于压缩包控制器（刀3 搬走），这里先经过宿主转发 ----
        void closeArchiveQuiet(Pane p);

        void showArchiveDir(Pane p);

        void archGoUp(Pane p);

        /** 宿主的主线程 Handler（原来是 taskUi，控制器不自己 new，避免线程错位/泄漏） */
        android.os.Handler ui();
    }

    private final Host host;

    public DirectoryController(Host host) {
        this.host = host;
    }

    /** v1.97.0：单次读目录的超时上限（超过即按失败收尾，见 loadDir 里的看门狗） */
    private static final long LOAD_TIMEOUT_MS = 20000L;

    public void loadDir(Pane p, File dir) {
        loadDir(p, dir, true);
    }

    /**
     * push=false 用于历史导航（不写入历史栈）。
     *
     * @return true = 已发起/正在进行的读取（调用方不必自己收下拉圈），
     *         false = 参数校验没过或被去重跳过（没有收尾回调会来）
     */
    public boolean loadDir(Pane p, File dir, boolean push) {
        // v1.42.0：root 设备上 stat 可能被拒（ls 才需要 su），放行交给 DirReader 的 root 回退
        if (dir == null || (!dir.isDirectory() && !RootUtil.isRootAvailable())) {
            Toast.makeText(host.ctx(), "无法访问: " + dir, Toast.LENGTH_SHORT).show();
            return false;
        }
        // v1.98.0：同一目录、上一次还在读 → 直接忽略。
        // 双击/连点会让同一个目录读两遍，两遍结果都会回主线程（代次只保住最新那份，白白多读一次）；
        // 大目录或 su 回退时这一次多读就是几百毫秒到几秒的额外占用。
        if (p.dir != null && p.dir.equals(dir) && p.adapter.isLoading()) return true;
        if (p.adapter != null && p.adapter.selectionMode) host.exitSelection(p);
        host.closeArchiveQuiet(p);
        p.dir = dir;
        if (push) {
            // v1.97.0：同一个目录不重复入栈。新建文件/新建目录/退出压缩包浏览之后都是
            // loadDir(p, p.dir)（路径没变），原来每次都往历史栈里塞一条重复路径，
            // ‹ › 要按好几下才退得出去，看着像「返回键失灵」。
            String path = dir.getPath();
            boolean same = p.hIndex >= 0 && p.hIndex < p.history.size()
                    && p.history.get(p.hIndex).equals(path);
            if (!same) {
                while (p.history.size() > p.hIndex + 1) p.history.remove(p.history.size() - 1);
                p.history.add(path);
                p.hIndex = p.history.size() - 1;
            }
        }
        // v1.96.0：路径立刻切（用户马上看到自己在哪），条目在后台读完后一次性替换。
        // 原来 listFiles + 排序 + 逐项 lstat 都在主线程，几千项的目录或 su ls 回退会卡住甚至 ANR。
        final int gen = ++p.dirGen;
        p.adapter.setLoading(true);
        // 顶栏路径立刻切（慢目录 root 回退要几百毫秒到几秒，路径不动用户会以为没点中）；
        // v1.99.0（O-06）：计数这一栏此时显示「读取中…」，不再沿用上一个目录的数字
        host.updateHeader();
        // 换目录后网格要回到顶部：数据换了一批，保留旧滚动位置会落在中间
        if (p.grid != null) p.grid.setSelection(0);
        final int sort = p.adapter.sortMode;
        final boolean hidden = p.adapter.showHidden;
        final File root = p.adapter.rootDir;
        Bg.io(new Runnable() {
            public void run() {
                // v1.99.0（O-03）：真正开跑之前先看代次。IO 池只有 3 条线程，用户连点三个
                // 目录就会排出三条任务；不在入口拦一道的话，已经作废的两条仍会跑完整的
                // stat / su ls（慢目录各几百毫秒到几秒），把线程占满让最新那条排队 ——
                // 用户看到的就是「越点越慢」。排在后面的作废任务直接退出，一个 syscall 都不发。
                if (gen != p.dirGen) return;
                // v1.97.0：readSafe 不抛异常 —— 失败会带着 error 回来，走同一条收尾路径。
                // 原来这里直接 read：任务体一抛，下面的 Bg.ui 就不执行了，
                // loading 卡在 true，而读取期间点击是被拦的 → 该窗格永久点不动。
                final DirReader.Result r = DirReader.readSafe(dir, sort, hidden, root);
                if (gen != p.dirGen) return;      // 读的过程里用户又切了，连回主线程都省了
                Bg.ui(new Runnable() {
                    public void run() {
                        applyDirResult(p, gen, r);
                    }
                });
            }
        }, new Bg.Fail() {
            public void onFail(Throwable t) {
                // 兜底：连 readSafe 都没兜住的（OOM 等）也要把 loading 收掉
                applyDirResult(p, gen, null);
            }
        });
        // v1.97.0：超时自愈。su ls 之类的外部进程可能挂住（RootUtil 自己 8s 超时，
        // 但排队 + 多级回退叠加起来仍可能很久），一旦超过阈值就当作失败收尾，
        // 让用户能重新点，而不是守着一个永远转圈、点不动的窗格。
        host.ui().postDelayed(new Runnable() {
            public void run() {
                if (gen != p.dirGen || !p.adapter.isLoading()) return;
                p.dirGen++;                  // 让在途结果作废，免得回来又把列表盖一次
                // v1.99.0（O-06）：超时也按失败收尾 —— 只把 loading 收掉、留着上一个目录的
                // 条目的话，路径已经是新目录，用户点到的却是旧目录里的文件（删除/复制全错位）。
                p.adapter.setResult(null);
                p.dirCount = 0;
                p.fileCount = 0;
                host.updateHeader();
                if (p.pull != null) p.pull.finishRefresh();
                if (host.alive()) host.toast("读取超时: " + dir.getName());
            }
        }, LOAD_TIMEOUT_MS);
        return true;
    }

    /** 读目录的收尾（成功/失败同一入口）：换数据、补计数、收下拉刷新、失败给可见提示 */
    private void applyDirResult(Pane p, int gen, DirReader.Result r) {
        // 期间又切了目录 / 界面已销毁：这份结果作废（否则会把旧目录的内容盖上去）
        if (gen != p.dirGen || !host.alive()) return;
        if (p.pull != null) p.pull.finishRefresh();
        if (r == null || r.error != null) {
            // 失败：清空列表并明确告知。留着上一个目录的条目更糟 —— 用户会以为读到了新目录，
            // 接着在上面做删除/复制，操作的全是别的目录里的东西。
            p.adapter.setResult(null);
            p.dirCount = 0;
            p.fileCount = 0;
            host.updateHeader();
            host.toast("读取失败: " + (r == null ? "未知错误" : r.error));
            return;
        }
        p.adapter.setResult(r);
        p.dirCount = r.dirs;
        p.fileCount = r.files;
        host.updateHeader();
        // v1.98.0：空目录给一句提示。读取中显示的是「读取中…」占位行，读完变空白，
        // 用户分不清是「空目录」还是「还没读出来」—— 尤其在慢目录上刚看过转圈之后。
        boolean onlyUp = true;
        for (FileItem it : r.items) {
            if (!it.upEntry) { onlyUp = false; break; }
        }
        if (onlyUp) host.toast("此文件夹为空");
    }

    /** 两栏一起刷新（不进历史栈） */
    public void refreshAll() {
        for (Pane p : host.panes()) {
            if (p.archiveFile != null) host.showArchiveDir(p);
            // v1.79.0：push=false —— 刷新不是「进入新目录」，否则每刷一次就往历史栈里塞一条重复路径，
            // ‹ › 要按很多下才退得出去
            else if (p.dir != null) loadDir(p, p.dir, false);
        }
        host.updateHeader();
    }

    /**
     * v1.79.0 下拉刷新：重读当前目录（压缩包浏览中则重列压缩包当前层）。
     *
     * <p>v1.99.0（O-01）：转圈的收尾交给真正读完的那一方。原来这里 {@code postDelayed(220)}
     * <b>无条件</b> {@code finishRefresh()}，而读目录早已是异步的（大目录 / su 回退几百毫秒到几秒）
     * —— 圈在 220ms 就停了，列表还是上一个目录的，点击又被 {@code isLoading()} 拦着，
     * 用户看到的是「刷新完了但点不动」，很自然就会反复下拉。现在只有没走异步读取的分支
     * （压缩包重列失败 / dir 为空 / 路径校验没过）才用 220ms 兜底，其余由
     * {@code applyDirResult} / 20s 看门狗 / 失败回调统一收。</p>
     */
    public void refreshPanel(final Pane p) {
        if (p == null || p.pull == null || p.adapter == null) return;
        p.pull.setRefreshing(true);
        final int pg = ++p.pullGen;
        p.list.postDelayed(new Runnable() {
            public void run() {
                if (p.adapter.selectionMode) host.exitSelection(p);
                boolean asyncRead;
                if (p.archiveFile != null) {
                    // 压缩包重列也是异步的（showArchiveDir 内部起线程 listChildren，
                    // 回主线程 setItems 后自己 finishRefresh），这里同样不能提前收圈；
                    // 归档被关掉/列目录抛异常时那条路径可能不回来，挂一个长兜底
                    host.showArchiveDir(p);
                    asyncRead = true;
                    finishPullLater(p, pg, 8000);
                } else if (p.dir != null) {
                    asyncRead = loadDir(p, p.dir, false);
                } else {
                    asyncRead = false;
                }
                host.updateHeader();
                host.updatePasteButton();      // 目录内容变了，剪贴板里失效的源要重新剔除
                // 220ms 只是让转圈至少画出一帧，不是「读取完成」的信号
                if (!asyncRead) finishPullLater(p, pg, 220);
            }
        }, 90);
    }

    /** 延迟收下拉圈；调用前已经在转才收，避免把下一次刷新的圈提前收掉 */
    private void finishPullLater(final Pane p, final int pg, long delayMs) {
        if (p == null || p.pull == null) return;
        p.pull.postDelayed(new Runnable() {
            public void run() {
                // 代次不一致 = 这期间用户又下拉了一次，别把新那次的圈收掉
                if (p.pull != null && p.pull.isRefreshing() && pg == p.pullGen)
                    p.pull.finishRefresh();
            }
        }, delayMs);
    }

    /**
     * v1.96.0：进程被系统回收后重建时，回到用户原来所在的两个目录。
     *
     * <p>以前 onCreate 一律 {@code loadDir(内部存储根)}，表现是「切出去几分钟再回来，
     * 发现回到了起点」。这里不是转屏重建（那条路被 configChanges 接管了），只有进程真被杀才会走到。</p>
     *
     * <p>压缩包内浏览不恢复：重开一次归档要解目录、可能几秒，恢复个「上次在看 zip」不如让人重进。</p>
     */
    public void restorePanesOrLoad(Bundle b) {
        File def = Environment.getExternalStorageDirectory();
        Pane[] ps = host.panes();
        String[] dk = {"left_dir", "right_dir"};
        String[] hk = {"left_hist", "right_hist"};
        String[] ik = {"left_hidx", "right_hidx"};
        for (int i = 0; i < 2; i++) {
            File dir = def;
            if (b != null) {
                String saved = b.getString(dk[i]);
                if (saved != null) {
                    File f = new File(saved);
                    // 目录可能在这期间没了（U 盘拔出 / 被别的应用清掉），退回默认根
                    if (f.isDirectory()) dir = f;
                }
            }
            loadDir(ps[i], dir);
            if (b == null) continue;
            java.util.ArrayList<String> hist = b.getStringArrayList(hk[i]);
            if (hist != null && !hist.isEmpty()) {
                // loadDir 已经写了一条「当前目录」，这里用存下来的历史整段覆盖
                ps[i].history.clear();
                ps[i].history.addAll(hist);
                int hi = b.getInt(ik[i], hist.size() - 1);
                ps[i].hIndex = Math.max(0, Math.min(hi, hist.size() - 1));
            }
        }
        if (b != null && b.getBoolean("right_active", false)) host.setActive(ps[1]);
        else ps[0].active = true;
    }

    /** ‹ › 历史导航：沿活动栏自己的历史栈前进/后退；压缩包浏览中先逐级退回 */
    public void jumpHistory(int delta) {
        Pane p = host.active();
        if (p.archiveFile != null) {          // 压缩包浏览中 → 先退压缩包（根则退出）
            host.archGoUp(p);
            return;
        }
        int ni = p.hIndex + delta;
        if (ni < 0 || ni >= p.history.size()) {
            host.toast(delta < 0 ? "没有更早的历史" : "没有更新的历史");
            return;
        }
        p.hIndex = ni;
        File dir = new File(p.history.get(ni));
        if (dir.isDirectory()) {
            loadDir(p, dir, false);
        } else {
            host.toast("目录已不存在");
            p.history.remove(ni);
            p.hIndex = Math.min(p.hIndex, p.history.size() - 1);
        }
    }
}
