package com.hex91.file.net;

import java.io.IOException;

/**
 * HLS 链路的**结构化**错误 —— v2.26.0（F-14/10）。
 *
 * <p>起因：这一层原本到处 {@code throw new IOException("清单里没有分片")}。文案要出英文时，
 * 只能把 Context / 语言往纯 Java 层塞，而且同一句话在 net 与 ui 两处维护，改一处忘一处。</p>
 *
 * <p>现在的分工：这里只给「错误码 + 参数」，参数是路径、长度、HTTP 状态这类**数据**；
 * 文案统一由 ui 层按 {@link Code} 取字符串资源。{@link #getMessage()} 仍给一句 ASCII
 * 诊断串（日志 / 单测用），**不该**出现在界面上。</p>
 *
 * <p>仍然 {@code extends IOException}：调用方原有的 {@code catch (IOException)} 全部不变。</p>
 */
public class HlsError extends IOException {

    /** 错误码。新增一个码要同步在 ui 层加译文，否则会落到「通用错误 + 诊断串」的兜底分支。 */
    public enum Code {
        /** 地址为空 */
        EMPTY_URL,
        /** 清单里没有分片 */
        NO_SEGMENTS,
        /** master 清单的最高档里没有分片 */
        MASTER_EMPTY,
        /** 内容不是 m3u8（参数：首行摘要） */
        NOT_M3U8,
        /** 加密方案解不了（参数：HlsKey） */
        BLOCKED,
        /** 建目录失败（参数：路径） */
        MKDIR_FAIL,
        /** 所有分片都是 EXT-X-GAP */
        ALL_GAP,
        /** 建分片临时目录失败（参数：路径） */
        TMP_DIR_FAIL,
        /** AES-128 但没给密钥地址 */
        KEY_URI_MISSING,
        /** 密钥文件长度不对（参数：实际字节数） */
        KEY_BAD_SIZE,
        /** 用户取消（已下分片保留） */
        CANCELLED_KEEP,
        /** 用户取消 */
        CANCELLED,
        /** 合并文件改名失败（参数：目标路径） */
        MERGE_RENAME_FAIL,
        /** 转封装后输出为空 */
        EMPTY_OUTPUT,
        /** 分片内容为空 */
        SEG_EMPTY,
        /** 服务器忽略 Range（参数：期望长度、实际长度） */
        RANGE_IGNORED,
        /** 分片写盘失败（参数：分片名） */
        SEG_WRITE_FAIL,
        /** 分片下载失败（cause 是真实原因） */
        SEG_FETCH_FAIL,
        /** 解密失败（参数：序号、底层原因） */
        DECRYPT_FAIL,
        /** HTTP 非 2xx（参数：状态码、缩略地址） */
        HTTP_STATUS,
        /** 响应超过上限（参数：MB） */
        TOO_LARGE,
        /** 响应比声明的 Range 短 */
        SHORT_RANGE,
        /** 本地文件不存在（参数：路径） */
        FILE_MISSING,
        /** Range 起点超出文件长度（参数：文件名） */
        RANGE_BEYOND_EOF
    }

    public final Code code;
    private final Object[] args;

    public HlsError(Code code, Object... args) {
        super(diagnose(code, args));
        this.code = code;
        this.args = args;
    }

    /** 带底层原因（{@link Code#SEG_FETCH_FAIL} 用它保留「HTTP 404」这类真实原因） */
    public HlsError(Code code, Throwable cause, Object... args) {
        super(diagnose(code, args), cause);
        this.code = code;
        this.args = args;
    }

    /** 第 i 个参数（越界返回 null） */
    public Object arg(int i) {
        return args != null && i >= 0 && i < args.length ? args[i] : null;
    }

    /** 字符串参数（null 视作空串） */
    public String str(int i) {
        Object o = arg(i);
        return o == null ? "" : String.valueOf(o);
    }

    /** 整数参数（缺省 -1） */
    public int num(int i) {
        Object o = arg(i);
        return o instanceof Number ? ((Number) o).intValue() : -1;
    }

    /** 诊断串：{@code HLS CODE: arg1 arg2}，全 ASCII，只给日志和单测 */
    private static String diagnose(Code code, Object[] args) {
        if (args == null || args.length == 0) return "HLS " + code;
        StringBuilder sb = new StringBuilder("HLS ").append(code).append(':');
        for (Object a : args) sb.append(' ').append(a);
        return sb.toString();
    }
}
