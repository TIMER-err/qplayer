package dev.t1m3.qplayer.audio.mix;

/**
 * <b>有界的离线变速（WSOLA）</b>：把一段素材在<b>保持音高</b>的前提下拉长或缩短到指定帧数。
 *
 * <p>为什么要它（2026-10-03）：参考对 128 vs 120 BPM 差 6.7%，7.5 秒的融合窗里两首歌的鼓点
 * 会滑开约 <b>半秒</b>——而"用户听不出换歌"这条要求里，鼓点错位是最直接的破绽。
 * 项目原先的方针是"不做时间拉伸"（{@code docs/automix-overview.md} §6.4），它成立的前提是
 * <b>窗口短到一两拍</b>；而用户听过之后选的是<b>长窗口</b>，那条前提已经不成立。
 *
 * <h3>实现要点</h3>
 *
 * <ul>
 *   <li><b>保调</b>：纯 OLA/WSOLA——只做波形层面的搬移与交叠相加，<b>不重采样</b>，
 *       所以音高不变（判据用基频钉着）。</li>
 *   <li><b>误差不累积</b>：每一块的搜索位置<b>锚定在目标时钟</b> {@code pos × speed} 上，
 *       而不是"接着上一块"，所以不会越跑越偏。</li>
 *   <li><b>立体声共用一套对齐</b>：相似度把各声道合起来算，避免左右声道各自找位置而错开。</li>
 *   <li><b>有界</b>：速度限定在 {@code 0.92~1.08}，素材不够或速度越界一律
 *       {@link IllegalArgumentException}——<b>调用方必须降级为"不变速"</b>，不能让它把一次过渡弄崩。</li>
 *   <li><b>关掉时逐位相同</b>：{@code speed == 1} 走精确拷贝的快路径。</li>
 * </ul>
 *
 * <p>来源：另一个 agent 在 {@code gpt-natural-mix} 分支上的实现
 * （{@code TempoBridge}），此处原样移植，只补文档。
 */
public final class TempoBridge {

    /**
     * <b>变速比的下界</b>（2026-10-06 第一阶段工作二：从 0.92 放开到 {@value}）。
     *
     * <p>用户原话：「做这个就不要考虑任何复杂度，以完美无感为最高标准，**该变速就变速**，
     * 该咋滴就咋滴，不要受到神秘条款制约。」原来的 0.92~1.08 是"保守"而不是"技术上不可能"，
     * 所以放开到 0.75~1.33（±33%，差不多是半音阶式的对拍空间）。
     *
     * <p>这里是**唯一的定义处**：原来这两个数在 {@code TempoBridge} 里以 {@code .92} /
     * {@code 1.08} 的字面量散了四处，改一处漏三处必然分叉——现在都引用这里。
     */
    public static final double MIN_SPEED = 0.75;

    /** 变速比的上界：见 {@link #MIN_SPEED}。 */
    public static final double MAX_SPEED = 1.33;
    private TempoBridge() { }

    public static float[][] stretch(float[][] input, int rate, int frames, double speed) {
        return stretchCore(input, rate, frames, speed, null);
    }

    /**
     * <b>时间渐变的同步长变速</b>：速率在整段输出里从 {@code startSpeed} <b>线性</b>变到
     * {@code endSpeed}（两者都在 0.92~1.08 内），音高不变（还是纯 WSOLA，不重采样）。
     *
     * <p>为什么不能"分成几段各用 {@link #stretch} 拼起来"（2026-10-06 实测的取舍）：
     * {@code stretch} 每一遍都会把开头 {@code grain}（40 ms）<b>逐位拷贝</b>进输出，
     * 且它内部的相似度搜索只锚在"这一遍自己的时间尺"上——首尾相接的两段之间因此必然留下
     * <b>一个没有交叠的硬接缝</b>（听感是咔哒、记账上还会多/少几帧）。要它连续，就得让
     * <b>每一块的目标位置来自同一条积分曲线</b>，也就是这里做的：一次 OLA 走完，
     * 没有段、没有缝。
     *
     * <h3>速率算术（这是这个方法的核心，改了它等于改了整条曲线的含义）</h3>
     *
     * <p>{@code speed} 的定义沿用 {@link #stretch}：<b>输出每推进 1 帧、源素材推进多少帧</b>
     * （{@code <1} = 把出场曲放慢）。于是第 {@code pos} 帧对应的源位置是
     * <b>速率的积分</b>，而不是 {@code pos × speed}：
     *
     * <pre>
     *   speed(u) = s0 + (s1 − s0) · u / frames          （线性斜坡）
     *   src(pos) = ∫₀^pos speed(u) du
     *            = s0 · pos + (s1 − s0) · pos² / (2 · frames)
     *   消耗的源素材总长 = src(frames) = frames · (s0 + s1) / 2
     * </pre>
     *
     * <p>每块（{@code hop} 帧）的搜索中心就是这个 {@code src(pos)} 取整，所以
     * <b>误差不累积</b>（与恒定那一版的"锚在目标时钟上"是同一条纪律），
     * 且 {@code pos = 0} 处 {@code src = 0}、{@code speed = s0}——只要
     * {@code s0 = 1}，这段素材与它前面的自然播放<b>在内容与速率上同时接得上</b>，
     * 这正是"渐变"相对"阶跃"的全部意义。
     *
     * @param startSpeed 窗口起点处的速率（通常 1.0 = 与自然播放同速）
     * @param endSpeed   窗口终点处的速率（单调下降时 {@code endSpeed ≤ startSpeed}）
     */
    public static float[][] stretchRamp(float[][] input, int rate, int frames,
                                        double startSpeed, double endSpeed) {
        if (!Double.isFinite(startSpeed) || !Double.isFinite(endSpeed)
                || startSpeed < MIN_SPEED || startSpeed > MAX_SPEED
                || endSpeed < MIN_SPEED || endSpeed > MAX_SPEED)
            throw new IllegalArgumentException("Unsupported tempo ramp");
        return stretchCore(input, rate, frames, startSpeed,
                new double[]{startSpeed, endSpeed});
    }

    /**
     * <b>前 f 段收敛、之后恒定</b>的变速（2026-10-06 第一阶段工作三的规格曲线）。
     *
     * <p>用户原话：「变速曲线：**前 25% 双方靠拢，中间 50% 保持，后 25% 不划回**。
     * 因为 B 的 Deck 本来就是原速，过渡结束后 B 按原速播，不需要划回。」
     * 也就是速率从 {@code startSpeed} 在窗口的前 {@code convergeFrac} 内走到
     * {@code endSpeed}，随后<b>一直保持</b>——与 {@link #stretchRamp}（整窗线性）的区别
     * 只在"收敛得多快"，两者的源位置都是速率曲线的积分，所以段与段之间没有缝。
     */
    public static float[][] stretchConverge(float[][] input, int rate, int frames,
                                            double startSpeed, double endSpeed,
                                            double convergeFrac) {
        if (!Double.isFinite(startSpeed) || !Double.isFinite(endSpeed)
                || startSpeed < MIN_SPEED || startSpeed > MAX_SPEED
                || endSpeed < MIN_SPEED || endSpeed > MAX_SPEED)
            throw new IllegalArgumentException("Unsupported tempo converge");
        double f = Math.min(1.0, Math.max(1e-3, convergeFrac));
        return stretchCore(input, rate, frames, startSpeed,
                new double[]{startSpeed, endSpeed, f});
    }

    /**
     * {@link #stretchConverge} 需要的源素材帧数。
     *
     * <p><b>必须与 {@code stretchCore} 里那条素材量判据用同一个（更保守的）式子</b>：
     * 它按**整窗线性**平均判（{@code frames × (s0+s1)/2}），而收敛曲线真正消耗的更少
     * （后半段停在 s1 上）。早先按真实消耗给，结果读进来的素材比它要求少了几千帧，
     * 直接抛 {@code Incomplete tempo material}（实测踩到，记在这里）。
     * 多读一点没有副作用——那点素材本来就落在窗口之后。
     */
    public static int requiredFramesConverge(int frames, int rate, double startSpeed,
                                             double endSpeed, double convergeFrac) {
        return requiredFramesRamp(frames, rate, startSpeed, endSpeed);
    }

    /** 渐变变速需要的源素材帧数（= 速率积分的上取整 + 一块的余量）。 */
    public static int requiredFramesRamp(int frames, int rate, double startSpeed, double endSpeed) {
        return (int) Math.ceil(frames * (startSpeed + endSpeed) / 2.0)
                + (int) Math.ceil(rate * .044) + 4;
    }

    /** @param ramp {@code null} = 恒定速率；否则 {@code {起点速率, 终点速率}} */
    private static float[][] stretchCore(float[][] input, int rate, int frames, double speed,
                                         double[] ramp) {
        double consumed = ramp == null ? speed
                : (ramp[0] + ramp[1]) / 2.0;          // 线性斜坡：平均速率就是两端均值
        if(input==null || input.length==0 || frames<=0 || rate<=0
                || !Double.isFinite(consumed) || consumed<MIN_SPEED || consumed>MAX_SPEED)
            throw new IllegalArgumentException("Unsupported tempo bridge");
        int grain=Math.max(32,(int)Math.round(rate*.04));
        int hop=grain/2, search=Math.max(4,(int)Math.round(rate*.004));
        int available=input[0].length;
        for(float[] ch:input) available=Math.min(available,ch.length);
        // 素材量：恒定那一档仍按 `frames × speed` 判（逐位不变）；渐变那一档按**速率积分**
        // 判（`frames × (s0+s1)/2`），也就是它真正会读到的帧数——多读一段会让"读得出来
        // 却不够"这种失败从准备期漏到渲染期。
        long need = ramp == null
                ? (long) Math.ceil(frames * speed)
                : (long) Math.ceil(frames * (ramp[0] + ramp[1]) / 2.0);
        if(available<(Math.abs(speed-1)<1e-6 && ramp==null ? frames : need+grain+search))
            throw new IllegalArgumentException("Incomplete tempo material");
        float[][] output=new float[input.length][frames];
        for(int c=0;c<input.length;c++) System.arraycopy(input[c],0,output[c],0,Math.min(grain,frames));
        if(ramp==null && Math.abs(speed-1)<1e-6) {
            for(int c=0;c<input.length;c++)System.arraycopy(input[c],0,output[c],0,frames);
            return output;
        }
        // ---- 两处只改吞吐、不改数值的优化（2026-10-03，逐位不变）------------------
        //
        // ① **窗表**：重叠相加那一步原本对每个采样都调一次 `Math.cos`（7.5 秒素材上是
        //    36 万次），而它只取决于 `hop`。表里装的还是同一批 `Math.cos` 的结果，
        //    取出来的值和现算的逐位相同。
        // ② **四条 lag 交错推进**：每个 lag 的三个内积都是串行依赖链，一条跑完再跑下一条时
        //    CPU 大半时间在等浮点延迟；把 4 条放进同一趟 `i/c` 循环里，每条链的求和顺序
        //    一个字都没变（还是 i 步 4、c 升序），只是流水线被填满了。
        //    顺带 `xx = Σ x²` 与 lag 无关（x 只取 output 那一侧），原来每个 lag 各算一遍
        //    ——同一个和、同一个顺序，现在只算一遍。
        final double[] win = new double[Math.max(1, hop)];
        for (int i = 0; i < hop; i++) {
            win[i] = 0.5 - 0.5 * Math.cos(Math.PI * i / hop);
        }
        final int chans = input.length;
        for(int pos=hop;pos<frames;pos+=hop) {
            // 目标源位置：恒定那一档是 `pos × speed`（逐位不变）；渐变那一档是**速率的积分**
            // `s0·pos + (s1−s0)·pos²/(2·frames)`（见 stretchRamp 的算术）。
            // 两者都只在每个 hop 上算一次，搜索仍以它为中心。
            // 三种曲线（都是同一件事的积分：输出位置 → 源位置）：
            //   ramp == null        恒定速率 `pos × speed`（逐位不变）
            //   ramp.length == 2    整窗线性斜坡：`s0·pos + (s1−s0)·pos²/(2·frames)`
            //   ramp.length == 3    **前 f 段收敛、之后恒定**（2026-10-06 第一阶段工作三的规格：
            //                       「前 25% 双方靠拢，中间 50% 保持，后 25% 不划回」）：
            //                       u ≤ f·frames 时按 `s0 + (s1−s0)·u/(f·frames)` 积分，
            //                       u > f·frames 之后按 s1 恒定（**不划回**）。
            int expected;
            if (ramp == null) {
                expected = (int)Math.round(pos*speed);
            } else if (ramp.length == 2) {
                expected = (int)Math.round(ramp[0]*pos
                        + (ramp[1]-ramp[0])*(double)pos*(double)pos/(2.0*frames));
            } else {
                double f = Math.max(1e-3, ramp[2]);
                double turn = f*frames;                       // 收敛完成的那一帧
                double u = Math.min(pos, turn);
                double acc = ramp[0]*u + (ramp[1]-ramp[0])*u*u/(2.0*turn);
                if (pos > turn) {
                    acc += ramp[1]*(pos-turn);                // 之后恒定，不再划回
                }
                expected = (int)Math.round(acc);
            }
            int low=Math.max(0,expected-search), high=Math.min(available-grain,expected+search);
            int overlap=Math.min(hop,frames-pos), best=expected;
            double bestScore=-Double.MAX_VALUE;
            // Similarity never accumulates timing error: search is anchored to the target clock.
            int at=low;
            for(;at+12<=high;at+=16) {
                double xx=0;
                double xy0=0,yy0=0,xy1=0,yy1=0,xy2=0,yy2=0,xy3=0,yy3=0;
                for(int i=0;i<overlap;i+=4) for(int c=0;c<chans;c++) {
                    float[] oc=output[c];
                    float[] ic=input[c];
                    double x=oc[pos+i];
                    double y0=ic[at+i],y1=ic[at+4+i],y2=ic[at+8+i],y3=ic[at+12+i];
                    xx+=x*x;
                    xy0+=x*y0;yy0+=y0*y0;
                    xy1+=x*y1;yy1+=y1*y1;
                    xy2+=x*y2;yy2+=y2*y2;
                    xy3+=x*y3;yy3+=y3*y3;
                }
                double s0=xy0/Math.sqrt(Math.max(1e-20,xx*yy0))-.005*Math.abs(at-expected)/Math.max(1,search);
                if(s0>bestScore){bestScore=s0;best=at;}
                double s1=xy1/Math.sqrt(Math.max(1e-20,xx*yy1))-.005*Math.abs(at+4-expected)/Math.max(1,search);
                if(s1>bestScore){bestScore=s1;best=at+4;}
                double s2=xy2/Math.sqrt(Math.max(1e-20,xx*yy2))-.005*Math.abs(at+8-expected)/Math.max(1,search);
                if(s2>bestScore){bestScore=s2;best=at+8;}
                double s3=xy3/Math.sqrt(Math.max(1e-20,xx*yy3))-.005*Math.abs(at+12-expected)/Math.max(1,search);
                if(s3>bestScore){bestScore=s3;best=at+12;}
            }
            for(;at<=high;at+=4) {
                double xy=0,xx=0,yy=0;
                for(int i=0;i<overlap;i+=4) for(int c=0;c<chans;c++) {
                    double x=output[c][pos+i],y=input[c][at+i];
                    xy+=x*y;xx+=x*x;yy+=y*y;
                }
                double score=xy/Math.sqrt(Math.max(1e-20,xx*yy))-.005*Math.abs(at-expected)/Math.max(1,search);
                if(score>bestScore){bestScore=score;best=at;}
            }
            for(int i=0;i<grain && pos+i<frames;i++) {
                double w=i<hop?win[i]:1;
                for(int c=0;c<chans;c++)
                    output[c][pos+i]=(float)(output[c][pos+i]*(1-w)+input[c][best+i]*w);
            }
        }
        return output;
    }

    public static int requiredFrames(int frames,int rate,double speed) {
        return (int)Math.ceil(frames*speed)+ (int)Math.ceil(rate*.044)+4;
    }
}
