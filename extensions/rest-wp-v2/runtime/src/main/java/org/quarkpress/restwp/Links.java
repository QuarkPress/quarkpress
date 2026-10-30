package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The links of a response, in the order they were added. Immutable: {@link #add} returns a new holder. */
public final class Links {

    /** One link: its relation, its target and its attributes in their order. */
    public record Link(String rel, String href, Map<String, JsonNode> attributes) {

        public Link {
            Objects.requireNonNull(rel, "rel");
            Objects.requireNonNull(href, "href");
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }

    private static final Links NONE = new Links(List.of());

    private final List<Link> links;

    private Links(List<Link> links) {
        this.links = links;
    }

    public static Links none() {
        return NONE;
    }

    public Links add(String rel, String href) {
        return add(rel, href, Map.of());
    }

    /** Attributes print before href, in the given order (e.g. embeddable, type, taxonomy). */
    public Links add(String rel, String href, Map<String, JsonNode> attributes) {
        List<Link> more = new ArrayList<>(links.size() + 1);
        more.addAll(links);
        more.add(new Link(rel, href, attributes));
        return new Links(List.copyOf(more));
    }

    public List<Link> all() {
        return links;
    }

    public boolean isEmpty() {
        return links.isEmpty();
    }
}
