package org.quarkpress.devui.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.devui.tests.DevUIBuildTimeDataTest;
import io.quarkus.devui.tests.DevUIJsonRPCTest;
import io.quarkus.value.registry.ValueRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the extension's Dev UI from a dev-mode test: its JSON-RPC methods and its build-time data. Each test class
 * holds one and calls {@link #connect} before each test.
 */
public final class DevUiClient {

    static final String NAMESPACE = "quarkpress-dev-ui";

    private final DevUIJsonRPCTest jsonRpc = new DevUIJsonRPCTest(NAMESPACE);
    private final DevUIBuildTimeDataTest buildTimeData = new DevUIBuildTimeDataTest(NAMESPACE) {};
    private final DevUIBuildTimeDataTest devUiData = new DevUIBuildTimeDataTest("devui") {};

    void connect(ValueRegistry registry) {
        jsonRpc.beforeEach(registry);
        buildTimeData.beforeEach(registry);
        devUiData.beforeEach(registry);
    }

    JsonNode call(String method) throws Exception {
        return jsonRpc.executeJsonRPCMethod(method);
    }

    JsonNode call(String method, Map<String, Object> params) throws Exception {
        return jsonRpc.executeJsonRPCMethod(method, params);
    }

    JsonNode data(String key) throws Exception {
        return buildTimeData.getBuildTimeData(key);
    }

    /** Dev UI's own description of the installed extensions, with their cards and pages. */
    JsonNode extensions() throws Exception {
        return devUiData.getBuildTimeData("extensions");
    }

    /** The page whose component link ends in {@code component}, searched anywhere in {@code node}. */
    static JsonNode page(JsonNode node, String component) {
        JsonNode page = findPage(node, component);
        if (page == null) {
            throw new AssertionError("no page of component " + component + " in " + node);
        }
        return page;
    }

    private static JsonNode findPage(JsonNode node, String component) {
        JsonNode link = node.get("componentLink");
        if (link != null && link.asText().endsWith(component)) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode page = findPage(child, component);
            if (page != null) {
                return page;
            }
        }
        return null;
    }

    /** The text of {@code field} in each element of {@code array}, in order. */
    static List<String> texts(JsonNode array, String field) {
        List<String> texts = new ArrayList<>();
        for (JsonNode element : array) {
            texts.add(element.get(field).asText());
        }
        return texts;
    }

    /** The text of each element of {@code array}, in order. */
    static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        for (JsonNode element : array) {
            texts.add(element.asText());
        }
        return texts;
    }

    /** The first element of {@code array} whose {@code field} has the text {@code value}. */
    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode element : array) {
            JsonNode node = element.get(field);
            if (node != null && node.asText().equals(value)) {
                return element;
            }
        }
        throw new AssertionError("no element with " + field + " = " + value + " in " + array);
    }
}
