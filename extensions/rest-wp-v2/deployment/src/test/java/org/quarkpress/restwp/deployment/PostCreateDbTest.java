package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermsAssigned;

/**
 * {@code POST /wp/v2/<base>}: WordPress's create checks in its order, its preparation of the request (status and its
 * capability, dates, slugs of drafts, author, password and stickiness, parent), the writes after the insert (sticky,
 * featured image, format, template, terms) in one transaction, and the 201 answer in the edit context. The site's
 * zone is Europe/Paris; members: alice (administrator and super admin), the editor bob, the author carol, the
 * contributor dave and the subscriber erin.
 */
class PostCreateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    PostCreateDbTest.class,
                    PostCreateDbTest.FailingTags.class,
                    BookType.class,
                    PostFixtures.class,
                    PostFixtures.Builder.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TaxonomyBuildItem(lockedTaxonomy()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build());

    /** A flat taxonomy of posts in REST whose terms only {@code manage_options} may assign. */
    static TaxonomyDefinition lockedTaxonomy() {
        return TaxonomyDefinition.builder("qp_locked", List.of("post"))
                .showInRest(true)
                .restBase("qp-locked")
                .capabilities(Map.of(
                        "manage_terms", "manage_categories",
                        "edit_terms", "manage_categories",
                        "delete_terms", "manage_categories",
                        "assign_terms", "manage_options"))
                .build();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    static AgroalDataSource data;
    static SiteId site;
    static SiteId other;
    static String host;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;
    static long outsider;
    static long defaultCategory;

    /** Fails every assignment of tags while set: a step after the insert that goes wrong. */
    @ApplicationScoped
    public static class FailingTags {

        static volatile boolean failing;

        void assigned(@Observes TermsAssigned event) {
            if (failing && event.taxonomy().equals("post_tag")) {
                throw new TermException("qp_test_tags_failed", "The tags could not be assigned.");
            }
        }
    }

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "Europe/Paris");
        other = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        alice = member("administrator");
        TestData.superAdmin(data, UserId.of(alice));
        bob = member("editor");
        carol = member("author");
        dave = member("contributor");
        erin = member("subscriber");
        // a user of the network who is no member of the site
        outsider = TestData.profile(data, "outsider").value();
        defaultCategory = term(site, "category", "uncategorized");
        TestData.siteOption(data, site, "default_category", Long.toString(defaultCategory));
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    private static long term(SiteId on, String taxonomy, String slug) {
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, ?, ?, ?, '')",
                on.value(),
                taxonomy,
                slug,
                slug);
        return TestData.termId(data, on, taxonomy, slug);
    }

    static RequestSpecification as(long user) {
        return Http.at(host, "test-" + user).contentType("application/json");
    }

    static Response create(long user, String base, String body) {
        return as(user).body(body).post("/wp-json/wp/v2/" + base);
    }

    static long posts(String title) {
        return Jdbc.count(data, "select count(*) from qp_post where site_id = ? and title = ?", site.value(), title);
    }

    @Test
    void createAnswers201InEditContextWithLocation() {
        String u = UUID.randomUUID().toString();
        Response created = as(carol).body("{\"title\":\"Hello " + u + "\"}").post("/wp-json/wp/v2/posts?context=view");
        created.then()
                .statusCode(201)
                .header("Location", "http://" + host + "/wp-json/wp/v2/posts/" + created.path("id"))
                .header("Allow", "GET, POST")
                .body("status", equalTo("draft"))
                .body("title.raw", equalTo("Hello " + u))
                .body("author", equalTo((int) carol))
                .body("password", equalTo(""))
                .body("categories", equalTo(List.of((int) defaultCategory)));
        String inAnHour = SECONDS.format(
                Instant.now().plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC).toLocalDateTime());
        create(carol, "posts", "{\"title\":\"x " + u + "\",\"status\":\"publish\",\"date_gmt\":\"" + inAnHour + "\"}")
                .then()
                .statusCode(201)
                .body("status", equalTo("future"))
                .body("date_gmt", equalTo(inAnHour));
    }

    @Test
    void permissionChecksInWordPressOrder() {
        String u = UUID.randomUUID().toString();
        create(alice, "posts", "{\"id\":5,\"title\":\"x\"}")
                .then()
                .statusCode(400)
                .header("Allow", "GET")
                .body("code", equalTo("rest_post_exists"))
                .body("message", equalTo("Cannot create existing post."));
        // an empty id passes the check, then names no post
        create(alice, "posts", "{\"id\":0,\"title\":\"id zero " + u + "\"}")
                .then()
                .statusCode(404)
                .header("Allow", "GET, POST")
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
        assertThat(posts("id zero " + u)).isZero();
        create(carol, "posts", "{\"title\":\"x\",\"author\":" + bob + "}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_edit_others"))
                .body("message", equalTo("Sorry, you are not allowed to create posts as this user."));
        // checked for every type, although only posts have the field
        create(carol, "pages", "{\"title\":\"x\",\"sticky\":true}")
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body("code", equalTo("rest_cannot_assign_sticky"))
                .body("message", equalTo("Sorry, you are not allowed to make posts sticky."));
        create(carol, "posts", "{\"title\":\"x\",\"sticky\":true}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_assign_sticky"));
        create(erin, "posts", "{\"title\":\"x\"}")
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create posts as this user."));
        Http.at(host, null)
                .contentType("application/json")
                .body("{\"title\":\"x\"}")
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_create"));
        create(dave, "posts", "{\"title\":\"x " + u + "\",\"status\":\"publish\"}")
                .then()
                .statusCode(403)
                .header("Allow", "GET, POST")
                .body("code", equalTo("rest_cannot_publish"))
                .body("message", equalTo("Sorry, you are not allowed to publish posts in this post type."));
        create(dave, "posts", "{\"title\":\"x " + u + "\",\"status\":\"private\"}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_publish"))
                .body("message", equalTo("Sorry, you are not allowed to create private posts in this post type."));
        create(dave, "posts", "{\"title\":\"x " + u + "\",\"status\":\"future\"}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_publish"))
                .body("message", equalTo("Sorry, you are not allowed to publish posts in this post type."));
        assertThat(posts("x " + u)).isZero();
        create(carol, "posts", "{\"title\":\"x\",\"author\":0}")
                .then()
                .statusCode(201)
                .body("author", equalTo((int) carol));
        create(alice, "posts", "{\"title\":\"x\",\"author\":" + outsider + "}")
                .then()
                .statusCode(201)
                .body("author", equalTo((int) outsider));
        create(alice, "posts", "{\"title\":\"x\",\"author\":999999}")
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST")
                .body("code", equalTo("rest_invalid_author"))
                .body("message", equalTo("Invalid author ID."));
        create(alice, "posts", "{\"title\":\"x\",\"author\":-1}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_author"));
    }

    @Test
    void namedTermsMustBeAssignable() {
        String u = UUID.randomUUID().toString();
        long locked = term(site, "qp_locked", "locked-" + u);
        create(carol, "posts", "{\"title\":\"locked " + u + "\",\"qp-locked\":[" + locked + "]}")
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body("code", equalTo("rest_cannot_assign_term"))
                .body("message", equalTo("Sorry, you are not allowed to assign the provided terms."));
        assertThat(posts("locked " + u)).isZero();
        // only terms that exist count
        create(carol, "posts", "{\"title\":\"unknown " + u + "\",\"qp-locked\":[999999]}")
                .then()
                .statusCode(201)
                .body("qp-locked", equalTo(List.of()));
        create(alice, "posts", "{\"title\":\"assigned " + u + "\",\"qp-locked\":[" + locked + "]}")
                .then()
                .statusCode(201)
                .body("qp-locked", equalTo(List.of((int) locked)));
    }

    @Test
    void termListsHoldEachIdOnceAndAtMostAThousand() {
        String u = UUID.randomUUID().toString();
        long tag = term(site, "post_tag", "once-" + u);
        create(alice, "posts", "{\"title\":\"once\",\"tags\":[" + tag + "," + tag + "," + tag + "]}")
                .then()
                .statusCode(201)
                .body("tags", equalTo(List.of((int) tag)));
        StringBuilder thousand = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            thousand.append(i == 1 ? "" : ",").append(i).append(',').append(i);
        }
        // 1000 distinct ids, each twice: within the bound
        create(alice, "posts", "{\"title\":\"thousand\",\"tags\":[" + thousand + "]}")
                .then()
                .statusCode(201);
        create(alice, "posts", "{\"title\":\"too many " + u + "\",\"tags\":[" + thousand + ",1001]}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): tags"))
                .body("data.params.tags", equalTo("tags must contain at most 1000 items."))
                .body("data.details.tags.code", equalTo("rest_too_many_items"));
        assertThat(posts("too many " + u)).isZero();
    }

    @Test
    void theStepsAfterTheInsertNeedNoSecondEditCheck() {
        // a role that may create and publish posts but not edit published ones
        SiteId own = TestData.site(data);
        TestData.siteOption(
                data,
                own,
                "qp_user_roles",
                "{\"publisher\":{\"name\":\"Publisher\",\"capabilities\":{\"read\":true,\"edit_posts\":true,"
                        + "\"publish_posts\":true}}}");
        UserId publisher = TestData.profile(data, "publisher");
        TestData.member(data, own, publisher, "[\"publisher\"]");
        String ownHost = Jdbc.strings(data, "select domain from qp_site where id = ?", own.value())
                .get(0);
        Http.at(ownHost, "test-" + publisher.value())
                .contentType("application/json")
                .body("{\"title\":\"published\",\"status\":\"publish\",\"format\":\"aside\","
                        + "\"featured_media\":999999,\"template\":\"\",\"categories\":[],\"tags\":[]}")
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(201)
                .body("status", equalTo("publish"))
                .body("format", equalTo("aside"));
    }

    @Test
    void preparationErrors() {
        String u = UUID.randomUUID().toString();
        JsonNode status = json(create(alice, "posts", "{\"title\":\"x\",\"status\":\"trash\"}")
                .then()
                .statusCode(400)
                .extract()
                .asString());
        assertThat(status.get("code").asText()).isEqualTo("rest_invalid_param");
        assertThat(status.get("message").asText()).isEqualTo("Invalid parameter(s): status");
        assertThat(status.at("/data/params/status").asText())
                .isEqualTo("status is not one of publish, future, draft, pending, and private.");
        assertThat(status.at("/data/details/status/code").asText()).isEqualTo("rest_not_in_enum");
        create(alice, "posts", "{\"title\":\"sticky " + u + "\",\"password\":\"p\",\"sticky\":true}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_field"))
                .body("message", equalTo("A post can not be sticky and have a password."));
        assertThat(posts("sticky " + u)).isZero();
        create(alice, "pages", "{\"title\":\"orphan " + u + "\",\"parent\":999999}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post parent ID."));
        assertThat(posts("orphan " + u)).isZero();
        // a parent may be a post of any type
        PostId aPost = PostFixtures.builder(site).title("parent " + u).insert(data);
        create(alice, "pages", "{\"title\":\"child\",\"parent\":" + aPost.value() + "}")
                .then()
                .statusCode(201)
                .body("parent", equalTo((int) aPost.value()));
        create(alice, "posts", "{}")
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST")
                .body("code", equalTo("empty_content"))
                .body("message", equalTo("Content, title, and excerpt are empty."));
        // pages may be empty
        create(alice, "pages", "{}")
                .then()
                .statusCode(201)
                .body("status", equalTo("draft"))
                .body("title.raw", equalTo(""));
        JsonNode template = json(create(alice, "posts", "{\"title\":\"x\",\"template\":\"nope\"}")
                .then()
                .statusCode(400)
                .extract()
                .asString());
        assertThat(template.get("message").asText()).isEqualTo("Invalid parameter(s): template");
        assertThat(template.at("/data/params/template").asText()).isEqualTo("template is not one of .");
        assertThat(template.at("/data/details/template").toString())
                .isEqualTo("{\"code\":\"rest_invalid_param\",\"message\":\"template is not one of .\",\"data\":null}");
    }

    @Test
    void datesFollowWordPress() {
        create(alice, "posts", "{\"title\":\"d1\",\"date\":\"2026-01-15T10:00:00+05:00\"}")
                .then()
                .statusCode(201)
                .body("status", equalTo("draft"))
                .body("date", equalTo("2026-01-15T06:00:00"))
                .body("date_gmt", equalTo("2026-01-15T05:00:00"));
        create(alice, "posts", "{\"title\":\"d2\",\"date_gmt\":\"2026-03-01T08:00:00\"}")
                .then()
                .statusCode(201)
                .body("date", equalTo("2026-03-01T09:00:00"))
                .body("date_gmt", equalTo("2026-03-01T08:00:00"));
        // an impossible day within 01-31 rolls over; lower-case t is accepted
        create(alice, "posts", "{\"title\":\"d3\",\"date\":\"2026-02-30t10:00:00\"}")
                .then()
                .statusCode(201)
                .body("date", equalTo("2026-03-02T10:00:00"));
        JsonNode invalid = json(create(alice, "posts", "{\"title\":\"d4\",\"date\":\"2026-02-32T10:00:00\"}")
                .then()
                .statusCode(400)
                .extract()
                .asString());
        assertThat(invalid.get("code").asText()).isEqualTo("rest_invalid_param");
        assertThat(invalid.get("message").asText()).isEqualTo("Invalid parameter(s): date");
        assertThat(invalid.at("/data/params/date").asText()).isEqualTo("Invalid date.");
        assertThat(invalid.at("/data/details/date").toString())
                .isEqualTo("{\"code\":\"rest_invalid_date\",\"message\":\"Invalid date.\",\"data\":null}");
        // a fraction is dropped, Z honoured
        create(alice, "posts", "{\"title\":\"d5\",\"date\":\"2026-03-05T10:00:00.250Z\"}")
                .then()
                .statusCode(201)
                .body("date", equalTo("2026-03-05T11:00:00"))
                .body("date_gmt", equalTo("2026-03-05T10:00:00"));
        create(alice, "posts", "{\"title\":\"d6\",\"status\":\"publish\",\"date_gmt\":\"2030-01-15T12:30:00\"}")
                .then()
                .statusCode(201)
                .body("status", equalTo("future"))
                .body("date", equalTo("2030-01-15T13:30:00"))
                .body("modified", equalTo("2030-01-15T13:30:00"));
    }

    @Test
    void slugsOfDraftsAreMadeUniqueAsPublished() {
        String u = UUID.randomUUID().toString();
        String taken = "taken-" + u;
        PostFixtures.builder(site)
                .slug(taken)
                .title("taken")
                .publishedAt("2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
        // the slugs drafts were given count too
        for (String[] step :
                List.of(new String[] {"draft", taken + "-2"}, new String[] {"pending", taken + "-3"}, new String[] {
                    "publish", taken + "-4"
                })) {
            create(alice, "posts", "{\"title\":\"x\",\"slug\":\"" + taken + "\",\"status\":\"" + step[0] + "\"}")
                    .then()
                    .statusCode(201)
                    .body("slug", equalTo(step[1]));
        }
        // a draft child takes the next free slug under its parent
        PostId parent = PostFixtures.builder(site)
                .type("page")
                .slug("parent-" + u)
                .title("parent")
                .publishedAt("2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
        String child = "child-" + u;
        PostFixtures.builder(site)
                .type("page")
                .slug(child)
                .title("child")
                .parent(parent)
                .publishedAt("2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
        create(
                        alice,
                        "pages",
                        "{\"title\":\"x\",\"slug\":\"" + child + "\",\"status\":\"draft\",\"parent\":" + parent.value()
                                + "}")
                .then()
                .statusCode(201)
                .body("slug", equalTo(child + "-2"));
        // a slug is sanitized as a title
        create(alice, "posts", "{\"title\":\"x\",\"slug\":\"Upper Case " + u + "\",\"status\":\"draft\"}")
                .then()
                .statusCode(201)
                .body("slug", equalTo("upper-case-" + u));
    }

    @Test
    void stickyFeaturedMediaFormatTemplateAndTerms() {
        String u = UUID.randomUUID().toString();
        create(alice, "posts", "{\"title\":\"sticky\",\"sticky\":true}")
                .then()
                .statusCode(201)
                .body("sticky", equalTo(true));
        create(alice, "posts", "{\"title\":\"not sticky\"}")
                .then()
                .statusCode(201)
                .body("sticky", equalTo(false));
        create(alice, "posts", "{\"title\":\"unknown media\",\"featured_media\":999999}")
                .then()
                .statusCode(201)
                .body("featured_media", equalTo(0));
        PostId attachment = MediaFixturesForRest.upload(site, UserId.of(carol), "image-" + u + ".jpg", false)
                .post()
                .id();
        create(carol, "posts", "{\"title\":\"media\",\"featured_media\":" + attachment.value() + "}")
                .then()
                .statusCode(201)
                .body("featured_media", equalTo((int) attachment.value()));
        create(alice, "posts", "{\"title\":\"aside\",\"format\":\"aside\"}")
                .then()
                .statusCode(201)
                .body("format", equalTo("aside"));
        create(alice, "posts", "{\"title\":\"standard\",\"format\":\"standard\"}")
                .then()
                .statusCode(201)
                .body("format", equalTo("standard"));
        create(alice, "posts", "{\"title\":\"template\",\"template\":\"\"}")
                .then()
                .statusCode(201)
                .body("template", equalTo(""));
        // an empty list removes the default category the insert gave
        create(alice, "posts", "{\"title\":\"no categories\",\"categories\":[]}")
                .then()
                .statusCode(201)
                .body("categories", equalTo(List.of()));
        long tag = term(site, "post_tag", "tag-" + u);
        create(alice, "posts", "{\"title\":\"tagged\",\"tags\":[" + tag + ",999999]}")
                .then()
                .statusCode(201)
                .body("tags", equalTo(List.of((int) tag)));
        long otherSitesCategory = term(other, "category", "elsewhere-" + u);
        create(alice, "posts", "{\"title\":\"elsewhere\",\"categories\":[" + otherSitesCategory + "]}")
                .then()
                .statusCode(201)
                .body("categories", equalTo(List.of()));
    }

    @Test
    void aFailureAfterTheInsertRollsEverythingBack() {
        String u = UUID.randomUUID().toString();
        long tag = term(site, "post_tag", "failing-" + u);
        FailingTags.failing = true;
        try {
            create(
                            alice,
                            "posts",
                            "{\"title\":\"rolled back " + u + "\",\"sticky\":true,\"format\":\"aside\",\"tags\":[" + tag
                                    + "]}")
                    .then()
                    .statusCode(500)
                    .body("code", equalTo("qp_test_tags_failed"))
                    .body("message", equalTo("The tags could not be assigned."))
                    .body("data", nullValue());
        } finally {
            FailingTags.failing = false;
        }
        // WordPress keeps the post, its stickiness and its format; QuarkPress keeps nothing (the stickiness and the
        // format live on the row and its term assignments)
        assertThat(posts("rolled back " + u)).isZero();
        assertThat(Jdbc.count(
                        data,
                        "select count(*) from qp_term_assignment where site_id = ? and term_id = ?",
                        site.value(),
                        tag))
                .isZero();
    }

    @Test
    void anotherSitesIdsAreUnknown() {
        String u = UUID.randomUUID().toString();
        PostId elsewhere =
                PostFixtures.builder(other).type("page").title("other " + u).insert(data);
        create(alice, "pages", "{\"title\":\"child " + u + "\",\"parent\":" + elsewhere.value() + "}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post parent ID."));
        PostId image = MediaFixturesForRest.upload(other, UserId.of(alice), "other-image-" + u + ".jpg", false)
                .post()
                .id();
        create(alice, "posts", "{\"title\":\"x\",\"featured_media\":" + image.value() + "}")
                .then()
                .statusCode(201)
                .body("featured_media", equalTo(0));
    }

    @Test
    void specialTextIsStoredAndEncodedAsWordPressDoes() {
        String title =
                "Caf" + new String(Character.toChars(0xE9)) + " <b>x</b> & " + new String(Character.toChars(0x1F600));
        String body = "{\"title\":" + quote(title) + ",\"password\":\"a&b\"}";
        // a contributor has no unfiltered_html: the title is filtered as WordPress saves it
        Response filtered = create(dave, "posts", body);
        filtered.then().statusCode(201);
        assertThat(filtered.asString())
                .contains("\"raw\":\"Caf\\u00e9 <b>x<\\/b> &amp; \\ud83d\\ude00\"")
                .contains("\"password\":\"a&b\"");
        // and so are its content and excerpt (both with the post tag set)
        create(
                        dave,
                        "posts",
                        "{\"title\":\"kses\",\"content\":\"<p onclick=\\\"x()\\\">a</p><script>alert(1)</script><b>b</b>\","
                                + "\"excerpt\":\"<p onclick=\\\"x()\\\">a</p><script>alert(1)</script><b>b</b>\"}")
                .then()
                .statusCode(201)
                .body("content.raw", equalTo("<p>a</p>alert(1)<b>b</b>"))
                .body("excerpt.raw", equalTo("<p>a</p>alert(1)<b>b</b>"));
        Response kept = create(alice, "posts", "{\"title\":" + quote(title) + ",\"status\":\"publish\"}");
        kept.then().statusCode(201).body("title.raw", equalTo(title)).body("slug", equalTo("cafe-x-%f0%9f%98%80"));
        assertThat(kept.asString()).contains("\"raw\":\"Caf\\u00e9 <b>x<\\/b> & \\ud83d\\ude00\"");
    }

    @Test
    void blockDelimitersAreReserialized() {
        String content = "<!-- wp:paragraph {\"className\":\"a<b--c&d\\\"e>f\",\"anchor\":\"x--y\"} -->"
                + "<p class=\"a<b--c\">g18 <b>text</b> -- here</p><!-- /wp:paragraph -->";
        // WordPress's attribute escaping: <, >, &, " and -- inside the attribute JSON become \\u escapes
        String escaped = "{\"className\":\"a\\u003cb\\u002d\\u002dc\\u0026d\\u0022e\\u003ef\","
                + "\"anchor\":\"x\\u002d\\u002dy\"}";
        Response created = create(alice, "posts", "{\"title\":\"g18\",\"content\":" + quote(content) + "}");
        created.then()
                .statusCode(201)
                .body(
                        "content.raw",
                        equalTo("<!-- wp:paragraph " + escaped + " --><p class=\"a<b--c\">g18 <b>text</b> -- here</p>"
                                + "<!-- /wp:paragraph -->"))
                .body("content.block_version", equalTo(1));
        // an attribute already escaped round-trips byte for byte
        String already = "<!-- wp:paragraph {\"className\":\"a\\u003cb\"} --><p>x</p><!-- /wp:paragraph -->";
        create(alice, "posts", "{\"title\":\"g18 kept\",\"content\":" + quote(already) + "}")
                .then()
                .statusCode(201)
                .body("content.raw", equalTo(already));
    }

    private static String quote(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
