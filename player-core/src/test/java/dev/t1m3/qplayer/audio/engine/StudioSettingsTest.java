package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.studio.BandType;
import dev.t1m3.qplayer.audio.studio.EqBand;
import dev.t1m3.qplayer.audio.studio.PitchParams;
import dev.t1m3.qplayer.audio.studio.StudioJson;
import dev.t1m3.qplayer.audio.studio.StudioPresets;
import dev.t1m3.qplayer.audio.studio.StudioRack;
import dev.t1m3.qplayer.audio.studio.StudioRacks;

import dev.t1m3.qplayer.settings.SettingSpec;
import dev.t1m3.qplayer.settings.SettingsCatalog;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P1 的接线判据：<b>设置里那几行 → 三台机架</b>的组合必须与界面上的说法一致。
 *
 * <p>组合逻辑是纯函数（{@code AutomixSettings.composeStudio}），所以这里直接喂参数核，
 * 不用造设置宿主：<b>「作用在哪一台」「曲线选关时不要覆盖自定义 JSON」这些语义</b>
 * 全靠这张表钉住。另外顺带钉住两件容易悄悄分叉的事：
 *
 * <ul>
 *   <li>下拉的选项文字与 {@link StudioPresets} 的曲线<strong>一一对应</strong>
 *       （数量与顺序都不能错位，否则界面上选「人声」实际上了「低频」）；</li>
 *   <li>五行都落在「音频工作室」那一组里，依赖关系声明齐全（不然关掉曲线那几行还在）。</li>
 * </ul>
 */
public class StudioSettingsTest {

    /** 曲线选「关」、没有自定义 JSON：整条链就是关的（直通）。 */
    @Test
    public void everythingAtDefaultsIsBypassed() {
        StudioRacks racks = AutomixSettings.composeStudio(0, 0, 0, false, "");
        assertFalse("默认必须是直通", racks.active());
        assertFalse(racks.master.active());
        assertFalse(racks.deckA.active());
        assertFalse(racks.deckB.active());
    }

    /** 曲线 + 作用机架：只上指定的那一台。 */
    @Test
    public void thePresetLandsOnTheChosenRackOnly() {
        int lowBoost = indexOf("低频增强");
        StudioRacks master = AutomixSettings.composeStudio(lowBoost, 0, 0, false, "");
        assertEquals("主总线应当是那条曲线", StudioPresets.at(lowBoost).rack.bands.size(),
                master.master.bands.size());
        assertEquals("出场曲应当是空的", 0, master.deckA.bands.size());
        assertEquals("进场曲应当是空的", 0, master.deckB.bands.size());

        StudioRacks deckA = AutomixSettings.composeStudio(lowBoost, 1, 0, false, "");
        assertEquals("出场曲", StudioPresets.at(lowBoost).rack.bands.size(), deckA.deckA.bands.size());
        assertEquals(0, deckA.master.bands.size());

        StudioRacks all = AutomixSettings.composeStudio(lowBoost, 3, 0, false, "");
        assertEquals("三台都上", StudioPresets.at(lowBoost).rack.bands.size(),
                all.master.bands.size());
        assertEquals(StudioPresets.at(lowBoost).rack.bands.size(), all.deckA.bands.size());
        assertEquals(StudioPresets.at(lowBoost).rack.bands.size(), all.deckB.bands.size());
    }

    /** 前级增益与响度开关始终叠加在选中的那一台上。 */
    @Test
    public void gainAndLoudnessAlwaysApply() {
        StudioRacks racks = AutomixSettings.composeStudio(indexOf("人声突出"), 1, -6, true, "");
        assertEquals("前级要落在选中的机架上", -6.0, racks.deckA.preampDb, 1e-9);
        assertEquals("主总线不该被动", 0.0, racks.master.preampDb, 1e-9);
        assertTrue("响度补偿要开着", racks.deckA.loudness);
        assertTrue("强度不能是 0（否则等于没开）", racks.deckA.loudnessAmount > 0.5);
        assertTrue(racks.active());
    }

    // ==================================================================
    // P2：音高那四行
    // ==================================================================

    /**
     * <b>音高落在「作用机架」选中的那一台上，半音与音分相加。</b>
     *
     * <p>为什么半音与音分要在<strong>这一层</strong>就合起来：界面给的是两个滑杆，
     * 而下游（{@code PitchParams.totalSemitones()}）只认一个数——合成必须只有一处，
     * 否则「界面显示 +1 半音 +50 音分、实际移了 +1 半音」这种偏差没人查得出来。
     */
    @Test
    public void pitchLandsOnTheChosenRackWithSemitonesPlusCents() {
        StudioRacks master = AutomixSettings.composeStudio(0, 0, 0, false, "",
                true, -2, 50, 100);
        assertTrue("音高开着就算在处理", master.active());
        assertTrue("主总线上的音高要开", master.master.pitch.active());
        assertEquals("半音", -2, master.master.pitch.semitone);
        assertEquals("音分", 50.0, master.master.pitch.cents, 1e-9);
        assertEquals("最终音高 = 半音 + 音分/100", -1.5, master.master.pitch.totalSemitones(), 1e-9);
        assertEquals("干湿", 1.0, master.master.pitch.mix, 1e-9);
        assertFalse("别的机架不该被动", master.deckA.pitch.active());

        StudioRacks deckB = AutomixSettings.composeStudio(0, 2, 0, false, "",
                true, 3, 0, 50);
        assertFalse("只作用在进场曲那一台", deckB.master.pitch.active());
        assertTrue(deckB.deckB.pitch.active());
        assertEquals("干湿一半", 0.5, deckB.deckB.pitch.mix, 1e-9);

        // 干湿 0 = 原样：参数合成了，但**不算在工作**（机架也不该报在处理）
        StudioRacks dry = AutomixSettings.composeStudio(0, 0, 0, false, "",
                true, 3, 0, 0);
        assertFalse("干湿 0 时音高不算在工作", dry.master.pitch.active());
        assertFalse("这时整台机架也不该报在处理（位完美不该无缘无故熄灭）", dry.master.active());

        // 三台都上
        StudioRacks all = AutomixSettings.composeStudio(0, 3, 0, false, "",
                true, 1, 0, 100);
        assertTrue(all.master.pitch.active());
        assertTrue(all.deckA.pitch.active());
        assertTrue(all.deckB.pitch.active());
    }

    /**
     * <b>音高开关关着时不许动 JSON 里已经写好的音高</b>——与曲线选「关」时保留 JSON 同一条纪律。
     *
     * <p>反过来的行为（关着就清零）会让「我用 JSON 配好的移调，随手把界面上的开关关一下再打开
     * 就没了」，而那种丢失是静默的。
     */
    @Test
    public void pitchOffKeepsWhateverTheCustomJsonHad() {
        String json = StudioJson.toJson(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)
                .withPitch(PitchParams.semitones(-5))));
        StudioRacks kept = AutomixSettings.composeStudio(0, 0, 0, false, json,
                false, 0, 0, 100);
        assertTrue("开关关着时 JSON 里的音高要原样留着", kept.master.pitch.active());
        assertEquals("而且数值不变", -5, kept.master.pitch.semitone);

        StudioRacks overridden = AutomixSettings.composeStudio(0, 0, 0, false, json,
                true, 7, 0, 100);
        assertEquals("开关开着时界面上的值覆盖 JSON", 7, overridden.master.pitch.semitone);
    }

    /** 曲线选「关」时，自定义 JSON 要原样保留（那是高级用户的入口）。 */
    @Test
    public void customJsonSurvivesWhenThePresetIsOff() {
        StudioRacks custom = StudioRacks.of(
                StudioRack.eq(java.util.Arrays.asList(EqBand.peak(120, 4.0, 0.9))),
                StudioRack.eq(java.util.Arrays.asList(EqBand.peak(3000, -3.0, 1.4))),
                StudioRack.EMPTY);
        String json = StudioJson.toJson(custom);
        StudioRacks out = AutomixSettings.composeStudio(0, 3, 2, false, json);
        assertEquals("主总线的自定义段要留着", 1, out.master.bands.size());
        assertEquals(120.0, out.master.bands.get(0).freqHz, 1e-9);
        assertEquals("出场曲的自定义段也要留着", 1, out.deckA.bands.size());
        assertEquals(3000.0, out.deckA.bands.get(0).freqHz, 1e-9);
        assertEquals("前级仍然叠加", 2.0, out.master.preampDb, 1e-9);
        assertEquals("前级也叠加到出场曲", 2.0, out.deckA.preampDb, 1e-9);
    }

    /** 越界的下标不许炸：退回主总线 + 关。 */
    @Test
    public void outOfRangeIndexesAreSafe() {
        StudioRacks racks = AutomixSettings.composeStudio(-5, -1, 0, false, "");
        assertFalse("下标越界 = 关", racks.active());
        StudioRacks weird = AutomixSettings.composeStudio(999, 9, 0, false, "");
        assertFalse("认不出来的曲线也当关", weird.active());
    }

    /** 下拉选项与曲线表必须一一对应（错位的话界面选的和实际上的不是同一条）。 */
    @Test
    public void dropdownLabelsMatchThePresetTable() {
        SettingSpec spec = spec(SettingsCatalog.STUDIO_PRESET_KEY);
        assertNotNull("曲线上那一行必须在", spec);
        assertEquals(SettingSpec.DROPDOWN, spec.type);
        String[] labels = StudioPresets.labels();
        assertEquals("选项数必须与曲线数一致", labels.length, spec.options.size());
        for (int i = 0; i < labels.length; i++) {
            assertEquals("第 " + i + " 个选项与曲线表对不上", labels[i], spec.options.get(i));
        }
        assertEquals("默认是「关」", StudioPresets.OFF, ((Number) spec.def).intValue());
        System.out.println("### 曲线下拉：" + String.join(" / ", labels));
    }

    /** 五行都在「音频工作室」那一组里，依赖声明齐全。 */
    @Test
    public void studioRowsAreGroupedAndWired() {
        String[] keys = {SettingsCatalog.STUDIO_PRESET_KEY, SettingsCatalog.STUDIO_RACK_KEY,
                SettingsCatalog.STUDIO_GAIN_KEY, SettingsCatalog.STUDIO_LOUDNESS_KEY,
                SettingsCatalog.STUDIO_CUSTOM_KEY};
        List<String> seen = new ArrayList<String>();
        for (String key : keys) {
            SettingSpec spec = spec(key);
            assertNotNull("设置里少了这一行：" + key, spec);
            assertEquals("都要落在音频那一类", SettingsCatalog.AUDIO, spec.category);
            assertEquals("都要在 studio 那一组里（界面上是一块）", "studio", spec.group);
            seen.add(key);
        }
        // 增益/响度/作用机架都跟着曲线走；自定义 JSON 独立（它就是给高级用户的）
        for (String key : new String[]{SettingsCatalog.STUDIO_RACK_KEY,
                SettingsCatalog.STUDIO_GAIN_KEY, SettingsCatalog.STUDIO_LOUDNESS_KEY}) {
            assertEquals(key + " 应当依赖曲线那一行",
                    SettingsCatalog.STUDIO_PRESET_KEY, spec(key).dependsOn);
        }
        assertEquals("自定义 JSON 不该被曲线藏起来", "", spec(SettingsCatalog.STUDIO_CUSTOM_KEY)
                .dependsOn);
        System.out.println("### 工作室那一组有 " + seen.size() + " 行");
    }

    private static int indexOf(String name) {
        List<StudioPresets.Preset> all = StudioPresets.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return i;
            }
        }
        throw new AssertionError("没有这条曲线：" + name);
    }

    private static SettingSpec spec(String key) {
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (key.equals(s.key)) {
                return s;
            }
        }
        return null;
    }

    // ==================================================================
    // P5–P7：动态 / 立体声 / 染色那几行
    // ==================================================================

    /**
     * <b>三组新参数都落在「作用机架」选中的那一台上，而且「开着」真的会把机架打开。</b>
     *
     * <p>后者不是小事：机架有自己的总闸（{@code enabled}），曲线选「关」时底座是空机架，
     * 照原样叠加这些行等于把它们静默丢掉——用户按下「压缩」却一点变化都没有，
     * 是这一整块最难查的一种 bug（P2 的音高那一行踩过一次）。
     */
    @Test
    public void dynamicsStereoAndSaturationLandOnTheChosenRackAndOpenIt() {
        StudioRacks master = AutomixSettings.composeStudio(0, 0, 0, false, "", false, 0, 0, 100,
                false, -45, true, -18, 3, 10, 120, 0, false, -1,
                false, 6, 0, 1, false, 100, 0, 0);
        assertTrue("压缩开着 → 机架必须被打开（否则这一行是静默丢失）", master.master.enabled);
        assertTrue("压缩要落上去", master.master.dynamics.compOn);
        assertEquals("阈值", -18.0, master.master.dynamics.compThresholdDb, 1e-9);
        assertEquals("比率", 3.0, master.master.dynamics.compRatio, 1e-9);
        assertFalse("别的机架不该被动", master.deckA.dynamics.compOn);
        assertTrue("这就算在处理", master.active());

        StudioRacks deckA = AutomixSettings.composeStudio(0, 1, 0, false, "", false, 0, 0, 100,
                false, -45, false, -18, 3, 10, 120, 0, false, -1,
                true, 6, 0, 1, true, 150, 120, 30);
        assertTrue("染色落在出场曲那一台", deckA.deckA.saturation.enabled);
        assertEquals("驱动", 6.0, deckA.deckA.saturation.driveDb, 1e-9);
        assertTrue("立体声也在那一台", deckA.deckA.stereo.enabled);
        assertEquals("宽度 150% → 1.5", 1.5, deckA.deckA.stereo.width, 1e-9);
        assertEquals("低音单声道分频点", 120.0, deckA.deckA.stereo.bassMonoHz, 1e-9);
        assertEquals("交叉馈送 30% → 0.30", 0.30, deckA.deckA.stereo.crossfeed, 1e-9);
        assertFalse("主总线不该被动", deckA.master.saturation.enabled);
    }

    /** 立体声的宽度 100% 且别的都是中性时，不许因为「开关开着」就把机架算成在工作。 */
    @Test
    public void neutralStereoDoesNotCountAsWork() {
        StudioRacks racks = AutomixSettings.composeStudio(0, 0, 0, false, "", false, 0, 0, 100,
                false, -45, false, -18, 3, 10, 120, 0, false, -1,
                false, 6, 0, 1, true, 100, 0, 0);
        assertFalse("宽度 100% + 无低音单声道 + 无交叉馈送 = 直通", racks.active());
    }
}
