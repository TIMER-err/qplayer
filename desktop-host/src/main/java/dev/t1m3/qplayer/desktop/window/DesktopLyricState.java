package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.i18n.I18n;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

/** qml4j context object owned and mutated only by the desktop-lyric thread. */
public final class DesktopLyricState extends QObject {

    static final String IDLE_PLACEHOLDER = I18n.tr("player.idle");

    public final Property<Boolean> playing = new Property<>(Boolean.FALSE);
    public final Property<Boolean> pointerInside = new Property<>(Boolean.FALSE);
    public final Property<Boolean> mousePassthrough = new Property<>(Boolean.FALSE);
    public final Property<String> surfaceColor = new Property<>("#28282b");
    public final Property<String> outlineColor = new Property<>("#49454f");
    public final Property<String> primaryColor = new Property<>("#d0bcff");
    public final Property<String> onSurfaceVariantColor = new Property<>("#cac4d0");
    public final Property<String> secondaryContainerColor = new Property<>("#4a4458");
    public final Property<String> onSecondaryContainerColor = new Property<>("#e8def8");

    private final DesktopLyricWindow owner;
    private LyricTimeline.Frame frame;
    private String fallbackText = "";
    private String fontFamily = "";
    private int fontSize = 26;
    private int fontWeight = 2;
    private boolean shadow = true;
    private boolean outline = true;
    private DesktopLyricPalette palette = DesktopLyricPalette.capture(true);
    private long positionMs;

    DesktopLyricState(DesktopLyricWindow owner) {
        this.owner = owner;
    }

    void update(DesktopLyricSnapshot snapshot, long nowNanos) {
        boolean active = snapshot.playing;
        positionMs = active ? snapshot.predictedPosition(nowNanos) : 0L;
        this.frame = active ? LyricTimeline.frameAt(snapshot.timeline, positionMs) : null;
        fallbackText = fallback(snapshot);
        fontFamily = snapshot.fontFamily;
        fontSize = Math.max(18, Math.min(38, snapshot.fontSize));
        fontWeight = Math.max(0, Math.min(3, snapshot.fontWeight));
        shadow = snapshot.shadow;
        outline = snapshot.outline;
        palette = snapshot.palette;
        playing.set(snapshot.playing);
        mousePassthrough.set(owner.isMousePassthrough());
        pointerInside.set(owner.isPointerInside() && !owner.isMousePassthrough());
        surfaceColor.set(palette.surface);
        outlineColor.set(palette.outline);
        primaryColor.set(palette.primary);
        onSurfaceVariantColor.set(palette.onSurfaceVariant);
        secondaryContainerColor.set(palette.secondaryContainer);
        onSecondaryContainerColor.set(palette.onSecondaryContainer);
    }

    long positionMs() {
        return positionMs;
    }

    LyricTimeline.Frame frame() {
        return frame;
    }

    String fallbackText() {
        return fallbackText;
    }

    int fontSizeValue() {
        return fontSize;
    }

    int fontWeightValue() {
        return fontWeight;
    }

    String fontFamilyValue() {
        return fontFamily;
    }

    boolean shadowValue() {
        return shadow;
    }

    boolean outlineValue() {
        return outline;
    }

    DesktopLyricPalette palette() {
        return palette;
    }

    public void previous() {
        owner.requestPrevious();
    }

    public void togglePlayback() {
        owner.requestTogglePlayback();
    }

    public void next() {
        owner.requestNext();
    }

    public void toggleMousePassthrough() {
        owner.requestToggleMousePassthrough();
    }

    public void openPlayer() {
        owner.requestOpenPlayer();
    }

    public void closeDesktopLyric() {
        owner.requestClose();
    }

    static String fallback(DesktopLyricSnapshot snapshot) {
        if (!snapshot.playing) return IDLE_PLACEHOLDER;
        if (snapshot.title.isEmpty()) return "";
        return snapshot.artist.isEmpty() ? snapshot.title : snapshot.title + "  ·  " + snapshot.artist;
    }
}
