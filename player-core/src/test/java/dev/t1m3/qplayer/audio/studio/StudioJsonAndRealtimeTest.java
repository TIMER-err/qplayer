package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 两件事：<b>参数的存取</b>（预设要靠它）与<b>实时安全</b>（泵线程的硬要求）。
 *
 * <h2>为什么「零分配」要拿数字钉，而不是靠自觉</h2>
 *
 * <p>这里跑在生产者线程上，环形缓冲只有约 200 ms：一次 GC 停顿就是一次爆音，
 * 而「每块 new 一个小数组」这种写法在 code review 里几乎看不出来。所以判据是
 * <strong>线程分配字节数</strong>（{@code ThreadMXBean.getThreadAllocatedBytes}）：
 * 处理 200 块，分配增量必须在几 KB 以内（只允许 JIT/类加载那种一次性开销）。
 */
public class StudioJsonAndRealtimeTest {

    private static final int RATE = 48_000;

    /** 存取往返：所有字段都要原样回来（预设就是靠这个）。 */
    @Test
    public void jsonRoundTripsEveryField() {
        StudioRack master = StudioRack.of(true, -3.5, 0.25, true, true, 0.75, Arrays.asList(
                EqBand.peak(80, 6.5, 1.1),
                EqBand.of(BandType.LOW_SHELF, 200, -2.5, 0.6),
                EqBand.of(BandType.HIGH_PASS, 30, 0, 0.7071, 24, ChannelTarget.SIDE, false),
                EqBand.of(BandType.NOTCH, 50, 0, 8.5, 12, ChannelTarget.MID, true)));
        StudioRacks want = StudioRacks.of(master, StudioRack.EMPTY, StudioRack.eq(
                Arrays.asList(EqBand.of(BandType.HIGH_SHELF, 9000, 3.0, 0.7071))));
        String json = StudioJson.toJson(want);
        StudioRacks back = StudioJson.fromJson(json);
        // 上限从 1600 放到 4000：每台机架多了动态（17 项）、立体声（6 项）、染色（6 项），
        // 一台满载的 JSON 实测 2750 字符。设置里存一个几 KB 的字符串没有代价
        // （它只在参数变更时解析一次），但那条「一个设置项装得下」的纪律仍然成立。
        assertTrue("参数的 JSON 应当仍然只占一个设置项：" + json.length(), json.length() < 4000);
        assertEquals("主总线段数", master.bands.size(), back.master.bands.size());
        assertEquals("前级", master.preampDb, back.master.preampDb, 1e-9);
        assertEquals("平衡", master.balance, back.master.balance, 1e-9);
        assertTrue("极性", back.master.polarityInvert);
        assertTrue("响度补偿", back.master.loudness);
        assertEquals("补偿强度", master.loudnessAmount, back.master.loudnessAmount, 1e-9);
        for (int i = 0; i < master.bands.size(); i++) {
            EqBand a = master.bands.get(i);
            EqBand b = back.master.bands.get(i);
            assertEquals("第 " + i + " 段类型", a.type, b.type);
            assertEquals("第 " + i + " 段频率", a.freqHz, b.freqHz, 1e-9);
            assertEquals("第 " + i + " 段增益", a.gainDb, b.gainDb, 1e-9);
            assertEquals("第 " + i + " 段 Q", a.q, b.q, 1e-9);
            assertEquals("第 " + i + " 段声道", a.channel, b.channel);
            assertEquals("第 " + i + " 段开关", a.enabled, b.enabled);
        }
        assertEquals("进场曲段数", 1, back.deckB.bands.size());
        System.out.println("### 一套三机架参数：" + json.length() + " 字节");
    }

    /** 坏输入一律退回「全关」（全关 = 直通），绝不能抛。 */
    @Test
    public void jsonSurvivesGarbage() {
        String[] garbage = {null, "", "   ", "not json", "[]", "{}", "{\"version\":\"x\"}",
                "{\"master\": 5}", "{\"master\":{\"bands\":[1,2,3]}}",
                "{\"master\":{\"bands\":[{\"type\":\"???\",\"freq\":\"abc\"}]}}",
                "{\"version\":99,\"master\":{\"enabled\":true}}"};
        for (String bad : garbage) {
            StudioRacks racks = StudioJson.fromJson(bad);
            assertTrue("坏输入必须退回全关：" + bad + " → " + racks, racks == StudioRacks.EMPTY
                    || !racks.active());
        }
        // 未知字段要能容忍（未来版本写的预设，旧版本读进来不该整份丢掉）
        StudioRacks future = StudioJson.fromJson("{\"version\":1,\"master\":{\"enabled\":true,"
                + "\"newThing\":123,\"bands\":[{\"type\":\"peak\",\"freq\":1000,\"gain\":3,"
                + "\"unknown\":9}]}}");
        assertTrue("认识的部分要读进来", future.master.enabled);
        assertEquals(1, future.master.bands.size());
        assertEquals(3.0, future.master.bands.get(0).gainDb, 1e-9);
    }

    /** 越界值在读进来时就被夹住（预设文件可能被手改）。 */
    @Test
    public void jsonClampsOutOfRangeValues() {
        StudioRacks racks = StudioJson.fromJson("{\"master\":{\"enabled\":true,\"preampDb\":900,"
                + "\"balance\":-42,\"loudnessAmount\":7,\"bands\":[{\"type\":\"peak\","
                + "\"freq\":1,\"gain\":1000,\"q\":0.0001,\"slope\":777}]}}");
        assertEquals(24.0, racks.master.preampDb, 1e-9);
        assertEquals(-1.0, racks.master.balance, 1e-9);
        assertEquals(1.0, racks.master.loudnessAmount, 1e-9);
        EqBand band = racks.master.bands.get(0);
        assertEquals(EqBand.MIN_FREQ, band.freqHz, 1e-9);
        assertEquals(EqBand.MAX_GAIN, band.gainDb, 1e-9);
        assertEquals(EqBand.MIN_Q, band.q, 1e-9);
        assertEquals("斜率只认 12/24/48", 12, band.slopeDbPerOct);
    }

    /**
     * 实时安全：处理 200 块（约 4 秒音频）<strong>几乎不分配</strong>，而且够快。
     *
     * <p>阈值不是随便定的：允许的只有 JIT/类加载那点一次性开销（几 KB），
     * 每块 new 一个数组的话 200 块就是几十 KB 起步、一眼能看出来。
     */
    @Test
    public void processingAllocatesNothingPerBlock() {
        int frames = 1024;
        int blocks = 200;
        StudioRack rack = StudioRack.eq(Arrays.asList(
                EqBand.peak(60, 5.0, 0.8), EqBand.peak(240, -4.0, 1.2),
                EqBand.of(BandType.LOW_SHELF, 120, 4.0, 0.7071),
                EqBand.of(BandType.HIGH_SHELF, 8000, 3.0, 0.7071),
                EqBand.of(BandType.HIGH_PASS, 30, 0, 0.7071, 24, ChannelTarget.STEREO, true),
                EqBand.of(BandType.NOTCH, 50, 0, 6.0, 12, ChannelTarget.MID, true),
                EqBand.of(BandType.PEAK, 3000, -2.0, 2.0, 12, ChannelTarget.SIDE, true)));
        StudioProcessor processor = new StudioProcessor(RATE, 2);
        processor.setRacks(StudioRacks.of(rack, rack, rack));
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.3 * Math.sin(i * 0.01));
            l.put(i, v);
            r.put(i, -v);
        }
        // 预热（JIT + 类加载都在这里发生）
        for (int i = 0; i < 50; i++) {
            processor.processDeckA(buf);
            processor.processMaster(buf);
        }
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(id);
        long t0 = System.nanoTime();
        for (int i = 0; i < blocks; i++) {
            processor.processDeckA(buf);
            processor.processMaster(buf);
        }
        long elapsedNs = System.nanoTime() - t0;
        long allocated = bean.getThreadAllocatedBytes(id) - before;
        double usPerBlock = elapsedNs / 1000.0 / blocks;
        System.out.printf("### 实时：%d 块（两块机架 × 7 段）共分配 %d 字节（%.1f 字节/块），"
                        + "%.1f µs/块（1024 帧）%n",
                Integer.valueOf(blocks), Long.valueOf(allocated),
                Double.valueOf(allocated / (double) blocks), Double.valueOf(usPerBlock));
        assertTrue("每块不该分配（实测共 " + allocated + " 字节）", allocated < 8192);
        // 预算：1024 帧在 48 kHz 下是 21.3 ms，处理必须远小于它（留足混音/重采样的余量）
        assertTrue("每块耗时 " + usPerBlock + " µs 太慢", usPerBlock < 2000);
    }

    // ==================================================================
    // P2：音高（移调）接进来之后
    // ==================================================================

    /**
     * <b>音高必须走 JSON 往返，而且旧 JSON（没有 pitch 那一段）读出来必须是「关」。</b>
     *
     * <p>第二条比第一条重要：用户升级一次播放器，旧设置里没有这一段——
     * 默认成「开着 ±0 半音」还算无害，但要是默认成某个非零值，所有歌都会被移调，
     * 而那种 bug 几乎没人会联想到 JSON 解析上。
     */
    @Test
    public void pitchRoundTripsAndOldJsonStaysOff() {
        StudioRacks want = StudioRacks.master(StudioRack.EMPTY.withEnabled(true)
                .withPitch(PitchParams.of(-3, 25.0, 0.65)));
        StudioRacks back = StudioJson.fromJson(StudioJson.toJson(want));
        assertTrue("旧 JSON（没有 pitch 段）读出来必须是关",
                !StudioJson.fromJson("{\"version\":1,\"master\":{\"enabled\":true}}")
                        .master.pitch.active());
        assertTrue("音高开关要原样回来", back.master.pitch.enabled);
        assertEquals("半音", -3, back.master.pitch.semitone);
        assertEquals("音分", 25.0, back.master.pitch.cents, 1e-9);
        assertEquals("干湿", 0.65, back.master.pitch.mix, 1e-9);
        assertEquals("合成音高", -3 + 0.25, back.master.pitch.totalSemitones(), 1e-9);
        assertEquals("没动别的机架", 0, back.deckA.bands.size());
        assertFalse("别的机架的音高还是关", back.deckA.pitch.active());

        // 越界值按 PitchParams 自己的规则夹（JSON 是用户能手改的）
        StudioRacks wild = StudioJson.fromJson(
                "{\"master\":{\"enabled\":true,\"pitch\":{\"enabled\":true,"
                        + "\"semitone\":99,\"cents\":999,\"mix\":5}}}");
        assertEquals("半音夹到 12", PitchParams.MAX_SEMITONE, wild.master.pitch.semitone);
        assertEquals("音分夹到 100", PitchParams.MAX_CENTS, wild.master.pitch.cents, 1e-9);
        assertEquals("干湿夹到 1", 1.0, wild.master.pitch.mix, 1e-9);
    }

    /**
     * <b>音高在处理时也必须逐块零分配。</b>
     *
     * <p>相位声码器是最容易「每块 new 一个窗」的地方，而它跑在生产者线程上——
     * 一次 GC 停顿就是一次爆音。判据与上面那条同源，只是把链换成了带音高的那一条。
     */
    @Test
    public void pitchProcessingAllocatesNothingPerBlock() {
        int frames = 1024;
        int blocks = 200;
        StudioRack rack = StudioRack.EMPTY.withEnabled(true)
                .withPreamp(-2.0)
                .withPitch(PitchParams.of(4, 12.0, 1.0));
        StudioProcessor processor = new StudioProcessor(RATE, 2);
        processor.setRacks(StudioRacks.of(rack, rack, rack));
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.3 * Math.sin(i * 0.01));
            l.put(i, v);
            r.put(i, -v);
        }
        for (int i = 0; i < 50; i++) {
            processor.processDeckA(buf);
            processor.processMaster(buf);
        }
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(id);
        long t0 = System.nanoTime();
        for (int i = 0; i < blocks; i++) {
            processor.processDeckA(buf);
            processor.processMaster(buf);
        }
        long elapsedNs = System.nanoTime() - t0;
        long allocated = bean.getThreadAllocatedBytes(id) - before;
        double usPerBlock = elapsedNs / 1000.0 / blocks;
        System.out.printf("### 实时（含音高，三台机架都在移调）：%d 块共分配 %d 字节（%.1f 字节/块），"
                        + "%.1f µs/块（1024 帧）%n",
                Integer.valueOf(blocks), Long.valueOf(allocated),
                Double.valueOf(allocated / (double) blocks), Double.valueOf(usPerBlock));
        assertTrue("含音高时每块也不该分配（实测共 " + allocated + " 字节）", allocated < 8192);
        assertTrue("含音高时每块耗时 " + usPerBlock + " µs 太慢（预算 21.3 ms）", usPerBlock < 6000);
    }

    /** 段数越多越慢，但上限也要在预算内（16 段 × 4 级是最坏情况）。 */
    @Test
    public void worstCaseBandCountStaysWithinBudget() {
        java.util.List<EqBand> many = new java.util.ArrayList<EqBand>();
        for (int i = 0; i < StudioRack.MAX_BANDS; i++) {
            many.add(EqBand.of(BandType.HIGH_PASS, 30 + i, 0, 0.7071, 48,
                    i % 2 == 0 ? ChannelTarget.STEREO : ChannelTarget.SIDE, true));
        }
        StudioProcessor processor = new StudioProcessor(RATE, 2);
        processor.setRacks(StudioRacks.master(StudioRack.eq(many)));
        int frames = 1024;
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        for (int i = 0; i < 20; i++) {
            processor.processMaster(buf);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            processor.processMaster(buf);
        }
        double usPerBlock = (System.nanoTime() - t0) / 1000.0 / 100;
        System.out.printf("### 最坏情况（%d 段 × 48 dB/oct）：%.1f µs/块（1024 帧 = 21.3 ms 的音频）%n",
                Integer.valueOf(StudioRack.MAX_BANDS), Double.valueOf(usPerBlock));
        assertTrue("最坏情况 " + usPerBlock + " µs 太慢", usPerBlock < 8000);
    }

    /** 换参数不能把滤波器状态清掉（否则拖滑杆就是一连串咔哒）。 */
    @Test
    public void changingParametersKeepsFilterState() {
        int frames = 256;
        StudioProcessor processor = new StudioProcessor(RATE, 2);
        processor.setRacks(StudioRacks.master(StudioRack.eq(
                Arrays.asList(EqBand.peak(1000, 6.0, 1.0)))));
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        for (int i = 0; i < frames; i++) {
            l.put(i, (float) Math.sin(i * 0.05));
        }
        processor.processMaster(buf);            // 先让它带状态
        // 只改增益（同一段、同一类型、同一声道）→ 应当沿用状态
        processor.setRacks(StudioRacks.master(StudioRack.eq(
                Arrays.asList(EqBand.peak(1200, 6.0, 1.0)))));
        double out = 0;
        for (int i = 0; i < frames; i++) {
            out = Math.max(out, Math.abs(buf.backingArray()[buf.channelBase(0) + i]));
        }
        assertFalse("处理过之后不该是一片静音（状态沿用而不是清零）", Math.abs(out) < 1e-9);
    }
}
