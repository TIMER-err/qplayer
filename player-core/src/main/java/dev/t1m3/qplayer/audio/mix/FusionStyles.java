package dev.t1m3.qplayer.audio.mix;

/**
 * <b>我们自己的四个接法，作为"额外模板"参与参考包的排序</b>（取长补短的第二步）。
 *
 * <p><b>2026-11-21 用户拍板：拍点剪切不参与竞争</b>（「去掉拍点剪切这个有问题，
 * 之前走尾音那个是对的更好听」）。原因是它的渲染语义是"窗口末尾硬切"，
 * 一旦和长窗口组合就会把 B 压到最后一帧才进（实测颗粒化）；
 * 它仍可在设置里作为**手动档位**单独使用，只是不再由融合排序选中。
 *
 * <p>参考包里那 24 个模板是 Apple 的；剩下这三个是我们自己做的、听感上不同的接法：
 * <ul>
 *   <li>{@link MixStyle#BASS_SWAP 低音交接}：高频先换、低频后换，接缝不糊；</li>
 *   <li>{@link MixStyle#TAIL_RIDE 尾音骑行}：出场尾巴骑在进场曲上；</li>
 *   <li>{@link MixStyle#SHOW_MODE 表现模式}：出场高通上扫渐弱、进场低通打开渐强。</li>
 * </ul>
 *
 * <p>做法不是"另开一套评分"，而是把它们写成与参考包同构的 {@link ReferenceProfiles.Profile}
 * （同样的 base/闸门/因子/tier/时长规则），这样它们和 24 个 Apple 模板在**同一把尺子**上
 * 竞争——谁赢就按谁的窗口与落点执行，日志里标 {@code [我们的接法]}。
 *
 * <p>base 分数刻意与 Apple 的同族模板对齐（拍点剪切≈smart 的 10、尾音骑行≈smart 的 10、
 * 低音交接≈filtered 的 20、表现模式≈dance 的 17）——base 是"原生先验"，
 * 定高了会让我们的接法仅凭先验就压过 Apple 的模板，那不是融合、是覆盖。
 *
 * <p>id 从 900 起，避免与参考包的 0..44 撞号。
 */
public final class FusionStyles {

    private FusionStyles() {
    }

    /** 四个自己接法的合成模板（字段口径与 profiles.json 一致）。 */
    public static final ReferenceProfiles.Profile[] PROFILES = {
            // 低音交接：对拍、16 拍、需要滤波能力；同调性优先
            // key_policy 必须是参考包认可的三个值之一（any / compatible / identical）：
            // 写 "soft" 会被当成"未知策略" ⇒ 对拍类模板在调性闸门上被直接淘汰（实测）
            profile(901, "低音交接（我们的）", "bass-swap", "filtered", 2.0, 20.0, true, 16.0, 2.0,
                    "compatible", 0.6, "our-style: BASS_SWAP"),
            // 尾音骑行：不对拍、按秒算（12 秒），只需要 fade 能力
            profile(902, "尾音骑行（我们的）", "tail-ride", "smart", 2.0, 10.0, false, 12.0, 1.0,
                    "compatible", 0.4, "our-style: TAIL_RIDE"),
            // 表现模式：对拍、16 拍、复杂度 3（高通扫频 + 低通打开）
            // family 用 filtered 而不是 dance：dance 那一族在参考包里还带 "流派词" 闸门
            // （dance/electronic/house/techno），而我们的流派是 BPM 代理出来的，
            // 一旦 BPM 落在 70–110 就会被代理成 hip-hop ⇒ 表现模式被**流派**闸门误杀。
            // 用 filtered 只保留对拍/调性/拍号这些真有依据的闸门。
            profile(903, "表现模式（我们的）", "show-mode", "filtered", 2.0, 17.0, true, 16.0, 3.0,
                    "compatible", 0.5, "our-style: SHOW_MODE"),
    };

    private static ReferenceProfiles.Profile profile(int id, String name, String effect, String family,
                                                     double tier, double base, boolean beatMatched,
                                                     double preferredBeats, double complexity,
                                                     String keyPolicy, double vocalSuppression,
                                                     String provenance) {
        return new ReferenceProfiles.Profile(0.0, base, provenance, beatMatched, provenance, effect,
                family, id, keyPolicy, name, provenance, preferredBeats, 0.0, complexity, tier,
                vocalSuppression);
    }

    /** 某个 id 是不是我们自己接法；是就返回对应的 {@link MixStyle}，否则 {@code null}。 */
    public static MixStyle mixStyleOf(int templateId) {
        switch (templateId) {
            case 901:
                return MixStyle.BASS_SWAP;
            case 902:
                return MixStyle.TAIL_RIDE;
            case 903:
                return MixStyle.SHOW_MODE;
            default:
                return null;
        }
    }

    /** 名字（日志用）。 */
    public static String nameOf(int templateId) {
        MixStyle style = mixStyleOf(templateId);
        return style == null ? null : style.label();
    }
}
