package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.Map;
import java.util.Optional;
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

    SecurityIdentity identity();

    Optional<UserId> user();

    /** WordPress's status for a refusal: 401 for an anonymous caller, 403 otherwise. */
    int authCode();
}
