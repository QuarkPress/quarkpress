package org.quarkpress.posts.runtime;

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
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;

/** The registries as JSON text, the form in which the build hands them to the running application. */
public final class RegistryJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RegistryJson() {}

    public static String writeTypes(List<PostTypeDefinition> types) {
        ArrayNode list = MAPPER.createArrayNode();
        for (PostTypeDefinition type : types) {
            ObjectNode node = list.addObject();
            node.put("key", type.key());
            writeMap(node.putObject("labels"), type.labels());
            node.put("description", type.description());
            node.put("public", type.publicly());
            node.put("hierarchical", type.hierarchical());
            node.put("excludeFromSearch", type.excludeFromSearch());
            node.put("publiclyQueryable", type.publiclyQueryable());
            node.put("embeddable", type.embeddable());
            node.put("showUi", type.showUi());
            node.put("showInMenu", type.showInMenu());
            node.put("showInNavMenus", type.showInNavMenus());
            node.put("showInAdminBar", type.showInAdminBar());
            node.put("capabilityType", type.capabilityType());
            node.put("capabilityTypePlural", type.capabilityTypePlural());
            writeMap(node.putObject("capabilities"), type.capabilities());
            node.put("mapMetaCap", type.mapMetaCap());
            ArrayNode supports = node.putArray("supports");
            type.supports().forEach(supports::add);
            node.put("hasArchive", type.hasArchive());
            node.put("showInRest", type.showInRest());
            type.restBase().ifPresent(base -> node.put("restBase", base));
            node.put("builtIn", type.builtIn());
        }
        return list.toString();
    }

    public static List<PostTypeDefinition> readTypes(String json) {
        List<PostTypeDefinition> types = new ArrayList<>();
        for (JsonNode node : read(json)) {
            List<String> supports = new ArrayList<>();
            node.get("supports").forEach(feature -> supports.add(feature.textValue()));
            types.add(new PostTypeDefinition(
                    node.get("key").textValue(),
                    readMap(node.get("labels")),
                    node.get("description").textValue(),
                    node.get("public").booleanValue(),
                    node.get("hierarchical").booleanValue(),
                    node.get("excludeFromSearch").booleanValue(),
                    node.get("publiclyQueryable").booleanValue(),
                    node.get("embeddable").booleanValue(),
                    node.get("showUi").booleanValue(),
                    node.get("showInMenu").booleanValue(),
                    node.get("showInNavMenus").booleanValue(),
                    node.get("showInAdminBar").booleanValue(),
                    node.get("capabilityType").textValue(),
                    node.get("capabilityTypePlural").textValue(),
                    readMap(node.get("capabilities")),
                    node.get("mapMetaCap").booleanValue(),
                    supports,
                    node.get("hasArchive").booleanValue(),
                    node.get("showInRest").booleanValue(),
                    Optional.ofNullable(node.get("restBase")).map(JsonNode::textValue),
                    node.get("builtIn").booleanValue()));
        }
        return types;
    }

    public static String writeStatuses(List<PostStatusDefinition> statuses) {
        ArrayNode list = MAPPER.createArrayNode();
        for (PostStatusDefinition status : statuses) {
            list.addObject()
                    .put("name", status.name())
                    .put("label", status.label())
                    .put("labelCountSingular", status.labelCountSingular())
                    .put("labelCountPlural", status.labelCountPlural())
                    .put("public", status.publicly())
                    .put("protected", status.protectedStatus())
                    .put("private", status.privateStatus())
                    .put("internal", status.internal())
                    .put("publiclyQueryable", status.publiclyQueryable())
                    .put("excludeFromSearch", status.excludeFromSearch())
                    .put("showInAdminAllList", status.showInAdminAllList())
                    .put("showInAdminStatusList", status.showInAdminStatusList())
                    .put("dateFloating", status.dateFloating())
                    .put("builtIn", status.builtIn());
        }
        return list.toString();
    }

    public static List<PostStatusDefinition> readStatuses(String json) {
        List<PostStatusDefinition> statuses = new ArrayList<>();
        for (JsonNode node : read(json)) {
            statuses.add(new PostStatusDefinition(
                    node.get("name").textValue(),
                    node.get("label").textValue(),
                    node.get("labelCountSingular").textValue(),
                    node.get("labelCountPlural").textValue(),
                    node.get("public").booleanValue(),
                    node.get("protected").booleanValue(),
                    node.get("private").booleanValue(),
                    node.get("internal").booleanValue(),
                    node.get("publiclyQueryable").booleanValue(),
                    node.get("excludeFromSearch").booleanValue(),
                    node.get("showInAdminAllList").booleanValue(),
                    node.get("showInAdminStatusList").booleanValue(),
                    node.get("dateFloating").booleanValue(),
                    node.get("builtIn").booleanValue()));
        }
        return statuses;
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

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the recorded registry is not JSON", e);
        }
    }
}
