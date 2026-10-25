package org.quarkpress.scheduling.runtime;

/** The batch size the jobs read a site's rows by: the configured one, where a value below 1 counts as 1. */
public final class Batch {

    private Batch() {}

    /** The page size for a configured {@code quarkpress.scheduling.batch-size}: at least 1. */
    public static int size(int configured) {
        return Math.max(1, configured);
    }
}
