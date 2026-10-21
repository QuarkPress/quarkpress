package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermCreated;
import org.quarkpress.taxonomy.TermDraft;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.TermWriteContext;
import org.quarkpress.taxonomy.TermWriteFilter;
import org.quarkpress.taxonomy.Terms;

class CreateEventsDbTest {

    /** A clock the test sets; replaces the system clock. */
    @ApplicationScoped
    static class FixedClock implements QuarkPressClock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-21T08:00:00Z"));

        @Override
        public Instant now() {
            return now.get();
        }

        void set(Instant instant) {
            now.set(instant);
        }
    }

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final List<String> announced = new CopyOnWriteArrayList<>();

        List<String> seen() {
            return seen;
        }

        List<String> announced() {
            return announced;
        }

        void created(@Observes TermCreated event) {
            if (event.term().name().equals("explode")) {
                throw new IllegalStateException("observer refused");
            }
            if (event.term().name().equals("refuse")) {
                throw new TermException("observer_refused", "the observer refused the term");
            }
            seen.add("created " + event.term().slug() + " count=" + event.term().count());
            announced.add("site=" + event.site().value() + " term site="
                    + event.term().site().value() + " at=" + event.at());
        }
    }

    /**
     * Runs first: renames "shout", moves "taken" onto an existing slug, makes "long" too long and gives "adopt" the
     * parent in {@link #adopted}.
     */
    @ApplicationScoped
    @Priority(20)
    static class Shout implements TermWriteFilter {
        static final AtomicReference<Long> adopted = new AtomicReference<>(0L);

        @Override
        public TermDraft apply(TermDraft draft, TermWriteContext context) {
            if (draft.name().equals("adopt")) {
                return new TermDraft(
                        draft.name(), draft.slug(), draft.description(), Optional.of(TermId.of(adopted.get())));
            }
            if (draft.name().equals("shout")) {
                return new TermDraft("SHOUT", "shout-filtered", draft.description(), draft.parent());
            }
            if (draft.name().equals("taken")) {
                return new TermDraft(draft.name(), "occupied", draft.description(), draft.parent());
            }
            if (draft.name().equals("long")) {
                return new TermDraft("x".repeat(201), draft.slug(), draft.description(), draft.parent());
            }
            return draft;
        }
    }

    /** Runs second (lower priority) and sees the first filter's result. */
    @ApplicationScoped
    @Priority(10)
    static class Second implements TermWriteFilter {
        final List<String> contexts = new CopyOnWriteArrayList<>();
        final List<Optional<UserId>> actors = new CopyOnWriteArrayList<>();

        @Override
        public TermDraft apply(TermDraft draft, TermWriteContext context) {
            actors.add(context.actor());
            contexts.add(context.taxonomy() + " update=" + context.update() + " before="
                    + context.before().isPresent() + " name=" + draft.name());
            return draft;
        }

        List<String> contexts() {
            return contexts;
        }

        List<Optional<UserId>> actors() {
            return actors;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            TaxonomyApp.with(CreateEventsDbTest.class, FixedClock.class, Recorder.class, Shout.class, Second.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    @Inject
    Recorder recorder;

    @Inject
    Second second;

    @Inject
    FixedClock clock;

    private SiteId site;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        recorder.seen().clear();
        recorder.announced().clear();
        second.contexts().clear();
        second.actors().clear();
    }

    private TermSnapshot create(String name) throws Exception {
        return create("post_tag", name);
    }

    private TermSnapshot create(String taxonomy, String name) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> terms.create(taxonomy, TermChanges.builder().name(name).build()));
    }

    private long adoptRows() {
        return Jdbc.count(
                dataSource, "select count(*) from qp_term where site_id = ? and name = 'adopt'", site.value());
    }

    @Test
    void aCreatedTermIsAnnouncedOnce() throws Exception {
        create("Fresh");
        assertThat(recorder.seen()).containsExactly("created fresh count=0");
        assertThat(second.contexts()).containsExactly("post_tag update=false before=false name=Fresh");
    }

    @Test
    void filtersChangeTheDraftInPriorityOrder() throws Exception {
        TermSnapshot shout = create("shout");
        assertThat(shout.name()).isEqualTo("SHOUT");
        assertThat(shout.slug()).isEqualTo("shout-filtered");
        assertThat(second.contexts()).containsExactly("post_tag update=false before=false name=SHOUT");
    }

    @Test
    void whatAFilterWritesIsCheckedAgain() throws Exception {
        create("Occupied");
        assertThatThrownBy(() -> create("taken"))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("duplicate_term_slug"))
                .hasMessage("The slug &#8220;occupied&#8221; is already in use by another term.");
        assertThatThrownBy(() -> create("long"))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("db_insert_error"));
    }

    @Test
    void anObserverThatThrowsUndoesTheCreate() {
        assertThatThrownBy(() -> create("explode")).hasMessageContaining("observer refused");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and name = 'explode'",
                        site.value()))
                .isZero();
        assertThat(recorder.seen()).isEmpty();
    }

    @Test
    void theEventNamesTheSiteAndTheClocksTimeAndFiltersSeeTheCaller() throws Exception {
        clock.set(Instant.parse("2026-10-21T08:15:30.123456789Z"));
        create("Timed");
        // the time is kept to the microsecond, as the database keeps it
        assertThat(recorder.announced())
                .containsExactly(
                        "site=" + site.value() + " term site=" + site.value() + " at=2026-10-21T08:15:30.123456Z");
        assertThat(second.actors()).containsExactly(Optional.empty());
        second.actors().clear();
        UserId editor = TestData.profile(dataSource, "editor");
        // single-site security answers for the main site
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> terms.create(
                        "post_tag", TermChanges.builder().name("Mine").build()));
        assertThat(second.actors()).containsExactly(Optional.of(editor));
    }

    @Test
    void aParentAFilterGivesIsCheckedAgain() throws Exception {
        TermSnapshot tag = create("Tag");
        TermSnapshot world = create("category", "World");
        Shout.adopted.set(tag.id().value());
        assertThatThrownBy(() -> create("post_tag", "adopt"))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("rest_taxonomy_not_hierarchical"));
        assertThatThrownBy(() -> create("category", "adopt"))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("missing_parent"));
        Shout.adopted.set(999_999_999L);
        assertThatThrownBy(() -> create("category", "adopt"))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("missing_parent"));
        assertThat(adoptRows()).isZero();
        Shout.adopted.set(world.id().value());
        assertThat(create("category", "adopt").parent()).contains(world.id());
        assertThat(adoptRows()).isEqualTo(1);
    }

    @Test
    void aWriteRefusedAfterItsFirstWriteCannotBeCommittedByACallerThatCatchesTheRefusal() {
        List<String> caught = new CopyOnWriteArrayList<>();
        assertThatThrownBy(() -> Identities.onSite(site, Identities.system(), () -> {
                    try {
                        terms.create(
                                "post_tag", TermChanges.builder().name("refuse").build());
                    } catch (TermException refused) {
                        caught.add(refused.code());
                    }
                    return "caught";
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(caught).containsExactly("observer_refused");
        assertThat(Jdbc.count(
                        dataSource, "select count(*) from qp_term where site_id = ? and name = 'refuse'", site.value()))
                .isZero();
    }
}
