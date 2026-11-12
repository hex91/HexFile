package com.hex91.file;

import com.hex91.file.R;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.70.0 网络请求页（类 Postman 精简版）。
 *
 * <p>能做的事：选方法 + 填 URL + 自定义请求头（每行 {@code Key: Value}）+ 请求体，发送后显示
 * 状态码 / 耗时 / 大小 / 响应头 / 响应体；JSON 响应会自动缩进。
 *
 * <p>实现用 {@link HttpURLConnection}：不引第三方库。明文 {@code http://} 依赖
 * {@code res/xml/network_security_config.xml} 放行（targetSdk 28+ 默认禁止 CLEARTEXT），
 * 否则会在建连阶段直接抛 "not permitted by network security policy"。
 * 请求跑在后台线程，主线程只刷 UI；响应体超过 512KB 会截断，避免大文件把内存吃光。
 */
public class HttpToolActivity extends BaseActivity {

    private static final String PREFS = "hex_toolbox";
    private static final String KEY_HISTORY = "http_history";
    private static final int MAX_BODY = 512 * 1024;

    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"};

    private Spinner methodSp;
    private EditText urlEt, headersEt, bodyEt;
    private TextView outTv, statusTv, tabHeaders, tabBody, sendBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean sending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_http);

        methodSp = (Spinner) findViewById(R.id.ht_method);
        urlEt = (EditText) findViewById(R.id.ht_url);
        headersEt = (EditText) findViewById(R.id.ht_headers);
        bodyEt = (EditText) findViewById(R.id.ht_body);
        outTv = (TextView) findViewById(R.id.ht_out);
        statusTv = (TextView) findViewById(R.id.ht_status);
        tabHeaders = (TextView) findViewById(R.id.ht_tab_headers);
        tabBody = (TextView) findViewById(R.id.ht_tab_body);
        sendBtn = (TextView) findViewById(R.id.ht_send);

        methodSp.setAdapter(ToolUi.adapter(this, METHODS));
        methodSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                // GET/HEAD 不需要请求体，切过去时自动收起，避免误以为会带上 body
                boolean noBody = "GET".equals(METHODS[pos]) || "HEAD".equals(METHODS[pos]);
                if (noBody && bodyEt.getVisibility() == View.VISIBLE) {
                    bodyEt.setVisibility(View.GONE);
                    ToolUi.chipState(tabBody, false, HttpToolActivity.this);
                }
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        sendBtn.setBackground(ToolUi.accentBg(this, 10));
        ToolUi.chipState(tabHeaders, false, this);
        ToolUi.chipState(tabBody, false, this);

        findViewById(R.id.ht_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.ht_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolUi.copy(HttpToolActivity.this, outTv.getText(), "响应");
            }
        });
        findViewById(R.id.ht_history).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showHistory();
            }
        });
        tabHeaders.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = headersEt.getVisibility() != View.VISIBLE;
                headersEt.setVisibility(show ? View.VISIBLE : View.GONE);
                ToolUi.chipState(tabHeaders, show, HttpToolActivity.this);
            }
        });
        tabBody.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = bodyEt.getVisibility() != View.VISIBLE;
                bodyEt.setVisibility(show ? View.VISIBLE : View.GONE);
                ToolUi.chipState(tabBody, show, HttpToolActivity.this);
            }
        });
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                send();
            }
        });

        if (b == null) {
            headersEt.setText("User-Agent: HexFile/1.70\nAccept: */*");
        }
    }

    // ============================================================ 发送

    private void send() {
        if (sending) return;
        final String u = urlEt.getText().toString().trim();
        if (u.length() == 0) {
            ToolUi.toast(this, "请先填写 URL");
            return;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            ToolUi.toast(this, "URL 需要以 http:// 或 https:// 开头");
            return;
        }
        final String m = METHODS[methodSp.getSelectedItemPosition()];
        final String hs = headersEt.getText().toString();
        final String bd = bodyEt.getText().toString();

        sending = true;
        sendBtn.setAlpha(0.5f);
        statusTv.setTextColor(ThemeManager.textSub(this));
        statusTv.setText("请求中… " + m + " " + u);
        outTv.setText("");

        new Thread(new Runnable() {
            public void run() {
                request(m, u, hs, bd);
            }
        }, "hex-http").start();
    }

    private void request(String method, String urlStr, String headerText, String bodyText) {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);

            boolean hasCT = false;
            for (String line : headerText.split("\n")) {
                String t = line.trim();
                if (t.length() == 0 || t.startsWith("#")) continue;
                int colon = t.indexOf(':');
                if (colon <= 0) continue;
                String k = t.substring(0, colon).trim();
                String v = t.substring(colon + 1).trim();
                if (k.length() == 0) continue;
                if (k.equalsIgnoreCase("Content-Type")) hasCT = true;
                c.setRequestProperty(k, v);
            }

            boolean bodyless = "GET".equals(method) || "HEAD".equals(method);
            byte[] payload = (!bodyless && bodyText.length() > 0) ? bodyText.getBytes("UTF-8") : null;
            if (payload != null) {
                if (!hasCT) {
                    String t = bodyText.trim();
                    c.setRequestProperty("Content-Type",
                            (t.startsWith("{") || t.startsWith("["))
                                    ? "application/json; charset=utf-8"
                                    : "text/plain; charset=utf-8");
                }
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(payload.length);
                OutputStream os = c.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
            }

            int code = c.getResponseCode();
            String msg = c.getResponseMessage();
            String ct = c.getContentType();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(is, charsetOf(ct));
            long ms = System.currentTimeMillis() - t0;

            StringBuilder head = new StringBuilder();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;   // 第一项是状态行
                for (String v : e.getValue()) {
                    head.append(e.getKey()).append(": ").append(v).append('\n');
                }
            }

            String pretty = body;
            if (looksJson(ct, body)) pretty = prettyJson(body);

            final StringBuilder all = new StringBuilder();
            all.append("── 响应头 ──\n").append(head);
            all.append('\n').append("── 响应体 ──\n").append(pretty);

            final int fc = code;
            final String fm = msg;
            final long fms = ms;
            final int size = body.getBytes("UTF-8").length;
            final String text = all.toString();

            ui.post(new Runnable() {
                public void run() {
                    if (isFinishing()) return;
                    outTv.setText(text);
                    statusTv.setTextColor(fc >= 200 && fc < 300 ? ThemeManager.ok(HttpToolActivity.this)
                            : (fc >= 400 ? ThemeManager.err(HttpToolActivity.this) : ThemeManager.textSub(HttpToolActivity.this)));
                    statusTv.setText(String.format(Locale.US, "HTTP %d %s · %d ms · %s",
                            fc, fm == null ? "" : fm, fms, size(size)));
                    done();
                }
            });
            saveHistory(method + " " + urlStr);
        } catch (final Exception e) {
            final long ms = System.currentTimeMillis() - t0;
            ui.post(new Runnable() {
                public void run() {
                    if (isFinishing()) return;
                    statusTv.setTextColor(ThemeManager.err(HttpToolActivity.this));
                    statusTv.setText("✗ 请求失败（" + ms + " ms）：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " - " + e.getMessage()));
                    outTv.setText("常见原因：\n"
                            + "· 域名解析失败 / 无网络 → 检查 URL 与网络\n"
                            + "· 明文 HTTP 被拦 → 换 https，或检查系统代理\n"
                            + "· 证书错误 → 该站点证书不被系统信任\n"
                            + "· 超时 → 连接 15s / 读取 25s 上限");
                    done();
                }
            });
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void done() {
        sending = false;
        sendBtn.setAlpha(1f);
    }

    // ============================================================ 历史记录

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void saveHistory(String line) {
        String old = prefs().getString(KEY_HISTORY, "");
        StringBuilder sb = new StringBuilder(line);
        int n = 1;
        for (String s : old.split("\n")) {
            if (s.trim().length() == 0 || s.equals(line)) continue;
            if (n++ >= 20) break;
            sb.append('\n').append(s);
        }
        prefs().edit().putString(KEY_HISTORY, sb.toString()).apply();
    }

    private void showHistory() {
        final String raw = prefs().getString(KEY_HISTORY, "");
        if (raw.trim().length() == 0) {
            ToolUi.toast(this, "还没有历史记录");
            return;
        }
        final String[] items = raw.split("\n");
        String[] shown = new String[items.length + 1];
        for (int i = 0; i < items.length; i++) shown[i] = items[i];
        shown[items.length] = "✕ 清空历史";
        new HfDialogBuilder(this)
                .setTitle("最近请求")
                .setItems(shown, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == items.length) {
                            prefs().edit().remove(KEY_HISTORY).apply();
                            ToolUi.toast(HttpToolActivity.this, "历史已清空");
                            return;
                        }
                        String it = items[which];
                        int sp = it.indexOf(' ');
                        if (sp > 0) {
                            String m = it.substring(0, sp);
                            for (int i = 0; i < METHODS.length; i++) {
                                if (METHODS[i].equals(m)) methodSp.setSelection(i);
                            }
                            urlEt.setText(it.substring(sp + 1));
                            urlEt.setSelection(urlEt.getText().length());
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ============================================================ 工具

    private static String size(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static String charsetOf(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.US);
            int i = lower.indexOf("charset=");
            if (i >= 0) {
                String cs = contentType.substring(i + 8).trim();
                int sc = cs.indexOf(';');
                if (sc >= 0) cs = cs.substring(0, sc);
                return cs.replace("\"", "").trim();
            }
        }
        return "UTF-8";
    }

    private static String readAll(InputStream is, String charset) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = is.read(buf)) > 0) {
            total += n;
            if (total > MAX_BODY) {
                bo.write(buf, 0, Math.max(0, n - (total - MAX_BODY)));
                bo.write("\n…（超过 512KB，已截断）".getBytes("UTF-8"));
                break;
            }
            bo.write(buf, 0, n);
        }
        is.close();
        try {
            return bo.toString(charset);
        } catch (Exception e) {
            return bo.toString("UTF-8");
        }
    }

    private static boolean looksJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase(Locale.US).contains("json")) return true;
        String t = body.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    /** 极简 JSON 缩进器：只在字符串外的 {}[] , 上做换行缩进，不校验语法（够看接口返回） */
    static String prettyJson(String src) {
        StringBuilder sb = new StringBuilder(src.length() + 64);
        int indent = 0;
        boolean inStr = false, esc = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"':
                    inStr = true;
                    sb.append(c);
                    break;
                case '{':
                case '[':
                    sb.append(c);
                    indent++;
                    sb.append('\n').append(sp(indent));
                    break;
                case '}':
                case ']':
                    indent = Math.max(0, indent - 1);
                    sb.append('\n').append(sp(indent)).append(c);
                    break;
                case ',':
                    sb.append(c).append('\n').append(sp(indent));
                    break;
                case ':':
                    sb.append(": ");
                    break;
                case ' ':
                case '\n':
                case '\r':
                case '\t':
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String sp(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n * 2; i++) sb.append(' ');
        return sb.toString();
    }
}
