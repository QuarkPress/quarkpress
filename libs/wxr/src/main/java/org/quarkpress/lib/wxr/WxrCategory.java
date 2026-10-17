package org.quarkpress.lib.wxr;

import java.util.List;
import java.util.Map;

/**
 * A {@code wp:category}. {@code parent} is the raw text: the parent's slug from WXR 1.1 on, its name in 1.0;
 * resolve by slug first, then by name. Empty for a top-level category.
 */
public record WxrCategory(
        Long termId,
        String nicename,
        String parent,
        String name,
        String description,
        List<WxrMeta> termMeta,
        Map<String, String> rawFields)
        implements WxrEntry {

    public WxrCategory {
        termMeta = Copies.list(termMeta);
        rawFields = Copies.map(rawFields);
    }

    public WxrCategory(
            Long termId, String nicename, String parent, String name, String description, List<WxrMeta> termMeta) {
        this(termId, nicename, parent, name, description, termMeta, Map.of());
    }
}
