package dev.t1m3.qplayer.desktop.tray;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.ATOM;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinDef.HMENU;
import com.sun.jna.platform.win32.WinDef.UINT_PTR;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import dev.t1m3.qplayer.util.Logger;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Windows system tray driven straight through the Win32 shell API via JNA —
 * {@code Shell_NotifyIcon} for the icon/tooltip and a native {@code CreatePopupMenu}
 * /{@code TrackPopupMenu} for the menu. The menu is rendered by the OS (GDI), so
 * CJK labels show correctly with zero {@code java.awt} font-manager init — which
 * is what dies in the native image ({@code sun.awt.FontConfiguration} NPEs because
 * the bare binary has no JDK lib dir).
 *
 * <p>A message-only window on a dedicated pump thread receives the tray callback
 * ({@code WM_TRAYICON}); left-click restores the window, while right-click opens
 * the menu with {@code TPM_RETURNCMD} so the selected command comes back inline.
 */
final class WinTray {

    // ---- Win32 constants ----
    private static final int WM_USER = 0x0400;
    private static final int WM_TRAYICON = WM_USER + 1;
    private static final int WM_QUIT_TRAY = WM_USER + 2;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int WM_LBUTTONDBLCLK = 0x0203;
    private static final int WM_RBUTTONUP = 0x0205;
    private static final int WM_DESTROY = 0x0002;

    private static final int NIM_ADD = 0x0;
    private static final int NIM_MODIFY = 0x1;
    private static final int NIM_DELETE = 0x2;
    private static final int NIF_MESSAGE = 0x1;
    private static final int NIF_ICON = 0x2;
    private static final int NIF_TIP = 0x4;

    private static final int IMAGE_ICON = 1;
    private static final int LR_LOADFROMFILE = 0x10;
    private static final int LR_DEFAULTSIZE = 0x40;
    // Notification-area icon metrics: the shell asks for SM_CXSMICON-sized icons
    // (16px at 100% scaling, 20/24/32 as DPI scaling goes up).
    private static final int SM_CXSMICON = 49;
    private static final int SM_CYSMICON = 50;

    private static final int MF_STRING = 0x0;
    private static final int MF_SEPARATOR = 0x800;
    private static final int TPM_RIGHTBUTTON = 0x2;
    private static final int TPM_RETURNCMD = 0x100;
    private static final int TPM_NONOTIFY = 0x80;

    private static final int WS_EX_NOACTIVATE = 0x08000000;

    // ---- JNA library bindings (W = Unicode entry points) ----
    interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.UNICODE_OPTIONS);

        ATOM RegisterClassExW(WNDCLASSEX lpwcx);
        HWND CreateWindowExW(int exStyle, WString className, WString windowName, int style,
                             int x, int y, int w, int h, HWND parent, HMENU menu,
                             HMODULE inst, Pointer param);
        boolean DestroyWindow(HWND hWnd);
        LRESULT DefWindowProcW(HWND hWnd, int msg, WPARAM wParam, LPARAM lParam);
        int GetMessageW(MSG msg, HWND hWnd, int min, int max);
        boolean TranslateMessage(MSG msg);
        LRESULT DispatchMessageW(MSG msg);
        void PostQuitMessage(int exitCode);
        boolean PostMessageW(HWND hWnd, int msg, WPARAM wParam, LPARAM lParam);
        boolean GetCursorPos(POINT p);
        boolean SetForegroundWindow(HWND hWnd);
        HMENU CreatePopupMenu();
        boolean AppendMenuW(HMENU menu, int flags, UINT_PTR idNewItem, WString item);
        int TrackPopupMenu(HMENU menu, int flags, int x, int y, int reserved, HWND hWnd, Pointer rect);
        boolean DestroyMenu(HMENU menu);
        HANDLE LoadImageW(HINSTANCE inst, WString name, int type, int cx, int cy, int load);
        boolean DestroyIcon(HICON icon);
        int GetSystemMetrics(int index);
    }

    interface Shell32 extends StdCallLibrary {
        Shell32 I = Native.load("shell32", Shell32.class, W32APIOptions.UNICODE_OPTIONS);
        boolean Shell_NotifyIconW(int message, NOTIFYICONDATA data);
    }

    interface WndProc extends StdCallLibrary.StdCallCallback {
        LRESULT callback(HWND hWnd, int msg, WPARAM wParam, LPARAM lParam);
    }

    @Structure.FieldOrder({"cbSize", "style", "lpfnWndProc", "cbClsExtra", "cbWndExtra",
            "hInstance", "hIcon", "hCursor", "hbrBackground", "lpszMenuName", "lpszClassName", "hIconSm"})
    public static class WNDCLASSEX extends Structure {
        public int cbSize;
        public int style;
        public WndProc lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public HMODULE hInstance;
        public HICON hIcon;
        public Pointer hCursor;
        public Pointer hbrBackground;
        public Pointer lpszMenuName;
        public Pointer lpszClassName;
        public HICON hIconSm;
    }

    @Structure.FieldOrder({"hwnd", "message", "wParam", "lParam", "time", "x", "y"})
    public static class MSG extends Structure {
        public HWND hwnd;
        public int message;
        public WPARAM wParam;
        public LPARAM lParam;
        public int time;
        public int x;
        public int y;
    }

    @Structure.FieldOrder({"cbSize", "hWnd", "uID", "uFlags", "uCallbackMessage", "hIcon",
            "szTip", "dwState", "dwStateMask", "szInfo", "uVersion", "szInfoTitle",
            "dwInfoFlags", "guidItem", "hBalloonIcon"})
    public static class NOTIFYICONDATA extends Structure {
        public int cbSize;
        public HWND hWnd;
        public int uID;
        public int uFlags;
        public int uCallbackMessage;
        public HICON hIcon;
        public char[] szTip = new char[128];
        public int dwState;
        public int dwStateMask;
        public char[] szInfo = new char[256];
        public int uVersion;
        public char[] szInfoTitle = new char[64];
        public int dwInfoFlags;
        public GUID guidItem;
        public HICON hBalloonIcon;
    }

    // ---- menu model ----
    private static final class Item {
        final int id;
        volatile String label;
        final Runnable action;
        final boolean separator;
        Item(int id, String label, Runnable action, boolean separator) {
            this.id = id; this.label = label; this.action = action; this.separator = separator;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int nextId = 1;
    // See openMenuOnce(): TrackPopupMenu blocks the pump thread until dismissed, and
    // Win32 silently cancels whatever popup is open if TrackPopupMenu is called again
    // before that -- guards against a second trigger landing while one is tracking.
    private final java.util.concurrent.atomic.AtomicBoolean menuOpen =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Runnable leftClickAction;
    private volatile BooleanSupplier darkTheme;

    /** Action for a left-click on the icon; the popup menu belongs to right-click. */
    void setLeftClickAction(Runnable r) {
        this.leftClickAction = r;
    }

    void setDarkTheme(BooleanSupplier dark) {
        this.darkTheme = dark;
    }

    /** uxtheme SetPreferredAppMode: ForceDark=2, ForceLight=3. */
    static int preferredAppMode(boolean dark) {
        return dark ? 2 : 3;
    }

    // Only ever touched on the pump thread (wndProc callback), so no synchronization
    // needed despite being read/written across the UP/DBLCLK branches above.
    private boolean suppressNextUp = false;
    private volatile HWND hWnd;
    private volatile HICON hIcon;
    private volatile byte[] iconIco;
    private final NOTIFYICONDATA nid = new NOTIFYICONDATA();
    private WndProc wndProc;            // strong ref: JNA callbacks must not be GC'd
    private Thread pump;
    private static final String CLASS_NAME = "QPlayerTrayWnd";
    private final WString className = new WString(CLASS_NAME);
    private Memory classNameW;          // strong ref: wide class name for WNDCLASSEX
    private byte[] iconPng;
    private String tooltip = "QPlayer";

    /** Add a clickable menu item; returns an opaque handle to later relabel it. */
    Object addItem(String label, Runnable action) {
        Item it = new Item(nextId++, label, action, false);
        items.add(it);
        return it;
    }

    void addSeparator() {
        items.add(new Item(0, null, null, true));
    }

    void setLabel(Object handle, String label) {
        if (handle instanceof Item it) it.label = label;
    }

    void setIconPng(byte[] png) {
        this.iconPng = png;
    }

    /** A ready-made multi-size .ico, preferred over the PNG: Windows then loads the
     *  entry that already matches the tray size instead of downscaling a 256px
     *  image itself (LoadImage's stretch is not resampled, so fine detail — the
     *  record's grooves — turns to noise). */
    void setIconIco(byte[] ico) {
        this.iconIco = ico;
    }

    void setTooltip(String tip) {
        this.tooltip = tip;
        HWND w = hWnd;
        if (w == null) return;
        synchronized (nid) {
            writeTip(tip);
            nid.uFlags = NIF_TIP;
            nid.write();
            Shell32.I.Shell_NotifyIconW(NIM_MODIFY, nid);
        }
    }

    /** Spawn the message-pump thread and register the icon. Returns false on failure. */
    boolean install() {
        final Object ready = new Object();
        final boolean[] ok = {false};
        final boolean[] done = {false};
        pump = new Thread(() -> {
            boolean built = build();
            synchronized (ready) {
                ok[0] = built;
                done[0] = true;
                ready.notifyAll();
            }
            if (built) loop();
        }, "qplayer-wintray");
        pump.setDaemon(true);
        pump.start();
        synchronized (ready) {
            while (!done[0]) {
                try { ready.wait(5000); } catch (InterruptedException e) { break; }
                if (!done[0]) break; // timed out
            }
        }
        return ok[0];
    }

    private boolean build() {
        try {
            HMODULE inst = Kernel32.INSTANCE.GetModuleHandle(null);

            wndProc = (w, msg, wParam, lParam) -> {
                if (msg == WM_TRAYICON) {
                    int evt = lParam.intValue() & 0xFFFF;
                    if (evt == WM_RBUTTONUP) {
                        openMenuOnce();
                    } else if (evt == WM_LBUTTONUP) {
                        if (suppressNextUp) {
                            suppressNextUp = false;
                        } else {
                            Runnable r = leftClickAction;
                            if (r != null) r.run();
                        }
                    } else if (evt == WM_LBUTTONDBLCLK) {
                        // The first UP already performed the action. Explorer sends
                        // one more UP after DBLCLK, so suppress that duplicate.
                        suppressNextUp = true;
                    }
                    return new LRESULT(0);
                }
                if (msg == WM_QUIT_TRAY) {
                    U32.I.DestroyWindow(w);
                    return new LRESULT(0);
                }
                if (msg == WM_DESTROY) {
                    U32.I.PostQuitMessage(0);
                    return new LRESULT(0);
                }
                return U32.I.DefWindowProcW(w, msg, wParam, lParam);
            };

            classNameW = new Memory((CLASS_NAME.length() + 1) * 2L);
            classNameW.setWideString(0, CLASS_NAME);

            WNDCLASSEX wc = new WNDCLASSEX();
            wc.cbSize = wc.size();
            wc.lpfnWndProc = wndProc;
            wc.hInstance = inst;
            wc.lpszClassName = classNameW;
            ATOM atom = U32.I.RegisterClassExW(wc);
            if (atom == null || atom.intValue() == 0) {
                Logger.warn("WinTray: RegisterClassEx failed (err={})", Native.getLastError());
                return false;
            }

            // A plain hidden top-level window (never shown) — receives the tray
            // callback message fine and avoids the HWND_MESSAGE (-3) parent, which
            // CreateWindowEx here rejected with ERROR_INVALID_WINDOW_HANDLE (1400).
            hWnd = U32.I.CreateWindowExW(WS_EX_NOACTIVATE, className, new WString("QPlayer"),
                    0, 0, 0, 0, 0, null, null, inst, null);
            if (hWnd == null) {
                Logger.warn("WinTray: CreateWindowEx failed (err={})", Native.getLastError());
                return false;
            }

            hIcon = loadIcon();

            nid.cbSize = nid.size();
            nid.hWnd = hWnd;
            nid.uID = 1;
            nid.uCallbackMessage = WM_TRAYICON;
            nid.uFlags = NIF_MESSAGE | NIF_TIP | (hIcon != null ? NIF_ICON : 0);
            nid.hIcon = hIcon;
            writeTip(tooltip);
            if (!Shell32.I.Shell_NotifyIconW(NIM_ADD, nid)) {
                Logger.warn("WinTray: Shell_NotifyIcon ADD failed");
                return false;
            }
            Logger.info("system tray initialized: Win32 Shell_NotifyIcon");
            return true;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("WinTray build failed:\n{}", sw);
            return false;
        }
    }

    private void loop() {
        MSG msg = new MSG();
        int r;
        while ((r = U32.I.GetMessageW(msg, null, 0, 0)) != 0) {
            if (r == -1) break;
            U32.I.TranslateMessage(msg);
            U32.I.DispatchMessageW(msg);
        }
        // window destroyed → tear down the icon
        try {
            synchronized (nid) { Shell32.I.Shell_NotifyIconW(NIM_DELETE, nid); }
            if (hIcon != null) U32.I.DestroyIcon(hIcon);
        } catch (Throwable ignored) {}
    }

    // TrackPopupMenu blocks the calling thread until dismissed; guards the (now rare,
    // but still possible e.g. right-click landing during the left-click timer window)
    // case of a second call arriving while one is already tracking -- Win32 silently
    // cancels whatever popup is currently open when TrackPopupMenu is called again.
    private void openMenuOnce() {
        POINT pt = new POINT();
        if (U32.I.GetCursorPos(pt)) openMenuOnce(pt.x, pt.y);
    }

    private void openMenuOnce(int x, int y) {
        if (menuOpen.compareAndSet(false, true)) {
            try { showMenu(x, y); } finally { menuOpen.set(false); }
        }
    }

    private void showMenu(int x, int y) {
        applyMenuTheme();
        HMENU menu = U32.I.CreatePopupMenu();
        if (menu == null) return;
        try {
            for (Item it : items) {
                if (it.separator) {
                    U32.I.AppendMenuW(menu, MF_SEPARATOR, new UINT_PTR(0), null);
                } else {
                    U32.I.AppendMenuW(menu, MF_STRING, new UINT_PTR(it.id), new WString(it.label));
                }
            }
            // Required Win32 dance so the menu dismisses when clicking elsewhere.
            U32.I.SetForegroundWindow(hWnd);
            int cmd = U32.I.TrackPopupMenu(menu,
                    TPM_RIGHTBUTTON | TPM_RETURNCMD | TPM_NONOTIFY, x, y, 0, hWnd, null);
            U32.I.PostMessageW(hWnd, 0 /* WM_NULL */, new WPARAM(0), new LPARAM(0));
            if (cmd > 0) {
                for (Item it : items) {
                    if (it.id == cmd && it.action != null) { it.action.run(); break; }
                }
            }
        } finally {
            U32.I.DestroyMenu(menu);
        }
    }

    private static Function setPreferredAppMode;
    private static Function flushMenuThemes;
    private static boolean themeBound;

    private void applyMenuTheme() {
        BooleanSupplier dark = darkTheme;
        if (dark == null) return;
        // shortcut: fails closed to the system theme before Windows 10 1903, replace if the menu is owner-drawn
        try {
            bindMenuTheme();
            if (setPreferredAppMode == null) return;
            setPreferredAppMode.invokeInt(new Object[]{preferredAppMode(dark.getAsBoolean())});
            flushMenuThemes.invokeVoid(new Object[0]);
        } catch (Throwable ignored) {}
    }

    private static void bindMenuTheme() {
        if (themeBound) return;
        themeBound = true;
        HMODULE theme = Kernel32.INSTANCE.LoadLibraryEx("uxtheme", null, 0);
        if (theme == null) return;
        Pointer set = Kernel32.INSTANCE.GetProcAddress(theme, 135);
        Pointer flush = Kernel32.INSTANCE.GetProcAddress(theme, 136);
        if (set == null || flush == null) return;
        setPreferredAppMode = Function.getFunction(set, Function.ALT_CONVENTION);
        flushMenuThemes = Function.getFunction(flush, Function.ALT_CONVENTION);
    }

    void shutdown() {
        HWND w = hWnd;
        if (w != null) U32.I.PostMessageW(w, WM_QUIT_TRAY, new WPARAM(0), new LPARAM(0));
        hWnd = null;
    }

    private void writeTip(String tip) {
        char[] buf = nid.szTip;
        java.util.Arrays.fill(buf, '\0');
        int n = Math.min(tip.length(), buf.length - 1);
        tip.getChars(0, n, buf, 0);
    }

    private HICON loadIcon() {
        byte[] ico = iconIco != null ? iconIco : (iconPng != null ? wrapPngAsIco(iconPng) : null);
        if (ico == null) return null;
        try {
            java.io.File f = java.io.File.createTempFile("qplayer-tray", ".ico");
            f.deleteOnExit();
            java.nio.file.Files.write(f.toPath(), ico);
            // Ask for the notification area's own size so a multi-size .ico is
            // matched entry-for-entry; LR_DEFAULTSIZE (32px) would make the shell
            // scale a second time. Falls back to the old behaviour if the metrics
            // are unavailable.
            int cx = U32.I.GetSystemMetrics(SM_CXSMICON);
            int cy = U32.I.GetSystemMetrics(SM_CYSMICON);
            int flags = LR_LOADFROMFILE | ((cx > 0 && cy > 0) ? 0 : LR_DEFAULTSIZE);
            HANDLE h = U32.I.LoadImageW(null, new WString(f.getAbsolutePath()),
                    IMAGE_ICON, Math.max(0, cx), Math.max(0, cy), flags);
            if (h == null) {
                Logger.warn("WinTray: LoadImage failed (err={})", Native.getLastError());
                return null;
            }
            return new HICON(h.getPointer());
        } catch (Throwable t) {
            Logger.warn("WinTray: icon load failed: {}", t);
            return null;
        }
    }

    // Vista+ accept a PNG embedded directly in an .ico container. Build the 22-byte
    // header (ICONDIR + one ICONDIRENTRY) around the PNG bytes. The directory entry's
    // width/height must match the PNG (a 0 there means 256, which a 64px PNG isn't),
    // so read them from the PNG's IHDR (big-endian ints at offsets 16/20).
    private static byte[] wrapPngAsIco(byte[] png) {
        int w = pngDim(png, 16);
        int h = pngDim(png, 20);
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 22);
        le16(out, 0);                 // reserved
        le16(out, 1);                 // type: icon
        le16(out, 1);                 // image count
        out.write(w >= 256 ? 0 : w);  // width  (0 = 256)
        out.write(h >= 256 ? 0 : h);  // height
        out.write(0);                 // color count
        out.write(0);                 // reserved
        le16(out, 1);                 // color planes
        le16(out, 32);                // bits per pixel
        le32(out, png.length);        // bytes in resource
        le32(out, 22);                // offset to image data
        out.writeBytes(png);
        return out.toByteArray();
    }

    private static int pngDim(byte[] png, int off) {
        if (png.length < off + 4) return 0;
        return ((png[off] & 0xFF) << 24) | ((png[off + 1] & 0xFF) << 16)
                | ((png[off + 2] & 0xFF) << 8) | (png[off + 3] & 0xFF);
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >>> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); o.write((v >>> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }
}
