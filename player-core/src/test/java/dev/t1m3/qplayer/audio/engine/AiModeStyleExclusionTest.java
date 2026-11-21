package dev.t1m3.qplayer.audio.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import dev.t1m3.qplayer.audio.mix.MixStyle;
import dev.t1m3.qplayer.audio.mix.TransitionFactory;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.audio.mix.Transition;
import org.junit.Test;

/**
 * **A：AI 档必须剔除「人声不撞」**（2026-10-09 ✓ 用户：「你先把A做一下 别选人声不撞了」→「改成剔除」✓）。
 *
 * <p>依据（真机同一对 ✓）：该档让 B 的进场落在极轻处（B=−32.9 dB ✗ 对 A=−7.5 dB ✗）⇒ 融合末尾落差 15 dB
 * ⇒ 听感「A 啪一下没了」✓；而表现模式（B 从 72.1 s 进、B=−10.5 dB ✓ 与 A 同响）完全正常 ✓。
 *
 * <p>这条判据钉的是**行为**：设置里给 AI 档配了 NO_VOCAL_CLASH ⇒ 造出来的接法**不能还是这一档** ✓
 * （应当是"交给打分器在其余档里挑" ✓）。手动选这一档**不受影响** ✓ —— 那是别的模式的事 ✓。
 */
public class AiModeStyleExclusionTest {
    private static TransitionFactory aiWith(int styleUiIndex) {
        return AutomixSettings.factoryFor(true, SettingsCatalog.AUTOMIX_MODE_AI, styleUiIndex,
                6.0f, 4.0f, 0, true, 48000, 2, null, null);
    }

    @Test
    public void aiModeDoesNotKeepTheNoVocalClashStyle() {
        final int ui = MixStyle.NO_VOCAL_CLASH.uiIndex();
        Transition t = aiWith(ui).create();
        assertNotNull("工厂必须造得出接法 ✓", t);
        assertFalse("AI 档配了「人声不撞」时必须**剔除**（不能仍然走这一档 ✗）——实测它会让 A 在融合末尾掉 15 dB ✓",
                t.name() != null && t.name().contains(MixStyle.NO_VOCAL_CLASH.label()));
    }

    @Test
    public void aiModeStillBuildsSomeTransition() {
        Transition t = aiWith(MixStyle.NO_VOCAL_CLASH.uiIndex()).create();
        assertNotNull("剔除之后仍要能正常造出接法（不是拒绝服务 ✗）", t);
    }
}
