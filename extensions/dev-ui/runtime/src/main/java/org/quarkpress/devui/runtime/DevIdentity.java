package org.quarkpress.devui.runtime;

import java.util.concurrent.Callable;

/** The identity a Dev UI call runs under: the system identity when the security module is installed. */
public interface DevIdentity {

    /** Runs {@code work} under this identity and returns its result. */
    <T> T call(Callable<T> work) throws Exception;
}
