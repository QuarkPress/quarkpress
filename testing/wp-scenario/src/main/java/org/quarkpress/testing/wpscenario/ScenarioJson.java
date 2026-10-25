package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.quarkpress.testing.wporacle.OracleJson;

/**
 * The scenario format: {@code {"id", "rule", "variant", "ops": [...]}} with ops {@code create_site {domain, path,
 * title}}, {@code lookup {host, path}}, {@code set_flag {site, flag, value}} ({@code flag} one of archived, mature,
 * spam, deleted, public), {@code read_options {site, names}} ({@code names} a non-empty list of distinct option
 * names), {@code create_user {username, given_name, family_name}}, {@code add_user {user, site, role}},
 * {@code remove_user {user, site}}, {@code set_role {user, site, role}}, {@code add_cap {user, site, capability,
 * grant}}, {@code super_admin {user, grant}}, {@code network_flag {name, value}} ({@code add_new_users} or
 * {@code menu_plugins}), {@code site_setting {site, name, value}} ({@code link_manager_enabled}), {@code add_role
 * {site, role, name, capabilities}}, {@code role_cap {site, role, capability, grant}}, {@code remove_role {site,
 * role}} and {@code can {user, site, capability, target}}. Users count from 1 in creation order; 0 is a logged-out
 * visitor in {@code can}, and a target of 0 is no user. Post operations on the main site ({@code site_option},
 * {@code front_page}, {@code insert_post}, {@code update_post}, {@code publish_post}, {@code trash_post},
 * {@code untrash_post}, {@code delete_post}, {@code stick}, {@code query_posts}, {@code page_uri} and
 * {@code read_post} and {@code update_post_meta}) are read and written by {@link PostScenarioJson}, and term
 * operations on the main site
 * ({@code insert_term}, {@code update_term}, {@code delete_term}, {@code set_post_terms}, {@code remove_post_terms},
 * {@code post_terms}, {@code query_terms}, {@code get_term} and {@code term_exists}) by {@link TermScenarioJson}, and
 * revision operations ({@code autosave}, {@code restore}, {@code delete_revision}, {@code revisions},
 * {@code autosave_of} and {@code revision_count}) by {@link RevisionScenarioJson}, and scheduling operations
 * ({@code run_publish_check}, {@code run_trash_purge}, {@code run_auto_draft_purge} and the fixture
 * {@code force_date}) by {@link SchedulingScenarioJson}. A scenario may carry {@code revisions_keep}, a whole number.
 * Unknown keys, duplicate keys and trailing content are refused.
 */
public final class ScenarioJson {

    /** A copy of the shared mapper that reads strictly: no trailing tokens, no duplicate keys. */
    private static final ObjectMapper STRICT_READER = OracleJson.MAPPER
            .copy()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ScenarioJson() {}

    public static Scenario parse(String json) {
        JsonNode node;
        try {
            node = STRICT_READER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("scenario JSON is not valid: " + e.getOriginalMessage(), e);
        }
        return parse(node, false);
    }

    public static Scenario parse(JsonNode node, boolean generated) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("a scenario must be a JSON object");
        }
        String id = text(node, "id", "(no id)");
        onlyKeys(node, id, "id", "rule", "variant", "ops", "revisions_keep");
        List<ScenarioOp> ops = new ArrayList<>();
        JsonNode list = node.path("ops");
        if (!list.isArray()) {
            throw new IllegalArgumentException("scenario " + id + ": 'ops' must be a list");
        }
        for (JsonNode op : list) {
            ops.add(op(op, id));
        }
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("scenario " + id + " has no ops");
        }
        return new Scenario(
                id,
                text(node, "rule", id),
                NetworkVariant.named(text(node, "variant", id)),
                ops,
                generated,
                node.has("revisions_keep") ? OptionalInt.of(number(node, "revisions_keep", id)) : OptionalInt.empty());
    }

    public static ObjectNode toJson(Scenario scenario) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        node.put("id", scenario.id());
        node.put("rule", scenario.rule());
        node.put("variant", scenario.variant().name());
        scenario.revisionsKeep().ifPresent(keep -> node.put("revisions_keep", keep));
        ArrayNode ops = node.putArray("ops");
        for (ScenarioOp op : scenario.ops()) {
            ObjectNode out = ops.addObject().put("op", op.kind());
            switch (op) {
                case ScenarioOp.CreateSite create ->
                    out.put("domain", create.domain())
                            .put("path", create.path())
                            .put("title", create.title());
                case ScenarioOp.Lookup lookup -> out.put("host", lookup.host()).put("path", lookup.path());
                case ScenarioOp.SetFlag flag ->
                    out.put("site", flag.site())
                            .put("flag", flag.flag().wordpressName())
                            .put("value", flag.value());
                case ScenarioOp.ReadOptions read -> {
                    out.put("site", read.site());
                    ArrayNode names = out.putArray("names");
                    read.names().forEach(names::add);
                }
                case ScenarioOp.CreateUser user ->
                    out.put("username", user.username())
                            .put("given_name", user.givenName())
                            .put("family_name", user.familyName());
                case ScenarioOp.AddUser add ->
                    out.put("user", add.user()).put("site", add.site()).put("role", add.role());
                case ScenarioOp.RemoveUser remove ->
                    out.put("user", remove.user()).put("site", remove.site());
                case ScenarioOp.SetRole set ->
                    out.put("user", set.user()).put("site", set.site()).put("role", set.role());
                case ScenarioOp.AddCap cap ->
                    out.put("user", cap.user())
                            .put("site", cap.site())
                            .put("capability", cap.capability())
                            .put("grant", cap.grant());
                case ScenarioOp.SuperAdmin admin ->
                    out.put("user", admin.user()).put("grant", admin.grant());
                case ScenarioOp.NetworkFlag flag -> out.put("name", flag.name()).put("value", flag.value());
                case ScenarioOp.SiteSetting setting ->
                    out.put("site", setting.site()).put("name", setting.name()).put("value", setting.value());
                case ScenarioOp.AddRole role -> {
                    out.put("site", role.site()).put("role", role.role()).put("name", role.name());
                    ArrayNode capabilities = out.putArray("capabilities");
                    role.capabilities().forEach(capabilities::add);
                }
                case ScenarioOp.RoleCap role ->
                    out.put("site", role.site())
                            .put("role", role.role())
                            .put("capability", role.capability())
                            .put("grant", role.grant());
                case ScenarioOp.RemoveRole role -> out.put("site", role.site()).put("role", role.role());
                case ScenarioOp.Can can ->
                    out.put("user", can.user())
                            .put("site", can.site())
                            .put("capability", can.capability())
                            .put("target", can.target());
                case ScenarioOp.PostOp post -> PostScenarioJson.write(post, out);
            }
        }
        return node;
    }

    public static String write(Scenario scenario) {
        try {
            return OracleJson.MAPPER.writeValueAsString(toJson(scenario));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The variant as the PHP driver reads it. */
    public static ObjectNode variantJson(NetworkVariant variant) {
        return OracleJson.MAPPER
                .createObjectNode()
                .put("name", variant.name())
                .put("domain", variant.domain())
                .put("path", variant.path())
                .put("subdomain_install", variant.subdomainInstall())
                .put("path_segments", variant.pathSegments());
    }

    private static ScenarioOp op(JsonNode op, String id) {
        if (!op.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": every op must be an object");
        }
        String kind = text(op, "op", id);
        if (SchedulingScenarioJson.KINDS.contains(kind)) {
            return SchedulingScenarioJson.read(kind, op, id);
        }
        if (RevisionScenarioJson.KINDS.contains(kind)) {
            return RevisionScenarioJson.read(kind, op, id);
        }
        if (TermScenarioJson.KINDS.contains(kind)) {
            return TermScenarioJson.read(kind, op, id);
        }
        if (PostScenarioJson.KINDS.contains(kind)) {
            return PostScenarioJson.read(kind, op, id);
        }
        switch (kind) {
            case "create_site" -> onlyKeys(op, id, "op", "domain", "path", "title");
            case "lookup" -> onlyKeys(op, id, "op", "host", "path");
            case "set_flag" -> onlyKeys(op, id, "op", "site", "flag", "value");
            case "read_options" -> onlyKeys(op, id, "op", "site", "names");
            case "create_user" -> onlyKeys(op, id, "op", "username", "given_name", "family_name");
            case "add_user", "set_role" -> onlyKeys(op, id, "op", "user", "site", "role");
            case "remove_user" -> onlyKeys(op, id, "op", "user", "site");
            case "add_cap" -> onlyKeys(op, id, "op", "user", "site", "capability", "grant");
            case "super_admin" -> onlyKeys(op, id, "op", "user", "grant");
            case "network_flag" -> onlyKeys(op, id, "op", "name", "value");
            case "site_setting" -> onlyKeys(op, id, "op", "site", "name", "value");
            case "add_role" -> onlyKeys(op, id, "op", "site", "role", "name", "capabilities");
            case "role_cap" -> onlyKeys(op, id, "op", "site", "role", "capability", "grant");
            case "remove_role" -> onlyKeys(op, id, "op", "site", "role");
            case "can" -> onlyKeys(op, id, "op", "user", "site", "capability", "target");
            default -> {}
        }
        return switch (kind) {
            case "create_site" ->
                new ScenarioOp.CreateSite(text(op, "domain", id), text(op, "path", id), text(op, "title", id));
            case "lookup" -> new ScenarioOp.Lookup(text(op, "host", id), text(op, "path", id));
            case "set_flag" ->
                new ScenarioOp.SetFlag(
                        number(op, "site", id), ScenarioOp.Flag.named(text(op, "flag", id)), bool(op, "value", id));
            case "read_options" -> {
                JsonNode list = op.get("names");
                if (list == null || !list.isArray() || list.isEmpty()) {
                    throw new IllegalArgumentException("scenario " + id + ": 'names' must be a non-empty list");
                }
                List<String> names = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (JsonNode name : list) {
                    if (!name.isTextual()) {
                        throw new IllegalArgumentException("scenario " + id + ": 'names' holds only text");
                    }
                    if (!seen.add(name.textValue())) {
                        throw new IllegalArgumentException(
                                "scenario " + id + ": 'names' lists '" + name.textValue() + "' twice");
                    }
                    names.add(name.textValue());
                }
                yield new ScenarioOp.ReadOptions(number(op, "site", id), names);
            }
            case "create_user" ->
                new ScenarioOp.CreateUser(
                        text(op, "username", id), text(op, "given_name", id), text(op, "family_name", id));
            case "add_user" ->
                new ScenarioOp.AddUser(number(op, "user", id), number(op, "site", id), text(op, "role", id));
            case "remove_user" -> new ScenarioOp.RemoveUser(number(op, "user", id), number(op, "site", id));
            case "set_role" ->
                new ScenarioOp.SetRole(number(op, "user", id), number(op, "site", id), text(op, "role", id));
            case "add_cap" ->
                new ScenarioOp.AddCap(
                        number(op, "user", id),
                        number(op, "site", id),
                        text(op, "capability", id),
                        bool(op, "grant", id));
            case "super_admin" -> new ScenarioOp.SuperAdmin(number(op, "user", id), bool(op, "grant", id));
            case "network_flag" -> new ScenarioOp.NetworkFlag(text(op, "name", id), bool(op, "value", id));
            case "site_setting" ->
                new ScenarioOp.SiteSetting(number(op, "site", id), text(op, "name", id), bool(op, "value", id));
            case "add_role" ->
                new ScenarioOp.AddRole(
                        number(op, "site", id),
                        text(op, "role", id),
                        text(op, "name", id),
                        texts(op, "capabilities", id));
            case "role_cap" ->
                new ScenarioOp.RoleCap(
                        number(op, "site", id),
                        text(op, "role", id),
                        text(op, "capability", id),
                        bool(op, "grant", id));
            case "remove_role" -> new ScenarioOp.RemoveRole(number(op, "site", id), text(op, "role", id));
            case "can" ->
                new ScenarioOp.Can(
                        number(op, "user", id),
                        number(op, "site", id),
                        text(op, "capability", id),
                        number(op, "target", id));
            default -> throw new IllegalArgumentException("scenario " + id + ": unknown op '" + kind + "'");
        };
    }

    static List<String> texts(JsonNode node, String field, String id) {
        JsonNode list = node.get(field);
        if (list == null || !list.isArray()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + field + "' must be a list");
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode element : list) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("scenario " + id + ": '" + field + "' holds only text");
            }
            texts.add(element.textValue());
        }
        return texts;
    }

    static void onlyKeys(JsonNode node, String id, String... allowed) {
        List<String> known = List.of(allowed);
        for (java.util.Iterator<String> keys = node.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            if (!known.contains(key)) {
                throw new IllegalArgumentException(
                        "scenario " + id + ": unknown key '" + key + "'; known: " + String.join(", ", known));
            }
        }
    }

    static String text(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + field + "' must be text");
        }
        return value.textValue();
    }

    static int number(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + field + "' must be a whole number");
        }
        return value.intValue();
    }

    static boolean bool(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + field + "' must be true or false");
        }
        return value.booleanValue();
    }
}
