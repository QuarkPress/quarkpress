package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.AutosaveSaved;
import org.quarkpress.revisions.Revisions;

/** Two autosaves of one user on one post at the same time leave one row. */
class AutosaveConcurrencyDbTest {

    /** Synchronous observers run under the locks, so their order per post is the order the writes took effect. */
    @ApplicationScoped
    static class Recorder {
        /** Static: the injected recorder is a client proxy, whose own fields are not the bean's. */
        static final Map<Long, List<String>> seen = new ConcurrentHashMap<>();

        void autosaved(@Observes AutosaveSaved event) {
            seen.computeIfAbsent(event.autosave().post().value(), post -> new CopyOnWriteArrayList<>())
                    .add((event.updated() ? "updated " : "created ")
                            + event.autosave().content());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(AutosaveConcurrencyDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    @Test
    void twoAutosavesOfOneUserLeaveOneRow() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        for (int round = 0; round < 5; round++) {
            // a fresh post per round, so every round races the insert of the first autosave
            PostId post = TestData.postBy(dataSource, site, editor, "post", "publish", "Raced " + round);
            String a = "a" + round;
            String b = "b" + round;
            List<Object> results = Races.run(List.<Callable<Object>>of(
                    () -> Identities.onSite(
                            site,
                            Identities.user(editor),
                            () -> revisions.autosave(
                                    post, AutosaveChanges.builder().content(a).build())),
                    () -> Identities.onSite(
                            site,
                            Identities.user(editor),
                            () -> revisions.autosave(
                                    post, AutosaveChanges.builder().content(b).build()))));
            for (Object result : results) {
                assertThat(result).isInstanceOf(AutosaveResult.Stored.class);
            }
            // one write created the row, the other updated it: the later text is the one that stays
            List<String> events = Recorder.seen.get(post.value());
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).startsWith("created ");
            assertThat(events.get(1)).startsWith("updated ");
            assertThat(Jdbc.strings(dataSource, "select content from qp_revision where post_id = ?", post.value()))
                    .hasSize(1)
                    .containsExactly(events.get(1).substring("updated ".length()));
        }
    }
}
