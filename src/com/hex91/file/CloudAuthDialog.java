package com.hex91.file;

import com.hex91.file.net.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/**
 * v1.89.0 云盘授权对话框（OneDrive 设备码 / 阿里云盘 refresh_token）。
 *
 * <p>两种流程的差别不是实现细节，而是「能不能不碰用户密码」：</p>
 * <ul>
 *   <li><b>OneDrive</b> 用设备码：App 只申请一个 8 位 user_code，用户在<b>系统浏览器</b>里登录，
 *       密码永远不进本应用，也不需要注册 redirect URI；</li>
 *   <li><b>阿里云盘</b> 没有对个人开放的 OAuth 注册入口，只能用浏览器里已经登录的 refresh_token。
 *       这是这条路唯一的现实做法，所以把「怎么取」写在对话框里，而不是让用户去猜。</li>
 * </ul>
 *
 * <p>两个流程都遵守同一件事：<b>拿到的新 token 立刻回调出去落盘</b>。
 * 阿里云盘的 refresh_token 是用一次就换的，不落盘等于下次必然失效。</p>
 */
final class CloudAuthDialog {

    interface Callback {
        void onAuthorized(String refreshToken);
    }

    private CloudAuthDialog() {}

    static void show(final Activity act, final String type, final String clientId,
                     final String currentRefresh, final Callback cb) {
        if ("aliyun".equals(type)) showAliyun(act, currentRefresh, cb);
        else showOneDrive(act, clientId, cb);
    }

    // ================================================================ OneDrive

    private static void showOneDrive(final Activity act, final String clientId, final Callback cb) {
        final LinearLayout box = column(act);

        box.addView(tip(act, "需要一个免费的 Azure 应用（只注册一次）：\n"
                + "1. 浏览器打开 portal.azure.com → Microsoft Entra ID → 应用注册 → 新注册\n"
                + "2. 账户类型选「个人 Microsoft 账户」，名称随意\n"
                + "3. 注册后复制「应用程序(客户端) ID」，填到表单的 Client ID\n"
                + "4. 该应用 → 身份验证 → 打开「允许公共客户端流」→ 保存\n"
                + "5. API 权限 → Microsoft Graph → 委托的权限 → Files.ReadWrite.All"));

        final TextView code = new TextView(act);
        code.setTextSize(30);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextColor(ThemeManager.accent(act));
        code.setPadding(0, dp(act, 14), 0, dp(act, 6));
        code.setVisibility(View.GONE);
        box.addView(code);

        final TextView status = tip(act, clientId == null || clientId.trim().isEmpty()
                ? "先在上面的表单填好 Client ID，再点下面的按钮"
                : "点「获取授权码」后，会打开浏览器让你登录");
        box.addView(status);

        final Button start = new Button(act);
        start.setText("获取授权码");
        box.addView(start);

        final Button open = new Button(act);
        open.setText("重新打开浏览器");
        open.setVisibility(View.GONE);
        box.addView(open);

        final AlertDialog[] holder = new AlertDialog[1];
        final boolean[] cancelled = {false};
        final String[] verifyUri = {""};

        AlertDialog d = new HfDialogBuilder(act)
                .setTitle("登录 OneDrive")
                .setView(scroll(act, box))
                .setNegativeButton("关闭", null)
                .create();
        holder[0] = d;
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface di) { cancelled[0] = true; }
        });

        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String cid = clientId == null ? "" : clientId.trim();
                if (cid.isEmpty()) {
                    status.setText("Client ID 为空：请先在表单里填入 Azure 应用的客户端 ID");
                    return;
                }
                start.setEnabled(false);
                status.setText("正在向微软申请授权码…");
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            final OAuthClient.DeviceCode dc = OAuthClient.requestDeviceCode(cid);
                            verifyUri[0] = dc.verificationUri;
                            ui(act, new Runnable() {
                                public void run() {
                                    code.setVisibility(View.VISIBLE);
                                    code.setText(dc.userCode);
                                    open.setVisibility(View.VISIBLE);
                                    status.setText("已打开浏览器，输入上面的代码并同意授权…");
                                    start.setText("重新获取");
                                    start.setEnabled(true);
                                    openBrowser(act, dc.verificationUri);
                                }
                            });
                            poll(act, cid, dc, cancelled, status, holder, cb);
                        } catch (final Exception e) {
                            ui(act, new Runnable() {
                                public void run() {
                                    start.setEnabled(true);
                                    status.setText("申请失败：" + friendly(e));
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!verifyUri[0].isEmpty()) openBrowser(act, verifyUri[0]);
            }
        });

        d.show();
    }

    /** 轮询令牌端点，直到用户完成授权 / 拒绝 / 超时 */
    private static void poll(final Activity act, final String clientId,
                             final OAuthClient.DeviceCode dc, final boolean[] cancelled,
                             final TextView status, final AlertDialog[] holder, final Callback cb) {
        final long deadline = System.currentTimeMillis() + dc.expiresInSec * 1000L;
        int interval = dc.intervalSec;
        while (!cancelled[0] && System.currentTimeMillis() < deadline) {
            sleep(interval * 1000L);
            if (cancelled[0]) return;
            try {
                final OAuthToken t = OAuthClient.pollDeviceCode(clientId, dc.deviceCode);
                if (!t.hasRefresh()) {
                    fail(act, status, "微软没有返回 refresh_token：请确认应用的权限里包含 offline_access");
                    return;
                }
                ui(act, new Runnable() {
                    public void run() {
                        if (holder[0] != null) {
                            holder[0].setOnDismissListener(null);   // 已成功，不当作取消
                            holder[0].dismiss();
                        }
                        Toast.makeText(act, "OneDrive 已授权", Toast.LENGTH_SHORT).show();
                    }
                });
                cb.onAuthorized(t.refresh);
                return;
            } catch (OAuthToken.OAuthError e) {
                if (e.slowDown()) {
                    interval += 5;                       // 服务端要求降频，否则持续被拒
                    continue;
                }
                if (e.pending()) {
                    final long left = Math.max(0, (deadline - System.currentTimeMillis()) / 1000);
                    ui(act, new Runnable() {
                        public void run() {
                            status.setText("等待你在浏览器里完成登录…（剩余 " + left + " 秒）");
                        }
                    });
                    continue;
                }
                if (e.declined()) {
                    fail(act, status, "你取消了授权");
                    return;
                }
                if (e.expired()) {
                    fail(act, status, "授权码已过期，请点「重新获取」");
                    return;
                }
                fail(act, status, friendly(e));
                return;
            } catch (IOException e) {
                fail(act, status, friendly(e));
                return;
            }
        }
        if (!cancelled[0]) fail(act, status, "授权超时，请重新获取授权码");
    }

    // ================================================================ 阿里云盘

    private static void showAliyun(final Activity act, final String currentRefresh, final Callback cb) {
        final LinearLayout box = column(act);

        box.addView(tip(act, "手动粘贴方式（一般用不到）：表单里的「登录 / 授权」会直接打开"
                + "阿里云盘登录页，登录完自动取回凭证，不需要下面这些步骤。\n"
                + "只有在那个页面登录不了（例如站点改版、企业网络拦截）时才用这里：\n"
                + "1. 电脑浏览器打开 alipan.com 并登录\n"
                + "2. F12 → Application（应用）→ Local Storage → https://www.alipan.com\n"
                + "3. 找到键 token，复制它的值（一整段 JSON 也行）\n"
                + "4. 粘贴到下面，点「验证并保存」\n\n"
                + "refresh_token 每用一次就会换新的，App 会自动保存最新值。"));

        final EditText input = new EditText(act);
        input.setHint("粘贴 refresh_token，或整段 {\"refresh_token\":...}");
        input.setTextSize(13);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setText(OAuthToken.extractRefresh(currentRefresh));
        box.addView(input);

        final TextView status = tip(act, "");
        box.addView(status);

        AlertDialog d = new HfDialogBuilder(act)
                .setTitle("登录阿里云盘")
                .setView(scroll(act, box))
                .setPositiveButton("验证并保存", null)
                .setNegativeButton("取消", null)
                .create();

        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                final AlertDialog ad = (AlertDialog) di;
                ad.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        final String rt = OAuthToken.extractRefresh(input.getText().toString());
                        if (rt.isEmpty()) {
                            status.setText("没找到 refresh_token：请复制完整的 token 值");
                            return;
                        }
                        status.setText("正在验证…");
                        ad.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    final OAuthToken t = OAuthClient.refreshAliyun(rt);
                                    if (!t.hasRefresh()) {
                                        ui(act, new Runnable() {
                                            public void run() {
                                                status.setText("验证成功但没返回新 token，请重试");
                                                ad.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                            }
                                        });
                                        return;
                                    }
                                    ui(act, new Runnable() {
                                        public void run() {
                                            ad.dismiss();
                                            Toast.makeText(act, "阿里云盘已授权", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                    cb.onAuthorized(t.refresh);
                                } catch (final Exception e) {
                                    ui(act, new Runnable() {
                                        public void run() {
                                            status.setText("验证失败：" + friendly(e));
                                            ad.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                });
            }
        });
        d.show();
    }

    // ================================================================ 小工具

    private static void fail(final Activity act, final TextView status, final String msg) {
        ui(act, new Runnable() {
            public void run() { status.setText(msg); }
        });
    }

    private static void ui(Activity act, Runnable r) {
        try {
            act.runOnUiThread(r);
        } catch (Exception ignore) {
            // Activity 已经销毁：授权结果由下一次进入页面时的 refresh 体现，不必弹 UI
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }

    private static void openBrowser(Activity act, String uri) {
        if (uri == null || uri.isEmpty()) return;
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Exception e) {
            Toast.makeText(act, "打不开浏览器，请手动访问 " + uri, Toast.LENGTH_LONG).show();
        }
    }

    private static LinearLayout column(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 20);
        box.setPadding(pad, dp(act, 6), pad, dp(act, 4));
        return box;
    }

    private static TextView tip(Activity act, String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(ThemeManager.textSub(act));
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, dp(act, 10), 0, dp(act, 4));
        return t;
    }

    private static ScrollView scroll(Activity act, View content) {
        ScrollView sc = new ScrollView(act);
        sc.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static String friendly(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        if (e instanceof java.net.SocketTimeoutException) return "网络超时：请检查网络或代理";
        if (e instanceof java.net.UnknownHostException) return "域名解析失败：请检查网络/DNS";
        if (e instanceof javax.net.ssl.SSLException) return "TLS 失败：系统时间不准或网络被劫持";
        return m;
    }

}
