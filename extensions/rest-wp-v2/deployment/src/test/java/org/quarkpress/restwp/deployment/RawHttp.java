package org.quarkpress.restwp.deployment;

import io.restassured.RestAssured;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 requests sent byte for byte, for request targets an HTTP client would normalize (dot segments, double
 * slashes, encoded slashes).
 */
public final class RawHttp {

    /** The status line's code and the rest of the response as text. */
    public record Reply(int status, String text) {}

    private RawHttp() {}

    public static Reply get(String target) {
        return send("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    public static Reply send(String request) {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            in.transferTo(reply);
            String text = reply.toString(StandardCharsets.ISO_8859_1);
            int space = text.indexOf(' ');
            return new Reply(Integer.parseInt(text.substring(space + 1, space + 4)), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
