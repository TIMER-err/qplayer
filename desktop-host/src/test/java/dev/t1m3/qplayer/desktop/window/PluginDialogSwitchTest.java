package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.i18n.I18n;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A plugin dialog switch reports its state through the inputs map PluginDialog
 * submits. Reporting the pre-click state made every toggle write the value the
 * user just moved away from (the netease unblock switch could never be turned on).
 */
public class PluginDialogSwitchTest {

    /** Stands in for PlayerController.pluginDialogAction, which QML calls on submit. */
    public static final class Probe {
        private String submitted = "";

        public void record(String value) {
            submitted = value;
        }
    }

    private static final String SCENE =
            "import QtQuick\n"
            + "import \"components\"\n"
            + "Item { id: root; width: 900; height: 700\n"
            + "  function submit(actionId) {\n"
            + "    var inputs = {};\n"
            + "    if (node0.node && node0.node.type === \"switch\")\n"
            + "      inputs[node0.node.id] = node0.switchValue;\n"
            + "    probe.record(actionId + \" \" + JSON.stringify(inputs));\n"
            + "  }\n"
            + "  PluginDialogNode { id: node0; objectName: \"node0\"\n"
            + "    width: 600; x: 20; y: 20; host: root\n"
            + "    node: ({\"type\": \"switch\", \"id\": \"enabled\", \"label\": \"unblock\",\n"
            + "            \"desc\": \"try other sources\", \"checked\": %s})\n"
            + "  }\n"
            + "}";

    @Test
    public void flippingAnOffSwitchSubmitsTrue() {
        assertEquals("enabled {\"enabled\":true}", clickSwitch(false));
    }

    @Test
    public void flippingAnOnSwitchSubmitsFalse() {
        assertEquals("enabled {\"enabled\":false}", clickSwitch(true));
    }

    private static String clickSwitch(boolean initiallyChecked) {
        Probe probe = new Probe();
        QmlView view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResourceLoader())
                .context("i18n", I18n.instance())
                .context("probe", probe);
        try {
            view.load(String.format(SCENE, initiallyChecked));
            settle(view);
            Item toggle = view.findByObjectName("pluginNodeSwitch");
            assertNotNull("the node must render a switch", toggle);
            float[] centre = centreOf(toggle);
            click(view, centre[0], centre[1]);
            settle(view);
            return probe.submitted;
        } finally {
            view.dispose();
        }
    }

    private static float[] centreOf(Item item) {
        float x = 0;
        float y = 0;
        for (Item node = item; node != null; node = node.parent.peek()) {
            x += node.x.peekFloat();
            y += node.y.peekFloat();
        }
        return new float[]{x + item.width.peekFloat() / 2f, y + item.height.peekFloat() / 2f};
    }

    private static void click(QmlView view, float x, float y) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            view.dispatchPointerDown(x, y);
            view.dispatchPointerUp(x, y);
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            view.root().width.set(900);
            view.root().height.set(700);
            for (int i = 0; i < 3; i++) {
                queue.flush();
                view.renderer().layoutOnly(view.root());
            }
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }
}
