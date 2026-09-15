package dev.t1m3.qplayer.desktop.lyric.tempera;

import dev.t1m3.qplayer.bridge.WindowChromeStub;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;

import org.junit.Test;
import org.objectweb.asm.MethodTooLargeException;

import static org.junit.Assert.fail;

/**
 * `Main.qml` 的编译探针：不开窗口、不建 GL 上下文，只把整棵 QML 场景编译一遍。
 *
 * <p>存在的理由：qml4j 会把一个 QML 元素的所有子元素构造、属性赋值、绑定装配全部生成为
 * 该元素那个类的一个 `&lt;init&gt;` 方法，而 JVM 硬性限制单个方法 64KB 字节码。`Main.qml`
 * 是应用外壳，根元素本身就极重，往根上再多挂一个全屏图层就有可能越界——越界时抛的是
 * {@link MethodTooLargeException}，表现是渲染线程在 `APPLICATION_FRAME` 阶段直接崩溃，
 * 而不是一条可读的 QML 错误。
 *
 * <p>所以对 `Main.qml` 的任何改动都应该先跑这个探针，再看运行效果。
 */
public class QmlCompileProbeTest {

    @Test
    public void mainQmlFitsTheJvmMethodLimit() throws Exception {
        ClasspathResourceLoader resources = new ClasspathResourceLoader();
        byte[] bytes = resources.load("Main.qml");
        if (bytes == null) fail("Main.qml 不在 classpath 上（先跑 process-resources）");
        String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        QmlEngine engine = new QmlEngine();
        QmlView view = QmlView.withStockTypes(engine)
                .resources(resources)
                .context("player", new Object())
                .context("settings", new Object())
                .context("hostWindow", new WindowChromeStub());

        Throwable failure = null;
        try {
            // 单参形式与生产路径一致：Load 收的是源码本身，import "…" 由引擎自行解析。
            view.load(source);
        } catch (Throwable t) {
            failure = t;
        }

        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof MethodTooLargeException) {
                fail("Main.qml 的生成初始化方法超过了 JVM 的 64KB 单方法上限（"
                        + t.getMessage() + "）：往根元素上挂的东西太多了");
            }
        }
        // 编译通过就够了；这里用的是占位 context 对象，实例化阶段缺属性是意料之中的。
        System.out.println("QML_COMPILE_PROBE compiled-ok; later-failure=" + failure);
    }
}
