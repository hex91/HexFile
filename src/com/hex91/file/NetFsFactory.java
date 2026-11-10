package com.hex91.file;

import android.content.Context;

import org.json.JSONObject;

/**
 * v1.87.0：按已保存的服务器配置构造 {@link NetFs}。
 *
 * <p>抽出来的原因：网络存储页与云同步页都要「拿一条 JSON 换一个连接」，
 * 各写一份迟早漂移（比如新增一种协议只改了一边）。密码在配置里是密文，
 * 统一在这里解密。</p>
 */
public final class NetFsFactory {

    public static final String TYPE_WEBDAV = "webdav";
    public static final String TYPE_FTP = "ftp";
    public static final String TYPE_SFTP = "sftp";
    public static final String TYPE_SMB = "smb";

    private NetFsFactory() {}

    /** 打开连接（调用方负责 close）；失败抛异常 */
    public static NetFs open(Context c, JSONObject s) throws Exception {
        if (s == null) throw new IllegalArgumentException("服务器配置为空");
        CredentialVault.init(c);     // 幂等，保证密钥就绪
        String pw = CredentialVault.decrypt(s.optString("pass"));
        String t = s.optString("type");
        if (TYPE_WEBDAV.equals(t)) {
            String url = s.optString("url").isEmpty() ? s.optString("host") : s.optString("url");
            return new WebDavFs(url, s.optString("user"), pw);
        }
        if (TYPE_FTP.equals(t)) {
            return FtpFs.connect(s.optString("host"), s.optInt("port", 21),
                    s.optString("user"), pw);
        }
        if (TYPE_SFTP.equals(t)) {
            return SftpFs.connect(s.optString("host"), s.optInt("port", 22),
                    s.optString("user"), pw);
        }
        return SmbFs.connect(s.optString("host"), s.optInt("port", 445),
                s.optString("share"), s.optString("domain"),
                s.optString("user"), pw);
    }

    /** 列表里显示的标题 */
    public static String describe(JSONObject s) {
        if (s == null) return "未选择";
        String t = s.optString("type");
        String name = s.optString("name");
        String target;
        if (TYPE_WEBDAV.equals(t)) {
            target = s.optString("url").isEmpty() ? s.optString("host") : s.optString("url");
        } else if (TYPE_SMB.equals(t)) {
            target = s.optString("host") + "/" + s.optString("share");
        } else {
            target = s.optString("host");
        }
        return (name == null || name.isEmpty() ? t : name + " · " + t) + "  " + target;
    }
}
