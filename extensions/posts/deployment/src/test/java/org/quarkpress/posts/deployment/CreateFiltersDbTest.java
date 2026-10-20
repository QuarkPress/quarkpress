package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostDraft;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSlugFilter;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostWriteContext;
import org.quarkpress.posts.PostWriteFilter;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SlugContext;

class CreateFiltersDbTest {

    /** Runs first (higher priority): marks the title. */
    @ApplicationScoped
    @Priority(200)
    static class First implements PostWriteFilter {
        @Override
        public PostDraft apply(PostDraft draft, PostWriteContext context) {
            return draft.title().startsWith("filter-me") ? draft.withTitle(draft.title() + " [first]") : draft;
        }
    }

    /** Runs second: sees the first one's change; can move the parent elsewhere. */
    @ApplicationScoped
    @Priority(100)
    static class Second implements PostWriteFilter {
        static volatile Optional<PostId> parent = Optional.empty();

        @Override
        public PostDraft apply(PostDraft draft, PostWriteContext context) {
            if (draft.title().startsWith("reparent")) {
                return draft.withParent(parent);
            }
            return draft.title().startsWith("filter-me") ? draft.withTitle(draft.title() + " [second]") : draft;
        }
    }

    @ApplicationScoped
    static class Suffix implements PostSlugFilter {
        @Override
        public String apply(String uniqueSlug, SlugContext context) {
            return context.original().startsWith("slugfilter") ? uniqueSlug + "-x" : uniqueSlug;
        }
    }

    /** Refuses the late slug of a page, which is decided after the row was written. */
    @ApplicationScoped
    static class LateRefusal implements PostSlugFilter {
        @Override
        public String apply(String uniqueSlug, SlugContext context) {
            if (context.post().isPresent() && context.type().equals("page")) {
                throw new PostException("late_refused", "the late slug is refused");
            }
            return uniqueSlug;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            PostsApp.with(CreateFiltersDbTest.class, First.class, Second.class, Suffix.class, LateRefusal.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TransactionManager transactions;

    SiteId site;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes.build()));
    }

    @Test
    void writeFiltersRunHigherPriorityFirst() throws Exception {
        assertThat(create(PostChanges.builder().title("filter-me")).title()).isEqualTo("filter-me [first] [second]");
    }

    @Test
    void slugFiltersSeeTheOriginalSlug() throws Exception {
        assertThat(create(PostChanges.builder().title("slugfilter post").status("publish"))
                        .slug())
                .isEqualTo("slugfilter-post-x");
        assertThat(create(PostChanges.builder().title("Plain").status("publish"))
                        .slug())
                .isEqualTo("plain");
    }

    @Test
    void aParentAFilterGivesIsCheckedToo() {
        SiteId other = TestData.site(dataSource);
        Second.parent = Optional.of(TestData.post(dataSource, other, "page", "publish", "far", "Far"));
        assertThatThrownBy(() -> create(PostChanges.builder().title("reparent me")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_parent"));
    }

    @Test
    void aRefusalAfterTheRowWasWrittenRollsTheInsertBack() {
        AtomicInteger status = new AtomicInteger(-1);
        assertThatThrownBy(() -> Identities.onSite(site, Identities.system(), () -> {
                    assertThatThrownBy(() -> posts.create(PostChanges.builder()
                                    .type("page")
                                    .title("!!!")
                                    .status("publish")
                                    .build()))
                            .isInstanceOfSatisfying(
                                    PostException.class,
                                    e -> assertThat(e.code()).isEqualTo("late_refused"))
                            .hasMessage("the late slug is refused");
                    status.set(transactions.getStatus());
                    return null;
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
    }
}
