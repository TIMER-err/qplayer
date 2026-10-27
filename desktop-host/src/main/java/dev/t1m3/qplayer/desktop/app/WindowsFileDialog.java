package dev.t1m3.qplayer.desktop.app;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Vista+ common item dialogs on a dedicated STA; Show supplies the COM message pump. */
final class WindowsFileDialog {
    private static final int CANCELLED = 0x800704c7;
    private static final String FILE_OPEN = "dc1c5a9c-e88a-4dde-a5a1-60f82a20aef7";
    private static final String FILE_SAVE = "c0b4e2f3-ba21-4773-8dba-335ec946eb8b";
    private static final String OPEN_INTERFACE = "d57c7288-d4ad-4768-be02-9d969532d960";
    private static final String SAVE_INTERFACE = "84bccd23-5fde-4cdb-aea4-af64b83d78ab";
    private static final String SHELL_ITEM = "43826d1e-e718-42ee-bc55-a1e261c37bfe";

    private WindowsFileDialog() {}

    static String show(String title, String directory, boolean directoryOnly,
                       String description, String[] extensions, String suggestedName, boolean save) {
        NativeLibrary ole = NativeLibrary.getInstance("ole32");
        NativeLibrary shell = NativeLibrary.getInstance("shell32");
        check(function(ole, "CoInitializeEx").invokeInt(new Object[]{Pointer.NULL, 0x2 | 0x4}),
                "CoInitializeEx(STA)");
        Pointer dialog = null;
        // COMDLG_FILTERSPEC points into these buffers until Show has returned.
        List<Memory> filterStrings = new ArrayList<>();
        Memory filters = null;
        try (Memory clsid = guid(save ? FILE_SAVE : FILE_OPEN);
             Memory iid = guid(save ? SAVE_INTERFACE : OPEN_INTERFACE)) {
            Pointer[] result = new Pointer[1];
            check(function(ole, "CoCreateInstance").invokeInt(
                    new Object[]{clsid, Pointer.NULL, 1, iid, result}), "CoCreateInstance(FileDialog)");
            dialog = result[0];
            int[] options = new int[1];
            check(call(dialog, 10, options), "IFileDialog.GetOptions");
            options[0] |= 0x40 | 0x8 | 0x800; // filesystem paths, no chdir, existing parent
            options[0] |= directoryOnly ? 0x20 : (save ? 0x2 : 0x1000);
            check(call(dialog, 9, options[0]), "IFileDialog.SetOptions");
            check(call(dialog, 17, new WString(title)), "IFileDialog.SetTitle");
            if (!directoryOnly && extensions != null && extensions.length > 0) {
                filters = new Memory(2L * Native.POINTER_SIZE);
                Memory label = wide(description);
                Memory pattern = wide("*." + String.join(";*.", extensions));
                filterStrings.add(label);
                filterStrings.add(pattern);
                filters.setPointer(0, label);
                filters.setPointer(Native.POINTER_SIZE, pattern);
                check(call(dialog, 4, 1, filters), "IFileDialog.SetFileTypes");
            }
            try (Memory shellIid = guid(SHELL_ITEM)) {
                Pointer[] folder = new Pointer[1];
                check(function(shell, "SHCreateItemFromParsingName").invokeInt(
                        new Object[]{new WString(directory), Pointer.NULL, shellIid, folder}),
                        "SHCreateItemFromParsingName");
                try {
                    // SetFolder, not SetDefaultFolder: honor the caller instead of a stale MRU.
                    check(call(dialog, 12, folder[0]), "IFileDialog.SetFolder");
                } finally {
                    call(folder[0], 2);
                }
            }
            if (save && suggestedName != null && !suggestedName.isEmpty()) {
                check(call(dialog, 15, new WString(suggestedName)), "IFileDialog.SetFileName");
            }
            int status = call(dialog, 3, Pointer.NULL);
            if (status == CANCELLED) return null;
            check(status, "IFileDialog.Show");
            Pointer[] item = new Pointer[1];
            check(call(dialog, 20, (Object) item), "IFileDialog.GetResult");
            try {
                Pointer[] name = new Pointer[1];
                check(call(item[0], 5, 0x80058000, name), "IShellItem.GetDisplayName");
                try {
                    if (name[0] == null) throw new IllegalStateException("File dialog returned no filesystem path");
                    return name[0].getWideString(0);
                } finally {
                    function(ole, "CoTaskMemFree").invokeVoid(new Object[]{name[0]});
                }
            } finally {
                call(item[0], 2);
            }
        } finally {
            if (dialog != null) call(dialog, 2);
            if (filters != null) filters.close();
            filterStrings.forEach(Memory::close);
            function(ole, "CoUninitialize").invokeVoid(new Object[0]);
        }
    }

    private static Function function(NativeLibrary library, String name) {
        return library.getFunction(name, Function.ALT_CONVENTION);
    }

    private static int call(Pointer object, int index, Object... arguments) {
        Object[] call = new Object[arguments.length + 1];
        call[0] = object;
        System.arraycopy(arguments, 0, call, 1, arguments.length);
        Pointer address = object.getPointer(0).getPointer((long) index * Native.POINTER_SIZE);
        return Function.getFunction(address, Function.ALT_CONVENTION).invokeInt(call);
    }

    private static Memory wide(String text) {
        Memory memory = new Memory((long) (text.length() + 1) * Native.WCHAR_SIZE);
        memory.setWideString(0, text);
        return memory;
    }

    private static Memory guid(String text) {
        UUID uuid = UUID.fromString(text);
        long high = uuid.getMostSignificantBits();
        long low = uuid.getLeastSignificantBits();
        Memory value = new Memory(16);
        value.setInt(0, (int) (high >>> 32));
        value.setShort(4, (short) (high >>> 16));
        value.setShort(6, (short) high);
        for (int i = 0; i < 8; i++) value.setByte(8 + i, (byte) (low >>> (56 - i * 8)));
        return value;
    }

    private static void check(int hresult, String operation) {
        if (hresult < 0) throw new IllegalStateException(operation + " failed: HRESULT 0x"
                + Integer.toHexString(hresult));
    }
}
