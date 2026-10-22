package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
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
    }

    @Test
    public void missingGeometryDoesNotPlaceACandidateWindow() {
        assertTrue(WinIme.boxFromItem(null, null, 1f) == null);
    }
}
