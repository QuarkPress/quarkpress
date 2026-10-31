package org.quarkpress.restwp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestRequest;

/**
 * WordPress's {@code Allow} header: the methods of the matched route that this caller may use, asked of each
 * endpoint's own permission, the one the dispatcher checks, with this request.
 */
public final class AllowHeader {

    private AllowHeader() {}

    /**
     * For each endpoint of the matched route in registration order and each of its methods: allowed when its permission
     * answers {@link PermissionResult.Allowed} for {@code request} (a permission that fails counts as a refusal); a
     * later endpoint's answer replaces an earlier one's for the same method. The allowed methods joined with
     * {@code ", "}, in the order first seen; empty when none is.
     */
    public static Optional<String> compute(RouteTable.MatchedRoute matched, RestRequest request) {
        List<String> allowed = methods(matched, request);
        return allowed.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", allowed));
    }

    /** The methods {@link #compute} lists, in its order; empty when none is allowed. */
    public static List<String> methods(RouteTable.MatchedRoute matched, RestRequest request) {
        Map<String, Boolean> methods = new LinkedHashMap<>();
        for (Endpoint endpoint : matched.route().endpoints()) {
            boolean allowed = allowed(endpoint, request);
            for (String method : endpoint.methods()) {
                methods.put(method, allowed);
            }
        }
        List<String> allowed = new ArrayList<>();
        methods.forEach((method, isAllowed) -> {
            if (isAllowed) {
                allowed.add(method);
            }
        });
        return allowed;
    }

    private static boolean allowed(Endpoint endpoint, RestRequest request) {
        try {
            return endpoint.permission().check(request) instanceof PermissionResult.Allowed;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
