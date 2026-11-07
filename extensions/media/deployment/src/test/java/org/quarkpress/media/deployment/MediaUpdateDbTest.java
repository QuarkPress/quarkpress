package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.MediaEntity;
import org.quarkpress.media.runtime.MediaRepository;
import org.quarkpress.posts.PostException;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Reading and updating an attachment: the post fields through the posts module's rules, the alternative text on the
 * media row, the attachment statuses, the parent and author, the modification date of an alt-only change, the
 * capability and read rules, and the versions that refuse stale updates.
 */
class MediaUpdateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            MediaApp.withoutStorage(MediaUpdateDbTest.class, TestStorage.class, TestProcessor.class);

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final SiteId MAIN = TestData.MAIN;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    MediaRepository mediaRows;

    @Inject
    TestStorage storage;

    @Inject
    TestProcessor processor;

    @Inject
    TestClock clock;

    UserId bob;
    UserId carol;
    UserId erin;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        TestData.siteOption(dataSource, MAIN, "timezone_string", "\"UTC\"");
        bob = TestData.memberOfMain(dataSource, "editor");
        carol = TestData.memberOfMain(dataSource, "author");
        erin = TestData.memberOfMain(dataSource, "subscriber");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-update"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Upload.Builder jpeg(String name) {
        return Upload.builder(name, file(MediaFixtures.jpeg(8, 8))).declaredType("image/jpeg");
    }

    private <T> T as(SiteId site, UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(user), work);
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return as(MAIN, user, work);
    }

    private MediaSnapshot stored(UserId user, Upload.Builder upload) throws Exception {
        return as(user, () -> media.store(upload.build()));
    }

    private MediaSnapshot stored(UserId user) throws Exception {
        return stored(user, jpeg("photo.jpg"));
    }

    private MediaSnapshot update(UserId user, PostId id, MediaChanges.Builder changes) throws Exception {
        return as(user, () -> media.update(id, changes.build()));
    }

    private Optional<MediaSnapshot> find(UserId user, PostId id) throws Exception {
        return as(user, () -> media.find(id));
    }

    private MediaSnapshot found(PostId id) throws Exception {
        return find(bob, id).orElseThrow();
    }

    private long rowVersion(PostId id) {
        return Jdbc.count(dataSource, "select version from qp_media where post_id = ?", id.value());
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void postFieldsAndAltAreUpdated() throws Exception {
        MediaSnapshot before = stored(bob);
        PostId id = before.post().id();
        long rowVersion = rowVersion(id);

        MediaSnapshot updated = update(
                bob,
                id,
                MediaChanges.builder()
                        .title("New")
                        .caption("Cap")
                        .description("Desc")
                        .altText(" <i>A</i>   kite "));

        assertThat(updated.post().title()).isEqualTo("New");
        assertThat(updated.post().excerpt()).isEqualTo("Cap");
        assertThat(updated.post().content()).isEqualTo("Desc");
        assertThat(updated.altText()).isEqualTo("A kite");
        assertThat(updated.storageKey()).isEqualTo(before.storageKey());
        assertThat(updated).isEqualTo(found(id));
        assertThat(Jdbc.strings(dataSource, "select alt_text from qp_media where post_id = ?", id.value()))
                .containsExactly("A kite");
        // the media row's own version moves with its write
        assertThat(rowVersion(id)).isEqualTo(rowVersion + 1);
    }

    @Test
    void statusesAreTheAttachmentOnes() throws Exception {
        Map<String, String> expected = Map.of(
                "private", "private",
                "publish", "inherit",
                "future", "inherit",
                "draft", "inherit");
        for (Map.Entry<String, String> status : expected.entrySet()) {
            PostId id = stored(bob).post().id();
            MediaSnapshot updated = update(bob, id, MediaChanges.builder().status(status.getKey()));
            assertThat(updated.post().status()).as(status.getKey()).isEqualTo(status.getValue());
            assertThat(found(id).post().status()).as(status.getKey()).isEqualTo(status.getValue());
        }
    }

    @Test
    void parentAndAuthor() throws Exception {
        PostId post = TestData.post(dataSource, MAIN, "post", "publish", "parent-" + UUID.randomUUID(), "Parent");
        PostId id = stored(bob).post().id();
        PostId other = stored(bob, jpeg("other.jpg")).post().id();

        assertThat(update(bob, id, MediaChanges.builder().parent(post)).post().parent())
                .contains(post);
        assertThat(found(id).post().parent()).contains(post);

        PostId revision =
                TestData.post(dataSource, MAIN, "revision", "inherit", post.value() + "-revision-v1", "Parent");
        for (PostId refused : List.of(other, revision)) {
            assertThatThrownBy(() -> update(bob, id, MediaChanges.builder().parent(refused)))
                    .isInstanceOfSatisfying(MediaException.class, refusal -> {
                        assertThat(refusal.code()).isEqualTo("rest_invalid_param");
                        assertThat(refusal).hasMessage("Invalid parent type.");
                    });
            assertThat(found(id).post().parent()).contains(post);
        }

        assertThat(update(bob, id, MediaChanges.builder().parent(0)).post().parent())
                .isEmpty();
        assertThat(found(id).post().parent()).isEmpty();

        assertThat(update(bob, id, MediaChanges.builder().author(carol)).post().author())
                .contains(carol);
        assertThat(found(id).post().author()).contains(carol);
    }

    @Test
    void anAltOnlyChangeUpdatesTheModificationDate() throws Exception {
        MediaSnapshot before = stored(bob);
        PostId id = before.post().id();
        Instant later = NOW.plusSeconds(2);
        clock.set(later);

        MediaSnapshot updated = update(bob, id, MediaChanges.builder().altText("Kite"));

        assertThat(updated.altText()).isEqualTo("Kite");
        assertThat(updated.post().modifiedAt()).isEqualTo(later);
        assertThat(updated.post().version()).isEqualTo(before.post().version() + 1);
        assertThat(updated.post().title()).isEqualTo(before.post().title());
        assertThat(updated.post().createdAt()).isEqualTo(before.post().createdAt());
        assertThat(found(id)).isEqualTo(updated);
    }

    @Test
    void capabilities() throws Exception {
        MediaSnapshot before = stored(bob, jpeg("bob.jpg").altText("Bob's"));
        PostId id = before.post().id();

        assertThatThrownBy(() -> update(carol, id, MediaChanges.builder().title("Carol's")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> update(carol, id, MediaChanges.builder().altText("Carol's")))
                .isInstanceOf(ForbiddenException.class);
        // the edit check comes before the parent rule: an attachment as parent is refused as forbidden first
        PostId post = TestData.post(dataSource, MAIN, "post", "publish", "carols-" + UUID.randomUUID(), "Post");
        PostId attachment = stored(bob, jpeg("parent.jpg")).post().id();
        for (PostId parent : List.of(post, attachment)) {
            assertThatThrownBy(() -> update(carol, id, MediaChanges.builder().parent(parent)))
                    .isInstanceOf(ForbiddenException.class);
        }

        MediaSnapshot after = found(id);
        assertThat(after.post().title()).isEqualTo(before.post().title());
        assertThat(after.altText()).isEqualTo("Bob's");
        assertThat(after.post().version()).isEqualTo(before.post().version());
        assertThat(after.post().parent()).isEmpty();
        // carol's own attachment is hers to change
        PostId hers = stored(carol).post().id();
        assertThat(update(carol, hers, MediaChanges.builder().altText("Carol's"))
                        .altText())
                .isEqualTo("Carol's");
    }

    @Test
    void updatingWhatIsNoAttachmentIsRefused() throws Exception {
        PostId post = TestData.post(dataSource, MAIN, "post", "publish", "plain-" + UUID.randomUUID(), "Plain");
        // an attachment post without its media row is no attachment of the media library
        PostId rowless =
                TestData.post(dataSource, MAIN, "attachment", "inherit", "rowless-" + UUID.randomUUID(), "Rowless");
        for (PostId id : List.of(post, rowless, PostId.of(999_999_999L))) {
            assertThatThrownBy(() -> update(bob, id, MediaChanges.builder().altText("x")))
                    .isInstanceOfSatisfying(
                            PostException.class,
                            refusal -> assertThat(refusal.code()).isEqualTo("invalid_post"));
        }
        assertThat(Jdbc.strings(dataSource, "select title from qp_post where id = ?", post.value()))
                .containsExactly("Plain");
        assertThat(Jdbc.strings(dataSource, "select title from qp_post where id = ?", rowless.value()))
                .containsExactly("Rowless");
    }

    @Test
    void theMediaFeatureOffRefusesUpdates() throws Exception {
        SiteId site = TestData.siteIn(dataSource, "UTC");
        PostId attachment = TestData.post(dataSource, site, "attachment", "inherit", "off", "Off");
        Jdbc.update(
                dataSource,
                "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, sizes, extra, alt_text,"
                        + " processing, stored_bytes) values (?, ?, ?, 'image/jpeg', 8, " + TestDb.json() + ", "
                        + TestDb.json() + ", 'Kept', 'none', 8)",
                attachment.value(),
                site.value(),
                "sites/" + site.value() + "/2026/11/off.jpg",
                "{}",
                "{}");
        TestData.siteOption(dataSource, site, "qp_features", "{\"media\": false}");

        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.system(),
                        () -> media.update(
                                attachment,
                                MediaChanges.builder()
                                        .title("On")
                                        .altText("Changed")
                                        .build())))
                .isInstanceOfSatisfying(
                        SiteFeatureDisabledException.class,
                        refusal -> assertThat(refusal.key()).isEqualTo("media"));
        assertThat(Jdbc.strings(dataSource, "select title from qp_post where id = ?", attachment.value()))
                .containsExactly("Off");
        assertThat(Jdbc.strings(dataSource, "select alt_text from qp_media where post_id = ?", attachment.value()))
                .containsExactly("Kept");
    }

    @Test
    void findHonoursSitesAndReadRules() throws Exception {
        SiteId site = TestData.site(dataSource);
        TestData.siteOption(dataSource, site, "timezone_string", "\"UTC\"");
        // another site's attachment, written past the service
        PostId elsewhere = TestData.post(dataSource, site, "attachment", "inherit", "elsewhere", "Elsewhere");
        Jdbc.update(
                dataSource,
                "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, sizes, extra, alt_text,"
                        + " processing, stored_bytes) values (?, ?, ?, 'image/jpeg', 8, " + TestDb.json() + ", "
                        + TestDb.json() + ", '', 'none', 8)",
                elsewhere.value(),
                site.value(),
                "sites/" + site.value() + "/2026/11/elsewhere.jpg",
                "{}",
                "{}");
        assertThat(Identities.onSite(site, Identities.system(), () -> media.find(elsewhere)))
                .isPresent();
        assertThat(find(bob, elsewhere)).isEmpty();

        PostId hidden = stored(bob).post().id();
        update(bob, hidden, MediaChanges.builder().status("private"));
        assertThatThrownBy(() -> find(erin, hidden)).isInstanceOf(ForbiddenException.class);
        assertThat(find(bob, hidden)).isPresent();

        PostId post = TestData.post(dataSource, MAIN, "post", "publish", "not-media-" + UUID.randomUUID(), "Post");
        assertThat(find(bob, post)).isEmpty();
    }

    @Test
    void staleVersionsAreRefused() throws Exception {
        MediaSnapshot read = stored(bob, jpeg("stale.jpg").altText("First"));
        PostId id = read.post().id();
        update(bob, id, MediaChanges.builder().title("Changed"));
        MediaSnapshot current = found(id);

        for (MediaChanges.Builder stale : List.of(
                MediaChanges.builder()
                        .title("Stale")
                        .expectedVersion(read.post().version()),
                MediaChanges.builder()
                        .altText("Stale")
                        .expectedVersion(read.post().version()))) {
            assertThatThrownBy(() -> update(bob, id, stale)).isInstanceOf(OptimisticLockException.class);
        }
        assertThat(found(id)).isEqualTo(current);
        assertThat(update(
                                bob,
                                id,
                                MediaChanges.builder()
                                        .altText("Fresh")
                                        .expectedVersion(current.post().version()))
                        .altText())
                .isEqualTo("Fresh");
    }

    @Test
    void aStaleMediaRowIsNotWrittenOver() throws Exception {
        PostId id = stored(bob, jpeg("row.jpg").altText("Original")).post().id();

        assertThatThrownBy(() -> Identities.onSite(MAIN, Identities.system(), () -> {
                    MediaEntity row = mediaRows.findMedia(MAIN, id).orElseThrow();
                    // another writer changes the row once this one has read it
                    QuarkusTransaction.suspendingExisting()
                            .run(() -> Jdbc.update(
                                    dataSource,
                                    "update qp_media set alt_text = 'Theirs', version = version + 1 where post_id = ?",
                                    id.value()));
                    row.altText = "Ours";
                    mediaRows.flush();
                    return null;
                }))
                .isInstanceOf(OptimisticLockException.class);

        assertThat(found(id).altText()).isEqualTo("Theirs");
    }
}
