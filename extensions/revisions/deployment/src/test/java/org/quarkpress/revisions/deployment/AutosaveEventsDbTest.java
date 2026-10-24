package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveSaved;
import org.quarkpress.revisions.RevisionCreated;
import org.quarkpress.revisions.Revisions;

class AutosaveEventsDbTest {

    @ApplicationScoped
    static class Recorder {
        /** Static: the injected recorder is a client proxy, whose own fields are not the bean's. */
        static final List<String> seen = new CopyOnWriteArrayList<>();

        void created(@Observes RevisionCreated event) {
            seen.add("created autosave=" + event.revision().autosave());
        }

        void autosaved(@Observes AutosaveSaved event) {
            seen.add("autosaved updated=" + event.updated() + " content="
                    + event.autosave().content());
        }

        void saved(@Observes PostSaved event) {
            seen.add("saved " + event.post().content());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(AutosaveEventsDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Revisions revisions;

    @Inject
    TestClock clock;

    private SiteId site;
    private UserId author;
    private UserId editor;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, TestData.MAIN, author, "[\"author\"]");
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    @Test
    void aCreatedAutosaveIsAnnouncedTwiceAnUpdatedOnceAnIdenticalOneNotAtAll() throws Exception {
        // the editor creates the author's published post, so the author may edit (and so autosave) it
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(PostChanges.builder()
                        .title("T")
                        .content("Body")
                        .status("publish")
                        .author(author)
                        .build()));
        Recorder.seen.clear();
        Identities.onSite(
                site,
                Identities.user(author),
                () -> revisions.autosave(
                        post.id(), AutosaveChanges.builder().content("a").build()));
        Identities.onSite(
                site,
                Identities.user(author),
                () -> revisions.autosave(
                        post.id(), AutosaveChanges.builder().content("b").build()));
        Identities.onSite(
                site,
                Identities.user(author),
                () -> revisions.autosave(
                        post.id(), AutosaveChanges.builder().content("Body").build()));
        assertThat(Recorder.seen)
                .containsExactly(
                        "created autosave=true",
                        "autosaved updated=false content=a",
                        "autosaved updated=true content=b");
    }

    @Test
    void anOwnDraftUpdateIsASaveWithoutARevision() throws Exception {
        PostSnapshot draft = Identities.onSite(
                site,
                Identities.user(author),
                () -> posts.create(PostChanges.builder()
                        .title("T")
                        .content("x")
                        .status("draft")
                        .build()));
        Recorder.seen.clear();
        Identities.onSite(
                site,
                Identities.user(author),
                () -> revisions.autosave(
                        draft.id(), AutosaveChanges.builder().content("xy").build()));
        assertThat(Recorder.seen).containsExactly("saved xy");
        // the mark is gone once the autosave returned: an ordinary update of the draft is revisioned again
        Identities.onSite(
                site,
                Identities.user(author),
                () -> posts.update(
                        draft.id(), PostChanges.builder().content("xyz").build()));
        assertThat(Recorder.seen).containsExactly("saved xy", "created autosave=false", "saved xyz");
    }
}
