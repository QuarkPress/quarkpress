package org.quarkpress.it.posts;

/** A role to give a user on the site. */
public record MembershipRequest(long user, String role) {}
