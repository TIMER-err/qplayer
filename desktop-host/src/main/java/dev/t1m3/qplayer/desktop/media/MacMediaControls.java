package dev.t1m3.qplayer.desktop.media;

import dev.t1m3.qplayer.desktop.window.DesktopWindow;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS Control Center / menu-bar Now Playing integration through MediaPlayer.
 *
 * <p>The Objective-C runtime is used directly through JNA, without a Java UI
 * toolkit. MediaPlayer supplies both halves of the
 * native contract: MPNowPlayingInfoCenter publishes metadata/timeline state and
 * MPRemoteCommandCenter routes system buttons back to PlayerController.
 */
public final class MacMediaControls implements DesktopMediaControls {
    private static final String MEDIA_PLAYER =
            "/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer";
    private static final String APP_KIT =
            "/System/Library/Frameworks/AppKit.framework/AppKit";

    interface ObjC extends Library {
        Pointer objc_getClass(String name);
        Pointer sel_registerName(String name);
        Pointer objc_allocateClassPair(Pointer superclass, String name, long extraBytes);
        void objc_registerClassPair(Pointer cls);
        boolean class_addMethod(Pointer cls, Pointer selector, Callback implementation,
                                String types);
    }

    interface ActionCallback extends Callback {
        long invoke(Pointer self, Pointer selector, Pointer event);
    }

    private final PlayerController controller;
    private final DesktopWindow window;
    private final List<Callback> keepAlive = new ArrayList<>();

    private ObjC objc;
    private NativeLibrary objcLib;
    private NativeLibrary mediaPlayerLib;
    private Pointer center;
    private Pointer commandCenter;
    private Pointer target;
    private volatile boolean running;
    private volatile long pausedPositionMs;

    public MacMediaControls(PlayerController controller, DesktopWindow window) {
        this.controller = controller;
        this.window = window;
    }

    @Override
    public void start() {
        try {
            NativeLibrary.getInstance(APP_KIT);
            mediaPlayerLib = NativeLibrary.getInstance(MEDIA_PLAYER);
            objcLib = NativeLibrary.getInstance("objc");
            objc = Native.load("objc", ObjC.class);

            center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter");
            commandCenter = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter");
            installCommandTarget();
            running = center != null && commandCenter != null;
            if (running) {
                publish();
                Logger.info("system media controls initialized: macOS MediaPlayer");
            }
        } catch (Throwable t) {
            Logger.warn("macOS system media controls unavailable: {}", t);
        }
    }

    @Override
    public void shutdown() {
        running = false;
        try {
            if (center != null) {
                sendVoid(center, "setNowPlayingInfo:", Pointer.NULL);
                sendVoid(center, "setPlaybackState:", 0L);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPlaybackChanged() {
        if (!controller.isPlaying()) pausedPositionMs = Math.max(0L, controller.position());
        publish();
    }

    private void installCommandTarget() {
        Pointer superClass = cls("NSObject");
        Pointer targetClass = objc.objc_allocateClassPair(superClass,
                "QPlayerRemoteCommandTarget", 0);
        boolean newlyAllocated = targetClass != null;
        if (!newlyAllocated) targetClass = cls("QPlayerRemoteCommandTarget");
        if (targetClass == null) throw new IllegalStateException("cannot create command target");

        addAction(targetClass, "qplayerPlay:", event -> {
            if (!controller.isPlaying()) window.postMainTask(controller::toggle);
        });
        addAction(targetClass, "qplayerPause:", event -> {
            if (controller.isPlaying()) window.postMainTask(controller::toggle);
        });
        addAction(targetClass, "qplayerToggle:", event ->
                window.postMainTask(controller::toggle));
        addAction(targetClass, "qplayerNext:", event ->
                window.postMainTask(controller::next));
        addAction(targetClass, "qplayerPrevious:", event ->
                window.postMainTask(controller::prev));
        addAction(targetClass, "qplayerPosition:", event -> {
            double seconds = sendDouble(event, "positionTime");
            long ms = Math.max(0L, Math.round(seconds * 1000.0));
            window.postMainTask(() -> {
                controller.seek(ms);
                pausedPositionMs = ms;
                publish();
            });
        });
        addAction(targetClass, "qplayerShuffle:", event -> {
            long type = sendLong(event, "shuffleType");
            window.postMainTask(() -> controller.setPlayMode(type != 0 ? 1 : 0));
        });
        addAction(targetClass, "qplayerRepeat:", event -> {
            long type = sendLong(event, "repeatType");
            window.postMainTask(() -> controller.setPlayMode(type == 1 ? 2 : 0));
        });

        // objc_registerClassPair must only run for the newly allocated class.
        // Looking it up above returns null until registration.
        if (newlyAllocated) objc.objc_registerClassPair(targetClass);
        target = send(send(targetClass, "alloc"), "init");

        bind("playCommand", "qplayerPlay:");
        bind("pauseCommand", "qplayerPause:");
        bind("togglePlayPauseCommand", "qplayerToggle:");
        bind("nextTrackCommand", "qplayerNext:");
        bind("previousTrackCommand", "qplayerPrevious:");
        bind("changePlaybackPositionCommand", "qplayerPosition:");
        bind("changeShuffleModeCommand", "qplayerShuffle:");
        bind("changeRepeatModeCommand", "qplayerRepeat:");
    }

    private interface EventAction {
        void run(Pointer event);
    }

    private void addAction(Pointer targetClass, String selectorName, EventAction action) {
        ActionCallback callback = (self, selector, event) -> {
            try {
                action.run(event);
                return 0L; // MPRemoteCommandHandlerStatusSuccess
            } catch (Throwable t) {
                Logger.warn("macOS media command failed: {}", t);
                return 200L; // MPRemoteCommandHandlerStatusCommandFailed
            }
        };
        keepAlive.add(callback);
        if (!objc.class_addMethod(targetClass, sel(selectorName), callback, "q@:@")) {
            Logger.warn("macOS media command selector already exists: {}", selectorName);
        }
    }

    private void bind(String commandSelector, String actionSelector) {
        Pointer command = send(commandCenter, commandSelector);
        if (command == null) return;
        sendVoid(command, "setEnabled:", true);
        send(command, "addTarget:action:", target, sel(actionSelector));
    }

    private void publish() {
        if (!running || center == null) return;
        try {
            Track track = controller.currentTrack();
            if (track == null) {
                sendVoid(center, "setNowPlayingInfo:", Pointer.NULL);
                sendVoid(center, "setPlaybackState:", 0L);
                return;
            }

            Pointer dict = send(cls("NSMutableDictionary"), "dictionary");
            put(dict, constant("MPMediaItemPropertyTitle"), string(track.title));
            put(dict, constant("MPMediaItemPropertyArtist"), string(track.artist));
            put(dict, constant("MPMediaItemPropertyAlbumTitle"), string(track.album));
            put(dict, constant("MPMediaItemPropertyPlaybackDuration"),
                    number(Math.max(0L, duration(track)) / 1000.0));
            put(dict, constant("MPNowPlayingInfoPropertyElapsedPlaybackTime"),
                    number(positionMs() / 1000.0));
            put(dict, constant("MPNowPlayingInfoPropertyPlaybackRate"),
                    number(controller.isPlaying() ? 1.0 : 0.0));

            Pointer artwork = artwork(track);
            if (artwork != null) put(dict, constant("MPMediaItemPropertyArtwork"), artwork);

            sendVoid(center, "setNowPlayingInfo:", dict);
            sendVoid(center, "setPlaybackState:", controller.isPlaying() ? 1L : 2L);
            int mode = playMode();
            sendVoid(send(commandCenter, "changeShuffleModeCommand"),
                    "setCurrentShuffleType:", mode == 1 ? 1L : 0L);
            sendVoid(send(commandCenter, "changeRepeatModeCommand"),
                    "setCurrentRepeatType:", mode == 2 ? 1L : 2L);
        } catch (Throwable t) {
            Logger.warn("macOS now-playing update failed: {}", t);
        }
    }

    private Pointer artwork(Track track) {
        String path = localCover(track);
        if (path == null) return null;
        Pointer image = send(send(cls("NSImage"), "alloc"),
                "initWithContentsOfFile:", string(path));
        if (image == null) return null;
        return send(send(cls("MPMediaItemArtwork"), "alloc"), "initWithImage:", image);
    }

    private String localCover(Track track) {
        String cached = controller.currentCoverPath();
        if (cached != null && !cached.isEmpty() && new File(cached).isFile()) return cached;
        for (String path : new String[]{track.coverLocalPath, track.coverThumbPath}) {
            if (path != null && !path.startsWith("http") && new File(path).isFile()) return path;
        }
        return null;
    }

    private long duration(Track track) {
        return track.durationMs > 0 ? track.durationMs : controller.duration();
    }

    private long positionMs() {
        return controller.isPlaying() ? Math.max(0L, controller.position()) : pausedPositionMs;
    }

    private int playMode() {
        Integer value = controller.playMode.peek();
        return value != null ? value : 0;
    }

    private void put(Pointer dict, Pointer key, Pointer value) {
        if (dict != null && key != null && value != null) {
            sendVoid(dict, "setObject:forKey:", value, key);
        }
    }

    private Pointer constant(String name) {
        Pointer address = mediaPlayerLib.getGlobalVariableAddress(name);
        return address != null ? address.getPointer(0) : null;
    }

    private Pointer string(String value) {
        if (value == null || value.isEmpty()) return null;
        return send(cls("NSString"), "stringWithUTF8String:", value);
    }

    private Pointer number(double value) {
        return send(cls("NSNumber"), "numberWithDouble:", value);
    }

    private Pointer cls(String name) {
        return objc.objc_getClass(name);
    }

    private Pointer sel(String name) {
        return objc.sel_registerName(name);
    }

    private Pointer send(Pointer receiver, String selector, Object... args) {
        if (receiver == null) return null;
        List<Object> call = new ArrayList<>();
        call.add(receiver);
        call.add(sel(selector));
        java.util.Collections.addAll(call, args);
        return (Pointer) objcLib.getFunction("objc_msgSend")
                .invoke(Pointer.class, call.toArray());
    }

    private void sendVoid(Pointer receiver, String selector, Object... args) {
        if (receiver == null) return;
        List<Object> call = new ArrayList<>();
        call.add(receiver);
        call.add(sel(selector));
        java.util.Collections.addAll(call, args);
        objcLib.getFunction("objc_msgSend").invokeVoid(call.toArray());
    }

    private long sendLong(Pointer receiver, String selector) {
        if (receiver == null) return 0L;
        return (Long) objcLib.getFunction("objc_msgSend")
                .invoke(long.class, new Object[]{receiver, sel(selector)});
    }

    private double sendDouble(Pointer receiver, String selector) {
        if (receiver == null) return 0.0;
        return (Double) objcLib.getFunction("objc_msgSend")
                .invoke(double.class, new Object[]{receiver, sel(selector)});
    }
}
