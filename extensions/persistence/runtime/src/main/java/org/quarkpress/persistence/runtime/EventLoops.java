package org.quarkpress.persistence.runtime;

import io.vertx.core.Context;

/** Vert.x is optional: this class is loaded only when Vert.x is on the class path. */
final class EventLoops {

    private EventLoops() {}

    static boolean onEventLoop() {
        return Context.isOnEventLoopThread();
    }
}
