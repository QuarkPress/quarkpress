package org.quarkpress.restwp.deployment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local oEmbed provider on {@code 127.0.0.1} for the proxy tests: its endpoints answer by the path of the {@code url}
 * they are asked about (a rich embed with a script, a trusted embed with newlines, a failing page, a JSON list, an
 * empty body, a body over the size cap, a 501), and every request is logged as {@code path?query}, readable at
 * {@code /log}. It starts once per JVM; only its port is shared, as a system property, because a
 * {@code QuarkusExtensionTest} loads the test class a second time in the application's class loader (and a system
 * property that is not a string breaks the transaction manager's start).
 */
final class LocalProvider {

    private static final String KEY = LocalProvider.class.getName() + ".port";

    private final int port;

    private LocalProvider(int port) {
        this.port = port;
    }

    /** The provider, started by the first call in the JVM. */
    static synchronized LocalProvider get() {
        String port = System.getProperty(KEY);
        if (port == null) {
            port = Integer.toString(start());
            System.setProperty(KEY, port);
        }
        return new LocalProvider(Integer.parseInt(port));
    }

    int port() {
        return port;
    }

    /** An endpoint URL below the server: {@code http://127.0.0.1:<port>/<path>}. */
    String endpoint(String path) {
        return "http://127.0.0.1:" + port + "/" + path;
    }

    /** Every request seen, as {@code path?query}, oldest first. */
    List<String> requests() {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(endpoint("log")).toURL().openConnection();
            try (InputStream in = connection.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return body.isEmpty() ? List.of() : List.of(body.split("\n"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The requests whose decoded {@code url} parameter equals {@code url}. */
    List<String> requestsFor(String url) {
        List<String> hits = new ArrayList<>();
        for (String request : requests()) {
            if (url.equals(query(request).get("url"))) {
                hits.add(request);
            }
        }
        return hits;
    }

    /** The query of a logged request, decoded, in order. */
    static Map<String, String> query(String request) {
        Map<String, String> query = new LinkedHashMap<>();
        int mark = request.indexOf('?');
        if (mark < 0) {
            return query;
        }
        for (String pair : request.substring(mark + 1).split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            query.put(
                    URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static int start() {
        try {
            Queue<String> requests = new ConcurrentLinkedQueue<>();
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "local-provider");
                thread.setDaemon(true);
                return thread;
            });
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/log")) {
                    send(
                            exchange,
                            200,
                            "text/plain",
                            String.join("\n", requests).getBytes(StandardCharsets.UTF_8));
                    return;
                }
                requests.add(query == null ? path : path + "?" + query);
                answer(exchange, path, query == null ? "" : query);
            });
            server.start();
            return server.getAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The answer, decided by the path of the {@code url} asked about. */
    private static void answer(HttpExchange exchange, String path, String rawQuery) throws IOException {
        String url = query("?" + rawQuery).getOrDefault("url", "");
        String kind = url.replace("https://", "").replace("http://", "");
        kind = kind.substring(kind.indexOf('/') + 1);
        if (kind.startsWith("fail/")) {
            send(
                    exchange,
                    500,
                    "text/html",
                    "<html><body>Something went wrong</body></html>".getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("list/")) {
            send(exchange, 200, "application/json", "[1]".getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("empty/")) {
            send(exchange, 200, "application/json", new byte[0]);
        } else if (kind.startsWith("status501/")) {
            send(exchange, 501, "application/json", rich(url).getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("big/")) {
            // a valid object of 2 MiB: over the proxy's 1 MiB cap
            StringBuilder big = new StringBuilder("{\"type\":\"rich\",\"html\":\"");
            while (big.length() < 2 * 1024 * 1024) {
                big.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
            }
            big.append("\"}");
            send(exchange, 200, "application/json", big.toString().getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("newline/")) {
            send(exchange, 200, "application/json", newline(url).getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("quoted/")) {
            send(exchange, 200, "application/json", quoted(url).getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("photo/")) {
            send(
                    exchange,
                    200,
                    "application/json",
                    ("{\"version\":\"1.0\",\"type\":\"photo\",\"url\":\"https://local.invalid/i.jpg\",\"width\":10,"
                                    + "\"height\":20,\"title\":\"Photo\"}")
                            .getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("watch?v=ok")) {
            send(exchange, 200, "application/json", rich(url).getBytes(StandardCharsets.UTF_8));
        } else if (kind.startsWith("watch?v=")) {
            send(exchange, 500, "text/html", "<html><body>no</body></html>".getBytes(StandardCharsets.UTF_8));
        } else {
            // the error-page case of WordPress: any status with a JSON object body is accepted
            send(
                    exchange,
                    kind.startsWith("error404/") ? 404 : 200,
                    "application/json",
                    rich(url).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A rich embed with a script, a blockquote, two iframes and a trailing newline, naming the URL asked about. */
    private static String rich(String url) {
        return "{\"version\":\"1.0\",\"type\":\"rich\",\"title\":\"Rich & \\\"quoted\\\"\",\"provider_name\":\"Local\","
                + "\"width\":100,\"height\":50,\"html\":\"<script>alert(1)</script><blockquote><a href=\\\"" + url
                + "\\\">Rich</a></blockquote>\\n<iframe src=\\\"https://local.invalid/frame\\\" width=\\\"100\\\""
                + " height=\\\"50\\\" allow=\\\"camera\\\"></iframe><iframe src=\\\"https://local.invalid/second\\\">"
                + "</iframe>\\n\"}";
    }

    /** A rich embed opening with its blockquote, then one iframe: the shape of a real untrusted embed. */
    private static String quoted(String url) {
        return "{\"version\":\"1.0\",\"type\":\"rich\",\"title\":\"Quoted\",\"html\":\"<blockquote><a href=\\\"" + url
                + "\\\">Quoted</a></blockquote>\\n<iframe src=\\\"https://local.invalid/frame\\\" width=\\\"100\\\""
                + " height=\\\"50\\\"></iframe>\\n\"}";
    }

    /** A video embed as a trusted provider sends it: a script, a titled iframe and newlines, one inside a pre. */
    private static String newline(String url) {
        return "{\"version\":\"1.0\",\"type\":\"video\",\"title\":\"Trusted\",\"html\":\"<script>x()</script>\\n"
                + "<iframe src=\\\"https://trusted.invalid/f\\\" allow=\\\"camera\\\"></iframe>\\r\\n<pre>a\\nb</pre>\\n\"}";
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
