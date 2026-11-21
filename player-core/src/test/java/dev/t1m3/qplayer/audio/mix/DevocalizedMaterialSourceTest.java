package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * <b>窗口素材的来源可替换</b>（2026-10-06：修用户报的「人声速度不一致」）。
 *
 * <p>用户的实测反馈：「他确实匹配到了歌词相同段 但是锚点计算有问题……**还有就是人声速度不一致**」。
 * 机制（代码级确认）：自动点位那一层要自己读一段 PCM 做变速预渲染，而渲染路径上的素材在
 * "去人声"那一档是**去过人声**的——它却从**原始整轨**读，于是窗口里 A、B 的人声又回来了，
 * 还分别带着 −3.2% / +3.3% 的速率（两侧拍速本身精确相等，探针实测差 0.000000 BPM）。
 *
 * <p>这条判据钉接缝：① 装了来源就必须**走来源**；② 来源给不出东西时必须**回退读文件**；
 * ③ 卸掉来源也必须回到读文件——那一路要与从前逐位相同。
 */
public class DevocalizedMaterialSourceTest {

    /**
     * 走文件那条路在这个测试环境里会抛（player-core 不装解码器工厂），
     * 所以"回退"用**异常的类型与措辞**钉：只有真进了文件读取那条路才会抛这个。
     */
    private static void assertWentToTheFileReader(String what) {
        try {
            AutoCueTransition.readWindowMaterial("不存在的路径.mp3", null, 0.0, 2, 22050);
            fail(what + "：应当去读文件（本环境没装解码器 ⇒ 必须抛）");
        } catch (IllegalStateException expected) {
            String msg = expected.getMessage();
            assertTrue(what + "：必须是因为进了文件读取那条路才抛的，实际：" + msg,
                    msg != null && msg.contains("解码器"));
        }
    }

    @Test
    public void theInstalledSourceIsConsultedFirst() {
        try {
            // 路径不可能存在 ⇒ 只要读到东西，就一定是来源给的
            float[][] fake = new float[][] {new float[] {0.5f, 0.25f}, new float[] {0.5f, 0.25f}};
            AutoCueTransition.installMaterialSource((path, headers, startSecs, frames, rate) -> fake);
            float[][] got = AutoCueTransition.readWindowMaterial("不存在的路径.mp3", null, 0.0, 2, 22050);
            assertNotNull("装了来源就必须走来源", got);
            assertSame("拿到的必须就是来源给的那一份", fake, got);
        } finally {
            AutoCueTransition.installMaterialSource(null);
        }
    }

    @Test
    public void aFailingSourceFallsBackToTheFileAndRemovingItDoesToo() {
        try {
            AutoCueTransition.installMaterialSource((path, headers, startSecs, frames, rate) -> null);
            assertWentToTheFileReader("来源返回 null");
            AutoCueTransition.installMaterialSource(null);
            assertWentToTheFileReader("卸掉来源");
        } finally {
            AutoCueTransition.installMaterialSource(null);
        }
    }

    @Test
    public void offIsNotASeparatorModeAtAll() {
        // 这条是**前提**而不是防线：OFF 压根不是一个分离器档，调用方（引擎）在套包装之前
        // 就把它挡掉了——所以"去人声那一层"永远不会带着 OFF 走到分离器这里。
        try {
            dev.t1m3.qplayer.audio.stem.StemSeparators.forMode(
                    dev.t1m3.qplayer.audio.stem.StemSeparators.OFF);
            fail("OFF 不是一个分离器档，取它必须失败（引擎在套包装之前就该挡掉）");
        } catch (Throwable expected) {
            assertTrue("必须是「不是一个档」这一类失败，实际：" + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("分离器"));
        }
    }
}
