package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Text;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarqueeResizeTest {
    private static final String TITLE = "Long song title for resizing the desktop player window";

    @Test
    public void resizeRestartsPauseAndStopsWhenTitleFits() throws Exception {
        try (Scene scene = new Scene()) {
            scene.frames(100);
            assertTrue(scene.offset() < 0);

            scene.view.root().width.set(420);
            scene.frames(30);
            assertEquals(372f, scene.marquee.width.peekFloat(), 0.01f);
            assertEquals(0f, scene.offset(), 0.01f);
            scene.frames(70);
            assertTrue(scene.offset() < 0);

            scene.view.root().width.set(1600);
            scene.frames(100);
            assertFalse(scene.overflowing());
            assertEquals(0f, scene.offset(), 0.01f);

            scene.view.root().width.set(240);
            scene.frames(30);
            assertTrue(scene.overflowing());
            assertEquals(0f, scene.offset(), 0.01f);
            scene.frames(70);
            assertTrue(scene.offset() < 0);
        }
    }

    @Test
    public void changedTextMetricsReplaceTheActiveScrollCycle() throws Exception {
        try (Scene scene = new Scene()) {
            scene.frames(100);
            double previousCycle = scene.property("cycleWidth").peekDouble();
            scene.property("fontSize").set(30);
            scene.frames(30);
            assertTrue(scene.property("cycleWidth").peekDouble() > previousCycle);
            assertEquals(0f, scene.offset(), 0.01f);
            scene.frames(70);
            float start = scene.offset();
            scene.frames(50);
            assertEquals(-32f * 0.8f, scene.offset() - start, 0.1f);

            scene.property("text").set("Short title");
            scene.frames(30);
            assertFalse(scene.overflowing());
            assertEquals(0f, scene.offset(), 0.01f);
        }
    }

    private static final class Scene implements AutoCloseable {
        final QmlView view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResourceLoader());
        final Item marquee;
        final Text scrollingText;
        long clock = 1_000_000_000L;

        Scene() {
            view.load("import QtQuick\nimport \"components\"\n"
                    + "Item { width: 300; height: 120\n"
                    + "MarqueeText { objectName: \"marquee\"; fontSize: 20;"
                    + " anchors.left: parent.left; anchors.right: parent.right; anchors.margins: 24;"
                    + " text: \"" + TITLE + "\" } }");
            marquee = view.findByObjectName("marquee");
            frames(1);
            scrollingText = findScrollingText(marquee);
        }

        void frames(int count) {
            DirtyQueue queue = view.dirtyQueue();
            queue.install();
            try {
                for (int i = 0; i < count; i++) {
                    clock += 16_000_000L;
                    view.tickAnimations(clock);
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                    queue.flush();
                }
            } finally {
                queue.uninstall();
            }
        }

        @SuppressWarnings("unchecked")
        Property<Object> property(String name) throws Exception {
            return (Property<Object>) marquee.getClass().getField(name).get(marquee);
        }

        boolean overflowing() throws Exception {
            return Boolean.TRUE.equals(property("overflowing").peek());
        }

        float offset() { return scrollingText.x.peekFloat(); }

        private static Text findScrollingText(Item parent) {
            for (Item child : parent.children) {
                if (child instanceof Text && Boolean.TRUE.equals(child.visible.peek())
                        && ((Text) child).text.peek().contains(TITLE + "      " + TITLE)) {
                    return (Text) child;
                }
                Text nested = findScrollingText(child);
                if (nested != null) return nested;
            }
            return null;
        }

        @Override
        public void close() { view.dispose(); }
    }
}
