package org.quarkpress.lib.wxr;

/**
 * A {@code category} element of an item: {@code domain} and {@code nicename} attributes as written (null when
 * absent; legacy files have bare categories and {@code domain="tag"}) and the element text as name.
 */
public record WxrItemTerm(String domain, String nicename, String name) {

    /** The taxonomy the importer files this under: {@code "tag"} becomes {@code "post_tag"}, no domain means category. */
    public String taxonomy() {
        if (domain == null) {
            return "category";
        }
        return "tag".equals(domain) ? "post_tag" : domain;
    }
}
