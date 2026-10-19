package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.quarkpress.core.RoleDefinition;

/**
 * Role tables: the default one built from WordPress's roles and registrations, and the stored form a site keeps once
 * its roles change: {@code {"<key>": {"name": "<display name>", "capabilities": {"<capability>": true|false}}}}, in
 * order.
 */
public final class RoleTables {

    private RoleTables() {}

    /**
     * WordPress's roles, then {@code registered} in key order, then each capability of {@code grants} (in name order)
     * appended to the roles that name it. The key super_admin is reserved (it is never a role). A key registered twice, or a grant to a role that is not in the table, is
     * refused with {@link IllegalArgumentException}.
     */
    public static List<RoleDefinition> defaults(
            List<RoleDefinition> builtIn, List<RoleDefinition> registered, Map<String, Set<String>> grants) {
        Map<String, RoleDefinition> table = new LinkedHashMap<>();
        for (RoleDefinition role : builtIn) {
            table.put(role.key(), role);
        }
        List<RoleDefinition> sorted = new ArrayList<>(registered);
        sorted.sort(Comparator.comparing(RoleDefinition::key));
        for (RoleDefinition role : sorted) {
            if (role.key().equals("super_admin")) {
                throw new IllegalArgumentException("the role key super_admin is reserved for network administrators");
            }
            if (table.putIfAbsent(role.key(), role) != null) {
                throw new IllegalArgumentException("the role " + role.key() + " is registered twice");
            }
        }
        for (Map.Entry<String, Set<String>> grant : new TreeMap<>(grants).entrySet()) {
            for (String key : new TreeSet<>(grant.getValue())) {
                RoleDefinition role = table.get(key);
                if (role == null) {
                    throw new IllegalArgumentException(
                            "the capability " + grant.getKey() + " is granted to the unknown role " + key);
                }
                table.put(key, role.withCapability(grant.getKey(), true));
            }
        }
        return List.copyOf(table.values());
    }

    public static Map<String, RoleDefinition> byKey(List<RoleDefinition> roles) {
        Map<String, RoleDefinition> byKey = new LinkedHashMap<>();
        for (RoleDefinition role : roles) {
            byKey.put(role.key(), role);
        }
        return byKey;
    }

    public static ObjectNode toJson(List<RoleDefinition> roles) {
        ObjectNode table = JsonNodeFactory.instance.objectNode();
        for (RoleDefinition role : roles) {
            ObjectNode entry = table.putObject(role.key());
            entry.put("name", role.displayName());
            ObjectNode capabilities = entry.putObject("capabilities");
            role.capabilities().forEach(capabilities::put);
        }
        return table;
    }

    /**
     * The roles of a stored table, in order. A value that is not an object holds no roles. Entries that are not
     * objects, or have an empty key, are skipped; a missing or non-text name is the key; capability values are read
     * as PHP reads them for truth (anything but a JSON object of capabilities counts as none).
     */
    public static List<RoleDefinition> fromJson(JsonNode stored) {
        List<RoleDefinition> roles = new ArrayList<>();
        if (stored == null || !stored.isObject()) {
            return roles;
        }
        for (Iterator<Map.Entry<String, JsonNode>> fields = stored.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode entry = field.getValue();
            if (field.getKey().isEmpty() || !entry.isObject()) {
                continue;
            }
            JsonNode name = entry.get("name");
            Map<String, Boolean> capabilities = new LinkedHashMap<>();
            JsonNode storedCapabilities = entry.get("capabilities");
            if (storedCapabilities != null && storedCapabilities.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> caps = storedCapabilities.fields(); caps.hasNext(); ) {
                    Map.Entry<String, JsonNode> cap = caps.next();
                    capabilities.put(cap.getKey(), PhpTruth.truthy(cap.getValue()));
                }
            }
            roles.add(new RoleDefinition(
                    field.getKey(),
                    name != null && name.isTextual() ? name.textValue() : field.getKey(),
                    capabilities));
        }
        return roles;
    }
}
