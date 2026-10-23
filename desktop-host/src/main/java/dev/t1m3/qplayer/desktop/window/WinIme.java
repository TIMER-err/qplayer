package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LRESULT;
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
    static final int WM_IME_REQUEST = 0x0288;
    static final int IMR_COMPOSITIONWINDOW = 0x0001;
    static final int IMR_CANDIDATEWINDOW = 0x0002;
    static final int IMR_QUERYCHARPOSITION = 0x0006;

    private static final int CFS_RECT = 0x0001;
    private static final int CFS_CANDIDATEPOS = 0x0040;
    private static final int CFS_EXCLUDE = 0x0080;
    private static final int GWLP_IMC = -12;
    private static boolean applying;

    private WinIme() {}

    static boolean isImeMessage(int msg) {
        return msg == WM_IME_STARTCOMPOSITION
                || msg == WM_IME_COMPOSITION
                || msg == WM_IME_NOTIFY
                || msg == WM_IME_ENDCOMPOSITION;
    }

    static boolean shouldPlace(int msg, int wParam) {
        if (msg == WM_IME_NOTIFY) return (wParam & 0xFFFF) == IMN_OPENCANDIDATE;
        return msg == WM_IME_STARTCOMPOSITION || msg == WM_IME_COMPOSITION;
    }

    static void placeCandidateWindow(HWND hwnd, DesktopWindow window) {
        if (applying || hwnd == null || window == null) return;
        int[] box = candidateBox(window);
        if (box == null) return;
        applying = true;
        try {
            U32.I.CreateCaret(hwnd, null, 1, 1);
            U32.I.SetCaretPos(box[0], box[3]);
            Pointer imc = Imm32.I.ImmGetContext(hwnd);
            if (imc == null) imc = U32.I.GetWindowLongPtrW(hwnd, GWLP_IMC);
            if (imc == null) return;
            try {
                CANDIDATEFORM pos = new CANDIDATEFORM();
                pos.dwIndex = 0;
                pos.dwStyle = CFS_CANDIDATEPOS;
                pos.ptCurrentPos.x = box[0];
                pos.ptCurrentPos.y = box[3];
                Imm32.I.ImmSetCandidateWindow(imc, pos);

                CANDIDATEFORM exclude = new CANDIDATEFORM();
                exclude.dwIndex = 0;
                exclude.dwStyle = CFS_EXCLUDE;
                exclude.ptCurrentPos.x = box[0];
                exclude.ptCurrentPos.y = box[3];
                exclude.rcArea.left = box[0];
                exclude.rcArea.top = box[1];
                exclude.rcArea.right = box[2];
                exclude.rcArea.bottom = box[3];
                Imm32.I.ImmSetCandidateWindow(imc, exclude);

                COMPOSITIONFORM composition = new COMPOSITIONFORM();
                composition.dwStyle = CFS_RECT;
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
        } finally {
            applying = false;
        }
    }

    static LRESULT answerImeRequest(int request, long address, DesktopWindow window) {
        if (address == 0 || window == null) return null;
        int[] box = candidateBox(window);
        if (box == null) return null;
        Pointer p = new Pointer(address);
        if (request == IMR_COMPOSITIONWINDOW) {
            COMPOSITIONFORM form = new COMPOSITIONFORM(p);
            form.dwStyle = CFS_RECT;
            form.ptCurrentPos.x = box[0];
            form.ptCurrentPos.y = box[1];
            writeRect(form.rcArea, box);
            form.write();
            return new LRESULT(1);
        }
        if (request == IMR_CANDIDATEWINDOW) {
            CANDIDATEFORM form = new CANDIDATEFORM(p);
            form.dwStyle = CFS_CANDIDATEPOS | CFS_EXCLUDE;
            form.ptCurrentPos.x = box[0];
            form.ptCurrentPos.y = box[3];
            writeRect(form.rcArea, box);
            form.write();
            return new LRESULT(1);
        }
        if (request == IMR_QUERYCHARPOSITION) {
            if (p.getInt(0) < 36) return null;
            p.setInt(8, box[0]);
            p.setInt(12, box[3]);
            p.setInt(16, Math.max(1, box[3] - box[1]));
            p.setInt(20, box[0]);
            p.setInt(24, box[1]);
            p.setInt(28, box[2]);
            p.setInt(32, box[3]);
            return new LRESULT(1);
        }
        return null;
    }

    private static void writeRect(RECT rect, int[] box) {
        rect.left = box[0];
        rect.top = box[1];
        rect.right = box[2];
        rect.bottom = box[3];
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
        boolean CreateCaret(HWND hWnd, Pointer hBitmap, int nWidth, int nHeight);
        boolean SetCaretPos(int x, int y);
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

        public CANDIDATEFORM() {}

        public CANDIDATEFORM(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({"dwStyle", "ptCurrentPos", "rcArea"})
    public static class COMPOSITIONFORM extends Structure {
        public int dwStyle;
        public POINT ptCurrentPos = new POINT();
        public RECT rcArea = new RECT();

        public COMPOSITIONFORM() {}

        public COMPOSITIONFORM(Pointer p) {
            super(p);
            read();
        }
    }
}
