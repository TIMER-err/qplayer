package dev.t1m3.qplayer.audio.model;

/**
 * {@link StemSeparator} 的宿主接缝与工厂。
 *
 * <p>形态与 {@link BeatModels}、{@link dev.t1m3.qplayer.audio.decode.Decoder}、
 * {@link dev.t1m3.qplayer.audio.output.OutputDevices} 一致：player-core 只放接口与
 * "能不能用"的判断，实现由宿主安装。
 *
 * <h2>「能不能用」只看磁盘上有没有那个模型</h2>
 *
 * <p>{@link #available()} 的答案必须来自 {@link ModelStore#available(String)}——
 * <strong>从 false 起步</strong>（这个值会变成界面上的承诺：宁可先低估，也不能对着空目录
 * 说"已启用"）；<strong>不做"本次会话内放弃"的闩锁</strong>（推理返回空既可能是权重不在、
 * 也可能是会话建不起来，而下载落地会自愈，所以只重新问磁盘）。
 *
 * <p>这与 folia 的教训一致：当年把"是不是桌面端"当成"有没有权重"，结果是界面上承诺了
 * 分离、对着空目录跑了整整一天零次。
 */
public final class StemModels {

    /** 清单里分轨模型的名字。 */
    public static final String MODEL_NAME = "htdemucs";

    /** 宿主提供的实现工厂。 */
    public interface Opener {
        /**
         * 打开一个分离器。
         *
         * @return 分离器；模型不在磁盘上或会话建不起来时抛 {@link InferException}
         */
        StemSeparator open();
    }

    private static volatile Opener opener;

    private StemModels() {
    }

    /** 安装宿主的实现（桌面 / 安卓各一份，启动时调用一次）。 */
    public static void install(Opener value) {
        opener = value;
    }

    /** 卸载（测试用；生产路径不需要）。 */
    public static void uninstall() {
        opener = null;
    }

    /** 宿主是否装了实现。 */
    public static boolean isInstalled() {
        return opener != null;
    }

    /**
     * 现在能不能分离——**只看磁盘上有没有那个模型**，不猜、也不做会话闩锁。
     *
     * <p>宿主没装实现时也返回 false：没有实现就没有能力，这与"有权重但实现没装"在
     * 调用方看来是同一件事（都无法分离），所以不该分成两个状态。
     */
    public static boolean available() {
        return opener != null && ModelStore.available(MODEL_NAME);
    }

    /**
     * 打开一个分离器。
     *
     * @throws InferException 没装实现、模型不在磁盘上、或会话建不起来
     */
    public static StemSeparator open() {
        Opener value = opener;
        if (value == null) {
            throw InferException.load(
                    "没有安装分轨模型实现（宿主必须在启动时调用 StemModels.install）");
        }
        return value.open();
    }
}
