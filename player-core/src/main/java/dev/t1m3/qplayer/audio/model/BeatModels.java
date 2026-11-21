package dev.t1m3.qplayer.audio.model;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.dsp.Resampler;

import java.io.File;

/**
 * 宿主安装的推理底座，以及「打开哪一个模型」这条策略（上游还没有对应物——
 * 它的 {@code TransitionSpec::AiStyle} 里 {@code use_model} 只是一句注释，
 * 模型路线还没接进 automix；这里是 QPlayer 侧把接缝补上的地方）。
 *
 * <p>用法（桌面端启动时一次）：
 *
 * <pre>
 * BeatModels.install(new OnnxBeatModels());
 * </pre>
 *
 * <p>没装底座时 {@link #isInstalled()} 为 {@code false}，{@link #open(String)} 直接抛
 * {@link ModelException}——<b>不静默返回 null</b>：调用方（{@code AiMix.prepare}）
 * 要把「为什么没用模型」写进日志，而且它必须能区分「没接线」与「模型坏了」。
 *
 * <h2>模型从哪来：folia 的清单是权威</h2>
 *
 * <p>{@link #PREFERRED_ID} 对应的那一条来自 folia 的
 * {@code shared/modelManifest.json}（本仓库 {@code /models/model-manifest.json} 里照抄了它的
 * 文件名字节数与 sha256，以及<b>镜像顺序</b>：hf-mirror → huggingface → 自家
 * GitHub release，第一个字节匹配哈希者胜）。
 *
 * <p>它在清单里那段话说得很准，抄在这里：「<b>「下载成功」和「能用」是两个不同的
 * 断言</b>……被截断或被镜像替换的 ONNX 不会响亮地失败——它会加载、自信地回答、
 * 而答案是错的。对拍点模型来说，后果是每次过渡都落在拍外，日志里还什么都看不出来。
 * 所以校验是重点，URL 不是。」
 *
 * <h2>为什么模型选择策略放在 player-core 而不是宿主</h2>
 *
 * <p>「首选哪个、耗时预算多少」是共享的行为决定，两端应当一致；
 * 而「怎么把 ONNX 会话建起来」才是宿主的事。所以策略在这里，实现在宿主。
 * 与 {@code Decoder}（接口 + 工厂在这里，jflac/jlayer 在桌面、MediaCodec 在安卓）
 * 是同一分层。
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::beat::BeatModel} + {@code infer::InferSession}
 * 的<b>组合</b>；上游没有「工厂注入」这一层。
 */
public final class BeatModels {

    /**
     * 拍点模型 id：全量 Beat This!。
     *
     * <p>用户对「硬算的 BPM 质量不够好」的判断就落在这里。
     *
     * <p><b>只有这一个。</b> 早先照 Sonalis 的清单接过一个「首选 + 第二选择」的两级
     * 设计（第二选择是 small checkpoint），现在已经去掉：模型来源以
     * <b>folia 的 {@code shared/modelManifest.json}</b> 为权威，而那份清单里拍点模型
     * 就一条。多出来的那个小 checkpoint 来自另一个仓库的导出，把它做成<b>自动</b>
     * 回退等于「在权威清单之外悄悄换了模型」，而换模型出错时的症状是拍点整体偏移、
     * 日志里什么都看不出来——最不该悄悄发生的就是这种事。
     *
     * <p>需要它做本地验证的人可以显式地把它加进清单再
     * {@link #open(String)}；<b>但那是有意识的动作，不是自动的</b>。
     */
    public static final String PREFERRED_ID = "beat_this";

    /**
     * 单次模型推理的耗时预算（秒）。
     *
     * <p>超过它就不再继续用模型，改走硬算。理由与 {@code AiMix.prepare} 必须异步
     * 是同一个：环形缓冲默认只有 200 ms，而 {@code prepare} 是在过渡开始
     * <b>之前</b>的一条后台线程上跑的——耗掉整个「剩余 ≤ 窗口 + 30 秒」的余量，
     * 结果就是过渡该开始时还没准备好。
     *
     * <p>取值依据：本机实测（release 17、Windows、单线程）全量 beat_this 的
     * 会话加载约 1.4 秒、12 秒素材的前端 + 推理不到 1 秒；10 秒是给慢得多的机器
     * 留的余量。手机上很可能跑不动全量模型，那时会走硬算——<b>如实降级，不是假装</b>。
     */
    public static final double BUDGET_SECS = 10.0;

    /** 宿主的推理底座。 */
    public interface Opener {

        /**
         * 打开一个模型。
         *
         * <p>上游 {@code BeatModel::load(label, path, params)} 多一个
         * {@code with_peaks} 构建器；Java 侧把 {@code peaks} 直接作为参数传进来——
         * 「返回一个新实例」的构建器会让「谁持有那个会话、谁负责 close」变得含糊。
         *
         * @param label  模型名（仅用于日志与错误信息）
         * @param path   模型文件
         * @param params 前端参数（默认 {@link MelParams#beatThis()}）
         * @param peaks  峰值拾取参数（默认 {@link PeakParams#defaults()}）
         * @throws InferException 加载失败（文件读不了、图不合法、算子不支持）
         */
        BeatDetector open(String label, java.io.File path, MelParams params, PeakParams peaks);
    }

    private static volatile Opener opener;

    private BeatModels() {
    }

    /** 安装宿主的推理底座（桌面端 / 安卓端各一份，启动时调用一次）。 */
    public static void install(Opener o) {
        opener = o;
    }

    /** 当前推理底座，未安装时为 {@code null}。 */
    public static Opener opener() {
        return opener;
    }

    /** 是否已安装推理底座。 */
    public static boolean isInstalled() {
        return opener != null;
    }

    /**
     * 卸载底座（{@code null} 等价于卸载）。给测试用：{@link #install} 是全局静态状态，
     * 每条测试都要能自己装一遍、跑完再擦掉。
     */
    public static void uninstall() {
        opener = null;
    }

    /**
     * 打开指定 id 的模型。
     *
     * <p>会依次检查：清单里有这个 id → 本地有这个文件 → 宿主装了底座 →
     * 底座能把它打开。每一步失败都<b>如实抛出</b>，不返回 {@code null}。
     *
     * <p>「本地有没有」问的是 {@link ModelDownloads#present}（一次 {@code stat}
     * 顺带比大小），也就是<b>可用性那个布尔的唯一来源</b>；这里不做任何缓存、
     * 也不记「上次失败过」，所以文件一落地（或用户把目录指过来）就立刻能用——
     * 自愈不需要任何人去清什么状态。
     *
     * <p>前端参数用 {@link MelParams#beatThis()}（<b>实测出来的</b>那套），
     * 不是 {@link MelParams#defaults()}——后者是上游给的一套通用配置，
     * {@code n_fft} 是 2048，而 Beat This! 训练时用的是 1024。
     * 用错不会报错，只会让拍点悄悄变差，实测差异见 {@link MelParams#beatThis()}。
     *
     * @throws ModelException 清单里没有、还没下载、没装底座
     * @throws InferException 底座加载失败
     */
    public static BeatDetector open(String id) {
        Opener o = opener;
        if (o == null) {
            throw ModelException.notAvailable(
                    "没有安装推理底座：宿主必须在启动时调用 BeatModels.install(...)");
        }
        ModelDownloads downloads = ModelDownloads.shared();
        ModelManifestEntry entry = downloads.manifest().require(id);
        File path = downloads.fileFor(entry);
        if (!downloads.present(entry)) {
            throw ModelException.io("模型还没下载：" + path);
        }
        return o.open(entry.name, path, MelParams.beatThis(), PeakParams.defaults());
    }

    /**
     * 这个模型的权重<b>现在</b>在不在磁盘上。
     *
     * <p>这就是可用性那个布尔：只问磁盘，不做会话内的缓存、不加「放弃」闩锁。
     * 界面要拿它做承诺时请用 {@link ModelAvailability#canRun}（那份带一次
     * {@code refresh}，免得每帧都 {@code stat}）。
     */
    public static boolean isAvailable(String id) {
        return ModelStore.available(id);
    }

    /**
     * 打开对拍要用的拍点模型（{@link #PREFERRED_ID}）。
     *
     * <p>打不开就<b>如实抛异常</b>，由调用方（{@code AiMix.prepare}）退回硬算并把原因
     * 写进日志——这是「算不出来就别猜」的落点，不是错误处理。
     *
     * <p>早先这里还带一级「退到 small checkpoint」的兜底，现在没有了：
     * 模型来源以 folia 的清单为权威，那份清单里拍点模型只有一条，
     * 而在权威清单之外悄悄换一个模型，出错的症状恰恰是查不出来的那种
     * （见 {@link #PREFERRED_ID}）。
     *
     * @throws ModelException 清单里没有、还没下载、没装底座
     * @throws InferException 底座加载失败
     */
    public static BeatDetector openPreferred() {
        return open(PREFERRED_ID);
    }

    /**
     * 把单声道 PCM 重采样到模型要的采样率。
     *
     * <p>上游 {@code BeatModel::beats} 的文档写着「输入必须是 {@code mel_params().sample_rate}
     * 的单声道 PCM；采样率不符时<b>由调用方先重采样</b>（这里不暗中重采样，
     * 否则「结果不对」会难以定位）」。这个函数就是那个调用方要用的东西——
     * 放在这里而不是 {@code Analysis}：它服务的是模型路线，
     * 而 {@code Analysis} 那条路是硬算、不碰模型采样率。
     *
     * <p>采样率相同<b>原样返回</b>（{@link Resampler#isPassthrough()} 那条路）：
     * 多跑一遍 sinc 核只会在最低有效位上引入差异，而模型对输入的差异比人耳敏感。
     *
     * <p>用 {@link Resampler#drain} 收尾而不是只 {@code pull}：{@code pull} 每算一个输出点
     * 都要看到它后面 16 帧「未来输入」，流末尾永远凑不齐，于是最后 16 帧会被
     * 静默丢掉（详见 {@code docs/audio-port.md} 的差异表）。
     */
    public static float[] resampleMono(float[] pcm, int inRate, int outRate) {
        if (inRate == outRate || pcm.length == 0) {
            return pcm;
        }
        if (inRate <= 0 || outRate <= 0) {
            throw new IllegalArgumentException("采样率必须大于 0：" + inRate + " → " + outRate);
        }

        // 块长要小于 Resampler.HISTORY_FRAMES（8192），否则 push 会静默丢数据
        final int chunk = 4096;

        Resampler rs = new Resampler(inRate, outRate, 1);
        AudioBuf in = new AudioBuf(ChannelLayout.MONO, chunk);
        AudioBuf out = new AudioBuf(ChannelLayout.MONO, chunk);

        // 预期输出帧数 + 余量。数组不够时按需翻倍（下面仍有增长分支兜底）。
        long expected = (long) ((double) pcm.length * (double) outRate / (double) inRate) + 64L;
        float[] result = new float[(int) Math.min(expected, (long) Integer.MAX_VALUE - 8)];
        int len = 0;

        int offset = 0;
        while (offset < pcm.length) {
            int n = Math.min(chunk, pcm.length - offset);
            in.setFrames(n);
            for (int i = 0; i < n; i++) {
                in.channelWriter(0).put(i, pcm[offset + i]);
            }
            offset += n;

            // pushAll 会一路喂到喂不进去为止；历史满时先拉干腾地方再补喂。
            int fed = 0;
            while (fed < n) {
                int got = rs.pushFrom(in, fed);
                if (got == 0) {
                    // 历史满：拉出来的都是真实输出，不能丢
                    int produced = rs.pull(out);
                    if (produced == 0) {
                        // 既喂不进去又拉不出来——重采样器状态不成立，如实报错而不是死循环
                        throw new IllegalStateException(
                                "重采样器卡住了：喂不进也拉不出（已有 " + len + " 帧输出）");
                    }
                    result = growInto(result, len, out, produced);
                    len += produced;
                    continue;
                }
                fed += got;
                int produced = rs.pull(out);
                if (produced > 0) {
                    result = growInto(result, len, out, produced);
                    len += produced;
                }
            }
        }

        // 输入已确认结束：收尾（上游没有这一步，见 docs/audio-port.md 的差异表）
        while (true) {
            int produced = rs.drain(out);
            if (produced == 0) {
                break;
            }
            result = growInto(result, len, out, produced);
            len += produced;
        }

        return java.util.Arrays.copyOf(result, len);
    }

    /**
     * 把 {@code out} 的前 {@code n} 帧追加到 {@code dst[len..]}。
     *
     * <p>Java 的数组是值传递，扩容后的新数组必须交回调用方，所以做成返回新数组的
     * 形式（调用方接着写返回值）。逐元素 {@code get(i)} 而不是带下标的批量读：
     * {@code FloatBuffer.get(int, float[], int, int)} 是 Java 13+，
     * 在安卓（release 8）上会挂，见 docs/audio-port.md 的坑表。
     */
    static float[] growInto(float[] dst, int len, AudioBuf out, int n) {
        if (len + n > dst.length) {
            long grown = Math.max((long) dst.length * 2L, (long) len + n);
            dst = java.util.Arrays.copyOf(dst, (int) Math.min(grown, (long) Integer.MAX_VALUE - 8));
        }
        java.nio.FloatBuffer src = out.channel(0);
        for (int i = 0; i < n; i++) {
            dst[len + i] = src.get(i);
        }
        return dst;
    }
}
