package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.RevisionCreated;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionRestored;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

/** The restore's events, in WordPress's order: the save, its revision, then the restore, then the meta. */
class RestoreEventsDbTest {

    @ApplicationScoped
    static class Recorder {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        @Inject
        PostMeta meta;

        void saved(@Observes PostSaved event) {
            seen.add("saved " + event.post().title());
        }

        void created(@Observes RevisionCreated event) {
            seen.add("created " + event.revision().title() + " meta="
                    + event.revision().meta("footnotes"));
        }

        void restored(@Observes RevisionRestored event) {
            // read through JPA: sees the in-memory state, not just what is flushed
            List<JsonNode> value = meta.get(event.post(), "footnotes");
            seen.add("restored " + event.revision().value() + " post-meta=" + value);
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(RestoreEventsDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    PostMeta meta;

    @Inject
    Revisions revisions;

    @Test
    void theRestoreIsAnnouncedAfterTheSaveAndBeforeTheMeta() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        PostSnapshot post = Identities.onSite(site, Identities.user(editor), () -> {
            PostSnapshot created = posts.create(
                    PostChanges.builder().title("v1").status("publish").build());
            meta.update(created.id(), "footnotes", TextNode.valueOf("[0]"));
            posts.update(created.id(), PostChanges.builder().title("v2").build());
            meta.update(created.id(), "footnotes", TextNode.valueOf("[1]"));
            return posts.update(created.id(), PostChanges.builder().title("v3").build());
        });
        RevisionSnapshot v2 =
                Identities.onSite(site, Identities.user(editor), () -> revisions.ofPost(post.id(), RevisionQuery.all()))
                        .stream()
                        .filter(revision -> revision.title().equals("v2"))
                        .findFirst()
                        .orElseThrow();
        Recorder.seen.clear();
        Identities.onSite(site, Identities.user(editor), () -> revisions.restore(v2.id(), Set.of()));
        assertThat(Recorder.seen)
                .containsExactly(
                        "created v2 meta=[\"[1]\"]",
                        "saved v2",
                        "restored " + v2.id().value() + " post-meta=[\"[1]\"]");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select meta from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("{\"footnotes\":[\"[0]\"]}");
    }
}
