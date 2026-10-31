package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * WordPress's {@code _embed}: for each item of a list answer (or the answer itself), each relation of its
 * {@code _links} that is asked for gets, under {@code _embedded}, one entry per link: the linked resource's data for
 * an embeddable link, {@code []} for any other. A relation none of whose entries holds anything is left out, and so is
 * {@code _embedded} when no relation is left. Each href is fetched once per answer; at most {@link #MAX_FETCHES}
 * different hrefs are fetched, later ones get {@code []}.
 */
public final class Embedder {

    /** The most different hrefs one answer fetches (WordPress has no bound). */
    public static final int MAX_FETCHES = 1000;

    private final EmbedChoice choice;
    private final Function<String, Optional<JsonNode>> fetch;
    private final int maxFetches;
    private final Set<String> listed;
    private final Map<String, JsonNode> cache = new HashMap<>();

    /**
     * @param fetch an href's data, empty when the href leads nowhere in this site's API
     */
    public Embedder(EmbedChoice choice, Function<String, Optional<JsonNode>> fetch) {
        this(choice, fetch, MAX_FETCHES);
    }

    Embedder(EmbedChoice choice, Function<String, Optional<JsonNode>> fetch, int maxFetches) {
        this.choice = choice;
        this.fetch = fetch;
        this.maxFetches = maxFetches;
        this.listed = new HashSet<>(choice.relations());
    }

    /** WordPress's reading of the query's {@code _embed}: see {@link #parse(JsonNode)}. */
    public static EmbedChoice parse(Optional<String> embedQuery) {
        return parse(embedQuery.<JsonNode>map(TextNode::valueOf).orElse(null));
    }

    /**
     * WordPress's reading of the query's {@code _embed}: absent embeds nothing; a value PHP counts as false,
     * {@code "true"} or {@code "1"} embeds every relation; text is split on runs of commas and whitespace, an array's
     * scalar items are taken as they are; a list left empty embeds every relation.
     */
    public static EmbedChoice parse(JsonNode value) {
        if (value == null || value.isNull()) {
            return EmbedChoice.NONE;
        }
        if (PhpValues.isEmpty(value)) {
            return EmbedChoice.ALL;
        }
        List<String> relations = new ArrayList<>();
        if (value.isContainerNode()) {
            value.forEach(item -> {
                if (item.isValueNode() && !item.isNull()) {
                    relations.add(item.asText());
                }
            });
        } else {
            String text = value.asText();
            if (text.equals("true") || text.equals("1")) {
                return EmbedChoice.ALL;
            }
            relations.addAll(PhpValues.parseList(text));
        }
        return relations.isEmpty() ? EmbedChoice.ALL : new EmbedChoice(EmbedChoice.Kind.THESE, relations);
    }

    /**
     * {@code data} with the asked-for links embedded: a list (or an object keyed only by integers, which PHP holds as
     * a list) item by item, any other value as a whole. {@code data} itself is not changed.
     */
    public JsonNode embed(JsonNode data) {
        if (choice.kind() == EmbedChoice.Kind.NONE) {
            return data;
        }
        if (data instanceof ArrayNode list) {
            ArrayNode items = RestJson.NODES.arrayNode(list.size());
            list.forEach(item -> items.add(embedLinks(item)));
            return items;
        }
        if (data instanceof ObjectNode object && PhpValues.hasIntegerKeys(object)) {
            ObjectNode items = RestJson.NODES.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> fields = object.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                items.set(field.getKey(), embedLinks(field.getValue()));
            }
            return items;
        }
        return embedLinks(data);
    }

    /** One item: its {@code _links} embedded under {@code _embedded} (set last, or in place of one it has). */
    private JsonNode embedLinks(JsonNode item) {
        if (!(item instanceof ObjectNode object) || !(object.get("_links") instanceof ObjectNode links)) {
            return item;
        }
        ObjectNode embedded = RestJson.NODES.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> relations = links.fields(); relations.hasNext(); ) {
            Map.Entry<String, JsonNode> relation = relations.next();
            if (choice.kind() == EmbedChoice.Kind.THESE && !listed.contains(relation.getKey())) {
                continue;
            }
            if (!relation.getValue().isContainerNode()) {
                continue;
            }
            ArrayNode embeds = RestJson.NODES.arrayNode();
            boolean any = false;
            for (JsonNode link : relation.getValue()) {
                JsonNode data = embeddable(link) ? fetched(link.get("href").textValue()) : RestJson.NODES.arrayNode();
                embeds.add(data);
                any |= !PhpValues.isEmpty(data);
            }
            if (any) {
                embedded.set(relation.getKey(), embeds);
            }
        }
        if (embedded.isEmpty()) {
            return item;
        }
        ObjectNode copy = RestJson.NODES.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> fields = object.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            copy.set(field.getKey(), field.getValue());
        }
        copy.set("_embedded", embedded);
        return copy;
    }

    /** A link that says it is embeddable (by PHP's truth) and has a textual href. */
    private static boolean embeddable(JsonNode link) {
        return link.isObject()
                && !PhpValues.isEmpty(link.get("embeddable"))
                && link.get("href") != null
                && link.get("href").isTextual();
    }

    /** The href's data, fetched once; {@code []} when it leads nowhere or the bound is reached. */
    private JsonNode fetched(String href) {
        JsonNode known = cache.get(href);
        if (known != null) {
            return known;
        }
        if (cache.size() >= maxFetches) {
            return RestJson.NODES.arrayNode();
        }
        Optional<JsonNode> data = fetch.apply(href);
        if (data.isEmpty()) {
            return RestJson.NODES.arrayNode();
        }
        cache.put(href, data.get());
        return data.get();
    }
}
