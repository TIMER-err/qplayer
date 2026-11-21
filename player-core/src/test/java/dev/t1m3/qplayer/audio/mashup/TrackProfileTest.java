package dev.t1m3.qplayer.audio.mashup;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link TrackProfile} 的文本格式：<b>写出去能原样读回来</b>，读不出来的东西一律当没有缓存。
 *
 * <p>为什么不测"格式长得好看"：这份文件的读者只有 {@link TrackProfile#parse(String)}，
 * 判据就该是 round-trip 与"坏输入不抛异常"这两条。
 */
public class TrackProfileTest {

    @Test
    public void roundTripsEveryField() {
        TrackProfile original = Profiles.rich();
        TrackProfile parsed = TrackProfile.parse(original.toText());
        assertNotNull("自己的输出必须能读回来", parsed);
        assertEquals(original.key, parsed.key);
        assertEquals(original.durationSecs, parsed.durationSecs, 1e-4);
        assertEquals(original.bpm, parsed.bpm, 1e-4);
        assertEquals(original.beatConfidence, parsed.beatConfidence, 1e-4);
        assertEquals(original.beatRate, parsed.beatRate);
        assertEquals(original.periodFrames, parsed.periodFrames, 1e-2);
        assertEquals(original.phaseFrames, parsed.phaseFrames, 1e-2);
        assertEquals(original.lufs, parsed.lufs, 1e-4);
        assertEquals(original.keyTonic, parsed.keyTonic);
        assertEquals(original.keyMode, parsed.keyMode);
        assertEquals(original.keyConfidence, parsed.keyConfidence, 1e-4);
        assertEquals(original.structureIntroSecs, parsed.structureIntroSecs, 1e-4);
        assertEquals(original.structureOutroSecs, parsed.structureOutroSecs, 1e-4);
        assertEquals(original.barSecs, parsed.barSecs, 1e-4);
        assertEquals(original.vocalShare, parsed.vocalShare, 1e-4);
        assertArrayEquals(original.meanChroma, parsed.meanChroma, 1e-5f);
        assertArrayEquals(original.barRms, parsed.barRms, 1e-5f);
        assertArrayEquals(original.barOnset, parsed.barOnset, 1e-5f);
        assertArrayEquals(original.barVocal, parsed.barVocal, 1e-5f);
    }

    @Test
    public void ignoresUnknownKeysAndTheirLineOrder() {
        String text = TrackProfile.MAGIC + " 1\n"
                + "futureField 42\n"
                + "key abc\n"
                + "durationSecs 200.0\n"
                + "bpm 128.0\n"
                + "anotherFuture 1 2 3\n";
        TrackProfile p = TrackProfile.parse(text);
        assertNotNull("未知键必须被忽略而不是让整份档案作废", p);
        assertEquals("abc", p.key);
        assertEquals(200.0, p.durationSecs, 1e-9);
        assertEquals(128.0, p.bpm, 1e-9);
    }

    @Test
    public void rejectsWhatItCannotTrust() {
        assertNull("空文本", TrackProfile.parse(null));
        assertNull("空串", TrackProfile.parse(""));
        assertNull("魔数不对", TrackProfile.parse("other-profile 1\nkey a\ndurationSecs 10\n"));
        assertNull("版本不认", TrackProfile.parse(TrackProfile.MAGIC + " 2\nkey a\ndurationSecs 10\n"));
        assertNull("缺 key", TrackProfile.parse(TrackProfile.MAGIC + " 1\ndurationSecs 10\n"));
        assertNull("缺时长", TrackProfile.parse(TrackProfile.MAGIC + " 1\nkey a\n"));
        assertNull("数字坏了",
                TrackProfile.parse(TrackProfile.MAGIC + " 1\nkey a\ndurationSecs 不是数字\n"));
        assertNull("色度维度不对", TrackProfile.parse(TrackProfile.MAGIC + " 1\nkey a\n"
                + "durationSecs 10\nmeanChroma 1 2 3\n"));
        assertNull("每小节长度不一致", TrackProfile.parse(TrackProfile.MAGIC + " 1\nkey a\n"
                + "durationSecs 10\nbarRms 1 2\nbarOnset 1 2\nbarVocal 1\n"));
    }

    @Test
    public void toleratesNanAndInfinityForUnknownNumbers() {
        TrackProfile noLufs = TrackProfile.parse(TrackProfile.MAGIC + " 1\nkey a\n"
                + "durationSecs 120\nlufs NaN\nbpm 120\n");
        assertNotNull(noLufs);
        assertTrue("NaN 必须传过去（= 未知），不能变成 0", Double.isNaN(noLufs.lufs));
    }

    @Test
    public void builderRejectsImpossibleProfiles() {
        try {
            TrackProfile.builder("k").durationSecs(0).build();
            fail("时长为 0 应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("时长"));
        }
        try {
            TrackProfile.builder("k").durationSecs(100)
                    .key(0, "major", 0.5, new float[11]).build();
            fail("色度不是 12 维应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("12"));
        }
        try {
            TrackProfile.builder("k").durationSecs(100)
                    .bars(2.0, new float[]{1f}, new float[]{1f}, new float[]{1f, 2f}, 0.1)
                    .build();
            fail("每小节数组长度不一致应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("长度不一致"));
        }
        try {
            TrackProfile.builder(" ").durationSecs(100).build();
            fail("空键应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("缓存键"));
        }
    }

    @Test
    public void helperPredicatesMatchTheData() {
        TrackProfile rich = Profiles.rich();
        assertTrue(rich.hasTempo());
        assertTrue(rich.hasKey());
        assertEquals(8, rich.bars());
        TrackProfile naked = Profiles.minimal();
        assertTrue(!naked.hasTempo());
        assertTrue(!naked.hasKey());
        assertEquals(0, naked.bars());
    }
}
