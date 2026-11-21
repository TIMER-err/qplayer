package dev.t1m3.qplayer.lyric.tempera;

/**
 * 逐字的「绝对时间」运动求解，1:1 移植自 folia-major {@code tempera/temperaMotion.ts}。
 *
 * <p>每一个值都只由时钟和该字素的常量推导，所以一次 seek 会画出与连续播放逐像素相同的帧。
 * 这里只做数学，不碰任何绘制资源。
 */
public final class TemperaMotion {
    private TemperaMotion() {
    }

    /** 当前字的强调量：一次很小的缩放起伏，不是画出来的底块。 */
    private static final float CURRENT_EMPHASIS = 0.05f;
    /** 唱完之后这次起伏衰减掉要多久。 */
    private static final float EMPHASIS_DECAY = 0.26f;
    private static final float ECHO_ALPHA = 0.5f;
    /**
     * 「显现」（不透明度与运动残影）允许跑的最长窗口。
     *
     * <p>落位窗口本身会被拉伸到本行末尾，好让整块在行结束前仍在缓入；但让淡入与残影
     * 也铺满这么长就完全是另一回事了：字必须在行进途中就可读，拖到开幕之后的残影读起来
     * 是糊，而不是运动。这里就是落位窗口过去自带的上限，于是任何短到早于拉伸的入场，
     * 行为与从前完全一致。
     */
    private static final float MAX_REVEAL_WINDOW = 1.35f;
    /** 唱完之后整块能张开多少字距。刻意很小：这是字距，不是漂移。 */
    private static final float RELEASE_TRACKING = 0.055f;

    /** 求解一个字素的入场所需的全部常量；运行时不再回读排版。 */
    public static final class Input {
        public double startTime;
        public double settleTime;
        /** 该字素停止演唱的时刻，驱动一点当前字的强调。 */
        public double endTime;
        public float enterX;
        public float enterY;
        public float enterRotation;
        public float enterScale;
        public float rotation;
        public String enterStyle;
        /**
         * 唱完之后这次释放达到满幅的时刻。上界是该行自身的时长，所以一个字素会一直
         * 张开到它那一行结束、不更久。
         */
        public double releaseTime;
        /** 该字素相对整块中心的偏移；释放会缩放它来张开字距。 */
        public float trackingX;
        public float trackingY;
    }

    /** 某时刻这个字素的可见性与变换。 */
    public static final class Frame {
        public boolean visible;
        public float alpha;
        public float x;
        public float y;
        public float rotation;
        /** 两个轴分开：好几种入场样式只在单轴张开。 */
        public float scaleX;
        public float scaleY;
        /** 第一层运动残影的偏移与它的不透明度；两者都会在落位时刻前死掉。 */
        public float echoX;
        public float echoY;
        public float echoAlpha;
    }

    /**
     * 求解一个字素的入场，以及唱完之后保住整行的字距释放。
     *
     * @param motion 调参/主题缩放后的量；0 会把字素钉在排版位置
     */
    public static Frame resolve(Input glyph, double time, float motion) {
        float window = Math.max((float) (glyph.settleTime - glyph.startTime), 0.08f);
        float linear = TemperaMotionEasing.clamp01((time - glyph.startTime) / window);
        float travel = 1f - TemperaMotionEasing.easeEnter(linear);
        TemperaEnterStyles.Input enterInput = new TemperaEnterStyles.Input(
                glyph.enterX, glyph.enterY, glyph.enterRotation, glyph.enterScale);
        TemperaEnterStyles.Frame entrance = TemperaEnterStyles.resolve(
                glyph.enterStyle, enterInput, travel, linear);
        // 不透明度与残影跑在各自有上限的窗口上；位置与缩放保持完整拉伸的那个窗口，
        // 这正是把一行长字变成一次连续运动的原因。
        float reveal = TemperaMotionEasing.clamp01(
                (time - glyph.startTime) / Math.min(window, MAX_REVEAL_WINDOW));
        // 不透明度比位置解算得更快，所以字在还在移动时就已经可读。
        float alpha = TemperaMotionEasing.easeInOut(TemperaMotionEasing.clamp01(reveal * 2.4));

        // 当前字强调是一次很小的缩放起伏，而不是画出来的底块：任何画在字后面的东西都会
        // 成为反色滤镜要读的背景，那正是把效果变成一个色块、而不是对画面的反应的原因。
        //
        // 它跟着演唱窗口本身走（起、持、衰），而不是从起点按一个合成长度倒计时。老写法
        // 让每个字素都脉动一次，不管它有没有被唱到，于是每个合并的标点（构造上零长度，
        // 因为解析器的词不覆盖它）都会自己弹一下。
        double sungWindow = glyph.endTime - glyph.startTime;
        float attack = (float) Math.min(0.12, sungWindow * 0.5);
        float emphasis = sungWindow <= 0
                ? 0f
                : TemperaMotionEasing.easeInOut(TemperaMotionEasing.clamp01(
                        (time - glyph.startTime) / Math.max(attack, 0.02f)))
                        * (1f - TemperaMotionEasing.easeInOut(TemperaMotionEasing.clamp01(
                                (time - glyph.endTime) / EMPHASIS_DECAY)));

        // 释放：字素一旦被唱完，整块会慢慢张开字距，而不是冻住。一行早早结束的字否则会在
        // 一个长镜头的余下时间里死着。这是刚性的、由中心向外的一次扩张——没有游荡、没有
        // 漂浮、没有旋转，因为漂移的字会与这个模式赖以成立的确定性排版相矛盾。
        // 斜坡从「唱完」与「落位」的较晚者开始，所以它永远不会和入场打架。
        double releaseStart = Math.max(glyph.endTime, glyph.settleTime);
        float release = TemperaMotionEasing.easeInOut(TemperaMotionEasing.clamp01(
                (time - releaseStart) / Math.max(glyph.releaseTime - releaseStart, 0.001)));
        float spread = release * TemperaMotionEasing.clamp01(motion) * RELEASE_TRACKING;
        float driftX = glyph.trackingX * spread;
        float driftY = glyph.trackingY * spread;

        // 保守的运动量会把入场往静止姿态拉，而不是反过来，所以 glyphMotion: 0
        // 会把每种样式都钉在排版位置上。
        float amount = TemperaMotionEasing.clamp01(motion);
        float swell = emphasis * CURRENT_EMPHASIS * amount;
        Frame out = new Frame();
        out.visible = time >= glyph.startTime;
        out.alpha = alpha;
        out.x = entrance.x * motion + driftX;
        out.y = entrance.y * motion + driftY;
        out.rotation = glyph.rotation + entrance.rotation * motion;
        out.scaleX = entrance.scaleX + (1f - entrance.scaleX) * (1f - amount) + swell;
        out.scaleY = entrance.scaleY + (1f - entrance.scaleY) * (1f - amount) + swell;
        out.echoX = entrance.x * motion;
        out.echoY = entrance.y * motion;
        out.echoAlpha = entrance.echo * (1f - TemperaMotionEasing.easeEnter(reveal))
                * ECHO_ALPHA * amount;
        return out;
    }
}
