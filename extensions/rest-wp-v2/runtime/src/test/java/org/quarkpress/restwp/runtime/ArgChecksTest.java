package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.json;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.RestError;

/** Expected answers are WordPress 7.1.3's (WP_REST_Request's checks run on the same args and sources, PHP 8.4). */
class ArgChecksTest {

    @Test
    void missingParamsAreAListAndWinOverEverything() {
        RestError error =
                check(List.of(required("name"), perPage()), query("per_page=0")).orElseThrow();
        assertThat(error.toJson().toString())
                .isEqualTo(
                        "{\"code\":\"rest_missing_callback_param\","
                                + "\"message\":\"Missing parameter(s): name\",\"data\":{\"status\":400,\"params\":[\"name\"]}}");
        RestError two = check(List.of(required("a"), required("b")), jsonAndQuery("{\"a\":null}", ""))
                .orElseThrow();
        assertThat(two.message()).isEqualTo("Missing parameter(s): a, b");
        assertThat(two.status()).isEqualTo(400);
    }

    @Test
    void anArgsOwnCheckReplacesItsSchemaAndReportsNoDetails() {
        // WordPress's meta field: an is-array check, no sanitizer; a refusal is "Invalid parameter." without details
        Arg meta = Arg.of("meta", "object")
                .validate(PhpValues::isPhpArray)
                .sanitize(UnaryOperator.identity())
                .build();
        assertThat(check(List.of(meta), jsonAndQuery("{\"meta\":\"\"}", ""))
                        .orElseThrow()
                        .toJson()
                        .toString())
                .isEqualTo("{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): meta\","
                        + "\"data\":{\"status\":400,\"params\":{\"meta\":\"Invalid parameter.\"},\"details\":[]}}");
        assertThat(check(List.of(meta), jsonAndQuery("{\"meta\":[1]}", ""))).isEmpty();
        assertThat(check(List.of(meta), jsonAndQuery("{\"meta\":{\"x\":\"y\"}}", "")))
                .isEmpty();
        assertThat(check(List.of(meta), jsonAndQuery("{\"meta\":null}", ""))).isEmpty();
        // with a schema failure beside it, details hold only the schema's error
        RestError both = check(List.of(meta, perPage()), jsonAndQuery("{\"meta\":5}", "per_page=0"))
                .orElseThrow();
        assertThat(both.message()).isEqualTo("Invalid parameter(s): meta, per_page");
        assertThat(both.toJson().at("/data/details").toString()).startsWith("{\"per_page\":");
    }

    @Test
    void phaseOneHidesPhaseTwo() {
        RestError error =
                check(List.of(perPage(), order()), query("per_page=0&order=up")).orElseThrow();
        assertThat(error.toJson().toString())
                .isEqualTo(
                        "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): per_page\","
                                + "\"data\":{\"status\":400,\"params\":{\"per_page\":\"per_page must be between 1 (inclusive) and 100 (inclusive)\"},"
                                + "\"details\":{\"per_page\":{\"code\":\"rest_out_of_bounds\",\"message\":\"per_page must be between 1 (inclusive) and 100 (inclusive)\",\"data\":null}}}}");
        assertThat(check(List.of(perPage(), order()), query("order=up"))
                        .orElseThrow()
                        .toJson()
                        .at("/data/details/order/code")
                        .asText())
                .isEqualTo("rest_not_in_enum");
    }

    @Test
    void phaseOneReportsEveryFailingArgInDeclarationOrder() {
        Arg a = Arg.of("a", "integer").build();
        Arg b = Arg.of("b", "integer").build();
        RestError error = check(List.of(a, b), query("b=x&a=y")).orElseThrow();
        assertThat(error.message()).isEqualTo("Invalid parameter(s): a, b");
        assertThat(error.toJson().at("/data/details/a").toString())
                .isEqualTo("{\"code\":\"rest_invalid_type\",\"message\":\"a is not of type integer.\","
                        + "\"data\":{\"param\":\"a\"}}");
    }

    @Test
    void phaseTwoChecksShadowedValuesInSourceOrder() {
        // JSON {"name":"ok"} and query name[]=x for a SANITIZE string arg "name": the query value fails
        assertThat(check(List.of(sanitizeOnlyString("name")), jsonAndQuery("{\"name\":\"ok\"}", "name[]=x"))
                        .orElseThrow()
                        .toJson()
                        .at("/data/params/name")
                        .asText())
                .isEqualTo("name is not of type string.");
        // names in the order the sources hold them, JSON first; a later failure of the same name keeps its place
        // and replaces its message
        Arg a = Arg.of("a", "string").enumOf("z").phase(Arg.Phase.SANITIZE).build();
        Arg c = Arg.of("c", "integer").phase(Arg.Phase.SANITIZE).build();
        RestError error =
                check(List.of(c, a), jsonAndQuery("{\"a\":\"x\"}", "c=q&a[]=y")).orElseThrow();
        assertThat(error.message()).isEqualTo("Invalid parameter(s): a, c");
        assertThat(error.toJson().at("/data/params").toString())
                .isEqualTo("{\"a\":\"a is not of type string.\",\"c\":\"c is not of type integer.\"}");
        // a JSON null is sanitized too, and fails even though the query supplies the effective value
        Arg n = Arg.of("n", "integer").phase(Arg.Phase.SANITIZE).required().build();
        assertThat(check(List.of(n), jsonAndQuery("{\"n\":null}", "n=4"))
                        .orElseThrow()
                        .message())
                .isEqualTo("Invalid parameter(s): n");
    }

    @Test
    void anArgWithItsOwnSanitizerIsNotTypeCheckedInPhaseTwo() {
        // Part D 8: POST /tags?name[]=x with JSON {"name":"d08-ok"} created the tag (201): the term "name" arg carries
        // WordPress's own sanitizer (text-field sanitization), which replaces the type check of phase 2, so the
        // shadowed query value is sanitized, not reported
        assertThat(check(List.of(stringWithOwnSanitizer("name")), jsonAndQuery("{\"name\":\"ok\"}", "name[]=x")))
                .isEmpty();
        ParamSources sources = jsonAndQuery("{\"name\":\" a <b>ok</b> \"}", "");
        assertThat(ArgChecks.check(List.of(stringWithOwnSanitizer("name")), sources))
                .isEmpty();
        assertThat(sources.json().get("name")).isEqualTo(TextNode.valueOf("a ok"));
    }

    @Test
    void valuesAreReplacedByTheirSanitizedForms() {
        // a phase-1 arg's shadowed value is sanitized without a check: "abc" becomes 0 and is never used
        ParamSources sources = jsonAndQuery("{\"per_page\":5}", "per_page=abc&include=1,2");
        assertThat(ArgChecks.check(List.of(perPage(), includeArg()), sources)).isEmpty();
        assertThat(sources.json().get("per_page")).isEqualTo(IntNode.valueOf(5));
        assertThat(sources.query().get("per_page")).isEqualTo(IntNode.valueOf(0));
        assertThat(sources.query().get("include").toString()).isEqualTo("[1,2]");
        assertThat(sources.get("include").orElseThrow().toString()).isEqualTo("[1,2]");
        // a JSON null of a phase-1 arg is skipped by the check and then sanitized to 0, which becomes the value
        ParamSources nulled = jsonAndQuery("{\"per_page\":null}", "");
        nulled = withDefaults(List.of(perPage()), nulled);
        assertThat(ArgChecks.check(List.of(perPage()), nulled)).isEmpty();
        assertThat(nulled.get("per_page")).contains(IntNode.valueOf(0));
        // an untyped arg without a sanitizer is left alone; unknown parameters are ignored
        ParamSources untyped = jsonAndQuery("", "a[]=x&zzz=1");
        assertThat(ArgChecks.check(
                        List.of(Arg.of("a", null).phase(Arg.Phase.SANITIZE).build()), untyped))
                .isEmpty();
        assertThat(untyped.query().toString()).isEqualTo("{\"a\":{\"0\":\"x\"},\"zzz\":\"1\"}");
    }

    @Test
    void hostileValuesGiveWordPressErrors() {
        assertThat(code(check(List.of(perPage()), query("per_page=1e400")))).isEqualTo("rest_invalid_param");
        assertThat(check(List.of(perPage()), query("per_page=1e400"))
                        .orElseThrow()
                        .toJson()
                        .at("/data/details/per_page/code")
                        .asText())
                .isEqualTo("rest_out_of_bounds");
        assertThat(code(check(List.of(page()), query("page=-1")))).isEqualTo("rest_invalid_param");
        assertThat(check(List.of(page()), query("page=-1"))
                        .orElseThrow()
                        .toJson()
                        .at("/data/params/page")
                        .asText())
                .isEqualTo("page must be greater than or equal to 1");
        assertThat(check(List.of(includeArg()), query("include[a]=1"))
                        .orElseThrow()
                        .toJson()
                        .at("/data/params/include")
                        .asText())
                .isEqualTo("include is not of type array.");
    }

    @Test
    void aFractionIsCheckedAgainstTheBoundsBeforeItsType() {
        assertThat(check(List.of(perPage()), query("per_page=0.5"))
                        .orElseThrow()
                        .toJson()
                        .toString())
                .isEqualTo(
                        "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): per_page\","
                                + "\"data\":{\"status\":400,\"params\":{\"per_page\":\"per_page must be between 1 (inclusive)"
                                + " and 100 (inclusive)\"},\"details\":{\"per_page\":{\"code\":\"rest_out_of_bounds\","
                                + "\"message\":\"per_page must be between 1 (inclusive) and 100 (inclusive)\",\"data\":null}}}}");
    }

    @Test
    void listsThatSanitizingMakesDuplicateFailInEitherPhase() {
        for (Arg.Phase phase : Arg.Phase.values()) {
            Arg ids = Arg.of("ids", "array")
                    .items(schema("{\"type\":\"integer\"}"))
                    .phase(phase)
                    .build();
            ids.schema().put("uniqueItems", true);
            RestError error = check(List.of(ids), query("ids[]=1&ids[]=01")).orElseThrow();
            assertThat(error.toJson().toString())
                    .isEqualTo("{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): ids\","
                            + "\"data\":{\"status\":400,\"params\":{\"ids\":\"ids has duplicate items.\"},"
                            + "\"details\":{\"ids\":{\"code\":\"rest_duplicate_items\","
                            + "\"message\":\"ids has duplicate items.\",\"data\":null}}}}");
            assertThat(check(List.of(ids), query("ids[]=1&ids[]=2"))).isEmpty();
        }
    }

    @Test
    void emptyStringIsPresentAndDefaultsAreCheckedLikeOtherValues() {
        // "" satisfies required
        ParamSources sources = query("per_page=7&name=");
        assertThat(ArgChecks.check(List.of(perPage(), required("name")), sources))
                .isEmpty();
        assertThat(sources.get("name")).contains(TextNode.valueOf(""));
        // a default of "5" for an integer arg arrives as 5, whichever phase checks the arg
        for (Arg.Phase phase : Arg.Phase.values()) {
            Arg five = Arg.of("n", "integer")
                    .defaultValue(TextNode.valueOf("5"))
                    .phase(phase)
                    .build();
            ParamSources defaulted = withDefaults(List.of(five), query(""));
            assertThat(ArgChecks.check(List.of(five), defaulted)).isEmpty();
            assertThat(defaulted.get("n")).contains(IntNode.valueOf(5));
        }
        // WordPress 7.1.3 checks a default like any value: one outside the bounds is reported in either phase (the
        // behaviour reference's "never validated" does not hold; checked with WP_REST_Request on PHP 8.4)
        for (Arg.Phase phase : Arg.Phase.values()) {
            Arg low = Arg.of("n", "integer")
                    .minimum(1)
                    .defaultValue(IntNode.valueOf(0))
                    .phase(phase)
                    .build();
            assertThat(check(List.of(low), query(""))
                            .orElseThrow()
                            .toJson()
                            .at("/data/params/n")
                            .asText())
                    .isEqualTo("n must be greater than or equal to 1");
        }
    }

    @Test
    void manyValuesAreCheckedInLinearWork() {
        int n = 100_000;
        StringBuilder body = new StringBuilder("{\"include\":[");
        for (int i = 0; i < n; i++) {
            body.append(i == 0 ? "" : ",").append('"').append(i).append('"');
        }
        body.append("]}");
        ParamSources sources = jsonAndQuery(body.toString(), "");
        long started = System.nanoTime();
        assertThat(ArgChecks.check(List.of(includeArg()), sources)).isEmpty();
        assertThat(sources.json().get("include").size()).isEqualTo(n);
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
    }

    // helpers

    static Optional<RestError> check(List<Arg> args, ParamSources sources) {
        return ArgChecks.check(args, withDefaults(args, sources));
    }

    static String code(Optional<RestError> error) {
        return error.orElseThrow().code();
    }

    /** The sources with the args' defaults, as the server adds them for the matched endpoint. */
    static ParamSources withDefaults(List<Arg> args, ParamSources sources) {
        ObjectNode defaults = sources.defaults().deepCopy();
        for (Arg arg : args) {
            arg.defaultValue().ifPresent(value -> defaults.set(arg.name(), value.deepCopy()));
        }
        return new ParamSources(sources.json(), sources.body(), sources.query(), Map.of(), defaults);
    }

    static ParamSources query(String query) {
        return jsonAndQuery("", query);
    }

    static ParamSources jsonAndQuery(String json, String query) {
        ObjectNode body = json.isEmpty() ? RestJson.NODES.objectNode() : (ObjectNode) json(json);
        return new ParamSources(
                body, RestJson.NODES.objectNode(), PhpQuery.parse(query), Map.of(), RestJson.NODES.objectNode());
    }

    static Arg required(String name) {
        return Arg.of(name, "string").required().build();
    }

    static Arg perPage() {
        return Arg.of("per_page", "integer")
                .minimum(1)
                .maximum(100)
                .defaultValue(IntNode.valueOf(10))
                .build();
    }

    static Arg page() {
        return Arg.of("page", "integer")
                .minimum(1)
                .defaultValue(IntNode.valueOf(1))
                .build();
    }

    static Arg order() {
        return Arg.of("order", "string")
                .enumOf("asc", "desc")
                .defaultValue(TextNode.valueOf("asc"))
                .phase(Arg.Phase.SANITIZE)
                .build();
    }

    static Arg includeArg() {
        return Arg.of("include", "array")
                .items(schema("{\"type\":\"integer\"}"))
                .defaultValue(RestJson.NODES.arrayNode())
                .phase(Arg.Phase.SANITIZE)
                .build();
    }

    static Arg sanitizeOnlyString(String name) {
        return Arg.of(name, "string").phase(Arg.Phase.SANITIZE).build();
    }

    static Arg stringWithOwnSanitizer(String name) {
        return Arg.of(name, "string").sanitize(ArgChecksTest::textField).build();
    }

    private static JsonNode textField(JsonNode value) {
        return value.isTextual() ? TextNode.valueOf(TextFields.textField(value.textValue())) : value;
    }
}
