/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Embedded-mode webview support for Swing/AWT (Linux GTK and macOS Cocoa).
 *
 * On Linux this reparents a top-level GTK window underneath the X11 window of
 * a JAWT-managed AWT Canvas using XReparentWindow.  The GTK main loop is
 * driven on a dedicated pump thread; all GTK and WebKitGTK operations are
 * marshaled onto that thread.
 *
 * On macOS this creates a WKWebView, sets its layer onto the JAWT
 * SurfaceLayers of the AWT Canvas, and lets AppKit's own run loop (which is
 * already pumping inside the JVM) deliver events.
 */

#include "ca_weblite_webview_WebViewNative.h"

#include <jawt.h>
#include <jawt_md.h>

#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <stdexcept>
#include <thread>
#include <utility>
#include <vector>

#ifdef WEBVIEW_GTK
#include <X11/Xlib.h>
#include <gtk/gtk.h>
#include <gdk/gdk.h>
#include <gdk/gdkx.h>
#include <glib/gstdio.h>
#include <webkit2/webkit2.h>
// Route WebKitGTK/JSC through the runtime-resolved loader (no hard SONAME dep).
#include "webkit_loader.h"
#include "webkit_shim.h"
#endif

#ifdef WEBVIEW_COCOA
#include <CoreGraphics/CoreGraphics.h>
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
#include <dispatch/dispatch.h>
#include <objc/objc-runtime.h>
#include <objc/runtime.h>
#endif

namespace embed {

// ---------------------------------------------------------------------------
// Diagnostic logging
// ---------------------------------------------------------------------------

// Embedding diagnostics are quiet by default.  Informational/success
// "[webview-embed] ..." traces are routed through EMBED_LOG and only reach
// stderr when the DEBUG_WEBVIEW_EMBED environment variable is set; a normal
// embed/launch therefore prints nothing.  Genuine error/failure conditions
// stay on a plain fprintf(stderr, ...) path so real problems are always
// visible.  This is the same switch that gates the per-frame draw#/frame-clock
// instrumentation below, so one flag controls all embedding diagnostics.
static bool embed_verbose() {
    static const bool verbose = (getenv("DEBUG_WEBVIEW_EMBED") != nullptr);
    return verbose;
}

#define EMBED_LOG(...) \
    do { if (embed_verbose()) fprintf(stderr, __VA_ARGS__); } while (0)

// ---------------------------------------------------------------------------
// JAWT helpers
// ---------------------------------------------------------------------------

// Explicitly resolve JAWT_GetAWT through dlopen/dlsym instead of relying on
// the static reference + -Wl,-undefined,dynamic_lookup chain.  In practice
// the dynamic lookup on macOS can resolve the symbol to something other than
// the real libjawt entry point (e.g. a stub installed by another framework),
// which manifests as JAWT_GetAWT silently returning JNI_FALSE for every
// version mask.  Going through dlsym anchors us to the JDK's libjawt.
using jawt_get_awt_fn = jboolean (*)(JNIEnv *, JAWT *);

static jawt_get_awt_fn resolve_jawt_get_awt() {
    static jawt_get_awt_fn cached = nullptr;
    static bool resolved = false;
    if (resolved) return cached;
    resolved = true;

    // First try the process-default scope.  If libjawt is already loaded
    // (e.g. via System.loadLibrary("jawt")) this finds the real entry point
    // without us having to know the JDK install layout.
    void *sym = dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
    if (sym != nullptr) {
        EMBED_LOG(
            "[webview-embed] Resolved JAWT_GetAWT via RTLD_DEFAULT at %p\n",
            sym);
        cached = reinterpret_cast<jawt_get_awt_fn>(sym);
        return cached;
    }
    EMBED_LOG(
        "[webview-embed] JAWT_GetAWT not visible in RTLD_DEFAULT; "
        "trying explicit dlopen of libjawt.\n");

    // Walk a few candidate paths.  Order: just the soname (uses dyld search
    // path), then $JAVA_HOME variants for the common JDK layouts (JDK 9+
    // uses $JAVA_HOME/lib; JDK 8 puts libjawt in jre/lib/<arch> on Linux
    // and jre/lib on macOS).
    const char *home = getenv("JAVA_HOME");
    std::vector<std::string> candidates;
    candidates.push_back("libjawt.dylib");
    candidates.push_back("libjawt.so");
    if (home != nullptr) {
        std::string h(home);
        candidates.push_back(h + "/jre/lib/libjawt.dylib");
        candidates.push_back(h + "/lib/libjawt.dylib");
        candidates.push_back(h + "/jre/lib/libjawt.so");
        candidates.push_back(h + "/lib/libjawt.so");
        candidates.push_back(h + "/jre/lib/amd64/libjawt.so");
        candidates.push_back(h + "/jre/lib/aarch64/libjawt.so");
        candidates.push_back(h + "/jre/lib/arm/libjawt.so");
        candidates.push_back(h + "/jre/lib/i386/libjawt.so");
    }
    void *handle = nullptr;
    for (const auto &path : candidates) {
        handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (handle != nullptr) {
            EMBED_LOG(
                "[webview-embed] dlopen'd libjawt from \"%s\"\n",
                path.c_str());
            break;
        }
    }
    if (handle == nullptr) {
        fprintf(stderr,
            "[webview-embed] dlopen libjawt failed for all candidates "
            "(last dlerror: %s).\n", dlerror());
        return nullptr;
    }
    sym = dlsym(handle, "JAWT_GetAWT");
    if (sym == nullptr) {
        fprintf(stderr,
            "[webview-embed] dlsym JAWT_GetAWT failed: %s\n", dlerror());
        return nullptr;
    }
    EMBED_LOG(
        "[webview-embed] Resolved JAWT_GetAWT via dlsym at %p\n", sym);
    cached = reinterpret_cast<jawt_get_awt_fn>(sym);
    return cached;
}

struct JawtLock {
    JAWT awt;
    JAWT_DrawingSurface *ds = nullptr;
    JAWT_DrawingSurfaceInfo *dsi = nullptr;
    jint lock = 0;
    bool ok = false;

    JawtLock(JNIEnv *env, jobject component) {
        jawt_get_awt_fn fn = resolve_jawt_get_awt();
        if (fn == nullptr) {
            fprintf(stderr,
                "[webview-embed] No JAWT_GetAWT symbol available; "
                "cannot attach embed peer.\n");
            return;
        }

        // Try the newest JAWT version mask we know about first, then fall
        // back through older masks.  On macOS the CALAYER flag has to be
        // OR'd in for the platformInfo to expose JAWT_SurfaceLayers.
        std::vector<jint> masks;
#if defined(WEBVIEW_COCOA) && defined(JAWT_MACOSX_USE_CALAYER)
#if defined(JAWT_VERSION_9)
        masks.push_back(JAWT_VERSION_9 | JAWT_MACOSX_USE_CALAYER);
#endif
        masks.push_back(JAWT_VERSION_1_7 | JAWT_MACOSX_USE_CALAYER);
        masks.push_back(JAWT_VERSION_1_4 | JAWT_MACOSX_USE_CALAYER);
#endif
#if defined(JAWT_VERSION_9)
        masks.push_back(JAWT_VERSION_9);
#endif
        masks.push_back(JAWT_VERSION_1_7);
        masks.push_back(JAWT_VERSION_1_4);

        bool got = false;
        for (jint m : masks) {
            awt.version = m;
            if (fn(env, &awt)) {
                EMBED_LOG(
                    "[webview-embed] JAWT_GetAWT succeeded with mask 0x%x\n",
                    (unsigned)m);
                got = true;
                break;
            }
        }
        if (!got) {
            fprintf(stderr,
                "[webview-embed] JAWT_GetAWT rejected every version mask "
                "we know about; the JDK does not appear to expose JAWT.\n");
            return;
        }

        ds = awt.GetDrawingSurface(env, component);
        if (ds == nullptr) {
            fprintf(stderr,
                "[webview-embed] JAWT GetDrawingSurface returned NULL "
                "(component is not displayable or not a heavyweight peer).\n");
            return;
        }
        lock = ds->Lock(ds);
        if (lock & JAWT_LOCK_ERROR) {
            fprintf(stderr,
                "[webview-embed] JAWT Lock returned JAWT_LOCK_ERROR (0x%x).\n",
                (unsigned)lock);
            awt.FreeDrawingSurface(ds);
            ds = nullptr;
            return;
        }
        dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi == nullptr) {
            fprintf(stderr,
                "[webview-embed] JAWT GetDrawingSurfaceInfo returned NULL.\n");
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

// ---------------------------------------------------------------------------
// Cross-platform engine handle
// ---------------------------------------------------------------------------

struct Engine;

// Java callback bridge.  Owned by the engine; ref-counted in JNI globals.
struct Binding {
    jobject fn;             // global ref to WebViewNativeCallback
    jclass cls;             // global ref to its class
    std::string name;
};

using DispatchFn = std::function<void()>;

// One-shot JNI completion for cookie queries. The native browser APIs are
// asynchronous on every platform, so the callback is held as a global ref
// until exactly one success/error result has been delivered.
struct CookieCompletion {
    JavaVM *jvm = nullptr;
    jobject callback = nullptr;
};

static CookieCompletion *new_cookie_completion(JNIEnv *env, jobject callback) {
    if (!env || !callback) return nullptr;
    CookieCompletion *c = new CookieCompletion();
    env->GetJavaVM(&c->jvm);
    c->callback = env->NewGlobalRef(callback);
    if (!c->jvm || !c->callback) {
        if (c->callback) env->DeleteGlobalRef(c->callback);
        delete c;
        return nullptr;
    }
    return c;
}

static void complete_cookie_query(CookieCompletion *c,
                                  const std::string &header,
                                  const char *error) {
    if (!c) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (c->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (c->jvm->AttachCurrentThread((void **)&env, nullptr) == JNI_OK) {
            detach = true;
        }
    }
    if (env) {
        jclass cls = env->GetObjectClass(c->callback);
        jmethodID method = cls ? env->GetMethodID(
            cls, "completed", "(Ljava/lang/String;Ljava/lang/String;)V")
            : nullptr;
        jstring jheader = env->NewStringUTF(header.c_str());
        jstring jerror = error ? env->NewStringUTF(error) : nullptr;
        if (method) env->CallVoidMethod(c->callback, method, jheader, jerror);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (jerror) env->DeleteLocalRef(jerror);
        if (jheader) env->DeleteLocalRef(jheader);
        if (cls) env->DeleteLocalRef(cls);
        env->DeleteGlobalRef(c->callback);
    }
    if (detach) c->jvm->DetachCurrentThread();
    delete c;
}

// ---------------------------------------------------------------------------
// Canvas 29: print-to-PDF request.  One per print; holds a global ref to the
// Java WebViewPdfCallback.  pdf_finish upcalls onPdfFinished exactly once
// (Canvas 29 D1), deletes the ref and the job.  Callable from any thread.
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

// ---------------------------------------------------------------------------
// Canvas 30: custom URL schemes -- the state every engine shares (D14).
// Installed once by webview_scheme_install, before the first engine exists:
// the lower-case scheme names, the Java SchemeDispatcher (a global ref) and
// the JVM.  Requests get monotonic ids; each engine keeps its own table of
// pending platform objects (Cocoa: the WKURLSchemeTask) under the one lock.
// ---------------------------------------------------------------------------
static std::vector<std::string> g_scheme_names;
static jobject g_scheme_dispatcher = nullptr;
static JavaVM *g_scheme_jvm = nullptr;
static std::atomic<long long> g_scheme_next_id(0);
static std::mutex g_scheme_mutex;
static bool g_scheme_installed = false;

// JNI env for the current thread, attaching when needed; *attached says so.
static JNIEnv *scheme_env(bool *attached) {
    *attached = false;
    if (!g_scheme_jvm) return nullptr;
    JNIEnv *env = nullptr;
    if (g_scheme_jvm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_scheme_jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) return nullptr;
    *attached = true;
    return env;
}

// Upcall SchemeDispatcher.onSchemeRequest (D15).  Call from a non-UI thread
// on macOS; on GTK from the pump thread, since it only enqueues (Canvas 31 D6).
static void scheme_upcall_request(long long id, const std::string &method,
                                  const std::string &url,
                                  const std::vector<std::string> &headerPairs,
                                  const std::vector<unsigned char> &body,
                                  bool bodyAvailable) {
    if (!g_scheme_dispatcher) return;
    bool attached = false;
    JNIEnv *env = scheme_env(&attached);
    if (!env) return;
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

// Upcall SchemeDispatcher.onSchemeCancelled (D15).  Call from a non-UI thread.
__attribute__((unused))
static void scheme_upcall_cancelled(long long id) {
    if (!g_scheme_dispatcher) return;
    bool attached = false;
    JNIEnv *env = scheme_env(&attached);
    if (!env) return;
    jclass cls = env->GetObjectClass(g_scheme_dispatcher);
    jmethodID m = cls ? env->GetMethodID(cls, "onSchemeCancelled", "(J)V") : nullptr;
    if (m) env->CallVoidMethod(g_scheme_dispatcher, m, (jlong)id);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (cls) env->DeleteLocalRef(cls);
    if (attached) g_scheme_jvm->DetachCurrentThread();
}

// The largest request body read from the engine: the dispatcher's cap plus
// one byte, so an over-cap body is still recognised and answered 413.
static const size_t kSchemeMaxRequestRead = 16u * 1024u * 1024u + 1u;

#ifdef WEBVIEW_GTK
// =========================================================================
// Linux / GTK / X11
// =========================================================================

class GtkPump {
public:
    static GtkPump &instance() {
        static GtkPump p;
        return p;
    }

    // Run f synchronously on the GTK thread, blocking until it completes.
    template <typename F> void run_sync(F &&f) {
        if (g_main_context_is_owner(g_main_context_default())) {
            f();
            return;
        }
        ensure_started();
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
        auto holder = new std::function<void()>([&]() {
            f();
            {
                std::lock_guard<std::mutex> lk(m);
                done = true;
            }
            cv.notify_all();
        });
        g_idle_add_full(G_PRIORITY_HIGH_IDLE,
                        (GSourceFunc)+[](void *data) -> int {
                          auto *cb = static_cast<std::function<void()> *>(data);
                          (*cb)();
                          return G_SOURCE_REMOVE;
                        },
                        holder,
                        +[](void *data) {
                          delete static_cast<std::function<void()> *>(data);
                        });
        std::unique_lock<std::mutex> lk(m);
        cv.wait(lk, [&] { return done; });
    }

    // Run f asynchronously on the GTK thread.
    void run_async(DispatchFn f) {
        ensure_started();
        auto holder = new DispatchFn(std::move(f));
        g_idle_add_full(G_PRIORITY_HIGH_IDLE,
                        (GSourceFunc)+[](void *data) -> int {
                          auto *cb = static_cast<DispatchFn *>(data);
                          (*cb)();
                          return G_SOURCE_REMOVE;
                        },
                        holder,
                        +[](void *data) {
                          delete static_cast<DispatchFn *>(data);
                        });
    }

private:
    GtkPump() = default;

    std::mutex start_mutex;
    std::atomic<bool> started{false};
    std::thread thread;
    bool initialized = false;

    void ensure_started() {
        if (started.load()) {
            if (!initialized) throw std::runtime_error("GTK could not open the native display");
            return;
        }
        std::lock_guard<std::mutex> lk(start_mutex);
        if (started.load()) {
            if (!initialized) throw std::runtime_error("GTK could not open the native display");
            return;
        }
        std::mutex ready_m;
        std::condition_variable ready_cv;
        bool ready = false;
        thread = std::thread([&] {
            // X11 must be told we're going to use it from multiple threads.
            XInitThreads();
            // Embedding via XReparentWindow requires a real X11 GdkDisplay
            // on both sides.  Force the X11 backend in case GTK would
            // otherwise pick Wayland or some other backend (e.g. in a
            // Parallels / virtual desktop session that exposes both).
            gdk_set_allowed_backends("x11");
            int argc = 0;
            char **argv = nullptr;
            initialized = gtk_init_check(&argc, &argv);
            // Issue #64: WebKit keeps the default web context in a static
            // reference released by libc exit handlers on the JVM's exit
            // thread, not this one.  If that is the last reference, the
            // context is finalized off WebKit's main thread and the process
            // aborts.  Hold one extra reference for the life of the process
            // and never release it, so exit only ever decrements the count.
            if (initialized) g_object_ref(webkit_web_context_get_default());
            {
                std::lock_guard<std::mutex> lk2(ready_m);
                ready = true;
            }
            ready_cv.notify_all();
            if (initialized) gtk_main();
        });
        thread.detach();
        std::unique_lock<std::mutex> lk2(ready_m);
        ready_cv.wait(lk2, [&] { return ready; });
        started.store(true);
        if (!initialized) throw std::runtime_error("GTK could not open the native display");
    }
};

// ---------------------------------------------------------------------------
// Canvas 31: custom URL schemes on WebKitGTK.  Every view the library creates
// shares WebKit's default web context (D1), so each scheme is registered there
// once, on the pump thread, before the first view (D2).  WebKit calls
// gtk_scheme_request_cb on the pump thread; the request is kept (ref'd) by id
// until Java answers, and the answer is applied back on the pump thread (D7).
// WebKitGTK does not report abandoned requests, so Linux never upcalls a
// cancellation; an unanswered request ends at the dispatcher's 504 (D8).
// ---------------------------------------------------------------------------
static std::map<long long, WebKitURISchemeRequest *> g_scheme_requests;

#if WEBKIT_CHECK_VERSION(2, 36, 0)
static void gtk_scheme_header_pair(const char *name, const char *value, gpointer data) {
    auto *pairs = static_cast<std::vector<std::string> *>(data);
    pairs->push_back(name ? name : "");
    pairs->push_back(value ? value : "");
}
#endif

// D6.  Pump thread.
static void gtk_scheme_request_cb(WebKitURISchemeRequest *request, gpointer) {
    long long id = ++g_scheme_next_id;
    g_object_ref(request);
    {
        std::lock_guard<std::mutex> lk(g_scheme_mutex);
        g_scheme_requests[id] = request;
    }
    const gchar *uri = webkit_uri_scheme_request_get_uri(request);
    std::string url = uri ? uri : "";
    std::string method = "GET";
#if WEBKIT_CHECK_VERSION(2, 12, 0)
    if (WK_HAS(webkit_uri_scheme_request_get_http_method)) {
        const gchar *m = webkit_uri_scheme_request_get_http_method(request);
        if (m && *m) method = m;
    }
#endif
    std::vector<std::string> pairs;
#if WEBKIT_CHECK_VERSION(2, 36, 0)
    if (WK_HAS(webkit_uri_scheme_request_get_http_headers) &&
        WK_HAS(soup_message_headers_foreach)) {
        SoupMessageHeaders *h = webkit_uri_scheme_request_get_http_headers(request);
        if (h) soup_message_headers_foreach(h, gtk_scheme_header_pair, &pairs);
    }
#endif
    std::vector<unsigned char> body;
    bool bodyAvailable = false;
#if WEBKIT_CHECK_VERSION(2, 40, 0)
    if (WK_HAS(webkit_uri_scheme_request_get_http_body)) {
        bodyAvailable = true;
        GInputStream *in = webkit_uri_scheme_request_get_http_body(request);
        if (in) {
            unsigned char buf[16384];
            while (body.size() < kSchemeMaxRequestRead) {
                gssize got = g_input_stream_read(in, buf, sizeof(buf), nullptr, nullptr);
                if (got <= 0) break;
                body.insert(body.end(), buf, buf + got);
            }
            if (body.size() > kSchemeMaxRequestRead) body.resize(kSchemeMaxRequestRead);
            g_object_unref(in);
        }
    }
#endif
    // The dispatcher only builds the request and submits the handler, so the
    // pump thread never waits on application code (Canvas 30 D5).
    scheme_upcall_request(id, method, url, pairs, body, bodyAvailable);
}

// D2, D3.  Pump thread only; runs once, and not at all when nothing is
// registered (Canvas 30 D2).
static void gtk_install_schemes_once() {
    static bool done = false;
    if (done || g_scheme_names.empty()) return;
    done = true;
    WebKitWebContext *ctx = webkit_web_context_get_default();
    WebKitSecurityManager *sm = webkit_web_context_get_security_manager(ctx);
    for (const std::string &name : g_scheme_names) {
        webkit_web_context_register_uri_scheme(ctx, name.c_str(), gtk_scheme_request_cb,
                                               nullptr, nullptr);
        webkit_security_manager_register_uri_scheme_as_secure(sm, name.c_str());
        webkit_security_manager_register_uri_scheme_as_cors_enabled(sm, name.c_str());
    }
}

// D7.  Any thread; the answer is applied on the pump thread, and only if the
// request is still pending.
static void gtk_scheme_respond(long long id, int status, std::vector<std::string> pairs,
                               std::vector<unsigned char> body) {
    GtkPump::instance().run_async([id, status, pairs, body]() {
        WebKitURISchemeRequest *req = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_scheme_mutex);
            auto it = g_scheme_requests.find(id);
            if (it == g_scheme_requests.end()) return;
            req = it->second;
            g_scheme_requests.erase(it);
        }
        std::string contentType;
        for (size_t i = 0; i + 1 < pairs.size(); i += 2) {
            if (g_ascii_strcasecmp(pairs[i].c_str(), "Content-Type") == 0) {
                contentType = pairs[i + 1];
                break;
            }
        }
        GBytes *bytes = g_bytes_new(body.empty() ? nullptr : body.data(), body.size());
        GInputStream *stream = g_memory_input_stream_new_from_bytes(bytes);
        g_bytes_unref(bytes);
        bool done = false;
#if WEBKIT_CHECK_VERSION(2, 36, 0)
        if (WK_HAS(webkit_uri_scheme_response_new) &&
            WK_HAS(webkit_uri_scheme_response_set_status) &&
            WK_HAS(webkit_uri_scheme_response_set_content_type) &&
            WK_HAS(webkit_uri_scheme_response_set_http_headers) &&
            WK_HAS(webkit_uri_scheme_request_finish_with_response) &&
            WK_HAS(soup_message_headers_new) && WK_HAS(soup_message_headers_append)) {
            WebKitURISchemeResponse *resp =
                webkit_uri_scheme_response_new(stream, (gint64)body.size());
            webkit_uri_scheme_response_set_status(resp, (guint)status, nullptr);
            if (!contentType.empty()) {
                webkit_uri_scheme_response_set_content_type(resp, contentType.c_str());
            }
            SoupMessageHeaders *hdrs = soup_message_headers_new(SOUP_MESSAGE_HEADERS_RESPONSE);
            for (size_t i = 0; i + 1 < pairs.size(); i += 2) {
                if (g_ascii_strcasecmp(pairs[i].c_str(), "Content-Type") == 0) continue;
                soup_message_headers_append(hdrs, pairs[i].c_str(), pairs[i + 1].c_str());
            }
            webkit_uri_scheme_response_set_http_headers(resp, hdrs);  // takes ownership
            webkit_uri_scheme_request_finish_with_response(req, resp);
            g_object_unref(resp);
            done = true;
        }
#endif
        if (!done) {
            // WebKitGTK < 2.36: only a content type can be sent, so a 2xx keeps
            // its body and type and anything else becomes a load error.
            if (status >= 200 && status <= 299) {
                webkit_uri_scheme_request_finish(req, stream, (gint64)body.size(),
                                                 contentType.empty() ? nullptr : contentType.c_str());
            } else {
                GError *err = g_error_new(g_quark_from_static_string("webview-scheme"), status,
                                          "HTTP %d", status);
                webkit_uri_scheme_request_finish_error(req, err);
                g_error_free(err);
            }
        }
        g_object_unref(stream);
        g_object_unref(req);
    });
}

struct Engine {
    Window parent_xid = 0;
    Display *parent_display = nullptr;

    GtkWidget *window = nullptr;
    GtkWidget *web = nullptr;        // WebKitWebView*
    WebKitUserContentManager *manager = nullptr;

    // The X11 GdkFrameClock paces itself against _NET_WM_FRAME_DRAWN
    // ClientMessages from the window manager.  Our reparented popup
    // has no WM relationship (the WM only manages the AWT top-level
    // frame), so the clock waits forever for vsync pulses that never
    // come.  gdk_frame_clock_begin_updating is supposed to drive it
    // anyway but doesn't in practice on this code path -- experiment
    // confirms only a handful of phase events across the entire
    // session.  Instead, drive paints from a plain g_timeout at
    // ~60Hz.  On a healthy GTK stack this is a redundant tick that
    // WebKit's internal damage tracking optimizes away; on the
    // virtualized X stacks where the clock stalls, it provides the
    // working paint cycle that's otherwise missing.
    guint redraw_timer_id = 0;

    bool debug = false;

    // Bindings: name -> Binding*
    std::map<std::string, Binding *> bindings;

    JavaVM *jvm = nullptr;

    // JNI global ref to the registered WebViewClickCallback, or nullptr.
    // Invoked from the gtk_gesture_multi_press "pressed" handler each
    // time the user presses a mouse button anywhere on the embedded
    // WebKitWebView -- see Operation 13 of the heavyweight-embedding
    // Canvas.  Cleared in gtk_destroy_engine BEFORE the widget is
    // destroyed so a late press cannot fire into a freed ref.
    jobject click_callback = nullptr;

    // JNI global ref to the registered WebViewDialogCallback, or nullptr.
    // Storage only in this canvas (STORY-004-001) -- the signal handler
    // wiring that actually invokes the callback lands in STORY-004-002
    // (script-dialog + run-file-chooser on WebKitWebView).  The field
    // is declared here so the JNI bridge function can store / clear
    // the global ref without dropping the canvas-004-002 changes.
    jobject dialog_callback = nullptr;

    // JNI global ref to the registered WebViewDownloadCallback, or
    // nullptr.  Populated by gtk_set_download_callback (which also
    // installs the view's DownloadSink and connects `download-started`
    // on the shared WebKitWebContext); cleared in gtk_destroy_engine
    // BEFORE the widget is destroyed, because a download can outlive
    // the page and the navigation that started it -- Canvas 24.
    jobject download_callback = nullptr;

    // JNI global ref to the registered WebViewPopupCallback, or nullptr.
    // Populated by gtk_set_popup_callback; cleared in gtk_destroy_engine.
    // The `create` signal handler wired in gtk_create_engine invokes it
    // (onPopupRequested / onPopupOpened / onPopupClosed) so window.open /
    // target=_blank open a native GTK popup window linked to the opener --
    // Canvas 16.  A child (popup) web view inherits this ref via its own
    // PopupEngine so nested popups work.
    jobject popup_callback = nullptr;
    // Canvas 34 D7: JNI global ref to the WebViewNavigationCallback, or
    // nullptr.  Set by gtk_set_navigation_callback; read by the
    // "decide-policy" handler on every navigation; freed in
    // gtk_destroy_engine.
    jobject navigation_callback = nullptr;
    // Canvas 21 (1.5.0): per-destination User-Agent resolver
    // (java.util.function.Function<String,String>) as a JNI global ref.
    // Consulted at the popup-child creation site with the CHILD's target
    // URL; a decline falls back to copying the opener's UA.  Deleted on
    // replacement and on engine destroy.
    jobject ua_resolver = nullptr;

    // JNI global ref to the registered WebViewPasswordCallback, or
    // nullptr.  Set by cocoa_set_password_callback / gtk_set_password_callback
    // and cleared on engine destroy.  Invoked by the __webview_pw__
    // script-message handler (login submission / autofill request) that
    // the injected PasswordDispatcher.SHIM_JS posts to (Canvas 26 macOS;
    // Canvas 27 Linux).
    jobject password_callback = nullptr;

    Engine() {}
    ~Engine() {}
};

// Invoke the Java WebViewClickCallback registered on the engine, if any.
// Called from the GTK main thread; the Java callback is responsible for
// marshalling to the EDT before touching Swing state.  Mirrors the cocoa
// branch's fire_focus_callback shape but with no boolean payload because
// WebViewClickCallback.invoke is a no-arg method.
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
// JS-initiated UI dialog bridge for WebKitGTK.
//
// Connects to the `script-dialog` and `run-file-chooser` signals on each
// WebKitWebView (both heavyweight gtk_create_engine and lightweight
// gtk_off_create_engine).  Returning TRUE from each handler suppresses the
// default GTK dialog; the application drives the response by invoking
// WebViewDialogCallback methods on the per-engine dialog_callback global ref
// and feeding the answer back via webkit_script_dialog_*_set_* /
// webkit_file_chooser_request_select_files / _cancel.
//
// All four `fire_dialog_*` helpers mirror fire_click_callback's shape:
// defensive AttachCurrentThread + detach-only-if-we-attached, resolve method
// id per call (no caching), ExceptionCheck/Clear after every Call*Method.
// Strings returned from fire_dialog_prompt are g_strdup'd (caller g_free's);
// arrays returned from fire_dialog_file_picker are g_new0+g_strdup'd (caller
// g_strfreev's).  WebKitGTK copies both internally per its docs.
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
                    result = g_strdup(cstr);
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
    return result;  // caller owns; free with g_free
}

// Build a jobjectArray of UTF-8 jstrings from a NULL-terminated
// const gchar*const* (may be NULL).  Returns a freshly-allocated
// local ref; the caller is responsible for DeleteLocalRef on the
// outer array.  Element local refs are released inside this helper.
static jobjectArray nullterm_array_to_jstring_array(
        JNIEnv *env, jclass stringCls, const gchar *const *arr) {
    jsize n = 0;
    if (arr) {
        while (arr[n] != nullptr) n++;
    }
    jobjectArray ja = env->NewObjectArray(n, stringCls, nullptr);
    if (!ja) return nullptr;
    for (jsize i = 0; i < n; i++) {
        jstring js = env->NewStringUTF(arr[i]);
        if (js) {
            env->SetObjectArrayElement(ja, i, js);
            env->DeleteLocalRef(js);
        }
    }
    return ja;
}

static gchar **fire_dialog_file_picker(JavaVM *jvm, jobject callback,
                                       gboolean multiple,
                                       const gchar *const *mimeTypes,
                                       const gchar *const *extensions,
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
    gchar **result = nullptr;
    jclass stringCls = env->FindClass("java/lang/String");
    if (!stringCls) {
        if (detach) jvm->DetachCurrentThread();
        return nullptr;
    }
    jobjectArray jmimes = nullterm_array_to_jstring_array(
        env, stringCls, mimeTypes);
    jobjectArray jexts = nullterm_array_to_jstring_array(
        env, stringCls, extensions);
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onFilePicker",
            "(Z[Ljava/lang/String;[Ljava/lang/String;"
            "Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;");
        if (m) {
            jobjectArray jresult = (jobjectArray)env->CallObjectMethod(
                callback, m,
                (jboolean)(multiple ? JNI_TRUE : JNI_FALSE),
                jmimes, jexts, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                jresult = nullptr;
            }
            if (jresult) {
                jsize len = env->GetArrayLength(jresult);
                if (len > 0) {
                    result = g_new0(gchar *, (gsize)len + 1);
                    gsize idx = 0;
                    for (jsize i = 0; i < len; i++) {
                        jstring js = (jstring)env->GetObjectArrayElement(
                            jresult, i);
                        if (!js) continue;
                        const char *cstr = env->GetStringUTFChars(js, nullptr);
                        if (cstr) {
                            result[idx++] = g_strdup(cstr);
                            env->ReleaseStringUTFChars(js, cstr);
                        }
                        env->DeleteLocalRef(js);
                    }
                    if (idx == 0) {
                        // Defensive: every entry was null / unreadable.
                        g_strfreev(result);
                        result = nullptr;
                    }
                }
                env->DeleteLocalRef(jresult);
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmimes) env->DeleteLocalRef(jmimes);
    if (jexts) env->DeleteLocalRef(jexts);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    env->DeleteLocalRef(stringCls);
    if (detach) jvm->DetachCurrentThread();
    return result;  // caller owns; free with g_strfreev
}

// Shared inner dispatcher for the `script-dialog` signal.  Engine-agnostic;
// the per-engine wrappers (on_script_dialog_engine /
// on_script_dialog_off_engine) pull jvm + dialog_callback + page URL out
// of their respective Engine / OffEngine struct and call into this
// function.  Always returns TRUE — the GTK default dialog is permanently
// suppressed for any WebViewComponent-managed engine.  Even on the
// null-callback / JNI-attach-failure path, every branch resolves the
// dialog (via the engine-specific set_* APIs or by not calling them) so
// the page's JS thread always resumes within bounded time.
static gboolean handle_script_dialog(JavaVM *jvm, jobject dialog_callback,
                                     const gchar *page_url,
                                     WebKitScriptDialog *dialog) {
    if (!dialog) return TRUE;
    WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
    const gchar *message = webkit_script_dialog_get_message(dialog);
    const char *frame_url = page_url ? page_url : "";
    switch (type) {
        case WEBKIT_SCRIPT_DIALOG_ALERT:
            if (dialog_callback) {
                fire_dialog_alert(jvm, dialog_callback,
                                  message, page_url, frame_url);
            }
            // No set_* call needed for alert.
            break;
        case WEBKIT_SCRIPT_DIALOG_CONFIRM:
        case WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM: {
            jboolean ok = JNI_FALSE;
            if (dialog_callback) {
                ok = fire_dialog_confirm(jvm, dialog_callback,
                                         message, page_url, frame_url);
            }
            webkit_script_dialog_confirm_set_confirmed(
                dialog, ok == JNI_TRUE);
            break;
        }
        case WEBKIT_SCRIPT_DIALOG_PROMPT: {
            const gchar *def =
                webkit_script_dialog_prompt_get_default_text(dialog);
            char *answer = nullptr;
            if (dialog_callback) {
                answer = fire_dialog_prompt(jvm, dialog_callback,
                                            message, def ? def : "",
                                            page_url, frame_url);
            }
            if (answer) {
                webkit_script_dialog_prompt_set_text(dialog, answer);
                g_free(answer);
            }
            // If answer is null: do NOT call _set_text.  WebKitGTK treats
            // the absence of a _set_text call as "user cancelled" and the
            // page sees prompt() return null — exactly the JS-spec cancel
            // semantic.
            break;
        }
        default:
            // Unknown dialog kind: suppress the GTK default but don't
            // forward to Java.  Conservative — future WebKitGTK versions
            // may add new dialog types we haven't taught the handler
            // about yet.
            break;
    }
    return TRUE;
}

// Shared inner dispatcher for the `run-file-chooser` signal.  Same
// engine-agnostic shape as handle_script_dialog.  Always returns TRUE so
// the GTK default file picker never fires; always resolves the request
// via either _select_files (accept) or _cancel (cancel / null callback /
// empty result) so the page's JS `change` event fires within bounded
// time.
static gboolean handle_run_file_chooser(JavaVM *jvm, jobject dialog_callback,
                                        const gchar *page_url,
                                        WebKitFileChooserRequest *request) {
    if (!request) return TRUE;
    if (!dialog_callback) {
        webkit_file_chooser_request_cancel(request);
        return TRUE;
    }
    gboolean multiple =
        webkit_file_chooser_request_get_select_multiple(request);
    const gchar *const *mime_types =
        webkit_file_chooser_request_get_mime_types(request);
    const char *frame_url = page_url ? page_url : "";

    // WebKitGTK's file-chooser API does not expose the original `.ext`
    // strings the page wrote (only the opaque GtkFileFilter via
    // _get_mime_types_filter), so we pass an empty extensions array.
    // The Java-side DialogDispatcher.normaliseExtensions handles the
    // empty case correctly; the default JFileChooser falls through to
    // "show all files".  Documented Linux limitation per the canvas.
    gchar **paths = fire_dialog_file_picker(
        jvm, dialog_callback, multiple, mime_types,
        /* extensions */ nullptr, page_url, frame_url);

    if (!paths) {
        webkit_file_chooser_request_cancel(request);
    } else {
        webkit_file_chooser_request_select_files(
            request, (const gchar *const *)paths);
        // WebKitGTK copies the paths internally per its docs, so free
        // our array immediately.
        g_strfreev(paths);
    }
    return TRUE;
}

// Per-Engine wrapper for the `script-dialog` signal.  Reads page URL,
// jvm, and dialog_callback from the heavyweight Engine struct and
// delegates to handle_script_dialog.  The OffEngine counterpart is
// defined alongside the OffEngine struct further down so its forward
// dependency is satisfied.
static gboolean on_script_dialog_engine(WebKitWebView *web,
                                        WebKitScriptDialog *dialog,
                                        gpointer user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_script_dialog(e->jvm, e->dialog_callback, uri, dialog);
}

static gboolean on_run_file_chooser_engine(WebKitWebView *web,
                                           WebKitFileChooserRequest *request,
                                           gpointer user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_run_file_chooser(e->jvm, e->dialog_callback, uri, request);
}

// ---------------------------------------------------------------------------
// Popup (window.open) support — Canvas 16 (Linux / WebKitGTK).
//
// window.open / target=_blank is delivered to a WebKitWebView through the
// `create` signal, whose handler must RETURN a new (linked) WebKitWebView for
// WebKit to drive, or NULL to block.  The child is created with
// webkit_web_view_new_with_related_view(opener) so it is LINKED to the opener
// (window.opener / postMessage work — required for OAuth signInWithPopup) and
// hosted in an engine-owned GTK_WINDOW_TOPLEVEL.  ready-to-show sizes/shows the
// window and fires onPopupOpened; close destroys it and fires onPopupClosed.
//
// The allow/deny hop (onPopupRequested) is SYNCHRONOUS on the GTK main thread
// (the create handler must return the child before yielding to WebKit), exactly
// like the script-dialog confirm path.  onPopupOpened / onPopupClosed are
// fire-and-forget; the Java PopupDispatcher marshals them to the EDT via
// invokeLater (non-blocking), so the GTK main thread is never blocked — no
// detached worker thread is needed (unlike the macOS AppKit-main path).
// ---------------------------------------------------------------------------

// A child popup's native state: a standalone on-screen GTK toplevel hosting a
// WebKit-related child web view.  Distinct from Engine / OffEngine — no AWT
// reparenting, no offscreen blitting.  The inherited popup_callback /
// Per-view record mapping a WebKitWebView back to the Java dispatcher
// that owns it.  Attached to the view with g_object_set_data_full so its
// lifetime is the view's -- a global registry would have to be kept in
// step with view destruction by hand, and getting that wrong is the
// use-after-free this design avoids.
struct DownloadSink {
    JavaVM *jvm = nullptr;
    jobject download_callback = nullptr;   // global ref, may be null
    long long next_id = 1;
};

// One in-flight WebKitDownload.  Attached to the download object with
// g_object_set_data_full so it dies with the transfer.
struct GtkDownloadCtx {
    DownloadSink *sink;
    long long id;
    guint64 received;
    gint64 total;
    gboolean terminal;
};

static const char *DOWNLOAD_SINK_KEY = "weblite-download-sink";
static const char *DOWNLOAD_CTX_KEY = "weblite-download-ctx";
static const char *DOWNLOAD_CONNECTED_KEY = "weblite-download-connected";

// Defined further down, next to the rest of the download machinery; declared
// here because gtk_destroy_engine and handle_create_web_view both sit above
// that block and need them.
static void free_download_sink(gpointer p);
static void gtk_install_download_sink(GtkWidget *web, JavaVM *jvm,
                                      JNIEnv *env, jobject cb);
static void gtk_clear_download_sink(GtkWidget *web, JNIEnv *env);

// dialog_callback global refs are freed in on_close_popup.
struct PopupEngine {
    GtkWidget *window = nullptr;        // GTK_WINDOW_TOPLEVEL
    WebKitWebView *web = nullptr;       // the related child web view
    JavaVM *jvm = nullptr;
    jobject popup_callback = nullptr;   // inherited global ref
    // Canvas 21 (1.5.0): inherited global ref, so a nested popup resolves
    // its own child's UA the same way its opener did.
    jobject ua_resolver = nullptr;
    jobject dialog_callback = nullptr;  // inherited global ref, may be null
    jobject download_callback = nullptr; // inherited global ref, may be null
    jlong popup_id = 0;
};

// Canvas 19 (popup adoption — Linux/WebKitGTK coverage of the Canvas 18 Java
// contract): retained-but-unadopted popup child engines, keyed by popup_id.
// Populated by handle_create_web_view's ADOPT branch (which creates the
// opener-linked WebKit child but NO GtkWindow and holds an explicit strong
// reference on the widget), drained by gtk_adopt_popup (reparent into a
// caller-supplied WebViewComponent's realized X11 surface) or gtk_discard_popup
// (reclaim an unadopted child).  Guarded by its own mutex; the GTK counterpart
// of the macOS g_retained_popups map.  ON-DEVICE VALIDATION REQUIRED: this file
// has no GTK toolchain in the generating sandbox — the reparent + widget
// ownership handoff below must be exercised on a real WebKitGTK/X11 stack
// before release (see Canvas 19 Safeguards).
static std::mutex g_gtk_retained_popups_mutex;
static std::map<jlong, PopupEngine *> g_gtk_retained_popups;

// Synchronous allow/deny hop into Java on the GTK main thread.  Returns false
// on null callback, attach failure, or any exception (block-on-error default).
static bool fire_popup_requested(JavaVM *jvm, jobject cb,
                                 const char *url, const char *name,
                                 bool gesture, int w, int h,
                                 const char *page) {
    if (!jvm || !cb) return false;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return false;
        detach = true;
    }
    bool allow = false;
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF(name ? name : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(cb);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupRequested",
            "(Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)Z");
        if (mid) {
            jboolean r = env->CallBooleanMethod(cb, mid, ju, jn,
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

// Canvas 34 D7: the navigation decision, shared by Engine and OffEngine.
// Asks the WebViewNavigationCallback (synchronously, on the GTK main thread,
// before WebKit sends any request) about a NAVIGATION_ACTION decision of the
// view or one of its frames -- WebKitGTK reports frame navigations exactly
// like the view's own.  Returns TRUE after ignoring a refused navigation;
// FALSE lets WebKit decide as it would without a handler.  New windows
// (NEW_WINDOW_ACTION) belong to the popup path, and responses to the download
// path, so both are left alone.  Every failure refuses.
enum { NAV_LINK = 0, NAV_FORM = 1, NAV_BACK_FORWARD = 2, NAV_RELOAD = 3,
       NAV_REDIRECT = 4, NAV_OTHER = 5 };

static gboolean gtk_decide_navigation(JavaVM *jvm, jobject cb,
                                      WebKitWebView *view,
                                      WebKitPolicyDecision *decision,
                                      WebKitPolicyDecisionType type) {
    if (type != WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION) return FALSE;
    if (!jvm || !cb || !decision) return FALSE;
    // Plain C casts: the WEBKIT_* GObject cast macros would reference
    // *_get_type symbols the runtime loader does not resolve.
    WebKitNavigationAction *action =
        webkit_navigation_policy_decision_get_navigation_action(
            (WebKitNavigationPolicyDecision *)decision);
    const char *url = "";
    int cause = NAV_OTHER;
    if (action) {
        WebKitURIRequest *req = webkit_navigation_action_get_request(action);
        const char *u = req ? webkit_uri_request_get_uri(req) : nullptr;
        if (u) url = u;
        if (webkit_navigation_action_is_redirect(action)) {
            cause = NAV_REDIRECT;
        } else {
            switch (webkit_navigation_action_get_navigation_type(action)) {
                case WEBKIT_NAVIGATION_TYPE_LINK_CLICKED: cause = NAV_LINK; break;
                case WEBKIT_NAVIGATION_TYPE_FORM_SUBMITTED:
                case WEBKIT_NAVIGATION_TYPE_FORM_RESUBMITTED: cause = NAV_FORM; break;
                case WEBKIT_NAVIGATION_TYPE_BACK_FORWARD: cause = NAV_BACK_FORWARD; break;
                case WEBKIT_NAVIGATION_TYPE_RELOAD: cause = NAV_RELOAD; break;
                default: cause = NAV_OTHER; break;
            }
        }
    }
    const char *current = view ? webkit_web_view_get_uri(view) : nullptr;

    bool allowed = false;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) env = nullptr;
        else detach = true;
    }
    if (env) {
        jstring ju = env->NewStringUTF(url);
        jstring jc = env->NewStringUTF(current ? current : "");
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
    if (allowed) return FALSE;
    webkit_policy_decision_ignore(decision);
    return TRUE;
}

// Canvas 19: synchronous DISPOSITION hop into Java on the GTK main thread.
// Calls WebViewPopupCallback.onPopupDisposition and returns the
// PopupDisposition ordinal (0 = BLOCK, 1 = NATIVE_WINDOW, 2 = ADOPT).  Mirrors
// the macOS fire_popup_disposition and this file's fire_popup_requested; a null
// callback / attach failure / thrown decision all yield BLOCK (0).  Runs
// synchronously on the GTK main thread (the `create` handler must return the
// child before yielding to WebKit) and MUST NOT be marshalled to the EDT.  The
// default WebViewPopupCallback.onPopupDisposition derives from onPopupRequested,
// so legacy Canvas 16 boolean handlers still map to BLOCK / NATIVE_WINDOW.
static int fire_popup_disposition(JavaVM *jvm, jobject cb,
                                  const char *url, const char *name,
                                  bool gesture, int w, int h,
                                  const char *page) {
    if (!jvm || !cb) return 0; // BLOCK
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return 0;
        detach = true;
    }
    int disposition = 0; // BLOCK
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF(name ? name : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(cb);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupDisposition",
            "(Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)I");
        if (mid) {
            jint r = env->CallIntMethod(cb, mid, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = 0;
            }
            disposition = (int)r;
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
    return disposition;
}

// Canvas 21 (1.5.0): ask the per-destination User-Agent resolver which UA to
// present for a navigation to `url`.  Returns a freshly allocated UTF-8 string
// the CALLER must free(), or nullptr when there is no resolver, no url, or the
// resolver declines (a null/empty return) or throws.  A resolver must never be
// able to break a navigation, so a pending exception is cleared and treated as
// a decline -- the caller then falls back to the opener-copy behaviour.
// Invoked on the engine UI thread, which is not necessarily attached to the
// JVM, so it attaches and detaches symmetrically.  The resolver object is
// java.util.function.Function, invoked reflectively as apply(Object)Object.
static char *resolve_ua_for(JavaVM *jvm, jobject resolver, const char *url) {
    if (!jvm || !resolver || !url || !*url) return nullptr;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return nullptr;
        detach = true;
    }
    char *out = nullptr;
    jstring ju = env->NewStringUTF(url);
    jclass cls = env->GetObjectClass(resolver);
    if (cls && ju) {
        jmethodID mid = env->GetMethodID(cls, "apply",
            "(Ljava/lang/Object;)Ljava/lang/Object;");
        if (mid) {
            jobject r = env->CallObjectMethod(resolver, mid, ju);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = nullptr;
            }
            if (r) {
                const char *cs = env->GetStringUTFChars((jstring)r, nullptr);
                if (cs && *cs) out = strdup(cs);
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

// Fire onPopupOpened.  CallVoidMethod (fire-and-forget); the Java dispatcher
// marshals to the EDT via invokeLater so this does not block the GTK main
// thread.
static void fire_gtk_popup_opened(JavaVM *jvm, jobject cb, jlong popup_id,
                                  const char *url, const char *name,
                                  bool gesture, int w, int h,
                                  const char *page) {
    if (!jvm || !cb) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF(name ? name : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(cb);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupOpened",
            "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(cb, mid, popup_id, ju, jn,
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

// Canvas 19: notify Java (WebViewPopupCallback.onPopupAdoptable) that a popup
// child has been retained (windowless) and is ready to adopt into a
// WebViewComponent.  Unlike the macOS fire_popup_notify_adoptable — which hops
// onto a detached worker thread because it is invoked while the AppKit main
// thread is blocked in the create delegate — the GTK create handler already
// runs OFF the EDT on the GTK main thread, so a direct CallVoidMethod is safe
// (the Java PopupDispatcher marshals popupAdoptable to the EDT via invokeLater
// itself).  Same fire-and-forget shape as fire_gtk_popup_opened.
static void fire_popup_notify_adoptable(JavaVM *jvm, jobject cb, jlong popup_id,
                                        const char *url, const char *name,
                                        bool gesture, int w, int h,
                                        const char *page) {
    if (!jvm || !cb) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF(name ? name : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(cb);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupAdoptable",
            "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(cb, mid, popup_id, ju, jn,
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

// Fire onPopupClosed.  Does NOT delete the inherited global refs — the caller
// (on_close_popup) frees them after this synchronous call returns.  dialog_cb
// is unused here; it is freed by the caller alongside popup_cb.
static void fire_gtk_popup_closed(JavaVM *jvm, jobject popup_cb,
                                  jobject dialog_cb, jlong popup_id,
                                  const char *url, const char *page) {
    (void)dialog_cb;
    if (!jvm || !popup_cb) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(popup_cb);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupClosed",
            "(JLjava/lang/String;Ljava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(popup_cb, mid, popup_id, ju, jp);
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

// Forward declarations for the child-popup signal handlers (defined below;
// referenced by handle_create_web_view when connecting the child's signals).
static GtkWidget *on_create_web_view_popup(WebKitWebView *web,
        WebKitNavigationAction *nav, gpointer user_data);
static gboolean on_script_dialog_popup(WebKitWebView *web,
        WebKitScriptDialog *dialog, gpointer user_data);
static gboolean on_run_file_chooser_popup(WebKitWebView *web,
        WebKitFileChooserRequest *request, gpointer user_data);
static void on_ready_to_show_popup(WebKitWebView *web, gpointer user_data);
static void on_close_popup(WebKitWebView *web, gpointer user_data);

// Shared inner handler for the `create` signal.  Engine-agnostic: the three
// wrappers (engine / off_engine / popup) pull jvm + popup_callback +
// dialog_callback from their struct and call here.  Returns a new linked child
// WebKitWebView for WebKit to adopt, or NULL to block the popup.
static GtkWidget *handle_create_web_view(JavaVM *jvm, jobject popup_cb,
                                         jobject dialog_cb,
                                         jobject ua_resolver,
                                         WebKitWebView *opener,
                                         WebKitNavigationAction *nav) {
    if (!popup_cb) return NULL;

    const char *uri = "";
    if (nav) {
        WebKitURIRequest *req = webkit_navigation_action_get_request(nav);
        if (req) {
            const char *u = webkit_uri_request_get_uri(req);
            if (u) uri = u;
        }
    }
    bool gesture =
        nav ? (webkit_navigation_action_is_user_gesture(nav) != FALSE) : true;
    const char *page = opener ? webkit_web_view_get_uri(opener) : nullptr;
    if (!page) page = "";

    // Canvas 19: synchronous DISPOSITION hop into Java (GTK main -> JNI).
    // 0 = BLOCK (return NULL), 1 = NATIVE_WINDOW (engine-owned GtkWindow, the
    // Canvas 16 path), 2 = ADOPT (retain the child windowless, notify Java to
    // reparent it into a caller-supplied WebViewComponent).  Size is not known
    // until ready-to-show, so pass -1/-1 (the event contract's "unspecified"
    // sentinel).  The default WebViewPopupCallback.onPopupDisposition derives
    // from onPopupRequested, so legacy Canvas 16 boolean handlers still map to
    // BLOCK / NATIVE_WINDOW.
    int disposition =
        fire_popup_disposition(jvm, popup_cb, uri, "", gesture, -1, -1, page);
    if (disposition == 0)
        return NULL; // BLOCK
    const bool adopt = (disposition == 2);

    // Create the child LINKED to the opener (window.opener / postMessage).
    // WebKit drives the ORIGINAL navigation-action request (POST verb + body)
    // into this child regardless of disposition, preserving POST and
    // window.opener for both NATIVE_WINDOW and ADOPT.
    GtkWidget *childw = webkit_web_view_new_with_related_view(opener);
    if (!childw) return NULL;
    WebKitWebView *child = WEBKIT_WEB_VIEW(childw);

    // Canvas 21: propagate the opener's User-Agent to the popup child BEFORE
    // its in-flight initial navigation.  A child from
    // webkit_web_view_new_with_related_view gets fresh default settings, so
    // copy the opener view's user-agent onto the child's WebKitSettings.  The
    // GTK getter returns the effective UA (it cannot signal "no override"), but
    // copying an unset opener's default UA onto a same-engine child is byte-
    // identical to the child's own default, so the no-override case still
    // yields the engine default.  Composes for nested popups: a popup's child
    // reads the popup view's already-propagated UA.  Covers BOTH the ADOPT and
    // NATIVE_WINDOW dispositions.
    //
    // Canvas 21 (1.5.0): when a per-destination resolver is installed, the
    // child's UA is chosen from the CHILD's own target URL rather than copied
    // from the opener -- the case that matters is an OAuth sign-in popped out
    // of a site that requires a spoofed UA, landing on an identity provider
    // that penalises exactly that spoof.  A resolver that declines (or none at
    // all) falls through to the opener-copy below, so pre-1.5.0 behaviour is
    // preserved byte-for-byte for callers that never set one.
    {
        WebKitSettings *cs = webkit_web_view_get_settings(child);
        char *resolved = resolve_ua_for(jvm, ua_resolver, uri);
        if (resolved) {
            if (cs) webkit_settings_set_user_agent(cs, resolved);
            free(resolved);
        } else if (opener) {
            WebKitSettings *os = webkit_web_view_get_settings(opener);
            if (os && cs) {
                const char *oua = webkit_settings_get_user_agent(os);
                if (oua) webkit_settings_set_user_agent(cs, oua);
            }
        }
    }

    // NATIVE_WINDOW: host the child in an engine-owned native top-level window
    // (gtk_container_add sinks the widget's floating reference — the window owns
    // it).  ADOPT: create NO window; instead take an explicit strong reference
    // so the child survives with no GTK parent until gtk_adopt_popup reparents
    // it.  g_object_ref_sink clears the floating flag (if WebKit has not already
    // done so on the value we return from `create`) and gives us an owning ref
    // held on behalf of g_gtk_retained_popups; it is balanced by the
    // g_object_unref in gtk_adopt_popup (once the adopting window has taken its
    // own container ref) or the gtk_widget_destroy + g_object_unref in
    // gtk_discard_popup.  ON-DEVICE VALIDATION REQUIRED: this dual ownership
    // (our ref + WebKit's own reference on the returned child) mirrors the
    // NATIVE_WINDOW container+WebKit refcounting, but the windowless variant is
    // unexercised in this sandbox.
    GtkWidget *win = nullptr;
    if (!adopt) {
        win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_title(GTK_WINDOW(win), "Popup");
        gtk_container_add(GTK_CONTAINER(win), childw);
    } else {
        g_object_ref_sink(childw);
    }

    PopupEngine *pe = new PopupEngine();
    pe->window = win;              // nullptr for ADOPT
    pe->web = child;
    pe->jvm = jvm;
    pe->popup_id = (jlong)(intptr_t)pe;

    // Inherit fresh global refs so the popup can raise dialogs / nested popups
    // and notify onPopupClosed / onPopupAdoptable even after the opener is gone.
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) == JNI_OK)
            detach = true;
    }
    if (env) {
        pe->popup_callback = env->NewGlobalRef(popup_cb);
        if (dialog_cb) pe->dialog_callback = env->NewGlobalRef(dialog_cb);
        // Canvas 21 (1.5.0): a nested popup resolves its own child's UA the
        // same way its opener did, so the resolver is inherited transitively.
        if (ua_resolver) pe->ua_resolver = env->NewGlobalRef(ua_resolver);
        // Downloads started in a popup belong to the OPENER's handler --
        // a transfer is the user's, not a property of which view began
        // it (Canvas 24).  The opener's DownloadSink is the source of
        // truth here rather than a threaded-through parameter, so a
        // nested popup inherits transitively and an adopted popup keeps
        // the sink it was created with instead of changing owner
        // mid-transfer.
        DownloadSink *osink = opener
            ? (DownloadSink *)g_object_get_data(G_OBJECT(opener),
                                                DOWNLOAD_SINK_KEY)
            : nullptr;
        if (osink && osink->download_callback) {
            pe->download_callback = env->NewGlobalRef(osink->download_callback);
            gtk_install_download_sink(childw, jvm, env,
                                      osink->download_callback);
        }
        if (detach) jvm->DetachCurrentThread();
    }

    // Nested popups + dialogs from inside the popup, and the close hook, wire up
    // for BOTH dispositions.  ready-to-show (which sizes + shows the GtkWindow
    // and fires onPopupOpened) is connected ONLY for NATIVE_WINDOW — an ADOPT
    // child must never create/show a window; it surfaces via onPopupAdoptable
    // and is shown only once reparented into the adopting component.
    g_signal_connect(child, "create",
                     (GCallback)on_create_web_view_popup, pe);
    g_signal_connect(child, "script-dialog",
                     (GCallback)on_script_dialog_popup, pe);
    g_signal_connect(child, "run-file-chooser",
                     (GCallback)on_run_file_chooser_popup, pe);
    g_signal_connect(child, "close",
                     (GCallback)on_close_popup, pe);
    if (!adopt) {
        g_signal_connect(child, "ready-to-show",
                         (GCallback)on_ready_to_show_popup, pe);
    } else {
        // Retain the windowless child and notify Java it is adoptable.  The
        // dispatcher marshals popupAdoptable to the EDT; the application then
        // calls WebViewComponent.adoptPopup(popup_id), whose peer attach calls
        // webview_embed_adopt_popup -> gtk_adopt_popup with this popup_id.
        {
            std::lock_guard<std::mutex> lk(g_gtk_retained_popups_mutex);
            g_gtk_retained_popups[pe->popup_id] = pe;
        }
        fire_popup_notify_adoptable(jvm, popup_cb, pe->popup_id, uri, "",
                                    gesture, -1, -1, page);
    }

    // WebKit adopts (refs) the returned view.  For ADOPT we additionally hold
    // our own ref (above), so the child is not destroyed while windowless.
    return childw;
}

// child `ready-to-show`: size from window properties (fallback 500x650), show,
// then notify onPopupOpened.
static void on_ready_to_show_popup(WebKitWebView *web, gpointer user_data) {
    PopupEngine *pe = static_cast<PopupEngine *>(user_data);
    if (!pe) return;
    // Canvas 19: an ADOPT child is windowless (pe->window == nullptr) and must
    // never show a window or fire onPopupOpened — it surfaces via
    // onPopupAdoptable instead.  handle_create_web_view does not connect this
    // handler for ADOPT, but guard here belt-and-suspenders in case WebKit
    // emits ready-to-show through another path.
    if (!pe->window) return;
    int W = 500, H = 650;
    WebKitWindowProperties *props = webkit_web_view_get_window_properties(web);
    if (props) {
        GdkRectangle geo = {0, 0, 0, 0};
        webkit_window_properties_get_geometry(props, &geo);
        if (geo.width > 0) W = geo.width;
        if (geo.height > 0) H = geo.height;
    }
    if (pe->window) {
        gtk_window_set_default_size(GTK_WINDOW(pe->window), W, H);
        gtk_widget_show_all(pe->window);
        gtk_window_present(GTK_WINDOW(pe->window));
    }
    const char *url = webkit_web_view_get_uri(web);
    fire_gtk_popup_opened(pe->jvm, pe->popup_callback, pe->popup_id,
                          url ? url : "", "", true, W, H, "");
}

// child `close`: window.close() (or the user closed the window).  Notify
// onPopupClosed, destroy the window, free the inherited refs, delete the
// PopupEngine.  Because the notify is synchronous and the Java side only reads
// the event it correlated at open time, freeing the refs here is safe.
static void on_close_popup(WebKitWebView *web, gpointer user_data) {
    PopupEngine *pe = static_cast<PopupEngine *>(user_data);
    if (!pe) return;
    // If this child is still registered as a retained-but-unadopted ADOPT
    // popup (window.close() raced adoption), claim it from the registry
    // under the mutex so a later gtk_adopt_popup / gtk_discard_popup cannot
    // find and re-tear-down the same freed shell.  Whoever removes the id
    // owns the single teardown (mirrors the macOS g_retained_popups claim
    // rule and the Windows RetainedPopupCloseHandler).
    {
        std::lock_guard<std::mutex> lk(g_gtk_retained_popups_mutex);
        auto it = g_gtk_retained_popups.find(pe->popup_id);
        if (it != g_gtk_retained_popups.end() && it->second == pe) {
            g_gtk_retained_popups.erase(it);
        }
    }
    const char *url = webkit_web_view_get_uri(web);
    fire_gtk_popup_closed(pe->jvm, pe->popup_callback, pe->dialog_callback,
                          pe->popup_id, url ? url : "", "");
    if (pe->window) {
        // NATIVE_WINDOW popup: destroying the window destroys its child.
        gtk_widget_destroy(pe->window);
        pe->window = nullptr;
        pe->web = nullptr;
    } else if (pe->web) {
        // Windowless ADOPT child closed BEFORE adoption: no window owns it,
        // so disconnect its handlers, destroy the widget directly, and drop
        // the g_object_ref_sink reference taken in handle_create_web_view
        // (parity with gtk_discard_popup) instead of leaking the child and
        // its ref.
        g_signal_handlers_disconnect_by_data(pe->web, pe);
        gtk_widget_destroy(GTK_WIDGET(pe->web));
        g_object_unref(pe->web);
        pe->web = nullptr;
    }
    JNIEnv *env = nullptr;
    bool detach = false;
    if (pe->jvm && pe->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (pe->jvm->AttachCurrentThread((void **)&env, nullptr) == JNI_OK)
            detach = true;
    }
    if (env) {
        if (pe->popup_callback) env->DeleteGlobalRef(pe->popup_callback);
        if (pe->dialog_callback) env->DeleteGlobalRef(pe->dialog_callback);
        // Clear the child view's DownloadSink before its inherited ref
        // goes, so an in-flight download stops firing into Java
        // (Canvas 24).
        if (pe->web) gtk_clear_download_sink(GTK_WIDGET(pe->web), env);
        if (pe->download_callback) env->DeleteGlobalRef(pe->download_callback);
        if (pe->ua_resolver) env->DeleteGlobalRef(pe->ua_resolver);
        if (detach) pe->jvm->DetachCurrentThread();
    }
    pe->popup_callback = nullptr;
    pe->dialog_callback = nullptr;
    pe->ua_resolver = nullptr;
    pe->download_callback = nullptr;
    delete pe;
}

// Per-PopupEngine wrappers (nested popup + dialogs from inside a popup).
static GtkWidget *on_create_web_view_popup(WebKitWebView *web,
        WebKitNavigationAction *nav, gpointer user_data) {
    PopupEngine *pe = static_cast<PopupEngine *>(user_data);
    if (!pe) return NULL;
    return handle_create_web_view(pe->jvm, pe->popup_callback,
                                  pe->dialog_callback, pe->ua_resolver,
                                  web, nav);
}
static gboolean on_script_dialog_popup(WebKitWebView *web,
        WebKitScriptDialog *dialog, gpointer user_data) {
    PopupEngine *pe = static_cast<PopupEngine *>(user_data);
    if (!pe) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_script_dialog(pe->jvm, pe->dialog_callback, uri, dialog);
}
static gboolean on_run_file_chooser_popup(WebKitWebView *web,
        WebKitFileChooserRequest *request, gpointer user_data) {
    PopupEngine *pe = static_cast<PopupEngine *>(user_data);
    if (!pe) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_run_file_chooser(pe->jvm, pe->dialog_callback, uri, request);
}

// Canvas 34 D7: per-Engine wrapper for "decide-policy" (heavyweight, and an
// adopted popup's view).
static gboolean on_decide_policy_engine(WebKitWebView *web,
        WebKitPolicyDecision *decision, WebKitPolicyDecisionType type,
        gpointer user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e) return FALSE;
    return gtk_decide_navigation(e->jvm, e->navigation_callback, web,
                                 decision, type);
}

// Per-Engine wrapper for the `create` signal (heavyweight).  Mirrors
// on_script_dialog_engine — the divergence is the shared inner it delegates to.
static GtkWidget *on_create_web_view_engine(WebKitWebView *web,
        WebKitNavigationAction *nav, gpointer user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e) return NULL;
    return handle_create_web_view(e->jvm, e->popup_callback,
                                  e->dialog_callback, e->ua_resolver,
                                  web, nav);
}

static void engine_on_message(Engine *e, const char *msg) {
    if (msg == nullptr) return;
    // The message is JSON: {name, seq, args}.  We mirror the bind format used
    // by webview::webview; for the embed API we just forward the raw payload
    // to the named callback (which is what WebViewNativeCallback does).
    // Simple JSON parse for "name":
    std::string s(msg);
    auto pos = s.find("\"name\":\"");
    if (pos == std::string::npos) return;
    auto start = pos + 8;
    auto end = s.find('"', start);
    if (end == std::string::npos) return;
    std::string name = s.substr(start, end - start);
    auto it = e->bindings.find(name);
    if (it == e->bindings.end()) return;
    Binding *b = it->second;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        e->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    jmethodID mid = env->GetMethodID(b->cls, "invoke", "(Ljava/lang/String;J)V");
    if (mid) {
        jstring js = env->NewStringUTF(msg);
        env->CallVoidMethod(b->fn, mid, js, (jlong)e);
        env->DeleteLocalRef(js);
    }
    if (detach) e->jvm->DetachCurrentThread();
}

// Password-manager fire helpers (Canvas 27).  Linux copies of the macOS
// helpers (which live inside the WEBVIEW_COCOA block); identical JNI
// mechanics -- per-call GetMethodID, ExceptionCheck/Clear, attach/detach
// symmetry, null-callback short-circuit.  Only one platform is compiled per
// build, so there is no duplicate-symbol conflict with the Cocoa copies.
static void fire_password_submitted(JavaVM *jvm, jobject callback,
                                    const char *frameUrl,
                                    const char *b64User,
                                    const char *b64Pass) {
    if (!jvm || !callback) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    if (!env) { if (detach) jvm->DetachCurrentThread(); return; }
    jstring jurl = env->NewStringUTF(frameUrl ? frameUrl : "");
    jstring juser = env->NewStringUTF(b64User ? b64User : "");
    jstring jpass = env->NewStringUTF(b64Pass ? b64Pass : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onLoginSubmitted",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jurl, juser, jpass);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jurl) env->DeleteLocalRef(jurl);
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
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    if (!env) { if (detach) jvm->DetachCurrentThread(); return; }
    jstring jurl = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(cls, "onFillRequested",
                                       "(Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jurl);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jurl) env->DeleteLocalRef(jurl);
    if (detach) jvm->DetachCurrentThread();
}

// Parse a "__webview_pw__" payload from `js` and fire the matching callback.
// Templated over the engine type so the heavyweight Engine and the
// lightweight OffEngine (both carry web / jvm / password_callback) share one
// code path.  The origin is stamped natively from webkit_web_view_get_uri,
// never from the JS payload (anti-cross-origin invariant).
template <typename E>
static void gtk_handle_pw_message(E *e, const char *js) {
    if (!e || !e->password_callback || !js) return;
    const gchar *uri = webkit_web_view_get_uri(WEBKIT_WEB_VIEW(e->web));
    std::string frameUrl = uri ? uri : "";
    std::string payload = js;
    if (payload.empty()) return;
    if (payload[0] == 'F') {
        fire_password_fill_requested(e->jvm, e->password_callback,
                                     frameUrl.c_str());
        return;
    }
    if (payload.size() >= 2 && payload[0] == 'S' && payload[1] == '|') {
        size_t p1 = 2;
        size_t p2 = payload.find('|', p1);
        std::string b64user = (p2 == std::string::npos)
            ? payload.substr(p1) : payload.substr(p1, p2 - p1);
        std::string b64pass = (p2 == std::string::npos)
            ? std::string() : payload.substr(p2 + 1);
        fire_password_submitted(e->jvm, e->password_callback,
                                frameUrl.c_str(), b64user.c_str(),
                                b64pass.c_str());
    }
}

// Register (or clear) the Java WebViewPasswordCallback for an engine
// (Canvas 27).  Templated over Engine / OffEngine; mirrors
// gtk_set_dialog_callback.
template <typename E>
static void gtk_set_password_callback_impl(E *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->password_callback) {
        env->DeleteGlobalRef(e->password_callback);
        e->password_callback = nullptr;
    }
    if (cb) e->password_callback = env->NewGlobalRef(cb);
}

// Build a heavyweight engine embedded in `component`'s realized X11 surface.
// Canvas 19: when `existing_web` is non-null (the popup-adoption path) the
// engine REUSES that already-created WebKitWebView instead of allocating a
// fresh one — preserving its in-flight POST navigation + window.opener linkage
// — while still performing the identical JAWT/XReparent/window/frame-clock/
// signal wiring.  `existing_web` must carry no GTK parent (the ADOPT branch
// never adds it to a container); gtk_container_add below adopts it.
static Engine *gtk_create_engine(JNIEnv *env, jobject component, jint debug,
                                 GtkWidget *existing_web = nullptr) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) return nullptr;
    auto *info = (JAWT_X11DrawingSurfaceInfo *)lock.dsi->platformInfo;
    if (info->drawable == 0) return nullptr;
    auto *e = new Engine();
    env->GetJavaVM(&e->jvm);
    e->parent_xid = (Window)info->drawable;
    e->parent_display = info->display;
    e->debug = debug != 0;

    bool ok = false;
    GtkPump::instance().run_sync([&] {
        // GTK_WINDOW_TOPLEVEL rather than POPUP.  POPUP is heavily
        // special-cased in GTK: many WMs ignore it for activation,
        // it gets override-redirect semantics, and its focus
        // bookkeeping is partial.  TOPLEVEL goes through GTK's
        // full focus/activation machinery, which the focus
        // research brief identified as a likely contributor to
        // the visible-text-input-feedback regression on this
        // reparented-popup-with-no-WM-relationship setup.
        // set_decorated(FALSE) suppresses the titlebar so the
        // TOPLEVEL window doesn't paint chrome inside the AWT
        // canvas region.
        e->window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_decorated(GTK_WINDOW(e->window), FALSE);
        // Don't set app_paintable -- with that flag set, GTK skips
        // painting a default background, which leaves the X11 default
        // (often black) showing through whenever the WebKit view fails
        // to fully cover the GdkWindow.  Letting GTK paint the theme
        // background means a render bug shows up as white, not black,
        // and reduces visual artifacts during live resize.
        gtk_widget_realize(e->window);

        GdkWindow *gdkw = gtk_widget_get_window(e->window);
        if (!gdkw) {
            fprintf(stderr,
                "[webview-embed] gtk_widget_get_window returned NULL "
                "after realize; aborting attach.\n");
            return;
        }
        if (!GDK_IS_X11_WINDOW(gdkw)) {
            fprintf(stderr,
                "[webview-embed] GdkWindow is not an X11 window (display "
                "backend is %s).  Heavyweight embedding requires X11.\n",
                gdk_display_get_name(gdk_window_get_display(gdkw)));
            return;
        }

        Window child = GDK_WINDOW_XID(gdkw);
        Display *gdkd = GDK_WINDOW_XDISPLAY(gdkw);
        EMBED_LOG(
            "[webview-embed] Reparenting GTK X11 window 0x%lx under AWT "
            "Canvas X11 window 0x%lx (display %s).\n",
            (unsigned long)child, (unsigned long)e->parent_xid,
            gdk_display_get_name(gdk_window_get_display(gdkw)));

        // Size the GTK window to match the AWT canvas's current X11
        // bounds so the very first frame doesn't show up as the GTK
        // default ~200x200 in the corner.  Java's first setBounds call
        // will refresh it once paint() fires on the canvas.
        {
            Window root_w;
            int gx = 0, gy = 0;
            unsigned int gw = 1, gh = 1, gb = 0, gd = 0;
            if (XGetGeometry(gdkd, e->parent_xid, &root_w,
                             &gx, &gy, &gw, &gh, &gb, &gd) &&
                gw > 0 && gh > 0) {
                gtk_window_resize(GTK_WINDOW(e->window), (int)gw, (int)gh);
                gdk_window_move_resize(gdkw, 0, 0, (int)gw, (int)gh);
            }
        }

        // Reparent under the AWT canvas.  We use the GDK display since AWT
        // and GTK may have different Display* handles for the same X server.
        // Don't XMapWindow here -- gtk_widget_show_all below will call
        // gdk_window_show which maps via the X server through GDK, keeping
        // GDK's mapped-state tracking in sync with reality.
        XReparentWindow(gdkd, child, e->parent_xid, 0, 0);
        XSync(gdkd, False);

        // Canvas 19: reuse the retained popup child (adoption) or create fresh.
        // The reused child already carries its opener linkage + in-flight POST
        // navigation from handle_create_web_view; the caller (gtk_adopt_popup)
        // has already disconnected the child's old PopupEngine signal handlers
        // so the fresh engine-scoped handlers connected below are the only ones.
        // Canvas 31 D2: custom schemes go on the default context before the
        // first view exists (a no-op once done, or when none are registered).
        gtk_install_schemes_once();
        if (existing_web) {
            e->web = existing_web;
        } else {
            e->web = webkit_web_view_new();
        }
        e->manager =
            webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(e->web));

        // Force software compositing.  WebKitGTK's hardware-accelerated
        // path uses DMA-BUF / GL surfaces that frequently fail when the
        // hosting GdkWindow has been XReparented into a foreign X11 tree
        // (or when the system's GL stack is virtualized, e.g. in a
        // Parallels ARM VM).  Software rendering follows reparenting
        // reliably.
        {
            WebKitSettings *s =
                webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
            webkit_settings_set_hardware_acceleration_policy(
                s, WEBKIT_HARDWARE_ACCELERATION_POLICY_NEVER);
        }

        // Force an opaque white background so a failure to render leaves
        // the WebView white (visibly empty) rather than black (which can
        // look identical to "X11 window with no content yet").
        {
            GdkRGBA white = {1.0, 1.0, 1.0, 1.0};
            webkit_web_view_set_background_color(
                WEBKIT_WEB_VIEW(e->web), &white);
        }

        // Log load lifecycle events.  The actual repaint pumping is
        // handled by the GdkFrameClock hookup below -- no need to
        // queue_draw from here, the frame clock fires every vsync and
        // WebKit's internal damage tracking decides whether anything
        // changed.  G_CALLBACK is a single-argument macro; cast to
        // GCallback by hand so the preprocessor doesn't split the
        // lambda parameter list on commas.
        auto on_load_changed =
            +[](WebKitWebView *wv, WebKitLoadEvent ev, gpointer) {
                static const char *names[] = {
                    "started", "redirected", "committed", "finished"
                };
                int idx = (int)ev;
                const char *n = (idx >= 0 && idx < 4) ? names[idx] : "?";
                const char *uri = webkit_web_view_get_uri(wv);
                EMBED_LOG(
                    "[webview-embed] load-%s: %s\n",
                    n, uri ? uri : "(null)");
            };
        auto on_load_failed =
            +[](WebKitWebView *, WebKitLoadEvent, const char *uri,
                GError *err, gpointer) -> gboolean {
                fprintf(stderr,
                    "[webview-embed] load-failed: uri=%s err=%s\n",
                    uri ? uri : "(null)",
                    (err && err->message) ? err->message : "(no message)");
                return FALSE;
            };
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "load-changed",
                         (GCallback)on_load_changed, nullptr);
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "load-failed",
                         (GCallback)on_load_failed, nullptr);

        // Wire JS-initiated dialogs (alert / confirm / prompt /
        // <input type=file>) to the per-engine Java DialogDispatcher via
        // the dialog_callback global ref.  Returning TRUE from each
        // handler suppresses the default GTK dialog; the Java side
        // decides what UI (if any) to show, with default behaviour being
        // Swing dialogs anchored on the host JFrame.  See
        // WebViewDialogHandler for the full contract.  Identical
        // registration site in gtk_off_create_engine — both engines
        // share the inner handle_script_dialog / handle_run_file_chooser
        // logic via the per-engine wrappers.
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "script-dialog",
                         (GCallback)on_script_dialog_engine, e);
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "run-file-chooser",
                         (GCallback)on_run_file_chooser_engine, e);
        // window.open / target=_blank -> native popup window (Canvas 16).
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "create",
                         (GCallback)on_create_web_view_engine, e);
        // Canvas 34 D7: every navigation of the view and its frames is put
        // to the WebViewNavigationCallback first.  Also covers an adopted
        // popup, whose child view arrives here as `childw`.
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "decide-policy",
                         (GCallback)on_decide_policy_engine, e);

        // Wire up the "external" message handler.
        g_signal_connect(
            e->manager, "script-message-received::external",
            G_CALLBACK(+[](WebKitUserContentManager *m,
                           WebKitJavascriptResult *r, gpointer arg) {
                auto *eng = static_cast<Engine *>(arg);
                JSCValue *v = webkit_javascript_result_get_js_value(r);
                char *s = jsc_value_to_string(v);
                engine_on_message(eng, s);
                g_free(s);
            }),
            e);
        webkit_user_content_manager_register_script_message_handler(
            e->manager, "external");
        // Wire the "__webview_pw__" password-manager channel (Canvas 27):
        // PasswordDispatcher.SHIM_JS (injected by the Java layer) posts to
        // it; the handler stamps the origin natively and fires the callback.
        g_signal_connect(
            e->manager, "script-message-received::__webview_pw__",
            G_CALLBACK(+[](WebKitUserContentManager *m,
                           WebKitJavascriptResult *r, gpointer arg) {
                auto *eng = static_cast<Engine *>(arg);
                JSCValue *v = webkit_javascript_result_get_js_value(r);
                char *s = jsc_value_to_string(v);
                gtk_handle_pw_message(eng, s);
                g_free(s);
            }),
            e);
        webkit_user_content_manager_register_script_message_handler(
            e->manager, "__webview_pw__");
        // Install the same external.invoke shim that the existing engine uses.
        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(
                "window.external={invoke:function(s){"
                "window.webkit.messageHandlers.external.postMessage(s);}};",
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL));

        if (e->debug) {
            WebKitSettings *s =
                webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
            webkit_settings_set_enable_developer_extras(s, TRUE);
            webkit_settings_set_enable_write_console_messages_to_stdout(s, TRUE);
        }

        gtk_container_add(GTK_CONTAINER(e->window), e->web);
        gtk_widget_show_all(e->window);

        // Click-to-focus on Linux.  X11 routes key events by
        // XSetInputFocus rather than pointer position, so without an
        // explicit focus handoff a click in the WebView lights up the
        // pointer but text fields never receive keystrokes -- the
        // AWT top-level frame keeps system focus.
        //
        // A previous attempt connected to the "button-press-event"
        // signal on the WebKitWebView.  That broke WebView rendering
        // entirely (suspected fast-path collision inside webkit2gtk's
        // input pipeline).  GtkGestureMultiPress sits on the modern
        // GtkEventController API and observes clicks *alongside* the
        // normal event-signal pipeline rather than intercepting it,
        // so it should leave WebKit's input handling untouched.
        {
            GtkGesture *click =
                gtk_gesture_multi_press_new(e->web);
            // Listen for any mouse button, not just primary, so a
            // right-click also grabs focus.
            gtk_gesture_single_set_button(
                GTK_GESTURE_SINGLE(click), 0);
            // Note: leaving the propagation phase at the default
            // BUBBLE.  An earlier revision set CAPTURE so the focus
            // grab would happen before WebKit reacts to the click,
            // but CAPTURE somehow broke WebKit's rendering pipeline
            // entirely.  BUBBLE observes alongside WebKit's normal
            // handling and is rendering-safe.
            auto on_pressed =
                +[](GtkGestureMultiPress *, gint, gdouble x, gdouble y,
                    gpointer data) {
                    Engine *eng = static_cast<Engine *>(data);
                    if (!eng || !eng->web) return;
                    // X11 focus: target the WebKitWebView's own
                    // GdkWindow (child of the popup) so the X server
                    // routes keystrokes directly to the widget that
                    // wants them, not to the popup's outer window.
                    GdkWindow *wgw = gtk_widget_get_window(eng->web);
                    if (wgw && GDK_IS_X11_WINDOW(wgw)) {
                        XSetInputFocus(GDK_WINDOW_XDISPLAY(wgw),
                                       GDK_WINDOW_XID(wgw),
                                       RevertToParent, CurrentTime);
                        XSync(GDK_WINDOW_XDISPLAY(wgw), False);
                    }
                    // GTK focus chain: tell GTK that the WebKitWebView
                    // is the focus widget within the popup, so any
                    // keys that arrive get dispatched to it.
                    gtk_widget_grab_focus(eng->web);
                    EMBED_LOG(
                        "[webview-embed] click @ (%.0f,%.0f) -> "
                        "focus grabbed (web_xid=0x%lx)\n",
                        x, y,
                        wgw ? (unsigned long)GDK_WINDOW_XID(wgw) : 0UL);
                    // Notify Java of the press so Swing can dismiss any
                    // open JPopupMenu / JMenu / JComboBox dropdown.  AWT's
                    // BasicPopupMenuUI MouseGrabber listener never sees
                    // these clicks because they reach the embedded
                    // WebKitWebView's GdkWindow directly rather than via
                    // AWT's event queue.  Added AFTER the focus-grab work
                    // above so the existing focus behaviour is preserved
                    // exactly.
                    fire_click_callback(eng);
                };
            g_signal_connect(click, "pressed",
                             (GCallback)on_pressed, e);
            // The gesture is owned by the widget; it stays alive
            // for as long as the WebKitWebView does.
        }

        // Drive the GTK paint pipeline from a plain g_timeout at ~60Hz.
        // See the redraw_timer_id field comment on Engine for the
        // rationale; in short, the X11 GdkFrameClock won't pace itself
        // on a reparented popup that has no WM relationship, so we
        // run the queue_draw + process_updates pair ourselves on a
        // timer.  WebKit's internal damage tracking decides whether
        // anything actually needs repainting; calls with no damage
        // are effectively no-ops.
        auto redraw_tick =
            +[](gpointer data) -> gboolean {
                Engine *eng = static_cast<Engine *>(data);
                if (!eng || !eng->web) return G_SOURCE_REMOVE;
                if (gtk_widget_get_visible(eng->web)) {
                    gtk_widget_queue_draw(eng->web);
                    if (eng->window) {
                        GdkWindow *pgw =
                            gtk_widget_get_window(eng->window);
                        if (pgw) {
                            G_GNUC_BEGIN_IGNORE_DEPRECATIONS
                            gdk_window_process_updates(pgw, TRUE);
                            G_GNUC_END_IGNORE_DEPRECATIONS
                        }
                    }
                }
                return G_SOURCE_CONTINUE;
            };
        e->redraw_timer_id = g_timeout_add(16, redraw_tick, e);
        EMBED_LOG(
            "[webview-embed] repaint timer started (id=%u, period=16ms).\n",
            (unsigned)e->redraw_timer_id);

        // Verbose pipeline instrumentation -- enabled with
        // DEBUG_WEBVIEW_EMBED=1 because the per-frame signals are too
        // chatty for normal use.  When set, this tells us:
        //   - whether ::draw fires on the WebKitWebView at all
        //     (no => paint pipeline is dead before WebKit ever runs)
        //   - whether each GdkFrameClock phase fires
        //     (no => begin_updating isn't taking effect on this window)
        //   - widget state right after show_all
        //     (mapped/realized/drawable/viewable + allocation)
        if (embed_verbose()) {
            auto on_draw =
                +[](GtkWidget *w, cairo_t *, gpointer name) -> gboolean {
                    static guint counter = 0;
                    guint c = ++counter;
                    if (c < 8 || (c % 60) == 0) {
                        fprintf(stderr,
                            "[webview-embed] draw#%u on %s (%p)\n",
                            c, (const char *)name, (void *)w);
                    }
                    return FALSE;
                };
            g_signal_connect(e->web, "draw",
                             (GCallback)on_draw, (gpointer)"WebKitWebView");
            g_signal_connect(e->window, "draw",
                             (GCallback)on_draw, (gpointer)"popup");

            GdkWindow *gdkw_pop = gtk_widget_get_window(e->window);
            GdkFrameClock *clk = gdkw_pop
                ? gdk_window_get_frame_clock(gdkw_pop) : nullptr;
            if (clk) {
                auto on_phase =
                    +[](GdkFrameClock *, gpointer name) {
                        static guint counters[8] = {0};
                        const char *n = (const char *)name;
                        guint h = (guint)((uintptr_t)name & 7);
                        guint c = ++counters[h];
                        if (c < 4 || (c % 240) == 0) {
                            fprintf(stderr,
                                "[webview-embed] frame-clock %s #%u\n", n, c);
                        }
                    };
                g_signal_connect(clk, "before-paint",
                                 (GCallback)on_phase, (gpointer)"before-paint");
                g_signal_connect(clk, "update",
                                 (GCallback)on_phase, (gpointer)"update");
                g_signal_connect(clk, "layout",
                                 (GCallback)on_phase, (gpointer)"layout");
                g_signal_connect(clk, "paint",
                                 (GCallback)on_phase, (gpointer)"paint");
                g_signal_connect(clk, "after-paint",
                                 (GCallback)on_phase, (gpointer)"after-paint");
            }

            GtkAllocation alloc = {0, 0, 0, 0};
            if (e->web) gtk_widget_get_allocation(e->web, &alloc);
            GdkWindow *pgw = gtk_widget_get_window(e->window);
            fprintf(stderr,
                "[webview-embed] state after show_all: popup mapped=%d "
                "realized=%d drawable=%d viewable=%d, webview mapped=%d "
                "realized=%d drawable=%d allocation=%dx%d at (%d,%d)\n",
                gtk_widget_get_mapped(e->window),
                gtk_widget_get_realized(e->window),
                gtk_widget_is_drawable(e->window),
                pgw ? gdk_window_is_viewable(pgw) : -1,
                e->web ? gtk_widget_get_mapped(e->web) : -1,
                e->web ? gtk_widget_get_realized(e->web) : -1,
                e->web ? gtk_widget_is_drawable(e->web) : -1,
                alloc.width, alloc.height, alloc.x, alloc.y);
        }

        ok = true;
    });
    if (!ok) {
        delete e;
        return nullptr;
    }
    return e;
}

static void gtk_destroy_engine(Engine *e) {
    if (!e) return;
    // Canvas 34 D7: stop asking, then drop the navigation callback, before
    // anything else is torn down: a decision arriving mid-teardown must not
    // reach a freed engine or a freed ref.
    if (e->web) {
        g_signal_handlers_disconnect_by_func(G_OBJECT(e->web),
            (gpointer)on_decide_policy_engine, e);
    }
    if (e->navigation_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->navigation_callback);
        e->navigation_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Canvas 21 (1.5.0): drop the User-Agent resolver's global ref.  Only the
    // popup-child creation path reads it, but a late popup during teardown
    // would follow a freed ref, so it goes with the other callbacks.
    if (e->ua_resolver) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Release the click callback's JNI global ref BEFORE we destroy the
    // GtkWidget tree so any pressed signal already dispatched but not yet
    // run sees a null field instead of invoking a freed ref.  Symmetric
    // with the click-callback clear in EmbeddedWebView.dispose() on the
    // Java side -- belt-and-suspenders coverage for callbacks installed
    // via setClickCallback that never made it through that path.
    if (e->click_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the dialog-callback global ref.  Stored only
    // in this canvas; STORY-004-002 will fire signal handlers off
    // this field, so symmetric cleanup is required even though
    // STORY-004-001 itself never invokes the callback on Linux.
    if (e->dialog_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the popup-callback global ref (Canvas 16).
    if (e->popup_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the password-callback global ref (Canvas 27).
    if (e->password_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->password_callback);
        e->password_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the download-callback global ref, plus the
    // view's DownloadSink (Canvas 24).  A download can outlive the page
    // and the navigation, so both go BEFORE the widget is destroyed --
    // a late fire into a freed ref is a SIGSEGV, not an exception.
    if (e->download_callback || e->web) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) {
            gtk_clear_download_sink(e->web, env);
            if (e->download_callback) env->DeleteGlobalRef(e->download_callback);
        }
        e->download_callback = nullptr;
        if (detach && e->jvm) e->jvm->DetachCurrentThread();
    }
    GtkPump::instance().run_sync([&] {
        if (e->redraw_timer_id) {
            g_source_remove(e->redraw_timer_id);
            e->redraw_timer_id = 0;
        }
        if (e->window) {
            gtk_widget_destroy(e->window);
            e->window = nullptr;
            e->web = nullptr;
        }
    });
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
    delete e;
}

static void gtk_set_bounds(Engine *e, int /*x*/, int /*y*/,
                           int width, int height) {
    // The Java side passes (x,y) as the canvas's position in the AWT
    // Window's content-pane coordinates because the Mac path needs that
    // (its host is NSWindow.contentView, not the canvas).  On Linux the
    // host is the canvas's own X11 window, which is already correctly
    // positioned/sized by AWT, so we always place the GTK child at
    // (0,0) of it and just match the size.
    GtkPump::instance().run_async([=] {
        if (!e->window) return;
        int w = width > 0 ? width : 1;
        int h = height > 0 ? height : 1;
        gtk_window_resize(GTK_WINDOW(e->window), w, h);
        GdkWindow *gdkw = gtk_widget_get_window(e->window);
        if (gdkw) {
            gdk_window_move_resize(gdkw, 0, 0, w, h);
        }
        // Force a repaint after resize -- in some virtualized X11 setups
        // the resize does not on its own generate an Expose, leaving the
        // newly-revealed regions blank.
        gtk_widget_queue_draw(e->window);
        if (e->web) gtk_widget_queue_draw(e->web);
    });
}

static void gtk_navigate(Engine *e, std::string url) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        EMBED_LOG(
            "[webview-embed] webkit_web_view_load_uri: %s\n", url.c_str());
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(e->web), url.c_str());
    });
}

static void gtk_init_script(Engine *e, std::string js) {
    GtkPump::instance().run_async([=] {
        if (!e->manager) return;
        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(js.c_str(),
                                   WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                                   WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                                   NULL, NULL));
    });
}

static void gtk_eval(Engine *e, std::string js) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        webkit_web_view_run_javascript(WEBKIT_WEB_VIEW(e->web), js.c_str(),
                                       NULL, NULL, NULL);
    });
}

static void gtk_set_visible(Engine *e, bool visible) {
    GtkPump::instance().run_async([=] {
        if (!e->window) return;
        if (visible) gtk_widget_show(e->window);
        else gtk_widget_hide(e->window);
    });
}

// Move X11 input focus to the embedded WebView's X11 window.  X11 routes
// key events based on input focus, not pointer position; on the AWT
// embed path the WM has only ever assigned input focus to the AWT
// top-level frame, so without this call keystrokes typed while the
// pointer is over the WebView region go to the AWT frame (and get
// swallowed there) instead of the WebKit widget.
//
// We target the WebKitWebView's own GdkWindow rather than the popup's
// outer window.  WebKitWebView is a windowed GtkContainer (creates its
// own X11 child window inside the popup), so giving X11 focus to that
// inner window lets the X server route keystrokes straight to the
// widget that wants them.
static void gtk_request_focus(Engine *e) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *wgw = gtk_widget_get_window(e->web);
        if (wgw && GDK_IS_X11_WINDOW(wgw)) {
            XSetInputFocus(GDK_WINDOW_XDISPLAY(wgw),
                           GDK_WINDOW_XID(wgw),
                           RevertToParent, CurrentTime);
            XSync(GDK_WINDOW_XDISPLAY(wgw), False);
        }
        gtk_widget_grab_focus(e->web);
    });
}

static void gtk_bind(Engine *e, Binding *b) {
    e->bindings[b->name] = b;
}

// Register (or clear, when cb is null) the Java WebViewClickCallback for
// this engine.  The pressed signal handler installed in gtk_create_engine
// fires this callback via fire_click_callback so Swing can dismiss any
// open JPopupMenu when the user clicks into the WebView -- AWT's
// MouseGrabber AWTEventListener cannot see those clicks because they
// reach the WebKitWebView's GdkWindow directly rather than via AWT.
// Always deletes any previously installed global ref before installing
// the new one, even when cb is null.  Mirrors cocoa_set_focus_callback.
static void gtk_set_click_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->click_callback) {
        env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
    }
    if (cb) {
        e->click_callback = env->NewGlobalRef(cb);
    }
}

// ---------------------------------------------------------------------------
// Browser-initiated file downloads for WebKitGTK (Canvas 24).
//
// Three things about this backend shape the code below:
//
//   1. `download-started` is emitted on the WebKitWebContext, NOT on the
//      web view, and one context is shared by every view created against
//      it.  Every download therefore has to be mapped back to the view
//      that started it (webkit_download_get_web_view) before a Java
//      dispatcher can be chosen.  Getting this wrong sends one
//      component's downloads to another component's handler.  A download
//      whose view we do not recognise is left entirely alone, so
//      WebKitGTK keeps its built-in behaviour for anything this library
//      did not create.
//
//   2. Refusing a download means CANCELLING it.  Returning TRUE from
//      `decide-destination` without setting a destination is not enough
//      -- WebKitGTK may still fall back to the user's download
//      directory, which is exactly the behaviour this canvas removes.
//
//   3. webkit_download_set_destination changed meaning at WebKitGTK
//      2.40: a file:// URI before, a plain path from then on.  One
//      libwebview.so serves both 4.0 and 4.1 hosts, so the form is
//      chosen at runtime.
// ---------------------------------------------------------------------------

static void free_download_sink(gpointer p) {
    DownloadSink *sink = (DownloadSink *)p;
    if (!sink) return;
    if (sink->download_callback && sink->jvm) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (sink->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            sink->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(sink->download_callback);
        if (detach) sink->jvm->DetachCurrentThread();
    }
    sink->download_callback = nullptr;
    g_free(sink);
}

// Invoke WebViewDownloadCallback.onDownloadRequested; copy the returned
// absolute path into *out_path.  Returns false when there is no
// callback, the method is missing, or Java refused (null return).
// Shape mirrors fire_click_callback exactly.
static bool fire_download_requested(DownloadSink *sink, long long id,
                                    const char *url, const char *suggested,
                                    const char *mime, gint64 total,
                                    const char *page_url,
                                    std::string *out_path) {
    if (!sink || !sink->download_callback || !sink->jvm || !out_path) {
        return false;
    }
    JNIEnv *env = nullptr;
    bool detach = false;
    if (sink->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        sink->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    bool got = false;
    if (env) {
        jclass cls = env->GetObjectClass(sink->download_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(
                cls, "onDownloadRequested",
                "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;J"
                "Ljava/lang/String;)Ljava/lang/String;");
            if (m) {
                jstring jurl = env->NewStringUTF(url ? url : "");
                jstring jname = env->NewStringUTF(suggested ? suggested : "");
                jstring jmime = env->NewStringUTF(mime ? mime : "");
                jstring jpage = env->NewStringUTF(page_url ? page_url : "");
                jobject res = env->CallObjectMethod(
                    sink->download_callback, m, (jlong)id, jurl, jname, jmime,
                    (jlong)total, jpage);
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
    if (detach) sink->jvm->DetachCurrentThread();
    return got;
}

static void fire_download_progress(DownloadSink *sink, long long id,
                                   guint64 received, gint64 total) {
    if (!sink || !sink->download_callback || !sink->jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (sink->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        sink->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(sink->download_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(cls, "onDownloadProgress", "(JJJ)V");
            if (m) {
                env->CallVoidMethod(sink->download_callback, m, (jlong)id,
                                    (jlong)received, (jlong)total);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) sink->jvm->DetachCurrentThread();
}

static void fire_download_completed(DownloadSink *sink, long long id,
                                    gboolean success, const char *reason,
                                    guint64 received) {
    if (!sink || !sink->download_callback || !sink->jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (sink->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        sink->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(sink->download_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(
                cls, "onDownloadCompleted", "(JZLjava/lang/String;J)V");
            if (m) {
                jstring jreason = env->NewStringUTF(reason ? reason : "");
                env->CallVoidMethod(sink->download_callback, m, (jlong)id,
                                    (jboolean)(success ? JNI_TRUE : JNI_FALSE),
                                    jreason, (jlong)received);
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (jreason) env->DeleteLocalRef(jreason);
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) sink->jvm->DetachCurrentThread();
}

// webkit_download_set_destination takes a file:// URI before WebKitGTK
// 2.40 and a plain filesystem path from 2.40 onward.  One libwebview.so
// serves both, so pick at runtime.  Caller g_free's the result.
static gchar *gtk_download_destination_arg(const char *abs_path) {
    if (!abs_path) return nullptr;
    guint major = webkit_get_major_version();
    guint minor = webkit_get_minor_version();
    if (major > 2 || (major == 2 && minor >= 40)) {
        return g_strdup(abs_path);
    }
    // Pre-2.40 wants a URI.  g_filename_to_uri percent-encodes, which
    // hand-concatenating "file://" would not -- a path containing a
    // space, '#' or '?' has to survive.
    gchar *uri = g_filename_to_uri(abs_path, nullptr, nullptr);
    return uri ? uri : g_strdup(abs_path);
}

// `decide-destination`: returning TRUE means "handled, stop emission",
// which is what suppresses WebKitGTK's own destination choice.
static gboolean on_download_decide_destination(WebKitDownload *d,
                                               const gchar *suggested,
                                               gpointer user_data) {
    GtkDownloadCtx *ctx = (GtkDownloadCtx *)user_data;
    if (!ctx || !ctx->sink) return FALSE;

    const char *url = nullptr;
    const char *mime = nullptr;
    gint64 total = -1;

    WebKitURIResponse *resp = webkit_download_get_response(d);
    if (resp) {
        guint64 len = webkit_uri_response_get_content_length(resp);
        total = (len == 0) ? -1 : (gint64)len;
        mime = webkit_uri_response_get_mime_type(resp);
        url = webkit_uri_response_get_uri(resp);
    }
    if (!url) {
        WebKitURIRequest *req = webkit_download_get_request(d);
        if (req) url = webkit_uri_request_get_uri(req);
    }
    ctx->total = total;

    const char *page_url = nullptr;
    WebKitWebView *view = webkit_download_get_web_view(d);
    if (view) page_url = webkit_web_view_get_uri(view);

    std::string path;
    if (!fire_download_requested(ctx->sink, ctx->id, url, suggested, mime,
                                 total, page_url, &path)) {
        // Refused.  Cancel -- returning TRUE alone would let WebKitGTK
        // fall back to the user's download directory.
        webkit_download_cancel(d);
        return TRUE;
    }

    // The destination was chosen deliberately (by the user through the
    // stock save dialog's overwrite confirmation, or by an application
    // handler); leaving overwrite disabled would make WebKitGTK rename
    // or fail a download the host already approved.
    webkit_download_set_allow_overwrite(d, TRUE);
    gchar *arg = gtk_download_destination_arg(path.c_str());
    if (arg) {
        webkit_download_set_destination(d, arg);
        g_free(arg);
    }
    return TRUE;
}

static void on_download_received_data(WebKitDownload *d, guint64 length,
                                      gpointer user_data) {
    (void)d;
    GtkDownloadCtx *ctx = (GtkDownloadCtx *)user_data;
    if (!ctx || !ctx->sink || ctx->terminal) return;
    ctx->received += length;
    // No native throttling: the Java dispatcher coalesces progress, and
    // throttling here would make the last reported value lag the
    // terminal event.
    fire_download_progress(ctx->sink, ctx->id, ctx->received, ctx->total);
}

static void on_download_finished(WebKitDownload *d, gpointer user_data) {
    GtkDownloadCtx *ctx = (GtkDownloadCtx *)user_data;
    if (!ctx || !ctx->sink || ctx->terminal) return;
    ctx->terminal = TRUE;
    guint64 got = webkit_download_get_received_data_length(d);
    if (got > ctx->received) ctx->received = got;
    fire_download_completed(ctx->sink, ctx->id, TRUE, "", ctx->received);
}

static void on_download_failed(WebKitDownload *d, GError *error,
                               gpointer user_data) {
    (void)d;
    GtkDownloadCtx *ctx = (GtkDownloadCtx *)user_data;
    if (!ctx || !ctx->sink || ctx->terminal) return;
    ctx->terminal = TRUE;
    // A refusal arrives here as WEBKIT_DOWNLOAD_ERROR_CANCELLED_BY_USER
    // and needs no special case: the dispatcher already reported the
    // refusal and latched the id, so this second report is dropped
    // Java-side.
    const char *reason = (error && error->message) ? error->message
                                                   : "Download failed";
    fire_download_completed(ctx->sink, ctx->id, FALSE, reason, ctx->received);
}

// `download-started` on the shared WebKitWebContext.
static void on_context_download_started(WebKitWebContext *context,
                                        WebKitDownload *d,
                                        gpointer user_data) {
    (void)context;
    (void)user_data;
    if (!d) return;
    WebKitWebView *view = webkit_download_get_web_view(d);
    if (!view) return;
    DownloadSink *sink = (DownloadSink *)g_object_get_data(G_OBJECT(view),
                                                           DOWNLOAD_SINK_KEY);
    // Not one of ours, or no handler installed: leave the download
    // entirely alone so WebKitGTK's built-in handling still applies.
    if (!sink || !sink->download_callback) return;

    GtkDownloadCtx *ctx = g_new0(GtkDownloadCtx, 1);
    ctx->sink = sink;
    ctx->id = sink->next_id++;
    ctx->total = -1;
    ctx->received = 0;
    ctx->terminal = FALSE;
    g_object_set_data_full(G_OBJECT(d), DOWNLOAD_CTX_KEY, ctx, g_free);

    g_signal_connect(d, "decide-destination",
                     (GCallback)on_download_decide_destination, ctx);
    g_signal_connect(d, "received-data",
                     (GCallback)on_download_received_data, ctx);
    g_signal_connect(d, "finished",
                     (GCallback)on_download_finished, ctx);
    g_signal_connect(d, "failed",
                     (GCallback)on_download_failed, ctx);
}

// Install (or refresh) the per-view DownloadSink and make sure
// `download-started` is connected on the view's context EXACTLY ONCE.
// Two engines on one context would otherwise both handle every download.
static void gtk_install_download_sink(GtkWidget *web, JavaVM *jvm,
                                      JNIEnv *env, jobject cb) {
    if (!web) return;
    DownloadSink *sink =
        (DownloadSink *)g_object_get_data(G_OBJECT(web), DOWNLOAD_SINK_KEY);
    if (!sink) {
        sink = g_new0(DownloadSink, 1);
        sink->next_id = 1;
        g_object_set_data_full(G_OBJECT(web), DOWNLOAD_SINK_KEY, sink,
                               free_download_sink);
    }
    sink->jvm = jvm;
    if (sink->download_callback && env) {
        env->DeleteGlobalRef(sink->download_callback);
    }
    sink->download_callback = (cb && env) ? env->NewGlobalRef(cb) : nullptr;

    WebKitWebContext *wctx = webkit_web_view_get_context(WEBKIT_WEB_VIEW(web));
    if (!wctx) return;
    if (g_object_get_data(G_OBJECT(wctx), DOWNLOAD_CONNECTED_KEY)) return;
    g_object_set_data(G_OBJECT(wctx), DOWNLOAD_CONNECTED_KEY,
                      GINT_TO_POINTER(1));
    g_signal_connect(wctx, "download-started",
                     (GCallback)on_context_download_started, nullptr);
}

// Clear the per-view DownloadSink's callback so an in-flight download
// stops firing into Java.  Called from the engine destroy paths BEFORE
// the widget is destroyed.
static void gtk_clear_download_sink(GtkWidget *web, JNIEnv *env) {
    if (!web) return;
    DownloadSink *sink =
        (DownloadSink *)g_object_get_data(G_OBJECT(web), DOWNLOAD_SINK_KEY);
    if (!sink) return;
    if (sink->download_callback && env) {
        env->DeleteGlobalRef(sink->download_callback);
    }
    sink->download_callback = nullptr;
}

// Register (or clear, when cb is null) the Java WebViewDownloadCallback
// for a heavyweight engine.  Mirrors gtk_set_dialog_callback, and
// additionally installs the view's DownloadSink and the one-time
// context-level `download-started` connection.
static void gtk_set_download_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->download_callback) {
        env->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
    }
    if (cb) {
        e->download_callback = env->NewGlobalRef(cb);
    }
    gtk_install_download_sink(e->web, e->jvm, env, cb);
}


// Register (or clear, when cb is null) the Java WebViewDialogCallback
// for this engine.  STORY-004-001 stores the global ref but does NOT
// install the WebKitWebView script-dialog / run-file-chooser signal
// handlers that actually invoke it -- those land in STORY-004-002.
// Implementing the storage lifecycle here keeps the JNI bridge linkable
// across all three platforms and lets STORY-004-002 ship by only
// touching the signal-handler wiring.
static void gtk_set_dialog_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

// Register (or clear, when cb is null) the Java WebViewPopupCallback for this
// heavyweight engine (Canvas 16).  Mirrors gtk_set_dialog_callback.
static void gtk_set_popup_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->popup_callback) {
        env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
    }
    if (cb) {
        e->popup_callback = env->NewGlobalRef(cb);
    }
}

// Canvas 34 D5: register (or clear, when cb is null) the navigation callback.
// Mirrors gtk_set_popup_callback; the "decide-policy" handler reads the field
// on every navigation.
static void gtk_set_navigation_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->navigation_callback) {
        env->DeleteGlobalRef(e->navigation_callback);
        e->navigation_callback = nullptr;
    }
    if (cb) {
        e->navigation_callback = env->NewGlobalRef(cb);
    }
}

// Canvas 21: override the WebKitGTK User-Agent (changes the HTTP header).
// ua == nullptr restores the engine default.  Takes effect on the next
// navigation.
static void gtk_set_user_agent(Engine *e, const char *ua) {
    if (!e || !e->web) return;
    WebKitSettings *s = webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
    if (s) webkit_settings_set_user_agent(s, ua);
}

// Canvas 21 (1.5.0): install/clear the per-destination User-Agent resolver.
// Held as a JNI global ref so it survives the setting call; the previous ref is
// released first.  Consulted only at the popup-child creation site (navigations
// Java drives are resolved on the Java side before navigate).
static void gtk_set_user_agent_resolver(Engine *e, JNIEnv *env, jobject r) {
    if (!e || !env) return;
    if (e->ua_resolver) {
        env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
    }
    if (r) {
        e->ua_resolver = env->NewGlobalRef(r);
    }
}

// Canvas 29 D8: an in-flight WebKitPrintOperation.  `failed` fires before
// `finished`; the first to fire answers the job.
struct GtkPdfOp {
    PdfJob *job;
    std::string path;
    time_t started;
};

static void gtk_pdf_failed(WebKitPrintOperation *, GError *error,
                           gpointer user_data) {
    GtkPdfOp *op = static_cast<GtkPdfOp *>(user_data);
    std::string msg = "The page could not be printed to PDF";
    if (error && error->message && *error->message) {
        msg += ": ";
        msg += error->message;
    }
    msg += ".";
    pdf_finish(op->job, false, msg.c_str());
    op->job = nullptr;
}

static void gtk_pdf_finished(WebKitPrintOperation *operation,
                             gpointer user_data) {
    GtkPdfOp *op = static_cast<GtkPdfOp *>(user_data);
    if (op->job) {
        // `finished` without `failed` is not proof: a print the backend
        // dropped also ends here.  Only a file written by this print counts.
        GStatBuf st;
        bool written = g_stat(op->path.c_str(), &st) == 0 && st.st_size > 0 &&
                       st.st_mtime + 1 >= op->started;
        pdf_finish(op->job, written,
                   written ? nullptr : "The PDF file was not written.");
    }
    op->job = nullptr;
    delete op;
    // Emission holds its own ref; this drops the one webkit_print_operation_new
    // gave us.
    g_object_unref(operation);
}

// Canvas 29 D8: print `web` to a PDF at `path` through GTK's "Print to File"
// printer.  Used by the offscreen (lightweight) engine, the supported Linux
// mode (D13).  The GtkWidget* is
// read on the GTK thread through `get_web`, since the engine may not have
// created its view yet when the request arrives.
static void gtk_print_to_pdf(std::function<GtkWidget *()> get_web,
                             PdfJob *job, std::string path, double w,
                             double h, double mt, double mr, double mb,
                             double ml, bool bg) {
    GtkPump::instance().run_async([=] {
        time_t started = time(nullptr);
        GtkWidget *web = get_web();
        if (!web) {
            pdf_finish(job, false, kPdfNotAttached);
            return;
        }
        WebKitWebView *view = WEBKIT_WEB_VIEW(web);
        g_object_set(G_OBJECT(webkit_web_view_get_settings(view)),
                     "print-backgrounds", bg ? TRUE : FALSE, NULL);

        GError *uerr = nullptr;
        gchar *uri = g_filename_to_uri(path.c_str(), nullptr, &uerr);
        if (!uri) {
            if (uerr) g_error_free(uerr);
            pdf_finish(job, false, "The PDF path is not a valid file path.");
            return;
        }
        GtkPaperSize *paper = gtk_paper_size_new_custom(
            "aaf-pdf", "PDF", w, h, GTK_UNIT_INCH);

        GtkPrintSettings *settings = gtk_print_settings_new();
        gtk_print_settings_set_printer(settings, "Print to File");
        gtk_print_settings_set(settings, GTK_PRINT_SETTINGS_OUTPUT_FILE_FORMAT,
                               "pdf");
        gtk_print_settings_set(settings, GTK_PRINT_SETTINGS_OUTPUT_URI, uri);
        gtk_print_settings_set_paper_size(settings, paper);
        gtk_print_settings_set_orientation(settings,
                                           GTK_PAGE_ORIENTATION_PORTRAIT);
        g_free(uri);

        GtkPageSetup *setup = gtk_page_setup_new();
        gtk_page_setup_set_paper_size(setup, paper);
        gtk_page_setup_set_orientation(setup, GTK_PAGE_ORIENTATION_PORTRAIT);
        gtk_page_setup_set_top_margin(setup, mt, GTK_UNIT_INCH);
        gtk_page_setup_set_right_margin(setup, mr, GTK_UNIT_INCH);
        gtk_page_setup_set_bottom_margin(setup, mb, GTK_UNIT_INCH);
        gtk_page_setup_set_left_margin(setup, ml, GTK_UNIT_INCH);
        gtk_paper_size_free(paper);

        WebKitPrintOperation *operation = webkit_print_operation_new(view);
        webkit_print_operation_set_print_settings(operation, settings);
        webkit_print_operation_set_page_setup(operation, setup);
        g_object_unref(settings);
        g_object_unref(setup);

        GtkPdfOp *op = new GtkPdfOp{job, path, started};
        g_signal_connect(operation, "failed", G_CALLBACK(gtk_pdf_failed), op);
        g_signal_connect(operation, "finished", G_CALLBACK(gtk_pdf_finished),
                         op);
        webkit_print_operation_print(operation);
    });
}

// Canvas 22: purge the WebKitGTK HTTP resource cache (memory + disk) for the
// view's web context.  Clears the resource cache only -- the cookie manager
// is untouched, so an active login survives.
static void gtk_clear_cache(Engine *e) {
    if (!e || !e->web) return;
    WebKitWebContext *ctx = webkit_web_view_get_context(WEBKIT_WEB_VIEW(e->web));
    if (ctx) webkit_web_context_clear_cache(ctx);
}

struct GtkCookieQuery {
    WebKitCookieManager *manager = nullptr;
    WebKitWebContext *context = nullptr;
    CookieCompletion *completion = nullptr;
};

static void gtk_cookies_ready(GObject *, GAsyncResult *result,
                              gpointer user_data) {
    GtkCookieQuery *query = static_cast<GtkCookieQuery *>(user_data);
    // The manager's finalizer still accesses its WebsiteDataStore after this
    // callback returns. Keep the owning context on the result until GTask has
    // released its source-manager reference, including when the view is closed.
    g_object_set_data_full(G_OBJECT(result), "qplayer-cookie-context",
                           query->context, (GDestroyNotify)g_object_unref);
    GError *error = nullptr;
    GList *cookies = webkit_cookie_manager_get_cookies_finish(
        query->manager, result, &error);
    std::string header;
    if (!error) {
        for (GList *it = cookies; it; it = it->next) {
            SoupCookie *cookie = static_cast<SoupCookie *>(it->data);
            const char *name = soup_cookie_get_name(cookie);
            const char *value = soup_cookie_get_value(cookie);
            if (!name || !value) continue;
            if (!header.empty()) header += "; ";
            header += name;
            header += '=';
            header += value;
        }
    }
    const char *message = error ? error->message : nullptr;
    complete_cookie_query(query->completion, header, message);
    if (cookies) g_list_free_full(cookies, (GDestroyNotify)soup_cookie_free);
    if (error) g_error_free(error);
    delete query;
}

static void gtk_get_cookies(GtkWidget *web, const std::string &url,
                            CookieCompletion *completion) {
    if (!web) {
        complete_cookie_query(completion, "", "WebView is not available");
        return;
    }
    // Only setup is synchronous; the cookie IPC remains asynchronous. A close
    // cannot destroy the widget before this operation acquires its context.
    GtkPump::instance().run_sync([web, url, completion] {
        WebKitWebContext *context = webkit_web_view_get_context(
            WEBKIT_WEB_VIEW(web));
        WebKitCookieManager *manager = context
            ? webkit_web_context_get_cookie_manager(context) : nullptr;
        if (!manager) {
            complete_cookie_query(completion, "",
                                  "WebKit cookie manager is not available");
            return;
        }
        GtkCookieQuery *query = new GtkCookieQuery();
        query->manager = manager;
        query->context = WEBKIT_WEB_CONTEXT(g_object_ref(context));
        query->completion = completion;
        webkit_cookie_manager_get_cookies(manager, url.c_str(), nullptr,
                                          gtk_cookies_ready, query);
    });
}

// ---------------------------------------------------------------------------
// Canvas 19: popup adoption — reparent a retained-but-unadopted popup child
// into a caller-supplied WebViewComponent's realized X11 surface.
//
// Two-phase, mirroring the macOS cocoa_adopt_popup / cocoa_discard_popup:
//   Phase 1 (handle_create_web_view ADOPT branch, above): the opener-linked
//     child WebKitWebView is created windowless, retained under
//     g_gtk_retained_popups, and onPopupAdoptable is fired.
//   Phase 2 (here): the application's WebViewComponent.adoptPopup peer attach
//     calls webview_embed_adopt_popup -> gtk_adopt_popup, which claims the
//     retained child (adopt-once), builds a normal heavyweight Engine for the
//     new parent that REUSES the child web view (via gtk_create_engine's
//     existing_web parameter), transfers the inherited callbacks, and frees the
//     PopupEngine shell.  gtk_discard_popup is the reclaim path for a child that
//     was decided ADOPT but never adopted.
//
// ON-DEVICE VALIDATION REQUIRED (no GTK toolchain in the generating sandbox):
//   * the X11 XReparentWindow of the reused child under the new AWT canvas
//     window (performed inside gtk_create_engine);
//   * the widget ownership handoff — our retained g_object_ref_sink ref, the
//     new window's container ref, and WebKit's own reference on the child;
//   * whether the child's in-flight POST navigation continues to render after
//     being reparented from windowless to the adopting surface.
// ---------------------------------------------------------------------------
static Engine *gtk_adopt_popup(JNIEnv *env, jobject parent, jlong popupId,
                               jint debug) {
    // Claim the retained child (adopt-once): remove under lock so a second
    // adopt of the same id finds nothing and returns null (-> JNI 0 -> Java
    // IllegalStateException).
    PopupEngine *pe = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_gtk_retained_popups_mutex);
        auto it = g_gtk_retained_popups.find(popupId);
        if (it == g_gtk_retained_popups.end()) return nullptr;
        pe = it->second;
        g_gtk_retained_popups.erase(it);
    }
    if (!pe || !pe->web) {
        // Nothing usable to adopt; drop the empty shell if present.
        if (pe) delete pe;
        return nullptr;
    }

    GtkWidget *childw = GTK_WIDGET(pe->web);

    // Disconnect the child's PopupEngine-scoped signal handlers (create /
    // script-dialog / run-file-chooser / close — ready-to-show was never
    // connected for an ADOPT child) BEFORE the engine reconnects its own
    // engine-scoped handlers, so each signal has exactly one handler and none
    // dereferences the PopupEngine we are about to free.
    GtkPump::instance().run_sync([&] {
        g_signal_handlers_disconnect_by_data(pe->web, pe);
    });

    // Build the heavyweight engine reusing the retained child web view.  This
    // does the JAWT lock, XReparent, window creation, frame-clock, and fresh
    // engine-scoped signal wiring — identical to a normal create except the web
    // view is reused rather than allocated.  gtk_create_engine's epilogue
    // gtk_container_add takes a container ref on the child.
    Engine *e = gtk_create_engine(env, parent, debug, childw);
    if (!e) {
        // Attach failed (e.g. JAWT lock / non-X11 surface).  Reconnect the
        // PopupEngine handlers and re-retain so the child is reclaimable and
        // not lost; report failure to Java (0 -> IllegalStateException).
        GtkPump::instance().run_sync([&] {
            g_signal_connect(pe->web, "create",
                             (GCallback)on_create_web_view_popup, pe);
            g_signal_connect(pe->web, "script-dialog",
                             (GCallback)on_script_dialog_popup, pe);
            g_signal_connect(pe->web, "run-file-chooser",
                             (GCallback)on_run_file_chooser_popup, pe);
            g_signal_connect(pe->web, "close",
                             (GCallback)on_close_popup, pe);
        });
        std::lock_guard<std::mutex> lk(g_gtk_retained_popups_mutex);
        g_gtk_retained_popups[popupId] = pe;
        return nullptr;
    }

    // Transfer the inherited popup / dialog callbacks from the shell to the
    // engine so nested popups + dialogs from the adopted view keep working
    // immediately.  These are strong JNI global refs; ownership moves to the
    // engine (nulled on pe so delete pe does not double-free, and freed later
    // by gtk_destroy_engine or overwritten by the component's own
    // gtk_set_popup_callback / gtk_set_dialog_callback at attach).
    e->popup_callback = pe->popup_callback;
    pe->popup_callback = nullptr;
    // Canvas 21 (1.5.0): the resolver rides along with the callbacks so an
    // adopted popup keeps resolving its own children's UAs until the
    // component's attach installs its own.
    e->ua_resolver = pe->ua_resolver;
    pe->ua_resolver = nullptr;
    e->dialog_callback = pe->dialog_callback;
    pe->dialog_callback = nullptr;
    // The adopted child keeps the DownloadSink it was created with, so an
    // in-flight download keeps reporting to the handler that approved it
    // rather than silently changing owner mid-transfer (Canvas 24).  The
    // inherited ref moves onto the engine here so its teardown frees it;
    // the component's own gtk_set_download_callback at attach replaces
    // both it and the sink's.
    e->download_callback = pe->download_callback;
    pe->download_callback = nullptr;

    // Drop the retained strong reference taken by the ADOPT branch's
    // g_object_ref_sink — the engine's window now holds a container ref on the
    // child.  Runs on the GTK thread for refcount-thread-safety.
    GtkPump::instance().run_sync([&] {
        g_object_unref(childw);
    });

    delete pe;
    return e;
}

// Canvas 19: discard a retained-but-unadopted popup child (the ADOPT reclaim
// path — opener disposed or grace period elapsed).  Tears the child down
// WITHOUT ever showing a window; silent no-op for an unknown popupId.  Mirrors
// on_close_popup minus the window: notify onPopupClosed, destroy the widget,
// drop our retained ref, free the inherited global refs, delete the shell.
static void gtk_discard_popup(jlong popupId) {
    PopupEngine *pe = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_gtk_retained_popups_mutex);
        auto it = g_gtk_retained_popups.find(popupId);
        if (it == g_gtk_retained_popups.end()) return;
        pe = it->second;
        g_gtk_retained_popups.erase(it);
    }
    if (!pe) return;

    const char *url = pe->web ? webkit_web_view_get_uri(pe->web) : "";
    fire_gtk_popup_closed(pe->jvm, pe->popup_callback, pe->dialog_callback,
                          pe->popup_id, url ? url : "", "");

    GtkPump::instance().run_sync([&] {
        if (pe->web) {
            // No window owns this child (ADOPT is windowless); disconnect its
            // handlers, destroy the widget directly, then drop the strong ref
            // we took via g_object_ref_sink in handle_create_web_view.
            g_signal_handlers_disconnect_by_data(pe->web, pe);
            gtk_widget_destroy(GTK_WIDGET(pe->web));
            g_object_unref(pe->web);
            pe->web = nullptr;
        }
    });

    JNIEnv *env = nullptr;
    bool detach = false;
    if (pe->jvm && pe->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (pe->jvm->AttachCurrentThread((void **)&env, nullptr) == JNI_OK)
            detach = true;
    }
    if (env) {
        if (pe->popup_callback) env->DeleteGlobalRef(pe->popup_callback);
        if (pe->dialog_callback) env->DeleteGlobalRef(pe->dialog_callback);
        // Clear the child view's DownloadSink before its inherited ref
        // goes, so an in-flight download stops firing into Java
        // (Canvas 24).
        if (pe->web) gtk_clear_download_sink(GTK_WIDGET(pe->web), env);
        if (pe->download_callback) env->DeleteGlobalRef(pe->download_callback);
        if (pe->ua_resolver) env->DeleteGlobalRef(pe->ua_resolver);
        if (detach) pe->jvm->DetachCurrentThread();
    }
    pe->popup_callback = nullptr;
    pe->dialog_callback = nullptr;
    pe->ua_resolver = nullptr;
    pe->download_callback = nullptr;
    delete pe;
}

// ===========================================================================
// Linux / GTK lightweight (offscreen) engine
//
// Renders the WebKitWebView into the engine's hidden popup toplevel, which
// never touches the user's screen (Canvas 33 D2: a GTK_WINDOW_POPUP placed
// outside every monitor, not a GtkOffscreenWindow -- WebKitGTK never counts
// a view in an offscreen window as "in a window", so media never starts).  Java polls the latest pixels via JNI and blits them
// into a JComponent itself.  The whole heavyweight AWT/X11/GTK focus and
// frame-clock circus is bypassed -- we own the paint cycle, Java owns the
// AWT event flow.
// ===========================================================================

struct OffEngine {
    GtkWidget *window = nullptr;    // hidden popup toplevel (Canvas 33)
    GtkWidget *web = nullptr;       // WebKitWebView
    WebKitUserContentManager *manager = nullptr;
    int width = 1;
    int height = 1;
    bool debug = false;
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;

    // JNI global ref to the registered WebViewDialogCallback, or nullptr.
    // Set by gtk_off_set_dialog_callback; cleared in gtk_off_destroy_engine.
    // Invoked by the script-dialog / run-file-chooser signal handlers
    // installed in gtk_off_create_engine, which route through the shared
    // handle_script_dialog / handle_run_file_chooser inner functions
    // declared in the heavyweight engine block above.
    jobject dialog_callback = nullptr;

    // JNI global ref to the registered WebViewDownloadCallback, or
    // nullptr.  Set by gtk_off_set_download_callback; cleared in
    // gtk_off_destroy_engine.  Same contract as the heavyweight
    // engine's -- Canvas 24.
    jobject download_callback = nullptr;

    // JNI global ref to the registered WebViewPopupCallback, or nullptr.
    // Set by gtk_off_set_popup_callback; cleared in gtk_off_destroy_engine.
    // Invoked by the `create` signal handler installed in
    // gtk_off_create_engine, which routes through the shared
    // handle_create_web_view inner function (Canvas 16).
    jobject popup_callback = nullptr;
    // Canvas 34 D7: the WebViewNavigationCallback global ref, or nullptr.
    // Set by gtk_off_set_navigation_callback; freed in
    // gtk_off_destroy_engine.
    jobject navigation_callback = nullptr;
    // Canvas 21 (1.5.0): per-destination User-Agent resolver
    // (java.util.function.Function<String,String>) as a JNI global ref.
    // Consulted at the popup-child creation site with the CHILD's target
    // URL; a decline falls back to copying the opener's UA.  Deleted on
    // replacement and on engine destroy.
    jobject ua_resolver = nullptr;

    // JNI global ref to the registered WebViewPasswordCallback, or nullptr
    // (Canvas 27).  Set by the offscreen password-callback setter; cleared
    // in gtk_off_destroy_engine.  Invoked by the "__webview_pw__"
    // script-message handler installed in gtk_off_create_engine.
    jobject password_callback = nullptr;
};

// Per-OffEngine wrapper for the `script-dialog` signal.  Reads page URL,
// jvm, and dialog_callback from the lightweight OffEngine struct and
// delegates to the shared handle_script_dialog.  Mirrors
// on_script_dialog_engine — single divergence is the user_data cast type.
// Per the canvas Safeguards: behaviour MUST be byte-identical to the
// heavyweight variant, which is achieved by sharing handle_script_dialog.
static gboolean on_script_dialog_off_engine(WebKitWebView *web,
                                            WebKitScriptDialog *dialog,
                                            gpointer user_data) {
    OffEngine *e = static_cast<OffEngine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_script_dialog(e->jvm, e->dialog_callback, uri, dialog);
}

static gboolean on_run_file_chooser_off_engine(
        WebKitWebView *web, WebKitFileChooserRequest *request,
        gpointer user_data) {
    OffEngine *e = static_cast<OffEngine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_run_file_chooser(e->jvm, e->dialog_callback, uri, request);
}

// Per-OffEngine wrapper for the `create` signal (lightweight).  Delegates to
// the shared handle_create_web_view with the OffEngine's callbacks (Canvas 16).
static GtkWidget *on_create_web_view_off_engine(WebKitWebView *web,
        WebKitNavigationAction *nav, gpointer user_data) {
    OffEngine *e = static_cast<OffEngine *>(user_data);
    if (!e) return NULL;
    return handle_create_web_view(e->jvm, e->popup_callback,
                                  e->dialog_callback, e->ua_resolver,
                                  web, nav);
}

// Canvas 34 D7: per-OffEngine wrapper for "decide-policy".
static gboolean on_decide_policy_off_engine(WebKitWebView *web,
        WebKitPolicyDecision *decision, WebKitPolicyDecisionType type,
        gpointer user_data) {
    OffEngine *e = static_cast<OffEngine *>(user_data);
    if (!e) return FALSE;
    return gtk_decide_navigation(e->jvm, e->navigation_callback, web,
                                 decision, type);
}

// Parallel of engine_on_message for OffEngine: parse the {name, seq, args}
// envelope produced by window.external.invoke / the bind shim, look the
// binding up by name, and forward the raw JSON payload to the Java
// callback's WebViewNativeCallback.invoke(String, long).
static void off_engine_on_message(OffEngine *e, const char *msg) {
    if (msg == nullptr) return;
    std::string s(msg);
    auto pos = s.find("\"name\":\"");
    if (pos == std::string::npos) return;
    auto start = pos + 8;
    auto end = s.find('"', start);
    if (end == std::string::npos) return;
    std::string name = s.substr(start, end - start);
    auto it = e->bindings.find(name);
    if (it == e->bindings.end()) return;
    Binding *b = it->second;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        e->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    jmethodID mid = env->GetMethodID(b->cls, "invoke", "(Ljava/lang/String;J)V");
    if (mid) {
        jstring js = env->NewStringUTF(msg);
        env->CallVoidMethod(b->fn, mid, js, (jlong)e);
        env->DeleteLocalRef(js);
    }
    if (detach) e->jvm->DetachCurrentThread();
}

// Canvas 19 (lightweight/offscreen coverage): when `existing_web` is non-null
// (the offscreen popup-adoption path) the engine REUSES that already-created
// WebKitWebView instead of allocating a fresh one — preserving its in-flight
// POST navigation + window.opener linkage — while still building the
// hidden popup toplevel (Canvas 33) and performing the identical external-message / dialog /
// popup / IM-disable / focus-synth / container / show wiring.  `existing_web`
// must carry no GTK parent (the ADOPT branch never adds it to a container);
// gtk_container_add below adopts it into the offscreen window.  Mirrors
// gtk_create_engine's existing_web reuse.  The caller (gtk_off_adopt_popup)
// has already disconnected the child's old PopupEngine-scoped signal handlers
// so the fresh OffEngine-scoped handlers connected below are the only ones.
static OffEngine *gtk_off_create_engine(JNIEnv *env,
                                        int width, int height, jint debug,
                                        GtkWidget *existing_web = nullptr,
                                        bool standalone = false) {
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    auto *e = new OffEngine();
    env->GetJavaVM(&e->jvm);
    e->width = width;
    e->height = height;
    e->debug = debug != 0;

    bool ok = false;
    GtkPump::instance().run_sync([&] {
        // Canvas 33 D1/D2: a popup (override-redirect on X11, the only
        // backend the pump allows) placed far outside every monitor, rather
        // than a GtkOffscreenWindow.  WebKitGTK only lets a page start media
        // once its view is in an on-screen toplevel, and never counts an
        // offscreen window as one; this window is mapped but never visible,
        // undecorated, unfocusable and out of the taskbar.
        e->window = gtk_window_new(standalone ? GTK_WINDOW_TOPLEVEL : GTK_WINDOW_POPUP);
        if (!standalone) {
            gtk_window_move(GTK_WINDOW(e->window), -32000, -32000);
            gtk_window_set_accept_focus(GTK_WINDOW(e->window), FALSE);
            gtk_window_set_focus_on_map(GTK_WINDOW(e->window), FALSE);
            gtk_window_set_skip_taskbar_hint(GTK_WINDOW(e->window), TRUE);
            gtk_window_set_skip_pager_hint(GTK_WINDOW(e->window), TRUE);
            gtk_window_set_decorated(GTK_WINDOW(e->window), FALSE);
        }
        // Canvas 19: reuse the retained popup child (adoption) or create fresh.
        // The reused child already carries its opener linkage + in-flight POST
        // navigation from handle_create_web_view.
        // Canvas 31 D2: custom schemes go on the default context before the
        // first view exists (a no-op once done, or when none are registered).
        gtk_install_schemes_once();
        e->web = existing_web ? existing_web : webkit_web_view_new();
        e->manager =
            webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(e->web));

        // Software compositing -- same rationale as the heavyweight engine:
        // hardware-accelerated paths assume on-screen surfaces, and we are
        // offscreen by design.
        WebKitSettings *s =
            webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
        webkit_settings_set_hardware_acceleration_policy(
            s, WEBKIT_HARDWARE_ACCELERATION_POLICY_NEVER);
        if (e->debug) {
            webkit_settings_set_enable_developer_extras(s, TRUE);
            webkit_settings_set_enable_write_console_messages_to_stdout(s, TRUE);
        }

        // Wire up the "external" script-message channel exactly as the
        // embed path does (see gtk_create_engine lines 553-573).  This
        // is what makes window.external.invoke -- and therefore the
        // bind-shim's webview_offscreen_bind plumbing and the
        // ConsoleDispatcher console capture -- work on the lightweight
        // component.
        g_signal_connect(
            e->manager, "script-message-received::external",
            G_CALLBACK(+[](WebKitUserContentManager *,
                           WebKitJavascriptResult *r, gpointer arg) {
                auto *eng = static_cast<OffEngine *>(arg);
                JSCValue *v = webkit_javascript_result_get_js_value(r);
                char *str = jsc_value_to_string(v);
                off_engine_on_message(eng, str);
                g_free(str);
            }),
            e);
        webkit_user_content_manager_register_script_message_handler(
            e->manager, "external");

        // Password-manager channel (Canvas 27) -- same handler as the
        // heavyweight engine, routed through OffEngine.  Registered once.
        g_signal_connect(
            e->manager, "script-message-received::__webview_pw__",
            G_CALLBACK(+[](WebKitUserContentManager *,
                           WebKitJavascriptResult *r, gpointer arg) {
                auto *eng = static_cast<OffEngine *>(arg);
                JSCValue *v = webkit_javascript_result_get_js_value(r);
                char *s = jsc_value_to_string(v);
                gtk_handle_pw_message(eng, s);
                g_free(s);
            }),
            e);
        webkit_user_content_manager_register_script_message_handler(
            e->manager, "__webview_pw__");

        // Wire JS-initiated dialogs to the per-engine Java DialogDispatcher.
        // Same shape as gtk_create_engine; the offscreen variants of the
        // signal handlers route through OffEngine instead of Engine but
        // call the same inner handle_script_dialog /
        // handle_run_file_chooser logic.
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "script-dialog",
                         (GCallback)on_script_dialog_off_engine, e);
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "run-file-chooser",
                         (GCallback)on_run_file_chooser_off_engine, e);
        // window.open / target=_blank -> native popup window (Canvas 16).
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "create",
                         (GCallback)on_create_web_view_off_engine, e);
        // Canvas 34 D7: the navigation decision, as for Engine.
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "decide-policy",
                         (GCallback)on_decide_policy_off_engine, e);

        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(
                "window.external={invoke:function(s){"
                "window.webkit.messageHandlers.external.postMessage(s);}};",
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL));

        // Opaque white background so transparent / empty regions don't
        // surface premultiplied artefacts when blitted into Swing.
        GdkRGBA white = {1.0, 1.0, 1.0, 1.0};
        webkit_web_view_set_background_color(
            WEBKIT_WEB_VIEW(e->web), &white);

        // Disable WebKit's input method context entirely.  In our
        // offscreen embed all input arrives synthesized from Java
        // AWT, so there is nothing for an IM to compose; leaving an
        // IM in place was observed to swallow special keys -- ibus
        // / fcitx / even gtk-im-context-simple committed the
        // control character of Backspace (0x08) and Delete (0x7F)
        // as text input, leaving the field with no visible change
        // (BS) or a block glyph (DEL) instead of triggering the
        // DeleteBackward / DeleteForward editor commands.
        // Disabling IM forces every key event through WebKit's
        // editor-command lookup, which maps Backspace etc. correctly.
        if (!standalone) webkit_web_view_set_input_method_context(
            WEBKIT_WEB_VIEW(e->web), NULL);

        // Install the window.external.invoke shim -- the bind shim's
        // envelope is identical to the heavyweight engine, so bindings
        // stay the single source of truth for the window.<name>(...)
        // contract across both modes.
        //
        // The "external" channel itself is connected ABOVE and must not be
        // connected again here: g_signal_connect is additive, so a second
        // connection on the same manager delivers every posted message to
        // Java twice (see Canvas 7 Safeguards).
        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(
                "window.external={invoke:function(s){"
                "window.webkit.messageHandlers.external.postMessage(s);}};",
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL));

        gtk_widget_set_size_request(e->web, standalone ? 640 : e->width, standalone ? 560 : e->height);
        gtk_container_add(GTK_CONTAINER(e->window), e->web);
        gtk_window_set_default_size(GTK_WINDOW(e->window),
                                    e->width, e->height);
        gtk_widget_show_all(e->window);

        // Tell WebKit it has focus so input fields can show carets
        // and the page is treated as "active" for compositing.  GTK
        // normally only emits focus-in-event when the toplevel
        // GtkWindow gains WM focus -- which never happens for our
        // offscreen toplevel -- so without this synthetic event
        // WebKit thinks the page is permanently inactive and skips
        // caret painting.
        gtk_widget_grab_focus(e->web);
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (gw) {
            GdkEvent *fe = gdk_event_new(GDK_FOCUS_CHANGE);
            fe->focus_change.window = (GdkWindow *)g_object_ref(gw);
            fe->focus_change.send_event = TRUE;
            fe->focus_change.in = TRUE;
            gtk_main_do_event(fe);
            gdk_event_free(fe);
        }

        ok = (e->web != nullptr && e->window != nullptr);
    });
    if (!ok) {
        delete e;
        return nullptr;
    }
    EMBED_LOG(
        "[webview-embed] offscreen engine ready (%dx%d)\n",
        e->width, e->height);
    return e;
}

static void gtk_off_destroy_engine(OffEngine *e) {
    if (!e) return;
    // Canvas 34 D7: stop asking, then drop the navigation callback, before
    // anything else is torn down: a decision arriving mid-teardown must not
    // reach a freed engine or a freed ref.
    if (e->web) {
        g_signal_handlers_disconnect_by_func(G_OBJECT(e->web),
            (gpointer)on_decide_policy_off_engine, e);
    }
    if (e->navigation_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->navigation_callback);
        e->navigation_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Canvas 21 (1.5.0): drop the User-Agent resolver's global ref.  Only the
    // popup-child creation path reads it, but a late popup during teardown
    // would follow a freed ref, so it goes with the other callbacks.
    if (e->ua_resolver) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Drop the download-callback global ref and the view's DownloadSink
    // BEFORE the widget is destroyed (Canvas 24) -- same ordering rule
    // as the heavyweight engine.
    if (e->download_callback || e->web) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) {
            gtk_clear_download_sink(e->web, env);
            if (e->download_callback) env->DeleteGlobalRef(e->download_callback);
        }
        e->download_callback = nullptr;
        if (detach && e->jvm) e->jvm->DetachCurrentThread();
    }
    GtkPump::instance().run_sync([&] {
        if (e->window) {
            gtk_widget_destroy(e->window);
            e->window = nullptr;
            e->web = nullptr;
        }
    });
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
    // Same treatment for the popup-callback global ref (Canvas 16).
    if (e->popup_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the password-callback global ref (Canvas 27).
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
    delete e;
}

// Register (or clear, when cb is null) the Java WebViewDialogCallback
// for this offscreen engine.  STORY-004-001 stores the global ref but
// does NOT install the WebKitGTK signal handlers; STORY-004-002 wires
// the script-dialog + run-file-chooser handlers on the offscreen
// WebKitWebView.
// Register (or clear, when cb is null) the Java WebViewDownloadCallback
// for an offscreen engine (Canvas 24).  Mirrors gtk_set_download_callback:
// the GTK signal model is identical in both modes, so both converge on the
// same DownloadSink + context-level `download-started` machinery.
static void gtk_off_set_download_callback(OffEngine *e, JNIEnv *env,
                                          jobject cb) {
    if (!e) return;
    if (e->download_callback) {
        env->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
    }
    if (cb) {
        e->download_callback = env->NewGlobalRef(cb);
    }
    gtk_install_download_sink(e->web, e->jvm, env, cb);
}

static void gtk_off_set_dialog_callback(OffEngine *e, JNIEnv *env,
                                        jobject cb) {
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

// Register (or clear, when cb is null) the Java WebViewPopupCallback for this
// offscreen engine (Canvas 16).  Mirrors gtk_off_set_dialog_callback.
static void gtk_off_set_popup_callback(OffEngine *e, JNIEnv *env,
                                       jobject cb) {
    if (!e) return;
    if (e->popup_callback) {
        env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
    }
    if (cb) {
        e->popup_callback = env->NewGlobalRef(cb);
    }
}

// Canvas 34 D5: offscreen counterpart to gtk_set_navigation_callback.
static void gtk_off_set_navigation_callback(OffEngine *e, JNIEnv *env,
                                            jobject cb) {
    if (!e) return;
    if (e->navigation_callback) {
        env->DeleteGlobalRef(e->navigation_callback);
        e->navigation_callback = nullptr;
    }
    if (cb) {
        e->navigation_callback = env->NewGlobalRef(cb);
    }
}

// Canvas 21: offscreen counterpart to gtk_set_user_agent.
static void gtk_off_set_user_agent(OffEngine *e, const char *ua) {
    if (!e || !e->web) return;
    WebKitSettings *s = webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
    if (s) webkit_settings_set_user_agent(s, ua);
}

// Canvas 21 (1.5.0): install/clear the per-destination User-Agent resolver.
// Held as a JNI global ref so it survives the setting call; the previous ref is
// released first.  Consulted only at the popup-child creation site (navigations
// Java drives are resolved on the Java side before navigate).
static void gtk_off_set_user_agent_resolver(OffEngine *e, JNIEnv *env, jobject r) {
    if (!e || !env) return;
    if (e->ua_resolver) {
        env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
    }
    if (r) {
        e->ua_resolver = env->NewGlobalRef(r);
    }
}

// Canvas 22: offscreen counterpart to gtk_clear_cache.
static void gtk_off_clear_cache(OffEngine *e) {
    if (!e || !e->web) return;
    WebKitWebContext *ctx = webkit_web_view_get_context(WEBKIT_WEB_VIEW(e->web));
    if (ctx) webkit_web_context_clear_cache(ctx);
}

// ---------------------------------------------------------------------------
// Canvas 19 (lightweight/offscreen coverage): popup adoption into a
// caller-supplied lightweight WebViewComponent's offscreen surface.  The
// offscreen twin of gtk_adopt_popup — same two-phase model, same shared
// g_gtk_retained_popups registry, same shared gtk_discard_popup reclaim.
//
//   Phase 1 (handle_create_web_view ADOPT branch, above): the opener-linked
//     child WebKitWebView is created windowless, retained under
//     g_gtk_retained_popups, and onPopupAdoptable is fired.  This is identical
//     whether the opener was heavyweight (Engine) or lightweight (OffEngine) —
//     both route `create` through the shared handle_create_web_view.
//   Phase 2 (here): the application's lightweight WebViewComponent.adoptPopup
//     peer attach calls webview_offscreen_adopt_popup -> gtk_off_adopt_popup,
//     which claims the retained child (adopt-once), builds a normal OffEngine
//     that REUSES the child web view inside a hidden popup toplevel (Canvas 33) (via
//     gtk_off_create_engine's existing_web parameter), transfers the inherited
//     callbacks, and frees the PopupEngine shell.
//
// ON-DEVICE VALIDATION REQUIRED (no GTK toolchain in the generating sandbox):
//   * the ownership/reparent handoff — the child, held windowless by
//     g_object_ref_sink, is gtk_container_add-ed into a fresh
//     popup toplevel (which takes a container ref); the retained ref is
//     then dropped.  Unlike the heavyweight adopt there is NO XReparentWindow
//     into a foreign on-screen X11 tree — the child lives in an offscreen
//     toplevel — but the refcount balance (offscreen window ref + WebKit's own
//     ref) still needs on-device confirmation (no premature finalization /
//     use-after-destroy).
//   * blit of a mid-navigation child — the reused child may be mid-POST
//     navigation when moved from windowless into the offscreen window; verify
//     the snapshot/blit pipeline (cairo surface -> BufferedImage at ~30Hz,
//     driven by the Java component repaint timer) begins producing correct
//     frames and does not race the reparent.
//   * the g_signal_handlers_disconnect_by_data handoff (no double `create`
//     handling, no dangling PopupEngine dereference).
// ---------------------------------------------------------------------------
static OffEngine *gtk_off_adopt_popup(JNIEnv *env, jlong popupId,
                                      jint width, jint height, jint debug) {
    // Claim the retained child (adopt-once): remove under lock so a second
    // adopt of the same id finds nothing and returns null (-> JNI 0 -> Java
    // IllegalStateException).  Shares the SAME registry the heavyweight
    // gtk_adopt_popup claims from — the opener may have been either engine.
    PopupEngine *pe = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_gtk_retained_popups_mutex);
        auto it = g_gtk_retained_popups.find(popupId);
        if (it == g_gtk_retained_popups.end()) return nullptr;
        pe = it->second;
        g_gtk_retained_popups.erase(it);
    }
    if (!pe || !pe->web) {
        // Nothing usable to adopt; drop the empty shell if present.
        if (pe) delete pe;
        return nullptr;
    }

    GtkWidget *childw = GTK_WIDGET(pe->web);

    // Disconnect the child's PopupEngine-scoped signal handlers (create /
    // script-dialog / run-file-chooser / close — ready-to-show was never
    // connected for an ADOPT child) BEFORE gtk_off_create_engine reconnects
    // its own OffEngine-scoped handlers, so each signal has exactly one
    // handler and none dereferences the PopupEngine we are about to free.
    GtkPump::instance().run_sync([&] {
        g_signal_handlers_disconnect_by_data(pe->web, pe);
    });

    // Build the offscreen engine reusing the retained child web view.  This
    // creates the hidden popup toplevel (Canvas 33), wires the external-message / dialog /
    // popup / IM-disable / focus-synth pipeline, and gtk_container_add-s the
    // child into the offscreen window (taking a container ref) — identical to
    // a normal offscreen create except the web view is reused rather than
    // allocated.
    OffEngine *e =
        gtk_off_create_engine(env, (int)width, (int)height, debug, childw);
    if (!e) {
        // Create failed.  Reconnect the PopupEngine handlers and re-retain so
        // the child is reclaimable and not lost; report failure to Java
        // (0 -> IllegalStateException).  Mirrors gtk_adopt_popup's failure
        // path exactly.
        GtkPump::instance().run_sync([&] {
            g_signal_connect(pe->web, "create",
                             (GCallback)on_create_web_view_popup, pe);
            g_signal_connect(pe->web, "script-dialog",
                             (GCallback)on_script_dialog_popup, pe);
            g_signal_connect(pe->web, "run-file-chooser",
                             (GCallback)on_run_file_chooser_popup, pe);
            g_signal_connect(pe->web, "close",
                             (GCallback)on_close_popup, pe);
        });
        std::lock_guard<std::mutex> lk(g_gtk_retained_popups_mutex);
        g_gtk_retained_popups[popupId] = pe;
        return nullptr;
    }

    // Transfer the inherited popup / dialog callbacks from the shell to the
    // engine so nested popups + dialogs from the adopted view keep working
    // immediately.  These are strong JNI global refs; ownership moves to the
    // OffEngine (nulled on pe so delete pe does not double-free, and freed
    // later by gtk_off_destroy_engine or overwritten by the component's own
    // gtk_off_set_popup_callback / gtk_off_set_dialog_callback at attach).
    e->popup_callback = pe->popup_callback;
    pe->popup_callback = nullptr;
    // Canvas 21 (1.5.0): the resolver rides along with the callbacks so an
    // adopted popup keeps resolving its own children's UAs until the
    // component's attach installs its own.
    e->ua_resolver = pe->ua_resolver;
    pe->ua_resolver = nullptr;
    e->dialog_callback = pe->dialog_callback;
    pe->dialog_callback = nullptr;
    // The adopted child keeps the DownloadSink it was created with, so an
    // in-flight download keeps reporting to the handler that approved it
    // rather than silently changing owner mid-transfer (Canvas 24).  The
    // inherited ref moves onto the engine here so its teardown frees it;
    // the component's own gtk_set_download_callback at attach replaces
    // both it and the sink's.
    e->download_callback = pe->download_callback;
    pe->download_callback = nullptr;

    // Drop the retained strong reference taken by the ADOPT branch's
    // g_object_ref_sink — the offscreen window now holds a container ref on
    // the child.  Runs on the GTK thread for refcount-thread-safety.
    GtkPump::instance().run_sync([&] {
        g_object_unref(childw);
    });

    delete pe;
    return e;
}

static void gtk_off_init_script(OffEngine *e, std::string js) {
    GtkPump::instance().run_async([=] {
        if (!e->manager) return;
        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(js.c_str(),
                                   WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                                   WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                                   NULL, NULL));
    });
}

static void gtk_off_eval(OffEngine *e, std::string js) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        webkit_web_view_run_javascript(WEBKIT_WEB_VIEW(e->web), js.c_str(),
                                       NULL, NULL, NULL);
    });
}

static void gtk_off_bind(OffEngine *e, Binding *b) {
    e->bindings[b->name] = b;
}

static void gtk_off_resize(OffEngine *e, int w, int h) {
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    GtkPump::instance().run_async([=] {
        if (!e->web || !e->window) return;
        e->width = w;
        e->height = h;
        gtk_widget_set_size_request(e->web, w, h);
        gtk_window_resize(GTK_WINDOW(e->window), w, h);
    });
}

static void gtk_off_navigate(OffEngine *e, std::string url) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        EMBED_LOG(
            "[webview-embed] offscreen load_uri: %s\n", url.c_str());
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(e->web), url.c_str());
    });
}

// Open the WebKitGTK Web Inspector for the offscreen WebView in a separate
// OS-level GtkWindow.  The inspector belongs to the WebView's own
// process and is unaffected by the host Swing window's visibility.
// Returns 1 on success, 0 if developer-extras is disabled or the
// inspector is unavailable.
static int gtk_off_open_devtools(OffEngine *e) {
    if (!e || !e->web) return 0;
    int result = 0;
    GtkPump::instance().run_sync([&] {
        if (!e->web) return;
        WebKitSettings *s =
            webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
        if (!s || !webkit_settings_get_enable_developer_extras(s)) return;
        WebKitWebInspector *insp =
            webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web));
        if (!insp) return;
        webkit_web_inspector_show(insp);
        result = 1;
    });
    return result;
}

// Execute Cut/Copy/Paste/SelectAll against the focused frame of the
// offscreen WebKitGTK widget.  Mirrors gtk_execute_editing_command for
// the heavyweight engine -- same switch-on-cmdId + GtkPump::run_async +
// webkit_web_view_execute_editing_command pattern, just over OffEngine.
//
// cmdId values are the EditingCommand contract: 1=CUT, 2=COPY, 3=PASTE,
// 4=SELECT_ALL.  Unknown cmdIds are silently dropped.
static void gtk_off_execute_editing_command(OffEngine *e, int cmdId) {
    if (!e || !e->web) return;
    const char *command = nullptr;
    switch (cmdId) {
        case 1: command = WEBKIT_EDITING_COMMAND_CUT;        break;
        case 2: command = WEBKIT_EDITING_COMMAND_COPY;       break;
        case 3: command = WEBKIT_EDITING_COMMAND_PASTE;      break;
        case 4: command = WEBKIT_EDITING_COMMAND_SELECT_ALL; break;
        default: return;
    }
    GtkPump::instance().run_async([e, command] {
        if (!e || !e->web) return;
        webkit_web_view_execute_editing_command(
            WEBKIT_WEB_VIEW(e->web), command);
    });
}

// Same idea for the heavyweight embed-path engine.  Lives here next to
// the offscreen variant so both implementations are visible side by side.
static int gtk_open_devtools(Engine *e) {
    if (!e || !e->web) return 0;
    int result = 0;
    GtkPump::instance().run_sync([&] {
        if (!e->web) return;
        WebKitSettings *s =
            webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
        if (!s || !webkit_settings_get_enable_developer_extras(s)) return;
        WebKitWebInspector *insp =
            webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web));
        if (!insp) return;
        webkit_web_inspector_show(insp);
        result = 1;
    });
    return result;
}

// Execute Cut/Copy/Paste/SelectAll on the focused frame of the embedded
// WebKitGTK widget.  Marshals to the GTK pump thread asynchronously; the
// editing-command primitive operates against the WebView's current focused
// frame internally, so we don't have to do focus-routing ourselves.
//
// cmdId values are the EditingCommand contract: 1=CUT, 2=COPY, 3=PASTE,
// 4=SELECT_ALL.  Unknown cmdIds are silently dropped.
static void gtk_execute_editing_command(Engine *e, int cmdId) {
    if (!e || !e->web) return;
    const char *command = nullptr;
    switch (cmdId) {
        case 1: command = WEBKIT_EDITING_COMMAND_CUT;        break;
        case 2: command = WEBKIT_EDITING_COMMAND_COPY;       break;
        case 3: command = WEBKIT_EDITING_COMMAND_PASTE;      break;
        case 4: command = WEBKIT_EDITING_COMMAND_SELECT_ALL; break;
        default: return;
    }
    GtkPump::instance().run_async([e, command] {
        if (!e || !e->web) return;
        webkit_web_view_execute_editing_command(
            WEBKIT_WEB_VIEW(e->web), command);
    });
}

// Mouse event injection.  Java listeners on WebViewLightweightComponent
// translate AWT MouseEvents into these calls; we synthesize a GdkEvent
// and dispatch through gtk_main_do_event, which routes through GTK's
// normal event pipeline so WebKit sees them just like a real click.
static void gtk_off_mouse_button(OffEngine *e, bool press,
                                 int x, int y, int button, int modifiers,
                                 int click_count) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *pointer = seat ? gdk_seat_get_pointer(seat) : nullptr;

        GdkEventType type;
        if (press) {
            type = (click_count >= 3) ? GDK_3BUTTON_PRESS
                 : (click_count == 2) ? GDK_2BUTTON_PRESS
                 :                       GDK_BUTTON_PRESS;
        } else {
            type = GDK_BUTTON_RELEASE;
        }

        GdkEvent *ev = gdk_event_new(type);
        ev->button.window = (GdkWindow *)g_object_ref(gw);
        ev->button.send_event = TRUE;
        ev->button.time = GDK_CURRENT_TIME;
        ev->button.x = x;
        ev->button.y = y;
        ev->button.x_root = x;
        ev->button.y_root = y;
        ev->button.state = (GdkModifierType)modifiers;
        ev->button.button = button;
        ev->button.device = pointer;
        if (pointer) gdk_event_set_device(ev, pointer);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

static void gtk_off_mouse_motion(OffEngine *e, int x, int y, int modifiers) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *pointer = seat ? gdk_seat_get_pointer(seat) : nullptr;

        GdkEvent *ev = gdk_event_new(GDK_MOTION_NOTIFY);
        ev->motion.window = (GdkWindow *)g_object_ref(gw);
        ev->motion.send_event = TRUE;
        ev->motion.time = GDK_CURRENT_TIME;
        ev->motion.x = x;
        ev->motion.y = y;
        ev->motion.x_root = x;
        ev->motion.y_root = y;
        ev->motion.state = (GdkModifierType)modifiers;
        ev->motion.is_hint = FALSE;
        ev->motion.device = pointer;
        if (pointer) gdk_event_set_device(ev, pointer);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

static void gtk_off_mouse_scroll(OffEngine *e, int x, int y,
                                 double dx, double dy, int modifiers) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *pointer = seat ? gdk_seat_get_pointer(seat) : nullptr;

        GdkEvent *ev = gdk_event_new(GDK_SCROLL);
        ev->scroll.window = (GdkWindow *)g_object_ref(gw);
        ev->scroll.send_event = TRUE;
        ev->scroll.time = GDK_CURRENT_TIME;
        ev->scroll.x = x;
        ev->scroll.y = y;
        ev->scroll.x_root = x;
        ev->scroll.y_root = y;
        ev->scroll.state = (GdkModifierType)modifiers;
        ev->scroll.direction = GDK_SCROLL_SMOOTH;
        ev->scroll.delta_x = dx;
        ev->scroll.delta_y = dy;
        ev->scroll.device = pointer;
        if (pointer) gdk_event_set_device(ev, pointer);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

// Key event injection.  Java KeyListeners on
// WebViewLightweightComponent translate AWT KeyEvents to a GDK
// keyval (via the GdkInput helper) and forward via these calls.
// We also focus the WebKitWebView once on first key press so GTK's
// internal focus chain agrees that this widget should receive the
// dispatched key event.
//
// hardware_keycode is derived from the keyval via the active keymap.
// WebKit's editor command lookup uses the keycode (combined with the
// group / level) to identify the physical key; with hardware_keycode
// left at 0 it falls into a "treat as character input" path and ends
// up inserting the Unicode of the keysym (0x08 for BackSpace, 0x7F
// for Delete) instead of executing DeleteBackward / DeleteForward.
static void gtk_off_key_event(OffEngine *e, bool press,
                              int keyval, int modifiers,
                              bool is_modifier_key) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *keyboard = seat ? gdk_seat_get_keyboard(seat) : nullptr;

        guint hwcode = 0;
        guint8 group = 0;
        GdkKeymap *km = gdk_keymap_get_for_display(display);
        if (km) {
            GdkKeymapKey *keys = nullptr;
            gint n_keys = 0;
            if (gdk_keymap_get_entries_for_keyval(km, (guint)keyval,
                                                  &keys, &n_keys)
                && n_keys > 0 && keys != nullptr) {
                hwcode = keys[0].keycode;
                group = (guint8)keys[0].group;
            }
            if (keys) g_free(keys);
        }

        if (!gtk_widget_has_focus(e->web)) {
            gtk_widget_grab_focus(e->web);
        }

        GdkEvent *ev =
            gdk_event_new(press ? GDK_KEY_PRESS : GDK_KEY_RELEASE);
        ev->key.window = (GdkWindow *)g_object_ref(gw);
        ev->key.send_event = TRUE;
        ev->key.time = GDK_CURRENT_TIME;
        ev->key.state = (GdkModifierType)modifiers;
        ev->key.keyval = (guint)keyval;
        ev->key.length = 0;
        ev->key.string = nullptr;
        ev->key.hardware_keycode = (guint16)hwcode;
        ev->key.group = group;
        ev->key.is_modifier = is_modifier_key ? 1 : 0;
        if (keyboard) gdk_event_set_device(ev, keyboard);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

// Copies the current contents of the offscreen window into the caller-
// supplied Java int[].  Pixels are CAIRO_FORMAT_ARGB32 -- 0xAARRGGBB per
// pixel on both little- and big-endian builds (matches Java
// BufferedImage TYPE_INT_ARGB).  The Java array must be at least w*h ints.
//
// Implementation note: we allocate our own image surface and ask the
// engine's hidden popup toplevel (Canvas 33) to draw into it via
// gtk_widget_draw.  This never reads the window's on-screen contents (it is
// off every monitor), and never relied on gtk_offscreen_window_get_surface,
// which was (transfer-none) and only populated after a frame-clock-driven
// draw that isn't reliably ticking on this path.  Drawing on demand into a
// surface we own sidesteps both lifetime and timing concerns.
static void gtk_off_snapshot_into(OffEngine *e, JNIEnv *env,
                                  jintArray dest, jint w, jint h) {
    if (!e || w < 1 || h < 1) return;
    jsize len = env->GetArrayLength(dest);
    if (len < (jsize)((size_t)w * (size_t)h)) return;

    // Default-fill the temp buffer with opaque white so any region not
    // actually drawn by WebKit shows up as expected background colour
    // rather than uninitialised memory.
    std::vector<uint32_t> tmp((size_t)w * (size_t)h, 0xFFFFFFFFu);

    GtkPump::instance().run_sync([&] {
        if (!e->window || !e->web) return;
        cairo_surface_t *dst = cairo_image_surface_create(
            CAIRO_FORMAT_ARGB32, w, h);
        if (!dst || cairo_surface_status(dst) != CAIRO_STATUS_SUCCESS) {
            if (dst) cairo_surface_destroy(dst);
            return;
        }
        cairo_t *cr = cairo_create(dst);
        if (cr && cairo_status(cr) == CAIRO_STATUS_SUCCESS) {
            gtk_widget_draw(e->window, cr);
        }
        if (cr) cairo_destroy(cr);

        cairo_surface_flush(dst);
        unsigned char *data = cairo_image_surface_get_data(dst);
        int stride = cairo_image_surface_get_stride(dst);
        if (data) {
            for (int y = 0; y < h; y++) {
                std::memcpy(tmp.data() + (size_t)y * (size_t)w,
                            data + (size_t)y * (size_t)stride,
                            (size_t)w * 4);
            }
        }
        cairo_surface_destroy(dst);
    });

    // Now safely on the calling thread: copy temp buffer into the Java
    // int[].  SetIntArrayRegion is thread-safe per the JNI spec.
    env->SetIntArrayRegion(dest, 0, (jsize)((size_t)w * (size_t)h),
                           reinterpret_cast<const jint *>(tmp.data()));
}

#endif // WEBVIEW_GTK

#ifdef WEBVIEW_COCOA
// =========================================================================
// macOS / Cocoa / WKWebView
// =========================================================================

static id objc_cls(const char *n) { return (id)objc_getClass(n); }
static SEL sel(const char *n) { return sel_registerName(n); }

// Modern macOS SDKs (Xcode 15+) declare objc_msgSend with no parameters even
// when OBJC_OLD_DISPATCH_PROTOTYPES is defined, and on ARM64 the variadic
// calling convention does not match the ABI used for struct-by-value
// arguments anyway.  Every call therefore has to go through a typed
// function-pointer cast.  msg<>() centralises that pattern.
template <typename Ret = id, typename... Args>
static inline Ret msg(id receiver, SEL selector, Args... args) {
    using Fn = Ret (*)(id, SEL, Args...);
    return reinterpret_cast<Fn>(objc_msgSend)(receiver, selector, args...);
}

// Struct-return-safe dispatch.  msg<>() above is only valid for selectors
// whose return value comes back in registers (and for passing struct-by-value
// *arguments*).  Selectors that *return* a struct larger than 16 bytes --
// notably NSRect / CGRect (32 bytes), e.g. -[NSView bounds] -- must NOT go
// through plain objc_msgSend on x86_64: the SysV ABI passes such returns via a
// hidden pointer in the first integer-argument register, which shifts self/cmd
// by one slot so the runtime dereferences the stack return-buffer as the
// receiver -- a SIGSEGV in objc_msgSend.  x86_64 must dispatch these through
// objc_msgSend_stret; arm64 has no objc_msgSend_stret and returns large
// structs via the x8 register, so plain objc_msgSend is correct there.  See
// the "ABI-correct Objective-C struct-return dispatch on macOS" norm in the
// heavyweight-embedding Canvas (issue #36).
template <typename Ret, typename... Args>
static inline Ret msg_stret(id receiver, SEL selector, Args... args) {
    using Fn = Ret (*)(id, SEL, Args...);
#if defined(__x86_64__)
    return reinterpret_cast<Fn>(objc_msgSend_stret)(receiver, selector, args...);
#else
    return reinterpret_cast<Fn>(objc_msgSend)(receiver, selector, args...);
#endif
}

static id ns_str(const char *s) {
    return msg(objc_cls("NSString"), sel("stringWithUTF8String:"), s);
}

// ---------------------------------------------------------------------------
// Canvas 30 D13: custom URL schemes on WKWebView.  One WKURLSchemeHandler class
// and one shared instance serve every scheme of every engine.  Pending tasks
// live in g_scheme_tasks (id -> retained task); start/stop and the response
// all run on AppKit main, and Java is only ever called from detached threads.
// ---------------------------------------------------------------------------
static std::map<long long, id> g_scheme_tasks;
static std::once_flag g_scheme_handler_once;
static id g_scheme_handler_instance = nil;

static std::string ns_utf8(id s) {
    if (!s) return std::string();
    const char *c = msg<const char *>(s, sel("UTF8String"));
    return c ? std::string(c) : std::string();
}

static void impl_scheme_start(id, SEL, id, id task) {
    long long rid = ++g_scheme_next_id;
    msg(task, sel("retain"));
    {
        std::lock_guard<std::mutex> lk(g_scheme_mutex);
        g_scheme_tasks[rid] = task;
    }
    id req = msg(task, sel("request"));
    std::string url = ns_utf8(msg(msg(req, sel("URL")), sel("absoluteString")));
    std::string method = ns_utf8(msg(req, sel("HTTPMethod")));
    std::vector<std::string> pairs;
    id fields = msg(req, sel("allHTTPHeaderFields"));
    if (fields) {
        id keys = msg(fields, sel("allKeys"));
        unsigned long n = msg<unsigned long>(keys, sel("count"));
        for (unsigned long i = 0; i < n; i++) {
            id k = msg<id, unsigned long>(keys, sel("objectAtIndex:"), i);
            id v = msg<id, id>(fields, sel("objectForKey:"), k);
            pairs.push_back(ns_utf8(k));
            pairs.push_back(ns_utf8(v));
        }
    }
    std::vector<unsigned char> body;
    id data = msg(req, sel("HTTPBody"));
    if (data) {
        unsigned long len = msg<unsigned long>(data, sel("length"));
        const unsigned char *bytes = msg<const unsigned char *>(data, sel("bytes"));
        if (bytes && len) {
            size_t take = std::min((size_t)len, kSchemeMaxRequestRead);
            body.assign(bytes, bytes + take);
        }
    } else {
        id stream = msg(req, sel("HTTPBodyStream"));
        if (stream) {
            msg<void>(stream, sel("open"));
            unsigned char buf[16384];
            while (body.size() < kSchemeMaxRequestRead) {
                long got = msg<long, unsigned char *, unsigned long>(
                    stream, sel("read:maxLength:"), buf, (unsigned long)sizeof(buf));
                if (got <= 0) break;
                body.insert(body.end(), buf, buf + got);
            }
            if (body.size() > kSchemeMaxRequestRead) body.resize(kSchemeMaxRequestRead);
            msg<void>(stream, sel("close"));
        }
    }
    std::thread([rid, method, url, pairs, body]() {
        scheme_upcall_request(rid, method, url, pairs, body, true);
    }).detach();
}

static void impl_scheme_stop(id, SEL, id, id task) {
    long long rid = 0;
    {
        std::lock_guard<std::mutex> lk(g_scheme_mutex);
        for (auto it = g_scheme_tasks.begin(); it != g_scheme_tasks.end(); ++it) {
            if (it->second == task) {
                rid = it->first;
                g_scheme_tasks.erase(it);
                break;
            }
        }
    }
    if (!rid) return;
    msg<void>(task, sel("release"));
    std::thread([rid]() { scheme_upcall_cancelled(rid); }).detach();
}

static id cocoa_scheme_handler() {
    std::call_once(g_scheme_handler_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewSchemeHandler", 0);
        if (c) {
            class_addProtocol(c, objc_getProtocol("WKURLSchemeHandler"));
            class_addMethod(c, sel("webView:startURLSchemeTask:"),
                            (IMP)impl_scheme_start, "v@:@@");
            class_addMethod(c, sel("webView:stopURLSchemeTask:"),
                            (IMP)impl_scheme_stop, "v@:@@");
            objc_registerClassPair(c);
        } else {
            c = (Class)objc_cls("WebviewSchemeHandler");
        }
        if (c) g_scheme_handler_instance = msg(msg((id)c, sel("alloc")), sel("init"));
    });
    return g_scheme_handler_instance;
}

// Set the shared handler for every installed scheme on a configuration that
// has not yet been used to create a WKWebView (D13).
static void cocoa_install_schemes(id config) {
    if (!config || g_scheme_names.empty()) return;
    id handler = cocoa_scheme_handler();
    if (!handler) return;
    for (const std::string &name : g_scheme_names) {
        msg<void, id, id>(config, sel("setURLSchemeHandler:forURLScheme:"), handler,
                          ns_str(name.c_str()));
    }
}

// Apply a response on AppKit main, if its task is still pending (D13).
static void cocoa_scheme_respond(long long rid, int status,
                                 std::vector<std::string> pairs,
                                 std::vector<unsigned char> body) {
    dispatch_async(dispatch_get_main_queue(), ^{
        id task = nil;
        {
            std::lock_guard<std::mutex> lk(g_scheme_mutex);
            auto it = g_scheme_tasks.find(rid);
            if (it == g_scheme_tasks.end()) return;
            task = it->second;
            g_scheme_tasks.erase(it);
        }
        id url = msg(msg(task, sel("request")), sel("URL"));
        id fields = msg(objc_cls("NSMutableDictionary"), sel("dictionary"));
        for (size_t i = 0; i + 1 < pairs.size(); i += 2) {
            id k = ns_str(pairs[i].c_str());
            id prev = msg<id, id>(fields, sel("objectForKey:"), k);
            std::string v = pairs[i + 1];
            if (prev) v = ns_utf8(prev) + ", " + v;
            msg<void, id, id>(fields, sel("setObject:forKey:"), ns_str(v.c_str()), k);
        }
        id resp = msg(objc_cls("NSHTTPURLResponse"), sel("alloc"));
        resp = msg<id, id, long, id, id>(resp,
            sel("initWithURL:statusCode:HTTPVersion:headerFields:"), url, (long)status,
            ns_str("HTTP/1.1"), fields);
        msg<void, id>(task, sel("didReceiveResponse:"), resp);
        if (!body.empty()) {
            id data = msg<id, const void *, unsigned long>(objc_cls("NSData"),
                sel("dataWithBytes:length:"), (const void *)body.data(),
                (unsigned long)body.size());
            msg<void, id>(task, sel("didReceiveData:"), data);
        }
        msg<void>(task, sel("didFinish"));
        if (resp) msg<void>(resp, sel("release"));
        msg<void>(task, sel("release"));
    });
}

struct Engine {
    id webview = nullptr;   // WKWebView
    id manager = nullptr;   // WKUserContentController
    id config = nullptr;
    bool debug = false;
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;

    // Reference back into the JAWT-provided SurfaceLayers; we need it on
    // destroy to clear the layer.
    id surface_layers = nullptr;
    // The NSView hosting the WKWebView as a real subview.  WKWebView's
    // CARemoteLayer-based rendering only engages once the view is part of
    // an NSView hierarchy that ends in an NSWindow.  On Corretto 8 macOS
    // arm64 (and OpenJDK builds with a similar layer-only design) there is
    // no per-Canvas AWT NSView; we attach to NSWindow.contentView and use
    // the caller-supplied AWT canvas position to set the WKWebView's
    // frame within it.
    id host_view = nullptr;
    // True when host_view is a per-Canvas AWT NSView whose bounds already
    // match the canvas (so WKWebView frame is just (0,0,w,h)).  False when
    // host_view is NSWindow.contentView and we have to honor the caller's
    // (x,y) and translate AWT top-left coords into Cocoa bottom-left.
    bool host_is_awt = false;

    // JNI global ref to the registered WebViewFocusCallback, or nullptr.
    // Invoked by the swizzled becomeFirstResponder / resignFirstResponder
    // implementations.
    jobject focus_callback = nullptr;

    // JNI global ref to the registered WebViewClickCallback, or nullptr.
    // Invoked by the swizzled mouseDown: / rightMouseDown: /
    // otherMouseDown: implementations after the original IMP has run, so
    // Swing can dismiss any open JPopupMenu when the user clicks into
    // the WebView -- AWT's MouseGrabber AWTEventListener cannot see
    // those clicks because the heavyweight peer receives them through
    // the AppKit responder chain rather than AWT's event queue.
    jobject click_callback = nullptr;

    // Mirrored first-responder state.  Written on the AppKit main thread
    // by the KVO observer callback on NSWindow.firstResponder (see
    // WebviewEmbedKvoObserver below); read lock-free from any thread by
    // cocoa_is_first_responder.  A stale read by at most one event-loop
    // tick is acceptable; a single bool admits no torn reads.
    std::atomic<bool> is_first_responder{false};

    // Destroyed flag.  Set true as the FIRST action inside the destroy
    // lambda (cocoa_destroy_engine), before any AppKit teardown runs.
    // Every other async-on-main lambda (navigate / eval / init_script /
    // set_bounds / set_visible / request_focus / execute_editing_command
    // / bind) reads this at fire time and short-circuits cleanly if true.
    // The primary correctness guarantee is dispatch-queue FIFO ordering +
    // EDT-only enqueueing; this flag is belt-and-suspenders against
    // (a) destroy-from-non-EDT, (b) future code changes that violate the
    // EDT-only-enqueue invariant, and (c) the cocoa_eval late-fire path
    // that needs to short-circuit if the engine is gone.
    std::atomic<bool> destroyed{false};

    // KVO observer wired against the host window's firstResponder key
    // path; lazily registered on the first non-nil window the WKWebView
    // sees, and re-registered if the WKWebView moves between windows at
    // runtime.  Owned by the engine (retained); released in the destroy
    // lambda before the WKWebView itself is released.
    id kvo_observer = nullptr;

    // Last NSWindow the KVO observer was registered against.  Used to
    // unregister cleanly during destroy or on window change.  Weak
    // (unretained) back-reference; AppKit owns the window's lifecycle.
    id observed_window = nullptr;

    // Attach-completion resolution state.  Coordinated between
    // (a) the AppKit-main-thread epilogue inside cocoa_create_engine,
    // which sets attach_resolved+attach_ok+attach_failure_message and
    // fires the callback if one is registered; and (b) the EDT-thread
    // cocoa_set_attach_callback, which stores the callback and fires
    // it immediately if attach is already resolved.  Guarded by
    // attach_callback_mutex.  The callback is fired exactly once: the
    // mutex serialises the "store callback + fire if resolved" and
    // "set resolved + fire if callback present" windows, and the
    // callback fields are cleared after firing.
    std::mutex attach_callback_mutex;
    bool attach_resolved = false;
    bool attach_ok = false;
    std::string attach_failure_message;
    jobject attach_callback = nullptr;
    jclass attach_callback_cls = nullptr;

    // JNI global ref to the registered WebViewDialogCallback, or
    // nullptr.  Invoked by the WKUIDelegate selectors below for each
    // JS-initiated alert / confirm / prompt and for <input type=file>
    // clicks.  The selector waits for the Java side's return value
    // (via DialogDispatcher's invokeAndWait EDT hop) before invoking
    // the platform's completion handler, which is what releases the
    // page's JS thread.  Cleared in cocoa_destroy_engine BEFORE the
    // ui_delegate is released so any in-flight selector reads a null
    // field instead of a freed ref.
    jobject dialog_callback = nullptr;

    // JNI global ref to the registered WebViewPasswordCallback, or
    // nullptr.  Read by the __webview_pw__ WKScriptMessageHandler on each
    // login-submission / fill-request message.  Set by
    // cocoa_set_password_callback; cleared in cocoa_destroy_engine.
    jobject password_callback = nullptr;

    // Per-engine WKUIDelegate instance assigned to e->webview via
    // setUIDelegate:.  Retained by us (we hold the only strong ref);
    // released in cocoa_destroy_engine after we clear the WKWebView's
    // uiDelegate property.
    id ui_delegate = nullptr;

    // JNI global ref to the registered WebViewPopupCallback, or nullptr.
    // Read by the createWebViewWithConfiguration: / webViewDidClose:
    // selectors to decide/notify popups.  For a child (popup) engine this
    // is inherited from the opener at creation so onPopupClosed can reach
    // the opener's PopupDispatcher.  Cleared before ui_delegate is
    // released.
    jobject popup_callback = nullptr;
    // Canvas 34 D8: JNI global ref to the WebViewNavigationCallback, or
    // nullptr.  Read by decidePolicyForNavigationAction on AppKit main;
    // set by cocoa_set_navigation_callback; freed in cocoa_destroy_engine.
    jobject navigation_callback = nullptr;
    // Canvas 21 (1.5.0): per-destination User-Agent resolver
    // (java.util.function.Function<String,String>) as a JNI global ref.
    // Consulted at the popup-child creation site with the CHILD's target
    // URL; a decline falls back to copying the opener's UA.  Deleted on
    // replacement and on engine destroy.
    jobject ua_resolver = nullptr;

    // For a child (popup) engine only: the NSWindow the engine created to
    // host the popup web view, and the opaque id correlating the
    // onPopupOpened / onPopupClosed pair.  nullptr / 0 on a normal engine.
    id popup_window = nullptr;
    jlong popup_id = 0;

    // JNI global ref to the registered WebViewDownloadCallback, or
    // nullptr.  Read by the WKDownloadDelegate selectors and by the
    // NSProgress KVO observer for each in-flight download.  A download
    // can outlive the page, the navigation, and plausibly the
    // component, so this is cleared in cocoa_destroy_engine BEFORE the
    // delegate is released -- a late event firing into a freed ref is
    // a SIGSEGV, not an exception.
    jobject download_callback = nullptr;

    // Monotonic per-engine download identity.  Several downloads may be
    // in flight at once and the destination file is neither known at
    // request time nor unique across sequential downloads, so identity
    // has to be minted here rather than derived from the path.
    std::atomic<long long> next_download_id{1};

    // True when a Java EmbeddedWebView owns this engine's lifecycle -- i.e.
    // Java calls webview_embed_destroy -> cocoa_destroy_engine exactly once
    // for it.  Set for every cocoa_create_engine engine and for any popup
    // child promoted to a real embedded engine by cocoa_adopt_popup.  A
    // browser-initiated window.close() (impl_web_view_did_close) MUST NOT
    // free a java_owned engine -- doing so double-frees it against the
    // Java-driven destroy (the objc_msgSend use-after-free observed on app
    // quit).  Left false for engine-owned native-window popups and
    // retained-but-unadopted popup children, whose sole owner is their
    // native close / discard path.
    bool java_owned = false;
};

// Defined with the rest of the download machinery further down; declared here
// because the popup-close path calls it from above that block (Canvas 23).
static void cocoa_download_drop_engine(Engine *e);

// Process-global map from WKWebView (id) to its owning Engine*.  Populated
// in cocoa_create_engine after WKWebView alloc; cleared in
// cocoa_destroy_engine.  Guarded by g_webview_map_mutex because the
// swizzled responder hooks fire on the AppKit main thread while
// create/destroy run on EDT-driven native code.
static std::mutex g_webview_map_mutex;
static std::map<id, Engine *> g_webview_map;

// Canvas 18 (popup adoption): retained-but-unadopted popup child engines,
// keyed by popup_id.  Populated by impl_create_web_view's ADOPT branch (which
// creates the opener-linked child but NO NSWindow), drained by
// cocoa_adopt_popup (reparent into a caller surface) or cocoa_discard_popup
// (reclaim).  Guarded by its own mutex; entries are Engine* also present in
// g_webview_map.  ON-DEVICE VALIDATION REQUIRED: this file has no native
// toolchain in the generating sandbox (see Canvas 18 Safeguards).
static std::mutex g_retained_popups_mutex;
static std::map<jlong, Engine *> g_retained_popups;

// The EDT↔AppKit-main synchronous bridge has been eliminated.  Per
// Canvas 6 Norms (the macOS sync EDT→AppKit-main bridge prohibition),
// every per-engine native operation runs via cocoa_run_on_main_async
// (FIFO dispatch_get_main_queue) or inlines when already on main; any
// AppKit-thread state the EDT needs to read is mirrored into an
// std::atomic on the Engine (see Engine::is_first_responder, fed by
// the KVO observer on NSWindow.firstResponder, walked below).  The
// historical scaffolding -- cocoa_run_on_main, WebViewAwtMainBridge,
// performWork:, ensure_awt_main_bridge, awt_bridge_box,
// awt_main_bridge_perform_impl, the g_awt_main_bridge_* statics, and
// the AwtBridgeWork heap-allocated work item -- has been removed.  Do
// NOT reintroduce any of them; the structural reason is documented in
// the canvas's "Eliminate Sync EDT↔AppKit Bridge" approach entry.

static void cocoa_run_on_main_async(std::function<void()> f) {
    BOOL is_main = msg<BOOL>(objc_cls("NSThread"), sel("isMainThread"));
    if (is_main) {
        f();
        return;
    }
    struct Holder { std::function<void()> f; };
    Holder *h = new Holder{std::move(f)};
    dispatch_async_f(dispatch_get_main_queue(), h, +[](void *p) {
        Holder *g = static_cast<Holder *>(p);
        g->f();
        delete g;
    });
}

static void engine_on_message(Engine *e, const char *msg) {
    if (!msg) return;
    std::string s(msg);
    auto pos = s.find("\"name\":\"");
    if (pos == std::string::npos) return;
    auto start = pos + 8;
    auto end = s.find('"', start);
    if (end == std::string::npos) return;
    std::string name = s.substr(start, end - start);
    auto it = e->bindings.find(name);
    if (it == e->bindings.end()) return;
    Binding *b = it->second;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        e->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    jmethodID mid = env->GetMethodID(b->cls, "invoke", "(Ljava/lang/String;J)V");
    if (mid) {
        jstring js = env->NewStringUTF(msg);
        env->CallVoidMethod(b->fn, mid, js, (jlong)e);
        env->DeleteLocalRef(js);
    }
    if (detach) e->jvm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------
// First-responder hook on WKWebView.
//
// We swizzle becomeFirstResponder / resignFirstResponder on the WKWebView
// class so we can mirror the native focus state back into Swing.  Swizzling
// is class-wide: it affects every WKWebView in the process, including any
// the host application created independently of this library.  That's
// fine because each swizzled implementation looks up the receiver in
// g_webview_map and silently no-ops if we don't own it.
//
// The Java callback is invoked via JNI on whatever thread AppKit drove
// the responder change on (typically the AppKit main thread).  Java-side
// callers MUST marshal to the EDT before touching Swing state -- the
// callback contract documents this explicitly.
// ---------------------------------------------------------------------------

typedef BOOL (*BoolFromIdSel)(id, SEL);
static BoolFromIdSel g_orig_becomeFirstResponder = nullptr;
static BoolFromIdSel g_orig_resignFirstResponder = nullptr;
static std::once_flag g_focus_swizzle_once;

// WKScriptMessageHandler delegate class used by every Cocoa engine for the
// window.external.invoke bridge.  objc_allocateClassPair returns Nil if the
// class name is already registered, so the allocation/registration MUST run
// exactly once per JVM -- the same pattern as g_focus_swizzle_once above.
// See issue #21 and the Canvas constraint on cocoa_create_engine.
static std::once_flag g_webview_embed_delegate_once;
static Class g_webview_embed_delegate_cls = nil;

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

static BOOL swizzled_become_first_responder(id self, SEL _cmd) {
    BOOL result = g_orig_becomeFirstResponder
        ? g_orig_becomeFirstResponder(self, _cmd)
        : NO;
    if (result) {
        Engine *eng = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_webview_map_mutex);
            auto it = g_webview_map.find(self);
            if (it != g_webview_map.end()) eng = it->second;
        }
        if (eng) fire_focus_callback(eng, true);
    }
    return result;
}

static BOOL swizzled_resign_first_responder(id self, SEL _cmd) {
    BOOL result = g_orig_resignFirstResponder
        ? g_orig_resignFirstResponder(self, _cmd)
        : NO;
    if (result) {
        Engine *eng = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_webview_map_mutex);
            auto it = g_webview_map.find(self);
            if (it != g_webview_map.end()) eng = it->second;
        }
        if (eng) fire_focus_callback(eng, false);
    }
    return result;
}

static void install_focus_swizzle() {
    std::call_once(g_focus_swizzle_once, [] {
        Class wk = (Class)objc_cls("WKWebView");
        if (!wk) return;
        Method become = class_getInstanceMethod(wk, sel("becomeFirstResponder"));
        Method resign = class_getInstanceMethod(wk, sel("resignFirstResponder"));
        if (!become || !resign) return;
        g_orig_becomeFirstResponder = (BoolFromIdSel)method_setImplementation(
            become, (IMP)swizzled_become_first_responder);
        g_orig_resignFirstResponder = (BoolFromIdSel)method_setImplementation(
            resign, (IMP)swizzled_resign_first_responder);
    });
}

static Class get_webview_embed_delegate_cls() {
    std::call_once(g_webview_embed_delegate_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedDelegate", 0);
        class_addProtocol(c, objc_getProtocol("WKScriptMessageHandler"));
        class_addMethod(
            c,
            sel("userContentController:didReceiveScriptMessage:"),
            (IMP)(+[](id self, SEL, id, id m) {
                Engine *eng = (Engine *)objc_getAssociatedObject(self, "eng");
                id body = msg(m, sel("body"));
                const char *s = msg<const char *>(body, sel("UTF8String"));
                engine_on_message(eng, s);
            }),
            "v@:@@");
        objc_registerClassPair(c);
        g_webview_embed_delegate_cls = c;
    });
    return g_webview_embed_delegate_cls;
}

// ---------------------------------------------------------------------------
// WKUIDelegate hook for JS-initiated dialogs.
//
// WKWebView consults its uiDelegate to know what to do for
// runJavaScriptAlertPanelWithMessage: / runJavaScriptConfirmPanel: /
// runJavaScriptTextInputPanel: / runOpenPanelWithParameters:.  If the
// uiDelegate is nil -- which is the default -- WKWebView SILENTLY DROPS
// these requests: alert() returns immediately, confirm() returns false,
// prompt() returns null, and <input type=file> clicks open no picker.
// Installing a uiDelegate that implements these selectors restores the
// expected JS behaviour and lets the host customise it via
// WebViewDialogHandler.
//
// Selectors are invoked on the AppKit main thread.  Each selector takes
// a completionHandler block that MUST be invoked exactly once to release
// the page's JavaScript thread; the dispatcher's invokeAndWait hop to
// the Swing EDT happens between selector entry and completion-handler
// invocation, so AppKit main is parked during the modal Swing dialog.
// That is the correct JS-contract behaviour: the page is frozen while
// the dialog is open.
//
// Class registration mirrors get_webview_embed_delegate_cls above
// (once-per-JVM call_once + objc_allocateClassPair + class_addMethod +
// objc_registerClassPair).
// ---------------------------------------------------------------------------
static std::once_flag g_webview_embed_ui_delegate_once;
static Class g_webview_embed_ui_delegate_cls = nil;

// Helper: convert an NSString (or nil) to a fresh jstring via UTF-8.
// Returns nullptr for nil input.  Caller is responsible for releasing
// the local ref via DeleteLocalRef when done.
static jstring ns_to_jstring(JNIEnv *env, id ns) {
    if (!ns) return nullptr;
    const char *cstr = msg<const char *>(ns, sel("UTF8String"));
    if (!cstr) return nullptr;
    return env->NewStringUTF(cstr);
}

// Helper: read the top-level page URL from the WKWebView.
static jstring page_url_jstring(JNIEnv *env, id webView) {
    if (!webView) return nullptr;
    id url = msg(webView, sel("URL"));
    if (!url) return nullptr;
    id abs = msg(url, sel("absoluteString"));
    return ns_to_jstring(env, abs);
}

// Helper: read the URL of the frame that initiated the dialog.  When
// frame is nil or its request has no URL, fall back to the page URL.
static jstring frame_url_jstring(JNIEnv *env, id frame, id webView) {
    if (frame) {
        id req = msg(frame, sel("request"));
        if (req) {
            id url = msg(req, sel("URL"));
            if (url) {
                id abs = msg(url, sel("absoluteString"));
                jstring js = ns_to_jstring(env, abs);
                if (js) return js;
            }
        }
    }
    return page_url_jstring(env, webView);
}

// UTF-8 std::string variants of the three helpers above.  Used by the
// WKUIDelegate IMPs to capture string inputs synchronously on AppKit
// main BEFORE launching the worker thread that does the JNI hop --
// std::string captures are thread-safe and don't require us to retain
// NSString instances across thread boundaries.
static std::string ns_string_to_utf8(id ns) {
    if (!ns) return std::string();
    const char *cstr = msg<const char *>(ns, sel("UTF8String"));
    return cstr ? std::string(cstr) : std::string();
}

static std::string page_url_utf8(id webView) {
    if (!webView) return std::string();
    id url = msg(webView, sel("URL"));
    if (!url) return std::string();
    id abs = msg(url, sel("absoluteString"));
    return ns_string_to_utf8(abs);
}

static std::string frame_url_utf8(id frame, id webView) {
    if (frame) {
        id req = msg(frame, sel("request"));
        if (req) {
            id url = msg(req, sel("URL"));
            if (url) {
                id abs = msg(url, sel("absoluteString"));
                std::string s = ns_string_to_utf8(abs);
                if (!s.empty()) return s;
            }
        }
    }
    return page_url_utf8(webView);
}

// Read an NSArray<NSString*> (or nil) into a std::vector<std::string>.
// Used by impl_run_open_panel to snapshot the mime / extension arrays
// on AppKit main before handing off to the worker thread.
static std::vector<std::string> ns_array_to_utf8_vector(id nsArray) {
    std::vector<std::string> out;
    if (!nsArray) return out;
    long count = msg<long>(nsArray, sel("count"));
    out.reserve((size_t)count);
    for (long i = 0; i < count; i++) {
        id ns = msg<id, long>(nsArray, sel("objectAtIndex:"), i);
        out.push_back(ns_string_to_utf8(ns));
    }
    return out;
}

// Helper: convert an NSArray<NSString*> (or nil) to a fresh
// jobjectArray of UTF-8 jstrings.  Returns an empty array for nil
// input -- never returns nullptr -- so the Java side can assume
// non-null arrays.  Caller is responsible for releasing the local ref.
static jobjectArray ns_array_to_jstring_array(JNIEnv *env, id nsArray) {
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    if (!nsArray) {
        jobjectArray empty = env->NewObjectArray(0, strCls, nullptr);
        env->DeleteLocalRef(strCls);
        return empty;
    }
    long count = msg<long>(nsArray, sel("count"));
    jobjectArray arr = env->NewObjectArray(
        (jsize)count, strCls, nullptr);
    for (long i = 0; i < count; i++) {
        id ns = msg<id, long>(nsArray, sel("objectAtIndex:"), i);
        jstring js = ns_to_jstring(env, ns);
        if (js) {
            env->SetObjectArrayElement(arr, (jsize)i, js);
            env->DeleteLocalRef(js);
        }
    }
    env->DeleteLocalRef(strCls);
    return arr;
}

// Ensure the current AppKit thread is attached to the JVM and return
// the JNIEnv.  Sets *attached to true when this call attached (the
// caller must detach before returning to AppKit).  Returns nullptr on
// failure -- caller must invoke the platform completion handler with
// the safe default.
static JNIEnv *ensure_jni_env(JavaVM *jvm, bool *attached) {
    *attached = false;
    if (!jvm) return nullptr;
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        *attached = true;
    }
    return env;
}

// IMP: -[WebviewEmbedUIDelegate webView:runJavaScriptAlertPanelWithMessage:
//                               initiatedByFrame:completionHandler:]
// ----- Deferral pattern (canvas-11 + bug-fix for the synchronous
// AppKit-main / EDT deadlock) -----------------------------------------
//
// WKUIDelegate selectors are invoked on the AppKit main thread.  Doing
// SwingUtilities.invokeAndWait directly from here deadlocks: the EDT
// shows a modal JOptionPane, which creates an NSWindow under the hood,
// which requires AppKit main thread work -- and AppKit main is blocked
// in invokeAndWait waiting for the EDT to return.  Classic.
//
// Fix: copy the completion handler block, return from the selector
// immediately, and run the JNI hop on a worker thread.  When Java
// returns the answer, dispatch_async back onto AppKit main to invoke
// the completion handler (WKWebView requires its completion handlers
// to fire on the thread they were delivered on -- AppKit main).  Page's
// JS thread stays suspended for the duration (the completion handler
// hasn't fired yet), which is exactly the JS-contract behaviour we want.
//
// Same deferral pattern Windows uses via GetDeferral + dispatch_to_thread
// (canvas-13).  Linux doesn't need it because the GTK pump thread is
// already decoupled from AWT's EDT thread.
//
// Block lifetime: blocks passed as ObjC method arguments are stack-
// allocated and become invalid once the selector returns.  Calling
// -copy moves the block to the heap and retains it; we balance with
// -release after we invoke (or skip invoking) it.
static void impl_run_alert(id self, SEL, id webView, id message,
                           id frame, id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");

    // Copy the completion handler block so it survives past selector
    // return.  See block-lifetime note above.
    id ch = msg(completionHandler, sel("copy"));

    // Capture string inputs synchronously while still on AppKit main;
    // std::string captures cross thread boundaries trivially without
    // having to retain NSStrings.
    std::string msg_utf8 = ns_string_to_utf8(message);
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        // No Java handler wired (yet, or after disposal).  Fire
        // completion synchronously with the safe default -- we're
        // already on AppKit main, no need for dispatch_async.
        ((void (^)(void))ch)();
        msg(ch, sel("release"));
        return;
    }

    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    // Hand off to a worker thread.  AppKit main returns immediately
    // so the EDT can use AppKit for the modal Swing dialog without
    // contention.  Detached thread because we never need to join.
    std::thread([jvm, cb, msg_utf8, page_url, frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            // JVM attach failed; can't call Java.  Still must fire
            // the completion handler so the page's JS thread resumes.
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(void))ch)();
                msg(ch, sel("release"));
            });
            return;
        }
        jstring jmsg = env->NewStringUTF(msg_utf8.c_str());
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onAlert",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            if (mid) {
                env->CallVoidMethod(cb, mid, jmsg, jpage, jframe);
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
        jvm->DetachCurrentThread();

        // Java returned.  Fire the completion handler on AppKit main
        // (WKWebView requires it) and release our copy of the block.
        dispatch_async(dispatch_get_main_queue(), ^{
            ((void (^)(void))ch)();
            msg(ch, sel("release"));
        });
    }).detach();
}

// IMP: -[WebviewEmbedUIDelegate webView:runJavaScriptConfirmPanelWithMessage:
//                               initiatedByFrame:completionHandler:]
static void impl_run_confirm(id self, SEL, id webView, id message,
                             id frame, id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    id ch = msg(completionHandler, sel("copy"));
    std::string msg_utf8 = ns_string_to_utf8(message);
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        ((void (^)(BOOL))ch)(NO);
        msg(ch, sel("release"));
        return;
    }
    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    std::thread([jvm, cb, msg_utf8, page_url, frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(BOOL))ch)(NO);
                msg(ch, sel("release"));
            });
            return;
        }
        jboolean result = JNI_FALSE;
        jstring jmsg = env->NewStringUTF(msg_utf8.c_str());
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onConfirm",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
            if (mid) {
                result = env->CallBooleanMethod(
                    cb, mid, jmsg, jpage, jframe);
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
        jvm->DetachCurrentThread();

        BOOL outcome = (result == JNI_TRUE ? YES : NO);
        dispatch_async(dispatch_get_main_queue(), ^{
            ((void (^)(BOOL))ch)(outcome);
            msg(ch, sel("release"));
        });
    }).detach();
}

// IMP: -[WebviewEmbedUIDelegate webView:runJavaScriptTextInputPanelWithPrompt:
//                               defaultText:initiatedByFrame:completionHandler:]
static void impl_run_prompt(id self, SEL, id webView, id prompt,
                            id defaultText, id frame,
                            id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    id ch = msg(completionHandler, sel("copy"));
    std::string msg_utf8 = ns_string_to_utf8(prompt);
    std::string default_utf8 = ns_string_to_utf8(defaultText);
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        ((void (^)(id))ch)(nil);
        msg(ch, sel("release"));
        return;
    }
    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    std::thread([jvm, cb, msg_utf8, default_utf8, page_url,
                 frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(id))ch)(nil);
                msg(ch, sel("release"));
            });
            return;
        }
        // Capture the result as a std::string with a "cancelled" flag
        // we send to the main-thread block.  std::string can't
        // distinguish "" from null, so use a separate bool.
        std::string result_utf8;
        bool cancelled = true;
        jstring jmsg = env->NewStringUTF(msg_utf8.c_str());
        jstring jdefault = env->NewStringUTF(default_utf8.c_str());
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onPrompt",
                "(Ljava/lang/String;Ljava/lang/String;"
                "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
            if (mid) {
                jstring jresult = (jstring)env->CallObjectMethod(
                    cb, mid, jmsg, jdefault, jpage, jframe);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    jresult = nullptr;
                }
                if (jresult) {
                    const char *cstr =
                        env->GetStringUTFChars(jresult, nullptr);
                    if (cstr) {
                        result_utf8 = cstr;
                        cancelled = false;
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
        jvm->DetachCurrentThread();

        // Build the NSString result on AppKit main before invoking
        // the completion handler -- nil means cancel (page sees null
        // per the JS contract), non-nil means the entered text.
        dispatch_async(dispatch_get_main_queue(), ^{
            id text = cancelled ? nil : ns_str(result_utf8.c_str());
            ((void (^)(id))ch)(text);
            msg(ch, sel("release"));
        });
    }).detach();
}

// IMP: -[WebviewEmbedUIDelegate webView:runOpenPanelWithParameters:
//                               initiatedByFrame:completionHandler:]
//
// _acceptedMIMETypes and _acceptedFileExtensions are documented in
// WebKit source and have been stable across macOS releases since
// 10.12, but they are NOT part of the public WKWebKit headers.  Probe
// them via the ObjC runtime's respondsToSelector: (see below) rather
// than blind invocation so a hypothetical future macOS that hides them
// yields empty arrays here and the default JFileChooser shows all
// files unfiltered (the page's own client-side accept validation
// continues to work).  We avoid @try/@catch because this file is
// compiled as plain C++, not Objective-C++.
static void impl_run_open_panel(id self, SEL, id webView, id parameters,
                                id frame, id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    id ch = msg(completionHandler, sel("copy"));

    // Snapshot parameters on AppKit main.  WKOpenPanelParameters and
    // its private _acceptedMIMETypes / _acceptedFileExtensions
    // accessors must be invoked here, not from a worker thread, since
    // WKWebView's object graph isn't guaranteed thread-safe.
    BOOL multiple = NO;
    std::vector<std::string> mime_types;
    std::vector<std::string> ext_types;
    if (parameters) {
        multiple = msg<BOOL>(parameters, sel("allowsMultipleSelection"));
        // _acceptedMIMETypes / _acceptedFileExtensions are private
        // (underscore-prefixed) accessors on WKOpenPanelParameters that
        // have been stable since macOS 10.12.  Probe respondsToSelector:
        // rather than blindly invoking them so a hypothetical future
        // macOS that removes or renames the selectors degrades
        // gracefully (we pass an empty array to Java and the default
        // JFileChooser shows all files; the page's own client-side
        // `accept` validation still works).
        //
        // Probing via the ObjC runtime keeps this file as plain C++ --
        // the Objective-C++ @try/@catch syntax can't be used because
        // src_c/webview_embed.cpp is compiled with `c++`, not
        // `clang++ -x objective-c++`.
        SEL mime_sel = sel("_acceptedMIMETypes");
        if (msg<BOOL, SEL>(parameters, sel("respondsToSelector:"),
                           mime_sel)) {
            mime_types = ns_array_to_utf8_vector(
                msg<id>(parameters, mime_sel));
        }
        SEL ext_sel = sel("_acceptedFileExtensions");
        if (msg<BOOL, SEL>(parameters, sel("respondsToSelector:"),
                           ext_sel)) {
            ext_types = ns_array_to_utf8_vector(
                msg<id>(parameters, ext_sel));
        }
    }
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        ((void (^)(id))ch)(nil);
        msg(ch, sel("release"));
        return;
    }
    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    std::thread([jvm, cb, multiple, mime_types, ext_types,
                 page_url, frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(id))ch)(nil);
                msg(ch, sel("release"));
            });
            return;
        }

        // Build the input string-arrays in this worker (NewStringUTF
        // requires a JNIEnv).
        jclass strCls = env->FindClass("java/lang/String");
        auto vec_to_jarray = [&](const std::vector<std::string> &v) {
            jobjectArray a = env->NewObjectArray(
                (jsize)v.size(), strCls, nullptr);
            for (size_t i = 0; i < v.size(); i++) {
                jstring s = env->NewStringUTF(v[i].c_str());
                if (s) {
                    env->SetObjectArrayElement(a, (jsize)i, s);
                    env->DeleteLocalRef(s);
                }
            }
            return a;
        };
        jobjectArray jmimes = vec_to_jarray(mime_types);
        jobjectArray jexts = vec_to_jarray(ext_types);
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());

        // Capture chosen file paths as a std::vector<std::string> so
        // we can build the NSArray<NSURL*> back on AppKit main (NSURL
        // construction is technically thread-safe but staying on main
        // keeps the WKWebView completion-handler invocation simple).
        std::vector<std::string> chosen_paths;

        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onFilePicker",
                "(Z[Ljava/lang/String;[Ljava/lang/String;"
                "Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;");
            if (mid) {
                jobjectArray jresult = (jobjectArray)env->CallObjectMethod(
                    cb, mid,
                    (jboolean)(multiple == YES ? JNI_TRUE : JNI_FALSE),
                    jmimes, jexts, jpage, jframe);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    jresult = nullptr;
                }
                if (jresult) {
                    jsize n = env->GetArrayLength(jresult);
                    chosen_paths.reserve((size_t)n);
                    for (jsize i = 0; i < n; i++) {
                        jstring js = (jstring)env->GetObjectArrayElement(
                            jresult, i);
                        if (!js) continue;
                        const char *cstr =
                            env->GetStringUTFChars(js, nullptr);
                        if (cstr) {
                            chosen_paths.emplace_back(cstr);
                            env->ReleaseStringUTFChars(js, cstr);
                        }
                        env->DeleteLocalRef(js);
                    }
                    env->DeleteLocalRef(jresult);
                }
            }
            env->DeleteLocalRef(cls);
        }
        if (jmimes) env->DeleteLocalRef(jmimes);
        if (jexts) env->DeleteLocalRef(jexts);
        if (jpage) env->DeleteLocalRef(jpage);
        if (jframe) env->DeleteLocalRef(jframe);
        if (strCls) env->DeleteLocalRef(strCls);
        jvm->DetachCurrentThread();

        // Build NSArray<NSURL*> on AppKit main and invoke completion.
        // Empty paths vector → nil → user cancelled (empty FileList).
        dispatch_async(dispatch_get_main_queue(), ^{
            id urls = nil;
            if (!chosen_paths.empty()) {
                id NSURLcls = objc_cls("NSURL");
                id arr = msg(objc_cls("NSMutableArray"),
                             sel("arrayWithCapacity:"),
                             (long)chosen_paths.size());
                for (const std::string &path : chosen_paths) {
                    id path_ns = ns_str(path.c_str());
                    id url = msg<id, id>(
                        NSURLcls, sel("fileURLWithPath:"), path_ns);
                    if (url) {
                        msg<void, id>(arr, sel("addObject:"), url);
                    }
                }
                long count = msg<long>(arr, sel("count"));
                if (count > 0) urls = arr;
            }
            ((void (^)(id))ch)(urls);
            msg(ch, sel("release"));
        });
    }).detach();
}

// ---------------------------------------------------------------------------
// Popup (window.open) support — Canvas 15.
//
// window.open / target=_blank is delivered to WKWebView through the
// WKUIDelegate selector
//   webView:createWebViewWithConfiguration:forNavigationAction:windowFeatures:
// which must RETURN the child WKWebView (or nil to block) synchronously.
// Unlike the dialog selectors (which defer to a worker thread and fire a
// completion handler), the popup channel needs the decision inline, so the
// allow/deny hop into Java (onPopupRequested) is synchronous on AppKit main.
// The child web view is created from the SAME WKWebViewConfiguration WebKit
// hands us so it is LINKED to the opener (window.opener / postMessage work —
// required for OAuth signInWithPopup).  We host it in an NSWindow the engine
// owns.  window.close() from the popup fires webViewDidClose:, which closes
// the window and tears the child engine down.
// ---------------------------------------------------------------------------

// Forward declaration: impl_create_web_view (below) builds each child engine's
// UI delegate from the shared class, whose definition follows this block.
static Class get_webview_embed_ui_delegate_cls();

// Fire an async notification (onPopupOpened / onPopupClosed) into Java on a
// detached worker thread so AppKit main is not blocked.  Void return.
static void fire_popup_notify_opened(JavaVM *jvm, jobject cb, jlong popup_id,
                                     std::string url, std::string name,
                                     bool gesture, int w, int h,
                                     std::string page) {
    if (!jvm || !cb) return;
    std::thread([jvm, cb, popup_id, url, name, gesture, w, h, page]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        jstring ju = env->NewStringUTF(url.c_str());
        jstring jn = env->NewStringUTF(name.c_str());
        jstring jp = env->NewStringUTF(page.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onPopupOpened",
                "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
            if (mid) {
                env->CallVoidMethod(cb, mid, popup_id, ju, jn,
                                    gesture ? JNI_TRUE : JNI_FALSE,
                                    (jint)w, (jint)h, jp);
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
        jvm->DetachCurrentThread();
    }).detach();
}

// Fires onPopupClosed and then takes ownership of the child engine's two
// inherited global refs (popup_cb, dialog_cb), deleting them after the JNI
// call.  Ownership transfer avoids a use-after-free: the caller must NOT
// delete these refs itself (the async worker outlives the caller's teardown).
// dialog_cb may be nullptr.
static void fire_popup_notify_closed(JavaVM *jvm, jobject popup_cb,
                                     jobject dialog_cb, jlong popup_id,
                                     std::string url, std::string page) {
    if (!jvm) return;
    std::thread([jvm, popup_cb, dialog_cb, popup_id, url, page]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        if (popup_cb) {
            jstring ju = env->NewStringUTF(url.c_str());
            jstring jp = env->NewStringUTF(page.c_str());
            jclass cls = env->GetObjectClass(popup_cb);
            if (cls) {
                jmethodID mid = env->GetMethodID(cls, "onPopupClosed",
                    "(JLjava/lang/String;Ljava/lang/String;)V");
                if (mid) {
                    env->CallVoidMethod(popup_cb, mid, popup_id, ju, jp);
                    if (env->ExceptionCheck()) {
                        env->ExceptionDescribe();
                        env->ExceptionClear();
                    }
                }
                env->DeleteLocalRef(cls);
            }
            if (ju) env->DeleteLocalRef(ju);
            if (jp) env->DeleteLocalRef(jp);
        }
        // Now free the inherited global refs (ownership transferred to us).
        if (popup_cb) env->DeleteGlobalRef(popup_cb);
        if (dialog_cb) env->DeleteGlobalRef(dialog_cb);
        jvm->DetachCurrentThread();
    }).detach();
}

// Read an NSNumber* (or nil) window-feature dimension into an int, or -1.
static int popup_feature_int(id nsNumber) {
    if (!nsNumber) return -1;
    return (int)msg<int>(nsNumber, sel("intValue"));
}

// Canvas 18: synchronous disposition gate.  Calls WebViewPopupCallback
// .onPopupDisposition on the AppKit main thread and returns the
// PopupDisposition ordinal (0 = BLOCK, 1 = NATIVE_WINDOW, 2 = ADOPT).
// Mirrors fire_popup_requested; a null callback / attach failure / thrown
// decision all yield BLOCK (0).  MUST NOT be marshalled to the EDT (the
// AppKit main thread is blocked awaiting this decision).
static int fire_popup_disposition(JavaVM *jvm, jobject cb,
                                  const char *url, const char *name,
                                  bool gesture, int w, int h,
                                  const char *page) {
    if (!jvm || !cb) return 0; // BLOCK
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return 0;
        detach = true;
    }
    int disposition = 0; // BLOCK
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF(name ? name : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(cb);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupDisposition",
            "(Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)I");
        if (mid) {
            jint r = env->CallIntMethod(cb, mid, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = 0;
            }
            disposition = (int)r;
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
    return disposition;
}

// Canvas 21 (1.5.0): ask the per-destination User-Agent resolver which UA to
// present for a navigation to `url`.  Returns a freshly allocated UTF-8 string
// the CALLER must free(), or nullptr when there is no resolver, no url, or the
// resolver declines (a null/empty return) or throws.  A resolver must never be
// able to break a navigation, so a pending exception is cleared and treated as
// a decline -- the caller then falls back to the opener-copy behaviour.
// Invoked on the engine UI thread, which is not necessarily attached to the
// JVM, so it attaches and detaches symmetrically.  The resolver object is
// java.util.function.Function, invoked reflectively as apply(Object)Object.
static char *resolve_ua_for(JavaVM *jvm, jobject resolver, const char *url) {
    if (!jvm || !resolver || !url || !*url) return nullptr;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return nullptr;
        detach = true;
    }
    char *out = nullptr;
    jstring ju = env->NewStringUTF(url);
    jclass cls = env->GetObjectClass(resolver);
    if (cls && ju) {
        jmethodID mid = env->GetMethodID(cls, "apply",
            "(Ljava/lang/Object;)Ljava/lang/Object;");
        if (mid) {
            jobject r = env->CallObjectMethod(resolver, mid, ju);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = nullptr;
            }
            if (r) {
                const char *cs = env->GetStringUTFChars((jstring)r, nullptr);
                if (cs && *cs) out = strdup(cs);
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

// Canvas 18: async notification that a popup child has been retained and is
// ready to adopt.  Fire-and-forget on a detached worker thread (the Java
// dispatcher marshals popupAdoptable to the EDT via invokeLater), mirroring
// fire_popup_notify_opened.
static void fire_popup_notify_adoptable(JavaVM *jvm, jobject cb, jlong popup_id,
                                        std::string url, std::string name,
                                        bool gesture, int w, int h,
                                        std::string page) {
    if (!jvm || !cb) return;
    std::thread([jvm, cb, popup_id, url, name, gesture, w, h, page]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        jstring ju = env->NewStringUTF(url.c_str());
        jstring jn = env->NewStringUTF(name.c_str());
        jstring jp = env->NewStringUTF(page.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onPopupAdoptable",
                "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
            if (mid) {
                env->CallVoidMethod(cb, mid, popup_id, ju, jn,
                                    gesture ? JNI_TRUE : JNI_FALSE,
                                    (jint)w, (jint)h, jp);
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
        jvm->DetachCurrentThread();
    }).detach();
}

// WKUIDelegate createWebViewWithConfiguration:forNavigationAction:windowFeatures:
// Returns the child WKWebView, or nil to block.
static id impl_create_web_view(id self, SEL, id webView, id configuration,
                               id navigationAction, id windowFeatures) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    if (!e || !e->popup_callback) return nullptr;

    // Extract the request URL, gesture, and requested size.
    std::string target;
    {
        id req = msg(navigationAction, sel("request"));
        id url = req ? msg(req, sel("URL")) : nullptr;
        id abs = url ? msg(url, sel("absoluteString")) : nullptr;
        if (abs) target = ns_string_to_utf8(abs);
    }
    std::string page = page_url_utf8(webView);
    int req_w = popup_feature_int(msg(windowFeatures, sel("width")));
    int req_h = popup_feature_int(msg(windowFeatures, sel("height")));
    // window.open in practice requires a user gesture; report true.
    bool gesture = true;

    // Canvas 18: synchronous DISPOSITION hop into Java (AppKit main -> JNI).
    // 0 = BLOCK (return nil), 1 = NATIVE_WINDOW (engine-owned window, the
    // Canvas 15 path), 2 = ADOPT (retain the child, NO window, notify Java to
    // reparent it into a caller-supplied WebViewComponent).  The default
    // WebViewPopupCallback.onPopupDisposition derives from onPopupRequested,
    // so legacy boolean handlers still map to BLOCK / NATIVE_WINDOW.
    JavaVM *jvm = e->jvm;
    jobject cb = e->popup_callback;
    int disposition = fire_popup_disposition(
        jvm, cb, target.c_str(), "", gesture, req_w, req_h, page.c_str());
    if (disposition == 0) {
        return nullptr; // BLOCK
    }
    const bool adopt = (disposition == 2);

    // Size the popup: use the requested size, else a typical auth-popup size.
    int W = req_w > 0 ? req_w : 500;
    int H = req_h > 0 ? req_h : 650;

    // Isolate an ADOPT child's script world from the opener's.  The
    // configuration WebKit hands us for a browser-initiated popup shares the
    // opener's WKUserContentController, so the opener's injected user scripts
    // and its single "external" script-message handler are shared with the
    // child.  An adopted child never installs its own "external" handler
    // (addScriptMessageHandler: with a name already present on the SAME
    // controller throws), so its aaf* bridge messages -- including the address
    // bar's location reporter -- were delivered to the OPENER engine's handler
    // instead: the observed bug where an adopted tab (e.g. an Okta-launched
    // Slack tab) drove the opener tab's URL field and vice-versa.  Swap in a
    // fresh, empty controller BEFORE init so the child gets its own script
    // world; the shared processPool / websiteDataStore (and therefore
    // window.opener and the in-flight POST) live on the rest of the
    // configuration and are untouched.  Scoped to ADOPT: a NATIVE_WINDOW popup
    // is engine-owned and unbridged, so its controller is left as-is.
    //
    // ON-DEVICE VALIDATION REQUIRED (macOS): confirm window.opener + the
    // in-flight POST still reach the adopted child after the controller swap.
    if (adopt) {
        id freshUCC = msg(objc_cls("WKUserContentController"), sel("alloc"));
        freshUCC = msg(freshUCC, sel("init"));
        if (freshUCC) {
            msg<void, id>(configuration, sel("setUserContentController:"),
                          freshUCC);
            msg<void>(freshUCC, sel("release")); // configuration now owns it
        }
    }

    // Create the child WKWebView LINKED to the opener via the passed config.
    // WebKit drives the ORIGINAL navigation-action request (POST verb + body)
    // into this child regardless of disposition, which is what preserves POST
    // and window.opener for both NATIVE_WINDOW and ADOPT.
    id child = msg(objc_cls("WKWebView"), sel("alloc"));
    child = msg<id, CGRect, id>(child, sel("initWithFrame:configuration:"),
                                CGRectMake(0, 0, W, H), configuration);
    if (!child) {
        return nullptr;
    }

    // Canvas 21: propagate the opener's custom User-Agent to the popup child
    // BEFORE its in-flight initial navigation.  customUserAgent is a per-
    // WKWebView INSTANCE property (NOT part of WKWebViewConfiguration), so the
    // child does not inherit it from the shared config.  Reading it back from
    // the opener returns nil when no override is set, so an engine-default
    // opener correctly leaves the child at the engine default.  Applied here
    // (before the `if (!adopt)` window setup) so it covers BOTH the ADOPT and
    // NATIVE_WINDOW dispositions.
    //
    // Canvas 21 (1.5.0): when a per-destination resolver is installed, the
    // child's UA is chosen from the CHILD's own target URL rather than copied
    // from the opener -- the case that matters is an OAuth sign-in popped out
    // of a site that requires a spoofed UA, landing on an identity provider
    // that penalises exactly that spoof.  A resolver that declines (or none at
    // all) falls through to the opener-copy, so pre-1.5.0 behaviour is
    // preserved byte-for-byte for callers that never set one.
    char *resolvedUA = e ? resolve_ua_for(jvm, e->ua_resolver, target.c_str())
                         : nullptr;
    if (resolvedUA) {
        msg<void, id>(child, sel("setCustomUserAgent:"), ns_str(resolvedUA));
        free(resolvedUA);
    } else {
        id openerUA = e ? msg(e->webview, sel("customUserAgent")) : nullptr;
        if (openerUA) {
            msg<void, id>(child, sel("setCustomUserAgent:"), openerUA);
        }
    }

    // Attach a JNIEnv to create the child engine's inherited global refs.
    JNIEnv *env = nullptr;
    bool attached = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env) {
            msg<void>(child, sel("release"));
            return nullptr;
        }
        attached = true;
    }

    // NATIVE_WINDOW: host the child in an engine-owned NSWindow (titled|
    // closable|miniaturizable|resizable = 1|2|4|8; NSBackingStoreBuffered =
    // 2).  ADOPT: create NO window — the child is retained under
    // g_retained_popups and reparented later by cocoa_adopt_popup, so nothing
    // is ever shown until the caller adopts it.
    id win = nullptr;
    if (!adopt) {
        win = msg(objc_cls("NSWindow"), sel("alloc"));
        win = msg<id, CGRect, unsigned long, unsigned long, BOOL>(
            win, sel("initWithContentRect:styleMask:backing:defer:"),
            CGRectMake(0, 0, W, H), (unsigned long)15, (unsigned long)2, NO);
        msg<void, id>(win, sel("setTitle:"), ns_str("Popup"));
        msg<void, id>(win, sel("setContentView:"), child);
        msg<void>(win, sel("center"));
        msg<void, id>(win, sel("makeKeyAndOrderFront:"), nullptr);
    }

    // Build the child Engine, inheriting the opener's callbacks so the popup
    // supports dialogs / nested popups and can notify onPopupClosed /
    // onPopupAdoptable.
    Engine *child_e = new Engine();
    child_e->webview = child;
    child_e->jvm = jvm;
    child_e->config = configuration;
    child_e->manager = msg(configuration, sel("userContentController"));
    child_e->popup_window = win;              // nil for ADOPT
    child_e->popup_id = (jlong)child_e;
    if (env) {
        child_e->popup_callback = env->NewGlobalRef(cb);
        if (e->dialog_callback)
            child_e->dialog_callback = env->NewGlobalRef(e->dialog_callback);
        // A download started in a popup must reach the OPENER's handler
        // -- the transfer belongs to the user, not to which view began
        // it (Canvas 23).
        if (e->download_callback)
            child_e->download_callback = env->NewGlobalRef(e->download_callback);
        // Canvas 21 (1.5.0): a nested popup resolves its own child's UA the
        // same way its opener did, so the resolver is inherited transitively.
        if (e->ua_resolver)
            child_e->ua_resolver = env->NewGlobalRef(e->ua_resolver);
    }
    Class uicls = get_webview_embed_ui_delegate_cls();
    id ui = msg((id)uicls, sel("new"));
    objc_setAssociatedObject(ui, "eng", (id)child_e, OBJC_ASSOCIATION_ASSIGN);
    msg<void, id>(child, sel("setUIDelegate:"), ui);
    // Downloads started in a popup reach the opener's handler through
    // the child engine's inherited download_callback (Canvas 23).
    msg<void, id>(child, sel("setNavigationDelegate:"), ui);
    child_e->ui_delegate = ui;
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        g_webview_map[child] = child_e;
    }

    // An ADOPT child was given its own (empty) WKUserContentController above,
    // so install the child's OWN "external" script-message bridge + invoke
    // shim on it -- bound to the CHILD engine -- exactly as cocoa_create_engine
    // does for a normal engine.  Without this the isolated child would have no
    // "external" handler at all and its aaf* bridge traffic would go nowhere;
    // with it, the child's messages route to child_e's bindings (registered by
    // the adopting Java EmbeddedWebView), never the opener's.  engine_on_message
    // safely drops any message that arrives before those bindings exist.
    if (adopt) {
        Class mh_cls = get_webview_embed_delegate_cls();
        id mh = msg((id)mh_cls, sel("new"));
        objc_setAssociatedObject(mh, "eng", (id)child_e,
                                 OBJC_ASSOCIATION_ASSIGN);
        msg<void, id, id>(child_e->manager,
                          sel("addScriptMessageHandler:name:"), mh,
                          ns_str("external"));
        id shim = msg(objc_cls("WKUserScript"), sel("alloc"));
        shim = msg<id, id, long, BOOL>(
            shim, sel("initWithSource:injectionTime:forMainFrameOnly:"),
            ns_str("window.external={invoke:function(s){"
                   "window.webkit.messageHandlers.external.postMessage(s);}};"),
            (long)0 /* WKUserScriptInjectionTimeAtDocumentStart */,
            YES);
        msg<void, id>(child_e->manager, sel("addUserScript:"), shim);
    }

    if (adopt) {
        std::lock_guard<std::mutex> lk(g_retained_popups_mutex);
        g_retained_popups[child_e->popup_id] = child_e;
    }

    // Global refs are created; safe to detach now if we attached above.
    if (attached) jvm->DetachCurrentThread();

    // Notify the opener's PopupDispatcher (async).  Both notify helpers attach
    // their own worker thread and do not use `env`.
    if (adopt) {
        fire_popup_notify_adoptable(jvm, cb, child_e->popup_id, target, "",
                                    gesture, W, H, page);
    } else {
        fire_popup_notify_opened(jvm, cb, child_e->popup_id, target, "",
                                 gesture, W, H, page);
    }
    return child;
}

// WKUIDelegate webViewDidClose: — the popup called window.close().  Runs on
// AppKit main.  We tear down the child engine's native objects synchronously
// here and hand the inherited Java global refs to the async close-notify
// worker, which fires onPopupClosed and then frees them (ownership transfer —
// see fire_popup_notify_closed).  The worker only reads captured-by-value
// state, never `e`, so deleting `e` here is safe.
static void impl_web_view_did_close(id self, SEL, id webView) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    if (!e) return;
    std::string url = page_url_utf8(webView);
    JavaVM *jvm = e->jvm;
    jlong pid = e->popup_id;

    // A browser-initiated window.close() must NEVER free an engine whose
    // lifecycle Java owns (a normal embedded engine, or a popup already
    // promoted by cocoa_adopt_popup).  That engine is freed exactly once by
    // cocoa_destroy_engine (EmbeddedWebView.dispose(), which is idempotent);
    // deleting it here too double-frees it -- the objc_msgSend
    // use-after-free observed on app quit when the destroy main-queue block
    // messaged the already-freed objects during -[NSApplication terminate:].
    // Fire onPopupClosed (for a real popup) using COPIES of the inherited
    // global refs so the async worker never frees the engine's own refs,
    // then return without touching native objects or the engine.
    if (e->java_owned) {
        if (pid != 0 && e->popup_callback) {
            JNIEnv *env = nullptr;
            bool detach = false;
            if (jvm && jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
                jvm->AttachCurrentThread((void **)&env, nullptr);
                detach = true;
            }
            jobject pc = nullptr, dc = nullptr;
            if (env) {
                pc = env->NewGlobalRef(e->popup_callback);
                if (e->dialog_callback) dc = env->NewGlobalRef(e->dialog_callback);
            }
            if (detach && jvm) jvm->DetachCurrentThread();
            fire_popup_notify_closed(jvm, pc, dc, pid, url, "");
        }
        return;
    }

    // Non-Java-owned: an engine-owned native-window popup, or a
    // retained-but-unadopted child.  We are its sole owner; tear it down.
    jobject popup_cb = e->popup_callback;
    jobject dialog_cb = e->dialog_callback;

    // Detach the delegate, drop its unretained "eng" back-pointer, and drop
    // from BOTH registries BEFORE releasing so no in-flight selector -- and
    // no later cocoa_adopt_popup / cocoa_discard_popup lookup -- can recover
    // and dereference a freed engine.  Erasing g_retained_popups closes the
    // close-before-adopt race (a windowless ADOPT child whose page closes
    // before the app adopts it would otherwise leave a dangling entry).
    objc_setAssociatedObject(self, "eng", (id)nullptr, OBJC_ASSOCIATION_ASSIGN);
    msg<void, id>(webView, sel("setUIDelegate:"), nullptr);
    msg<void, id>(webView, sel("setNavigationDelegate:"), (id)nullptr);
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        g_webview_map.erase(webView);
    }
    if (pid != 0) {
        std::lock_guard<std::mutex> lk(g_retained_popups_mutex);
        g_retained_popups.erase(pid);
    }
    if (e->popup_window) {
        msg<void>(e->popup_window, sel("close"));
    }

    // Notify Java and transfer ownership of the two inherited global refs.
    fire_popup_notify_closed(jvm, popup_cb, dialog_cb, pid, url, "");

    // Abandon the popup's in-flight downloads (removing their NSProgress
    // KVO observers) and drop its inherited download-callback ref before
    // anything below is released.
    cocoa_download_drop_engine(e);
    if (e->download_callback) {
        JNIEnv *denv = nullptr;
        bool ddetach = false;
        if (jvm && jvm->GetEnv((void **)&denv, JNI_VERSION_1_6) != JNI_OK) {
            jvm->AttachCurrentThread((void **)&denv, nullptr);
            ddetach = true;
        }
        if (denv) denv->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
        if (ddetach && jvm) jvm->DetachCurrentThread();
    }

    // Release native objects and free the engine.  The refs are now owned by
    // the worker, so we must not touch them here.
    e->popup_callback = nullptr;
    e->dialog_callback = nullptr;
    if (e->ui_delegate) { msg(e->ui_delegate, sel("release")); e->ui_delegate = nullptr; }
    if (e->popup_window) { msg(e->popup_window, sel("release")); e->popup_window = nullptr; }
    // Balance the +1 from the [[WKWebView alloc] init...] in
    // impl_create_web_view.  WebKit and (until now) the NSWindow contentView
    // held their own refs; releasing our alloc ref lets the child web view
    // deallocate once WebKit drops its reference.  (Retain/release balance
    // here is a prime candidate for on-device zombie/Instruments validation.)
    if (e->webview) { msg(e->webview, sel("release")); }
    e->webview = nullptr;
    delete e;
}

// Register (or clear, when cb is null) the Java WebViewPopupCallback for this
// engine.  Mirrors cocoa_set_dialog_callback.
static void cocoa_set_popup_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->popup_callback) {
        env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
    }
    if (cb) {
        e->popup_callback = env->NewGlobalRef(cb);
    }
}

// Canvas 34 D5: register (or clear, when cb is null) the navigation callback.
// Mirrors cocoa_set_popup_callback.
static void cocoa_set_navigation_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->navigation_callback) {
        env->DeleteGlobalRef(e->navigation_callback);
        e->navigation_callback = nullptr;
    }
    if (cb) {
        e->navigation_callback = env->NewGlobalRef(cb);
    }
}

// ---------------------------------------------------------------------------
// Browser-initiated file downloads (Canvas 23).
//
// WKWebView produces a WKDownload only when the navigation-response
// policy says so, so impl_decide_policy_for_navigation_response answers
// WKNavigationResponsePolicyDownload for a response the engine cannot
// render or that carries Content-Disposition: attachment.  The two
// didBecomeDownload: callbacks then adopt the download and this file
// drives it to completion.
//
// Three things about this platform shape the code below:
//
//   1. WKDownloadDelegate has NO byte-count callback.  The only
//      progress WKDownload exposes is its `progress` property, an
//      NSProgress, so progress is observed with KVO on
//      completedUnitCount -- the same objc_allocateClassPair +
//      associated-object pattern the engine already uses for
//      NSWindow.firstResponder.
//
//   2. The destination decision MUST NOT block AppKit main.  The Java
//      handler runs on the Swing EDT and the stock one opens a modal
//      JFileChooser, which needs AppKit main itself; waiting for it
//      from AppKit main deadlocks on the first download.  We use the
//      deferral shape impl_run_alert already established: copy the
//      completion block, return immediately, do the JNI hop on a
//      detached worker thread, dispatch_async back to main to answer.
//
//   3. WKDownload is macOS 11.3+.  The selectors are always added (an
//      unused selector costs nothing) but the policy method only ever
//      answers Download when the class exists at runtime, so an older
//      system keeps exactly its current behaviour.
// ---------------------------------------------------------------------------

// One in-flight download.  Held in g_download_map rather than as an
// associated object because the context must be *freed* at a definite
// point, and OBJC_ASSOCIATION_ASSIGN frees nothing.
struct DownloadCtx {
    Engine *e = nullptr;
    long long download_id = 0;   // NOT `id`: that shadows the ObjC ::id typedef
    std::atomic<long long> written{0};
    std::atomic<bool> terminal{false};
    id observer = nullptr;   // WebviewEmbedDownloadObserver, retained
    id progress = nullptr;   // the observed NSProgress, retained
};

static std::mutex g_download_mutex;
static std::map<id, DownloadCtx *> g_download_map;   // WKDownload -> ctx

static std::once_flag g_download_observer_once;
static Class g_download_observer_cls = nil;
static const char DOWNLOAD_CTX_KEY[] = "dlctx";
static int kvo_ctx_download_progress = 0;

// True when the running system has WKDownload (macOS 11.3+).  Computed
// once; the policy selector is the only reader.
static bool cocoa_downloads_available() {
    static bool available =
        (objc_getClass("WKDownload") != nullptr);
    return available;
}

// Invoke WebViewDownloadCallback.onDownloadRequested and copy the
// returned absolute path into *out_path.  Returns false when there is
// no callback, the method is missing, or Java refused (null return).
//
// Called from a detached worker thread (see impl_download_decide_
// destination), so it always attaches; the defensive
// GetEnv/AttachCurrentThread shape matches fire_click_callback.
static bool fire_download_requested(JavaVM *jvm, jobject cb, long long id,
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
    if (!env) return false;
    bool got = false;
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
                const char *c = env->GetStringUTFChars((jstring)res, nullptr);
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
    if (detach) jvm->DetachCurrentThread();
    return got;
}

static void fire_download_progress(Engine *e, long long id,
                                   long long received, long long total) {
    if (!e || !e->download_callback) return;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
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

static void fire_download_completed(Engine *e, long long id, bool success,
                                    const std::string &reason,
                                    long long received) {
    if (!e || !e->download_callback) return;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
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

// KVO callback on the download's NSProgress.completedUnitCount.
static void download_kvo_observe_impl(id self, SEL /*_cmd*/, id /*keyPath*/,
                                      id /*object*/, id /*change*/,
                                      void *context) {
    if (context != &kvo_ctx_download_progress) return;
    auto *ctx = (DownloadCtx *)objc_getAssociatedObject(self,
                                                        DOWNLOAD_CTX_KEY);
    if (!ctx || ctx->terminal.load()) return;
    Engine *e = ctx->e;
    if (!e || e->destroyed.load()) return;
    id progress = ctx->progress;
    if (!progress) return;
    long long done = msg<long long>(progress, sel("completedUnitCount"));
    long long total = msg<long long>(progress, sel("totalUnitCount"));
    ctx->written.store(done);
    fire_download_progress(e, ctx->download_id, done, total > 0 ? total : -1);
}

static void ensure_download_observer_class() {
    std::call_once(g_download_observer_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedDownloadObserver", 0);
        class_addMethod(
            c,
            sel("observeValueForKeyPath:ofObject:change:context:"),
            (IMP)download_kvo_observe_impl,
            "v@:@@@^v");
        objc_registerClassPair(c);
        g_download_observer_cls = c;
    });
}

// Stop observing and release the observer + progress.  Caller MUST be on
// AppKit main.  Idempotent.  An NSProgress released while still observed
// is an AppKit hard error, so this always runs before the download goes
// away and again at engine teardown for anything still in flight.
static void cocoa_download_stop_observing(DownloadCtx *ctx) {
    if (!ctx) return;
    if (ctx->progress && ctx->observer) {
        msg<void, id, id, void *>(
            ctx->progress, sel("removeObserver:forKeyPath:context:"),
            ctx->observer, ns_str("completedUnitCount"),
            &kvo_ctx_download_progress);
    }
    if (ctx->progress) { msg(ctx->progress, sel("release")); ctx->progress = nullptr; }
    if (ctx->observer) { msg(ctx->observer, sel("release")); ctx->observer = nullptr; }
}

// Look up (without removing) the context for a WKDownload.
static DownloadCtx *cocoa_download_ctx(id download) {
    std::lock_guard<std::mutex> lock(g_download_mutex);
    auto it = g_download_map.find(download);
    return it == g_download_map.end() ? nullptr : it->second;
}

// Claim the terminal report for `download`, detach its context from the
// map, stop observing, and return the context so the caller can report
// and free it.  Returns nullptr when some other path already claimed it.
static DownloadCtx *cocoa_download_claim_terminal(id download) {
    DownloadCtx *ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_download_mutex);
        auto it = g_download_map.find(download);
        if (it == g_download_map.end()) return nullptr;
        ctx = it->second;
        if (!ctx || ctx->terminal.exchange(true)) return nullptr;
        g_download_map.erase(it);
    }
    cocoa_download_stop_observing(ctx);
    return ctx;
}

// Canvas 34 D8: synchronous navigation decision into Java on AppKit main,
// like fire_popup_disposition.  A missing callback, an attach failure, or a
// thrown decision all refuse.
static bool cocoa_fire_navigation(JavaVM *jvm, jobject cb, const char *url,
                                  const char *current, int cause) {
    if (!jvm || !cb) return false;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return false;
        detach = true;
    }
    bool allowed = false;
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jc = env->NewStringUTF(current ? current : "");
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
    return allowed;
}

// WKNavigationDelegate:
//   -webView:decidePolicyForNavigationAction:decisionHandler:  (Canvas 34 D8)
// Asks the WebViewNavigationCallback about every navigation of the view and
// its frames, answering Cancel (0) on a refusal.  Implementing this selector
// replaces WebKit's own default, so an allowed navigation -- or any, with no
// callback -- reproduces that default exactly: a new-window action (nil
// targetFrame) is used (the popup path decides it); a request WebKit can load,
// or one for a registered custom scheme, is downloaded when the action says
// shouldPerformDownload and used otherwise; anything else is opened with
// NSWorkspace (never a file URL) and ignored.  about:, data:, blob: and
// javascript: are treated as loadable.
static void impl_decide_policy_for_navigation_action(
        id self, SEL, id webView, id navigationAction, id decisionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    void (^answer)(long) = (void (^)(long))decisionHandler;
    if (!navigationAction) { answer(1); return; }
    id target_frame = msg(navigationAction, sel("targetFrame"));
    if (!target_frame) { answer(1); return; }   // WKNavigationActionPolicyAllow

    id req = msg(navigationAction, sel("request"));
    id url = req ? msg(req, sel("URL")) : nullptr;
    std::string u;
    if (url) {
        id abs = msg(url, sel("absoluteString"));
        if (abs) u = ns_string_to_utf8(abs);
    }

    if (e && e->navigation_callback && !e->destroyed.load()) {
        long type = msg<long>(navigationAction, sel("navigationType"));
        int cause = 5;  // OTHER (redirects are not flagged by WKWebView)
        switch (type) {
            case 0: cause = 0; break;   // WKNavigationTypeLinkActivated
            case 1: cause = 1; break;   // WKNavigationTypeFormSubmitted
            case 2: cause = 2; break;   // WKNavigationTypeBackForward
            case 3: cause = 3; break;   // WKNavigationTypeReload
            case 4: cause = 1; break;   // WKNavigationTypeFormResubmitted
            default: cause = 5; break;
        }
        std::string current = page_url_utf8(webView);
        if (!cocoa_fire_navigation(e->jvm, e->navigation_callback, u.c_str(),
                                   current.c_str(), cause)) {
            answer(0);                                   // Cancel
            return;
        }
    }

    // WebKit's default policy, reproduced.
    bool loadable = false;
    if (url) {
        std::string scheme;
        id sc = msg(url, sel("scheme"));
        if (sc) scheme = ns_string_to_utf8(sc);
        for (size_t i = 0; i < scheme.size(); i++) {
            scheme[i] = (char)tolower((unsigned char)scheme[i]);
        }
        if (scheme == "about" || scheme == "data" || scheme == "blob"
                || scheme == "javascript") {
            loadable = true;
        }
        for (const std::string &name : g_scheme_names) {
            if (name == scheme) loadable = true;
        }
        if (!loadable && req) {
            loadable = msg<BOOL, id>(objc_cls("NSURLConnection"),
                                     sel("canHandleRequest:"), req) == YES;
        }
    }
    if (loadable) {
        long policy = 1;                                 // Allow
        if (msg<BOOL, SEL>(navigationAction, sel("respondsToSelector:"),
                           sel("shouldPerformDownload")) == YES
                && msg<BOOL>(navigationAction, sel("shouldPerformDownload")) == YES) {
            policy = 2;                                  // Download (11.3+)
        }
        answer(policy);
        return;
    }
    if (url && msg<BOOL>(url, sel("isFileURL")) == NO) {
        id ws = msg(objc_cls("NSWorkspace"), sel("sharedWorkspace"));
        if (ws) msg<BOOL, id>(ws, sel("openURL:"), url);
    }
    answer(0);
}

// WKNavigationDelegate:
//   -webView:decidePolicyForNavigationResponse:decisionHandler:
// Answers Download (2) for a response the engine will not render or that
// carries an attachment disposition, Allow (1) otherwise.  On a system
// without WKDownload this always answers Allow, which is exactly the
// pre-feature behaviour.
static void impl_decide_policy_for_navigation_response(
        id self, SEL, id /*webView*/, id navigationResponse,
        id decisionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    long policy = 1;  // WKNavigationResponsePolicyAllow

    if (e && e->download_callback && cocoa_downloads_available()
            && navigationResponse) {
        BOOL can_show = msg<BOOL>(navigationResponse, sel("canShowMIMEType"));
        bool as_download = (can_show == NO);
        if (!as_download) {
            id response = msg(navigationResponse, sel("response"));
            if (response
                    && msg<BOOL, SEL>(response, sel("respondsToSelector:"),
                                      sel("valueForHTTPHeaderField:")) == YES) {
                id disp = msg<id, id>(response,
                                      sel("valueForHTTPHeaderField:"),
                                      ns_str("Content-Disposition"));
                std::string d = ns_string_to_utf8(disp);
                size_t first = d.find_first_not_of(" \t");
                if (first != std::string::npos) d = d.substr(first);
                for (size_t i = 0; i < d.size(); i++) {
                    d[i] = (char)tolower((unsigned char)d[i]);
                }
                if (d.compare(0, 10, "attachment") == 0) as_download = true;
            }
        }
        if (as_download) policy = 2;  // WKNavigationResponsePolicyDownload
    }
    ((void (^)(long))decisionHandler)(policy);
}

// Shared tail of the two didBecomeDownload: callbacks: adopt the
// download, mint its id, and register the context.
static void cocoa_adopt_download(id self, id download) {
    if (!download) return;
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    if (!e || e->destroyed.load()) return;
    msg<void, id>(download, sel("setDelegate:"), self);
    DownloadCtx *ctx = new DownloadCtx();
    ctx->e = e;
    ctx->download_id = e->next_download_id.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_download_mutex);
        g_download_map[download] = ctx;
    }
}

static void impl_navigation_action_did_become_download(
        id self, SEL, id /*webView*/, id /*navigationAction*/, id download) {
    cocoa_adopt_download(self, download);
}

static void impl_navigation_response_did_become_download(
        id self, SEL, id /*webView*/, id /*navigationResponse*/, id download) {
    cocoa_adopt_download(self, download);
}

// WKDownloadDelegate:
//   -download:decideDestinationUsingResponse:suggestedFilename:
//    completionHandler:
//
// Deferral shape mirrors impl_run_alert: copy the block, capture the
// inputs as std::string while still on AppKit main, return immediately,
// run the JNI hop on a detached worker thread, and dispatch_async back
// to main to answer.  Blocking AppKit main here would deadlock against
// the stock handler's modal JFileChooser.
static void impl_download_decide_destination(
        id self, SEL, id download, id response, id suggestedFilename,
        id completionHandler) {
    (void)self;
    id ch = msg(completionHandler, sel("copy"));
    DownloadCtx *ctx = cocoa_download_ctx(download);
    Engine *e = ctx ? ctx->e : nullptr;

    if (!ctx || !e || e->destroyed.load() || !e->download_callback) {
        // Nothing to ask.  Cancel the transfer rather than letting
        // WebKit pick a destination of its own.
        ((void (^)(id))ch)(nullptr);
        msg(ch, sel("release"));
        return;
    }

    std::string suggested = ns_string_to_utf8(suggestedFilename);
    std::string mime;
    long long total = -1;
    if (response) {
        mime = ns_string_to_utf8(msg(response, sel("MIMEType")));
        long long len = msg<long long>(response, sel("expectedContentLength"));
        total = (len < 0) ? -1 : len;
    }
    std::string url;
    id req = msg(download, sel("originalRequest"));
    if (req) {
        id u = msg(req, sel("URL"));
        if (u) url = ns_string_to_utf8(msg(u, sel("absoluteString")));
    }
    std::string page_url = page_url_utf8(msg(download, sel("webView")));

    JavaVM *jvm = e->jvm;
    jobject cb = e->download_callback;
    long long id_val = ctx->download_id;
    id dl = download;

    std::thread([jvm, cb, id_val, url, suggested, mime, total, page_url,
                 ch, dl, ctx, e]() {
        std::string path;
        bool accepted = fire_download_requested(
            jvm, cb, id_val, url, suggested, mime, total, page_url, &path);
        std::string chosen = accepted ? path : std::string();
        dispatch_async(dispatch_get_main_queue(), ^{
            if (!chosen.empty() && !e->destroyed.load()) {
                // Observe the NSProgress for byte counts -- WKDownload
                // reports none through its delegate.
                id progress = msg(dl, sel("progress"));
                if (progress) {
                    ensure_download_observer_class();
                    id observer = msg((id)g_download_observer_cls, sel("new"));
                    if (observer) {
                        objc_setAssociatedObject(observer, DOWNLOAD_CTX_KEY,
                                                 (id)ctx,
                                                 OBJC_ASSOCIATION_ASSIGN);
                        ctx->observer = observer;
                        ctx->progress = msg(progress, sel("retain"));
                        msg<void, id, id, unsigned long, void *>(
                            progress,
                            sel("addObserver:forKeyPath:options:context:"),
                            observer, ns_str("completedUnitCount"),
                            (unsigned long)0, &kvo_ctx_download_progress);
                    }
                }
                id nsurl = msg<id, id>(objc_cls("NSURL"),
                                       sel("fileURLWithPath:"),
                                       ns_str(chosen.c_str()));
                ((void (^)(id))ch)(nsurl);
            } else {
                // Refused (or the engine went away): cancel.  Java has
                // already reported the single terminal event for this
                // id and latched it, so the cancellation WebKit reports
                // back through didFailWithError: is dropped there.
                ((void (^)(id))ch)(nullptr);
            }
            msg(ch, sel("release"));
        });
    }).detach();
}

static void impl_download_did_finish(id self, SEL, id download) {
    (void)self;
    DownloadCtx *ctx = cocoa_download_claim_terminal(download);
    if (!ctx) return;
    Engine *e = ctx->e;
    if (e && !e->destroyed.load()) {
        fire_download_completed(e, ctx->download_id, true, std::string(),
                                ctx->written.load());
    }
    delete ctx;
}

static void impl_download_did_fail(id self, SEL, id download, id error,
                                   id /*resumeData*/) {
    (void)self;
    DownloadCtx *ctx = cocoa_download_claim_terminal(download);
    if (!ctx) return;
    Engine *e = ctx->e;
    if (e && !e->destroyed.load()) {
        std::string reason;
        if (error) {
            reason = ns_string_to_utf8(msg(error, sel("localizedDescription")));
        }
        if (reason.empty()) reason = "Download failed";
        fire_download_completed(e, ctx->download_id, false, reason,
                                ctx->written.load());
    }
    delete ctx;
}

// Drop every download belonging to `e`.  Called from cocoa_destroy_engine
// on AppKit main, before anything is released, so no KVO observer
// outlives its NSProgress and no late callback reaches a freed engine.
static void cocoa_download_drop_engine(Engine *e) {
    std::vector<DownloadCtx *> mine;
    {
        std::lock_guard<std::mutex> lock(g_download_mutex);
        for (auto it = g_download_map.begin(); it != g_download_map.end(); ) {
            if (it->second && it->second->e == e) {
                it->second->terminal.store(true);
                mine.push_back(it->second);
                it = g_download_map.erase(it);
            } else {
                ++it;
            }
        }
    }
    for (size_t i = 0; i < mine.size(); i++) {
        cocoa_download_stop_observing(mine[i]);
        delete mine[i];
    }
}

// Register (or clear, when cb is null) the Java WebViewDownloadCallback
// for this engine.  Mirrors cocoa_set_dialog_callback.
static void cocoa_set_download_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->download_callback) {
        env->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
    }
    if (cb) {
        e->download_callback = env->NewGlobalRef(cb);
    }
}

static Class get_webview_embed_ui_delegate_cls() {
    std::call_once(g_webview_embed_ui_delegate_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedUIDelegate", 0);
        class_addProtocol(c, objc_getProtocol("WKUIDelegate"));
        class_addMethod(
            c,
            sel("webView:runJavaScriptAlertPanelWithMessage:"
                "initiatedByFrame:completionHandler:"),
            (IMP)impl_run_alert, "v@:@@@@");
        class_addMethod(
            c,
            sel("webView:runJavaScriptConfirmPanelWithMessage:"
                "initiatedByFrame:completionHandler:"),
            (IMP)impl_run_confirm, "v@:@@@@");
        class_addMethod(
            c,
            sel("webView:runJavaScriptTextInputPanelWithPrompt:"
                "defaultText:initiatedByFrame:completionHandler:"),
            (IMP)impl_run_prompt, "v@:@@@@@");
        class_addMethod(
            c,
            sel("webView:runOpenPanelWithParameters:"
                "initiatedByFrame:completionHandler:"),
            (IMP)impl_run_open_panel, "v@:@@@@");
        // Popup (window.open) support — Canvas 15.
        class_addMethod(
            c,
            sel("webView:createWebViewWithConfiguration:"
                "forNavigationAction:windowFeatures:"),
            (IMP)impl_create_web_view, "@@:@@@@");
        class_addMethod(
            c,
            sel("webViewDidClose:"),
            (IMP)impl_web_view_did_close, "v@:@");
        // Download support -- Canvas 23.  The same object serves as the
        // WKNavigationDelegate and the WKDownloadDelegate: it already
        // carries the per-engine "eng" associated object every selector
        // needs, and the WKWebView's navigationDelegate was nil.
        // Protocols missing on an older SDK/runtime resolve to nullptr;
        // class_addProtocol tolerates that and the selectors are simply
        // never called.
        {
            Protocol *nav = objc_getProtocol("WKNavigationDelegate");
            if (nav) class_addProtocol(c, nav);
            Protocol *dl = objc_getProtocol("WKDownloadDelegate");
            if (dl) class_addProtocol(c, dl);
        }
        class_addMethod(
            c,
            sel("webView:decidePolicyForNavigationResponse:"
                "decisionHandler:"),
            (IMP)impl_decide_policy_for_navigation_response, "v@:@@@");
        // Canvas 34 D8: the navigation decision (and WebKit's default,
        // reproduced, when it allows).
        class_addMethod(
            c,
            sel("webView:decidePolicyForNavigationAction:"
                "decisionHandler:"),
            (IMP)impl_decide_policy_for_navigation_action, "v@:@@@");
        class_addMethod(
            c,
            sel("webView:navigationAction:didBecomeDownload:"),
            (IMP)impl_navigation_action_did_become_download, "v@:@@@");
        class_addMethod(
            c,
            sel("webView:navigationResponse:didBecomeDownload:"),
            (IMP)impl_navigation_response_did_become_download, "v@:@@@");
        class_addMethod(
            c,
            sel("download:decideDestinationUsingResponse:"
                "suggestedFilename:completionHandler:"),
            (IMP)impl_download_decide_destination, "v@:@@@@");
        class_addMethod(
            c,
            sel("downloadDidFinish:"),
            (IMP)impl_download_did_finish, "v@:@");
        class_addMethod(
            c,
            sel("download:didFailWithError:resumeData:"),
            (IMP)impl_download_did_fail, "v@:@@@");
        objc_registerClassPair(c);
        g_webview_embed_ui_delegate_cls = c;
    });
    return g_webview_embed_ui_delegate_cls;
}

// Register (or clear, when cb is null) the Java WebViewDialogCallback
// for this engine.  Mirrors cocoa_set_focus_callback /
// cocoa_set_click_callback.  The WKUIDelegate IMPs above read
// e->dialog_callback on every dispatch, so installing the global ref
// here is the single place that wires Java handler into the selector
// hot path.
static void cocoa_set_dialog_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

// ---------------------------------------------------------------------------
// Password-manager bridge (Canvas 26).
//
// The injected PasswordDispatcher.SHIM_JS posts to the reserved
// "__webview_pw__" script-message channel:
//   "S|<b64user>|<b64pass>"  a login form was submitted
//   "F"                      the page is ready and requests autofill
// A dedicated WKScriptMessageHandler reads the committed frame URL
// natively (message.frameInfo / webView.URL) -- the trusted origin source,
// never a value from the JS payload -- and invokes the Java
// WebViewPasswordCallback.  The callback is void (non-blocking): the Java
// PasswordDispatcher marshals the save prompt to the EDT and runs store
// I/O on a worker, so AppKit main is not parked.
//
// The two fire helpers use pure JNI (no Cocoa/GLib) and mirror
// fire_dialog_alert's shape: defensive attach + detach-if-attached,
// per-call GetMethodID, ExceptionCheck/Clear after Call*Method.
// ---------------------------------------------------------------------------

static void fire_password_submitted(JavaVM *jvm, jobject callback,
                                    const char *frameUrl,
                                    const char *b64User,
                                    const char *b64Pass) {
    if (!jvm || !callback) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env) {
            return;
        }
        detach = true;
    }
    if (!env) { if (detach) jvm->DetachCurrentThread(); return; }
    jstring jurl = env->NewStringUTF(frameUrl ? frameUrl : "");
    jstring juser = env->NewStringUTF(b64User ? b64User : "");
    jstring jpass = env->NewStringUTF(b64Pass ? b64Pass : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onLoginSubmitted",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jurl, juser, jpass);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jurl) env->DeleteLocalRef(jurl);
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
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env) {
            return;
        }
        detach = true;
    }
    if (!env) { if (detach) jvm->DetachCurrentThread(); return; }
    jstring jurl = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(cls, "onFillRequested",
                                       "(Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jurl);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jurl) env->DeleteLocalRef(jurl);
    if (detach) jvm->DetachCurrentThread();
}

// Parse a "__webview_pw__" payload and fire the matching callback.  The
// origin comes from frameUrl (native-stamped), never the payload.
static void handle_password_message(Engine *e, const std::string &payload,
                                    const std::string &frameUrl) {
    if (!e || !e->password_callback) return;
    if (payload.empty()) return;
    if (payload[0] == 'F') {
        fire_password_fill_requested(e->jvm, e->password_callback,
                                     frameUrl.c_str());
        return;
    }
    if (payload.size() >= 2 && payload[0] == 'S' && payload[1] == '|') {
        size_t p1 = 2;
        size_t p2 = payload.find('|', p1);
        std::string b64user = (p2 == std::string::npos)
            ? payload.substr(p1) : payload.substr(p1, p2 - p1);
        std::string b64pass = (p2 == std::string::npos)
            ? std::string() : payload.substr(p2 + 1);
        fire_password_submitted(e->jvm, e->password_callback,
                                frameUrl.c_str(), b64user.c_str(),
                                b64pass.c_str());
    }
}

static std::once_flag g_webview_pw_delegate_once;
static Class g_webview_pw_delegate_cls = nil;

// Dedicated WKScriptMessageHandler for the "__webview_pw__" channel.  Reads
// the committed frame URL natively (frameInfo.request.URL, falling back to
// webView.URL) and hands it to Java as the trusted origin.
static Class get_webview_pw_delegate_cls() {
    std::call_once(g_webview_pw_delegate_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewPwDelegate", 0);
        class_addProtocol(c, objc_getProtocol("WKScriptMessageHandler"));
        class_addMethod(
            c,
            sel("userContentController:didReceiveScriptMessage:"),
            (IMP)(+[](id self, SEL, id, id m) {
                Engine *eng = (Engine *)objc_getAssociatedObject(self, "eng");
                if (!eng) return;
                id body = msg(m, sel("body"));
                if (!body) return;
                const char *s = msg<const char *>(body, sel("UTF8String"));
                if (!s) return;
                std::string payload(s);
                std::string frameUrl = frame_url_utf8(
                    msg(m, sel("frameInfo")), msg(m, sel("webView")));
                handle_password_message(eng, payload, frameUrl);
            }),
            "v@:@@");
        objc_registerClassPair(c);
        g_webview_pw_delegate_cls = c;
    });
    return g_webview_pw_delegate_cls;
}

// Register (or clear, when cb is null) the Java WebViewPasswordCallback for
// this engine.  Mirrors cocoa_set_dialog_callback.
static void cocoa_set_password_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->password_callback) {
        env->DeleteGlobalRef(e->password_callback);
        e->password_callback = nullptr;
    }
    if (cb) {
        e->password_callback = env->NewGlobalRef(cb);
    }
}

// ---------------------------------------------------------------------------
// Mouse-down hook on WKWebView.
//
// We swizzle -[WKWebView mouseDown:], -[WKWebView rightMouseDown:], and
// -[WKWebView otherMouseDown:] so we can notify Java each time the user
// presses any mouse button inside the WebView.  This is the macOS half of
// the cross-platform native click hook that drives Swing-side popup
// dismissal: AWT's BasicPopupMenuUI MouseGrabber listener never sees
// clicks that land in the WKWebView because they reach it via the AppKit
// responder chain rather than through AWT's event queue, so an open
// JPopupMenu would otherwise stay open when the user clicked the
// WebView.
//
// Each swizzled implementation calls the original IMP FIRST so WebKit's
// normal click handling (link clicks, text selection, form interaction,
// becomeFirstResponder) is unaffected; the click callback fires
// afterwards.  Swizzling is class-wide: any WKWebView in the process is
// affected, including ones the host application created independently.
// The g_webview_map lookup returns nullptr for those and we silently
// skip the callback -- behaviour for unrelated WKWebViews is preserved.
//
// The Java callback runs on whatever thread AppKit drove the click on
// (the AppKit main thread for normal user input); Java-side callers MUST
// marshal to the EDT before touching Swing state.  See WebViewClickCallback
// for the contract.
// ---------------------------------------------------------------------------

typedef void (*VoidFromIdSelEvent)(id, SEL, id);
static VoidFromIdSelEvent g_orig_mouseDown = nullptr;
static VoidFromIdSelEvent g_orig_rightMouseDown = nullptr;
static VoidFromIdSelEvent g_orig_otherMouseDown = nullptr;
static std::once_flag g_click_swizzle_once;

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

static void swizzled_mouse_down(id self, SEL _cmd, id event) {
    if (g_orig_mouseDown) g_orig_mouseDown(self, _cmd, event);
    Engine *eng = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        auto it = g_webview_map.find(self);
        if (it != g_webview_map.end()) eng = it->second;
    }
    if (eng) fire_click_callback(eng);
}

static void swizzled_right_mouse_down(id self, SEL _cmd, id event) {
    if (g_orig_rightMouseDown) g_orig_rightMouseDown(self, _cmd, event);
    Engine *eng = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        auto it = g_webview_map.find(self);
        if (it != g_webview_map.end()) eng = it->second;
    }
    if (eng) fire_click_callback(eng);
}

static void swizzled_other_mouse_down(id self, SEL _cmd, id event) {
    if (g_orig_otherMouseDown) g_orig_otherMouseDown(self, _cmd, event);
    Engine *eng = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        auto it = g_webview_map.find(self);
        if (it != g_webview_map.end()) eng = it->second;
    }
    if (eng) fire_click_callback(eng);
}

static void install_click_swizzle() {
    std::call_once(g_click_swizzle_once, [] {
        Class wk = (Class)objc_cls("WKWebView");
        if (!wk) return;
        Method down = class_getInstanceMethod(wk, sel("mouseDown:"));
        Method rdown = class_getInstanceMethod(wk, sel("rightMouseDown:"));
        Method odown = class_getInstanceMethod(wk, sel("otherMouseDown:"));
        if (!down || !rdown || !odown) return;
        g_orig_mouseDown = (VoidFromIdSelEvent)method_setImplementation(
            down, (IMP)swizzled_mouse_down);
        g_orig_rightMouseDown = (VoidFromIdSelEvent)method_setImplementation(
            rdown, (IMP)swizzled_right_mouse_down);
        g_orig_otherMouseDown = (VoidFromIdSelEvent)method_setImplementation(
            odown, (IMP)swizzled_other_mouse_down);
    });
}

// Walk the responder chain from [window firstResponder] upward through
// superview looking for e->webview.  Runs on the AppKit main thread
// (invoked from the KVO observer below and from the post-window-attach
// recompute path).  Identical logic to the previous synchronous
// cocoa_is_first_responder probe -- correctly handles inner WebKit
// content views (NSResponder subclasses inside WKWebView) by walking
// up the view hierarchy until it either reaches e->webview (match) or
// runs out of superviews (no match).  Stores the result into
// e->is_first_responder so cocoa_is_first_responder can read it
// lock-free with no thread hop.
static void cocoa_recompute_first_responder_on_main(Engine *e) {
    if (!e || !e->webview) {
        if (e) e->is_first_responder.store(false);
        return;
    }
    id window = msg(e->webview, sel("window"));
    if (!window) {
        e->is_first_responder.store(false);
        return;
    }
    id fr = msg(window, sel("firstResponder"));
    if (!fr) {
        e->is_first_responder.store(false);
        return;
    }
    SEL is_kind_of_view = sel("isKindOfClass:");
    id view_cls = objc_cls("NSView");
    if (!msg<BOOL, id>(fr, is_kind_of_view, view_cls)) {
        // First responder isn't an NSView (e.g. NSWindow itself) -- not us.
        e->is_first_responder.store(false);
        return;
    }
    for (id v = fr; v; v = msg(v, sel("superview"))) {
        if (v == e->webview) {
            e->is_first_responder.store(true);
            return;
        }
    }
    e->is_first_responder.store(false);
}

// Returns 1 if the engine's WKWebView (or a descendant view in its
// subview hierarchy -- WebKit content view, etc.) is currently the
// first responder of its NSWindow.  Lock-free read of the mirrored
// state maintained by the KVO observer registered on
// NSWindow.firstResponder; no AppKit-main-thread hop, no deadlock
// surface.  Safe to call from any thread, but in practice fired only
// from the EDT (the editing-shortcut dispatcher in
// WebViewHeavyweightComponent).
static int cocoa_is_first_responder(Engine *e) {
    if (!e) return 0;
    return e->is_first_responder.load() ? 1 : 0;
}

// ---------------------------------------------------------------------------
// KVO observer for NSWindow.firstResponder + WKWebView.window.
//
// The Engine struct holds an std::atomic<bool> is_first_responder that
// mirrors AppKit-main-thread state for lock-free EDT reads.  Two key paths
// drive updates:
//   1. NSWindow.firstResponder fires for every focus transition anywhere
//      in the host window's responder hierarchy.  Each firing walks the
//      responder chain via cocoa_recompute_first_responder_on_main and
//      stores the result in the atomic.
//   2. WKWebView.window fires when the WKWebView is added to / removed
//      from / moved between NSWindows.  When the property goes non-nil,
//      we register the firstResponder observer on the new window and
//      recompute immediately.  When it changes to a different window we
//      unregister from the previous window and register on the new one.
//      When it goes nil (e.g. just before removeFromSuperview during
//      destroy) we unregister and clear the cache.
//
// The Objective-C class is registered exactly once per JVM via
// std::call_once -- same pattern as the existing WKWebView swizzles and
// the WebviewEmbedDelegate class registration.  Each engine creates one
// instance of the class and stores the back-pointer to its owning Engine*
// via objc_setAssociatedObject (OBJC_ASSOCIATION_ASSIGN -- Engine is not
// an Obj-C object, so the runtime won't try to retain/release it).
// ---------------------------------------------------------------------------

static std::once_flag g_kvo_observer_once;
static Class g_kvo_observer_cls = nil;

static const char KVO_ENGINE_KEY[] = "eng";
// Distinct context pointers let observeValueForKeyPath: tell the two
// key paths apart without string-comparing the keypath every fire.
static int kvo_ctx_first_responder = 0;
static int kvo_ctx_window = 0;

// Forward declaration: defined after cocoa_destroy_engine so the
// KVO observer can reference engine teardown helpers.
static void cocoa_kvo_register_on_window(Engine *e, id window);
static void cocoa_kvo_unregister_from_window(Engine *e);

static void kvo_observe_impl(id self, SEL /*_cmd*/, id /*keyPath*/,
                             id /*object*/, id /*change*/, void *context) {
    auto *e = (Engine *)objc_getAssociatedObject(self, KVO_ENGINE_KEY);
    if (!e) return;
    if (e->destroyed.load()) return;
    if (context == &kvo_ctx_first_responder) {
        cocoa_recompute_first_responder_on_main(e);
    } else if (context == &kvo_ctx_window) {
        // WKWebView's window keypath changed.  Re-aim the firstResponder
        // observer at the new window (or unregister if nil) and
        // recompute.
        id new_window = e->webview ? msg(e->webview, sel("window"))
                                   : (id)nullptr;
        if (new_window != e->observed_window) {
            cocoa_kvo_unregister_from_window(e);
            if (new_window) {
                cocoa_kvo_register_on_window(e, new_window);
            }
        }
        cocoa_recompute_first_responder_on_main(e);
    }
}

static void ensure_kvo_observer_class() {
    std::call_once(g_kvo_observer_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedKvoObserver", 0);
        // observeValueForKeyPath:ofObject:change:context: --
        // signature "v@:@@@^v"
        //   v   void
        //   @   id self
        //   :   SEL _cmd
        //   @   id keyPath  (NSString)
        //   @   id object
        //   @   id change   (NSDictionary)
        //   ^v  void *context
        class_addMethod(
            c,
            sel("observeValueForKeyPath:ofObject:change:context:"),
            (IMP)kvo_observe_impl,
            "v@:@@@^v");
        objc_registerClassPair(c);
        g_kvo_observer_cls = c;
    });
}

// Register the engine's KVO observer against `window`'s firstResponder
// key path.  Caller MUST hold the AppKit main thread.  Idempotent
// against repeated registration on the same window (the second call
// is a no-op).
static void cocoa_kvo_register_on_window(Engine *e, id window) {
    if (!e || !window) return;
    if (e->observed_window == window) return;
    if (!e->kvo_observer) return;
    msg<void, id, id, unsigned long, void *>(
        window,
        sel("addObserver:forKeyPath:options:context:"),
        e->kvo_observer,
        ns_str("firstResponder"),
        (unsigned long)0,           // no NSKeyValueObservingOptions flags
        &kvo_ctx_first_responder);
    e->observed_window = window;
}

// Unregister the engine's KVO observer from its currently-observed
// window (if any).  Caller MUST hold the AppKit main thread.
static void cocoa_kvo_unregister_from_window(Engine *e) {
    if (!e || !e->observed_window || !e->kvo_observer) return;
    msg<void, id, id, void *>(
        e->observed_window,
        sel("removeObserver:forKeyPath:context:"),
        e->kvo_observer,
        ns_str("firstResponder"),  // forKeyPath:
        &kvo_ctx_first_responder); // context:
    // Template params: (Ret=void, Args = id observer, id keyPath, void* context)
    e->observed_window = nullptr;
}

// Install the engine's KVO observer.  Allocates the observer object,
// associates it with the Engine pointer, attaches the WKWebView.window
// observer (so we react to window changes), and -- if the WKWebView
// already has a window -- registers the firstResponder observer on
// that window and seeds the atomic.  Caller MUST hold the AppKit main
// thread.
static void cocoa_kvo_install(Engine *e) {
    if (!e || !e->webview) return;
    ensure_kvo_observer_class();
    id observer = msg((id)g_kvo_observer_cls, sel("new"));
    if (!observer) return;
    objc_setAssociatedObject(observer, KVO_ENGINE_KEY, (id)e,
                             OBJC_ASSOCIATION_ASSIGN);
    e->kvo_observer = observer;
    msg<void, id, id, unsigned long, void *>(
        e->webview,
        sel("addObserver:forKeyPath:options:context:"),
        observer,
        ns_str("window"),
        (unsigned long)0,
        &kvo_ctx_window);
    id w = msg(e->webview, sel("window"));
    if (w) {
        cocoa_kvo_register_on_window(e, w);
        cocoa_recompute_first_responder_on_main(e);
    }
}

// Tear down the engine's KVO observer.  Reverses cocoa_kvo_install;
// caller MUST hold the AppKit main thread, and MUST call this BEFORE
// any view release in the destroy lambda (AppKit logs a warning and
// may crash if an observed object is released while observers are
// still registered).
static void cocoa_kvo_teardown(Engine *e) {
    if (!e || !e->kvo_observer) return;
    cocoa_kvo_unregister_from_window(e);
    if (e->webview) {
        msg<void, id, id, void *>(
            e->webview,
            sel("removeObserver:forKeyPath:context:"),
            e->kvo_observer,
            ns_str("window"),         // forKeyPath:
            &kvo_ctx_window);         // context:
    }
    objc_setAssociatedObject(e->kvo_observer, KVO_ENGINE_KEY, (id)nullptr,
                             OBJC_ASSOCIATION_ASSIGN);
    msg<void>(e->kvo_observer, sel("release"));
    e->kvo_observer = nullptr;
}

// ---------------------------------------------------------------------------
// Attach-completion callback.
//
// The Java side registers an AttachCallback bridge object via
// cocoa_set_attach_callback before the EmbeddedWebView factory returns.
// The macOS async attach epilogue inside cocoa_create_engine, on
// success or failure, calls cocoa_attach_signal_complete to publish the
// outcome.  Two ordering cases:
//   (a) Java registers the callback BEFORE the async epilogue fires
//       (typical case): cocoa_set_attach_callback stores the global ref;
//       cocoa_attach_signal_complete sees the stored callback and fires
//       it from the main thread.
//   (b) Java registers the callback AFTER the async epilogue fires
//       (race window narrow but possible): the async epilogue stores the
//       resolution state without anyone to call; cocoa_set_attach_callback
//       sees attach_resolved==true and fires the callback from the EDT.
// Both paths are guarded by attach_callback_mutex.  The callback fires
// exactly once -- both paths null out the callback fields after firing.
// ---------------------------------------------------------------------------

// Caller MUST hold e->attach_callback_mutex.  Invokes the registered
// Java callback's onResolved(boolean, String) method and clears the
// stored callback fields.  Attaches the current thread to the JVM if
// needed (mirrors the fire_focus_callback pattern).
static void cocoa_attach_invoke_callback_locked(Engine *e) {
    if (!e || !e->attach_callback || !e->attach_callback_cls) return;
    bool ok = e->attach_ok;
    std::string msg_text = e->attach_failure_message;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jmethodID m = env->GetMethodID(
            e->attach_callback_cls, "onResolved",
            "(ZLjava/lang/String;)V");
        if (m) {
            jstring js = (!ok && !msg_text.empty())
                ? env->NewStringUTF(msg_text.c_str()) : nullptr;
            env->CallVoidMethod(e->attach_callback, m,
                                (jboolean)(ok ? JNI_TRUE : JNI_FALSE), js);
            if (js) env->DeleteLocalRef(js);
        }
        env->DeleteGlobalRef(e->attach_callback);
        env->DeleteGlobalRef(e->attach_callback_cls);
    }
    e->attach_callback = nullptr;
    e->attach_callback_cls = nullptr;
    if (detach) jvm->DetachCurrentThread();
}

// Called from the async attach epilogue inside cocoa_create_engine
// (on the AppKit main thread).  Records the outcome and fires the
// callback if one is already registered.
static void cocoa_attach_signal_complete(Engine *e, bool ok,
                                         std::string failure_message) {
    if (!e) return;
    std::lock_guard<std::mutex> lk(e->attach_callback_mutex);
    if (e->attach_resolved) return;  // idempotent
    e->attach_resolved = true;
    e->attach_ok = ok;
    e->attach_failure_message = std::move(failure_message);
    if (e->attach_callback) {
        cocoa_attach_invoke_callback_locked(e);
    }
}

// Java entry point: register the AttachCallback bridge object.  Called
// from EmbeddedWebView.attach on the EDT immediately after
// webview_embed_create returns.  Stores a JNI global ref; if the
// async attach epilogue has already completed (case (b) above), fires
// the callback immediately.
static void cocoa_set_attach_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e || !env) return;
    std::lock_guard<std::mutex> lk(e->attach_callback_mutex);
    // If we already have a callback registered, drop the old one
    // (defensive -- the Java factory wires this exactly once, but make
    // the contract robust).
    if (e->attach_callback) {
        env->DeleteGlobalRef(e->attach_callback);
        e->attach_callback = nullptr;
    }
    if (e->attach_callback_cls) {
        env->DeleteGlobalRef(e->attach_callback_cls);
        e->attach_callback_cls = nullptr;
    }
    if (cb) {
        e->attach_callback = env->NewGlobalRef(cb);
        jclass cls = env->GetObjectClass(cb);
        e->attach_callback_cls = (jclass)env->NewGlobalRef(cls);
        env->DeleteLocalRef(cls);
    }
    if (e->attach_resolved && e->attach_callback) {
        cocoa_attach_invoke_callback_locked(e);
    }
}

static void cocoa_set_focus_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->focus_callback) {
        env->DeleteGlobalRef(e->focus_callback);
        e->focus_callback = nullptr;
    }
    if (cb) {
        e->focus_callback = env->NewGlobalRef(cb);
    }
}

// Register (or clear, when cb is null) the Java WebViewClickCallback for
// this engine.  The swizzled mouseDown: / rightMouseDown: /
// otherMouseDown: implementations call fire_click_callback for any
// WKWebView found in g_webview_map; that function reads this field, so
// installing the global ref here is the single place that wires Java
// callbacks into the swizzled hot path.  Mirrors cocoa_set_focus_callback.
static void cocoa_set_click_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->click_callback) {
        env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
    }
    if (cb) {
        e->click_callback = env->NewGlobalRef(cb);
    }
}

// Synchronous prologue + async epilogue.  Returns the freshly-allocated
// Engine* (cast to jlong by the JNI export) immediately after the JAWT
// surface-layers handoff; the WKWebView creation, host-NSView discovery,
// addSubview:, configuration, and KVO observer install run later on the
// AppKit main thread.  The async epilogue calls cocoa_attach_signal_
// complete on completion to drive the Java-side AttachState transition.
//
// Only synchronous failure (JAWT lock failure) returns nullptr; every
// other failure surfaces via the attach-completion callback's onResolved
// (false, "<message>") path so the Java side can observe it through a
// registered WebViewAttachListener.
//
// The C++ Engine struct itself is NOT deleted on async-attach failure --
// the contract is that the Java side owns the EmbeddedWebView wrapper
// and will eventually call dispose() (typically via removeNotify in the
// heavyweight component), which triggers cocoa_destroy_engine to free
// the Engine.  The async failure path therefore leaves the Engine in a
// partially-initialised state but with destroyed==false, so the user
// can still call dispose() to clean up.  destroyed is set to true only
// inside cocoa_destroy_engine.
static Engine *cocoa_create_engine(JNIEnv *env, jobject parentComponent,
                                   jlong /*display*/, jint debug) {
    auto *e = new Engine();
    // Java owns this engine's lifecycle: EmbeddedWebView.dispose() ->
    // webview_embed_destroy -> cocoa_destroy_engine frees it exactly once.
    // A page-initiated window.close() must never free it out from under Java.
    e->java_owned = true;
    env->GetJavaVM(&e->jvm);
    e->debug = debug != 0;

    // Resolve the JAWT surface layers object up front, then release the
    // surface lock immediately -- holding it across a hop to the AppKit
    // main thread (the async epilogue below) could deadlock with
    // AppKit's redraw loop.  This is the only step that requires the
    // calling thread's JNIEnv, so it stays in the synchronous prologue.
    if (parentComponent) {
        JawtLock lock(env, parentComponent);
        if (!lock.ok) {
            delete e;
            return nullptr;
        }
        // On modern JDKs the platformInfo conforms to JAWT_SurfaceLayers and
        // exposes a 'layer' property we can populate.  Retain the id so it
        // survives any AWT-side recreation between attach and destroy.
        e->surface_layers = (id)lock.dsi->platformInfo;
        if (e->surface_layers) {
            msg<void>(e->surface_layers, sel("retain"));
        }
    }

    // Async epilogue: AppKit-side setup on the main thread.  Captures e
    // by value (pointer); never blocks the EDT.  dispatch_get_main_queue
    // is serial FIFO, so subsequent cocoa_navigate / cocoa_eval / etc.
    // blocks enqueued by the EDT before the user's first listener call
    // are guaranteed to fire AFTER this block on the main thread.  Each
    // of those blocks already null-checks e->webview / e->manager and
    // silently no-ops if cleared -- so an op enqueued before the
    // epilogue creates the WKWebView simply finds it ready by the time
    // it fires.
    cocoa_run_on_main_async([e] {
        if (e->destroyed.load()) {
            // The user called dispose() between the prologue returning
            // and this block firing.  The destroy lambda is enqueued
            // AFTER this block; bail without doing AppKit-side work so
            // the destroy lambda has nothing to tear down.
            cocoa_attach_signal_complete(e, false,
                "EmbeddedWebView disposed before attach completed");
            return;
        }
        // Install the WKWebView first-responder swizzle once per JVM
        // BEFORE creating the WKWebView, so any becomeFirstResponder
        // calls during init are routed through our hook from the start.
        install_focus_swizzle();
        // Same reasoning for the mouseDown: / rightMouseDown: /
        // otherMouseDown: swizzle that drives native click notifications
        // back to Swing (used for outside-click popup dismissal -- see
        // Operation 13 of the heavyweight-embedding Canvas).
        install_click_swizzle();

        e->config = msg(objc_cls("WKWebViewConfiguration"), sel("new"));
        if (!e->surface_layers) {
            msg<void, id>(e->config, sel("setWebsiteDataStore:"),
                msg(objc_cls("WKWebsiteDataStore"), sel("nonPersistentDataStore")));
        }
        // Canvas 30 D13: custom schemes must be on the configuration before
        // the WKWebView is created from it; popups inherit them from it.
        cocoa_install_schemes(e->config);
        e->manager = msg(e->config, sel("userContentController"));
        id wv = msg(objc_cls("WKWebView"), sel("alloc"));
        wv = msg<id, CGRect, id>(
            wv, sel("initWithFrame:configuration:"),
            CGRectMake(0, 0, 800, 600), e->config);
        if (!wv) {
            // Release the partial AppKit allocations so they don't leak
            // before reporting the failure.  e->manager is an
            // autoreleased getter from config so does not need explicit
            // release; e->config does.
            e->manager = nullptr;
            if (e->config) {
                msg<void>(e->config, sel("release"));
                e->config = nullptr;
            }
            cocoa_attach_signal_complete(e, false,
                "WKWebView allocation failed");
            return;
        }
        e->webview = wv;

        // Register the WKWebView in the engine map so the swizzled
        // responder hooks can find their Engine pointer.
        {
            std::lock_guard<std::mutex> lk(g_webview_map_mutex);
            g_webview_map[e->webview] = e;
        }

        // Find a hostable NSView.  Walk up the windowLayer's superlayer
        // chain and use whatever NSView class we encounter to reach the
        // owning NSWindow.contentView -- the layer-only AWT design used by
        // Corretto 8 macOS arm64 has no per-Canvas AWT NSView, so we
        // attach the WKWebView to contentView and convert windowLayer's
        // frame on every setBounds to keep it overlaid on the canvas.  If
        // we happen to find an AWT-named view (other JDK layouts) we use
        // it directly; coordinates there are already canvas-relative.
        id ns_view_cls = objc_cls("NSView");
        SEL is_kind_of_class = sel("isKindOfClass:");
        id window_layer = e->surface_layers
            ? msg(e->surface_layers, sel("windowLayer"))
            : (id)nullptr;
        id host = nullptr;
        const char *host_kind = "(none)";
        bool host_is_awt = false;
        for (id l = window_layer; l; l = msg(l, sel("superlayer"))) {
            id ld = msg(l, sel("delegate"));
            if (!ld) continue;
            if (!msg<BOOL>(ld, is_kind_of_class, ns_view_cls)) continue;
            Class cls = object_getClass(ld);
            const char *cls_name = cls ? class_getName(cls) : "<unknown>";
            if (std::strstr(cls_name, "AWT") != nullptr) {
                host = ld;
                host_kind = cls_name;
                host_is_awt = true;
                break;
            }
            // Not an AWT view -- treat this as a stepping stone to
            // NSWindow.contentView.  We don't break here because a later
            // (further-up) layer might have an AWT NSView delegate.
            if (host == nullptr) {
                id window = msg(ld, sel("window"));
                if (window) {
                    id cv = msg(window, sel("contentView"));
                    if (cv) {
                        host = cv;
                        host_kind = "NSWindow.contentView";
                    }
                }
            }
            EMBED_LOG(
                "[webview-embed] Found NSView %s; %s\n", cls_name,
                host_is_awt ? "using directly" :
                (host ? "deferring to NSWindow.contentView" : "no window"));
        }

        if (host != nullptr) {
            msg<void>(host, sel("retain"));
            e->host_view = host;
            e->host_is_awt = host_is_awt;
            // contentView is often not layer-backed until something forces
            // it; make sure it is so AppKit composites WKWebView properly.
            msg<void, BOOL>(host, sel("setWantsLayer:"), YES);
            msg<void, id>(host, sel("addSubview:"), e->webview);
            EMBED_LOG(
                "[webview-embed] WKWebView added as subview of %s at %p\n",
                host_kind, host);
            // Hide the WKWebView until the first setBounds positions it
            // correctly; otherwise the placeholder 800x600 frame from init
            // shows up at the bottom of contentView (Cocoa origin) and
            // briefly covers the URL bar.
            msg<void, CGRect>(e->webview, sel("setFrame:"),
                              CGRectMake(0, 0, 0, 0));
        } else if (e->surface_layers) {
            // Layer-only fallback (won't render WKWebView content, but
            // keeps the API surface intact for layer-friendly engines).
            msg<void, BOOL>(e->webview, sel("setWantsLayer:"), YES);
            id layer = msg(e->webview, sel("layer"));
            msg<void, id>(e->surface_layers, sel("setLayer:"), layer);
            fprintf(stderr,
                "[webview-embed] WARNING: could not locate any host NSView; "
                "falling back to layer-only attach. WKWebView content will "
                "not render in this mode.\n");
        }

        // External-message bridge: register a script message handler.
        // The ObjC delegate class is registered exactly once per JVM (see
        // get_webview_embed_delegate_cls); every engine instantiates a
        // fresh delegate object from the cached Class.
        Class delegate_cls = get_webview_embed_delegate_cls();
        id delegate = msg((id)delegate_cls, sel("new"));
        objc_setAssociatedObject(delegate, "eng", (id)e, OBJC_ASSOCIATION_ASSIGN);
        msg<void, id, id>(e->manager,
                          sel("addScriptMessageHandler:name:"), delegate,
                          ns_str("external"));

        // Password-manager bridge: a dedicated script-message handler for
        // the "__webview_pw__" channel that the injected
        // PasswordDispatcher.SHIM_JS posts login-submission / fill-request
        // messages to.  Reads the committed frame URL natively (the trusted
        // origin).  Removed in cocoa_destroy_engine alongside "external".
        Class pw_delegate_cls = get_webview_pw_delegate_cls();
        id pw_delegate = msg((id)pw_delegate_cls, sel("new"));
        objc_setAssociatedObject(pw_delegate, "eng", (id)e,
                                 OBJC_ASSOCIATION_ASSIGN);
        msg<void, id, id>(e->manager,
                          sel("addScriptMessageHandler:name:"), pw_delegate,
                          ns_str("__webview_pw__"));

        // Browser-dialog bridge: install a WKUIDelegate so JS-initiated
        // alert / confirm / prompt and <input type=file> requests flow
        // through Java (DialogDispatcher → WebViewDialogHandler) instead
        // of being silently dropped (default behaviour when uiDelegate
        // is nil).  Each engine constructs a fresh delegate object from
        // the cached Class so the per-engine Engine pointer can be
        // stashed via objc_setAssociatedObject for the selector IMPs to
        // recover.
        Class ui_delegate_cls = get_webview_embed_ui_delegate_cls();
        id ui_delegate = msg((id)ui_delegate_cls, sel("new"));
        objc_setAssociatedObject(
            ui_delegate, "eng", (id)e, OBJC_ASSOCIATION_ASSIGN);
        msg<void, id>(e->webview, sel("setUIDelegate:"), ui_delegate);
        // Download bridge -- Canvas 23.  The same object also serves as
        // the navigationDelegate (previously nil): WKWebView only
        // produces a WKDownload when the navigation-response policy
        // says so, and that policy method lives on WKNavigationDelegate.
        msg<void, id>(e->webview, sel("setNavigationDelegate:"), ui_delegate);
        // We hold the only strong ref to the delegate (the WKWebView's
        // uiDelegate is a weak reference per WebKit convention).  Stash
        // it on the engine so cocoa_destroy_engine can release it.
        e->ui_delegate = ui_delegate;
        // Install the external.invoke shim.
        id script = msg(objc_cls("WKUserScript"), sel("alloc"));
        script = msg<id, id, long, BOOL>(
            script, sel("initWithSource:injectionTime:forMainFrameOnly:"),
            ns_str("window.external={invoke:function(s){"
                   "window.webkit.messageHandlers.external.postMessage(s);}};"),
            (long)0 /* WKUserScriptInjectionTimeAtDocumentStart */,
            YES);
        msg<void, id>(e->manager, sel("addUserScript:"), script);

        if (e->debug) {
            id prefs = msg(e->config, sel("preferences"));
            id one = msg<id, BOOL>(objc_cls("NSNumber"),
                                   sel("numberWithBool:"), YES);
            msg<void, id, id>(prefs, sel("setValue:forKey:"),
                              one, ns_str("developerExtrasEnabled"));
            // macOS 13.3+ exposes -[WKWebView setInspectable:] as a
            // public BOOL property; enabling it exposes the Web
            // Inspector via the Safari Develop menu (remote inspection)
            // and is required for right-click -> Inspect Element to
            // function on those OS versions.  On macOS 12.x and earlier
            // the selector does not exist and is silently skipped --
            // the legacy developerExtrasEnabled flag alone is
            // sufficient for in-process inspection there.
            if (msg<BOOL>(e->webview, sel("respondsToSelector:"),
                          sel("setInspectable:"))) {
                msg<void, BOOL>(e->webview, sel("setInspectable:"), YES);
            }
        }

        // Install the KVO observer on the WKWebView's window keypath --
        // when the WKWebView lands in an NSWindow, the observer
        // registers a firstResponder observer on that window and seeds
        // the cached atomic so cocoa_is_first_responder can read it
        // lock-free.  See cocoa_kvo_install above.
        cocoa_kvo_install(e);

        // Signal attach completion.  cocoa_attach_signal_complete fires
        // the Java AttachCallback immediately if one is already
        // registered (typical case -- the Java factory installs the
        // callback synchronously before any wider event-loop time slice
        // elapses); otherwise it just stores the resolution, and
        // cocoa_set_attach_callback fires it when the registration
        // arrives.
        cocoa_attach_signal_complete(e, true, "");
    });
    return e;
}

// Canvas 18: reparent a retained popup child (created windowless by
// impl_create_web_view's ADOPT branch) into parentComponent's realized native
// surface, promoting it to a normal embedded engine.  Returns the child
// Engine* (as cocoa_create_engine would) or nullptr when popupId is unknown /
// already adopted, or the JAWT surface cannot be resolved.  Reuses the exact
// host-view walk + addSubview + KVO + attach-signal from cocoa_create_engine's
// epilogue; it deliberately does NOT re-create the WKWebView / config / UI
// delegate — the retained child already carries them (and its in-flight POST
// navigation) from impl_create_web_view.  The eval / function bridges are
// installed by the Java EmbeddedWebView.adopt wrapper (webview_embed_init /
// _bind) after this returns.
//
// ON-DEVICE VALIDATION REQUIRED: the reparent retain/release balance and the
// first-responder handoff are prime zombie/Instruments candidates (Canvas 18
// Safeguards); this file has no native toolchain in the generating sandbox.
static Engine *cocoa_adopt_popup(JNIEnv *env, jobject parentComponent,
                                 jlong popupId, jint /*debug*/) {
    // Claim the retained child (adopt-once): remove under lock so a second
    // adopt of the same id finds nothing and returns 0.
    Engine *e = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_retained_popups_mutex);
        auto it = g_retained_popups.find(popupId);
        if (it == g_retained_popups.end()) return nullptr;
        e = it->second;
        g_retained_popups.erase(it);
    }
    if (!e) return nullptr;

    // Adoption transfers lifecycle ownership to the adopting Java
    // EmbeddedWebView: from here the child is freed exactly once, by
    // cocoa_destroy_engine (dispose()).  A later window.close() on the
    // adopted child (impl_web_view_did_close) MUST NOT free it.
    e->java_owned = true;

    // Resolve the JAWT surface layers for the new parent (mirrors the
    // cocoa_create_engine prologue), then release the surface lock before the
    // async main-thread epilogue.
    {
        JawtLock lock(env, parentComponent);
        if (!lock.ok) {
            // Could not attach; put the child back so it is reclaimable and
            // not lost.
            std::lock_guard<std::mutex> lk(g_retained_popups_mutex);
            g_retained_popups[popupId] = e;
            return nullptr;
        }
        if (e->surface_layers) {
            msg<void>(e->surface_layers, sel("release"));
            e->surface_layers = nullptr;
        }
        e->surface_layers = (id)lock.dsi->platformInfo;
        if (e->surface_layers) {
            msg<void>(e->surface_layers, sel("retain"));
        }
    }

    // Async epilogue on the AppKit main thread: reparent the retained child's
    // WKWebView into the resolved host view and finish attach.  The host-view
    // walk is identical to cocoa_create_engine's.
    cocoa_run_on_main_async([e] {
        if (e->destroyed.load()) {
            cocoa_attach_signal_complete(e, false,
                "EmbeddedWebView disposed before adopt completed");
            return;
        }
        id ns_view_cls = objc_cls("NSView");
        SEL is_kind_of_class = sel("isKindOfClass:");
        id window_layer = e->surface_layers
            ? msg(e->surface_layers, sel("windowLayer"))
            : (id)nullptr;
        id host = nullptr;
        bool host_is_awt = false;
        for (id l = window_layer; l; l = msg(l, sel("superlayer"))) {
            id ld = msg(l, sel("delegate"));
            if (!ld) continue;
            if (!msg<BOOL>(ld, is_kind_of_class, ns_view_cls)) continue;
            Class cls = object_getClass(ld);
            const char *cls_name = cls ? class_getName(cls) : "<unknown>";
            if (std::strstr(cls_name, "AWT") != nullptr) {
                host = ld;
                host_is_awt = true;
                break;
            }
            if (host == nullptr) {
                id window = msg(ld, sel("window"));
                if (window) {
                    id cv = msg(window, sel("contentView"));
                    if (cv) host = cv;
                }
            }
        }
        if (host != nullptr) {
            msg<void>(host, sel("retain"));
            e->host_view = host;
            e->host_is_awt = host_is_awt;
            msg<void, BOOL>(host, sel("setWantsLayer:"), YES);
            // Reparent the existing (retained) child WKWebView into the tab's
            // host view — this is the crux that hosts the popup in the caller's
            // surface instead of a native window, preserving the in-flight
            // POST navigation.
            msg<void, id>(host, sel("addSubview:"), e->webview);
            // Hide until the first setBounds positions it (as create does).
            msg<void, CGRect>(e->webview, sel("setFrame:"),
                              CGRectMake(0, 0, 0, 0));
        } else if (e->surface_layers) {
            msg<void, BOOL>(e->webview, sel("setWantsLayer:"), YES);
            id layer = msg(e->webview, sel("layer"));
            msg<void, id>(e->surface_layers, sel("setLayer:"), layer);
            fprintf(stderr,
                "[webview-embed] WARNING: adopt could not locate a host "
                "NSView; layer-only fallback (content may not render).\n");
        }
        // The child is no longer a windowless popup: it is now a normal
        // embedded engine hosted in the caller's surface.  Clear popup_window
        // (already nil for ADOPT) and register KVO / signal attach so the Java
        // AttachCallback fires (webViewDidClose: still routes onPopupClosed).
        e->popup_window = nullptr;
        cocoa_kvo_install(e);
        cocoa_attach_signal_complete(e, true, "");
    });
    return e;
}

// Canvas 18: discard a retained-but-unadopted popup child (the ADOPT reclaim
// path).  Tears the child engine down WITHOUT ever showing a window; silent
// no-op for an unknown popupId.  Mirrors impl_web_view_did_close teardown
// (minus the window) and transfers ownership of the inherited global refs to
// the async close-notify worker.
static void cocoa_discard_popup(jlong popupId) {
    Engine *e = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_retained_popups_mutex);
        auto it = g_retained_popups.find(popupId);
        if (it == g_retained_popups.end()) return;
        e = it->second;
        g_retained_popups.erase(it);
    }
    if (!e) return;
    cocoa_run_on_main_async([e] {
        JavaVM *jvm = e->jvm;
        jobject popup_cb = e->popup_callback;
        jobject dialog_cb = e->dialog_callback;
        jlong pid = e->popup_id;
        std::string url = page_url_utf8(e->webview);
        if (e->webview) {
            msg<void, id>(e->webview, sel("setUIDelegate:"), nullptr);
            msg<void, id>(e->webview, sel("setNavigationDelegate:"), (id)nullptr);
        }
        {
            std::lock_guard<std::mutex> lk(g_webview_map_mutex);
            g_webview_map.erase(e->webview);
        }
        // Notify Java (onPopupClosed) and transfer ownership of the inherited
        // refs to the worker, which frees them after the call.
        fire_popup_notify_closed(jvm, popup_cb, dialog_cb, pid, url, "");
        e->popup_callback = nullptr;
        e->dialog_callback = nullptr;
        if (e->ui_delegate) {
            // Drop the delegate's unretained "eng" back-pointer before
            // releasing it, so a late WKUIDelegate selector cannot recover
            // and message this freed engine (mirrors cocoa_destroy_engine).
            objc_setAssociatedObject(e->ui_delegate, "eng", (id)nullptr,
                                     OBJC_ASSOCIATION_ASSIGN);
            msg(e->ui_delegate, sel("release"));
            e->ui_delegate = nullptr;
        }
        if (e->webview) {
            msg(e->webview, sel("release"));
            e->webview = nullptr;
        }
        delete e;
    });
}

// Canvas 21: override the WKWebView's User-Agent (changes the HTTP header).
// ua == nullptr clears the override (customUserAgent = nil -> engine default).
// Runs on the AppKit main thread; takes effect on the next navigation.
static void cocoa_set_user_agent(Engine *e, const char *ua) {
    if (!e) return;
    bool has = (ua != nullptr);
    std::string s = has ? ua : std::string();
    cocoa_run_on_main_async([e, s, has] {
        if (e->destroyed.load() || !e->webview) return;
        id v = has ? ns_str(s.c_str()) : (id)nullptr;
        msg<void, id>(e->webview, sel("setCustomUserAgent:"), v);
    });
}

// Canvas 21 (1.5.0): install/clear the per-destination User-Agent resolver.
// Held as a JNI global ref so it survives the setting call; the previous ref is
// released first.  Consulted only at the popup-child creation site (navigations
// Java drives are resolved on the Java side before navigate).
static void cocoa_set_user_agent_resolver(Engine *e, JNIEnv *env, jobject r) {
    if (!e || !env) return;
    if (e->ua_resolver) {
        env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
    }
    if (r) {
        e->ua_resolver = env->NewGlobalRef(r);
    }
}

// Canvas 22: purge the WKWebView's HTTP resource cache (disk + memory) via its
// configuration's WKWebsiteDataStore.  Only the cache data types are removed,
// so cookies / local storage / service workers survive (an active login is
// retained).  Runs on the AppKit main thread; the removal is asynchronous.
// The WKWebsiteDataType* constants are WebKit NSString* globals resolved via
// dlsym (this translation unit has no WebKit headers, matching the JAWT dlsym
// idiom).
static void cocoa_clear_cache(Engine *e) {
    if (!e) return;
    cocoa_run_on_main_async([e] {
        if (e->destroyed.load() || !e->webview) return;
        id config = msg<id>(e->webview, sel("configuration"));
        if (!config) return;
        id store = msg<id>(config, sel("websiteDataStore"));
        if (!store) return;
        // Resolve the disk + memory cache type constants at runtime.
        id diskCache = nullptr, memCache = nullptr;
        void *pd = dlsym(RTLD_DEFAULT, "WKWebsiteDataTypeDiskCache");
        void *pm = dlsym(RTLD_DEFAULT, "WKWebsiteDataTypeMemoryCache");
        if (pd) diskCache = *(id *)pd;
        if (pm) memCache = *(id *)pm;
        id types = msg<id>(msg<id>(objc_cls("NSMutableSet"), sel("alloc")),
                           sel("init"));
        if (diskCache) msg<void, id>(types, sel("addObject:"), diskCache);
        if (memCache) msg<void, id>(types, sel("addObject:"), memCache);
        id past = msg<id>(objc_cls("NSDate"), sel("distantPast"));
        msg<void, id, id, void (^)(void)>(
            store, sel("removeDataOfTypes:modifiedSince:completionHandler:"),
            types, past, ^{});
        msg<void>(types, sel("release"));
    });
}

static std::string ascii_lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) { return (char)std::tolower(c); });
    return value;
}

static bool cocoa_cookie_matches_url(id cookie, id url) {
    if (!cookie || !url) return false;
    std::string host = ascii_lower(ns_string_to_utf8(
        msg<id>(url, sel("host"))));
    std::string domain = ascii_lower(ns_string_to_utf8(
        msg<id>(cookie, sel("domain"))));
    if (host.empty() || domain.empty()) return false;
    // NSHTTPCookie convention: a leading dot marks a domain cookie (host and
    // subdomains); no leading dot is host-only (exact host match).
    bool domain_cookie = domain[0] == '.';
    if (domain_cookie) domain.erase(0, 1);
    bool domain_ok = host == domain;
    if (!domain_ok && domain_cookie && host.size() > domain.size()
            && host.compare(host.size() - domain.size(), domain.size(), domain) == 0
            && host[host.size() - domain.size() - 1] == '.') {
        domain_ok = true;
    }
    if (!domain_ok) return false;

    std::string request_path = ns_string_to_utf8(msg<id>(url, sel("path")));
    if (request_path.empty()) request_path = "/";
    std::string cookie_path = ns_string_to_utf8(
        msg<id>(cookie, sel("path")));
    if (cookie_path.empty()) cookie_path = "/";
    if (request_path.compare(0, cookie_path.size(), cookie_path) != 0) {
        return false;
    }
    if (cookie_path.back() != '/' && request_path.size() > cookie_path.size()
            && request_path[cookie_path.size()] != '/') {
        return false;
    }

    BOOL secure = msg<BOOL>(cookie, sel("isSecure"));
    std::string scheme = ascii_lower(ns_string_to_utf8(
        msg<id>(url, sel("scheme"))));
    if (secure && scheme != "https") return false;

    id expires = msg<id>(cookie, sel("expiresDate"));
    if (expires && msg<double>(expires, sel("timeIntervalSinceNow")) <= 0.0) {
        return false;
    }
    return true;
}

// Query WKHTTPCookieStore rather than document.cookie so HttpOnly login
// credentials are included. WKHTTPCookieStore returns the whole data-store
// jar; filter it with normal domain/path/secure/expiry rules for the requested
// URL before formatting an HTTP Cookie header.
static void cocoa_get_cookies(Engine *e, const std::string &url_string,
                              CookieCompletion *completion) {
    if (!e) {
        complete_cookie_query(completion, "", "WebView is not available");
        return;
    }
    cocoa_run_on_main_async([e, url_string, completion] {
        if (e->destroyed.load() || !e->webview) {
            complete_cookie_query(completion, "", "WebView is not available");
            return;
        }
        id url = msg<id>(objc_cls("NSURL"), sel("URLWithString:"),
                         ns_str(url_string.c_str()));
        id config = msg<id>(e->webview, sel("configuration"));
        id store = config ? msg<id>(config, sel("websiteDataStore")) : nullptr;
        id cookie_store = store ? msg<id>(store, sel("httpCookieStore")) : nullptr;
        if (!url || !cookie_store) {
            complete_cookie_query(completion, "",
                                  "WKHTTPCookieStore is not available");
            return;
        }
        // Non-ARC C++: the block does not retain captured ids, and the
        // autoreleased NSURL would be gone by the time getAllCookies:
        // completes on a later run-loop pass.  Balanced in the block.
        msg<id>(url, sel("retain"));
        msg<void, void (^)(id)>(cookie_store, sel("getAllCookies:"),
            ^(id cookies) {
                struct Match { size_t path_len; std::string pair; };
                std::vector<Match> matches;
                unsigned long count = cookies
                    ? msg<unsigned long>(cookies, sel("count")) : 0;
                for (unsigned long i = 0; i < count; ++i) {
                    id cookie = msg<id, unsigned long>(
                        cookies, sel("objectAtIndex:"), i);
                    if (!cocoa_cookie_matches_url(cookie, url)) continue;
                    std::string name = ns_string_to_utf8(
                        msg<id>(cookie, sel("name")));
                    std::string value = ns_string_to_utf8(
                        msg<id>(cookie, sel("value")));
                    if (name.empty()) continue;
                    std::string path = ns_string_to_utf8(
                        msg<id>(cookie, sel("path")));
                    matches.push_back({path.size(), name + "=" + value});
                }
                msg<void>(url, sel("release"));
                // RFC 6265 5.4: longer paths first; stable keeps store order.
                std::stable_sort(matches.begin(), matches.end(),
                    [](const Match &a, const Match &b) {
                        return a.path_len > b.path_len;
                    });
                std::string header;
                for (const Match &m : matches) {
                    if (!header.empty()) header += "; ";
                    header += m.pair;
                }
                complete_cookie_query(completion, header, nullptr);
            });
    });
}

// ---------------------------------------------------------------------------
// Canvas 29 D9: print to PDF.  -[WKWebView printOperationWithPrintInfo:]
// (macOS 11+) run modally on the view's window with a save-job NSPrintInfo,
// so WKWebView renders into the operation asynchronously and no panel shows.
// ---------------------------------------------------------------------------

// Answer a job off AppKit main, as the other macOS upcalls do: the Java side
// may start the next queued print (D12) from inside the answer, and that must
// not begin a new modal run from inside the previous one's didRun callback.
static void cocoa_pdf_finish_async(PdfJob *job, bool ok, const char *err) {
    std::string e = err ? err : "";
    std::thread([job, ok, e]() {
        pdf_finish(job, ok, ok ? nullptr : e.c_str());
    }).detach();
}

static void impl_pdf_did_run(id, SEL, id, BOOL success, void *ctx) {
    cocoa_pdf_finish_async(static_cast<PdfJob *>(ctx), success == YES,
                           "The page could not be printed to PDF.");
}

static std::once_flag g_webview_embed_pdf_delegate_once;
static id g_webview_embed_pdf_delegate = nullptr;

static id get_webview_embed_pdf_delegate() {
    std::call_once(g_webview_embed_pdf_delegate_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedPdfDelegate", 0);
        class_addMethod(c, sel("printOperationDidRun:success:contextInfo:"),
                        (IMP)impl_pdf_did_run, "v@:@c^v");
        objc_registerClassPair(c);
        // One shared instance for the life of the JVM; never released.
        g_webview_embed_pdf_delegate =
            msg<id>(msg<id>((id)c, sel("alloc")), sel("init"));
    });
    return g_webview_embed_pdf_delegate;
}

static void cocoa_print_to_pdf(Engine *e, PdfJob *job, std::string path,
                               double w, double h, double mt, double mr,
                               double mb, double ml, bool bg) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load() || !e->webview) {
            cocoa_pdf_finish_async(job, false, kPdfNotAttached);
            return;
        }
        id wv = e->webview;
        if (!msg<BOOL, SEL>(wv, sel("respondsToSelector:"),
                            sel("printOperationWithPrintInfo:"))) {
            cocoa_pdf_finish_async(job, false,
                                   "PDF printing needs macOS 11 or later.");
            return;
        }
        id window = msg<id>(wv, sel("window"));
        if (!window) {
            cocoa_pdf_finish_async(job, false,
                                   "The WebView has no window to print from.");
            return;
        }

        // Backgrounds: WKPreferences.shouldPrintBackgrounds (macOS 13.3+).
        id config = msg<id>(wv, sel("configuration"));
        id prefs = config ? msg<id>(config, sel("preferences")) : nullptr;
        if (prefs && msg<BOOL, SEL>(prefs, sel("respondsToSelector:"),
                                    sel("setShouldPrintBackgrounds:"))) {
            msg<void, BOOL>(prefs, sel("setShouldPrintBackgrounds:"),
                            bg ? YES : NO);
        }

        const CGFloat pw = (CGFloat)(w * 72.0), ph = (CGFloat)(h * 72.0);
        id info = msg<id>(msg<id>(objc_cls("NSPrintInfo"),
                                  sel("sharedPrintInfo")), sel("copy"));
        msg<void, id>(info, sel("setJobDisposition:"), ns_str("NSPrintSaveJob"));
        id url = msg<id, id>(objc_cls("NSURL"), sel("fileURLWithPath:"),
                             ns_str(path.c_str()));
        id dict = msg<id>(info, sel("dictionary"));
        msg<void, id, id>(dict, sel("setObject:forKey:"), url,
                          ns_str("NSJobSavingURL"));
        msg<void, CGSize>(info, sel("setPaperSize:"), CGSizeMake(pw, ph));
        msg<void, long>(info, sel("setOrientation:"), 0L);  // portrait
        msg<void, CGFloat>(info, sel("setTopMargin:"), (CGFloat)(mt * 72.0));
        msg<void, CGFloat>(info, sel("setRightMargin:"), (CGFloat)(mr * 72.0));
        msg<void, CGFloat>(info, sel("setBottomMargin:"), (CGFloat)(mb * 72.0));
        msg<void, CGFloat>(info, sel("setLeftMargin:"), (CGFloat)(ml * 72.0));
        // NSPrintingPaginationModeAutomatic == 0.
        msg<void, long>(info, sel("setHorizontalPagination:"), 0L);
        msg<void, long>(info, sel("setVerticalPagination:"), 0L);
        msg<void, BOOL>(info, sel("setHorizontallyCentered:"), NO);
        msg<void, BOOL>(info, sel("setVerticallyCentered:"), NO);

        id op = msg<id, id>(wv, sel("printOperationWithPrintInfo:"), info);
        msg<void>(info, sel("release"));
        if (!op) {
            cocoa_pdf_finish_async(job, false,
                                   "The page could not be printed to PDF.");
            return;
        }
        msg<void, BOOL>(op, sel("setShowsPrintPanel:"), NO);
        msg<void, BOOL>(op, sel("setShowsProgressPanel:"), NO);
        // Without a frame the operation's view prints blank pages.
        id opView = msg<id>(op, sel("view"));
        if (opView) {
            msg<void, CGRect>(opView, sel("setFrame:"),
                              CGRectMake(0, 0, pw, ph));
        }
        msg<void, id, id, SEL, void *>(
            op, sel("runOperationModalForWindow:delegate:didRunSelector:contextInfo:"),
            window, get_webview_embed_pdf_delegate(),
            sel("printOperationDidRun:success:contextInfo:"), (void *)job);
    });
}

// Asynchronous engine destroy.  Returns immediately on the calling
// thread (typically the EDT) after a small Java-side cleanup; the
// AppKit teardown, view-hierarchy removal, KVO observer unregister,
// binding global-ref release, and finally `delete e` run on the
// AppKit main thread in a single async-on-main lambda.
//
// Pre-async cleanup (calling thread):
//   1. Erase from g_webview_map so the swizzled responder / mouse hooks
//      cannot find the engine again.  This window MUST be as small as
//      possible -- the calling thread runs this synchronously before
//      any subsequent async ops can be enqueued.
//   2. DeleteGlobalRef on focus_callback and click_callback so the
//      hooks (which had read the fields up to this point) cannot fire
//      Java callbacks against freed refs.  Uses the calling thread's
//      JNIEnv via attach/detach.
//
// Async cleanup (AppKit main thread, FIFO after any previously-enqueued
// per-engine op):
//   1. Set destroyed=true as the FIRST action so any LATER fires of
//      previously-enqueued lambdas (defence-in-depth -- FIFO ordering
//      makes this impossible in normal operation) see the flag.
//   2. Tear down the KVO observer BEFORE any view release (AppKit logs
//      a warning if an observed object is released while observers are
//      still registered).
//   3. removeFromSuperview, release retained AppKit objects.
//   4. Drain e->bindings (releasing the per-binding Java global refs).
//   5. Release any still-pending attach callback global ref (the
//      typical case is that it was already cleared inside attach
//      resolution, but a never-resolved attach + dispose pattern would
//      land here).
//   6. delete e.
static void cocoa_destroy_engine(Engine *e) {
    if (!e) return;
    // Canvas 21 (1.5.0): drop the User-Agent resolver's global ref.  Only the
    // popup-child creation path reads it, but a late popup during teardown
    // would follow a freed ref, so it goes with the other callbacks.
    if (e->ua_resolver) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->ua_resolver);
        e->ua_resolver = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Drop the webview from the engine map BEFORE any teardown work --
    // the swizzled responder hooks can fire at any moment during destroy
    // (AppKit unwinds the view hierarchy and resigns first responder),
    // and we don't want them invoking a callback into a freed Engine.
    if (e->webview) {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        g_webview_map.erase(e->webview);
    }
    // Drop the focus-callback global ref (if any) on the EDT-driven
    // thread that holds the JNIEnv -- we cannot delete a global ref
    // from inside the AppKit-main lambda below without re-attaching.
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
    // Same treatment for the download-callback global ref.  A download
    // can outlive the page, the navigation, and the component, so this
    // must be gone before any teardown -- a late fire into a freed ref
    // is a SIGSEGV, not an exception.
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
    // Canvas 34 D8: the navigation-callback global ref goes the same way.
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
    // Same treatment for the click-callback global ref.  Cleared after
    // the webview map entry above so the swizzled mouseDown: hooks read
    // a null field instead of a freed ref if they race against destroy.
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
    // Drop the dialog-callback global ref BEFORE the async teardown
    // lambda runs, so any in-flight WKUIDelegate selector observes a
    // null callback field and invokes its completion handler with the
    // safe default (returning the WebKit JS thread cleanly).  Released
    // here -- outside the async lambda -- because the lambda runs on
    // the AppKit main thread later and we still have the EDT JNIEnv
    // available right now (matches the click_callback cleanup above).
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
    cocoa_run_on_main_async([e] {
        // Mark destroyed FIRST.  Any LATER-firing lambdas that read this
        // flag short-circuit; FIFO ordering on the main queue makes
        // "later" impossible in normal operation but the flag is the
        // canvas-mandated belt-and-suspenders.
        e->destroyed.store(true);

        // KVO observer unregistration MUST happen BEFORE any view
        // release -- AppKit warns (and may crash) if an observed object
        // is released with observers still attached.
        cocoa_kvo_teardown(e);


        // Neutralise the external-message WKScriptMessageHandler BEFORE
        // releasing config/webview.  That handler carries an unretained
        // "eng" back-pointer (OBJC_ASSOCIATION_ASSIGN) to this engine; if
        // a didReceiveScriptMessage: drained during
        // -[NSApplication terminate:] after the engine is freed it would
        // dereference freed memory.  Removing the handler drops the
        // userContentController's strong ref so it deallocates (taking its
        // association with it).
        if (e->manager) {
            msg<void, id>(e->manager, sel("removeScriptMessageHandlerForName:"),
                          ns_str("external"));
            msg<void, id>(e->manager, sel("removeScriptMessageHandlerForName:"),
                          ns_str("__webview_pw__"));
        }
        // Abandon every download still in flight for this engine and
        // remove its NSProgress KVO observer.  An NSProgress released
        // while still observed is an AppKit hard error, not a warning,
        // so this runs before anything below is released.
        cocoa_download_drop_engine(e);
        if (e->webview) {
            // Clear the WKWebView's uiDelegate BEFORE releasing the
            // WKWebView so any in-flight delegate selector observes
            // the cleared field (WKWebView holds a weak reference to
            // uiDelegate per Apple's convention).
            msg<void, id>(e->webview, sel("setUIDelegate:"), (id)nullptr);
            msg<void, id>(e->webview, sel("setNavigationDelegate:"), (id)nullptr);
            // If we added the WKWebView as a subview, remove it before
            // releasing so AppKit unwinds the view hierarchy cleanly.
            msg<void>(e->webview, sel("removeFromSuperview"));
        }
        if (e->ui_delegate) {
            // Drop the delegate's unretained "eng" back-pointer BEFORE
            // releasing it.  The WKUIDelegate selectors (webViewDidClose:,
            // the dialog panels) recover the engine from this association;
            // clearing it means a selector that the main dispatch queue
            // drains during -[NSApplication terminate:], after this engine
            // is freed, recovers nil and returns without messaging freed
            // memory -- the objc_msgSend use-after-free this fix closes.
            objc_setAssociatedObject(e->ui_delegate, "eng", (id)nullptr,
                                     OBJC_ASSOCIATION_ASSIGN);
            msg<void>(e->ui_delegate, sel("release"));
            e->ui_delegate = nullptr;
        }
        if (e->host_view) {
            msg<void>(e->host_view, sel("release"));
            e->host_view = nullptr;
        }
        if (e->surface_layers) {
            msg<void, id>(e->surface_layers, sel("setLayer:"), (id)nullptr);
            msg<void>(e->surface_layers, sel("release"));
            e->surface_layers = nullptr;
        }
        if (e->webview) {
            msg<void>(e->webview, sel("release"));
            e->webview = nullptr;
        }
        if (e->config) {
            msg<void>(e->config, sel("release"));
            e->config = nullptr;
        }

        // Drain e->bindings on the main thread.  Each binding holds
        // two Java global refs (the callback and its class); attach to
        // the JVM via the engine's stored JavaVM* to release them.
        for (auto &kv : e->bindings) {
            Binding *b = kv.second;
            JNIEnv *env2 = nullptr;
            bool detach = false;
            if (e->jvm && e->jvm->GetEnv((void **)&env2, JNI_VERSION_1_6) != JNI_OK) {
                e->jvm->AttachCurrentThread((void **)&env2, nullptr);
                detach = true;
            }
            if (env2) {
                env2->DeleteGlobalRef(b->fn);
                env2->DeleteGlobalRef(b->cls);
            }
            if (detach && e->jvm) e->jvm->DetachCurrentThread();
            delete b;
        }
        e->bindings.clear();

        // Release any still-pending attach-callback global ref.  Under
        // normal flow cocoa_attach_signal_complete cleared this when
        // the callback fired, but a never-resolved-then-disposed
        // engine (rare: user disposes while the async epilogue's
        // destroyed-check above bailed) would leave it set.
        {
            std::lock_guard<std::mutex> lk(e->attach_callback_mutex);
            if (e->attach_callback || e->attach_callback_cls) {
                JNIEnv *env2 = nullptr;
                bool detach = false;
                if (e->jvm && e->jvm->GetEnv((void **)&env2, JNI_VERSION_1_6) != JNI_OK) {
                    e->jvm->AttachCurrentThread((void **)&env2, nullptr);
                    detach = true;
                }
                if (env2) {
                    if (e->attach_callback) env2->DeleteGlobalRef(e->attach_callback);
                    if (e->attach_callback_cls) env2->DeleteGlobalRef(e->attach_callback_cls);
                }
                e->attach_callback = nullptr;
                e->attach_callback_cls = nullptr;
                if (detach && e->jvm) e->jvm->DetachCurrentThread();
            }
        }
        delete e;
    });
}

// Update the WKWebView's frame so it overlays exactly the AWT canvas
// region.  When host_view is a per-Canvas AWT NSView its bounds already
// match the canvas, so the WKWebView just fills it.  Otherwise host_view
// is NSWindow.contentView and the caller's (x,y,w,h) -- the canvas
// position in NSWindow content-pane coords with AWT's top-left origin --
// is translated into Cocoa's bottom-left coords.
static void cocoa_set_bounds(Engine *e, int x, int y, int w, int h) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview || !e->host_view) return;
        CGFloat fx = (CGFloat)x;
        CGFloat fy = (CGFloat)y;
        CGFloat fw = (CGFloat)w;
        CGFloat fh = (CGFloat)h;
        if (e->host_is_awt) {
            msg<void, CGRect>(e->webview, sel("setFrame:"),
                              CGRectMake(0, 0, fw, fh));
            return;
        }
        CGRect b = msg_stret<CGRect>(e->host_view, sel("bounds"));
        BOOL flipped = msg<BOOL>(e->host_view, sel("isFlipped"));
        CGFloat outY = flipped
            ? fy
            : (b.origin.y + b.size.height - fy - fh);
        msg<void, CGRect>(e->webview, sel("setFrame:"),
                          CGRectMake(b.origin.x + fx, outY, fw, fh));
    });
}

static void cocoa_navigate(Engine *e, std::string url) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        // WKWebView's loadRequest: silently refuses data: URLs (a
        // long-standing WKWebView restriction) -- the page renders blank
        // with no error.  Route data:text/html through
        // loadHTMLString:baseURL: instead, decoding the body from base64
        // or percent-encoding as the prefix indicates.
        static const std::string DATA_HTML = "data:text/html";
        if (url.compare(0, DATA_HTML.size(), DATA_HTML) == 0) {
            size_t comma = url.find(',');
            if (comma != std::string::npos) {
                std::string meta = url.substr(0, comma);
                std::string body = url.substr(comma + 1);
                id html = nullptr;
                if (meta.find(";base64") != std::string::npos) {
                    id b64 = ns_str(body.c_str());
                    id data = msg<id, id, unsigned long>(
                        msg<id>(objc_cls("NSData"), sel("alloc")),
                        sel("initWithBase64EncodedString:options:"),
                        b64, (unsigned long)0);
                    if (data) {
                        // NSUTF8StringEncoding == 4
                        html = msg<id, id, unsigned long>(
                            msg<id>(objc_cls("NSString"), sel("alloc")),
                            sel("initWithData:encoding:"),
                            data, (unsigned long)4);
                    }
                } else {
                    id raw = ns_str(body.c_str());
                    id decoded = msg<id>(
                        raw, sel("stringByRemovingPercentEncoding"));
                    html = decoded ? decoded : raw;
                }
                if (html) {
                    msg<void, id, id>(e->webview,
                                      sel("loadHTMLString:baseURL:"),
                                      html, (id)nullptr);
                    return;
                }
            }
        }
        id nsurl = msg<id, id>(objc_cls("NSURL"), sel("URLWithString:"),
                               ns_str(url.c_str()));
        id req = msg<id, id>(objc_cls("NSURLRequest"),
                             sel("requestWithURL:"), nsurl);
        msg<void, id>(e->webview, sel("loadRequest:"), req);
    });
}

static void cocoa_init_script(Engine *e, std::string js) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->manager) return;
        id script = msg(objc_cls("WKUserScript"), sel("alloc"));
        script = msg<id, id, long, BOOL>(
            script, sel("initWithSource:injectionTime:forMainFrameOnly:"),
            ns_str(js.c_str()), (long)0, YES);
        msg<void, id>(e->manager, sel("addUserScript:"), script);
    });
}

static void cocoa_eval(Engine *e, std::string js) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        msg<void, id, id>(e->webview,
                          sel("evaluateJavaScript:completionHandler:"),
                          ns_str(js.c_str()), (id)nullptr);
    });
}

static void cocoa_set_visible(Engine *e, bool visible) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        msg<void, BOOL>(e->webview, sel("setHidden:"), visible ? NO : YES);
    });
}

static void cocoa_request_focus(Engine *e) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        id win = msg(e->webview, sel("window"));
        if (win) {
            msg<BOOL, id>(win, sel("makeFirstResponder:"), e->webview);
        }
    });
}

// Convert the per-engine binding registration to async-on-main so it
// serialises against the script-message-handler delegate's reads of
// e->bindings (which also run on main).  FIFO main-queue ordering
// guarantees the bind block fires AFTER the attach epilogue (which
// creates the WKWebView and sets up the script-message-handler) and
// BEFORE any cocoa_navigate enqueued by the caller after the bind --
// so the binding is registered in e->bindings before the page can call
// it from JS.
static void cocoa_bind(Engine *e, Binding *b) {
    if (!e || !b) {
        if (b) delete b;
        return;
    }
    cocoa_run_on_main_async([e, b] {
        if (e->destroyed.load()) {
            // Engine destroyed before this bind could fire.  Release
            // the Binding's Java global refs and delete it; no engine
            // to attach it to.
            JNIEnv *env = nullptr;
            bool detach = false;
            if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
                e->jvm->AttachCurrentThread((void **)&env, nullptr);
                detach = true;
            }
            if (env) {
                if (b->fn) env->DeleteGlobalRef(b->fn);
                if (b->cls) env->DeleteGlobalRef(b->cls);
            }
            if (detach && e->jvm) e->jvm->DetachCurrentThread();
            delete b;
            return;
        }
        e->bindings[b->name] = b;
    });
}

// macOS has no public API to programmatically open the Web Inspector; the
// only public entry points are right-click -> Inspect Element (gated by
// developerExtrasEnabled, already set in debug mode) and Safari ->
// Develop menu (gated by setInspectable:YES, also set in debug mode).
// Returning 0 signals to the Java side that no programmatic open
// happened, so the boolean openDevTools() return surfaces the macOS
// limitation honestly.
static int cocoa_open_devtools(Engine *e) {
    (void)e;
    return 0;
}

// Dispatch Cut/Copy/Paste/SelectAll directly to the embedded WKWebView.
// Initial design used [NSApp sendAction:... to:nil from:webview] so the
// responder chain would route to the inner focused DOM element, but in
// the AWT-embedded setup (WKWebView parented under NSWindow.contentView,
// which is AWT's NSView) the AppKit first responder is not reliably the
// WKWebView -- AWT keeps system focus on its own view -- so the
// responder walk never reaches WKWebView and the action no-ops.
//
// Sending the action directly to WKWebView side-steps the chain entirely.
// WKWebView's implementations of cut:/copy:/paste:/selectAll: delegate to
// the WebKit page's current selection / focused element internally, so
// the operation hits the correct in-page target regardless of AppKit
// first-responder state.  Guard with respondsToSelector: so a missing
// selector on an older SDK fails silently instead of aborting.
//
// cmdId values are the EditingCommand contract: 1=CUT, 2=COPY, 3=PASTE,
// 4=SELECT_ALL.  Unknown cmdIds are silently dropped.
static void cocoa_execute_editing_command(Engine *e, int cmdId) {
    if (!e) return;
    // No early bail on e->webview: the engine may still be in async
    // attach (e->webview not yet set) -- FIFO ordering on the main
    // queue means the lambda below sees a populated e->webview by the
    // time it fires.
    SEL action = nullptr;
    const char *name = nullptr;
    switch (cmdId) {
        case 1: action = sel("cut:");        name = "cut:";        break;
        case 2: action = sel("copy:");       name = "copy:";       break;
        case 3: action = sel("paste:");      name = "paste:";      break;
        case 4: action = sel("selectAll:");  name = "selectAll:";  break;
        default: return;
    }
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        if (!msg<BOOL, SEL>(e->webview, sel("respondsToSelector:"),
                            action)) {
            if (getenv("WEBVIEW_DEBUG_SHORTCUT")) {
                fprintf(stderr,
                    "[webview-editing-shortcut] cocoa: WKWebView does not "
                    "respond to %s, dropping\n", name);
            }
            return;
        }
        if (getenv("WEBVIEW_DEBUG_SHORTCUT")) {
            fprintf(stderr,
                "[webview-editing-shortcut] cocoa: dispatching %s "
                "directly to WKWebView %p\n", name, (void *)e->webview);
        }
        msg<void, id>(e->webview, action, (id)nullptr);
    });
}

#endif // WEBVIEW_COCOA

} // namespace embed

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

using embed::Binding;
using embed::Engine;
using embed::JawtLock;

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_jawt_1get_1window_1handle
  (JNIEnv *env, jclass, jobject component) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) return 0;
#ifdef WEBVIEW_GTK
    auto *info = (JAWT_X11DrawingSurfaceInfo *)lock.dsi->platformInfo;
    return (jlong)info->drawable;
#elif defined(WEBVIEW_COCOA)
    // Return the platformInfo pointer; on macOS this is an id<JAWT_SurfaceLayers>.
    // The value is only meaningful while the JAWT surface is locked, which is
    // not the case once we return.  Callers should prefer webview_embed_create
    // (which holds the lock for the duration of attach).
    return (jlong)lock.dsi->platformInfo;
#else
    return 0;
#endif
}

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1create
  (JNIEnv *env, jclass, jobject component, jint debug) {
#ifdef WEBVIEW_GTK
    Engine *e = embed::gtk_create_engine(env, component, debug);
    return (jlong)e;
#elif defined(WEBVIEW_COCOA)
    Engine *e = embed::cocoa_create_engine(env, component, 0, debug);
    return (jlong)e;
#else
    (void)env; (void)component; (void)debug;
    return 0;
#endif
}

// NOTE: the popup-adoption JNI bridges (webview_embed_adopt_popup /
// webview_embed_discard_popup) are defined INSIDE the `extern "C"` block below
// (next to webview_embed_set_user_agent), not here.  They are newly-added
// methods with no prototype in the generated JNI header, so — like the dialog
// setters — they must be wrapped in `extern "C"` or the JVM can't resolve
// them (UnsatisfiedLinkError).  See the block comment above that `extern "C"`.

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1destroy
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    embed::gtk_destroy_engine((Engine *)wv);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_destroy_engine((Engine *)wv);
#else
    (void)wv;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1bounds
  (JNIEnv *, jclass, jlong wv, jint x, jint y, jint w, jint h) {
#ifdef WEBVIEW_GTK
    embed::gtk_set_bounds((Engine *)wv, x, y, w, h);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_bounds((Engine *)wv, x, y, w, h);
#else
    (void)wv; (void)x; (void)y; (void)w; (void)h;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1visible
  (JNIEnv *, jclass, jlong wv, jint visible) {
#ifdef WEBVIEW_GTK
    embed::gtk_set_visible((Engine *)wv, visible != 0);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_visible((Engine *)wv, visible != 0);
#else
    (void)wv; (void)visible;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1request_1focus
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    embed::gtk_request_focus((Engine *)wv);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_request_focus((Engine *)wv);
#else
    (void)wv;
#endif
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1pump
  (JNIEnv *, jclass, jlong /*wv*/, jint /*wait*/) {
    // On both Linux (dedicated GTK thread) and macOS (AppKit runs in JVM main
    // thread alongside AWT) the host doesn't need to pump the embed loop.
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1navigate
  (JNIEnv *env, jclass, jlong wv, jstring url) {
    const char *u = env->GetStringUTFChars(url, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_navigate((Engine *)wv, u ? u : "");
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_navigate((Engine *)wv, u ? u : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(url, u);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1init
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_init_script((Engine *)wv, s ? s : "");
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_init_script((Engine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1eval
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_eval((Engine *)wv, s ? s : "");
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_eval((Engine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
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

    // Install the same bind shim as the existing engine so callers can write
    // window.<name>(args) and receive a JSON {name, seq, args} payload.
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

#ifdef WEBVIEW_GTK
    embed::gtk_bind(e, b);
    embed::gtk_init_script(e, js);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_bind(e, b);
    embed::cocoa_init_script(e, js);
#else
    delete b;
#endif
    env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1dispatch
  (JNIEnv *env, jclass, jlong wv, jobject callback) {
#if defined(WEBVIEW_GTK) || defined(WEBVIEW_COCOA)
    auto *e = (Engine *)wv;
    if (!e) return;
    JavaVM *jvm = e->jvm;
    jobject ref = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    jclass gcls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);

    auto fn = [jvm, ref, gcls] {
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
    };
#ifdef WEBVIEW_GTK
    embed::GtkPump::instance().run_async(fn);
#else
    embed::cocoa_run_on_main_async(fn);
#endif
#else
    (void)env; (void)wv; (void)callback;
#endif
}

// ---------------------------------------------------------------------------
// Offscreen / lightweight JNI exports (Linux-only for now).
// macOS and Windows return 0 / no-op from these entry points; their
// lightweight implementations are scaffolded but not yet wired.
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1create
  (JNIEnv *env, jclass, jint w, jint h, jint debug) {
#ifdef WEBVIEW_GTK
    return (jlong)embed::gtk_off_create_engine(env, (int)w, (int)h, debug);
#else
    (void)env; (void)w; (void)h; (void)debug;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1destroy
  (JNIEnv *, jclass, jlong peer) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_destroy_engine((embed::OffEngine *)peer);
#else
    (void)peer;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1resize
  (JNIEnv *, jclass, jlong peer, jint w, jint h) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_resize((embed::OffEngine *)peer, (int)w, (int)h);
#else
    (void)peer; (void)w; (void)h;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1navigate
  (JNIEnv *env, jclass, jlong peer, jstring url) {
#ifdef WEBVIEW_GTK
    const char *u = env->GetStringUTFChars(url, nullptr);
    embed::gtk_off_navigate((embed::OffEngine *)peer, u ? u : "");
    env->ReleaseStringUTFChars(url, u);
#else
    (void)env; (void)peer; (void)url;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1snapshot
  (JNIEnv *env, jclass, jlong peer, jintArray pixels, jint w, jint h) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_snapshot_into((embed::OffEngine *)peer, env, pixels, w, h);
#else
    (void)env; (void)peer; (void)pixels; (void)w; (void)h;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1button
  (JNIEnv *, jclass, jlong peer, jint press, jint x, jint y, jint button,
   jint modifiers, jint click_count) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_mouse_button((embed::OffEngine *)peer, press != 0,
                                (int)x, (int)y, (int)button,
                                (int)modifiers, (int)click_count);
#else
    (void)peer; (void)press; (void)x; (void)y; (void)button;
    (void)modifiers; (void)click_count;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1motion
  (JNIEnv *, jclass, jlong peer, jint x, jint y, jint modifiers) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_mouse_motion((embed::OffEngine *)peer,
                                (int)x, (int)y, (int)modifiers);
#else
    (void)peer; (void)x; (void)y; (void)modifiers;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1scroll
  (JNIEnv *, jclass, jlong peer, jint x, jint y, jdouble dx, jdouble dy,
   jint modifiers) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_mouse_scroll((embed::OffEngine *)peer,
                                (int)x, (int)y, (double)dx, (double)dy,
                                (int)modifiers);
#else
    (void)peer; (void)x; (void)y; (void)dx; (void)dy; (void)modifiers;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1key_1event
  (JNIEnv *, jclass, jlong peer, jint press, jint keyval, jint modifiers,
   jint is_modifier_key) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_key_event((embed::OffEngine *)peer, press != 0,
                             (int)keyval, (int)modifiers,
                             is_modifier_key != 0);
#else
    (void)peer; (void)press; (void)keyval; (void)modifiers;
    (void)is_modifier_key;
#endif
}

// ---------------------------------------------------------------------------
// DevTools open + offscreen JS bridge / dispatch JNI exports.
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1open_1devtools
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    return (jint)embed::gtk_open_devtools((Engine *)wv);
#elif defined(WEBVIEW_COCOA)
    return (jint)embed::cocoa_open_devtools((Engine *)wv);
#else
    (void)wv;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1execute_1editing_1command
  (JNIEnv *, jclass, jlong wv, jint cmdId) {
#ifdef WEBVIEW_GTK
    embed::gtk_execute_editing_command((Engine *)wv, (int)cmdId);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_execute_editing_command((Engine *)wv, (int)cmdId);
#else
    (void)wv; (void)cmdId;
#endif
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1is_1native_1first_1responder
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_COCOA
    return (jint)embed::cocoa_is_first_responder((Engine *)wv);
#else
    (void)wv;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1focus_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
#ifdef WEBVIEW_COCOA
    embed::cocoa_set_focus_callback((Engine *)wv, env, cb);
#else
    (void)env; (void)wv; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1click_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
#ifdef WEBVIEW_GTK
    embed::gtk_set_click_callback((Engine *)wv, env, cb);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_click_callback((Engine *)wv, env, cb);
#else
    (void)env; (void)wv; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1release_1native_1focus
  (JNIEnv *, jclass, jlong wv) {
    // No-op on macOS and Linux: AppKit / X11 focus handling is already
    // adequate.  Windows has its own implementation in
    // windows/webview_embed.cc that performs the cross-thread SetFocus.
    (void)wv;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1attach_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    auto *e = (Engine *)wv;
    if (!e) return;
#if defined(WEBVIEW_COCOA)
    // macOS attach is asynchronous; the callback connects the Java
    // EmbeddedWebView's AttachState machine to the async epilogue's
    // resolution.  cocoa_set_attach_callback handles both orderings
    // (callback registered before vs. after the async epilogue
    // completes) under attach_callback_mutex.
    embed::cocoa_set_attach_callback(e, env, cb);
#else
    // GTK / unsupported: attach is synchronous (the engine is fully
    // ready by the time webview_embed_create returns), so signal
    // completion immediately.  Fire onResolved(true, null) from the
    // calling thread (typically the EDT); the Java handler marshals
    // via SwingUtilities.invokeLater so the listener actually fires on
    // the next EDT tick regardless of which thread we are on here.
    if (!cb) return;
    jclass cls = env->GetObjectClass(cb);
    if (!cls) return;
    jmethodID m = env->GetMethodID(cls, "onResolved",
                                   "(ZLjava/lang/String;)V");
    if (m) {
        env->CallVoidMethod(cb, m, (jboolean)JNI_TRUE, (jstring)nullptr);
    }
    env->DeleteLocalRef(cls);
#endif
}

// The two dialog-callback JNI bridges below are wrapped in `extern "C"`
// so the symbols emitted match the JVM's JNI lookup expectations
// (`Java_<classpath>_<method>` with C linkage).  The existing focus /
// click / release-native-focus / set-attach-callback bridges above get
// this treatment from `ca_weblite_webview_WebViewNative.h`'s
// `extern "C" { ... }` wrapper, but the dialog setters were added
// without regenerating that header so they need the explicit linkage
// spec.  Regenerating the header is a cosmetic follow-up; the explicit
// `extern "C"` here keeps the symbols callable from the JVM today.
extern "C" {

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1dialog_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    if (wv == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_set_dialog_callback((embed::Engine *)wv, env, cb);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_dialog_callback((embed::Engine *)wv, env, cb);
#else
    (void)env; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1dialog_1callback
  (JNIEnv *env, jclass, jlong peer, jobject cb) {
    if (peer == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_off_set_dialog_callback((embed::OffEngine *)peer, env, cb);
#else
    // macOS / Windows have no offscreen engine; the Java
    // OffscreenWebView.setDialogCallback never gets here because
    // OffscreenWebView.create returns null on those platforms.
    (void)env; (void)cb;
#endif
}

// ---------------------------------------------------------------------------
// Password-manager callback registration + credential store (Canvas 26
// macOS; Canvas 27 Linux).  Must live inside this extern "C" block so the
// JVM can resolve them (UnsatisfiedLinkError otherwise).
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1password_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    if (wv == 0) return;
#if defined(WEBVIEW_COCOA)
    embed::cocoa_set_password_callback((embed::Engine *)wv, env, cb);
#elif defined(WEBVIEW_GTK)
    embed::gtk_set_password_callback_impl((embed::Engine *)wv, env, cb);
#else
    (void)env; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1password_1callback
  (JNIEnv *env, jclass, jlong peer, jobject cb) {
#if defined(WEBVIEW_GTK)
    if (peer == 0) return;
    embed::gtk_set_password_callback_impl((embed::OffEngine *)peer, env, cb);
#else
    // No offscreen engine on macOS / Windows.
    (void)peer; (void)env; (void)cb;
#endif
}

#if defined(WEBVIEW_COCOA)
// Build a CFString from a UTF-8 C string (caller CFRelease's).
static CFStringRef pw_cf(const char *s) {
    return CFStringCreateWithCString(kCFAllocatorDefault, s ? s : "",
                                     kCFStringEncodingUTF8);
}
#endif

#ifdef WEBVIEW_GTK
// ---------------------------------------------------------------------------
// libsecret runtime shim (Canvas 27).
//
// libsecret is dlopen'd at first use rather than linked, matching the
// WebKitGTK runtime-load convention in webkit_loader.cpp -- absence of a
// Secret Service provider degrades gracefully (available/save/delete return
// false, find returns empty) and never fails library load.  The SecretSchema
// is caller-owned by design, so we declare the libsecret ABI locally (its
// public layout is stable) rather than #include <libsecret/secret.h>.
// GLib symbols (g_*) are already available via the GTK link; only the
// secret_* symbols are resolved through dlsym.
// ---------------------------------------------------------------------------
typedef enum { WV_SECRET_SCHEMA_NONE = 0 } WvSecretSchemaFlags;
typedef enum { WV_SECRET_SCHEMA_ATTRIBUTE_STRING = 0 } WvSecretSchemaAttributeType;
typedef struct { const gchar *name; WvSecretSchemaAttributeType type; }
    WvSecretSchemaAttribute;
// Mirrors struct _SecretSchema (name, flags, attributes[32], then 8 private
// reserved slots).  Layout must match libsecret's header exactly.
typedef struct {
    const gchar *name;
    WvSecretSchemaFlags flags;
    WvSecretSchemaAttribute attributes[32];
    gint reserved;
    gpointer reserved1, reserved2, reserved3, reserved4;
    gpointer reserved5, reserved6, reserved7;
} WvSecretSchema;

// SecretSearchFlags bits (SECRET_SEARCH_ALL|UNLOCK|LOAD_SECRETS).
enum { WV_SECRET_SEARCH_ALL = 1 << 1,
       WV_SECRET_SEARCH_UNLOCK = 1 << 2,
       WV_SECRET_SEARCH_LOAD_SECRETS = 1 << 3 };

static const WvSecretSchema WEBVIEW_PW_SCHEMA = {
    "ca.weblite.webview.passwords",
    WV_SECRET_SCHEMA_NONE,
    {
        { "service",  WV_SECRET_SCHEMA_ATTRIBUTE_STRING },
        { "origin",   WV_SECRET_SCHEMA_ATTRIBUTE_STRING },
        { "username", WV_SECRET_SCHEMA_ATTRIBUTE_STRING },
        { NULL, WV_SECRET_SCHEMA_ATTRIBUTE_STRING }
    },
    0, NULL, NULL, NULL, NULL, NULL, NULL, NULL
};

typedef gboolean (*pw_store_sync_fn)(const WvSecretSchema *, const gchar *,
    const gchar *, const gchar *, void *, GError **, ...);
typedef gboolean (*pw_clear_sync_fn)(const WvSecretSchema *, void *,
    GError **, ...);
typedef GList *(*pw_search_sync_fn)(const WvSecretSchema *, int, void *,
    GError **, ...);
typedef GHashTable *(*pw_get_attrs_fn)(void *);
typedef void *(*pw_retrieve_secret_sync_fn)(void *, void *, GError **);
typedef const gchar *(*pw_value_get_text_fn)(void *);
typedef void (*pw_value_unref_fn)(void *);

static pw_store_sync_fn            p_secret_store_sync = nullptr;
static pw_clear_sync_fn            p_secret_clear_sync = nullptr;
static pw_search_sync_fn           p_secret_search_sync = nullptr;
static pw_get_attrs_fn             p_secret_get_attrs = nullptr;
static pw_retrieve_secret_sync_fn  p_secret_retrieve_secret_sync = nullptr;
static pw_value_get_text_fn        p_secret_value_get_text = nullptr;
static pw_value_unref_fn           p_secret_value_unref = nullptr;

static bool g_secret_ok = false;
static bool g_secret_tried = false;

// Resolve libsecret once; cache the result.  Any missing symbol ⇒ off.
static bool ensure_secret() {
    if (g_secret_tried) return g_secret_ok;
    g_secret_tried = true;
    void *h = dlopen("libsecret-1.so.0", RTLD_NOW | RTLD_GLOBAL);
    if (!h) { g_secret_ok = false; return false; }
    p_secret_store_sync = (pw_store_sync_fn)dlsym(h, "secret_password_store_sync");
    p_secret_clear_sync = (pw_clear_sync_fn)dlsym(h, "secret_password_clear_sync");
    p_secret_search_sync = (pw_search_sync_fn)dlsym(h, "secret_password_search_sync");
    p_secret_get_attrs = (pw_get_attrs_fn)dlsym(h, "secret_retrievable_get_attributes");
    p_secret_retrieve_secret_sync = (pw_retrieve_secret_sync_fn)
        dlsym(h, "secret_retrievable_retrieve_secret_sync");
    p_secret_value_get_text = (pw_value_get_text_fn)dlsym(h, "secret_value_get_text");
    p_secret_value_unref = (pw_value_unref_fn)dlsym(h, "secret_value_unref");
    g_secret_ok = p_secret_store_sync && p_secret_clear_sync
        && p_secret_search_sync && p_secret_get_attrs
        && p_secret_retrieve_secret_sync && p_secret_value_get_text
        && p_secret_value_unref;
    return g_secret_ok;
}
#endif // WEBVIEW_GTK

JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1save
  (JNIEnv *env, jclass, jstring jservice, jstring jorigin, jstring juser,
   jstring jpass, jlong millis) {
#if defined(WEBVIEW_COCOA)
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    const char *user = env->GetStringUTFChars(juser, nullptr);
    const char *pass = env->GetStringUTFChars(jpass, nullptr);
    std::string svc = std::string(service ? service : "") + ":"
        + (origin ? origin : "");
    std::string value = std::to_string((long long)millis) + "\n"
        + (pass ? pass : "");
    CFStringRef cfSvc = pw_cf(svc.c_str());
    CFStringRef cfAcct = pw_cf(user ? user : "");
    CFDataRef cfVal = CFDataCreate(kCFAllocatorDefault,
        (const UInt8 *)value.data(), (CFIndex)value.size());
    const void *qk[] = { kSecClass, kSecAttrService, kSecAttrAccount };
    const void *qv[] = { kSecClassGenericPassword, cfSvc, cfAcct };
    CFDictionaryRef query = CFDictionaryCreate(kCFAllocatorDefault, qk, qv, 3,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    jboolean ok = JNI_FALSE;
    if (SecItemCopyMatching(query, nullptr) == errSecSuccess) {
        const void *uk[] = { kSecValueData };
        const void *uv[] = { cfVal };
        CFDictionaryRef upd = CFDictionaryCreate(kCFAllocatorDefault, uk, uv, 1,
            &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        ok = (SecItemUpdate(query, upd) == errSecSuccess) ? JNI_TRUE : JNI_FALSE;
        CFRelease(upd);
    } else {
        const void *ak[] = { kSecClass, kSecAttrService, kSecAttrAccount,
                             kSecValueData, kSecAttrSynchronizable };
        const void *av[] = { kSecClassGenericPassword, cfSvc, cfAcct,
                             cfVal, kCFBooleanFalse };
        CFDictionaryRef add = CFDictionaryCreate(kCFAllocatorDefault, ak, av, 5,
            &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        OSStatus addSt = SecItemAdd(add, nullptr);
        if (addSt == errSecDuplicateItem) {
            // The existence check above said no and the add says duplicate:
            // the query and the item disagree on an attribute (an empty
            // account is the case that produced this).  Update rather than
            // report a failure the caller cannot act on.
            const void *uk[] = { kSecValueData };
            const void *uv[] = { cfVal };
            CFDictionaryRef upd = CFDictionaryCreate(kCFAllocatorDefault,
                uk, uv, 1, &kCFTypeDictionaryKeyCallBacks,
                &kCFTypeDictionaryValueCallBacks);
            addSt = SecItemUpdate(query, upd);
            CFRelease(upd);
        }
        ok = (addSt == errSecSuccess) ? JNI_TRUE : JNI_FALSE;
        CFRelease(add);
    }
    CFRelease(query); CFRelease(cfSvc); CFRelease(cfAcct); CFRelease(cfVal);
    if (service) env->ReleaseStringUTFChars(jservice, service);
    if (origin) env->ReleaseStringUTFChars(jorigin, origin);
    if (user) env->ReleaseStringUTFChars(juser, user);
    if (pass) env->ReleaseStringUTFChars(jpass, pass);
    return ok;
#elif defined(WEBVIEW_GTK)
    if (!ensure_secret()) {
        (void)jservice; (void)jorigin; (void)juser; (void)jpass; (void)millis;
        return JNI_FALSE;
    }
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    const char *user = env->GetStringUTFChars(juser, nullptr);
    const char *pass = env->GetStringUTFChars(jpass, nullptr);
    // Same value encoding as macOS: "<millis>\n<password>".
    std::string value = std::to_string((long long)millis) + "\n"
        + (pass ? pass : "");
    std::string label = std::string("WebView password for ")
        + (origin ? origin : "");
    GError *err = nullptr;
    gboolean ok = p_secret_store_sync(
        &WEBVIEW_PW_SCHEMA, "default", label.c_str(), value.c_str(),
        nullptr, &err,
        "service", service ? service : "",
        "origin", origin ? origin : "",
        "username", user ? user : "",
        (const char *)nullptr);
    if (service) env->ReleaseStringUTFChars(jservice, service);
    if (origin) env->ReleaseStringUTFChars(jorigin, origin);
    if (user) env->ReleaseStringUTFChars(juser, user);
    if (pass) env->ReleaseStringUTFChars(jpass, pass);
    if (err) { g_error_free(err); return JNI_FALSE; }
    return ok ? JNI_TRUE : JNI_FALSE;
#else
    (void)env; (void)jservice; (void)jorigin; (void)juser; (void)jpass;
    (void)millis;
    return JNI_FALSE;
#endif
}

JNIEXPORT jobjectArray JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1find
  (JNIEnv *env, jclass, jstring jservice, jstring jorigin) {
    jclass strCls = env->FindClass("java/lang/String");
#if defined(WEBVIEW_COCOA)
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    std::string svc = std::string(service ? service : "") + ":"
        + (origin ? origin : "");
    CFStringRef cfSvc = pw_cf(svc.c_str());
    std::vector<std::string> triples;
    // The macOS keychain rejects kSecMatchLimitAll combined with
    // kSecReturnData (errSecParam), so read in two phases: phase 1
    // enumerates the matching items (attributes and refs, no data); phase 2
    // fetches each item's secret by reference with a data-returning query.
    const void *q1k[] = { kSecClass, kSecAttrService, kSecMatchLimit,
                          kSecReturnAttributes, kSecReturnRef };
    const void *q1v[] = { kSecClassGenericPassword, cfSvc, kSecMatchLimitAll,
                          kCFBooleanTrue, kCFBooleanTrue };
    CFDictionaryRef q1 = CFDictionaryCreate(kCFAllocatorDefault, q1k, q1v, 5,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFTypeRef listResult = nullptr;
    OSStatus st = SecItemCopyMatching(q1, &listResult);
    if (st == errSecSuccess && listResult) {
        CFArrayRef arr = (CFArrayRef)listResult;
        CFIndex n = CFArrayGetCount(arr);
        for (CFIndex i = 0; i < n; i++) {
            CFDictionaryRef item =
                (CFDictionaryRef)CFArrayGetValueAtIndex(arr, i);
            // A credential saved with NO user name -- a two-step login, where
            // the page carrying the password field has no username field --
            // comes back with kSecAttrAccount empty or absent.  Skipping it
            // here hid the credential from every lookup while it sat in the
            // keychain: the save wrote it, and nothing could ever read it
            // back.  An absent account is an empty user name, not a bad row.
            CFStringRef acct =
                (CFStringRef)CFDictionaryGetValue(item, kSecAttrAccount);
            std::string username, millisStr = "0", password;
            if (acct) {
                CFIndex maxlen = CFStringGetMaximumSizeForEncoding(
                    CFStringGetLength(acct), kCFStringEncodingUTF8) + 1;
                std::vector<char> buf((size_t)maxlen);
                if (!CFStringGetCString(acct, buf.data(), maxlen,
                                        kCFStringEncodingUTF8)) {
                    continue;
                }
                username = buf.data();
            }
            // Phase 2: fetch this item's secret BY REFERENCE.  Matching on
            // {service, account} a second time cannot fetch an item whose
            // account did not round-trip; the ref names the row phase 1 has
            // already found, so the fetch no longer depends on an attribute
            // matching itself.
            CFTypeRef ref = CFDictionaryGetValue(item, kSecValueRef);
            if (!ref) continue;
            const void *q2k[] = { kSecValueRef, kSecReturnData };
            const void *q2v[] = { ref, kCFBooleanTrue };
            CFDictionaryRef q2 = CFDictionaryCreate(kCFAllocatorDefault,
                q2k, q2v, 2, &kCFTypeDictionaryKeyCallBacks,
                &kCFTypeDictionaryValueCallBacks);
            CFTypeRef dataResult = nullptr;
            if (SecItemCopyMatching(q2, &dataResult) == errSecSuccess
                    && dataResult) {
                CFDataRef data = (CFDataRef)dataResult;
                const UInt8 *bytes = CFDataGetBytePtr(data);
                CFIndex len = CFDataGetLength(data);
                std::string blob((const char *)bytes, (size_t)len);
                size_t nl = blob.find('\n');
                if (nl != std::string::npos) {
                    millisStr = blob.substr(0, nl);
                    password = blob.substr(nl + 1);
                } else {
                    password = blob;
                }
            }
            if (dataResult) CFRelease(dataResult);
            CFRelease(q2);
            triples.push_back(username);
            triples.push_back(millisStr);
            triples.push_back(password);
        }
    }
    if (listResult) CFRelease(listResult);
    CFRelease(q1); CFRelease(cfSvc);
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
#elif defined(WEBVIEW_GTK)
    if (!ensure_secret()) {
        (void)jservice; (void)jorigin;
        return env->NewObjectArray(0, strCls, nullptr);
    }
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    std::vector<std::string> triples;
    GError *err = nullptr;
    // Search all items matching {service, origin} (username unbound) with
    // secrets loaded; libsecret filters by the schema attributes.
    GList *items = p_secret_search_sync(
        &WEBVIEW_PW_SCHEMA,
        WV_SECRET_SEARCH_ALL | WV_SECRET_SEARCH_UNLOCK
            | WV_SECRET_SEARCH_LOAD_SECRETS,
        nullptr, &err,
        "service", service ? service : "",
        "origin", origin ? origin : "",
        (const char *)nullptr);
    if (err) { g_error_free(err); err = nullptr; }
    for (GList *l = items; l != nullptr; l = l->next) {
        void *item = l->data;
        if (!item) continue;
        std::string username, millisStr = "0", password;
        GHashTable *attrs = p_secret_get_attrs(item);
        if (attrs) {
            const char *uname =
                (const char *)g_hash_table_lookup(attrs, (gpointer)"username");
            if (uname) username = uname;
        }
        GError *e2 = nullptr;
        void *val = p_secret_retrieve_secret_sync(item, nullptr, &e2);
        if (e2) { g_error_free(e2); e2 = nullptr; }
        if (val) {
            const char *text = p_secret_value_get_text(val);
            if (text) {
                std::string blob(text);
                size_t nl = blob.find('\n');
                if (nl != std::string::npos) {
                    millisStr = blob.substr(0, nl);
                    password = blob.substr(nl + 1);
                } else {
                    password = blob;
                }
            }
            p_secret_value_unref(val);
        }
        if (attrs) g_hash_table_unref(attrs);
        triples.push_back(username);
        triples.push_back(millisStr);
        triples.push_back(password);
    }
    if (items) g_list_free_full(items, g_object_unref);
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
#else
    (void)jservice; (void)jorigin;
    return env->NewObjectArray(0, strCls, nullptr);
#endif
}

JNIEXPORT jobjectArray JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1find_1all
  (JNIEnv *env, jclass, jstring jservice) {
    jclass strCls = env->FindClass("java/lang/String");
#if defined(WEBVIEW_COCOA)
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    std::string prefix = std::string(service ? service : "") + ":";
    // Each item's kSecAttrService is "<namespace>:<origin>" (origin embedded
    // in the service, plain username as the account), so there is no single
    // service value covering all origins and the keychain has no prefix
    // query.  Enumerate every generic-password item and keep those whose
    // service starts with "<service>:".  Two phases as in find: the
    // kSecMatchLimitAll + kSecReturnData combination returns errSecParam.
    std::vector<std::string> quads;
    const void *q1k[] = { kSecClass, kSecMatchLimit, kSecReturnAttributes,
                          kSecReturnRef };
    const void *q1v[] = { kSecClassGenericPassword, kSecMatchLimitAll,
                          kCFBooleanTrue, kCFBooleanTrue };
    CFDictionaryRef q1 = CFDictionaryCreate(kCFAllocatorDefault, q1k, q1v, 4,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFTypeRef listResult = nullptr;
    OSStatus st = SecItemCopyMatching(q1, &listResult);
    if (st == errSecSuccess && listResult) {
        CFArrayRef arr = (CFArrayRef)listResult;
        CFIndex n = CFArrayGetCount(arr);
        for (CFIndex i = 0; i < n; i++) {
            CFDictionaryRef item =
                (CFDictionaryRef)CFArrayGetValueAtIndex(arr, i);
            CFStringRef svcAttr =
                (CFStringRef)CFDictionaryGetValue(item, kSecAttrService);
            CFStringRef acct =
                (CFStringRef)CFDictionaryGetValue(item, kSecAttrAccount);
            // An absent account is a credential saved with no user name (a
            // two-step login), not a row to drop -- see find() above.
            if (!svcAttr) continue;
            // Read the full service string.
            CFIndex svcMax = CFStringGetMaximumSizeForEncoding(
                CFStringGetLength(svcAttr), kCFStringEncodingUTF8) + 1;
            std::vector<char> svcBuf((size_t)svcMax);
            if (!CFStringGetCString(svcAttr, svcBuf.data(), svcMax,
                                    kCFStringEncodingUTF8)) {
                continue;
            }
            std::string fullSvc = svcBuf.data();
            // Keep only our namespace; derive origin from the suffix.
            if (fullSvc.size() < prefix.size()
                    || fullSvc.compare(0, prefix.size(), prefix) != 0) {
                continue;
            }
            std::string origin = fullSvc.substr(prefix.size());
            // Read the account (username); absent means none was captured.
            std::string username;
            if (acct) {
                CFIndex acctMax = CFStringGetMaximumSizeForEncoding(
                    CFStringGetLength(acct), kCFStringEncodingUTF8) + 1;
                std::vector<char> acctBuf((size_t)acctMax);
                if (!CFStringGetCString(acct, acctBuf.data(), acctMax,
                                        kCFStringEncodingUTF8)) {
                    continue;
                }
                username = acctBuf.data();
            }
            std::string millisStr = "0", password;
            // Phase 2: fetch this item's secret by reference, not by matching
            // {service, account} again -- see find() above.
            CFTypeRef ref = CFDictionaryGetValue(item, kSecValueRef);
            if (!ref) continue;
            const void *q2k[] = { kSecValueRef, kSecReturnData };
            const void *q2v[] = { ref, kCFBooleanTrue };
            CFDictionaryRef q2 = CFDictionaryCreate(kCFAllocatorDefault,
                q2k, q2v, 2, &kCFTypeDictionaryKeyCallBacks,
                &kCFTypeDictionaryValueCallBacks);
            CFTypeRef dataResult = nullptr;
            if (SecItemCopyMatching(q2, &dataResult) == errSecSuccess
                    && dataResult) {
                CFDataRef data = (CFDataRef)dataResult;
                const UInt8 *bytes = CFDataGetBytePtr(data);
                CFIndex len = CFDataGetLength(data);
                std::string blob((const char *)bytes, (size_t)len);
                size_t nl = blob.find('\n');
                if (nl != std::string::npos) {
                    millisStr = blob.substr(0, nl);
                    password = blob.substr(nl + 1);
                } else {
                    password = blob;
                }
            }
            if (dataResult) CFRelease(dataResult);
            CFRelease(q2);
            quads.push_back(origin);
            quads.push_back(username);
            quads.push_back(millisStr);
            quads.push_back(password);
        }
    }
    if (listResult) CFRelease(listResult);
    CFRelease(q1);
    if (service) env->ReleaseStringUTFChars(jservice, service);
    jobjectArray out =
        env->NewObjectArray((jsize)quads.size(), strCls, nullptr);
    for (size_t i = 0; i < quads.size(); i++) {
        jstring js = env->NewStringUTF(quads[i].c_str());
        env->SetObjectArrayElement(out, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return out;
#elif defined(WEBVIEW_GTK)
    if (!ensure_secret()) {
        (void)jservice;
        return env->NewObjectArray(0, strCls, nullptr);
    }
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    std::vector<std::string> quads;
    GError *err = nullptr;
    // Enumerate every item in our namespace: bind only "service", leaving
    // origin and username unbound (STORY-006-005).
    GList *items = p_secret_search_sync(
        &WEBVIEW_PW_SCHEMA,
        WV_SECRET_SEARCH_ALL | WV_SECRET_SEARCH_UNLOCK
            | WV_SECRET_SEARCH_LOAD_SECRETS,
        nullptr, &err,
        "service", service ? service : "",
        (const char *)nullptr);
    if (err) { g_error_free(err); err = nullptr; }
    for (GList *l = items; l != nullptr; l = l->next) {
        void *item = l->data;
        if (!item) continue;
        std::string origin, username, millisStr = "0", password;
        GHashTable *attrs = p_secret_get_attrs(item);
        if (attrs) {
            const char *o =
                (const char *)g_hash_table_lookup(attrs, (gpointer)"origin");
            const char *u =
                (const char *)g_hash_table_lookup(attrs, (gpointer)"username");
            if (o) origin = o;
            if (u) username = u;
        }
        GError *e2 = nullptr;
        void *val = p_secret_retrieve_secret_sync(item, nullptr, &e2);
        if (e2) { g_error_free(e2); e2 = nullptr; }
        if (val) {
            const char *text = p_secret_value_get_text(val);
            if (text) {
                std::string blob(text);
                size_t nl = blob.find('\n');
                if (nl != std::string::npos) {
                    millisStr = blob.substr(0, nl);
                    password = blob.substr(nl + 1);
                } else {
                    password = blob;
                }
            }
            p_secret_value_unref(val);
        }
        if (attrs) g_hash_table_unref(attrs);
        quads.push_back(origin);
        quads.push_back(username);
        quads.push_back(millisStr);
        quads.push_back(password);
    }
    if (items) g_list_free_full(items, g_object_unref);
    if (service) env->ReleaseStringUTFChars(jservice, service);
    jobjectArray out =
        env->NewObjectArray((jsize)quads.size(), strCls, nullptr);
    for (size_t i = 0; i < quads.size(); i++) {
        jstring js = env->NewStringUTF(quads[i].c_str());
        env->SetObjectArrayElement(out, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return out;
#else
    (void)jservice;
    return env->NewObjectArray(0, strCls, nullptr);
#endif
}

JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1delete
  (JNIEnv *env, jclass, jstring jservice, jstring jorigin, jstring juser) {
#if defined(WEBVIEW_COCOA)
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    const char *user = env->GetStringUTFChars(juser, nullptr);
    std::string svc = std::string(service ? service : "") + ":"
        + (origin ? origin : "");
    CFStringRef cfSvc = pw_cf(svc.c_str());
    CFStringRef cfAcct = pw_cf(user ? user : "");
    const void *qk[] = { kSecClass, kSecAttrService, kSecAttrAccount };
    const void *qv[] = { kSecClassGenericPassword, cfSvc, cfAcct };
    CFDictionaryRef query = CFDictionaryCreate(kCFAllocatorDefault, qk, qv, 3,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    OSStatus st = SecItemDelete(query);
    CFRelease(query); CFRelease(cfSvc); CFRelease(cfAcct);
    if (service) env->ReleaseStringUTFChars(jservice, service);
    if (origin) env->ReleaseStringUTFChars(jorigin, origin);
    if (user) env->ReleaseStringUTFChars(juser, user);
    return (st == errSecSuccess) ? JNI_TRUE : JNI_FALSE;
#elif defined(WEBVIEW_GTK)
    if (!ensure_secret()) {
        (void)jservice; (void)jorigin; (void)juser;
        return JNI_FALSE;
    }
    const char *service = env->GetStringUTFChars(jservice, nullptr);
    const char *origin = env->GetStringUTFChars(jorigin, nullptr);
    const char *user = env->GetStringUTFChars(juser, nullptr);
    GError *err = nullptr;
    gboolean removed = p_secret_clear_sync(
        &WEBVIEW_PW_SCHEMA, nullptr, &err,
        "service", service ? service : "",
        "origin", origin ? origin : "",
        "username", user ? user : "",
        (const char *)nullptr);
    if (service) env->ReleaseStringUTFChars(jservice, service);
    if (origin) env->ReleaseStringUTFChars(jorigin, origin);
    if (user) env->ReleaseStringUTFChars(juser, user);
    if (err) { g_error_free(err); return JNI_FALSE; }
    return removed ? JNI_TRUE : JNI_FALSE;
#else
    (void)env; (void)jservice; (void)jorigin; (void)juser;
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1cred_1store_1available
  (JNIEnv *env, jclass) {
    (void)env;
#if defined(WEBVIEW_COCOA)
    return JNI_TRUE;
#elif defined(WEBVIEW_GTK)
    return ensure_secret() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

// Popup (window.open) callback registration — Canvas 15 (Java + macOS),
// Canvas 16 (Linux / GTK).  Windows native popup site lands in Canvas 17, so
// that branch stays a no-op for now and window.open stays blocked there (the
// Java handler reference is held but no native callback is invoked).
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1popup_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    if (wv == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_set_popup_callback((embed::Engine *)wv, env, cb);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_popup_callback((embed::Engine *)wv, env, cb);
#else
    (void)env; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1download_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    if (wv == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_set_download_callback((embed::Engine *)wv, env, cb);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_download_callback((embed::Engine *)wv, env, cb);
#else
    (void)env; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1download_1callback
  (JNIEnv *env, jclass, jlong peer, jobject cb) {
    if (peer == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_off_set_download_callback((embed::OffEngine *)peer, env, cb);
#else
    // No offscreen engine on macOS or Windows -- same shape as the
    // dialog and popup offscreen bridges.
    (void)env; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1popup_1callback
  (JNIEnv *env, jclass, jlong peer, jobject cb) {
    if (peer == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_off_set_popup_callback((embed::OffEngine *)peer, env, cb);
#else
    // macOS / Windows have no offscreen engine; OffscreenWebView.create
    // returns null there so this is never reached.
    (void)env; (void)cb;
#endif
}

// Navigation decisions — Canvas 34 D5.  webview_navigation_available is the
// support probe (an older native lacks the symbol); the setters install the
// WebViewNavigationCallback each engine asks before every navigation.
JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1navigation_1available
  (JNIEnv *, jclass) {
#if defined(WEBVIEW_GTK) || defined(WEBVIEW_COCOA)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1navigation_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    if (wv == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_set_navigation_callback((embed::Engine *)wv, env, cb);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_navigation_callback((embed::Engine *)wv, env, cb);
#else
    (void)env; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1navigation_1callback
  (JNIEnv *env, jclass, jlong peer, jobject cb) {
    if (peer == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_off_set_navigation_callback((embed::OffEngine *)peer, env, cb);
#else
    // No offscreen engine on macOS or Windows.
    (void)env; (void)cb;
#endif
}

// Popup-adoption JNI bridges — Canvas 18 (macOS) + Canvas 19 (Linux).  Must
// live inside this `extern "C"` block (they are newly-added methods with no
// generated-header prototype, so C++ name-mangling would otherwise make them
// unresolvable from the JVM — UnsatisfiedLinkError; this exact bug was hit and
// fixed for the dialog setters).  macOS reparents the retained WKWebView via
// cocoa_adopt_popup; Linux reparents the retained WebKitGTK child via
// gtk_adopt_popup (Canvas 19).  Windows (Canvas 20) lands its reparent later,
// so it still returns 0 (EmbeddedWebView.adopt turns 0 into a documented
// IllegalStateException).
JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1adopt_1popup
  (JNIEnv *env, jclass, jobject parent, jlong popupId, jint debug) {
#ifdef WEBVIEW_GTK
    Engine *e = embed::gtk_adopt_popup(env, parent, popupId, debug);
    return (jlong)e;
#elif defined(WEBVIEW_COCOA)
    Engine *e = embed::cocoa_adopt_popup(env, parent, popupId, debug);
    return (jlong)e;
#else
    (void)env; (void)parent; (void)popupId; (void)debug;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1discard_1popup
  (JNIEnv *, jclass, jlong /*w*/, jlong popupId) {
#ifdef WEBVIEW_GTK
    embed::gtk_discard_popup(popupId);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_discard_popup(popupId);
#else
    (void)popupId;
#endif
}

// Custom User-Agent registration — Canvas 21.  ua == null clears the override
// (engine default).  Takes effect on the next navigation.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1user_1agent
  (JNIEnv *env, jclass, jlong wv, jstring ua) {
    if (wv == 0) return;
    const char *s = ua ? env->GetStringUTFChars(ua, nullptr) : nullptr;
#ifdef WEBVIEW_GTK
    embed::gtk_set_user_agent((embed::Engine *)wv, s);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_user_agent((embed::Engine *)wv, s);
#else
    (void)wv;
#endif
    if (ua && s) env->ReleaseStringUTFChars(ua, s);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1user_1agent
  (JNIEnv *env, jclass, jlong peer, jstring ua) {
    if (peer == 0) return;
    const char *s = ua ? env->GetStringUTFChars(ua, nullptr) : nullptr;
#ifdef WEBVIEW_GTK
    embed::gtk_off_set_user_agent((embed::OffEngine *)peer, s);
#else
    (void)peer;
#endif
    if (ua && s) env->ReleaseStringUTFChars(ua, s);
}

// Install/clear the per-destination User-Agent resolver — Canvas 21 (1.5.0).
// The resolver is a java.util.function.Function<String,String>; it is held as a
// JNI global ref and consulted at the popup-child creation site with the
// child's own target URL.  resolver == nullptr clears it.  Never throws.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1user_1agent_1resolver
  (JNIEnv *env, jclass, jlong wv, jobject resolver) {
    if (wv == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_set_user_agent_resolver((embed::Engine *)wv, env, resolver);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_user_agent_resolver((embed::Engine *)wv, env, resolver);
#else
    (void)wv; (void)env; (void)resolver;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1user_1agent_1resolver
  (JNIEnv *env, jclass, jlong peer, jobject resolver) {
    if (peer == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_off_set_user_agent_resolver((embed::OffEngine *)peer, env, resolver);
#else
    (void)peer; (void)env; (void)resolver;
#endif
}

// Clear the embedded WebView's HTTP resource cache — Canvas 22.  Resource
// cache only (cookies survive).  Runs on the engine UI thread; asynchronous.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1clear_1cache
  (JNIEnv *, jclass, jlong wv) {
    if (wv == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_clear_cache((embed::Engine *)wv);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_clear_cache((embed::Engine *)wv);
#else
    (void)wv;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1clear_1cache
  (JNIEnv *, jclass, jlong peer) {
    if (peer == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_off_clear_cache((embed::OffEngine *)peer);
#else
    (void)peer;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1get_1cookies
  (JNIEnv *env, jclass, jlong wv, jstring url, jobject callback) {
    embed::CookieCompletion *completion =
        embed::new_cookie_completion(env, callback);
    if (!completion) return;
    if (wv == 0 || !url) {
        embed::complete_cookie_query(completion, "", "WebView is not available");
        return;
    }
    const char *chars = env->GetStringUTFChars(url, nullptr);
    std::string value = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(url, chars);
#ifdef WEBVIEW_GTK
    embed::gtk_get_cookies(((embed::Engine *)wv)->web, value, completion);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_get_cookies((embed::Engine *)wv, value, completion);
#else
    embed::complete_cookie_query(completion, "", "Cookie queries are unsupported");
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1get_1cookies
  (JNIEnv *env, jclass, jlong peer, jstring url, jobject callback) {
    embed::CookieCompletion *completion =
        embed::new_cookie_completion(env, callback);
    if (!completion) return;
    if (peer == 0 || !url) {
        embed::complete_cookie_query(completion, "", "WebView is not available");
        return;
    }
    const char *chars = env->GetStringUTFChars(url, nullptr);
    std::string value = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(url, chars);
#ifdef WEBVIEW_GTK
    embed::gtk_get_cookies(((embed::OffEngine *)peer)->web, value, completion);
#else
    embed::complete_cookie_query(completion, "", "Offscreen cookie queries are unsupported");
#endif
}

// Print to PDF — Canvas 29.  Must live inside this `extern "C"` block (D11).
// Every path answers the callback exactly once; nothing throws into Java.
JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1pdf_1available
  (JNIEnv *, jclass) {
#if defined(WEBVIEW_GTK) || defined(WEBVIEW_COCOA)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// Custom URL schemes — Canvases 30 and 31.  Inside this `extern "C"` block like
// every export.  macOS and Linux serve them now; Windows follows in Canvas 32.
// On GTK the mandatory scheme symbols are resolved in JNI_OnLoad, so a loaded
// library can always serve them (Canvas 31 D9).
JNIEXPORT jboolean JNICALL Java_ca_weblite_webview_WebViewNative_webview_1scheme_1available
  (JNIEnv *, jclass) {
#if defined(WEBVIEW_COCOA) || defined(WEBVIEW_GTK)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1scheme_1install
  (JNIEnv *env, jclass, jobjectArray names, jobject dispatcher) {
    std::lock_guard<std::mutex> lk(embed::g_scheme_mutex);
    if (embed::g_scheme_installed) return;
    embed::g_scheme_installed = true;
    env->GetJavaVM(&embed::g_scheme_jvm);
    if (dispatcher) embed::g_scheme_dispatcher = env->NewGlobalRef(dispatcher);
    jsize n = names ? env->GetArrayLength(names) : 0;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(names, i);
        if (!js) continue;
        const char *c = env->GetStringUTFChars(js, nullptr);
        if (c) {
            embed::g_scheme_names.push_back(c);
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
#if defined(WEBVIEW_COCOA)
    embed::cocoa_scheme_respond((long long)id, (int)status, pairs, bytes);
#elif defined(WEBVIEW_GTK)
    embed::gtk_scheme_respond((long long)id, (int)status, pairs, bytes);
#else
    (void)id; (void)status;
#endif
}

static std::string embed_jstring_utf8(JNIEnv *env, jstring s) {
    std::string out;
    if (!s) return out;
    const char *c = env->GetStringUTFChars(s, nullptr);
    if (c) {
        out = c;
        env->ReleaseStringUTFChars(s, c);
    }
    return out;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1print_1to_1pdf
  (JNIEnv *env, jclass, jlong wv, jstring path, jdouble w, jdouble h,
   jdouble mt, jdouble mr, jdouble mb, jdouble ml, jboolean bg, jobject cb) {
    embed::PdfJob *job = embed::pdf_new_job(env, cb);
    if (wv == 0) {
        embed::pdf_finish(job, false, embed::kPdfNotAttached);
        return;
    }
    std::string p = embed_jstring_utf8(env, path);
#ifdef WEBVIEW_GTK
    // Linux supports the lightweight component only (Canvas 29 D13): printing
    // the embedded (heavyweight) view crashes inside WebKitGTK's print
    // operation, so refuse rather than take the JVM down.
    (void)p; (void)w; (void)h; (void)mt; (void)mr; (void)mb; (void)ml; (void)bg;
    embed::pdf_finish(job, false,
        "PDF printing on Linux needs the lightweight WebView component.");
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_print_to_pdf((embed::Engine *)wv, job, p, w, h, mt, mr, mb, ml,
                              bg == JNI_TRUE);
#else
    (void)p; (void)w; (void)h; (void)mt; (void)mr; (void)mb; (void)ml; (void)bg;
    embed::pdf_finish(job, false,
        "PDF printing is not available in this version of the native library.");
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1print_1to_1pdf
  (JNIEnv *env, jclass, jlong peer, jstring path, jdouble w, jdouble h,
   jdouble mt, jdouble mr, jdouble mb, jdouble ml, jboolean bg, jobject cb) {
    embed::PdfJob *job = embed::pdf_new_job(env, cb);
#ifdef WEBVIEW_GTK
    if (peer == 0) {
        embed::pdf_finish(job, false, embed::kPdfNotAttached);
        return;
    }
    embed::OffEngine *e = (embed::OffEngine *)peer;
    embed::gtk_print_to_pdf([e] { return e->web; }, job,
                            embed_jstring_utf8(env, path), w, h, mt, mr, mb,
                            ml, bg == JNI_TRUE);
#else
    // No offscreen engine off Linux (Canvas 29 op 10).
    (void)peer; (void)path; (void)w; (void)h; (void)mt; (void)mr; (void)mb;
    (void)ml; (void)bg;
    embed::pdf_finish(job, false, embed::kPdfNotAttached);
#endif
}

// Offscreen popup-adoption JNI bridges — Canvas 19 (Linux lightweight
// coverage).  Must live inside this `extern "C"` block (they are newly-added
// methods with no generated-header prototype, so C++ name-mangling would
// otherwise make them unresolvable from the JVM — UnsatisfiedLinkError; the
// exact bug already hit and fixed for the dialog setters and the heavyweight
// adopt/discard bridges).  Linux reuses the retained WebKitGTK child inside a
// hidden popup toplevel (Canvas 33) via gtk_off_adopt_popup; the reclaim path reuses the
// SHARED gtk_discard_popup (the retained child is the same PopupEngine in the
// same registry).  macOS / Windows offscreen engines are stubs, so adopt
// returns 0 (OffscreenWebView.adopt turns 0 into an IllegalStateException) and
// discard is a no-op.
JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1adopt_1popup
  (JNIEnv *env, jclass, jint width, jint height, jlong popupId, jint debug) {
#ifdef WEBVIEW_GTK
    return (jlong)embed::gtk_off_adopt_popup(env, popupId, width, height, debug);
#else
    (void)env; (void)width; (void)height; (void)popupId; (void)debug;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1discard_1popup
  (JNIEnv *, jclass, jlong /*peer*/, jlong popupId) {
#ifdef WEBVIEW_GTK
    embed::gtk_discard_popup(popupId);
#else
    (void)popupId;
#endif
}

} // extern "C"

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1init
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_off_init_script((embed::OffEngine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1eval
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_off_eval((embed::OffEngine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1bind
  (JNIEnv *env, jclass, jlong wv, jstring name, jobject fn, jlong /*arg*/) {
#ifdef WEBVIEW_GTK
    auto *e = (embed::OffEngine *)wv;
    if (!e) return;
    const char *n = env->GetStringUTFChars(name, nullptr);
    embed::Binding *b = new embed::Binding();
    b->name = n ? n : "";
    b->fn = env->NewGlobalRef(fn);
    jclass cls = env->GetObjectClass(fn);
    b->cls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);

    // Byte-identical to the heavyweight bind shim at
    // webview_embed.cpp:1791-1801 so the window.<name>(...) contract
    // is the same in both modes.
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

    embed::gtk_off_bind(e, b);
    embed::gtk_off_init_script(e, js);
    env->ReleaseStringUTFChars(name, n);
#else
    (void)env; (void)wv; (void)name; (void)fn;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1dispatch
  (JNIEnv *env, jclass, jlong wv, jobject callback) {
#ifdef WEBVIEW_GTK
    auto *e = (embed::OffEngine *)wv;
    if (!e) return;
    JavaVM *jvm = e->jvm;
    jobject ref = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    jclass gcls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);

    auto fn = [jvm, ref, gcls] {
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
    };
    embed::GtkPump::instance().run_async(fn);
#else
    (void)env; (void)wv; (void)callback;
#endif
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1open_1devtools
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    return (jint)embed::gtk_off_open_devtools((embed::OffEngine *)wv);
#else
    (void)wv;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1execute_1editing_1command
  (JNIEnv *, jclass, jlong wv, jint cmdId) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_execute_editing_command(
        (embed::OffEngine *)wv, (int)cmdId);
#else
    (void)wv; (void)cmdId;
#endif
}
