package dev.t1m3.qplayer.desktop;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Windows console handling for the GUI-subsystem native binary.
 *
 * <p>The shipped {@code qplayer.exe} is patched to the Windows (GUI) subsystem
 * (see package-windows.ps1), so double-clicking shows no console at all — not even
 * a flash. The trade-off is that a GUI-subsystem process doesn't inherit a parent
 * terminal's console, so when launched from a shell it would print nothing. So at
 * startup we attach to the parent console if there is one and route stdout/stderr
 * to it; otherwise (double-clicked) we silence them, leaving the file log as the
 * record. Net: double-click = clean GUI, run from a terminal = logs stream to it.
 */
final class WinConsole {

    private static final int ATTACH_PARENT_PROCESS = -1;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_SHARE_RW = 0x00000003;
    private static final int OPEN_EXISTING = 3;
    private static final int CP_UTF8 = 65001;

    interface K32 extends StdCallLibrary {
        K32 I = Native.load("kernel32", K32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean AttachConsole(int processId);
        Pointer GetConsoleWindow();
        boolean SetConsoleOutputCP(int codePageId);
        WinNT.HANDLE CreateFileW(WString name, int access, int share, Pointer security,
                                 int creation, int flags, Pointer template);
        boolean WriteFile(WinNT.HANDLE file, byte[] buffer, int toWrite,
                          IntByReference written, Pointer overlapped);
    }

    private WinConsole() {}

    /** Attach to the launching terminal's console (if any) and stream stdout/stderr
     *  there; with none (double-clicked) silence them so log4j's console appender
     *  doesn't write to a dead handle. */
    static void attachParentConsole() {
        try {
            if (K32.I.AttachConsole(ATTACH_PARENT_PROCESS)) {
                routeStreamsToConsole();
            } else if (K32.I.GetConsoleWindow() == null) {
                // No console at all (GUI launch) — quiet stdout/stderr.
                PrintStream nul = new PrintStream(OutputStream.nullOutputStream(), false);
                System.setOut(nul);
                System.setErr(nul);
            }
        } catch (Throwable ignored) {
            // No kernel32 / odd environment — leave the streams as they are.
        }
    }

    private static void routeStreamsToConsole() {
        WinNT.HANDLE h = K32.I.CreateFileW(new WString("CONOUT$"), GENERIC_WRITE, FILE_SHARE_RW,
                Pointer.NULL, OPEN_EXISTING, 0, Pointer.NULL);
        if (h == null || WinNT.INVALID_HANDLE_VALUE.equals(h)) return;
        K32.I.SetConsoleOutputCP(CP_UTF8);
        final WinNT.HANDLE out = h;
        OutputStream os = new OutputStream() {
            @Override public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }
            @Override public void write(byte[] b, int off, int len) {
                byte[] slice = (off == 0 && len == b.length) ? b : Arrays.copyOfRange(b, off, off + len);
                K32.I.WriteFile(out, slice, len, new IntByReference(), Pointer.NULL);
            }
        };
        PrintStream ps = new PrintStream(os, true, StandardCharsets.UTF_8);
        System.setOut(ps);
        System.setErr(ps);
    }
}
