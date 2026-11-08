package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaPage;
import org.quarkpress.media.MediaQuery;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.AsSystem;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SearchColumn;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * What the REST media collection asks of the library: the unfiltered read of every row of the statuses asked, exact
 * types, the collection's filters, the search of the stored file path, and the caller's allowed types and the output
 * type of generated sizes.
 */
class RestQueryDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    RestQueryDbTest.class, TestStorage.class, TestProcessor.class, TestMultisiteMode.class)
            .overrideConfigKey("quarkpress.media.output-formats.\"image/png\"", "image/jpeg");

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

    @Inject
    AsSystem asSystem;

    @Inject
    Acting acting;

    SiteId site;
    UserId bob;
    UserId carol;
    PostId draft;
    PostId sunset;
    PostId logo;
    PostId report;
    PostId secret;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        site = TestData.siteIn(dataSource, "UTC");
        bob = TestData.profile(dataSource, "editor");
        carol = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, bob, "[\"editor\"]");
        TestData.member(dataSource, site, carol, "[\"author\"]");
        draft = as(
                        Identities.user(bob),
                        () -> posts.create(PostChanges.builder()
                                .title("Draft")
                                .status("draft")
                                .build()))
                .id();
        // carol's image, attached to bob's draft
        sunset = stored(upload("IMG_1234.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                .title("Sunset")
                .author(carol)
                .parent(draft));
        logo = stored(upload("logo.png", MediaFixtures.png(8, 8), "image/png"));
        report = stored(upload("report.pdf", MediaFixtures.pdf(), "application/pdf"));
        secret = stored(
                upload("secret.png", MediaFixtures.png(8, 8), "image/png").status("private"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Upload.Builder upload(String name, byte[] bytes, String type) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "rest-query"));
            return Upload.builder(
                            name, Files.write(dir.resolve(UUID.randomUUID().toString()), bytes))
                    .declaredType(type);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private <T> T as(SecurityIdentity identity, Callable<T> work) throws Exception {
        return Identities.onSite(site, identity, work);
    }

    private PostId stored(Upload.Builder upload) throws Exception {
        return as(Identities.user(bob), () -> media.store(upload.build()))
                .post()
                .id();
    }

    private MediaPage page(SecurityIdentity identity, MediaQuery.Builder query) throws Exception {
        return as(identity, () -> media.query(query.build()));
    }

    private List<PostId> ids(SecurityIdentity identity, MediaQuery.Builder query) throws Exception {
        return page(identity, query).items().stream()
                .map(item -> item.post().id())
                .toList();
    }

    private List<PostId> ids(MediaQuery.Builder query) throws Exception {
        return ids(Identities.user(bob), query);
    }

    private static MediaQuery.Builder query() {
        return MediaQuery.builder();
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void anUnfilteredQueryListsWhatTheCallerCannotRead() throws Exception {
        UserId dave = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, dave, "[\"author\"]");
        MediaQuery.Builder inherit = query().statuses(List.of("inherit"));
        // the posts module's read rules: the image of bob's draft is not shown to another author, and an anonymous
        // caller may not ask for a status at all
        assertThat(ids(Identities.user(dave), inherit)).containsExactlyInAnyOrder(logo, report);
        assertThat(page(Identities.user(dave), inherit).found()).isEqualTo(2);
        assertThatThrownBy(() -> ids(Identities.anonymous(), inherit)).isInstanceOf(UnauthorizedException.class);

        MediaPage unfiltered = page(
                Identities.anonymous(), query().statuses(List.of("inherit")).unfiltered(true));
        assertThat(unfiltered.items().stream().map(item -> item.post().id()))
                .containsExactlyInAnyOrder(sunset, logo, report);
        assertThat(unfiltered.found()).isEqualTo(3);
        MediaSnapshot image = unfiltered.items().stream()
                .filter(item -> item.post().id().equals(sunset))
                .findFirst()
                .orElseThrow();
        assertThat(image.post().author()).contains(carol);
        assertThat(image.post().parent()).contains(draft);

        // every status asked, whoever asks
        assertThat(ids(
                        Identities.anonymous(),
                        query().statuses(List.of("private")).unfiltered(true)))
                .containsExactly(secret);
        assertThat(ids(
                        Identities.user(carol),
                        query().statuses(List.of("inherit", "private")).unfiltered(true)))
                .containsExactlyInAnyOrder(sunset, logo, report, secret);
        assertThat(page(
                                Identities.anonymous(),
                                query().statuses(List.of("inherit"))
                                        .unfiltered(true)
                                        .perPage(1))
                        .found())
                .isEqualTo(3);
        // the caller's own identity is back after the query: dave still cannot read the image of bob's draft
        assertThat(as(Identities.user(dave), () -> {
                    media.query(query().statuses(List.of("inherit"))
                            .unfiltered(true)
                            .build());
                    return media.query(query().statuses(List.of("inherit")).build()).items().stream()
                            .map(item -> item.post().id())
                            .toList();
                }))
                .containsExactlyInAnyOrder(logo, report);
    }

    @Test
    void exactTypesAreCaseSensitive() throws Exception {
        assertThat(ids(query().exactMimeTypes(List.of("IMAGE/JPEG")))).isEmpty();
        assertThat(ids(query().exactMimeTypes(List.of("image/jpeg")))).containsExactly(sunset);
        assertThat(ids(query().exactMimeTypes(List.of("image/png", "application/pdf"))))
                .containsExactlyInAnyOrder(logo, report, secret);
        // they replace the patterns, which ignore case
        assertThat(ids(query().mimeTypes(List.of("IMAGE/JPEG")).exactMimeTypes(List.of("application/pdf"))))
                .containsExactly(report);
        assertThat(ids(query().mimeTypes(List.of("IMAGE/JPEG")))).containsExactly(sunset);
        // an image row stored in capitals is found by its exact type only
        Jdbc.update(dataSource, "update qp_media set mime_type = 'IMAGE/JPEG' where post_id = ?", sunset.value());
        assertThat(ids(query().exactMimeTypes(List.of("image/jpeg")))).isEmpty();
        assertThat(ids(query().exactMimeTypes(List.of("IMAGE/JPEG")))).containsExactly(sunset);
    }

    @Test
    void theCollectionFiltersReachThePostQuery() throws Exception {
        assertThat(ids(query().authorNotIn(List.of(bob)))).containsExactly(sunset);
        assertThat(ids(query().authorNotIn(List.of(carol)))).containsExactlyInAnyOrder(logo, report, secret);
        assertThat(ids(query().parentNotIn(List.of(draft.value())))).containsExactlyInAnyOrder(logo, report, secret);
        assertThat(ids(query().include(List.of(report, logo)).orderBy(OrderKey.POST_IN, true)))
                .containsExactly(report, logo);
        assertThat(ids(query().include(List.of(logo, report)).orderBy(OrderKey.POST_IN, true)))
                .containsExactly(logo, report);
        assertThat(ids(query().exclude(List.of(logo, secret)))).containsExactlyInAnyOrder(sunset, report);
        assertThat(ids(query().slugs(List.of("report", "logo")).orderBy(OrderKey.NAME_IN, true)))
                .containsExactly(report, logo);
        assertThat(ids(query().slugs(List.of("logo", "report")).orderBy(OrderKey.NAME_IN, true)))
                .containsExactly(logo, report);
    }

    @Test
    void zeroInTheParentListsMeansUnattached() throws Exception {
        PostId other = as(
                        Identities.user(bob),
                        () -> posts.create(PostChanges.builder()
                                .title("Other")
                                .status("publish")
                                .build()))
                .id();
        PostId attachedElsewhere = stored(
                upload("other.pdf", MediaFixtures.pdf(), "application/pdf").parent(other));

        assertThat(ids(query().parentIn(List.of(0L)))).containsExactlyInAnyOrder(logo, report, secret);
        assertThat(ids(query().parentIn(List.of(0L, draft.value()))))
                .containsExactlyInAnyOrder(sunset, logo, report, secret);
        assertThat(ids(query().parentIn(List.of(draft.value(), 0L))))
                .containsExactlyInAnyOrder(sunset, logo, report, secret);
        assertThat(ids(query().parentNotIn(List.of(0L)))).containsExactlyInAnyOrder(sunset, attachedElsewhere);
        assertThat(ids(query().parentNotIn(List.of(0L, draft.value())))).containsExactly(attachedElsewhere);
        assertThatThrownBy(() -> query().parentIn(List.of(-1L)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parentIn holds post ids, or 0 for the unattached attachments");
        assertThatThrownBy(() -> query().parentNotIn(List.of(-1L)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parentNotIn holds post ids, or 0 for the unattached attachments");
    }

    @Test
    void anUnfilteredQueryWithoutStatusesTakesTheCallersDefaults() throws Exception {
        // an anonymous caller may not read others' private attachments: inherit only
        assertThat(ids(Identities.anonymous(), query().unfiltered(true)))
                .containsExactlyInAnyOrder(sunset, logo, report);
        // bob may: inherit and private
        assertThat(ids(Identities.user(bob), query().unfiltered(true)))
                .containsExactlyInAnyOrder(sunset, logo, report, secret);
    }

    @Test
    void theSystemReadWorksOutsideARequestContext() throws Exception {
        ExecutorService plain = Executors.newSingleThreadExecutor();
        try {
            List<Boolean> seen = plain.submit(() -> {
                        boolean before = Arc.container().requestContext().isActive();
                        boolean system = asSystem.call(acting::system);
                        boolean after = Arc.container().requestContext().isActive();
                        return List.of(before, system, after);
                    })
                    .get(30, TimeUnit.SECONDS);
            assertThat(seen).containsExactly(false, true, false);
        } finally {
            plain.shutdownNow();
        }
        // inside a request context the caller's identity is back after the read
        assertThat(as(Identities.user(bob), () -> List.of(asSystem.call(acting::system), acting.system())))
                .containsExactly(true, false);
    }

    @Test
    void searchMatchesTheStoredPath() throws Exception {
        assertThat(as(Identities.user(bob), () -> media.find(sunset))
                        .orElseThrow()
                        .storageKey())
                .isEqualTo("sites/" + site.value() + "/2026/11/IMG_1234.jpg");
        assertThat(ids(query().search("IMG_12"))).containsExactly(sunset);
        assertThat(ids(query().search("img_12"))).containsExactly(sunset);
        assertThat(ids(query().search("sunset"))).containsExactly(sunset);
        assertThat(ids(query().search("-IMG"))).containsExactlyInAnyOrder(logo, report, secret);
        assertThat(ids(query().search("2026"))).containsExactlyInAnyOrder(sunset, logo, report, secret);
        assertThat(ids(query().search("2026 -png"))).containsExactlyInAnyOrder(sunset, report);
        // the site's folder is not part of the path
        assertThat(ids(query().search("sites"))).isEmpty();
        // whatever the searched columns
        assertThat(ids(query().search("IMG_12").searchColumns(SearchColumn.TITLE)))
                .containsExactly(sunset);
        assertThat(ids(query().search("report").searchColumns(SearchColumn.CONTENT)))
                .containsExactly(report);
        // an exact search matches the whole path
        assertThat(ids(query().search("2026/11/IMG_1234.jpg").exactSearch(true)))
                .containsExactly(sunset);
        assertThat(ids(query().search("IMG_1234.jpg").exactSearch(true))).isEmpty();
        assertThat(ids(query().search("Sunset").exactSearch(true))).containsExactly(sunset);
        assertThat(ids(query().search("IMG_1234.jpg"))).containsExactly(sunset);

        // on the main site the stored key is the path
        UserId alice = TestData.memberOfMain(dataSource, "editor");
        PostId main = Identities.onSite(
                        TestData.MAIN,
                        Identities.user(alice),
                        () -> media.store(upload("IMG_9999.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .build()))
                .post()
                .id();
        assertThat(Identities.onSite(
                                TestData.MAIN,
                                Identities.user(alice),
                                () -> media.query(query().search("2026/11/IMG_9999.jpg")
                                        .exactSearch(true)
                                        .build()))
                        .items()
                        .stream()
                        .map(item -> item.post().id()))
                .containsExactly(main);
    }

    @Test
    void theCallersAllowedTypesAndTheOutputType() throws Exception {
        List<Map.Entry<String, String>> allowed = as(Identities.user(bob), () -> media.allowedTypes());
        assertThat(allowed).contains(Map.entry("jpg|jpeg|jpe", "image/jpeg"), Map.entry("png", "image/png"));
        // HTML only for a caller who may post unfiltered HTML: on a network, super admins
        assertThat(allowed).extracting(Map.Entry::getKey).doesNotContain("htm|html");

        assertThat(as(
                        Identities.anonymous(),
                        () -> List.of(
                                media.outputType("image/png"),
                                media.outputType("image/jpeg"),
                                media.outputType("image/gif"),
                                media.outputType("image/heic"))))
                .containsExactly(Optional.of("image/jpeg"), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
