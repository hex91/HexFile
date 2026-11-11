package com.hex91.file;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * TS → MP4 无损转封装 —— v1.92.0。
 *
 * <p>注意这里<b>不转码</b>：读出来的 H.264/H.265 码流原样写进 MP4 容器，
 * 像素与码率一个字节都没变。所谓「转成 MP4」只是换了个盒子 ——
 * 这也是它能跑到几十倍速的原因（纯磁盘 IO，不解码不编码）。</p>
 *
 * <p>为什么值得做：TS 是直播/切片用的流式容器，一个包 188 字节、索引信息稀薄，
 * 很多手机播放器、相册、剪辑软件都不认；MP4 到处能放，还带完整索引可随意拖动。
 * 同一个码流换个盒子，兼容性差别很大。</p>
 *
 * <h3>四个必须处理对的点</h3>
 * <ol>
 *   <li><b>时间戳基线</b>：TS 的 PTS 常以 1.4 秒、10 秒甚至 90000×N 开头（编码器惯例），
 *       直接写进 MP4 会得到「开头空白几十秒」的视频。要整体减去首帧时间。</li>
 *   <li><b>音视频必须共用同一个基线</b>：两轨各自归一化会让音画错位几百毫秒 ——
 *       这是「转完口型对不上」的唯一原因。所以先探一次两轨各自的首帧时间，取最小。</li>
 *   <li><b>同轨时间戳必须严格递增</b>：MP4 的 stts 不允许相等或倒退，
 *       切片边界（EXT-X-DISCONTINUITY）常有重复时间戳，会让 muxer 直接抛异常。</li>
 *   <li><b>尾部残帧</b>：下载中断的 TS 最后一段可能只有半帧。已写到 90% 之后出的写入错误
 *       应当忽略并正常收尾，而不是把整个转换判死。</li>
 * </ol>
 */
public final class TsRemuxer {

    public interface Progress {
        /** @param us 已处理到的媒体时间（微秒）；totalUs 未知时为 0 */
        void onProgress(long us, long totalUs);
    }

    private static final int BUF = 256 * 1024;

    private TsRemuxer() {}

    /**
     * 把 TS（或 fMP4 片段拼出来的文件）重新封装成标准 MP4。
     *
     * @param src  源文件
     * @param dst  目标 mp4（已存在会被覆盖）
     * @param prog 进度回调，可空
     * @return 写出的 MP4 字节数
     */
    public static long remuxToMp4(File src, File dst, Progress prog) throws IOException {
        if (src == null || !src.isFile() || src.length() <= 0) {
            throw new IOException("源文件不存在或为空");
        }
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try {
            ex.setDataSource(src.getAbsolutePath());

            int vTrack = -1, aTrack = -1;
            MediaFormat vFmt = null, aFmt = null;
            long totalUs = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (vTrack < 0 && mime.startsWith("video/")) {
                    vTrack = i;
                    vFmt = f;
                } else if (aTrack < 0 && mime.startsWith("audio/")) {
                    aTrack = i;
                    aFmt = f;
                }
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    totalUs = Math.max(totalUs, f.getLong(MediaFormat.KEY_DURATION));
                }
            }
            if (vTrack < 0 && aTrack < 0) {
                throw new IOException("文件里没有可识别的音视频轨道（可能不是 TS，或只有数据流）");
            }
            if (vTrack >= 0) ex.selectTrack(vTrack);
            if (aTrack >= 0) ex.selectTrack(aTrack);

            // ---- 第一遍：只读时间戳，取两轨里最早的那个当共同基线
            long baseUs = probeBase(ex, vTrack, aTrack);
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            mux = new MediaMuxer(tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outV = -1, outA = -1;
            if (vFmt != null) {
                try {
                    outV = mux.addTrack(vFmt);
                } catch (Throwable t) {
                    throw new IOException("视频轨道无法写入 MP4（" + mime(vFmt) + "）：" + t.getMessage());
                }
            }
            if (aFmt != null) {
                try {
                    outA = mux.addTrack(aFmt);
                } catch (Throwable t) {
                    // 音频加不进去不该让整个转换失败：静音 MP4 也比没有强
                    outA = -1;
                }
            }
            if (outV < 0 && outA < 0) throw new IOException("没有可写入 MP4 的轨道");
            final boolean skipAudio = (outA < 0);

            mux.start();

            ByteBuffer buf = ByteBuffer.allocateDirect(BUF);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long vLast = -1, aLast = -1;
            long lastReport = 0;
            int writtenSamples = 0;

            while (true) {
                int track = ex.getSampleTrackIndex();
                if (track < 0) break;
                boolean isV = track == vTrack;
                boolean isA = track == aTrack;
                if (!isV && !isA) {
                    if (!ex.advance()) break;
                    continue;
                }
                if (isA && skipAudio) {
                    if (!ex.advance()) break;
                    continue;
                }

                buf.clear();
                info.size = ex.readSampleData(buf, 0);
                if (info.size <= 0) {
                    if (!ex.advance()) break;
                    continue;
                }
                info.flags = ex.getSampleFlags();
                long pts = ex.getSampleTime() - baseUs;
                if (pts < 0) pts = 0;

                int muxTrack;
                if (isV) {
                    if (pts <= vLast) pts = vLast + 1;
                    vLast = pts;
                    muxTrack = outV;
                } else {
                    if (pts <= aLast) pts = aLast + 1;
                    aLast = pts;
                    muxTrack = outA;
                }
                info.presentationTimeUs = pts;
                if (muxTrack < 0) {
                    if (!ex.advance()) break;
                    continue;
                }
                try {
                    mux.writeSampleData(muxTrack, buf, info);
                    writtenSamples++;
                } catch (Throwable t) {
                    boolean tail = totalUs > 0 && pts > totalUs * 9 / 10;
                    if (!tail) {
                        throw new IOException("写入" + (isV ? "视频" : "音频")
                                + "样本失败（文件可能不完整）：" + t.getMessage());
                    }
                    // 尾部残帧：丢掉了事，下面正常 stop 收尾
                }
                if (prog != null && pts - lastReport > 500000L) {
                    lastReport = pts;
                    prog.onProgress(pts, totalUs);
                }
                if (!ex.advance()) break;
            }

            if (writtenSamples == 0) throw new IOException("没有写出任何样本（源文件可能已损坏）");
            mux.stop();
            mux.release();
            mux = null;

            if (dst.exists() && !dst.delete()) {
                throw new IOException("目标文件被占用：" + dst.getName());
            }
            if (!tmp.renameTo(dst)) throw new IOException("重命名失败：" + dst.getName());
            long size = dst.length();
            if (size <= 0) throw new IOException("输出文件为空");
            if (prog != null) prog.onProgress(totalUs > 0 ? totalUs : vLast, totalUs);
            return size;
        } catch (IOException e) {
            tmp.delete();
            throw e;
        } catch (Throwable t) {
            tmp.delete();
            String m = t.getMessage();
            throw new IOException("转封装失败：" + (m == null ? t.getClass().getSimpleName() : m));
        } finally {
            try { ex.release(); } catch (Throwable ignore) {}
            if (mux != null) {
                try { mux.stop(); } catch (Throwable ignore) {}
                try { mux.release(); } catch (Throwable ignore) {}
            }
        }
    }

    /**
     * 探测两轨首帧时间，取最小值作为共同基线。
     * <p>只读到「两轨各自出现第一个样本」就停，不遍历整个文件。</p>
     */
    private static long probeBase(MediaExtractor ex, int vTrack, int aTrack) {
        long vFirst = -1, aFirst = -1;
        int guard = 0;
        while (guard++ < 2000) {
            int t = ex.getSampleTrackIndex();
            if (t < 0) break;
            long ts = ex.getSampleTime();
            if (t == vTrack && vFirst < 0) vFirst = ts;
            if (t == aTrack && aFirst < 0) aFirst = ts;
            boolean vDone = vTrack < 0 || vFirst >= 0;
            boolean aDone = aTrack < 0 || aFirst >= 0;
            if (vDone && aDone) break;
            if (!ex.advance()) break;
        }
        long base;
        if (vFirst >= 0 && aFirst >= 0) base = Math.min(vFirst, aFirst);
        else base = vFirst >= 0 ? vFirst : aFirst;
        return base < 0 ? 0 : base;
    }

    private static String mime(MediaFormat f) {
        try {
            return f.getString(MediaFormat.KEY_MIME);
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 按给定顺序把多个文件拼成一个（纯字节拼接）。
     *
     * <p>TS 切片是自同步的、每片都从包边界开始，所以「直接拼」就是合法的单一 TS 流，
     * 不需要任何解析 —— 这正是 m3u8 分片能被简单合并的原因。fMP4 同理，
     * 只是要保证 init 段在最前面。</p>
     */
    public static long concat(List<File> parts, File dst) throws IOException {
        if (parts == null || parts.isEmpty()) throw new IOException("没有可合并的文件");
        OutputStream os = new FileOutputStream(dst);
        long total = 0;
        try {
            byte[] buf = new byte[BUF];
            for (File f : parts) {
                if (f == null || !f.isFile()) continue;
                InputStream in = new FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                } finally {
                    try { in.close(); } catch (IOException ignore) {}
                }
            }
            os.flush();
        } finally {
            try { os.close(); } catch (IOException ignore) {}
        }
        if (total <= 0) throw new IOException("合并结果为空");
        return total;
    }

    /** 建议的输出名：同名换 .mp4；重名时加序号 */
    public static File mp4Sibling(File src) {
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File dir = src.getParentFile();
        File out = new File(dir, base + ".mp4");
        if (!out.equals(src) && !out.exists()) return out;
        if (out.equals(src)) out = new File(dir, base + "_remux.mp4");
        int i = 1;
        while (out.exists()) {
            out = new File(dir, base + "_" + i + ".mp4");
            i++;
        }
        return out;
    }
}
