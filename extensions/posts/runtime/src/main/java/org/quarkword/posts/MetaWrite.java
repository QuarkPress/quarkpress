package org.quarkword.posts;

/** The outcome of a write through {@link RegisteredMeta}. */
public enum MetaWrite {
    WRITTEN,
    UNCHANGED,
    DENIED
}
