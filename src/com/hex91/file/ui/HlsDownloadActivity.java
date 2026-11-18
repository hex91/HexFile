package com.hex91.file.ui;

import com.hex91.file.net.HlsDownloader;
import com.hex91.file.net.HlsError;
import com.hex91.file.net.HlsKey;
import com.hex91.file.net.HlsPlaylist;
import com.hex91.file.net.M3u8Playlist;
import com.hex91.file.net.StreamUrl;
import com.hex91.file.R;
import com.hex91.file.fs.WorkDir;
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
            toast(s(R.string.hls_no_valid_url));
            return null;
        }
        if (!HlsDownloader.isLocal(u)
                && !(u.startsWith("http://") || u.startsWith("https://"))) {
            toast(s(R.string.hls_http_only));
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
        tvResult.setText(s(R.string.hls_parsing));
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
                            tvResult.setText(s(R.string.hls_parse_fail, msg(e)));
                            log(s(R.string.hls_parse_fail, msg(e)));
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
            tvResult.setText(s(R.string.hls_master_no_variant));
            return;
        }
        tvResult.setText(describe(srcUrl, media));
        if (media.isLive()) {
            log(s(R.string.hls_live_note, media.downloadableCount()));
        }
        if (media.hasDiscontinuity) {
            log(s(R.string.hls_discontinuity));
        }
        log(s(R.string.hls_parse_done, media.downloadableCount(), cryptText(media),
                media.likelyFmp4() ? "fMP4" : "MPEG-TS"));
    }

    /** 解析结果的一句话摘要 */
    private String describe(String srcUrl, HlsPlaylist p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.isLive() ? s(R.string.hls_kind_live) : s(R.string.hls_kind_vod));
        if (master != null && master.hasChoice()) {
            sb.append(s(R.string.hls_master_suffix, master.variants().size()));
        }
        sb.append('\n');
        sb.append(s(R.string.hls_segments, p.downloadableCount()));
        long dur = p.totalDurationMs();
        if (dur > 0) sb.append(s(R.string.hls_duration_suffix, clock(dur)));
        sb.append('\n');
        sb.append(s(R.string.hls_encrypt_line, cryptText(p)));
        sb.append(s(R.string.hls_container_suffix, p.likelyFmp4() ? "fMP4" : "MPEG-TS"));
        long est = estimateBytes(srcUrl, p, dur);
        if (est > 0) {
            sb.append('\n').append(s(R.string.hls_est_size, bytes(est)));
        }
        if (p.likelyFmp4()) {
            sb.append('\n').append(s(R.string.hls_fmp4_note));
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
        String v = qualityLabel == null ? s(R.string.hls_quality_auto) : qualityLabel;
        String detail = "";
        if (master != null) {
            M3u8Playlist.Variant var = qualityLabel == null ? null : master.byLabel(qualityLabel);
            if (var != null) detail = " · " + var.detail();
        }
        rowQuality.setText(s(R.string.hls_quality_row, v, detail));
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
                .setTitle(s(R.string.hls_pick_quality))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        M3u8Playlist.Variant v = list.get(which);
                        qualityLabel = v.label;
                        updateQualityRow();
                        loadVariant(v);
                    }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
                .show();
    }

    /** 切档：只重取那一档的子清单（几 KB），不重新下载已下的分片 */
    private void loadVariant(final M3u8Playlist.Variant v) {
        working = true;
        setActionsEnabled(false);
        tvResult.setText(s(R.string.hls_variant_loading, v.label));
        log(s(R.string.hls_switch_log, v.label, v.url));
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
                                tvResult.setText(s(R.string.hls_variant_empty));
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
                            tvResult.setText(s(R.string.hls_variant_fail, msg(e)));
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
            toast(s(R.string.hls_need_parse));
            return;
        }
        HlsKey bad = media.blockingKey();
        if (bad != null) {
            alert(s(R.string.hls_block_title), blockText(bad));
            return;
        }
        final String src = cleanInput();
        final String baseName = HlsDownloader.deriveName(src);
        final boolean toMp4 = cbMp4.isChecked();
        final File dir = outDir;
        if (dir == null) {
            toast(s(R.string.hls_no_dir));
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            toast(s(R.string.hls_dir_create_fail, dir.getAbsolutePath()));
            return;
        }

        working = true;
        cancel.set(false);
        setActionsEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        pb.setVisibility(View.VISIBLE);
        pb.setProgress(0);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(s(R.string.hls_preparing));
        tvLog.setVisibility(View.VISIBLE);
        tvLog.setText("");
        log(s(R.string.hls_saved_to_log, dir.getAbsolutePath()));

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
                                            String sp = speed > 0
                                                    ? s(R.string.hls_speed_suffix, bytes(speed)) : "";
                                            tvStatus.setText(s(R.string.hls_progress,
                                                    done, total, bytes(bytes), sp, pct));
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
                            tvStatus.setText(s(R.string.hls_done_status, fr.file.getName(), bytes(fr.file.length())));
                            log(s(R.string.hls_done_log, fr.file.getAbsolutePath()), true);
                            if (fr.note != null) log(noteText(fr.note, fr.noteArg));
                            showDone(fr);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            working = false;
                            btnCancel.setVisibility(View.GONE);
                            setActionsEnabled(true);
                            tvStatus.setText(s(R.string.hls_fail_status, msg(e)));
                            log(s(R.string.hls_fail_log, msg(e)), true);
                        }
                    });
                }
            }
        }, "hls-download").start();
    }

    private void showDone(final HlsDownloader.Result r) {
        StringBuilder m = new StringBuilder();
        m.append(r.file.getAbsolutePath()).append('\n').append(bytes(r.file.length()));
        if (r.note != null) m.append('\n').append('\n').append(noteText(r.note, r.noteArg));
        new HfDialogBuilder(this)
                .setTitle(r.remuxed ? s(R.string.hls_dlg_mp4_done) : s(R.string.hls_dlg_merged))
                .setMessage(m.toString())
                .setPositiveButton(s(R.string.hls_play), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(HlsDownloadActivity.this, VideoPlayerActivity.class)
                                .putExtra(VideoPlayerActivity.EXTRA_FILE, r.file.getAbsolutePath())
                                .putExtra(VideoPlayerActivity.EXTRA_TITLE, r.file.getName()));
                    }
                })
                .setNegativeButton(s(R.string.common_close), null)
                .show();
    }

    private void confirmCancel() {
        if (!working) return;
        new HfDialogBuilder(this)
                .setTitle(s(R.string.hls_cancel_title))
                .setMessage(s(R.string.hls_cancel_msg))
                .setPositiveButton(s(R.string.hls_cancel_ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cancel.set(true);
                        btnCancel.setEnabled(false);
                        tvStatus.setText(s(R.string.hls_canceling));
                    }
                })
                .setNegativeButton(s(R.string.hls_continue), null)
                .show();
    }

    // ---------------------------------------------------------------- 杂项

    private void showHelp() {
        alert(s(R.string.hls_help_title), s(R.string.hls_help_body));
    }

    private void pasteFromClipboard() {
        String t = getClipboardText();
        String u = StreamUrl.firstUrl(t);
        if (TextUtils.isEmpty(u)) {
            toast(s(R.string.hls_clip_empty));
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
        rowDir.setText(s(R.string.hls_dir_row, outDir == null
                ? s(R.string.hls_dir_unset) : outDir.getAbsolutePath()));
    }

    private void setActionsEnabled(boolean on) {
        btnParse.setEnabled(on);
        btnStart.setEnabled(on);
        rowQuality.setEnabled(on);
        if (on) btnCancel.setVisibility(View.GONE);
    }

    private void log(String line) {
        log(line, false);
    }

    /**
     * 追加一行日志。150ms 内的重复行会被节流（逐片刷屏会拖慢 UI 线程）。
     *
     * <p>{@code force=true} 用于「完成 / 失败」这类收尾行 —— 它们可能紧跟在
     * 一串进度行之后，节流吃掉就看不到结果了。
     *
     * <p>早先这里是 {@code !s.startsWith("完成") && !s.startsWith("失败")}，
     * 靠**文案前缀**判断要不要放行。文案一旦进资源并翻译，这个判断就永远为真，
     * 收尾行会被静默吞掉 —— 所以改成显式的 {@code force} 参数。</p>
     */
    private void log(String line, boolean force) {
        if (tvLog == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastLogAt < 150) return;
        lastLogAt = now;
        CharSequence old = tvLog.getText();
        String all = (old == null ? "" : old.toString()) + line + "\n";
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
                .setTitle(s(R.string.hls_back_title))
                .setMessage(s(R.string.hls_back_msg))
                .setPositiveButton(s(R.string.hls_back_ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cancel.set(true);
                        finish();
                    }
                })
                .setNegativeButton(s(R.string.hls_back_continue), null)
                .show();
    }

    /** 取资源文案（F-14：页面文案全部走 values/values-en） */
    private String s(int id) {
        return getString(id);
    }

    private String s(int id, Object... args) {
        return getString(id, args);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void alert(String title, String msg) {
        new HfDialogBuilder(this).setTitle(title).setMessage(msg)
                .setPositiveButton(s(R.string.common_got_it), null).show();
    }

    /**
     * 异常 → 给人看的一句话。
     *
     * <p>{@link HlsError} 只带错误码，文案在这里按资源取（F-14/10）；
     * 其它异常（SocketTimeoutException 之类）本来就是系统文案，原样透出。</p>
     */
    private String msg(Throwable t) {
        if (t == null) return s(R.string.hls_unknown_error);
        if (t instanceof HlsError) return errorText((HlsError) t);
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /** 加密说明：未加密 / 统一方案名（协议标识，不翻）/ 多把密钥 */
    private String cryptText(HlsPlaylist p) {
        switch (p.crypt()) {
            case UNIFORM:
                return p.uniformKey().label();
            case MIXED:
                return s(R.string.hls_crypt_mixed);
            default:
                return s(R.string.hls_crypt_none);
        }
    }

    /** 解不了的加密：把方案名嵌进去，说清卡在哪一类 */
    private String blockText(HlsKey bad) {
        if (bad == null) return s(R.string.hls_block_title);
        return bad.kind() == HlsKey.Kind.DRM
                ? s(R.string.hls_block_drm, bad.method)
                : s(R.string.hls_block_other, bad.method);
    }

    /** 下载结果的补充说明 */
    private String noteText(HlsDownloader.Note note, String arg) {
        String a = arg == null ? "" : arg;
        switch (note) {
            case SMALL_FILE:
                return s(R.string.hls_note_small, a);
            default:
                return s(R.string.hls_note_remux_fail, a);
        }
    }

    /** 错误码 → 文案（参数是路径 / 长度这类数据，语序交给译文） */
    private String errorText(HlsError e) {
        switch (e.code) {
            case EMPTY_URL:
                return s(R.string.hls_err_empty_url);
            case NO_SEGMENTS:
                return s(R.string.hls_err_no_segments);
            case MASTER_EMPTY:
                return s(R.string.hls_err_master_empty);
            case NOT_M3U8:
                return s(R.string.hls_err_not_m3u8, e.str(0));
            case BLOCKED:
                return blockText((HlsKey) e.arg(0));
            case MKDIR_FAIL:
                return s(R.string.hls_err_mkdir, e.str(0));
            case ALL_GAP:
                return s(R.string.hls_err_all_gap);
            case TMP_DIR_FAIL:
                return s(R.string.hls_err_tmp_dir, e.str(0));
            case KEY_URI_MISSING:
                return s(R.string.hls_err_key_uri);
            case KEY_BAD_SIZE:
                return s(R.string.hls_err_key_size, e.num(0));
            case CANCELLED_KEEP:
                return s(R.string.hls_err_cancelled_keep);
            case CANCELLED:
                return s(R.string.hls_err_cancelled);
            case MERGE_RENAME_FAIL:
                return s(R.string.hls_err_rename, e.str(0));
            case EMPTY_OUTPUT:
                return s(R.string.hls_err_empty_output);
            case SEG_EMPTY:
                return s(R.string.hls_err_seg_empty);
            case RANGE_IGNORED:
                return s(R.string.hls_err_range_ignored, e.num(0), e.num(1));
            case SEG_WRITE_FAIL:
                return s(R.string.hls_err_seg_write, e.str(0));
            case SEG_FETCH_FAIL:
                return s(R.string.hls_err_seg_fetch, msg(e.getCause()));
            case DECRYPT_FAIL:
                return s(R.string.hls_err_decrypt, e.num(0), e.str(1));
            case HTTP_STATUS:
                return s(R.string.hls_err_http, e.num(0), e.str(1));
            case TOO_LARGE:
                return s(R.string.hls_err_too_large, e.num(0));
            case SHORT_RANGE:
                return s(R.string.hls_err_short_range);
            case FILE_MISSING:
                return s(R.string.hls_err_file_missing, e.str(0));
            case RANGE_BEYOND_EOF:
                return s(R.string.hls_err_range_eof, e.str(0));
            default:
                // 新增了错误码却忘了加译文时，至少把诊断串露出来，别显示空白
                return s(R.string.hls_err_generic, e.getMessage());
        }
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
