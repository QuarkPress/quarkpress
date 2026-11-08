package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The step after storing: it runs with the stored, pending attachment, committed and outside any transaction, before
 * the processing; when it fails, the attachment stays stored and unprocessed.
 */
class AfterStoreDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            MediaApp.withoutStorage(AfterStoreDbTest.class, TestStorage.class, TestProcessor.class);

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    TestStorage storage;

    @Inject
    TestProcessor processor;

    @Inject
    TestClock clock;

    @Inject
    TransactionManager transactionManager;

    UserId carol;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
    }

    private static Upload jpeg() {
        try {
            Path dir = Files.createDirectories(Path.of("target", "after-store"));
            Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), MediaFixtures.jpeg(400, 300));
            return Upload.builder("photo.jpg", file).declaredType("image/jpeg").build();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Runs {@code work} with the site scope's transaction suspended; what it throws reaches the caller as itself. */
    private static MediaSnapshot outsideTransaction(Callable<MediaSnapshot> work) {
        try {
            return QuarkusTransaction.suspendingExisting().call(work);
        } catch (QuarkusTransactionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw wrapped;
        }
    }

    private MediaSnapshot upload(Media.AfterStore afterStore) throws Exception {
        return Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> outsideTransaction(() -> media.upload(jpeg(), afterStore)));
    }

    @Test
    void afterStoreRunsBetweenStoringAndProcessing() throws Exception {
        List<String> seen = new ArrayList<>();
        MediaSnapshot answer = upload(stored -> {
            try {
                seen.add("processing " + stored.processing());
                seen.add("transaction " + transactionManager.getStatus());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            seen.add("rows "
                    + Jdbc.count(
                            dataSource,
                            "select count(*) from qp_media where post_id = ? and processing = 'pending'",
                            stored.post().id().value()));
            seen.add("sizes made " + processor.encodes());
        });
        assertThat(seen)
                .containsExactly(
                        "processing PENDING", "transaction " + Status.STATUS_NO_TRANSACTION, "rows 1", "sizes made 0");
        assertThat(answer.processing()).isEqualTo(Processing.DONE);
        assertThat(answer.sizes()).isNotEmpty();
    }

    @Test
    void anAfterStoreFailureLeavesThePendingAttachmentUnprocessed() throws Exception {
        IllegalStateException failure = new IllegalStateException("x");
        List<MediaSnapshot> stored = new ArrayList<>();
        assertThatThrownBy(() -> upload(attachment -> {
                    stored.add(attachment);
                    throw failure;
                }))
                .isSameAs(failure);
        MediaSnapshot read = Identities.onSite(
                        TestData.MAIN,
                        Identities.user(carol),
                        () -> media.read(stored.get(0).post().id()))
                .orElseThrow();
        assertThat(read.processing()).isEqualTo(Processing.PENDING);
        assertThat(read.sizes()).isEmpty();
        assertThat(read.wordPressMetadata()).isEmpty();
        assertThat(processor.encodes()).isZero();
        // the stored file stays
        assertThat(storage.keys()).containsExactly(read.storageKey());
    }

    @Test
    void uploadWithoutAStepProcessesAsBefore() throws Exception {
        MediaSnapshot answer = Identities.onSite(
                TestData.MAIN, Identities.user(carol), () -> outsideTransaction(() -> media.upload(jpeg())));
        assertThat(answer.processing()).isEqualTo(Processing.DONE);
        assertThat(answer.sizes()).isNotEmpty();
    }

    @Test
    void theAnswerIsReadAfterTheStep() throws Exception {
        MediaSnapshot answer = upload(stored -> {
            media.update(
                    stored.post().id(), MediaChanges.builder().title("Changed").build());
            // the processing run cannot read the attachment, twice: it fails, and the upload answers without it
            storage.failUrls(2);
        });
        assertThat(answer.post().title()).isEqualTo("Changed");
        assertThat(answer.processing()).isNotEqualTo(Processing.DONE);

        MediaSnapshot processed = upload(stored -> media.update(
                stored.post().id(), MediaChanges.builder().title("Also changed").build()));
        assertThat(processed.post().title()).isEqualTo("Also changed");
        assertThat(processed.processing()).isEqualTo(Processing.DONE);
    }
}
