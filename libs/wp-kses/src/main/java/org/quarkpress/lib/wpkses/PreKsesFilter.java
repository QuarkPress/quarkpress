package org.quarkpress.lib.wpkses;

import java.util.List;

/**
 * Rewrites content before it is tokenized, like a filter on WordPress's {@code pre_kses} hook. Runs on every kses
 * pass, including the nested passes for comments and block attribute values, after WordPress's default
 * pre-filters. Its output is not normalized again.
 *
 * <p>The order differs slightly from WordPress. There, the block-attribute filter removes itself while it runs and
 * then adds itself back at the end of priority 10, so from its second call on, a host filter added at priority 10
 * runs before it. Here, host filters always run after both default pre-filters.
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
