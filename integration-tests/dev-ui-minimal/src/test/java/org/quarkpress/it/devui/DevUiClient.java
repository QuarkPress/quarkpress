package org.quarkpress.it.devui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.devui.tests.DevUIBuildTimeDataTest;
import io.quarkus.value.registry.ValueRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Reads the extension's Dev UI from a dev-mode test: its JSON-RPC methods and its build-time data. Each test class
 * holds one and calls {@link #connect} before each test.
 *
 * <p>JSON-RPC calls go through a small client of our own over the Dev UI websocket, the endpoint Quarkus's
 * {@code DevUIJsonRPCTest} uses: that helper cannot report an error answer (it waits for a result until it gives up),
 * so a refused call could not be told from one that never answered. Here a call that answers an error returns it, and
 * a call that does not answer within {@link #TIMEOUT} fails the test. Copied from the dev-ui deployment test helper
 * of that name.
 */
public final class DevUiClient {

    static final String NAMESPACE = "quarkpress-dev-ui";

    /** The longest a JSON-RPC call may take to answer. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Where Quarkus registers the running application's base URI, as Dev UI's test helpers read it. */
    private static final ValueRegistry.RuntimeKey<URI> LOCAL_BASE_URI =
            ValueRegistry.RuntimeKey.key("quarkus.http.local-base-uri");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private final DevUIBuildTimeDataTest buildTimeData = new DevUIBuildTimeDataTest(NAMESPACE) {};
    private final DevUIBuildTimeDataTest devUiData = new DevUIBuildTimeDataTest("devui") {};
    private URI jsonRpc;

    /** The error a JSON-RPC call answered: its code and message, as Dev UI wrote them. */
    record RpcError(int code, String message) {}

    /**
     * What a JSON-RPC call answered: either its result ({@code error} is {@code null}) or its error ({@code result} is
     * {@code null}). An answer with neither fails the test.
     */
    record RpcResponse(JsonNode result, RpcError error) {}

    void connect(ValueRegistry registry) {
        URI base = registry.get(LOCAL_BASE_URI);
        String root = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.root-path", String.class)
                .orElse("/q");
        if (!root.startsWith("/")) {
            root = "/" + root;
        }
        if (!root.endsWith("/")) {
            root = root + "/";
        }
        String http = base.toString();
        String ws = http.startsWith("https://") ? "wss://" + http.substring(8) : http.replace("http://", "ws://");
        jsonRpc = URI.create(ws + root + "dev-ui/json-rpc-ws");
        buildTimeData.beforeEach(registry);
        devUiData.beforeEach(registry);
    }

    /** The result of {@code method}; the test fails if it answers an error. */
    JsonNode call(String method) throws Exception {
        return call(method, Map.of());
    }

    /** The result of {@code method} called with {@code params}; the test fails if it answers an error. */
    JsonNode call(String method, Map<String, Object> params) throws Exception {
        RpcResponse response = rpc(method, params);
        if (response.error() != null) {
            throw new AssertionError("Dev UI call " + method + params + " answered " + response.error());
        }
        return response.result();
    }

    /**
     * What {@code method} called with {@code params} answered, result or error. The test fails if no answer comes
     * within {@link #TIMEOUT}.
     */
    RpcResponse rpc(String method, Map<String, Object> params) throws Exception {
        if (jsonRpc == null) {
            throw new IllegalStateException("connect was not called, or the application serves no HTTP");
        }
        int id = IDS.incrementAndGet();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", NAMESPACE + "_" + method);
        request.set("params", MAPPER.valueToTree(params));
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        HttpClient http = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> connecting = null;
        WebSocket socket = null;
        try {
            connecting =
                    http.newWebSocketBuilder().connectTimeout(TIMEOUT).buildAsync(jsonRpc, new Answers(id, answer));
            socket = connecting.get(remaining(deadline), TimeUnit.NANOSECONDS);
            socket.sendText(MAPPER.writeValueAsString(request), true).get(remaining(deadline), TimeUnit.NANOSECONDS);
            JsonNode message = answer.get(remaining(deadline), TimeUnit.NANOSECONDS);
            JsonNode error = message.get("error");
            if (error != null) {
                return new RpcResponse(
                        null,
                        new RpcError(
                                error.get("code").asInt(), error.get("message").asText()));
            }
            JsonNode result = message.path("result").get("object");
            if (result == null) {
                throw new AssertionError(
                        "Dev UI call " + method + params + " answered neither a result nor an error: " + message);
            }
            return new RpcResponse(result, null);
        } catch (TimeoutException e) {
            throw new AssertionError("Dev UI call " + method + params + " gave no answer within " + TIMEOUT, e);
        } finally {
            if (socket != null) {
                socket.abort();
            } else if (connecting != null) {
                // a connection that opens after the caller gave up is aborted as soon as it opens
                connecting.thenAccept(WebSocket::abort);
            }
            // does not wait for the client's outstanding operations, so the call stays within TIMEOUT
            http.shutdownNow();
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** Collects text frames into messages and completes {@code answer} with the one whose id is {@code id}. */
    private static final class Answers implements WebSocket.Listener {

        private final int id;
        private final CompletableFuture<JsonNode> answer;
        private final StringBuilder text = new StringBuilder();

        Answers(int id, CompletableFuture<JsonNode> answer) {
            this.id = id;
            this.answer = answer;
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try {
                    JsonNode message = MAPPER.readTree(text.toString());
                    if (message.path("id").asLong(-1) == id) {
                        answer.complete(message);
                    }
                } catch (IOException e) {
                    answer.completeExceptionally(e);
                }
                text.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            answer.completeExceptionally(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            answer.completeExceptionally(
                    new IllegalStateException("the Dev UI websocket closed (" + statusCode + " " + reason + ")"));
            return null;
        }
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
