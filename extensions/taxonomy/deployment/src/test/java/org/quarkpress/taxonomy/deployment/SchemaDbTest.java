package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.runtime.TermAssignmentEntity;
import org.quarkpress.taxonomy.runtime.TermAssignmentRepository;
import org.quarkpress.taxonomy.runtime.TermEntity;
import org.quarkpress.taxonomy.runtime.TermRepository;

class SchemaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.with(SchemaDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TermRepository terms;

    @Inject
    TermAssignmentRepository assignments;

    @Test
    void theTaxonomyBandRunsAfterThePostsBand() {
        assertThat(Jdbc.strings(
                        dataSource,
                        "select script from qp_schema_history where success = true and type = 'SQL'"
                                + " order by installed_rank"))
                .containsSubsequence(
                        "db/quarkpress/" + TestDb.kind() + "/V4_001__posts_create_tables.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V5_001__taxonomy_create_tables.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V5_002__taxonomy_default_category.sql");
    }

    @Test
    void theMainSiteHasUncategorizedNamedByItsDefaultCategory() {
        List<String> rows = Jdbc.strings(
                dataSource,
                "select concat(id, '|', name, '|', slug, '|', description, '|', count, '|', version) from qp_term"
                        + " where site_id = 1 and taxonomy = 'category' and parent_id is null");
        assertThat(rows).hasSize(1);
        String id = rows.get(0).substring(0, rows.get(0).indexOf('|'));
        assertThat(rows.get(0)).isEqualTo(id + "|Uncategorized|uncategorized||0|0");
        assertThat(TestData.option(dataSource, TestData.MAIN, "default_category"))
                .containsExactly(id);
        assertThat(Jdbc.strings(dataSource, "select meta from qp_term where id = ?", Long.parseLong(id)))
                .containsExactly("{}");
    }

    @Test
    void slugsAreUniquePerSiteAndTaxonomy() {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        TestData.term(dataSource, one, "post_tag", "News", "news", null);
        assertThatThrownBy(() -> TestData.term(dataSource, one, "post_tag", "News 2", "news", null))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_term_slug_uq"));
        TestData.term(dataSource, one, "category", "News", "news", null);
        TestData.term(dataSource, two, "post_tag", "News", "news", null);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where slug = 'news' and site_id in (?, ?)",
                        one.value(),
                        two.value()))
                .isEqualTo(3);
    }

    @Test
    void aParentMustBeATermOfTheSameSite() {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        TermId parent = TestData.term(dataSource, one, "category", "World", "world", null);
        assertThatThrownBy(() -> TestData.term(dataSource, two, "category", "News", "news", parent))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_term_parent_fk"));
        TermId child = TestData.term(dataSource, one, "category", "News", "news", parent);
        assertThat(Jdbc.count(dataSource, "select parent_id from qp_term where id = ?", child.value()))
                .isEqualTo(parent.value());
    }

    @Test
    void anAssignmentJoinsAPostAndATermOfOneSiteOnce() {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, one, "post", "publish", "p", "P");
        TermId mine = TestData.term(dataSource, one, "post_tag", "Mine", "mine", null);
        TermId theirs = TestData.term(dataSource, two, "post_tag", "Theirs", "theirs", null);
        assertThatThrownBy(() -> TestData.assign(dataSource, one, post, theirs))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_term_assignment_term_fk"));
        assertThatThrownBy(() -> TestData.assign(dataSource, two, post, theirs))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_term_assignment_post_fk"));
        TestData.assign(dataSource, one, post, mine);
        assertThatThrownBy(() -> TestData.assign(dataSource, one, post, mine))
                .satisfies(
                        failure -> assertThat(Failures.messages(failure)).contains("qp_term_assignment_post_term_uq"));
        assertThat(Jdbc.count(dataSource, "select term_order from qp_term_assignment where post_id = ?", post.value()))
                .isZero();
    }

    @Test
    void deletingAPostOrASiteRemovesWhatHangsOnIt() {
        SiteId site = TestData.site(dataSource);
        PostId kept = TestData.post(dataSource, site, "post", "publish", "kept", "Kept");
        PostId gone = TestData.post(dataSource, site, "post", "publish", "gone", "Gone");
        TermId top = TestData.term(dataSource, site, "category", "Top", "top", null);
        TermId middle = TestData.term(dataSource, site, "category", "Middle", "middle", top);
        TestData.term(dataSource, site, "category", "Bottom", "bottom", middle);
        TestData.assign(dataSource, site, kept, top);
        TestData.assign(dataSource, site, gone, top);
        Jdbc.update(dataSource, "delete from qp_post where id = ?", gone.value());
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term_assignment where site_id = ?", site.value()))
                .isEqualTo(1);
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site.value());
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term where site_id = ?", site.value()))
                .isZero();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term_assignment where site_id = ?", site.value()))
                .isZero();
    }

    @Test
    void theEntitiesMapTheRows() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "post", "publish", "mapped", "Mapped");
        TermId parent = TestData.term(dataSource, site, "category", "Parent", "parent", null);
        TermSnapshot stored = Identities.onSite(
                site,
                Identities.system(),
                () -> QuarkusTransaction.requiringNew().call(() -> {
                    TermEntity term = TermEntity.create(site, "category");
                    term.name = "Child";
                    term.slug = "child";
                    term.parentId = parent.value();
                    terms.persist(term);
                    assignments.persist(TermAssignmentEntity.create(site, post, TermId.of(term.id), 3));
                    return term.toSnapshot();
                }));
        assertThat(stored.taxonomy()).isEqualTo("category");
        assertThat(stored.name()).isEqualTo("Child");
        assertThat(stored.description()).isEmpty();
        assertThat(stored.parent()).contains(parent);
        assertThat(stored.count()).isZero();
        assertThat(stored.site()).isEqualTo(site);
        Optional<TermSnapshot> found = Identities.onSite(
                site,
                Identities.system(),
                () -> terms.findTerm(site, stored.id(), "category").map(TermEntity::toSnapshot));
        assertThat(found).contains(stored);
        assertThat(Identities.onSite(site, Identities.system(), () -> terms.findTerm(site, stored.id(), "post_tag")))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.system(), () -> assignments.postsOf(site, stored.id())))
                .containsExactly(post);
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> terms.findTerm(site, stored.id()).map(TermEntity::toSnapshot)))
                .contains(stored);
        // bound to the term's own site, so only the site the query names keeps another site's id from finding it
        assertThat(Identities.onSite(site, Identities.system(), () -> terms.findTerm(TestData.MAIN, stored.id())))
                .isEmpty();
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> terms.findTerm(TestData.MAIN, stored.id(), "category")))
                .isEmpty();
    }

    @Test
    void theDefaultCategoryMigrationGivesOnlyTheSitesThatLackOneTheirCategoryAndCanRunAgain() {
        SiteId zero = bareSite();
        TestData.siteOption(dataSource, zero, "default_category", "0");
        SiteId missing = bareSite();
        SiteId set = bareSite();
        TermId own = TestData.term(dataSource, set, "category", "Own", "own", null);
        TestData.siteOption(dataSource, set, "default_category", Long.toString(own.value()));

        runDefaultCategoryMigration();

        for (SiteId site : List.of(zero, missing)) {
            List<String> categories = Jdbc.strings(
                    dataSource,
                    "select id from qp_term where site_id = ? and taxonomy = 'category' and slug = 'uncategorized'"
                            + " and name = 'Uncategorized'",
                    site.value());
            assertThat(categories).hasSize(1);
            assertThat(TestData.option(dataSource, site, "default_category")).containsExactlyElementsOf(categories);
        }
        assertThat(Jdbc.strings(dataSource, "select slug from qp_term where site_id = ?", set.value()))
                .containsExactly("own");
        assertThat(TestData.option(dataSource, set, "default_category")).containsExactly(Long.toString(own.value()));

        List<String> termsBefore = Jdbc.strings(dataSource, "select concat(id, '|', site_id, '|', slug) from qp_term");
        List<String> optionsBefore = defaultCategoryOptions();
        runDefaultCategoryMigration();
        assertThat(Jdbc.strings(dataSource, "select concat(id, '|', site_id, '|', slug) from qp_term"))
                .containsExactlyInAnyOrderElementsOf(termsBefore);
        assertThat(defaultCategoryOptions()).containsExactlyInAnyOrderElementsOf(optionsBefore);
    }

    /** A site row with no options and no terms. */
    private SiteId bareSite() {
        String domain = "bare-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'bare')", domain);
        return SiteId.of(Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain));
    }

    private List<String> defaultCategoryOptions() {
        return Jdbc.strings(
                dataSource,
                "select concat(site_id, '|', " + (TestDb.kind().equals("postgresql") ? "cast(value as text)" : "value")
                        + ", '|', updated_at) from qp_option where name = 'default_category'");
    }

    /** Runs the default-category migration script again, statement by statement, without its comment lines. */
    private void runDefaultCategoryMigration() {
        String script;
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("db/quarkpress/" + TestDb.kind() + "/V5_002__taxonomy_default_category.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StringBuilder code = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.startsWith("--")) {
                code.append(line).append('\n');
            }
        }
        for (String statement : code.toString().split(";")) {
            if (!statement.isBlank()) {
                Jdbc.update(dataSource, statement);
            }
        }
    }
}
