package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermDraft;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.TermUpdated;
import org.quarkpress.taxonomy.TermWriteContext;
import org.quarkpress.taxonomy.TermWriteFilter;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.runtime.TermEntity;
import org.quarkpress.taxonomy.runtime.TermHierarchy;
import org.quarkpress.taxonomy.runtime.TermRepository;

class UpdateDbTest {

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        List<String> seen() {
            return seen;
        }

        void updated(@Observes TermUpdated event) {
            seen.add(event.before().slug() + ">" + event.after().slug() + " parent "
                    + event.before().parent().map(TermId::value).orElse(0L) + ">"
                    + event.after().parent().map(TermId::value).orElse(0L));
        }
    }

    /** On update, puts into the draft what the columns cannot hold, for the names that ask for it. */
    @ApplicationScoped
    @Priority(10)
    static class Unstorable implements TermWriteFilter {
        @Override
        public TermDraft apply(TermDraft draft, TermWriteContext context) {
            if (!context.update()) {
                return draft;
            }
            return switch (draft.name()) {
                case "long slug" -> new TermDraft(draft.name(), "s".repeat(201), draft.description(), draft.parent());
                case "nul name" -> new TermDraft("nul" + (char) 0, draft.slug(), draft.description(), draft.parent());
                case "nul slug" -> new TermDraft(draft.name(), "s" + (char) 0, draft.description(), draft.parent());
                case "nul description" -> new TermDraft(draft.name(), draft.slug(), "d" + (char) 0, draft.parent());
                default -> draft;
            };
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.with(UpdateDbTest.class, Recorder.class, Unstorable.class);

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    @Inject
    TermRepository rows;

    @Inject
    Recorder recorder;

    private SiteId site;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        recorder.seen().clear();
    }

    private TermSnapshot create(String taxonomy, String name, String slug, TermId parent) throws Exception {
        TermChanges.Builder changes = TermChanges.builder().name(name).slug(slug);
        if (parent != null) {
            changes.parent(parent.value());
        }
        return Identities.onSite(site, Identities.system(), () -> terms.create(taxonomy, changes.build()));
    }

    private TermSnapshot update(TermSnapshot term, TermChanges.Builder changes) throws Exception {
        return Identities.onSite(
                site, Identities.system(), () -> terms.update(term.id(), term.taxonomy(), changes.build()));
    }

    private String refusal(TermSnapshot term, String taxonomy, TermChanges.Builder changes) {
        try {
            Identities.onSite(site, Identities.system(), () -> terms.update(term.id(), taxonomy, changes.build()));
        } catch (TermException refused) {
            return refused.code();
        } catch (Exception other) {
            throw new AssertionError("expected a TermException, got " + other, other);
        }
        throw new AssertionError("the update was not refused");
    }

    @Test
    void renamingKeepsTheSlugAndAnEmptySlugRegeneratesIt() throws Exception {
        TermSnapshot old = create("post_tag", "Old", "", null);
        TermSnapshot renamed = update(old, TermChanges.builder().name("New"));
        assertThat(renamed.name()).isEqualTo("New");
        assertThat(renamed.slug()).isEqualTo("old");
        assertThat(renamed.version()).isEqualTo(old.version() + 1);
        create("post_tag", "Newer", "new", null);
        assertThat(update(renamed, TermChanges.builder().slug("")).slug()).isEqualTo("new-2");
        assertThat(recorder.seen()).containsExactly("old>old parent 0>0", "old>new-2 parent 0>0");
    }

    @Test
    void renamingToASiblingsNameIsAllowed() throws Exception {
        create("post_tag", "A", "", null);
        TermSnapshot b = create("post_tag", "B", "", null);
        assertThat(update(b, TermChanges.builder().name("A")).name()).isEqualTo("A");
        assertThat(Jdbc.count(
                        dataSource, "select count(*) from qp_term where site_id = ? and name = 'A'", site.value()))
                .isEqualTo(2);
    }

    @Test
    void aTakenSlugIsRefusedUnlessRegeneratedOrTheParentChanges() throws Exception {
        create("post_tag", "a", "", null);
        TermSnapshot tagB = create("post_tag", "b", "", null);
        assertThat(refusal(tagB, "post_tag", TermChanges.builder().slug("a"))).isEqualTo("duplicate_term_slug");
        TermSnapshot catA = create("category", "a", "", null);
        TermSnapshot catB = create("category", "b", "", null);
        TermSnapshot moved =
                update(catB, TermChanges.builder().slug("a").parent(catA.id().value()));
        assertThat(moved.slug()).isEqualTo("a-a");
        assertThat(moved.parent()).contains(catA.id());
        TermSnapshot catC = create("category", "c", "", null);
        assertThat(update(catC, TermChanges.builder().name("A").slug("")).slug())
                .isEqualTo("a-2");
        assertThat(update(catA, TermChanges.builder().slug("a")).slug()).isEqualTo("a");
        // a parent the loop check drops leaves the parent as it was: the taken slug is refused
        assertThat(refusal(
                        catA,
                        "category",
                        TermChanges.builder().slug("a-a").parent(catA.id().value())))
                .isEqualTo("duplicate_term_slug");
        assertThat(refusal(
                        catC, "category", TermChanges.builder().name("Renamed").slug("a")))
                .isEqualTo("duplicate_term_slug");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(name, '|', slug) from qp_term where id in (?, ?) order by id",
                        catA.id().value(),
                        catC.id().value()))
                .containsExactly("a|a", "A|a-2");
    }

    @Test
    void aRegeneratedSlugIsMadeFromTheUnslashedName() throws Exception {
        TermSnapshot tag = create("post_tag", "Plain", "", null);
        // unslashed, the backslashes leave room for the encoded characters within sanitize_title's 200 bytes;
        // slashed, they would fill it and leave no slug
        String name = "\\".repeat(150) + cp(0x65E5, 0x672C);
        TermSnapshot renamed = update(tag, TermChanges.builder().name(name).slug(""));
        assertThat(renamed.name()).isEqualTo(name);
        assertThat(renamed.slug()).isEqualTo("%e6%97%a5%e6%9c%ac");
    }

    @Test
    void valuesTheColumnsCannotHoldAreRefusedAsUpdateErrorsAndNothingIsWritten() throws Exception {
        TermSnapshot tag = create("post_tag", "Kept", "", null);
        assertThat(refusal(tag, "post_tag", TermChanges.builder().name(cp(0xE9).repeat(201))))
                .isEqualTo("db_update_error");
        assertThat(refusal(tag, "post_tag", TermChanges.builder().name("long slug")))
                .isEqualTo("db_update_error");
        assertThat(refusal(tag, "post_tag", TermChanges.builder().name("nul name")))
                .isEqualTo("db_update_error");
        assertThat(refusal(tag, "post_tag", TermChanges.builder().name("nul description")))
                .isEqualTo("db_update_error");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(name, '|', slug, '|', version) from qp_term where id = ?",
                        tag.id().value()))
                .containsExactly("Kept|kept|" + tag.version());
        assertThat(recorder.seen()).isEmpty();
    }

    @Test
    void loopsAreCutSilently() throws Exception {
        TermSnapshot a = create("category", "A", "", null);
        TermSnapshot b = create("category", "B", "", a.id());
        TermSnapshot c = create("category", "C", "", b.id());
        assertThat(update(a, TermChanges.builder().parent(c.id().value())).parent())
                .isEmpty();
        assertThat(update(b, TermChanges.builder().parent(b.id().value())).parent())
                .isEmpty();
        assertThat(update(c, TermChanges.builder().parent(a.id().value())).parent())
                .contains(a.id());
    }

    @Test
    void aLoopFurtherUpIsCutAndAnnounced() throws Exception {
        TermSnapshot x = create("category", "X", "", null);
        TermSnapshot y = create("category", "Y", "", x.id());
        Jdbc.update(dataSource, "update qp_term set parent_id = ? where id = ?", y.id().value(), x.id().value());
        TermSnapshot z = create("category", "Z", "", null);
        recorder.seen().clear();
        assertThat(update(z, TermChanges.builder().parent(x.id().value())).parent())
                .contains(x.id());
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where id in (?, ?) and parent_id is null",
                        x.id().value(),
                        y.id().value()))
                .isEqualTo(2);
        // each loop member is announced as it loses its parent, in the loop check's order, before the term itself
        Map<TermId, TermId> stored = Map.of(x.id(), y.id(), y.id(), x.id());
        List<TermId> cut = TermHierarchy.check(z.id(), Optional.of(x.id()), id -> Optional.ofNullable(stored.get(id)))
                .cut();
        assertThat(cut).containsExactlyInAnyOrder(x.id(), y.id());
        List<String> expected = new ArrayList<>();
        for (TermId member : cut) {
            String slug = member.equals(x.id()) ? x.slug() : y.slug();
            expected.add(slug + ">" + slug + " parent " + stored.get(member).value() + ">0");
        }
        expected.add("z>z parent 0>" + x.id().value());
        assertThat(recorder.seen()).containsExactlyElementsOf(expected);
    }

    @Test
    void parentsFollowTheRestRules() throws Exception {
        TermSnapshot tag = create("post_tag", "Tag", "", null);
        TermSnapshot other = create("post_tag", "Other", "", null);
        TermSnapshot category = create("category", "Cat", "", null);
        assertThat(refusal(
                        tag, "post_tag", TermChanges.builder().parent(other.id().value())))
                .isEqualTo("rest_taxonomy_not_hierarchical");
        TermSnapshot child = create("category", "Child", "", null);
        assertThat(refusal(
                        child, "category", TermChanges.builder().parent(tag.id().value())))
                .isEqualTo("missing_parent");
        assertThat(update(child, TermChanges.builder().parent(category.id().value()))
                        .parent())
                .contains(category.id());
        assertThat(update(child, TermChanges.builder().parent(0)).parent()).isEmpty();
    }

    @Test
    void aTermOfAnotherTaxonomyOrSiteIsInvalid() throws Exception {
        TermSnapshot category = create("category", "Cat", "", null);
        assertThat(refusal(category, "post_tag", TermChanges.builder().name("x")))
                .isEqualTo("invalid_term");
        assertThat(refusal(category, "bogus", TermChanges.builder().name("x"))).isEqualTo("invalid_taxonomy");
        SiteId mine = site;
        site = TestData.site(dataSource);
        assertThat(refusal(category, "category", TermChanges.builder().name("x")))
                .isEqualTo("invalid_term");
        site = mine;
    }

    @Test
    void storedTextIsFilteredAgainAndAnEmptyNameIsRefused() throws Exception {
        TermSnapshot tag = create("post_tag", "Raw", "", null);
        Jdbc.update(
                dataSource,
                "update qp_term set name = '<b>Bold</b> & co' where id = ?",
                tag.id().value());
        TermSnapshot again = update(tag, TermChanges.builder().description("<script>x</script>kept"));
        assertThat(again.name()).isEqualTo("Bold &amp; co");
        assertThat(again.description()).isEqualTo("xkept");
        assertThat(refusal(again, "post_tag", TermChanges.builder().name("<p></p>")))
                .isEqualTo("empty_term_name");
    }

    @Test
    void aCountChangeDoesNotMakeAnUpdateStale() throws Exception {
        TermSnapshot tag = create("post_tag", "Counted", "", null);
        Identities.onSite(
                site,
                Identities.system(),
                () -> QuarkusTransaction.requiringNew().call(() -> {
                    TermEntity row = rows.lockTerm(site, tag.id()).orElseThrow();
                    row.termCount = 3;
                    rows.flush();
                    return null;
                }));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(count, '|', version) from qp_term where id = ?",
                        tag.id().value()))
                .containsExactly("3|" + tag.version());
        TermSnapshot renamed = update(tag, TermChanges.builder().name("Renamed").expectedVersion(tag.version()));
        assertThat(renamed.count()).isEqualTo(3);
    }

    @Test
    void aNulSlugFromAFilterIsRefusedAndTheTransactionStaysUsable() throws Exception {
        TermSnapshot tag = create("post_tag", "Plain", "", null);
        TermSnapshot after = Identities.onSite(site, Identities.system(), () -> {
            assertThatThrownBy(() -> terms.update(
                            tag.id(),
                            "post_tag",
                            TermChanges.builder().name("nul slug").build()))
                    .isInstanceOfSatisfying(TermException.class, refused -> {
                        assertThat(refused.code()).isEqualTo("db_update_error");
                        assertThat(refused.detail()).isEqualTo("the slug holds a NUL character");
                    });
            return terms.update(
                    tag.id(),
                    "post_tag",
                    TermChanges.builder().description("kept").build());
        });
        assertThat(after.name()).isEqualTo("Plain");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(name, '|', slug, '|', description) from qp_term where id = ?",
                        tag.id().value()))
                .containsExactly("Plain|plain|kept");
    }

    @Test
    void aCountChangedByAnotherTransactionMeanwhileDoesNotFailARename() throws Exception {
        TermSnapshot tag = create("post_tag", "Busy", "", null);
        TermSnapshot renamed = Identities.onSite(site, Identities.system(), () -> {
            // the caller read the term before another transaction changed its count
            assertThat(rows.findTerm(site, tag.id()).orElseThrow().termCount).isZero();
            QuarkusTransaction.requiringNew().run(() -> {
                rows.lockTerm(site, tag.id()).orElseThrow().termCount = 1;
                rows.flush();
            });
            return terms.update(
                    tag.id(),
                    "post_tag",
                    TermChanges.builder()
                            .name("Renamed")
                            .expectedVersion(tag.version())
                            .build());
        });
        assertThat(renamed.count()).isEqualTo(1);
        assertThat(renamed.version()).isEqualTo(tag.version() + 1);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(name, '|', count, '|', version) from qp_term where id = ?",
                        tag.id().value()))
                .containsExactly("Renamed|1|" + (tag.version() + 1));
    }

    @Test
    void aConcurrentRenameMakesAnUpdateStale() throws Exception {
        TermSnapshot tag = create("post_tag", "First", "", null);
        update(tag, TermChanges.builder().name("Second"));
        String kept = Identities.onSite(site, Identities.system(), () -> {
            try {
                terms.update(
                        tag.id(),
                        "post_tag",
                        TermChanges.builder()
                                .name("Third")
                                .expectedVersion(tag.version())
                                .build());
                return "updated";
            } catch (OptimisticLockException stale) {
                // the caller's transaction stays usable
                return terms.update(
                                tag.id(),
                                "post_tag",
                                TermChanges.builder().description("after").build())
                        .name();
            }
        });
        assertThat(kept).isEqualTo("Second");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(name, '|', description) from qp_term where id = ?",
                        tag.id().value()))
                .containsExactly("Second|after");
    }
}
