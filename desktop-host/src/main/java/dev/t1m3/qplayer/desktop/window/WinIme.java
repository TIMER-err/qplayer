package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.input.TextEditable;

import java.util.Map;

/**
 * Places the system IME candidate window under the focused QML text field.
 *
 * <p>GLFW only delivers committed Unicode via {@code glfwSetCharCallback}.
 * Chinese/Japanese IMEs still own the candidate UI, but a custom-framed
 * GLFW window does not advertise a caret, so Windows parks that UI at
 * (0,0) — often clipped or fully hidden. {@code ImmSetCandidateWindow}
 * with CFS_EXCLUDE + CFS_CANDIDATEPOS restores the usual overlay next
 * to the field.
 */
final class WinIme {

    static final int WM_IME_STARTCOMPOSITION = 0x010D;
    static final int WM_IME_ENDCOMPOSITION = 0x010E;
    static final int WM_IME_COMPOSITION = 0x010F;
    static final int WM_IME_NOTIFY = 0x0282;
    static final int IMN_OPENCANDIDATE = 0x0005;
    static final int IMN_SETCANDIDATEPOS = 0x0009;

    private static final int CFS_CANDIDATEPOS = 0x0040;
    private static final int CFS_EXCLUDE = 0x0080;
    private static final int GWLP_IMC = -12;

    private WinIme() {}

    static boolean isImeMessage(int msg) {
        return msg == WM_IME_STARTCOMPOSITION
                || msg == WM_IME_COMPOSITION
                || msg == WM_IME_NOTIFY
                || msg == WM_IME_ENDCOMPOSITION;
    }

    static void placeCandidateWindow(HWND hwnd, DesktopWindow window) {
        if (hwnd == null || window == null) return;
        int[] box = candidateBox(window);
        if (box == null) return;
        Pointer imc = Imm32.I.ImmGetContext(hwnd);
        if (imc == null) imc = U32.I.GetWindowLongPtrW(hwnd, GWLP_IMC);
        if (imc == null) return;
        try {
            CANDIDATEFORM exclude = new CANDIDATEFORM();
            exclude.dwIndex = 0;
            exclude.dwStyle = CFS_EXCLUDE;
            exclude.ptCurrentPos.x = box[0];
            exclude.ptCurrentPos.y = box[1];
            exclude.rcArea.left = box[0];
            exclude.rcArea.top = box[1];
            exclude.rcArea.right = box[2];
            exclude.rcArea.bottom = box[3];
            Imm32.I.ImmSetCandidateWindow(imc, exclude);

            CANDIDATEFORM pos = new CANDIDATEFORM();
            pos.dwIndex = 0;
            pos.dwStyle = CFS_CANDIDATEPOS;
            pos.ptCurrentPos.x = box[0];
            pos.ptCurrentPos.y = box[3];
            Imm32.I.ImmSetCandidateWindow(imc, pos);

            COMPOSITIONFORM composition = new COMPOSITIONFORM();
            composition.dwStyle = CFS_EXCLUDE;
            composition.ptCurrentPos.x = box[0];
            composition.ptCurrentPos.y = box[1];
            composition.rcArea.left = box[0];
            composition.rcArea.top = box[1];
            composition.rcArea.right = box[2];
            composition.rcArea.bottom = box[3];
            Imm32.I.ImmSetCompositionWindow(imc, composition);
        } finally {
            Imm32.I.ImmReleaseContext(hwnd, imc);
        }
    }

    /**
     * Focused editable's client-pixel box: [left, top, right, bottom].
     * Visible for tests.
     */
    static int[] boxFromItem(Item root, Item item, float uiScale) {
        if (root == null || item == null || uiScale <= 0f) return null;
        Number width = item.width.peek();
        Number height = item.height.peek();
        if (width == null || height == null) return null;
        int w = Math.max(1, Math.round(width.floatValue() * uiScale));
        int h = Math.max(1, Math.round(height.floatValue() * uiScale));
        Map<String, Object> origin = root.mapFromItem(item, 0, 0);
        if (origin == null) return null;
        Object xObj = origin.get("x");
        Object yObj = origin.get("y");
        if (!(xObj instanceof Number) || !(yObj instanceof Number)) return null;
        int left = Math.round(((Number) xObj).floatValue() * uiScale);
        int top = Math.round(((Number) yObj).floatValue() * uiScale);
        return new int[]{left, top, left + w, top + h};
    }

    private static int[] candidateBox(DesktopWindow window) {
        QmlView view = window.view();
        if (view == null || view.root() == null) return null;
        Item focused = view.focused();
        if (!(focused instanceof TextEditable)) return null;
        return boxFromItem(view.root(), focused, window.uiScale());
    }

    interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.UNICODE_OPTIONS);
        Pointer GetWindowLongPtrW(HWND hWnd, int nIndex);
    }

    interface Imm32 extends StdCallLibrary {
        Imm32 I = Native.load("imm32", Imm32.class, W32APIOptions.UNICODE_OPTIONS);
        Pointer ImmGetContext(HWND hWnd);
        boolean ImmReleaseContext(HWND hWnd, Pointer hIMC);
        boolean ImmSetCandidateWindow(Pointer hIMC, CANDIDATEFORM lpCandidate);
        boolean ImmSetCompositionWindow(Pointer hIMC, COMPOSITIONFORM lpCompForm);
    }

    @Structure.FieldOrder({"x", "y"})
    public static class POINT extends Structure {
        public int x;
        public int y;
    }

    @Structure.FieldOrder({"dwIndex", "dwStyle", "ptCurrentPos", "rcArea"})
    public static class CANDIDATEFORM extends Structure {
        public int dwIndex;
        public int dwStyle;
        public POINT ptCurrentPos = new POINT();
        public RECT rcArea = new RECT();
    }

    @Structure.FieldOrder({"dwStyle", "ptCurrentPos", "rcArea"})
    public static class COMPOSITIONFORM extends Structure {
        public int dwStyle;
        public POINT ptCurrentPos = new POINT();
        public RECT rcArea = new RECT();
    }
}
