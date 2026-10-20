package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostDeleted;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTrashed;
import org.quarkpress.posts.Posts;

/** With the trash turned off, trashing and deleting delete at once. */
class TrashOffDbTest {

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        // the test reaches this bean through its client proxy: state goes through methods, not fields
        List<String> seen() {
            return seen;
        }

        void trashed(@Observes PostTrashed event) {
            seen.add("trashed " + event.post().title());
        }

        void deleted(@Observes PostDeleted event) {
            seen.add("deleted " + event.id().value());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(TrashOffDbTest.class, Recorder.class)
            .overrideRuntimeConfigKey("quarkpress.posts.empty-trash-days", "0");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Recorder recorder;

    SiteId site;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        recorder.seen().clear();
    }

    private PostSnapshot create(String title) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(
                        PostChanges.builder().title(title).status("publish").build()));
    }

    private long count() {
        return Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value());
    }

    @Test
    void trashingAndDeletingDeleteAtOnce() throws Exception {
        PostSnapshot trashed = create("One");
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.trash(trashed.id())))
                .map(PostSnapshot::status)
                .contains("publish");
        PostSnapshot deleted = create("Two");
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.delete(deleted.id(), false)))
                .map(PostSnapshot::status)
                .contains("publish");
        assertThat(count()).isZero();
        assertThat(recorder.seen())
                .containsExactly(
                        "deleted " + trashed.id().value(),
                        "deleted " + deleted.id().value());
    }

    @Test
    void trashingAPostAlreadyInTheTrashDeletesIt() throws Exception {
        PostId old = TestData.post(dataSource, site, "page", "trash", "old", "Old");
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.trash(old)))
                .map(PostSnapshot::status)
                .contains("trash");
        assertThat(count()).isZero();
    }
}
