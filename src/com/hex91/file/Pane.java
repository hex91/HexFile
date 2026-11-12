package com.hex91.file;

import java.io.File;

/**
 * 单窗格（左右各一个）的 UI 引用 + 浏览状态。
 *
 * <p>v2.0.0（O-15 刀1）：从 {@code MainActivity.Panel} 原样搬出，字段集合一个没动。
 * 提成顶层类的唯一目的是让 {@link DirectoryController} / {@link ArchiveController} /
 * {@link TaskUiController} 能把它当参数类型用 —— 内部类只能由宿主自己引用，
 * 那样拆出来的控制器等于没拆。</p>
 *
 * <p>包内可见（不是 public）：只有 com.hex91.file 里的类需要它，别让外部包扩散引用。
 * 字段同样保持包内可见，控制器与宿主直接读写，不额外造 getter/setter 噪音。</p>
 */
final class Pane {
    android.widget.ListView list;
    /** v1.80.0：网格/大图视图（与 list 共用同一份数据，同一时刻只有一个可见） */
    android.widget.GridView grid;
    FileGridAdapter gridAdapter;
    /** v1.79.0：列表外层的下拉刷新容器 */
    PullRefreshLayout pull;
    FileAdapter adapter;
    File dir;
    boolean active;

    // ---------- 窗格内嵌压缩包浏览（v1.14） ----------
    File archiveFile;                 // 非空 = 该窗格正在浏览压缩包
    ArchiveUtil.Archive archive;
    String archPrefix = "";           // 压缩包内当前路径前缀

    /** 目录历史栈（‹ › 前进后退用） */
    final java.util.List<String> history = new java.util.ArrayList<String>();
    int hIndex = -1;

    /**
     * v1.96.0：目录读取代次。读目录移到后台线程后，用户可能在结果回来之前
     * 又切了别的目录，回来时才发现手里这份是上一个目录的 —— 每次发起读取 +1，
     * 结果回来时代次对不上就整份丢掉。
     *
     * <p>v1.99.0（O-03）：加 volatile。后台任务在 <b>开始执行前</b> 也要查一次代次
     * （见 loadDir 里的 Bg.io），否则连点几下会排出一串已经作废的读取，
     * 每条都要跑完整的 stat / su ls，把 3 条 IO 线程占满，新目录反而更慢。
     * 主线程写、工作线程读，没有 volatile 时这个判断可能读到旧值。</p>
     */
    volatile int dirGen;
    /** v1.99.0（O-01）：下拉刷新的代次，延迟收圈时用来确认「收的是自己那次」 */
    int pullGen;
    /** v1.96.0：最近一次读取统计（顶栏用，避免每次 O(n) 遍历适配器） */
    int dirCount, fileCount;
}
