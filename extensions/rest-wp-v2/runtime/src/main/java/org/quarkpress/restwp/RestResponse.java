package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.quarkpress.restwp.runtime.Pagination;
import org.quarkpress.restwp.runtime.ServedRequest;

/**
 * A handler's answer: status, body, headers (in the order set) and links.
 *
 * @param headers header values by name; {@link #withHeader} replaces a header's values
 */
public record RestResponse(int status, JsonNode body, Map<String, List<String>> headers, Links links) {

    public RestResponse {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(links, "links");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copy);
    }

    public static RestResponse ok(JsonNode body) {
        return new RestResponse(200, body, Map.of(), Links.none());
    }

    public static RestResponse created(JsonNode body, String location) {
        return new RestResponse(201, body, Map.of(), Links.none()).withHeader("Location", location);
    }

    /**
     * A page of a collection: status 200, {@code body}, and WordPress's pagination headers ({@code X-WP-Total},
     * {@code X-WP-TotalPages}, and one {@code Link} line with the previous and next pages when there are any, which
     * replaces the API root's {@code Link}). The current page is the one {@code paging}'s offset falls on; the links
     * name the matched route's URL with every query parameter of {@code request}.
     *
     * @param request the request the REST server handed to the handler; it must implement the runtime's
     *     {@code ServedRequest} (which knows the route's URL), as every request the server dispatches does. A stub
     *     request in a handler's unit test implements it too.
     * @throws IllegalArgumentException when {@code request} does not implement {@code ServedRequest}
     */
    public static RestResponse paged(JsonNode body, long total, Paging paging, RestRequest request) {
        if (!(request instanceof ServedRequest routed)) {
            throw new IllegalArgumentException("not a request the REST server dispatched: "
                    + request.getClass().getName());
        }
        return new RestResponse(
                200,
                body,
                Pagination.headers(
                        total,
                        paging.perPage(),
                        Paging.currentPage(paging.offset(), paging.perPage()),
                        routed.routeUrl(),
                        request.queryParams()),
                Links.none());
    }

    public RestResponse withStatus(int status) {
        return new RestResponse(status, body, headers, links);
    }

    public RestResponse withHeader(String name, String value) {
        Map<String, List<String>> more = new LinkedHashMap<>(headers);
        more.put(name, new ArrayList<>(List.of(value)));
        return new RestResponse(status, body, more, links);
    }

    public RestResponse withLinks(Links links) {
        return new RestResponse(status, body, headers, links);
    }
}
