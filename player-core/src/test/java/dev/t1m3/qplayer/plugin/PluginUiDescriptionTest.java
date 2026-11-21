package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginUiDescriptionTest {

    private static Map<String, Object> node(Object... pairs) {
        Map<String, Object> node = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) node.put((String) pairs[i], pairs[i + 1]);
        return node;
    }

    private static Map<String, Object> dialog(Object... body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "一起听");
        out.put("body", new ArrayList<>(Arrays.asList(body)));
        return out;
    }

    @Test public void normalizesAFullDialog() {
        String json = PluginUiDescription.normalize(dialog(
                node("type", "text", "style", "title", "text", "room", "center", true),
                node("type", "input", "id", "invitation", "placeholder", "link"),
                node("type", "row", "items", Arrays.asList(
                        node("type", "button", "id", "create", "label", "create"),
                        node("type", "button", "id", "join", "label", "join",
                                "style", "outlined", "enabled", false)))));

        assertTrue(json.contains("\"style\":\"title\""));
        assertTrue(json.contains("\"id\":\"invitation\""));
        assertTrue(json.contains("\"style\":\"outlined\""));
        assertTrue(json.contains("\"enabled\":false"));
        // Defaults are materialized so QML never has to guess.
        assertTrue(json.contains("\"refreshMs\":0"));
        assertTrue(json.contains("\"icon\":\"extension\""));
    }

    @Test public void rejectsUnknownNodeType() {
        rejects(dialog(node("type", "webview", "url", "https://example.com")));
    }

    @Test public void rejectsUnknownEnumValue() {
        rejects(dialog(node("type", "button", "id", "go", "label", "go", "style", "elevated")));
    }

    @Test public void rejectsControlCharactersInText() {
        rejects(dialog(node("type", "text", "text", "line\nbreak")));
    }

    @Test public void rejectsOverlongBody() {
        List<Object> body = new ArrayList<>();
        for (int i = 0; i <= PluginUiDescription.MAX_NODES; i++) {
            body.add(node("type", "text", "text", "x"));
        }
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("title", "t");
        description.put("body", body);
        rejects(description);
    }

    @Test public void rejectsNestedRows() {
        rejects(dialog(node("type", "row", "items", Arrays.asList(
                node("type", "row", "items", Arrays.asList(
                        node("type", "button", "id", "a", "label", "A")))))));
    }

    @Test public void rejectsDuplicateInputIds() {
        rejects(dialog(
                node("type", "input", "id", "same"),
                node("type", "input", "id", "same")));
    }

    @Test public void rejectsOutOfRangeRefreshInterval() {
        Map<String, Object> description = dialog(node("type", "text", "text", "x"));
        description.put("refreshMs", 10);
        rejects(description);
    }

    private static void rejects(Object description) {
        try {
            PluginUiDescription.normalize(description);
            fail("expected an off-schema description to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}
