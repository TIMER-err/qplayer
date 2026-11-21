package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>「新加一个旋钮」这件事必须一次性做全</b>：{@link MixTuning} 里每一个可调字段都要能被
 * <b>区分</b>（{@code equals} / {@code hashCode}）、被 <b>复现</b>（{@code toLine} →
 * {@code parse} 往返）。
 *
 * <h2>为什么要有这条判据</h2>
 *
 * <p>这个类的样板代码是手写的（上游 Rust 的结构体搬过来），加字段时很容易漏掉其中一两处，
 * 而且<b>漏了不会报错、只会静默失效</b>。这一轮里同一个坑踩了<b>两次</b>：
 *
 * <ol>
 *   <li>P2 加 {@code spaceWidth} 时漏了<b>拷贝构造器</b>——旋钮接上去等于没接，
 *       输出逐位相同（当时是靠「量相关性从 −0.003 变成 −0.003」才发现的）；</li>
 *   <li>P4/P5 加 {@code levelMatch} / {@code lowDuck} 时漏了 {@code equals}、
 *       {@code hashCode} 和 {@code toLine}——{@code toLine()} 的承诺是「贴回去就能复现」，
 *       漏掉就等于把旋钮悄悄扔掉，而既有的往返判据只改老字段，照不到。</li>
 * </ol>
 *
 * <p>与其每次都靠眼睛，不如让<b>反射</b>来遍历：翻动每一个可调字段，看它是否真的
 * 「翻得动」——被 {@code equals} 看见、被 {@code toLine} 带出去、再被 {@code parse}
 * 带回来。以后再加字段，漏一处这里就红一处。
 */
public class MixTuningCompletenessTest {

    /** 一个默认旋钮。 */
    private static MixTuning base() {
        return MixTuning.defaults();
    }

    /** 所有可调字段（公开、非静态、非 final）。 */
    private static List<Field> tunableFields() {
        List<Field> out = new ArrayList<Field>();
        for (Field f : MixTuning.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            if (t == float.class || t == double.class || t == int.class) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 把某个字段翻成「另一个合法值」（逐个候选试到 {@code parse} 接受为止）。
     *
     * <p>候选里第一位是<b>当前值的一半</b>：有些字段带「必须大于另一个字段」的约束
     * （{@code hp_to > hp_from}、{@code lp_to > lp_from}），固定的几个小数字全都不合法
     * ——实测这两个字段就是这么变成「验证不到的盲点」的（判据自己报了盲点，没有假装通过）。
     */
    private static MixTuning flipped(Field field, Object value) throws Exception {
        double current = value instanceof Number ? ((Number) value).doubleValue() : 0.0;
        double[] floatCandidates = {current * 0.5, current * 0.8, 1.0, 0.31, 0.5, 2.0, 0.05};
        MixTuning t = base();
        if (field.getType() == float.class) {
            for (double c : floatCandidates) {
                if (Math.abs(c - (Float) value) < 1e-9) {
                    continue;
                }
                field.setFloat(t, (float) c);
                if (roundTrips(t, "width=" + "0")) {     // 只要 parse 不炸就算合法
                    return t;
                }
            }
        } else if (field.getType() == double.class) {
            for (double c : floatCandidates) {
                if (Math.abs(c - (Double) value) < 1e-9) {
                    continue;
                }
                field.setDouble(t, c);
                if (roundTrips(t, "width=0")) {
                    return t;
                }
            }
        } else {
            int[] intCandidates = {((Integer) value) + 1, Math.max(1, ((Integer) value) - 1)};
            for (int c : intCandidates) {
                if (c == (Integer) value) {
                    continue;
                }
                field.setInt(t, c);
                if (roundTrips(t, "width=0")) {
                    return t;
                }
            }
        }
        return null;
    }

    /** 把这一份旋钮写成行、再解析回来——顺带验证写出来的行本身是合法的。 */
    private static boolean roundTrips(MixTuning t, String extra) {
        try {
            MixTuning back = MixTuning.parse(t.toLine() + "," + extra);
            return back != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * <b>每个可调字段都必须「翻得动」</b>：翻过之后 {@code equals} 要能区分，
     * 而且翻转后的值必须能活着穿过 {@code toLine} → {@code parse}。
     */
    @Test
    public void everyTunableFieldIsDistinguishedAndRoundTrips() throws Exception {
        List<Field> fields = tunableFields();
        // 25 → 31：Chooser 重写时把六个素材门槛从常量搬进了 MixTuning
        // （低频冲突/drop 显著度/可叠秒数/学习EQ 最短窗口/A 尾斜率/B 起跳门槛）。
        // 这条计数是**有意的**：加旋钮就得来改它，改的时候顺手想一遍
        // "这个旋钮真的需要被用户拧吗"。
        // 31 → 34：滤波扫的三个旋钮（sweep_from / sweep_to / sweep_q ✓，2026-10-07
        // 「让名不副实的档名副其实」：FILTER 那一族以前跑的是固定分频的 bassSwap ✗）。
        // 34 → 38：混响的四个旋钮（reverb_send / reverb_wet / reverb_decay / reverb_damping ✓）
        // —— 名字带 REVERB 的五档以前**一个混响实现都没有** ✗。
        // 38 → 42：跟拍延迟的四个旋钮（delay_send / delay_wet / delay_beats / delay_feedback ✓）
        // —— 名字带 DELAY 的三档以前跑的是 tailRide / learnedEq ✗。
        // 42 → 43：长出短进的 short_in（进场侧提前到窗口的百分之几就满 ✓）——
        // 名字带 LONG_OUT_SHORT_IN 的几档以前是**等功率对称**淡化 ✗。
        // 43 → 44：确认重复的 loop_cost（"直接过渡效果不好"的门 ✓，用户 2026-10-07 拍板：
        // 「融合窗口低于 2 s **且**直接过渡效果不好才算真缺时间」✓）。
        assertEquals("可调字段数（加旋钮时这个数会变，是有意的）", 44, fields.size());

        List<String> blind = new ArrayList<String>();
        for (Field f : fields) {
            Object before = f.get(base());
            MixTuning changed = flipped(f, before);
            if (changed == null) {
                blind.add(f.getName() + "（没有找到合法的「另一个值」，判据没法验证它）");
                continue;
            }
            if (changed.equals(base())) {
                blind.add(f.getName() + "：改了值但 equals 认为相等（这个字段对 equals 是隐形的）");
                continue;
            }
            Object after = f.get(changed);
            MixTuning back = MixTuning.parse(changed.toLine());
            Object restored = f.get(back);
            if (f.getType() == float.class) {
                assertEquals(f.getName() + " 没能穿过 toLine → parse 往返",
                        (Float) after, (Float) restored, 1e-6f);
            } else if (f.getType() == double.class) {
                assertEquals(f.getName() + " 没能穿过 toLine → parse 往返",
                        (Double) after, (Double) restored, 1e-9);
            } else {
                assertEquals(f.getName() + " 没能穿过 toLine → parse 往返", after, restored);
            }
            // 契约那一半：equals 相等 ⇒ hashCode 必须相等（翻了之后通常也就不等了）
            assertFalse(f.getName() + "：翻了值之后 hashCode 竟然还相等（契约没道理要求它不等，"
                    + "但如果真的相等，说明 hashCode 里没含这个字段，值得看一眼）",
                    changed.hashCode() == base().hashCode() && changed.equals(base()));
        }
        assertTrue("这些字段的「翻得动」没被验证到：\n  " + String.join("\n  ", blind),
                blind.isEmpty());
        System.out.println("[tuning] " + fields.size() + " 个可调字段：equals 区分 ✔、"
                + "toLine → parse 往返 ✔");
    }
}
