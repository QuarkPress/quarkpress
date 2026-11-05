package org.quarkpress.media.storage.fs.deployment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sends one HTTP/1.1 request with the path exactly as given (no client normalizes {@code ..} or percent escapes) and
 * reads the whole answer.
 */
public final class RawHttp {

    private RawHttp() {}

    /** An answer: status, headers by lower-case name, body. */
    public record Answer(int status, Map<String, String> headers, byte[] body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public static Answer send(URL server, String method, String path) throws IOException {
        return send(server, method, path, "");
    }

    /** As {@link #send(URL, String, String)} with extra request header lines, each ending in CRLF. */
    public static Answer send(URL server, String method, String path, String headerLines) throws IOException {
        try (Socket socket = new Socket(server.getHost(), server.getPort())) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            String request = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + headerLines
                    + ("POST".equals(method) ? "Content-Type: image/jpeg\r\nContent-Length: 4\r\n\r\nevil" : "\r\n");
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            return parse(all.toByteArray(), "HEAD".equals(method));
        }
    }

    private static Answer parse(byte[] raw, boolean head) {
        int end = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        if (end < 0) {
            throw new IllegalStateException("no complete HTTP answer: " + new String(raw, StandardCharsets.ISO_8859_1));
        }
        String[] lines = new String(raw, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(
                    lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }
        byte[] body = Arrays.copyOfRange(raw, end + 4, raw.length);
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding")) && !head) {
            body = unchunk(body);
        }
        return new Answer(status, headers, body);
    }

    private static byte[] unchunk(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = 0;
        while (at < body.length) {
            int lineEnd =
                    indexOf(Arrays.copyOfRange(body, at, body.length), "\r\n".getBytes(StandardCharsets.ISO_8859_1));
            String sizeLine = new String(body, at, lineEnd, StandardCharsets.ISO_8859_1);
            int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
            at += lineEnd + 2;
            if (size == 0) {
                break;
            }
            out.write(body, at, size);
            at += size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
