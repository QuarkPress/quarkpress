package org.quarkpress.security;

/**
 * The object of a meta capability check (WordPress's object id argument): a post, a term or a user described by the
 * module that owns it, or an id that does not resolve to anything.
 */
public sealed interface CapObject permits PostFacts, TermFacts, UserTarget, MissingObject {}
