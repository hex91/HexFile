package com.hex91.file.net;

import java.io.IOException;

/**
 * 网络存储链路的可预期失败（v2.94.0，F-14/net 去中文化）。
 *
 * <p>起因：net 包各云盘/协议类到处 {@code throw new IOException("中文提示...")}，
 * 而界面文案要出海。把 Context / 语言塞进纯 Java 层不现实，同一句话在 net 与 ui
 * 两处维护也迟早漂移。</p>
 *
 * <p>现在的分工（同包 {@link HlsError} 的成熟套路）：这里只给「错误码 + 数据参数」，
 * 参数是路径、HTTP 状态、服务端 Message 这类**数据**；句子统一由
 * {@code ui/NetText} 按码取字符串资源。{@link #getMessage()} 仍给一句全 ASCII
 * 诊断串（日志 / 单测用），<b>不该</b>直接出现在界面上。</p>
 *
 * <p>仍然 {@code extends IOException}：调用方原有的 {@code catch (IOException)}
 * 全部不变。</p>
 */
public class NetFsError extends IOException {

    public enum Code {
        /** 对象存储地址为空 */
        EMPTY_ENDPOINT,
        /** 存储桶为空 */
        EMPTY_BUCKET,
        /** AccessKey 为空 */
        EMPTY_AK,
        /** SecretKey 为空 */
        EMPTY_SK,
        /** 地址无法解析（参数：原地址） */
        BAD_ENDPOINT,
        /** 协议操作 HTTP 失败（参数：op, path, httpCode, hintCode, detail） */
        OP_FAIL,
        /** 服务端忽略 Range 无法续传 */
        RANGE_IGNORED,
        /** 缺少上传临时目录 */
        TMP_DIR_MISSING,
        /** 无法创建上传临时目录（参数：目录） */
        TMP_DIR_FAIL,
        /** 单文件超过实现上限 */
        TOO_LARGE,
        /** 重命名失败且原因在服务端 body（参数：detail） */
        RENAME_DETAIL,
        /** 服务端响应解析失败（参数：底层解析错误） */
        RESP_PARSE,
        /** SHA-256 算法不可用 */
        SHA_UNAVAILABLE
    }

    /** OP_FAIL 的第 0 个参数：操作码 */
    public static final int OP_LIST = 1, OP_READ = 2, OP_RESUME = 3,
            OP_UPLOAD = 4, OP_MKDIR = 5, OP_DELETE = 6, OP_RENAME = 7;

    /** OP_FAIL 的第 3 个参数：HTTP 状态码提示码 */
    public static final int HINT_NONE = 0,
            HINT_S3_REGION = 1, HINT_S3_SIGN = 2, HINT_S3_NOTFOUND = 3;

    public final Code code;
    private final Object[] args;

    public NetFsError(Code code, Object... args) {
        super(diagnose(code, args));
        this.code = code;
        this.args = args;
    }

    /** 带底层原因（保留「地址无法解析」这类真实 cause） */
    public NetFsError(Code code, Throwable cause, Object... args) {
        super(diagnose(code, args), cause);
        this.code = code;
        this.args = args;
    }

    public Object arg(int i) {
        return args != null && i >= 0 && i < args.length ? args[i] : null;
    }

    public String str(int i) {
        Object o = arg(i);
        return o == null ? "" : String.valueOf(o);
    }

    public int num(int i) {
        Object o = arg(i);
        return o instanceof Number ? ((Number) o).intValue() : -1;
    }

    // ---- 静态工厂：调用点不必写 NetFsError.Code.XXX ----
    public static NetFsError emptyEndpoint() { return new NetFsError(Code.EMPTY_ENDPOINT); }
    public static NetFsError emptyBucket()   { return new NetFsError(Code.EMPTY_BUCKET); }
    public static NetFsError emptyAk()       { return new NetFsError(Code.EMPTY_AK); }
    public static NetFsError emptySk()       { return new NetFsError(Code.EMPTY_SK); }
    public static NetFsError badEndpoint(String ep, Throwable e) {
        return new NetFsError(Code.BAD_ENDPOINT, e, ep);
    }
    public static NetFsError opFail(int op, String path, int http, int hint, String detail) {
        return new NetFsError(Code.OP_FAIL, op, path, http, hint, detail);
    }
    public static NetFsError rangeIgnored()  { return new NetFsError(Code.RANGE_IGNORED); }
    public static NetFsError tmpDirMissing() { return new NetFsError(Code.TMP_DIR_MISSING); }
    public static NetFsError tmpDirFail(String dir) { return new NetFsError(Code.TMP_DIR_FAIL, dir); }
    public static NetFsError tooLarge()      { return new NetFsError(Code.TOO_LARGE); }
    public static NetFsError renameDetail(String detail) {
        return new NetFsError(Code.RENAME_DETAIL, detail);
    }
    public static NetFsError respParse(String cause, Throwable e) {
        return new NetFsError(Code.RESP_PARSE, e, cause);
    }
    public static NetFsError shaUnavailable(Throwable e) {
        return new NetFsError(Code.SHA_UNAVAILABLE, e);
    }

    /** 诊断串：{@code NETFS CODE: arg1 arg2}，全 ASCII，只给日志和单测 */
    private static String diagnose(Code code, Object[] args) {
        if (args == null || args.length == 0) return "NETFS " + code;
        StringBuilder sb = new StringBuilder("NETFS ").append(code).append(':');
        for (Object a : args) sb.append(' ').append(a);
        return sb.toString();
    }
}
