// QPlayer standalone hosts for the MIT-licensed swingwebview 1.10.0 engines.
// The upstream Java Swing layer is deliberately not used or loaded.
#ifdef _WIN32
#include "upstream/windows/webview_embed.cc"
#else
#include "upstream/src_c/webview_embed.cpp"
#endif

namespace qplayer {
struct Browser {
#ifdef _WIN32
    embed_win::Engine *engine = nullptr;
    std::thread worker;
#elif defined(WEBVIEW_GTK)
    embed::OffEngine *engine = nullptr;
#else
    embed::Engine *engine = nullptr;
    id window = nullptr;
#endif
    bool visible = false;
    std::atomic<bool> open{true};
};
static std::string string(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *p = env->GetStringUTFChars(value, nullptr);
    std::string result = p ? p : "";
    if (p) env->ReleaseStringUTFChars(value, p);
    return result;
}
static void error(JNIEnv *env, const char *text) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, text);
}
#ifdef _WIN32
static void wait_for_browser(embed_win::Engine *e) {
    if (!e->standalone_browser_process) return;
    WaitForSingleObject(e->standalone_browser_process, 10000);
    CloseHandle(e->standalone_browser_process);
    e->standalone_browser_process = nullptr;
}
// WebView2's environment variable takes precedence over its API argument.
// Keep our override until asynchronous environment/controller creation finishes.
// Java serializes creation; existing environments no longer consult this value.
struct ProfileOverride {
    std::wstring previous;
    bool existed = false;
    bool changed = false;
    ProfileOverride() {
        SetLastError(ERROR_SUCCESS);
        DWORD size = GetEnvironmentVariableW(L"WEBVIEW2_USER_DATA_FOLDER", nullptr, 0);
        existed = size != 0 || GetLastError() != ERROR_ENVVAR_NOT_FOUND;
        if (size) {
            previous.resize(size);
            DWORD count = GetEnvironmentVariableW(L"WEBVIEW2_USER_DATA_FOLDER", &previous[0], size);
            previous.resize(count);
        }
    }
    ~ProfileOverride() {
        if (changed) SetEnvironmentVariableW(L"WEBVIEW2_USER_DATA_FOLDER", existed ? previous.c_str() : nullptr);
    }
};
#endif
}

#define API(name) Java_dev_t1m3_qplayer_desktop_app_DesktopNativeBrowser_##name
extern "C" {
JNIEXPORT jlong JNICALL API(create)(JNIEnv *env, jclass, jboolean visible,
        jstring title, jstring dataDirectory, jobject bridge) {
    auto *b = new qplayer::Browser();
    b->visible = visible;
    std::string caption = qplayer::string(env, title);
#ifdef _WIN32
    USEROBJECTFLAGS flags{};
    DWORD size = 0;
    if (!GetUserObjectInformation(GetProcessWindowStation(), UOI_FLAGS, &flags, sizeof flags, &size)
            || !(flags.dwFlags & WSF_VISIBLE)) {
        delete b;
        qplayer::error(env, "no interactive Windows desktop is available");
        return 0;
    }
    auto *e = new embed_win::Engine();
    b->engine = e;
    e->standalone = true;
    e->standalone_visible = visible;
    e->standalone_data = embed_win::utf8_to_wide(qplayer::string(env, dataDirectory).c_str());
    qplayer::ProfileOverride profileOverride;
    if (!SetEnvironmentVariableW(L"WEBVIEW2_USER_DATA_FOLDER", e->standalone_data.c_str())) {
        delete e;
        delete b;
        qplayer::error(env, "cannot isolate the temporary WebView2 profile");
        return 0;
    }
    profileOverride.changed = true;
    env->GetJavaVM(&e->jvm);
    std::atomic<bool> ready{false}, ok{false};
    try {
        b->worker = std::thread([e, &ready, &ok] {
            e->thread_id = GetCurrentThreadId();
            embed_win::engine_thread(e, nullptr, 1100, 800, false, &ready, &ok);
        });
    } catch (const std::exception &error) {
        delete e;
        delete b;
        qplayer::error(env, error.what());
        return 0;
    }
    while (!ready.load()) Sleep(1);
    if (!ok.load()) {
        b->worker.join();
        qplayer::wait_for_browser(e);
        e->thread_id = 0;
        embed_win::destroy_engine(e);
        delete b;
        qplayer::error(env, "WebView2 initialization failed");
        return 0;
    }
    std::wstring wideCaption = embed_win::utf8_to_wide(caption.c_str());
    embed_win::dispatch_to_thread(e, [e, wideCaption] { SetWindowTextW(e->child, wideCaption.c_str()); });
#elif defined(WEBVIEW_GTK)
    char failure[512] = {};
    if (!webkit_loader_ensure(failure, sizeof failure)) {
        delete b;
        qplayer::error(env, failure);
        return 0;
    }
    XInitThreads();
    Display *display = XOpenDisplay(nullptr);
    if (!display) {
        delete b;
        qplayer::error(env, "no X11 display is available for WebKitGTK");
        return 0;
    }
    XCloseDisplay(display);
    GtkWidget *web = nullptr;
    try {
        embed::GtkPump::instance().run_sync([&] {
            WebKitWebContext *context = g_wk.fn_webkit_web_context_new_ephemeral();
            web = g_wk.fn_webkit_web_view_new_with_context(context);
            g_object_unref(context);
        });
    } catch (const std::exception &error) {
        delete b;
        qplayer::error(env, error.what());
        return 0;
    }
    b->engine = embed::gtk_off_create_engine(env, 1100, 800, 0, web, visible);
    if (!b->engine) { delete b; qplayer::error(env, "WebKitGTK initialization failed"); return 0; }
    embed::GtkPump::instance().run_sync([&] {
        gtk_window_set_title(GTK_WINDOW(b->engine->window), caption.c_str());
        if (visible) {
            gtk_window_set_position(GTK_WINDOW(b->engine->window), GTK_WIN_POS_CENTER);
            g_signal_connect(b->engine->window, "delete-event", G_CALLBACK(+[](GtkWidget *window, GdkEvent *, gpointer data) -> gboolean {
                static_cast<qplayer::Browser *>(data)->open.store(false);
                gtk_widget_hide(window);
                return TRUE; // Java owns teardown, never destroy a live engine here.
            }), b);
        }
    });
#else
    b->engine = embed::cocoa_create_engine(env, nullptr, 0, 0);
    embed::cocoa_run_on_main_async([b, caption] {
        using namespace embed;
        auto *e = b->engine;
        if (!e->webview) { b->open.store(false); return; }
        id window = msg(objc_cls("NSWindow"), sel("alloc"));
        window = msg<id, CGRect, unsigned long, unsigned long, BOOL>(window,
            sel("initWithContentRect:styleMask:backing:defer:"),
            CGRectMake(0, 0, 1100, 800), b->visible ? 15UL : 0UL, 2UL, NO);
        b->window = window;
        msg<void, BOOL>(window, sel("setReleasedWhenClosed:"), NO);
        if (b->visible) msg<void, CGSize>(window, sel("setContentMinSize:"), CGSizeMake(640, 560));
        msg<void, id>(window, sel("setTitle:"), ns_str(caption.c_str()));
        id content = msg(window, sel("contentView"));
        msg<void, id>(content, sel("addSubview:"), e->webview);
        msg<void, CGRect>(e->webview, sel("setFrame:"), CGRectMake(0, 0, 1100, 800));
        msg<void, unsigned long>(e->webview, sel("setAutoresizingMask:"), 18UL);
        if (b->visible) {
            msg<void>(window, sel("center"));
            msg<void, id>(window, sel("makeKeyAndOrderFront:"), (id)nullptr);
            msg<void, BOOL>(msg(objc_cls("NSApplication"), sel("sharedApplication")), sel("activateIgnoringOtherApps:"), YES);
        } else {
            // A real native window/view hierarchy is required by WKWebView,
            // but orderFront is never sent for a hidden script session.
            msg<void, BOOL>(window, sel("setCanHide:"), YES);
        }
    });
#endif
    jstring name = env->NewStringUTF("__qplayer");
#ifdef WEBVIEW_GTK
    Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1bind(env, nullptr, (jlong)b->engine, name, bridge, 0);
#else
    Java_ca_weblite_webview_WebViewNative_webview_1embed_1bind(env, nullptr, (jlong)b->engine, name, bridge, 0);
#endif
    env->DeleteLocalRef(name);
    return (jlong)b;
}
JNIEXPORT void JNICALL API(navigate)(JNIEnv *env, jclass, jlong peer, jstring url) {
    auto *b = (qplayer::Browser *)peer;
#ifdef WEBVIEW_GTK
    Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1navigate(env, nullptr, (jlong)b->engine, url);
#else
    Java_ca_weblite_webview_WebViewNative_webview_1embed_1navigate(env, nullptr, (jlong)b->engine, url);
#endif
}
JNIEXPORT void JNICALL API(evaluate)(JNIEnv *env, jclass, jlong peer, jstring js) {
    auto *b = (qplayer::Browser *)peer;
#ifdef WEBVIEW_GTK
    Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1eval(env, nullptr, (jlong)b->engine, js);
#else
    Java_ca_weblite_webview_WebViewNative_webview_1embed_1eval(env, nullptr, (jlong)b->engine, js);
#endif
}
JNIEXPORT void JNICALL API(cookies)(JNIEnv *env, jclass, jlong peer, jstring url, jobject callback) {
    auto *b = (qplayer::Browser *)peer;
#ifdef WEBVIEW_GTK
    Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1get_1cookies(env, nullptr, (jlong)b->engine, url, callback);
#else
    Java_ca_weblite_webview_WebViewNative_webview_1embed_1get_1cookies(env, nullptr, (jlong)b->engine, url, callback);
#endif
}
JNIEXPORT jboolean JNICALL API(isOpen)(JNIEnv *, jclass, jlong peer) {
    auto *b = (qplayer::Browser *)peer;
#ifdef _WIN32
    return !b->engine->standalone_closed.load();
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_run_on_main_async([b] {
        if (b->visible && b->window && !embed::msg<BOOL>(b->window, embed::sel("isVisible"))) b->open.store(false);
    });
#endif
    return b->open.load();
}
JNIEXPORT void JNICALL API(focus)(JNIEnv *, jclass, jlong peer) {
    auto *b = (qplayer::Browser *)peer;
#ifdef _WIN32
    embed_win::dispatch_to_thread(b->engine, [b] {
        ShowWindow(b->engine->child, SW_RESTORE);
        SetForegroundWindow(b->engine->child);
        if (b->engine->controller) b->engine->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
    });
#elif defined(WEBVIEW_GTK)
    embed::GtkPump::instance().run_async([b] { gtk_window_present(GTK_WINDOW(b->engine->window)); });
#else
    embed::cocoa_run_on_main_async([b] { embed::msg<void, id>(b->window, embed::sel("makeKeyAndOrderFront:"), (id)nullptr); });
#endif
}
JNIEXPORT void JNICALL API(destroy)(JNIEnv *, jclass, jlong peer) {
    auto *b = (qplayer::Browser *)peer;
#ifdef _WIN32
    PostThreadMessage(b->engine->thread_id, embed_win::WM_EMBED_QUIT, 0, 0);
    b->worker.join(); // All COM objects/windows are released on their apartment.
    qplayer::wait_for_browser(b->engine);
    b->engine->thread_id = 0;
    embed_win::destroy_engine(b->engine);
    delete b;
#elif defined(WEBVIEW_GTK)
    // Flush queued GTK operations before freeing the engine and its bindings.
    embed::GtkPump::instance().run_sync([b] { embed::gtk_off_destroy_engine(b->engine); });
    delete b;
#else
    embed::cocoa_run_on_main_async([b] {
        embed::cocoa_destroy_engine(b->engine);
        if (b->window) {
            embed::msg<void>(b->window, embed::sel("close"));
            embed::msg<void>(b->window, embed::sel("release"));
        }
        delete b;
    });
#endif
}
}
