package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.runtime.DefaultTerms;

class PostTermsDbTest {

    @Taxonomy(key = "genre", objectTypes = "post", hierarchical = true, defaultTerm = "General")
    static class Genre {}

    @Taxonomy(key = "medium", objectTypes = "attachment")
    static class Medium {}

    /** A taxonomy whose default term only editors may assign. */
    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(PostTermsDbTest.class, Genre.class, Medium.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TaxonomyBuildItem(TaxonomyDefinition.builder("mood", List.of("post"))
                                    .defaultTerm("Calm")
                                    .capabilities(Map.of("assign_terms", "manage_categories"))
                                    .build()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build());

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Terms terms;

    @Inject
    DefaultTerms defaults;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostLocks locks;

    private SiteId site;
    private UserId editor;
    private UserId contributor;

    @BeforeEach
    void newSite() throws Exception {
        site = TestData.site(dataSource);
        // a site created after startup gets its custom default terms when it is created; this one is written by JDBC
        Identities.onSite(site, Identities.system(), () -> {
            locks.lock(site);
            defaults.ensure(site, taxonomies.require("genre"));
            return defaults.ensure(site, taxonomies.require("mood"));
        });
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]");
        contributor = TestData.profile(dataSource, "contributor");
        TestData.member(dataSource, site, contributor, "[\"contributor\"]");
    }

    private PostSnapshot create(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    private PostSnapshot update(PostId post, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.update(post, changes.build()));
    }

    private List<String> slugs(PostId post, String taxonomy) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.postTerms(post, taxonomy)).stream()
                .map(TermSnapshot::slug)
                .toList();
    }

    private long count(TermId term) {
        return Jdbc.count(dataSource, "select count from qp_term where id = ?", term.value());
    }

    @Test
    void aSavedPostGetsTheDefaultCategory() throws Exception {
        PostSnapshot draft = create(editor, PostChanges.builder().title("Draft").status("draft"));
        assertThat(slugs(draft.id(), "category")).containsExactly("uncategorized");
        TermId uncategorized = TestData.defaultCategory(dataSource, site);
        assertThat(count(uncategorized)).isZero();
        update(draft.id(), PostChanges.builder().status("publish"));
        assertThat(count(uncategorized)).isEqualTo(1);
    }

    @Test
    void autoDraftsAndPagesGetNone() throws Exception {
        PostSnapshot auto = create(editor, PostChanges.builder().title("Auto").status("auto-draft"));
        assertThat(slugs(auto.id(), "category")).isEmpty();
        PostSnapshot page =
                create(editor, PostChanges.builder().type("page").title("Page").status("publish"));
        assertThat(slugs(page.id(), "category")).isEmpty();
        update(auto.id(), PostChanges.builder().status("draft"));
        assertThat(slugs(auto.id(), "category")).containsExactly("uncategorized");
    }

    @Test
    void aPostWithACategoryKeepsItAndOneWithoutGetsTheDefaultAgainOnSave() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Post").status("publish"));
        TermId news = TestData.term(dataSource, site, "category", "News", "news", null);
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> terms.setPostTerms(post.id(), "category", List.of(TermRef.id(news)), false));
        update(post.id(), PostChanges.builder().title("Renamed"));
        assertThat(slugs(post.id(), "category")).containsExactly("news");
        Identities.onSite(
                site, Identities.user(editor), () -> terms.setPostTerms(post.id(), "category", List.of(), false));
        assertThat(slugs(post.id(), "category")).isEmpty();
        update(post.id(), PostChanges.builder().title("Again"));
        assertThat(slugs(post.id(), "category")).containsExactly("uncategorized");
        assertThat(count(news)).isZero();
        assertThat(count(TestData.defaultCategory(dataSource, site))).isEqualTo(1);
    }

    @Test
    void customDefaultTermsGoToCallersWhoMayAssignThem() throws Exception {
        PostSnapshot byEditor = create(editor, PostChanges.builder().title("E").status("draft"));
        assertThat(slugs(byEditor.id(), "genre")).containsExactly("general");
        assertThat(slugs(byEditor.id(), "mood")).containsExactly("calm");
        PostSnapshot byContributor =
                create(contributor, PostChanges.builder().title("C").status("draft"));
        assertThat(slugs(byContributor.id(), "genre")).containsExactly("general");
        assertThat(slugs(byContributor.id(), "mood")).isEmpty();
        PostSnapshot bySystem = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(
                        PostChanges.builder().title("S").status("draft").build()));
        assertThat(slugs(bySystem.id(), "mood")).containsExactly("calm");
    }

    @Test
    void countsFollowStatusesAcrossPublish() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("Counted").status("draft"));
        TermId tag = TestData.term(dataSource, site, "post_tag", "a", "a", null);
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> terms.setPostTerms(post.id(), "post_tag", List.of(TermRef.id(tag)), false));
        assertThat(count(tag)).isZero();
        update(post.id(), PostChanges.builder().status("publish"));
        assertThat(count(tag)).isEqualTo(1);
        Identities.onSite(site, Identities.user(editor), () -> posts.trash(post.id()));
        assertThat(count(tag)).isZero();
        Identities.onSite(site, Identities.user(editor), () -> posts.untrash(post.id()));
        assertThat(count(tag)).isZero();
        update(post.id(), PostChanges.builder().status("publish"));
        assertThat(count(tag)).isEqualTo(1);
        update(post.id(), PostChanges.builder().status("private"));
        assertThat(count(tag)).isZero();
        update(post.id(), PostChanges.builder().status("future").dateGmt(Instant.parse("2090-01-01T00:00:00Z")));
        assertThat(count(tag)).isZero();
        Identities.onSite(site, Identities.system(), () -> posts.publish(post.id()));
        assertThat(count(tag)).isEqualTo(1);
    }

    @Test
    void deletingAPostRemovesItsAssignmentsAndRecounts() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Doomed").status("publish"));
        TermId tag = TestData.term(dataSource, site, "post_tag", "d", "d", null);
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> terms.setPostTerms(post.id(), "post_tag", List.of(TermRef.id(tag)), false));
        assertThat(count(tag)).isEqualTo(1);
        Identities.onSite(site, Identities.user(editor), () -> posts.delete(post.id(), true));
        assertThat(count(tag)).isZero();
        assertThat(count(TestData.defaultCategory(dataSource, site))).isZero();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term_assignment where post_id = ?",
                        post.id().value()))
                .isZero();
    }

    @Test
    void attachmentsCountThroughTheirParentButAreNotRecountedWhenItChanges() throws Exception {
        PostSnapshot parent =
                create(editor, PostChanges.builder().title("Parent").status("publish"));
        PostId attached = TestData.post(dataSource, site, "attachment", "inherit", "img", "Img");
        Jdbc.update(
                dataSource,
                "update qp_post set parent_id = ? where id = ?",
                parent.id().value(),
                attached.value());
        PostId loose = TestData.post(dataSource, site, "attachment", "inherit", "loose", "Loose");
        TermId medium = TestData.term(dataSource, site, "medium", "photo", "photo", null);
        for (PostId attachment : List.of(attached, loose)) {
            Identities.onSite(
                    site,
                    Identities.system(),
                    () -> terms.setPostTerms(attachment, "medium", List.of(TermRef.id(medium)), true));
        }
        assertThat(count(medium)).isEqualTo(1);
        update(parent.id(), PostChanges.builder().status("draft"));
        assertThat(count(medium)).isEqualTo(1);
    }

    // Beyond the brief: the publish path, the option's lookup, missing categories, and deletes across taxonomies.

    private TermId defaultTermOf(String taxonomy) {
        return TermId.of(Long.parseLong(
                TestData.option(dataSource, site, "default_term_" + taxonomy).get(0)));
    }

    private long assignmentsOf(PostId post) {
        return Jdbc.count(
                dataSource,
                "select count(*) from qp_term_assignment where site_id = ? and post_id = ?",
                site.value(),
                post.value());
    }

    @Test
    void publishingGivesTheDefaultsAndCustomOnesOnlyToCallersWhoMayAssignThem() throws Exception {
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        PostSnapshot byAuthor = create(author, PostChanges.builder().title("A").status("draft"));
        assertThat(slugs(byAuthor.id(), "mood")).isEmpty();
        Identities.onSite(
                site, Identities.user(author), () -> terms.setPostTerms(byAuthor.id(), "category", List.of(), false));
        assertThat(slugs(byAuthor.id(), "category")).isEmpty();
        assertThat(Identities.onSite(site, Identities.user(author), () -> posts.publish(byAuthor.id())))
                .isTrue();
        assertThat(slugs(byAuthor.id(), "category")).containsExactly("uncategorized");
        assertThat(slugs(byAuthor.id(), "genre")).containsExactly("general");
        // WordPress's wp_publish_post would give the default term to any caller
        assertThat(slugs(byAuthor.id(), "mood")).isEmpty();
        assertThat(count(TestData.defaultCategory(dataSource, site))).isEqualTo(1);
        assertThat(count(defaultTermOf("genre"))).isEqualTo(1);

        PostSnapshot byContributor =
                create(contributor, PostChanges.builder().title("C").status("draft"));
        assertThat(slugs(byContributor.id(), "mood")).isEmpty();
        Identities.onSite(site, Identities.user(editor), () -> posts.publish(byContributor.id()));
        assertThat(slugs(byContributor.id(), "mood")).containsExactly("calm");
        assertThat(count(defaultTermOf("mood"))).isEqualTo(1);
    }

    @Test
    void theDefaultCategoryIsTheTermItsOptionNamesInTheTaxonomy() throws Exception {
        TermId news = TestData.term(dataSource, site, "category", "News", "news", null);
        TestData.siteOption(dataSource, site, "default_category", "\"" + news.value() + "\"");
        PostSnapshot named = create(editor, PostChanges.builder().title("Named").status("draft"));
        assertThat(slugs(named.id(), "category")).containsExactly("news");

        TermId tag = TestData.term(dataSource, site, "post_tag", "t", "t", null);
        TestData.siteOption(dataSource, site, "default_category", Long.toString(tag.value()));
        PostSnapshot ofAnotherTaxonomy =
                create(editor, PostChanges.builder().title("Tag").status("draft"));
        assertThat(slugs(ofAnotherTaxonomy.id(), "category")).isEmpty();
        assertThat(slugs(ofAnotherTaxonomy.id(), "post_tag")).isEmpty();

        TestData.siteOption(dataSource, site, "default_category", "0");
        PostSnapshot none = create(editor, PostChanges.builder().title("None").status("draft"));
        assertThat(slugs(none.id(), "category")).isEmpty();
        assertThat(slugs(none.id(), "genre")).containsExactly("general");
    }

    @Test
    void categoriesThatDoNotExistLeaveThePostWithoutAnyUntilItsNextSave() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("Missing").status("publish"));
        List<TermId> set = Identities.onSite(
                site,
                Identities.user(editor),
                () -> terms.setPostTerms(post.id(), "category", List.of(TermRef.id(TermId.of(Long.MAX_VALUE))), false));
        assertThat(set).isEmpty();
        assertThat(slugs(post.id(), "category")).isEmpty();
        assertThat(count(TestData.defaultCategory(dataSource, site))).isZero();
        update(post.id(), PostChanges.builder().title("Saved"));
        assertThat(slugs(post.id(), "category")).containsExactly("uncategorized");
        assertThat(count(TestData.defaultCategory(dataSource, site))).isEqualTo(1);
    }

    @Test
    void statusChangesRecountTheTermsOfEveryTaxonomyOfThePost() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("All").status("publish"));
        TermId tag = TestData.term(dataSource, site, "post_tag", "all", "all", null);
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> terms.setPostTerms(post.id(), "post_tag", List.of(TermRef.id(tag)), false));
        List<TermId> all =
                List.of(tag, TestData.defaultCategory(dataSource, site), defaultTermOf("genre"), defaultTermOf("mood"));
        assertThat(all).allSatisfy(term -> assertThat(count(term)).isEqualTo(1));
        update(post.id(), PostChanges.builder().status("pending"));
        assertThat(all).allSatisfy(term -> assertThat(count(term)).isZero());
        update(post.id(), PostChanges.builder().status("publish"));
        assertThat(all).allSatisfy(term -> assertThat(count(term)).isEqualTo(1));
    }

    @Test
    void deletingRemovesEveryAssignmentOfOnlyThatPostAndRecountsEveryTerm() throws Exception {
        PostSnapshot doomed =
                create(editor, PostChanges.builder().title("Doomed").status("publish"));
        PostSnapshot kept = create(editor, PostChanges.builder().title("Kept").status("publish"));
        TermId tag = TestData.term(dataSource, site, "post_tag", "both", "both", null);
        for (PostSnapshot post : List.of(doomed, kept)) {
            Identities.onSite(
                    site,
                    Identities.user(editor),
                    () -> terms.setPostTerms(post.id(), "post_tag", List.of(TermRef.id(tag)), false));
        }
        List<TermId> all =
                List.of(tag, TestData.defaultCategory(dataSource, site), defaultTermOf("genre"), defaultTermOf("mood"));
        assertThat(all).allSatisfy(term -> assertThat(count(term)).isEqualTo(2));
        assertThat(assignmentsOf(doomed.id())).isEqualTo(4);

        Identities.onSite(site, Identities.user(editor), () -> posts.delete(doomed.id(), true));

        assertThat(assignmentsOf(doomed.id())).isZero();
        assertThat(assignmentsOf(kept.id())).isEqualTo(4);
        assertThat(all).allSatisfy(term -> assertThat(count(term)).isEqualTo(1));
        assertThat(slugs(kept.id(), "post_tag")).containsExactly("both");

        // a post without assignments is deleted as well
        PostSnapshot bare = create(editor, PostChanges.builder().title("Bare").status("auto-draft"));
        assertThat(assignmentsOf(bare.id())).isZero();
        Identities.onSite(site, Identities.user(editor), () -> posts.delete(bare.id(), true));
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where id = ?",
                        bare.id().value()))
                .isZero();
        assertThat(all).allSatisfy(term -> assertThat(count(term)).isEqualTo(1));
    }

    @Test
    void deletingAnAttachmentRemovesItsAssignmentsAndRecounts() throws Exception {
        PostSnapshot parent =
                create(editor, PostChanges.builder().title("Parent").status("publish"));
        PostId attached = TestData.post(dataSource, site, "attachment", "inherit", "pic", "Pic");
        Jdbc.update(
                dataSource,
                "update qp_post set parent_id = ? where id = ?",
                parent.id().value(),
                attached.value());
        TermId medium = TestData.term(dataSource, site, "medium", "print", "print", null);
        Identities.onSite(
                site,
                Identities.system(),
                () -> terms.setPostTerms(attached, "medium", List.of(TermRef.id(medium)), false));
        assertThat(count(medium)).isEqualTo(1);
        Identities.onSite(site, Identities.user(editor), () -> posts.delete(attached, true));
        assertThat(count(medium)).isZero();
        assertThat(assignmentsOf(attached)).isZero();
    }
}
