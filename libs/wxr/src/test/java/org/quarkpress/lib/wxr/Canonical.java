package org.quarkpress.lib.wxr;

import java.util.Map;

/** Entries without their raw fields, for comparing with hand-built expectations. */
final class Canonical {

    private Canonical() {}

    static WxrEntry strip(WxrEntry entry) {
        return switch (entry) {
            case WxrAuthor a ->
                new WxrAuthor(a.id(), a.login(), a.email(), a.displayName(), a.firstName(), a.lastName());
            case WxrCategory c ->
                new WxrCategory(c.termId(), c.nicename(), c.parent(), c.name(), c.description(), c.termMeta());
            case WxrTag t -> new WxrTag(t.termId(), t.slug(), t.name(), t.description(), t.termMeta());
            case WxrTerm t ->
                new WxrTerm(t.termId(), t.taxonomy(), t.slug(), t.parent(), t.name(), t.description(), t.termMeta());
            case WxrItem i ->
                i.toBuilder()
                        .rawFields(Map.of())
                        .comments(i.comments().stream()
                                .map(c -> c.toBuilder().rawFields(Map.of()).build())
                                .toList())
                        .build();
        };
    }
}
