package org.quarkpress.taxonomy.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.TaxonomyDefinition;

/** The taxonomy registry as JSON text, the form in which the build hands it to the running application. */
public final class TaxonomyJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaxonomyJson() {}

    public static String write(List<TaxonomyDefinition> taxonomies) {
        ArrayNode list = MAPPER.createArrayNode();
        for (TaxonomyDefinition taxonomy : taxonomies) {
            ObjectNode node = list.addObject();
            node.put("key", taxonomy.key());
            ArrayNode types = node.putArray("objectTypes");
            taxonomy.objectTypes().forEach(types::add);
            writeMap(node.putObject("labels"), taxonomy.labels());
            node.put("description", taxonomy.description());
            node.put("public", taxonomy.publicly());
            node.put("publiclyQueryable", taxonomy.publiclyQueryable());
            node.put("hierarchical", taxonomy.hierarchical());
            node.put("showUi", taxonomy.showUi());
            node.put("showInMenu", taxonomy.showInMenu());
            node.put("showInNavMenus", taxonomy.showInNavMenus());
            node.put("showTagcloud", taxonomy.showTagcloud());
            node.put("showInQuickEdit", taxonomy.showInQuickEdit());
            node.put("showAdminColumn", taxonomy.showAdminColumn());
            writeMap(node.putObject("capabilities"), taxonomy.capabilities());
            node.put("showInRest", taxonomy.showInRest());
            taxonomy.restBase().ifPresent(base -> node.put("restBase", base));
            taxonomy.defaultTerm()
                    .ifPresent(term -> node.putObject("defaultTerm")
                            .put("name", term.name())
                            .put("slug", term.slug())
                            .put("description", term.description()));
            node.put("sort", taxonomy.sort());
            node.put("builtIn", taxonomy.builtIn());
        }
        return list.toString();
    }

    public static List<TaxonomyDefinition> read(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the recorded taxonomy registry is not JSON", e);
        }
        List<TaxonomyDefinition> taxonomies = new ArrayList<>();
        for (JsonNode node : root) {
            List<String> types = new ArrayList<>();
            node.get("objectTypes").forEach(type -> types.add(type.textValue()));
            Optional<TaxonomyDefinition.DefaultTerm> defaultTerm = Optional.ofNullable(node.get("defaultTerm"))
                    .map(term -> new TaxonomyDefinition.DefaultTerm(
                            term.get("name").textValue(),
                            term.get("slug").textValue(),
                            term.get("description").textValue()));
            taxonomies.add(new TaxonomyDefinition(
                    node.get("key").textValue(),
                    types,
                    readMap(node.get("labels")),
                    node.get("description").textValue(),
                    node.get("public").booleanValue(),
                    node.get("publiclyQueryable").booleanValue(),
                    node.get("hierarchical").booleanValue(),
                    node.get("showUi").booleanValue(),
                    node.get("showInMenu").booleanValue(),
                    node.get("showInNavMenus").booleanValue(),
                    node.get("showTagcloud").booleanValue(),
                    node.get("showInQuickEdit").booleanValue(),
                    node.get("showAdminColumn").booleanValue(),
                    readMap(node.get("capabilities")),
                    node.get("showInRest").booleanValue(),
                    Optional.ofNullable(node.get("restBase")).map(JsonNode::textValue),
                    defaultTerm,
                    node.get("sort").booleanValue(),
                    node.get("builtIn").booleanValue()));
        }
        return taxonomies;
    }

    private static void writeMap(ObjectNode node, Map<String, String> map) {
        map.forEach(node::put);
    }

    private static Map<String, String> readMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        node.properties()
                .forEach(entry -> map.put(entry.getKey(), entry.getValue().textValue()));
        return map;
    }
}
