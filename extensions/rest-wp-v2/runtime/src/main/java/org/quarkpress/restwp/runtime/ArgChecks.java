package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.RestError;

/**
 * WordPress's checks of an endpoint's arguments, in its order.
 *
 * <ol>
 *   <li>Every required argument without a value (absent, or JSON null in every source) is listed: 400
 *       {@code rest_missing_callback_param}. An empty string is a value.
 *   <li>Phase 1: each {@link Arg.Phase#VALIDATE} argument's value (by precedence, a default included) is checked
 *       against its schema, in declaration order; all failures are reported together.
 *   <li>Phase 2, only when phase 1 found nothing: every source's value of every argument (JSON, form body, query, URL,
 *       defaults, each in its own order; JSON nulls and values another source shadows included) is sanitized and
 *       replaced by its sanitized form. An argument's own sanitizer is applied alone. A typed argument without one is
 *       sanitized by its schema (a list that sanitizing leaves with duplicates under {@code uniqueItems} fails), and a
 *       {@link Arg.Phase#SANITIZE} argument's value is checked first; failures are reported in the order found, a
 *       later failure of the same name replacing the earlier message in place. An untyped argument without a
 *       sanitizer is left alone.
 * </ol>
 *
 * Failures answer 400 {@code rest_invalid_param} with each argument's message under {@code params} and its error
 * under {@code details}.
 */
public final class ArgChecks {

    private ArgChecks() {}

    /** The first failing step's error, empty when the arguments pass; the sources then hold sanitized values. */
    public static Optional<RestError> check(List<Arg> args, ParamSources sources) {
        List<String> missing = new ArrayList<>();
        for (Arg arg : args) {
            if (arg.required() && !sources.has(arg.name())) {
                missing.add(arg.name());
            }
        }
        if (!missing.isEmpty()) {
            ObjectNode data = RestJson.NODES.objectNode();
            data.put("status", 400);
            ArrayNode params = data.putArray("params");
            missing.forEach(params::add);
            return Optional.of(new RestError(
                    "rest_missing_callback_param",
                    "Missing parameter(s): " + String.join(", ", missing),
                    data,
                    List.of(),
                    List.of()));
        }
        Map<String, RestError> invalid = new LinkedHashMap<>();
        for (Arg arg : args) {
            if (arg.phase() == Arg.Phase.VALIDATE) {
                Optional<JsonNode> value = sources.get(arg.name());
                if (value.isPresent()) {
                    SchemaValidator.validate(value.get(), arg.schema(), arg.name())
                            .ifPresent(error -> invalid.put(arg.name(), error));
                }
            }
        }
        if (invalid.isEmpty()) {
            sanitize(args, sources, invalid);
        }
        return invalid.isEmpty() ? Optional.empty() : Optional.of(invalidParams(invalid));
    }

    private static void sanitize(List<Arg> args, ParamSources sources, Map<String, RestError> invalid) {
        Map<String, Arg> byName = new LinkedHashMap<>();
        for (Arg arg : args) {
            byName.put(arg.name(), arg);
        }
        for (ObjectNode source : sources.order()) {
            List<Map.Entry<String, JsonNode>> replaced = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = source.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                Arg arg = byName.get(entry.getKey());
                if (arg == null) {
                    continue;
                }
                JsonNode value = entry.getValue();
                if (arg.sanitize().isPresent()) {
                    replaced.add(Map.entry(entry.getKey(), arg.sanitize().get().apply(value)));
                    continue;
                }
                if (!arg.schema().has("type")) {
                    continue;
                }
                if (arg.phase() == Arg.Phase.SANITIZE) {
                    Optional<RestError> error = SchemaValidator.validate(value, arg.schema(), arg.name());
                    if (error.isPresent()) {
                        invalid.put(arg.name(), error.get());
                        continue;
                    }
                }
                JsonNode sanitized = SchemaSanitizer.sanitize(value, arg.schema(), arg.name());
                Optional<RestError> duplicates = SchemaSanitizer.duplicates(sanitized, arg.schema(), arg.name());
                if (duplicates.isPresent()) {
                    invalid.put(arg.name(), duplicates.get());
                    continue;
                }
                replaced.add(Map.entry(entry.getKey(), sanitized));
            }
            for (Map.Entry<String, JsonNode> entry : replaced) {
                source.set(entry.getKey(), entry.getValue());
            }
        }
    }

    private static RestError invalidParams(Map<String, RestError> invalid) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("status", 400);
        ObjectNode params = data.putObject("params");
        ObjectNode details = data.putObject("details");
        for (Map.Entry<String, RestError> entry : invalid.entrySet()) {
            params.put(entry.getKey(), entry.getValue().message());
            details.set(entry.getKey(), entry.getValue().toJson());
        }
        return new RestError(
                "rest_invalid_param",
                "Invalid parameter(s): " + String.join(", ", invalid.keySet()),
                data,
                List.of(),
                List.of());
    }
}
