package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.mix.Deck;
import dev.t1m3.qplayer.audio.model.StemSeparator;
import dev.t1m3.qplayer.audio.stem.DspStemSeparator;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * <b>整曲分轨的生成与缓存</b>（mashup 第三阶段）。
 *
 * <h2>为什么要分窗</h2>
 *
 * <p>{@link DspStemSeparator} 一次处理整段会按"整段长度"分配 4 条 {@code double[]}
 * （48 kHz 下约 1.5 MB/秒素材）——4 分钟就是 339 MB，而且那个累加器走 {@code ThreadLocal}
 * 复用、会一直钉在进程里。所以这里按 30 s 的窗调用它，把峰值压在 ~100 MB。
 *
 * <h2>为什么绝不 seek</h2>
 *
 * <p>{@code Deck} 的注释里有实测：MP3 上 {@code seek} 与"从 0 自然播"差
 * <b>1152~2304 帧（24~48 ms）</b>，而拍网格是从 0 自然播算出来的。为了让分轨素材
 * 和拍网格对得上，这里<b>只开一次解码器、从头顺序读到尾</b>，中间不 seek。
 * 窗口之间的重叠靠内存里留尾巴，不靠跳回去重读。
 *
 * <h2>窗口与接缝</h2>
 *
 * <p>窗长 30 s、跳 20 s（10 s 重叠）。第 k 个窗覆盖 {@code [kH, kH+W)}，产出
 * {@code [kH, kH+H)}；接缝在 {@code kH+H}，两侧都有估计，所以在
 * {@code [kH+H, kH+H+X)}（X = 0.5 s）里做等功率交叉淡化——<b>不淡化就会听见
 * 分离结果在窗边界上"人声忽大忽小"</b>（STFT 边界与内部不一致）。
 *
 * <h2>缓存</h2>
 *
 * <p>文件名是 "<内容 sha256>-<档位>.stem"，写完才原子改名。已存在且头能打开、
 * 采样率/档位都对就直接复用——<b>不重算</b>（一首 4 分钟的歌要 5~8 秒 CPU）。
 */
public final class StemStore {

    /** 默认输出采样率。 */
    public static final int DEFAULT_RATE = 48_000;

    /** 窗长（秒）。 */
    public static final double WINDOW_SECS = 30.0;

    /** 窗与窗之间的跳（秒）。 */
    public static final double HOP_SECS = 20.0;

    /** 接缝交叉淡化长度（秒）。 */
    public static final double CROSSFADE_SECS = 0.5;

    /** 档位标记：DSP（卡拉 OK 级近似）。 */
    public static final int TAG_DSP = 0;

    /** 进度回调（每个窗一次；{@code total < 0} 表示总窗数未知）。 */
    public interface Progress {
        void onWindows(int done, int total);
    }

    /** 取消检查（每个窗开始时问一次；一次分窗约 0.7 s，所以取消延迟在秒级）。 */
    public interface Cancel {
        boolean isCanceled();
    }

    private final Path dir;

    /** 指定缓存目录。 */
    public StemStore(Path dir) {
        if (dir == null) {
            throw new IllegalArgumentException("分轨目录不能为空");
        }
        this.dir = dir;
    }

    /** 默认位置：{@code <cache>/mashup/stems}。 */
    public static StemStore inCache() {
        return new StemStore(
                dev.t1m3.qplayer.store.AppDirs.cacheDir().resolve("mashup").resolve("stems"));
    }

    /** 缓存目录。 */
    public Path dir() {
        return dir;
    }

    /** 某个键 + 档位对应的文件。 */
    public Path pathFor(String key, int tag) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("分轨缓存必须有键");
        }
        return dir.resolve(sha256(key) + "-" + tag + ".stem");
    }

    /**
     * 确保这份分轨存在；已有可用的就直接返回路径。
     *
     * <p><b>失败/取消不留半成品</b>：写的是 {@code .tmp}，只有收尾成功才改名；
     * 取消时删掉临时文件并把 {@link java.util.concurrent.CancellationException} 抛给调用方
     * （"用户取消"是正常事件，但调用方仍要知道这次没产出）。
     */
    public Path ensure(String key, String source, java.util.Map<String, String> headers,
                       int rate, Progress progress, Cancel cancel) throws IOException {
        Path target = pathFor(key, TAG_DSP);
        if (StemFile.isUsable(target, rate, TAG_DSP)) {
            return target;
        }
        int window = (int) Math.round(WINDOW_SECS * rate);
        int hop = (int) Math.round(HOP_SECS * rate);
        int fade = (int) Math.round(CROSSFADE_SECS * rate);
        if (hop <= 0) {
            throw new IllegalArgumentException("采样率太低，跳长算出来是 0");
        }
        if (hop + fade > window) {
            throw new IllegalArgumentException("窗口太短：跳 " + hop + " + 淡化 " + fade
                    + " 超过窗长 " + window);
        }

        Deck deck = Deck.open(source, rate, 2);
        AudioBuf block = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        DspStemSeparator separator = new DspStemSeparator();
        StemWriter writer = null;
        try {
            Long remaining = deck.remainingFrames();
            long total = remaining == null ? -1 : deck.outFrames() + remaining;
            int totalWindows = total < 0 ? -1
                    : (int) Math.ceil((double) total / (double) hop);

            writer = StemWriter.open(target, rate, hop, TAG_DSP);

            int capacity = window + Deck.RENDER_FRAMES;
            float[][] win = new float[2][capacity];
            float[] windowLeft = new float[window];
            float[] windowRight = new float[window];
            float[][] carryI = new float[2][fade];
            float[][] carryV = new float[2][fade];
            float[][] blendI = new float[2][fade];
            float[][] blendV = new float[2][fade];
            float[][] emitI = new float[2][hop];
            float[][] emitV = new float[2][hop];
            int filled = 0;
            long bufferStart = 0;

            long emitted = 0;
            long windowStart = 0;
            int windows = 0;
            boolean firstWindow = true;

            while (total < 0 || windowStart < total) {
                if (cancel != null && cancel.isCanceled()) {
                    writer.abort();
                    throw new java.util.concurrent.CancellationException("用户取消了分轨");
                }
                if (progress != null) {
                    progress.onWindows(windows, totalWindows);
                }

                // 1) 把窗口挪到 windowStart（窗口之间重叠 10 s，靠内存里的尾巴，不 seek）
                int shift = (int) (windowStart - bufferStart);
                if (shift > 0) {
                    if (shift >= filled) {
                        filled = 0;
                    } else {
                        for (int c = 0; c < 2; c++) {
                            System.arraycopy(win[c], shift, win[c], 0, filled - shift);
                        }
                        filled -= shift;
                    }
                    bufferStart = windowStart;
                }

                // 2) 顺序读到"窗口满"为止（读多了的部分留着给下一次移位）
                int zeroReads = 0;
                while (filled < window && !deck.isDrained()) {
                    int n = deck.render(block);
                    if (n <= 0) {
                        if (deck.isDrained() || ++zeroReads > 8) {
                            break;
                        }
                        continue;
                    }
                    zeroReads = 0;
                    int take = Math.min(n, capacity - filled);
                    for (int c = 0; c < 2; c++) {
                        FloatBuffer ch = block.channel(c);
                        for (int i = 0; i < take; i++) {
                            win[c][filled + i] = ch.get(i);
                        }
                    }
                    filled += take;
                }
                if (filled < window) {
                    for (int c = 0; c < 2; c++) {
                        Arrays.fill(win[c], filled, window, 0f);
                    }
                }

                // 3) 分轨（只喂窗内那一段）
                System.arraycopy(win[0], 0, windowLeft, 0, window);
                System.arraycopy(win[1], 0, windowRight, 0, window);
                StemSeparator.StemSet set = separator.separate(windowLeft, windowRight, rate);

                // 4) 产出 [windowStart, windowStart+hop)
                long room = total < 0 ? Long.MAX_VALUE : Math.max(0, total - emitted);
                int roomNow = (int) Math.min(room, Integer.MAX_VALUE);
                int cross = firstWindow ? 0 : Math.min(fade, roomNow);
                if (cross > 0) {
                    for (int c = 0; c < 2; c++) {
                        for (int i = 0; i < cross; i++) {
                            double w = 0.5 - 0.5 * Math.cos(Math.PI * i / Math.max(1, fade));
                            blendI[c][i] = (float) (carryI[c][i] * (1.0 - w)
                                    + channelOf(set, c, i, false) * w);
                            blendV[c][i] = (float) (carryV[c][i] * (1.0 - w)
                                    + channelOf(set, c, i, true) * w);
                        }
                    }
                    writer.write(blendI, blendV, cross);
                    emitted += cross;
                }
                int steady = Math.min(hop - cross, roomNow - cross);
                if (steady > 0) {
                    for (int c = 0; c < 2; c++) {
                        for (int i = 0; i < steady; i++) {
                            emitI[c][i] = channelOf(set, c, cross + i, false);
                            emitV[c][i] = channelOf(set, c, cross + i, true);
                        }
                    }
                    writer.write(emitI, emitV, steady);
                    emitted += steady;
                }

                // 5) 留给下一个窗的接缝素材：本窗的 [hop, hop+fade)
                for (int c = 0; c < 2; c++) {
                    for (int i = 0; i < fade; i++) {
                        int at = hop + i;
                        carryI[c][i] = at < window ? channelOf(set, c, at, false) : 0f;
                        carryV[c][i] = at < window ? channelOf(set, c, at, true) : 0f;
                    }
                }

                firstWindow = false;
                windows++;
                windowStart += hop;
            }
            writer.close();
            if (progress != null) {
                progress.onWindows(windows, windows);
            }
            return target;
        } catch (java.util.concurrent.CancellationException e) {
            if (writer != null) {
                writer.abort();
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            if (writer != null) {
                writer.abort();
            }
            throw e;
        } finally {
            separator.close();
            deck.close();
        }
    }

    /** 从 {@link StemSeparator.StemSet} 取第 {@code c} 路、第 {@code frame} 帧。 */
    private static float channelOf(StemSeparator.StemSet set, int c, int frame, boolean vocals) {
        if (vocals) {
            return c == 0 ? set.vocalsLeft[frame] : set.vocalsRight[frame];
        }
        return c == 0 ? set.instrumentalLeft[frame] : set.instrumentalRight[frame];
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** 这个文件还在不在（设置页"清缓存"之后可用）。 */
    public boolean has(String key, int tag) {
        return Files.isRegularFile(pathFor(key, tag));
    }

    @Override
    public String toString() {
        return "StemStore{" + dir + "}";
    }
}
