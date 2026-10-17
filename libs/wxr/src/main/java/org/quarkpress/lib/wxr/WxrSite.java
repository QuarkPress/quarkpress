package org.quarkpress.lib.wxr;

/**
 * Channel-level fields: raw strings, null when absent. {@link WxrReader#site()} returns the fields read so far;
 * WordPress writes {@code generator} after the terms, so call it again after the last entry to see it.
 */
public record WxrSite(
        String title,
        String link,
        String description,
        String pubDate,
        String language,
        String wxrVersion,
        String baseSiteUrl,
        String baseBlogUrl,
        String generator) {}
