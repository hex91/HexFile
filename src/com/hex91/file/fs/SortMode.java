package com.hex91.file.fs;

/**
 * v2.4.0（O-17）：目录排序方式。
 *
 * <p><b>为什么单独成一个类</b>：{@link DirReader} 只负责读目录，却要拿
 * {@code FileAdapter.SORT_SIZE} 这个常量来判断怎么排 —— {@code fs} 因此反向依赖了
 * {@code ui}，而 {@code FileAdapter} 是 Android 的 Adapter，桌面单测连类都加载不了
 * （{@code DirReaderTest} 为了三个 int 常量被迫 import 整个 ui 包）。</p>
 *
 * <p>数值沿用历史值（0/1/2）：它们被写进 {@code SharedPreferences}，改了会让老用户的
 * 排序偏好悄悄变成另一种。{@code FileAdapter.SORT_*} 保留为别名，调用点不用动。</p>
 */
public final class SortMode {

    /** 按名称（目录恒在前） */
    public static final int NAME = 0;
    /** 按大小（目录恒在前） */
    public static final int SIZE = 1;
    /** 按修改时间，新的在前（目录恒在前） */
    public static final int TIME = 2;

    private SortMode() {}
}
