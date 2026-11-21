package dev.t1m3.qplayer.audio.mix;

/**
 * 过渡引擎工厂：<strong>反复造出等价的新实例</strong>。
 *
 * <p>为什么需要它，见 {@link Director} 类注释「准备必须异步」那一节：准备要在
 * <strong>另一个实例</strong>上做，做完再换上来，于是渲染路径上不需要任何锁。
 *
 * <p>上游这个类型是 {@code Arc<dyn Fn() -> Box<dyn Transition> + Send + Sync>}
 * （一个类型别名，指向装箱的闭包）。Java 里按约定做成 {@code interface}：
 * <ul>
 *   <li>{@code Arc} 不需要——Java 的对象引用本来就能在多条线程间共享，
 *       判空也由 {@link Director} 的构造器负责；</li>
 *   <li>{@code Send + Sync} 在 Java 的类型系统里表达不了，只能写成约定：
 *       <strong>实现必须线程安全</strong>（准备线程会调用它，而渲染用的实例由
 *       构造器在主线程上造），并且<strong>每次调用都必须返回一个新实例</strong>——
 *       返回同一个实例就等于绕过了「每个实例只服务一次过渡」那条约定，
 *       自动接法挑中的结论会泄漏到下一次过渡上（见
 *       {@code AiMix.prepare} 里对 {@code MixStyle.AUTO} 的改写）。</li>
 * </ul>
 *
 * <p>上游的关联函数名就是 {@code factory()}（调用处写作 {@code factory()}），
 * 这里对应 {@link #create()}。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::director::TransitionFactory}。
 */
public interface TransitionFactory {

    /** 造一个<strong>新的</strong>等价实例（同一个接法、同样的采样率与声道数）。 */
    Transition create();
}
