package org.quarkpress.lib.wpkses;

import java.util.List;

/**
 * Rewrites content before it is tokenized, like a filter on WordPress's {@code pre_kses} hook. Runs on every kses
 * pass, including the nested passes for comments and block attribute values, after WordPress's default
 * pre-filters. Its output is not normalized again.
 */
@FunctionalInterface
public interface PreKsesFilter {

    /**
     * @param content the normalized content
     * @param allowedHtml what the caller passed: a context or an explicit allowlist
     * @param protocols the protocols in effect for this call
     * @return the content to tokenize; must not be null
     */
    String filter(String content, AllowedHtml allowedHtml, List<String> protocols);
}
