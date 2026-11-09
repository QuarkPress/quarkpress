package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Upload;

/**
 * The media collection as WordPress lists it: every row of the statuses asked is read, the rows the caller may not read
 * (or, in the edit context, edit) are left out of the body but counted in the totals; the attachment statuses and who
 * may ask for them; the type filters as a union of the caller's allowed types; the search of the stored file path;
 * the parent filters with 0 for the unattached attachments; one read per parent and per cover image in a request;
 * HEAD counting without reading any media row; one set of player numbers per response. Each test lists the library
 * of a site of its own, where bob is an editor, carol an author and erin a subscriber; alice is a super admin.
 */
class MediaCollectionDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(MediaCollectionDbTest.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private static final String MEDIA_ENTITY = "org.quarkpress.media.runtime.MediaEntity";

    static AgroalDataSource data;
    static UserId alice;
    static UserId bob;
    static UserId carol;
    static UserId erin;

    @Inject
    @PersistenceUnit("quarkpress")
    SessionFactory sessions;

    @BeforeAll
    static void users() {
        data = Jdbc.dataSource();
        alice = TestData.profile(data, "administrator");
        TestData.superAdmin(data, alice);
        bob = TestData.profile(data, "editor");
        carol = TestData.profile(data, "author");
        erin = TestData.profile(data, "subscriber");
    }

    @Test
    void anonymousSeesReadableRowsAndTotalsCountAll() {
        Library site = library();
        PostId published = site.post("publish", "published");
        PostId draft = site.post("draft", "draft");
        long unattached = site.upload(carol, "loose.jpg", 1, upload -> upload);
        long onPublished = site.upload(bob, "public.jpg", 2, upload -> upload.parent(published));
        long onDraft = site.upload(carol, "hidden.jpg", 3, upload -> upload);
        site.attach(onDraft, draft);

        Response anonymous = site.get(null, "?_fields=id");
        assertThat(ids(anonymous)).containsExactly(onPublished, unattached);
        assertThat(anonymous.header("X-WP-Total")).isEqualTo("3");
        assertThat(anonymous.header("X-WP-TotalPages")).isEqualTo("1");
        // the attachment's author reads it through her own capability, and an editor through the parent's
        assertThat(ids(site.get(carol, "?_fields=id"))).containsExactly(onDraft, onPublished, unattached);
        assertThat(ids(site.get(bob, "?_fields=id"))).containsExactly(onDraft, onPublished, unattached);
        // the rows are paged before they are checked: a hidden row empties its page, the totals unchanged
        Response first = site.get(null, "?_fields=id&per_page=1");
        assertThat(ids(first)).isEmpty();
        assertThat(first.header("X-WP-Total")).isEqualTo("3");
        assertThat(first.header("X-WP-TotalPages")).isEqualTo("3");
        assertThat(ids(site.get(null, "?_fields=id&per_page=1&page=2"))).containsExactly(onPublished);
    }

    @Test
    void hiddenRowsAreCountedButNeverShown() {
        Library site = library();
        PostId draft = site.post("draft", "draft");
        String stem = "qpstem" + suffix();
        long hidden = site.upload(carol, stem + ".jpg", 1, upload -> upload.title("Untitled"));
        site.attach(hidden, draft);
        site.upload(bob, "other.jpg", 2, upload -> upload);

        // the file name is in the path only, not in any text column
        Response anonymous = site.get(null, "?_fields=id&search=" + stem);
        assertThat(ids(anonymous)).isEmpty();
        assertThat(anonymous.header("X-WP-Total")).isEqualTo("1");
        Response subscriber = site.get(erin, "?_fields=id&search=" + stem);
        assertThat(ids(subscriber)).isEmpty();
        assertThat(subscriber.header("X-WP-Total")).isEqualTo("1");
        assertThat(ids(site.get(carol, "?_fields=id&search=" + stem))).containsExactly(hidden);
    }

    @Test
    void theDataViewsQueryShowsTheAuthorsOwnRows() {
        Library site = library();
        PostId draft = site.post("draft", "draft");
        long carols = site.upload(carol, "mine.jpg", 1, upload -> upload);
        long bobs = site.upload(bob, "his.jpg", 2, upload -> upload);
        long carolsOnDraft = site.upload(carol, "mine-on-draft.jpg", 3, upload -> upload);
        site.attach(carolsOnDraft, draft);

        Response modal = site.get(carol, "?context=edit&status=inherit&per_page=20&_fields=id");
        assertThat(ids(modal)).containsExactly(carolsOnDraft, carols);
        assertThat(modal.header("X-WP-Total")).isEqualTo("3");
        assertThat(ids(site.get(bob, "?context=edit&_fields=id"))).containsExactly(carolsOnDraft, bobs, carols);

        // the edit context needs the attachment type's edit_posts
        site.refused(
                erin,
                "?context=edit",
                403,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit posts in this post type.");
        site.refused(
                null,
                "?context=edit",
                401,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit posts in this post type.");
    }

    @Test
    void statusRules() {
        Library site = library();
        long carols = site.upload(carol, "carols-private.jpg", 1, upload -> upload.status("private"));
        site.upload(bob, "bobs-private.jpg", 2, upload -> upload.status("private"));
        site.upload(bob, "public.jpg", 3, upload -> upload);

        JsonNode anonymous = site.invalid(null, "?status=private");
        assertThat(anonymous.get("message").asText()).isEqualTo("Invalid parameter(s): status");
        assertThat(anonymous.at("/data/params/status").asText()).isEqualTo("Status is forbidden.");
        assertThat(anonymous.at("/data/details/status").toString())
                .isEqualTo("{\"code\":\"rest_forbidden_status\",\"message\":\"Status is forbidden.\","
                        + "\"data\":{\"status\":401}}");
        JsonNode subscriber = site.invalid(erin, "?status=inherit,private");
        assertThat(subscriber.at("/data/details/status/code").asText()).isEqualTo("rest_forbidden_status");
        assertThat(subscriber.at("/data/details/status/data/status").asInt()).isEqualTo(403);
        JsonNode any = site.invalid(alice, "?status=any");
        assertThat(any.get("message").asText()).isEqualTo("Invalid parameter(s): status");
        assertThat(any.at("/data/params/status").asText())
                .isEqualTo("status[0] is not one of inherit, private, and trash.");
        assertThat(any.at("/data/details/status/code").asText()).isEqualTo("rest_not_in_enum");
        assertThat(any.at("/data/details/status/message").asText())
                .isEqualTo("status[0] is not one of inherit, private, and trash.");

        // an author may ask for private attachments: hers are shown, all of them counted
        Response own = site.get(carol, "?status=private&context=edit&_fields=id");
        assertThat(ids(own)).containsExactly(carols);
        assertThat(own.header("X-WP-Total")).isEqualTo("2");
        assertThat(site.get(alice, "?status=private&_fields=id").header("X-WP-Total"))
                .isEqualTo("2");
    }

    @Test
    void typeFiltersAreAUnionAndUnknownTypesDropTheFilter() {
        Library site = library();
        long jpeg = site.upload(bob, "photo.jpg", 1, upload -> upload);
        long png = site.upload(bob, "logo.png", 2, upload -> upload);
        long pdf = site.upload(bob, "report.pdf", 3, upload -> upload);
        long mp3 = site.upload(bob, "song.mp3", 4, upload -> upload);
        String byId = "&_fields=id&orderby=id&order=asc";

        assertThat(ids(site.get(null, "?media_type=image&mime_type=application/pdf" + byId)))
                .containsExactly(jpeg, png, pdf);
        assertThat(ids(site.get(null, "?media_type=audio" + byId))).containsExactly(mp3);
        assertThat(ids(site.get(null, "?media_type=image,audio" + byId))).containsExactly(jpeg, png, mp3);
        assertThat(ids(site.get(null, "?mime_type=image/png" + byId))).containsExactly(png);
        // values not exactly a type of the allowed list are dropped; with nothing left, everything is listed
        assertThat(ids(site.get(null, "?mime_type=image/jpg" + byId))).containsExactly(jpeg, png, pdf, mp3);
        assertThat(ids(site.get(null, "?mime_type=IMAGE/JPEG" + byId))).containsExactly(jpeg, png, pdf, mp3);
        assertThat(ids(site.get(null, "?mime_type=image" + byId))).containsExactly(jpeg, png, pdf, mp3);
        assertThat(ids(site.get(null, "?mime_type=image/jpg,application/pdf" + byId)))
                .containsExactly(pdf);

        JsonNode font = site.invalid(null, "?media_type=font");
        assertThat(font.get("message").asText()).isEqualTo("Invalid parameter(s): media_type");
        String notInEnum = "media_type[0] is not one of image, video, audio, text, and application.";
        assertThat(font.at("/data/params/media_type").asText()).isEqualTo(notInEnum);
        assertThat(font.at("/data/details/media_type/code").asText()).isEqualTo("rest_not_in_enum");
        assertThat(font.at("/data/details/media_type/message").asText()).isEqualTo(notInEnum);
    }

    @Test
    void theTypeEnumAndTheTypesAreTheCallersOnThisRequest() {
        Library site = library();
        long jpeg = site.upload(bob, "photo.jpg", 1, upload -> upload);
        site.upload(bob, "logo.png", 2, upload -> upload);
        long pdf = site.upload(bob, "report.pdf", 3, upload -> upload);
        site.upload(bob, "song.mp3", 4, upload -> upload);
        String saved = Jdbc.strings(data, "select value from qp_network_option where name = 'upload_filetypes'")
                .get(0);
        try {
            Jdbc.update(
                    data,
                    "update qp_network_option set value = " + TestDb.json() + " where name = 'upload_filetypes'",
                    "\"jpg jpeg pdf\"");
            JsonNode audio = site.invalid(null, "?media_type=audio");
            assertThat(audio.at("/data/params/media_type").asText())
                    .isEqualTo("media_type[0] is not one of image and application.");
            assertThat(audio.at("/data/details/media_type/code").asText()).isEqualTo("rest_not_in_enum");
            // a group's types are the allowed ones only
            assertThat(ids(site.get(null, "?media_type=image&_fields=id&orderby=id&order=asc")))
                    .containsExactly(jpeg);
            assertThat(ids(site.get(null, "?mime_type=image/png,application/pdf&_fields=id")))
                    .containsExactly(pdf);
        } finally {
            Jdbc.update(
                    data,
                    "update qp_network_option set value = " + TestDb.json() + " where name = 'upload_filetypes'",
                    saved);
        }
    }

    @Test
    void searchMatchesFilePaths() {
        Library site = library();
        long sunset = site.upload(bob, "IMG_1234.jpg", 1, upload -> upload.title("Sunset"));
        long report = site.upload(bob, "report.pdf", 2, upload -> upload.title("Report"));
        String byId = "&_fields=id&orderby=id&order=asc";

        assertThat(ids(site.get(null, "?search=IMG_12" + byId))).containsExactly(sunset);
        assertThat(ids(site.get(null, "?search=sunset" + byId))).containsExactly(sunset);
        assertThat(ids(site.get(null, "?search=-IMG" + byId))).containsExactly(report);
        // the file path is searched whatever columns are named
        assertThat(ids(site.get(null, "?search=IMG&search_columns=post_title" + byId)))
                .containsExactly(sunset);
        // the path is relative to the uploads folder: year and month, never the site's folder
        String file = json(site.get(null, "?include=" + sunset + "&_fields=media_details"))
                .at("/0/media_details/file")
                .asText();
        assertThat(file).endsWith("/IMG_1234.jpg").doesNotContain("sites");
        String year = file.substring(0, file.indexOf('/'));
        assertThat(ids(site.get(null, "?search=" + year + byId))).containsExactly(sunset, report);
        assertThat(ids(site.get(null, "?search=sites" + byId))).isEmpty();
    }

    @Test
    void parentZeroListsUnattached() {
        Library site = library();
        PostId published = site.post("publish", "published");
        PostId other = site.post("publish", "other");
        long unattached = site.upload(bob, "loose.jpg", 1, upload -> upload);
        long onPublished = site.upload(bob, "on-published.jpg", 2, upload -> upload.parent(published));
        long onOther = site.upload(bob, "on-other.jpg", 3, upload -> upload.parent(other));
        String byId = "&_fields=id&orderby=id&order=asc";

        assertThat(ids(site.get(null, "?parent=0" + byId))).containsExactly(unattached);
        assertThat(ids(site.get(null, "?parent=" + published.value() + byId))).containsExactly(onPublished);
        assertThat(ids(site.get(null, "?parent=0," + other.value() + byId))).containsExactly(unattached, onOther);
        assertThat(ids(site.get(null, "?parent_exclude=0" + byId))).containsExactly(onPublished, onOther);
        assertThat(ids(site.get(null, "?parent_exclude=" + published.value() + byId)))
                .containsExactly(unattached, onOther);
        // parent wins over parent_exclude
        assertThat(ids(site.get(null, "?parent=" + published.value() + "&parent_exclude=" + published.value() + byId)))
                .containsExactly(onPublished);
    }

    @Test
    void theOtherFiltersAndOrders() {
        Library site = library();
        long carols = site.upload(carol, "carols.jpg", 1, upload -> upload.slug("carols-slug"));
        long bobs = site.upload(bob, "bobs.jpg", 2, upload -> upload.slug("bobs-slug"));
        long third = site.upload(bob, "third.jpg", 3, upload -> upload);
        String fields = "&_fields=id";

        assertThat(ids(site.get(null, "?author=" + carol.value() + fields))).containsExactly(carols);
        assertThat(ids(site.get(null, "?author_exclude=" + carol.value() + fields)))
                .containsExactly(third, bobs);
        assertThat(ids(site.get(null, "?include=" + bobs + "," + carols + "&orderby=include" + fields)))
                .containsExactly(bobs, carols);
        assertThat(ids(site.get(null, "?exclude=" + bobs + fields))).containsExactly(third, carols);
        assertThat(ids(site.get(null, "?slug=carols-slug,bobs-slug&orderby=include_slugs" + fields)))
                .containsExactly(carols, bobs);
        assertThat(ids(site.get(null, "?order=asc" + fields))).containsExactly(carols, bobs, third);
        assertThat(ids(site.get(null, "?offset=1&per_page=1" + fields))).containsExactly(bobs);
        assertThat(ids(site.get(null, "?before=2026-01-02T00:00:00" + fields))).containsExactly(carols);
        assertThat(ids(site.get(null, "?after=2026-01-02T00:00:00" + fields))).containsExactly(third, bobs);

        site.refused(
                null,
                "?orderby=relevance",
                400,
                "rest_no_search_term_defined",
                "You need to define a search term to order by relevance.");
        site.refused(
                null,
                "?orderby=include",
                400,
                "rest_orderby_include_missing_include",
                "You need to define an include parameter to order by include.");
        site.refused(
                null,
                "?page=3",
                400,
                "rest_post_invalid_page_number",
                "The page number requested is larger than the number of pages available.");
    }

    @Test
    void eachParentIsReadOncePerRequest() {
        // the same library unattached and without cover images: the reads every listed attachment costs anyway
        Library plain = library();
        for (int i = 0; i < 11; i++) {
            plain.upload(bob, "plain" + i + ".jpg", 1 + i, upload -> upload);
        }
        Library site = library();
        PostId first = site.post("publish", "first");
        PostId second = site.post("publish", "second");
        long cover = site.upload(bob, "cover.jpg", 1, upload -> upload);
        for (int i = 0; i < 10; i++) {
            PostId parent = i % 2 == 0 ? first : second;
            long attachment = site.upload(bob, "child" + i + ".jpg", 2 + i, upload -> upload.parent(parent));
            site.cover(attachment, cover);
        }
        Statistics statistics = statistics();
        long before = postReads(statistics);
        assertThat(ids(plain.get(null, "?per_page=20&_fields=id,_links"))).hasSize(11);
        long baseline = postReads(statistics) - before;

        before = postReads(statistics);
        Response all = site.get(null, "?per_page=20&_fields=id,_links");
        long reads = postReads(statistics) - before;
        assertThat(ids(all)).hasSize(11);
        assertThat(all.header("X-WP-Total")).isEqualTo("11");
        assertThat(json(all).at("/1/_links/wp:featuredmedia/0/href").asText()).endsWith("/media/" + cover);
        assertThat(json(all).at("/1/_links/wp:attached-to/0/id").asLong()).isIn(first.value(), second.value());
        // the read decision and the links share one read per parent and one of the cover image
        assertThat(reads - baseline).isEqualTo(3);

        // the edit context decides by edit_post, whose facts read the parents through the same memo
        before = postReads(statistics);
        assertThat(ids(plain.get(alice, "?per_page=20&context=edit&_fields=id,_links")))
                .hasSize(11);
        long editBaseline = postReads(statistics) - before;
        before = postReads(statistics);
        assertThat(ids(site.get(alice, "?per_page=20&context=edit&_fields=id,_links")))
                .hasSize(11);
        assertThat(postReads(statistics) - before - editBaseline).isEqualTo(3);
    }

    @Test
    void headCountsWithoutRendering() {
        Library site = library();
        for (int i = 0; i < 5; i++) {
            site.upload(bob, "head" + i + ".jpg", 1 + i, upload -> upload);
        }
        Statistics statistics = statistics();
        long before = mediaLoads(statistics);
        Response head = Http.at(site.host(), null).head(MEDIA);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.header("X-WP-Total")).isEqualTo("5");
        assertThat(head.header("X-WP-TotalPages")).isEqualTo("1");
        assertThat(head.asString()).isEmpty();
        assertThat(mediaLoads(statistics)).isEqualTo(before);
        // a GET reads the rows it shows
        assertThat(ids(site.get(null, ""))).hasSize(5);
        assertThat(mediaLoads(statistics)).isGreaterThan(before);
    }

    @Test
    void oneSetOfPlayerNumbersServesTheWholeResponse() {
        Library site = library();
        long older = site.upload(bob, "older.mp3", 1, upload -> upload);
        long newer = site.upload(bob, "newer.mp3", 2, upload -> upload);

        JsonNode items = json(site.get(alice, "?media_type=audio"));
        assertThat(items.at("/0/id").asLong()).isEqualTo(newer);
        // each attachment numbers its visible player, then the hidden one of its generated caption
        assertThat(items.at("/0/description/rendered").asText()).contains("id=\"audio-" + newer + "-1\"");
        assertThat(items.at("/1/id").asLong()).isEqualTo(older);
        assertThat(items.at("/1/description/rendered").asText()).contains("id=\"audio-" + older + "-3\"");
    }

    @Test
    void theMediaFeatureOffAnswers404() {
        Library site = library();
        site.upload(bob, "off.jpg", 1, upload -> upload);
        TestData.siteOption(data, site.id(), "qp_features", "{\"media\": false}");
        site.refused(bob, "", 404, "rest_no_route", "No route was found matching the URL and request method.");
        site.refused(
                bob,
                "?media_type=audio",
                404,
                "rest_no_route",
                "No route was found matching the URL and request method.");
        TestData.siteOption(data, site.id(), "qp_features", "{\"media\": true}");
        assertThat(ids(site.get(bob, "?_fields=id"))).hasSize(1);
    }

    // ---- helpers

    /** A site of its own, with bob as editor, carol as author and erin as subscriber. */
    private static Library library() {
        SiteId site = TestData.site(data);
        TestData.member(data, site, bob, "[\"editor\"]");
        TestData.member(data, site, carol, "[\"author\"]");
        TestData.member(data, site, erin, "[\"subscriber\"]");
        String host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        return new Library(site, host);
    }

    private record Library(SiteId id, String host) {

        /** A post of bob's. */
        PostId post(String status, String title) {
            return PostFixtures.builder(id)
                    .type("post")
                    .status(status)
                    .author(bob.value())
                    .title(title + " " + suffix())
                    .publishedAt(status.equals("draft") ? null : "2026-01-01T00:00:00Z")
                    .modifiedAt("2026-01-01T00:00:00Z")
                    .insert(data);
        }

        /** Uploads {@code name} as {@code user}, dated on day {@code day} of January 2026. */
        long upload(UserId user, String name, int day, UnaryOperator<Upload.Builder> fields) {
            return MediaFixturesForRest.upload(
                            id,
                            user,
                            name,
                            false,
                            upload -> fields.apply(upload.date(LocalDateTime.of(2026, 1, day, 12, 0))))
                    .post()
                    .id()
                    .value();
        }

        /** Attaches {@code attachment} to {@code parent} past the services (as to a post its author cannot edit). */
        void attach(long attachment, PostId parent) {
            Jdbc.update(data, "update qp_post set parent_id = ? where id = ?", parent.value(), attachment);
        }

        /** Names {@code cover} as the attachment's cover image, past the services. */
        void cover(long attachment, long cover) {
            Jdbc.update(
                    data,
                    "update qp_post set meta = " + TestDb.json() + " where id = ?",
                    "{\"_thumbnail_id\":[\"" + cover + "\"]}",
                    attachment);
        }

        Response get(UserId user, String query) {
            Response response =
                    Http.at(host, user == null ? null : "test-" + user.value()).get(MEDIA + query);
            assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
            return response;
        }

        JsonNode invalid(UserId user, String query) {
            Response response =
                    Http.at(host, user == null ? null : "test-" + user.value()).get(MEDIA + query);
            assertThat(response.statusCode()).as(response.asString()).isEqualTo(400);
            JsonNode body = json(response);
            assertThat(body.get("code").asText()).isEqualTo("rest_invalid_param");
            assertThat(body.at("/data/status").asInt()).isEqualTo(400);
            return body;
        }

        void refused(UserId user, String query, int status, String code, String message) {
            Response response =
                    Http.at(host, user == null ? null : "test-" + user.value()).get(MEDIA + query);
            assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
            JsonNode body = json(response);
            assertThat(body.get("code").asText()).isEqualTo(code);
            assertThat(body.get("message").asText()).isEqualTo(message);
            assertThat(body.at("/data/status").asInt()).isEqualTo(status);
        }
    }

    /** The persistence unit's statistics, switched on. */
    private Statistics statistics() {
        Statistics statistics = sessions.getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    /** The executions of the posts module's read of one post by site and id. */
    private static long postReads(Statistics statistics) {
        long count = 0;
        for (String query : statistics.getQueries()) {
            if (query.contains("PostEntity") && query.contains("siteId = ?1 and id = ?2")) {
                count += statistics.getQueryStatistics(query).getExecutionCount();
            }
        }
        return count;
    }

    private static long mediaLoads(Statistics statistics) {
        return statistics.getEntityStatistics(MEDIA_ENTITY).getLoadCount();
    }

    private static List<Long> ids(Response response) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : json(response)) {
            ids.add(item.get("id").asLong());
        }
        return ids;
    }

    private static JsonNode json(Response response) {
        try {
            return MAPPER.readTree(response.asString());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
