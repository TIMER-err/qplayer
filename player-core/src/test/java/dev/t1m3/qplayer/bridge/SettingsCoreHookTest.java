package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.settings.SettingsCore;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.assertEquals;

public class SettingsCoreHookTest {
    @Test public void removingActivityHookKeepsTheBackgroundSubscriber() throws Exception {
        SettingsCore settings = new SettingsCore();
        AtomicInteger activity = new AtomicInteger();
        AtomicInteger background = new AtomicInteger();
        Consumer<Object> oldActivity = value -> activity.incrementAndGet();
        settings.onChange("lyricFontSize", oldActivity);
        settings.onChange("lyricFontSize", value -> background.incrementAndGet());
        settings.removeOnChange("lyricFontSize", oldActivity);
        Method fire = SettingsCore.class.getDeclaredMethod("fireHooks", String.class, Object.class);
        fire.setAccessible(true);
        fire.invoke(settings, "lyricFontSize", 42);
        assertEquals(0, activity.get());
        assertEquals(1, background.get());
    }
}
