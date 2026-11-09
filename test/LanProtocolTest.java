import com.hex91.file.LanProtocol;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v1.74.0 局域网快传的协议回归。
 *
 * <p>发现报文是**广播端口上的不可信输入**：同网段可能有别的应用乱发、可能有半截包、
 * 也可能有人故意构造 {@code ../../} 文件名。这些解析全部在 {@link LanProtocol} 里完成，
 * 所以只要能在这里覆盖住，真机上就只剩「网络能不能通」这一个变量。</p>
 */
public class LanProtocolTest {

    static int pass = 0, fail = 0;

    static void eq(Object expect, Object got, String msg) {
        boolean ok = expect == null ? got == null : expect.equals(got);
        if (ok) {
            pass++;
            System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    static void isNull(Object got, String msg) {
        if (got == null) {
            pass++;
            System.out.println("PASS  " + msg + " → null");
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 null 实际 " + got);
        }
    }

    static void isTrue(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    public static void main(String[] args) {
        // ---------------- 编解码
        String msg = LanProtocol.encode(LanProtocol.TYPE_PROBE, "abc123", 43210, "Xiaomi 13");
        LanProtocol.Device d = LanProtocol.parse(msg, "192.168.1.7", 1000L);
        isTrue(d != null, "基本报文可解析");
        if (d != null) {
            eq("abc123", d.id, "ID 保留");
            eq("Xiaomi 13", d.name, "设备名（含空格）保留");
            eq(43210, d.port, "端口保留");
            eq("192.168.1.7", d.host, "来源 IP 来自 socket 而非报文");
            eq(LanProtocol.TYPE_PROBE, d.kind, "类型");
        }

        String cn = LanProtocol.encode(LanProtocol.TYPE_HERE, "id2", 5000, "华为 | 中文 名称");
        LanProtocol.Device dc = LanProtocol.parse(cn, "10.0.0.2", 0L);
        isTrue(dc != null, "含中文与竖线的名字可解析");
        if (dc != null) eq("华为 | 中文 名称", dc.name, "竖线/中文经编码后不破坏结构");

        eq("HEXFILE_LAN/1|P|id|1234|a%20b", 
                LanProtocol.encode("P", "id", 1234, "a b"), "空格编码为 %20");
        eq("a b", LanProtocol.percentDecode("a%20b"), "%20 解码");
        eq("a+b", LanProtocol.percentDecode("a%2Bb"), "编码过的加号能还原");
        eq("a b", LanProtocol.percentDecode("a+b"), "裸加号按空格");
        eq("A-Za-z0-9._~", LanProtocol.percentEncode("A-Za-z0-9._~"), "unreserved 原样保留");

        // ---------------- 垃圾数据免疫
        isNull(LanProtocol.parse(null, "1.1.1.1", 0L), "null 报文");
        isNull(LanProtocol.parse("HELLO", "1.1.1.1", 0L), "非本协议");
        isNull(LanProtocol.parse("HEXFILE_LAN/1|X|id|1234|n", "1.1.1.1", 0L), "未知类型");
        isNull(LanProtocol.parse("HEXFILE_LAN/1|P|id|0|n", "1.1.1.1", 0L), "端口 0");
        isNull(LanProtocol.parse("HEXFILE_LAN/1|P|id|70000|n", "1.1.1.1", 0L), "端口越界");
        isNull(LanProtocol.parse("HEXFILE_LAN/1|P|id|abc|n", "1.1.1.1", 0L), "端口非数字");
        isNull(LanProtocol.parse("HEXFILE_LAN/1|P||1234|n", "1.1.1.1", 0L), "空 ID");
        isNull(LanProtocol.parse("HEXFILE_LAN/1|P|id|1234", "1.1.1.1", 0L), "字段不足");
        eq("", LanProtocol.kindOf("garbage"), "kindOf 垃圾包");

        // ---------------- 超时
        LanProtocol.Device old = new LanProtocol.Device("id", "n", "1.1.1.1", 1, 0L, "H");
        isTrue(old.isStale(LanProtocol.TTL_MS + 1), "超过 TTL 视为离线");
        isTrue(!old.isStale(LanProtocol.TTL_MS - 1), "TTL 内仍在线");
        eq(500L, old.refresh(500L).lastSeen, "refresh 更新时间戳");

        // ---------------- 文件名净化（不可信输入）
        eq("passwd", LanProtocol.safeFileName("../../etc/passwd"), "目录穿越只取文件名");
        eq("c_d_e_.txt", LanProtocol.safeFileName("a\\b\\c:d*e?.txt"), "非法字符替换为下划线");
        eq("file.bin", LanProtocol.safeFileName(""), "空名兜底");
        eq("file.bin", LanProtocol.safeFileName(null), "null 兜底");
        eq("file.bin", LanProtocol.safeFileName(".."), "纯点名拒绝");
        eq("file.bin", LanProtocol.safeFileName("."), "单点拒绝");
        eq("正常 文件.txt", LanProtocol.safeFileName("  正常 文件.txt  "), "首尾空白裁掉、中文保留");

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) longName.append('a');
        longName.append(".apk");
        String safeLong = LanProtocol.safeFileName(longName.toString());
        isTrue(safeLong.length() <= LanProtocol.MAX_FILE_NAME, "超长名截断（长度 " + safeLong.length() + "）");
        isTrue(safeLong.endsWith(".apk"), "截断后保留扩展名");

        // ---------------- 重名
        Set<String> exist = new HashSet<String>();
        exist.add("a.txt");
        eq("a (1).txt", LanProtocol.uniqueName("a.txt", exist), "重名追加 (1)");
        exist.add("a (1).txt");
        eq("a (2).txt", LanProtocol.uniqueName("a.txt", exist), "再次重名追加 (2)");
        eq("README (1)", LanProtocol.uniqueName("README", new HashSet<String>() {{
            add("README");
        }}), "无扩展名也能去重");
        eq("b.txt", LanProtocol.uniqueName("b.txt", exist), "不重名原样返回");
        eq("b.txt", LanProtocol.uniqueName("b.txt", null), "null 集合视为无冲突");

        // ---------------- query 解析
        Map<String, String> q = LanProtocol.parseQuery("/lan/send?name=%E4%B8%AD%E6%96%87.txt&size=1024");
        eq("中文.txt", q.get("name"), "query 里的中文文件名");
        eq("1024", q.get("size"), "query 里的 size");
        Map<String, String> q2 = LanProtocol.parseQuery("name=a%20b.apk&size=7");
        eq("a b.apk", q2.get("name"), "不带问号的 query 也能解析");
        eq(0, LanProtocol.parseQuery(null).size(), "null query 返回空表");

        // ---------------- ID 规范化
        eq("id123", LanProtocol.normalizeId("id|12 3"), "ID 去掉竖线与空格");
        eq("", LanProtocol.normalizeId(null), "null ID → 空串");

        // ---------------- 接收列表（v1.75.0）
        java.io.File tmp = new java.io.File("build/lanchek/rectest_" + System.nanoTime());
        tmp.mkdirs();
        try {
            write(tmp, "old.apk", 10, 1000L);
            write(tmp, "new.apk", 20, 5000L);
            write(tmp, "mid.apk", 15, 3000L);
            write(tmp, "downloading.zip.part", 5, 9000L);   // 半截临时文件，不该出现在列表
            write(tmp, ".hidden", 1, 9999L);                 // 隐藏文件，同样跳过
            new java.io.File(tmp, "subdir").mkdirs();        // 子目录不该混进来
            java.util.List<java.io.File> got = LanProtocol.receivedFiles(tmp);
            eq(3, got.size(), "接收列表：过滤 .part / 隐藏文件 / 子目录");
            eq("new.apk", got.get(0).getName(), "接收列表按修改时间倒序（第一项最新）");
            eq("old.apk", got.get(2).getName(), "接收列表按修改时间倒序（末项最旧）");
            for (int i = 0; i < got.size(); i++)
                isTrue(!got.get(i).getName().endsWith(".part"), "列表不含 .part：" + got.get(i).getName());
        } finally {
            deleteTree(tmp);
        }
        eq(0, LanProtocol.receivedFiles(null).size(), "null 目录返回空表");
        eq(0, LanProtocol.receivedFiles(new java.io.File(tmp, "not-exist")).size(),
                "不存在的目录返回空表");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void write(java.io.File dir, String name, int bytes, long mtime) {
        java.io.File f = new java.io.File(dir, name);
        try {
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            try {
                os.write(new byte[bytes]);
            } finally {
                os.close();
            }
            f.setLastModified(mtime);
        } catch (Throwable t) {
            System.out.println("FAIL  准备测试文件 " + name + ": " + t);
            fail++;
        }
    }

    static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] fs = f.listFiles();
        if (fs != null) for (java.io.File c : fs) deleteTree(c);
        f.delete();
    }
}
