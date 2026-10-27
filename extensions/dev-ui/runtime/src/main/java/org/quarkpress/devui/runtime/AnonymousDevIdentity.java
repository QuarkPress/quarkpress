package org.quarkpress.devui.runtime;

import java.util.concurrent.Callable;

/** The Dev UI identity of an application without the security module: the work runs as it is. */
public class AnonymousDevIdentity implements DevIdentity {

    @Override
    public <T> T call(Callable<T> work) throws Exception {
        return work.call();
    }
}
