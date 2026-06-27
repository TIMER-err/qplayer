package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.Clipboard;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

/** System clipboard via AWT, for QML text fields (copy/paste). */
final class AwtClipboard implements Clipboard {

    @Override
    public String getText() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) return null;
            Object data = t.getTransferData(DataFlavor.stringFlavor);
            return data.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public void setText(String text) {
        try {
            StringSelection sel = new StringSelection(text == null ? "" : text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
        } catch (Exception ignored) {
        }
    }
}
