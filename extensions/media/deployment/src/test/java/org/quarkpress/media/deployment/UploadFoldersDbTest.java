package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.UploadFolders;
import org.quarkpress.persistence.runtime.SiteDefaults;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/** The folder an upload goes to: the parent's date or the site's local now, year and month folders, the site prefix. */
class UploadFoldersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
            UploadFoldersDbTest.class, TestStorage.class, TestProcessor.class, TestMultisiteMode.class);

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final SiteId MAIN = TestData.MAIN;

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
    TestClock clock;

    UserId bob;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        TestData.siteOption(dataSource, MAIN, "timezone_string", "\"UTC\"");
        TestData.siteOption(dataSource, MAIN, "uploads_use_yearmonth_folders", "\"1\"");
        bob = TestData.memberOfMain(dataSource, "editor");
    }

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "upload-folders"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private MediaSnapshot store(SiteId site, Upload.Builder upload) throws Exception {
        return Identities.onSite(site, Identities.user(bob), () -> media.store(upload.build()));
    }

    private Upload.Builder photo(String name) {
        return Upload.builder(name, file(MediaFixtures.jpeg(8, 8))).declaredType("image/jpeg");
    }

    private PostSnapshot post(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(MAIN, Identities.user(bob), () -> posts.create(changes.build()));
    }

    @Test
    void theFolderFollowsTheParentsDateExceptForPages() throws Exception {
        LocalDateTime pi = LocalDateTime.parse("2019-03-14T10:00:00");
        PostSnapshot dated =
                post(PostChanges.builder().title("Dated").status("publish").date(pi));
        PostSnapshot page = post(PostChanges.builder()
                .type("page")
                .title("Page")
                .status("publish")
                .date(pi));
        PostSnapshot scheduled = post(PostChanges.builder()
                .title("Scheduled")
                .status("future")
                .date(LocalDateTime.parse("2027-01-10T08:00:00")));
        clock.set(Instant.parse("2025-12-20T12:00:00Z"));
        PostSnapshot draft = post(PostChanges.builder().title("Draft"));
        clock.set(NOW);
        assertThat(store(MAIN, photo("dated.jpg").parent(dated.id())).storageKey())
                .isEqualTo("2019/03/dated.jpg");
        assertThat(store(MAIN, photo("page.jpg").parent(page.id())).storageKey())
                .isEqualTo("2026/11/page.jpg");
        assertThat(store(MAIN, photo("scheduled.jpg").parent(scheduled.id())).storageKey())
                .isEqualTo("2027/01/scheduled.jpg");
        MediaSnapshot underDraft = store(MAIN, photo("draft.jpg").parent(draft.id()));
        assertThat(underDraft.storageKey()).isEqualTo("2025/12/draft.jpg");
        assertThat(underDraft.attachedFile()).isEqualTo("2025/12/draft.jpg");
        assertThat(underDraft.post().localDate(ZoneOffset.UTC).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(store(MAIN, photo("dated2.jpg").parent(dated.id()))
                        .post()
                        .localDate(ZoneOffset.UTC)
                        .toLocalDate())
                .isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void theFolderUsesTheSitesLocalTime() throws Exception {
        SiteId site = TestData.siteIn(dataSource, "Etc/GMT-2");
        TestData.member(dataSource, site, bob, "[\"editor\"]");
        clock.set(Instant.parse("2026-10-31T23:30:00Z"));
        MediaSnapshot stored = store(site, photo("photo.jpg"));
        assertThat(stored.attachedFile()).isEqualTo("2026/11/photo.jpg");
        assertThat(stored.storageKey()).isEqualTo("sites/" + site.value() + "/2026/11/photo.jpg");
        // and the arithmetic alone
        assertThat(UploadFolders.when(
                        java.util.Optional.empty(),
                        java.time.ZoneId.of("Etc/GMT-2"),
                        Instant.parse("2026-10-31T23:30:00Z")))
                .isEqualTo(LocalDateTime.parse("2026-11-01T01:30:00"));
        assertThat(UploadFolders.of(MAIN, 1, true, LocalDateTime.parse("0999-02-01T00:00:00"))
                        .folderKey())
                .isEqualTo("0999/02");
    }

    @Test
    void yearMonthFoldersCanBeOff() throws Exception {
        TestData.siteOption(dataSource, MAIN, "uploads_use_yearmonth_folders", "\"0\"");
        MediaSnapshot first = store(MAIN, photo("photo.jpg"));
        assertThat(first.storageKey()).isEqualTo("photo.jpg");
        assertThat(first.attachedFile()).isEqualTo("photo.jpg");
        assertThat(first.url()).isEqualTo("http://localhost/test-uploads/photo.jpg");
        // the base folder is listed for the unique name too
        assertThat(store(MAIN, photo("photo.jpg")).storageKey()).isEqualTo("photo-1.jpg");
    }

    @Test
    void anotherSitesFilesLiveUnderItsPrefix() throws Exception {
        String path = "/site-" + UUID.randomUUID().toString().substring(0, 8) + "/";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values ('localhost', ?, 'sub')", path);
        SiteId site = SiteId.of(
                Jdbc.count(dataSource, "select id from qp_site where domain = 'localhost' and path = ?", path));
        for (Map.Entry<String, JsonNode> option : SiteDefaults.forNewSite("sub").entrySet()) {
            TestData.siteOption(
                    dataSource, site, option.getKey(), option.getValue().toString());
        }
        TestData.siteOption(dataSource, site, "timezone_string", "\"UTC\"");
        TestData.member(dataSource, site, bob, "[\"editor\"]");
        MediaSnapshot stored = store(site, photo("photo.jpg"));
        assertThat(stored.storageKey()).isEqualTo("sites/" + site.value() + "/2026/11/photo.jpg");
        assertThat(stored.attachedFile()).isEqualTo("2026/11/photo.jpg");
        assertThat(stored.post().guid())
                .isEqualTo(stored.url())
                .isEqualTo("http://localhost" + path + "test-uploads/sites/" + site.value() + "/2026/11/photo.jpg");
        // the main site's own photo.jpg is another file
        assertThat(store(MAIN, photo("photo.jpg")).storageKey()).isEqualTo("2026/11/photo.jpg");
    }

    @Test
    void nonAsciiNamesStayUnencoded() throws Exception {
        String foto = cp(0x424, 0x43E, 0x442, 0x43E);
        MediaSnapshot stored = store(MAIN, photo(foto + " 1.jpg"));
        assertThat(stored.storageKey()).isEqualTo("2026/11/" + foto + "-1.jpg");
        assertThat(stored.url()).isEqualTo("http://localhost/test-uploads/2026/11/" + foto + "-1.jpg");
        assertThat(stored.post().guid()).isEqualTo(stored.url());
        assertThat(stored.post().title()).isEqualTo(foto + " 1");
        assertThat(stored.post().slug()).isEqualTo("%d1%84%d0%be%d1%82%d0%be-1");
    }
}
