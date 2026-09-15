package io.github.timer_err.qml4j.android;

import io.github.timer_err.qml4j.render.items.animation.NumberAnimation;
import io.github.timer_err.qml4j.render.items.animation.Timer;
import io.github.timer_err.qml4j.render.items.core.Image;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidRenderPolicyTest {
    @Test
    public void runningAnimationKeepsIdleSurfaceAlive() {
        Item root = new Item();
        NumberAnimation animation = new NumberAnimation();
        root.resources.add(animation);

        assertFalse(AndroidRenderPolicy.hasActiveQmlWork(root));
        animation.running.set(true);
        assertTrue(AndroidRenderPolicy.hasActiveQmlWork(root));
    }

    @Test
    public void runningTimerKeepsIdleSurfaceAlive() {
        Item root = new Item();
        Timer timer = new Timer();
        root.resources.add(timer);
        timer.running.set(true);

        assertTrue(AndroidRenderPolicy.hasActiveQmlWork(root));
    }

    @Test
    public void visiblePendingImageKeepsFramesFlowingUntilAdopted() {
        Item root = new Item();
        Image image = new Image();
        root.children.add(image);
        image.loadedSource = "https://example.test/cover.jpg";
        image.decodeGen = 3;
        image.adoptedGen = 2;

        assertTrue(AndroidRenderPolicy.hasActiveQmlWork(root));
        image.adoptedGen = 3;
        assertFalse(AndroidRenderPolicy.hasActiveQmlWork(root));
    }

    @Test
    public void invisiblePendingImageDoesNotPreventIdleMode() {
        Item root = new Item();
        Item hiddenPage = new Item();
        hiddenPage.visible.set(false);
        Image image = new Image();
        image.loadedSource = "https://example.test/cover.jpg";
        image.decodeGen = 1;
        image.adoptedGen = 0;
        hiddenPage.children.add(image);
        root.children.add(hiddenPage);

        assertFalse(AndroidRenderPolicy.hasActiveQmlWork(root));
    }

    @Test
    public void wakeSettleAndSameFrameChangesKeepASecondFrameAlive() {
        Item root = new Item();

        assertTrue(AndroidRenderPolicy.shouldRenderContinuously(false, false, true, root));
        assertTrue(AndroidRenderPolicy.shouldRenderContinuously(false, true, false, root));
        assertFalse(AndroidRenderPolicy.shouldRenderContinuously(false, false, false, root));
    }
}
