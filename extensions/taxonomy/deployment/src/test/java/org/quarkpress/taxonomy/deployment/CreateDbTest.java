package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

class CreateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(CreateDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private SiteId site;
    private UserId editor;

    private void newSite() {
        site = TestData.site(dataSource);
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]");
    }

    private TermSnapshot create(String taxonomy, TermChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> terms.create(taxonomy, changes.build()));
    }

    private TermException refused(String taxonomy, TermChanges.Builder changes) {
        try {
            create(taxonomy, changes);
        } catch (TermException refused) {
            return refused;
        } catch (Exception other) {
            throw new AssertionError("expected a TermException, got " + other, other);
        }
        throw new AssertionError("the create was not refused");
    }

    @Test
    void aTagIsStoredWithWordPresssDefaults() throws Exception {
        newSite();
        TermSnapshot tag = create("post_tag", TermChanges.builder().name("  Rock & Roll  "));
        assertThat(tag.taxonomy()).isEqualTo("post_tag");
        assertThat(tag.name()).isEqualTo("Rock &amp; Roll");
        assertThat(tag.slug()).isEqualTo("rock-roll");
        assertThat(tag.description()).isEmpty();
        assertThat(tag.parent()).isEmpty();
        assertThat(tag.count()).isZero();
        assertThat(tag.site()).isEqualTo(site);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(name, '|', slug, '|', count) from qp_term where id = ?",
                        tag.id().value()))
                .containsExactly("Rock &amp; Roll|rock-roll|0");
    }

    @Test
    void namesEditorsTypeAreStoredAsWordPressStoresThem() throws Exception {
        newSite();
        String[][] table = {
            {cp(0x1F389) + " Party", cp(0x1F389) + " Party", "%f0%9f%8e%89-party"},
            {"Caf" + cp(0xE9) + " cr" + cp(0xE8) + "me", "Caf" + cp(0xE9) + " cr" + cp(0xE8) + "me", "cafe-creme"},
            {cp(0x65E5, 0x672C), cp(0x65E5, 0x672C), "%e6%97%a5%e6%9c%ac"},
            {
                cp(0x41F, 0x440, 0x438, 0x432, 0x435, 0x442),
                cp(0x41F, 0x440, 0x438, 0x432, 0x435, 0x442),
                "%d0%bf%d1%80%d0%b8%d0%b2%d0%b5%d1%82"
            },
            {"a < b", "a &lt; b", "a-b"},
            {"Tom's \"best\"", "Tom's \"best\"", "toms-best"}
        };
        for (String[] row : table) {
            TermSnapshot tag = create("post_tag", TermChanges.builder().name(row[0]));
            assertThat(tag.name()).as(row[0]).isEqualTo(row[1]);
            assertThat(tag.slug()).as(row[0]).isEqualTo(row[2]);
            assertThat(Jdbc.strings(
                            dataSource,
                            "select name from qp_term where id = ?",
                            tag.id().value()))
                    .containsExactly(row[1]);
        }
        String longest = cp(0xE9).repeat(200);
        TermSnapshot fits = create("post_tag", TermChanges.builder().name(longest));
        assertThat(fits.name()).isEqualTo(longest);
        // remove_accents turns each into "e", and ASCII is not cut
        assertThat(fits.slug()).isEqualTo("e".repeat(200));
        assertThat(refused("post_tag", TermChanges.builder().name(cp(0xE9).repeat(201) + "x"))
                        .code())
                .isEqualTo("db_insert_error");
        assertThat(refused("post_tag", TermChanges.builder().name("<script>alert(1)</script>"))
                        .code())
                .isEqualTo("invalid_term_name");
        assertThat(refused("post_tag", TermChanges.builder().name(" \t ")).code())
                .isEqualTo("empty_term_name");
        assertThat(refused("post_tag", TermChanges.builder()).code()).isEqualTo("empty_term_name");
        // kses removes control characters, NUL included, as it does in WordPress
        assertThat(create("post_tag", TermChanges.builder().name("nul" + (char) 0 + "name"))
                        .name())
                .isEqualTo("nulname");
    }

    @Test
    void duplicateNamesAreRefusedAsWordPressRefusesThem() throws Exception {
        newSite();
        TermSnapshot news = create("post_tag", TermChanges.builder().name("News"));
        TermException duplicate = refused("post_tag", TermChanges.builder().name("news"));
        assertThat(duplicate.code()).isEqualTo("term_exists");
        assertThat(duplicate.existing()).contains(news.id());
        assertThat(duplicate).hasMessage("A term with the name provided already exists in this taxonomy.");
        TermSnapshot alt = create("post_tag", TermChanges.builder().name("News").slug("news-alt"));
        assertThat(alt.slug()).isEqualTo("news-alt");
        TermSnapshot topNews = create("category", TermChanges.builder().name("News"));
        TermException sibling = refused("category", TermChanges.builder().name("news"));
        assertThat(sibling.existing()).contains(topNews.id());
        assertThat(sibling).hasMessage("A term with the name provided already exists with this parent.");
        TermSnapshot world = create("category", TermChanges.builder().name("World"));
        TermSnapshot worldNews = create(
                "category", TermChanges.builder().name("News").parent(world.id().value()));
        assertThat(worldNews.slug()).isEqualTo("news-world");
        TermSnapshot headlines =
                create("category", TermChanges.builder().name("Daily").slug("headlines"));
        TermSnapshot daily = create("category", TermChanges.builder().name("daily"));
        assertThat(daily.slug()).isEqualTo("daily");
        assertThat(headlines.slug()).isEqualTo("headlines");
    }

    @Test
    void slugsAreMadeUniqueAcrossTheSitesTaxonomies() throws Exception {
        newSite();
        create("post_tag", TermChanges.builder().name("News"));
        assertThat(create("post_tag", TermChanges.builder().name("News!").slug("news"))
                        .slug())
                .isEqualTo("news-2");
        assertThat(create("category", TermChanges.builder().name("News")).slug())
                .isEqualTo("news");
        assertThat(create("category", TermChanges.builder().name("Newsy").slug("news"))
                        .slug())
                .isEqualTo("news-3");
        SiteId other = site;
        newSite();
        assertThat(create("post_tag", TermChanges.builder().name("News")).slug())
                .isEqualTo("news");
        assertThat(other).isNotEqualTo(site);
    }

    @Test
    void aNameWithoutSluggableCharactersGetsItsIdAsSlug() throws Exception {
        newSite();
        TermSnapshot bangs = create("post_tag", TermChanges.builder().name("!!!"));
        assertThat(bangs.slug()).isEqualTo(Long.toString(bangs.id().value()));
        TermSnapshot zero = create("post_tag", TermChanges.builder().name("0"));
        assertThat(zero.slug()).isEqualTo("0");
    }

    @Test
    void parentsFollowTheRestRules() throws Exception {
        newSite();
        TermSnapshot tag = create("post_tag", TermChanges.builder().name("Tag"));
        TermSnapshot world = create("category", TermChanges.builder().name("World"));
        TermException flat = refused(
                "post_tag", TermChanges.builder().name("Child").parent(tag.id().value()));
        assertThat(flat.code()).isEqualTo("rest_taxonomy_not_hierarchical");
        assertThat(refused(
                                "category",
                                TermChanges.builder()
                                        .name("Child")
                                        .parent(tag.id().value()))
                        .code())
                .isEqualTo("missing_parent");
        assertThat(refused("category", TermChanges.builder().name("Child").parent(999_999_999L))
                        .code())
                .isEqualTo("missing_parent");
        assertThat(create("category", TermChanges.builder().name("Top").parent(0))
                        .parent())
                .isEmpty();
        assertThat(create("category", TermChanges.builder().name("Low").parent(-4))
                        .parent())
                .isEmpty();
        assertThat(create("post_tag", TermChanges.builder().name("Flat").parent(0))
                        .parent())
                .isEmpty();
        assertThat(create(
                                "category",
                                TermChanges.builder()
                                        .name("Child")
                                        .parent(world.id().value()))
                        .parent())
                .contains(world.id());
    }

    @Test
    void aParentOfAnotherSiteIsMissing() throws Exception {
        newSite();
        TermSnapshot elsewhere = create("category", TermChanges.builder().name("Elsewhere"));
        newSite();
        assertThat(refused(
                                "category",
                                TermChanges.builder()
                                        .name("Here")
                                        .parent(elsewhere.id().value()))
                        .code())
                .isEqualTo("missing_parent");
    }

    @Test
    void anUnknownTaxonomyIsRefused() throws Exception {
        newSite();
        TermException refused = refused("bogus", TermChanges.builder().name("News"));
        assertThat(refused.code()).isEqualTo("invalid_taxonomy");
        assertThat(refused.detail()).isEqualTo("bogus");
    }

    @Test
    void aRefusalBeforeTheWriteLeavesTheTransactionUsable() throws Exception {
        newSite();
        List<String> seen = new ArrayList<>();
        TermSnapshot created = Identities.onSite(site, Identities.user(editor), () -> {
            try {
                terms.create("post_tag", TermChanges.builder().name(" ").build());
            } catch (TermException refused) {
                seen.add(refused.code());
            }
            return terms.create("post_tag", TermChanges.builder().name("Kept").build());
        });
        assertThat(seen).containsExactly("empty_term_name");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where id = ?",
                        created.id().value()))
                .isEqualTo(1);
        assertThat(created.slug()).isEqualTo("kept");
    }

    @Test
    void aGivenSlugThatSanitizesToNothingOrZeroFallsBackToTheNamesSlug() throws Exception {
        newSite();
        assertThat(create("post_tag", TermChanges.builder().name("Zero Slug").slug("0"))
                        .slug())
                .isEqualTo("zero-slug");
        assertThat(create("post_tag", TermChanges.builder().name("Bang Slug").slug("!!!"))
                        .slug())
                .isEqualTo("bang-slug");
    }

    @Test
    void aLongCjkNameIsKeptAndItsSlugIsCutAt200Bytes() throws Exception {
        newSite();
        String name = cp(0x65E5).repeat(200);
        TermSnapshot term = create("post_tag", TermChanges.builder().name(name));
        assertThat(term.name()).isEqualTo(name);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select name from qp_term where id = ?",
                        term.id().value()))
                .containsExactly(name);
        // sanitize_title percent-encodes and stops before the encoded text passes 200 bytes: 22 characters
        assertThat(term.slug()).isEqualTo("%e6%97%a5".repeat(22));
    }

    @Test
    void aSlugMadeFromTheNameIsMadeFromTheUnslashedName() throws Exception {
        newSite();
        // unslashed, the backslashes leave room for the encoded characters within sanitize_title's 200 bytes;
        // slashed, they would fill it and leave no slug
        String name = "\\".repeat(150) + cp(0x65E5, 0x672C);
        TermSnapshot term = create("post_tag", TermChanges.builder().name(name));
        assertThat(term.name()).isEqualTo(name);
        assertThat(term.slug()).isEqualTo("%e6%97%a5%e6%9c%ac");
    }

    @Test
    void parentRefusalsComeBeforeTheWriteAndLeaveTheTransactionUsable() throws Exception {
        newSite();
        TermSnapshot tag = create("post_tag", TermChanges.builder().name("Tag"));
        List<String> seen = new ArrayList<>();
        TermSnapshot created = Identities.onSite(site, Identities.user(editor), () -> {
            try {
                terms.create(
                        "post_tag",
                        TermChanges.builder()
                                .name("Flat")
                                .parent(tag.id().value())
                                .build());
            } catch (TermException refused) {
                seen.add(refused.code());
            }
            try {
                terms.create(
                        "category",
                        TermChanges.builder()
                                .name("Lost")
                                .parent(tag.id().value())
                                .build());
            } catch (TermException refused) {
                seen.add(refused.code());
            }
            try {
                terms.create(
                        "category",
                        TermChanges.builder().name(" ").parent(tag.id().value()).build());
            } catch (TermException refused) {
                seen.add(refused.code());
            }
            return terms.create("category", TermChanges.builder().name("Kept").build());
        });
        assertThat(seen).containsExactly("rest_taxonomy_not_hierarchical", "missing_parent", "missing_parent");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where id = ?",
                        created.id().value()))
                .isEqualTo(1);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and name in ('Flat', 'Lost')",
                        site.value()))
                .isZero();
    }

    @Test
    void descriptionsAreFilteredWithKsesAsWordPressStoresThem() throws Exception {
        newSite();
        String[][] table = {
            {"<script>x</script><b>ok</b> & more", "x<b>ok</b> &amp; more"},
            {
                "<a href=\"javascript:alert(1)\" title=\"t\" onclick=\"x()\">link</a> <em>e</em> <div>d</div>",
                "<a href=\"alert(1)\" title=\"t\">link</a> <em>e</em> d"
            },
            {"  keep\n spaces  ", "  keep\n spaces  "},
            {"a" + (char) 0 + "b", "ab"}
        };
        int n = 0;
        for (String[] row : table) {
            TermSnapshot tag = create(
                    "post_tag", TermChanges.builder().name("Described " + n++).description(row[0]));
            assertThat(tag.description()).as(row[0]).isEqualTo(row[1]);
            assertThat(Jdbc.strings(
                            dataSource,
                            "select description from qp_term where id = ?",
                            tag.id().value()))
                    .as(row[0])
                    .containsExactly(row[1]);
        }
    }
}
