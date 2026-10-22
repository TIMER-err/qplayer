package dev.t1m3.qplayer.desktop.app;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import dev.t1m3.qplayer.util.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * AppKit file panels for macOS. GLFW already owns {@code NSApplication};
 * Swing's {@code JFileChooser} would boot AWT and nest {@code NSApplication.run}
 * inside the GLFW wait (issue #61). {@code runModal} must run on the main thread.
 */
final class MacNativeDialog {

    private static final String APP_KIT =
            "/System/Library/Frameworks/AppKit.framework/AppKit";
    private static final String FOUNDATION =
            "/System/Library/Frameworks/Foundation.framework/Foundation";
    private static final long MODAL_OK = 1L;

    interface ObjC extends Library {
        Pointer objc_getClass(String name);
        Pointer sel_registerName(String name);
    }

    private static ObjC objc;
    private static NativeLibrary objcLib;

    private MacNativeDialog() {}

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    static String open(String title, String initialDirectory, boolean directoryOnly,
                       String[] extensions) {
        try {
            ensureLoaded();
            Pointer panel = send(cls("NSOpenPanel"), "openPanel");
            if (panel == null) return null;
            configure(panel, title, initialDirectory, directoryOnly, !directoryOnly, extensions);
            sendVoid(panel, "setAllowsMultipleSelection:", false);
            sendVoid(panel, "setCanCreateDirectories:", directoryOnly);
            if (sendLong(panel, "runModal") != MODAL_OK) return null;
            return pathOf(send(panel, "URL"));
        } catch (Throwable t) {
            Logger.warn("macOS open panel failed: {}", t);
            return null;
        }
    }

    static String save(String title, String initialDirectory, String suggestedFileName,
                       String[] extensions) {
        try {
            ensureLoaded();
            Pointer panel = send(cls("NSSavePanel"), "savePanel");
            if (panel == null) return null;
            configure(panel, title, initialDirectory, false, false, extensions);
            sendVoid(panel, "setCanCreateDirectories:", true);
            if (suggestedFileName != null && !suggestedFileName.isEmpty()) {
                sendVoid(panel, "setNameFieldStringValue:", nsString(suggestedFileName));
            }
            if (sendLong(panel, "runModal") != MODAL_OK) return null;
            return pathOf(send(panel, "URL"));
        } catch (Throwable t) {
            Logger.warn("macOS save panel failed: {}", t);
            return null;
        }
    }

    private static void configure(Pointer panel, String title, String initialDirectory,
                                  boolean directories, boolean files, String[] extensions) {
        if (title != null && !title.isEmpty()) sendVoid(panel, "setTitle:", nsString(title));
        sendVoid(panel, "setCanChooseDirectories:", directories);
        sendVoid(panel, "setCanChooseFiles:", files);
        if (initialDirectory != null && !initialDirectory.isEmpty()) {
            Pointer url = send(cls("NSURL"), "fileURLWithPath:isDirectory:",
                    nsString(initialDirectory), true);
            if (url != null) sendVoid(panel, "setDirectoryURL:", url);
        }
        if (extensions != null && extensions.length > 0) {
            Pointer types = send(cls("NSMutableArray"), "array");
            for (String extension : extensions) {
                if (extension == null || extension.isEmpty()) continue;
                sendVoid(types, "addObject:", nsString(extension));
            }
            sendVoid(panel, "setAllowedFileTypes:", types);
        }
    }

    private static String pathOf(Pointer url) {
        if (url == null) return null;
        Pointer path = send(url, "path");
        if (path == null) return null;
        Pointer utf8 = send(path, "UTF8String");
        return utf8 == null ? null : utf8.getString(0, "UTF-8");
    }

    private static synchronized void ensureLoaded() {
        if (objc != null) return;
        NativeLibrary.getInstance(FOUNDATION);
        NativeLibrary.getInstance(APP_KIT);
        objcLib = NativeLibrary.getInstance("objc");
        objc = Native.load("objc", ObjC.class);
    }

    private static Pointer nsString(String value) {
        return send(cls("NSString"), "stringWithUTF8String:", value == null ? "" : value);
    }

    private static Pointer cls(String name) {
        return objc.objc_getClass(name);
    }

    private static Pointer sel(String name) {
        return objc.sel_registerName(name);
    }

    private static Pointer send(Pointer receiver, String selector, Object... args) {
        if (receiver == null) return null;
        List<Object> call = new ArrayList<>();
        call.add(receiver);
        call.add(sel(selector));
        java.util.Collections.addAll(call, args);
        return (Pointer) objcLib.getFunction("objc_msgSend")
                .invoke(Pointer.class, call.toArray());
    }

    private static void sendVoid(Pointer receiver, String selector, Object... args) {
        if (receiver == null) return;
        List<Object> call = new ArrayList<>();
        call.add(receiver);
        call.add(sel(selector));
        java.util.Collections.addAll(call, args);
        objcLib.getFunction("objc_msgSend").invokeVoid(call.toArray());
    }

    private static long sendLong(Pointer receiver, String selector) {
        if (receiver == null) return 0L;
        return (Long) objcLib.getFunction("objc_msgSend")
                .invoke(long.class, new Object[]{receiver, sel(selector)});
    }
}
