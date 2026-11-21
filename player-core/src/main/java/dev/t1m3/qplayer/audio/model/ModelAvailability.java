package dev.t1m3.qplayer.audio.model;

import dev.t1m3.qplayer.util.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「哪些模型的权重真的在磁盘上」，做成一个谁都读得到的同步取值
 * （对应 folia 的 {@code src/services/automix/modelAvailability.ts}）。
 *
 * <h2>为什么这件事需要单独一个类</h2>
 *
 * <p>在这个项目里，每一个「这个功能能不能跑」的问题过去都是靠「是不是桌面端」回答的——
 * 那个答案之所以一直是对的，只是因为权重过去是随安装包一起发下去的。一旦权重变成
 * 用户自己按需下载的东西，<b>「是不是桌面端」与「手上有没有权重」就是两个不同的问题，
 * 而前者对最常见的情况是错的</b>。所以这里只回答一个更小的问题，并且只有它能回答：
 * <b>磁盘上有没有权重。</b>
 *
 * <h2>三条必须照搬的规矩</h2>
 *
 * <ol>
 *   <li><b>{@link #canRun} 的答案就是「磁盘上有没有」。</b> 不许把「平台」「宿主装没装
 *       推理底座」「上次有没有失败」混进来——混进来的每一个都会让这个值在某个时刻
 *       说一句它并不知道的话，而这个值会变成界面上的一句承诺。</li>
 *   <li><b>从 {@code false} 起步。</b> 见 {@link #ABSENT} 的注释：起点低，一个回合之后
 *       就纠正上去了；起点高则是页面在一个空目录上承诺「可以做人声分离」。</li>
 *   <li><b>没有「本次会话内放弃」的闩锁。</b> 见 {@link #noteFailed} 的注释。
 *       推理返回空既可能是权重没了、也可能是进程重启掐断了在途请求，两者都会自愈——
 *       所以这里唯一会做的事是<b>重新问磁盘</b>。</li>
 * </ol>
 *
 * <p>它是叶子：不 import 界面的任何东西，也不持有界面状态。谁知道「权重在不在」，
 * 谁就只问这里。
 */
public final class ModelAvailability {

    /**
     * 每次启动、以及第一次检查落地之前的答案：<b>全部 {@code false}</b>。
     *
     * <p>不做成「未知」。这个值会被两个地方读成对用户的一句承诺（设置里那一行的状态、
     * 以及将来任何依赖模型的功能开关），而 {@code false} 只是低估一个回合就自愈，
     * {@code true} 则是在一个空目录上承诺一个跑不起来的功能——后者正是
     * 「一个手势整天一次都没触发」那类故障的来源。
     */
    private static final Map<String, Boolean> ABSENT = allFalse();

    private static volatile Map<String, Boolean> present = ABSENT;

    private ModelAvailability() {
    }

    private static Map<String, Boolean> allFalse() {
        Map<String, Boolean> m = new LinkedHashMap<String, Boolean>();
        try {
            for (ModelManifestEntry e : ModelManifest.embedded().models()) {
                m.put(e.name, Boolean.FALSE);
            }
        } catch (RuntimeException e) {
            // 清单本身坏了：那时「一个模型都没有」是唯一诚实的答案
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * 这个模型现在能不能跑。<b>它的权重在不在磁盘上，就是问题的全部。</b>
     *
     * <p>这里刻意<b>没有</b>「这个运行时拒绝过，本会话内放弃」的闩锁。那个闩锁只在
     * 「权重随安装包一起发」的年代是安全的：那时文件在、结果却是空的，除了「运行时不
     * 起来」没有别的解释。权重变成按需下载之后，同一个空结果开始因为一个平凡的原因出现
     * ——宿主每次切文件夹、每次下载完成都会重启工作进程，被那一下掐断的在途请求返回的
     * 就是空。事后去分辨这两者只能靠重新 stat 磁盘，而磁盘上的目录指的是<b>现在</b>的位置：
     * 在那一瞬间换过文件夹或重下过一次，一次转瞬即逝的空结果就会把模型永久锁死，
     * 只能重启 App 才能解开。工作进程那边自己有「真正失败过的运行时不再重复初始化」的
     * 每进程保护，那才是这道保护该在的地方，而且没有这个假阳性。
     */
    public static boolean canRun(String name) {
        Map<String, Boolean> snapshot = present;
        Boolean v = name == null ? null : snapshot.get(name);
        return v != null && v.booleanValue();
    }

    /** 当前全部答案（不可变）。 */
    public static Map<String, Boolean> snapshot() {
        return present;
    }

    /**
     * 重新问一次磁盘，返回新答案。
     *
     * <p>同步且<b>廉价</b>：每个条目一次 {@code stat}（顺带比大小，见
     * {@link ModelDownloads#present}），不读文件内容。所以它可以随便调——
     * 下载完成之后、导入之后、以及每次打开设置页时。
     *
     * <p>它<b>不会</b>抛：清单读不出来时保留上一次的答案。
     * 拿一次读失败去把一套本来能用的构建降级成「什么都没有」，
     * 是把一个暂时性的问题变成一个看起来永久的问题。
     */
    public static Map<String, Boolean> refresh() {
        Map<String, Boolean> next;
        try {
            ModelDownloads downloads = ModelDownloads.shared();
            Map<String, Boolean> m = new LinkedHashMap<String, Boolean>();
            for (ModelManifestEntry e : downloads.manifest().models()) {
                m.put(e.name, Boolean.valueOf(downloads.present(e)));
            }
            next = Collections.unmodifiableMap(m);
        } catch (RuntimeException e) {
            // 保留上一次的答案（见上），只记一笔
            Logger.warn("模型可用性：这次问磁盘失败了（{}），沿用上一次的答案", e.toString());
            return present;
        }

        Map<String, Boolean> previous = present;
        if (next.equals(previous)) {
            return previous;
        }
        present = next;
        return next;
    }

    /**
     * 一次推理返回了空之后调它：<b>重新问磁盘，别做别的事</b>。
     *
     * <p>它<b>不</b>会把这个模型在本会话里关掉——理由见 {@link #canRun}。
     * 空结果不是「运行时坏了」的证据：权重不见时会空，工作进程在请求途中被重启时也会空，
     * 而这两种情况都会自愈。所以这里唯一该做的是让「磁盘上有没有」这个答案变新，
     * 并且只在唯一那条可行动的分支上出声——权重真的没了。
     */
    public static void noteFailed(String name) {
        if (refresh().get(name) == Boolean.FALSE) {
            Logger.warn("模型 {} 这次没给出结果，而磁盘上也没有它的权重；"
                    + "它一旦被下回来就会被再次使用（不做会话内闩锁）", name);
        }
    }

    /** 回到「全部 false」的起点。给测试用：这是一个全局静态状态。 */
    public static void reset() {
        present = ABSENT;
    }
}
