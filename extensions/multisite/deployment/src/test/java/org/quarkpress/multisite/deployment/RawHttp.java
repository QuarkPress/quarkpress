package org.quarkpress.multisite.deployment;

import io.restassured.RestAssured;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Sends one HTTP/1.0 request whose request line is exactly as written: HTTP clients remove dot segments and decode
 * paths before sending, which the path tests must avoid. HTTP/1.0 keeps the response unchunked.
 */
public final class RawHttp {

    /** The status code and the body of a response. */
    public record Response(int status, String body) {}

    private RawHttp() {}

    public static Response get(String host, String rawPath) throws IOException {
        return request("GET " + rawPath, "Host: " + host);
    }

    /** One HTTP/1.0 request with the given request line (without version) and header lines, exactly as written. */
    public static Response request(String requestLine, String... headers) throws IOException {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write((requestLine + " HTTP/1.0\r\n" + String.join("\r\n", headers)
                            + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String text = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(text.substring("HTTP/1.x ".length(), "HTTP/1.x 200".length()));
            int bodyStart = text.indexOf("\r\n\r\n");
            return new Response(status, bodyStart < 0 ? "" : text.substring(bodyStart + 4));
        }
    }
}
