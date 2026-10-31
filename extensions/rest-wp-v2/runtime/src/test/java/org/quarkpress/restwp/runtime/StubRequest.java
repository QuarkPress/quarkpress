package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.UserId;

/**
 * A request for handler unit tests, as the REST server would hand it over: anonymous, method GET, its parameters from
 * {@code params} (the query too), the route's URL and the endpoint's declared argument names as given.
 */
public final class StubRequest implements ServedRequest {

    private final ObjectNode params;
    private final String routeUrl;
    private final Set<String> declared;

    public StubRequest(ObjectNode params, String routeUrl, Set<String> declared) {
        this.params = params;
        this.routeUrl = routeUrl;
        this.declared = declared;
    }

    @Override
    public String routeUrl() {
        return routeUrl;
    }

    @Override
    public boolean declaresArg(String name) {
        return declared.contains(name);
    }

    @Override
    public String method() {
        return "GET";
    }

    @Override
    public String route() {
        return "/stub";
    }

    @Override
    public String path() {
        return "/stub";
    }

    @Override
    public Map<String, String> urlParams() {
        return Map.of();
    }

    @Override
    public Optional<JsonNode> param(String name) {
        return Optional.ofNullable(params.get(name)).filter(value -> !value.isNull());
    }

    @Override
    public boolean hasParam(String name) {
        return param(name).isPresent();
    }

    @Override
    public ObjectNode queryParams() {
        return params;
    }

    @Override
    public String context() {
        return param("context").map(JsonNode::asText).orElse("view");
    }

    @Override
    public SecurityIdentity identity() {
        return null;
    }

    @Override
    public Optional<UserId> user() {
        return Optional.empty();
    }

    @Override
    public int authCode() {
        return 401;
    }
}
