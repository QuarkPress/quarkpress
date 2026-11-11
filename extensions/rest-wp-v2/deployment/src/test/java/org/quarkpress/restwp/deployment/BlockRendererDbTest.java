package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.blocks.BlockRenderContext;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeProvider;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's block renderer route ({@code /wp/v2/block-renderer/<namespace>/<name>}) over the registered renderer
 * beans, as WordPress 7.1.3 answered it: the arguments checked first with their defaults (so no {@code context=edit} is
 * 400 for everyone), then the permission ({@code edit_post} on a given post, else {@code edit_posts}), then the
 * case-sensitive lookup (404 {@code block_invalid} for a type without renderer, with {@code Allow}); a registered
 * type's attributes are its own only. Members of the main site: alice (administrator), bob (editor), carol (author),
 * dave (contributor) and erin (subscriber).
 */
class BlockRendererDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            BlockRendererDbTest.class,
            TestBlocks.class,
            TestBlocks.Renderer.class,
            RendererTypes.class,
            EchoRenderer.class,
            BoomRenderer.class,
            NullRenderer.class);

    private static final String ROUTE = "/wp-json/wp/v2/block-renderer";

    /** WordPress's answer without {@code context=edit} (b13). */
    private static final String NO_CONTEXT = "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s):"
            + " context\",\"data\":{\"status\":400,\"params\":{\"context\":\"context is not edit.\"},\"details\":"
            + "{\"context\":{\"code\":\"rest_not_in_enum\",\"message\":\"context is not edit.\",\"data\":null}}}}";

    /** WordPress's answer to a {@code post_id} that is not an integer (b13). */
    private static final String POST_ID_ABC = "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s):"
            + " post_id\",\"data\":{\"status\":400,\"params\":{\"post_id\":\"post_id is not of type integer.\"},"
            + "\"details\":{\"post_id\":{\"code\":\"rest_invalid_type\",\"message\":\"post_id is not of type"
            + " integer.\",\"data\":{\"param\":\"post_id\"}}}}}";

    private static final String INVALID_BLOCK =
            "{\"code\":\"block_invalid\",\"message\":\"Invalid block.\",\"data\":{\"status\":404}}";

    /** WordPress's OPTIONS body of the route (b14), whatever the block named. */
    private static final String OPTIONS = "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\",\"POST\"],\"endpoints\":"
            + "[{\"methods\":[\"GET\",\"POST\"],\"args\":{\"name\":{\"description\":\"Unique registered name for the"
            + " block.\",\"type\":\"string\",\"required\":false},\"context\":{\"description\":\"Scope under which the"
            + " request is made; determines fields present in response.\",\"type\":\"string\",\"enum\":[\"edit\"],"
            + "\"default\":\"view\",\"required\":false},\"attributes\":{\"description\":\"Attributes for the"
            + " block.\",\"type\":\"object\",\"default\":[],\"required\":false},\"post_id\":{\"description\":\"ID of"
            + " the post context.\",\"type\":\"integer\",\"required\":false}}}],\"schema\":{\"$schema\":"
            + "\"http:\\/\\/json-schema.org\\/schema#\",\"title\":\"rendered-block\",\"type\":\"object\","
            + "\"properties\":{\"rendered\":{\"description\":\"The rendered block.\",\"type\":\"string\","
            + "\"required\":true,\"context\":[\"edit\"]}}}}";

    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;
    static long bobsDraft;
    static long elsewhere;

    @BeforeAll
    static void members() {
        AgroalDataSource data = Jdbc.dataSource();
        alice = TestData.memberOfMain(data, "administrator").value();
        bob = TestData.memberOfMain(data, "editor").value();
        carol = TestData.memberOfMain(data, "author").value();
        dave = TestData.memberOfMain(data, "contributor").value();
        erin = TestData.memberOfMain(data, "subscriber").value();
        bobsDraft = TestData.post(data, TestData.MAIN, UserId.of(bob), "post", "draft")
                .value();
        SiteId other = TestData.site(data);
        TestData.member(data, other, UserId.of(alice), "[\"administrator\"]");
        elsewhere =
                TestData.post(data, other, UserId.of(alice), "post", "publish").value();
    }

    static RequestSpecification as(long user) {
        return Http.as(user == 0 ? null : "test-" + user);
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** WordPress's 400 for one attribute failure: the arguments' message and the failure under details. */
    static String invalidAttributes(String detail) {
        JsonNode error = json(detail);
        return "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): attributes\",\"data\":"
                + "{\"status\":400,\"params\":{\"attributes\":" + error.get("message") + "},\"details\":"
                + "{\"attributes\":" + detail + "}}}";
    }

    static String cannotRead(String message, int status) {
        return "{\"code\":\"block_cannot_read\",\"message\":\"Sorry, you are not allowed to read blocks " + message
                + ".\",\"data\":{\"status\":" + status + "}}";
    }

    static String rendered(Response response) {
        response.then().statusCode(200);
        return json(response.asString()).get("rendered").asText();
    }

    @Test
    void theOrderOfChecks() {
        // validation first, defaults included: no context is 400 for everyone, with Allow only for a permitted caller
        Response anonymous = as(0).get(ROUTE + "/acme/dyn");
        anonymous.then().statusCode(400).body(equalTo(NO_CONTEXT));
        assertThat(anonymous.getHeader("Allow")).isNull();
        as(alice)
                .get(ROUTE + "/acme/dyn")
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST")
                .body(equalTo(NO_CONTEXT));
        // then the permission, before the lookup
        for (String name : new String[] {"/acme/dyn", "/acme/nope"}) {
            as(0).get(ROUTE + name + "?context=edit")
                    .then()
                    .statusCode(401)
                    .body(equalTo(cannotRead("as this user", 401)));
        }
        Response erinsAnswer = as(erin).get(ROUTE + "/acme/dyn?context=edit");
        erinsAnswer.then().statusCode(403).body(equalTo(cannotRead("as this user", 403)));
        assertThat(erinsAnswer.getHeader("Allow")).isNull();
        // a post: edit_post on it, a missing post or one of another site refused alike
        as(carol)
                .get(ROUTE + "/acme/dyn?context=edit&post_id=" + bobsDraft)
                .then()
                .statusCode(403)
                .body(equalTo(cannotRead("of this post", 403)));
        as(dave).get(ROUTE + "/acme/dyn?context=edit&post_id=999999")
                .then()
                .statusCode(403)
                .body(equalTo(cannotRead("of this post", 403)));
        as(alice)
                .get(ROUTE + "/acme/dyn?context=edit&post_id=" + elsewhere)
                .then()
                .statusCode(403)
                .body(equalTo(cannotRead("of this post", 403)));
        as(alice)
                .get(ROUTE + "/acme/dyn?context=edit&post_id=99999999999999999999")
                .then()
                .statusCode(403)
                .body(equalTo(cannotRead("of this post", 403)));
        as(0).get(ROUTE + "/acme/dyn?context=edit&post_id=" + bobsDraft)
                .then()
                .statusCode(401)
                .body(equalTo(cannotRead("of this post", 401)));
        // a post_id that is not an integer fails validation, anonymous too
        for (long user : new long[] {0, alice}) {
            as(user).get(ROUTE + "/acme/dyn?context=edit&post_id=abc")
                    .then()
                    .statusCode(400)
                    .body(equalTo(POST_ID_ABC));
        }
        // 0 and below take the edit_posts path
        assertThat(rendered(as(alice).get(ROUTE + "/acme/echo?context=edit&post_id=0")))
                .endsWith("|none");
        assertThat(rendered(as(alice).get(ROUTE + "/acme/echo?context=edit&post_id=-1")))
                .endsWith("|none");
        assertThat(rendered(as(bob).get(ROUTE + "/acme/echo?context=edit&post_id=" + bobsDraft)))
                .endsWith("|" + bobsDraft);
        // then the lookup: a type without renderer, or none at all, is 404 with Allow (the permission passed)
        for (String name : new String[] {"/acme/notice", "/acme/nope"}) {
            as(alice)
                    .get(ROUTE + name + "?context=edit")
                    .then()
                    .statusCode(404)
                    .header("Allow", "GET, POST")
                    .body(equalTo(INVALID_BLOCK));
        }
        // the context and attributes are checked together, post_id only once they pass
        Response both = as(0).get(ROUTE + "/acme/echo?attributes[foo]=1&post_id=abc");
        both.then().statusCode(400);
        assertThat(json(both.asString()).get("message").asText())
                .isEqualTo("Invalid parameter(s): context, attributes");
    }

    @Test
    void attributesAreValidated() {
        String forbidden = "{\"code\":\"rest_additional_properties_forbidden\","
                + "\"message\":\"foo is not a valid property of Object.\",\"data\":null}";
        for (long user : new long[] {0, alice}) {
            as(user).get(ROUTE + "/acme/echo?context=edit&attributes[foo]=1")
                    .then()
                    .statusCode(400)
                    .body(equalTo(invalidAttributes(forbidden)));
        }
        as(alice)
                .get(ROUTE + "/acme/echo?context=edit&attributes[showLabel]=notbool")
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST")
                .body(
                        equalTo(
                                invalidAttributes(
                                        "{\"code\":\"rest_invalid_type\","
                                                + "\"message\":\"[showLabel] is not of type boolean.\",\"data\":{\"param\":\"[showLabel]\"}}")));
        as(alice)
                .get(ROUTE + "/acme/echo?context=edit&attributes[message][]=x")
                .then()
                .statusCode(400)
                .body(equalTo(invalidAttributes("{\"code\":\"rest_invalid_type\","
                        + "\"message\":\"[message] is not of type string.\",\"data\":{\"param\":\"[message]\"}}")));
        // the attributes the type's supports add are its attributes too
        assertThat(rendered(as(alice).get(ROUTE + "/acme/echo?context=edit&attributes[className]=x")))
                .isEqualTo("{\"className\":\"x\",\"message\":\"\",\"showLabel\":false}|none");
        // anything goes for an unregistered type: the permission, then the lookup, answer
        as(alice)
                .get(ROUTE + "/acme/nope?context=edit&attributes=5")
                .then()
                .statusCode(404)
                .body(equalTo(INVALID_BLOCK));
        as(erin).get(ROUTE + "/acme/nope?context=edit&attributes=5")
                .then()
                .statusCode(403)
                .body(equalTo(cannotRead("as this user", 403)));
    }

    @Test
    void rendersThroughTheBean() {
        // sanitized by the attributes' schemas ("true" is a boolean), the missing ones from their defaults
        assertThat(rendered(as(dave).get(ROUTE + "/acme/echo?context=edit&attributes[message]=Hi")))
                .isEqualTo("{\"message\":\"Hi\",\"showLabel\":false}|none");
        assertThat(rendered(as(dave).get(ROUTE + "/acme/echo?context=edit&attributes[showLabel]=true")))
                .isEqualTo("{\"showLabel\":true,\"message\":\"\"}|none");
        Response posted = as(dave).contentType("application/json")
                .body("{\"attributes\":{\"message\":\"Hi\"}}")
                .post(ROUTE + "/acme/echo?context=edit");
        posted.then().header("Allow", "GET, POST");
        assertThat(rendered(posted)).isEqualTo("{\"message\":\"Hi\",\"showLabel\":false}|none");
        as(dave).get(ROUTE + "/acme/dyn?context=edit")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST")
                .body(equalTo("{\"rendered\":\"<p>dyn<\\/p>\"}"));
        as(dave).get(ROUTE + "/acme/dyn?context=edit&_fields=rendered")
                .then()
                .statusCode(200)
                .body(equalTo("{\"rendered\":\"<p>dyn<\\/p>\"}"));
        as(dave).get(ROUTE + "/acme/dyn?context=edit&_fields=other")
                .then()
                .statusCode(200)
                .body(equalTo("[]"));
        as(dave).head(ROUTE + "/acme/dyn?context=edit").then().statusCode(200).body(equalTo(""));
        Response options = as(alice).options(ROUTE + "/acme/dyn");
        options.then().statusCode(200).header("Allow", "GET, POST");
        assertThat(options.asString()).isEqualTo(OPTIONS);
    }

    @Test
    void aNullDefaultIsNotADefault() {
        // WordPress fills only the defaults that are set: a null one leaves the attribute out
        assertThat(rendered(as(dave).get(ROUTE + "/acme/echo?context=edit")))
                .isEqualTo("{\"message\":\"\",\"showLabel\":false}|none");
    }

    @Test
    void aHiddenBlockRendersAsNothing() {
        for (String name : new String[] {"/acme/echo", "/acme/dyn"}) {
            as(alice)
                    .contentType("application/json")
                    .body("{\"attributes\":{\"metadata\":{\"blockVisibility\":false}}}")
                    .post(ROUTE + name + "?context=edit")
                    .then()
                    .statusCode(200)
                    .body(equalTo("{\"rendered\":\"\"}"));
        }
        // only false itself hides it
        assertThat(rendered(as(alice)
                        .contentType("application/json")
                        .body("{\"attributes\":{\"metadata\":{\"blockVisibility\":true}}}")
                        .post(ROUTE + "/acme/echo?context=edit")))
                .isEqualTo("{\"metadata\":{\"blockVisibility\":true},\"message\":\"\",\"showLabel\":false}|none");
        assertThat(rendered(
                        as(alice).get(ROUTE + "/acme/echo?context=edit&attributes[metadata][blockVisibility]=false")))
                .isEqualTo("{\"metadata\":{\"blockVisibility\":\"false\"},\"message\":\"\",\"showLabel\":false}|none");
    }

    @Test
    void theQuerysNameWinsOverTheUrls() {
        // WordPress reads the name as any parameter, the query before the URL
        as(alice)
                .get(ROUTE + "/acme/nope?context=edit&name=acme/dyn")
                .then()
                .statusCode(200)
                .body(equalTo("{\"rendered\":\"<p>dyn<\\/p>\"}"));
        // the attributes are checked against the type the query names
        as(alice)
                .get(ROUTE + "/acme/nope?context=edit&name=acme/echo&attributes[foo]=1")
                .then()
                .statusCode(400)
                .body(equalTo(invalidAttributes("{\"code\":\"rest_additional_properties_forbidden\","
                        + "\"message\":\"foo is not a valid property of Object.\",\"data\":null}")));
        as(alice)
                .get(ROUTE + "/acme/dyn?context=edit&name=acme/nope")
                .then()
                .statusCode(404)
                .body(equalTo(INVALID_BLOCK));
    }

    @Test
    void aThrowingRendererIs500() {
        as(alice)
                .get(ROUTE + "/acme/boom?context=edit")
                .then()
                .statusCode(500)
                .body(equalTo("{\"code\":\"internal_server_error\",\"message\":\"There has been a critical error on"
                        + " this website.\",\"data\":{\"status\":500}}"));
        as(alice).get(ROUTE + "/acme/null?context=edit").then().statusCode(200).body(equalTo("{\"rendered\":\"\"}"));
    }

    @Test
    void deepAttributesAreValidatedNotCrashed() {
        StringBuilder deep = new StringBuilder("attributes[style]");
        for (int i = 0; i < 40; i++) {
            deep.append("[a").append(i).append(']');
        }
        // style is an object of any shape: 40 levels pass and reach the renderer
        String nested = rendered(as(alice).get(ROUTE + "/acme/echo?context=edit&" + deep + "=x"));
        assertThat(nested).startsWith("{\"style\":{\"a0\":{\"a1\":").contains("\"a39\":\"x\"");
        // under a string attribute the same depth is a type failure
        String underMessage = deep.toString().replace("[style]", "[message]");
        as(alice)
                .get(ROUTE + "/acme/echo?context=edit&" + underMessage + "=x")
                .then()
                .statusCode(400)
                .body(equalTo(invalidAttributes("{\"code\":\"rest_invalid_type\","
                        + "\"message\":\"[message] is not of type string.\",\"data\":{\"param\":\"[message]\"}}")));
        // and 1000 unknown attributes beside a style 40 deep, in a JSON body: the first unknown one is refused
        StringBuilder body = new StringBuilder("{\"attributes\":{\"style\":");
        for (int i = 0; i < 40; i++) {
            body.append("{\"a").append(i).append("\":");
        }
        body.append("\"x\"").append("}".repeat(40));
        for (int i = 0; i < 1000; i++) {
            body.append(",\"k").append(i).append("\":\"v\"");
        }
        body.append("}}");
        as(alice)
                .contentType("application/json")
                .body(body.toString())
                .post(ROUTE + "/acme/echo?context=edit")
                .then()
                .statusCode(400)
                .body(equalTo(invalidAttributes("{\"code\":\"rest_additional_properties_forbidden\","
                        + "\"message\":\"k0 is not a valid property of Object.\",\"data\":null}")));
    }

    @Test
    void routeMatchesIgnoringCaseLookupDoesNot() {
        as(alice)
                .get(ROUTE + "/Acme/Dyn?context=edit")
                .then()
                .statusCode(404)
                .header("Allow", "GET, POST")
                .body(equalTo(INVALID_BLOCK));
        // the route's characters: no underscore, exactly two segments
        for (String name : new String[] {"/acme_x/dyn", "/acme", "/acme/dyn/x"}) {
            as(alice)
                    .get(ROUTE + name + "?context=edit")
                    .then()
                    .statusCode(404)
                    .body(equalTo("{\"code\":\"rest_no_route\",\"message\":\"No route was found matching the URL"
                            + " and request method.\",\"data\":{\"status\":404}}"));
        }
    }

    /**
     * The block types of the renderer tests: {@code acme/echo} (a string {@code message} and a boolean
     * {@code showLabel}, both with defaults, and a string {@code nothing} whose default is null) rendered as its attributes and post, {@code acme/boom} whose renderer
     * throws and {@code acme/null} whose renderer answers null.
     */
    @ApplicationScoped
    public static class RendererTypes implements BlockTypeProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(
                    BlockType.builder("acme/echo")
                            .title("Echo")
                            .attributes(TestBlocks.json(
                                    "{\"message\":{\"type\":\"string\",\"default\":\"\"},"
                                            + "\"showLabel\":{\"type\":\"boolean\",\"default\":false},"
                                            + "\"nothing\":{\"type\":\"string\",\"default\":null}}",
                                    ObjectNode.class))
                            .build(),
                    BlockType.builder("acme/boom").title("Boom").build(),
                    BlockType.builder("acme/null").title("Null").build());
        }
    }

    /** Renders {@code acme/echo} as {@code <attributes as JSON>|<post id or "none">}. */
    @ApplicationScoped
    public static class EchoRenderer implements BlockRenderer {

        @Override
        public String blockName() {
            return "acme/echo";
        }

        @Override
        public String render(BlockRenderContext context) {
            return context.attributes() + "|"
                    + (context.postId().isPresent()
                            ? Long.toString(context.postId().getAsLong())
                            : "none");
        }
    }

    /** Fails while rendering {@code acme/boom}. */
    @ApplicationScoped
    public static class BoomRenderer implements BlockRenderer {

        @Override
        public String blockName() {
            return "acme/boom";
        }

        @Override
        public String render(BlockRenderContext context) {
            throw new IllegalStateException("boom");
        }
    }

    /** Answers null for {@code acme/null}. */
    @ApplicationScoped
    public static class NullRenderer implements BlockRenderer {

        @Override
        public String blockName() {
            return "acme/null";
        }

        @Override
        public String render(BlockRenderContext context) {
            return null;
        }
    }
}
