package org.quarkpress.multisite.runtime;

import io.vertx.core.Context;

/** Where the calling thread runs. */
final class EventLoops {

    private EventLoops() {}

    static boolean onEventLoop() {
        return Context.isOnEventLoopThread();
    }
}
