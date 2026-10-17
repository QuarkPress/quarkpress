package org.quarkpress.lib.wpkses;

/**
 * Overrides the verdict on one CSS declaration, like WordPress's {@code safecss_filter_attr_allow_css} filter.
 * {@code testString} is the declaration with its url(), gradient and allowed function texts removed. Not called for declarations whose property is not allowed.
 */
@FunctionalInterface
public interface CssAllowFilter {

    boolean allow(boolean allowed, String testString);
}
