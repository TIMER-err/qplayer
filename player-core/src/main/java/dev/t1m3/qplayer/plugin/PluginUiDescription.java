package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One dialog, as described by a plugin and rendered by QPlayer's own components.
 *
 * <p>A plugin contributes no QML. It returns this description from its
 * {@code ui.<contribution>} handler, and QPlayer draws it with md3.Core, so a
 * plugin dialog matches the app's theme automatically and no third-party
 * document, engine or realm ever enters the QML scene.
 *
 * <p>Everything here is validated before it reaches QML: node types, ids,
 * enums, counts and text lengths. Anything off-schema fails the whole
 * description rather than being silently dropped, so a plugin cannot probe for
 * which malformed shapes survive. Colors, images, raw markup and free-form
 * geometry are deliberately absent — a plugin describes a dialog, it does not
 * paint one, and it cannot imitate host chrome it was not given.
 */
public final class PluginUiDescription {
    /** Bounded so one description can never become an unrenderable wall. */
    public static final int MAX_NODES = 10;
    private static final int MAX_ROW_ITEMS = 3;
    private static final int MAX_TITLE = 60;
    private static final int MAX_SUBTITLE = 120;
    private static final int MAX_TEXT = 500;
    private static final int MAX_ERROR = 300;
    private static final int MAX_LABEL = 24;
    private static final int MAX_PLACEHOLDER = 60;
    private static final int MAX_VALUE = 2048;
    private static final int MIN_REFRESH_MS = 500;
    private static final int MAX_REFRESH_MS = 60_000;

    private static final String ID_PATTERN = "[a-z][a-z0-9._-]{0,63}";
    private static final String ICON_PATTERN = "[a-z][a-z0-9_]{0,63}";
    private static final Set<String> TEXT_STYLES =
            new LinkedHashSet<>(Arrays.asList("title", "body", "caption"));
    private static final Set<String> BUTTON_STYLES =
            new LinkedHashSet<>(Arrays.asList("filled", "outlined", "text"));

    private static final Gson GSON = new Gson();

    private PluginUiDescription() {}

    /**
     * Validates a plugin-returned description and re-emits it as canonical JSON
     * for QML. The output only ever contains keys this method wrote.
     *
     * @throws IllegalArgumentException when the description is off-schema
     */
    public static String normalize(Object returned) {
        if (!(returned instanceof Map)) {
            throw new IllegalArgumentException("plugin UI description is not an object");
        }
        Map<?, ?> source = (Map<?, ?>) returned;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", text(source.get("title"), "title", MAX_TITLE, false));
        out.put("subtitle", text(source.get("subtitle"), "subtitle", MAX_SUBTITLE, true));
        out.put("icon", icon(source.get("icon")));
        out.put("refreshMs", refreshMs(source.get("refreshMs")));

        Object body = source.get("body");
        if (!(body instanceof List)) {
            throw new IllegalArgumentException("plugin UI body is not a list");
        }
        List<?> nodes = (List<?>) body;
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("plugin UI body has too many nodes");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> inputIds = new LinkedHashSet<>();
        for (Object node : nodes) normalized.add(node(node, inputIds, true));
        out.put("body", normalized);
        return GSON.toJson(out);
    }

    private static Map<String, Object> node(Object raw, Set<String> inputIds, boolean rowsAllowed) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("plugin UI node is not an object");
        }
        Map<?, ?> node = (Map<?, ?>) raw;
        String type = text(node.get("type"), "node type", 16, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        switch (type) {
            case "text":
                out.put("text", text(node.get("text"), "text", MAX_TEXT, true));
                out.put("style", oneOf(node.get("style"), TEXT_STYLES, "body", "text style"));
                out.put("center", flag(node.get("center"), false));
                return out;
            case "error":
                out.put("text", text(node.get("text"), "error text", MAX_ERROR, true));
                return out;
            case "spacer":
                out.put("height", bounded(node.get("height"), 0, 48, 8, "spacer height"));
                return out;
            case "input":
                out.put("id", id(node.get("id"), "input id"));
                if (!inputIds.add((String) out.get("id"))) {
                    throw new IllegalArgumentException("duplicate plugin UI input id");
                }
                out.put("placeholder", text(node.get("placeholder"), "placeholder", MAX_PLACEHOLDER, true));
                out.put("value", text(node.get("value"), "input value", MAX_VALUE, true));
                out.put("secret", flag(node.get("secret"), false));
                return out;
            case "button":
                out.put("id", id(node.get("id"), "button id"));
                out.put("label", text(node.get("label"), "button label", MAX_LABEL, false));
                out.put("style", oneOf(node.get("style"), BUTTON_STYLES, "filled", "button style"));
                out.put("enabled", flag(node.get("enabled"), true));
                out.put("destructive", flag(node.get("destructive"), false));
                return out;
            case "row":
                if (!rowsAllowed) throw new IllegalArgumentException("plugin UI rows cannot nest");
                Object items = node.get("items");
                if (!(items instanceof List)) {
                    throw new IllegalArgumentException("plugin UI row has no item list");
                }
                List<?> list = (List<?>) items;
                if (list.isEmpty() || list.size() > MAX_ROW_ITEMS) {
                    throw new IllegalArgumentException("plugin UI row item count is out of range");
                }
                List<Map<String, Object>> rowItems = new ArrayList<>();
                for (Object item : list) {
                    Map<String, Object> child = node(item, inputIds, false);
                    if (!"button".equals(child.get("type"))) {
                        throw new IllegalArgumentException("plugin UI rows hold buttons only");
                    }
                    rowItems.add(child);
                }
                out.put("items", rowItems);
                return out;
            default:
                throw new IllegalArgumentException("unknown plugin UI node type: " + type);
        }
    }

    private static String text(Object value, String what, int limit, boolean optional) {
        if (value == null) {
            if (optional) return "";
            throw new IllegalArgumentException("plugin UI " + what + " is missing");
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("plugin UI " + what + " is not a string");
        }
        String string = (String) value;
        if (string.length() > limit) {
            throw new IllegalArgumentException("plugin UI " + what + " is too long");
        }
        if (!optional && string.isEmpty()) {
            throw new IllegalArgumentException("plugin UI " + what + " is empty");
        }
        // Control characters would let a description forge line structure the host
        // never laid out; the renderer wraps text itself.
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("plugin UI " + what + " has control characters");
            }
        }
        return string;
    }

    private static String id(Object value, String what) {
        String string = text(value, what, 64, false);
        if (!string.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("invalid plugin UI " + what);
        }
        return string;
    }

    private static String icon(Object value) {
        if (value == null) return "extension";
        String string = text(value, "icon", 64, true);
        if (string.isEmpty()) return "extension";
        if (!string.matches(ICON_PATTERN)) {
            throw new IllegalArgumentException("invalid plugin UI icon");
        }
        return string;
    }

    private static String oneOf(Object value, Set<String> allowed, String fallback, String what) {
        if (value == null) return fallback;
        String string = text(value, what, 16, true);
        if (string.isEmpty()) return fallback;
        if (!allowed.contains(string)) {
            throw new IllegalArgumentException("invalid plugin UI " + what + ": " + string);
        }
        return string;
    }

    private static boolean flag(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("plugin UI flag is not a boolean");
        }
        return (Boolean) value;
    }

    private static int bounded(Object value, int min, int max, int fallback, String what) {
        if (value == null) return fallback;
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("plugin UI " + what + " is not a number");
        }
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || number < min || number > max) {
            throw new IllegalArgumentException("plugin UI " + what + " is out of range");
        }
        return (int) number;
    }

    /** 0 disables polling; anything else must be a sane interval. */
    private static int refreshMs(Object value) {
        if (value == null) return 0;
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("plugin UI refreshMs is not a number");
        }
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || number == 0d) return 0;
        if (number < MIN_REFRESH_MS || number > MAX_REFRESH_MS) {
            throw new IllegalArgumentException("plugin UI refreshMs is out of range");
        }
        return (int) number;
    }
}
