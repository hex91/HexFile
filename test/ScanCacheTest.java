import com.hex91.file.core.ScanCache;

import java.io.File;

/**
 * v1.73.2 目录扫描缓存：TTL 过期、LRU 淘汰、祖先链失效、路径规范化。
 * 用假时钟验证过期逻辑，不用真的等 60 秒。
 */
public class ScanCacheTest {
    static int n = 0;
    static void ok(String w) { n++; System.out.println("  ok: " + w); }

    static void check(boolean b, String w) {
        if (!b) throw new AssertionError(w);
        ok(w);
    }

    public static void main(String[] a) throws Exception {
        File base = new File("out/scancache");
        base.mkdirs();
        File dirA = new File(base, "a");
        File dirB = new File(dirA, "b");
        File dirC = new File(dirB, "c");
        File dirX = new File(base, "x");        // 兄弟目录，不应被连带失效
        dirC.mkdirs();
        dirX.mkdirs();

        // 1. 基本命中 / 未命中
        final long[] now = new long[]{1000L};
        ScanCache.Clock clock = new ScanCache.Clock() { public long now() { return now[0]; } };
        ScanCache<String> c = new ScanCache<String>(10, 1000L, clock);

        c.put(dirA, "A");
        check("A".equals(c.get(dirA)), "命中 A");
        check(c.get(dirB) == null, "未缓存的 B 返回 null");

        // 2. TTL：ttl=1000，恰好 1000ms 时仍未过期，超过才过期
        now[0] = 2000L;
        check("A".equals(c.get(dirA)), "TTL 边界（差 1000ms）仍有效");
        now[0] = 2100L;
        check(c.get(dirA) == null, "超过 TTL 后失效");
        check(c.get(dirA) == null && c.size() == 0, "过期条目已被清除");

        // 3. ageOf
        now[0] = 5000L;
        c.put(dirA, "A2");
        now[0] = 5600L;
        check(c.ageOf(dirA) == 600L, "ageOf=600ms");
        check(c.ageOf(dirB) == -1L, "ageOf 未命中返回 -1");

        // 4. LRU：max=3，访问过的条目不会被先淘汰
        ScanCache<String> lru = new ScanCache<String>(3, 60000L, clock);
        lru.put(dirA, "1");
        lru.put(dirB, "2");
        lru.put(dirC, "3");
        lru.get(dirA);                       // A 变成最近使用
        lru.put(dirX, "4");                  // 淘汰最久未用的 B
        check(lru.get(dirB) == null, "LRU 淘汰最久未用的 B");
        check("1".equals(lru.get(dirA)), "刚访问过的 A 保留");
        check("4".equals(lru.get(dirX)), "新写入的 X 保留");
        check(lru.size() == 3, "容量不超过 3");

        // 5. 祖先失效：改文件会改变每一级的总量
        ScanCache<String> inv = new ScanCache<String>(10, 60000L, clock);
        inv.put(dirA, "A");
        inv.put(dirB, "B");
        inv.put(dirC, "C");
        inv.put(dirX, "X");
        inv.invalidate(dirC);
        check(inv.get(dirC) == null, "自身已失效");
        check(inv.get(dirB) == null, "父级已失效");
        check(inv.get(dirA) == null, "祖父级已失效");
        check("X".equals(inv.get(dirX)), "兄弟目录不受影响");

        // 6. 路径规范化：同一目录的不同写法命中同一条目
        check(ScanCache.keyOf(new File(base, "a/./b")).equals(ScanCache.keyOf(dirB)),
                "带 ./ 的写法规范化到同一键");
        check(ScanCache.keyOf(null).isEmpty(), "null 键为空串");

        // 7. clear
        inv.clear();
        check(inv.size() == 0, "clear 后为空");

        System.out.println("SCANCACHE PASS (" + n + ")");
    }
}
