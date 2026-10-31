package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.core.deployment.spi.PostTypeBuildItem;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The registries WordPress serves under {@code /wp/v2}: post types, taxonomies and post statuses, with their fields in
 * WordPress's order per context, their links, and their refusals (Part D 20, 21, 22). The application also registers
 * a post type and a taxonomy that are not shown in REST.
 */
class RegistriesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(RegistriesDbTest.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new PostTypeBuildItem(
                                    PostTypeDefinition.builder("qp_hidden").build()));
                            context.produce(new PostTypeBuildItem(PostTypeDefinition.builder("qp_book")
                                    .capabilityType("book")
                                    .showInRest(true)
                                    .build()));
                            context.produce(
                                    new TaxonomyBuildItem(TaxonomyDefinition.builder("qp_hidden_tax", List.of("page"))
                                            .build()));
                        }
                    })
                    .produces(PostTypeBuildItem.class)
                    .produces(TaxonomyBuildItem.class)
                    .build());

    static final String CURIES =
            "\"curies\":[{\"name\":\"wp\",\"href\":\"https:\\/\\/api.w.org\\/{rel}\",\"templated\":true}]";

    @Inject
    PostTypes types;

    long subscriber;
    long contributor;
    long editor;
    long administrator;

    @BeforeEach
    void users() {
        AgroalDataSource data = Jdbc.dataSource();
        subscriber = TestData.memberOfMain(data, "subscriber").value();
        contributor = TestData.memberOfMain(data, "contributor").value();
        editor = TestData.memberOfMain(data, "editor").value();
        administrator = TestData.memberOfMain(data, "administrator").value();
    }

    @Test
    void typesCollectionAndItem() {
        JsonNode types = json(Http.as(null).get("/wp-json/wp/v2/types").asString());
        assertThat(fieldNames(types)).containsExactly("post", "page", "attachment", "wp_block", "qp_book");
        assertThat(fieldNames(types.get("post")))
                .containsExactly(
                        "description",
                        "hierarchical",
                        "has_archive",
                        "name",
                        "slug",
                        "icon",
                        "taxonomies",
                        "rest_base",
                        "rest_namespace",
                        "template",
                        "template_lock",
                        "_links");
        assertThat(types.at("/post/_links").toString())
                .isEqualTo("{\"collection\":[{\"href\":\"http://localhost/wp-json/wp/v2/types\"}],"
                        + "\"wp:items\":[{\"href\":\"http://localhost/wp-json/wp/v2/posts\"}],"
                        + "\"curies\":[{\"name\":\"wp\",\"href\":\"https://api.w.org/{rel}\",\"templated\":true}]}");
        Http.as("test-" + subscriber)
                .get("/wp-json/wp/v2/types?context=edit")
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_cannot_view\",\"message\":\"Sorry, you are not allowed to edit posts"
                        + " in this post type.\",\"data\":{\"status\":403}}"));
        Http.as(null)
                .get("/wp-json/wp/v2/types/nope")
                .then()
                .statusCode(404)
                .header("Allow", "GET")
                .body(
                        equalTo(
                                "{\"code\":\"rest_type_invalid\",\"message\":\"Invalid post type.\",\"data\":{\"status\":404}}"));
        Http.as("test-" + subscriber)
                .get("/wp-json/wp/v2/types/qp_hidden")
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body("code", equalTo("rest_cannot_read_type"))
                .body("message", equalTo("Cannot view post type."));
        // Part D 20
        Http.as(null)
                .get("/wp-json/wp/v2/types/qp_hidden")
                .then()
                .statusCode(401)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_cannot_read_type\",\"message\":\"Cannot view post type.\","
                        + "\"data\":{\"status\":401}}"));
        Http.as("test-" + contributor)
                .get("/wp-json/wp/v2/types/page?context=edit")
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_forbidden_context\",\"message\":\"Sorry, you are not allowed to edit"
                        + " posts in this post type.\",\"data\":{\"status\":403}}"));
        // Part D 20, edit context as administrator (alice's answers for post, page, attachment, wp_block)
        JsonNode post = json(Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/types/post?context=edit")
                .asString());
        assertThat(fieldNames(post))
                .containsExactly(
                        "capabilities",
                        "description",
                        "hierarchical",
                        "has_archive",
                        "visibility",
                        "viewable",
                        "labels",
                        "name",
                        "slug",
                        "icon",
                        "supports",
                        "taxonomies",
                        "rest_base",
                        "rest_namespace",
                        "template",
                        "template_lock",
                        "_links");
        assertThat(post.get("capabilities").toString())
                .isEqualTo("{\"edit_post\":\"edit_post\",\"read_post\":\"read_post\",\"delete_post\":\"delete_post\","
                        + "\"edit_posts\":\"edit_posts\",\"edit_others_posts\":\"edit_others_posts\","
                        + "\"delete_posts\":\"delete_posts\",\"publish_posts\":\"publish_posts\","
                        + "\"read_private_posts\":\"read_private_posts\",\"read\":\"read\","
                        + "\"delete_private_posts\":\"delete_private_posts\","
                        + "\"delete_published_posts\":\"delete_published_posts\","
                        + "\"delete_others_posts\":\"delete_others_posts\",\"edit_private_posts\":\"edit_private_posts\","
                        + "\"edit_published_posts\":\"edit_published_posts\",\"create_posts\":\"edit_posts\"}");
        assertThat(post.get("supports").toString())
                .isEqualTo("{\"title\":true,\"editor\":[{\"notes\":true}],\"author\":true,\"thumbnail\":true,"
                        + "\"excerpt\":true,\"trackbacks\":true,\"custom-fields\":true,\"comments\":true,"
                        + "\"revisions\":true,\"post-formats\":true,\"autosave\":true}");
        assertThat(post.get("visibility").toString()).isEqualTo("{\"show_in_nav_menus\":true,\"show_ui\":true}");
        assertThat(post.get("viewable")).isEqualTo(BooleanNode.TRUE);
        assertThat(post.get("has_archive")).isEqualTo(BooleanNode.FALSE);
        assertThat(post.get("description")).isEqualTo(TextNode.valueOf(""));
        assertThat(post.get("name")).isEqualTo(TextNode.valueOf("Posts"));
        assertThat(post.get("taxonomies").toString()).isEqualTo("[\"category\",\"post_tag\"]");
        assertThat(post.at("/labels/singular_name").asText()).isEqualTo("Post");
        assertThat(post.at("/labels/parent_item_colon")).isEqualTo(NullNode.getInstance());
        assertThat(fieldNames(post.get("labels")))
                .containsSubsequence("not_found_in_trash", "parent_item_colon", "all_items")
                .doesNotContain("template_name")
                .hasSize(35);
        // create_posts: post edit_posts, page edit_pages, attachment upload_files, wp_block publish_posts; icon
        // dashicons-admin-post / dashicons-admin-page / dashicons-admin-media / null; wp_block viewable false and its
        // read capability edit_posts; template [] and template_lock false everywhere (TypeExtras holds these values)
        Map<String, String> created = Map.of(
                "post", "edit_posts", "page", "edit_pages", "attachment", "upload_files", "wp_block", "publish_posts");
        Map<String, JsonNode> icons = Map.of(
                "post",
                TextNode.valueOf("dashicons-admin-post"),
                "page",
                TextNode.valueOf("dashicons-admin-page"),
                "attachment",
                TextNode.valueOf("dashicons-admin-media"),
                "wp_block",
                NullNode.getInstance());
        for (String type : List.of("post", "page", "attachment", "wp_block")) {
            JsonNode item = json(Http.as("test-" + administrator)
                    .get("/wp-json/wp/v2/types/" + type + "?context=edit")
                    .asString());
            assertThat(item.at("/capabilities/create_posts").asText()).as(type).isEqualTo(created.get(type));
            assertThat(fieldNames(item.get("capabilities")))
                    .as(type)
                    .hasSize(15)
                    .endsWith("create_posts");
            assertThat(item.get("icon")).as(type).isEqualTo(icons.get(type));
            assertThat(item.get("template").toString()).as(type).isEqualTo("[]");
            assertThat(item.get("template_lock")).as(type).isEqualTo(BooleanNode.FALSE);
            assertThat(item.get("rest_namespace")).as(type).isEqualTo(TextNode.valueOf("wp/v2"));
            assertThat(item.get("viewable")).as(type).isEqualTo(BooleanNode.valueOf(!type.equals("wp_block")));
        }
        JsonNode block = json(Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/types/wp_block?context=edit")
                .asString());
        assertThat(block.at("/capabilities/read").asText()).isEqualTo("edit_posts");
        assertThat(block.at("/capabilities/edit_post").asText()).isEqualTo("edit_block");
        assertThat(block.get("visibility").toString()).isEqualTo("{\"show_in_nav_menus\":false,\"show_ui\":true}");
        assertThat(block.get("supports").toString())
                .isEqualTo("{\"title\":true,\"excerpt\":true,\"editor\":true,\"revisions\":true,"
                        + "\"custom-fields\":true,\"autosave\":true}");
        assertThat(block.get("rest_base")).isEqualTo(TextNode.valueOf("blocks"));
        assertThat(block.at("/_links/wp:items/0/href").asText()).isEqualTo("http://localhost/wp-json/wp/v2/blocks");
        // embed context keeps name, slug, icon, rest_base, rest_namespace, template, template_lock
        assertThat(fieldNames(json(Http.as(null)
                        .get("/wp-json/wp/v2/types/page?context=embed")
                        .asString())))
                .containsExactly(
                        "name", "slug", "icon", "rest_base", "rest_namespace", "template", "template_lock", "_links");
    }

    @Test
    void theShownCapabilitiesAreTheEnforcedOnes() {
        // an administrator also granted edit_books may read the custom type's edit context
        AgroalDataSource data = Jdbc.dataSource();
        UserId librarian = TestData.profile(data, "librarian");
        Jdbc.update(
                data,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                TestData.MAIN.value(),
                librarian.value(),
                "[\"administrator\"]",
                "{\"edit_books\":true}");
        for (String type : List.of("post", "page", "attachment", "wp_block", "qp_book")) {
            JsonNode shown = json(Http.as("test-" + librarian.value())
                            .get("/wp-json/wp/v2/types/" + type + "?context=edit")
                            .asString())
                    .get("capabilities");
            Map<String, String> enforced =
                    types.capabilities(type).orElseThrow().names();
            assertThat(fieldNames(shown)).as(type).containsExactlyElementsOf(enforced.keySet());
            enforced.forEach((generic, name) ->
                    assertThat(shown.get(generic)).as(type + " " + generic).isEqualTo(TextNode.valueOf(name)));
        }
        // a custom type without meta mapping: its own names, no mapped ones
        JsonNode book = json(Http.as("test-" + librarian.value())
                .get("/wp-json/wp/v2/types/qp_book?context=edit")
                .asString());
        assertThat(book.get("capabilities").toString())
                .isEqualTo("{\"edit_post\":\"edit_book\",\"read_post\":\"read_book\",\"delete_post\":\"delete_book\","
                        + "\"edit_posts\":\"edit_books\",\"edit_others_posts\":\"edit_others_books\","
                        + "\"delete_posts\":\"delete_books\",\"publish_posts\":\"publish_books\","
                        + "\"read_private_posts\":\"read_private_books\",\"create_posts\":\"edit_books\"}");
    }

    @Test
    void theEditContextCollectionSkipsTypesTheCallerCannotEdit() {
        // a contributor edits posts (so attachments and patterns, whose edit_posts is edit_posts) but not pages
        JsonNode types = json(Http.as("test-" + contributor)
                .get("/wp-json/wp/v2/types?context=edit")
                .asString());
        assertThat(fieldNames(types)).containsExactly("post", "attachment", "wp_block");
        assertThat(fieldNames(types.get("post")).get(0)).isEqualTo("capabilities");
        assertThat(fieldNames(json(Http.as("test-" + editor)
                        .get("/wp-json/wp/v2/types?context=edit")
                        .asString())))
                .containsExactly("post", "page", "attachment", "wp_block");
    }

    @Test
    void objectsWordPressBuildsAsPhpObjectsAreKeptWholeByFields() {
        // labels and capabilities are PHP objects: a requested child keeps them whole; supports is an array
        JsonNode labels = json(Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/types/post?context=edit&_fields=labels.name")
                .asString());
        assertThat(fieldNames(labels)).containsExactly("labels");
        assertThat(fieldNames(labels.get("labels"))).contains("name", "singular_name", "menu_name");
        JsonNode capabilities = json(Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/types/post?context=edit&_fields=capabilities.read")
                .asString());
        assertThat(fieldNames(capabilities.get("capabilities"))).hasSize(15);
        assertThat(Http.as("test-" + administrator)
                        .get("/wp-json/wp/v2/types/post?context=edit&_fields=supports.title")
                        .asString())
                .isEqualTo("{\"supports\":{\"title\":true}}");
        // the taxonomies controller tests its fields by exact name: a child selects nothing
        assertThat(Http.as("test-" + administrator)
                        .get("/wp-json/wp/v2/taxonomies/category?context=edit&_fields=labels.name")
                        .asString())
                .isEqualTo("[]");
        JsonNode taxonomyCapabilities = json(Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/taxonomies/category?context=edit&_fields=capabilities")
                .asString());
        assertThat(fieldNames(taxonomyCapabilities.get("capabilities"))).hasSize(4);
        // collections are keyed by slug, so the server's reduction selects slugs (WordPress's quirk)
        assertThat(Http.as(null).get("/wp-json/wp/v2/types?_fields=name").asString())
                .isEqualTo("[]");
        // a slug's child selects no field of the item, whose empty data then stays empty
        assertThat(Http.as(null).get("/wp-json/wp/v2/types?_fields=post.slug").asString())
                .isEqualTo("{\"post\":[]}");
    }

    @Test
    void taxonomies() {
        assertThat(fieldNames(
                        json(Http.as(null).get("/wp-json/wp/v2/taxonomies").asString())))
                .containsExactly("category", "post_tag");
        assertThat(fieldNames(json(
                        Http.as(null).get("/wp-json/wp/v2/taxonomies?type=post").asString())))
                .containsExactly("category", "post_tag");
        Http.as(null)
                .get("/wp-json/wp/v2/taxonomies?type=page")
                .then()
                .statusCode(200)
                .body(equalTo("{}"));
        Http.as(null)
                .get("/wp-json/wp/v2/taxonomies?type=nope")
                .then()
                .statusCode(200)
                .body(equalTo("{}"));
        Http.as(null)
                .get("/wp-json/wp/v2/taxonomies?context=edit")
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_cannot_view\",\"message\":\"Sorry, you are not allowed to manage terms"
                        + " in this taxonomy.\",\"data\":{\"status\":401}}"));
        // Part D 21
        Http.as("test-" + subscriber)
                .get("/wp-json/wp/v2/taxonomies?context=edit")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_view"))
                .body("message", equalTo("Sorry, you are not allowed to manage terms in this taxonomy."));
        // a type whose taxonomies the caller may not assign
        Http.as("test-" + contributor)
                .get("/wp-json/wp/v2/taxonomies?context=edit&type=page")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_view"))
                .body("message", equalTo("Sorry, you are not allowed to manage terms in this taxonomy."));
        Http.as(null)
                .get("/wp-json/wp/v2/taxonomies/qp_hidden_tax")
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\","
                        + "\"data\":{\"status\":401}}"));
        Http.as("test-" + subscriber)
                .get("/wp-json/wp/v2/taxonomies/post_tag?context=edit")
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_forbidden_context\",\"message\":\"Sorry, you are not allowed to"
                        + " manage terms in this taxonomy.\",\"data\":{\"status\":403}}"));
        Http.as(null)
                .get("/wp-json/wp/v2/taxonomies/nope")
                .then()
                .statusCode(404)
                .header("Allow", "GET")
                .body(
                        equalTo(
                                "{\"code\":\"rest_taxonomy_invalid\",\"message\":\"Invalid taxonomy.\",\"data\":{\"status\":404}}"));
        // category in edit context as administrator (Part D 21)
        JsonNode category = json(Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/taxonomies/category?context=edit")
                .asString());
        assertThat(fieldNames(category))
                .containsExactly(
                        "name",
                        "slug",
                        "capabilities",
                        "description",
                        "labels",
                        "types",
                        "show_cloud",
                        "hierarchical",
                        "rest_base",
                        "rest_namespace",
                        "visibility",
                        "_links");
        assertThat(category.get("capabilities").toString())
                .isEqualTo("{\"manage_terms\":\"manage_categories\",\"edit_terms\":\"edit_categories\","
                        + "\"delete_terms\":\"delete_categories\",\"assign_terms\":\"assign_categories\"}");
        assertThat(category.get("visibility").toString())
                .isEqualTo("{\"public\":true,\"publicly_queryable\":true,\"show_admin_column\":true,"
                        + "\"show_in_nav_menus\":true,\"show_in_quick_edit\":true,\"show_ui\":true}");
        assertThat(category.get("types").toString()).isEqualTo("[\"post\"]");
        assertThat(category.get("show_cloud")).isEqualTo(BooleanNode.TRUE);
        assertThat(category.get("hierarchical")).isEqualTo(BooleanNode.TRUE);
        assertThat(category.get("rest_base")).isEqualTo(TextNode.valueOf("categories"));
        assertThat(category.get("rest_namespace")).isEqualTo(TextNode.valueOf("wp/v2"));
        assertThat(category.get("name")).isEqualTo(TextNode.valueOf("Categories"));
        assertThat(category.at("/labels/singular_name").asText()).isEqualTo("Category");
        // labels WordPress leaves unset for a hierarchical taxonomy are null, in WordPress's order
        assertThat(category.at("/labels/popular_items")).isEqualTo(NullNode.getInstance());
        assertThat(fieldNames(category.get("labels")))
                .startsWith("name", "singular_name", "search_items", "popular_items", "all_items")
                .endsWith("menu_name", "name_admin_bar", "template_name")
                .hasSize(31);
        assertThat(category.at("/_links/wp:items/0/href").asText())
                .isEqualTo("http://localhost/wp-json/wp/v2/categories");
        // view context: the fields WordPress shows everyone
        assertThat(Http.as(null).get("/wp-json/wp/v2/taxonomies/post_tag").asString())
                .isEqualTo("{\"name\":\"Tags\",\"slug\":\"post_tag\",\"description\":\"\",\"types\":[\"post\"],"
                        + "\"hierarchical\":false,\"rest_base\":\"tags\",\"rest_namespace\":\"wp\\/v2\","
                        + "\"_links\":{\"collection\":[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/taxonomies\"}],"
                        + "\"wp:items\":[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/tags\"}],"
                        + CURIES + "}}");
    }

    @Test
    void statuses() {
        // Part D 22
        Http.as(null)
                .get("/wp-json/wp/v2/statuses")
                .then()
                .statusCode(200)
                .body(equalTo("{\"publish\":{\"name\":\"Published\",\"public\":true,\"queryable\":true,"
                        + "\"slug\":\"publish\",\"date_floating\":false,\"_links\":{\"archives\":"
                        + "[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/posts\"}]}}}"));
        assertThat(fieldNames(json(Http.as("test-" + subscriber)
                        .get("/wp-json/wp/v2/statuses")
                        .asString())))
                .containsExactly("publish");
        JsonNode all = json(Http.as("test-" + contributor)
                .get("/wp-json/wp/v2/statuses?context=edit")
                .asString());
        assertThat(fieldNames(all)).containsExactly("publish", "future", "draft", "pending", "private", "trash");
        assertThat(all.get("draft").toString())
                .startsWith(
                        "{\"name\":\"Draft\",\"private\":false,\"protected\":true,"
                                + "\"public\":false,\"queryable\":false,\"show_in_list\":true,\"slug\":\"draft\",\"date_floating\":true,");
        assertThat(all.get("trash").toString())
                .startsWith("{\"name\":\"Trash\",\"private\":false,\"protected\":false,\"public\":false,"
                        + "\"queryable\":false,\"show_in_list\":false,\"slug\":\"trash\",\"date_floating\":false,");
        assertThat(all.get("publish").toString())
                .startsWith("{\"name\":\"Published\",\"private\":false,\"protected\":false,\"public\":true,"
                        + "\"queryable\":true,\"show_in_list\":true,\"slug\":\"publish\",\"date_floating\":false,");
        assertThat(all.at("/future/protected")).isEqualTo(BooleanNode.TRUE);
        assertThat(all.at("/private/private")).isEqualTo(BooleanNode.TRUE);
        assertThat(all.at("/pending/date_floating")).isEqualTo(BooleanNode.TRUE);
        List<String> labels = List.of("Published", "Scheduled", "Draft", "Pending", "Private", "Trash");
        List<String> names = fieldNames(all);
        for (int i = 0; i < names.size(); i++) {
            assertThat(all.at("/" + names.get(i) + "/name").asText()).isEqualTo(labels.get(i));
        }
        assertThat(all.at("/publish/_links/archives/0/href").asText())
                .isEqualTo("http://localhost/wp-json/wp/v2/posts");
        assertThat(all.at("/draft/_links/archives/0/href").asText())
                .isEqualTo("http://localhost/wp-json/wp/v2/posts?status=draft");
        Http.as(null)
                .get("/wp-json/wp/v2/statuses?context=edit")
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_cannot_view\",\"message\":\"Sorry, you are not allowed to manage post"
                        + " statuses.\",\"data\":{\"status\":401}}"));
        // internal statuses are never visible, not even to administrators
        Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/statuses/auto-draft")
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body(equalTo("{\"code\":\"rest_cannot_read_status\",\"message\":\"Cannot view status.\","
                        + "\"data\":{\"status\":403}}"));
        Http.as(null)
                .get("/wp-json/wp/v2/statuses/draft")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_read_status"));
        Http.as(null)
                .get("/wp-json/wp/v2/statuses/nope")
                .then()
                .statusCode(404)
                .header("Allow", nullValue())
                .body(
                        equalTo(
                                "{\"code\":\"rest_status_invalid\",\"message\":\"Invalid status.\",\"data\":{\"status\":404}}"));
        // the item has no edit-context check beyond visibility, and its links are added whatever _fields says
        JsonNode trash = json(Http.as("test-" + contributor)
                .get("/wp-json/wp/v2/statuses/trash?context=edit&_fields=slug")
                .asString());
        assertThat(fieldNames(trash)).containsExactly("slug", "_links");
        assertThat(trash.at("/_links/archives/0/href").asText())
                .isEqualTo("http://localhost/wp-json/wp/v2/posts?status=trash");
        // the statuses controller tests its fields by exact name
        assertThat(Http.as(null)
                        .get("/wp-json/wp/v2/statuses/publish?_fields=name.x")
                        .asString())
                .isEqualTo(
                        "{\"_links\":{\"archives\":[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/posts\"}]}}");
    }

    @Test
    void headSkipsTheBodies() {
        for (String url : List.of(
                "/wp-json/wp/v2/types",
                "/wp-json/wp/v2/taxonomies",
                "/wp-json/wp/v2/statuses",
                "/wp-json/wp/v2/types/post")) {
            Http.as(null)
                    .head(url)
                    .then()
                    .statusCode(200)
                    .header("Allow", "GET")
                    .body(equalTo(""));
        }
    }

    @Test
    void optionsDescribesTheRoutesWithTheirSchemas() {
        JsonNode types = json(Http.as(null).options("/wp-json/wp/v2/types/post").asString());
        assertThat(fieldNames(types)).containsExactly("namespace", "methods", "endpoints", "schema");
        assertThat(fieldNames(types.at("/schema/properties")))
                .containsExactly(
                        "capabilities",
                        "description",
                        "hierarchical",
                        "viewable",
                        "labels",
                        "name",
                        "slug",
                        "supports",
                        "has_archive",
                        "taxonomies",
                        "rest_base",
                        "rest_namespace",
                        "visibility",
                        "icon",
                        "template",
                        "template_lock");
        assertThat(types.at("/schema/properties/template_lock").toString())
                .isEqualTo("{\"type\":[\"string\",\"boolean\"],\"enum\":[\"all\",\"insert\",\"contentOnly\",false],"
                        + "\"description\":\"The template_lock associated with the post type, or false if none.\","
                        + "\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]}");
        JsonNode statuses =
                json(Http.as(null).options("/wp-json/wp/v2/statuses").asString());
        assertThat(fieldNames(statuses.at("/schema/properties")))
                .containsExactly(
                        "name", "private", "protected", "public", "queryable", "show_in_list", "slug", "date_floating");
        JsonNode taxonomies =
                json(Http.as(null).options("/wp-json/wp/v2/taxonomies").asString());
        assertThat(taxonomies.at("/schema/title").asText()).isEqualTo("taxonomy");
        assertThat(fieldNames(taxonomies.at("/schema/properties/visibility/properties")))
                .containsExactly(
                        "public",
                        "publicly_queryable",
                        "show_ui",
                        "show_admin_column",
                        "show_in_nav_menus",
                        "show_in_quick_edit");
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
