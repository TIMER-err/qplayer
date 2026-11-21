package dev.t1m3.qplayer.audio.mix;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>native_routing.py 的端口</b>：判断某个 style_id 是否属于"原生默认生成"的 16 个风格。
 *
 * <p>为什么重要：参考包 {@code plan_transition} 默认 {@code native_default_styles_only=True}，
 * 于是 24 个模板里有 **8 个（catalogue-only：2/6/7/10/11/13/14/22）直接被淘汰**
 * ——不 port 这条，我们会在那些模板上算出候选，选点自然对不上。
 */
public final class ReferenceRouter {

    private ReferenceRouter() {
    }

    /** 目录里的全部 24 个 style_id（CATALOG_STYLE_IDS）。 */
    static final int[] CATALOG_STYLE_IDS = {0, 1, 2, 3, 33, 4, 44, 5, 6, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16, 17, 18, 19, 20, 22};

    /** 只出现在目录里、默认不生成的那 8 个（CATALOG_ONLY_STYLE_IDS）。 */
    static final int[] CATALOG_ONLY_STYLE_IDS = {2, 6, 7, 10, 11, 13, 14, 22};

    /** 风格 → 家族（FAMILY_SPECS 的 style_ids 展开）。 */
    private static final Map<Integer, String> FAMILY = new HashMap<Integer, String>();

    static {
        int[] dance = {17, 19};
        int[] hiphop = {15, 18};
        int[] pop = {16, 20};
        int[] filtered = {8, 9, 12};
        int[] smart = {3, 33, 4, 44};
        int[] deadAir = {0};
        int[] softSkip = {5};
        int[] fallback = {1};
        put("dance", dance);
        put("hiphop", hiphop);
        put("pop", pop);
        put("filtered", filtered);
        put("smart", smart);
        put("dead-air", deadAir);
        put("soft-skip", softSkip);
        put("fallback-crossfade", fallback);
    }

    private static void put(String family, int[] ids) {
        for (int i = 0; i < ids.length; i++) {
            FAMILY.put(Integer.valueOf(ids[i]), family);
        }
    }

    static boolean catalogueMember(int styleId) {
        for (int i = 0; i < CATALOG_STYLE_IDS.length; i++) {
            if (CATALOG_STYLE_IDS[i] == styleId) {
                return true;
            }
        }
        return false;
    }

    /** 参考包 {@code default_route(style_id)['default_generated']}。 */
    static boolean defaultGenerated(int styleId) {
        return FAMILY.containsKey(Integer.valueOf(styleId));
    }
}
