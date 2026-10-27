// webkit_loader.cpp
//
// Linux-only. Resolves WebKitGTK / JavaScriptCore at runtime (dlopen + dlsym),
// preferring the 4.1 SONAMEs and falling back to 4.0, so a single libwebview.so
// runs on both. See webkit_loader.h for the design and the symbol inventory.
// A missing mandatory symbol fails the load; optional symbols never do.
//
// This file deliberately does NOT include webkit_shim.h: it assigns the real
// g_wk members and resolves symbols by string name.
#include "webkit_loader.h"

#if defined(WEBVIEW_GTK) || defined(__linux__)

#include <dlfcn.h>
#include <jni.h>
#include <cstdio>
#include <cstring>

// The single runtime-resolved pointer table.
WkFns g_wk;

namespace {

// Candidate SONAMEs. 4.1 is preferred; 4.0 is the fallback.
const char *const kWebKit41 = "libwebkit2gtk-4.1.so.0";
const char *const kWebKit40 = "libwebkit2gtk-4.0.so.37";
const char *const kJsc41 = "libjavascriptcoregtk-4.1.so.0";
const char *const kJsc40 = "libjavascriptcoregtk-4.0.so.18";

void *g_webkit_handle = nullptr;
void *g_jsc_handle = nullptr;
char g_err[512] = {0};

// Resolve everything exactly once. Returns true on success; on failure writes
// a diagnostic to g_err and returns false. Called only through the C++11
// thread-safe function-local static in webkit_loader_ensure().
bool do_init() {
  // 1. WebKit: prefer 4.1, fall back to 4.0.
  bool is_41 = true;
  g_webkit_handle = dlopen(kWebKit41, RTLD_NOW | RTLD_GLOBAL);
  if (!g_webkit_handle) {
    g_webkit_handle = dlopen(kWebKit40, RTLD_NOW | RTLD_GLOBAL);
    is_41 = false;
  }
  if (!g_webkit_handle) {
    std::snprintf(g_err, sizeof(g_err),
                  "swingwebview: unable to load the WebKitGTK runtime — tried "
                  "'%s' then '%s' (%s). Install libwebkit2gtk-4.1-0 (Ubuntu "
                  "22.04+) or libwebkit2gtk-4.0-37 (Ubuntu 20.04).",
                  kWebKit41, kWebKit40, dlerror());
    return false;
  }

  // 2. JavaScriptCore: match the WebKit generation so only one libsoup loads.
  const char *jsc_primary = is_41 ? kJsc41 : kJsc40;
  const char *jsc_secondary = is_41 ? kJsc40 : kJsc41;
  g_jsc_handle = dlopen(jsc_primary, RTLD_NOW | RTLD_GLOBAL);
  if (!g_jsc_handle) {
    g_jsc_handle = dlopen(jsc_secondary, RTLD_NOW | RTLD_GLOBAL);
  }
  if (!g_jsc_handle) {
    std::snprintf(g_err, sizeof(g_err),
                  "swingwebview: unable to load the JavaScriptCore runtime — "
                  "tried '%s' then '%s' (%s).",
                  kJsc41, kJsc40, dlerror());
    return false;
  }

  // 3. Resolve the symbol tables. First missing symbol wins the error.
  const char *missing = nullptr;
#define WK_RESOLVE(handle, sym)                                                \
  do {                                                                         \
    g_wk.fn_##sym =                                                            \
        reinterpret_cast<decltype(g_wk.fn_##sym)>(dlsym((handle), #sym));      \
    if (!g_wk.fn_##sym && !missing) missing = #sym;                            \
  } while (0);
#define WK_RESOLVE_WEBKIT(sym) WK_RESOLVE(g_webkit_handle, sym)
#define WK_RESOLVE_JSC(sym) WK_RESOLVE(g_jsc_handle, sym)
  WK_WEBKIT_SYMS(WK_RESOLVE_WEBKIT)
  WK_SOUP_SYMS(WK_RESOLVE_WEBKIT)
  WK_WEBKIT_JS_SYMS(WK_RESOLVE_WEBKIT)
  WK_JSC_JS_SYMS(WK_RESOLVE_JSC)
#undef WK_RESOLVE_JSC
#undef WK_RESOLVE_WEBKIT
#undef WK_RESOLVE

  // Optional symbols (Canvas 31 D5): same lookup, but an absent one is simply
  // left null and never fails the load.  Searching the WebKit handle also
  // finds the libsoup WebKit depends on.
#define WK_RESOLVE_OPTIONAL_WEBKIT(sym)                                        \
  g_wk.fn_##sym =                                                              \
      reinterpret_cast<decltype(g_wk.fn_##sym)>(dlsym(g_webkit_handle, #sym));
  WK_WEBKIT_OPT_SYMS(WK_RESOLVE_OPTIONAL_WEBKIT)
#undef WK_RESOLVE_OPTIONAL_WEBKIT

  if (missing) {
    std::snprintf(g_err, sizeof(g_err),
                  "swingwebview: the installed WebKitGTK runtime (%s) is "
                  "missing required symbol '%s' — it is too old or incomplete.",
                  is_41 ? kWebKit41 : kWebKit40, missing);
    return false;
  }
  return true;
}

}  // namespace

bool webkit_loader_ensure(char *errbuf, size_t errlen) {
  // C++11 guarantees thread-safe, once-only initialization of this static.
  static const bool ok = do_init();
  if (!ok && errbuf && errlen) {
    std::snprintf(errbuf, errlen, "%s", g_err);
  }
  return ok;
}

// Resolve at library load time. Returning JNI_ERR makes System.load throw an
// UnsatisfiedLinkError carrying our diagnostic — the same load-time failure
// point as the old hard DT_NEEDED, but now naming both candidate SONAMEs.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
  (void)vm;
  char err[512];
  if (!webkit_loader_ensure(err, sizeof(err))) {
    std::fprintf(stderr, "%s\n", err);
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

#endif  // WEBVIEW_GTK || __linux__
