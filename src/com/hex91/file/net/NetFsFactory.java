package com.hex91.file.net;

import com.hex91.file.utils.*;
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
    /** v1.88.0：S3 兼容对象存储 */
    public static final String TYPE_S3 = "s3";
    /** v1.89.0：OneDrive（Graph） */
    public static final String TYPE_ONEDRIVE = "onedrive";
    /** v1.89.0：阿里云盘 */
    public static final String TYPE_ALIYUN = "aliyun";

    static final String PREFS = "netstorage";
    static final String KEY_SERVERS = "servers";

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
        if (TYPE_S3.equals(t)) {
            return S3Fs.connect(s.optString("host"), s.optString("share"),
                    s.optString("domain"), s.optString("user"), pw,
                    new java.io.File(c.getCacheDir(), "s3up"));
        }
        if (TYPE_ONEDRIVE.equals(t) || TYPE_ALIYUN.equals(t)) {
            // 云盘的 token 会刷新（阿里云盘还会轮换 refresh_token），必须回写配置文件，
            // 否则「同步一次成功、下次就失效」，且用户完全看不出原因
            OAuthClient.TokenSink sink = tokenSink(c, s);
            java.io.File tmp = new java.io.File(c.getCacheDir(), "cloudup");
            String token = CredentialVault.decrypt(s.optString("token"));
            if (TYPE_ONEDRIVE.equals(t)) {
                return OneDriveFs.connect(s.optString("user"), token, sink, tmp);
            }
            return AliyunDriveFs.connect(token, sink, tmp);
        }
        return SmbFs.connect(s.optString("host"), s.optInt("port", 445),
                s.optString("share"), s.optString("domain"),
                s.optString("user"), pw);
    }

    /** 刷新后的 refresh_token 加密写回 servers 数组（按 type+host+user 定位同一条配置） */
    private static OAuthClient.TokenSink tokenSink(final Context c, final JSONObject cfg) {
        return new OAuthClient.TokenSink() {
            public void onToken(OAuthToken tok) {
                if (tok == null || !tok.hasRefresh()) return;
                try {
                    String enc = CredentialVault.encrypt(tok.refresh);
                    cfg.put("token", enc);          // 内存里的这份也同步
                    android.content.SharedPreferences sp =
                            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    org.json.JSONArray arr = new org.json.JSONArray(sp.getString(KEY_SERVERS, "[]"));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (sameServer(o, cfg)) o.put("token", enc);
                    }
                    sp.edit().putString(KEY_SERVERS, arr.toString()).apply();
                } catch (Exception ignore) {
                    // 写盘失败只影响下次授权，不该打断当前同步/浏览
                }
            }
        };
    }

    private static boolean sameServer(JSONObject a, JSONObject b) {
        return eq(a.optString("type"), b.optString("type"))
                && eq(a.optString("host"), b.optString("host"))
                && eq(a.optString("user"), b.optString("user"))
                && eq(a.optString("share"), b.optString("share"));
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 列表里显示的标题 */
    public static String describe(JSONObject s) {
        if (s == null) return "未选择";
        String t = s.optString("type");
        String name = s.optString("name");
        String target;
        if (TYPE_WEBDAV.equals(t)) {
            target = s.optString("url").isEmpty() ? s.optString("host") : s.optString("url");
        } else if (TYPE_SMB.equals(t) || TYPE_S3.equals(t)) {
            target = s.optString("host") + "/" + s.optString("share");
        } else if (TYPE_ONEDRIVE.equals(t) || TYPE_ALIYUN.equals(t)) {
            target = TYPE_ONEDRIVE.equals(t) ? "OneDrive" : "阿里云盘";
        } else {
            target = s.optString("host");
        }
        return (name == null || name.isEmpty() ? t : name + " · " + t) + "  " + target;
    }
}
