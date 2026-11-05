package org.quarkpress.media;

/** How {@link MediaStorage#put} treats a key that already holds a file. */
public enum WriteMode {
    /**
     * Store the file only when the key is free: when it holds a file, {@link KeyTakenException} is thrown and that
     * file is left as it is, even when two writers race for the same key.
     */
    CREATE_NEW,
    /** Store the file, replacing what the key holds in one step: a reader sees the old file or the new one. */
    REPLACE
}
