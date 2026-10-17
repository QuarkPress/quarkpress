package org.quarkpress.lib.wxr;

import java.util.List;
import java.util.Map;

/**
 * A {@code wp:term} of a custom taxonomy or a nav menu ({@code taxonomy} {@code "nav_menu"}, which WordPress
 * writes without parent, description or term meta). {@code parent} is raw text, as for categories.
 */
public record WxrTerm(
        Long termId,
        String taxonomy,
        String slug,
        String parent,
        String name,
        String description,
        List<WxrMeta> termMeta,
        Map<String, String> rawFields)
        implements WxrEntry {

    public WxrTerm {
        termMeta = Copies.list(termMeta);
        rawFields = Copies.map(rawFields);
    }

    public WxrTerm(
            Long termId,
            String taxonomy,
            String slug,
            String parent,
            String name,
            String description,
            List<WxrMeta> termMeta) {
        this(termId, taxonomy, slug, parent, name, description, termMeta, Map.of());
    }
}
