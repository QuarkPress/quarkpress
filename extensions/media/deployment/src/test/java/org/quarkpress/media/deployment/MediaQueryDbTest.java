package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaPage;
import org.quarkpress.media.MediaQuery;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.DateBound;
import org.quarkpress.posts.DateColumn;
import org.quarkpress.posts.DateCondition;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Library queries: the MIME filter as WordPress reads it (recorded for WordPress 7.1.3: {@code IMAGE} finds the
 * images, {@code image/*,video} the videos too, an empty value among others drops the filter), parent, author,
 * search (the stored file path included), paging, and the site and read rules of the posts module.
 */
class MediaQueryDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
            MediaQueryDbTest.class, TestStorage.class, TestProcessor.class, TestMultisiteMode.class);

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    Posts posts;

    @Inject
    TestStorage storage;

    @Inject
    TestProcessor processor;

    @Inject
    TestClock clock;

    SiteId site;
    UserId bob;
    UserId carol;
    UserId erin;
    PostId post;
    PostId jpeg;
    PostId png;
    PostId pdf;
    PostId mp4;
    PostId carolJpeg;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        site = TestData.siteIn(dataSource, "UTC");
        bob = TestData.profile(dataSource, "editor");
        carol = TestData.profile(dataSource, "author");
        erin = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, bob, "[\"editor\"]");
        TestData.member(dataSource, site, carol, "[\"author\"]");
        TestData.member(dataSource, site, erin, "[\"author\"]");
        post = as(
                        carol,
                        () -> posts.create(PostChanges.builder()
                                .title("Trip")
                                .status("publish")
                                .build()))
                .id();
        jpeg = stored(
                bob,
                upload("IMG_0001.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg").title("Sunset"));
        png = stored(bob, upload("logo.png", MediaFixtures.png(8, 8), "image/png"));
        pdf = stored(bob, upload("report.pdf", MediaFixtures.pdf(), "application/pdf"));
        mp4 = stored(bob, upload("clip.mp4", MediaFixtures.mp4(16, 16, 1), "video/mp4"));
        carolJpeg = stored(
                carol,
                upload("beach.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                        .title("IMG of the beach")
                        .parent(post));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-query"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Upload.Builder upload(String name, byte[] bytes, String type) {
        return Upload.builder(name, file(bytes)).declaredType(type);
    }

    private <T> T as(SiteId on, UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(on, Identities.user(user), work);
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return as(site, user, work);
    }

    private PostId stored(UserId user, Upload.Builder upload) throws Exception {
        return as(user, () -> media.store(upload.build())).post().id();
    }

    private static MediaQuery.Builder query() {
        return MediaQuery.builder();
    }

    private MediaPage page(UserId user, MediaQuery.Builder query) throws Exception {
        return as(user, () -> media.query(query.build()));
    }

    private List<PostId> ids(UserId user, MediaQuery.Builder query) throws Exception {
        return page(user, query).items().stream().map(item -> item.post().id()).toList();
    }

    private List<PostId> ids(MediaQuery.Builder query) throws Exception {
        return ids(bob, query);
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void mimeFiltersMatchCaseInsensitively() throws Exception {
        assertThat(ids(query().mimeTypes(List.of("IMAGE")))).containsExactlyInAnyOrder(jpeg, png, carolJpeg);
        assertThat(ids(query().mimeTypes(List.of("image/*", "video"))))
                .containsExactlyInAnyOrder(jpeg, png, carolJpeg, mp4);
        assertThat(ids(query().mimeTypes(List.of("image", "")))).hasSize(5);
        assertThat(ids(query().mimeTypes(List.of("application/pdf")))).containsExactly(pdf);
        assertThat(ids(query().mimeTypes(List.of("Application/PDF")))).containsExactly(pdf);
        assertThat(ids(query().mimeTypes(List.of("image/JPEG")))).containsExactlyInAnyOrder(jpeg, carolJpeg);
        assertThat(ids(query().mimeTypes(List.of("*")))).hasSize(5);
        assertThat(ids(query().mimeTypes(List.of("%")))).hasSize(5);
        // whitespace goes first, as in WordPress: these drop the filter as well
        assertThat(ids(query().mimeTypes(List.of("   ")))).hasSize(5);
        assertThat(ids(query().mimeTypes(List.of(" %")))).hasSize(5);
        assertThat(ids(query().mimeTypes(List.of("% ")))).hasSize(5);
        assertThat(ids(query().mimeTypes(List.of("image/jp*", "application/vnd.ms-*"))))
                .containsExactlyInAnyOrder(jpeg, carolJpeg);
        assertThat(ids(query().mimeTypes(List.of("audio")))).isEmpty();
        assertThat(ids(query().mimeTypes(List.of()))).containsExactlyInAnyOrder(jpeg, png, pdf, mp4, carolJpeg);
        // a stored type in capitals is found too
        Jdbc.update(dataSource, "update qp_media set mime_type = 'VIDEO/MP4' where post_id = ?", mp4.value());
        assertThat(ids(query().mimeTypes(List.of("video/mp4")))).containsExactly(mp4);

        // the filter counts in the totals
        MediaPage images = page(bob, query().mimeTypes(List.of("image")).perPage(2));
        assertThat(images.items()).hasSize(2);
        assertThat(images.found()).isEqualTo(3);
        assertThat(images.maxPages()).isEqualTo(2);
    }

    @Test
    void parentAuthorAndPaging() throws Exception {
        assertThat(ids(query().parent(0))).containsExactlyInAnyOrder(jpeg, png, pdf, mp4);
        assertThat(ids(query().parent(post.value()))).containsExactly(carolJpeg);
        assertThat(ids(query().parentIn(List.of(post.value(), jpeg.value())))).containsExactly(carolJpeg);
        assertThat(ids(query().author(carol))).containsExactly(carolJpeg);
        assertThat(ids(query().authorIn(List.of(carol, erin)))).containsExactly(carolJpeg);
        assertThat(ids(query().author(bob).mimeTypes(List.of("image")))).containsExactlyInAnyOrder(jpeg, png);

        MediaPage first = page(bob, query().perPage(2));
        assertThat(first.items()).hasSize(2);
        assertThat(first.found()).isEqualTo(5);
        assertThat(first.maxPages()).isEqualTo(3);
        MediaPage last = page(bob, query().perPage(2).page(3));
        assertThat(last.items()).hasSize(1);
        assertThat(last.found()).isEqualTo(5);
        assertThat(page(bob, query().perPage(2).offset(4)).items()).hasSize(1);

        // titles, captions and descriptions are searched, and the stored file path
        assertThat(ids(query().search("IMG"))).containsExactlyInAnyOrder(carolJpeg, jpeg);
        assertThat(ids(query().search("beach"))).containsExactly(carolJpeg);
        assertThat(ids(query().search("Sunset"))).containsExactly(jpeg);

        assertThat(ids(query().orderBy(OrderKey.TITLE, true).mimeTypes(List.of("image"))))
                .containsExactly(carolJpeg, png, jpeg);
        assertThat(ids(query().orderBy(OrderKey.TITLE, false).mimeTypes(List.of("image"))))
                .containsExactly(jpeg, png, carolJpeg);
        assertThat(ids(query().date(DateCondition.after(DateColumn.DATE, DateBound.year(2030), true))))
                .isEmpty();
        assertThat(ids(query().date(DateCondition.before(DateColumn.DATE, DateBound.year(2030), true))))
                .hasSize(5);

        // the snapshot of each item is the attachment's, with its media row
        MediaSnapshot item =
                page(bob, query().mimeTypes(List.of("application/pdf"))).items().get(0);
        assertThat(item.mimeType()).isEqualTo("application/pdf");
        assertThat(item.post().type()).isEqualTo("attachment");
        assertThat(item.post().title()).isEqualTo("report");
        assertThat(item).isEqualTo(as(bob, () -> media.find(pdf)).orElseThrow());
    }

    @Test
    void anotherSitesAndUnreadableAttachmentsNeverShow() throws Exception {
        SiteId other = TestData.siteIn(dataSource, "UTC");
        TestData.member(dataSource, other, bob, "[\"editor\"]");
        PostId elsewhere = as(
                        other,
                        bob,
                        () -> media.store(upload("x.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .build()))
                .post()
                .id();
        PostId hidden = stored(
                bob,
                upload("secret.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg").status("private"));

        assertThat(ids(query())).containsExactlyInAnyOrder(jpeg, png, pdf, mp4, carolJpeg, hidden);
        assertThat(ids(query().mimeTypes(List.of("image")))).doesNotContain(elsewhere);
        assertThat(ids(erin, query())).containsExactlyInAnyOrder(jpeg, png, pdf, mp4, carolJpeg);
        assertThat(page(erin, query()).found()).isEqualTo(5);
        assertThat(ids(erin, query().statuses(List.of("inherit", "private"))))
                .containsExactlyInAnyOrder(jpeg, png, pdf, mp4, carolJpeg);
        assertThat(ids(query().statuses(List.of("private")))).containsExactly(hidden);
        assertThat(ids(query().statuses(List.of("inherit")))).containsExactlyInAnyOrder(jpeg, png, pdf, mp4, carolJpeg);
        assertThat(as(other, bob, () -> media.query(MediaQuery.builder().build())).items().stream()
                        .map(item -> item.post().id())
                        .toList())
                .containsExactly(elsewhere);

        TestData.siteOption(dataSource, site, "qp_features", "{\"media\": false}");
        assertThatThrownBy(() -> ids(query()))
                .isInstanceOfSatisfying(
                        SiteFeatureDisabledException.class,
                        refusal -> assertThat(refusal.key()).isEqualTo("media"));
    }

    @Test
    void commaListsAreSplitKeepingEmptyPieces() throws Exception {
        assertThat(ids(query().mimeTypes(List.of("image/*,video"))))
                .containsExactlyInAnyOrder(jpeg, png, carolJpeg, mp4);
        assertThat(ids(query().mimeTypes(List.of("application/pdf,VIDEO")))).containsExactlyInAnyOrder(pdf, mp4);
        assertThat(ids(query().mimeTypes(List.of("image,")))).hasSize(5);
        assertThat(ids(query().mimeTypes(List.of("audio", ",video")))).hasSize(5);
    }

    @Test
    void attachmentsWithoutAMediaRowAreNotInTheLibrary() throws Exception {
        PostId rowless = as(
                        bob,
                        () -> posts.create(PostChanges.builder()
                                .type("attachment")
                                .status("inherit")
                                .title("IMG without a file")
                                .build()))
                .id();
        assertThat(ids(query())).doesNotContain(rowless).hasSize(5);
        MediaPage paged = page(bob, query().perPage(2));
        assertThat(paged.found()).isEqualTo(5);
        assertThat(paged.maxPages()).isEqualTo(3);
        assertThat(page(bob, query().perPage(2).page(3)).items()).hasSize(1);
        assertThat(page(bob, query().perPage(-1)).found()).isEqualTo(5);
        // a dropped MIME filter still asks for the media row
        assertThat(page(bob, query().mimeTypes(List.of("image", "")).perPage(2)).found())
                .isEqualTo(5);
        assertThat(ids(query().search("IMG"))).containsExactlyInAnyOrder(carolJpeg, jpeg);
        assertThat(ids(query().parent(0))).containsExactlyInAnyOrder(jpeg, png, pdf, mp4);
    }

    @Test
    void anUnpagedQueryLoadsEveryRowPastOneChunk() throws Exception {
        int bulk = 1003;
        // 1003 attachment posts in one statement, numbered from the digits of four small tables
        String digit = "(select 0 as i union all select 1 union all select 2 union all select 3 union all select 4"
                + " union all select 5 union all select 6 union all select 7 union all select 8 union all select 9)";
        Jdbc.update(
                dataSource,
                "insert into qp_post (site_id, type, status, slug, title, content, excerpt, created_at, modified_at,"
                        + " comment_status, ping_status, guid) select " + site.value() + ", 'attachment', 'inherit',"
                        + " concat('bulk-', n), concat('Bulk ', n), '', '', " + TestDb.now() + ", " + TestDb.now()
                        + ", 'open', 'open', concat('bulk-guid-" + site.value() + "-', n) from (select a.i + 10 * b.i"
                        + " + 100 * c.i + 1000 * d.i as n from " + digit + " a cross join " + digit + " b cross join "
                        + digit + " c cross join " + digit + " d) numbers where n < " + bulk);
        Jdbc.update(
                dataSource,
                "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, sizes, extra, alt_text,"
                        + " processing, stored_bytes) select id, site_id, concat('bulk/', slug), 'image/png', 8, "
                        + TestDb.json() + ", " + TestDb.json() + ", '', 'none', 8 from qp_post where site_id = ? and"
                        + " slug like 'bulk-%'",
                "{}",
                "{}",
                site.value());

        MediaPage all = page(bob, query().perPage(-1));
        assertThat(all.items()).hasSize(bulk + 5);
        assertThat(all.found()).isEqualTo(bulk + 5);
        assertThat(all.items().stream().map(item -> item.post().id()).distinct())
                .hasSize(bulk + 5);
        assertThat(all.items())
                .filteredOn(item -> item.post().title().startsWith("Bulk "))
                .hasSize(bulk)
                .allSatisfy(item -> assertThat(item.storageKey())
                        .isEqualTo("bulk/" + item.post().slug()));
    }

    @Test
    void parentInRefusesNegativeIds() {
        assertThatThrownBy(() -> MediaQuery.builder().parentIn(List.of(5L, -1L)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parentIn holds post ids, or 0 for the unattached attachments");
        assertThat(MediaQuery.builder().parentIn(List.of(5L, 0L)).build().parentIn())
                .containsExactly(5L, 0L);
        assertThat(MediaQuery.builder().parentIn(List.of(5L)).build().parentIn())
                .containsExactly(5L);
    }

    @Test
    void theQueryIsRecordedAsGiven() {
        MediaQuery query = MediaQuery.builder()
                .mimeTypes(List.of("image"))
                .parent(0)
                .author(UserId.of(7))
                .search("x")
                .perPage(3)
                .page(2)
                .build();
        assertThat(query.mimeTypes()).containsExactly("image");
        assertThat(query.parent()).hasValue(0);
        assertThat(query.author()).contains(UserId.of(7));
        assertThat(query.search()).contains("x");
        assertThat(query.statuses()).isEmpty();
        assertThat(MediaQuery.builder().build().mimeTypes()).isEmpty();
    }
}
