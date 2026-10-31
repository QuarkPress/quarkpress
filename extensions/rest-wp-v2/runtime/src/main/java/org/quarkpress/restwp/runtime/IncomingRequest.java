package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the REST server receives of an HTTP request.
 *
 * @param method the HTTP method, before any override
 * @param route the requested route, such as {@code /wp/v2/categories/5}
 * @param query the query string, parsed as PHP parses it
 * @param headers the request headers, names compared ignoring case
 * @param body the raw body, empty when there is none
 * @param form the form fields PHP itself reads (a POST with a form content type), empty otherwise
 * @param target the path and query the client asked for, as sent, such as {@code /wp-json/wp/v2/posts?page=2}
 */
public record IncomingRequest(
        String method,
        String route,
        ObjectNode query,
        Map<String, List<String>> headers,
        byte[] body,
        ObjectNode form,
        String target) {

    private static final String FORM = "application/x-www-form-urlencoded";

    public IncomingRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(target, "target");
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.merge(name, List.copyOf(values), (a, b) -> {
            List<String> all = new ArrayList<>(a);
            all.addAll(b);
            return List.copyOf(all);
        }));
        headers = copy;
    }

    /** A request whose target is its route (for requests built in code). */
    public IncomingRequest(
            String method,
            String route,
            ObjectNode query,
            Map<String, List<String>> headers,
            byte[] body,
            ObjectNode form) {
        this(method, route, query, headers, body, form, route);
    }

    /** This request with {@code route} as its route. */
    public IncomingRequest withRoute(String route) {
        return new IncomingRequest(method, route, query, headers, body, form, target);
    }

    /**
     * Header {@code name} as PHP's server variables hold it: every value of the header joined with {@code ", "}, or
     * null when the request has none.
     */
    public String joinedHeader(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    /** The first value of header {@code name}, or null. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String contentType() {
        return header("Content-Type");
    }

    /** The media type of a content type: before any {@code ;}, trimmed, ASCII lower-case; null for null. */
    static String mediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip();
        return type.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a content type is JSON as WordPress tells: {@code application/json} or any {@code application/...+json},
     * optionally followed by {@code +oembed}, ignoring case and parameters.
     */
    public static boolean isJson(String contentType) {
        String type = mediaType(contentType);
        if (type == null || !type.startsWith("application/")) {
            return false;
        }
        String subtype = type.substring("application/".length());
        if (subtype.endsWith("+oembed")) {
            subtype = subtype.substring(0, subtype.length() - "+oembed".length());
        }
        return subtype.equals("json") || (subtype.endsWith("+json") && subtype.length() > "+json".length());
    }

    /** Whether PHP reads a POST body of this content type as form fields (URL-encoded; multipart is not read). */
    public static boolean isForm(String contentType) {
        return FORM.equals(mediaType(contentType));
    }

    /** Whether the body of a method other than POST is read as form fields: no content type, or exactly the form one. */
    static boolean isRawForm(String contentType) {
        return contentType == null || contentType.equals(FORM);
    }

    /** PHP's {@code empty()} of a body: nothing, or {@code 0}. */
    boolean emptyBody() {
        return body.length == 0 || (body.length == 1 && body[0] == '0');
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return method + " " + route;
    }
}
