/*
 * MIT License
 *
 * Embedded-mode WebView2 (Chromium-Edge) for Swing/AWT on Windows.
 *
 * Creates a child HWND of the JAWT-provided AWT Canvas HWND and hosts an
 * ICoreWebView2Controller + ICoreWebView2 inside it.  The WebView2
 * environment runs on a dedicated thread.  Operations from Java (navigate,
 * eval, set_bounds, ...) marshal to that thread via PostThreadMessage.
 *
 * Built against the stable WebView2 SDK (Microsoft.Web.WebView2, 1.0.x),
 * statically linked via WebView2LoaderStatic.lib.  Requires the system-wide
 * WebView2 Runtime (ships with current Windows 11 / Edge).
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
// WIN32_LEAN_AND_MEAN excludes objbase.h, which defines `interface` (=struct)
// used pervasively by WebView2.h's COM declarations.  Pull it in explicitly.
#include <objbase.h>
#include <shlwapi.h>  // SHCreateMemStream (Canvas 32 D5)
// Windows Credential Manager (CredWriteW / CredReadW / CredEnumerateW /
// CredDeleteW / CredFree) for the password-manager secret store (Canvas 28).
#include <wincred.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <jawt.h>
#include <jawt_md.h>

#include "ca_weblite_webview_WebViewNative.h"
#include "WebView2.h"
// Canvas 32 D2: the SDK's own WRL helpers for environment options and custom
// scheme registrations (they keep every other option at the SDK default).
#include <wrl.h>
#include "WebView2EnvironmentOptions.h"

#define WV_LOG(fmt, ...) do { \
    fprintf(stderr, "[webview-embed] " fmt "\n", ##__VA_ARGS__); \
    fflush(stderr); \
} while (0)

namespace embed_win {

static const UINT WM_EMBED_DISPATCH = WM_APP + 1;
static const UINT WM_EMBED_QUIT     = WM_APP + 2;

using DispatchFn = std::function<void()>;

static std::wstring get_environment_variable(const wchar_t *name) {
    DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (size == 0) return {};

    std::vector<wchar_t> value(size);
    DWORD written = GetEnvironmentVariableW(name, value.data(), size);
    if (written == 0 || written >= size) return {};
    return std::wstring(value.data(), written);
}

static std::wstring get_host_executable_path() {
    // GetModuleFileNameW does not provide a size-query mode.  32,768 UTF-16
    // code units is the maximum extended-length path supported by Win32.
    std::vector<wchar_t> path(32768);
    DWORD written = GetModuleFileNameW(nullptr, path.data(),
                                       static_cast<DWORD>(path.size()));
    if (written == 0 || written >= path.size()) return {};
    return std::wstring(path.data(), written);
}

static uint64_t fnv1a_path_hash(const std::wstring &path) {
    // Hash UTF-16 code units explicitly so the folder name stays stable across
    // compiler/runtime updates (unlike std::hash, whose output is unspecified).
    uint64_t hash = UINT64_C(14695981039346656037);
    for (wchar_t ch : path) {
        hash ^= static_cast<uint16_t>(ch);
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

static std::wstring join_path(const std::wstring &parent,
                              const std::wstring &child) {
    if (parent.empty()) return child;
    wchar_t last = parent.back();
    return parent + ((last == L'\\' || last == L'/') ? L"" : L"\\") + child;
}

static bool ensure_directory(const std::wstring &path, DWORD *error) {
    DWORD attributes = GetFileAttributesW(path.c_str());
    if (attributes != INVALID_FILE_ATTRIBUTES) {
        if (attributes & FILE_ATTRIBUTE_DIRECTORY) return true;
        if (error) *error = ERROR_ALREADY_EXISTS;
        return false;
    }

    if (CreateDirectoryW(path.c_str(), nullptr)) return true;

    DWORD create_error = GetLastError();
    attributes = GetFileAttributesW(path.c_str());
    if (create_error == ERROR_ALREADY_EXISTS &&
        attributes != INVALID_FILE_ATTRIBUTES &&
        (attributes & FILE_ATTRIBUTE_DIRECTORY)) {
        return true;
    }
    if (error) *error = create_error;
    return false;
}

static std::wstring make_user_data_folder(const std::wstring &root,
                                          const std::wstring &host_id,
                                          DWORD *error) {
    if (root.empty()) return {};
    std::wstring library_root = join_path(root, L"SwingWebView");
    if (!ensure_directory(library_root, error)) return {};

    std::wstring user_data_folder = join_path(library_root, host_id);
    if (!ensure_directory(user_data_folder, error)) return {};
    return user_data_folder;
}

// True when path is an existing directory in which a probe file can be
// created and deleted.  Never creates the directory itself.
static bool is_writable_directory(const std::wstring &path) {
    DWORD attributes = GetFileAttributesW(path.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES ||
            !(attributes & FILE_ATTRIBUTE_DIRECTORY)) {
        return false;
    }
    std::wstring probe = join_path(path, L".swingwebview-write-probe");
    HANDLE h = CreateFileW(probe.c_str(), GENERIC_WRITE, 0, nullptr,
                           CREATE_ALWAYS,
                           FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE,
                           nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    CloseHandle(h);
    return true;
}

static std::wstring resolve_webview2_user_data_folder(DWORD *error) {
    if (error) *error = ERROR_SUCCESS;

    std::wstring override_path =
        get_environment_variable(L"WEBVIEW2_USER_DATA_FOLDER");
    if (!override_path.empty()) return override_path;

    std::wstring executable = get_host_executable_path();
    std::wstring filename = L"host";
    if (!executable.empty()) {
        size_t separator = executable.find_last_of(L"\\/");
        filename = separator == std::wstring::npos
            ? executable : executable.substr(separator + 1);
        if (filename.empty()) filename = L"host";

        // Preserve existing installations: WebView2's own default is
        // <exe dir>\<exe name>.WebView2.  Keep using it when it already
        // exists and is writable so upgrading does not drop browser data.
        if (separator != std::wstring::npos) {
            std::wstring legacy = executable.substr(0, separator + 1)
                + filename + L".WebView2";
            if (is_writable_directory(legacy)) return legacy;
        }
    }

    wchar_t hash_suffix[40]{};
    swprintf_s(hash_suffix, _countof(hash_suffix), L"-%016llx.WebView2",
               static_cast<unsigned long long>(fnv1a_path_hash(executable)));
    std::wstring host_id = filename + hash_suffix;

    DWORD last_error = ERROR_PATH_NOT_FOUND;
    std::wstring folder = make_user_data_folder(
        get_environment_variable(L"LOCALAPPDATA"), host_id, &last_error);
    if (!folder.empty()) return folder;

    std::vector<wchar_t> temp_path(32768);
    DWORD temp_length = GetTempPathW(static_cast<DWORD>(temp_path.size()),
                                     temp_path.data());
    if (temp_length > 0 && temp_length < temp_path.size()) {
        folder = make_user_data_folder(
            std::wstring(temp_path.data(), temp_length), host_id, &last_error);
        if (!folder.empty()) return folder;
    } else if (temp_length == 0) {
        last_error = GetLastError();
    } else {
        last_error = ERROR_INSUFFICIENT_BUFFER;
    }

    if (error) *error = last_error;
    return {};
}

struct JawtLock {
    JAWT awt{};
    JAWT_DrawingSurface *ds = nullptr;
    JAWT_DrawingSurfaceInfo *dsi = nullptr;
    jint lock = 0;
    bool ok = false;

    JawtLock(JNIEnv *env, jobject component) {
        auto get_awt = reinterpret_cast<jboolean (*)(JNIEnv *, JAWT *)>(
            GetProcAddress(GetModuleHandleA("jawt.dll"), "JAWT_GetAWT"));
        if (!get_awt) return;
#if defined(JAWT_VERSION_9)
        awt.version = JAWT_VERSION_9;
        if (!get_awt(env, &awt)) {
            awt.version = JAWT_VERSION_1_4;
            if (!get_awt(env, &awt)) return;
        }
#else
        awt.version = JAWT_VERSION_1_4;
        if (!get_awt(env, &awt)) return;
#endif
        ds = awt.GetDrawingSurface(env, component);
        if (!ds) return;
        lock = ds->Lock(ds);
        if (lock & JAWT_LOCK_ERROR) {
            awt.FreeDrawingSurface(ds);
            ds = nullptr;
            return;
        }
        dsi = ds->GetDrawingSurfaceInfo(ds);
        if (!dsi) {
            ds->Unlock(ds);
            awt.FreeDrawingSurface(ds);
            ds = nullptr;
            return;
        }
        ok = true;
    }
    ~JawtLock() {
        if (ds) {
            if (dsi) ds->FreeDrawingSurfaceInfo(dsi);
            ds->Unlock(ds);
            awt.FreeDrawingSurface(ds);
        }
    }
};

struct Binding {
    std::string name;
    jobject fn = nullptr;
    jclass cls = nullptr;
};

struct Engine {
    HWND parent = nullptr;
    HWND child = nullptr;
    bool standalone = false;
    bool standalone_visible = false;
    std::atomic<bool> standalone_closed{false};
    std::wstring standalone_data;
    HANDLE standalone_browser_process = nullptr;
    DWORD thread_id = 0;
    HANDLE thread = nullptr;
    ICoreWebView2Controller *controller = nullptr;
    ICoreWebView2 *webview = nullptr;
    EventRegistrationToken message_token{};
    EventRegistrationToken got_focus_token{};
    EventRegistrationToken lost_focus_token{};
    // EventRegistrationToken for the ScriptDialogOpening handler
    // registered in create_engine.  Not explicitly removed in
    // destroy_engine -- the controller / webview Release calls
    // there detach all event handlers transitively (same as
    // message_token / got_focus_token / lost_focus_token).
    EventRegistrationToken script_dialog_token{};
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;
    bool debug = false;
    std::mutex bindings_mutex;
    // JNI global ref to the registered WebViewFocusCallback, or nullptr.
    // Invoked from the WebView2 controller's GotFocus / LostFocus events.
    jobject focus_callback = nullptr;
    // JNI global ref to the registered WebViewClickCallback, or nullptr.
    // Invoked from the WM_PARENTNOTIFY hook in EmbedWndProc each time the
    // WebView2 child HWND receives a mouse-button-down message -- see
    // Operation 13 of the heavyweight-embedding Canvas.  Used by the
    // Swing wrapper to dismiss any open JPopupMenu when the user clicks
    // into the WebView (AWT's MouseGrabber AWTEventListener never sees
    // those clicks because they reach the WebView2 HWND directly).
    jobject click_callback = nullptr;
    // JNI global ref to the registered WebViewDialogCallback, or nullptr.
    // Storage only in this canvas (STORY-004-001) -- STORY-004-003 wires
    // ICoreWebView2::add_ScriptDialogOpening + AreDefaultScriptDialogsEnabled(FALSE)
    // to actually invoke this callback for JS alert / confirm / prompt.
    // The Windows file picker remains OS-native (WebView2 exposes no
    // public hook for <input type=file>), so this callback is never
    // invoked for the file-picker event kind on Windows.
    jobject dialog_callback = nullptr;

    // JNI global ref to the registered WebViewDownloadCallback, or
    // nullptr — Canvas 25.  The DownloadStarting handler fires off this
    // field.  A download can outlive the page and the navigation that
    // started it, so it is cleared in destroy_engine before the
    // controller is released.
    jobject download_callback = nullptr;

    // EventRegistrationToken for the DownloadStarting handler, removed in
    // destroy_engine.  Only meaningful when downloads_available is true.
    EventRegistrationToken download_starting_token{};

    // True when the runtime exposed ICoreWebView2_4 and the
    // DownloadStarting handler was actually registered.  On an older
    // runtime this stays false and WebView2 keeps its built-in download
    // handling — a supported configuration, not an error.
    bool downloads_available = false;

    // Monotonic per-engine download identity.  Several downloads may be
    // in flight at once and the destination is neither known at request
    // time nor unique across sequential downloads.
    long long next_download_id = 1;

    // In-flight downloads, keyed by the raw operation pointer.  Entries
    // are erased on the terminal transition and the whole map is cleared
    // at teardown; a stale key reused by a later allocation would
    // misattribute events.
    std::mutex downloads_mutex;
    std::map<ICoreWebView2DownloadOperation *, struct WinDownloadCtx *> downloads;

    // JNI global ref to the registered WebViewPasswordCallback, or nullptr.
    // Stored here on Windows (Canvas 26); Canvas 28 wires the __webview_pw__
    // branch of the WebMessageReceived handler off this field.
    jobject password_callback = nullptr;

    // JNI global ref to the registered WebViewPopupCallback, or nullptr —
    // Canvas 15.  Stored here on Windows; the follow-up Windows coverage
    // canvas wires ICoreWebView2::add_NewWindowRequested off this field
    // (put_NewWindow with a controller from the same environment) plus the
    // child's add_WindowCloseRequested so window.open opens a native window
    // linked to the opener.  Until then window.open stays blocked on Windows.
    jobject popup_callback = nullptr;

    // EventRegistrationToken for the NewWindowRequested handler registered in
    // the controller-ready callback (Canvas 17).  Not explicitly removed in
    // destroy_engine -- the controller / webview Release detaches it
    // transitively, like the other tokens.
    EventRegistrationToken new_window_token{};

    // Canvas 34 D9: JNI global ref to the WebViewNavigationCallback, or
    // nullptr, read by NavigationStartingHandler on every navigation of the
    // view and its frames; and the two event tokens it is registered under,
    // removed in destroy_engine.
    jobject navigation_callback = nullptr;
    EventRegistrationToken navigation_starting_token{};
    EventRegistrationToken frame_navigation_starting_token{};
    bool navigation_registered = false;

    // The ICoreWebView2Environment this engine was created from, AddRef'd at
    // environment-ready and Release'd in destroy_engine (Canvas 17).  The
    // NewWindowRequested handler creates the child popup controller from THIS
    // environment so the popup is a linked view (window.opener / postMessage
    // work) -- a controller from a different environment is not linked.
    ICoreWebView2Environment *environment = nullptr;

    // Canvas 20 (popup adoption).  An ADOPTED popup engine reuses the OPENER
    // engine's WebView2 worker thread, because its ICoreWebView2Controller was
    // created on that thread and WebView2 objects are apartment-bound to their
    // creating thread (they cannot be moved to a fresh thread).  For such an
    // engine `thread_id` is the opener's worker thread and this flag is TRUE,
    // so destroy_engine must NOT post WM_EMBED_QUIT (that would tear down the
    // opener's message loop and the opener engine with it).  Instead it
    // synchronously Close()es the controller/webview on that shared thread.
    // ON-DEVICE-VALIDATION-REQUIRED: shared-worker-thread lifecycle (opener
    // outliving / being disposed before the adopted child).
    bool shared_thread = false;

    // Canvas 21 (popup UA inheritance).  The last NON-empty custom User-Agent
    // passed to webview_embed_set_user_agent, tracked so the
    // NewWindowRequested handler can propagate the opener's override to a
    // popup child before its in-flight initial navigation.  Empty means "no
    // override" (engine default) — ICoreWebView2Settings2::get_UserAgent
    // cannot distinguish an override from the default, so we cache it here.
    // Written / read on this engine's WebView2 worker thread only.
    std::wstring user_agent;

    // Canvas 21 Op 7.3: the PRISTINE engine User-Agent, captured from
    // get_UserAgent on the first setter call -- necessarily before any
    // override, since the setter is the only thing that overrides. WebView2
    // has no "clear" verb: put_UserAgent(L"") returns S_OK and leaves the
    // previous override in force, so a reset has to write this string back
    // literally. Worker thread only.
    std::wstring default_user_agent;

    // Canvas 21 (1.5.0): per-destination User-Agent resolver
    // (java.util.function.Function<String,String>) held as a JNI global ref.
    // Consulted at the popup-child creation site with the CHILD's own target
    // URL; a decline falls back to the tracked `user_agent` above.  Deleted on
    // replacement and on engine destroy.
    jobject ua_resolver = nullptr;
};

static void fire_focus_callback(Engine *e, bool became) {
    if (!e || !e->focus_callback) return;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(e->focus_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(cls, "invoke", "(Z)V");
            if (m) {
                env->CallVoidMethod(e->focus_callback, m, (jboolean)became);
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) jvm->DetachCurrentThread();
}

// Invoke the Java WebViewClickCallback registered on the engine, if any.
// Called from EmbedWndProc when WM_PARENTNOTIFY arrives with a mouse-down
// trigger; the Java callback marshals to the EDT internally before
// touching Swing state.  Mirrors fire_focus_callback shape but the Java
// method has signature ()V (no boolean payload).
static void fire_click_callback(Engine *e) {
    if (!e || !e->click_callback) return;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(e->click_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(cls, "invoke", "()V");
            if (m) {
                env->CallVoidMethod(e->click_callback, m);
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) jvm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------
// JS-initiated UI dialog bridge for WebView2.
//
// Three helpers mirroring the Linux fire_dialog_* shape (canvas-12 /
// STORY-004-002), used by ScriptDialogHandler::Invoke below.  Each helper
// attaches the spawned worker thread to the JVM, calls one of the three
// WebViewDialogCallback methods, sanitises any pending Java exception, and
// detaches.  Method names / signatures are byte-identical across all three
// platform binaries -- the same Java WebViewDialogCallback interface is the
// target.
//
// No fire_dialog_file_picker on Windows: WebView2 exposes no public hook
// for <input type=file>, so the file picker continues to use the OS-native
// Common Item Dialog and WebViewFilePickerEvent never fires on Windows.
// Documented platform limitation per canvas-11 / STORY-004-003 AC4.
// ---------------------------------------------------------------------------

static void fire_dialog_alert(JavaVM *jvm, jobject callback,
                              const char *message,
                              const char *pageUrl,
                              const char *frameUrl) {
    if (!jvm || !callback) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return;
    }
    jstring jmsg = env->NewStringUTF(message ? message : "");
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onAlert",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jmsg, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmsg) env->DeleteLocalRef(jmsg);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    if (detach) jvm->DetachCurrentThread();
}

static jboolean fire_dialog_confirm(JavaVM *jvm, jobject callback,
                                    const char *message,
                                    const char *pageUrl,
                                    const char *frameUrl) {
    if (!jvm || !callback) return JNI_FALSE;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return JNI_FALSE;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return JNI_FALSE;
    }
    jboolean result = JNI_FALSE;
    jstring jmsg = env->NewStringUTF(message ? message : "");
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onConfirm",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
        if (m) {
            result = env->CallBooleanMethod(callback, m, jmsg, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                result = JNI_FALSE;
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmsg) env->DeleteLocalRef(jmsg);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    if (detach) jvm->DetachCurrentThread();
    return result;
}

// Caller MUST free() the returned string when done.  Returns nullptr
// for cancel (Java returned null) or any error path -- the completion
// lambda treats nullptr as cancel and skips the put_ResultText / Accept
// calls so WebView2 reports null to the page.
static char *fire_dialog_prompt(JavaVM *jvm, jobject callback,
                                const char *message,
                                const char *defaultValue,
                                const char *pageUrl,
                                const char *frameUrl) {
    if (!jvm || !callback) return nullptr;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return nullptr;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return nullptr;
    }
    char *result = nullptr;
    jstring jmsg = env->NewStringUTF(message ? message : "");
    jstring jdefault = env->NewStringUTF(defaultValue ? defaultValue : "");
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onPrompt",
            "(Ljava/lang/String;Ljava/lang/String;"
            "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        if (m) {
            jstring jresult = (jstring)env->CallObjectMethod(
                callback, m, jmsg, jdefault, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                jresult = nullptr;
            }
            if (jresult) {
                const char *cstr = env->GetStringUTFChars(jresult, nullptr);
                if (cstr) {
                    size_t n = strlen(cstr);
                    result = (char *)malloc(n + 1);
                    if (result) memcpy(result, cstr, n + 1);
                    env->ReleaseStringUTFChars(jresult, cstr);
                }
                env->DeleteLocalRef(jresult);
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmsg) env->DeleteLocalRef(jmsg);
    if (jdefault) env->DeleteLocalRef(jdefault);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    if (detach) jvm->DetachCurrentThread();
    return result;  // caller owns; free with free()
}

// Password-manager fire helpers (Canvas 28) -- mirror the fire_dialog_*
// shape.  Both target methods are void and non-blocking on the Java side
// (PasswordDispatcher marshals the save prompt to the EDT via invokeLater
// and runs store I/O on a worker), so unlike the dialog confirm/prompt
// path these may be called directly from the WebMessageReceived worker
// without a deferral.  The username / password arrive already base64url
// encoded and are passed through verbatim; Java decodes them.  No-op when
// the callback global ref is null.
static void fire_password_submitted(JavaVM *jvm, jobject callback,
                                    const char *frameUrl,
                                    const char *b64user,
                                    const char *b64pass) {
    if (!jvm || !callback) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return;
    }
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jstring juser = env->NewStringUTF(b64user ? b64user : "");
    jstring jpass = env->NewStringUTF(b64pass ? b64pass : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onLoginSubmitted",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jframe, juser, jpass);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jframe) env->DeleteLocalRef(jframe);
    if (juser) env->DeleteLocalRef(juser);
    if (jpass) env->DeleteLocalRef(jpass);
    if (detach) jvm->DetachCurrentThread();
}

static void fire_password_fill_requested(JavaVM *jvm, jobject callback,
                                         const char *frameUrl) {
    if (!jvm || !callback) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return;
    }
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(cls, "onFillRequested",
                                       "(Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jframe) env->DeleteLocalRef(jframe);
    if (detach) jvm->DetachCurrentThread();
}

// IUnknown helper -- gives each WebView2 callback proper refcounting and
// QueryInterface support.  The interfaces we implement are all single-
// inheritance (Iface : IUnknown), so a templated base keeps boilerplate low.
template <typename Iface>
class CallbackBase : public Iface {
public:
    ULONG STDMETHODCALLTYPE AddRef() override { return ++m_ref; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG n = --m_ref;
        if (n == 0) delete this;
        return n;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, LPVOID *ppv) override {
        if (!ppv) return E_POINTER;
        if (iid == __uuidof(Iface) || iid == IID_IUnknown) {
            *ppv = static_cast<Iface*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
protected:
    virtual ~CallbackBase() = default;
private:
    std::atomic<ULONG> m_ref{1};
};

class EnvHandler : public CallbackBase<
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler> {
public:
    using Cb = std::function<void(HRESULT, ICoreWebView2Environment*)>;
    explicit EnvHandler(Cb cb) : m_cb(std::move(cb)) {}
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result,
                                     ICoreWebView2Environment *env) override {
        m_cb(result, env);
        return S_OK;
    }
private:
    Cb m_cb;
};

class ControllerHandler : public CallbackBase<
    ICoreWebView2CreateCoreWebView2ControllerCompletedHandler> {
public:
    using Cb = std::function<void(HRESULT, ICoreWebView2Controller*)>;
    explicit ControllerHandler(Cb cb) : m_cb(std::move(cb)) {}
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result,
                                     ICoreWebView2Controller *ctrl) override {
        m_cb(result, ctrl);
        return S_OK;
    }
private:
    Cb m_cb;
};

// Canvas 22: completed-handler for ICoreWebView2Profile2::ClearBrowsingData
// (the raw COM method — the docs' "Async" suffix is only the WinRT/C#
// projection name).  The purge result is not surfaced to Java; the base's
// Release deletes the handler.
class ClearCacheHandler : public CallbackBase<
    ICoreWebView2ClearBrowsingDataCompletedHandler> {
public:
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT /*errorCode*/) override {
        return S_OK;
    }
};

// ---------------------------------------------------------------------------
// Canvas 29: print-to-PDF request.  One per print; holds a global ref to the
// Java WebViewPdfCallback.  pdf_finish upcalls onPdfFinished exactly once
// (D1), deletes the ref and the job.  Callable from any thread.
// ---------------------------------------------------------------------------
struct PdfJob {
    JavaVM *jvm = nullptr;
    jobject cb = nullptr;
    bool done = false;
};

static PdfJob *pdf_new_job(JNIEnv *env, jobject cb) {
    PdfJob *job = new PdfJob();
    env->GetJavaVM(&job->jvm);
    job->cb = cb ? env->NewGlobalRef(cb) : nullptr;
    return job;
}

static void pdf_finish(PdfJob *job, bool ok, const char *err) {
    if (!job || job->done) return;
    job->done = true;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (job->jvm && job->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        job->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env && job->cb) {
        jclass cls = env->GetObjectClass(job->cb);
        jmethodID m = cls ? env->GetMethodID(cls, "onPdfFinished",
                                             "(ZLjava/lang/String;)V")
                          : nullptr;
        if (m) {
            jstring jerr = (ok || !err) ? nullptr : env->NewStringUTF(err);
            env->CallVoidMethod(job->cb, m, (jboolean)(ok ? JNI_TRUE : JNI_FALSE),
                                jerr);
            if (jerr) env->DeleteLocalRef(jerr);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (cls) env->DeleteLocalRef(cls);
        env->DeleteGlobalRef(job->cb);
        job->cb = nullptr;
    }
    if (detach) job->jvm->DetachCurrentThread();
    delete job;
}

static const char *const kPdfNotAttached = "The WebView is not attached yet.";

static void pdf_finish_hr(PdfJob *job, HRESULT hr) {
    char buf[96];
    snprintf(buf, sizeof(buf),
             "The page could not be printed to PDF (HRESULT 0x%08lX).",
             (unsigned long)hr);
    pdf_finish(job, false, buf);
}

// Canvas 29 D10: completed-handler for ICoreWebView2_7::PrintToPdf.
class PrintToPdfHandler : public CallbackBase<
    ICoreWebView2PrintToPdfCompletedHandler> {
public:
    explicit PrintToPdfHandler(PdfJob *job) : m_job(job) {}
    // Take the job back when PrintToPdf fails synchronously and will never
    // invoke this handler.
    PdfJob *take() {
        PdfJob *job = m_job;
        m_job = nullptr;
        return job;
    }
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT errorCode,
                                     BOOL isSuccessful) override {
        PdfJob *job = take();
        if (!job) return S_OK;
        if (FAILED(errorCode)) {
            pdf_finish_hr(job, errorCode);
        } else {
            pdf_finish(job, isSuccessful ? true : false,
                       "The page could not be printed to PDF.");
        }
        return S_OK;
    }
private:
    PdfJob *m_job;
};

// Forward declarations.
static void engine_on_message(Engine *e, LPCWSTR msg);
static std::wstring utf8_to_wide(const char *s);
static std::string wide_to_utf8(LPCWSTR w);
static bool dispatch_to_thread(Engine *e, DispatchFn fn);

// One-shot JNI completion for asynchronous WebView2 cookie queries.  The
// callback remains valid after the JNI entry returns and is released after
// exactly one result has been delivered.
struct CookieCompletion {
    JavaVM *jvm = nullptr;
    jobject callback = nullptr;
};

static CookieCompletion *new_cookie_completion(JNIEnv *env, jobject callback) {
    if (!env || !callback) return nullptr;
    auto *completion = new CookieCompletion();
    env->GetJavaVM(&completion->jvm);
    completion->callback = env->NewGlobalRef(callback);
    if (!completion->jvm || !completion->callback) {
        if (completion->callback) env->DeleteGlobalRef(completion->callback);
        delete completion;
        return nullptr;
    }
    return completion;
}

static void complete_cookie_query(CookieCompletion *completion,
                                  const std::string &header,
                                  const char *error) {
    if (!completion) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (completion->jvm->GetEnv(
            reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (completion->jvm->AttachCurrentThread(
                reinterpret_cast<void **>(&env), nullptr) == JNI_OK) {
            detach = true;
        }
    }
    if (env) {
        jclass cls = env->GetObjectClass(completion->callback);
        jmethodID method = cls ? env->GetMethodID(
            cls, "completed", "(Ljava/lang/String;Ljava/lang/String;)V")
            : nullptr;
        jstring jheader = env->NewStringUTF(header.c_str());
        jstring jerror = error ? env->NewStringUTF(error) : nullptr;
        if (method) {
            env->CallVoidMethod(completion->callback, method, jheader, jerror);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (jerror) env->DeleteLocalRef(jerror);
        if (jheader) env->DeleteLocalRef(jheader);
        if (cls) env->DeleteLocalRef(cls);
        env->DeleteGlobalRef(completion->callback);
    }
    if (detach) completion->jvm->DetachCurrentThread();
    delete completion;
}

class GetCookiesHandler : public CallbackBase<
    ICoreWebView2GetCookiesCompletedHandler> {
public:
    explicit GetCookiesHandler(CookieCompletion *completion)
        : m_completion(completion) {}

    HRESULT STDMETHODCALLTYPE Invoke(
            HRESULT result, ICoreWebView2CookieList *cookie_list) override {
        if (FAILED(result) || !cookie_list) {
            complete_cookie_query(m_completion, "", "WebView2 cookie query failed");
            m_completion = nullptr;
            return S_OK;
        }

        UINT count = 0;
        if (FAILED(cookie_list->get_Count(&count))) {
            complete_cookie_query(m_completion, "", "WebView2 cookie list is unavailable");
            m_completion = nullptr;
            return S_OK;
        }
        struct Match { size_t path_len; std::string pair; };
        std::vector<Match> matches;
        for (UINT index = 0; index < count; ++index) {
            ICoreWebView2Cookie *cookie = nullptr;
            if (FAILED(cookie_list->GetValueAtIndex(index, &cookie)) || !cookie) {
                continue;
            }
            LPWSTR name = nullptr;
            LPWSTR value = nullptr;
            LPWSTR path = nullptr;
            if (SUCCEEDED(cookie->get_Name(&name)) && name &&
                    SUCCEEDED(cookie->get_Value(&value)) && value) {
                size_t path_len = 0;
                if (SUCCEEDED(cookie->get_Path(&path)) && path) {
                    path_len = wcslen(path);
                }
                matches.push_back(
                    {path_len, wide_to_utf8(name) + "=" + wide_to_utf8(value)});
            }
            if (name) CoTaskMemFree(name);
            if (value) CoTaskMemFree(value);
            if (path) CoTaskMemFree(path);
            cookie->Release();
        }
        // GetCookies does not order by path; RFC 6265 5.4 wants longer paths
        // first.  Stable keeps WebView2's order for equal lengths.
        std::stable_sort(matches.begin(), matches.end(),
            [](const Match &a, const Match &b) {
                return a.path_len > b.path_len;
            });
        std::string header;
        for (const Match &m : matches) {
            if (!header.empty()) header += "; ";
            header += m.pair;
        }
        complete_cookie_query(m_completion, header, nullptr);
        m_completion = nullptr;
        return S_OK;
    }

protected:
    ~GetCookiesHandler() override {
        if (m_completion) {
            complete_cookie_query(m_completion, "", "WebView2 cookie query was cancelled");
        }
    }

private:
    CookieCompletion *m_completion;
};

class FocusHandler : public CallbackBase<
    ICoreWebView2FocusChangedEventHandler> {
public:
    FocusHandler(Engine *e, bool became) : m_engine(e), m_became(became) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2Controller *,
                                     IUnknown *) override {
        fire_focus_callback(m_engine, m_became);
        return S_OK;
    }
private:
    Engine *m_engine;
    bool m_became;
};

class MsgHandler : public CallbackBase<
    ICoreWebView2WebMessageReceivedEventHandler> {
public:
    explicit MsgHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2WebMessageReceivedEventArgs *args) override {
        if (!args) return S_OK;
        LPWSTR msg = nullptr;
        if (FAILED(args->TryGetWebMessageAsString(&msg)) || !msg) {
            // Fallback: postMessage(object) -> JSON
            args->get_WebMessageAsJson(&msg);
        }
        if (msg) {
            // Password-manager channel (Canvas 28): the shared SHIM_JS posts
            // `__webview_pw__:<payload>` on the single WebView2 message
            // channel.  Demux by content -- a `__webview_pw__:` prefix is a
            // login-submission ("S|b64user|b64pass") or fill-request ("F")
            // and is routed to the password callback; everything else falls
            // through to the normal bind dispatch.
            std::string full = wide_to_utf8(msg);
            static const std::string PW_PREFIX = "__webview_pw__:";
            if (full.rfind(PW_PREFIX, 0) == 0) {
                std::string payload = full.substr(PW_PREFIX.size());
                // Committed source URL, read natively (the trusted origin).
                std::string src;
                LPWSTR src_w = nullptr;
                if (SUCCEEDED(args->get_Source(&src_w)) && src_w) {
                    src = wide_to_utf8(src_w);
                    CoTaskMemFree(src_w);
                } else if (m_engine && m_engine->webview) {
                    LPWSTR page_w = nullptr;
                    if (SUCCEEDED(m_engine->webview->get_Source(&page_w))
                            && page_w) {
                        src = wide_to_utf8(page_w);
                        CoTaskMemFree(page_w);
                    }
                }
                JavaVM *jvm = m_engine ? m_engine->jvm : nullptr;
                jobject cb = m_engine ? m_engine->password_callback : nullptr;
                if (cb && !payload.empty()) {
                    if (payload[0] == 'S') {
                        // S|b64user|b64pass -- the base64url fields never
                        // contain '|', so a plain split is unambiguous.
                        std::string rest = payload.substr(1);
                        if (!rest.empty() && rest[0] == '|') rest = rest.substr(1);
                        size_t bar = rest.find('|');
                        std::string b64user = (bar == std::string::npos)
                            ? rest : rest.substr(0, bar);
                        std::string b64pass = (bar == std::string::npos)
                            ? std::string() : rest.substr(bar + 1);
                        fire_password_submitted(jvm, cb, src.c_str(),
                                                b64user.c_str(),
                                                b64pass.c_str());
                    } else if (payload[0] == 'F') {
                        fire_password_fill_requested(jvm, cb, src.c_str());
                    }
                }
                CoTaskMemFree(msg);
                return S_OK;
            }
            engine_on_message(m_engine, msg);
            CoTaskMemFree(msg);
        }
        return S_OK;
    }
private:
    Engine *m_engine;
};

// ScriptDialogHandler -- bridges WebView2's ScriptDialogOpening event to the
// per-engine Java DialogDispatcher (canvas-13 / STORY-004-003).  Invoke runs
// on the WebView2 worker thread per Microsoft's threading model; we must NOT
// block it synchronously waiting for the EDT (the completion-side
// dispatch_to_thread would self-deadlock).  Instead we GetDeferral, spawn a
// short-lived std::thread for the JNI hop into DialogDispatcher (which does
// SwingUtilities.invokeAndWait), and once the answer is in hand we
// dispatch_to_thread back onto the WebView2 worker to call
// Accept / put_ResultText / Complete in the right COM apartment.
//
// `args` and `deferral` are AddRef'd before the worker thread launches and
// Release'd inside the completion lambda -- WebView2 would otherwise release
// the args between the S_OK return and the completion.
//
// On null dialog_callback the safe-default path runs (alert: Accept; confirm:
// don't Accept -> page sees false; prompt: don't put_ResultText / Accept ->
// page sees null) so the page's JS thread always resumes.  before-unload
// routes to fire_dialog_confirm, matching Linux behaviour.  File picker is
// NOT intercepted -- WebView2 has no public hook.
class ScriptDialogHandler : public CallbackBase<
    ICoreWebView2ScriptDialogOpeningEventHandler> {
public:
    explicit ScriptDialogHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2ScriptDialogOpeningEventArgs *args) override {
        if (!args || !m_engine) return S_OK;

        COREWEBVIEW2_SCRIPT_DIALOG_KIND kind =
            COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT;
        args->get_Kind(&kind);

        LPWSTR uri_w = nullptr;
        LPWSTR msg_w = nullptr;
        LPWSTR def_w = nullptr;
        args->get_Uri(&uri_w);
        args->get_Message(&msg_w);
        if (kind == COREWEBVIEW2_SCRIPT_DIALOG_KIND_PROMPT) {
            args->get_DefaultText(&def_w);
        }
        std::string uri = wide_to_utf8(uri_w);
        std::string msg = wide_to_utf8(msg_w);
        std::string def = wide_to_utf8(def_w);
        if (uri_w) CoTaskMemFree(uri_w);
        if (msg_w) CoTaskMemFree(msg_w);
        if (def_w) CoTaskMemFree(def_w);

        ICoreWebView2Deferral *deferral = nullptr;
        HRESULT hr = args->GetDeferral(&deferral);
        if (FAILED(hr) || !deferral) {
            // Can't defer -- the SDK refused to hand us a deferral.  Rare;
            // log and let WebView2's default-suppressed flow take its
            // course (page receives the JS-spec default for each kind).
            WV_LOG("GetDeferral failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            return S_OK;
        }
        args->AddRef();

        Engine *e = m_engine;
        std::thread([e, args, deferral, kind, uri, msg, def] {
            jobject cb = e ? e->dialog_callback : nullptr;
            JavaVM *jvm = e ? e->jvm : nullptr;
            jboolean confirmed = JNI_FALSE;
            char *prompt_answer = nullptr;
            switch (kind) {
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT:
                    if (cb) fire_dialog_alert(
                        jvm, cb, msg.c_str(),
                        uri.c_str(), uri.c_str());
                    break;
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_CONFIRM:
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD:
                    if (cb) confirmed = fire_dialog_confirm(
                        jvm, cb, msg.c_str(),
                        uri.c_str(), uri.c_str());
                    break;
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_PROMPT:
                    if (cb) prompt_answer = fire_dialog_prompt(
                        jvm, cb, msg.c_str(), def.c_str(),
                        uri.c_str(), uri.c_str());
                    break;
                default:
                    break;
            }

            // Marshal back onto the WebView2 worker thread; ICoreWebView2*
            // methods are apartment-bound to the engine's worker, so
            // Accept / put_ResultText / Complete must NOT run on the Java
            // worker thread we're currently in.
            dispatch_to_thread(e, [args, deferral, kind, confirmed,
                                   prompt_answer] {
                switch (kind) {
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT:
                        args->Accept();
                        break;
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_CONFIRM:
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD:
                        if (confirmed == JNI_TRUE) args->Accept();
                        // else: don't Accept -- WebView2 returns false to
                        // the page.
                        break;
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_PROMPT:
                        if (prompt_answer != nullptr) {
                            std::wstring wtxt = utf8_to_wide(prompt_answer);
                            args->put_ResultText(wtxt.c_str());
                            args->Accept();
                            free(prompt_answer);
                        }
                        // else: don't put_ResultText, don't Accept --
                        // WebView2 returns null to the page.
                        break;
                    default:
                        // Unknown future SDK kind: just Complete the
                        // deferral without Accept; the page sees the
                        // JS-spec default.
                        break;
                }
                deferral->Complete();
                deferral->Release();
                args->Release();
            });
        }).detach();

        return S_OK;
    }
private:
    Engine *m_engine;
};

// ---------------------------------------------------------------------------
// Browser-initiated file downloads — Canvas 25 (Windows / WebView2).
//
// WebView2 handles downloads itself by default: a download flyout rendered
// inside the embedded view, writing to a path under the user's Downloads
// folder.  Both halves are wrong in a Swing application — the host has no
// say in the destination and no report of the outcome, and the flyout is
// browser chrome appearing inside what is meant to be a native desktop
// window.  ICoreWebView2_4::add_DownloadStarting lets us take both over.
//
// Threading, as for ScriptDialogHandler: Invoke runs on the WebView2 worker
// thread and every ICoreWebView2* method is apartment-bound to it, but the
// Java handler runs on the Swing EDT (and the stock one opens a modal
// JFileChooser).  Blocking the worker would deadlock, and dispatch_to_thread
// from inside the worker would self-deadlock.  So: GetDeferral, AddRef, hop
// to Java on a short-lived worker, then dispatch_to_thread back to apply the
// outcome and Complete the deferral exactly once.
//
// Runtime tolerance: add_DownloadStarting needs ICoreWebView2_4.  On an older
// runtime the QueryInterface fails, nothing is registered, and WebView2 keeps
// its built-in handling.  A half-registered state — Handled set but never
// answered — would hang every download, which is worse than not intercepting
// at all.
// ---------------------------------------------------------------------------

// One in-flight download.  Held in Engine::downloads keyed by the operation
// pointer so the event tokens can be removed and the entry erased when the
// download reaches a terminal state.
struct WinDownloadCtx {
    Engine *engine = nullptr;
    long long id = 0;
    long long received = 0;
    long long total = -1;
    EventRegistrationToken bytes_token{};
    EventRegistrationToken state_token{};
    bool terminal = false;
};

// Invoke WebViewDownloadCallback.onDownloadRequested; copy the returned
// absolute path into *out_path.  Returns false when there is no callback,
// the method is missing, or Java refused (null return).  Runs on the
// short-lived worker thread, never on the WebView2 worker.
static bool fire_win_download_requested(JavaVM *jvm, jobject cb, long long id,
                                        const std::string &url,
                                        const std::string &suggested,
                                        const std::string &mime,
                                        long long total,
                                        const std::string &page_url,
                                        std::string *out_path) {
    if (!jvm || !cb || !out_path) return false;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) {
            return false;
        }
        detach = true;
    }
    bool got = false;
    if (env) {
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID m = env->GetMethodID(
                cls, "onDownloadRequested",
                "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;J"
                "Ljava/lang/String;)Ljava/lang/String;");
            if (m) {
                jstring jurl = env->NewStringUTF(url.c_str());
                jstring jname = env->NewStringUTF(suggested.c_str());
                jstring jmime = env->NewStringUTF(mime.c_str());
                jstring jpage = env->NewStringUTF(page_url.c_str());
                jobject res = env->CallObjectMethod(
                    cb, m, (jlong)id, jurl, jname, jmime, (jlong)total, jpage);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    res = nullptr;
                }
                if (res) {
                    const char *c =
                        env->GetStringUTFChars((jstring)res, nullptr);
                    if (c) {
                        out_path->assign(c);
                        env->ReleaseStringUTFChars((jstring)res, c);
                        got = !out_path->empty();
                    }
                    env->DeleteLocalRef(res);
                }
                if (jurl) env->DeleteLocalRef(jurl);
                if (jname) env->DeleteLocalRef(jname);
                if (jmime) env->DeleteLocalRef(jmime);
                if (jpage) env->DeleteLocalRef(jpage);
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) jvm->DetachCurrentThread();
    return got;
}

static void fire_win_download_progress(Engine *e, long long id,
                                       long long received, long long total) {
    if (!e || !e->download_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(e->download_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(cls, "onDownloadProgress", "(JJJ)V");
            if (m) {
                env->CallVoidMethod(e->download_callback, m, (jlong)id,
                                    (jlong)received, (jlong)total);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) jvm->DetachCurrentThread();
}

static void fire_win_download_completed(Engine *e, long long id, bool success,
                                        const std::string &reason,
                                        long long received) {
    if (!e || !e->download_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(e->download_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(
                cls, "onDownloadCompleted", "(JZLjava/lang/String;J)V");
            if (m) {
                jstring jreason = env->NewStringUTF(reason.c_str());
                env->CallVoidMethod(e->download_callback, m, (jlong)id,
                                    (jboolean)(success ? JNI_TRUE : JNI_FALSE),
                                    jreason, (jlong)received);
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (jreason) env->DeleteLocalRef(jreason);
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) jvm->DetachCurrentThread();
}

// Human-readable text for a COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON.  The
// default arm keeps the numeric value so an unmapped future reason still
// produces a usable message rather than an empty string.
static std::string win_download_interrupt_reason(
        COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON r) {
    switch (r) {
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NONE:
            return "Download interrupted";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_FAILED:
            return "Could not write the file";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_ACCESS_DENIED:
            return "Access to the destination was denied";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_NO_SPACE:
            return "Not enough disk space";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_NAME_TOO_LONG:
            return "The file name is too long";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TOO_LARGE:
            return "The file is too large";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_MALICIOUS:
            return "The file was flagged as malicious";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TRANSIENT_ERROR:
            return "A temporary problem writing the file";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_BLOCKED_BY_POLICY:
            return "Blocked by policy";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_SECURITY_CHECK_FAILED:
            return "A security check failed";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TOO_SHORT:
            return "The file ended before it was complete";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_HASH_MISMATCH:
            return "The file hash did not match";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_FAILED:
            return "The network connection failed";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_TIMEOUT:
            return "The network connection timed out";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_DISCONNECTED:
            return "The network connection was lost";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_SERVER_DOWN:
            return "The server is unavailable";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_INVALID_REQUEST:
            return "The server rejected the request";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_FAILED:
            return "The server failed";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_NO_RANGE:
            return "The server does not support resuming";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_BAD_CONTENT:
            return "The server sent no usable content";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_UNAUTHORIZED:
            return "Not authorized to download this file";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_CERTIFICATE_PROBLEM:
            return "The server's certificate was rejected";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_FORBIDDEN:
            return "The server refused the request";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_UNEXPECTED_RESPONSE:
            return "The server sent an unexpected response";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_CANCELED:
            return "The download was cancelled";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_SHUTDOWN:
            return "The application shut down";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_PAUSED:
            return "The download was paused";
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_DOWNLOAD_PROCESS_CRASHED:
            return "The download process stopped";
        default: {
            char buf[64];
            snprintf(buf, sizeof(buf), "Download interrupted (reason %d)",
                     (int)r);
            return std::string(buf);
        }
    }
}

// Look up (without removing) the context for an operation.  Caller MUST hold
// the engine's download mutex or be on the WebView2 worker thread.
static WinDownloadCtx *win_download_ctx(Engine *e,
                                        ICoreWebView2DownloadOperation *op) {
    if (!e || !op) return nullptr;
    std::lock_guard<std::mutex> lk(e->downloads_mutex);
    auto it = e->downloads.find(op);
    return it == e->downloads.end() ? nullptr : it->second;
}

class DownloadBytesReceivedHandler : public CallbackBase<
    ICoreWebView2BytesReceivedChangedEventHandler> {
public:
    explicit DownloadBytesReceivedHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2DownloadOperation *op,
                                     IUnknown *) override {
        WinDownloadCtx *ctx = win_download_ctx(m_engine, op);
        if (!ctx || ctx->terminal) return S_OK;
        INT64 received = 0;
        if (SUCCEEDED(op->get_BytesReceived(&received))) {
            ctx->received = (long long)received;
        }
        // No native throttling: the Java dispatcher coalesces progress,
        // and throttling here would make the last reported value lag the
        // terminal event.
        fire_win_download_progress(m_engine, ctx->id, ctx->received,
                                   ctx->total);
        return S_OK;
    }
private:
    Engine *m_engine;
};

class DownloadStateChangedHandler : public CallbackBase<
    ICoreWebView2StateChangedEventHandler> {
public:
    explicit DownloadStateChangedHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2DownloadOperation *op,
                                     IUnknown *) override {
        Engine *e = m_engine;
        if (!e || !op) return S_OK;
        COREWEBVIEW2_DOWNLOAD_STATE state =
            COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS;
        if (FAILED(op->get_State(&state))) return S_OK;
        if (state == COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS) return S_OK;

        // Claim the terminal report and detach the entry in one step so a
        // second transition finds nothing.
        WinDownloadCtx *ctx = nullptr;
        {
            std::lock_guard<std::mutex> lk(e->downloads_mutex);
            auto it = e->downloads.find(op);
            if (it == e->downloads.end()) return S_OK;
            ctx = it->second;
            if (!ctx || ctx->terminal) return S_OK;
            ctx->terminal = true;
            e->downloads.erase(it);
        }

        INT64 received = 0;
        if (SUCCEEDED(op->get_BytesReceived(&received))) {
            ctx->received = (long long)received;
        }

        if (state == COREWEBVIEW2_DOWNLOAD_STATE_COMPLETED) {
            fire_win_download_completed(e, ctx->id, true, std::string(),
                                        ctx->received);
        } else {
            COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON reason =
                COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NONE;
            op->get_InterruptReason(&reason);
            fire_win_download_completed(
                e, ctx->id, false, win_download_interrupt_reason(reason),
                ctx->received);
        }

        op->remove_BytesReceivedChanged(ctx->bytes_token);
        op->remove_StateChanged(ctx->state_token);
        delete ctx;
        op->Release();   // balances the AddRef taken in DownloadStartingHandler
        return S_OK;
    }
private:
    Engine *m_engine;
};

class DownloadStartingHandler : public CallbackBase<
    ICoreWebView2DownloadStartingEventHandler> {
public:
    explicit DownloadStartingHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *sender,
        ICoreWebView2DownloadStartingEventArgs *args) override {
        Engine *e = m_engine;
        if (!args || !e) return S_OK;

        ICoreWebView2DownloadOperation *op = nullptr;
        if (FAILED(args->get_DownloadOperation(&op)) || !op) return S_OK;

        // Read everything while still on the WebView2 worker thread.
        LPWSTR uri_w = nullptr;
        LPWSTR mime_w = nullptr;
        LPWSTR path_w = nullptr;
        LPWSTR page_w = nullptr;
        INT64 total64 = 0;
        op->get_Uri(&uri_w);
        op->get_MimeType(&mime_w);
        op->get_TotalBytesToReceive(&total64);
        args->get_ResultFilePath(&path_w);
        if (sender) sender->get_Source(&page_w);

        std::string url = wide_to_utf8(uri_w);
        std::string mime = wide_to_utf8(mime_w);
        std::string default_path = wide_to_utf8(path_w);
        std::string page_url = wide_to_utf8(page_w);
        if (uri_w) CoTaskMemFree(uri_w);
        if (mime_w) CoTaskMemFree(mime_w);
        if (path_w) CoTaskMemFree(path_w);
        if (page_w) CoTaskMemFree(page_w);

        // WebView2 exposes no bare suggested name, only a full default
        // path under the Downloads folder.  Its leaf is the suggestion;
        // the Java dispatcher sanitises it unconditionally, so nothing
        // Windows-specific is needed here.
        std::string suggested = default_path;
        size_t cut = suggested.find_last_of("\\/");
        if (cut != std::string::npos) suggested = suggested.substr(cut + 1);

        long long total = (total64 <= 0) ? -1 : (long long)total64;
        long long id = e->next_download_id++;

        ICoreWebView2Deferral *deferral = nullptr;
        HRESULT hr = args->GetDeferral(&deferral);
        if (FAILED(hr) || !deferral) {
            // The SDK refused a deferral, so we have no way to answer
            // later.  Do NOT set Handled -- WebView2's own flow taking
            // over is far better than a download that hangs unanswered.
            WV_LOG("Download GetDeferral failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            op->Release();
            return S_OK;
        }
        args->AddRef();

        std::thread([e, args, deferral, op, id, url, suggested, mime, total,
                     page_url] {
            jobject cb = e ? e->download_callback : nullptr;
            JavaVM *jvm = e ? e->jvm : nullptr;
            std::string path;
            bool accepted = fire_win_download_requested(
                jvm, cb, id, url, suggested, mime, total, page_url, &path);

            // Every ICoreWebView2* method is apartment-bound to the
            // engine's worker thread, so the outcome must be applied
            // there, not on this Java worker.
            dispatch_to_thread(e, [e, args, deferral, op, id, total, accepted,
                                   path] {
                // Suppress the download flyout either way -- a refused
                // download must not flash browser chrome inside the Swing
                // window either.
                args->put_Handled(TRUE);
                if (accepted) {
                    std::wstring w = utf8_to_wide(path.c_str());
                    args->put_ResultFilePath(w.c_str());

                    auto *ctx = new WinDownloadCtx();
                    ctx->engine = e;
                    ctx->id = id;
                    ctx->total = total;
                    ctx->received = 0;
                    ctx->terminal = false;

                    // Subscribe BEFORE completing the deferral: a tiny or
                    // already-failed download can transition immediately,
                    // and subscribing afterwards can miss it entirely.
                    auto *bh = new DownloadBytesReceivedHandler(e);
                    op->add_BytesReceivedChanged(bh, &ctx->bytes_token);
                    bh->Release();
                    auto *sh = new DownloadStateChangedHandler(e);
                    op->add_StateChanged(sh, &ctx->state_token);
                    sh->Release();

                    {
                        std::lock_guard<std::mutex> lk(e->downloads_mutex);
                        e->downloads[op] = ctx;
                    }
                    // op's AddRef (from get_DownloadOperation) is handed to
                    // the ctx; DownloadStateChangedHandler releases it.
                } else {
                    // Refusal is put_Cancel, NOT merely an unset path --
                    // leaving ResultFilePath alone lets WebView2 write its
                    // own default under Downloads, which is exactly the
                    // behaviour this canvas removes.
                    args->put_Cancel(TRUE);
                    op->Release();
                }
                deferral->Complete();
                deferral->Release();
                args->Release();
            });
        }).detach();

        return S_OK;
    }
private:
    Engine *m_engine;
};

// Abandon every download still in flight for `e`.  Called from the engine
// teardown path on the WebView2 worker thread, before the controller is
// released, so no stale operation pointer survives to misattribute events.
static void win_download_drop_engine(Engine *e) {
    if (!e) return;
    std::vector<std::pair<ICoreWebView2DownloadOperation *,
                          WinDownloadCtx *>> mine;
    {
        std::lock_guard<std::mutex> lk(e->downloads_mutex);
        for (auto &kv : e->downloads) mine.push_back(kv);
        e->downloads.clear();
    }
    for (auto &kv : mine) {
        ICoreWebView2DownloadOperation *op = kv.first;
        WinDownloadCtx *ctx = kv.second;
        if (op && ctx) {
            op->remove_BytesReceivedChanged(ctx->bytes_token);
            op->remove_StateChanged(ctx->state_token);
            op->Release();
        }
        delete ctx;
    }
}

// ---------------------------------------------------------------------------
// Popup (window.open) support — Canvas 17 (Windows / WebView2).
//
// window.open / target=_blank raises ICoreWebView2::NewWindowRequested.  Invoke
// runs on the WebView2 worker thread and must NOT block it, so we use the
// deferral pattern (like ScriptDialogHandler): GetDeferral, hop to Java on a
// short-lived worker for the allow/deny decision, then dispatch_to_thread back
// onto the WebView2 worker to create the child (via put_NewWindow with a
// controller built from the SAME environment, so window.opener / postMessage
// work) and Complete the deferral.  The engine owns the popup HWND; the child's
// WindowCloseRequested destroys it.
// ---------------------------------------------------------------------------

// A child popup's native state: an engine-owned top-level window hosting a
// linked child WebView2.  Freed in PopupWndProc's WM_DESTROY.
struct PopupWindow {
    HWND hwnd = nullptr;
    ICoreWebView2Controller *controller = nullptr;
    ICoreWebView2 *webview = nullptr;
    Engine *opener = nullptr;   // for jvm + popup_callback (notifications)
    jlong popup_id = 0;
    std::string url;
    EventRegistrationToken close_token{};
};

static LRESULT CALLBACK PopupWndProc(HWND hwnd, UINT msg, WPARAM wp,
                                     LPARAM lp) {
    PopupWindow *pw = (PopupWindow *)GetWindowLongPtr(hwnd, GWLP_USERDATA);
    switch (msg) {
    case WM_SIZE:
        if (pw && pw->controller) {
            RECT r;
            GetClientRect(hwnd, &r);
            pw->controller->put_Bounds(r);
        }
        return 0;
    case WM_DESTROY:
        if (pw) {
            if (pw->webview) { pw->webview->Release(); pw->webview = nullptr; }
            if (pw->controller) {
                pw->controller->Close();
                pw->controller->Release();
                pw->controller = nullptr;
            }
            SetWindowLongPtr(hwnd, GWLP_USERDATA, 0);
            delete pw;
        }
        return 0;
    default:
        return DefWindowProc(hwnd, msg, wp, lp);
    }
}

static ATOM ensure_popup_class_registered() {
    static ATOM atom = 0;
    if (atom != 0) return atom;
    WNDCLASSEX wc{};
    wc.cbSize = sizeof(wc);
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = "WebViewEmbedPopup";
    wc.lpfnWndProc = PopupWndProc;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    atom = RegisterClassEx(&wc);
    return atom;
}

// Synchronous allow/deny hop into Java (runs on the JNI worker thread the
// NewWindowRequested handler spins up).  Returns false on null callback,
// attach failure, or any exception (block-on-error default).
static bool fire_popup_requested_win(Engine *e, const char *url, bool gesture,
                                     int w, int h, const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return false;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return false;
        detach = true;
    }
    bool allow = false;
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupRequested",
            "(Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)Z");
        if (mid) {
            jboolean r = env->CallBooleanMethod(e->popup_callback, mid, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = JNI_FALSE;
            }
            allow = (r == JNI_TRUE);
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
    return allow;
}

// Fire onPopupOpened (fire-and-forget; the Java dispatcher marshals to the
// EDT via invokeLater).  Runs on the WebView2 worker thread.
static void fire_popup_opened_win(Engine *e, jlong popup_id, const char *url,
                                  bool gesture, int w, int h,
                                  const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupOpened",
            "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(e->popup_callback, mid, popup_id, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// Fire onPopupClosed.  Runs on the WebView2 worker thread.
static void fire_popup_closed_win(Engine *e, jlong popup_id, const char *url,
                                  const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupClosed",
            "(JLjava/lang/String;Ljava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(e->popup_callback, mid, popup_id, ju, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------
// Popup ADOPTION support — Canvas 20 (Windows / WebView2).
//
// Mirrors the shipped macOS (Canvas 18) + Linux (Canvas 19) adoption backends
// against the SAME platform-agnostic Java contract (no Java changes).  On an
// ADOPT disposition the NewWindowRequested handler builds the linked child
// controller from the opener's SAME environment against a HIDDEN holder HWND
// (never shown), returns the child to WebView2 (so it drives the original
// request -- POST verb+body, window.opener), retains it in
// g_win_retained_popups keyed by a jlong popupId, and fires onPopupAdoptable.
// Later webview_embed_adopt_popup reparents the retained controller into the
// adopting component's realized AWT HWND via put_ParentWindow; an unclaimed
// child is reclaimed by webview_embed_discard_popup.
//
// VALIDATED ON-DEVICE: the core adopt path (retain a POST/opener-linked child,
// reparent it into a WebViewComponent tab via put_ParentWindow, POST body +
// window.opener preserved) has been confirmed working on a real WebView2 stack.
// The remaining ON-DEVICE-VALIDATION markers below flag the teardown / reclaim
// / race edges that the happy-path confirmation does not fully exercise: the
// COM ref-counting on the controller/webview/environment handoffs, the
// apartment-thread (worker-thread) affinity of every ICoreWebView2* call, and
// the JNI global-ref lifecycle across retain -> adopt/discard remain prime
// candidates for on-device scrutiny.
// ---------------------------------------------------------------------------

// Synchronous disposition hop into Java (runs on the JNI worker thread the
// NewWindowRequested handler spins up), returning the PopupDisposition ordinal
// (0=BLOCK, 1=NATIVE_WINDOW, 2=ADOPT).  Returns 0 (BLOCK) on null callback,
// attach failure, or any exception -- the safe block-on-error default.
// Mirrors fire_popup_requested_win but calls onPopupDisposition (returns int).
static jint fire_popup_disposition_win(Engine *e, const char *url, bool gesture,
                                       int w, int h, const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return 0;  // BLOCK
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return 0;
        detach = true;
    }
    jint disposition = 0;
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupDisposition",
            "(Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)I");
        if (mid) {
            jint r = env->CallIntMethod(e->popup_callback, mid, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = 0;
            }
            disposition = r;
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
    return disposition;
}

// Fire onPopupAdoptable (fire-and-forget; the Java dispatcher marshals to the
// EDT via invokeLater).  Runs on the WebView2 worker thread.  Same signature
// shape as fire_popup_opened_win, different method name.
static void fire_popup_adoptable_win(Engine *e, jlong popup_id, const char *url,
                                     bool gesture, int w, int h,
                                     const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupAdoptable",
            "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(e->popup_callback, mid, popup_id, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// A retained-but-unadopted popup child: a linked child WebView2 hosted in a
// HIDDEN holder HWND (never shown), held until webview_embed_adopt_popup
// reparents it or webview_embed_discard_popup reclaims it.  The controller /
// webview / environment are AddRef'd COM references owned by this struct; the
// popup/dialog callbacks are inherited JNI global refs owned by this struct.
struct RetainedPopup {
    ICoreWebView2Controller *controller = nullptr;  // AddRef'd
    ICoreWebView2 *webview = nullptr;               // AddRef'd
    ICoreWebView2Environment *environment = nullptr;// AddRef'd (opener's)
    HWND holder = nullptr;                           // hidden top-level HWND
    JavaVM *jvm = nullptr;
    jlong popup_id = 0;
    std::string url;
    std::string page;
    // Worker thread the controller was created on (== opener engine's
    // thread_id).  Every ICoreWebView2* call on controller/webview MUST run on
    // this apartment thread.
    DWORD worker_thread_id = 0;
    // Inherited callback global refs (new global refs on the opener's, so the
    // retained child keeps working for nested popups/dialogs until adoption
    // transfers ownership to the adopted engine).
    jobject popup_callback = nullptr;
    jobject dialog_callback = nullptr;
    // Retained-phase event handler tokens, removed before the adopted engine
    // registers its own handlers (analogous to Linux's
    // g_signal_handlers_disconnect_by_data).
    EventRegistrationToken new_window_token{};
    EventRegistrationToken script_dialog_token{};
    EventRegistrationToken close_token{};
};

// ON-DEVICE-VALIDATION-REQUIRED: the retained-popup registry is the Windows
// counterpart of the macOS g_retained_popups / Linux g_gtk_retained_popups.
static std::mutex g_win_retained_popups_mutex;
static std::map<jlong, RetainedPopup *> g_win_retained_popups;

// Post a fire-and-forget op onto a specific WebView2 worker thread (the
// apartment that owns a retained child's COM objects).  Falls back to running
// inline if the thread id is unknown (best effort; wrong apartment).
static void post_to_worker_thread(DWORD tid, DispatchFn fn) {
    if (!tid) { fn(); return; }
    auto *holder = new DispatchFn(std::move(fn));
    PostThreadMessage(tid, WM_EMBED_DISPATCH, 0, (LPARAM)holder);
}

// Hidden holder window class for retained popup children.  A plain
// DefWindowProc window that is NEVER shown; it exists only to give the child
// controller a valid parent HWND until adoption reparents it.
static ATOM ensure_popup_holder_class_registered() {
    static ATOM atom = 0;
    if (atom != 0) return atom;
    WNDCLASSEX wc{};
    wc.cbSize = sizeof(wc);
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = "WebViewEmbedPopupHolder";
    wc.lpfnWndProc = DefWindowProc;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    atom = RegisterClassEx(&wc);
    return atom;
}

// Fire onPopupClosed for a retained (never-shown) child, using the inherited
// jvm + popup_callback stored on the RetainedPopup.  Mirrors
// fire_popup_closed_win but sources jvm/callback from the shell rather than an
// Engine (the retained child has no opener-independent Engine yet).
static void fire_popup_closed_retained(RetainedPopup *rp) {
    if (!rp || !rp->popup_callback || !rp->jvm) return;
    JavaVM *jvm = rp->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(rp->url.c_str());
    jstring jp = env->NewStringUTF(rp->page.c_str());
    jclass cls = env->GetObjectClass(rp->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupClosed",
            "(JLjava/lang/String;Ljava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(rp->popup_callback, mid, rp->popup_id, ju, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// Tear down a retained-but-unadopted child.  MUST run on rp->worker_thread_id
// (the COM apartment owning controller/webview) -- callers dispatch it there.
// ON-DEVICE-VALIDATION-REQUIRED: Close/Release ordering + global-ref frees.
static void free_inherited_refs(JavaVM *jvm, jobject popup_cb,
                                jobject dialog_cb) {
    if (!jvm || (!popup_cb && !dialog_cb)) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    if (env) {
        if (popup_cb) env->DeleteGlobalRef(popup_cb);
        if (dialog_cb) env->DeleteGlobalRef(dialog_cb);
    }
    if (detach) jvm->DetachCurrentThread();
}

static void retained_popup_teardown(RetainedPopup *rp, bool fire_closed) {
    if (!rp) return;
    if (fire_closed) fire_popup_closed_retained(rp);
    if (rp->webview) { rp->webview->Release(); rp->webview = nullptr; }
    if (rp->controller) {
        rp->controller->Close();
        rp->controller->Release();
        rp->controller = nullptr;
    }
    if (rp->holder) { DestroyWindow(rp->holder); rp->holder = nullptr; }
    if (rp->environment) { rp->environment->Release(); rp->environment = nullptr; }
    free_inherited_refs(rp->jvm, rp->popup_callback, rp->dialog_callback);
    rp->popup_callback = nullptr;
    rp->dialog_callback = nullptr;
    delete rp;
}

// window.close() raised by a retained child BEFORE it is adopted -> discard it.
// Runs on the child's WebView2 worker thread (the opener's), so the COM
// teardown is on the correct apartment.  Claims under the mutex so an adoption
// racing the close wins-or-loses exactly once (whoever removes from the
// registry owns the teardown).
class RetainedPopupCloseHandler : public CallbackBase<
    ICoreWebView2WindowCloseRequestedEventHandler> {
public:
    explicit RetainedPopupCloseHandler(RetainedPopup *rp) : m_rp(rp) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2 *, IUnknown *) override {
        if (!m_rp) return S_OK;
        RetainedPopup *rp = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_win_retained_popups_mutex);
            auto it = g_win_retained_popups.find(m_rp->popup_id);
            if (it != g_win_retained_popups.end() && it->second == m_rp) {
                rp = it->second;
                g_win_retained_popups.erase(it);
            }
        }
        if (rp) retained_popup_teardown(rp, /*fire_closed=*/true);
        return S_OK;
    }
private:
    RetainedPopup *m_rp;
};

// window.close() from the popup (or the user closing the native window).
// Notify onPopupClosed, then DestroyWindow -> PopupWndProc WM_DESTROY frees
// the controller/webview and deletes the PopupWindow.
class PopupCloseHandler : public CallbackBase<
    ICoreWebView2WindowCloseRequestedEventHandler> {
public:
    explicit PopupCloseHandler(PopupWindow *pw) : m_pw(pw) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2 *, IUnknown *) override {
        if (!m_pw) return S_OK;
        fire_popup_closed_win(m_pw->opener, m_pw->popup_id,
                              m_pw->url.c_str(), "");
        if (m_pw->hwnd) DestroyWindow(m_pw->hwnd);
        return S_OK;
    }
private:
    PopupWindow *m_pw;
};

// Canvas 21: propagate the opener's tracked custom User-Agent to a popup child
// BEFORE its in-flight initial navigation (i.e. before deferral->Complete()).
// No-op when the opener has no override (empty tracked value -> child keeps the
// engine default) or on an old runtime lacking the ICoreWebView2Settings2
// interface (mirrors the embed setter's tolerance).  Nested popups reuse the
// opener engine, so they inherit the same override.  Covers BOTH the ADOPT and
// NATIVE_WINDOW dispositions.
// Canvas 21 (1.5.0): ask the per-destination User-Agent resolver which UA to
// present for a navigation to `url`.  Returns the empty string when there is no
// resolver, no url, or the resolver declines (a null/empty return) or throws --
// a resolver must never be able to break a navigation, so a pending exception
// is cleared and treated as a decline.  Runs on the WebView2 worker thread,
// which is not attached to the JVM, so it attaches and detaches symmetrically.
static std::wstring resolve_ua_for_win(Engine *e, const char *url) {
    std::wstring out;
    // Canvas 21 Op 7.4: "declined" and "never installed" imply different bugs,
    // so say which.
    if (!e || !e->jvm) { WV_LOG("resolve_ua: no engine/jvm"); return out; }
    if (!e->ua_resolver) {
        WV_LOG("resolve_ua: NO RESOLVER INSTALLED on this engine");
        return out;
    }
    if (!url || !*url) { WV_LOG("resolve_ua: no target url"); return out; }
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return out;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url);
    jclass cls = env->GetObjectClass(e->ua_resolver);
    if (cls && ju) {
        jmethodID mid = env->GetMethodID(cls, "apply",
            "(Ljava/lang/Object;)Ljava/lang/Object;");
        if (mid) {
            jobject r = env->CallObjectMethod(e->ua_resolver, mid, ju);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = nullptr;
            }
            if (r) {
                const char *cs = env->GetStringUTFChars((jstring)r, nullptr);
                if (cs && *cs) out = utf8_to_wide(cs);
                if (cs) env->ReleaseStringUTFChars((jstring)r, cs);
                env->DeleteLocalRef(r);
            }
        }
    }
    if (cls) env->DeleteLocalRef(cls);
    if (ju) env->DeleteLocalRef(ju);
    if (detach) jvm->DetachCurrentThread();
    return out;
}

// Canvas 21 Op 7.5: sets the User-Agent on a pop-up child's FIRST request.
//
// put_UserAgent on the child returns S_OK and does not affect that request --
// by the time NewWindowRequested hands us the child, WebView2 has committed the
// navigation and a settings write cannot overtake it. The request HEADER can
// still be rewritten, and a request cannot lose a race with itself.
//
// Scoped as narrowly as possible: filtered to the DOCUMENT resource context, so
// only the child's main-document request is seen, and latched so it acts once
// and is inert thereafter. Nothing is cancelled or re-issued -- only a header is
// set -- so window.opener and the in-flight POST body (Canvas 18 D7) survive.
class PopupUaHeaderHandler : public CallbackBase<
    ICoreWebView2WebResourceRequestedEventHandler> {
public:
    explicit PopupUaHeaderHandler(std::wstring ua) : m_ua(std::move(ua)) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2WebResourceRequestedEventArgs *args) override {
        if (m_done || !args) return S_OK;
        ICoreWebView2WebResourceRequest *req = nullptr;
        if (SUCCEEDED(args->get_Request(&req)) && req) {
            ICoreWebView2HttpRequestHeaders *headers = nullptr;
            if (SUCCEEDED(req->get_Headers(&headers)) && headers) {
                HRESULT hr = headers->SetHeader(L"User-Agent", m_ua.c_str());
                WV_LOG("popup_ua: first-request header rewrite hr=0x%08lx ua=%ls",
                       (unsigned long)hr, m_ua.c_str());
                if (SUCCEEDED(hr)) m_done = true;
                headers->Release();
            } else {
                WV_LOG("popup_ua: first-request get_Headers failed");
            }
            req->Release();
        } else {
            WV_LOG("popup_ua: first-request get_Request failed");
        }
        return S_OK;
    }
private:
    std::wstring m_ua;
    bool m_done = false;
};

static void propagate_popup_user_agent(Engine *opener, ICoreWebView2 *child,
                                       const char *target_uri) {
    // Canvas 21 Op 7.4: this function has three silent exits and an unchecked
    // put_UserAgent, and a child left on the engine default is consistent with
    // every one of them. Log which path was taken.
    WV_LOG("popup_ua: enter uri=%s opener=%p child=%p",
           target_uri ? target_uri : "(null)", (void *)opener, (void *)child);
    if (!opener || !child) {
        WV_LOG("popup_ua: no opener or child -- nothing to propagate");
        return;
    }
    // Canvas 21 (1.5.0): a resolver keyed on the CHILD's own target URL wins --
    // the case that matters is an OAuth sign-in popped out of a site that
    // requires a spoofed UA, landing on an identity provider that penalises
    // exactly that spoof.  A resolver that declines (or none at all) falls
    // through to the opener's tracked override, so pre-1.5.0 behaviour is
    // preserved byte-for-byte for callers that never set one.
    std::wstring resolved = resolve_ua_for_win(opener, target_uri);
    std::wstring ua = resolved;
    const char *source = "resolver";
    if (ua.empty()) {
        ua = opener->user_agent;
        source = "opener tracked override";
    }
    WV_LOG("popup_ua: resolver=%ls openerTracked=%ls chose=%s",
           resolved.empty() ? L"(declined)" : resolved.c_str(),
           opener->user_agent.empty() ? L"(none)" : opener->user_agent.c_str(),
           ua.empty() ? "(nothing -- child keeps the engine default)" : source);
    if (ua.empty()) return;
    ICoreWebView2Settings *settings = nullptr;
    HRESULT hrGet = child->get_Settings(&settings);
    if (SUCCEEDED(hrGet) && settings) {
        ICoreWebView2Settings2 *settings2 = nullptr;
        HRESULT hrQi = settings->QueryInterface(
                __uuidof(ICoreWebView2Settings2),
                reinterpret_cast<void **>(&settings2));
        if (SUCCEEDED(hrQi) && settings2) {
            HRESULT hrPut = settings2->put_UserAgent(ua.c_str());
            WV_LOG("popup_ua: put_UserAgent hr=0x%08lx ua=%ls",
                   (unsigned long)hrPut, ua.c_str());
            settings2->Release();
        } else {
            WV_LOG("popup_ua: ICoreWebView2Settings2 UNAVAILABLE on the child "
                   "hr=0x%08lx -- cannot set its UA", (unsigned long)hrQi);
        }
        settings->Release();
    } else {
        WV_LOG("popup_ua: child get_Settings failed hr=0x%08lx",
               (unsigned long)hrGet);
    }

    // The settings write above cannot reach the child's FIRST request, so hook
    // that one request and set the header directly (Op 7.5). Registered here,
    // inside the deferral and before Complete(), which is the only window in
    // which the request has not yet gone out. put_UserAgent still covers every
    // request after this one.
    auto *hook = new PopupUaHeaderHandler(ua);
    EventRegistrationToken hookToken{};
    HRESULT hrFilter = child->AddWebResourceRequestedFilter(
        L"*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT);
    HRESULT hrAdd = child->add_WebResourceRequested(hook, &hookToken);
    WV_LOG("popup_ua: first-request hook filter=0x%08lx add=0x%08lx",
           (unsigned long)hrFilter, (unsigned long)hrAdd);
    // WebView2 holds its own reference on success; on failure this is the last
    // one and the handler deletes itself.
    hook->Release();
}

// ---------------------------------------------------------------------------
// Canvas 32: custom URL schemes on WebView2.  The schemes are declared on the
// environment when it is created (D1, D2), a WebResourceRequested filter and
// handler go on every webview the library creates (D3), and each request is
// held by a deferral until Java answers (D4, D5).  WebView2 never reports an
// abandoned request, so Windows never upcalls a cancellation (D6).
//
// The shared state and the upcall mirror src_c/webview_embed.cpp (Canvas 30
// D14, D15); this file is a separate translation unit, so it has its own copy
// (Canvas 32 D9).
// ---------------------------------------------------------------------------
static std::vector<std::string> g_scheme_names;
static jobject g_scheme_dispatcher = nullptr;
static JavaVM *g_scheme_jvm = nullptr;
static std::atomic<long long> g_scheme_next_id(0);
static std::mutex g_scheme_mutex;
static bool g_scheme_installed = false;

// The largest request body read: the dispatcher's cap plus one byte, so an
// over-cap body is still recognised and answered 413.
static const size_t kSchemeMaxRequestRead = 16u * 1024u * 1024u + 1u;

struct WinSchemeRequest {
    ICoreWebView2WebResourceRequestedEventArgs *args = nullptr;
    ICoreWebView2Deferral *deferral = nullptr;
    ICoreWebView2Environment *env = nullptr;
    DWORD thread_id = 0;
};
static std::map<long long, WinSchemeRequest> g_win_scheme_requests;

// Upcall SchemeDispatcher.onSchemeRequest (Canvas 30 D15).  Called from a
// detached thread, never the engine thread.
static void scheme_upcall_request(long long id, const std::string &method,
                                  const std::string &url,
                                  const std::vector<std::string> &headerPairs,
                                  const std::vector<unsigned char> &body,
                                  bool bodyAvailable) {
    if (!g_scheme_dispatcher || !g_scheme_jvm) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_scheme_jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_scheme_jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env) return;
        attached = true;
    }
    jclass cls = env->GetObjectClass(g_scheme_dispatcher);
    jmethodID m = cls ? env->GetMethodID(cls, "onSchemeRequest",
        "(JLjava/lang/String;Ljava/lang/String;[Ljava/lang/String;[BZ)V") : nullptr;
    if (m) {
        jstring jmethod = env->NewStringUTF(method.c_str());
        jstring jurl = env->NewStringUTF(url.c_str());
        jclass strCls = env->FindClass("java/lang/String");
        jobjectArray jpairs = strCls
            ? env->NewObjectArray((jsize)headerPairs.size(), strCls, nullptr) : nullptr;
        if (jpairs) {
            for (size_t i = 0; i < headerPairs.size(); i++) {
                jstring v = env->NewStringUTF(headerPairs[i].c_str());
                env->SetObjectArrayElement(jpairs, (jsize)i, v);
                if (v) env->DeleteLocalRef(v);
            }
        }
        jbyteArray jbody = env->NewByteArray((jsize)body.size());
        if (jbody && !body.empty()) {
            env->SetByteArrayRegion(jbody, 0, (jsize)body.size(),
                                    (const jbyte *)body.data());
        }
        env->CallVoidMethod(g_scheme_dispatcher, m, (jlong)id, jmethod, jurl, jpairs,
                            jbody, (jboolean)(bodyAvailable ? JNI_TRUE : JNI_FALSE));
        if (jmethod) env->DeleteLocalRef(jmethod);
        if (jurl) env->DeleteLocalRef(jurl);
        if (jpairs) env->DeleteLocalRef(jpairs);
        if (jbody) env->DeleteLocalRef(jbody);
        if (strCls) env->DeleteLocalRef(strCls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (cls) env->DeleteLocalRef(cls);
    if (attached) g_scheme_jvm->DetachCurrentThread();
}

// D2.  The environment options carrying the scheme registrations, or null when
// nothing is registered (then environments are created exactly as before) or
// on any failure (then the engine is created without schemes).
static Microsoft::WRL::ComPtr<ICoreWebView2EnvironmentOptions> win_scheme_environment_options() {
    Microsoft::WRL::ComPtr<ICoreWebView2EnvironmentOptions> result;
    if (g_scheme_names.empty()) return result;
    auto options = Microsoft::WRL::Make<CoreWebView2EnvironmentOptions>();
    if (!options) {
        WV_LOG("scheme options: Make<CoreWebView2EnvironmentOptions> failed");
        return result;
    }
    std::vector<Microsoft::WRL::ComPtr<ICoreWebView2CustomSchemeRegistration>> regs;
    std::vector<ICoreWebView2CustomSchemeRegistration *> raw;
    for (const std::string &name : g_scheme_names) {
        std::wstring wname = utf8_to_wide(name.c_str());
        std::wstring origins = wname + L"://*";
        auto reg = Microsoft::WRL::Make<CoreWebView2CustomSchemeRegistration>(wname.c_str());
        if (!reg) {
            WV_LOG("scheme options: registration for %s failed", name.c_str());
            return result;
        }
        reg->put_TreatAsSecure(TRUE);
        reg->put_HasAuthorityComponent(TRUE);
        LPCWSTR allowed[1] = { origins.c_str() };
        reg->SetAllowedOrigins(1, allowed);
        Microsoft::WRL::ComPtr<ICoreWebView2CustomSchemeRegistration> iface;
        if (FAILED(reg.As(&iface))) return result;
        regs.push_back(iface);
        raw.push_back(iface.Get());
    }
    Microsoft::WRL::ComPtr<ICoreWebView2EnvironmentOptions4> options4;
    if (FAILED(options.As(&options4)) || !options4) {
        WV_LOG("scheme options: ICoreWebView2EnvironmentOptions4 unavailable");
        return result;
    }
    HRESULT hr = options4->SetCustomSchemeRegistrations((UINT32)raw.size(), raw.data());
    if (FAILED(hr)) {
        WV_LOG("scheme options: SetCustomSchemeRegistrations hr=0x%08lx", (unsigned long)hr);
        return result;
    }
    options.As(&result);
    return result;
}

// D4.  One handler per webview; it ignores every request whose scheme is not
// registered, because WebView2 hands each filtered request to every handler
// on the webview (the popup UA hook filters "*").
class SchemeRequestHandler : public CallbackBase<
    ICoreWebView2WebResourceRequestedEventHandler> {
public:
    explicit SchemeRequestHandler(ICoreWebView2Environment *env) : m_env(env) {
        if (m_env) m_env->AddRef();
    }
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2WebResourceRequestedEventArgs *args) override {
        if (!args || !m_env) return S_OK;
        ICoreWebView2WebResourceRequest *req = nullptr;
        if (FAILED(args->get_Request(&req)) || !req) return S_OK;
        std::string url;
        LPWSTR wuri = nullptr;
        if (SUCCEEDED(req->get_Uri(&wuri)) && wuri) {
            url = wide_to_utf8(wuri);
            CoTaskMemFree(wuri);
        }
        size_t colon = url.find(':');
        std::string scheme = colon == std::string::npos ? std::string() : url.substr(0, colon);
        for (auto &c : scheme) c = (char)tolower((unsigned char)c);
        bool ours = false;
        for (const std::string &n : g_scheme_names) {
            if (n == scheme) { ours = true; break; }
        }
        if (!ours) { req->Release(); return S_OK; }

        std::string method = "GET";
        LPWSTR wmethod = nullptr;
        if (SUCCEEDED(req->get_Method(&wmethod)) && wmethod) {
            if (*wmethod) method = wide_to_utf8(wmethod);
            CoTaskMemFree(wmethod);
        }
        std::vector<std::string> pairs;
        ICoreWebView2HttpRequestHeaders *headers = nullptr;
        if (SUCCEEDED(req->get_Headers(&headers)) && headers) {
            ICoreWebView2HttpHeadersCollectionIterator *it = nullptr;
            if (SUCCEEDED(headers->GetIterator(&it)) && it) {
                BOOL has = FALSE;
                while (SUCCEEDED(it->get_HasCurrentHeader(&has)) && has) {
                    LPWSTR n = nullptr, v = nullptr;
                    if (SUCCEEDED(it->GetCurrentHeader(&n, &v))) {
                        pairs.push_back(n ? wide_to_utf8(n) : std::string());
                        pairs.push_back(v ? wide_to_utf8(v) : std::string());
                    }
                    if (n) CoTaskMemFree(n);
                    if (v) CoTaskMemFree(v);
                    BOOL next = FALSE;
                    if (FAILED(it->MoveNext(&next)) || !next) break;
                }
                it->Release();
            }
            headers->Release();
        }
        std::vector<unsigned char> body;
        IStream *content = nullptr;
        if (SUCCEEDED(req->get_Content(&content)) && content) {
            unsigned char buf[16384];
            while (body.size() < kSchemeMaxRequestRead) {
                ULONG got = 0;
                HRESULT hr = content->Read(buf, (ULONG)sizeof(buf), &got);
                if (FAILED(hr) || got == 0) break;
                body.insert(body.end(), buf, buf + got);
            }
            if (body.size() > kSchemeMaxRequestRead) body.resize(kSchemeMaxRequestRead);
            content->Release();
        }
        req->Release();

        ICoreWebView2Deferral *deferral = nullptr;
        HRESULT hr = args->GetDeferral(&deferral);
        if (FAILED(hr) || !deferral) {
            WV_LOG("scheme GetDeferral failed: HRESULT=0x%08lx", (unsigned long)hr);
            return S_OK;   // left to WebView2: a load failure, never a hang
        }
        long long id = ++g_scheme_next_id;
        args->AddRef();
        m_env->AddRef();
        {
            std::lock_guard<std::mutex> lk(g_scheme_mutex);
            WinSchemeRequest &r = g_win_scheme_requests[id];
            r.args = args;
            r.deferral = deferral;
            r.env = m_env;
            r.thread_id = GetCurrentThreadId();
        }
        std::thread([id, method, url, pairs, body] {
            scheme_upcall_request(id, method, url, pairs, body, true);
        }).detach();
        return S_OK;
    }
protected:
    ~SchemeRequestHandler() override {
        if (m_env) m_env->Release();
    }
private:
    ICoreWebView2Environment *m_env;
};

// D3.  Filter and hook one webview (the engine's, or a popup child).  A no-op
// when nothing is registered.  The handler lives as long as the webview.
static void win_install_scheme_hook(ICoreWebView2 *wv, ICoreWebView2Environment *env) {
    if (!wv || !env || g_scheme_names.empty()) return;
    for (const std::string &name : g_scheme_names) {
        std::wstring filter = utf8_to_wide(name.c_str()) + L":*";
        HRESULT hr = wv->AddWebResourceRequestedFilter(
            filter.c_str(), COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
        if (FAILED(hr)) {
            WV_LOG("scheme filter %s failed: HRESULT=0x%08lx", name.c_str(), (unsigned long)hr);
        }
    }
    auto *h = new SchemeRequestHandler(env);
    EventRegistrationToken token{};
    HRESULT hr = wv->add_WebResourceRequested(h, &token);
    if (FAILED(hr)) {
        WV_LOG("scheme add_WebResourceRequested failed: HRESULT=0x%08lx", (unsigned long)hr);
    }
    h->Release();
}

static LPCWSTR win_reason_phrase(int status) {
    switch (status) {
    case 200: return L"OK";
    case 204: return L"No Content";
    case 301: return L"Moved Permanently";
    case 302: return L"Found";
    case 304: return L"Not Modified";
    case 400: return L"Bad Request";
    case 403: return L"Forbidden";
    case 404: return L"Not Found";
    case 405: return L"Method Not Allowed";
    case 413: return L"Payload Too Large";
    case 500: return L"Internal Server Error";
    case 504: return L"Gateway Timeout";
    default:  return L"";
    }
}

// D5.  Any thread; applied on the request's engine thread, once.
static void win_scheme_respond(long long id, int status, std::vector<std::string> pairs,
                               std::vector<unsigned char> body) {
    WinSchemeRequest r;
    {
        std::lock_guard<std::mutex> lk(g_scheme_mutex);
        auto it = g_win_scheme_requests.find(id);
        if (it == g_win_scheme_requests.end()) return;
        r = it->second;
        g_win_scheme_requests.erase(it);
    }
    // If the engine thread has already exited, the post fails and these COM
    // references are leaked rather than released on the wrong apartment (D5.3).
    post_to_worker_thread(r.thread_id, [r, status, pairs, body] {
        IStream *stream = SHCreateMemStream(body.empty() ? nullptr : body.data(),
                                            (UINT)body.size());
        std::wstring headers;
        for (size_t i = 0; i + 1 < pairs.size(); i += 2) {
            headers += utf8_to_wide(pairs[i].c_str());
            headers += L": ";
            headers += utf8_to_wide(pairs[i + 1].c_str());
            headers += L"\r\n";
        }
        ICoreWebView2WebResourceResponse *resp = nullptr;
        HRESULT hr = r.env->CreateWebResourceResponse(stream, status, win_reason_phrase(status),
                                                      headers.c_str(), &resp);
        if (SUCCEEDED(hr) && resp) {
            r.args->put_Response(resp);
        } else {
            WV_LOG("scheme CreateWebResourceResponse failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
        }
        r.deferral->Complete();   // always: a load failure, never a hang
        if (resp) resp->Release();
        if (stream) stream->Release();
        r.deferral->Release();
        r.args->Release();
        r.env->Release();
    });
}

// Canvas 34 D9: NavigationStarting and FrameNavigationStarting -> the
// WebViewNavigationCallback, synchronously on the WebView2 thread, before any
// request is sent; a refusal is put_Cancel(TRUE).  One class serves both
// events: they share the handler and args interfaces.  WebView2 can't tell a
// link from a form or a script, so those are OTHER; reload and back/forward
// come from ICoreWebView2NavigationStartingEventArgs3 when the runtime has it.
// Every failure refuses.
class NavigationStartingHandler : public CallbackBase<
    ICoreWebView2NavigationStartingEventHandler> {
public:
    explicit NavigationStartingHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2NavigationStartingEventArgs *args) override {
        if (!args || !m_engine) return S_OK;
        jobject cb = m_engine->navigation_callback;
        JavaVM *jvm = m_engine->jvm;
        if (!cb || !jvm) return S_OK;

        LPWSTR uri_w = nullptr;
        args->get_Uri(&uri_w);
        std::string uri = wide_to_utf8(uri_w);
        if (uri_w) CoTaskMemFree(uri_w);

        int cause = 5;                                   // OTHER
        BOOL redirected = FALSE;
        args->get_IsRedirected(&redirected);
        if (redirected) {
            cause = 4;                                   // REDIRECT
        } else {
            ICoreWebView2NavigationStartingEventArgs3 *a3 = nullptr;
            if (SUCCEEDED(args->QueryInterface(
                    __uuidof(ICoreWebView2NavigationStartingEventArgs3),
                    reinterpret_cast<void **>(&a3))) && a3) {
                COREWEBVIEW2_NAVIGATION_KIND kind =
                    COREWEBVIEW2_NAVIGATION_KIND_NEW_DOCUMENT;
                if (SUCCEEDED(a3->get_NavigationKind(&kind))) {
                    if (kind == COREWEBVIEW2_NAVIGATION_KIND_RELOAD) cause = 3;
                    else if (kind == COREWEBVIEW2_NAVIGATION_KIND_BACK_OR_FORWARD) cause = 2;
                }
                a3->Release();
            }
        }

        std::string current;
        {
            LPWSTR src = nullptr;
            if (m_engine->webview &&
                SUCCEEDED(m_engine->webview->get_Source(&src)) && src) {
                current = wide_to_utf8(src);
            }
            if (src) CoTaskMemFree(src);
        }

        bool allowed = false;
        JNIEnv *env = nullptr;
        bool detach = false;
        if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) env = nullptr;
            else detach = true;
        }
        if (env) {
            jstring ju = env->NewStringUTF(uri.c_str());
            jstring jc = env->NewStringUTF(current.c_str());
            jclass cls = env->GetObjectClass(cb);
            if (cls) {
                jmethodID mid = env->GetMethodID(cls, "onNavigationRequested",
                    "(Ljava/lang/String;Ljava/lang/String;I)Z");
                if (mid) {
                    jboolean r = env->CallBooleanMethod(cb, mid, ju, jc, (jint)cause);
                    allowed = r == JNI_TRUE;
                }
                env->DeleteLocalRef(cls);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                allowed = false;
            }
            if (ju) env->DeleteLocalRef(ju);
            if (jc) env->DeleteLocalRef(jc);
            if (detach) jvm->DetachCurrentThread();
        }
        if (!allowed) args->put_Cancel(TRUE);
        return S_OK;
    }

private:
    Engine *m_engine;
};

// Canvas 34 D9: register the navigation handler on `wv` for `e`, for the view
// and its frames.  Called beside add_NewWindowRequested, for a fresh engine
// and for an adopted child.
static void win_register_navigation(Engine *e, ICoreWebView2 *wv) {
    if (!e || !wv) return;
    auto *nh = new NavigationStartingHandler(e);
    bool ok = SUCCEEDED(wv->add_NavigationStarting(nh, &e->navigation_starting_token));
    ok = SUCCEEDED(wv->add_FrameNavigationStarting(nh, &e->frame_navigation_starting_token)) && ok;
    nh->Release();
    e->navigation_registered = ok;
}

// NewWindowRequested -> allow/deny via Java, then create the linked child in an
// engine-owned top-level window.  Deferral pattern; see the block comment.
class NewWindowRequestedHandler : public CallbackBase<
    ICoreWebView2NewWindowRequestedEventHandler> {
public:
    explicit NewWindowRequestedHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2NewWindowRequestedEventArgs *args) override {
        if (!args || !m_engine) return S_OK;

        LPWSTR uri_w = nullptr;
        args->get_Uri(&uri_w);
        std::string uri = wide_to_utf8(uri_w);
        if (uri_w) CoTaskMemFree(uri_w);

        BOOL user_initiated = FALSE;
        args->get_IsUserInitiated(&user_initiated);

        int req_w = -1, req_h = -1;
        ICoreWebView2WindowFeatures *feat = nullptr;
        if (SUCCEEDED(args->get_WindowFeatures(&feat)) && feat) {
            BOOL has_size = FALSE;
            feat->get_HasSize(&has_size);
            if (has_size) {
                UINT32 fw = 0, fh = 0;
                feat->get_Width(&fw);
                feat->get_Height(&fh);
                if (fw > 0) req_w = (int)fw;
                if (fh > 0) req_h = (int)fh;
            }
            feat->Release();
        }

        // Opener document URL (best effort).
        std::string page;
        {
            LPWSTR src = nullptr;
            if (m_engine->webview &&
                SUCCEEDED(m_engine->webview->get_Source(&src)) && src) {
                page = wide_to_utf8(src);
            }
            if (src) CoTaskMemFree(src);
        }

        ICoreWebView2Deferral *deferral = nullptr;
        HRESULT hr = args->GetDeferral(&deferral);
        if (FAILED(hr) || !deferral) {
            WV_LOG("NewWindowRequested GetDeferral failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            return S_OK;  // WebView2 opens its own default window (fallback)
        }
        args->AddRef();

        Engine *e = m_engine;
        bool gesture = (user_initiated != FALSE);
        std::thread([e, args, deferral, uri, page, gesture, req_w, req_h] {
            // Canvas 20: the disposition switch replaces the Canvas-17 boolean
            // gate.  0=BLOCK, 1=NATIVE_WINDOW (the unchanged Canvas-17
            // engine-owned shown-window path), 2=ADOPT (retain a windowless
            // child for later adoption into a caller-provided component).
            jint disposition = fire_popup_disposition_win(
                e, uri.c_str(), gesture, req_w, req_h, page.c_str());
            dispatch_to_thread(e, [e, args, deferral, uri, page, gesture,
                                   req_w, req_h, disposition] {
                if (disposition == 0) {   // BLOCK
                    args->put_Handled(TRUE);   // block; window.open -> null
                    deferral->Complete();
                    deferral->Release();
                    args->Release();
                    return;
                }
                if (disposition == 2) {   // ADOPT — retain windowless child
                    // Need the opener's environment to build a LINKED child
                    // (window.opener / postMessage / POST replay).  Without it
                    // we cannot honour ADOPT -> block.
                    if (!e->environment) {
                        args->put_Handled(TRUE);
                        deferral->Complete();
                        deferral->Release();
                        args->Release();
                        return;
                    }
                    ensure_popup_holder_class_registered();
                    // Hidden holder top-level window -- NEVER ShowWindow.  The
                    // child renders offscreen here until adoption reparents it
                    // via put_ParentWindow.  (WebView2 requires a real parent
                    // HWND for the controller.)
                    HWND holder = CreateWindowEx(
                        0, "WebViewEmbedPopupHolder", "", WS_OVERLAPPEDWINDOW,
                        CW_USEDEFAULT, CW_USEDEFAULT,
                        req_w > 0 ? req_w : 500, req_h > 0 ? req_h : 650,
                        nullptr, nullptr, GetModuleHandle(nullptr), nullptr);
                    if (!holder) {
                        args->put_Handled(TRUE);
                        deferral->Complete();
                        deferral->Release();
                        args->Release();
                        return;
                    }
                    // Inherit the opener's popup/dialog callbacks as NEW global
                    // refs so the retained child keeps working (nested popups +
                    // dialogs) until adoption transfers them to the adopted
                    // engine.  ON-DEVICE-VALIDATION-REQUIRED: global-ref
                    // lifecycle across retain -> adopt/discard.
                    JavaVM *jvm = e->jvm;
                    jobject inh_popup = nullptr;
                    jobject inh_dialog = nullptr;
                    if (jvm) {
                        JNIEnv *jenv = nullptr;
                        bool jdetach = false;
                        if (jvm->GetEnv((void **)&jenv, JNI_VERSION_1_6)
                                != JNI_OK) {
                            jvm->AttachCurrentThread((void **)&jenv, nullptr);
                            jdetach = true;
                        }
                        if (jenv) {
                            if (e->popup_callback)
                                inh_popup = jenv->NewGlobalRef(e->popup_callback);
                            if (e->dialog_callback)
                                inh_dialog =
                                    jenv->NewGlobalRef(e->dialog_callback);
                        }
                        if (jdetach) jvm->DetachCurrentThread();
                    }

                    RetainedPopup *rp = new RetainedPopup();
                    rp->holder = holder;
                    rp->jvm = jvm;
                    rp->url = uri;
                    rp->page = page;
                    rp->popup_callback = inh_popup;
                    rp->dialog_callback = inh_dialog;
                    rp->worker_thread_id = e->thread_id;
                    rp->environment = e->environment;
                    rp->environment->AddRef();
                    rp->popup_id = (jlong)(LONG_PTR)rp;

                    auto *ctrl_handler = new ControllerHandler(
                        [e, args, deferral, rp, uri, page, gesture,
                         req_w, req_h]
                        (HRESULT r2, ICoreWebView2Controller *ctrl) {
                            if (FAILED(r2) || !ctrl) {
                                WV_LOG("adopt CreateController completion "
                                       "failed: 0x%08lx", (unsigned long)r2);
                                args->put_Handled(TRUE);
                                deferral->Complete();
                                deferral->Release();
                                args->Release();
                                // rp never entered the registry; tear the
                                // half-built shell down on THIS (worker) thread.
                                retained_popup_teardown(rp,
                                                        /*fire_closed=*/false);
                                return;
                            }
                            ctrl->AddRef();
                            rp->controller = ctrl;
                            ICoreWebView2 *child = nullptr;
                            ctrl->get_CoreWebView2(&child);
                            if (child) { child->AddRef(); rp->webview = child; }

                            // Canvas 21: inherit the opener's custom UA before
                            // the child's in-flight initial navigation.
                            propagate_popup_user_agent(e, child, uri.c_str());
                            // Canvas 32 D3: the popup child answers the same custom schemes (AC11).
                            win_install_scheme_hook(child, e->environment);

                            // Return the LINKED child to WebView2 so it drives
                            // the original request (POST verb+body,
                            // window.opener) into it.
                            args->put_NewWindow(child);
                            args->put_Handled(TRUE);
                            deferral->Complete();

                            // Keep it HIDDEN: no ShowWindow, controller not
                            // visible.  It renders offscreen until adoption.
                            RECT rc;
                            GetClientRect(rp->holder, &rc);
                            ctrl->put_Bounds(rc);
                            ctrl->put_IsVisible(FALSE);

                            if (child) {
                                // Retained-phase handlers reuse the opener
                                // engine's callbacks (opener outlives the
                                // retained child until adoption).  Tokens are
                                // stored so adoption can remove them before the
                                // adopted engine wires its own handlers.
                                auto *nwh = new NewWindowRequestedHandler(e);
                                child->add_NewWindowRequested(
                                    nwh, &rp->new_window_token);
                                nwh->Release();

                                ICoreWebView2Settings *settings = nullptr;
                                if (SUCCEEDED(child->get_Settings(&settings)) &&
                                    settings) {
                                    settings
                                        ->put_AreDefaultScriptDialogsEnabled(
                                            FALSE);
                                    settings->Release();
                                }
                                auto *sdh = new ScriptDialogHandler(e);
                                child->add_ScriptDialogOpening(
                                    sdh, &rp->script_dialog_token);
                                sdh->Release();

                                // window.close() before adoption -> discard.
                                auto *rch = new RetainedPopupCloseHandler(rp);
                                child->add_WindowCloseRequested(
                                    rch, &rp->close_token);
                                rch->Release();
                            }

                            {
                                std::lock_guard<std::mutex> lk(
                                    g_win_retained_popups_mutex);
                                g_win_retained_popups[rp->popup_id] = rp;
                            }

                            // Notify Java: child retained + adoptable.  The Java
                            // dispatcher marshals popupAdoptable to the EDT.
                            fire_popup_adoptable_win(e, rp->popup_id,
                                                     uri.c_str(), gesture,
                                                     req_w, req_h, page.c_str());

                            deferral->Release();
                            args->Release();
                        });
                    HRESULT rc2 = e->environment->CreateCoreWebView2Controller(
                        rp->holder, ctrl_handler);
                    ctrl_handler->Release();
                    if (FAILED(rc2)) {
                        WV_LOG("adopt CreateCoreWebView2Controller call "
                               "failed: 0x%08lx", (unsigned long)rc2);
                        args->put_Handled(TRUE);
                        deferral->Complete();
                        deferral->Release();
                        args->Release();
                        retained_popup_teardown(rp, /*fire_closed=*/false);
                    }
                    return;
                }
                // disposition == 1: NATIVE_WINDOW -- the unchanged Canvas-17
                // engine-owned shown-window path below.
                int W = req_w > 0 ? req_w : 500;
                int H = req_h > 0 ? req_h : 650;
                ensure_popup_class_registered();
                HWND popup = CreateWindowEx(
                    0, "WebViewEmbedPopup", "Popup", WS_OVERLAPPEDWINDOW,
                    CW_USEDEFAULT, CW_USEDEFAULT, W, H,
                    nullptr, nullptr, GetModuleHandle(nullptr), nullptr);
                if (!popup || !e->environment) {
                    if (popup) DestroyWindow(popup);
                    args->put_Handled(TRUE);   // blocked
                    deferral->Complete();
                    deferral->Release();
                    args->Release();
                    return;
                }
                PopupWindow *pw = new PopupWindow();
                pw->hwnd = popup;
                pw->opener = e;
                pw->popup_id = (jlong)(LONG_PTR)pw;
                pw->url = uri;
                SetWindowLongPtr(popup, GWLP_USERDATA, (LONG_PTR)pw);

                auto *ctrl_handler = new ControllerHandler(
                    [e, args, deferral, pw, uri, page, gesture, W, H]
                    (HRESULT r2, ICoreWebView2Controller *ctrl) {
                        if (FAILED(r2) || !ctrl) {
                            WV_LOG("popup CreateController completion failed: "
                                   "0x%08lx", (unsigned long)r2);
                            args->put_Handled(TRUE);   // blocked
                            deferral->Complete();
                            deferral->Release();
                            args->Release();
                            DestroyWindow(pw->hwnd);   // WM_DESTROY frees pw
                            return;
                        }
                        ctrl->AddRef();
                        pw->controller = ctrl;
                        ICoreWebView2 *child = nullptr;
                        ctrl->get_CoreWebView2(&child);
                        if (child) { child->AddRef(); pw->webview = child; }

                        // Canvas 21: inherit the opener's custom UA before the
                        // child's in-flight initial navigation.
                        propagate_popup_user_agent(e, child, uri.c_str());
                        // Canvas 32 D3: the popup child answers the same custom schemes (AC11).
                        win_install_scheme_hook(child, e->environment);

                        args->put_NewWindow(child);   // LINKED to opener
                        args->put_Handled(TRUE);
                        deferral->Complete();

                        RECT rc;
                        GetClientRect(pw->hwnd, &rc);
                        ctrl->put_Bounds(rc);
                        ctrl->put_IsVisible(TRUE);
                        ShowWindow(pw->hwnd, SW_SHOW);

                        if (child) {
                            // Nested popups + dialogs from inside the popup,
                            // reusing the opener's callbacks.
                            EventRegistrationToken nw_t{};
                            auto *nwh = new NewWindowRequestedHandler(e);
                            child->add_NewWindowRequested(nwh, &nw_t);
                            nwh->Release();

                            ICoreWebView2Settings *settings = nullptr;
                            if (SUCCEEDED(child->get_Settings(&settings)) &&
                                settings) {
                                settings->put_AreDefaultScriptDialogsEnabled(
                                    FALSE);
                                settings->Release();
                            }
                            EventRegistrationToken sd_t{};
                            auto *sdh = new ScriptDialogHandler(e);
                            child->add_ScriptDialogOpening(sdh, &sd_t);
                            sdh->Release();

                            auto *pch = new PopupCloseHandler(pw);
                            child->add_WindowCloseRequested(pch,
                                                            &pw->close_token);
                            pch->Release();
                        }

                        fire_popup_opened_win(e, pw->popup_id, uri.c_str(),
                                              gesture, W, H, page.c_str());

                        deferral->Release();
                        args->Release();
                    });
                HRESULT rc2 = e->environment->CreateCoreWebView2Controller(
                    pw->hwnd, ctrl_handler);
                ctrl_handler->Release();
                if (FAILED(rc2)) {
                    WV_LOG("popup CreateCoreWebView2Controller call failed: "
                           "0x%08lx", (unsigned long)rc2);
                    args->put_Handled(TRUE);
                    deferral->Complete();
                    deferral->Release();
                    args->Release();
                    DestroyWindow(pw->hwnd);   // WM_DESTROY frees pw
                }
            });
        }).detach();

        return S_OK;
    }
private:
    Engine *m_engine;
};

static void engine_on_message(Engine *e, LPCWSTR msg) {
    if (!msg) return;
    int n = WideCharToMultiByte(CP_UTF8, 0, msg, -1, nullptr, 0, nullptr, nullptr);
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, msg, -1, &s[0], n, nullptr, nullptr);
    if (!s.empty() && s.back() == '\0') s.pop_back();

    auto pos = s.find("\"name\":\"");
    if (pos == std::string::npos) return;
    auto start = pos + 8;
    auto end = s.find('"', start);
    if (end == std::string::npos) return;
    std::string name = s.substr(start, end - start);

    Binding *b = nullptr;
    {
        std::lock_guard<std::mutex> lk(e->bindings_mutex);
        auto it = e->bindings.find(name);
        if (it == e->bindings.end()) return;
        b = it->second;
    }
    JNIEnv *env = nullptr;
    bool detach = false;
    if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        e->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    jmethodID mid = env->GetMethodID(b->cls, "invoke", "(Ljava/lang/String;J)V");
    if (mid) {
        jstring js = env->NewStringUTF(s.c_str());
        env->CallVoidMethod(b->fn, mid, js, (jlong)e);
        env->DeleteLocalRef(js);
    }
    if (detach) e->jvm->DetachCurrentThread();
}

static LRESULT CALLBACK EmbedWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    Engine *e = (Engine *)GetWindowLongPtr(hwnd, GWLP_USERDATA);
    switch (msg) {
    case WM_CLOSE:
        if (e && e->standalone) {
            e->standalone_closed.store(true);
            ShowWindow(hwnd, SW_HIDE);
            return 0;
        }
        return DefWindowProc(hwnd, msg, wp, lp);
    case WM_SIZE:
        if (e && e->controller) {
            RECT r;
            GetClientRect(hwnd, &r);
            e->controller->put_Bounds(r);
        }
        return 0;
    case WM_PARENTNOTIFY:
        // Windows posts WM_PARENTNOTIFY to the parent HWND whenever a
        // direct child receives a mouse-button-down message.  Our child
        // HWND ("WebViewEmbedChild") is the immediate parent of the
        // WebView2-created HWND, so a click on the WebView2 surface
        // arrives here as WM_PARENTNOTIFY with LOWORD(wParam) carrying
        // the original message id.  This is the Windows half of the
        // cross-platform native click hook used to dismiss any open
        // Swing JPopupMenu when the user clicks into the WebView -- AWT's
        // MouseGrabber AWTEventListener never sees these clicks because
        // they reach the WebView2 HWND directly rather than through AWT.
        if (e) {
            switch (LOWORD(wp)) {
            case WM_LBUTTONDOWN:
            case WM_RBUTTONDOWN:
            case WM_MBUTTONDOWN:
            case WM_XBUTTONDOWN:
                fire_click_callback(e);
                break;
            default:
                break;
            }
        }
        return DefWindowProc(hwnd, msg, wp, lp);
    default:
        return DefWindowProc(hwnd, msg, wp, lp);
    }
}

static ATOM ensure_class_registered() {
    static ATOM atom = 0;
    if (atom != 0) return atom;
    WNDCLASSEX wc{};
    wc.cbSize = sizeof(wc);
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = "WebViewEmbedChild";
    wc.lpfnWndProc = EmbedWndProc;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    atom = RegisterClassEx(&wc);
    return atom;
}

static void engine_thread(Engine *e, HWND /*parent*/, int width, int height,
                          bool /*debug*/, std::atomic<bool> *ready,
                          std::atomic<bool> *ok) {
    ensure_class_registered();
    e->child = CreateWindowEx(0, "WebViewEmbedChild", "",
                              e->standalone ? WS_OVERLAPPEDWINDOW : WS_CHILD | WS_VISIBLE,
                              e->standalone ? CW_USEDEFAULT : 0, e->standalone ? CW_USEDEFAULT : 0,
                              width, height, e->parent,
                              nullptr, GetModuleHandle(nullptr), nullptr);
    if (!e->child) {
        WV_LOG("CreateWindowEx for child HWND failed: GetLastError=%lu",
               GetLastError());
        *ok = false; *ready = true;
        return;
    }
    SetWindowLongPtr(e->child, GWLP_USERDATA, (LONG_PTR)e);

    HRESULT apartment = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(apartment)) {
        DestroyWindow(e->child);
        e->child = nullptr;
        *ok = false; *ready = true;
        return;
    }
    struct ApartmentCleanup {
        Engine *e;
        ~ApartmentCleanup() {
            if (e->controller) { e->controller->Close(); e->controller->Release(); e->controller = nullptr; }
            if (e->webview) { e->webview->Release(); e->webview = nullptr; }
            if (e->environment) { e->environment->Release(); e->environment = nullptr; }
            if (e->child) { DestroyWindow(e->child); e->child = nullptr; }
            CoUninitialize();
        }
    } cleanup{e};

    struct InitState {
        std::atomic<bool> done{false};
        HRESULT result = S_OK;
    };
    auto init = std::make_shared<InitState>();

    auto *env_handler = new EnvHandler(
        [e, init](HRESULT r, ICoreWebView2Environment *env) {
            if (FAILED(r) || !env) {
                WV_LOG("CreateCoreWebView2EnvironmentWithOptions "
                       "completion failed: HRESULT=0x%08lx",
                       (unsigned long)r);
                init->result = r;
                init->done.store(true);
                return;
            }
            // Keep the environment alive so NewWindowRequested can build the
            // child popup controller from it (linked view) -- Canvas 17.
            e->environment = env;
            env->AddRef();
            auto *ctrl_handler = new ControllerHandler(
                [e, init](HRESULT r2, ICoreWebView2Controller *ctrl) {
                    if (FAILED(r2) || !ctrl) {
                        WV_LOG("CreateCoreWebView2Controller completion "
                               "failed: HRESULT=0x%08lx",
                               (unsigned long)r2);
                        init->result = r2;
                        init->done.store(true);
                        return;
                    }
                    ctrl->AddRef();
                    e->controller = ctrl;
                    HRESULT r3 = ctrl->get_CoreWebView2(&e->webview);
                    if (FAILED(r3) || !e->webview) {
                        WV_LOG("get_CoreWebView2 failed: HRESULT=0x%08lx",
                               (unsigned long)r3);
                        init->result = r3;
                        init->done.store(true);
                        return;
                    }
                    // get_CoreWebView2 already transfers a reference to us.
                    if (e->standalone) {
                        UINT32 pid = 0;
                        if (SUCCEEDED(e->webview->get_BrowserProcessId(&pid)) && pid)
                            e->standalone_browser_process = OpenProcess(SYNCHRONIZE, FALSE, pid);
                    }

                    RECT rc;
                    GetClientRect(e->child, &rc);
                    ctrl->put_Bounds(rc);
                    ctrl->put_IsVisible(TRUE);

                    ICoreWebView2Settings *settings = nullptr;
                    if (SUCCEEDED(e->webview->get_Settings(&settings)) &&
                        settings) {
                        settings->put_AreDevToolsEnabled(
                            e->debug ? TRUE : FALSE);
                        settings->put_AreDefaultContextMenusEnabled(TRUE);
                        // Suppress WebView2's built-in alert / confirm /
                        // prompt dialogs.  Java drives the response via
                        // the ScriptDialogHandler registered below.
                        // STORY-004-003.
                        settings->put_AreDefaultScriptDialogsEnabled(e->standalone ? TRUE : FALSE);
                        // Disable Edge's built-in "save password?" autosave so
                        // it does not compete with this library's own manager
                        // (Canvas 28).  Guarded: a Runtime without
                        // ICoreWebView2Settings4 is a silent no-op.
                        ICoreWebView2Settings4 *settings4 = nullptr;
                        if (SUCCEEDED(settings->QueryInterface(
                                __uuidof(ICoreWebView2Settings4),
                                reinterpret_cast<void **>(&settings4)))
                                && settings4) {
                            settings4->put_IsPasswordAutosaveEnabled(FALSE);
                            settings4->Release();
                        }
                        settings->Release();
                    }

                    // Shim window.external.invoke to use the modern
                    // WebView2 message channel.  Existing demos and the
                    // standalone API both call window.external.invoke.
                    e->webview->AddScriptToExecuteOnDocumentCreated(
                        L"window.external = { invoke: s => "
                        L"window.chrome.webview.postMessage(s) };",
                        nullptr);

                    auto *mh = new MsgHandler(e);
                    e->webview->add_WebMessageReceived(mh, &e->message_token);
                    mh->Release();

                    // Wire JS-initiated dialogs (alert / confirm / prompt
                    // / before-unload) to the per-engine Java
                    // DialogDispatcher via the dialog_callback global ref.
                    // WebView2's built-in dialogs are suppressed by
                    // put_AreDefaultScriptDialogsEnabled(FALSE) above.
                    // File picker (<input type=file>) is NOT intercepted
                    // -- WebView2 exposes no public hook; the OS-native
                    // Common Item Dialog continues to appear as before.
                    // STORY-004-003.
                    auto *sdh = new ScriptDialogHandler(e);
                    e->webview->add_ScriptDialogOpening(
                        sdh, &e->script_dialog_token);
                    sdh->Release();

                    // Browser-initiated downloads -- Canvas 25.  Gated on
                    // ICoreWebView2_4; on an older runtime nothing is
                    // registered and WebView2 keeps its own flyout and
                    // Downloads-folder destination.  Refusing to build the
                    // engine over a missing download API would break every
                    // application that never downloads anything.
                    {
                        ICoreWebView2_4 *wv4 = nullptr;
                        if (SUCCEEDED(e->webview->QueryInterface(
                                __uuidof(ICoreWebView2_4),
                                reinterpret_cast<void **>(&wv4))) && wv4) {
                            auto *dsh = new DownloadStartingHandler(e);
                            if (SUCCEEDED(wv4->add_DownloadStarting(
                                    dsh, &e->download_starting_token))) {
                                e->downloads_available = true;
                            }
                            dsh->Release();
                            wv4->Release();
                        }
                    }

                    // Hook GotFocus / LostFocus on the controller so the
                    // Java side can suppress and restore the previously-
                    // focused JTextComponent's caret while WebView2 holds
                    // Win32 keyboard focus.  Two separate handler
                    // instances because the same callback signature has
                    // no way to distinguish got vs lost from the args.
                    auto *gh = new FocusHandler(e, true);
                    ctrl->add_GotFocus(gh, &e->got_focus_token);
                    gh->Release();
                    auto *lh = new FocusHandler(e, false);
                    ctrl->add_LostFocus(lh, &e->lost_focus_token);
                    lh->Release();

                    // window.open / target=_blank -> native popup window
                    // linked to the opener (Canvas 17).
                    auto *nwh = new NewWindowRequestedHandler(e);
                    e->webview->add_NewWindowRequested(
                        nwh, &e->new_window_token);
                    nwh->Release();

                    // Navigation decisions (Canvas 34 D9).
                    win_register_navigation(e, e->webview);

                    // Custom URL schemes (Canvas 32 D3); a no-op when none
                    // are registered.
                    win_install_scheme_hook(e->webview, e->environment);

                    init->done.store(true);
                });
            HRESULT r2 = env->CreateCoreWebView2Controller(
                e->child, ctrl_handler);
            ctrl_handler->Release();
            if (FAILED(r2)) {
                WV_LOG("CreateCoreWebView2Controller call failed: "
                       "HRESULT=0x%08lx", (unsigned long)r2);
                init->result = r2;
                init->done.store(true);
            }
        });
    DWORD user_data_error = ERROR_SUCCESS;
    std::wstring user_data_folder = e->standalone ? e->standalone_data :
        resolve_webview2_user_data_folder(&user_data_error);
    if (user_data_folder.empty()) {
        WV_LOG("Could not create a writable WebView2 user data folder: "
               "GetLastError=%lu; falling back to the WebView2 default",
               user_data_error);
    }
    // Canvas 32 D1: declare the custom schemes on the environment. Null when
    // none are registered, retaining the default WebView2 environment options.
    Microsoft::WRL::ComPtr<ICoreWebView2EnvironmentOptions> scheme_options =
        win_scheme_environment_options();
    HRESULT res = CreateCoreWebView2EnvironmentWithOptions(
        nullptr,
        user_data_folder.empty() ? nullptr : user_data_folder.c_str(),
        scheme_options.Get(), env_handler);
    env_handler->Release();
    if (FAILED(res)) {
        WV_LOG("CreateCoreWebView2EnvironmentWithOptions failed: "
               "HRESULT=0x%08lx", (unsigned long)res);
        if (res == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)) {
            WV_LOG("  The WebView2 runtime is not installed.  Install it:");
            WV_LOG("  https://developer.microsoft.com/microsoft-edge/webview2/");
        }
        *ok = false; *ready = true;
        return;
    }

    // Pump until env+controller flow completes.
    MSG msg{};
    ULONGLONG deadline = GetTickCount64() + 20000;
    while (!init->done.load()) {
        if (GetTickCount64() >= deadline) {
            *ok = false; *ready = true;
            return;
        }
        MsgWaitForMultipleObjects(0, nullptr, FALSE, 50, QS_ALLINPUT);
        while (PeekMessage(&msg, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessage(&msg);
        }
    }

    if (!e->webview || FAILED(init->result)) {
        WV_LOG("WebView2 init did not produce an ICoreWebView2");
        *ok = false; *ready = true;
        return;
    }

    *ok = true; *ready = true;
    if (e->standalone_visible) ShowWindow(e->child, SW_SHOW);

    // Main loop -- Java -> native ops arrive as WM_EMBED_DISPATCH messages.
    while (GetMessage(&msg, nullptr, 0, 0)) {
        if (msg.message == WM_EMBED_DISPATCH) {
            auto *fn = (DispatchFn *)msg.lParam;
            (*fn)();
            delete fn;
            continue;
        }
        if (msg.message == WM_EMBED_QUIT) break;
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

}

static bool dispatch_to_thread(Engine *e, DispatchFn fn) {
    if (!e || e->thread_id == 0) return false;
    auto *holder = new DispatchFn(std::move(fn));
    if (!PostThreadMessage(e->thread_id, WM_EMBED_DISPATCH, 0, (LPARAM)holder)) {
        delete holder;
        return false;
    }
    return true;
}

static Engine *create_engine(JNIEnv *env, jobject component, int debug) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) {
        WV_LOG("JAWT lock failed (ok=%d dsi=%p)",
               lock.ok ? 1 : 0,
               lock.dsi ? lock.dsi->platformInfo : nullptr);
        return nullptr;
    }
    auto *info = (JAWT_Win32DrawingSurfaceInfo *)lock.dsi->platformInfo;
    HWND parent = info->hwnd;
    if (!parent) {
        WV_LOG("JAWT platform info had no HWND");
        return nullptr;
    }
    RECT r;
    GetClientRect(parent, &r);
    int width = r.right - r.left;
    int height = r.bottom - r.top;
    if (width <= 0) width = 1;
    if (height <= 0) height = 1;

    auto *e = new Engine();
    e->parent = parent;
    e->debug = debug != 0;
    env->GetJavaVM(&e->jvm);

    std::atomic<bool> ready{false};
    std::atomic<bool> ok{false};
    std::thread t(engine_thread, e, parent, width, height, e->debug,
                  &ready, &ok);
    e->thread_id = GetThreadId(t.native_handle());
    e->thread = t.native_handle();
    t.detach();
    while (!ready.load()) {
        Sleep(1);
    }
    if (!ok.load()) {
        PostThreadMessage(e->thread_id, WM_EMBED_QUIT, 0, 0);
        delete e;
        return nullptr;
    }
    return e;
}

static void destroy_engine(Engine *e) {
    if (!e) return;
    if (e->shared_thread) {
        // Canvas 20: an ADOPTED engine shares the opener's WebView2 worker
        // thread.  Posting WM_EMBED_QUIT here would exit the opener's message
        // loop and tear the opener down too.  Instead synchronously Close() the
        // controller/webview and destroy our child HWND on that shared thread,
        // then fall through to free the global refs + delete e.  The opener's
        // loop keeps running.  ON-DEVICE-VALIDATION-REQUIRED: apartment-correct
        // Close/Release on the shared worker thread.
        std::atomic<bool> done{false};
        dispatch_to_thread(e, [e, &done] {
            if (e->controller) {
                e->controller->Close();
                e->controller->Release();
                e->controller = nullptr;
            }
            if (e->webview) {
                e->webview->Release();
                e->webview = nullptr;
            }
            if (e->child) {
                DestroyWindow(e->child);
                e->child = nullptr;
            }
            done.store(true);
        });
        while (!done.load()) Sleep(1);
    } else if (e->thread_id) {
        PostThreadMessage(e->thread_id, WM_EMBED_QUIT, 0, 0);
    }
    // Release the focus callback global ref BEFORE the WebView2 worker
    // thread tears down -- the GotFocus / LostFocus handlers can fire
    // during teardown (LostFocus in particular fires when the controller
    // is closed) and we don't want them invoking a callback into a freed
    // Java global ref.
    if (e->focus_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->focus_callback);
        e->focus_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the click callback global ref.  Cleared after
    // the focus callback above but before the worker thread teardown so
    // a late WM_PARENTNOTIFY arriving during destruction reads a null
    // field instead of invoking a freed ref.
    if (e->click_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Symmetric cleanup for the dialog callback global ref.  Storage
    // only in this canvas; STORY-004-003 will fire the ScriptDialogOpening
    // event handler off this field, so the symmetric cleanup is required
    // even though STORY-004-001 itself never invokes the callback on
    // Windows.
    if (e->dialog_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Symmetric cleanup for the password callback global ref (Canvas 28).
    // The WebMessageReceived handler fires off this field, so clear it
    // before the worker thread tears down.
    if (e->password_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->password_callback);
        e->password_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Symmetric cleanup for the download callback global ref (Canvas 25),
    // plus every download still in flight.  A download can outlive the
    // page and the navigation, so both go before the worker thread tears
    // down -- a late fire into a freed ref is an access violation, not an
    // exception.
    win_download_drop_engine(e);
    if (e->downloads_available && e->webview) {
        ICoreWebView2_4 *wv4 = nullptr;
        if (SUCCEEDED(e->webview->QueryInterface(
                __uuidof(ICoreWebView2_4),
                reinterpret_cast<void **>(&wv4))) && wv4) {
            wv4->remove_DownloadStarting(e->download_starting_token);
            wv4->Release();
        }
        e->downloads_available = false;
    }
    if (e->download_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Canvas 34 D9: stop asking, then drop the navigation callback's global
    // ref, before the worker thread tears down.
    if (e->navigation_registered && e->webview) {
        e->webview->remove_NavigationStarting(e->navigation_starting_token);
        e->webview->remove_FrameNavigationStarting(
            e->frame_navigation_starting_token);
        e->navigation_registered = false;
    }
    if (e->navigation_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->navigation_callback);
        e->navigation_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Symmetric cleanup for the popup callback global ref (Canvas 17).  The
    // NewWindowRequested handler fires off this field, so clear it before the
    // worker thread tears down.
    if (e->popup_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
        // Canvas 21 (1.5.0): the User-Agent resolver's global ref goes with the
        // popup callback -- only the popup path reads it, and a late popup
        // during teardown would otherwise follow a freed ref.
        if (env && e->ua_resolver) env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Release the environment ref taken at environment-ready (Canvas 17).
    if (e->environment) {
        e->environment->Release();
        e->environment = nullptr;
    }
    {
        std::lock_guard<std::mutex> lk(e->bindings_mutex);
        for (auto &kv : e->bindings) {
            Binding *b = kv.second;
            JNIEnv *env = nullptr;
            bool detach = false;
            if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
                e->jvm->AttachCurrentThread((void **)&env, nullptr);
                detach = true;
            }
            if (env) {
                env->DeleteGlobalRef(b->fn);
                env->DeleteGlobalRef(b->cls);
            }
            if (detach) e->jvm->DetachCurrentThread();
            delete b;
        }
        e->bindings.clear();
    }
    delete e;
}

static std::wstring utf8_to_wide(const char *s) {
    if (!s) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, &w[0], n);
    if (!w.empty() && w.back() == L'\0') w.pop_back();
    return w;
}

// Convert a (possibly null) UTF-16 LPWSTR to a UTF-8 std::string.
// Returns the empty string for null / empty input or on WideCharToMultiByte
// failure.  Used by ScriptDialogHandler::Invoke to copy the COM-allocated
// args strings into UTF-8 std::strings BEFORE the JNI worker thread runs,
// so we can CoTaskMemFree the LPWSTRs while still on the WebView2 worker
// (mirrors the inline pattern already used in engine_on_message).
static std::string wide_to_utf8(LPCWSTR w) {
    if (!w || *w == L'\0') return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0,
                                nullptr, nullptr);
    if (n <= 0) return std::string();
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    if (!s.empty() && s.back() == '\0') s.pop_back();
    return s;
}

// Build a normal embedded Engine from a claimed RetainedPopup, reusing its
// child controller/webview (apartment-bound to the opener's worker thread).
// The retained COM references + inherited global refs are TRANSFERRED into the
// returned Engine (rp is a plain shell freed by the dispatched op -- its raw
// pointers are copied, not re-released, so no double-free).  Returns the Engine
// (never null here; the caller already validated the claim).  The reparent +
// handler registration happen asynchronously on the shared worker thread; the
// Engine is returned immediately with a valid controller/webview so Java gets a
// usable peer handle.  ON-DEVICE-VALIDATION-REQUIRED: put_ParentWindow reparent
// of a live (mid-navigation) controller, and the retained->adopted handler
// handoff.
static Engine *adopt_retained_popup(JNIEnv *env, HWND parent, RetainedPopup *rp,
                                    int debug) {
    Engine *e = new Engine();
    e->parent = parent;
    e->debug = debug != 0;
    e->jvm = rp->jvm;
    e->shared_thread = true;                 // reuse opener's worker thread
    e->thread_id = rp->worker_thread_id;
    e->thread = nullptr;
    e->controller = rp->controller;          // transferred (already AddRef'd)
    e->webview = rp->webview;                // transferred (already AddRef'd)
    e->environment = rp->environment;        // transferred (already AddRef'd)
    e->popup_callback = rp->popup_callback;  // inherited global refs transferred
    e->dialog_callback = rp->dialog_callback;

    // Everything below touches WebView2 objects, so it MUST run on the
    // controller's apartment thread (the opener's worker thread).
    post_to_worker_thread(rp->worker_thread_id, [e, parent, rp] {
        ICoreWebView2 *child = e->webview;
        // Remove the retained-phase handlers (bound to the opener engine)
        // before wiring the adopted engine's own handlers, so exactly one
        // handler set survives (analogue of Linux disconnect_by_data).
        if (child) {
            child->remove_NewWindowRequested(rp->new_window_token);
            child->remove_ScriptDialogOpening(rp->script_dialog_token);
            child->remove_WindowCloseRequested(rp->close_token);
        }

        // Create the standard child HWND under the adopting AWT canvas HWND,
        // exactly like a freshly-created engine, so WM_SIZE / WM_PARENTNOTIFY
        // (the click hook) behave identically.
        ensure_class_registered();
        RECT pr;
        GetClientRect(parent, &pr);
        int cw = pr.right - pr.left;
        int chh = pr.bottom - pr.top;
        if (cw <= 0) cw = 1;
        if (chh <= 0) chh = 1;
        e->child = CreateWindowEx(0, "WebViewEmbedChild", "",
                                  WS_CHILD | WS_VISIBLE, 0, 0, cw, chh,
                                  parent, nullptr, GetModuleHandle(nullptr),
                                  nullptr);
        if (e->child) SetWindowLongPtr(e->child, GWLP_USERDATA, (LONG_PTR)e);

        // Reparent the retained controller into the adopting surface and make
        // it visible.
        if (e->controller) {
            e->controller->put_ParentWindow(e->child ? e->child : parent);
            RECT rc;
            GetClientRect(e->child ? e->child : parent, &rc);
            e->controller->put_Bounds(rc);
            e->controller->put_IsVisible(TRUE);

            auto *gh = new FocusHandler(e, true);
            e->controller->add_GotFocus(gh, &e->got_focus_token);
            gh->Release();
            auto *lh = new FocusHandler(e, false);
            e->controller->add_LostFocus(lh, &e->lost_focus_token);
            lh->Release();
        }

        if (child) {
            ICoreWebView2Settings *settings = nullptr;
            if (SUCCEEDED(child->get_Settings(&settings)) && settings) {
                settings->put_AreDevToolsEnabled(e->debug ? TRUE : FALSE);
                settings->put_AreDefaultContextMenusEnabled(TRUE);
                settings->put_AreDefaultScriptDialogsEnabled(FALSE);
                settings->Release();
            }
            child->AddScriptToExecuteOnDocumentCreated(
                L"window.external = { invoke: s => "
                L"window.chrome.webview.postMessage(s) };",
                nullptr);

            auto *mh = new MsgHandler(e);
            child->add_WebMessageReceived(mh, &e->message_token);
            mh->Release();

            auto *sdh = new ScriptDialogHandler(e);
            child->add_ScriptDialogOpening(sdh, &e->script_dialog_token);
            sdh->Release();

            auto *nwh = new NewWindowRequestedHandler(e);
            child->add_NewWindowRequested(nwh, &e->new_window_token);
            nwh->Release();

            // Navigation decisions (Canvas 34 D9) on the adopted child.
            win_register_navigation(e, child);
        }

        // The hidden holder top-level window is no longer needed once the
        // controller has been reparented onto the adopting child HWND above.
        // Destroy it on this (its creating) worker thread to avoid leaking a
        // top-level HWND per adopted popup.  retained_popup_teardown does the
        // same on the discard/close paths.
        if (rp->holder) { DestroyWindow(rp->holder); rp->holder = nullptr; }

        // Free the retained shell.  Its COM references + global refs were
        // TRANSFERRED to e (copied, not re-AddRef'd), so DO NOT release them
        // here; just free the struct.
        delete rp;
    });

    return e;
}

// Standard base64url (no padding) over raw bytes -- used to build a
// delimiter-safe Credential Manager TargetName in the password store
// (Canvas 28).
static std::string pw_b64url_encode(const std::string &in) {
    static const char *T =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    out.reserve(((in.size() + 2) / 3) * 4);
    size_t i = 0;
    for (; i + 2 < in.size(); i += 3) {
        unsigned n = ((unsigned char)in[i] << 16)
                   | ((unsigned char)in[i + 1] << 8)
                   | ((unsigned char)in[i + 2]);
        out.push_back(T[(n >> 18) & 63]);
        out.push_back(T[(n >> 12) & 63]);
        out.push_back(T[(n >> 6) & 63]);
        out.push_back(T[n & 63]);
    }
    size_t rem = in.size() - i;
    if (rem == 1) {
        unsigned n = ((unsigned char)in[i] << 16);
        out.push_back(T[(n >> 18) & 63]);
        out.push_back(T[(n >> 12) & 63]);
    } else if (rem == 2) {
        unsigned n = ((unsigned char)in[i] << 16)
                   | ((unsigned char)in[i + 1] << 8);
        out.push_back(T[(n >> 18) & 63]);
        out.push_back(T[(n >> 12) & 63]);
        out.push_back(T[(n >> 6) & 63]);
    }
    return out;
}

static bool pw_b64url_decode(const std::string &in, std::string &out) {
    auto val = [](char c) -> int {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '-') return 62;
        if (c == '_') return 63;
        return -1;
    };
    out.clear();
    int buf = 0, bits = 0;
    for (char c : in) {
        int v = val(c);
        if (v < 0) return false;
        buf = (buf << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back((char)((buf >> bits) & 0xFF));
        }
    }
    return true;
}

} // namespace embed_win

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

using embed_win::Binding;
using embed_win::Engine;
using embed_win::JawtLock;

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_jawt_1get_1window_1handle
  (JNIEnv *env, jclass, jobject component) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) return 0;
    auto *info = (JAWT_Win32DrawingSurfaceInfo *)lock.dsi->platformInfo;
    return (jlong)(uintptr_t)info->hwnd;
}

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1create
  (JNIEnv *env, jclass, jobject component, jint debug) {
    Engine *e = embed_win::create_engine(env, component, debug);
    return (jlong)e;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1destroy
  (JNIEnv *, jclass, jlong wv) {
    embed_win::destroy_engine((Engine *)wv);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1bounds
  (JNIEnv *, jclass, jlong wv, jint /*x*/, jint /*y*/, jint w, jint h) {
    auto *e = (Engine *)wv;
    if (!e) return;
    // The Java side sends x,y in AWT-window content-pane coordinates (used
    // by the macOS WKWebView path, which is a sibling of the canvas).  On
    // Windows our child HWND is parented directly under the canvas's HWND,
    // so it should always sit at (0,0) relative to its parent -- using the
    // window-relative x,y would offset us by the canvas's own position.
    embed_win::dispatch_to_thread(e, [e, w, h] {
        if (e->child) {
            SetWindowPos(e->child, nullptr, 0, 0, w, h,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        if (e->controller) {
            RECT r{0, 0, w, h};
            e->controller->put_Bounds(r);
        }
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1visible
  (JNIEnv *, jclass, jlong wv, jint visible) {
    auto *e = (Engine *)wv;
    if (!e) return;
    embed_win::dispatch_to_thread(e, [e, visible] {
        if (e->controller) e->controller->put_IsVisible(visible != 0 ? TRUE : FALSE);
        if (e->child) ShowWindow(e->child, visible != 0 ? SW_SHOWNA : SW_HIDE);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1request_1focus
  (JNIEnv *, jclass, jlong wv) {
    auto *e = (Engine *)wv;
    if (!e) return;
    embed_win::dispatch_to_thread(e, [e] {
        if (e->controller) {
            e->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
        }
    });
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1pump
  (JNIEnv *, jclass, jlong /*wv*/, jint /*wait*/) {
    // Worker thread handles its own pumping.
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1navigate
  (JNIEnv *env, jclass, jlong wv, jstring url) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *u = env->GetStringUTFChars(url, nullptr);
    std::wstring w = embed_win::utf8_to_wide(u);
    env->ReleaseStringUTFChars(url, u);
    embed_win::dispatch_to_thread(e, [e, w] {
        if (e->webview) e->webview->Navigate(w.c_str());
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1init
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *s = env->GetStringUTFChars(js, nullptr);
    std::wstring w = embed_win::utf8_to_wide(s);
    env->ReleaseStringUTFChars(js, s);
    embed_win::dispatch_to_thread(e, [e, w] {
        if (e->webview) e->webview->AddScriptToExecuteOnDocumentCreated(
            w.c_str(), nullptr);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1eval
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *s = env->GetStringUTFChars(js, nullptr);
    std::wstring w = embed_win::utf8_to_wide(s);
    env->ReleaseStringUTFChars(js, s);
    embed_win::dispatch_to_thread(e, [e, w] {
        if (e->webview) e->webview->ExecuteScript(w.c_str(), nullptr);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1bind
  (JNIEnv *env, jclass, jlong wv, jstring name, jobject fn, jlong /*arg*/) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *n = env->GetStringUTFChars(name, nullptr);
    Binding *b = new Binding();
    b->name = n ? n : "";
    b->fn = env->NewGlobalRef(fn);
    jclass cls = env->GetObjectClass(fn);
    b->cls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    env->ReleaseStringUTFChars(name, n);

    {
        std::lock_guard<std::mutex> lk(e->bindings_mutex);
        e->bindings[b->name] = b;
    }

    std::string js =
        std::string("(function(){var n='") + b->name + "';" +
        "window[n]=function(){"
        "  var me=window[n];"
        "  if(!me.callbacks){me.callbacks={};me.errors={};}"
        "  var seq=(me.lastSeq||0)+1;me.lastSeq=seq;"
        "  var p=new Promise(function(res,rej){me.callbacks[seq]=res;me.errors[seq]=rej;});"
        "  window.external.invoke(JSON.stringify({name:n,seq:seq,"
        "    args:Array.prototype.slice.call(arguments)}));"
        "  return p;"
        "};})()";
    std::wstring wjs = embed_win::utf8_to_wide(js.c_str());
    embed_win::dispatch_to_thread(e, [e, wjs] {
        if (e->webview) e->webview->AddScriptToExecuteOnDocumentCreated(
            wjs.c_str(), nullptr);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1dispatch
  (JNIEnv *env, jclass, jlong wv, jobject callback) {
    auto *e = (Engine *)wv;
    if (!e) return;
    JavaVM *jvm = e->jvm;
    jobject ref = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    jclass gcls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    embed_win::dispatch_to_thread(e, [jvm, ref, gcls] {
        JNIEnv *e2 = nullptr;
        bool detach = false;
        if (jvm->GetEnv((void **)&e2, JNI_VERSION_1_6) != JNI_OK) {
            jvm->AttachCurrentThread((void **)&e2, nullptr);
            detach = true;
        }
        jmethodID m = e2->GetMethodID(gcls, "run", "()V");
        if (m) e2->CallVoidMethod(ref, m);
        e2->DeleteGlobalRef(ref);
        e2->DeleteGlobalRef(gcls);
        if (detach) jvm->DetachCurrentThread();
    });
}

// ---------------------------------------------------------------------------
// Offscreen / lightweight JNI exports.  Lightweight is Linux-only today;
// Windows stubs return 0 / no-op so WebViewLightweightComponent falls back
// to its empty Swing background.
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1create
  (JNIEnv *, jclass, jint, jint, jint) { return 0; }

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1destroy
  (JNIEnv *, jclass, jlong) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1resize
  (JNIEnv *, jclass, jlong, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1navigate
  (JNIEnv *, jclass, jlong, jstring) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1snapshot
  (JNIEnv *, jclass, jlong, jintArray, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1button
  (JNIEnv *, jclass, jlong, jint, jint, jint, jint, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1motion
  (JNIEnv *, jclass, jlong, jint, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1scroll
  (JNIEnv *, jclass, jlong, jint, jint, jdouble, jdouble, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1key_1event
  (JNIEnv *, jclass, jlong, jint, jint, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1init
  (JNIEnv *, jclass, jlong, jstring) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1eval
  (JNIEnv *, jclass, jlong, jstring) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1bind
  (JNIEnv *, jclass, jlong, jstring, jobject, jlong) {}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1open_1devtools
  (JNIEnv *, jclass, jlong) { return 0; }

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1execute_1editing_1command
  (JNIEnv *, jclass, jlong, jint) {}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1open_1devtools
  (JNIEnv *, jclass, jlong wv) {
    auto *e = (Engine *)wv;
    if (!e) return 0;
    // Marshal to the WebView2 worker thread (the thread the controller
    // was created on) and call OpenDevToolsWindow.  Synchronously wait
    // for the worker to report success/failure so the JNI return value
    // accurately reflects whether the window opened.  The wait is
    // bounded by normal WebView2 method dispatch.
    std::atomic<bool> done{false};
    std::atomic<int> result{0};
    embed_win::dispatch_to_thread(e, [e, &done, &result] {
        if (!e->webview) { result.store(0); done.store(true); return; }
        ICoreWebView2Settings *settings = nullptr;
        BOOL enabled = FALSE;
        if (SUCCEEDED(e->webview->get_Settings(&settings)) && settings) {
            settings->get_AreDevToolsEnabled(&enabled);
            settings->Release();
        }
        if (!enabled) { result.store(0); done.store(true); return; }
        HRESULT hr = e->webview->OpenDevToolsWindow();
        if (FAILED(hr)) {
            WV_LOG("OpenDevToolsWindow failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            result.store(0);
        } else {
            result.store(1);
        }
        done.store(true);
    });
    while (!done.load()) {
        Sleep(1);
    }
    return (jint)result.load();
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1is_1native_1first_1responder
  (JNIEnv *, jclass, jlong) {
    // Windows has no notion of "first responder"; the focus-cooperation
    // dispatcher heuristic is macOS-only.  Returning 0 means the Java
    // dispatcher falls back to its standard AWT-focus-owner gating.
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1focus_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Store the Java callback (JNI global ref) on the Engine.  The
    // ICoreWebView2 GotFocus / LostFocus handlers (registered during
    // engine creation) read this field and invoke the callback so the
    // Java side can mirror WebView2's focus state into Swing -- e.g.,
    // suppress and restore the previously-focused JTextComponent's
    // caret while WebView2 holds Win32 keyboard focus.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->focus_callback) {
        env->DeleteGlobalRef(e->focus_callback);
        e->focus_callback = nullptr;
    }
    if (cb) {
        e->focus_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1click_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Store the Java callback (JNI global ref) on the Engine.  The
    // WM_PARENTNOTIFY hook in EmbedWndProc reads this field and invokes
    // the callback for every WM_LBUTTONDOWN / WM_RBUTTONDOWN /
    // WM_MBUTTONDOWN / WM_XBUTTONDOWN that the WebView2 child HWND
    // receives, so Swing can dismiss any open JPopupMenu when the user
    // clicks into the WebView.  Without this hook AWT's MouseGrabber
    // AWTEventListener never sees the click and the popup stays open.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->click_callback) {
        env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
    }
    if (cb) {
        e->click_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1dialog_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Store the Java callback (JNI global ref) on the Engine.
    // STORY-004-001 only ships storage on Windows -- STORY-004-003
    // wires the ICoreWebView2::add_ScriptDialogOpening event handler
    // off this field plus put_AreDefaultScriptDialogsEnabled(FALSE) so
    // built-in WebView2 dialogs are suppressed and JS alert / confirm /
    // prompt route through Java instead.  <input type=file> remains
    // OS-native on Windows -- WebView2 exposes no public hook for it.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1dialog_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // Windows has no offscreen engine; OffscreenWebView.create returns
    // null on Windows so this JNI bridge should never be reached.
    // Stub it for link-symmetry across all three native binaries.
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1download_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Canvas 25: the DownloadStarting handler reads this field on every
    // download, so installing the global ref here is the single place that
    // wires the Java handler into the WebView2 event path.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->download_callback) {
        env->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
    }
    if (cb) {
        e->download_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1download_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // Windows has no offscreen engine; OffscreenWebView.create returns
    // null on Windows so this JNI bridge should never be reached.
    // Stub it for link-symmetry across all three native binaries.
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1password_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Stores the WebViewPasswordCallback global ref that the
    // __webview_pw__ branch of the WebMessageReceived handler fires off
    // (Canvas 28).  Cleared here on replacement and in the engine destroy
    // path.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->password_callback) {
        env->DeleteGlobalRef(e->password_callback);
        e->password_callback = nullptr;
    }
    if (cb) {
        e->password_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1password_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // Windows has no offscreen engine; stub for link-symmetry.
}

// Credential store primitives — Canvas 28, backed by the Windows
// Credential Manager (CredWriteW / CredEnumerateW / CredDeleteW).
//
// Each record is a CRED_TYPE_GENERIC credential whose TargetName is
//   "<service>:" + b64url(origin) + "|" + b64url(username)
// (origin and username are base64url-encoded so the '|' delimiter and any
// URL characters are unambiguous), and whose CredentialBlob is UTF-8
// "<savedAtMillis>\n<password>".  b64url keeps TargetName a valid,
// delimiter-safe wide string.  The base64url helpers live in namespace
// embed_win (above); reference them qualified from this extern "C" block.

JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1save
  (JNIEnv *env, jclass, jstring jservice, jstring jorigin, jstring juser,
   jstring jpass, jlong millis) {
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    const char *user = env->GetStringUTFChars(juser, nullptr);
    const char *pass = env->GetStringUTFChars(jpass, nullptr);
    std::string target = std::string(service ? service : "") + ":"
        + embed_win::pw_b64url_encode(origin ? origin : "") + "|"
        + embed_win::pw_b64url_encode(user ? user : "");
    std::string blob = std::to_string((long long)millis) + "\n"
        + (pass ? pass : "");
    std::wstring targetW = embed_win::utf8_to_wide(target.c_str());
    std::wstring userW = embed_win::utf8_to_wide(user ? user : "");

    CREDENTIALW cred = {};
    cred.Type = CRED_TYPE_GENERIC;
    cred.TargetName = const_cast<LPWSTR>(targetW.c_str());
    cred.CredentialBlobSize = (DWORD)blob.size();
    cred.CredentialBlob = (LPBYTE)blob.data();
    cred.Persist = CRED_PERSIST_LOCAL_MACHINE;
    cred.UserName = const_cast<LPWSTR>(userW.c_str());
    BOOL ok = CredWriteW(&cred, 0);
    // Do not leave the plaintext password lingering in this buffer.
    if (!blob.empty()) SecureZeroMemory(&blob[0], blob.size());

    if (service) env->ReleaseStringUTFChars(jservice, service);
    if (origin) env->ReleaseStringUTFChars(jorigin, origin);
    if (user) env->ReleaseStringUTFChars(juser, user);
    if (pass) env->ReleaseStringUTFChars(jpass, pass);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1find
  (JNIEnv *env, jclass, jstring jservice, jstring jorigin) {
    jclass strCls = env->FindClass("java/lang/String");
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    std::string svc = service ? service : "";
    std::string targetOrigin = origin ? origin : "";
    std::string prefix = svc + ":";
    std::wstring filterW = embed_win::utf8_to_wide((svc + ":*").c_str());

    std::vector<std::string> triples;
    DWORD count = 0;
    PCREDENTIALW *creds = nullptr;
    if (CredEnumerateW(filterW.c_str(), 0, &count, &creds) && creds) {
        for (DWORD i = 0; i < count; i++) {
            PCREDENTIALW c = creds[i];
            if (!c || !c->TargetName) continue;
            std::string name = embed_win::wide_to_utf8(c->TargetName);
            if (name.rfind(prefix, 0) != 0) continue;
            std::string rest = name.substr(prefix.size());
            size_t bar = rest.find('|');
            if (bar == std::string::npos) continue;
            std::string origin_dec, user_dec;
            if (!embed_win::pw_b64url_decode(rest.substr(0, bar), origin_dec)) continue;
            if (!embed_win::pw_b64url_decode(rest.substr(bar + 1), user_dec)) continue;
            if (origin_dec != targetOrigin) continue;
            std::string blob;
            if (c->CredentialBlob && c->CredentialBlobSize > 0) {
                blob.assign((const char *)c->CredentialBlob,
                            (size_t)c->CredentialBlobSize);
            }
            std::string millisStr = "0", password;
            size_t nl = blob.find('\n');
            if (nl != std::string::npos) {
                millisStr = blob.substr(0, nl);
                password = blob.substr(nl + 1);
            } else {
                password = blob;
            }
            triples.push_back(user_dec);
            triples.push_back(millisStr);
            triples.push_back(password);
        }
        CredFree(creds);
    }
    if (service) env->ReleaseStringUTFChars(jservice, service);
    if (origin) env->ReleaseStringUTFChars(jorigin, origin);

    jobjectArray out =
        env->NewObjectArray((jsize)triples.size(), strCls, nullptr);
    for (size_t i = 0; i < triples.size(); i++) {
        jstring js = env->NewStringUTF(triples[i].c_str());
        env->SetObjectArrayElement(out, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

JNIEXPORT jobjectArray JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1find_1all
  (JNIEnv *env, jclass, jstring jservice) {
    jclass strCls = env->FindClass("java/lang/String");
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    std::string svc = service ? service : "";
    std::string prefix = svc + ":";
    std::wstring filterW = embed_win::utf8_to_wide((svc + ":*").c_str());

    // Enumerate-all: same CredEnumerateW over the namespace prefix as find,
    // but with no origin filter — every entry is kept and its origin emitted.
    std::vector<std::string> quads;
    DWORD count = 0;
    PCREDENTIALW *creds = nullptr;
    if (CredEnumerateW(filterW.c_str(), 0, &count, &creds) && creds) {
        for (DWORD i = 0; i < count; i++) {
            PCREDENTIALW c = creds[i];
            if (!c || !c->TargetName) continue;
            std::string name = embed_win::wide_to_utf8(c->TargetName);
            if (name.rfind(prefix, 0) != 0) continue;
            std::string rest = name.substr(prefix.size());
            size_t bar = rest.find('|');
            if (bar == std::string::npos) continue;
            std::string origin_dec, user_dec;
            if (!embed_win::pw_b64url_decode(rest.substr(0, bar), origin_dec)) continue;
            if (!embed_win::pw_b64url_decode(rest.substr(bar + 1), user_dec)) continue;
            std::string blob;
            if (c->CredentialBlob && c->CredentialBlobSize > 0) {
                blob.assign((const char *)c->CredentialBlob,
                            (size_t)c->CredentialBlobSize);
            }
            std::string millisStr = "0", password;
            size_t nl = blob.find('\n');
            if (nl != std::string::npos) {
                millisStr = blob.substr(0, nl);
                password = blob.substr(nl + 1);
            } else {
                password = blob;
            }
            quads.push_back(origin_dec);
            quads.push_back(user_dec);
            quads.push_back(millisStr);
            quads.push_back(password);
        }
        CredFree(creds);
    }
    if (service) env->ReleaseStringUTFChars(jservice, service);

    jobjectArray out =
        env->NewObjectArray((jsize)quads.size(), strCls, nullptr);
    for (size_t i = 0; i < quads.size(); i++) {
        jstring js = env->NewStringUTF(quads[i].c_str());
        env->SetObjectArrayElement(out, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1delete
  (JNIEnv *env, jclass, jstring jservice, jstring jorigin, jstring juser) {
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    const char *user = env->GetStringUTFChars(juser, nullptr);
    std::string target = std::string(service ? service : "") + ":"
        + embed_win::pw_b64url_encode(origin ? origin : "") + "|"
        + embed_win::pw_b64url_encode(user ? user : "");
    std::wstring targetW = embed_win::utf8_to_wide(target.c_str());
    BOOL ok = CredDeleteW(targetW.c_str(), CRED_TYPE_GENERIC, 0);
    if (service) env->ReleaseStringUTFChars(jservice, service);
    if (origin) env->ReleaseStringUTFChars(jorigin, origin);
    if (user) env->ReleaseStringUTFChars(juser, user);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1available
  (JNIEnv *, jclass) {
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1popup_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Canvas 15 ships the callback storage on Windows; the follow-up
    // Windows coverage canvas wires ICoreWebView2::add_NewWindowRequested
    // off this field.  Until then storing the ref is a harmless no-op and
    // window.open stays blocked on Windows.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->popup_callback) {
        env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
    }
    if (cb) {
        e->popup_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1popup_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // Windows has no offscreen engine; stub for link-symmetry across all
    // three native binaries.
}

// Navigation decisions (Canvas 34 D5, D9).
JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1navigation_1available
  (JNIEnv *, jclass) {
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1navigation_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->navigation_callback) {
        env->DeleteGlobalRef(e->navigation_callback);
        e->navigation_callback = nullptr;
    }
    if (cb) {
        e->navigation_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1navigation_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // Windows has no offscreen engine; stub for link-symmetry.
}

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1adopt_1popup
  (JNIEnv *, jclass, jint, jint, jlong, jint) {
    // Windows has no offscreen engine (webview_offscreen_create returns 0), so
    // adoption into a lightweight component is never triggered here; return 0.
    // Popup adoption on Windows uses the heavyweight webview_embed_adopt_popup
    // path (Canvas 20).  Stub for link-symmetry (Canvas 19 offscreen bridge).
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1discard_1popup
  (JNIEnv *, jclass, jlong, jlong) {
    // Windows has no offscreen engine; stub for link-symmetry.
}

// Custom User-Agent — Canvas 21.  ua == null / empty restores the engine
// default.  Applied on the WebView2 UI thread via ICoreWebView2Settings2;
// no-op on an old runtime lacking the _2 settings interface.  Takes effect on
// the next navigation.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1user_1agent
  (JNIEnv *env, jclass, jlong wv, jstring ua) {
    auto *e = (Engine *)wv;
    if (!e) return;
    std::wstring w;
    if (ua) {
        const char *s = env->GetStringUTFChars(ua, nullptr);
        w = embed_win::utf8_to_wide(s);
        env->ReleaseStringUTFChars(ua, s);
    }
    embed_win::dispatch_to_thread(e, [e, w] {
        // Canvas 21: track the override so the NewWindowRequested handler can
        // propagate it to popup children (empty == engine default).  Recorded
        // on the worker thread, where the popup handler also reads it.
        e->user_agent = w;
        if (!e->webview) {
            WV_LOG("set_user_agent: no webview yet, stored only");
            return;
        }
        // Canvas 21 Op 7.3: report the outcome. WebView2's failure mode for an
        // unavailable _2 interface is a silent no-op, so without this a UA that
        // never changes is indistinguishable from one the engine ignored.
        ICoreWebView2Settings *settings = nullptr;
        HRESULT hrGet = e->webview->get_Settings(&settings);
        if (SUCCEEDED(hrGet) && settings) {
            ICoreWebView2Settings2 *settings2 = nullptr;
            HRESULT hrQi = settings->QueryInterface(
                    __uuidof(ICoreWebView2Settings2),
                    reinterpret_cast<void **>(&settings2));
            if (SUCCEEDED(hrQi) && settings2) {
                // Capture the pristine default before the first override, so a
                // later reset has something literal to write back.
                if (e->default_user_agent.empty()) {
                    LPWSTR cur = nullptr;
                    if (SUCCEEDED(settings2->get_UserAgent(&cur)) && cur) {
                        e->default_user_agent.assign(cur);
                        CoTaskMemFree(cur);
                    }
                }
                // A reset restores the captured default. put_UserAgent(L"") is
                // NOT a reset on this engine -- it succeeds and does nothing.
                const bool reset = w.empty();
                const std::wstring &target =
                    reset ? e->default_user_agent : w;
                if (reset && target.empty()) {
                    WV_LOG("set_user_agent: cannot reset -- the engine default "
                           "was never captured; the previous override stays in "
                           "force");
                } else {
                    HRESULT hrPut = settings2->put_UserAgent(target.c_str());
                    WV_LOG("set_user_agent: put_UserAgent hr=0x%08lx %sua=%ls",
                           (unsigned long)hrPut,
                           reset ? "(restoring captured default) " : "",
                           target.c_str());
                }
                settings2->Release();
            } else {
                WV_LOG("set_user_agent: ICoreWebView2Settings2 UNAVAILABLE "
                       "hr=0x%08lx -- the runtime is too old to set a UA; "
                       "this call is a no-op", (unsigned long)hrQi);
            }
            settings->Release();
        } else {
            WV_LOG("set_user_agent: get_Settings failed hr=0x%08lx",
                   (unsigned long)hrGet);
        }
    });
}

// Install/clear the per-destination User-Agent resolver — Canvas 21 (1.5.0).
// The resolver is a java.util.function.Function<String,String>; it is held as a
// JNI global ref and consulted by the NewWindowRequested handler with the popup
// child's own target URL.  resolver == nullptr clears it.  The global ref is
// created/deleted on the CALLING thread (which holds a JNIEnv); the worker
// thread only reads the jobject, which is safe for a global ref.  Never throws.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1user_1agent_1resolver
  (JNIEnv *env, jclass, jlong wv, jobject resolver) {
    auto *e = (embed_win::Engine *)wv;
    if (!e) return;
    if (e->ua_resolver) {
        env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
    }
    if (resolver) {
        e->ua_resolver = env->NewGlobalRef(resolver);
    }
}

// Offscreen counterpart — no offscreen engine on Windows, so a silent no-op
// (mirrors webview_offscreen_set_user_agent).
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1user_1agent_1resolver
  (JNIEnv *, jclass, jlong, jobject) {
}

// Clear the embedded WebView's HTTP resource cache — Canvas 22.  Purges the
// DISK_CACHE browsing-data kind via ICoreWebView2Profile2::ClearBrowsingData on
// the WebView2 UI thread; cookies / DOM storage survive.  Runtime-tolerant: a
// no-op if the runtime is too old for the profile interfaces (mirrors the UA
// setter's ICoreWebView2Settings2 QueryInterface pattern).  Never throws via
// JNI.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1clear_1cache
  (JNIEnv *, jclass, jlong wv) {
    auto *e = (Engine *)wv;
    if (!e) return;
    embed_win::dispatch_to_thread(e, [e] {
        if (!e->webview) return;
        ICoreWebView2_13 *wv13 = nullptr;
        if (FAILED(e->webview->QueryInterface(
                __uuidof(ICoreWebView2_13),
                reinterpret_cast<void **>(&wv13))) || !wv13) {
            return;  // runtime too old for the Profile API
        }
        ICoreWebView2Profile *profile = nullptr;
        if (SUCCEEDED(wv13->get_Profile(&profile)) && profile) {
            ICoreWebView2Profile2 *profile2 = nullptr;
            if (SUCCEEDED(profile->QueryInterface(
                    __uuidof(ICoreWebView2Profile2),
                    reinterpret_cast<void **>(&profile2))) && profile2) {
                // Resource cache only (DISK_CACHE) — cookies/storage survive.
                profile2->ClearBrowsingData(
                    COREWEBVIEW2_BROWSING_DATA_KINDS_DISK_CACHE,
                    new embed_win::ClearCacheHandler());
                profile2->Release();
            }
            profile->Release();
        }
        wv13->Release();
    });
}

// Offscreen cache purge — Windows has no offscreen engine; stub for
// link-symmetry with the JNI declaration.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1clear_1cache
  (JNIEnv *, jclass, jlong) {
}

// Return all cookies applicable to the requested URL in HTTP Cookie header
// syntax.  WebView2's cookie manager includes HttpOnly cookies, unlike
// document.cookie, which is required for authenticated browser handoff.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1get_1cookies
  (JNIEnv *env, jclass, jlong wv, jstring url, jobject callback) {
    auto *completion = embed_win::new_cookie_completion(env, callback);
    if (!completion) return;
    auto *e = (Engine *)wv;
    if (!e || !url || e->thread_id == 0) {
        embed_win::complete_cookie_query(
            completion, "", "WebView is not available");
        return;
    }
    const char *chars = env->GetStringUTFChars(url, nullptr);
    std::wstring requested_url = embed_win::utf8_to_wide(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(url, chars);
    bool posted = embed_win::dispatch_to_thread(e, [e, requested_url, completion] {
        if (!e->webview) {
            embed_win::complete_cookie_query(
                completion, "", "WebView is not available");
            return;
        }
        ICoreWebView2_2 *wv2 = nullptr;
        if (FAILED(e->webview->QueryInterface(
                __uuidof(ICoreWebView2_2),
                reinterpret_cast<void **>(&wv2))) || !wv2) {
            embed_win::complete_cookie_query(
                completion, "", "WebView2 cookie manager is unavailable");
            return;
        }
        ICoreWebView2CookieManager *manager = nullptr;
        HRESULT manager_result = wv2->get_CookieManager(&manager);
        wv2->Release();
        if (FAILED(manager_result) || !manager) {
            embed_win::complete_cookie_query(
                completion, "", "WebView2 cookie manager is unavailable");
            return;
        }
        auto *handler = new embed_win::GetCookiesHandler(completion);
        HRESULT query_result = manager->GetCookies(
            requested_url.c_str(), handler);
        manager->Release();
        if (FAILED(query_result)) {
            handler->Invoke(query_result, nullptr);
        }
        // Release our reference. WebView2 retains the handler until its
        // asynchronous completion when GetCookies succeeds.
        handler->Release();
    });
    if (!posted) {
        // The worker is gone; the lambda was discarded unrun, so answer here.
        embed_win::complete_cookie_query(
            completion, "", "WebView is not available");
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1get_1cookies
  (JNIEnv *env, jclass, jlong, jstring, jobject callback) {
    auto *completion = embed_win::new_cookie_completion(env, callback);
    embed_win::complete_cookie_query(
        completion, "", "Offscreen cookie queries are unsupported on Windows");
}

// Print to PDF — Canvas 29 D10.  Must live inside this `extern "C"` block
// (D11).  Every path answers the callback exactly once; nothing throws into
// Java.
JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1pdf_1available
  (JNIEnv *, jclass) {
    return JNI_TRUE;
}

// Canvas 32: custom URL schemes on WebView2.  Whether the installed Runtime
// honours the registrations is only known when an environment is created
// (D7), so the library reports support.
JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1scheme_1available
  (JNIEnv *, jclass) {
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1scheme_1install
  (JNIEnv *env, jclass, jobjectArray names, jobject dispatcher) {
    std::lock_guard<std::mutex> lk(embed_win::g_scheme_mutex);
    if (embed_win::g_scheme_installed) return;
    embed_win::g_scheme_installed = true;
    env->GetJavaVM(&embed_win::g_scheme_jvm);
    if (dispatcher) embed_win::g_scheme_dispatcher = env->NewGlobalRef(dispatcher);
    jsize n = names ? env->GetArrayLength(names) : 0;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(names, i);
        if (!js) continue;
        const char *c = env->GetStringUTFChars(js, nullptr);
        if (c) {
            embed_win::g_scheme_names.push_back(c);
            env->ReleaseStringUTFChars(js, c);
        }
        env->DeleteLocalRef(js);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1scheme_1respond
  (JNIEnv *env, jclass, jlong id, jint status, jobjectArray headerPairs, jbyteArray body) {
    std::vector<std::string> pairs;
    jsize n = headerPairs ? env->GetArrayLength(headerPairs) : 0;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(headerPairs, i);
        std::string v;
        if (js) {
            const char *c = env->GetStringUTFChars(js, nullptr);
            if (c) {
                v = c;
                env->ReleaseStringUTFChars(js, c);
            }
            env->DeleteLocalRef(js);
        }
        pairs.push_back(v);
    }
    std::vector<unsigned char> bytes;
    jsize len = body ? env->GetArrayLength(body) : 0;
    if (len > 0) {
        bytes.resize((size_t)len);
        env->GetByteArrayRegion(body, 0, len, (jbyte *)bytes.data());
    }
    embed_win::win_scheme_respond((long long)id, (int)status, pairs, bytes);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1print_1to_1pdf
  (JNIEnv *env, jclass, jlong wv, jstring path, jdouble w, jdouble h,
   jdouble mt, jdouble mr, jdouble mb, jdouble ml, jboolean bg, jobject cb) {
    embed_win::PdfJob *job = embed_win::pdf_new_job(env, cb);
    auto *e = (Engine *)wv;
    if (!e) {
        embed_win::pdf_finish(job, false, embed_win::kPdfNotAttached);
        return;
    }
    // The path goes to WebView2 as UTF-16, straight from the Java string.
    std::wstring wpath;
    if (path) {
        const jchar *chars = env->GetStringChars(path, nullptr);
        if (chars) {
            wpath.assign(reinterpret_cast<const wchar_t *>(chars),
                         (size_t)env->GetStringLength(path));
            env->ReleaseStringChars(path, chars);
        }
    }
    const bool backgrounds = bg == JNI_TRUE;
    embed_win::dispatch_to_thread(e, [=] {
        if (!e->webview) {
            embed_win::pdf_finish(job, false, embed_win::kPdfNotAttached);
            return;
        }
        ICoreWebView2_7 *wv7 = nullptr;
        ICoreWebView2Environment6 *env6 = nullptr;
        if (FAILED(e->webview->QueryInterface(
                __uuidof(ICoreWebView2_7), reinterpret_cast<void **>(&wv7))) ||
            !wv7 || !e->environment ||
            FAILED(e->environment->QueryInterface(
                __uuidof(ICoreWebView2Environment6),
                reinterpret_cast<void **>(&env6))) || !env6) {
            if (wv7) wv7->Release();
            embed_win::pdf_finish(job, false,
                "PDF printing needs a newer WebView2 runtime.");
            return;
        }
        ICoreWebView2PrintSettings *settings = nullptr;
        HRESULT hr = env6->CreatePrintSettings(&settings);
        env6->Release();
        if (FAILED(hr) || !settings) {
            wv7->Release();
            embed_win::pdf_finish_hr(job, FAILED(hr) ? hr : E_FAIL);
            return;
        }
        settings->put_Orientation(COREWEBVIEW2_PRINT_ORIENTATION_PORTRAIT);
        settings->put_PageWidth(w);
        settings->put_PageHeight(h);
        settings->put_MarginTop(mt);
        settings->put_MarginRight(mr);
        settings->put_MarginBottom(mb);
        settings->put_MarginLeft(ml);
        settings->put_ScaleFactor(1.0);
        settings->put_ShouldPrintBackgrounds(backgrounds ? TRUE : FALSE);
        settings->put_ShouldPrintHeaderAndFooter(FALSE);

        auto *handler = new embed_win::PrintToPdfHandler(job);
        hr = wv7->PrintToPdf(wpath.c_str(), settings, handler);
        if (FAILED(hr)) {
            // The handler will not be invoked; answer here.
            embed_win::pdf_finish_hr(handler->take(), hr);
        }
        handler->Release();
        settings->Release();
        wv7->Release();
    });
}

// Offscreen print — Windows has no offscreen engine (Canvas 29 D13).
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1print_1to_1pdf
  (JNIEnv *env, jclass, jlong, jstring, jdouble, jdouble, jdouble, jdouble,
   jdouble, jdouble, jboolean, jobject cb) {
    embed_win::pdf_finish(embed_win::pdf_new_job(env, cb), false,
                          embed_win::kPdfNotAttached);
}

// Adopt a retained popup child (Canvas 20) into `parent`'s realized AWT HWND.
// Resolves the parent HWND via the SAME JAWT path as webview_embed_create,
// claims the RetainedPopup (adopt-once under the mutex; 0 -> Java throws
// IllegalStateException), and reuses its controller/webview.
JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1adopt_1popup
  (JNIEnv *env, jclass, jobject component, jlong popupId, jint debug) {
    // Resolve the parent AWT HWND (same mechanism as create_engine).
    HWND parent = nullptr;
    {
        JawtLock lock(env, component);
        if (!lock.ok || !lock.dsi->platformInfo) {
            WV_LOG("adopt_popup: JAWT lock failed");
            return 0;
        }
        auto *info = (JAWT_Win32DrawingSurfaceInfo *)lock.dsi->platformInfo;
        parent = info->hwnd;
    }
    if (!parent) {
        WV_LOG("adopt_popup: JAWT platform info had no HWND");
        return 0;
    }
    // Claim the retained popup (adopt-once).  0 for unknown/consumed id ->
    // EmbeddedWebView.adopt turns it into IllegalStateException.
    embed_win::RetainedPopup *rp = nullptr;
    {
        std::lock_guard<std::mutex> lk(embed_win::g_win_retained_popups_mutex);
        auto it = embed_win::g_win_retained_popups.find(popupId);
        if (it == embed_win::g_win_retained_popups.end()) return 0;
        rp = it->second;
        embed_win::g_win_retained_popups.erase(it);
    }
    Engine *e = embed_win::adopt_retained_popup(env, parent, rp, debug);
    return (jlong)e;
}

// Discard a retained-but-unadopted popup child (Canvas 20 reclaim path).  `wv`
// (any live embed peer) is unused on Windows -- the retained child is located
// by popupId alone.  Unknown id is a silent no-op.  The COM teardown is
// dispatched onto the child's owning worker thread.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1discard_1popup
  (JNIEnv *, jclass, jlong /*wv*/, jlong popupId) {
    embed_win::RetainedPopup *rp = nullptr;
    {
        std::lock_guard<std::mutex> lk(embed_win::g_win_retained_popups_mutex);
        auto it = embed_win::g_win_retained_popups.find(popupId);
        if (it == embed_win::g_win_retained_popups.end()) return;
        rp = it->second;
        embed_win::g_win_retained_popups.erase(it);
    }
    // Tear down on the apartment thread that owns the controller/webview.
    DWORD tid = rp->worker_thread_id;
    embed_win::post_to_worker_thread(tid, [rp] {
        embed_win::retained_popup_teardown(rp, /*fire_closed=*/true);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1release_1native_1focus
  (JNIEnv *, jclass, jlong wv) {
    // When AWT moves its Java-side focus owner to a Swing component, Win32
    // keyboard focus does NOT automatically follow -- if the user had
    // clicked into the WebView2 child HWND first, that HWND keeps focus
    // and steals subsequent keystrokes from AWT.  Force focus back to
    // the AWT-owned parent HWND so the new Swing focus owner actually
    // receives keystrokes.
    //
    // Win32 focus is per-thread.  The WebView2 worker thread holds focus
    // on its own HWND; the AWT-owned parent HWND lives on the EDT.  We
    // need to share input state between the two before SetFocus can
    // transfer focus across them.
    //
    // Done synchronously on the calling thread (the EDT, since this
    // JNI is invoked from the global focus-owner PropertyChangeListener
    // on the EDT) -- not via dispatch_to_thread, because the WebView2
    // worker may be busy with rendering or JS and we want SetFocus to
    // happen BEFORE the user's next keystroke.  AttachThreadInput merges
    // the two threads' input queues, so SetFocus from the EDT will
    // transfer focus from the WebView2 HWND in the worker thread's
    // queue to the parent HWND in the EDT's queue.
    auto *e = (Engine *)wv;
    if (!e || !e->parent) return;
    // The JAWT-provided HWND on Windows is the heavyweight Canvas peer,
    // NOT the JFrame's HWND.  The URL JTextField is a lightweight Swing
    // component drawn inside the JFrame's HWND area; for Win32 keystrokes
    // to reach AWT and be routed to the JTextField, we need focus on the
    // top-level window, not on the Canvas (which is a sibling of the
    // toolbar containing the JTextField).
    HWND target = GetAncestor(e->parent, GA_ROOT);
    if (!target) target = e->parent;
    DWORD edt_tid = GetCurrentThreadId();
    DWORD wv2_tid = e->thread_id;
    bool debug = getenv("WEBVIEW_DEBUG_SHORTCUT") != nullptr;
    if (wv2_tid != 0 && wv2_tid != edt_tid) {
        if (!AttachThreadInput(edt_tid, wv2_tid, TRUE)) {
            if (debug) {
                WV_LOG("[webview-focus] AttachThreadInput(edt=%lu,wv2=%lu) "
                       "failed: %lu",
                       (unsigned long)edt_tid, (unsigned long)wv2_tid,
                       (unsigned long)GetLastError());
            }
            return;
        }
        HWND prev = GetFocus();
        HWND now = SetFocus(target);
        AttachThreadInput(edt_tid, wv2_tid, FALSE);
        if (debug) {
            WV_LOG("[webview-focus] SetFocus(target=%p canvas=%p) prev=%p "
                   "after=%p now=%p edt=%lu wv2=%lu",
                   (void *)target, (void *)e->parent,
                   (void *)prev, (void *)now, (void *)GetFocus(),
                   (unsigned long)edt_tid, (unsigned long)wv2_tid);
        }
    } else {
        HWND prev = GetFocus();
        HWND now = SetFocus(target);
        if (debug) {
            WV_LOG("[webview-focus] same-thread SetFocus(target=%p) "
                   "prev=%p after=%p now=%p",
                   (void *)target, (void *)prev, (void *)now,
                   (void *)GetFocus());
        }
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1attach_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    auto *e = (Engine *)wv;
    if (!e || !cb) return;
    // Windows attach is synchronous: by the time the Java factory calls
    // this entry, embed_win::create_engine has already returned a
    // populated Engine pointer, so the engine is fully in ATTACHED
    // state.  Fire onResolved(true, null) immediately; the Java handler
    // (EmbeddedWebView.AttachCallback.onResolved) marshals via
    // SwingUtilities.invokeLater so the WebViewAttachListener actually
    // fires on the next EDT tick regardless of which thread invoked
    // this JNI entry.
    jclass cls = env->GetObjectClass(cb);
    if (!cls) return;
    jmethodID m = env->GetMethodID(cls, "onResolved",
                                   "(ZLjava/lang/String;)V");
    if (m) {
        env->CallVoidMethod(cb, m, (jboolean)JNI_TRUE, (jstring)nullptr);
    }
    env->DeleteLocalRef(cls);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1execute_1editing_1command
  (JNIEnv *, jclass, jlong wv, jint cmdId) {
    auto *e = (Engine *)wv;
    if (!e) return;
    // WebView2 exposes no first-class editing-command IPC; route via
    // document.execCommand on the WebView2 worker thread.  This reliably
    // triggers the focused element's clipboard handlers and matches the
    // semantics we get from the Cocoa / GTK sides.  Fire-and-forget --
    // no callback, no result wait.
    const wchar_t *js = nullptr;
    switch (cmdId) {
        case 1: js = L"document.execCommand('cut')";       break;
        case 2: js = L"document.execCommand('copy')";      break;
        case 3: js = L"document.execCommand('paste')";     break;
        case 4: js = L"document.execCommand('selectAll')"; break;
        default: return;
    }
    std::wstring wjs = js;
    embed_win::dispatch_to_thread(e, [e, wjs] {
        if (e->webview) e->webview->ExecuteScript(wjs.c_str(), nullptr);
    });
}

} // extern "C"
