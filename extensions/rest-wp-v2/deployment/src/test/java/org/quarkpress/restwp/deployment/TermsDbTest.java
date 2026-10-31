package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.security.MetaCapRequest;
import org.quarkpress.security.MetaCapabilityFilter;
import org.quarkpress.security.TermFacts;
import org.quarkpress.taxonomy.runtime.TermEntity;
import org.quarkpress.taxonomy.runtime.TermRepository;

/**
 * The term routes of every taxonomy shown in REST (categories and tags): arguments, permissions, query, fields,
 * links, writes and their errors as WordPress answers them (Part D 3, 4, 7, 8, 14, 15, 23), and the site boundary: a
 * term or post of another site does not exist here. Members: an administrator, an editor, the author carol, the
 * contributor dave and the subscriber erin.
 */
class TermsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    TermsDbTest.class, StaleWriter.class, StaleRoute.class, RefusedTerms.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            // a public custom taxonomy, and one that is neither public nor publicly queryable; both
                            // shown in REST
                            context.produce(
                                    new TaxonomyBuildItem(TaxonomyDefinition.builder("qp_genre", List.of("post"))
                                            .showInRest(true)
                                            .restBase("genres")
                                            .build()));
                            context.produce(
                                    new TaxonomyBuildItem(TaxonomyDefinition.builder("qp_private", List.of("post"))
                                            .publicly(false)
                                            .showInRest(true)
                                            .restBase("private-terms")
                                            .build()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build());

    long administrator;
    long editor;
    long carol;
    long dave;
    long erin;
    AgroalDataSource data;

    /** A suffix that keeps this test's names apart from every other test's. */
    String u;

    /** The most entries TermsController takes in include, exclude or slug. */
    static final int MAX_LIST_ITEMS = 1000;

    @BeforeEach
    void members() {
        data = Jdbc.dataSource();
        administrator = TestData.memberOfMain(data, "administrator").value();
        editor = TestData.memberOfMain(data, "editor").value();
        carol = TestData.memberOfMain(data, "author").value();
        dave = TestData.memberOfMain(data, "contributor").value();
        erin = TestData.memberOfMain(data, "subscriber").value();
        u = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createAnswers201WithLocationInEditContext() {
        // the context asked for does not matter: a created term is answered in the edit context
        Response created = Http.as("test-" + carol)
                .contentType("application/json")
                .body("{\"name\":\"News " + u + "\"}")
                .post("/wp-json/wp/v2/tags?context=embed");
        long id = created.then().statusCode(201).extract().jsonPath().getLong("id");
        created.then()
                .header("Location", "http://localhost/wp-json/wp/v2/tags/" + id)
                .header("Allow", "GET, POST")
                .body("taxonomy", equalTo("post_tag"))
                .body("count", equalTo(0))
                .body("meta", equalTo(List.of()))
                .body("link", equalTo("http://localhost/?tag=news-" + u))
                .body("description", equalTo(""));
        assertThat(fieldNames(json(created.asString())))
                .containsExactly("id", "count", "description", "link", "name", "slug", "taxonomy", "meta", "_links");
        // Part D 7
        Http.as("test-" + carol)
                .contentType("application/json")
                .body("{\"name\":\"News " + u + "\"}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST")
                .body(equalTo("{\"code\":\"term_exists\",\"message\":\"A term with the name provided already exists in"
                        + " this taxonomy.\",\"data\":{\"status\":400,\"term_id\":" + id + "},\"additional_data\":["
                        + id + "," + id + "]}"));
        long category = create("test-" + editor, "categories", "{\"name\":\"Dup " + u + "\"}");
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Dup " + u + "\"}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"term_exists\",\"message\":\"A term with the name provided already exists"
                        + " with this parent.\",\"data\":{\"status\":400,\"term_id\":" + category + "},"
                        + "\"additional_data\":[" + category + "," + category + "]}"));
        // Part D 8: a shadowed value of the wrong type is not reported, the name arg having its own sanitizer
        Http.as("test-" + carol)
                .contentType("application/json")
                .queryParam("name[]", "x")
                .body("{\"name\":\"d08 " + u + "\"}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(201)
                .body("name", equalTo("d08 " + u));
        // the value that wins is checked
        Http.as("test-" + carol)
                .contentType("application/json")
                .body("{\"name\":[\"x\"]}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.name", equalTo("name is not of type string."));
        Http.as("test-" + carol)
                .contentType("application/json")
                .body("{\"name\":\"X\"}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create terms in this taxonomy."));
        Http.as(null)
                .contentType("application/json")
                .body("{\"name\":\"X\"}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_create"));
        // a contributor assigns tags, so creates them
        create("test-" + dave, "tags", "{\"name\":\"Dave " + u + "\"}");
        Http.as("test-" + erin)
                .contentType("application/json")
                .body("{\"name\":\"Erin " + u + "\"}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(403);
        Http.as("test-" + carol)
                .contentType("application/json")
                .body("{}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_missing_callback_param"))
                .body("data.params", equalTo(List.of("name")));
        // Part D 23
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"<b></b>\"}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(500)
                .header("Allow", "GET, POST")
                .body(equalTo("{\"code\":\"empty_term_name\",\"message\":\"A name is required for this term.\","
                        + "\"data\":null}"));
    }

    @Test
    void parentRules() {
        long parent = create("test-" + editor, "categories", "{\"name\":\"Parent " + u + "\"}");
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Child " + u + "\",\"parent\":" + parent + "}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(201)
                .body("parent", equalTo((int) parent));
        String invalid = "{\"code\":\"rest_term_invalid\",\"message\":\"Parent term does not exist.\","
                + "\"data\":{\"status\":400}}";
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Orphan " + u + "\",\"parent\":987654}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(400)
                .body(equalTo(invalid));
        // Part D 23: a tag is no parent of a category
        long tag = create("test-" + editor, "tags", "{\"name\":\"Tag " + u + "\"}");
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Cross " + u + "\",\"parent\":" + tag + "}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(400)
                .body(equalTo(invalid));
        // nor is another site's category
        long foreign = foreignTerm("category");
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Foreign " + u + "\",\"parent\":" + foreign + "}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(400)
                .body(equalTo(invalid));
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Top " + u + "\",\"parent\":0}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(201)
                .body("parent", equalTo(0));
        // a flat taxonomy declares no parent, yet a given one is refused
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Flat " + u + "\",\"parent\":0}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_taxonomy_not_hierarchical\",\"message\":\"Cannot set parent term,"
                        + " taxonomy is not hierarchical.\",\"data\":{\"status\":400}}"));
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"parent\":987654}")
                .put("/wp-json/wp/v2/categories/" + parent)
                .then()
                .statusCode(400)
                .body(equalTo(invalid));
    }

    @Test
    void collectionQueryParametersAndPaging() {
        String p = "pg" + u;
        List<Long> tags = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            tags.add(create("test-" + editor, "tags", "{\"name\":\"" + p + " a" + String.format("%02d", i) + "\"}"));
        }
        Response page = Http.as(null).get("/wp-json/wp/v2/tags?search=" + p + "&per_page=10&page=2");
        page.then()
                .statusCode(200)
                .header("X-WP-Total", "25")
                .header("X-WP-TotalPages", "3")
                .header(
                        "Link",
                        "<http://localhost/wp-json/wp/v2/tags?search=" + p + "&per_page=10&page=1>;"
                                + " rel=\"prev\", <http://localhost/wp-json/wp/v2/tags?search=" + p
                                + "&per_page=10&page=3>; rel=\"next\"");
        assertThat(page.jsonPath().getList("id", Long.class)).isEqualTo(tags.subList(10, 20));
        Http.as(null)
                .get("/wp-json/wp/v2/tags?search=" + p + "&page=4")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "25")
                .body(equalTo("[]"));
        assertThat(ids("/wp-json/wp/v2/tags?search=" + p + "&offset=5&per_page=2"))
                .containsExactly(tags.get(5), tags.get(6));
        // parent is not a parameter of tags: ignored
        Http.as(null)
                .get("/wp-json/wp/v2/tags?search=" + p + "&parent=0")
                .then()
                .header("X-WP-Total", "25");
        assertThat(ids("/wp-json/wp/v2/tags?include=" + tags.get(16) + "," + tags.get(2) + "&orderby=include"))
                .containsExactly(tags.get(16), tags.get(2));
        // WordPress takes the absolute value of an id, and an include list of only zeros matches nothing
        assertThat(ids("/wp-json/wp/v2/tags?include=-" + tags.get(3))).containsExactly(tags.get(3));
        Http.as(null)
                .get("/wp-json/wp/v2/tags?include=0")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .body(equalTo("[]"));
        assertThat(ids("/wp-json/wp/v2/tags?search=" + p + "&exclude=" + tags.get(0) + "&per_page=1"))
                .containsExactly(tags.get(1));
        assertThat(ids("/wp-json/wp/v2/tags?slug=" + p + "-a02," + p + "-a01"))
                .containsExactly(tags.get(0), tags.get(1));
        assertThat(ids("/wp-json/wp/v2/tags?slug=" + p + "-a02," + p + "-a01&orderby=include_slugs"))
                .containsExactly(tags.get(1), tags.get(0));
        Jdbc.update(data, "update qp_term set count = 2 where id = ?", tags.get(4));
        Jdbc.update(data, "update qp_term set count = 1 where id = ?", tags.get(8));
        assertThat(ids("/wp-json/wp/v2/tags?search=" + p + "&hide_empty=true"))
                .containsExactly(tags.get(4), tags.get(8));
        Http.as(null)
                .get("/wp-json/wp/v2/tags?search=" + p + "&hide_empty=1")
                .then()
                .header("X-WP-Total", "2");
        // ties broken by id in the query's direction
        assertThat(ids("/wp-json/wp/v2/tags?search=" + p + "&orderby=count&order=desc&per_page=3"))
                .containsExactly(tags.get(4), tags.get(8), tags.get(24));
        assertThat(ids("/wp-json/wp/v2/tags?search=" + p + "&orderby=id&order=desc&per_page=2"))
                .containsExactly(tags.get(24), tags.get(23));
        long brew = create("test-" + editor, "tags", "{\"name\":\"Brew" + u + "\"}");
        assertThat(ids("/wp-json/wp/v2/tags?search=ew" + u)).containsExactly(brew);
        Http.as(null)
                .get("/wp-json/wp/v2/tags?hide_empty=")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.hide_empty", equalTo("hide_empty is not of type boolean."));
        // categories: offset is not a parameter of a hierarchical taxonomy; parent is
        String c = "cq" + u;
        long first = create("test-" + editor, "categories", "{\"name\":\"" + c + " 1\"}");
        long second = create("test-" + editor, "categories", "{\"name\":\"" + c + " 2\",\"parent\":" + first + "}");
        long third = create("test-" + editor, "categories", "{\"name\":\"" + c + " 3\"}");
        assertThat(ids("/wp-json/wp/v2/categories?search=" + c + "&offset=5")).containsExactly(first, second, third);
        assertThat(ids("/wp-json/wp/v2/categories?search=" + c + "&parent=0")).containsExactly(first, third);
        assertThat(ids("/wp-json/wp/v2/categories?search=" + c + "&parent=" + first))
                .containsExactly(second);
        // HEAD answers the totals without a body
        Http.as(null)
                .head("/wp-json/wp/v2/tags?search=" + p)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "25")
                .header("X-WP-TotalPages", "3")
                .header("Allow", "GET")
                .body(equalTo(""));
        // the id and slug lists are bounded
        StringJoiner many = new StringJoiner(",", "{\"include\":[", "]}");
        for (int i = 1; i <= MAX_LIST_ITEMS + 1; i++) {
            many.add(Integer.toString(i));
        }
        Http.as(null)
                .contentType("application/json")
                .body(many.toString())
                .get("/wp-json/wp/v2/tags")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.details.include.code", equalTo("rest_too_many_items"))
                .body("data.params.include", equalTo("include must contain at most " + MAX_LIST_ITEMS + " items."));
    }

    @Test
    void itemReadsAndEditContext() {
        long category = create("test-" + editor, "categories", "{\"name\":\"Item " + u + "\"}");
        long tag = create("test-" + editor, "tags", "{\"name\":\"Item " + u + "\"}");
        Http.as(null)
                .get("/wp-json/wp/v2/tags/" + category)
                .then()
                .statusCode(404)
                .header("Allow", nullValue())
                .body(
                        equalTo(
                                "{\"code\":\"rest_term_invalid\",\"message\":\"Term does not exist.\",\"data\":{\"status\":404}}"));
        // Part D 15
        Http.as(null)
                .get("/wp-json/wp/v2/categories/999999?_envelope")
                .then()
                .statusCode(200)
                .body(equalTo("{\"body\":{\"code\":\"rest_term_invalid\",\"message\":\"Term does not exist.\","
                        + "\"data\":{\"status\":404}},\"status\":404,\"headers\":[]}"));
        Http.as("test-" + carol)
                .get("/wp-json/wp/v2/categories/" + category + "?context=edit")
                .then()
                .statusCode(403)
                // the edit context fails every method's permission for her
                .header("Allow", nullValue())
                .body("code", equalTo("rest_forbidden_context"));
        Http.as("test-" + carol)
                .get("/wp-json/wp/v2/categories?context=edit")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden_context"))
                .body("message", equalTo("Sorry, you are not allowed to edit terms in this taxonomy."));
        Http.as(null)
                .get("/wp-json/wp/v2/tags?context=edit")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"));
        Http.as("test-" + editor)
                .get("/wp-json/wp/v2/categories/" + category + "?context=edit")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .body("parent", equalTo(0));
        JsonNode embed = json(Http.as(null)
                .get("/wp-json/wp/v2/categories/" + category + "?context=embed")
                .asString());
        assertThat(fieldNames(embed)).containsExactly("id", "link", "name", "slug", "taxonomy", "_links");
        assertThat(embed.get("link").asText()).isEqualTo("http://localhost/?cat=" + category);
        JsonNode view = json(Http.as(null).get("/wp-json/wp/v2/tags/" + tag).asString());
        assertThat(fieldNames(view))
                .containsExactly("id", "count", "description", "link", "name", "slug", "taxonomy", "meta", "_links");
        assertThat(view.get("link").asText()).isEqualTo("http://localhost/?tag=item-" + u);
        JsonNode categoryView =
                json(Http.as(null).get("/wp-json/wp/v2/categories/" + category).asString());
        assertThat(fieldNames(categoryView))
                .containsExactly(
                        "id", "count", "description", "link", "name", "slug", "taxonomy", "parent", "meta", "_links");
        // another site's term does not exist here, whoever asks
        long foreign = foreignTerm("post_tag");
        for (String bearer : new String[] {null, "test-" + administrator}) {
            Http.as(bearer)
                    .get("/wp-json/wp/v2/tags/" + foreign)
                    .then()
                    .statusCode(404)
                    .body("code", equalTo("rest_term_invalid"));
        }
        assertThat(ids("/wp-json/wp/v2/tags?include=" + foreign)).isEmpty();
    }

    @Test
    void linksOfATerm() {
        long parent = create("test-" + editor, "categories", "{\"name\":\"Parent " + u + "\"}");
        long child = create("test-" + editor, "categories", "{\"name\":\"Child " + u + "\",\"parent\":" + parent + "}");
        String raw = Http.as(null)
                .get("/wp-json/wp/v2/categories/" + child + "?_embed=up")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        String api = "http://localhost/wp-json/wp/v2/";
        String curies = "\"curies\":[{\"name\":\"wp\",\"href\":\"https://api.w.org/{rel}\",\"templated\":true}]";
        assertThat(raw.replace("\\/", "/"))
                .isEqualTo("{\"id\":" + child + ",\"count\":0,\"description\":\"\",\"link\":\"http://localhost/?cat="
                        + child + "\",\"name\":\"Child " + u + "\",\"slug\":\"child-" + u + "\",\"taxonomy\":"
                        + "\"category\",\"parent\":" + parent + ",\"meta\":[],\"_links\":{\"self\":[{\"href\":\"" + api
                        + "categories/" + child
                        + "\",\"targetHints\":{\"allow\":[\"GET\"]}}],\"collection\":[{\"href\":\""
                        + api + "categories\"}],\"about\":[{\"href\":\"" + api + "taxonomies/category\"}],\"up\":[{"
                        + "\"embeddable\":true,\"href\":\"" + api + "categories/" + parent + "\"}],\"wp:post_type\":[{"
                        + "\"href\":\"" + api + "posts?categories=" + child + "\"}]," + curies + "},\"_embedded\":{"
                        + "\"up\":[{\"id\":" + parent + ",\"link\":\"http://localhost/?cat=" + parent + "\",\"name\":"
                        + "\"Parent " + u + "\",\"slug\":\"parent-" + u + "\",\"taxonomy\":\"category\",\"_links\":{"
                        + "\"self\":[{\"href\":\"" + api + "categories/" + parent + "\",\"targetHints\":{\"allow\":"
                        + "[\"GET\"]}}],\"collection\":[{\"href\":\"" + api + "categories\"}],\"about\":[{\"href\":\""
                        + api + "taxonomies/category\"}],\"wp:post_type\":[{\"href\":\"" + api + "posts?categories="
                        + parent + "\"}]," + curies + "}}]}}");
        // a tag links its posts by the tags parameter; the editor's self link lists every method
        long tag = create("test-" + editor, "tags", "{\"name\":\"Linked " + u + "\"}");
        JsonNode links = json(Http.as("test-" + editor)
                        .get("/wp-json/wp/v2/tags/" + tag)
                        .asString())
                .get("_links");
        assertThat(fieldNames(links)).containsExactly("self", "collection", "about", "wp:post_type", "curies");
        assertThat(links.at("/self/0/targetHints/allow").toString())
                .isEqualTo("[\"GET\",\"POST\",\"PUT\",\"PATCH\",\"DELETE\"]");
        assertThat(links.at("/about/0/href").asText()).isEqualTo(api + "taxonomies/post_tag");
        assertThat(links.at("/wp:post_type/0/href").asText()).isEqualTo(api + "posts?tags=" + tag);
        // _fields without links computes none
        Http.as(null)
                .get("/wp-json/wp/v2/tags/" + tag + "?_fields=name")
                .then()
                .body(equalTo("{\"name\":\"Linked " + u + "\"}"));
    }

    @Test
    void postParameter() {
        long news = create("test-" + editor, "categories", "{\"name\":\"News " + u + "\"}");
        long arts = create("test-" + editor, "categories", "{\"name\":\"Arts " + u + "\"}");
        create("test-" + editor, "categories", "{\"name\":\"Other " + u + "\"}");
        PostId published = TestData.post(data, TestData.MAIN, "post", "publish", "pub-" + u, "Published");
        assign(TestData.MAIN, published, news);
        assign(TestData.MAIN, published, arts);
        PostId draft = TestData.post(data, TestData.MAIN, "post", "draft", "draft-" + u, "Draft");
        Jdbc.update(data, "update qp_post set author_id = ? where id = ?", carol, draft.value());
        // Part D 23
        Http.as(null)
                .get("/wp-json/wp/v2/categories?post=987654")
                .then()
                .statusCode(400)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_post_invalid_id\",\"message\":\"Invalid post ID.\","
                        + "\"data\":{\"status\":400}}"));
        Http.as(null)
                .get("/wp-json/wp/v2/categories?post=" + draft.value())
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_forbidden_context\",\"message\":\"Sorry, you are not allowed to view"
                        + " terms for this post.\",\"data\":{\"status\":401}}"));
        Http.as("test-" + erin)
                .get("/wp-json/wp/v2/categories?post=" + draft.value())
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden_context"));
        // the draft's author reads its terms
        Http.as("test-" + carol)
                .get("/wp-json/wp/v2/categories?post=" + draft.value())
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0");
        Http.as(null)
                .get("/wp-json/wp/v2/categories?post=" + published.value())
                .then()
                .statusCode(200)
                .header("X-WP-Total", "2");
        assertThat(ids("/wp-json/wp/v2/categories?post=" + published.value())).containsExactly(arts, news);
        // tags are not used by pages
        PostId page = TestData.post(data, TestData.MAIN, "page", "publish", "page-" + u, "Page");
        Http.as(null)
                .get("/wp-json/wp/v2/tags?post=" + page.value())
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"));
        // another site's post does not exist here
        SiteId other = TestData.site(data);
        PostId foreign = TestData.post(data, other, "post", "publish", "foreign-" + u, "Foreign");
        Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/categories?post=" + foreign.value())
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_post_invalid_id"));
    }

    @Test
    void updateAndDelete() {
        long category = create("test-" + editor, "categories", "{\"name\":\"Upd " + u + "\"}");
        int step = 0;
        for (String method : new String[] {"PUT", "PATCH", "POST"}) {
            step++;
            Http.as("test-" + editor)
                    .contentType("application/json")
                    .body("{\"name\":\"Upd " + u + " " + step + "\",\"description\":\"<b>d</b>\"}")
                    .request(method, "/wp-json/wp/v2/categories/" + category + "?context=embed")
                    .then()
                    .statusCode(200)
                    .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                    .body("name", equalTo("Upd " + u + " " + step))
                    .body("slug", equalTo("upd-" + u))
                    .body("description", equalTo("<b>d</b>"))
                    .body("count", equalTo(0));
        }
        Http.as("test-" + carol)
                .contentType("application/json")
                .body("{\"name\":\"Nope\"}")
                .put("/wp-json/wp/v2/categories/" + category)
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_cannot_update\",\"message\":\"Sorry, you are not allowed to edit this"
                        + " term.\",\"data\":{\"status\":403}}"));
        long taken = create("test-" + editor, "categories", "{\"name\":\"S" + u + "\"}");
        assertThat(taken).isPositive();
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"slug\":\"s" + u + "\"}")
                .post("/wp-json/wp/v2/categories/" + category)
                .then()
                .statusCode(500)
                .body(equalTo("{\"code\":\"duplicate_term_slug\",\"message\":\"The slug &#8220;s" + u + "&#8221; is"
                        + " already in use by another term.\",\"data\":null}"));
        // nothing to update: the term is not written
        long version = version(category);
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"meta\":{}}")
                .put("/wp-json/wp/v2/categories/" + category)
                .then()
                .statusCode(200)
                .body("name", equalTo("Upd " + u + " 3"));
        assertThat(version(category)).isEqualTo(version);
        long tag = create("test-" + editor, "tags", "{\"name\":\"Del " + u + "\"}");
        Http.as("test-" + editor)
                .delete("/wp-json/wp/v2/tags/" + tag)
                .then()
                .statusCode(501)
                .body(equalTo("{\"code\":\"rest_trash_not_supported\",\"message\":\"Terms do not support trashing."
                        + " Set 'force=true' to delete.\",\"data\":{\"status\":501}}"));
        String deleted = Http.as("test-" + editor)
                .delete("/wp-json/wp/v2/tags/" + tag + "?force=true")
                .then()
                .statusCode(200)
                .header("Allow", nullValue())
                .extract()
                .asString();
        assertThat(deleted.replace("\\/", "/"))
                .isEqualTo("{\"deleted\":true,\"previous\":{\"id\":" + tag + ",\"count\":0,\"description\":\"\","
                        + "\"link\":\"http://localhost/?tag=del-" + u + "\",\"name\":\"Del " + u + "\",\"slug\":\"del-"
                        + u + "\",\"taxonomy\":\"post_tag\",\"meta\":[]}}");
        Http.as(null).get("/wp-json/wp/v2/tags/" + tag).then().statusCode(404);
        // Part D 23: nobody deletes the default category
        long uncategorized = Jdbc.count(
                data, "select id from qp_term where site_id = 1 and taxonomy = 'category' and slug = 'uncategorized'");
        Http.as("test-" + administrator)
                .delete("/wp-json/wp/v2/categories/" + uncategorized + "?force=true")
                .then()
                .statusCode(403)
                .header("Allow", "GET, POST, PUT, PATCH")
                .body(equalTo("{\"code\":\"rest_cannot_delete\",\"message\":\"Sorry, you are not allowed to delete"
                        + " this term.\",\"data\":{\"status\":403}}"));
        // Part D 4
        long other = create("test-" + editor, "tags", "{\"name\":\"Gone " + u + "\"}");
        Http.as(null)
                .header("X-HTTP-Method-Override", "DELETE")
                .post("/wp-json/wp/v2/tags/" + other + "?force=true")
                .then()
                .statusCode(401)
                .header("Allow", "GET")
                .header("Cache-Control", "no-cache, must-revalidate, max-age=0, no-store, private")
                .body(equalTo("{\"code\":\"rest_cannot_delete\",\"message\":\"Sorry, you are not allowed to delete"
                        + " this term.\",\"data\":{\"status\":401}}"));
        // the same through ?_method: no no-cache headers for an anonymous caller (Part D 4)
        Http.as(null)
                .contentType("application/json")
                .body("{}")
                .post("/wp-json/wp/v2/tags/" + other + "?_method=DELETE&force=true")
                .then()
                .statusCode(401)
                .header("Allow", "GET")
                .header("Cache-Control", nullValue())
                .body(equalTo("{\"code\":\"rest_cannot_delete\",\"message\":\"Sorry, you are not allowed to delete"
                        + " this term.\",\"data\":{\"status\":401}}"));
        Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/tags/" + other + "?_method=DELETE&force=true")
                .then()
                .statusCode(200)
                .header("Allow", nullValue())
                .body("deleted", equalTo(true))
                .body("previous.id", equalTo((int) other));
        Http.as(null)
                .get("/wp-json/wp/v2/tags/" + other)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_term_invalid"));
        // another site's term cannot be changed or deleted from here
        long foreign = foreignTerm("post_tag");
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"name\":\"Taken over\"}")
                .put("/wp-json/wp/v2/tags/" + foreign)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_term_invalid"));
        Http.as("test-" + administrator)
                .delete("/wp-json/wp/v2/tags/" + foreign + "?force=true")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_term_invalid"));
        assertThat(Jdbc.count(data, "select count(*) from qp_term where id = ? and name = 'Foreign'", foreign))
                .isEqualTo(1);
    }

    @Test
    void specialCharactersRoundTripAsWordPressStoresAndEncodesThem() {
        // Part D 14: Cafe with an acute e, "/Bar <b> & " and a grinning face keep no tag and one space, store & as
        // &amp;
        // and are encoded as PHP's json_encode writes them (non-ASCII as escapes, the slash escaped)
        String raw = Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Caf\\u00e9/Bar <b> & \\ud83d\\ude00 " + u + "\"}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(201)
                .extract()
                .asString();
        assertThat(raw)
                .contains("\"name\":\"Caf\\u00e9\\/Bar &amp; \\ud83d\\ude00 " + u + "\"")
                .contains("\"slug\":\"cafe-bar-%f0%9f%98%80-" + u + "\"")
                .contains("\"link\":\"http:\\/\\/localhost\\/?tag=cafe-bar-%f0%9f%98%80-" + u + "\"");
        String plain = Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Caf\\u00e9/Bar <b> " + u + "\"}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(201)
                .extract()
                .asString();
        assertThat(plain)
                .contains("\"name\":\"Caf\\u00e9\\/Bar " + u + "\"")
                .contains("\"slug\":\"cafe-bar-" + u + "\"");
    }

    @Test
    void queryBeatsThePathId() {
        long a = create("test-" + editor, "tags", "{\"name\":\"QA " + u + "\"}");
        long b = create("test-" + editor, "tags", "{\"name\":\"QB " + u + "\"}");
        Http.as(null)
                .get("/wp-json/wp/v2/tags/" + a + "?id=" + b)
                .then()
                .statusCode(200)
                .body("id", equalTo((int) b));
        long c = create("test-" + editor, "categories", "{\"name\":\"QC " + u + "\"}");
        long d = create("test-" + editor, "categories", "{\"name\":\"QD " + u + "\"}");
        Http.as(null)
                .get("/wp-json/wp/v2/categories/" + c + "?id=" + d)
                .then()
                .statusCode(200)
                .body("id", equalTo((int) d));
        Http.as(null)
                .get("/wp-json/wp/v2/categories/" + c + "?id=abc")
                .then()
                .statusCode(400)
                .body("data.params.id", equalTo("id is not of type integer."));
    }

    @Test
    void optionsDescribeTheRoutesAsWordPressDoes() {
        Http.as(null)
                .options("/wp-json/wp/v2/categories")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body(equalTo(CATEGORIES_OPTIONS));
        long tag = create("test-" + editor, "tags", "{\"name\":\"Opt " + u + "\"}");
        Http.as("test-" + administrator)
                .options("/wp-json/wp/v2/tags/" + tag)
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .body(equalTo(TAG_OPTIONS));
        Http.as(null).options("/wp-json/wp/v2/tags").then().statusCode(200).body(equalTo(TAGS_OPTIONS));
        Http.as(null)
                .options("/wp-json/wp/v2/categories/1")
                .then()
                .statusCode(200)
                .body(equalTo(CATEGORY_OPTIONS));
        // both routes of each REST taxonomy are in the namespace index, collection first
        JsonNode routes = json(Http.as(null).get("/wp-json/wp/v2").asString()).get("routes");
        List<String> names = fieldNames(routes);
        assertThat(names)
                .containsSubsequence(
                        "/wp/v2/categories",
                        "/wp/v2/categories/(?P<id>[\\d]+)",
                        "/wp/v2/tags",
                        "/wp/v2/tags/(?P<id>[\\d]+)");
    }

    /** Part of the edit context: items the caller may not edit are dropped after paging; the totals still count them. */
    @Test
    void editContextDropsTermsTheCallerCannotEditAfterPaging() {
        String p = "ed" + u;
        long first = create("test-" + editor, "tags", "{\"name\":\"" + p + " 1\"}");
        long refused = create("test-" + editor, "tags", "{\"name\":\"" + p + " 2\"}");
        long third = create("test-" + editor, "tags", "{\"name\":\"" + p + " 3\"}");
        RefusedTerms.REFUSED.add(refused);
        try {
            Response page = Http.as("test-" + editor).get("/wp-json/wp/v2/tags?context=edit&search=" + p);
            page.then().statusCode(200).header("X-WP-Total", "3").header("X-WP-TotalPages", "1");
            assertThat(page.jsonPath().getList("id", Long.class)).containsExactly(first, third);
            Http.as("test-" + editor)
                    .get("/wp-json/wp/v2/tags/" + refused + "?context=edit")
                    .then()
                    .statusCode(403)
                    .body(equalTo("{\"code\":\"rest_forbidden_context\",\"message\":\"Sorry, you are not allowed to"
                            + " edit this term.\",\"data\":{\"status\":403}}"));
            // the view context lists it
            assertThat(ids("/wp-json/wp/v2/tags?search=" + p)).containsExactly(first, refused, third);
        } finally {
            RefusedTerms.REFUSED.remove(refused);
        }
    }

    /**
     * Every taxonomy shown in REST is read by anyone, public or not (WordPress's terms controller consults neither
     * public nor publicly_queryable); plain links use the query variable of a publicly queryable taxonomy (its key)
     * and taxonomy=&term= otherwise.
     */
    @Test
    void customTaxonomiesShownInRest() {
        long genre = create("test-" + editor, "genres", "{\"name\":\"Rock " + u + "\"}");
        long hidden = create("test-" + editor, "private-terms", "{\"name\":\"Hidden " + u + "\"}");
        Http.as(null)
                .get("/wp-json/wp/v2/genres/" + genre)
                .then()
                .statusCode(200)
                .body("taxonomy", equalTo("qp_genre"))
                .body("link", equalTo("http://localhost/?qp_genre=rock-" + u))
                .body("_links.about[0].href", equalTo("http://localhost/wp-json/wp/v2/taxonomies/qp_genre"))
                .body("_links.'wp:post_type'[0].href", equalTo("http://localhost/wp-json/wp/v2/posts?genres=" + genre));
        Http.as(null)
                .get("/wp-json/wp/v2/private-terms/" + hidden)
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body("link", equalTo("http://localhost/?taxonomy=qp_private&term=hidden-" + u));
        assertThat(ids("/wp-json/wp/v2/private-terms?search=" + u)).containsExactly(hidden);
        Http.as(null)
                .get("/wp-json/wp/v2/private-terms?context=edit")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"));
        Http.as(null)
                .get("/wp-json/wp/v2/private-terms/" + hidden + "?context=edit")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"))
                .body("message", equalTo("Sorry, you are not allowed to edit this term."));
        Http.as("test-" + erin)
                .get("/wp-json/wp/v2/private-terms/" + hidden + "?context=embed")
                .then()
                .statusCode(200)
                .body("name", equalTo("Hidden " + u));
    }

    /**
     * meta: only an array (a JSON object or list) passes; anything else is "Invalid parameter." without details;
     * keys are ignored, as no term meta is shown in REST; meta reads [] in every context that has it.
     */
    @Test
    void metaIsCheckedOnlyForBeingAnArray() {
        String refused = "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): meta\","
                + "\"data\":{\"status\":400,\"params\":{\"meta\":\"Invalid parameter.\"},\"details\":[]}}";
        for (String meta : new String[] {"\"\"", "\"x\"", "5", "true", "false"}) {
            Http.as("test-" + editor)
                    .contentType("application/json")
                    .body("{\"name\":\"Meta " + u + "\",\"meta\":" + meta + "}")
                    .post("/wp-json/wp/v2/tags")
                    .then()
                    .statusCode(400)
                    .body(equalTo(refused));
        }
        Http.as("test-" + editor)
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Meta " + u)
                .formParam("meta", "")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(400)
                .body(equalTo(refused));
        long tag = Http.as("test-" + editor)
                .contentType("application/json")
                .body("{\"name\":\"Meta " + u + "\",\"meta\":{\"x\":1}}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(201)
                .body("meta", equalTo(List.of()))
                .extract()
                .jsonPath()
                .getLong("id");
        for (String meta : new String[] {"[1,2]", "[]", "{}", "null"}) {
            Http.as("test-" + editor)
                    .contentType("application/json")
                    .body("{\"meta\":" + meta + "}")
                    .put("/wp-json/wp/v2/tags/" + tag)
                    .then()
                    .statusCode(200)
                    .body("meta", equalTo(List.of()));
        }
        assertThat(Jdbc.strings(data, "select meta from qp_term where id = ?", tag))
                .containsExactly("{}");
        Http.as(null).get("/wp-json/wp/v2/tags/" + tag).then().body("meta", equalTo(List.of()));
    }

    /**
     * T10a: a stale write that fails when a transactional method commits (Narayana's RollbackException around
     * Hibernate's optimistic lock failure) is answered 409, never 500.
     */
    @Test
    void aStaleWriteThroughTheTransactionCommitIsAConflict() {
        long tag = create("test-" + editor, "tags", "{\"name\":\"Stale " + u + "\"}");
        Http.as("test-" + editor)
                .put("/wp-json/qp-stale/v1/terms/" + tag)
                .then()
                .statusCode(409)
                .body(equalTo("{\"code\":\"quarkpress_conflict\",\"message\":\"The item was changed by another"
                        + " request.\",\"data\":{\"status\":409}}"));
        assertThat(Jdbc.strings(data, "select description from qp_term where id = ?", tag))
                .containsExactly("");
    }

    /** Writes a term whose row another transaction changed after it was read. */
    @ApplicationScoped
    public static class StaleWriter {

        @Inject
        TermRepository repository;

        @Transactional
        public void describeAfterAnotherWrite(long id) {
            TermEntity term = repository.findById(id);
            QuarkusTransaction.requiringNew()
                    .run(() -> Jdbc.update(
                            Jdbc.dataSource(), "update qp_term set version = version + 1 where id = ?", id));
            term.description = "stale";
        }
    }

    /** {@code PUT /qp-stale/v1/terms/<id>}: the stale write, in a REST request of the main site. */
    @ApplicationScoped
    public static class StaleRoute implements RestRouteProvider {

        @Inject
        StaleWriter writer;

        @Override
        public void register(RouteRegistry routes) {
            routes.route("qp-stale/v1", "/terms/(?P<id>[\\d]+)")
                    .endpoint(Endpoint.of("PUT")
                            .handler(request -> {
                                writer.describeAfterAnotherWrite(
                                        Long.parseLong(request.urlParams().get("id")));
                                return RestResponse.ok(RestJson.NODES.objectNode());
                            })
                            .build())
                    .register();
        }
    }

    /** Refuses edit_term on the terms a test lists, as a capability filter of an application might. */
    @ApplicationScoped
    public static class RefusedTerms implements MetaCapabilityFilter {

        static final Set<Long> REFUSED = ConcurrentHashMap.newKeySet();

        @Override
        public Set<String> apply(Set<String> requiredCaps, MetaCapRequest req) {
            if (req.capability().equals("edit_term")
                    && req.object().orElse(null) instanceof TermFacts term
                    && REFUSED.contains(term.id().value())) {
                Set<String> refused = new LinkedHashSet<>(requiredCaps);
                refused.add("do_not_allow");
                return refused;
            }
            return requiredCaps;
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long create(String bearer, String base, String json) {
        return Http.as(bearer)
                .contentType("application/json")
                .body(json)
                .post("/wp-json/wp/v2/" + base)
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private List<Long> ids(String target) {
        return Http.as(null)
                .get(target)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id", Long.class);
    }

    private void assign(SiteId site, PostId post, long term) {
        Jdbc.update(
                data,
                "insert into qp_term_assignment (site_id, post_id, term_id, term_order) values (?, ?, ?, 0)",
                site.value(),
                post.value(),
                term);
    }

    private long version(long term) {
        return Jdbc.count(data, "select version from qp_term where id = ?", term);
    }

    /** A term of another site, written past the service. */
    private long foreignTerm(String taxonomy) {
        SiteId other = TestData.site(data);
        String slug = "foreign-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, ?, 'Foreign', ?, '')",
                other.value(),
                taxonomy,
                slug);
        return Jdbc.count(data, "select id from qp_term where slug = ?", slug);
    }

    // WordPress 7.1.3's OPTIONS bodies of /wp/v2/categories and /wp/v2/tags/<id> (recorded in Part D, as alice)
    static final String CATEGORIES_OPTIONS =
            "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\",\"POST\"],\"endpoints\":[{\"methods\":[\"GET\"],\"allow_batch\":{\"v"
                    + "1\":true},\"args\":{\"context\":{\"description\":\"Scope under which the request is made; determines fie"
                    + "lds present in response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],\"default\":\"view\",\"requi"
                    + "red\":false},\"page\":{\"description\":\"Current page of the collection.\",\"type\":\"integer\",\"default\":1"
                    + ",\"minimum\":1,\"required\":false},\"per_page\":{\"description\":\"Maximum number of items to be returned"
                    + " in result set.\",\"type\":\"integer\",\"default\":10,\"minimum\":1,\"maximum\":100,\"required\":false},\"sear"
                    + "ch\":{\"description\":\"Limit results to those matching a string.\",\"type\":\"string\",\"required\":false}"
                    + ",\"exclude\":{\"description\":\"Ensure result set excludes specific IDs.\",\"type\":\"array\",\"items\":{\"ty"
                    + "pe\":\"integer\"},\"default\":[],\"required\":false},\"include\":{\"description\":\"Limit result set to spec"
                    + "ific IDs.\",\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"default\":[],\"required\":false},\"order\":{\"de"
                    + "scription\":\"Order sort attribute ascending or descending.\",\"type\":\"string\",\"default\":\"asc\",\"enum"
                    + "\":[\"asc\",\"desc\"],\"required\":false},\"orderby\":{\"description\":\"Sort collection by term attribute.\""
                    + ",\"type\":\"string\",\"default\":\"name\",\"enum\":[\"id\",\"include\",\"name\",\"slug\",\"include_slugs\",\"term_gro"
                    + "up\",\"description\",\"count\"],\"required\":false},\"hide_empty\":{\"description\":\"Whether to hide terms "
                    + "not assigned to any posts.\",\"type\":\"boolean\",\"default\":false,\"required\":false},\"parent\":{\"descri"
                    + "ption\":\"Limit result set to terms assigned to a specific parent.\",\"type\":\"integer\",\"required\":fa"
                    + "lse},\"post\":{\"description\":\"Limit result set to terms assigned to a specific post.\",\"type\":\"inte"
                    + "ger\",\"default\":null,\"required\":false},\"slug\":{\"description\":\"Limit result set to terms with one "
                    + "or more specific slugs.\",\"type\":\"array\",\"items\":{\"type\":\"string\"},\"required\":false}}},{\"methods\""
                    + ":[\"POST\"],\"allow_batch\":{\"v1\":true},\"args\":{\"description\":{\"description\":\"HTML description of th"
                    + "e term.\",\"type\":\"string\",\"required\":false},\"name\":{\"description\":\"HTML title for the term.\",\"typ"
                    + "e\":\"string\",\"required\":true},\"slug\":{\"description\":\"An alphanumeric identifier for the term uniq"
                    + "ue to its type.\",\"type\":\"string\",\"required\":false},\"parent\":{\"description\":\"The parent term ID.\""
                    + ",\"type\":\"integer\",\"required\":false},\"meta\":{\"description\":\"Meta fields.\",\"type\":\"object\",\"proper"
                    + "ties\":[],\"required\":false}}}],\"schema\":{\"$schema\":\"http:\\/\\/json-schema.org\\/draft-04\\/schema#\","
                    + "\"title\":\"category\",\"type\":\"object\",\"properties\":{\"id\":{\"description\":\"Unique identifier for the "
                    + "term.\",\"type\":\"integer\",\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"count\":{\"description"
                    + "\":\"Number of published posts for the term.\",\"type\":\"integer\",\"context\":[\"view\",\"edit\"],\"readonly"
                    + "\":true},\"description\":{\"description\":\"HTML description of the term.\",\"type\":\"string\",\"context\":["
                    + "\"view\",\"edit\"]},\"link\":{\"description\":\"URL of the term.\",\"type\":\"string\",\"format\":\"uri\",\"context"
                    + "\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"name\":{\"description\":\"HTML title for the term.\",\"typ"
                    + "e\":\"string\",\"context\":[\"view\",\"embed\",\"edit\"],\"required\":true},\"slug\":{\"description\":\"An alphanu"
                    + "meric identifier for the term unique to its type.\",\"type\":\"string\",\"context\":[\"view\",\"embed\",\"ed"
                    + "it\"]},\"taxonomy\":{\"description\":\"Type attribution for the term.\",\"type\":\"string\",\"enum\":[\"catego"
                    + "ry\"],\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"parent\":{\"description\":\"The parent term"
                    + " ID.\",\"type\":\"integer\",\"context\":[\"view\",\"edit\"]},\"meta\":{\"description\":\"Meta fields.\",\"type\":\"o"
                    + "bject\",\"context\":[\"view\",\"edit\"],\"properties\":[]}}},\"_links\":{\"self\":[{\"href\":\"http:\\/\\/localhos"
                    + "t\\/wp-json\\/wp\\/v2\\/categories\"}]}}";

    static final String TAG_OPTIONS =
            "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\",\"POST\",\"PUT\",\"PATCH\",\"DELETE\"],\"endpoints\":[{\"methods\":[\""
                    + "GET\"],\"allow_batch\":{\"v1\":true},\"args\":{\"id\":{\"description\":\"Unique identifier for the term.\",\"t"
                    + "ype\":\"integer\",\"required\":false},\"context\":{\"description\":\"Scope under which the request is made"
                    + "; determines fields present in response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],\"defaul"
                    + "t\":\"view\",\"required\":false}}},{\"methods\":[\"POST\",\"PUT\",\"PATCH\"],\"allow_batch\":{\"v1\":true},\"args\""
                    + ":{\"id\":{\"description\":\"Unique identifier for the term.\",\"type\":\"integer\",\"required\":false},\"desc"
                    + "ription\":{\"description\":\"HTML description of the term.\",\"type\":\"string\",\"required\":false},\"name\""
                    + ":{\"description\":\"HTML title for the term.\",\"type\":\"string\",\"required\":false},\"slug\":{\"descriptio"
                    + "n\":\"An alphanumeric identifier for the term unique to its type.\",\"type\":\"string\",\"required\":fals"
                    + "e},\"meta\":{\"description\":\"Meta fields.\",\"type\":\"object\",\"properties\":[],\"required\":false}}},{\"me"
                    + "thods\":[\"DELETE\"],\"allow_batch\":{\"v1\":true},\"args\":{\"id\":{\"description\":\"Unique identifier for t"
                    + "he term.\",\"type\":\"integer\",\"required\":false},\"force\":{\"type\":\"boolean\",\"default\":false,\"descript"
                    + "ion\":\"Required to be true, as terms do not support trashing.\",\"required\":false}}}],\"schema\":{\"$s"
                    + "chema\":\"http:\\/\\/json-schema.org\\/draft-04\\/schema#\",\"title\":\"tag\",\"type\":\"object\",\"properties\":"
                    + "{\"id\":{\"description\":\"Unique identifier for the term.\",\"type\":\"integer\",\"context\":[\"view\",\"embed"
                    + "\",\"edit\"],\"readonly\":true},\"count\":{\"description\":\"Number of published posts for the term.\",\"typ"
                    + "e\":\"integer\",\"context\":[\"view\",\"edit\"],\"readonly\":true},\"description\":{\"description\":\"HTML descr"
                    + "iption of the term.\",\"type\":\"string\",\"context\":[\"view\",\"edit\"]},\"link\":{\"description\":\"URL of th"
                    + "e term.\",\"type\":\"string\",\"format\":\"uri\",\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"name"
                    + "\":{\"description\":\"HTML title for the term.\",\"type\":\"string\",\"context\":[\"view\",\"embed\",\"edit\"],\"r"
                    + "equired\":true},\"slug\":{\"description\":\"An alphanumeric identifier for the term unique to its type"
                    + ".\",\"type\":\"string\",\"context\":[\"view\",\"embed\",\"edit\"]},\"taxonomy\":{\"description\":\"Type attributio"
                    + "n for the term.\",\"type\":\"string\",\"enum\":[\"post_tag\"],\"context\":[\"view\",\"embed\",\"edit\"],\"readonly"
                    + "\":true},\"meta\":{\"description\":\"Meta fields.\",\"type\":\"object\",\"context\":[\"view\",\"edit\"],\"properti"
                    + "es\":[]}}}}";

    static final String TAGS_OPTIONS =
            "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\",\"POST\"],\"endpoints\":[{\"methods\":[\"GET\"],\"allow_batch\":{\"v"
                    + "1\":true},\"args\":{\"context\":{\"description\":\"Scope under which the request is made; determines fie"
                    + "lds present in response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],\"default\":\"view\",\"requi"
                    + "red\":false},\"page\":{\"description\":\"Current page of the collection.\",\"type\":\"integer\",\"default\":1"
                    + ",\"minimum\":1,\"required\":false},\"per_page\":{\"description\":\"Maximum number of items to be returned"
                    + " in result set.\",\"type\":\"integer\",\"default\":10,\"minimum\":1,\"maximum\":100,\"required\":false},\"sear"
                    + "ch\":{\"description\":\"Limit results to those matching a string.\",\"type\":\"string\",\"required\":false}"
                    + ",\"exclude\":{\"description\":\"Ensure result set excludes specific IDs.\",\"type\":\"array\",\"items\":{\"ty"
                    + "pe\":\"integer\"},\"default\":[],\"required\":false},\"include\":{\"description\":\"Limit result set to spec"
                    + "ific IDs.\",\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"default\":[],\"required\":false},\"offset\":{\"d"
                    + "escription\":\"Offset the result set by a specific number of items.\",\"type\":\"integer\",\"required\":f"
                    + "alse},\"order\":{\"description\":\"Order sort attribute ascending or descending.\",\"type\":\"string\",\"de"
                    + "fault\":\"asc\",\"enum\":[\"asc\",\"desc\"],\"required\":false},\"orderby\":{\"description\":\"Sort collection b"
                    + "y term attribute.\",\"type\":\"string\",\"default\":\"name\",\"enum\":[\"id\",\"include\",\"name\",\"slug\",\"includ"
                    + "e_slugs\",\"term_group\",\"description\",\"count\"],\"required\":false},\"hide_empty\":{\"description\":\"Whet"
                    + "her to hide terms not assigned to any posts.\",\"type\":\"boolean\",\"default\":false,\"required\":false}"
                    + ",\"post\":{\"description\":\"Limit result set to terms assigned to a specific post.\",\"type\":\"integer\""
                    + ",\"default\":null,\"required\":false},\"slug\":{\"description\":\"Limit result set to terms with one or m"
                    + "ore specific slugs.\",\"type\":\"array\",\"items\":{\"type\":\"string\"},\"required\":false}}},{\"methods\":[\"P"
                    + "OST\"],\"allow_batch\":{\"v1\":true},\"args\":{\"description\":{\"description\":\"HTML description of the te"
                    + "rm.\",\"type\":\"string\",\"required\":false},\"name\":{\"description\":\"HTML title for the term.\",\"type\":\""
                    + "string\",\"required\":true},\"slug\":{\"description\":\"An alphanumeric identifier for the term unique t"
                    + "o its type.\",\"type\":\"string\",\"required\":false},\"meta\":{\"description\":\"Meta fields.\",\"type\":\"obje"
                    + "ct\",\"properties\":[],\"required\":false}}}],\"schema\":{\"$schema\":\"http:\\/\\/json-schema.org\\/draft-04"
                    + "\\/schema#\",\"title\":\"tag\",\"type\":\"object\",\"properties\":{\"id\":{\"description\":\"Unique identifier fo"
                    + "r the term.\",\"type\":\"integer\",\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"count\":{\"descr"
                    + "iption\":\"Number of published posts for the term.\",\"type\":\"integer\",\"context\":[\"view\",\"edit\"],\"re"
                    + "adonly\":true},\"description\":{\"description\":\"HTML description of the term.\",\"type\":\"string\",\"cont"
                    + "ext\":[\"view\",\"edit\"]},\"link\":{\"description\":\"URL of the term.\",\"type\":\"string\",\"format\":\"uri\",\"c"
                    + "ontext\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"name\":{\"description\":\"HTML title for the term."
                    + "\",\"type\":\"string\",\"context\":[\"view\",\"embed\",\"edit\"],\"required\":true},\"slug\":{\"description\":\"An a"
                    + "lphanumeric identifier for the term unique to its type.\",\"type\":\"string\",\"context\":[\"view\",\"embe"
                    + "d\",\"edit\"]},\"taxonomy\":{\"description\":\"Type attribution for the term.\",\"type\":\"string\",\"enum\":[\""
                    + "post_tag\"],\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"meta\":{\"description\":\"Meta fields"
                    + ".\",\"type\":\"object\",\"context\":[\"view\",\"edit\"],\"properties\":[]}}},\"_links\":{\"self\":[{\"href\":\"http:"
                    + "\\/\\/localhost\\/wp-json\\/wp\\/v2\\/tags\"}]}}";

    static final String CATEGORY_OPTIONS =
            "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\",\"POST\",\"PUT\",\"PATCH\",\"DELETE\"],\"endpoints\":[{\"methods\":[\""
                    + "GET\"],\"allow_batch\":{\"v1\":true},\"args\":{\"id\":{\"description\":\"Unique identifier for the term.\",\"t"
                    + "ype\":\"integer\",\"required\":false},\"context\":{\"description\":\"Scope under which the request is made"
                    + "; determines fields present in response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],\"defaul"
                    + "t\":\"view\",\"required\":false}}},{\"methods\":[\"POST\",\"PUT\",\"PATCH\"],\"allow_batch\":{\"v1\":true},\"args\""
                    + ":{\"id\":{\"description\":\"Unique identifier for the term.\",\"type\":\"integer\",\"required\":false},\"desc"
                    + "ription\":{\"description\":\"HTML description of the term.\",\"type\":\"string\",\"required\":false},\"name\""
                    + ":{\"description\":\"HTML title for the term.\",\"type\":\"string\",\"required\":false},\"slug\":{\"descriptio"
                    + "n\":\"An alphanumeric identifier for the term unique to its type.\",\"type\":\"string\",\"required\":fals"
                    + "e},\"parent\":{\"description\":\"The parent term ID.\",\"type\":\"integer\",\"required\":false},\"meta\":{\"des"
                    + "cription\":\"Meta fields.\",\"type\":\"object\",\"properties\":[],\"required\":false}}},{\"methods\":[\"DELETE"
                    + "\"],\"allow_batch\":{\"v1\":true},\"args\":{\"id\":{\"description\":\"Unique identifier for the term.\",\"type"
                    + "\":\"integer\",\"required\":false},\"force\":{\"type\":\"boolean\",\"default\":false,\"description\":\"Required "
                    + "to be true, as terms do not support trashing.\",\"required\":false}}}],\"schema\":{\"$schema\":\"http:\\/"
                    + "\\/json-schema.org\\/draft-04\\/schema#\",\"title\":\"category\",\"type\":\"object\",\"properties\":{\"id\":{\"de"
                    + "scription\":\"Unique identifier for the term.\",\"type\":\"integer\",\"context\":[\"view\",\"embed\",\"edit\"],"
                    + "\"readonly\":true},\"count\":{\"description\":\"Number of published posts for the term.\",\"type\":\"intege"
                    + "r\",\"context\":[\"view\",\"edit\"],\"readonly\":true},\"description\":{\"description\":\"HTML description of "
                    + "the term.\",\"type\":\"string\",\"context\":[\"view\",\"edit\"]},\"link\":{\"description\":\"URL of the term.\",\""
                    + "type\":\"string\",\"format\":\"uri\",\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"name\":{\"descri"
                    + "ption\":\"HTML title for the term.\",\"type\":\"string\",\"context\":[\"view\",\"embed\",\"edit\"],\"required\":t"
                    + "rue},\"slug\":{\"description\":\"An alphanumeric identifier for the term unique to its type.\",\"type\":"
                    + "\"string\",\"context\":[\"view\",\"embed\",\"edit\"]},\"taxonomy\":{\"description\":\"Type attribution for the "
                    + "term.\",\"type\":\"string\",\"enum\":[\"category\"],\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"p"
                    + "arent\":{\"description\":\"The parent term ID.\",\"type\":\"integer\",\"context\":[\"view\",\"edit\"]},\"meta\":{"
                    + "\"description\":\"Meta fields.\",\"type\":\"object\",\"context\":[\"view\",\"edit\"],\"properties\":[]}}}}";
}
