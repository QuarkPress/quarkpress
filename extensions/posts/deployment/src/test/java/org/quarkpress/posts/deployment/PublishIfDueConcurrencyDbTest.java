package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPublished;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/** Concurrent callers of publishIfDue, as application nodes polling one database, publish a due post once. */
class PublishIfDueConcurrencyDbTest {

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        /** Read through a method: the injected client proxy does not share the bean's fields. */
        List<String> seen() {
            return seen;
        }

        void published(@Observes PostPublished event) {
            seen.add("published");
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            PostsApp.with(PublishIfDueConcurrencyDbTest.class, Recorder.class, TestClock.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    @Inject
    Recorder recorder;

    SiteId site;
    UserId editor;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        editor = TestData.memberOfMain(dataSource, "editor");
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(user), work);
    }

    @Test
    void twoCallersPublishOneDuePostOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            clock.set(Instant.parse("2026-01-15T12:00:00Z"));
            String title = "due " + round;
            PostSnapshot post = as(
                    editor,
                    () -> posts.create(PostChanges.builder()
                            .title(title)
                            .status("publish")
                            .dateGmt(Instant.parse("2026-01-15T13:00:00Z"))
                            .build()));
            assertThat(post.status()).isEqualTo("future");
            clock.set(Instant.parse("2026-01-15T13:00:00Z"));
            recorder.seen().clear();
            List<Object> results = Races.run(List.of(
                    () -> as(editor, () -> posts.publishIfDue(post.id())),
                    () -> as(editor, () -> posts.publishIfDue(post.id()))));
            assertThat(results).containsExactlyInAnyOrder(true, false);
            assertThat(recorder.seen()).containsExactly("published");
            assertThat(as(editor, () -> posts.find(post.id())).orElseThrow().status())
                    .isEqualTo("publish");
        }
    }
}
