package dev.t1m3.qplayer.desktop.tray;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import dev.t1m3.qplayer.util.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * macOS menu-bar extra driven through AppKit's {@code NSStatusItem} via JNA.
 *
 * <p>GLFW already owns thread 0 / {@code NSApplication}. Creating an AWT
 * {@code SystemTray} on that same process starts a nested
 * {@code NSApplication.run} inside GLFW's wait, so the main window never
 * shows (issue #61). This backend talks to AppKit directly without starting
 * another toolkit event loop.
 *
 * <p>Must be installed on the GLFW main thread.
 */
final class MacTray {

    private static final String APP_KIT =
            "/System/Library/Frameworks/AppKit.framework/AppKit";
    private static final String FOUNDATION =
            "/System/Library/Frameworks/Foundation.framework/Foundation";
    /** {@code NSSquareStatusItemLength}. */
    private static final double SQUARE_LENGTH = -2.0;

    interface ObjC extends Library {
        Pointer objc_getClass(String name);
        Pointer object_getClass(Pointer object);
        Pointer sel_registerName(String name);
        Pointer objc_allocateClassPair(Pointer superclass, String name, long extraBytes);
        void objc_registerClassPair(Pointer cls);
        boolean class_addMethod(Pointer cls, Pointer selector, Callback implementation,
                                String types);
    }

    interface ActionCallback extends Callback {
        void invoke(Pointer self, Pointer selector, Pointer sender);
    }

    interface ReopenCallback extends Callback {
        byte invoke(Pointer self, Pointer selector, Pointer sender, byte hasVisibleWindows);
    }

    private static final class Item {
        final int tag;
        final boolean separator;
        String label;
        final Runnable action;
        Pointer menuItem;

        Item(int tag, String label, Runnable action, boolean separator) {
            this.tag = tag;
            this.label = label;
            this.action = action;
            this.separator = separator;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Callback> keepAlive = new ArrayList<>();
    private final List<Memory> pinned = new ArrayList<>();

    private ObjC objc;
    private NativeLibrary objcLib;
    private Pointer statusItem;
    private Pointer menu;
    private Pointer target;
    private Runnable applicationQuitAction;
    private Runnable applicationReopenAction;
    private byte[] iconPng;
    private String tooltip = "QPlayer";
    private int nextTag = 1;
    private boolean installed;

    Object addItem(String label, Runnable action) {
        Item item = new Item(nextTag++, label, action, false);
        items.add(item);
        return item;
    }

    void addSeparator() {
        items.add(new Item(0, null, null, true));
    }

    void setLabel(Object handle, String label) {
        if (!(handle instanceof Item item) || item.separator) return;
        item.label = label;
        if (item.menuItem != null) {
            sendVoid(item.menuItem, "setTitle:", nsString(label));
        }
    }

    void setIconPng(byte[] png) {
        this.iconPng = png;
        if (installed) applyIcon();
    }

    void setApplicationQuitAction(Runnable action) {
        this.applicationQuitAction = action;
    }

    void setApplicationReopenAction(Runnable action) {
        this.applicationReopenAction = action;
    }

    void setTooltip(String tip) {
        this.tooltip = tip == null ? "QPlayer" : tip;
        if (statusItem != null) {
            sendVoid(statusItem, "setToolTip:", nsString(this.tooltip));
        }
    }

    boolean install() {
        try {
            NativeLibrary.getInstance(FOUNDATION);
            NativeLibrary.getInstance(APP_KIT);
            objcLib = NativeLibrary.getInstance("objc");
            objc = Native.load("objc", ObjC.class);

            Pointer bar = send(cls("NSStatusBar"), "systemStatusBar");
            if (bar == null) return false;
            statusItem = send(bar, "statusItemWithLength:", SQUARE_LENGTH);
            if (statusItem == null) return false;
            send(statusItem, "retain");

            installTarget();
            menu = send(send(cls("NSMenu"), "alloc"), "init");
            send(menu, "retain");
            for (Item item : items) {
                if (item.separator) {
                    sendVoid(menu, "addItem:", send(cls("NSMenuItem"), "separatorItem"));
                    continue;
                }
                Pointer menuItem = send(send(cls("NSMenuItem"), "alloc"),
                        "initWithTitle:action:keyEquivalent:",
                        nsString(item.label),
                        sel("qplayerTray:"),
                        nsString(""));
                sendVoid(menuItem, "setTarget:", target);
                sendVoid(menuItem, "setTag:", (long) item.tag);
                sendVoid(menu, "addItem:", menuItem);
                item.menuItem = menuItem;
            }
            sendVoid(statusItem, "setMenu:", menu);
            sendVoid(statusItem, "setToolTip:", nsString(tooltip));
            installApplicationQuitAction();
            installApplicationReopenAction();
            applyIcon();
            installed = true;
            Logger.info("system tray initialized: macOS NSStatusItem");
            return true;
        } catch (Throwable t) {
            Logger.warn("macOS tray init failed: {}", t);
            shutdown();
            return false;
        }
    }

    void shutdown() {
        installed = false;
        try {
            if (statusItem != null) {
                Pointer bar = send(cls("NSStatusBar"), "systemStatusBar");
                if (bar != null) sendVoid(bar, "removeStatusItem:", statusItem);
            }
        } catch (Throwable ignored) {
        }
        statusItem = null;
        menu = null;
        target = null;
    }

    private void installTarget() {
        Pointer superClass = cls("NSObject");
        Pointer targetClass = objc.objc_allocateClassPair(superClass,
                "QPlayerMacTrayTarget", 0);
        boolean newlyAllocated = targetClass != null;
        if (!newlyAllocated) targetClass = cls("QPlayerMacTrayTarget");
        if (targetClass == null) throw new IllegalStateException("cannot create tray target");

        ActionCallback callback = (self, selector, sender) -> {
            try {
                long tag = sendLong(sender, "tag");
                for (Item item : items) {
                    if (!item.separator && item.tag == tag && item.action != null) {
                        item.action.run();
                        return;
                    }
                }
            } catch (Throwable t) {
                Logger.warn("macOS tray action failed: {}", t);
            }
        };
        ActionCallback quitCallback = (self, selector, sender) -> {
            try {
                if (applicationQuitAction != null) applicationQuitAction.run();
            } catch (Throwable t) {
                Logger.warn("macOS application quit action failed: {}", t);
            }
        };
        keepAlive.add(callback);
        keepAlive.add(quitCallback);
        if (newlyAllocated) {
            if (!objc.class_addMethod(targetClass, sel("qplayerTray:"), callback, "v@:@")) {
                Logger.warn("macOS tray selector already exists");
            }
            if (!objc.class_addMethod(targetClass, sel("qplayerQuit:"), quitCallback, "v@:@")) {
                Logger.warn("macOS quit selector already exists");
            }
            objc.objc_registerClassPair(targetClass);
        }
        target = send(send(targetClass, "alloc"), "init");
    }

    /**
     * GLFW maps the native application Quit command to the window-close callback.
     * QPlayer deliberately vetoes that callback so the red close button can hide
     * to the tray, which also (incorrectly) turns Command-Q into hide-to-tray.
     * Redirect AppKit's terminate menu item to the explicit quit path instead.
     */
    private void installApplicationQuitAction() {
        if (applicationQuitAction == null || target == null) return;
        Pointer app = send(cls("NSApplication"), "sharedApplication");
        Pointer mainMenu = send(app, "mainMenu");
        if (!replaceTerminateAction(mainMenu)) {
            Logger.warn("macOS application Quit menu item unavailable");
        }
    }

    private boolean replaceTerminateAction(Pointer nativeMenu) {
        if (nativeMenu == null) return false;
        long count = sendLong(nativeMenu, "numberOfItems");
        Pointer terminate = sel("terminate:");
        for (long i = 0; i < count; i++) {
            Pointer menuItem = send(nativeMenu, "itemAtIndex:", i);
            Pointer action = send(menuItem, "action");
            if (action != null && Pointer.nativeValue(action) == Pointer.nativeValue(terminate)) {
                sendVoid(menuItem, "setTarget:", target);
                sendVoid(menuItem, "setAction:", sel("qplayerQuit:"));
                return true;
            }
            if (replaceTerminateAction(send(menuItem, "submenu"))) return true;
        }
        return false;
    }

    /**
     * A Dock click for an already-running application is delivered to AppKit's
     * reopen delegate method, not to QPlayer's second-launch socket. GLFW does
     * not install that optional method, so add it to GLFW's existing delegate
     * class without replacing the delegate or disturbing its other callbacks.
     */
    private void installApplicationReopenAction() {
        if (applicationReopenAction == null) return;
        Pointer app = send(cls("NSApplication"), "sharedApplication");
        Pointer delegate = send(app, "delegate");
        Pointer delegateClass = delegate == null ? null : objc.object_getClass(delegate);
        if (delegateClass == null) {
            Logger.warn("macOS application delegate unavailable; Dock reopen disabled");
            return;
        }
        ReopenCallback reopen = (self, selector, sender, hasVisibleWindows) -> {
            try {
                applicationReopenAction.run();
            } catch (Throwable t) {
                Logger.warn("macOS Dock reopen action failed: {}", t);
            }
            return 1;
        };
        keepAlive.add(reopen);
        if (!objc.class_addMethod(delegateClass,
                sel("applicationShouldHandleReopen:hasVisibleWindows:"),
                reopen, "B@:@B")) {
            Logger.warn("macOS application delegate already handles Dock reopen");
        }
    }

    private void applyIcon() {
        if (statusItem == null || iconPng == null || iconPng.length == 0) return;
        Pointer image = nsImage(iconPng);
        if (image == null) return;
        sendVoid(image, "setTemplate:", false);
        Pointer button = send(statusItem, "button");
        if (button != null) {
            // NSImageScaleProportionallyDown prevents a large source image from
            // being cropped to the status-item button bounds.
            sendVoid(button, "setImageScaling:", 0L);
            sendVoid(button, "setImage:", image);
        } else {
            sendVoid(statusItem, "setImage:", image);
        }
    }

    private Pointer nsImage(byte[] png) {
        Memory bytes = new Memory(png.length);
        bytes.write(0, png, 0, png.length);
        pinned.add(bytes);
        Pointer data = send(cls("NSData"), "dataWithBytes:length:",
                bytes, (long) png.length);
        if (data == null) return null;
        return send(send(cls("NSImage"), "alloc"), "initWithData:", data);
    }

    private Pointer nsString(String value) {
        if (value == null) value = "";
        return send(cls("NSString"), "stringWithUTF8String:", value);
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
}
