package org.quarkword.front.runtime;

import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

@Recorder
public class FrontRecorder {

    /** The catch-all route's handler, re-routing front-end requests to {@code entryPath}. */
    public Handler<RoutingContext> handler(String entryPath, String nonApplicationRoot) {
        return new FrontRoute(entryPath, nonApplicationRoot);
    }
}
