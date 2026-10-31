package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Supplier;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.RestRequest;

/**
 * A request the REST server matched to a route and an endpoint: it knows the route's URL and the endpoint's declared
 * arguments. Tests of handlers implement it on their stub requests.
 */
public interface ServedRequest extends RestRequest {

    /**
     * The matched route's URL for this request: the route's pattern with each named group replaced by the value the
     * request gave it, below the site's API root (as WordPress names a collection in its pagination links).
     */
    String routeUrl();

    /**
     * Whether any parameter source holds {@code name}, with JSON null too (which {@link #param} and {@link #hasParam}
     * count as absent): a setting sent as null is reset.
     */
    default boolean sentParam(String name) {
        return hasParam(name);
    }

    /** Whether the matched endpoint declares an argument named {@code name}. */
    boolean declaresArg(String name);

    /** The request's {@code _fields}, parsed; the server's requests parse them once. */
    default List<String> requestedFields() {
        return param("_fields").map(Fields::parse).orElse(List.of());
    }

    /**
     * What renders this request's links, resolving them against the server's routes and the site; null for a request
     * whose links are rendered without target hints.
     */
    default LinkRenderer linkRenderer() {
        return null;
    }

    /**
     * The selection of fields for {@code itemSchema}: {@code compute}'s answer, which the server's requests keep for
     * the request (one per schema), so that a controller asking once per item does the work once.
     */
    default Fields.Selection selection(ObjectNode itemSchema, Supplier<Fields.Selection> compute) {
        return compute.get();
    }
}
