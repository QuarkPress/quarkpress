package org.quarkpress.lib.wxr;

import java.util.List;
import java.util.Map;

/** A {@code wp:tag}. Tags have no parent. */
public record WxrTag(
        Long termId,
        String slug,
        String name,
        String description,
        List<WxrMeta> termMeta,
        Map<String, String> rawFields)
        implements WxrEntry {

    public WxrTag {
        termMeta = Copies.list(termMeta);
        rawFields = Copies.map(rawFields);
    }

    public WxrTag(Long termId, String slug, String name, String description, List<WxrMeta> termMeta) {
        this(termId, slug, name, description, termMeta, Map.of());
    }
}
