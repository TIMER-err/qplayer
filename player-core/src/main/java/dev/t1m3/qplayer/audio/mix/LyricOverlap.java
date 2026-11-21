package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>歌词内容级锚点：从重复的词组里钻进去</b>（2026-10-06 用户提的玩法 + 三条修正）。
 *
 * <p>用户原话：「我直接看歌词数据有没有歌词内容重叠区域，我直接先对拍然后直接从重复的地方
 * 钻进去岂不是完美。」随后的三条修正：
 * <ol>
 *   <li>「歌词不一定一整行都一样，**内部区域一样就行**，我们穿插的是内部区域，全行一样肯定更好」
 *       ⇒ 比的是**相邻词组成的连续段**，不是整行；</li>
 *   <li>「**只有英文参与这个**，中文这些参与的话会显得很诡异」⇒ 中文行直接跳过；</li>
 *   <li>「英文要求**至少两个相邻单词相等**才触发」⇒ {@value #MIN_RUN_WORDS} 个词的硬门槛；</li>
 *   <li>「触发后切换逻辑差不多就是**前半是 A 的后半换成 B 的**了，类比一下，两个单词的话
 *       就是第一个单词 A 念第二个单词 B 念」⇒ 交接点落在重复段的**中缝**上。</li>
 * </ol>
 *
 * <p>于是这个类返回的不是"整行对齐"，而是**一个词级的交接点**：
 * A 唱重复段的前半、B 唱后半，那一缝就是过渡的交接处。
 *
 * <p><b>词的时间戳哪里来</b>：现有歌词只有**行级**时间戳（{@code LyricSpans.starts/ends}），
 * 所以词的时间按行内**线性插值**（第 k 个词 / 共 n 个词）。这是近似——真实演唱里词长不等，
 * 但交接点只要求两首歌**同一个词**对齐，线性插值对"两句都按同一节奏唱"的重复段足够准；
 * 而且后面还有整拍量化与采样级相位精修兜着。
 */
public final class LyricOverlap {

    /** 触发门槛：至少这么多个**相邻**单词相等（用户要求 2）。 */
    public static final int MIN_RUN_WORDS = 2;

    /** 一对同句里的一句话。 */
    public static final class Match {

        /** A 侧第几行（{@code -1} = 没找到）。 */
        public final int aLine;

        /** B 侧第几行。 */
        public final int bLine;

        /** 重复段在 A 那一行里的**起始词序号**（0 起）。 */
        public final int aRunStart;

        /** 重复段在 B 那一行里的起始词序号。 */
        public final int bRunStart;

        /** 重复段有几个词。 */
        public final int runWords;

        /** A 那一行一共几个词。 */
        public final int aLineWords;

        /** B 那一行一共几个词。 */
        public final int bLineWords;

        /** 交接词在 A 那一行里的序号（= 过词数）。 */
        public final int aHandoverWord;

        /** 交接词在 B 那一行里的序号。 */
        public final int bHandoverWord;

        /** A 的交接时刻（秒，行内插值）。 */
        public final double aHandoverSecs;

        /** 重复段在 A 那一行里的**起点时刻**（秒，行内插值）。 */
        public final double aRunStartSecs;

        /**
         * 重复段在 A 那一行里的**终点时刻**（秒，行内插值）。
         *
         * <p>{@code aRunEndSecs − aRunStartSecs} 就是**这一段有多长**——融合窗口该多长由它决定
         * （见 {@code AutoCue.lyricAnchor}：窗口盖住这一段，交接词在正中，前半 A 为主、后半 B 为主）。
         */
        public final double aRunEndSecs;

        /** B 的交接时刻（秒，行内插值）。 */
        public final double bHandoverSecs;

        Match(int aLine, int bLine, int aRunStart, int bRunStart, int runWords,
              int aLineWords, int bLineWords, int aHandoverWord, int bHandoverWord,
              double aHandoverSecs, double bHandoverSecs,
              double aRunStartSecs, double aRunEndSecs) {
            this.aLine = aLine;
            this.bLine = bLine;
            this.aRunStart = aRunStart;
            this.bRunStart = bRunStart;
            this.runWords = runWords;
            this.aLineWords = aLineWords;
            this.bLineWords = bLineWords;
            this.aHandoverWord = aHandoverWord;
            this.bHandoverWord = bHandoverWord;
            this.aHandoverSecs = aHandoverSecs;
            this.bHandoverSecs = bHandoverSecs;
            this.aRunStartSecs = aRunStartSecs;
            this.aRunEndSecs = aRunEndSecs;
        }

        /** 够不够触发（至少 {@value LyricOverlap#MIN_RUN_WORDS} 个相邻词相等）。 */
        public boolean strong() {
            return aLine >= 0 && runWords >= MIN_RUN_WORDS;
        }

        /**
         * 对齐偏移（秒）：{@code tA − tB}，交接点上的。
         *
         * <p>定进场位置时**符号不能弄错**（判据抓过一次）：A 从 cut 起播、B 从 entry 起播，
         * 要让同一个词同时在两边唱出来是 {@code tA − cut = tB − entry}，于是
         * <pre>
         *   entry = cut + (tB − tA) = cut − offsetSecs
         * </pre>
         */
        public double offsetSecs() {
            return aHandoverSecs - bHandoverSecs;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "重复段 A#%d:%d+%d ↔ B#%d:%d+%d · 交接在第 %d/%d 与 %d/%d 词"
                            + "（%.3f s / %.3f s、偏移 %+.3f s）",
                    Integer.valueOf(aLine), Integer.valueOf(aRunStart), Integer.valueOf(runWords),
                    Integer.valueOf(bLine), Integer.valueOf(bRunStart), Integer.valueOf(runWords),
                    Integer.valueOf(aHandoverWord), Integer.valueOf(aLineWords),
                    Integer.valueOf(bHandoverWord), Integer.valueOf(bLineWords),
                    Double.valueOf(aHandoverSecs), Double.valueOf(bHandoverSecs),
                    Double.valueOf(offsetSecs()));
        }
    }

    private static final Match NONE = new Match(-1, -1, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0);

    /**
     * 在 A 的 {@code [aFrom, aTo]} 与 B 的 {@code [bFrom, bTo]} 两个时间窗里找**最长的相邻重复词组**。
     *
     * <p>候选的判定是**与窗口相交**（不是"起点落在窗口内"）：重复的那一句常常起点在窗口之前
     * ——出场点落在句子中间是常态。任一侧没有字、或窗口里没有相交的英文行，返回"没找到"。
     */
    public static Match best(AutoCue.LyricSpans a, double aFrom, double aTo,
                             AutoCue.LyricSpans b, double bFrom, double bTo) {
        if (a == null || b == null || !a.hasTexts() || !b.hasTexts()) {
            return NONE;
        }
        Match best = NONE;
        for (int i = 0; i < a.starts.length; i++) {
            double ta = a.starts[i];
            if (ta > aTo) {
                continue;
            }
            // **只要与窗口相交就算候选**（判据抓过一次）：重复的那一句往往**起点在窗口之前**
            // ——比如出场点 181.4 落在 181.0 那一句的中间，按"起点在窗口内"筛就把这一整行排除了，
            // 于是永远找不到锚点。音乐上要的正是"这一句与窗口有交集"。
            double aEnd = a.ends != null && i < a.ends.length && a.ends[i] > ta
                    ? a.ends[i] : ta;                       // 行末不可知时按"整行同时"处理
            if (aEnd < aFrom) {
                continue;
            }
            List<String> wa = englishWords(a.textAt(i));
            if (wa.size() < MIN_RUN_WORDS) {
                continue;
            }
            for (int j = 0; j < b.starts.length; j++) {
                double tb = b.starts[j];
                if (tb > bTo) {
                    continue;
                }
                double bEnd = b.ends != null && j < b.ends.length && b.ends[j] > tb
                        ? b.ends[j] : tb;
                if (bEnd < bFrom) {
                    continue;
                }
                List<String> wb = englishWords(b.textAt(j));
                if (wb.size() < MIN_RUN_WORDS) {
                    continue;
                }
                int[] run = longestCommonRun(wa, wb);
                int words = run[2];
                if (words < MIN_RUN_WORDS) {
                    continue;
                }
                // 交接词 = 重复段起点 + 过词数（前半 A 唱、后半 B 唱；两个词时就是第 2 个词起由 B 唱）
                int aHand = run[0] + (words + 1) / 2;
                int bHand = run[1] + (words + 1) / 2;
                double aSecs = wordTimeSecs(ta, aEnd, wa.size(), aHand);
                double bSecs = wordTimeSecs(tb, bEnd, wb.size(), bHand);
                double aRunFrom = wordTimeSecs(ta, aEnd, wa.size(), run[0]);
                double aRunTo = wordTimeSecs(ta, aEnd, wa.size(), run[0] + words);
                boolean better = words > best.runWords
                        || (words == best.runWords && best.aLine < 0);
                if (better) {
                    best = new Match(i, j, run[0], run[1], words, wa.size(), wb.size(),
                            aHand, bHand, aSecs, bSecs, aRunFrom, aRunTo);
                }
            }
        }
        return best;
    }

    /** 第 {@code word} 个词（0 起）在行内的时间：线性插值。 */
    static double wordTimeSecs(double lineStart, double lineEnd, int words, int word) {
        if (words <= 0) {
            return lineStart;
        }
        double span = Math.max(0.0, lineEnd - lineStart);
        return lineStart + span * (word / (double) words);
    }

    /**
     * <b>只取英文</b>：把一行切成词（小写、去标点）。
     *
     * <p>用户要求：「只有英文参与这个，中文这些参与的话会显得很诡异。」
     * 所以含中日韩字符的行直接返回空 ⇒ 这一行不参与匹配。
     */
    static List<String> englishWords(String line) {
        List<String> out = new ArrayList<String>();
        if (line == null) {
            return out;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (isCjk(c)) {
                return new ArrayList<String>();          // 中文/日文/韩文：整行不参与
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isLetterOrDigit(c) || c == 0x27) {   // 0x27 = 撇号（don't）
                sb.append(Character.toLowerCase(c));
            } else {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    /**
     * 两个词序列的**最长连续公共段**。
     *
     * @return {@code {A 里的起点, B 里的起点, 长度}}；没有共同词时长度为 0
     */
    static int[] longestCommonRun(List<String> a, List<String> b) {
        int bestLen = 0;
        int bestA = 0;
        int bestB = 0;
        int[] prev = new int[b.size() + 1];
        int[] cur = new int[b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > bestLen) {
                        bestLen = cur[j];
                        bestA = i - cur[j];
                        bestB = j - cur[j];
                    }
                } else {
                    cur[j] = 0;
                }
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
            java.util.Arrays.fill(cur, 0);
        }
        return new int[] {bestA, bestB, bestLen};
    }

    private LyricOverlap() {
    }
}
