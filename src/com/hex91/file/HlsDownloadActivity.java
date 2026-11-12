package com.hex91.file;

import com.hex91.file.fs.*;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HLS 下载 / 合并为 MP4 —— v1.92.0。
 *
 * <p>一个页面走完「粘贴地址 → 解析 → 选画质 → 下载 → 转封装」，不做任务队列，
 * 也不做后台服务：用户按返回就明确提示「会取消」。理由是本工程的任务中心是
 * 「文件复制/移动」的语义（源与目标都已知、可断点重入），而 HLS 下载的进度是
 * 「网络 + 远端清单」决定的，塞进同一个面板会让两边都变复杂，而收益只是少一个页面。</p>
 *
 * <p>另一个克制的点：<b>不做自动解析</b>。粘贴后立刻发请求看似聪明，
 * 但用户往往要改一下地址里的参数，自动请求既浪费流量也会让「正在解析」闪一下。</p>
 */
public class HlsDownloadActivity extends BaseActivity {

    /** 预填地址（从长按 .m3u8、⋮ 菜单或外部 VIEW 进来） */
    public static final String EXTRA_URL = "hls_url";

    private EditText etUrl;
    private TextView tvResult, rowQuality, rowDir, tvStatus, tvLog;
    private CheckBox cbMp4;
    private Button btnParse, btnStart, btnCancel;
    private ProgressBar pb;

    private File outDir;

    /** master 的档位列表（可能为空：来源本身就是 media） */
    private M3u8Playlist master;
    /** 当前选中的档位（显示用） */
    private String qualityLabel;

    /** 已就绪的分片清单 */
    private HlsPlaylist media;
    private String mediaUrl;

    /** 正在解析或下载：用来禁用按钮、拦住返回 */
    private volatile boolean working;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private long lastLogAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hls_download);

        etUrl = (EditText) findViewById(R.id.et_url);
        tvResult = (TextView) findViewById(R.id.tv_result);
        rowQuality = (TextView) findViewById(R.id.row_quality);
        rowDir = (TextView) findViewById(R.id.row_dir);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvLog = (TextView) findViewById(R.id.tv_log);
        cbMp4 = (CheckBox) findViewById(R.id.cb_mp4);
        btnParse = (Button) findViewById(R.id.btn_parse);
        btnStart = (Button) findViewById(R.id.btn_start);
        btnCancel = (Button) findViewById(R.id.btn_cancel);
        pb = (ProgressBar) findViewById(R.id.pb);

        outDir = new File(WorkDir.base(), "Download");
        updateDirRow();

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressed();
            }
        });
        findViewById(R.id.btn_help).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showHelp();
            }
        });
        findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });
        btnParse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doParse();
            }
        });
        rowQuality.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickQuality();
            }
        });
        rowDir.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                DirPickerDialog.show(HlsDownloadActivity.this, outDir,
                        new DirPickerDialog.OnPick() {
                            public void onPick(File dir) {
                                outDir = dir;
                                updateDirRow();
                            }
                        });
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doStart();
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmCancel();
            }
        });

        String pre = getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(pre)) {
            pre = StreamUrl.firstUrl(getClipboardText());
        }
        if (!TextUtils.isEmpty(pre)) etUrl.setText(pre);
    }

    // ---------------------------------------------------------------- 解析

    /** 清洗输入：从聊天/网页复制过来的文本常带中文说明与多行 */
    private String cleanInput() {
        String raw = etUrl.getText().toString();
        String u = StreamUrl.firstUrl(raw);
        if (TextUtils.isEmpty(u)) {
            toast("没找到有效地址");
            return null;
        }
        if (!HlsDownloader.isLocal(u)
                && !(u.startsWith("http://") || u.startsWith("https://"))) {
            toast("只支持 http/https 的 m3u8，或本地 .m3u8 文件路径");
            return null;
        }
        return u;
    }

    private void doParse() {
        if (working) return;
        final String url = cleanInput();
        if (url == null) return;
        working = true;
        setActionsEnabled(false);
        cancel.set(false);
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText("正在解析清单…");
        rowQuality.setVisibility(View.GONE);
        log("GET " + url);

        new Thread(new Runnable() {
            public void run() {
                try {
                    // deep=true：直接钻到最高档，省掉「解析完还要再点一次」的等待
                    final HlsDownloader.Fetched f = HlsDownloader.fetch(url, null, true, null);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            working = false;
                            setActionsEnabled(true);
                            applyParsed(url, f);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            working = false;
                            setActionsEnabled(true);
                            tvResult.setText("解析失败：" + msg(e));
                            log("解析失败：" + msg(e));
                        }
                    });
                }
            }
        }, "hls-parse").start();
    }

    private void applyParsed(String srcUrl, HlsDownloader.Fetched f) {
        master = f.master;
        media = f.media;
        mediaUrl = f.url;

        if (master != null && master.hasChoice()) {
            M3u8Playlist.Variant best = master.best();
            qualityLabel = best == null ? null : best.label;
            rowQuality.setVisibility(View.VISIBLE);
            updateQualityRow();
        } else {
            qualityLabel = null;
            rowQuality.setVisibility(View.GONE);
        }

        if (media == null) {
            tvResult.setText("解析到 master 清单，但没有可用的画质档位。");
            return;
        }
        tvResult.setText(describe(srcUrl, media));
        if (media.isLive()) {
            log("这是直播流（没有 ENDLIST），只会下载当前窗口的 "
                    + media.downloadableCount() + " 个分片");
        }
        if (media.hasDiscontinuity) {
            log("清单里有 DISCONTINUITY：合并后时间轴可能有一次跳变");
        }
        log("解析完成：" + media.downloadableCount() + " 个分片 · "
                + media.encryptionText() + " · " + (media.likelyFmp4() ? "fMP4" : "MPEG-TS"));
    }

    /** 解析结果的一句话摘要 */
    private String describe(String srcUrl, HlsPlaylist p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.isLive() ? "HLS 直播" : "HLS 点播");
        if (master != null && master.hasChoice()) {
            sb.append("（master，").append(master.variants().size()).append(" 档）");
        }
        sb.append('\n');
        sb.append("分片：").append(p.downloadableCount()).append(" 个");
        long dur = p.totalDurationMs();
        if (dur > 0) sb.append(" · 时长 ").append(clock(dur));
        sb.append('\n');
        sb.append("加密：").append(p.encryptionText());
        sb.append(" · 容器：").append(p.likelyFmp4() ? "fMP4" : "MPEG-TS");
        long est = estimateBytes(srcUrl, p, dur);
        if (est > 0) {
            sb.append('\n').append("预计大小：约 ").append(bytes(est));
        }
        if (p.likelyFmp4()) {
            sb.append('\n').append("fMP4 分片拼接后本身就是 MP4，不再额外转封装。");
        }
        return sb.toString();
    }

    /** 用 master 的 BANDWIDTH × 时长估算体积（拿不到就返回 0，不瞎猜） */
    private long estimateBytes(String srcUrl, HlsPlaylist p, long durMs) {
        if (durMs <= 0) return 0;
        long bw = 0;
        if (master != null && master.hasChoice()) {
            M3u8Playlist.Variant v = qualityLabel == null ? master.best()
                    : master.byLabel(qualityLabel);
            if (v != null) bw = v.bandwidth;
        }
        if (bw <= 0) return 0;
        return bw / 8 * (durMs / 1000);
    }

    private void updateQualityRow() {
        String v = qualityLabel == null ? "自动（最高）" : qualityLabel;
        String detail = "";
        if (master != null) {
            M3u8Playlist.Variant var = qualityLabel == null ? null : master.byLabel(qualityLabel);
            if (var != null) detail = " · " + var.detail();
        }
        rowQuality.setText("画质：" + v + detail + "   ›");
    }

    private void pickQuality() {
        if (master == null || !master.hasChoice() || working) return;
        final java.util.List<M3u8Playlist.Variant> list = master.byQualityDesc();
        final String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            M3u8Playlist.Variant v = list.get(i);
            labels[i] = v.label + "  " + v.detail();
        }
        new HfDialogBuilder(this)
                .setTitle("选择画质")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        M3u8Playlist.Variant v = list.get(which);
                        qualityLabel = v.label;
                        updateQualityRow();
                        loadVariant(v);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 切档：只重取那一档的子清单（几 KB），不重新下载已下的分片 */
    private void loadVariant(final M3u8Playlist.Variant v) {
        working = true;
        setActionsEnabled(false);
        tvResult.setText("正在读取 " + v.label + " 的分片…");
        log("切换到 " + v.label + " → " + v.url);
        new Thread(new Runnable() {
            public void run() {
                try {
                    final HlsDownloader.Fetched f = HlsDownloader.fetch(v.url, null, false, null);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            working = false;
                            setActionsEnabled(true);
                            media = f.media;
                            mediaUrl = f.url;
                            if (media == null) {
                                tvResult.setText("这一档没有可用的分片，请换一档。");
                                return;
                            }
                            tvResult.setText(describe(v.url, media));
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            working = false;
                            setActionsEnabled(true);
                            tvResult.setText("切换画质失败：" + msg(e));
                        }
                    });
                }
            }
        }, "hls-variant").start();
    }

    // ---------------------------------------------------------------- 下载

    private void doStart() {
        if (working) return;
        if (media == null || media.isEmpty()) {
            toast("请先点「解析」，确认分片清单");
            return;
        }
        String block = media.blockReason();
        if (block != null) {
            alert("无法下载", block);
            return;
        }
        final String src = cleanInput();
        final String baseName = HlsDownloader.deriveName(src);
        final boolean toMp4 = cbMp4.isChecked();
        final File dir = outDir;
        if (dir == null) {
            toast("请选择保存目录");
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            toast("无法创建目录：" + dir.getAbsolutePath());
            return;
        }

        working = true;
        cancel.set(false);
        setActionsEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        pb.setVisibility(View.VISIBLE);
        pb.setProgress(0);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("准备下载…");
        tvLog.setVisibility(View.VISIBLE);
        tvLog.setText("");
        log("保存到 " + dir.getAbsolutePath());

        final HlsPlaylist pl = media;
        final String useUrl = mediaUrl;
        new Thread(new Runnable() {
            public void run() {
                try {
                    HlsDownloader.Result r = HlsDownloader.download(pl, useUrl, dir, baseName,
                            toMp4, new HlsDownloader.Cb() {
                                public void onProgress(final int done, final int total,
                                                       final long bytes, final long speed) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            if (isFinishing()) return;
                                            pb.setProgress(total <= 0 ? 0 : (int) (done * 100L / total));
                                            long pct = total <= 0 ? 0 : done * 100L / total;
                                            tvStatus.setText("已下载 " + done + "/" + total
                                                    + " 片 · " + bytes(bytes)
                                                    + (speed > 0 ? " · " + bytes(speed) + "/s" : "")
                                                    + " · " + pct + "%");
                                        }
                                    });
                                }
                            }, new HlsDownloader.Escape() {
                                public boolean cancelled() {
                                    return cancel.get();
                                }
                            });
                    final HlsDownloader.Result fr = r;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            working = false;
                            btnCancel.setVisibility(View.GONE);
                            setActionsEnabled(true);
                            pb.setProgress(100);
                            tvStatus.setText("完成：" + fr.file.getName() + " · " + bytes(fr.file.length()));
                            log("完成 → " + fr.file.getAbsolutePath());
                            if (fr.note != null) log(fr.note);
                            showDone(fr);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            working = false;
                            btnCancel.setVisibility(View.GONE);
                            setActionsEnabled(true);
                            tvStatus.setText("失败：" + msg(e));
                            log("失败：" + msg(e));
                        }
                    });
                }
            }
        }, "hls-download").start();
    }

    private void showDone(final HlsDownloader.Result r) {
        StringBuilder m = new StringBuilder();
        m.append(r.file.getAbsolutePath()).append('\n').append(bytes(r.file.length()));
        if (r.note != null) m.append('\n').append('\n').append(r.note);
        new HfDialogBuilder(this)
                .setTitle(r.remuxed ? "已生成 MP4" : "已合并完成")
                .setMessage(m.toString())
                .setPositiveButton("播放", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(HlsDownloadActivity.this, VideoPlayerActivity.class)
                                .putExtra(VideoPlayerActivity.EXTRA_FILE, r.file.getAbsolutePath())
                                .putExtra(VideoPlayerActivity.EXTRA_TITLE, r.file.getName()));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmCancel() {
        if (!working) return;
        new HfDialogBuilder(this)
                .setTitle("取消下载？")
                .setMessage("已经下载的分片会保留，下次对同一地址再点开始时可以接着下。")
                .setPositiveButton("取消下载", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cancel.set(true);
                        btnCancel.setEnabled(false);
                        tvStatus.setText("正在取消…");
                    }
                })
                .setNegativeButton("继续", null)
                .show();
    }

    // ---------------------------------------------------------------- 杂项

    private void showHelp() {
        alert("说明",
                "· 支持 m3u8（HLS）：AES-128 加密、字节区间切片、fMP4 分片都能下。\n"
                + "· 有 master 清单时可选画质；默认取最高一档。\n"
                + "· 「转为 MP4」只是换容器（TS → MP4），不重新编码，画质不变、速度快。\n"
                + "· 转封装失败时会保留 TS 文件 —— TS 本身就能播放。\n"
                + "· 直播流没有 ENDLIST，只能下载点开始那一刻的窗口。\n\n"
                + "不支持的：SAMPLE-AES（Apple DRM）加密、需登录 Cookie 的私有流。");
    }

    private void pasteFromClipboard() {
        String t = getClipboardText();
        String u = StreamUrl.firstUrl(t);
        if (TextUtils.isEmpty(u)) {
            toast("剪贴板里没有可用的链接");
            return;
        }
        etUrl.setText(u);
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateDirRow() {
        rowDir.setText("保存到：" + (outDir == null ? "未选择" : outDir.getAbsolutePath()) + "   ›");
    }

    private void setActionsEnabled(boolean on) {
        btnParse.setEnabled(on);
        btnStart.setEnabled(on);
        rowQuality.setEnabled(on);
        if (on) btnCancel.setVisibility(View.GONE);
    }

    private void log(String s) {
        if (tvLog == null) return;
        long now = System.currentTimeMillis();
        // 日志只做「发生了什么」，不做实时逐片刷屏（那会把 UI 线程拖慢）
        if (now - lastLogAt < 150 && !s.startsWith("完成") && !s.startsWith("失败")) return;
        lastLogAt = now;
        CharSequence old = tvLog.getText();
        String all = (old == null ? "" : old.toString()) + s + "\n";
        if (all.length() > 4000) all = all.substring(all.length() - 4000);
        tvLog.setText(all);
        tvLog.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (!working) {
            super.onBackPressed();
            return;
        }
        new HfDialogBuilder(this)
                .setTitle("正在下载")
                .setMessage("返回会取消本次下载。已下载的分片会保留。")
                .setPositiveButton("取消并返回", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cancel.set(true);
                        finish();
                    }
                })
                .setNegativeButton("继续下载", null)
                .show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void alert(String title, String msg) {
        new HfDialogBuilder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("知道了", null).show();
    }

    private static String msg(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    static String bytes(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    static String clock(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }
}
