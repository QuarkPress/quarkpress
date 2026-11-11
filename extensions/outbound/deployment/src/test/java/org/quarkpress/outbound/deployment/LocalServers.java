package org.quarkpress.outbound.deployment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.StandardConstants;

/**
 * The local servers the fetcher tests talk to, all on {@code 127.0.0.1}: a plain HTTP server, an HTTPS server with the
 * certificate of {@code pinned.test}, one with the certificate of {@code other.test}, and a sentinel socket that only
 * counts connections. They start once per JVM and are shared through the system properties with JDK types only,
 * because a {@code QuarkusExtensionTest} loads the test class a second time in the application's class loader: both
 * copies must see the same servers (the configured ports name them).
 */
final class LocalServers {

    private static final String KEY = LocalServers.class.getName();

    static final long MIB = 1024 * 1024;

    private final Map<String, Object> shared;

    private LocalServers(Map<String, Object> shared) {
        this.shared = shared;
    }

    /** The servers, started by the first call in the JVM. */
    @SuppressWarnings("unchecked")
    static synchronized LocalServers get() {
        Map<String, Object> shared =
                (Map<String, Object>) System.getProperties().computeIfAbsent(KEY, key -> start());
        return new LocalServers(shared);
    }

    /** The ports of the three HTTP servers, as {@code quarkpress.outbound.allowed-ports} lists them. */
    String allowedPorts() {
        String ports = httpPort() + "," + pinnedPort() + "," + otherPort() + "," + bigPort();
        return ipv6Port() == -1 ? ports : ports + "," + ipv6Port();
    }

    /** The port of a plain HTTP server on {@code ::1}, or -1 when the host has no IPv6 loopback. */
    int ipv6Port() {
        Object server = shared.get("ipv6");
        return server == null ? -1 : ((HttpServer) server).getAddress().getPort();
    }

    /**
     * A raw HTTP/1.1 server, one connection at a time, whose every answer is a 100 MiB body, written with a 64 KiB
     * socket send buffer so that the count of written bytes is not hidden by megabytes of kernel buffering.
     */
    int bigPort() {
        return ((ServerSocket) shared.get("big")).getLocalPort();
    }

    int httpPort() {
        return ((HttpServer) shared.get("http")).getAddress().getPort();
    }

    int pinnedPort() {
        return ((HttpServer) shared.get("pinned")).getAddress().getPort();
    }

    int otherPort() {
        return ((HttpServer) shared.get("other")).getAddress().getPort();
    }

    /** A port on {@code 127.0.0.1} that is not among the allowed ones; connections to it are counted. */
    int sentinelPort() {
        return ((ServerSocket) shared.get("sentinel")).getLocalPort();
    }

    int sentinelConnections() {
        return ((AtomicInteger) shared.get("sentinel-connections")).get();
    }

    /** What the HTTPS servers saw: {@code "<server> sni=<name> host=<Host header>"}, one entry per request. */
    @SuppressWarnings("unchecked")
    Queue<String> tlsRequests() {
        return (Queue<String>) shared.get("tls-requests");
    }

    /** The paths the plain server answered, in order. */
    @SuppressWarnings("unchecked")
    Queue<String> httpRequests() {
        return (Queue<String>) shared.get("http-requests");
    }

    /** Bytes the big server handed to its latest connection before a write failed. */
    long bigWritten() {
        return ((AtomicLong) shared.get("big-written")).get();
    }

    /** Waits until the big server stopped writing to its latest connection. */
    @SuppressWarnings("unchecked")
    boolean awaitBigDone(long seconds) throws InterruptedException {
        return ((AtomicReference<CountDownLatch>) shared.get("big-done")).get().await(seconds, TimeUnit.SECONDS);
    }

    private static Map<String, Object> start() {
        try {
            Map<String, Object> shared = new ConcurrentHashMap<>();
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "local-server");
                thread.setDaemon(true);
                return thread;
            });
            Queue<String> tlsRequests = new ConcurrentLinkedQueue<>();
            Queue<String> httpRequests = new ConcurrentLinkedQueue<>();
            AtomicLong bigWritten = new AtomicLong();
            // the latch of the latest connection to the big server, counted down when it stops writing
            AtomicReference<CountDownLatch> bigDone = new AtomicReference<>(new CountDownLatch(1));
            AtomicInteger sentinelConnections = new AtomicInteger();
            shared.put("tls-requests", tlsRequests);
            shared.put("http-requests", httpRequests);
            shared.put("big-written", bigWritten);
            shared.put("big-done", bigDone);
            shared.put("sentinel-connections", sentinelConnections);

            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            http.setExecutor(executor);
            http.createContext("/", exchange -> {
                httpRequests.add(exchange.getRequestURI().getPath());
                plain(exchange, shared);
            });
            http.start();
            shared.put("http", http);

            try {
                HttpServer ipv6 = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
                ipv6.setExecutor(executor);
                ipv6.createContext("/", exchange -> {
                    httpRequests.add("[::1]" + exchange.getRequestURI().getPath());
                    plain(exchange, shared);
                });
                ipv6.start();
                shared.put("ipv6", ipv6);
            } catch (IOException noIpv6) {
                // the IPv6 test is skipped
            }

            shared.put("pinned", https("pinned", "/pinned-test.p12", executor, tlsRequests));
            shared.put("other", https("other", "/other-test.p12", executor, tlsRequests));

            ServerSocket sentinel = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(
                    () -> {
                        while (true) {
                            try {
                                sentinel.accept().close();
                                sentinelConnections.incrementAndGet();
                            } catch (IOException e) {
                                return;
                            }
                        }
                    },
                    "sentinel");
            acceptor.setDaemon(true);
            acceptor.start();
            shared.put("sentinel", sentinel);

            ServerSocket big = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread bigAcceptor = new Thread(
                    () -> {
                        while (true) {
                            try {
                                Socket socket = big.accept();
                                bigWritten.set(0);
                                CountDownLatch done = new CountDownLatch(1);
                                bigDone.set(done);
                                big(socket, bigWritten, done);
                            } catch (IOException e) {
                                return;
                            }
                        }
                    },
                    "big");
            bigAcceptor.setDaemon(true);
            bigAcceptor.start();
            shared.put("big", big);
            return shared;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void plain(HttpExchange exchange, Map<String, Object> shared) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try (exchange) {
            if (path.equals("/ok") || path.equals("/rebind")) {
                exchange.getResponseHeaders().add("X-Test", "a");
                exchange.getResponseHeaders().add("X-Test", "b");
                byte[] body = "hello local".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else if (path.startsWith("/chain/")) {
                int hop = Integer.parseInt(path.substring("/chain/".length()));
                exchange.getResponseHeaders().add("Location", "/chain/" + (hop + 1));
                exchange.getResponseHeaders().add("X-Hop", Integer.toString(hop));
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/to-ssh")) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:22/");
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/to-sentinel")) {
                int port = ((ServerSocket) shared.get("sentinel")).getLocalPort();
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/");
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/stall")) {
                // one byte, then silence
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                try {
                    out.write('x');
                    out.flush();
                    sleep(30_000);
                } catch (IOException expected) {
                    // the client gave up
                }
            } else if (path.equals("/eight-mib")) {
                exchange.sendResponseHeaders(200, 8 * MIB);
                OutputStream out = exchange.getResponseBody();
                byte[] chunk = new byte[64 * 1024];
                for (long sent = 0; sent < 8 * MIB; sent += chunk.length) {
                    out.write(chunk);
                }
            } else if (path.equals("/trickle")) {
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                try {
                    for (int i = 0; i < 60; i++) {
                        out.write('x');
                        out.flush();
                        sleep(1000);
                    }
                } catch (IOException expected) {
                    // the client gave up
                }
            } else if (path.equals("/bomb")) {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream gzip = new GZIPOutputStream(exchange.getResponseBody(), 64 * 1024)) {
                    byte[] zeros = new byte[64 * 1024];
                    for (long sent = 0; sent < 100 * MIB; sent += zeros.length) {
                        gzip.write(zeros);
                    }
                } catch (IOException expected) {
                    // the client gave up
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void big(Socket socket, AtomicLong written, CountDownLatch done) {
        try (socket) {
            socket.setSendBufferSize(64 * 1024);
            InputStream in = socket.getInputStream();
            // the request head ends with an empty line
            for (int state = 0, c; state < 4 && (c = in.read()) != -1; ) {
                state = (c == '\r' && state % 2 == 0) || (c == '\n' && state % 2 == 1) ? state + 1 : 0;
            }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + 100 * MIB + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[8 * 1024];
            for (long sent = 0; sent < 100 * MIB; sent += chunk.length) {
                out.write(chunk);
                out.flush();
                written.addAndGet(chunk.length);
            }
        } catch (IOException expected) {
            // the client reset the connection
        } finally {
            done.countDown();
        }
    }

    private static HttpServer https(String name, String keyStore, ExecutorService executor, Queue<String> requests)
            throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = LocalServers.class.getResourceAsStream(keyStore)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + keyStore);
            }
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        // the SNI name of the connection being set up, per handshake thread; read when the request arrives
        Map<Object, String> sniOfHandshake = new ConcurrentHashMap<>();
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = context.getDefaultSSLParameters();
                parameters.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                    @Override
                    public boolean matches(SNIServerName serverName) {
                        sniOfHandshake.put("last", ((SNIHostName) serverName).getAsciiName());
                        return true;
                    }
                }));
                params.setSSLParameters(parameters);
            }
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                String sni = sniOfHandshake.remove("last");
                requests.add(name + " sni=" + (sni == null ? "-" : sni) + " host="
                        + exchange.getRequestHeaders().getFirst("Host"));
                byte[] body = ("tls " + name).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return server;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted"));
        }
    }
}
