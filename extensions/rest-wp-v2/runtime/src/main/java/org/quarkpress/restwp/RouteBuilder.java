package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Supplier;

/** One route being declared: its shared arguments, its item schema and its endpoints. */
public interface RouteBuilder {

    /** An argument of every endpoint of the route; an endpoint's own argument of the same name replaces it. */
    RouteBuilder arg(Arg arg);

    /** The schema of the route's items, built when it is first needed. */
    RouteBuilder schema(Supplier<ObjectNode> itemSchema);

    RouteBuilder endpoint(Endpoint endpoint);

    /** Adds the route; a route registered again under the same pattern gets the new endpoints after its own. */
    void register();
}
