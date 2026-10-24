package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.runtime.RevisionEntity;
import org.quarkpress.revisions.runtime.RevisionRepository;

class SchemaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(SchemaDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    RevisionRepository revisions;

    @Test
    void theRevisionsBandRunsAfterThePostsBand() {
        assertThat(Jdbc.strings(
                        dataSource,
                        "select script from qp_schema_history where success = true and type = 'SQL'"
                                + " order by installed_rank"))
                .containsSubsequence(
                        "db/quarkpress/" + TestDb.kind() + "/V4_001__posts_create_tables.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V6_001__revisions_create_table.sql");
    }

    @Test
    void aRevisionBelongsToAPostOfItsOwnSite() {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        PostId mine = TestData.post(dataSource, one, "post", "publish", "mine", "Mine");
        assertThatThrownBy(() -> TestData.revision(
                        dataSource, two, mine, null, "T", "C", "E", "{}", false, "2026-01-15T12:00:00Z"))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_revision_post_fk"));
        RevisionId kept =
                TestData.revision(dataSource, one, mine, null, "T", "C", "E", "{}", false, "2026-01-15T12:00:00Z");
        assertThat(Jdbc.count(dataSource, "select post_id from qp_revision where id = ?", kept.value()))
                .isEqualTo(mine.value());
    }

    @Test
    void oneAutosavePerUserAndPostButAnyNumberWithoutOwner() {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "user");
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        TestData.revision(dataSource, site, post, user, "a", "", "", "{}", true, "2026-01-15T12:00:00Z");
        assertThatThrownBy(() -> TestData.revision(
                        dataSource, site, post, user, "b", "", "", "{}", true, "2026-01-15T12:01:00Z"))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_revision_autosave_uq"));
        // regular revisions by the same user are not limited, nor are autosaves without an author
        TestData.revision(dataSource, site, post, user, "c", "", "", "{}", false, "2026-01-15T12:02:00Z");
        TestData.revision(dataSource, site, post, user, "d", "", "", "{}", false, "2026-01-15T12:03:00Z");
        TestData.revision(dataSource, site, post, null, "e", "", "", "{}", true, "2026-01-15T12:04:00Z");
        TestData.revision(dataSource, site, post, null, "f", "", "", "{}", true, "2026-01-15T12:05:00Z");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value()))
                .isEqualTo(5);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_revision where post_id = ? and autosave_owner = ?",
                        post.value(),
                        user.value()))
                .containsExactly("a");
    }

    @Test
    void deletingAPostOrASiteRemovesItsRevisions() {
        SiteId site = TestData.site(dataSource);
        PostId kept = TestData.post(dataSource, site, "post", "publish", "kept", "Kept");
        PostId gone = TestData.post(dataSource, site, "post", "publish", "gone", "Gone");
        TestData.revision(dataSource, site, kept, null, "k", "", "", "{}", false, "2026-01-15T12:00:00Z");
        TestData.revision(dataSource, site, gone, null, "g", "", "", "{}", false, "2026-01-15T12:00:00Z");
        Jdbc.update(dataSource, "delete from qp_post where id = ?", gone.value());
        assertThat(Jdbc.strings(dataSource, "select title from qp_revision where site_id = ?", site.value()))
                .containsExactly("k");
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site.value());
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_revision where site_id = ?", site.value()))
                .isZero();
    }

    @Test
    void aDeletedProfileLeavesItsRevisionsWithoutAuthor() {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "gone");
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        RevisionId revision =
                TestData.revision(dataSource, site, post, user, "t", "", "", "{}", true, "2026-01-15T12:00:00Z");
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", user.value());
        assertThat(Jdbc.strings(dataSource, "select author_id from qp_revision where id = ?", revision.value()))
                .containsExactly((String) null);
        assertThat(Jdbc.strings(dataSource, "select autosave_owner from qp_revision where id = ?", revision.value()))
                .containsExactly((String) null);
    }

    @Test
    void theMetaColumnHoldsOnlyJson() {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        assertThatThrownBy(() -> TestData.revision(
                        dataSource, site, post, null, "t", "", "", "not json", false, "2026-01-15T12:00:00Z"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(Failures.messages(failure))
                        .contains(
                                TestDb.kind().equals("mysql")
                                        ? "qp_revision_meta_json"
                                        : "invalid input syntax for type json"));
    }

    @Test
    void theEntityMapsTheRow() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "author");
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        Instant at = Instant.parse("2026-01-15T12:00:00Z");
        RevisionSnapshot stored = Identities.onSite(
                site,
                Identities.system(),
                () -> QuarkusTransaction.requiringNew().call(() -> {
                    RevisionEntity row = RevisionEntity.create(site, post);
                    row.authorId = user.value();
                    row.title = "Hello";
                    row.content = "Body";
                    row.excerpt = "Ex";
                    row.meta = JsonNodeFactory.instance
                            .objectNode()
                            .set(
                                    "footnotes",
                                    JsonNodeFactory.instance.arrayNode().add(TextNode.valueOf("[]")));
                    row.autosave = true;
                    row.createdAt = at;
                    revisions.persist(row);
                    revisions.flush();
                    return row.toSnapshot();
                }));
        assertThat(Jdbc.count(dataSource, "select autosave_owner from qp_revision where id = ?", stored.id().value()))
                .isEqualTo(user.value());
        assertThat(stored.post()).isEqualTo(post);
        assertThat(stored.site()).isEqualTo(site);
        assertThat(stored.author()).contains(user);
        assertThat(stored.title()).isEqualTo("Hello");
        assertThat(stored.content()).isEqualTo("Body");
        assertThat(stored.excerpt()).isEqualTo("Ex");
        assertThat(stored.meta("footnotes")).containsExactly(TextNode.valueOf("[]"));
        assertThat(stored.meta("absent")).isEmpty();
        assertThat(stored.autosave()).isTrue();
        assertThat(stored.createdAt()).isEqualTo(at);
        Optional<RevisionSnapshot> found = Identities.onSite(
                site,
                Identities.system(),
                () -> revisions.findRevision(site, stored.id()).map(RevisionEntity::toSnapshot));
        assertThat(found).contains(stored);
        assertThat(Identities.onSite(
                        TestData.MAIN, Identities.system(), () -> revisions.findRevision(TestData.MAIN, stored.id())))
                .isEmpty();
        assertThat(Identities.onSite(
                                site,
                                Identities.system(),
                                () -> revisions.autosaveOfAuthor(site, post, Optional.of(user)))
                        .map(RevisionEntity::toSnapshot))
                .contains(stored);
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> revisions.autosaveOfAuthor(site, post, Optional.empty())))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.system(), () -> revisions.countOfPost(site, post)))
                .isEqualTo(1);
    }

    @Test
    void revisionsOfAPostComeOldestFirstThenByIdAndLockInIdOrder() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        RevisionId later =
                TestData.revision(dataSource, site, post, null, "later", "", "", "{}", false, "2026-01-15T12:05:00Z");
        RevisionId firstOfTwo =
                TestData.revision(dataSource, site, post, null, "first", "", "", "{}", false, "2026-01-15T12:00:00Z");
        RevisionId secondOfTwo =
                TestData.revision(dataSource, site, post, null, "second", "", "", "{}", true, "2026-01-15T12:00:00Z");
        PostId other = TestData.post(dataSource, site, "post", "publish", "o", "O");
        TestData.revision(dataSource, site, other, null, "other", "", "", "{}", false, "2026-01-15T11:00:00Z");
        List<RevisionId> ids = Identities.onSite(
                site,
                Identities.system(),
                () -> revisions.ofPost(site, post).stream()
                        .map(row -> RevisionId.of(row.id))
                        .toList());
        assertThat(ids).containsExactly(firstOfTwo, secondOfTwo, later);
        List<RevisionId> locked = Identities.onSite(
                site,
                Identities.system(),
                () -> QuarkusTransaction.requiringNew()
                        .call(() -> revisions.lockOfPost(site, post).stream()
                                .map(row -> RevisionId.of(row.id))
                                .toList()));
        assertThat(locked).containsExactly(firstOfTwo, secondOfTwo, later);
    }
}
