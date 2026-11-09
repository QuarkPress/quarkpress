package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;

/**
 * The media routes' registration: the collection, the item and the post-process route (no image editing, client-side
 * upload, revisions or autosaves route), none batchable, with WordPress's arguments and attachment schema as their
 * OPTIONS describe them. Until each endpoint is served, it answers as no route would.
 */
class MediaRoutesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(MediaRoutesDbTest.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEDIA = "/wp/v2/media";
    private static final String ITEM = "/wp/v2/media/(?P<id>[\\d]+)";
    private static final String POST_PROCESS = "/wp/v2/media/(?P<id>[\\d]+)/post-process";

    private static final String NO_ROUTE =
            "{\"code\":\"rest_no_route\",\"message\":\"No route was found matching the URL"
                    + " and request method.\",\"data\":{\"status\":404}}";

    AgroalDataSource data;
    long alice;
    UserId carol;

    @BeforeEach
    void users() {
        data = Jdbc.dataSource();
        UserId admin = TestData.profile(data, "alice");
        TestData.superAdmin(data, admin);
        alice = admin.value();
        carol = TestData.memberOfMain(data, "author");
    }

    @Test
    void theThreeRoutesAreRegisteredWithoutBatching() {
        JsonNode routes = json(Http.as(null).get("/wp-json/wp/v2").asString()).get("routes");
        List<String> paths = fieldNames(routes);
        assertThat(paths).containsSubsequence("/wp/v2/pages", MEDIA, ITEM, POST_PROCESS, "/wp/v2/types");
        assertThat(paths.stream().filter(path -> path.startsWith(MEDIA))).containsExactly(MEDIA, ITEM, POST_PROCESS);
        assertThat(paths)
                .noneMatch(path -> path.endsWith("/edit")
                        || path.endsWith("/sideload")
                        || path.endsWith("/finalize")
                        || (path.startsWith(MEDIA) && path.contains("revisions"))
                        || (path.startsWith(MEDIA) && path.contains("autosaves")));
        for (String route : List.of(MEDIA, ITEM, POST_PROCESS)) {
            for (JsonNode endpoint : routes.get(route).get("endpoints")) {
                assertThat(endpoint.has("allow_batch")).as(route).isFalse();
            }
        }
        // the posts routes still batch: the flag is the media routes' own
        assertThat(routes.at("/~1wp~1v2~1posts/endpoints/0/allow_batch").toString())
                .isEqualTo("{\"v1\":true}");
    }

    @Test
    void optionsDescribeTheCollection() {
        JsonNode options = json(Http.as("test-" + alice)
                .options("/wp-json/wp/v2/media")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(options.get("namespace").asText()).isEqualTo("wp/v2");
        assertThat(options.get("methods").toString()).isEqualTo("[\"GET\",\"POST\"]");
        JsonNode list = options.at("/endpoints/0");
        assertThat(list.get("methods").toString()).isEqualTo("[\"GET\"]");
        assertThat(fieldNames(list.get("args")))
                .containsExactly(
                        "context",
                        "page",
                        "per_page",
                        "search",
                        "after",
                        "modified_after",
                        "author",
                        "author_exclude",
                        "before",
                        "modified_before",
                        "exclude",
                        "include",
                        "search_semantics",
                        "offset",
                        "order",
                        "orderby",
                        "parent",
                        "parent_exclude",
                        "search_columns",
                        "slug",
                        "status",
                        "media_type",
                        "mime_type");
        JsonNode args = list.get("args");
        assertThat(args.get("status").toString())
                .isEqualTo("{\"default\":\"inherit\",\"description\":\"Limit result set to posts assigned one or more"
                        + " statuses.\",\"type\":\"array\",\"items\":{\"enum\":[\"inherit\",\"private\",\"trash\"],"
                        + "\"type\":\"string\"},\"required\":false}");
        assertThat(args.get("media_type").toString())
                .isEqualTo("{\"default\":null,\"description\":\"Limit result set to attachments of a particular media"
                        + " type or media types.\",\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":"
                        + "[\"image\",\"video\",\"audio\",\"text\",\"application\"]},\"required\":false}");
        assertThat(args.get("mime_type").toString())
                .isEqualTo("{\"default\":null,\"description\":\"Limit result set to attachments of a particular MIME"
                        + " type or MIME types.\",\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                        + "\"required\":false}");
        assertThat(args.get("parent").toString())
                .isEqualTo("{\"description\":\"Limit result set to items with particular parent IDs.\","
                        + "\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"default\":[],\"required\":false}");
        JsonNode create = options.at("/endpoints/1");
        assertThat(create.get("methods").toString()).isEqualTo("[\"POST\"]");
        assertThat(fieldNames(create.get("args")))
                .containsExactly(
                        "date",
                        "date_gmt",
                        "slug",
                        "status",
                        "title",
                        "author",
                        "featured_media",
                        "comment_status",
                        "ping_status",
                        "meta",
                        "template",
                        "alt_text",
                        "caption",
                        "description",
                        "post",
                        "generate_sub_sizes",
                        "convert_format");
        assertThat(create.at("/args/caption").toString())
                .isEqualTo("{\"description\":\"The attachment caption.\",\"type\":\"object\",\"properties\":"
                        + "{\"raw\":{\"description\":\"Caption for the attachment, as it exists in the database.\","
                        + "\"type\":\"string\",\"context\":[\"edit\"]},\"rendered\":{\"description\":\"HTML caption"
                        + " for the attachment, transformed for display.\",\"type\":\"string\",\"context\":[\"view\","
                        + "\"edit\",\"embed\"],\"readonly\":true}},\"required\":false}");
        assertThat(create.at("/args/status/enum").toString())
                .isEqualTo("[\"publish\",\"future\",\"draft\",\"pending\",\"private\"]");
        assertThat(create.at("/args/generate_sub_sizes").toString())
                .isEqualTo("{\"type\":\"boolean\",\"default\":true,\"description\":\"Whether to generate image sub"
                        + " sizes.\",\"required\":false}");
        assertThat(create.at("/args/convert_format").toString())
                .isEqualTo("{\"type\":\"boolean\",\"default\":true,\"description\":\"Whether to convert image"
                        + " formats.\",\"required\":false}");
        JsonNode schema = options.get("schema");
        assertThat(schema.get("title").asText()).isEqualTo("attachment");
        assertThat(fieldNames(schema.get("properties")))
                .containsExactly(
                        "date",
                        "date_gmt",
                        "guid",
                        "id",
                        "link",
                        "modified",
                        "modified_gmt",
                        "slug",
                        "status",
                        "type",
                        "permalink_template",
                        "generated_slug",
                        "class_list",
                        "title",
                        "author",
                        "featured_media",
                        "comment_status",
                        "ping_status",
                        "meta",
                        "template",
                        "alt_text",
                        "caption",
                        "description",
                        "media_type",
                        "mime_type",
                        "media_details",
                        "post",
                        "source_url",
                        "missing_image_sizes",
                        "filename",
                        "filesize",
                        "exif_orientation",
                        "image_quality",
                        "image_output_format",
                        "image_save_progressive");
        JsonNode properties = schema.get("properties");
        assertThat(properties.get("post").toString())
                .isEqualTo("{\"description\":\"The ID for the associated post of the attachment.\","
                        + "\"type\":\"integer\",\"context\":[\"view\",\"edit\"]}");
        assertThat(properties.get("filesize").toString())
                .isEqualTo("{\"description\":\"Attachment file size in bytes.\",\"type\":[\"integer\",\"null\"],"
                        + "\"context\":[\"view\",\"edit\"],\"readonly\":true}");
        assertThat(properties.get("media_type").toString())
                .isEqualTo("{\"description\":\"Attachment type.\",\"type\":\"string\",\"enum\":[\"image\",\"file\"],"
                        + "\"context\":[\"view\",\"edit\",\"embed\"],\"readonly\":true}");
        // a type with REST meta keys keeps them as an object
        JsonNode posts = json(Http.as("test-" + alice)
                .options("/wp-json/wp/v2/posts")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(posts.at("/schema/properties/meta/properties").isObject()).isTrue();
        assertThat(posts.at("/schema/properties/meta/properties").has("footnotes"))
                .isTrue();
        // no meta key is registered for attachments: an empty list, as WordPress's empty PHP array prints
        assertThat(properties.at("/meta/properties").toString()).isEqualTo("[]");
        assertThat(create.at("/args/meta/properties").toString()).isEqualTo("[]");
        assertThat(properties.at("/description/properties/rendered/context").toString())
                .isEqualTo("[\"view\",\"edit\"]");
        // the site's registered sizes, in their order
        assertThat(fieldNames(properties.at("/image_quality/properties/sizes/properties")))
                .containsExactly("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");
        assertThat(properties
                        .at("/image_quality/properties/sizes/properties/large")
                        .toString())
                .isEqualTo("{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}");
        List<String> rels = new ArrayList<>();
        schema.get("links").forEach(link -> rels.add(link.get("rel").asText()));
        assertThat(rels)
                .containsExactly("https://api.w.org/action-unfiltered-html", "https://api.w.org/action-assign-author");
        assertThat(schema.at("/links/0/href").asText()).isEqualTo("http://localhost/wp-json/wp/v2/media/{id}");
    }

    @Test
    void theItemRouteDeclaresItsId() {
        long id = MediaFixturesForRest.upload(TestData.MAIN, carol, "photo.jpg", false)
                .post()
                .id()
                .value();
        JsonNode options = json(Http.as("test-" + alice)
                .options("/wp-json/wp/v2/media/" + id)
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(options.get("methods").toString()).isEqualTo("[\"GET\",\"POST\",\"PUT\",\"PATCH\",\"DELETE\"]");
        JsonNode endpoints = options.get("endpoints");
        assertThat(endpoints).hasSize(3);
        for (JsonNode endpoint : endpoints) {
            assertThat(fieldNames(endpoint.get("args")).get(0)).isEqualTo("id");
            assertThat(endpoint.at("/args/id").toString())
                    .isEqualTo("{\"description\":\"Unique identifier for the post.\",\"type\":\"integer\","
                            + "\"required\":false}");
        }
        assertThat(fieldNames(endpoints.at("/0/args"))).containsExactly("id", "context");
        assertThat(endpoints.at("/1/methods").toString()).isEqualTo("[\"POST\",\"PUT\",\"PATCH\"]");
        assertThat(fieldNames(endpoints.at("/1/args")))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "slug",
                        "status",
                        "title",
                        "author",
                        "featured_media",
                        "comment_status",
                        "ping_status",
                        "meta",
                        "template",
                        "alt_text",
                        "caption",
                        "description",
                        "post");
        assertThat(fieldNames(endpoints.at("/2/args"))).containsExactly("id", "force");
        assertThat(options.at("/schema/title").asText()).isEqualTo("attachment");
    }

    @Test
    void postProcessHasNoSchema() {
        JsonNode options = json(Http.as("test-" + alice)
                .options("/wp-json/wp/v2/media/5/post-process")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(options)).containsExactly("namespace", "methods", "endpoints");
        assertThat(options.get("endpoints")).hasSize(1);
        assertThat(options.at("/endpoints/0").toString())
                .isEqualTo("{\"methods\":[\"POST\"],\"args\":{\"id\":{\"description\":\"Unique identifier for the"
                        + " attachment.\",\"type\":\"integer\",\"required\":false},\"action\":{\"type\":\"string\","
                        + "\"enum\":[\"create-image-subsizes\"],\"required\":true}}}");
    }

    @Test
    void missingRoutesAre404() {
        long id = MediaFixturesForRest.upload(TestData.MAIN, carol, "missing.png", false)
                .post()
                .id()
                .value();
        Http.as("test-" + alice)
                .contentType("application/json")
                .body("{\"src\":\"http://localhost/wp-content/uploads/missing.png\"}")
                .post("/wp-json/wp/v2/media/" + id + "/edit")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
        Http.as("test-" + alice)
                .get("/wp-json/wp/v2/media/" + id + "/post-process")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
        Http.as("test-" + alice)
                .delete("/wp-json/wp/v2/media")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
        Http.as("test-" + alice)
                .get("/wp-json/wp/v2/media/" + id + "/revisions")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
        // the registered endpoints answer as no route until their task serves them, so Allow names none of them
        Http.as("test-" + alice)
                .contentType("application/json")
                .body("{}")
                .post("/wp-json/wp/v2/media")
                .then()
                .statusCode(404)
                .header("Allow", equalTo("GET"))
                .body(equalTo(NO_ROUTE));
        // the item is read; its update and delete are not served yet
        Http.as("test-" + alice)
                .get("/wp-json/wp/v2/media/" + id)
                .then()
                .statusCode(200)
                .header("Allow", equalTo("GET"));
        for (String path : List.of("/sideload", "/finalize", "/autosaves")) {
            Http.as("test-" + alice)
                    .post("/wp-json/wp/v2/media/" + id + path)
                    .then()
                    .statusCode(404)
                    .body(equalTo(NO_ROUTE));
        }
    }

    // ---- helpers

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
