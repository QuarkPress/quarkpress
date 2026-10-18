package org.quarkpress.it.multisite;

/** What a request sees of its site. */
public record SiteProbe(boolean resolved, Long id, String domain, String path, String status, String redirect) {}
