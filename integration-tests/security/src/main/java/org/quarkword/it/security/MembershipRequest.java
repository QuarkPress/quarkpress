package org.quarkword.it.security;

public record MembershipRequest(long site, long user, String role) {}
