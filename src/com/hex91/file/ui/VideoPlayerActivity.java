package com.hex91.file.ui;

import com.hex91.file.utils.*;
import com.hex91.file.*;
import com.hex91.file.net.*;
import com.hex91.file.apk.*;
import com.hex91.file.R;
import com.hex91.file.core.*;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.TextureView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 内置视频播放器 —— v1.91.0，播放内核为 Media3 ExoPlayer 1.4.1。
 *
 * <p>为什么不用 media3-ui：本工程是 aapt2 + javac 手写构建链，没有 AGP 的资源合并，
 * 引 media3-ui 等于手工搬一整套 layout/values/drawable 与它的 AppCompat 依赖。
 * 自绘控制层反而更贴合 MT 风格，也能做到「一排胶囊按钮」这种紧凑布局。</p>
 *
 * <p>能力清单（对应需求）：</p>
 * <ul>
 *   <li><b>本地/网络/串流</b>：mp4/mkv/webm/ts/mov/avi/flv/3gp 等容器由 media3-extractor 解析；
 *       HLS(m3u8)、DASH(mpd)、RTSP、渐进下载全覆盖。</li>
 *   <li><b>倍速</b>：0.25×~3.0× 共 10 档（见 {@link PlaySpeed}），长按画面临时 3 倍、松手还原。</li>
 *   <li><b>画质</b>：解析 m3u8 master 的多档码率，可锁死某一档（ABR 关掉），也能回到「自动」。</li>
 *   <li><b>不压缩</b>：TrackSelector 不设最大分辨率/码率上限，画面按源分辨率等比映射；
 *       只有用户显式切「铺满」才裁切，切「拉伸」才变形 —— 默认永不动比例。</li>
 *   <li><b>手势</b>：单击显隐控制条、双击暂停、横滑快进/快退、左竖滑亮度、右竖滑音量。</li>
 *   <li><b>断点续播</b>：本地文件退出时记位点，下次进来从上次处继续（≥5 秒且未看完才记）。</li>
 * </ul>
 *
 * <p>刻意没做的事：不接管音量键以外的系统按键、不做后台播放（文件管理器里的播放器
 * 一旦切后台就该停，否则用户会莫名其妙听到声音）、不做投屏。</p>
 */
public class VideoPlayerActivity extends BaseActivity {

    /** 本地文件绝对路径 */
    public static final String EXTRA_FILE = "path";
    /** 网络地址（http/https/rtsp） */
    public static final String EXTRA_URL = "url";
    /** 覆盖标题（不传则按文件名/地址末段） */
    public static final String EXTRA_TITLE = "title";
    /** 起播位置（毫秒），用于「继续播放」 */
    public static final String EXTRA_START_MS = "start_ms";

    private static final int HIDE_DELAY_MS = 3600;
    private static final String TAG = "HexPlayer";
    private static final int LONG_PRESS_MS = 460;
    private static final int DOUBLE_TAP_MS = 280;
    private static final long RESUME_MIN_MS = 5000;

    // ---------------- 播放内核
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private TextureView texture;

    // ---------------- 界面
    private View root, topBar, centerBar, bottomBar, gesture;
    private TextView titleView, playBtn, posView, durView, hintView, msgView;
    private TextView speedChip, qualityChip, scaleChip, loopChip, orientChip, fsChip;
    private SeekBar seekBar;
    private ProgressBar bufferView;
    private volatile boolean controlsVisible = true;

    // ---------------- 播放列表
    /** 每一项是「可直接播放的地址」：本地绝对路径或 http(s)/rtsp 地址 */
    private final List<String> sources = new ArrayList<String>();
    private int index = 0;
    /** 只有单个网络地址时为 true（决定画质/信息面板的措辞） */
    private boolean singleUrlMode;

    // ---------------- 状态
    private float speed = 1f;
    private int repeatMode = Player.REPEAT_MODE_OFF;
    private int scaleMode = PlayerPrefs.SCALE_FIT;
    /** 画面方向：自动（按视频比例）/ 竖屏锁定 / 横屏锁定 */
    private int orientMode = ScreenPolicy.AUTO;
    /** 本片的方向是否已决定过 —— 只让第一帧的 VideoSize 触发一次转屏 */
    private boolean orientationDecided;
    /** 按返回键临时转竖屏（不改用户偏好，只本片有效） */
    private boolean backPortrait;
    /** v1.94.0：VideoSize 是屏幕上「视频本身的宽×高」，不含像素宽高比校正 */
    private int videoW, videoH;
    private long durationMs;
    private boolean seekBarTouching;
    private String displayTitle = "";
    /** m3u8 master 解析结果；null = 还没解析或不是 HLS */
    private M3u8Playlist qualities;
    /** 当前锁定的画质标签，"自动" 表示不锁定 */
    private String qualityLabel = "自动";
    /** 解析画质时用的基准地址（HLS 主列表自身地址） */
    private String qualityBase;

    private final Handler ui = new Handler(Looper.getMainLooper());
    // ============================================================ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();

        bindViews();
        readIntent();
        buildQueue();
        if (sources.isEmpty()) {
            showMessage("没有可播放的内容：既没有文件路径，也没有可用地址");
            return;
        }
        setupPlayer();
        // index 由 buildQueue 指向「用户点的那个文件」在目录播放列表里的位置
        playCurrent(index);
    }

    private void bindViews() {
        root = findViewById(R.id.vp_root);
        texture = (TextureView) findViewById(R.id.vp_texture);
        gesture = findViewById(R.id.vp_gesture);
        topBar = findViewById(R.id.vp_top);
        centerBar = findViewById(R.id.vp_center);
        bottomBar = findViewById(R.id.vp_bottom);
        titleView = (TextView) findViewById(R.id.vp_title);
        playBtn = (TextView) findViewById(R.id.vp_play);
        posView = (TextView) findViewById(R.id.vp_pos);
        durView = (TextView) findViewById(R.id.vp_dur);
        hintView = (TextView) findViewById(R.id.vp_hint);
        msgView = (TextView) findViewById(R.id.vp_msg);
        speedChip = (TextView) findViewById(R.id.vp_speed);
        qualityChip = (TextView) findViewById(R.id.vp_quality);
        scaleChip = (TextView) findViewById(R.id.vp_scale);
        loopChip = (TextView) findViewById(R.id.vp_loop);
        orientChip = (TextView) findViewById(R.id.vp_orient);
        fsChip = (TextView) findViewById(R.id.vp_fs);
        seekBar = (SeekBar) findViewById(R.id.vp_seek);
        bufferView = (ProgressBar) findViewById(R.id.vp_buffer);

        // 容器尺寸一变（转屏、分屏、挖孔/手势条变化）就必须重算画面盒子。
        // v1.93.0 及以前只在 onVideoSizeChanged 里算一次，若那一刻还没 layout
        // （getWidth()==0），函数直接 return，此后没人再算 —— 表现就是
        // 「进播放器画面被拉满，比例全乱」，转到横屏反而好了。
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) applyScale();
            }
        });

        findViewById(R.id.vp_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 顶栏返回箭头必须与系统返回键同一条路径：横屏时先回竖屏继续播，
                // 竖屏才退出。以前这里直接 finish()，于是横屏点一下就整个退出到文件列表。
                if (!stepBackToPortrait()) exitPlayer();
            }
        });
        findViewById(R.id.vp_fs).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleFullscreen(); }
        });
        findViewById(R.id.vp_menu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(); }
        });
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePlay(); }
        });
        findViewById(R.id.vp_prev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stepItem(-1); }
        });
        findViewById(R.id.vp_next).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stepItem(1); }
        });
        findViewById(R.id.vp_orient).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.util.Log.i(TAG, "orient chip clicked, mode=" + orientMode);
                cycleOrientation();
            }
        });

        speedChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSpeedDialog(); }
        });
        qualityChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showQualityDialog(); }
        });
        scaleChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleScale(); }
        });
        loopChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleLoop(); }
        });
        findViewById(R.id.vp_info).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showInfo(); }
        });

        gesture.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) { return handleTouch(e); }
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || durationMs <= 0) return;
                posView.setText(fmtTime(durationMs * p / sb.getMax()));
            }

            public void onStartTrackingTouch(SeekBar sb) {
                seekBarTouching = true;
                cancelAutoHide();
            }

            public void onStopTrackingTouch(SeekBar sb) {
                seekBarTouching = false;
                if (durationMs > 0 && player != null) {
                    player.seekTo(durationMs * sb.getProgress() / sb.getMax());
                }
                scheduleAutoHide();
            }
        });
    }

    /** 读 Intent：支持 EXTRA_FILE / EXTRA_URL / 第三方 VIEW 过来的 data */
    private void readIntent() {
        Intent it = getIntent();
        String f = it.getStringExtra(EXTRA_FILE);
        String u = it.getStringExtra(EXTRA_URL);
        if (f == null && u == null && it.getData() != null) {
            String ds = it.getData().toString();
            // content:// 也当本地处理（DocumentProvider 给的就是它）
            if (ds.startsWith("content://") || ds.startsWith("file://")) f = ds;
            else u = ds;
        }
        String t = it.getStringExtra(EXTRA_TITLE);
        if (t != null && !t.trim().isEmpty()) displayTitle = t.trim();

        if (u != null) {
            String clean = StreamUrl.normalize(u);
            if (clean == null) {
                showMessage("不是可播放的地址：\n" + u);
                return;
            }
            sources.add(clean);
            singleUrlMode = true;
            if (displayTitle.isEmpty()) displayTitle = StreamUrl.fileName(clean);
        } else if (f != null) {
            if (f.startsWith("file://")) {
                try {
                    String p = Uri.parse(f).getPath();
                    if (p != null && !p.isEmpty()) f = p;
                } catch (Throwable ignore) {
                }
            }
            sources.add(f);
        }
    }

    /** 本地文件 → 同目录视频组成播放列表（自动连播、上一个/下一个才有意义） */
    private void buildQueue() {
        String first = sources.isEmpty() ? null : sources.get(0);
        if (first == null || singleUrlMode || first.startsWith("content://")) {
            if (displayTitle.isEmpty()) displayTitle = titleOf(first);
            return;
        }
        File f = new File(first);
        if (!MimeUtil.isVideo(f.getName())) {
            // 清单文件 / 非视频：不做目录连播（HLS 目录里全是 .ts 切片，连播毫无意义）
            displayTitle = displayTitle.isEmpty() ? titleOf(first) : displayTitle;
            return;
        }
        File dir = f.getParentFile();
        if (dir == null) {
            displayTitle = displayTitle.isEmpty() ? f.getName() : displayTitle;
            return;
        }
        File[] all = dir.listFiles();
        if (all == null) {
            displayTitle = displayTitle.isEmpty() ? f.getName() : displayTitle;
            return;
        }
        List<File> vids = new ArrayList<File>();
        for (File c : all) {
            if (c.isFile() && MimeUtil.isVideo(c.getName())) vids.add(c);
        }
        if (vids.size() <= 1) {
            displayTitle = displayTitle.isEmpty() ? f.getName() : displayTitle;
            return;
        }
        java.util.Collections.sort(vids, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        sources.clear();
        int at = 0;
        for (int i = 0; i < vids.size(); i++) {
            File v = vids.get(i);
            sources.add(v.getAbsolutePath());
            if (v.getAbsolutePath().equals(f.getAbsolutePath())) at = i;
        }
        index = at;
        if (displayTitle.isEmpty()) displayTitle = f.getName();
    }

    // ============================================================ 播放器

    private void setupPlayer() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent())
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                // 很多 CDN 是 https 页面 → http 回源、或 302 到另一个主机；
                // 不允许跨协议跳转时表现为「点开就报 3011」，而用浏览器打开是正常的
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory ds = new DefaultDataSource.Factory(this, http);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(ds);

        trackSelector = new DefaultTrackSelector(this);
        // 「不压缩」的落地：显式把上限开到最大。默认值本来也是不限，
        // 但写出来可以防止以后有人加「省流模式」把这里悄悄改成 720p 封顶。
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                .setMaxVideoBitrate(Integer.MAX_VALUE)
                .setMaxAudioBitrate(Integer.MAX_VALUE)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true));

        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                // 硬解初始化失败时自动退到下一个解码器（部分 HEVC/AV1 片源靠它救回来）
                .setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this)
                .setRenderersFactory(rf)
                .setMediaSourceFactory(msf)
                .setTrackSelector(trackSelector)
                .build();

        player.setVideoTextureView(texture);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        // 拔耳机/蓝牙断开自动暂停（否则会突然外放）
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);

        speed = PlayerPrefs.speed(this);
        repeatMode = PlayerPrefs.repeat(this);
        scaleMode = PlayerPrefs.scale(this);
        // 拉伸档不记忆：它是「我就要看变形」的一次性选择，不该成为下次打开时的默认，
        // 否则用户误点一次就永久得到一部变形的片子（v1.93.0 之前就是这样被投诉的）。
        if (scaleMode == PlayerPrefs.SCALE_STRETCH) scaleMode = PlayerPrefs.SCALE_FIT;
        orientMode = PlayerPrefs.orientation(this);
        player.setRepeatMode(repeatMode);
        player.setPlaybackParameters(new PlaybackParameters(speed));

        player.addListener(new Player.Listener() {
            public void onPlaybackStateChanged(int state) {
                boolean buffering = state == Player.STATE_BUFFERING;
                bufferView.setVisibility(buffering && player.getPlayWhenReady()
                        ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    hideMessage();
                    durationMs = player.getDuration();
                    updateTimeText();
                }
                updatePlayUi();
                if (state == Player.STATE_ENDED) onEnded();
            }

            public void onIsPlayingChanged(boolean playing) {
                updatePlayUi();
                if (playing) scheduleAutoHide();
                else showControls();
            }

            public void onVideoSizeChanged(VideoSize size) {
                videoW = size.width;
                videoH = size.height;
                if (size.pixelWidthHeightRatio > 0 && size.pixelWidthHeightRatio != 1f) {
                    videoW = Math.round(size.width * size.pixelWidthHeightRatio);
                }
                // 先按比例摆好画面盒子，再决定横竖屏：转屏会改变容器尺寸，
                // 布局监听会再算一次，两次都算对人眼才看不到中间态
                applyScale();
                maybeAutoOrient();
            }

            public void onPlayerError(PlaybackException error) {
                showMessage(describeError(error));
                bufferView.setVisibility(View.GONE);
                playBtn.setText("▶");
            }

            public void onMediaItemTransition(MediaItem item, int reason) {
                index = player.getCurrentMediaItemIndex();
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    // 换画质时整张播放列表被替换过：此时 index 已对齐，但不能把
                    // 用户刚锁定的画质重置成「自动」，否则锁了等于没锁
                    if (item != null && item.mediaMetadata.title != null) {
                        titleView.setText(item.mediaMetadata.title);
                    }
                    return;
                }
                if (item != null) {
                    CharSequence t = item.mediaMetadata.title;
                    if (t != null && t.length() > 0) displayTitle = t.toString();
                    titleView.setText(displayTitle);
                }
                // 换了片子，画质列表必须重解析（不同片子档位不同）
                qualities = null;
                qualityLabel = "自动";
                // 方向也重新判：横屏剧集切到竖屏短视频时该跟着回竖屏
                orientationDecided = false;
                backPortrait = false;
                updateChips();
                loadQualitiesAsync();
            }
        });

        // 面板初始状态
        titleView.setText(displayTitle);
        speedChip.setText(PlaySpeed.label(speed));
        updateChips();
        updateLoopChip();
    }

    private void playCurrent(int at) {
        if (player == null || sources.isEmpty()) return;
        index = at < 0 ? 0 : (at >= sources.size() ? sources.size() - 1 : at);
        List<MediaItem> items = new ArrayList<MediaItem>();
        for (String s : sources) items.add(buildItem(s));
        long start = getIntent().getLongExtra(EXTRA_START_MS, 0);
        long resume = start > 0 ? start : savedPosition(sources.get(index));
        if (resume > 0 && !isStream(sources.get(index))) {
            toast("从上次位置继续：" + fmtTime(resume));
        }
        player.setMediaItems(items, index, resume > 0 ? resume : 0);
        player.prepare();
        player.setPlayWhenReady(true);
        titleView.setText(displayTitle);
        updatePlayUi();
        scheduleAutoHide();
    }

    private MediaItem buildItem(String src) {
        Uri uri = uriOf(src);
        MediaItem.Builder b = new MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(titleOf(src)).build());
        String mime = mimeOf(src);
        if (mime != null) b.setMimeType(mime);
        return b.build();
    }

    private static Uri uriOf(String src) {
        if (src == null) return Uri.EMPTY;
        if (src.startsWith("content://") || src.startsWith("file://")) return Uri.parse(src);
        if (src.indexOf("://") > 0) {
            // 裸空格会让 HTTP 层直接抛 IllegalArgumentException，先编码掉
            return Uri.parse(src.replace(" ", "%20"));
        }
        return Uri.fromFile(new File(src));
    }

    private static String mimeOf(String src) {
        int kind = StreamUrl.kindOf(src);
        if (kind == StreamUrl.KIND_RTSP) return MimeTypes.APPLICATION_RTSP;
        if (kind == StreamUrl.KIND_HLS) return MimeTypes.APPLICATION_M3U8;
        if (kind == StreamUrl.KIND_DASH) return MimeTypes.APPLICATION_MPD;
        if (kind == StreamUrl.KIND_UNKNOWN) return null;
        // 本地/渐进流：让 media3 按扩展名嗅探（mkv/flv/avi 都是靠扩展名进 extractor）
        return null;
    }

    private String titleOf(String src) {
        if (src == null) return "";
        if (src.startsWith("content://")) return displayTitle.isEmpty() ? src : displayTitle;
        if (src.indexOf("://") > 0) return StreamUrl.fileName(src);
        String n = new File(src).getName();
        return n.isEmpty() ? src : n;
    }

    // ============================================================ 播放控制

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            player.prepare();
            player.setPlayWhenReady(true);
            return;
        }
        player.setPlayWhenReady(!player.getPlayWhenReady());
        if (player.getPlayWhenReady()) scheduleAutoHide();
    }

    private void stepItem(int dir) {
        if (player == null) return;
        if (dir < 0 && player.getCurrentPosition() > 3000) {
            // 和大多数播放器一致：播放超过 3 秒时「上一个」先回到本片开头，
            // 放在边界判断之前 —— 第一集播了 10 分钟后按「上一个」也该回到 00:00
            player.seekTo(0);
            return;
        }
        int target = player.getCurrentMediaItemIndex() + dir;
        if (target < 0) {
            toast("已经是第一个");
            return;
        }
        if (target >= sources.size()) {
            toast("已经是最后一个");
            return;
        }
        savePosition();
        player.seekTo(target, 0);
        player.setPlayWhenReady(true);
    }

    private void onEnded() {
        if (repeatMode == Player.REPEAT_MODE_OFF && index >= sources.size() - 1) {
            showControls();
            player.setPlayWhenReady(false);
        }
    }

    private void updatePlayUi() {
        if (player == null) return;
        playBtn.setText(player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED
                ? "⏸" : "▶");
        findViewById(R.id.vp_prev).setAlpha(index > 0 ? 1f : 0.4f);
        findViewById(R.id.vp_next).setAlpha(index < sources.size() - 1 ? 1f : 0.4f);
    }

    private void updateTimeText() {
        if (durationMs > 0) durView.setText(fmtTime(durationMs));
        else durView.setText(isLive() ? "直播" : "--:--");
        posView.setText(fmtTime(player == null ? 0 : player.getCurrentPosition()));
        if (durationMs > 0 && seekBar != null) {
            seekBar.setEnabled(true);
            seekBar.setProgress((int) (player.getCurrentPosition() * 1000 / durationMs));
        } else if (seekBar != null) {
            // 直播/未知时长：进度条不能假装能拖（拖了也只是重新开始缓冲）
            seekBar.setEnabled(false);
            seekBar.setProgress(0);
        }
    }

    /** 500ms 心跳：只在播放中走动，暂停后不再刷新，省电 */
    private final Runnable tick = new Runnable() {
        public void run() {
            if (player == null) return;
            if (!seekBarTouching) updateTimeText();
            if (player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED) {
                ui.postDelayed(this, 500);
            }
        }
    };

    private void startTicking() {
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void cancelTicking() {
        ui.removeCallbacks(tick);
    }

    // ============================================================ 倍速

    private void applySpeed(float s) {
        speed = PlaySpeed.clamp(s);
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
        PlayerPrefs.setSpeed(this, speed);
        speedChip.setText(PlaySpeed.label(speed));
    }

    private void showSpeedDialog() {
        final float[] steps = PlaySpeed.STEPS;
        String[] labels = new String[steps.length];
        int checked = PlaySpeed.indexOf(speed);
        for (int i = 0; i < steps.length; i++) {
            labels[i] = PlaySpeed.label(steps[i])
                    + (Math.abs(steps[i] - 1f) < 0.001f ? "（原速）" : "")
                    + (Math.abs(steps[i] - PlaySpeed.BOOST) < 0.001f ? "（上限）" : "");
        }
        new HfDialogBuilder(this)
                .setTitle("播放速度（最高 3.0×）")
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        applySpeed(steps[which]);
                        d.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        showControls();
    }

    // ============================================================ 画质

    /**
     * 异步解析 m3u8 master。网络地址走 HTTP 读前 256KB（主列表没有那么大），
     * 本地文件直接读；失败就静默不提供画质切换 —— 播放本身不受影响。
     */
    private void loadQualitiesAsync() {
        if (sources.isEmpty()) return;
        final String src = sources.get(index);
        final boolean hls = StreamUrl.kindOf(src) == StreamUrl.KIND_HLS
                || src.toLowerCase(Locale.US).endsWith(".m3u8");
        if (!hls) {
            qualities = null;
            qualityBase = null;
            updateQualityChip();
            return;
        }
        final String base = src.startsWith("content://") || src.startsWith("file://")
                ? src : src;
        new Thread(new Runnable() {
            public void run() {
                final M3u8Playlist p = fetchPlaylist(src, base);
                runOnUiThread(new Runnable() {
                    public void run() {
                        qualities = p;
                        qualityBase = base;
                        if (p != null && p.hasChoice()) {
                            qualityChip.setVisibility(View.VISIBLE);
                        }
                        updateQualityChip();
                    }
                });
            }
        }, "vp-m3u8").start();
    }

    private M3u8Playlist fetchPlaylist(String src, String base) {
        String text = null;
        try {
            if (src.startsWith("http://") || src.startsWith("https://")) {
                String u = src.replace(" ", "%20");
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", userAgent());
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 262144) {
                    bos.write(buf, 0, n);
                    total += n;
                }
                in.close();
                c.disconnect();
                text = new String(bos.toByteArray(), "UTF-8");
            } else {
                File f = new File(src.startsWith("file://") ? src.substring(7) : src);
                if (f.isFile() && f.length() < 4 * 1024 * 1024) {
                    byte[] all = FileInspect.readHead(f, (int) Math.min(f.length(), 262144));
                    if (all != null) text = new String(all, "UTF-8");
                }
            }
        } catch (Throwable t) {
            return null;
        }
        if (text == null) return null;
        String b = base;
        // 本地 m3u8：变体是相对路径，得用文件所在目录当基准
        if (!b.contains("://")) {
            File f = new File(b);
            File dir = f.getParentFile();
            b = dir == null ? b : "file://" + dir.getAbsolutePath() + "/";
        }
        return M3u8Playlist.parseOrEmpty(text, b);
    }

    private void updateChips() {
        updateQualityChip();
        updateScaleChip();
        updateLoopChip();
        updateOrientChip();
        updateFsChip();
        if (speedChip != null) speedChip.setText(PlaySpeed.label(speed));
    }

    private static boolean isHls(String src) {
        if (src == null) return false;
        if (StreamUrl.kindOf(src) == StreamUrl.KIND_HLS) return true;
        String n = src.toLowerCase(Locale.US);
        return n.endsWith(".m3u8") || n.endsWith(".m3u");
    }

    private void updateQualityChip() {
        if (sources.isEmpty() || !isHls(sources.get(index))) {
            qualityChip.setText("画质 源");
        } else if (qualities == null) {
            qualityChip.setText("画质 …");
        } else if (!qualities.hasChoice()) {
            qualityChip.setText("画质 源");
        } else {
            qualityChip.setText("画质 " + qualityLabel);
        }
    }

    private void showQualityDialog() {
        if (qualities == null || !qualities.hasChoice()) {
            String extra = qualities == null
                    ? "（不是 m3u8 主列表，或列表读取失败）"
                    : "（该片源只有一路码率）";
            new HfDialogBuilder(this)
                    .setTitle("画质")
                    .setMessage("这个片源没有多档画质可选" + extra
                            + "\n\n播放器按源分辨率原样解码，不做任何压缩或降采样。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final List<M3u8Playlist.Variant> list = qualities.byQualityDesc();
        String[] labels = new String[list.size() + 1];
        labels[0] = "自动（按网速自适应）";
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            M3u8Playlist.Variant v = list.get(i);
            labels[i + 1] = v.label + "   " + v.detail();
            if (v.label.equals(qualityLabel)) checked = i + 1;
        }
        new HfDialogBuilder(this)
                .setTitle("选择画质（锁定后不再自适应）")
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        switchQuality(which == 0 ? null : list.get(which - 1));
                        d.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 锁画质：把当前这一项换成该档的子播放列表地址重新 prepare。
     * 位置保持不动，代价是一次重新缓冲（无法避免 —— ABR 关闭必须让播放器只看到一路流）。
     */
    private void switchQuality(M3u8Playlist.Variant v) {
        if (player == null || sources.isEmpty()) return;
        int idx = player.getCurrentMediaItemIndex();
        long pos = player.getCurrentPosition();
        boolean play = player.getPlayWhenReady();
        String target = v == null ? qualityBase : v.url;
        if (target == null) {
            toast("原始地址已失效，请重新打开");
            return;
        }
        sources.set(idx, target);
        qualityLabel = v == null ? "自动" : v.label;
        List<MediaItem> items = new ArrayList<MediaItem>();
        for (String s : sources) items.add(buildItem(s));
        player.setMediaItems(items, idx, pos);
        player.prepare();
        player.setPlayWhenReady(play);
        updateQualityChip();
        toast(v == null ? "画质：自动" : "已锁定 " + v.label);
    }

    // ============================================================ 缩放 / 循环 / 方向

    private void cycleScale() {
        scaleMode = (scaleMode + 1) % 3;
        // 只记住「适配 / 铺满」，拉伸不落盘 —— 见 setupPlayer 里的说明
        PlayerPrefs.setScale(this, scaleMode == PlayerPrefs.SCALE_STRETCH
                ? PlayerPrefs.SCALE_FIT : scaleMode);
        updateScaleChip();
        applyScale();
        toast("画面：" + scaleText());
    }

    private String scaleText() {
        return scaleMode == PlayerPrefs.SCALE_CROP ? "铺满（等比，裁掉边缘）"
                : scaleMode == PlayerPrefs.SCALE_STRETCH ? "拉伸（比例会变形）" : "适配（等比、完整可见）";
    }

    private void updateScaleChip() {
        scaleChip.setText(scaleMode == PlayerPrefs.SCALE_CROP ? "铺满"
                : scaleMode == PlayerPrefs.SCALE_STRETCH ? "拉伸·变形" : "适配");
    }

    /**
     * 按视频比例摆放画面盒子（v1.94.0 起不再用 TextureView 的 transform）。
     *
     * <p>transform 方案有两个硬伤：① 依赖调用时机 —— view 还没 layout 时
     * {@code getWidth()==0} 只能跳过，之后没有任何地方会再算一次，画面就按
     * TextureView 的默认行为被拉满整个窗口（横视频在竖屏里被压扁/拉长）；
     * ② 渲染器自己也会写 transform 做像素宽高比校正，两边抢着设，
     * 谁生效取决于回调顺序 —— 表现为「有时按比例、有时变形」。</p>
     *
     * <p>改走布局：TextureView 的宽高设成「视频比例 × 缩放系数」并居中。
     * 适配留黑、铺满溢出被裁，只有拉伸档才满屏变形。布局天然跟着容器尺寸重算，
     * 转屏/分屏/挖孔变化都不需要额外补偿。</p>
     */
    private void applyScale() {
        if (texture == null || root == null) return;
        int cw = root.getWidth(), ch = root.getHeight();
        if (cw <= 0 || ch <= 0) return;              // 还没 layout，等布局监听再来
        int w, h;
        float s = scaleMode == PlayerPrefs.SCALE_STRETCH ? 0f
                : ScreenPolicy.scaleFor(scaleMode == PlayerPrefs.SCALE_CROP,
                videoW, videoH, cw, ch);
        if (s <= 0f) {
            w = cw;
            h = ch;                                   // 拉伸档，或视频尺寸还没解析出来
        } else {
            w = Math.max(1, Math.round(videoW * s));
            h = Math.max(1, Math.round(videoH * s));
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) texture.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(w, h);
        } else if (lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) {
            return;                                   // 没变就不动，避免反复 requestLayout
        }
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        texture.setLayoutParams(lp);
    }

    // ---------------------------------------------------------- 方向

    /**
     * 当前屏幕是不是横的。
     *
     * <p>不用 {@code getResources().getConfiguration().orientation}：系统字号被调出
     * [0.90,1.10] 时，{@link UiKit#alignMetrics} 会用 {@code createConfigurationContext}
     * 生成一份**页面创建时刻的配置快照**，转屏后它不会更新 —— 实测小米/Pixel 上
     * 横屏播放页里读到的一直是竖屏，导致「横屏点返回先回竖屏」判断失败、直接退出应用。</p>
     *
     * <p>改用窗口实际像素尺寸判断，这是唯一不会被配置快照骗到的事实。</p>
     */
    private boolean isLandscapeNow() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return dm.widthPixels > dm.heightPixels;
            }
        } catch (Throwable ignored) {
            // 极端情况（无窗口）退回到 View 尺寸
        }
        return root != null && root.getWidth() > root.getHeight();
    }

    /**
     * 按「档位 + 视频比例」请求屏幕方向。
     *
     * <p>用 SENSOR_* 而不是 LANDSCAPE/PORTRAIT：允许用户把手机上下颠倒继续看，
     * 横屏时也能左右手互换握持方向。</p>
     */
    private void applyOrientation() {
        boolean want = ScreenPolicy.wantLandscape(orientMode, videoW, videoH, isLandscapeNow());
        // 按过返回回竖屏后，本篇不再自动转回横屏（换片或用户重选档位时才会清掉 backPortrait）。
        // 这里不能只判 AUTO：手动选了「横屏」档的用户按返回同样期望停在竖屏。
        if (backPortrait) want = false;
        int target = want ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        android.util.Log.i(TAG, "applyOrientation mode=" + orientMode + " video=" + videoW + "x" + videoH
                + " land=" + isLandscapeNow() + " want=" + want + " target=" + target
                + " cur=" + getRequestedOrientation());
        if (getRequestedOrientation() != target) setRequestedOrientation(target);
        scheduleReframe();
    }

    /** 转屏过程中容器尺寸会连续变化，多打几个时间点，保证最后一帧的比例是对的 */
    private void scheduleReframe() {
        final int[] delays = {60, 180, 360, 700};
        for (int i = 0; i < delays.length; i++) {
            ui.postDelayed(new Runnable() {
                public void run() { applyScale(); }
            }, delays[i]);
        }
    }

    /** 第一帧的 VideoSize 到达后决定横竖屏；每换一片只判一次，不跟用户手动选择打架 */
    private void maybeAutoOrient() {
        if (orientationDecided) return;
        if (videoW <= 0 || videoH <= 0) return;
        orientationDecided = true;
        android.util.Log.i(TAG, "maybeAutoOrient video=" + videoW + "x" + videoH + " mode=" + orientMode);
        applyOrientation();
    }

    /** 底栏「方向」胶囊：自动 → 竖屏 → 横屏 → 自动 */
    private void cycleOrientation() {
        applyOrientMode(ScreenPolicy.next(orientMode));
    }

    /** 选定一个方向档位：持久化 + 立即生效 + 刷新胶囊文案 */
    private void applyOrientMode(int mode) {
        orientMode = ScreenPolicy.normalize(mode);
        backPortrait = false;
        PlayerPrefs.setOrientation(this, orientMode);
        updateOrientChip();
        applyOrientation();
        toast("画面方向：" + ScreenPolicy.describe(orientMode));
    }

    /** 三选一对话框：比「连点胶囊三次」直观，尤其是只想锁定一个方向的时候 */
    private void showOrientationDialog() {
        final int[] modes = {ScreenPolicy.AUTO, ScreenPolicy.PORTRAIT, ScreenPolicy.LANDSCAPE};
        String[] labels = {
                "自动 —— 横视频横屏、竖视频竖屏",
                "竖屏 —— 锁定，横视频等比缩小（上下留黑）",
                "横屏 —— 锁定，竖视频等比缩小（左右留黑）"
        };
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == orientMode) checked = i;
        }
        new HfDialogBuilder(this)
                .setTitle("画面方向")
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        applyOrientMode(modes[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        showControls();
    }

    private void updateOrientChip() {
        if (orientChip != null) orientChip.setText(ScreenPolicy.label(orientMode));
    }

    /** 右下角那颗固定按钮：竖屏是「撑开 + 全屏」，横屏是「竖屏手机 + 竖屏」 */
    private void updateFsChip() {
        if (fsChip == null) return;
        boolean land = isLandscapeNow();
        fsChip.setText(land ? "竖屏" : "全屏");
        fsChip.setCompoundDrawablesWithIntrinsicBounds(
                land ? R.drawable.ic_vp_portrait : R.drawable.ic_vp_expand, 0, 0, 0);
    }

    /**
     * 右下角全屏/竖屏按钮 —— 一步切换，不像「方向」胶囊那样要循环三档。
     *
     * <p>只改「本次」方向，不动持久化的 orientMode：临时全屏看一眼就退出来的场景
     * 不该把用户的长期偏好改掉。横屏→竖屏直接复用返回键那套（backPortrait），
     * 避免下次换片时又被自动转回横屏。</p>
     */
    private void toggleFullscreen() {
        if (isLandscapeNow()) {
            stepBackToPortrait();
        } else {
            backPortrait = false;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            scheduleReframe();
            android.util.Log.i(TAG, "fs: portrait -> landscape (mode=" + orientMode + ")");
        }
        updateFsChip();
        // 转屏是异步的，落地后再对一次（否则文字停在旧值）
        ui.postDelayed(new Runnable() {
            public void run() { updateFsChip(); }
        }, 450);
        showControls();
    }

    private void cycleLoop() {
        repeatMode = repeatMode == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ONE
                : repeatMode == Player.REPEAT_MODE_ONE ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF;
        PlayerPrefs.setRepeat(this, repeatMode);
        if (player != null) player.setRepeatMode(repeatMode);
        updateLoopChip();
    }

    private void updateLoopChip() {
        loopChip.setText(repeatMode == Player.REPEAT_MODE_ONE ? "单个"
                : repeatMode == Player.REPEAT_MODE_ALL ? "列表" : "关");
    }

    // ============================================================ 信息 / 错误

    private void showInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(displayTitle).append('\n');
        sb.append("来源：").append(isStream(sources.get(index))
                ? StreamUrl.kindLabel(StreamUrl.kindOf(sources.get(index))) : "本地文件").append('\n');
        if (player != null) {
            Format v = player.getVideoFormat();
            if (v != null) {
                sb.append("分辨率：").append(v.width > 0 ? v.width + "×" + v.height : "未知").append('\n');
                if (v.frameRate > 0) sb.append("帧率：").append(trimNum(v.frameRate)).append(" fps\n");
                if (v.bitrate > 0) sb.append("视频码率：").append(M3u8Playlist.mbpsText(v.bitrate)).append('\n');
                if (v.sampleMimeType != null) sb.append("编码：").append(v.sampleMimeType).append('\n');
                if (v.codecs != null && !v.codecs.isEmpty()) sb.append("codec：").append(v.codecs).append('\n');
            } else {
                sb.append("分辨率：尚未解析出视频轨\n");
            }
            Format a = player.getAudioFormat();
            if (a != null) {
                sb.append("音频：").append(a.sampleMimeType == null ? "未知" : a.sampleMimeType);
                if (a.channelCount > 0) sb.append(" · ").append(a.channelCount).append(" 声道");
                if (a.sampleRate > 0) sb.append(" · ").append(a.sampleRate).append(" Hz");
                sb.append('\n');
            }
            sb.append("倍速：").append(PlaySpeed.label(speed)).append('\n');
            if (videoW > 0 && videoH > 0) {
                sb.append("画面：").append(videoW).append("×").append(videoH)
                        .append("（").append(ratioText()).append("）\n");
            }
            sb.append("显示：").append(scaleText()).append('\n');
            sb.append("方向：").append(ScreenPolicy.describe(orientMode)).append('\n');
            sb.append("解码：MediaCodec 硬件解码（失败自动回退软解）\n");
            sb.append("内核：Media3 ExoPlayer 1.4.1\n");
            if (qualities != null && qualities.hasChoice()) {
                sb.append("画质档位：").append(qualities.variants().size()).append(" 档\n");
            }
        }
        sb.append("地址：").append(sources.get(index));

        // 颜色必须跟主题走：v1.95.1 及以前写死 0xFFEEEEEE，浅色主题下弹窗是白底，
        // 这层浅灰字几乎看不见（用户反馈「白色、灰字」）。播放器页面本身永远是黑底，
        // 但弹窗是应用主题的一部分，用 ThemeManager 取色才能两种主题都清楚。
        final TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12.5f);
        tv.setTextColor(ThemeManager.text(this));
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(14), dp(18), dp(8));
        new HfDialogBuilder(this)
                .setTitle("播放信息")
                .setView(tv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 错误码 → 人话。用户看到的不能是 4003 这种编号 */
    private String describeError(PlaybackException e) {
        String what = e == null ? "未知错误" : (e.getErrorCodeName() + "");
        int code = e == null ? PlaybackException.ERROR_CODE_UNSPECIFIED : e.errorCode;
        String hint;
        switch (code) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                hint = "网络连不上。检查 Wi-Fi/移动数据，或地址是否需要内网访问。";
                break;
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                hint = "连接超时。地址可能已失效，或服务器限速。";
                break;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                hint = "服务器返回错误状态（常见 403/404）：地址过期、需要 Referer/Cookie，或切片已被删除。";
                break;
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
                hint = "文件不存在或没有读取权限。";
                break;
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                hint = "本机解码器不支持该视频编码（AV1/HEVC 10bit 等较常见）。"
                        + "可换硬解更强的设备，或先用工具转码为 H.264。";
                break;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
                hint = "解码器初始化失败：可能是硬件解码器被占用或驱动异常。";
                break;
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                hint = "解码过程出错，片源可能损坏（下载不完整、封装错误）。";
                break;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                hint = "容器/清单解析失败：文件损坏，或 m3u8 内容其实是错误页面。";
                break;
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                hint = "清单格式不受支持（例如加密 HLS 缺少密钥）。";
                break;
            default:
                hint = "播放失败。";
                break;
        }
        String extra = e == null || e.getMessage() == null ? "" : "\n\n" + e.getMessage();
        return hint + "\n\n（" + what + "）" + extra;
    }

    // ============================================================ 手势

    private float downX, downY;
    private long downAt, lastTapAt;
    private boolean dragging, longBoosted, seekDrag;
    private long seekStartPos, seekTargetPos;
    private int startVolume;
    private float startBrightness;
    private final Handler longPress = new Handler(Looper.getMainLooper());
    private final Handler tapDelay = new Handler(Looper.getMainLooper());

    private final Runnable boostStart = new Runnable() {
        public void run() {
            if (player == null || !player.getPlayWhenReady()) return;
            longBoosted = true;
            player.setPlaybackParameters(new PlaybackParameters(PlaySpeed.BOOST));
            showHint("⚡ " + PlaySpeed.label(PlaySpeed.BOOST) + " 快进中，松手恢复");
        }
    };

    private final Runnable singleTap = new Runnable() {
        public void run() {
            if (controlsVisible) hideControls();
            else showControls();
        }
    };

    private boolean handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                downAt = SystemClock.uptimeMillis();
                dragging = false;
                longBoosted = false;
                seekDrag = false;
                startVolume = volumeNow();
                startBrightness = brightnessNow();
                longPress.postDelayed(boostStart, LONG_PRESS_MS);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!dragging && (Math.abs(dx) > dp(16) || Math.abs(dy) > dp(16))) {
                    dragging = true;
                    longPress.removeCallbacks(boostStart);
                    if (longBoosted) restoreBoost();
                    seekDrag = Math.abs(dx) >= Math.abs(dy);
                    seekStartPos = player == null ? 0 : player.getCurrentPosition();
                    seekTargetPos = seekStartPos;
                }
                if (!dragging || player == null) return true;
                if (seekDrag) {
                    if (durationMs <= 0) {
                        showHint("当前是直播流，无法拖动进度");
                        return true;
                    }
                    // 横向半屏 = 最多 90 秒，全屏 ≈ 3 分钟；短视频按比例自动缩小
                    long span = Math.min(durationMs, 180000);
                    if (span < 30000) span = Math.min(durationMs, 30000);
                    long delta = (long) (dx / Math.max(1, gesture.getWidth()) * span);
                    long t = seekStartPos + delta;
                    if (t < 0) t = 0;
                    if (t > durationMs) t = durationMs;
                    seekTargetPos = t;
                    showHint((delta >= 0 ? "快进 " : "快退 ") + fmtTime(Math.abs(delta))
                            + "  →  " + fmtTime(t) + " / " + fmtTime(durationMs));
                } else {
                    boolean left = downX < gesture.getWidth() / 2f;
                    float ratio = -dy / (gesture.getHeight() * 0.7f);
                    if (left) {
                        setBrightness(startBrightness + ratio);
                        showHint("亮度 " + Math.round(brightnessNow() * 100) + "%");
                    } else {
                        int max = audioMax();
                        int want = startVolume + Math.round(ratio * max);
                        setVolume(want);
                        showHint("音量 " + Math.round(volumeNow() * 100f / Math.max(1, max)) + "%");
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                longPress.removeCallbacks(boostStart);
                long dt = SystemClock.uptimeMillis() - downAt;
                if (longBoosted) {
                    restoreBoost();
                    hideHint();
                    return true;
                }
                if (dragging) {
                    if (seekDrag && player != null && durationMs > 0 && seekTargetPos != seekStartPos) {
                        player.seekTo(seekTargetPos);
                    }
                    hideHint();
                    showControls();
                    return true;
                }
                // 单击 / 双击：双击窗口内不立刻显隐，避免「双击暂停前闪一下控制条」
                long now = SystemClock.uptimeMillis();
                if (now - lastTapAt < DOUBLE_TAP_MS) {
                    lastTapAt = 0;
                    tapDelay.removeCallbacks(singleTap);
                    togglePlay();
                    showControls();
                } else {
                    lastTapAt = now;
                    tapDelay.postDelayed(singleTap, DOUBLE_TAP_MS);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                longPress.removeCallbacks(boostStart);
                tapDelay.removeCallbacks(singleTap);
                if (longBoosted) restoreBoost();
                hideHint();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 双指按住（想缩放）→ 取消长按加速，避免误触
                longPress.removeCallbacks(boostStart);
                return true;
            }
        }
        return false;
    }

    private void restoreBoost() {
        longBoosted = false;
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
        hideHint();
    }

    // ============================================================ 显隐 / 提示

    private void showControls() {
        controlsVisible = true;
        topBar.setVisibility(View.VISIBLE);
        centerBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        scheduleAutoHide();
    }

    private void hideControls() {
        controlsVisible = false;
        topBar.setVisibility(View.GONE);
        centerBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        cancelAutoHide();
    }

    private final Runnable autoHide = new Runnable() {
        public void run() {
            if (player != null && player.getPlayWhenReady()) hideControls();
        }
    };

    private void scheduleAutoHide() {
        cancelAutoHide();
        ui.postDelayed(autoHide, HIDE_DELAY_MS);
    }

    private void cancelAutoHide() {
        ui.removeCallbacks(autoHide);
    }

    private void showHint(String text) {
        hintView.setText(text);
        hintView.setVisibility(View.VISIBLE);
    }

    private void hideHint() {
        hintView.setVisibility(View.GONE);
    }

    private void showMessage(String text) {
        msgView.setText(text);
        msgView.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        msgView.setVisibility(View.GONE);
    }

    // ============================================================ 菜单

    private void showMenu() {
        final boolean stream = isStream(sources.get(index));
        List<String> ops = new ArrayList<String>();
        ops.add("播放速度");
        if (qualities != null && qualities.hasChoice()) ops.add("画质（" + qualities.variants().size() + " 档）");
        ops.add("画面缩放：" + (scaleMode == PlayerPrefs.SCALE_CROP ? "铺满"
                : scaleMode == PlayerPrefs.SCALE_STRETCH ? "拉伸·变形" : "适配"));
        ops.add("播放信息");
        ops.add("画面方向：" + ScreenPolicy.label(orientMode));
        if (stream) ops.add("复制地址");
        else ops.add("用其他应用打开");
        ops.add("重新加载");

        new HfDialogBuilder(this)
                .setTitle(displayTitle)
                .setItems(ops.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        // 菜单项是动态的（有画质档位时才多一项），索引按同一顺序推进
                        handleMenuIndex(which, stream);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void handleMenuIndex(int which, boolean stream) {
        int i = 0;
        if (which == i++) { showSpeedDialog(); return; }
        if (qualities != null && qualities.hasChoice()) {
            if (which == i++) { showQualityDialog(); return; }
        }
        if (which == i++) { cycleScale(); return; }
        if (which == i++) { showInfo(); return; }
        if (which == i++) { showOrientationDialog(); return; }
        if (which == i++) {
            if (stream) copyUrl();
            else OpenWith.open(this, new File(sources.get(index)));
            return;
        }
        if (which == i) {
            long pos = player == null ? 0 : player.getCurrentPosition();
            playCurrent(index);
            if (player != null && pos > 0) player.seekTo(pos);
        }
    }

    private void copyUrl() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", sources.get(index)));
            toast("地址已复制");
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    // ============================================================ 小工具

    private boolean isLive() {
        return player != null && player.isCurrentMediaItemLive();
    }

    private static boolean isStream(String src) {
        return src != null && src.indexOf("://") > 0 && !src.startsWith("file://");
    }

    private static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String trimNum(float v) {
        long r = Math.round(v * 100);
        if (r % 100 == 0) return String.valueOf(r / 100);
        return String.valueOf(r / 100f);
    }

    /** 宽高比文案：能约成 16:9 这种常见写法就用它，约不出来就给 2.35:1 */
    private String ratioText() {
        if (videoW <= 0 || videoH <= 0) return "未知";
        int g = gcd(videoW, videoH);
        int a = videoW / g, b = videoH / g;
        if (a <= 32 && b <= 32) return a + ":" + b;
        return String.format(Locale.US, "%.2f:1", (float) videoW / videoH);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a == 0 ? 1 : a;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private long savedPosition(String key) {
        if (key == null || isStream(key)) return 0;
        return PlayerPrefs.position(this, key);
    }

    private void savePosition() {
        if (player == null || sources.isEmpty()) return;
        String key = sources.get(index);
        if (isStream(key)) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (pos < RESUME_MIN_MS) {
            // 刚开始看就退出：清掉旧位点，否则下次会从「早就看过的位置」起播
            PlayerPrefs.setPosition(this, key, 0);
            return;
        }
        if (dur > 0 && pos > dur - 8000) {
            PlayerPrefs.setPosition(this, key, 0);   // 看完了，下次从头
            return;
        }
        PlayerPrefs.setPosition(this, key, pos);
    }

    private String userAgent() {
        return "HexFile/" + versionName() + " (Android " + android.os.Build.VERSION.RELEASE
                + "; " + android.os.Build.MODEL + ")";
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    // ---------------- 音量 / 亮度

    private AudioManager am() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    private int audioMax() {
        AudioManager a = am();
        return a == null ? 15 : a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private int volumeNow() {
        AudioManager a = am();
        return a == null ? 0 : a.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private void setVolume(int v) {
        AudioManager a = am();
        if (a == null) return;
        int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (v < 0) v = 0;
        if (v > max) v = max;
        try {
            a.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        } catch (Throwable ignore) {
        }
    }

    private float brightnessNow() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            int sys = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(0.05f, Math.min(1f, sys / 255f));
        } catch (Throwable t) {
            return 0.5f;
        }
    }

    private void setBrightness(float v) {
        if (v < 0.02f) v = 0.02f;
        if (v > 1f) v = 1f;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = v;
        getWindow().setAttributes(lp);
    }

    // ---------------- 系统栏

    private void hideSystemBars() {
        // 挖孔屏：默认 cull 掉挖孔所在的短边（Pixel 竖屏时是顶部 128px），
        // 表现是整页被内缩、顶栏悬浮在半空。播放器底色是纯黑，延伸进挖孔区没有副作用。
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ============================================================ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();          // 从后台回来时系统栏可能又被放回来了
        syncPlayUi();
        startTicking();
    }

    private void syncPlayUi() {
        if (player != null) {
            durationMs = player.getDuration();
            updatePlayUi();
            updateTimeText();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 文件管理器里的播放器切后台就该停（不做后台音频），否则莫名其妙响
        if (player != null) player.setPlayWhenReady(false);
        cancelTicking();
        cancelAutoHide();
        savePosition();
        PlayerPrefs.setSpeed(this, speed);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        longPress.removeCallbacksAndMessages(null);
        tapDelay.removeCallbacksAndMessages(null);
        savePosition();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /** 播放器整页黑底固定配色，不随主题重建（重建会中断播放） */
    @Override
    protected boolean shouldRecreateOnThemeChange() {
        return false;
    }

    /**
     * 转屏不重建（清单里声明了 orientation|screenSize），这里只做两件事：
     * 重新进沉浸模式（转屏后系统栏会自己冒出来），以及按新的容器尺寸重算画面盒子。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 转屏后系统会把状态栏/导航栏放回来（沉浸标志被重置），表现为整页被下推一个
        // 状态栏高度、顶栏之上多一条黑。补两次：一次立即，一次等系统栏动画收尾。
        hideSystemBars();
        ui.postDelayed(new Runnable() {
            public void run() { hideSystemBars(); }
        }, 420);
        // 右下角按钮的文案跟着方向翻转（全屏 ⇄ 竖屏）
        updateFsChip();
        scheduleReframe();
    }

    /**
     * 返回一步：横屏时先转回竖屏继续播放，返回 true 表示「已经消费掉这次返回」。
     * 顶栏箭头与系统返回键共用，避免两个入口行为不一致。
     */
    private boolean stepBackToPortrait() {
        if (!isLandscapeNow()) return false;
        if (backPortrait) {
            // 正在转回竖屏（转屏动画还没结束）时又按了一次：吞掉，别直接退出
            return true;
        }
        // backPortrait 只在本次生效：用户偏好（orientMode）不动，下次进来仍按「自动」走。
        backPortrait = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        scheduleReframe();
        android.util.Log.i(TAG, "stepBack: landscape -> portrait, mode=" + orientMode);
        return true;
    }

    @Override
    public void onBackPressed() {
        // 横屏看片时按返回先回竖屏，再按一次才退出 —— 与主流播放器一致。
        if (stepBackToPortrait()) return;
        exitPlayer();
    }

    /**
     * 退出播放页。
     *
     * <p>如果本页就是任务里的第一屏（从浏览器/聊天/「打开方式」直接进来的，栈下面
     * 什么都没有），直接 finish() 会露出桌面 —— 用户看到的是「播个视频把应用退掉了」。
     * 这种情况先拉起文件管理器再结束，落点始终是这个 App 的文件列表。</p>
     */
    private void exitPlayer() {
        if (isTaskRoot()) {
            try {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (Throwable ignored) {
                // 拉不起来就退回普通 finish，绝不让退出动作卡住
            }
        }
        finish();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
