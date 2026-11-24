package org.quarkword.security;

/** An object id that resolves to nothing: every meta capability that needs an object is refused for it. */
public record MissingObject(long id) implements CapObject {}
