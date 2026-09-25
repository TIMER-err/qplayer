package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Windows-only: turns the native GLFW-decorated top-level window into a
 * custom-drawn one. Keeps the OS's WS_CAPTION|WS_THICKFRAME style bits (so
 * Aero Snap, the drop shadow, Alt-Tab thumbnail and taskbar preview all keep
 * working for free) and instead subclasses the window procedure to answer
 * WM_NCCALCSIZE (claim the whole window as client area) and WM_NCHITTEST
 * (drag the empty title-bar strip, resize from the edges/corners) itself --
 * the standard "extend client into non-client, do your own hit-testing"
 * recipe (Windows Terminal and similar apps use the same one), rather than
 * GLFW_DECORATED=false (which would also lose Snap/shadow and still need
 * fully manual resize hit-testing anyway, so the extra native-frame
 * preservation here is close to free).
 */
final class WinFrameless {

    private static final int GWLP_WNDPROC = -4;
    private static final int WM_SIZE = 0x0005;
    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_NCACTIVATE = 0x0086;
    private static final int WM_DWMCOMPOSITIONCHANGED = 0x031E;

    private static final int HTCLIENT = 1;
    private static final int HTCAPTION = 2;
    private static final int HTLEFT = 10;
    private static final int HTRIGHT = 11;
    private static final int HTTOP = 12;
    private static final int HTTOPLEFT = 13;
    private static final int HTTOPRIGHT = 14;
    private static final int HTBOTTOM = 15;
    private static final int HTBOTTOMLEFT = 16;
    private static final int HTBOTTOMRIGHT = 17;

    private static final int MONITOR_DEFAULTTONEAREST = 2;
    private static final int SM_CXFRAME = 32;
    private static final int SM_CYFRAME = 33;
    private static final int SM_CXPADDEDBORDER = 92;

    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int SWP_NOOWNERZORDER = 0x0200;

    // LyricOverlay.qml: 40px IconButtons, 6px from the top/outer edge and 6px
    // from each other.
    private static final double LYRIC_BUTTON_SIZE_LOGICAL_PX = 40;
    private static final double LYRIC_BUTTON_MARGIN_LOGICAL_PX = 6;
    /**
     * How many buttons LyricOverlay puts in the page's top-RIGHT corner
     * (full-width, cover mode, lyric offset).
     *
     * <p>This has to be grown with that row. A button past the last reserved
     * slot sits over the caption strip, where Windows answers the press itself
     * as a window drag — the click never reaches the app at all, so the button
     * renders perfectly and does absolutely nothing, with nothing in any log and
     * no QML test able to see it (a dispatched pointer bypasses WM_NCHITTEST).
     * Reserving a slot for a button that happens to be hidden only costs a small
     * non-draggable gap, so all of them are always reserved.
     */
    private static final int LYRIC_RIGHT_BUTTON_COUNT = 3;

    interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer SetWindowLongPtrW(HWND hWnd, int nIndex, WndProc newProc);
        LRESULT CallWindowProcW(Pointer prevWndFunc, HWND hWnd, int msg, WPARAM wParam, LPARAM lParam);
        LRESULT DefWindowProcW(HWND hWnd, int msg, WPARAM wParam, LPARAM lParam);
        boolean GetWindowRect(HWND hWnd, RECT rect);
        boolean IsZoomed(HWND hWnd);
        Pointer MonitorFromWindow(HWND hWnd, int dwFlags);
        boolean GetMonitorInfoW(Pointer hMonitor, MONITORINFO lpmi);
        int GetDpiForWindow(HWND hWnd);
        int GetSystemMetricsForDpi(int nIndex, int dpi);
        boolean SetWindowPos(HWND hWnd, HWND hWndInsertAfter, int x, int y, int cx, int cy, int flags);
    }

    interface WndProc extends StdCallLibrary.StdCallCallback {
        LRESULT callback(HWND hWnd, int msg, WPARAM wParam, LPARAM lParam);
    }

    interface Dwmapi extends StdCallLibrary {
        Dwmapi I = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);

        int DwmExtendFrameIntoClientArea(HWND hWnd, MARGINS margins);
    }

    @Structure.FieldOrder({"cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight"})
    public static class MARGINS extends Structure {
        public int cxLeftWidth;
        public int cxRightWidth;
        public int cyTopHeight;
        public int cyBottomHeight;
    }

    @Structure.FieldOrder({"cbSize", "rcMonitor", "rcWork", "dwFlags"})
    public static class MONITORINFO extends Structure {
        public int cbSize = size();
        public RECT rcMonitor;
        public RECT rcWork;
        public int dwFlags;
    }

    @Structure.FieldOrder({"rgrc", "lppos"})
    public static class NCCALCSIZE_PARAMS extends Structure {
        public RECT[] rgrc = (RECT[]) new RECT().toArray(3);
        public Pointer lppos;

        public NCCALCSIZE_PARAMS() {}

        public NCCALCSIZE_PARAMS(Pointer p) {
            super(p);
            read();
        }
    }

    private WndProc wndProc;           // strong ref: JNA callbacks must not be GC'd
    private Pointer originalWndProc;   // the GLFW-installed proc, called for anything we don't handle
    private boolean extendLegacyDwmFrame;

    /** Subclass {@code window}'s WNDPROC. Call once per {@code createWindow()} --
     *  safe to call again on a freshly (re)created window (e.g. the Vulkan-
     *  fallback recreate path): each call targets a brand-new hwnd, and the
     *  caller ({@link DesktopWindow}) holds a fresh {@code WinFrameless}
     *  instance per creation so a stale one can never receive a callback for
     *  an already-destroyed window. */
    void install(DesktopWindow window, double titleBarHeightLogicalPx,
                 boolean extendLegacyDwmFrame) {
        long hwndLong = GLFWNativeWin32.glfwGetWin32Window(window.window());
        HWND hwnd = new HWND(Pointer.createConstant(hwndLong));
        this.extendLegacyDwmFrame = extendLegacyDwmFrame;

        wndProc = (h, msg, wParam, lParam) -> {
            // Publish the client size before GLFW's original procedure runs.  In
            // particular this keeps the render thread fed from inside Windows'
            // modal interactive-resize loop instead of waiting for the outer GLFW
            // event pump to regain control after the mouse button is released.
            if (msg == WM_SIZE) {
                int packed = lParam.intValue();
                window.onNativeFramebufferResize(
                        packed & 0xFFFF, (packed >>> 16) & 0xFFFF);
            }
            if (msg == WM_NCCALCSIZE) return ncCalcSize(h, wParam, lParam);
            if (msg == WM_NCHITTEST) return hitTest(h, lParam, window, titleBarHeightLogicalPx);
            if (msg == WM_NCACTIVATE) return U32.I.DefWindowProcW(h, msg, wParam, new LPARAM(-1));
            if (msg == WM_DWMCOMPOSITIONCHANGED && this.extendLegacyDwmFrame) {
                extendDwmFrame(h);
            }
            return U32.I.CallWindowProcW(originalWndProc, h, msg, wParam, lParam);
        };
        originalWndProc = U32.I.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, wndProc);
        // WM_NCCALCSIZE for the window's current frame already ran once (during
        // GLFW's own CreateWindow, through the ORIGINAL wndproc) before this method
        // ever got a chance to intercept it -- without forcing Windows to recompute
        // the frame now, the native caption/border stays visible until the next
        // resize/move. SWP_FRAMECHANGED with every other flag set to "don't actually
        // move/resize/reorder/activate" does exactly that recompute with no visible
        // side effect otherwise.
        U32.I.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
        if (extendLegacyDwmFrame) extendDwmFrame(hwnd);
    }

    /**
     * Windows 10 shadow fallback. A one-pixel DWM frame keeps composition of the
     * native shadow alive while WM_NCCALCSIZE gives the visible frame to QML.
     * Win11 does not need this because its retained native style already receives
     * the system shadow and rounded-corner treatment.
     */
    private void extendDwmFrame(HWND hwnd) {
        try {
            MARGINS margins = new MARGINS();
            margins.cxLeftWidth = 1;
            margins.cxRightWidth = 1;
            margins.cyTopHeight = 1;
            margins.cyBottomHeight = 1;
            Dwmapi.I.DwmExtendFrameIntoClientArea(hwnd, margins);
        } catch (Throwable ignored) {
            // DWM composition is best-effort; the retained native frame styles
            // still preserve resize/Snap even if frame extension is unavailable.
        }
    }

    /** WM_NCCALCSIZE: claiming the whole window as client area (leave the
     *  proposed rect untouched, return 0) is correct for the restored state,
     *  but a MAXIMIZED window needs its client rect explicitly set to the
     *  monitor's WORK area (not the full monitor rect) -- otherwise it
     *  overhangs the taskbar/screen edge by the invisible resize-border
     *  thickness a WS_THICKFRAME window still nominally has. This is the
     *  standard fix for that exact (very common) DIY-frameless-window bug. */
    private LRESULT ncCalcSize(HWND hwnd, WPARAM wParam, LPARAM lParam) {
        if (U32.I.IsZoomed(hwnd)) {
            RECT work = monitorWorkArea(hwnd);
            if (work != null) {
                Pointer p = new Pointer(lParam.longValue());
                if (wParam.intValue() != 0) {
                    NCCALCSIZE_PARAMS params = new NCCALCSIZE_PARAMS(p);
                    copyRect(work, params.rgrc[0]);
                    params.write();
                } else {
                    RECT r = Structure.newInstance(RECT.class, p);
                    r.read();
                    copyRect(work, r);
                    r.write();
                }
            }
        }
        return new LRESULT(0);
    }

    private RECT monitorWorkArea(HWND hwnd) {
        Pointer monitor = U32.I.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        if (monitor == null) return null;
        MONITORINFO mi = new MONITORINFO();
        mi.cbSize = mi.size();
        if (!U32.I.GetMonitorInfoW(monitor, mi)) return null;
        return mi.rcWork;
    }

    private static void copyRect(RECT from, RECT to) {
        to.left = from.left;
        to.top = from.top;
        to.right = from.right;
        to.bottom = from.bottom;
    }

    /** WM_NCHITTEST: reports HTCAPTION over the empty title-bar strip (native
     *  drag + double-click-maximize + Aero Snap all come free once this is
     *  answered correctly), HTLEFT/RIGHT/TOP/BOTTOM/corners near the window
     *  edges (skipped entirely while maximized -- nothing to grab), and
     *  HTCLIENT over the three caption buttons and the rest of the client
     *  area. The top resize band spans the *entire* window width, including
     *  under the caption buttons, matching native convention (you can still
     *  grab-resize from the literal top pixel row above a close button). */
    private LRESULT hitTest(HWND hwnd, LPARAM lParam, DesktopWindow window, double titleBarHeightLogicalPx) {
        RECT rect = new RECT();
        if (!U32.I.GetWindowRect(hwnd, rect)) return new LRESULT(HTCLIENT);
        int lp = lParam.intValue();
        int x = (short) (lp & 0xFFFF);
        int y = (short) ((lp >> 16) & 0xFFFF);

        if (!U32.I.IsZoomed(hwnd)) {
            int dpi = U32.I.GetDpiForWindow(hwnd);
            int bx = U32.I.GetSystemMetricsForDpi(SM_CXFRAME, dpi) + U32.I.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
            int by = U32.I.GetSystemMetricsForDpi(SM_CYFRAME, dpi) + U32.I.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
            boolean left = x < rect.left + bx;
            boolean right = x >= rect.right - bx;
            boolean top = y < rect.top + by;
            boolean bottom = y >= rect.bottom - by;
            if (top && left) return new LRESULT(HTTOPLEFT);
            if (top && right) return new LRESULT(HTTOPRIGHT);
            if (bottom && left) return new LRESULT(HTBOTTOMLEFT);
            if (bottom && right) return new LRESULT(HTBOTTOMRIGHT);
            if (left) return new LRESULT(HTLEFT);
            if (right) return new LRESULT(HTRIGHT);
            if (top) return new LRESULT(HTTOP);
            if (bottom) return new LRESULT(HTBOTTOM);
        }

        double scale = window.uiScale();
        double titleBarPhysical = titleBarHeightLogicalPx * scale;
        if (y - rect.top < titleBarPhysical) {
            // The normal custom title bar is hidden on the lyric page. Preserve
            // HTCLIENT only over LyricOverlay's actual top controls (back on the
            // left; cover + offset on the right), and return HTCAPTION for the
            // empty space between them. That keeps every icon clickable without
            // sacrificing native drag, double-click-maximise, or Aero Snap.
            if (window.controller() != null
                    && Boolean.TRUE.equals(window.controller().lyricsOpen.peek())) {
                double clientX = x - rect.left;
                double clientY = y - rect.top;
                double width = rect.right - rect.left;
                if (isLyricButton(clientX, clientY, width, scale)) {
                    return new LRESULT(HTCLIENT);
                }
                return new LRESULT(HTCAPTION);
            }
            double buttonStripPhysical =
                    WindowChrome.BUTTON_WIDTH_LOGICAL_PX * WindowChrome.BUTTON_COUNT * scale;
            if (rect.right - x <= buttonStripPhysical) return new LRESULT(HTCLIENT);
            return new LRESULT(HTCAPTION);
        }
        return new LRESULT(HTCLIENT);
    }

    /** Pure geometry shared by the native hit-test and its unit tests. */
    static boolean isLyricButton(double x, double y, double windowWidth, double scale) {
        double margin = LYRIC_BUTTON_MARGIN_LOGICAL_PX * scale;
        double size = LYRIC_BUTTON_SIZE_LOGICAL_PX * scale;
        if (y < margin || y >= margin + size) return false;

        // Close, on the left.
        if (x >= margin && x < margin + size) return true;
        // The right-hand row, counted inwards from the window edge.
        double fromRight = windowWidth - x;
        for (int i = 0; i < LYRIC_RIGHT_BUTTON_COUNT; i++) {
            double start = margin * (i + 1) + size * i;
            double end = start + size;
            if (fromRight > start && fromRight <= end) return true;
        }
        return false;
    }
}
