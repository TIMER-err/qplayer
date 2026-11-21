package dev.t1m3.qplayer.glass;

import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Image;

/**
 * Everything {@link GlassCompositor} needs from a host, and nothing else.
 *
 * <p>The liquid-glass pass is the one piece of the frame that cannot be left to QML
 * (qml4j's {@code MultiEffect} has no backdrop filter), so it has to run wherever the
 * frame is actually being drawn — and that is the only thing that differs between the
 * desktop LWJGL host and the Android {@code GLSurfaceView}. Both already own a
 * {@code SurfaceBackend} with a Skija surface, a {@code DirectContext} and a pixel
 * size, so the whole platform seam is these four methods:
 *
 * <ul>
 *   <li>{@link #snapshot()} — the frame as it stands <em>right now</em>, i.e. after the
 *       caller's QML composite pass ran with the glass panes transparent, and before
 *       anything else is painted on top. It must stay a GPU-backed image: Skija's
 *       {@code makeImageSnapshot} is lazy, and reading the pixels back to the CPU every
 *       frame would cost far more than the effect is worth. Ownership transfers to the
 *       caller, which closes it.</li>
 *   <li>{@link #recordingContext()} — the {@code DirectContext} the snapshot belongs to.
 *       The blur renders into a quarter-size render target created from it.</li>
 *   <li>{@link #width()} / {@link #height()} — the device pixel size of the frame, used
 *       to size that render target when the caller does not pass one.</li>
 * </ul>
 *
 * <p>Desktop: {@code dev.t1m3.qplayer.desktop.window.GraphicsBackend} extends this on
 * top of qml4j's {@code SurfaceBackend}, so the existing call site is unchanged.
 * Android: the {@code SkijaGlSurface} inside {@code QmlGLSurfaceView} implements it
 * directly.
 *
 * <p>A backend that returns {@code null} from {@link #snapshot()} (minimized window,
 * not-yet-realized surface, no GL context) is not an error: the compositor keeps the
 * chrome on its QML material for that frame and tries again on the next one.
 */
public interface GlassBackend {

    /**
     * A snapshot of what has been drawn so far this frame, or {@code null} when this
     * host cannot provide one right now. Ownership transfers to the caller.
     */
    Image snapshot();

    /** The Skija context that owns {@link #snapshot()}, or {@code null} for a raster one. */
    DirectContext recordingContext();

    /** Device (physical) pixel width of the frame. */
    int width();

    /** Device (physical) pixel height of the frame. */
    int height();
}
