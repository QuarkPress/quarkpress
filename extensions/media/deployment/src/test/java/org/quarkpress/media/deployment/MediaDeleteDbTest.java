package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.RollbackException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.WriteMode;
import org.quarkpress.posts.PostDeleting;
import org.quarkpress.posts.Posts;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Deleting an attachment as WordPress's {@code wp_delete_attachment} does: always for good, the featured images that
 * name it cleared, the media row gone with the post, then every file of it removed once the delete has committed, in
 * WordPress's order, each name only inside the main file's folder and the main file only inside the site's uploads.
 */
class MediaDeleteDbTest {

    static final String DELETION_LOGGER = "org.quarkpress.media.runtime.AttachmentDeletion";

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    MediaDeleteDbTest.class,
                    TestStorage.class,
                    TestProcessor.class,
                    TestMultisiteMode.class,
                    FailingObserver.class)
            .overrideConfigKey("quarkpress.posts.empty-trash-days", "30");

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
    TestProcessor processor;

    @Inject
    TestClock clock;

    @Inject
    FailingObserver failing;

    UserId bob;
    UserId carol;

    /** Fails the delete of one attachment after the media module's observer has cleaned up (no client proxy). */
    @Singleton
    public static class FailingObserver {
        volatile long failFor;

        void deleting(@Observes PostDeleting event) {
            if (event.post().id().value() == failFor) {
                throw new IllegalArgumentException("a later observer refuses the delete");
            }
        }
    }

    final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    final Handler recorder = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        failing.failFor = 0;
        TestData.siteOption(dataSource, MAIN, "timezone_string", "\"UTC\"");
        bob = TestData.memberOfMain(dataSource, "editor");
        carol = TestData.memberOfMain(dataSource, "author");
        warnings.clear();
        Logger.getLogger(DELETION_LOGGER).addHandler(recorder);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(DELETION_LOGGER).removeHandler(recorder);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-delete"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Upload.Builder jpeg(String name, int width, int height) {
        return Upload.builder(name, file(MediaFixtures.jpeg(width, height))).declaredType("image/jpeg");
    }

    /** Runs {@code work} on {@code site} as {@code user}, outside any transaction; what it throws is rethrown. */
    private <T> T as(SiteId site, UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> {
            try {
                return QuarkusTransaction.suspendingExisting().call(work);
            } catch (QuarkusTransactionException wrapped) {
                throw wrapped.getCause() instanceof Exception cause ? cause : wrapped;
            }
        });
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return as(MAIN, user, work);
    }

    private MediaSnapshot upload(SiteId site, Upload.Builder upload) throws Exception {
        return as(site, bob, () -> media.upload(upload.build()));
    }

    private MediaSnapshot upload(Upload.Builder upload) throws Exception {
        return upload(MAIN, upload);
    }

    private Optional<MediaSnapshot> delete(SiteId site, UserId user, PostId id) throws Exception {
        return as(site, user, () -> media.delete(id));
    }

    private Optional<MediaSnapshot> delete(PostId id) throws Exception {
        return delete(MAIN, bob, id);
    }

    private long rows(PostId id) {
        return Jdbc.count(dataSource, "select count(*) from qp_media where post_id = ?", id.value());
    }

    private long postRows(PostId id) {
        return Jdbc.count(dataSource, "select count(*) from qp_post where id = ?", id.value());
    }

    private void put(String key, String content) throws IOException {
        storage.put(key, file(content.getBytes(StandardCharsets.US_ASCII)), "text/plain", WriteMode.REPLACE);
    }

    private void column(PostId id, String column, String json) {
        Jdbc.update(
                dataSource,
                "update qp_media set " + column + " = " + TestDb.json() + " where post_id = ?",
                json,
                id.value());
    }

    private void meta(PostId id, String json) {
        Jdbc.update(dataSource, "update qp_post set meta = " + TestDb.json() + " where id = ?", json, id.value());
    }

    private String postMeta(PostId id) {
        return Jdbc.strings(dataSource, "select meta from qp_post where id = ?", id.value())
                .get(0)
                .replace(" ", "");
    }

    private static String folderKey(String name) {
        return "2026/11/" + name;
    }

    private List<String> warningTexts() {
        List<String> texts = new ArrayList<>();
        for (LogRecord record : warnings) {
            Object[] parameters = record.getParameters();
            texts.add(parameters == null ? record.getMessage() : String.format(record.getMessage(), parameters));
        }
        return texts;
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void everyFileGoesAfterTheRow() throws Exception {
        MediaSnapshot m = upload(jpeg("IMG_1234.jpg", 4000, 3000));
        PostId id = m.post().id();
        assertThat(storage.keys()).hasSize(8);

        // inside the deleting transaction the files are still there; they go once it has committed
        MediaSnapshot deleted = Identities.onSite(
                MAIN,
                Identities.user(bob),
                () -> QuarkusTransaction.requiringNew().call(() -> {
                    MediaSnapshot answer = media.delete(id).orElseThrow();
                    assertThat(storage.deletes()).isEmpty();
                    assertThat(storage.keys()).hasSize(8);
                    return answer;
                }));

        assertThat(deleted.storageKey()).isEqualTo("2026/11/IMG_1234-scaled.jpg");
        assertThat(deleted.post().id()).isEqualTo(id);
        assertThat(deleted.sizes()).hasSize(6);
        assertThat(as(bob, () -> media.find(id))).isEmpty();
        assertThat(rows(id)).isZero();
        assertThat(postRows(id)).isZero();
        assertThat(storage.keys()).isEmpty();
        // WordPress's order: the sizes in their order, the original, the main file last
        List<String> expected = new ArrayList<>();
        for (MediaSize size : m.sizes().values()) {
            expected.add(folderKey(size.file()));
        }
        expected.add(folderKey("IMG_1234.jpg"));
        expected.add(folderKey("IMG_1234-scaled.jpg"));
        assertThat(storage.deletes()).containsExactlyElementsOf(expected);
        assertThat(warnings).isEmpty();
    }

    @Test
    void featuredImagesPointingAtItAreCleared() throws Exception {
        MediaSnapshot m = upload(jpeg("pictured.jpg", 8, 8));
        MediaSnapshot other = upload(jpeg("other.jpg", 8, 8));
        long id = m.post().id().value();
        PostId asNumber = TestData.post(dataSource, MAIN, "post", "publish", "", "A");
        PostId asText = TestData.post(dataSource, MAIN, "post", "draft", "", "B");
        PostId kept = TestData.post(dataSource, MAIN, "post", "publish", "", "C");
        meta(asNumber, "{\"_thumbnail_id\":[" + id + "]}");
        meta(asText, "{\"_thumbnail_id\":[\"" + id + "\"]}");
        meta(kept, "{\"_thumbnail_id\":[" + other.post().id().value() + "]}");

        delete(m.post().id());

        assertThat(postMeta(asNumber)).isEqualTo("{}");
        assertThat(postMeta(asText)).isEqualTo("{}");
        assertThat(postMeta(kept))
                .isEqualTo("{\"_thumbnail_id\":[" + other.post().id().value() + "]}");
    }

    @Test
    void aMissingMainFileStillDeletesTheRest() throws Exception {
        MediaSnapshot m = upload(jpeg("gone.jpg", 1200, 800));
        assertThat(m.sizes()).isNotEmpty();
        storage.delete(m.storageKey());
        assertThat(storage.keys()).hasSize(m.sizes().size());

        assertThat(delete(m.post().id())).isPresent();

        assertThat(storage.keys()).isEmpty();
        assertThat(rows(m.post().id())).isZero();
    }

    @Test
    void aSharedSizeFileIsDeletedOnce() throws Exception {
        MediaSnapshot m = upload(jpeg("x.jpg", 3000, 4000));
        assertThat(m.sizes().get("medium_large").file()).isEqualTo("x-768x1024.jpg");
        assertThat(m.sizes().get("large").file()).isEqualTo("x-768x1024.jpg");

        delete(m.post().id());

        assertThat(storage.deletes())
                .filteredOn(key -> key.equals(folderKey("x-768x1024.jpg")))
                .hasSize(1);
        assertThat(storage.deletes()).doesNotHaveDuplicates();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void backupSizesAndClientFlowKeysAreDeleted() throws Exception {
        MediaSnapshot m = upload(jpeg("x.jpg", 8, 8));
        PostId id = m.post().id();
        meta(
                id,
                "{\"_wp_attachment_backup_sizes\":[{\"full-orig\":{\"file\":\"x-e1700000000123.jpg\",\"width\":8,"
                        + "\"height\":8},\"thumbnail-orig\":{\"file\":\"x-e1700000000123-150x150.jpg\"}}]}");
        column(
                id,
                "extra",
                "{\"source_image\":\"x.heic\",\"animated_video\":\"x.mp4\",\"animated_video_poster\":\"x-poster.jpg\"}");
        for (String name :
                List.of("x-e1700000000123.jpg", "x-e1700000000123-150x150.jpg", "x.heic", "x.mp4", "x-poster.jpg")) {
            put(folderKey(name), name);
        }
        put(folderKey("unrelated.jpg"), "kept");

        delete(id);

        assertThat(storage.keys()).containsExactly(folderKey("unrelated.jpg"));
        assertThat(storage.deletes())
                .containsExactly(
                        folderKey("x.heic"),
                        folderKey("x.mp4"),
                        folderKey("x-poster.jpg"),
                        folderKey("x-e1700000000123.jpg"),
                        folderKey("x-e1700000000123-150x150.jpg"),
                        folderKey("x.jpg"));
    }

    @Test
    void craftedFileNamesOutsideTheFolderAreNotDeleted() throws Exception {
        MediaSnapshot m = upload(jpeg("x.jpg", 200, 150));
        PostId id = m.post().id();
        assertThat(m.sizes()).containsOnlyKeys("thumbnail");
        String thumbnail = m.sizes().get("thumbnail").file();
        column(
                id,
                "sizes",
                "{\"thumbnail\":{\"file\":\"" + thumbnail + "\",\"width\":150,\"height\":150,"
                        + "\"mime-type\":\"image/jpeg\",\"filesize\":1},"
                        + "\"evil\":{\"file\":\"../../keep.txt\",\"width\":1,\"height\":1},"
                        + "\"deep\":{\"file\":\"a/b.jpg\",\"width\":1,\"height\":1},"
                        + "\"back\":{\"file\":\"a\\\\b.jpg\",\"width\":1,\"height\":1},"
                        + "\"up\":{\"file\":\"..\",\"width\":1,\"height\":1},"
                        + "\"nul\":{\"file\":\"x\\u0000.jpg\",\"width\":1,\"height\":1},"
                        + "\"empty\":{\"file\":\"\",\"width\":1,\"height\":1},"
                        + "\"dots\":{\"file\":\"x..jpg\",\"width\":1,\"height\":1}}");
        put("keep.txt", "kept");
        put(folderKey("a/b.jpg"), "kept");
        put(folderKey("x..jpg"), "a plain name with two dots");

        delete(id);

        assertThat(storage.keys()).containsExactlyInAnyOrder("keep.txt", folderKey("a/b.jpg"));
        assertThat(storage.deletes()).containsExactly(folderKey(thumbnail), folderKey("x..jpg"), folderKey("x.jpg"));
        assertThat(rows(id)).isZero();
        assertThat(warningTexts())
                .hasSize(6)
                .anySatisfy(text -> assertThat(text).contains("../../keep.txt"))
                .anySatisfy(text -> assertThat(text).contains("a/b.jpg"));
    }

    @Test
    void aMainKeyOfAnotherSiteIsNeverDeleted() throws Exception {
        SiteId site = TestData.siteIn(dataSource, "UTC");
        SiteId neighbour = TestData.site(dataSource);
        TestData.member(dataSource, site, bob, "[\"editor\"]");
        MediaSnapshot m = upload(site, jpeg("x.jpg", 400, 300));
        assertThat(m.storageKey()).isEqualTo("sites/" + site.value() + "/2026/11/x.jpg");
        String foreign = "sites/" + neighbour.value() + "/2026/11/x.jpg";
        Jdbc.update(
                dataSource,
                "update qp_media set storage_key = ? where post_id = ?",
                foreign,
                m.post().id().value());
        put(foreign, "the neighbour's file");
        List<String> before = storage.keys();

        assertThat(delete(site, bob, m.post().id())).isPresent();

        assertThat(storage.keys()).isEqualTo(before).contains(foreign);
        assertThat(storage.deletes()).isEmpty();
        assertThat(rows(m.post().id())).isZero();
        assertThat(postRows(m.post().id())).isZero();
        assertThat(warningTexts())
                .singleElement()
                .satisfies(text -> assertThat(text).contains(foreign));

        // the main site's uploads hold no key of another site either
        warnings.clear();
        MediaSnapshot main = upload(jpeg("y.jpg", 8, 8));
        String other = "sites/" + neighbour.value() + "/2026/11/y.jpg";
        Jdbc.update(
                dataSource,
                "update qp_media set storage_key = ? where post_id = ?",
                other,
                main.post().id().value());
        put(other, "the neighbour's other file");

        assertThat(delete(main.post().id())).isPresent();

        assertThat(storage.keys()).contains(other, folderKey("y.jpg"));
        assertThat(storage.deletes()).isEmpty();
        assertThat(rows(main.post().id())).isZero();
        assertThat(warningTexts())
                .singleElement()
                .satisfies(text -> assertThat(text).contains(other));
    }

    @Test
    void deleteDuringProcessLeavesNoOrphan() throws Exception {
        for (int round = 0; round < 4; round++) {
            String name = "race" + round;
            MediaSnapshot stored =
                    as(bob, () -> media.store(jpeg(name + ".jpg", 1200, 800).build()));
            PostId id = stored.post().id();
            processor.slowDecodes(round * 20L);

            Races.run(List.of(() -> as(bob, () -> media.process(id)), () -> as(bob, () -> media.delete(id))));

            assertThat(rows(id)).isZero();
            assertThat(postRows(id)).isZero();
            assertThat(storage.keys()).as("round %d", round).noneMatch(key -> key.startsWith(folderKey(name)));
        }
    }

    @Test
    void aRolledBackDeleteKeepsEveryFile() throws Exception {
        MediaSnapshot m = upload(jpeg("kept.jpg", 400, 300));
        PostId id = m.post().id();
        List<String> before = storage.keys();

        Identities.onSite(MAIN, Identities.user(bob), () -> {
            assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                        assertThat(posts.delete(id, true)).isPresent();
                        QuarkusTransaction.setRollbackOnly();
                    }))
                    .isInstanceOf(QuarkusTransactionException.class)
                    .hasCauseInstanceOf(RollbackException.class);
            return null;
        });

        assertThat(storage.keys()).isEqualTo(before);
        assertThat(storage.deletes()).isEmpty();
        assertThat(rows(id)).isOne();
        assertThat(as(bob, () -> media.find(id))).contains(m);
    }

    @Test
    void aStorageFailureAfterTheRowIsLoggedNotRaised() throws Exception {
        MediaSnapshot m = upload(jpeg("stuck.jpg", 400, 300));
        storage.deleteFails(new IllegalStateException("the storage is down"));

        assertThat(delete(m.post().id())).isPresent();

        assertThat(rows(m.post().id())).isZero();
        assertThat(storage.deletes()).hasSize(m.sizes().size() + 1);
        assertThat(warnings)
                .hasSize(m.sizes().size() + 1)
                .allSatisfy(record -> assertThat(record.getThrown()).hasMessage("the storage is down"));
    }

    @Test
    void deletingThroughPostsDeletesTheFilesToo() throws Exception {
        MediaSnapshot m = upload(jpeg("posts.jpg", 400, 300));
        PostId other = TestData.post(dataSource, MAIN, "post", "publish", "", "Pictured");
        meta(other, "{\"_thumbnail_id\":[" + m.post().id().value() + "]}");

        as(bob, () -> posts.delete(m.post().id(), true));

        assertThat(storage.keys()).isEmpty();
        assertThat(rows(m.post().id())).isZero();
        assertThat(postMeta(other)).isEqualTo("{}");
    }

    @Test
    void deleteNeedsDeletePost() throws Exception {
        MediaSnapshot m = upload(jpeg("bobs.jpg", 400, 300));
        List<String> before = storage.keys();

        assertThatThrownBy(() -> delete(MAIN, carol, m.post().id())).isInstanceOf(ForbiddenException.class);

        assertThat(storage.keys()).isEqualTo(before);
        assertThat(storage.deletes()).isEmpty();
        assertThat(rows(m.post().id())).isOne();
        assertThat(postRows(m.post().id())).isOne();
    }

    @Test
    void deleteIsAlwaysPermanent() throws Exception {
        MediaSnapshot m = upload(jpeg("trash.jpg", 8, 8));

        assertThat(delete(m.post().id())).isPresent();

        assertThat(postRows(m.post().id())).isZero();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where status = 'trash' and id = ?",
                        m.post().id().value()))
                .isZero();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void onlyAttachmentsAreDeleted() throws Exception {
        PostId post = TestData.post(dataSource, MAIN, "post", "publish", "", "Not media");

        assertThat(delete(post)).isEmpty();
        assertThat(delete(PostId.of(999_999_999L))).isEmpty();

        assertThat(postRows(post)).isOne();
    }

    @Test
    void aLegacyThumbIsKeptWhileAnotherAttachmentNamesIt() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String thumb = "old_" + unique + ".thumbnail.jpg";
        MediaSnapshot first = upload(jpeg("first.jpg", 8, 8));
        MediaSnapshot second = upload(jpeg("second.jpg", 8, 8));
        MediaSnapshot third = upload(jpeg("third.jpg", 8, 8));
        column(first.post().id(), "extra", "{\"thumb\":\"" + thumb + "\"}");
        column(second.post().id(), "extra", "{\"thumb\":\"" + thumb + "\"}");
        // a name that matches the thumb only when like's wildcards are not escaped
        String lookalike = "oldX" + unique + ".thumbnail.jpg";
        column(third.post().id(), "extra", "{\"thumb\":\"" + lookalike + "\"}");
        put(folderKey(thumb), "shared");
        put(folderKey(lookalike), "the third's");

        delete(first.post().id());
        assertThat(storage.keys()).contains(folderKey(thumb));
        assertThat(storage.deletes()).containsExactly(folderKey("first.jpg"));

        delete(second.post().id());
        assertThat(storage.keys()).doesNotContain(folderKey(thumb)).contains(folderKey(lookalike));
        // the legacy thumbnail comes first
        assertThat(storage.deletes())
                .containsExactly(folderKey("first.jpg"), folderKey(thumb), folderKey("second.jpg"));
    }

    @Test
    void deleteNeedsTheMediaFeature() throws Exception {
        SiteId site = TestData.siteIn(dataSource, "UTC");
        TestData.member(dataSource, site, bob, "[\"editor\"]");
        MediaSnapshot m = upload(site, jpeg("off.jpg", 8, 8));
        TestData.siteOption(dataSource, site, "qp_features", "{\"media\": false}");
        List<String> before = storage.keys();

        assertThatThrownBy(() -> delete(site, bob, m.post().id()))
                .isInstanceOfSatisfying(
                        SiteFeatureDisabledException.class,
                        refusal -> assertThat(refusal.key()).isEqualTo("media"));

        assertThat(rows(m.post().id())).isOne();
        assertThat(postRows(m.post().id())).isOne();
        assertThat(storage.keys()).isEqualTo(before);
        assertThat(storage.deletes()).isEmpty();
    }

    @Test
    void aLegacyThumbNamedAnywhereInAnotherAttachmentsMetadataIsKept() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String inExtra = "legacy-a-" + unique + ".thumbnail.jpg";
        String inFile = "legacy-b-" + unique + ".thumbnail.jpg";
        String inOriginal = "legacy-c-" + unique + ".thumbnail.jpg";
        List<String> thumbs = List.of(inExtra, inFile, inOriginal);
        List<MediaSnapshot> owners = new ArrayList<>();
        for (String thumb : thumbs) {
            MediaSnapshot owner = upload(jpeg("owner.jpg", 8, 8));
            column(owner.post().id(), "extra", "{\"thumb\":\"" + thumb + "\"}");
            put(folderKey(thumb), thumb);
            owners.add(owner);
        }
        // another attachment names each: in its metadata in other letter case, as its file, as its original
        MediaSnapshot first = upload(jpeg("holder.jpg", 8, 8));
        column(first.post().id(), "extra", "{\"thumb\":\"" + inExtra.toUpperCase(java.util.Locale.ROOT) + "\"}");
        MediaSnapshot second = upload(jpeg("holder.jpg", 8, 8));
        Jdbc.update(
                dataSource,
                "update qp_media set storage_key = ? where post_id = ?",
                folderKey(inFile),
                second.post().id().value());
        MediaSnapshot third = upload(jpeg("holder.jpg", 8, 8));
        Jdbc.update(
                dataSource,
                "update qp_media set original_key = ? where post_id = ?",
                folderKey(inOriginal),
                third.post().id().value());

        for (MediaSnapshot owner : owners) {
            delete(owner.post().id());
        }

        for (String thumb : thumbs) {
            assertThat(storage.keys()).contains(folderKey(thumb));
            assertThat(storage.deletes()).doesNotContain(folderKey(thumb));
        }
    }

    @Test
    void aDeleteFailingAfterTheCleanUpChangesNothing() throws Exception {
        MediaSnapshot m = upload(jpeg("refused.jpg", 400, 300));
        PostId pictured = TestData.post(dataSource, MAIN, "post", "publish", "", "Pictured");
        meta(pictured, "{\"_thumbnail_id\":[" + m.post().id().value() + "]}");
        List<String> before = storage.keys();
        failing.failFor = m.post().id().value();

        assertThatThrownBy(() -> delete(m.post().id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a later observer refuses the delete");

        assertThat(rows(m.post().id())).isOne();
        assertThat(postRows(m.post().id())).isOne();
        assertThat(postMeta(pictured))
                .isEqualTo("{\"_thumbnail_id\":[" + m.post().id().value() + "]}");
        assertThat(storage.keys()).isEqualTo(before);
        assertThat(storage.deletes()).isEmpty();
    }
}
