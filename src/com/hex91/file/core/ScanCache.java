package com.hex91.file;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目录扫描结果缓存（v1.73.2）。
 *
 * <p>用途：存储分析里下钻再返回时，直接复用上一次的统计结果，不重复遍历子树。
 * 大目录动辄上千个文件，一次遍历几十到几百毫秒，来回按返回会明显「卡一下又扫一遍」。
 *
 * <p>三条约束：
 * <ul>
 *   <li><b>时效</b>：结果超过 {@code ttlMs} 视为过期（默认 60 秒）。别的应用可能在
 *       后台改了文件，纯内存缓存不能永久可信。</li>
 *   <li><b>容量</b>：按路径计数，超出 {@code max} 后按访问顺序淘汰最久未用的（LRU），
 *       避免一路下钻把整棵树的统计结果都攒在内存里。</li>
 *   <li><b>失效</b>：文件增删会改变本目录<b>及所有祖先</b>的总量，
 *       因此 {@link #invalidate(File)} 沿父链逐级清除，否则返回上一级还会看到旧数字。</li>
 * </ul>
 *
 * <p>纯 Java 实现（只依赖 {@link File}），可在桌面 JVM 直接单测；时间源可注入，
 * 便于验证过期逻辑而不用真的等 60 秒。
 */
public final class ScanCache<V> {

    public static final int DEFAULT_MAX = 40;
    public static final long DEFAULT_TTL_MS = 60_000L;

    /** 时间源，便于测试注入假时钟 */
    public interface Clock {
        long now();
    }

    private static final class Entry<V> {
        final V value;
        final long at;
        Entry(V value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private final int max;
    private final long ttlMs;
    private final Clock clock;
    private final LinkedHashMap<String, Entry<V>> map;

    public ScanCache() {
        this(DEFAULT_MAX, DEFAULT_TTL_MS, null);
    }

    public ScanCache(int max, long ttlMs, Clock clock) {
        this.max = Math.max(1, max);
        this.ttlMs = ttlMs;
        this.clock = clock != null ? clock : new Clock() {
            public long now() { return System.currentTimeMillis(); }
        };
        // accessOrder=true：get 也会把条目移到末尾，从而实现 LRU
        // 注意：这里必须写 ScanCache.Entry，直接写 Entry 会被解析成 LinkedHashMap 继承来的 Map.Entry（2 个类型参数）
        this.map = new LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ScanCache.Entry<V>> eldest) {
                return size() > ScanCache.this.max;
            }
        };
    }

    /** 规范化路径作为键：不同写法（带尾斜杠、符号链接）指向同一目录时应命中同一条目 */
    public static String keyOf(File f) {
        if (f == null) return "";
        try {
            return f.getCanonicalPath();
        } catch (Throwable t) {
            return f.getAbsolutePath();
        }
    }

    public synchronized void put(File dir, V value) {
        if (dir == null) return;
        map.put(keyOf(dir), new Entry<V>(value, clock.now()));
    }

    /** 命中且未过期返回结果，否则返回 null（过期条目顺手清掉） */
    public synchronized V get(File dir) {
        String key = keyOf(dir);
        Entry<V> e = map.get(key);
        if (e == null) return null;
        if (clock.now() - e.at > ttlMs) {
            map.remove(key);
            return null;
        }
        return e.value;
    }

    /** 该目录结果的年龄（毫秒）；不存在返回 -1 */
    public synchronized long ageOf(File dir) {
        Entry<V> e = map.get(keyOf(dir));
        return e == null ? -1L : Math.max(0L, clock.now() - e.at);
    }

    /**
     * 作废 dir 自身与其所有祖先的缓存：任一文件增删都会改变各级的「总量」。
     * 父链最多走 64 层，防止异常路径（脏数据造成的环）把线程拖死。
     */
    public synchronized void invalidate(File dir) {
        File f = dir;
        int guard = 0;
        while (f != null && guard++ < 64) {
            map.remove(keyOf(f));
            f = f.getParentFile();
        }
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }
}
