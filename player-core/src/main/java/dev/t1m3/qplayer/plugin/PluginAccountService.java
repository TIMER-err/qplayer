package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.AccountProfile;
import dev.t1m3.qplayer.media.LoginChallenge;
import dev.t1m3.qplayer.media.LoginMethod;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Standard account/login ABI. Credentials remain opaque strings delivered only to
 * the selected plugin, which stores them through its namespaced credential vault. */
public final class PluginAccountService {
    private static final int MAX_LABEL_CHARS = 4096;
    private static final int MAX_INSTRUCTIONS_CHARS = 64 * 1024;
    private static final int MAX_QR_CONTENT_CHARS = 64 * 1024;
    private final PluginManager manager;
    private final CorePluginHostApi hostApi;

    public PluginAccountService(PluginManager manager, CorePluginHostApi hostApi) {
        this.manager = manager;
        this.hostApi = hostApi;
    }

    public CompletableFuture<List<LoginMethod>> methods(String provider) {
        return invoke(provider, "methods", Collections.<String, Object>emptyMap())
                .thenApply(raw -> parseMethods(provider, raw));
    }

    public CompletableFuture<LoginChallenge> begin(String provider, String methodId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("methodId", methodId);
        return invoke(provider, "begin", args).thenApply(raw -> parseChallenge(provider, raw));
    }

    public CompletableFuture<LoginChallenge> poll(String provider, String challengeId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("challengeId", challengeId);
        return invoke(provider, "poll", args).thenApply(raw -> parseChallenge(provider, raw));
    }

    public CompletableFuture<LoginChallenge> submit(String provider, String methodId,
                                                     String credential) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("methodId", methodId);
        args.put("credential", credential != null ? credential : "");
        return invoke(provider, "submit", args).thenApply(raw -> parseChallenge(provider, raw));
    }

    public CompletableFuture<AccountProfile> account(String provider) {
        return manager.invoke(provider, ProviderCapability.ACCOUNT.wireName(),
                Collections.<String, Object>emptyMap()).thenApply(raw -> parseAccount(provider, raw));
    }

    public CompletableFuture<Boolean> logout(String provider) {
        return invoke(provider, "logout", Collections.<String, Object>emptyMap())
                .thenApply(value -> !Boolean.FALSE.equals(value));
    }

    private CompletableFuture<Object> invoke(String provider, String operation,
                                             Map<String, Object> values) {
        Map<String, Object> args = new LinkedHashMap<>(values);
        args.put("operation", operation);
        return manager.invoke(provider, ProviderCapability.LOGIN.wireName(), args);
    }

    private List<LoginMethod> parseMethods(String provider, Object raw) {
        List<Object> values = list(raw, "login methods");
        if (values.size() > 8) throw new PluginExecutionException("too many login methods");
        List<LoginMethod> methods = new ArrayList<>();
        for (Object item : values) {
            Map<String, Object> value = map(item, "login method");
            LoginMethod method = new LoginMethod();
            method.id = identifier(value.get("id"), "login method id");
            method.type = required(value.get("type"), "login method type").toLowerCase();
            if (!("qr".equals(method.type) || "web".equals(method.type)
                    || "credential".equals(method.type))) {
                throw new PluginExecutionException("unsupported login method type " + method.type);
            }
            method.label = bounded(value.get("label"), MAX_LABEL_CHARS,
                    "login method label", true);
            method.instructions = bounded(value.get("instructions"), MAX_INSTRUCTIONS_CHARS,
                    "login method instructions", false);
            method.webUrl = optional(value.get("webUrl"));
            method.cookieUrl = optional(value.get("cookieUrl"));
            if ((!method.webUrl.isEmpty() && !hostApi.allowsReturnedUrl(provider, method.webUrl))
                    || (!method.cookieUrl.isEmpty()
                    && !hostApi.allowsReturnedUrl(provider, method.cookieUrl))) {
                throw new PluginExecutionException("login URL is outside the plugin network grant");
            }
            method.credentialCookieName = optional(value.get("credentialCookieName"));
            if ("web".equals(method.type)) {
                if (!hostApi.hasPermission(provider, PluginPermission.WEB_AUTH)) {
                    throw new PluginExecutionException(
                            "web login was returned without the webAuth permission");
                }
                if (method.webUrl.isEmpty() || method.cookieUrl.isEmpty()) {
                    throw new PluginExecutionException(
                            "web login requires webUrl and cookieUrl");
                }
                if (!method.webUrl.regionMatches(true, 0, "https://", 0, 8)
                        || !method.cookieUrl.regionMatches(true, 0, "https://", 0, 8)) {
                    throw new PluginExecutionException("web login URLs must use HTTPS");
                }
                if (!method.credentialCookieName.matches("[A-Za-z0-9_.-]{1,64}")) {
                    throw new PluginExecutionException(
                            "web login requires a valid credentialCookieName");
                }
            }
            String credentialLabel = optional(value.get("credentialLabel"));
            if (!credentialLabel.isEmpty()) method.credentialLabel = credentialLabel;
            methods.add(method);
        }
        return Collections.unmodifiableList(methods);
    }

    private LoginChallenge parseChallenge(String provider, Object raw) {
        Map<String, Object> value = map(raw, "login challenge");
        LoginChallenge challenge = new LoginChallenge();
        challenge.id = optional(value.get("id"));
        challenge.methodId = optional(value.get("methodId"));
        challenge.status = required(value.get("status"), "login status").toLowerCase();
        if (!("waiting".equals(challenge.status) || "scanned".equals(challenge.status)
                || "success".equals(challenge.status) || "expired".equals(challenge.status)
                || "failed".equals(challenge.status))) {
            throw new PluginExecutionException("invalid login status " + challenge.status);
        }
        challenge.message = bounded(value.get("message"), MAX_INSTRUCTIONS_CHARS,
                "login message", false);
        challenge.qrContent = bounded(value.get("qrContent"), MAX_QR_CONTENT_CHARS,
                "login QR content", false);
        challenge.expiresAtMs = boundedLong(value.get("expiresAtMs"), 0L,
                Long.MAX_VALUE, "login expiry");
        if (value.get("account") instanceof Map) {
            challenge.account = parseAccount(provider, value.get("account"));
        }
        return challenge;
    }

    private AccountProfile parseAccount(String provider, Object raw) {
        Map<String, Object> value = map(raw, "account");
        AccountProfile profile = new AccountProfile();
        profile.loggedIn = Boolean.TRUE.equals(value.get("loggedIn"));
        String nativeId = nativeIdentifier(value.get("id"));
        profile.id = nativeId.isEmpty() ? ""
                : MediaId.of(provider, MediaKind.USER, nativeId).toString();
        profile.displayName = bounded(value.get("displayName"), MAX_LABEL_CHARS,
                "account display name", false);
        String avatar = optional(value.get("avatarUrl"));
        profile.avatarUrl = avatar.isEmpty() || hostApi.allowsReturnedUrl(provider, avatar)
                ? avatar : "";
        profile.membershipTier = integer(value.get("membershipTier"), 0, 1000,
                "account membership tier");
        profile.level = integer(value.get("level"), 0, 100000, "account level");
        profile.signature = bounded(value.get("signature"), MAX_INSTRUCTIONS_CHARS,
                "account signature", false);
        return profile;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw, String label) {
        if (!(raw instanceof Map)) throw new PluginExecutionException(label + " must be an object");
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object raw, String label) {
        if (!(raw instanceof List)) throw new PluginExecutionException(label + " must be an array");
        return (List<Object>) raw;
    }

    private static String required(Object raw, String label) {
        String value = optional(raw);
        if (value.isEmpty()) throw new PluginExecutionException(label + " is empty");
        return value;
    }

    private static String identifier(Object raw, String label) {
        String value = required(raw, label);
        if (!value.matches("[a-z][a-z0-9._-]{0,63}")) {
            throw new PluginExecutionException("invalid " + label);
        }
        return value;
    }

    private static String optional(Object raw) { return raw != null ? String.valueOf(raw) : ""; }

    private static String nativeIdentifier(Object raw) {
        if (raw instanceof Number) {
            Number value = (Number) raw;
            double number = value.doubleValue();
            return number == Math.rint(number) ? Long.toString(value.longValue()) : value.toString();
        }
        return optional(raw);
    }

    private static String bounded(Object raw, int maximum, String label, boolean required) {
        String value = optional(raw);
        if (required && value.isEmpty()) throw new PluginExecutionException(label + " is empty");
        if (value.length() > maximum) throw new PluginExecutionException(label + " is too long");
        return value;
    }

    private static long boundedLong(Object raw, long minimum, long maximum, String label) {
        if (raw == null) return minimum;
        if (!(raw instanceof Number)) throw new PluginExecutionException(label + " must be a number");
        long value = ((Number) raw).longValue();
        if (value < minimum || value > maximum) {
            throw new PluginExecutionException(label + " is out of range");
        }
        return value;
    }

    private static int integer(Object raw, int minimum, int maximum, String label) {
        if (raw == null) return 0;
        if (!(raw instanceof Number)) throw new PluginExecutionException(label + " must be a number");
        long value = ((Number) raw).longValue();
        if (value < minimum || value > maximum) {
            throw new PluginExecutionException(label + " is out of range");
        }
        return (int) value;
    }
}
