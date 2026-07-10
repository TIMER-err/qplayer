package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.input.TextEditable;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;

import org.lwjgl.glfw.GLFW;

/**
 * Translates GLFW input (fired on the main thread) into qml4j dispatch calls, by
 * marshalling each event onto the render thread — the desktop analogue of the
 * Android view's {@code queueEvent(...)}. Pointer coordinates are passed in
 * logical units: under the standard framebuffer = window×contentScale relation,
 * the QML root (sized {@code framebuffer / uiScale}) matches GLFW's window/screen
 * coordinate space, so cursor positions map 1:1.
 *
 * <p>Owns the host-drawn lyric column's drag gesture (grab / slop / scroll /
 * tap-to-seek), mirroring the Android shell; all gesture state is mutated only
 * inside the posted render-thread tasks, so it needs no synchronization.
 */
final class InputBridge {

    private static final float LYRIC_TAP_SLOP = 12f;

    private final DesktopWindow win;

    // Match qml4j Flickable's own glide so wheel scrolling on desktop feels exactly
    // like the mobile fling: ease the shown position toward the target at the same
    // frame-rate-independent rate a = 1 - exp(-EASE*dt). (Flickable.EASE = 18.)
    private static final float EASE = 18f;
    // Notches accumulated per wheel detent (1.0 = one Flickable WHEEL_STEP ≈ 48px of
    // content); the engine applies WHEEL_STEP internally via dispatchWheel. >1 covers
    // more distance per detent (faster) while keeping the same glide curve.
    private static final float WHEEL_GAIN = 1.8f;

    private volatile double cursorX;
    private volatile double cursorY;

    // Smooth-scroll accumulators in wheel-notch units (render-thread only: written
    // via posted render tasks, eased toward 0 in tickScroll). The raw GLFW wheel is
    // discrete, so easing the remaining notches each frame reproduces the same glide
    // the Flickable applies to a fling.
    private double pendingScrollX;
    private double pendingScrollY;
    private long lastScrollNanos;

    // Lyric-body gesture state (render-thread only).
    private boolean lyGrab;
    private boolean lyMoved;
    private float lyDownY;

    InputBridge(DesktopWindow win) {
        this.win = win;
    }

    void install(long window) {
        GLFW.glfwSetCursorPosCallback(window, (w, x, y) -> {
            cursorX = x;
            cursorY = y;
            final float fx = (float) x, fy = (float) y;
            win.postRenderTask(() -> onMove(fx, fy));
        });
        GLFW.glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            final float fx = (float) cursorX, fy = (float) cursorY;
            if (action == GLFW.GLFW_PRESS) win.postRenderTask(() -> onPress(fx, fy));
            else if (action == GLFW.GLFW_RELEASE) win.postRenderTask(() -> onRelease(fx, fy));
        });
        GLFW.glfwSetScrollCallback(window, (w, dx, dy) -> {
            final double adx = dx * WHEEL_GAIN, ady = dy * WHEEL_GAIN;
            // Accumulate notches on the render thread; tickScroll() eases them out.
            win.postRenderTask(() -> {
                pendingScrollX += adx;
                pendingScrollY += ady;
            });
        });
        GLFW.glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            // Text-editing shortcuts: Ctrl (Win/Linux) or Cmd (macOS) + A/C/X/V. The
            // engine exposes copy/cut/paste as explicit calls (not part of dispatchKey)
            // and has no select-all at all, so the host must route the accelerators —
            // and mapKey returns 0 for letters, so the normal path below would drop
            // them anyway.
            if (action == GLFW.GLFW_PRESS
                    && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0
                    && (key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_C
                        || key == GLFW.GLFW_KEY_X || key == GLFW.GLFW_KEY_V)) {
                final int clipKey = key;
                win.postRenderTask(() -> {
                    QmlView v = win.view();
                    if (v == null) return;
                    if (clipKey == GLFW.GLFW_KEY_A) selectAll(v);
                    else if (clipKey == GLFW.GLFW_KEY_C) v.copy();
                    else if (clipKey == GLFW.GLFW_KEY_X) v.cut();
                    else v.paste();
                });
                return;
            }
            // Forward-delete: qml4j 0.2.13 has no KEY_DELETE (only KEY_BACKSPACE),
            // so dispatchKey can't express it — same "engine has no verb for it"
            // situation as select-all above. Do it host-side against TextEditable.
            if (action == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_DELETE) {
                win.postRenderTask(() -> {
                    QmlView v = win.view();
                    if (v != null) forwardDelete(v);
                });
                return;
            }
            if (action == GLFW.GLFW_REPEAT) return;
            final boolean down = action == GLFW.GLFW_PRESS;
            final int code = mapKey(key, mods);
            if (code == 0) return;
            final boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
            win.postRenderTask(() -> {
                QmlView v = win.view();
                if (v != null) v.dispatchKey(code, null, down, shift);
            });
        });
        GLFW.glfwSetCharCallback(window, (w, codepoint) -> {
            final String s = new String(Character.toChars(codepoint));
            win.postRenderTask(() -> {
                QmlView v = win.view();
                if (v != null && !s.isEmpty()) v.dispatchKey(0, s, true);
            });
        });
    }

    /** Called once per frame on the render thread: ease the accumulated wheel notches
     *  toward 0 with the Flickable's own a = 1 - exp(-EASE*dt) glide and feed the
     *  per-frame delta to dispatchWheel, so a desktop wheel scroll animates with the
     *  same curve as a mobile fling. */
    void tickScroll() {
        if (Math.abs(pendingScrollX) < 0.001 && Math.abs(pendingScrollY) < 0.001) {
            pendingScrollX = pendingScrollY = 0;
            lastScrollNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        if (lastScrollNanos == 0L) { lastScrollNanos = now; return; }
        float dt = (now - lastScrollNanos) / 1_000_000_000f;
        lastScrollNanos = now;
        if (dt <= 0f) return;
        if (dt > 0.05f) dt = 0.05f;

        QmlView v = win.view();
        if (v == null) return;
        double a = 1.0 - Math.exp(-EASE * dt);
        double stepX = pendingScrollX * a;
        double stepY = pendingScrollY * a;
        // Snap the tail so it settles instead of crawling asymptotically.
        if (Math.abs(pendingScrollX - stepX) < 0.002) stepX = pendingScrollX;
        if (Math.abs(pendingScrollY - stepY) < 0.002) stepY = pendingScrollY;
        pendingScrollX -= stepX;
        pendingScrollY -= stepY;
        float scale = win.uiScale();
        v.dispatchWheel((float) cursorX / scale, (float) cursorY / scale, (float) stepX, (float) stepY);
    }

    // Select-all on the focused editable, mirroring the engine's own selection
    // bookkeeping (anchor, then range, then caret — setting the caret does not
    // clear the range).
    private static void selectAll(QmlView v) {
        Item f = v.focused();
        if (!(f instanceof TextEditable t)) return;
        String s = t.text();
        int len = s == null ? 0 : s.length();
        if (len == 0) return;
        t.setSelectionAnchor(0);
        t.setSelectionRange(0, len);
        t.setCursorPosition(len);
    }

    // Delete the current selection, or the character right after the caret when
    // there's no selection — the usual Delete-key semantics, hand-rolled since the
    // engine only understands Backspace natively.
    private static void forwardDelete(QmlView v) {
        Item f = v.focused();
        if (!(f instanceof TextEditable t) || t.readOnly()) return;
        String s = t.text();
        if (s == null) s = "";
        int start = t.selectionStart();
        int end = t.selectionEnd();
        String newText;
        int newCursor;
        if (start != end) {
            int lo = Math.min(start, end), hi = Math.max(start, end);
            newText = s.substring(0, lo) + s.substring(hi);
            newCursor = lo;
        } else {
            int pos = t.cursorPosition();
            if (pos < 0 || pos >= s.length()) return;
            newText = s.substring(0, pos) + s.substring(pos + 1);
            newCursor = pos;
        }
        t.setText(newText);
        t.setSelectionAnchor(newCursor);
        t.setSelectionRange(newCursor, newCursor);
        t.setCursorPosition(newCursor);
        t.emitTextChanged();
    }

    // --- render-thread handlers ----------------------------------------------

    private void onPress(float x, float y) {
        QmlView v = win.view();
        if (v == null) return;
        // glfwGetCursorPos on Windows (Per-Monitor DPI V2) returns physical pixels;
        // QML coordinate space is logical (physical / uiScale). Divide so all
        // downstream consumers (QML + lyric compositor) receive logical coordinates.
        float scale = win.uiScale();
        float lx = x / scale, ly = y / scale;
        float topInset = win.settings() != null ? win.settings().topInset() : 0f;
        float surfaceWLogical = win.framebufferSize()[0] / scale;
        float surfaceHLogical = win.framebufferSize()[1] / scale;
        LyricCompositor c = win.compositor();
        // The lyric body (between the QML title and transport bands) is host-drawn
        // with no QML controls under it: a drag scrolls, a tap seeks. Wait for slop
        // before engaging the scroll so a tap stays a tap.
        if (c.lyricsScrollable(lx, ly, surfaceWLogical, surfaceHLogical, topInset)) {
            lyGrab = true;
            lyDownY = ly;
            lyMoved = false;
            return;
        }
        lyGrab = false;
        v.dispatchPointerDown(lx, ly);
    }

    private void onMove(float x, float y) {
        float scale = win.uiScale();
        float lx = x / scale, ly = y / scale;
        if (lyGrab) {
            LyricCompositor c = win.compositor();
            if (!lyMoved) {
                if (Math.abs(ly - lyDownY) > LYRIC_TAP_SLOP) {
                    lyMoved = true;
                    c.lyricRenderer().scrollDown(lyDownY);
                    c.lyricRenderer().scrollMove(ly);
                }
            } else {
                c.lyricRenderer().scrollMove(ly);
            }
            return;
        }
        QmlView v = win.view();
        if (v != null) v.dispatchPointerMove(lx, ly);
    }

    private void onRelease(float x, float y) {
        float scale = win.uiScale();
        float lx = x / scale, ly = y / scale;
        if (lyGrab) {
            lyGrab = false;
            LyricCompositor c = win.compositor();
            PlayerController controller = win.controller();
            if (lyMoved) {
                c.lyricRenderer().scrollUp();
            } else {
                long t = c.lyricRenderer().timeAtScreenY(lyDownY);
                if (t >= 0L && controller != null) controller.seek(t);
            }
            return;
        }
        QmlView v = win.view();
        if (v != null) v.dispatchPointerUp(lx, ly);
    }

    // Printable characters arrive via the char callback; this maps only the control
    // keys QmlView understands. 0 means "not a control key" -> ignored.
    private static int mapKey(int key, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE: return QmlView.KEY_BACKSPACE;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER: return QmlView.KEY_ENTER;
            case GLFW.GLFW_KEY_LEFT: return QmlView.KEY_LEFT;
            case GLFW.GLFW_KEY_RIGHT: return QmlView.KEY_RIGHT;
            case GLFW.GLFW_KEY_UP: return QmlView.KEY_UP;
            case GLFW.GLFW_KEY_DOWN: return QmlView.KEY_DOWN;
            case GLFW.GLFW_KEY_HOME: return QmlView.KEY_HOME;
            case GLFW.GLFW_KEY_END: return QmlView.KEY_END;
            case GLFW.GLFW_KEY_ESCAPE: return QmlView.KEY_ESCAPE;
            case GLFW.GLFW_KEY_TAB:
                return (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? QmlView.KEY_BACKTAB : QmlView.KEY_TAB;
            default: return 0;
        }
    }
}
