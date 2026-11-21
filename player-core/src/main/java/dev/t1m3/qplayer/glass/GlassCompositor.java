package dev.t1m3.qplayer.glass;

import dev.t1m3.qplayer.util.Logger;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectUniformInfo;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Liquid-glass compositor: the one thing qml4j cannot do for itself.
 *
 * <p>The engine's {@code MultiEffect} implements masking and drop shadows only — its
 * {@code blurEnabled}/{@code blur} knobs are accepted and never reach the paint path —
 * so a QML surface here has no way to see, let alone refract, what is behind it. The
 * host can, because it owns the whole frame.
 *
 * <p>The optics are ported from the reference implementation in
 * {@code glsl-playground/liquid-glass.html} (MIT): a rounded-rect SDF whose analytic
 * gradient becomes the surface normal, a {@code 1 - sqrt(1 - x²)} circle-map falloff
 * that displaces the backdrop sample by up to {@code uRefraction} pixels at the rim
 * and not at all in the middle, a thick-lens zoom, Apple's three-channel chromatic
 * aberration (R and B offset in opposite directions while G stays put, which is what
 * keeps the glass from going yellow), a soft dark cap where the light comes from
 * above, the equator bright line, the bottom rim arc and the thin shell highlight.
 *
 * <p>Per frame:
 * <ol>
 *   <li>the chrome panes are made transparent and the scene is re-rendered, so the
 *       canvas holds the <em>content only</em> — the honest backdrop;</li>
 *   <li>that frame is snapshotted and blurred on the GPU into a quarter-size render
 *       target (at this blur radius the extra resolution is invisible);</li>
 *   <li>the glass shader draws each pane, using the blurred backdrop as its child
 *       shader and the sharp one for the outside-of-shape pixels and the AA seam;</li>
 *   <li>the panes are restored and painted on top, so their own content — title,
 *       icons, artwork — lands on the glass.</li>
 * </ol>
 *
 * <p>Lives in {@code player-core} rather than in a host, because nothing in here is
 * platform-specific: the host-specific parts are the four things in
 * {@link GlassBackend} — a snapshot of the frame that was just drawn, the Skija
 * {@code DirectContext} that owns it and the device pixel size. The desktop LWJGL
 * host and the Android {@code QmlGLSurfaceView} both hand it the same three steps
 * ({@link #beginFrame} before the QML composite pass, {@link #finishFrame} after it,
 * {@link #setLyricSurfaceActive} so the host-drawn lyric page keeps the glass out of
 * its way), and both therefore paint identical material. Before the move the class
 * sat in the desktop host, which is precisely why Android had no glass at all.
 */
public class GlassCompositor {

    /** Every node that should be treated as a floating glass pane, by objectName. */
    private static final String[] PANES = {
            // The tab indicator is deliberately in here: it is a second, thinner
            // sheet of glass sliding over the bar (with dispersion), not a painted
            // chip — the reference draws it as its own backdrop layer too.
            "glassTopBar", "glassMiniPlayer", "glassTabBar", "glassRail", "glassTabPill",
            // 沉浸模式底部那条悬浮控制条（ImmersiveControlBar.qml 里的面板节点）。它和上面几个
            // 不同：**不在 Main.qml 里**，而是挂在路由页 ImmersivePage 上，由 ManagedPageLoader
            // 的 Loader 实例化。这没问题 —— Renderer.attachLoadedItem 会把 Loader 载入的页面
            // add 进 Loader.children，而 findByObjectName 是递归 children 的，所以只要页面在栈上
            // 就解析得到；页面不在栈上时节点不存在，pane 列表里自然没有它。
            "glassImmersiveBar",
    };

    /** Blur radius in device pixels. Larger surfaces read as thicker materials. */
    private static final float BLUR_SIGMA = 26f;
    /** The blur runs at this fraction of device resolution. */
    private static final float BLUR_SCALE = 0.25f;
    /** QML property Main.qml reads to decide who paints the chrome's material. */
    private static final String HOST_COMPOSITED = "hostGlassComposited";

    /**
     * The blur runs at this fraction of device resolution, adaptively.
     *
     * <p>{@link #BLUR_SCALE} is the ceiling (best quality). A phone has ~7x the device
     * pixels of a 420x900 window, and this pass — snapshot the frame, blur it into a
     * render target, then draw every pane through a shader that samples that target —
     * runs every frame. On a slow device it is the whole frame budget. The compositor
     * already measures itself, so it now spends that measurement: over budget it backs
     * the blur off (fewer pixels to blur and to sample), comfortably under budget it
     * walks back up. Quality is only traded away where the device cannot afford it.
     */
    private float blurScale = BLUR_SCALE;
    /** Rolling average above which the pass is considered too expensive, in microseconds. */
    private static final long GLASS_BUDGET_US = 6000L;
    private static final float MIN_BLUR_SCALE = 0.10f;
    private static final int ADAPT_WINDOW = 60;

    private final List<Item> panes = new ArrayList<>();
    /** Root of the tree the panes came from: what {@link #HOST_COMPOSITED} is set on. */
    private Item glassRoot;
    /** What the tree was last told, so the flag is written only when it changes. */
    private boolean hostComposited;
    // Which view the panes were resolved against. A process can host more than one
    // QmlView (the desktop lyric window, a headless snapshot harness), and resolving
    // against the wrong one and then latching "no panes" disabled the effect for the
    // real window — exactly the failure that let a chrome-less build ship.
    private QmlView resolvedFor;
    private boolean enabled = true;

    /**
     * The blurred backdrop is refreshed every other frame and reused in between.
     *
     * <p>It is a low-frequency signal (sigma 26 device px at quarter resolution): the
     * difference between "blurred this frame" and "blurred the frame before" is a
     * fraction of a level across a handful of pixels, while the refresh costs a full
     * frame snapshot plus a blur pass — on a phone that is the difference between a
     * usable UI and a slideshow. Everything else about the pass (the refraction, the
     * dispersion, the rim light, the tint, the chrome) is computed per frame exactly
     * as before, so the material does not lose a single effect.
     */
    private DirectContext cachedBlurContext;
    private int framesSinceBlur = Integer.MAX_VALUE;
    private static final int BLUR_REFRESH_EVERY = 2;
    /**
     * The blur refresh period, in frames, as a dial: {@link #BLUR_REFRESH_EVERY} keeps
     * the eye's own refresh ahead of it, and a device whose blur phase is the whole cost
     * may widen it while it stays inside the staleness budget in {@link #adapt}.
     */
    private int blurPeriod = BLUR_REFRESH_EVERY;
    private static final int MAX_BLUR_PERIOD = 4;
    /**
     * How many frames of backdrop staleness the adaptive dials may spend in total.
     * At 60 fps this is ~66 ms; the blur is a sigma-26 signal, so a shift of a few
     * pixels in it is what "stale" looks like at the pane edge.
     */
    private static final int STALENESS_BUDGET_FRAMES = 4;
    /**
     * Pin every adaptive dial at full quality (exact reuse, highest blur resolution).
     * Diagnostic switch: it makes "did this refactor change a single pixel?" answerable,
     * because otherwise the dials move on any machine slower than the budget.
     */
    private static final boolean FIXED_QUALITY = Boolean.getBoolean("qplayer.glass.fixed");

    /**
     * Print the per-window phase breakdown ({@code glass phases: …}) to the log.
     *
     * <p><b>默认关</b>（2026-10-02，用户：「渲染相关的 log 一直在刷屏」）。那一行每
     * {@link #ADAPT_WINDOW} 帧打一次——60 fps 下就是每秒一条，而且这条路径正好跑在渲染线程上，
     * 日志里满满一屏 {@code [qplayer-render/INFO]}。
     *
     * <p>数据本身没删：相位计时照旧累加，自适应（{@link #adapt}）照旧用它挑主相位、
     * 照旧在动某一档时打那几行「glass adapt: …」（那是<b>状态变化</b>，不是周期性刷屏）。
     * 要看回这份分解时开一下开关即可：
     * {@code -Dqplayer.glass.stats=true}。
     */
    private static final boolean GLASS_STATS = Boolean.getBoolean("qplayer.glass.stats");

    /**
     * Two alternating blur targets, each with its own cached image and image shader.
     *
     * <p>Skia keys a RuntimeEffect's compiled program on the identity of its child
     * shaders, and an ImageShader's key comes from its image. Building the child from a
     * brand-new image every frame therefore changed the program key every frame, so all
     * five pane programs missed the pipeline cache and were rebuilt ~60 times a second —
     * that, not the blur itself, is where this pass's 9.7 ms/frame was going. Writing
     * into two stable targets makes the key set {A, B}, so the programs compile once and
     * are then reused for the rest of the session.
     *
     * <p>The invariant that keeps it correct: with two slots and a reuse window of at
     * most one frame, a slot is only ever sampled by the shader created in its most
     * recent write, and no slot is written while its own shader is on screen.
     */
    /** Most blur generations a baked pane material may be reused across. */
    private static final int MAT_STRIDE_MAX = 3;
    private static final int BLUR_SLOTS = MAT_STRIDE_MAX + 1;
    private final Surface[] blurTargets = new Surface[BLUR_SLOTS];
    private final Image[] blurImages = new Image[BLUR_SLOTS];
    private final Shader[] blurShaders = new Shader[BLUR_SLOTS];
    private int blurSlot;
    private int cachedBlurSlot = -1;
    /** Bumped every time the blur is refilled; the material cache keys off it. */
    private long blurGeneration;

    /**
     * A pane's baked material.
     *
     * <p>The five-pane RuntimeEffect pass was 85-95% of this compositor's per-frame cost
     * (snapshot 4 us, blur ~300 us, panes 8.8 ms at 420x900 and 26 ms at 1240x820) and
     * its cost is proportional to pane area, which is what a per-pixel runtime shader
     * looks like. But the pane's pixels are a pure function of the blurred backdrop and
     * the uniform set, and the blurred backdrop is a sigma-26 signal that only changes
     * when the blur is refilled — so on the frames in between, the correct material is
     * bit-for-bit the one already drawn. Baking it into a small target once per blur
     * generation and blitting it back is therefore exact, not an approximation, and it
     * removes the pass's dominant cost.
     *
     * <p>Two targets alternate so a bake never writes into a target whose image is still
     * the one being blitted; the image of the slot about to be rewritten is released
     * first so Skia does not have to copy the target to preserve it.
     */
    private static final class PaneCache {
        Surface[] targets = new Surface[2];
        Image[] images = new Image[2];
        int slot;
        int capW, capH;
        float ox, oy;
        long signature;
        long blurGen = -1;
        boolean valid;
    }

    private final java.util.IdentityHashMap<Item, PaneCache> material =
            new java.util.IdentityHashMap<>();
    /**
     * How many blur generations a bake may be reused for. 1 is exact (the material is
     * remade whenever its only time-varying input changes); the adaptive pass raises it
     * only while the whole pass is over budget, and says so in the log.
     */
    private int matStride = 1;
    /** Hash of everything read while building a pane's uniforms. */
    private long sig;
    /**
     * This frame's pane boxes in window coordinates (null = not on screen), computed
     * once per frame: the pass needs them to size the material, the early-out needs
     * them to decide whether there is a pass at all, and walking the ancestor chain
     * five times per frame for each of those would be silly.
     */
    private final float[][] frameBoxes = new float[16][];
    private int visiblePanes;

    private RuntimeEffect effect;
    private int blurW, blurH;
    private DirectContext blurContext;

    // Per-phase cost, rolled up into the periodic log line so a phone log alone says
    // where the pass is spending its time.
    private long phaseSnapNanos, phaseBlurNanos, phasePaneNanos, phaseChromeNanos;

    private boolean panesHidden;
    private long frames = 0;
    private long totalNanos = 0;
    // A headless harness (UiSnapshotProbe) has no log4j sink, so a swallowed failure
    // would leave it silently rendering the pre-glass result and "verifying" a build
    // nobody ships. This makes the compositor narrate itself to stdout instead.
    private static volatile boolean stdoutDiagnostics = false;
    // Set by the host: true while the lyric page (or the whole-line tempera overlay)
    // owns the window.
    private static volatile java.util.function.BooleanSupplier lyricSurfaceActive;

    public static void enableStdoutDiagnostics() {
        stdoutDiagnostics = true;
    }

    /** The host tells the compositor when a host-drawn full-screen surface is up. */
    public static void setLyricSurfaceActive(java.util.function.BooleanSupplier active) {
        lyricSurfaceActive = active;
    }

    private static void say(String message) {
        if (stdoutDiagnostics) System.out.println("[glass] " + message);
    }

    /** Forget the resolved nodes: the QML tree was rebuilt (a respawned view). */
    public void invalidate() {
        panes.clear();
        glassRoot = null;
        hostComposited = false;
        resolvedFor = null;
        enabled = true;
        closeGpu();
    }

    private void closeGpu() {
        for (int i = 0; i < BLUR_SLOTS; i++) {
            close(blurShaders[i]);
            blurShaders[i] = null;
            close(blurImages[i]);
            blurImages[i] = null;
            if (blurTargets[i] != null) {
                try { blurTargets[i].close(); } catch (Throwable ignored) { }
                blurTargets[i] = null;
            }
        }
        for (PaneCache pc : material.values()) closePaneCache(pc);
        material.clear();
        blurSlot = 0;
        cachedBlurSlot = -1;
        blurGeneration = 0;
        cachedBlurContext = null;
        framesSinceBlur = Integer.MAX_VALUE;
        blurW = 0;
        blurH = 0;
    }

    private static void closePaneCache(PaneCache pc) {
        for (int i = 0; i < 2; i++) {
            if (pc.targets[i] != null) {
                try { pc.targets[i].close(); } catch (Throwable ignored) { }
                pc.targets[i] = null;
            }
            close(pc.images[i]);
            pc.images[i] = null;
        }
        pc.valid = false;
        pc.capW = 0;
        pc.capH = 0;
    }

    private void resolve(QmlView view) {
        resolvedFor = view;
        panes.clear();
        for (String name : PANES) {
            Item node = view.findByObjectName(name);
            if (node != null) panes.add(node);
        }
        glassRoot = view.root();
        // A fresh tree defaults to drawing its own material, so this compositor has
        // not claimed the chrome until the first frame it actually paints glass.
        hostComposited = false;
        say("resolved " + panes.size() + " panes");
    }

    /**
     * Hand the chrome's material to QML ({@code false}) or claim it for this host
     * ({@code true}). A host-rendered pane draws <em>nothing</em> itself, so the flag
     * is claimed only for a frame that really painted glass (see
     * {@link #composite}) and released again the moment one does not — otherwise a
     * backend that cannot snapshot would leave the chrome as bare floating content.
     */
    private void setHostComposited(boolean on) {
        Item root = glassRoot;
        if (root == null || on == hostComposited) return;
        try {
            if (MemberAccess.hasMember(root, HOST_COMPOSITED)) {
                MemberAccess.writeMember(root, HOST_COMPOSITED, Boolean.valueOf(on));
                hostComposited = on;
            }
        } catch (Throwable t) {
            say("could not set " + HOST_COMPOSITED + ": " + t);
        }
    }

    public void beginFrame(QmlView view) {
        if (view == null) return;
        if (!enabled || glassSuppressed()) {
            // A host-drawn full-screen surface owns the window (the lyric page, and its
            // slide in and out). No glass is painted this frame, so the chrome has to
            // fall back to its own material: leaving the panes marked host-rendered
            // meant the bars sliding back in during the lyric transition carried no
            // backdrop at all, and the glass only arrived once the animation had
            // finished — a visible beat late, every single time.
            setHostComposited(false);
            return;
        }
        if (resolvedFor != view) resolve(view);
        // Claim the material *before* the content pass, so the frame that paints host
        // glass and the frame that owns it are the same frame. Claiming it part-way
        // through (after the composite) left the pane still drawing its own material
        // in the first frame that showed host glass: two materials at once, with the
        // intended look only arriving on the frame after.
        setHostComposited(true);
        // `opacity`, not `visible`. Hiding with visible=false was tried and measured
        // cheaper on paper (the engine culls an invisible subtree: 102 nodes visited and
        // 94 us of measure against 510 nodes and 436 us with opacity 0, draw 14.8 vs
        // 16.0 ms) but it is not safe here: qml4j resolves anchors against an item's
        // geometry only while it is visible, so hiding five panes that the page anchors
        // to moved the *whole* layout — 27 of the suite's 57 snapshot scenes changed,
        // with deltas up to 240/255 across the full content box (378x857 of 420x900,
        // 1210x779 of 1240x820), not the handful of pixels inside the panes. Opacity 0
        // already culls the painting (30.4 -> 16.0 ms on the pane-drawing baseline) and
        // leaves layout alone.
        for (int i = 0; i < panes.size(); i++) panes.get(i).opacity.set(0.0);
        panesHidden = !panes.isEmpty();
    }

    private boolean glassSuppressed() {
        return lyricSurfaceActive != null && lyricSurfaceActive.getAsBoolean();
    }

    public void finishFrame(Canvas canvas, Renderer renderer, QmlView view, GlassBackend backend,
                            float uiScale, int pixelWidth, int pixelHeight) {
        if (!panesHidden) return;
        panesHidden = false;
        long started = System.nanoTime();
        try {
            // A frame that failed to paint glass hands the material straight back, so the
            // chrome is never left host-rendered with nothing behind it.
            if (!composite(canvas, view, backend, uiScale, pixelWidth, pixelHeight)) {
                setHostComposited(false);
            }
        } finally {
            // beginFrame made the panes transparent so the caller's composite pass would
            // photograph the content alone. `opacity` is a *persistent* property, not a
            // per-frame flag, so it has to be put back before anything else draws:
            // leaving it at 0 blanked every glass surface on every later frame, which is
            // exactly what the chrome looked like — pure material with no content in it.
            for (int i = 0; i < panes.size(); i++) panes.get(i).opacity.set(1.0);
            // ...and then the chrome itself has to be painted, because the frame that
            // was just composited contains only the backdrop.
            long c0 = System.nanoTime();
            paintChrome(canvas, renderer, uiScale);
            phaseChromeNanos += System.nanoTime() - c0;
        }

        frames++;
        totalNanos += System.nanoTime() - started;
        if (frames >= ADAPT_WINDOW) {
            long avgUs = totalNanos / frames / 1000L;
            long snapUs = phaseSnapNanos / frames / 1000L;
            long blurUs = phaseBlurNanos / frames / 1000L;
            long paneUs = phasePaneNanos / frames / 1000L;
            long chromeUs = phaseChromeNanos / frames / 1000L;
            adapt(avgUs, snapUs, blurUs, paneUs, chromeUs);
            if (GLASS_STATS) {
                Logger.info("glass phases: total {} us/frame = snapshot {} + blur {} + panes {}"
                                + " + chrome {} ({}x{} px, blur scale {}, period {}, stride {},"
                                + " panes {}/{})",
                        Long.valueOf(avgUs), Long.valueOf(snapUs), Long.valueOf(blurUs),
                        Long.valueOf(paneUs), Long.valueOf(chromeUs),
                        Integer.valueOf(pixelWidth), Integer.valueOf(pixelHeight),
                        Float.valueOf(blurScale), Integer.valueOf(blurPeriod),
                        Integer.valueOf(matStride), Integer.valueOf(visiblePanes),
                        Integer.valueOf(panes.size()));
            }
            frames = 0;
            totalNanos = 0;
            phaseSnapNanos = phaseBlurNanos = phasePaneNanos = phaseChromeNanos = 0;
        }
    }

    private static final int DIAL_NONE = 0;
    private static final int DIAL_SCALE = 1;
    private static final int DIAL_PERIOD = 2;
    private static final int DIAL_STRIDE = 3;
    private int lastDial = DIAL_NONE;
    private long lastDialBeforeUs;
    private final boolean[] dialFutile = new boolean[4];

    /**
     * Spend performance where it actually buys something, and give quality back when it
     * does not.
     *
     * <p>The previous ladder lowered {@code blurScale} whenever the pass was over budget,
     * on the assumption that the blur was what cost. On a device where the frame-sized
     * snapshot copy or the pane shaders dominate that assumption is simply false, and the
     * ladder bought nothing while permanently softening the material. So: pick the dial
     * the dominant phase answers to, move exactly one dial per window (so its effect is
     * attributable), and if the total does not fall by at least 8% undo that move and
     * stop trying it. When the pass is comfortably inside its budget the dials walk back
     * up, cheapest-to-restore first.
     */
    private void adapt(long avgUs, long snapUs, long blurUs, long paneUs, long chromeUs) {
        if (FIXED_QUALITY) return;
        boolean over = avgUs > GLASS_BUDGET_US;
        boolean comfy = avgUs * 3L < GLASS_BUDGET_US;
        if (lastDial != DIAL_NONE) {
            if (over && avgUs > lastDialBeforeUs * 0.92) {
                undoDial(lastDial);
                dialFutile[lastDial] = true;
                Logger.info("glass adapt: dial {} bought nothing ({} -> {} us/frame), reverted",
                        Integer.valueOf(lastDial), Long.valueOf(lastDialBeforeUs),
                        Long.valueOf(avgUs));
            }
            lastDial = DIAL_NONE;
        }
        if (over) {
            // Which phase is worth attacking? Only the dominant one can move the total.
            boolean panesWin = paneUs >= blurUs && paneUs >= snapUs;
            int first = panesWin ? DIAL_STRIDE : DIAL_PERIOD;
            int second = panesWin ? DIAL_PERIOD : DIAL_STRIDE;
            int[] order = { first, second, DIAL_SCALE };
            for (int i = 0; i < order.length; i++) {
                if (tryDial(order[i], avgUs)) return;
            }
            if (!warnedNoDial) {
                warnedNoDial = true;
                Logger.info("glass adapt: over budget at the quality floor ({} us/frame,"
                                + " snapshot {} + blur {} + panes {} + chrome {})",
                        Long.valueOf(avgUs), Long.valueOf(snapUs), Long.valueOf(blurUs),
                        Long.valueOf(paneUs), Long.valueOf(chromeUs));
            }
            return;
        }
        warnedNoDial = false;
        if (!comfy) return;
        // Plenty of headroom: buy quality back, cheapest first, and forget the verdicts
        // that were reached in a heavier frame (a scroll, a theme change, a cold start).
        if (blurScale < BLUR_SCALE) {
            blurScale = Math.min(BLUR_SCALE, blurScale / 0.7f);
            closeGpu();
        } else if (matStride > 1) {
            matStride--;
        } else if (blurPeriod > BLUR_REFRESH_EVERY) {
            blurPeriod--;
        } else {
            for (int i = 1; i < dialFutile.length; i++) dialFutile[i] = false;
        }
    }

    private boolean warnedNoDial;

    /** Move one dial down, unless the budget for staleness forbids it. Returns whether it moved. */
    private boolean tryDial(int dial, long avgUs) {
        if (dialFutile[dial]) return false;
        if (dial == DIAL_SCALE) {
            if (blurScale <= MIN_BLUR_SCALE) return false;
            lastDial = dial;
            lastDialBeforeUs = avgUs;
            blurScale = Math.max(MIN_BLUR_SCALE, blurScale * 0.7f);
            closeGpu();
            Logger.info("glass adapt: blur scale -> {} ({} us/frame)", Float.valueOf(blurScale),
                    Long.valueOf(avgUs));
            return true;
        }
        if (dial == DIAL_PERIOD) {
            if (blurPeriod >= MAX_BLUR_PERIOD) return false;
            if ((long) matStride * (blurPeriod + 1) > STALENESS_BUDGET_FRAMES) return false;
            lastDial = dial;
            lastDialBeforeUs = avgUs;
            blurPeriod++;
            Logger.info("glass adapt: blur period -> {} frames ({} us/frame)",
                    Integer.valueOf(blurPeriod), Long.valueOf(avgUs));
            return true;
        }
        if (matStride >= MAT_STRIDE_MAX) return false;
        if ((long) (matStride + 1) * blurPeriod > STALENESS_BUDGET_FRAMES) return false;
        lastDial = dial;
        lastDialBeforeUs = avgUs;
        matStride++;
        Logger.info("glass adapt: material stride -> {} blur generations ({} us/frame)",
                Integer.valueOf(matStride), Long.valueOf(avgUs));
        return true;
    }

    private void undoDial(int dial) {
        if (dial == DIAL_SCALE) {
            blurScale = Math.min(BLUR_SCALE, blurScale / 0.7f);
            closeGpu();
        } else if (dial == DIAL_PERIOD) {
            blurPeriod = Math.max(BLUR_REFRESH_EVERY, blurPeriod - 1);
        } else if (dial == DIAL_STRIDE) {
            matStride = Math.max(1, matStride - 1);
        }
    }

    /**
     * Refract the (already composited) backdrop into every pane's shape. Must run while
     * the panes are still transparent, so the snapshot holds content and nothing else.
     *
     * @return whether glass actually reached the canvas this frame
     */
    private boolean composite(Canvas canvas, QmlView view, GlassBackend backend,
                              float uiScale, int pixelWidth, int pixelHeight) {
        if (!enabled || view == null || backend == null) return false;
        if (panes.isEmpty()) return false;
        if (view.root() == null) return false;
        // Hosts that already know their target size pass it in; ask the backend
        // otherwise, so the adapter really is self-sufficient.
        if (pixelWidth <= 0) pixelWidth = backend.width();
        if (pixelHeight <= 0) pixelHeight = backend.height();
        if (uiScale <= 0f) uiScale = 1f;

        // One geometry walk for the whole frame, plus the cheapest possible pass: a
        // frame in which no pane is on screen (the lyric page, a full-screen sheet, the
        // compact layout's hidden rail) has nothing to refract and nothing to paint, so
        // the snapshot, the blur and the shader pass are all skipped outright. The
        // caller still restores the panes' opacity and repaints their chrome, so the
        // frame is identical to one where they were simply not visible.
        visiblePanes = 0;
        for (int i = 0; i < panes.size() && i < frameBoxes.length; i++) {
            float[] box = rootBox(panes.get(i));
            frameBoxes[i] = box;
            if (box != null && box[2] * uiScale > 1f && box[3] * uiScale > 1f) visiblePanes++;
        }
        if (visiblePanes == 0) return false;

        // A backend that cannot snapshot (or a shader that throws) must not take the
        // chrome down with it: the material goes back to QML (setHostComposited) and
        // the panes are painted normally by the caller's finally block.
        boolean painted = false;
        Image snap = null;
        try {
            boolean refresh = cachedBlurSlot < 0 || blurShaders[cachedBlurSlot] == null
                    || blurContext != cachedBlurContext || framesSinceBlur >= blurPeriod;
            if (!refresh) {
                // Reuse: same material, one frame of blur staleness (invisible).
                framesSinceBlur++;
                long p0 = System.nanoTime();
                painted = drawPanes(canvas, uiScale, blurShaders[cachedBlurSlot]);
                phasePaneNanos += System.nanoTime() - p0;
                return painted;
            }
            framesSinceBlur = 0;
            long s0 = System.nanoTime();
            snap = backend.snapshot();
            phaseSnapNanos += System.nanoTime() - s0;
            if (snap == null) {
                // Null surface: a minimized or not-yet-realized window. Retry next
                // frame rather than latching, but do not claim the material meanwhile.
                say("backend produced no snapshot; chrome kept flat");
            } else {
                long b0 = System.nanoTime();
                // The image shader evaluates the image at localMatrix^-1 * local, so the
                // matrix that maps canvas pixels into the quarter-size blur surface is
                // the RECIPROCAL of the blur scale. Passing 0.25 read a patch four times
                // further up and to the left — the chrome showed a miniature of the
                // library grid squeezed into its top-left corner.
                int slot = refreshBlur(backend, snap, pixelWidth, pixelHeight);
                phaseBlurNanos += System.nanoTime() - b0;
                if (slot >= 0) {
                    cachedBlurSlot = slot;
                    cachedBlurContext = blurContext;
                    long p0 = System.nanoTime();
                    painted = drawPanes(canvas, uiScale, blurShaders[slot]);
                    phasePaneNanos += System.nanoTime() - p0;
                }
            }
        } catch (Throwable t) {
            Logger.warn("glass compositor disabled: {}", t.toString());
            say("disabled: " + t);
            if (stdoutDiagnostics) t.printStackTrace();
            enabled = false;
            painted = false;
        } finally {
            close(snap);
        }
        return painted;
    }

    /** Paint each pane's own content — title, artwork, icons — over its glass. */
    private void paintChrome(Canvas canvas, Renderer renderer, float uiScale) {
        if (renderer == null) return;
        if (uiScale <= 0f) uiScale = 1f;
        // The engine paints in the tree's own (logical) coordinates and expects the
        // caller to have applied the device scale: LyricCompositor wraps every
        // render()/renderSubtree() call in exactly this scale, while the glass shader
        // above draws in device pixels. Without it the whole chrome collapses into
        // the top-left corner on a HiDPI display.
        int frame = canvas.save();
        canvas.scale(uiScale, uiScale);
        for (int i = 0; i < panes.size(); i++) {
            Item pane = panes.get(i);
            float[] box = rootBox(pane);
            if (box == null) continue;
            // renderSubtree clips to [0,w]x[0,h] in canvas space, and the renderer
            // then culls the node's children against `clip - node.xy`. Passing the
            // pane's own width/height therefore culls everything it contains (a tab
            // bar at y=830 got a bottom clip of 49-830 = -781 and drew as an empty
            // shell). Hand it the box the pane occupies instead.
            float px = box[0];
            float py = box[1];
            float w = box[2];
            float h = box[3];
            if (w <= 1f || h <= 1f) continue;
            float radius = readFloat(pane, "glassRadius", 16f);
            int save = canvas.save();
            // Content has to respect the material's silhouette, and a pane's children
            // are laid out to its *box*. A full-bleed child — the now-playing bar's
            // progress line is the one that keeps coming back — otherwise runs
            // straight through the corner and hangs outside the glass on the
            // diagonal. Clip to the same rounded rect the shader just drew.
            float[] scale = paneScale(pane);
            float cw = w * scale[0];
            float ch = h * scale[1];
            if (radius > 0.5f) {
                canvas.clipRRect(RRect.makeXYWH(px - (cw - w) * 0.5f, py - (ch - h) * 0.5f,
                        cw, ch, radius), true);
            }
            renderer.renderSubtree(canvas, pane, px + cw, py + ch);
            canvas.restoreToCount(save);
        }
        canvas.restoreToCount(frame);
    }

    /**
     * Blur the frame into the next alternating target and return that slot's index, or
     * -1 if the blur is unavailable. See {@link #blurTargets} for why it alternates.
     */
    private int refreshBlur(GlassBackend backend, Image snap, int w, int h) {
        DirectContext ctx = backend.recordingContext();
        int bw = Math.max(1, Math.round(w * blurScale));
        int bh = Math.max(1, Math.round(h * blurScale));
        if (blurTargets[0] == null || blurW != bw || blurH != bh) {
            closeGpu();
            ImageInfo info = ImageInfo.makeN32Premul(bw, bh);
            for (int i = 0; i < BLUR_SLOTS; i++) {
                blurTargets[i] = ctx != null
                        ? Surface.makeRenderTarget(ctx, false, info)
                        : Surface.makeRasterN32Premul(bw, bh);
                if (blurTargets[i] == null) return -1;
            }
            blurW = bw;
            blurH = bh;
            blurContext = ctx;
        }
        int slot = blurSlot;
        Surface target = blurTargets[slot];
        if (target == null) return -1;
        try (Paint paint = new Paint()) {
            float sigma = BLUR_SIGMA * blurScale;
            paint.setImageFilter(ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP));
            Canvas c = target.getCanvas();
            c.clear(0);
            c.save();
            c.scale(blurScale, blurScale);
            c.drawImageRect(snap, Rect.makeWH(snap.getWidth(), snap.getHeight()),
                    Rect.makeWH(snap.getWidth(), snap.getHeight()), SamplingMode.LINEAR, paint, true);
            c.restore();
        }
        if (ctx != null && blurContext == ctx) ctx.flush();
        Image img = target.makeImageSnapshot();
        if (img == null) return -1;
        Shader shader = img.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                SamplingMode.LINEAR, Matrix33.makeScale(1f / blurScale));
        if (shader == null) return -1;
        close(blurShaders[slot]);
        blurShaders[slot] = shader;
        // Kept alive alongside its shader: the image is what gives the shader its key.
        close(blurImages[slot]);
        blurImages[slot] = img;
        blurSlot = (slot + 1) % BLUR_SLOTS;
        blurGeneration++;
        return slot;
    }

    private boolean drawPanes(Canvas canvas, float uiScale, Shader blur) {
        RuntimeEffect fx = effect();
        if (fx == null) return false;
        if (uiScale <= 0f) uiScale = 1f;
        boolean drew = false;
        for (int i = 0; i < panes.size(); i++) {
            Item pane = panes.get(i);
            float[] box = i < frameBoxes.length ? frameBoxes[i] : null;
            if (box == null) continue;
            float x = box[0] * uiScale;
            float y = box[1] * uiScale;
            float w = box[2] * uiScale;
            float h = box[3] * uiScale;
            if (w <= 1f || h <= 1f) continue;
            float radius = readFloat(pane, "glassRadius", 16f) * uiScale;
            int tint = readColor(pane, "glassTint", 0x8CFFFFFF);
            float[] scale = paneScale(pane);
            try (Data uniforms = uniforms(fx, pane, uiScale, x, y, w, h, radius, tint)) {
                long signature = sig;
                PaneCache pc = material.get(pane);
                if (pc == null) {
                    pc = new PaneCache();
                    material.put(pane, pc);
                }
                boolean stale = !pc.valid || pc.signature != signature
                        || blurGeneration - pc.blurGen >= matStride;
                if (stale) {
                    bake(fx, uniforms, blur, pane, x, y, w, h, radius, scale, signature, pc);
                }
                Image baked = pc.images[pc.slot];
                if (pc.valid && baked != null) {
                    canvas.drawImage(baked, pc.ox, pc.oy);
                    drew = true;
                }
            }
        }
        return drew;
    }


    /**
     * Draw one pane's material into its cached target. Same rect, same uniforms, same
     * canvas transform as drawing it straight onto the frame; the only difference is
     * that the result is kept, because on the frames in between it is still correct.
     */
    private void bake(RuntimeEffect fx, Data uniforms, Shader blur, Item pane,
                      float x, float y, float w, float h, float radius, float[] scale,
                      long signature, PaneCache pc) {
        DirectContext ctx = blurContext;
        float sx = Math.abs(scale[0]);
        float sy = Math.abs(scale[1]);
        if (sx < 1e-3f) sx = 1e-3f;
        if (sy < 1e-3f) sy = 1e-3f;
        // The canvas scales the sheet about the pane's centre, so the pixels it can
        // produce are the scaled box, centred on the same point.
        int ox = (int) Math.floor(x + (w - w * sx) * 0.5f);
        int oy = (int) Math.floor(y + (h - h * sy) * 0.5f);
        int bw = (int) Math.ceil(w * sx) + 2;
        int bh = (int) Math.ceil(h * sy) + 2;
        if (bw <= 2 || bh <= 2) { pc.valid = false; return; }
        if (pc.targets[pc.slot] == null || pc.capW < bw || pc.capH < bh) {
            for (int i = 0; i < 2; i++) {
                if (pc.targets[i] != null) {
                    try { pc.targets[i].close(); } catch (Throwable ignored) { }
                    pc.targets[i] = null;
                }
                close(pc.images[i]);
                pc.images[i] = null;
            }
            // Slack so the tab indicator's drag (which stretches it) does not reallocate
            // a target every frame.
            int cw = Math.min(4096, (int) Math.ceil(bw * 1.25f) + 64);
            int ch = Math.min(4096, (int) Math.ceil(bh * 1.25f) + 64);
            ImageInfo info = ImageInfo.makeN32Premul(cw, ch);
            for (int i = 0; i < 2; i++) {
                pc.targets[i] = ctx != null
                        ? Surface.makeRenderTarget(ctx, false, info)
                        : Surface.makeRasterN32Premul(cw, ch);
                if (pc.targets[i] == null) { pc.valid = false; return; }
            }
            pc.capW = cw;
            pc.capH = ch;
            pc.slot = 0;
        }
        int slot = pc.slot;
        Surface target = pc.targets[slot];
        // Release the image of the slot being rewritten: a live snapshot would make Skia
        // copy the whole target before the write.
        close(pc.images[slot]);
        pc.images[slot] = null;
        pc.valid = false;
        try (Shader shader = fx.makeShader(uniforms, new Shader[]{blur});
             Paint paint = new Paint()) {
            if (shader == null) return;
            paint.setShader(shader);
            paint.setAntiAlias(true);
            Canvas c = target.getCanvas();
            c.clear(0);
            int save = c.save();
            c.translate(-ox, -oy);
            if (scale[0] != 1f || scale[1] != 1f) {
                // About the pane's centre: that is where the QML Scale's origin sits.
                float cx = x + w * 0.5f;
                float cy = y + h * 0.5f;
                c.translate(cx, cy);
                c.scale(scale[0], scale[1]);
                c.translate(-cx, -cy);
            }
            c.drawRect(Rect.makeXYWH(x, y, w, h), paint);
            c.restoreToCount(save);
        }
        Image img = target.makeImageSnapshot();
        if (img == null) return;
        pc.images[slot] = img;
        pc.ox = ox;
        pc.oy = oy;
        pc.signature = signature;
        pc.blurGen = blurGeneration;
        pc.valid = true;
        pc.slot = 1 - slot;
    }

    /**
     * The pane's box in window coordinates, or null when it is not on screen.
     *
     * <p>{@code Item.x/y} is relative to the pane's <em>parent</em>, and only the four
     * top-level panes happen to have the root as theirs. The tab indicator lives
     * inside the tab bar, so drawing it at its local x/y put a 97x56 capsule of glass
     * near the window's origin and slid it sideways with the selected tab. The
     * ancestor chain was never consulted either, which is why the desktop got one of
     * those capsules even while the whole tab bar was hidden.
     */
    /**
     * The pane's own scale: {@code Item.scale} times every {@code Scale} in its
     * {@code transform} list. The tab indicator squashes and stretches with the drag
     * velocity (velocity * 0.75 across, * 0.25 up), and its glass has to deform with
     * it — the QML material path did that for free, while this compositor drew the
     * sheet as a fixed rectangle, so the content stretched and the glass behind it
     * did not.
     */
    private static float[] paneScale(Item pane) {
        float sx = 1f;
        float sy = 1f;
        try {
            Object uniform = pane.scale.peek();
            if (uniform instanceof Number) {
                sx *= ((Number) uniform).floatValue();
                sy *= ((Number) uniform).floatValue();
            }
            for (io.github.timer_err.qml4j.render.items.transform.Transform t : pane.transform) {
                if (t instanceof io.github.timer_err.qml4j.render.items.transform.Scale) {
                    io.github.timer_err.qml4j.render.items.transform.Scale sc =
                            (io.github.timer_err.qml4j.render.items.transform.Scale) t;
                    sx *= sc.xScale.peekFloat();
                    sy *= sc.yScale.peekFloat();
                }
            }
        } catch (Throwable ignored) { }
        if (sx <= 0.01f || sy <= 0.01f) return new float[]{1f, 1f};
        return new float[]{sx, sy};
    }

    private static float[] rootBox(Item pane) {
        float x = 0f;
        float y = 0f;
        Item node = pane;
        while (node != null) {
            if (!Boolean.TRUE.equals(node.visible.peek())) return null;
            x += node.x.peekFloat();
            y += node.y.peekFloat();
            Item up = node.parent.peek();
            if (up == null || up == node) break;
            node = up;
        }
        return new float[]{x, y, pane.width.peekFloat(), pane.height.peekFloat()};
    }

    /** Pack the effect's uniforms using the layout the compiler reported. */
    private void mix(float v) {
        sig = (sig ^ Float.floatToIntBits(v)) * 0x100000001B3L;
    }

    private Data uniforms(RuntimeEffect fx, Item pane, float uiScale, float x, float y,
                          float w, float h, float radius, int tint) {
        sig = 0xcbf29ce484222325L;
        mix(x);
        mix(y);
        mix(w);
        mix(h);
        mix(radius);
        sig = (sig ^ tint) * 0x100000001B3L;
        // Their lens values are absolute dp, not a fraction of the pane: a 24dp edge
        // band bends the same way on a toolbar and on a full-height sidebar. The
        // canvas is in device pixels here, so the amounts scale with the display.
        float lensBand = readFloat(pane, "glassLens", 24f) * uiScale;
        mix(lensBand);
        float lens = readFloat(pane, "glassRefraction", lensBand / Math.max(uiScale, 1e-3f)) * uiScale;
        float dispersion = readFloat(pane, "glassDispersion", 0f);
        mix(dispersion);
        float highlight = readFloat(pane, "glassHighlight", 0.32f);
        mix(highlight);
        float falloff = readFloat(pane, "glassFalloff", 1f);
        mix(falloff);
        // The reference light comes from 45 degrees; the normal of a pane's rim faces
        // outward, so this lights the top-left and bottom-right arcs.
        double angle = Math.toRadians(readFloat(pane, "glassLightAngle", 45f));
        float lx = (float) Math.cos(angle);
        float ly = (float) -Math.sin(angle);
        mix(lx);
        mix(ly);
        // The QML side exposes a press channel per pane (GlassPress/GlassShine):
        // `glassPressProgress` 0..1 plus the touch point in surface coordinates. Press
        // thickens the sheet — the reference scales its whole optic stack from zero
        // with progress, but scaling from zero here would delete the resting
        // refraction the chrome is tuned around, so it runs 0.7x .. 1.3x instead.
        float press = clamp(readFloat(pane, "glassPressProgress", 0f), 0f, 1f);
        mix(press);
        float lensScale = 0.7f + 0.6f * press;
        lensBand *= lensScale;
        lens *= lensScale;
        highlight *= lensScale;
        dispersion *= lensScale;

        float touchStrength = 0f;
        float touchX = x + w * 0.5f;
        float touchY = y + h * 0.5f;
        try {
            Object active = MemberAccess.readMember(pane, "glassTouchActive");
            if (Boolean.TRUE.equals(active)) {
                touchX = readFloat(pane, "glassTouchX", touchX / uiScale) * uiScale;
                touchY = readFloat(pane, "glassTouchY", touchY / uiScale) * uiScale;
                touchStrength = 1f;
                mix(touchX);
                mix(touchY);
            }
        } catch (Throwable ignored) { }
        mix(touchStrength);

        float band = Math.max(1f, Math.min(lensBand, Math.min(w, h) * 0.5f));
        float refraction = -Math.max(0f, Math.min(lens, Math.min(w, h) * 0.5f));
        mix(lens);

        byte[] buf = new byte[fx.getUniformSize()];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (RuntimeEffectUniformInfo u : fx.getUniforms()) {
            int off = u._offset;
            switch (u.getName()) {
                case "uCenter": put2(bb, off, x + w * 0.5f, y + h * 0.5f); break;
                case "uSize": put2(bb, off, w, h); break;
                case "uLight": put2(bb, off, lx, ly); break;
                case "uTouch": put2(bb, off, touchX, touchY); break;
                case "uTouchStrength": put1(bb, off, touchStrength); break;
                case "uHighlight": put1(bb, off, highlight); break;
                case "uFalloff": put1(bb, off, falloff); break;
                case "uBand": put1(bb, off, band); break;
                case "uRefraction": put1(bb, off, refraction); break;
                case "uDispersion": put1(bb, off, dispersion); break;
                case "uCorner": put1(bb, off, radius); break;
                case "uTint": {
                    float a = ((tint >>> 24) & 0xFF) / 255f;
                    put4(bb, off,
                            ((tint >>> 16) & 0xFF) / 255f,
                            ((tint >>> 8) & 0xFF) / 255f,
                            (tint & 0xFF) / 255f,
                            a);
                    break;
                }
                default: break;
            }
        }
        return Data.makeFromBytes(buf);
    }

    private RuntimeEffect effect() {
        if (effect != null) return effect;
        try {
            effect = RuntimeEffect.makeForShader(SKSL);
        } catch (Throwable t) {
            Logger.warn("glass shader failed to compile: {}", t.toString());
            say("shader failed: " + t);
            enabled = false;
        }
        return effect;
    }

    // ------------------------------------------------------------------ helpers

    private static float readFloat(Item pane, String name, float fallback) {
        try {
            Object v = MemberAccess.readMember(pane, name);
            if (v instanceof Number) return ((Number) v).floatValue();
        } catch (Throwable ignored) { }
        return fallback;
    }

    /** Read a QML colour property as ARGB. */
    private static int readColor(Item pane, String name, int fallback) {
        try {
            Object v = MemberAccess.readMember(pane, name);
            if (v == null) return fallback;
            return Renderer.parseColor(String.valueOf(v));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float luminance(int argb) {
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void put1(ByteBuffer bb, int off, float v) { bb.putFloat(off, v); }
    private static void put2(ByteBuffer bb, int off, float a, float b) {
        bb.putFloat(off, a); bb.putFloat(off + 4, b);
    }
    private static void put4(ByteBuffer bb, int off, float a, float b, float c, float d) {
        bb.putFloat(off, a); bb.putFloat(off + 4, b);
        bb.putFloat(off + 8, c); bb.putFloat(off + 12, d);
    }

    private static void close(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) { }
    }

    /**
     * Ported from {@code glsl-playground/liquid-glass.html} (MIT). The Siri-wave term
     * is dropped: it is a demo of a reactive light source, not a material, and the
     * design guidance is explicit that glass belongs to the control layer rather than
     * being decoration.
     */
    /**
     * The glass itself, ported from Kyant0's AndroidLiquidGlass / backdrop
     * (Apache-2.0, {@code backdrop/internal/Shaders.kt}):
     *
     * <ul>
     *   <li>the backdrop is passed through <em>untouched</em> everywhere except a band
     *       along the edge, so the material stays legible instead of becoming a wash;</li>
     *   <li>inside that band the sample is displaced along the rounded-rect normal by
     *       {@code circleMap(-sd / band) * refraction}, pulled inward (the reference sets
     *       a negative amount) — this is the whole of the "liquid" bend, with no zoom,
     *       dome, cap or equator band layered on top of it;</li>
     *   <li>the indicator is a second, thinner sheet: the same displacement split into
     *       seven spectral taps (red · orange · yellow · green · cyan · blue · purple)
     *       rather than three channels yanked apart, which is what keeps dispersion
     *       looking like a prism instead of a rainbow stripe;</li>
     *   <li>the rim light is directional — {@code pow(|dot(normal, light)|, falloff)}
     *       of Apple's own highlight, not a painted bevel;</li>
     *   <li>the material colour is a flat translucent layer over the result, the way the
     *       reference draws {@code containerColor} on the backdrop's surface.</li>
     * </ul>
     */
    private static final String SKSL = String.join("\n",
            "uniform shader uBlur;",
            "uniform float2 uCenter;",
            "uniform float2 uSize;",
            "uniform float  uCorner;",
            "uniform float  uRefraction;",
            "uniform float  uBand;",
            "uniform float  uDispersion;",
            "uniform float2 uLight;",
            "uniform float  uHighlight;",
            "uniform float  uFalloff;",
            "uniform float2 uTouch;",
            "uniform float  uTouchStrength;",
            "uniform float4 uTint;",
            "",
            "float sdRoundRect(float2 c, float2 half_, float r) {",
            "    float2 cc = abs(c) - (half_ - float2(r));",
            "    float outside = length(max(cc, float2(0.0))) - r;",
            "    float inside = min(max(cc.x, cc.y), 0.0);",
            "    return outside + inside;",
            "}",
            "float2 gradSdRoundRect(float2 c, float2 half_, float r) {",
            "    float2 cc = abs(c) - (half_ - float2(r));",
            "    if (cc.x >= 0.0 || cc.y >= 0.0) {",
            "        return sign(c) * normalize(max(cc, float2(1e-6)));",
            "    }",
            "    float gx = step(cc.y, cc.x);",
            "    return sign(c) * float2(gx, 1.0 - gx);",
            "}",
            "float circleMap(float x) { return 1.0 - sqrt(max(1.0 - x * x, 0.0)); }",
            "",
            "half4 main(float2 fc) {",
            "    float2 c = fc - uCenter;",
            "    float sd = sdRoundRect(c, uSize * 0.5, uCorner);",
            "    if (sd > 1.0) { return half4(0.0); }   // outside the shape: paint nothing",
            "    float2 grad = normalize(gradSdRoundRect(c, uSize * 0.5,",
            "            min(uCorner * 1.5, min(uSize.x * 0.5, uSize.y * 0.5))));",
            "    float3 col;",
            "    if (-sd >= uBand) {",
            "        col = uBlur.eval(fc).rgb;",
            "    } else {",
            "        float d = circleMap(clamp(-sd / max(uBand, 1.0), 0.0, 1.0)) * uRefraction;",
            "        float2 refracted = fc + grad * d;",
            "        if (uDispersion > 0.001) {",
            "            float2 dispersed = grad * d * uDispersion",
            "                    * ((c.x * c.y) / max(uSize.x * uSize.y * 0.25, 1.0));",
            "            col = float3(0.0);",
            "            half4 r = uBlur.eval(refracted + dispersed);",
            "            half4 o = uBlur.eval(refracted + dispersed * (2.0 / 3.0));",
            "            half4 y = uBlur.eval(refracted + dispersed * (1.0 / 3.0));",
            "            half4 g = uBlur.eval(refracted);",
            "            half4 cy = uBlur.eval(refracted - dispersed * (1.0 / 3.0));",
            "            half4 b = uBlur.eval(refracted - dispersed * (2.0 / 3.0));",
            "            half4 p = uBlur.eval(refracted - dispersed);",
            "            col.r += r.r / 3.5 + o.r / 3.5 + y.r / 3.5 + p.r / 7.0;",
            "            col.g += o.g / 7.0 + y.g / 3.5 + g.g / 3.5 + cy.g / 3.5;",
            "            col.b += cy.b / 3.0 + b.b / 3.0 + p.b / 3.0;",
            "        } else {",
            "            col = uBlur.eval(refracted).rgb;",
            "        }",
            "    }",
            "    float lum = dot(col, float3(0.213, 0.715, 0.072));",
            "    col = mix(float3(lum), col, 1.45);",
            "    col = mix(col, uTint.rgb, uTint.a);",
            "    float rimBand = smoothstep(1.5, 0.0, abs(sd + 1.0));",
            "    float lit = pow(abs(dot(grad, uLight)), uFalloff);",
            "    col += float3(1.0) * (rimBand * lit * uHighlight);",
            "    // Touch shine: the reference's InteractiveHighlight puts a soft specular",
            "    // bloom where the finger is (their fallback draws two concentric discs at",
            "    // 35% and 65%) and it follows the pointer. The QML side tracks the point and",
            "    // exposes it per pane; this is the half only a host can draw, because it has",
            "    // to sit on the material instead of inside it.",
            "    float dTouch = length(uTouch - fc);",
            "    float minDim = min(uSize.x, uSize.y);",
            "    float shineInner = 1.0 - smoothstep(0.0, minDim * 0.75, dTouch);",
            "    float shineOuter = 1.0 - smoothstep(0.0, minDim * 1.50, dTouch);",
            "    col += float3(1.0, 0.99, 0.98)",
            "            * ((shineInner * 0.35 + shineOuter * 0.65) * uTouchStrength);",
            "    float aa = smoothstep(1.5, -1.5, sd);",
            "    // Nothing is painted outside the rounded silhouette: the pane is drawn over",
            "    // the frame it was snapshotted from, so repainting the backdrop there (the",
            "    // old `mix(sharp, …)`) left a faint rectangular seam around the pane's box",
            "    // — a rounded capsule with a barely visible square edge. Returning a",
            "    // premultiplied alpha instead lets the existing pixels show through, which",
            "    // is exactly what \"outside the shape\" should mean on any backend.",
            "    return half4(col * aa, half(aa));",
            "}");
}
