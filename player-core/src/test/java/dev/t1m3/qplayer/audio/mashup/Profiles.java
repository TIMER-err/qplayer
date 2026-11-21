package dev.t1m3.qplayer.audio.mashup;

/**
 * 测试用的档案与色度素材。
 *
 * <p>放在一处而不是各测试各写一份：色度一旦两次写得不一样，配对判据量的就不是同一件事。
 */
final class Profiles {

    private Profiles() {
    }

    /** 只有键和时长：什么结论都没有。 */
    static TrackProfile minimal() {
        return TrackProfile.builder("min").durationSecs(100).build();
    }

    /** 什么都有的一份（8 小节）。 */
    static TrackProfile rich() {
        float[] rms = {0.10f, 0.40f, 0.80f, 0.90f, 0.70f, 0.86f, 0.88f, 0.30f};
        float[] onset = {0.05f, 0.20f, 0.60f, 0.90f, 0.50f, 0.70f, 0.80f, 0.10f};
        float[] vocal = {0.00f, 0.90f, 1.00f, 1.00f, 0.80f, 1.00f, 1.00f, 0.20f};
        return TrackProfile.builder("sha256:rich")
                .durationSecs(213.4)
                .tempo(128.0, 0.82, 22_050, 10_335.94f, 123.5f)
                .loudness(-9.3)
                .key(9, "minor", 0.61, chromaOf(9, "minor"))
                .structure(4.1, 186.2)
                .bars(1.875, rms, onset, vocal, 0.74)
                .build();
    }

    /** 一首"速度明确、调性明确"的歌。 */
    static TrackProfile of(String key, double bpm, int tonic, String mode,
                           double durationSecs, double vocalShare) {
        double period = 22_050.0 * 60.0 / bpm;
        return TrackProfile.builder(key)
                .durationSecs(durationSecs)
                .tempo(bpm, 0.8, 22_050, (float) period, 0f)
                .loudness(-10.0)
                .key(tonic, mode, 0.7, chromaOf(tonic, mode))
                .structure(-1, -1)
                .bars(240.0 / bpm, new float[]{0.5f, 0.6f}, new float[]{0.5f, 0.6f},
                        new float[]{(float) vocalShare, (float) vocalShare}, vocalShare)
                .build();
    }

    /** 调性未知的歌（没有色度、没有主音）。 */
    static TrackProfile withoutKey(String key, double bpm, double durationSecs) {
        double period = 22_050.0 * 60.0 / bpm;
        return TrackProfile.builder(key)
                .durationSecs(durationSecs)
                .tempo(bpm, 0.8, 22_050, (float) period, 0f)
                .loudness(-10.0)
                .key(-1, "", 0.0, null)
                .structure(-1, -1)
                .bars(240.0 / bpm, new float[]{0.5f}, new float[]{0.5f}, new float[]{0.1f}, 0.1)
                .build();
    }

    /** 一个"只有这根音"的色度（拿去构造必然不兼容的调性）。 */
    static float[] singlePitchChroma(int pitchClass) {
        float[] c = new float[12];
        c[Math.floorMod(pitchClass, 12)] = 1f;
        return c;
    }

    /**
     * 某个主音/调式下"像样"的色度：主音最重、三度与五度次之、导音再次。
     *
     * <p>不追求乐理上严谨，只要<b>同调之间相似度高、不同调之间相似度低</b>这两条性质成立，
     * 配对判据才有可判性。
     */
    static float[] chromaOf(int tonic, String mode) {
        float[] c = new float[12];
        int third = mode != null && mode.equals("minor") ? 3 : 4;
        c[Math.floorMod(tonic, 12)] += 1.0f;
        c[Math.floorMod(tonic + third, 12)] += 0.7f;
        c[Math.floorMod(tonic + 7, 12)] += 0.6f;
        c[Math.floorMod(tonic + 11, 12)] += 0.25f;
        float sum = 0f;
        for (float v : c) {
            sum += v;
        }
        for (int i = 0; i < c.length; i++) {
            c[i] /= sum;
        }
        return c;
    }
}
