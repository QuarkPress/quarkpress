package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.UserId;

/** A REST request as a handler sees it. */
public interface RestRequest {

    /** The method after any override, upper-case; HEAD stays HEAD. */
    String method();

    /** The matched route's pattern key, such as {@code /wp/v2/categories/(?P<id>[\d]+)}. */
    String route();

    /** The requested route path, such as {@code /wp/v2/categories/5}. */
    String path();

    /** The named groups of the matched route. */
    Map<String, String> urlParams();

    /**
     * A parameter from the first source that has it: the JSON body, the form body, the query string, the route's
     * groups, then the endpoint's defaults. A JSON null counts as absent.
     */
    Optional<JsonNode> param(String name);

    boolean hasParam(String name);

    /** The query string as parsed, for pagination links. */
    ObjectNode queryParams();

    /** The {@code context} parameter as text, {@code view} when absent. */
    String context();

    /**
     * Sets the request's {@code context} parameter to {@code context} from now on, over every source, as WordPress's
     * handlers do before they prepare their answer (a created or updated term is answered in the edit context, a
     * deleted one's previous state in the view context). Only a request the REST server built honours it: from then on
     * {@link #context()} and {@link #isEditContext()} answer with that context, and the server computes the answer's
     * {@code Allow} header, after the handler, with it, as WordPress does. A request that wraps another ignores it, as
     * it answers the served-request accessors {@link #paramNames()}, {@link #secure()}, {@link #headerValues},
     * {@link #files()} and {@link #rawBody()} with their defaults.
     */
    default void useContext(String context) {}

    /**
     * Whether the request came over HTTPS: a TLS connection, or a proxy's {@code X-Forwarded-Proto: https} (or a
     * {@code Forwarded} header's {@code proto=https}) when Quarkus's proxy settings ({@code quarkus.http.proxy.*})
     * honour it. A request the REST server built answers it from the connection; any other answers false.
     */
    default boolean secure() {
        return false;
    }

    /**
     * The names of the request's parameters from every source (the JSON body, the form body, the query string, the
     * route's groups and the endpoint's defaults), the set WordPress lists for a request's parameters, a parameter sent
     * as JSON null included. A request the REST server built answers it from its sources; any other answers an empty
     * set.
     */
    default Set<String> paramNames() {
        return Set.of();
    }

    /**
     * Every value of request header {@code name} (compared ignoring case), one per header line in the order received,
     * never split at commas. A request the REST server built answers it from the HTTP request; any other answers an
     * empty list.
     */
    default List<String> headerValues(String name) {
        return List.of();
    }

    /**
     * The file parts of a multipart POST body, in body order (the parts of one name together, where the name first
     * appears). Quarkus stores each on disk for the time of the request and deletes it once the request ends. A
     * multipart body of any other method has none, as PHP fills its uploaded files only for POST. A request the REST
     * server built answers it from the HTTP request; any other answers an empty list.
     */
    default List<UploadedFile> files() {
        return List.of();
    }

    /**
     * The body as sent, which can be read once, for a request whose body is not multipart: a JSON, form or untyped body
     * (already read whole, within Quarkus's {@code quarkus.http.limits.max-body-size}) as an in-memory stream, a body
     * of any other content type as the live request stream, never held in memory, which Quarkus ends with a 413 once
     * more than that limit has been read. Empty for a multipart body, an embedded request, and a request the REST
     * server did not build.
     */
    default Optional<InputStream> rawBody() {
        return Optional.empty();
    }

    /**
     * Whether the {@code context} parameter is the text {@code edit} (WordPress compares it strictly, so only a string
     * equal to "edit" asks for the edit context).
     */
    default boolean isEditContext() {
        return param("context")
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .filter("edit"::equals)
                .isPresent();
    }

    SecurityIdentity identity();

    Optional<UserId> user();

    /** WordPress's status for a refusal: 401 for an anonymous caller, 403 otherwise. */
    int authCode();
}
