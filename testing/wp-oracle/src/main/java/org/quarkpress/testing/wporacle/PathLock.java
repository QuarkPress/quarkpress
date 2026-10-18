package org.quarkpress.testing.wporacle;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One in-JVM lock per path. A {@code FileLock} only separates processes: a second thread of the same JVM that asks for
 * it gets an {@code OverlappingFileLockException}, so threads take this lock first and wait.
 */
public final class PathLock {

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private PathLock() {}

    /** The lock for {@code path}; the same instance for every call with the same absolute, normalized path. */
    public static ReentrantLock of(Path path) {
        return LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), key -> new ReentrantLock());
    }
}
