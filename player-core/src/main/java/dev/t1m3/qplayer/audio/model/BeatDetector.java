package dev.t1m3.qplayer.audio.model;

/**
 * 拍点检测模型：一个已加载、可重复运行的推理会话（上游
 * {@code sonalis-model/src/beat.rs} 的 {@code BeatModel}）。
 *
 * <h2>这两条路线的关系</h2>
 *
 * <p>硬算路线（{@code dev.t1m3.qplayer.audio.mix.Analysis}）用频谱通量 + 自相关，
 * <b>零下载、零额外体积</b>，任何一种音乐都能给出可用的拍点与速度。
 * 模型路线存在的意义只有一条：<b>更准的拍点判定</b>——AI 模式对拍需要一个可靠的
 * 拍点栅格，而硬算在节拍不那么规整的素材上给出的栅格会整体偏移。
 *
 * <p>两条路线并存：模型可用（已下载、会话建得起来、耗时在预算内）就用模型，
 * 否则退回硬算。{@link #beats(float[])} 失败时<b>必须</b>抛出异常，
 * 由调用方如实降级——<b>不许在失败时假装「模型版」</b>（见
 * {@code AiMix.prepare} 里 {@code note} 的标注约定）。
 *
 * <h2>模型从哪来：folia 的清单是权威</h2>
 *
 * <p>拍点模型哪一份算数，以 folia 的 {@code shared/modelManifest.json} 为准
 * （本仓库 {@code /models/model-manifest.json} 里照抄了文件名字节数与 sha256，以及镜像顺序）。
 * 它的原话值得记住：<b>「下载成功」和「能用」是两个不同的断言</b>——被截断或被镜像
 * 替换的 ONNX 不会响亮地失败，它会加载、自信地回答、而答案是错的。
 * 对拍点模型来说，后果是每次过渡都落在拍外，而日志里什么都看不出来。
 * 所以校验是重点，URL 不是。
 *
 * <h2>⚠️ 模型的张量名、行顺序与输出语义必须从<b>元数据</b>确认，不要靠猜</h2>
 *
 * <p>这条不是洁癖，本模块已经踩到两次：
 *
 * <ol>
 *   <li><b>输入名会变。</b> 同一个 Beat This! 的两次导出，一次叫
 *       {@code input_spectrogram}（folia 那份）、一次叫 {@code spectrogram}，
 *       batch 维一个是 {@code 1}、一个是 {@code -1}。实现里写死名字就会在其中一份上
 *       直接失败——这还算好的；坏的是<b>按位置取输出</b>：真实模型有
 *       {@code beat} 与 {@code downbeat} 两个输出，取错了不报错，
 *       只会拿 downbeat 的激活值去挑拍点（听感是「拍点整体偏移」）。
 *       所以桌面实现取的是会话元数据里的输入名，输出<b>按名字</b>取。</li>
 *   <li><b>多输出的行顺序是模型自己定的。</b> 分轨模型（htdemucs）那一类的例子更直白：
 *       folia 的 {@code SOURCES = ["drums","bass","other","vocals"]} 就是<b>模型输出的
 *       行顺序</b>，出自参考实现 {@code demucs_onnx 0.3.4} 的
 *       {@code _chunked_separate_single}；猜错不会报错，只会把鼓当人声往外送。
 *       QPlayer 本轮没接分轨，但同一条纪律适用：
 *       <b>任何「第几行是什么」的说法都要有一个可指的出处，要么是模型元数据，
 *       要么是参考实现的实现细节，不能是「应该是这样」。</b></li>
 * </ol>
 *
 * <h2>为什么实现不在 player-core</h2>
 *
 * <p>QPlayer 的两个宿主推理底座不可能共用：桌面端用
 * {@code com.microsoft.onnxruntime:onnxruntime}（JAR + 原生动态库，随桌面包分发），
 * 安卓端要用 {@code com.microsoft.onnxruntime:onnxruntime-android}（AAR，
 * 按 ABI 打进 APK，arm64-v8a 一个就十几兆）。而 <b>player-core 必须保持
 * Java 8 且不背原生依赖</b>（安卓直接依赖它打出来的 jar）。
 *
 * <p>所以 player-core 只放<b>接口 + 工厂</b>（{@link BeatModels#install}）+
 * 纯 Java 的前端与后处理（{@link MelFrontend} / {@link Peaks}）+
 * 模型清单与校验的纯逻辑（{@link ModelManifest} / {@link ModelStore}）——
 * 与 {@code audio.decode.Decoder} / {@code audio.output.OutputDevices} 是同一分层。
 *
 * <h2>安卓怎么接（本轮不做，接缝先留好）</h2>
 *
 * <ol>
 *   <li>{@code android-shell} 的 Gradle 加
 *       {@code implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")}
 *       ——AAR 自带 {@code libonnxruntime.so}，会按 ABI 进 APK；
 *       若要控制体积，可以在 {@code abiFilters} 里只留 {@code arm64-v8a}。</li>
 *   <li>写一个 {@code AndroidBeatModels implements BeatModels.Opener}，内部就是
 *       {@code OrtEnvironment.getEnvironment()} + {@code OrtSession}——
 *       <b>可以直接复用 desktop-host 里那份 {@code OnnxInferSession}</b>，
 *       它只用 ONNX Runtime 的公开 Java API，没有一行桌面专属的东西；
 *       真要复用就把那个类挪进一个共用模块，<b>不要</b>把它挪进 player-core
 *       （那会把原生依赖带进去）。</li>
 *   <li>在 {@code Application.onCreate()} 里
 *       {@code BeatModels.install(new AndroidBeatModels())}。</li>
 *   <li>模型文件目录：安卓的用户数据目录（{@code context.getFilesDir()/models}）。
 *       目录只有一处（{@code AppDirs.modelsDir()}，桌面与安卓同一条路，
 *       用户在设置里也能改），所以安卓侧要做的是把 {@code AppDirs} 的基础路径指到
 *       它的私有目录，而不是另立一个「安卓专用」的模型目录——
 *       两个目录意味着「明明下好了，拍点这条路却说没有」。<b>本轮不做</b>。</li>
 * </ol>
 *
 * <p>没接线时 {@link BeatModels#isInstalled()} 为 {@code false}，AI 模式自动走硬算——
 * 安卓这一轮即使一行都不改，行为也是对的（只是没有模型那条路）。
 *
 * <h2>线程约定</h2>
 *
 * <p>{@link #beats(float[])} 与上游的 {@code BeatModel::beats(&self)} 一样是
 * <b>只读</b>的：同一个会话可以并发复用（ONNX Runtime 的 {@code OrtSession.run}
 * 本身线程安全）。但前端有暂存缓冲区，所以 {@code BeatModel} 上游用
 * {@code RefCell} 把「复用前端」这件事包起来——本接口因此<b>不承诺</b>
 * 「同一个实例可以被两条线程同时调用 {@code beats}」，宿主实现要自己保证
 * （desktop-host 的实现按「每次 {@code beats} 自带一个前端」做，
 * 于是同一个会话能被并发使用；这与上游那条
 * {@code a_reused_frontend_matches_a_fresh_one} 的性质不冲突——
 * 它锁的是「复用与新建结果逐位相同」，不是「必须复用」）。
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::beat::BeatModel}。
 */
public interface BeatDetector extends AutoCloseable {

    /** 模型标识（模型 id 或文件名），仅用于日志与错误信息。 */
    String label();

    /**
     * 前处理参数。
     *
     * <p>输入必须是 {@link MelParams#sampleRate} 的单声道 PCM；采样率不符时
     * <b>由调用方先重采样</b>（{@link BeatModels#resampleMono} 就是给这个用的）。
     * 上游刻意不在这里暗中重采样：否则「结果不对」会难以定位。
     *
     * <p>上游 {@code BeatModel} 把这个参数单独留了一份（{@code mel_params()} 要借出
     * {@code &MelParams}，而从 {@code RefCell<MelFrontend>} 里借不出生命周期足够长的
     * 引用）——本接口直接把它做成契约的一部分：<b>调用方要先问它要采样率</b>。
     */
    MelParams melParams();

    /** 峰值拾取参数。 */
    PeakParams peakParams();

    /**
     * 检测拍点，返回<b>秒</b>。
     *
     * <p>输入必须是 {@link #melParams()}{@code .sampleRate} 的<b>单声道</b> PCM。
     * 素材短于一个 FFT 窗口时返回<b>空数组</b>（上游 {@code Ok(Vec::new())}）——
     * 那不是错误，是「没得分析」。
     *
     * @throws InferException 加载后的运行期失败（形状对不上、算子没实现、
     *                        输出不是 f32 等）。调用方据此降级到硬算路
     */
    float[] beats(float[] pcm);

    /**
     * 释放会话。
     *
     * <p>{@code close()} 之后再调 {@link #beats(float[])} 的行为<b>未定义</b>
     * （ONNX Runtime 会抛它自己的异常），这是有意的：多一次「已关闭」检查
     * 会让每个实现都要维护一个状态位，而调用方本来就不该这么做。
     */
    @Override
    void close();
}
