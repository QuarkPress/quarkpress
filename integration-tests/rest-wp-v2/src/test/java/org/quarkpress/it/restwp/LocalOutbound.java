package org.quarkpress.it.restwp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The profile of the outbound tests: a local HTTP server on {@code 127.0.0.1} (an oEmbed provider at {@code /oembed},
 * an image, a redirect to a refused port and a 50 MiB body) that the application is allowed to reach (loopback
 * addresses allowed, the server's port among the allowed ports, the provider registered for the {@code /v/*} URLs
 * below the server); the upload cap stays the application's own (the network's default per-file limit), so the huge
 * body proves the real cap. The server starts once per JVM, when the profile is first read, before the application; in
 * the integration test the application is another process (the JVM jar locally, the native executable in CI) and
 * reaches the server over the loopback, while the overrides reach it as system properties. Only the port is shared, as
 * a system property, because a test class is loaded a second time in the application's class loader. The class is
 * public, unlike the rest of the package, because Quarkus instantiates a test profile reflectively.
 */
public class LocalOutbound implements QuarkusTestProfile {

    private static final String KEY = LocalOutbound.class.getName() + ".port";

    @Override
    public Map<String, String> getConfigOverrides() {
        int port = port();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("quarkpress.outbound.allow-private-addresses", "true");
        overrides.put("quarkpress.outbound.allowed-ports", "80,443,8080," + port);
        overrides.put("quarkpress.oembed.provider-schemes.local", "http://127.0.0.1:" + port + "/v/*");
        overrides.put("quarkpress.oembed.provider-endpoints.local", "http://127.0.0.1:" + port + "/oembed");
        return overrides;
    }

    /** The server's port, starting it on the first call in the JVM. */
    static synchronized int port() {
        String port = System.getProperty(KEY);
        if (port == null) {
            port = Integer.toString(start());
            System.setProperty(KEY, port);
        }
        return Integer.parseInt(port);
    }

    /** {@code http://127.0.0.1:<port>} + {@code pathAndQuery}. */
    static String url(String pathAndQuery) {
        return "http://127.0.0.1:" + port() + pathAndQuery;
    }

    /** Every request seen, as {@code METHOD path?query status}, oldest first; the huge body's lines add their bytes. */
    static List<String> log() {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(url("/log")).toURL().openConnection();
            try (InputStream in = connection.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return body.isEmpty() ? List.of() : List.of(body.split("\n"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bytes written to {@code path} before the client reset the connection, waiting for the reset. */
    static long waitForReset(String path) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (String line : log()) {
                if (line.startsWith("RESET " + path + " ")) {
                    return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("the server saw no reset on " + path + ": " + log());
    }

    private static int start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            Queue<String> log = new ConcurrentLinkedQueue<>();
            server.createContext("/", exchange -> handle(exchange, log));
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "local-outbound");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return server.getAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange, Queue<String> log) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        if (path.equals("/log")) {
            byte[] body = String.join("\n", log).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        String line = exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query);
        if (path.startsWith("/photo-") && path.endsWith(".jpg")) {
            // one name per test run, so that the stored name is never suffixed after an earlier run's file
            send(exchange, log, line, 200, "image/jpeg", MediaFixtures.jpeg(64, 48));
            return;
        }
        switch (path) {
            case "/oembed" ->
                send(exchange, log, line, 200, "application/json", rich().getBytes(StandardCharsets.UTF_8));
            case "/redirect.jpg" -> {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:22/a.jpg");
                send(exchange, log, line, 302, "text/plain", new byte[0]);
            }
            case "/huge.jpg" -> huge(exchange, log, path, line);
            default -> send(exchange, log, line, 404, "text/plain", "no such file".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A rich embed with a script, a blockquote and an iframe, as an untrusted provider might answer. */
    private static String rich() {
        return "{\"version\":\"1.0\",\"type\":\"rich\",\"title\":\"Local\",\"provider_name\":\"Local\",\"width\":100,"
                + "\"height\":50,\"html\":\"<script>alert(1)</script><blockquote><a href=\\\"http://127.0.0.1/v/rich\\\">"
                + "Local</a></blockquote>\\n<iframe src=\\\"https://local.invalid/frame\\\" width=\\\"100\\\""
                + " height=\\\"50\\\" allow=\\\"camera\\\" onload=\\\"x()\\\"></iframe>\\n\"}";
    }

    private static void send(
            HttpExchange exchange, Queue<String> log, String line, int status, String type, byte[] body)
            throws IOException {
        log.add(line + " " + status);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 50 MiB of JPEG-looking bytes, streamed until the client resets the connection. */
    private static void huge(HttpExchange exchange, Queue<String> log, String path, String line) throws IOException {
        log.add(line + " 200");
        exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
        long total = 50L * 1024 * 1024;
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte) 0x5a);
        long written = 0;
        exchange.sendResponseHeaders(200, total);
        try (OutputStream out = exchange.getResponseBody()) {
            while (written < total) {
                out.write(chunk);
                out.flush();
                written += chunk.length;
            }
            log.add("DONE " + path + " " + written);
        } catch (IOException reset) {
            log.add("RESET " + path + " " + written);
        }
    }
}
