package org.quarkpress.lib.phpserialize;

/** PHP integer (64-bit), serialized as {@code i:123;}. */
public record PhpInt(long value) implements PhpValue {}
