package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WinImeTest {

    @Test
    public void imeCompositionAndCandidateNotifyAreRecognized() {
        assertTrue(WinIme.isImeMessage(WinIme.WM_IME_STARTCOMPOSITION));
        assertTrue(WinIme.isImeMessage(WinIme.WM_IME_COMPOSITION));
        assertTrue(WinIme.isImeMessage(WinIme.WM_IME_NOTIFY));
        assertTrue(WinIme.isImeMessage(WinIme.WM_IME_ENDCOMPOSITION));
        assertFalse(WinIme.isImeMessage(0x0084)); // WM_NCHITTEST
        assertFalse(WinIme.isImeMessage(0x0005)); // WM_SIZE
        assertFalse(WinIme.isImeMessage(WinIme.WM_IME_REQUEST));
    }

    @Test
    public void candidateNotifyFromImmSetIsNotWrittenAgain() {
        assertTrue(WinIme.shouldPlace(WinIme.WM_IME_STARTCOMPOSITION, 0));
        assertTrue(WinIme.shouldPlace(WinIme.WM_IME_COMPOSITION, 0));
        assertTrue(WinIme.shouldPlace(WinIme.WM_IME_NOTIFY, WinIme.IMN_OPENCANDIDATE));
        assertFalse(WinIme.shouldPlace(WinIme.WM_IME_NOTIFY, 0x0009));
        assertFalse(WinIme.shouldPlace(WinIme.WM_IME_ENDCOMPOSITION, 0));
    }

    @Test
    public void imeRequestWithoutAFieldIsLeftToTheSystem() {
        assertNull(WinIme.answerImeRequest(WinIme.IMR_CANDIDATEWINDOW, 0x1000, null));
        assertEquals(32, new WinIme.CANDIDATEFORM().size());
        assertEquals(28, new WinIme.COMPOSITIONFORM().size());
    }

    @Test
    public void missingGeometryDoesNotPlaceACandidateWindow() {
        assertTrue(WinIme.boxFromItem(null, null, 1f) == null);
    }
}
