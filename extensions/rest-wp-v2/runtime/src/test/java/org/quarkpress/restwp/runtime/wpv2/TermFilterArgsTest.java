package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TaxClause.Operator;
import org.quarkpress.posts.TaxClause.Relation;
import org.quarkpress.posts.TaxQuery;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.StubRequest;

/** The term filter arguments of a post collection and the term query they map to, as WordPress builds them. */
class TermFilterArgsTest {

    static final TaxonomyDefinition CATEGORY = TaxonomyDefinition.builder("category", List.of("post"))
            .hierarchical(true)
            .showInRest(true)
            .restBase("categories")
            .build();
    static final TaxonomyDefinition TAG = TaxonomyDefinition.builder("post_tag", List.of("post"))
            .showInRest(true)
            .restBase("tags")
            .build();
    static final List<TaxonomyDefinition> BOTH = List.of(CATEGORY, TAG);

    @Test
    void argumentsAsTheSavedOptionsBody() {
        List<Arg> args = TermFilterArgs.args(BOTH);
        assertThat(args.stream().map(Arg::name))
                .containsExactly("tax_relation", "categories", "categories_exclude", "tags", "tags_exclude");
        String list = "{\"title\":\"Term ID List\",\"description\":\"Match terms with the listed IDs.\",\"type\":"
                + "\"array\",\"items\":{\"type\":\"integer\"}}";
        String terms = "\"terms\":{\"description\":\"Term IDs.\",\"type\":\"array\",\"items\":{\"type\":\"integer\"},"
                + "\"default\":[]}";
        String children = "\"include_children\":{\"description\":\"Whether to include child terms in the terms limiting"
                + " the result set.\",\"type\":\"boolean\",\"default\":false}";
        String operator = "\"operator\":{\"description\":\"Whether items must be assigned all or any of the specified"
                + " terms.\",\"type\":\"string\",\"enum\":[\"AND\",\"OR\"],\"default\":\"OR\"}";
        assertThat(args.get(0).schema().toString())
                .isEqualTo("{\"description\":\"Limit result set based on relationship between multiple taxonomies.\","
                        + "\"type\":\"string\",\"enum\":[\"AND\",\"OR\"]}");
        assertThat(args.get(1).schema().toString())
                .isEqualTo(filter("items with specific terms", list, terms, children, operator));
        assertThat(args.get(2).schema().toString())
                .isEqualTo(filter("items except those with specific terms", list, terms, children));
        assertThat(args.get(3).schema().toString())
                .isEqualTo(filter("items with specific terms", list, terms, operator)
                        .replace("categories taxonomy", "tags taxonomy"));
        assertThat(args.get(4).schema().toString())
                .isEqualTo(filter("items except those with specific terms", list, terms)
                        .replace("categories taxonomy", "tags taxonomy"));
        for (Arg arg : args) {
            assertThat(arg.required()).isFalse();
            assertThat(arg.defaultValue()).isEmpty();
            assertThat(arg.phase()).isEqualTo(Arg.Phase.SANITIZE);
        }
        // no taxonomy, no tax_relation
        assertThat(TermFilterArgs.args(List.of())).isEmpty();
        assertThat(TermFilterArgs.args(List.of(TAG)).stream().map(Arg::name))
                .containsExactly("tax_relation", "tags", "tags_exclude");
    }

    private static String filter(String what, String list, String... properties) {
        return "{\"description\":\"Limit result set to " + what + " assigned in the categories taxonomy.\",\"type\":"
                + "[\"object\",\"array\"],\"oneOf\":[" + list
                + ",{\"title\":\"Term ID Taxonomy Query\",\"description\":"
                + "\"Perform an advanced term query.\",\"type\":\"object\",\"properties\":{"
                + String.join(",", properties) + "},\"additionalProperties\":false}]}";
    }

    @Test
    void wordPressMappings() {
        assertThat(query("{\"categories\":\"1,2\"}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("category", false, Operator.IN, 1, 2))));
        assertThat(query("{\"tags\":{\"terms\":\"4,5\",\"operator\":\"AND\"}}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("post_tag", false, Operator.AND, 4, 5))));
        assertThat(query("{\"categories\":\"3\",\"tags\":\"5\",\"tax_relation\":\"OR\"}"))
                .contains(new TaxQuery(
                        Relation.OR,
                        List.of(terms("category", false, Operator.IN, 3), terms("post_tag", false, Operator.IN, 5))));
        assertThat(query("{}", "standard", "aside"))
                .contains(new TaxQuery(
                        Relation.AND,
                        List.of(new TaxClause.Group(
                                Relation.OR,
                                List.of(
                                        new TaxClause.NotExists("post_format"),
                                        new TaxClause.Slugs("post_format", List.of("post-format-aside")))))));
    }

    @Test
    void includeAndExcludeFormsInRegistrationOrder() {
        // per taxonomy the include form, then the exclude form; the format group last; tax_relation=AND as given
        assertThat(query(
                        "{\"tags_exclude\":[7],\"categories_exclude\":{\"terms\":[3],\"include_children\":\"1\"},"
                                + "\"categories\":{\"terms\":[2,3],\"include_children\":true,\"operator\":\"OR\"},"
                                + "\"tags\":[6,6],\"tax_relation\":\"AND\"}",
                        "aside",
                        "link"))
                .contains(new TaxQuery(
                        Relation.AND,
                        List.of(
                                terms("category", true, Operator.IN, 2, 3),
                                terms("category", true, Operator.NOT_IN, 3),
                                terms("post_tag", false, Operator.IN, 6, 6),
                                terms("post_tag", false, Operator.NOT_IN, 7),
                                new TaxClause.Group(
                                        Relation.OR,
                                        List.of(new TaxClause.Slugs(
                                                "post_format", List.of("post-format-aside", "post-format-link")))))));
        // include_children stays off unless given true; a list form has none
        assertThat(query("{\"categories\":{\"terms\":[2],\"include_children\":\"0\"}}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("category", false, Operator.IN, 2))));
        // the query string's list forms: a space-separated text and PHP's indexed keys
        assertThat(query("{\"categories\":\"2 4\"}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("category", false, Operator.IN, 2, 4))));
        assertThat(query("{\"categories\":{\"0\":\"2\",\"1\":\"4\"}}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("category", false, Operator.IN, 2, 4))));
        assertThat(query("{\"categories\":{\"terms\":{\"0\":\"2\"}}}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("category", false, Operator.IN, 2))));
        // zeros and negative ids are the provider's to read
        assertThat(query("{\"categories\":\"0,-3\"}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("category", false, Operator.IN, 0, -3))));
        // only "AND" asks for all terms
        assertThat(query("{\"tags\":{\"terms\":[5],\"operator\":\"OR\"}}"))
                .contains(new TaxQuery(Relation.AND, List.of(terms("post_tag", false, Operator.IN, 5))));
    }

    @Test
    void emptyFormsAddNothing() {
        assertThat(query("{}")).isEmpty();
        assertThat(query("{\"categories\":[],\"tags_exclude\":{\"terms\":[]},\"tags\":{\"operator\":\"AND\"},"
                        + "\"categories_exclude\":{\"include_children\":true},\"tax_relation\":\"OR\"}"))
                .isEmpty();
        // a taxonomy the type does not have is not read
        assertThat(TermFilterArgs.query(request("{\"categories\":[3]}"), List.of(TAG), List.of()))
                .isEmpty();
        // the relation alone is no query; with one clause it is kept
        assertThat(query("{\"tags\":[5],\"tax_relation\":\"OR\"}"))
                .contains(new TaxQuery(Relation.OR, List.of(terms("post_tag", false, Operator.IN, 5))));
        // "standard" alone is the not-exists clause in its group
        assertThat(query("{}", "standard"))
                .contains(new TaxQuery(
                        Relation.AND,
                        List.of(new TaxClause.Group(Relation.OR, List.of(new TaxClause.NotExists("post_format"))))));
    }

    @Test
    void theExpandingFilterIsTheFirstThatListsTermsAndIncludesChildren() {
        assertThat(TermFilterArgs.expandingParam(
                        request("{\"categories\":{\"terms\":[2],\"include_children\":\"1\"},"
                                + "\"categories_exclude\":{\"terms\":[3],\"include_children\":true}}"),
                        BOTH))
                .contains("categories");
        // a filter without terms adds no clause, so it expands nothing
        for (String empty : List.of(
                "{\"include_children\":\"1\"}",
                "{\"terms\":[],\"include_children\":\"1\"}",
                "{\"terms\":\"\",\"include_children\":\"1\"}")) {
            assertThat(TermFilterArgs.expandingParam(
                            request("{\"categories\":" + empty + ",\"categories_exclude\":{\"terms\":[3],"
                                    + "\"include_children\":\"1\"}}"),
                            BOTH))
                    .as(empty)
                    .contains("categories_exclude");
        }
        // children off, a list form, or a flat taxonomy: none expands
        assertThat(TermFilterArgs.expandingParam(
                        request("{\"categories\":{\"terms\":[2],\"include_children\":\"0\"},"
                                + "\"categories_exclude\":[3],\"tags\":{\"terms\":[4],\"include_children\":true}}"),
                        BOTH))
                .isEmpty();
    }

    @Test
    void termListsAreBounded() {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            ids.append(i).append(',');
        }
        assertThat(query("{\"categories\":\"" + ids + "\"}").orElseThrow().clauses())
                .singleElement()
                .satisfies(clause ->
                        assertThat(((TaxClause.Terms) clause).termIds()).hasSize(1000));
        String tooMany = ids + "1001";
        for (String params :
                List.of("{\"categories\":\"" + tooMany + "\"}", "{\"tags_exclude\":{\"terms\":\"" + tooMany + "\"}}")) {
            String name = params.contains("tags_exclude") ? "tags_exclude" : "categories";
            assertThatThrownBy(() -> query(params)).isInstanceOfSatisfying(RestException.class, refused -> {
                assertThat(refused.error().code()).isEqualTo("rest_invalid_param");
                assertThat(refused.error().message()).isEqualTo("Invalid parameter(s): " + name);
                assertThat(refused.error().data().get("status").asInt()).isEqualTo(400);
                assertThat(refused.error()
                                .data()
                                .get("details")
                                .get(name)
                                .get("code")
                                .asText())
                        .isEqualTo("rest_too_many_items");
                assertThat(refused.error().data().get("params").get(name).asText())
                        .isEqualTo(name + " must contain at most 1000 items.");
            });
        }
    }

    private static TaxClause.Terms terms(String taxonomy, boolean children, Operator operator, long... ids) {
        return new TaxClause.Terms(
                taxonomy, java.util.Arrays.stream(ids).boxed().toList(), children, operator);
    }

    private static Optional<TaxQuery> query(String params, String... formats) {
        return TermFilterArgs.query(request(params), BOTH, List.of(formats));
    }

    private static StubRequest request(String params) {
        return new StubRequest((ObjectNode) json(params), "/wp/v2/posts", Set.of());
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
