package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.UploadCheck;
import org.quarkpress.media.UploadFilter;
import org.quarkpress.posts.PostDraft;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostWriteContext;
import org.quarkpress.posts.PostWriteFilter;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * An upload reads, checks and stores its file before it opens the transaction that writes the attachment's rows, so
 * that no database connection is held while a large file is copied; the stored file still goes when those rows fail.
 */
class UploadTransactionDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
            UploadTransactionDbTest.class,
            TestStorage.class,
            TestProcessor.class,
            StatusFilter.class,
            RefusingWrites.class);

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
    TransactionManager transactions;

    UserId carol;

    private static int status(TransactionManager transactions) {
        try {
            return transactions.getStatus();
        } catch (SystemException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Records the transaction status each upload check runs in (static: the test sees the bean through a proxy). */
    @ApplicationScoped
    public static class StatusFilter implements UploadFilter {
        static final List<Integer> statuses = new CopyOnWriteArrayList<>();

        @Inject
        TransactionManager transactions;

        @Override
        public Optional<String> refuse(UploadCheck upload) {
            statuses.add(status(transactions));
            return Optional.empty();
        }
    }

    /** Records the transaction status attachments are written in, and refuses those titled {@code refused}. */
    @ApplicationScoped
    public static class RefusingWrites implements PostWriteFilter {
        static final List<Integer> statuses = new CopyOnWriteArrayList<>();

        @Inject
        TransactionManager transactions;

        @Override
        public PostDraft apply(PostDraft draft, PostWriteContext context) {
            if (draft.type().equals("attachment")) {
                statuses.add(status(transactions));
                if (draft.title().equals("refused")) {
                    throw new PostException("refused", "The test filter refused the attachment.");
                }
            }
            return draft;
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        StatusFilter.statuses.clear();
        RefusingWrites.statuses.clear();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "upload-transaction"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Stores {@code upload} as carol, outside any transaction of the caller's; a failure is thrown as it is. */
    private MediaSnapshot store(Upload.Builder upload) throws Exception {
        return Identities.onSite(TestData.MAIN, Identities.user(carol), () -> {
            try {
                return QuarkusTransaction.suspendingExisting().call(() -> media.store(upload.build()));
            } catch (QuarkusTransactionException wrapped) {
                // suspending wraps whatever the work threw
                throw wrapped.getCause() instanceof Exception cause ? cause : wrapped;
            }
        });
    }

    private static Upload.Builder jpeg(String name) {
        return Upload.builder(name, file(MediaFixtures.jpeg(400, 300))).declaredType("image/jpeg");
    }

    @Test
    void theFileWorkHappensBeforeTheRowsTransaction() throws Exception {
        List<Integer> storing = new CopyOnWriteArrayList<>();
        storage.duringPuts(() -> storing.add(status(transactions)));
        MediaSnapshot stored = store(jpeg("outside.jpg"));
        assertThat(StatusFilter.statuses).containsExactly(Status.STATUS_NO_TRANSACTION);
        assertThat(storing).containsExactly(Status.STATUS_NO_TRANSACTION);
        // the rows themselves are written in a transaction
        assertThat(RefusingWrites.statuses).containsExactly(Status.STATUS_ACTIVE);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where id = ? and type = 'attachment'",
                        stored.post().id().value()))
                .isEqualTo(1);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_media where post_id = ?",
                        stored.post().id().value()))
                .isEqualTo(1);
        assertThat(storage.keys()).containsExactly("2026/11/outside.jpg");
    }

    @Test
    void aFailedInsertStillRemovesTheStoredFile() throws Exception {
        long before = Jdbc.count(dataSource, "select count(*) from qp_post where type = 'attachment'");
        assertThatThrownBy(() -> store(jpeg("refused.jpg").title("refused")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("refused"));
        // the file was stored, and removed again when the rows' transaction rolled back
        assertThat(storage.puts()).isEqualTo(1);
        assertThat(storage.keys()).isEmpty();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where type = 'attachment'"))
                .isEqualTo(before);
    }

    @Test
    void anErrorInTheChecksReachesTheCallerAsItself() throws Exception {
        StackOverflowError error = new StackOverflowError("a check overflowed");
        processor.errorOnSupports(error);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Identities.onSite(TestData.MAIN, Identities.user(carol), () -> {
            // the caller's transaction is suspended by hand, so that nothing on the way wraps what the upload throws
            Transaction outer = transactions.suspend();
            try {
                media.store(jpeg("overflow.jpg").build());
            } catch (Throwable failure) {
                thrown.set(failure);
            } finally {
                transactions.resume(outer);
            }
            return null;
        });
        assertThat(thrown.get()).isSameAs(error);
        assertThat(storage.puts()).isZero();
    }
}
