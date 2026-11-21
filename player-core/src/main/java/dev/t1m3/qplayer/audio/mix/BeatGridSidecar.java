package dev.t1m3.qplayer.audio.mix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * <b>节拍网格的 sidecar</b>（2026-10-06 第一阶段工作二）。
 *
 * <p>用户原话：「电脑端用模型预计算。用 Beat This 或其他开源节拍检测模型，在电脑端跑一次，
 * 把节拍网格存成 sidecar 文件，和歌曲同目录。格式包含 fileHash、bpm、beats 数组、
 * downbeats 数组、confidence。**手机端直接读缓存，不需要跑模型**。」
 * 以及后来的补充：「**手机端也得具备硬算程序（DSP 不是模型）**，有 sidecar 读 sidecar，
 * 没有就自己算。」
 *
 * <p>所以这份文件的定位是<b>缓存</b>，不是依赖：它是纯数据、纯文本、零依赖，
 * 手机端只做解析；拿不到（没有文件 / 哈希对不上 / 内容坏了 / 版本不认识）一律
 * <b>返回 {@code null}</b>，调用方接着走既有的硬算链路（{@code BeatTracker} 自相关 +
 * DP + 稳健回归，再加采样级相位对齐）。**绝不因为缺缓存就不做事。**
 *
 * <p>为什么带 {@code fileHash}：缓存的键必须是<b>音频内容</b>而不是文件名——用户换掉文件
 * （重新压制、换码率）后文件名可能不变，而旧网格会静静地配错拍。哈希对不上就当没有缓存。
 *
 * <p>格式（行式，行内用空格分隔；解析时忽略空行与 {@code #} 开头的注释）：
 * <pre>
 *   qplayer-beatgrid 1
 *   fileHash sha256的十六进制
 *   bpm 128.000
 *   confidence 0.930
 *   beats 0.000 0.469 0.938 ...
 *   downbeats 0.000 1.875 ...
 * </pre>
 */
public final class BeatGridSidecar {

    /** 文件扩展名：{@code 歌.mp3} → {@code 歌.mp3.beats}（与歌曲同目录）。 */
    public static final String SUFFIX = ".beats";

    /** 当前格式版本。解析到不认识的版本一律当"没有缓存"。 */
    public static final int VERSION = 1;

    /** 魔数（第一行的第一个词）：别的程序留下的同名文件不会被误读。 */
    static final String MAGIC = "qplayer-beatgrid";

    /** 音频内容的 SHA-256（十六进制小写）。 */
    public final String fileHash;

    /** 每分钟拍数。 */
    public final double bpm;

    /** 拍点时刻（秒，升序）。 */
    public final double[] beats;

    /** 小节线时刻（秒，升序；可以是空数组 = 没算）。 */
    public final double[] downbeats;

    /** 模型自报的置信度（0..1）。 */
    public final double confidence;

    public BeatGridSidecar(String fileHash, double bpm, double[] beats, double[] downbeats,
                           double confidence) {
        this.fileHash = fileHash;
        this.bpm = bpm;
        this.beats = beats == null ? new double[0] : beats;
        this.downbeats = downbeats == null ? new double[0] : downbeats;
        this.confidence = confidence;
    }

    /** 序列化（{@code Locale.ROOT}，避免小数点变成逗号）。 */
    public String toText() {
        StringBuilder sb = new StringBuilder(256 + beats.length * 12);
        sb.append(MAGIC).append(' ').append(VERSION).append('\n');
        sb.append("fileHash ").append(fileHash).append('\n');
        sb.append("bpm ").append(fmt(bpm)).append('\n');
        sb.append("confidence ").append(fmt(confidence)).append('\n');
        sb.append("beats");
        for (double b : beats) {
            sb.append(' ').append(fmt(b));
        }
        sb.append('\n');
        sb.append("downbeats");
        for (double d : downbeats) {
            sb.append(' ').append(fmt(d));
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", Double.valueOf(v));
    }

    /**
     * 解析。任何一处不对（魔数/版本/缺字段/数字坏了/拍点不是升序）都返回 {@code null}
     * ——<b>坏缓存与没有缓存是同一条路</b>，调用方只需要判 {@code null}。
     */
    public static BeatGridSidecar parse(String text) {
        if (text == null) {
            return null;
        }
        String hash = null;
        double bpm = Double.NaN;
        double confidence = Double.NaN;
        double[] beats = null;
        double[] downbeats = null;
        boolean header = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sp = line.indexOf(' ');
            String key = sp < 0 ? line : line.substring(0, sp);
            String rest = sp < 0 ? "" : line.substring(sp + 1).trim();
            if (MAGIC.equals(key)) {
                if (!String.valueOf(VERSION).equals(rest)) {
                    return null;                        // 版本不认识：当没有缓存
                }
                header = true;
            } else if ("fileHash".equals(key)) {
                hash = rest;
            } else if ("bpm".equals(key)) {
                bpm = parseDouble(rest);
            } else if ("confidence".equals(key)) {
                confidence = parseDouble(rest);
            } else if ("beats".equals(key)) {
                beats = parseArray(rest);
            } else if ("downbeats".equals(key)) {
                downbeats = parseArray(rest);
            }
            // 不认识的键**忽略**：将来加字段不会让旧版读不了
        }
        if (!header || hash == null || hash.isEmpty() || Double.isNaN(bpm) || bpm <= 0.0
                || Double.isNaN(confidence) || beats == null || beats.length < 2) {
            return null;
        }
        for (int i = 1; i < beats.length; i++) {
            if (beats[i] <= beats[i - 1]) {
                return null;                            // 拍点必须严格升序
            }
        }
        return new BeatGridSidecar(hash, bpm, beats, downbeats, confidence);
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double[] parseArray(String s) {
        if (s.isEmpty()) {
            return new double[0];
        }
        String[] parts = s.split("\\s+");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parseDouble(parts[i]);
            if (Double.isNaN(out[i])) {
                return null;
            }
        }
        return out;
    }

    /**
     * 读缓存。**任何异常都吞掉并返回 {@code null}**（手机端读不到就自己算，
     * 不能因为一个附属文件把一次过渡搞崩）。
     */
    public static BeatGridSidecar read(Path audioPath) {
        try {
            Path side = sidecarFor(audioPath);
            if (!Files.isRegularFile(side)) {
                return null;                            // 没有缓存：调用方走硬算
            }
            BeatGridSidecar parsed = parse(new String(Files.readAllBytes(side),
                    StandardCharsets.UTF_8));
            if (parsed == null) {
                return null;                            // 坏了：同样走硬算
            }
            String hash = hashOf(audioPath);
            if (hash == null || !hash.equals(parsed.fileHash)) {
                return null;                            // **哈希对不上 = 当没有缓存**
            }
            return parsed;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    /** 写缓存（电脑端跑完模型之后）。失败返回 {@code false}，不抛。 */
    public static boolean write(Path audioPath, BeatGridSidecar grid) {
        try {
            Files.write(sidecarFor(audioPath), grid.toText().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    /** {@code 歌.mp3} → {@code 歌.mp3.beats}（与歌曲同目录）。 */
    public static Path sidecarFor(Path audioPath) {
        return audioPath.resolveSibling(audioPath.getFileName().toString() + SUFFIX);
    }

    /** 音频内容的 SHA-256（十六进制小写）；读不了返回 {@code null}。 */
    public static String hashOf(Path audioPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            try (java.io.InputStream in = Files.newInputStream(audioPath)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (RuntimeException | IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
