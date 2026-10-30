package dev.t1m3.qplayer.desktop.media;

import dev.t1m3.qplayer.desktop.window.DesktopWindow;

import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.PlaybackSnapshot;
import dev.t1m3.qplayer.bridge.PlaybackSnapshot.TrackMetadata;
import dev.t1m3.qplayer.util.Logger;

import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows 10/11 System Media Transport Controls (SMTC), projected directly from
 * WinRT through JNA. GetForWindow binds the session to QPlayer's own HWND, so the
 * flyout and hardware media keys identify the real application rather than a
 * helper process.
 */
public final class WindowsMediaControls implements DesktopMediaControls {
    private static final int S_OK = 0;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int RO_INIT_MULTITHREADED = 1;

    private static final GUID IID_INTEROP =
            guid("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a");
    private static final GUID IID_SMTC =
            guid("99fa3ff4-1742-42a6-902e-087d41f965ec");
    private static final GUID IID_SMTC2 =
            guid("ea98d2f6-7f3c-4af2-a586-72889808efb1");
    private static final GUID IID_URI_FACTORY =
            guid("44a9796f-723e-4fdf-a218-033e75b0c084");
    private static final GUID IID_STREAM_REFERENCE_STATICS =
            guid("857309dc-3fbf-4e7d-986f-ef3b1a07a964");
    private static final GUID IID_IUNKNOWN =
            guid("00000000-0000-0000-c000-000000000046");
    private static final GUID IID_IAGILE_OBJECT =
            guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90");
    private static final GUID IID_BUTTON_HANDLER =
            guid("0557e996-7b23-5bae-aa81-ea0d671143a4");
    private static final GUID IID_POSITION_HANDLER =
            guid("44e34f15-bdc0-50a7-ace4-39e91fb753f1");
    private static final GUID IID_SHUFFLE_HANDLER =
            guid("17ecea80-27e4-5dae-abb4-c858ad1c5307");
    private static final GUID IID_REPEAT_HANDLER =
            guid("a6214bde-02d5-55b3-ab0d-c6031be70da1");

    interface Combase extends Library {
        int RoInitialize(int initType);
        int WindowsCreateString(WString source, int length, PointerByReference value);
        int WindowsDeleteString(Pointer value);
        int RoGetActivationFactory(Pointer classId, GUID iid, PointerByReference factory);
        int RoActivateInstance(Pointer classId, PointerByReference instance);
    }

    interface QueryInterfaceCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer iid, PointerByReference object);
    }
    interface RefCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self);
    }
    interface InvokeCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer sender, Pointer args);
    }

    private final PlayerController controller;
    private final DesktopWindow window;
    private final List<Object> keepAlive = new ArrayList<>();

    private Combase combase;
    private Pointer smtc;
    private Pointer smtc2;
    private Pointer timeline;
    private volatile boolean running;
    private Thread timelineThread;
    private String publishedMetadataKey;
    private com.sun.net.httpserver.HttpServer coverServer;
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> coverFiles =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.ArrayDeque<Integer> coverOrder = new java.util.ArrayDeque<>();
    private int coverCounter;
    private int coverPort;

    public WindowsMediaControls(PlayerController controller, DesktopWindow window) {
        this.controller = controller;
        this.window = window;
    }

    @Override
    public void start() {
        try {
            combase = Native.load("combase", Combase.class);
            int init = combase.RoInitialize(RO_INIT_MULTITHREADED);
            if (failed(init) && init != 0x80010106) check(init, "RoInitialize");

            Pointer factory = activationFactory(
                    "Windows.Media.SystemMediaTransportControls", IID_INTEROP);
            PointerByReference out = new PointerByReference();
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(window.window());
            check(call(factory, 6, Pointer.createConstant(hwnd), IID_SMTC.getPointer(), out),
                    "ISystemMediaTransportControlsInterop.GetForWindow");
            smtc = out.getValue();
            release(factory);
            smtc2 = query(smtc, IID_SMTC2);

            configureButtons();
            installHandlers();
            check(call(smtc, 11, (byte) 1), "SetIsEnabled");
            timeline = activate(
                    "Windows.Media.SystemMediaTransportControlsTimelineProperties");
            running = true;
            startCoverServer();
            publish();
            startTimelineUpdates();
            Logger.info("system media controls initialized: Windows SMTC");
        } catch (Throwable t) {
            Logger.warn("Windows system media controls unavailable: {}", t);
            shutdown();
        }
    }

    @Override
    public void shutdown() {
        running = false;
        if (timelineThread != null) timelineThread.interrupt();
        timelineThread = null;
        try {
            if (smtc != null) {
                call(smtc, 7, 0);  // Closed
                call(smtc, 11, (byte) 0);
                release(smtc);
            }
            if (smtc2 != null) release(smtc2);
            if (timeline != null) release(timeline);
        } catch (Throwable ignored) {
        }
        if (coverServer != null) {
            coverServer.stop(0);
            coverServer = null;
        }
        coverFiles.clear();
        coverOrder.clear();
        smtc = null;
        smtc2 = null;
        timeline = null;
        publishedMetadataKey = null;
        keepAlive.clear();
    }

    @Override
    public void onPlaybackChanged() {
        publish();
    }

    private void configureButtons() {
        check(call(smtc, 13, (byte) 1), "SetIsPlayEnabled");
        check(call(smtc, 17, (byte) 1), "SetIsPauseEnabled");
        check(call(smtc, 25, (byte) 1), "SetIsPreviousEnabled");
        check(call(smtc, 27, (byte) 1), "SetIsNextEnabled");
    }

    private void installHandlers() {
        Pointer buttonHandler = handler(IID_BUTTON_HANDLER, (sender, args) -> {
            IntByReference button = new IntByReference();
            check(call(args, 6, button), "Button");
            switch (button.getValue()) {
                case 0:
                    if (!controller.isPlaying()) window.postMainTask(controller::toggle);
                    break;
                case 1:
                case 2:
                    if (controller.isPlaying()) window.postMainTask(controller::toggle);
                    break;
                case 6:
                    window.postMainTask(controller::next);
                    break;
                case 7:
                    window.postMainTask(controller::prev);
                    break;
                default:
                    break;
            }
        });
        check(call(smtc, 32, buttonHandler, new LongByReference()), "ButtonPressed");

        if (smtc2 == null) return;
        Pointer seekHandler = handler(IID_POSITION_HANDLER, (sender, args) -> {
            LongByReference ticks = new LongByReference();
            check(call(args, 6, ticks), "RequestedPlaybackPosition");
            long ms = Math.max(0L, ticks.getValue() / 10_000L);
            window.postMainTask(() -> {
                controller.seek(ms);
                publish();
            });
        });
        registerOptionalEvent(smtc2, 13, seekHandler,
                "PlaybackPositionChangeRequested");

        Pointer shuffleHandler = handler(IID_SHUFFLE_HANDLER, (sender, args) -> {
            ByteByReference enabled = new ByteByReference();
            check(call(args, 6, enabled), "RequestedShuffleEnabled");
            window.postMainTask(() -> {
                if (enabled.getValue() != 0) controller.setPlayMode(1);
                else if (playMode() == 1) controller.setPlayMode(0);
            });
        });
        registerOptionalEvent(smtc2, 17, shuffleHandler,
                "ShuffleEnabledChangeRequested");

        Pointer repeatHandler = handler(IID_REPEAT_HANDLER, (sender, args) -> {
            IntByReference mode = new IntByReference();
            check(call(args, 6, mode), "RequestedAutoRepeatMode");
            window.postMainTask(() -> controller.setPlayMode(
                    mode.getValue() == 1 ? 2 : 0));
        });
        registerOptionalEvent(smtc2, 19, repeatHandler,
                "AutoRepeatModeChangeRequested");
    }

    /**
     * Some desktop SMTC implementations expose ISystemMediaTransportControls2
     * but reject one or more of its request events with CO_E_NOT_SUPPORTED.
     * Those events only add seek/shuffle/repeat commands; they must not disable
     * the base session, metadata, or play/pause/next/previous controls.
     */
    private void registerOptionalEvent(Pointer source, int method, Pointer handler,
                                       String name) {
        int hr = call(source, method, handler, new LongByReference());
        if (failed(hr)) {
            Logger.warn("Windows SMTC optional event unavailable: {} (0x{})",
                    name, Integer.toHexString(hr));
        }
    }

    private interface EventBody {
        void invoke(Pointer sender, Pointer args);
    }

    /** Minimal WinRT TypedEventHandler COM object. */
    private Pointer handler(GUID delegateIid, EventBody body) {
        AtomicInteger refs = new AtomicInteger(1);
        Memory object = new Memory(Native.POINTER_SIZE);
        // WinRT delegate interfaces derive directly from IUnknown. Their ABI is
        // QueryInterface/AddRef/Release/Invoke, without IInspectable's three
        // GetIids/GetRuntimeClassName/GetTrustLevel entries.
        Memory vtable = new Memory((long) Native.POINTER_SIZE * 4);

        QueryInterfaceCallback qi = (self, iid, out) -> {
            GUID requested = new GUID(iid);
            if (!requested.equals(IID_IUNKNOWN)
                    && !requested.equals(IID_IAGILE_OBJECT)
                    && !requested.equals(delegateIid)) {
                out.setValue(Pointer.NULL);
                return E_NOINTERFACE;
            }
            out.setValue(self);
            refs.incrementAndGet();
            return S_OK;
        };
        RefCallback addRef = self -> refs.incrementAndGet();
        RefCallback release = self -> Math.max(0, refs.decrementAndGet());
        InvokeCallback invoke = (self, sender, args) -> {
            try {
                body.invoke(sender, args);
                return S_OK;
            } catch (Throwable t) {
                Logger.warn("Windows media command failed: {}", t);
                return 0x80004005;
            }
        };
        Object[] callbacks = {qi, addRef, release, invoke};
        for (int i = 0; i < callbacks.length; i++) {
            vtable.setPointer((long) i * Native.POINTER_SIZE,
                    CallbackReference.getFunctionPointer((com.sun.jna.Callback) callbacks[i]));
        }
        object.setPointer(0, vtable);
        keepAlive.add(object);
        keepAlive.add(vtable);
        java.util.Collections.addAll(keepAlive, callbacks);
        return object;
    }

    private void publish() {
        if (!running || smtc == null) return;
        try {
            PlaybackSnapshot snapshot = controller.playbackSnapshot();
            TrackMetadata track = snapshot.track();
            if (track == null) {
                // Closed is terminal for a SMTC session. At startup there is no
                // current track yet, but the session must remain available for a
                // later play request, so advertise Stopped and reserve Closed for
                // shutdown().
                check(call(smtc, 7, 2), "SetPlaybackStatus(Stopped)");
                return;
            }
            String metadataKey = metadataKey(track);
            if (!metadataKey.equals(publishedMetadataKey)) {
                updateMetadata(track);
                publishedMetadataKey = metadataKey;
            }
            call(smtc, 7, snapshot.playing() ? 3 : 4);

            if (smtc2 != null) {
                int mode = playMode();
                call(smtc2, 7, mode == 2 ? 1 : 2); // Track / List
                call(smtc2, 9, (byte) (mode == 1 ? 1 : 0));
                // A non-zero playback rate tells SMTC consumers to extrapolate
                // Position continuously between our periodic timeline syncs.
                check(call(smtc2, 11, 1.0d), "SetPlaybackRate");
                updateTimeline(snapshot);
            }
        } catch (Throwable t) {
            Logger.warn("Windows SMTC update failed: {}", t);
        }
    }

    private void updateMetadata(TrackMetadata track) {
        PointerByReference updaterOut = new PointerByReference();
        check(call(smtc, 8, updaterOut), "DisplayUpdater");
        Pointer updater = updaterOut.getValue();
        try {
            call(updater, 16);       // ClearAll
            call(updater, 7, 1);     // MediaPlaybackType.Music
            PointerByReference musicOut = new PointerByReference();
            check(call(updater, 12, musicOut), "MusicProperties");
            Pointer music = musicOut.getValue();
            try {
                setHString(music, 7, track.title());
                setHString(music, 11, track.artist());
                Pointer music2 = query(music,
                        guid("00368462-97d3-44b9-b00f-008afcefaf18"));
                if (music2 != null) {
                    try {
                        setHString(music2, 7, track.album());
                    } finally {
                        release(music2);
                    }
                }
            } finally {
                release(music);
            }
            Pointer thumbnail = resolveThumbnail(track);
            if (thumbnail != null) {
                try {
                    check(call(updater, 11, thumbnail), "SetThumbnail");
                } finally {
                    release(thumbnail);
                }
            }
            check(call(updater, 17), "DisplayUpdater.Update");
        } finally {
            release(updater);
        }
    }

    /** A local file (already-cached netease cover, or a local track's own art)
     *  wins over the remote URL -- mirrors MacMediaControls.localCover: a local
     *  track has no coverUrl at all, and a played-before netease track's cover is
     *  already on disk, no network needed. WinRT's Uri.CreateUri accepts a plain
     *  file:/... URI same as http(s), so this reuses the same CreateFromUri call
     *  below rather than a separate StorageFile path. */
    private String localCoverPath(TrackMetadata track) {
        String cached = track.coverPath();
        return cached != null && !cached.isEmpty() && new java.io.File(cached).isFile() ? cached : null;
    }

    private Pointer resolveThumbnail(TrackMetadata track) {
        // Prefer the local cover file (already-cached netease cover, or a local
        // track's own art), falling back to the remote http(s) URL.
        String local = localCoverPath(track);
        if (local != null) {
            String url = localCoverUrl(local);
            if (url != null) return thumbnailFromUri(url);
        }
        String url = track.coverUrl();
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            return thumbnailFromUri(url);
        }
        return null;
    }

    /** SMTC's thumbnail renderer drops RandomAccessStreamReference streams built
     *  from file: URIs entirely (any slashing or extension), but happily renders
     *  an http(s) URL, so the cover caches' ".img" files are exposed through a
     *  127.0.0.1 loopback endpoint carrying the sniffed Content-Type instead. */
    private String localCoverUrl(String path) {
        if (coverServer == null) return null;
        int id = ++coverCounter;
        coverFiles.put(id, path);
        coverOrder.addLast(id);
        while (coverOrder.size() > 32) {
            Integer oldest = coverOrder.pollFirst();
            coverFiles.remove(oldest);
        }
        return "http://127.0.0.1:" + coverPort + "/cover/" + id;
    }

    private void startCoverServer() {
        try {
            coverServer = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            coverPort = coverServer.getAddress().getPort();
            coverServer.createContext("/cover/", exchange -> {
                try {
                    String idStr = exchange.getRequestURI().getPath().substring("/cover/".length());
                    String path = coverFiles.get(Integer.parseInt(idStr));
                    byte[] bytes = path != null
                            ? java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)) : null;
                    if (bytes == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.getResponseHeaders().add("Content-Type", contentType(path));
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                } catch (Throwable t) {
                    try {
                        exchange.sendResponseHeaders(500, -1);
                    } catch (Throwable ignored) {
                    }
                } finally {
                    exchange.close();
                }
            });
            coverServer.start();
            Logger.info("SMTC local cover server on 127.0.0.1:{}", coverPort);
        } catch (Throwable t) {
            Logger.warn("SMTC local cover server unavailable: {}", t.getMessage());
            coverServer = null;
        }
    }

    private String contentType(String path) {
        String ext = sniffImageExtension(path);
        if (ext == null) return "application/octet-stream";
        switch (ext) {
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            default:
                return "image/jpeg";
        }
    }

    /** Content-type sniff for the cover caches' ".img" files (usually JPEG). */
    private String sniffImageExtension(String path) {
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            byte[] h = new byte[12];
            int n = in.read(h);
            if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return "jpg";
            if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
            if (n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "gif";
            if (n >= 2 && h[0] == 'B' && h[1] == 'M') return "bmp";
            if (n >= 12 && (h[0] & 0xFF) == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "webp";
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Pointer thumbnailFromUri(String url) {
        Pointer uriFactory = null;
        Pointer streamFactory = null;
        Pointer uri = null;
        try {
            uriFactory = activationFactory("Windows.Foundation.Uri", IID_URI_FACTORY);
            Pointer text = hstring(url);
            PointerByReference uriOut = new PointerByReference();
            try {
                check(call(uriFactory, 6, text, uriOut), "Uri.CreateUri");
            } finally {
                combase.WindowsDeleteString(text);
            }
            uri = uriOut.getValue();
            streamFactory = activationFactory(
                    "Windows.Storage.Streams.RandomAccessStreamReference",
                    IID_STREAM_REFERENCE_STATICS);
            PointerByReference streamOut = new PointerByReference();
            check(call(streamFactory, 7, uri, streamOut),
                    "RandomAccessStreamReference.CreateFromUri");
            return streamOut.getValue();
        } catch (Throwable t) {
            Logger.warn("Windows SMTC artwork unavailable: {}", t.getMessage());
            return null;
        } finally {
            release(uri);
            release(uriFactory);
            release(streamFactory);
        }
    }

    private void updateTimeline(PlaybackSnapshot snapshot) {
        if (timeline == null) return;
        long durationTicks = snapshot.durationMs() * 10_000L;
        long positionTicks = Math.max(0L, snapshot.positionMs()) * 10_000L;
        call(timeline, 7, 0L);                // StartTime
        call(timeline, 9, durationTicks);     // EndTime
        call(timeline, 11, 0L);               // MinSeekTime
        call(timeline, 13, durationTicks);    // MaxSeekTime
        call(timeline, 15, positionTicks);    // Position
        check(call(smtc2, 12, timeline), "UpdateTimelineProperties");
    }


    private String metadataKey(TrackMetadata track) {
        return track.canonicalId() + '\0' + String.valueOf(track.title()) + '\0'
                + String.valueOf(track.artist()) + '\0'
                + String.valueOf(track.album()) + '\0'
                + String.valueOf(track.coverUrl()) + '\0'
                + track.coverPath();
    }

    private void startTimelineUpdates() {
        if (smtc2 == null || timeline == null) return;
        timelineThread = new Thread(() -> {
            while (running) {
                try {
                    // Some third-party Windows media panels do not extrapolate
                    // Position from PlaybackRate, so provide a smooth live value.
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) return;
                window.postMainTask(() -> {
                    PlaybackSnapshot snapshot = controller.playbackSnapshot();
                    if (running && snapshot.track() != null) {
                        try {
                            updateTimeline(snapshot);
                        } catch (Throwable t) {
                            Logger.warn("Windows SMTC timeline update failed: {}", t);
                        }
                    }
                });
            }
        }, "qplayer-smtc-timeline");
        timelineThread.setDaemon(true);
        timelineThread.start();
    }

    private int playMode() {
        Integer value = controller.playMode.peek();
        return value != null ? value : 0;
    }

    private void setHString(Pointer object, int method, String value) {
        if (value == null) value = "";
        Pointer hstring = hstring(value);
        try {
            check(call(object, method, hstring), "set metadata string");
        } finally {
            combase.WindowsDeleteString(hstring);
        }
    }

    private Pointer activationFactory(String className, GUID iid) {
        Pointer name = hstring(className);
        try {
            PointerByReference out = new PointerByReference();
            check(combase.RoGetActivationFactory(name, iid, out), "RoGetActivationFactory");
            return out.getValue();
        } finally {
            combase.WindowsDeleteString(name);
        }
    }

    private Pointer activate(String className) {
        Pointer name = hstring(className);
        try {
            PointerByReference out = new PointerByReference();
            check(combase.RoActivateInstance(name, out), "RoActivateInstance");
            return out.getValue();
        } finally {
            combase.WindowsDeleteString(name);
        }
    }

    private Pointer hstring(String text) {
        PointerByReference out = new PointerByReference();
        check(combase.WindowsCreateString(new WString(text), text.length(), out),
                "WindowsCreateString");
        return out.getValue();
    }

    private static Pointer query(Pointer object, GUID iid) {
        PointerByReference out = new PointerByReference();
        int hr = call(object, 0, iid.getPointer(), out);
        return failed(hr) ? null : out.getValue();
    }

    private static void release(Pointer object) {
        if (object != null) call(object, 2);
    }

    private static int call(Pointer object, int method, Object... args) {
        if (object == null) return E_NOINTERFACE;
        Pointer vtable = object.getPointer(0);
        Pointer address = vtable.getPointer((long) method * Native.POINTER_SIZE);
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return (Integer) Function.getFunction(address, Function.ALT_CONVENTION)
                .invoke(int.class, all);
    }

    private static boolean failed(int hr) {
        return hr < 0;
    }

    private static void check(int hr, String operation) {
        if (failed(hr)) {
            throw new IllegalStateException(operation + " failed: 0x"
                    + Integer.toHexString(hr));
        }
    }

    private static GUID guid(String value) {
        GUID guid = new GUID(value);
        guid.write();
        return guid;
    }
}
