// webkit_loader.h
//
// Linux-only runtime resolver for WebKitGTK / JavaScriptCore.
//
// libwebview.so is built WITHOUT linking libwebkit2gtk / libjavascriptcoregtk
// (see build-linux.sh and the Linux CI jobs). Instead every WebKit/JSC symbol
// the wrapper calls is resolved at runtime into a typed function pointer here,
// and the call sites are redirected to those pointers by webkit_shim.h. This
// yields a single portable .so that runs on both WebKitGTK 4.1 (Ubuntu 22.04+)
// and 4.0 (Ubuntu 20.04) hosts.
//
// This header is included by webkit_loader.cpp (the resolver) and, together
// with webkit_shim.h, by the GTK call-site translation units (webview.h,
// webview_embed.cpp). It is inert on non-Linux/non-GTK builds.
#ifndef WEBLITE_WEBKIT_LOADER_H
#define WEBLITE_WEBKIT_LOADER_H

#if defined(WEBVIEW_GTK) || defined(__linux__)

#include <cstddef>
#include <JavaScriptCore/JavaScript.h>
#include <webkit2/webkit2.h>

// ---------------------------------------------------------------------------
// Symbol inventory (X-macro lists). Keep in sync with the redirects in
// webkit_shim.h. WK_WEBKIT_* symbols resolve from the libwebkit2gtk handle;
// WK_JSC_* symbols resolve from the libjavascriptcoregtk handle.
// ---------------------------------------------------------------------------
#define WK_WEBKIT_SYMS(X)                                        \
  X(webkit_download_cancel)                                      \
  X(webkit_download_get_received_data_length)                    \
  X(webkit_download_get_request)                                 \
  X(webkit_download_get_response)                                \
  X(webkit_download_get_web_view)                                \
  X(webkit_download_set_allow_overwrite)                         \
  X(webkit_download_set_destination)                             \
  X(webkit_file_chooser_request_cancel)                          \
  X(webkit_file_chooser_request_get_mime_types)                  \
  X(webkit_file_chooser_request_get_select_multiple)             \
  X(webkit_file_chooser_request_select_files)                    \
  X(webkit_cookie_manager_get_cookies)                           \
  X(webkit_cookie_manager_get_cookies_finish)                    \
  X(webkit_get_major_version)                                    \
  X(webkit_get_minor_version)                                    \
  X(webkit_navigation_action_get_navigation_type)                \
  X(webkit_navigation_action_get_request)                        \
  X(webkit_navigation_action_is_redirect)                        \
  X(webkit_navigation_action_is_user_gesture)                    \
  X(webkit_navigation_policy_decision_get_navigation_action)     \
  X(webkit_policy_decision_ignore)                               \
  X(webkit_print_operation_new)                                  \
  X(webkit_print_operation_print)                                \
  X(webkit_print_operation_set_page_setup)                       \
  X(webkit_print_operation_set_print_settings)                   \
  X(webkit_script_dialog_confirm_set_confirmed)                  \
  X(webkit_script_dialog_get_dialog_type)                        \
  X(webkit_script_dialog_get_message)                            \
  X(webkit_script_dialog_prompt_get_default_text)                \
  X(webkit_script_dialog_prompt_set_text)                        \
  X(webkit_security_manager_register_uri_scheme_as_cors_enabled) \
  X(webkit_security_manager_register_uri_scheme_as_secure)       \
  X(webkit_settings_get_enable_developer_extras)                 \
  X(webkit_settings_set_enable_developer_extras)                 \
  X(webkit_settings_set_enable_write_console_messages_to_stdout) \
  X(webkit_settings_set_hardware_acceleration_policy)            \
  X(webkit_uri_request_get_uri)                                  \
  X(webkit_uri_response_get_content_length)                      \
  X(webkit_uri_response_get_mime_type)                           \
  X(webkit_uri_response_get_uri)                                 \
  X(webkit_uri_scheme_request_finish)                            \
  X(webkit_uri_scheme_request_finish_error)                      \
  X(webkit_uri_scheme_request_get_uri)                           \
  X(webkit_user_content_manager_add_script)                      \
  X(webkit_user_content_manager_register_script_message_handler) \
  X(webkit_user_script_new)                                      \
  X(webkit_web_inspector_show)                                   \
  X(webkit_web_context_get_cookie_manager)                       \
  X(webkit_web_context_get_default)                              \
  X(webkit_web_context_new_ephemeral)                           \
  X(webkit_web_context_get_security_manager)                     \
  X(webkit_web_context_register_uri_scheme)                      \
  X(webkit_web_view_execute_editing_command)                     \
  X(webkit_web_view_get_context)                                 \
  X(webkit_web_view_get_inspector)                               \
  X(webkit_web_view_get_settings)                                \
  X(webkit_web_view_get_uri)                                     \
  X(webkit_web_view_get_user_content_manager)                    \
  X(webkit_web_view_get_window_properties)                       \
  X(webkit_web_view_load_uri)                                    \
  X(webkit_web_view_new)                                         \
  X(webkit_web_view_new_with_context)                            \
  X(webkit_web_view_new_with_related_view)                       \
  X(webkit_web_view_run_javascript)                              \
  X(webkit_web_view_set_background_color)                        \
  X(webkit_web_view_set_input_method_context)                    \
  X(webkit_window_properties_get_geometry)

#define WK_SOUP_SYMS(X)                                          \
  X(soup_cookie_free)                                            \
  X(soup_cookie_get_name)                                        \
  X(soup_cookie_get_value)

// JS-result readers. Mirror webview.h's version gate EXACTLY: the pre-2.22
// JSValueRef path (webkit_javascript_result_get_global_context / _get_value)
// was removed from modern headers, so referencing it unconditionally would
// fail to compile there. All target distros (Ubuntu 20.04+) ship >= 2.22.
#if WEBKIT_MAJOR_VERSION >= 2 && WEBKIT_MINOR_VERSION >= 22
#define WK_WEBKIT_JS_SYMS(X)                                                   \
  X(webkit_javascript_result_get_js_value)
#define WK_JSC_JS_SYMS(X)                                                      \
  X(jsc_value_to_string)
#else
#define WK_WEBKIT_JS_SYMS(X)                                                   \
  X(webkit_javascript_result_get_global_context)                              \
  X(webkit_javascript_result_get_value)
#define WK_JSC_JS_SYMS(X)                                                      \
  X(JSValueToStringCopy)                                                       \
  X(JSStringRelease)                                                           \
  X(JSStringGetMaximumUTF8CStringSize)                                         \
  X(JSStringGetUTF8CString)
#endif

// Optional symbols (Canvas 31 D5): resolved when the runtime has them, null
// otherwise -- they never fail the load.  Each group is also gated on the
// headers, because the members are declared with decltype(&sym).  Every call
// site sits inside the same #if AND behind WK_HAS(sym).  The libsoup symbols
// resolve through the WebKit handle, i.e. from the libsoup generation WebKit
// itself loaded (2 for the 4.0 API, 3 for 4.1); their signatures match in both.
#if WEBKIT_CHECK_VERSION(2, 12, 0)
#define WK_OPT_2_12(X) X(webkit_uri_scheme_request_get_http_method)
#else
#define WK_OPT_2_12(X)
#endif
#if WEBKIT_CHECK_VERSION(2, 36, 0)
#define WK_OPT_2_36(X)                                                         \
  X(webkit_uri_scheme_request_get_http_headers)                               \
  X(webkit_uri_scheme_request_finish_with_response)                           \
  X(webkit_uri_scheme_response_new)                                           \
  X(webkit_uri_scheme_response_set_status)                                    \
  X(webkit_uri_scheme_response_set_content_type)                              \
  X(webkit_uri_scheme_response_set_http_headers)                              \
  X(soup_message_headers_new)                                                 \
  X(soup_message_headers_append)                                              \
  X(soup_message_headers_foreach)
#else
#define WK_OPT_2_36(X)
#endif
#if WEBKIT_CHECK_VERSION(2, 40, 0)
#define WK_OPT_2_40(X) X(webkit_uri_scheme_request_get_http_body)
#else
#define WK_OPT_2_40(X)
#endif
#define WK_WEBKIT_OPT_SYMS(X) WK_OPT_2_12(X) WK_OPT_2_36(X) WK_OPT_2_40(X)

// The pointer table. Member types are taken from the real declarations via
// decltype — unevaluated, so this creates no link-time dependency on the
// WebKit/JSC symbols.
struct WkFns {
// Members are prefixed `fn_` so the member name never collides with the real
// symbol name used inside decltype(&sym) — an identical name would be
// ill-formed ([class.mem] "changes meaning"). The shim maps `sym` -> `fn_sym`.
#define WK_DECL_MEMBER(sym) decltype(&sym) fn_##sym;
  WK_WEBKIT_SYMS(WK_DECL_MEMBER)
  WK_SOUP_SYMS(WK_DECL_MEMBER)
  WK_WEBKIT_JS_SYMS(WK_DECL_MEMBER)
  WK_JSC_JS_SYMS(WK_DECL_MEMBER)
  WK_WEBKIT_OPT_SYMS(WK_DECL_MEMBER)
#undef WK_DECL_MEMBER
};

// The single resolved table (defined in webkit_loader.cpp). Call sites reach
// it through the redirects in webkit_shim.h.
extern WkFns g_wk;

// Whether an optional symbol (WK_WEBKIT_OPT_SYMS) resolved at run time.  The
// ## paste keeps webkit_shim.h's redirect of `sym` from expanding here.
#define WK_HAS(sym) (::g_wk.fn_##sym != nullptr)

// Resolve the WebKit/JSC runtime once (idempotent, thread-safe). Returns true
// on success. On failure, if errbuf/errlen are non-null, copies a diagnostic
// message naming the candidate SONAMEs. Invoked from JNI_OnLoad; callers do
// not normally need it directly.
bool webkit_loader_ensure(char *errbuf, size_t errlen);

#endif  // WEBVIEW_GTK || __linux__
#endif  // WEBLITE_WEBKIT_LOADER_H
