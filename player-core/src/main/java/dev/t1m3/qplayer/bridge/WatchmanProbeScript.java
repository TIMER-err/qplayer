package dev.t1m3.qplayer.bridge;

/** Builds the bounded browser-side adapter used by the platform system WebView.
 * The loaded SDK can inspect the real browser environment, but its only Java-facing
 * capability is returning one token/error payload through qplayerWatchmanDone. */
public final class WatchmanProbeScript {
    private WatchmanProbeScript() {}

    public static String javascript(String originUrl, String scriptUrl, String productNumber,
                                    String businessId) {
        return "(function(){'use strict';"
                + "if(window.__qplayerWatchmanStarted)return;"
                + "window.__qplayerWatchmanStarted=true;"
                + "var finished=false;"
                + "try{Object.defineProperty(window.document,'referrer',{configurable:true,"
                + "get:function(){return " + quote(originUrl) + ";}});}"
                + "catch(ignored){}"
                + "function report(token,error){"
                + "if(finished)return;finished=true;"
                + "window.qplayerWatchmanDone(JSON.stringify({token:token||'',error:error||''}));}"
                + "function message(error,fallback){"
                + "try{return error&&error.message?String(error.message):fallback;}"
                + "catch(ignored){return fallback;}}"
                + "function acquire(instance){"
                + "function getToken(){try{instance.getToken(" + quote(businessId)
                + ",function(token){if(token)report(String(token),'');"
                + "else report('','watchman returned an empty token');});}"
                + "catch(error){report('',message(error,'watchman token request failed'));}}"
                + "try{var raw=instance.getInstance&&instance.getInstance();"
                + "if(raw&&typeof raw.I==='function'){var resumed=false;"
                + "function resume(){if(resumed)return;resumed=true;clearTimeout(timer);getToken();}"
                + "var timer=setTimeout(resume,15000);raw.I(resume);}"
                + "else getToken();}catch(error){getToken();}}"
                + "function initialize(){try{window.initWatchman({auto:true,productNumber:"
                + quote(productNumber)
                + ",onload:function(instance){acquire(instance);},"
                + "onerror:function(error){report('',message(error,'watchman initialization failed'));}});}"
                + "catch(error){report('',message(error,'watchman initialization failed'));}}"
                + "var sdk=document.createElement('script');sdk.async=true;sdk.src=" + quote(scriptUrl) + ";"
                + "sdk.onload=initialize;"
                + "sdk.onerror=function(){report('','watchman SDK failed to load');};"
                + "(document.head||document.documentElement).appendChild(sdk);"
                + "})();";
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': escaped.append("\\\\"); break;
                case '\'': escaped.append("\\'"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\u2028': escaped.append("\\u2028"); break;
                case '\u2029': escaped.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        escaped.append("\\u00")
                                .append(Character.forDigit((c >>> 4) & 0xF, 16))
                                .append(Character.forDigit(c & 0xF, 16));
                    }
                    else escaped.append(c);
            }
        }
        return escaped.append('\'').toString();
    }
}
