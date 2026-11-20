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
        SHA_UNAVAILABLE,

        // ---- v2.95.0 云盘（阿里云盘 / OneDrive）通用码 ----
        /** 尚未授权（参数：云盘名，如「阿里云盘」「OneDrive」） */
        NOT_AUTHED,
        /** 授权已丢失（参数：云盘名） */
        AUTH_LOST,
        /** 授权已失效（参数：云盘名、服务端 code） */
        AUTH_EXPIRED,
        /** 云盘 API 调用失败（参数：path、code、message、hintCode） */
        API_FAIL,
        /** 服务端 HTTP 失败（参数：path、httpCode、hintCode） */
        HTTP_FAIL,
        /** 服务端 HTTP 失败带详情（参数：op、httpCode、hintCode、detail） */
        HTTP_DETAIL_FAIL,
        /** 找不到目标（参数：路径） */
        PATH_NOT_FOUND,
        /** 取不到下载地址（参数：路径） */
        NO_DOWNLOAD_URL,
        /** 不能对根目录操作（参数：动作「下载」「操作」） */
        ROOT_NOT_ALLOWED,
        /** 上传文件名非法（参数：CloudPath.NameError 的 code + arg） */
        BAD_NAME,
        /** 通用「X 不能为空」（参数：字段名，如 Client ID / refresh_token） */
        FIELD_REQUIRED,
        /** 云盘空间不足 */
        QUOTA_EXHAUSTED,
        /** 请求过于频繁 */
        TOO_MANY_REQUESTS,
        /** 文件类型不允许上传 */
        FILE_TYPE_FORBIDDEN,
        /** 名称不合法或重名 */
        NAME_INVALID,
        /** 取不到云盘 drive_id */
        NO_DRIVE_ID,
        /** 服务端没返回分片上传地址 */
        NO_PART_URLS,
        /** 创建上传任务失败 */
        CREATE_UPLOAD_FAIL,
        /** 本地文件在上传中被改动 */
        LOCAL_EOF_EARLY,
        /** 分片上传失败（参数：httpCode、body） */
        PART_UPLOAD_FAIL,
        /** 文件超过单次上传上限（参数：上限文案、当前大小、替代方案） */
        UPLOAD_TOO_LARGE,
        /** 上传路径为空 */
        EMPTY_UPLOAD_PATH,
        /** 取不到条目 id（参数：路径） */
        NO_ITEM_ID
    }

    /** OP_FAIL 的第 0 个参数：操作码 */
    public static final int OP_LIST = 1, OP_READ = 2, OP_RESUME = 3,
            OP_UPLOAD = 4, OP_MKDIR = 5, OP_DELETE = 6, OP_RENAME = 7;

    /** OP_FAIL 的第 3 个参数：HTTP 状态码提示码 */
    public static final int HINT_NONE = 0,
            HINT_S3_REGION = 1, HINT_S3_SIGN = 2, HINT_S3_NOTFOUND = 3,
            // 云盘 hint（API_FAIL / HTTP_FAIL 用）
            HINT_CLOUD_NOT_FOUND = 10, HINT_CLOUD_AUTH_EXPIRED = 11,
            HINT_CLOUD_NO_PERM = 12, HINT_CLOUD_QUOTA = 13,
            HINT_CLOUD_413 = 14,
            // 阿里云盘 hint
            HINT_ALI_NOT_FOUND = 20, HINT_ALI_QUOTA = 21, HINT_ALI_BUSY = 22,
            HINT_ALI_FILE_TYPE = 23, HINT_ALI_NAME = 24;

    /** 云盘名（NOT_AUTHED / AUTH_LOST / AUTH_EXPIRED 的参数） */
    public static final int CLOUD_ALIYUN = 1, CLOUD_ONEDRIVE = 2;

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

    // ---- 云盘通用工厂 ----
    public static NetFsError notAuthed(int cloud)          { return new NetFsError(Code.NOT_AUTHED, cloud); }
    public static NetFsError authLost(int cloud)           { return new NetFsError(Code.AUTH_LOST, cloud); }
    public static NetFsError authExpired(int cloud, String code, Throwable e) {
        return new NetFsError(Code.AUTH_EXPIRED, e, cloud, code);
    }
    public static NetFsError apiFail(String path, String code, String msg, int hint) {
        return new NetFsError(Code.API_FAIL, path, code, msg, hint);
    }
    public static NetFsError httpFail(String path, int http, int hint) {
        return new NetFsError(Code.HTTP_FAIL, path, http, hint);
    }
    public static NetFsError httpDetailFail(String op, int http, int hint, String detail) {
        return new NetFsError(Code.HTTP_DETAIL_FAIL, op, http, hint, detail);
    }
    public static NetFsError pathNotFound(String path)       { return new NetFsError(Code.PATH_NOT_FOUND, path); }
    public static NetFsError noDownloadUrl(String path)      { return new NetFsError(Code.NO_DOWNLOAD_URL, path); }
    public static NetFsError rootNotAllowed(String action)   { return new NetFsError(Code.ROOT_NOT_ALLOWED, action); }
    public static NetFsError badName(CloudPath.NameError e)  { return new NetFsError(Code.BAD_NAME, e.code, e.arg); }
    public static NetFsError fieldRequired(String field)     { return new NetFsError(Code.FIELD_REQUIRED, field); }
    public static NetFsError quotaExhausted()                { return new NetFsError(Code.QUOTA_EXHAUSTED); }
    public static NetFsError tooManyRequests()               { return new NetFsError(Code.TOO_MANY_REQUESTS); }
    public static NetFsError fileTypeForbidden()             { return new NetFsError(Code.FILE_TYPE_FORBIDDEN); }
    public static NetFsError nameInvalid()                   { return new NetFsError(Code.NAME_INVALID); }
    public static NetFsError noDriveId()                     { return new NetFsError(Code.NO_DRIVE_ID); }
    public static NetFsError noPartUrls()                    { return new NetFsError(Code.NO_PART_URLS); }
    public static NetFsError createUploadFail()              { return new NetFsError(Code.CREATE_UPLOAD_FAIL); }
    public static NetFsError localEofEarly()                 { return new NetFsError(Code.LOCAL_EOF_EARLY); }
    public static NetFsError partUploadFail(int http, String body) {
        return new NetFsError(Code.PART_UPLOAD_FAIL, http, body);
    }
    public static NetFsError uploadTooLarge(String limit, String size, String alternative) {
        return new NetFsError(Code.UPLOAD_TOO_LARGE, limit, size, alternative);
    }
    public static NetFsError emptyUploadPath()               { return new NetFsError(Code.EMPTY_UPLOAD_PATH); }
    public static NetFsError noItemId(String path)           { return new NetFsError(Code.NO_ITEM_ID, path); }

    /** 诊断串：{@code NETFS CODE: arg1 arg2}，全 ASCII，只给日志和单测 */
    private static String diagnose(Code code, Object[] args) {
        if (args == null || args.length == 0) return "NETFS " + code;
        StringBuilder sb = new StringBuilder("NETFS ").append(code).append(':');
        for (Object a : args) sb.append(' ').append(a);
        return sb.toString();
    }
}
