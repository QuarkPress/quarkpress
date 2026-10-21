package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.runtime.DuplicateNames;
import org.quarkpress.taxonomy.runtime.TermEntity;
import org.quarkpress.taxonomy.runtime.TermLookups;
import org.quarkpress.taxonomy.runtime.UniqueTermSlugs;

class TermLookupsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.with(TermLookupsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TermLookups lookups;

    @Inject
    Taxonomies taxonomies;

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void duplicateCandidatesShareTheParentAndTheLengthInNameOrder() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId world = TestData.term(dataSource, site, "category", "World", "world", null);
        TermId upper = TestData.term(dataSource, site, "category", "NEWS", "news", null);
        TermId lower = TestData.term(dataSource, site, "category", "news", "news-2", null);
        TestData.term(dataSource, site, "category", "News", "news-world", world);
        TestData.term(dataSource, site, "category", "Newsy", "newsy", null);
        TestData.term(dataSource, site, "post_tag", "News", "news", null);
        List<DuplicateNames.Stored> top = Identities.onSite(
                site, Identities.system(), () -> lookups.sameLength(site, "category", Optional.empty(), "News"));
        assertThat(top.stream().map(DuplicateNames.Stored::id)).containsExactly(upper, lower);
        List<DuplicateNames.Stored> under = Identities.onSite(
                site, Identities.system(), () -> lookups.sameLength(site, "category", Optional.of(world), "News"));
        assertThat(under).extracting(DuplicateNames.Stored::slug).containsExactly("news-world");
    }

    @Test
    void namesAreFoundIgnoringCaseAndAsStoredNames() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId rock = TestData.term(dataSource, site, "post_tag", "Rock &amp; Roll", "rock-roll", null);
        TermId cafe = TestData.term(dataSource, site, "post_tag", "Caf" + cp(0xE9), "cafe", null);
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.byName(site, "post_tag", "rock & roll").map(term -> term.id)))
                .contains(rock.value());
        assertThat(Identities.onSite(site, Identities.system(), () -> lookups.byName(site, "post_tag", "CAFE")))
                .isEmpty();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.byName(site, "post_tag", "caf" + cp(0xE9)).map(term -> term.id)))
                .contains(cafe.value());
    }

    @Test
    void existsLooksUpTheSlugThenTheName() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId news = TestData.term(dataSource, site, "post_tag", "Headlines", "news", null);
        TermId five = TestData.term(dataSource, site, "post_tag", "5", "five", null);
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.exists(site, "post_tag", "News!", OptionalLong.empty())
                                .map(term -> term.id)))
                .contains(news.value());
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.exists(site, "post_tag", " 5 ", OptionalLong.empty())
                                .map(term -> term.id)))
                .contains(five.value());
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> lookups.exists(site, "post_tag", "   ", OptionalLong.empty())))
                .isEmpty();
        TermId world = TestData.term(dataSource, site, "category", "World", "world", null);
        TermId child = TestData.term(dataSource, site, "category", "News", "news", world);
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> lookups.exists(site, "category", "News", OptionalLong.of(0))))
                .isEmpty();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.exists(site, "category", "News", OptionalLong.of(world.value()))
                                .map(term -> term.id)))
                .contains(child.value());
    }

    @Test
    void theUniqueSlugLookupSpansTaxonomiesWhereWordPressDoes() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId self = TestData.term(dataSource, site, "category", "News", "news", null);
        TestData.term(dataSource, site, "post_tag", "Other", "news-2", null);
        TestData.term(dataSource, site, "post_tag", "Named Like A Slug", "named", null);
        UniqueTermSlugs.Lookup lookup =
                Identities.onSite(site, Identities.system(), () -> lookups.uniqueLookup(site, "category"));
        assertThat(Identities.onSite(site, Identities.system(), () -> lookup.takenInTaxonomy("news")))
                .isTrue();
        assertThat(Identities.onSite(site, Identities.system(), () -> lookup.takenInTaxonomy("news-2")))
                .isFalse();
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> lookup.slugTakenAnywhere("news-2", Optional.empty())))
                .isTrue();
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> lookup.slugTakenAnywhere("news", Optional.of(self))))
                .isFalse();
        assertThat(Identities.onSite(site, Identities.system(), () -> lookup.existsAnywhere("named like a slug")))
                .isTrue();
        assertThat(Identities.onSite(
                        TestData.MAIN,
                        Identities.system(),
                        () -> lookups.uniqueLookup(TestData.MAIN, "category")
                                .slugTakenAnywhere("news-2", Optional.empty())))
                .isFalse();
    }

    @Test
    void childrenAndParentsComeFromTheHierarchicalTaxonomyOnly() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId a = TestData.term(dataSource, site, "category", "A", "a", null);
        TermId b = TestData.term(dataSource, site, "category", "B", "b", a);
        TermId c = TestData.term(dataSource, site, "category", "C", "c", a);
        Map<TermId, List<TermId>> children = Identities.onSite(
                site, Identities.system(), () -> lookups.children(site, taxonomies.require("category")));
        assertThat(children).containsExactly(Map.entry(a, List.of(b, c)));
        assertThat(Identities.onSite(site, Identities.system(), () -> lookups.storedParent(site, "category", b)))
                .contains(a);
        assertThat(Identities.onSite(site, Identities.system(), () -> lookups.storedParent(site, "post_tag", b)))
                .isEmpty();
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> lookups.children(site, taxonomies.require("post_tag"))))
                .isEmpty();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.bySlug(site, "category", "b")
                                .map(TermEntity::toSnapshot)
                                .map(term -> term.parent())))
                .contains(Optional.of(a));
    }

    @Test
    void parentsOfOtherTaxonomiesAreNotFound() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId tag = TestData.term(dataSource, site, "post_tag", "Tag", "tag", null);
        TermId child = TestData.term(dataSource, site, "category", "Child", "child", tag);
        assertThat(Identities.onSite(site, Identities.system(), () -> lookups.storedParent(site, "category", child)))
                .isEmpty();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.uniqueLookup(site, "category").parent(tag)))
                .isEmpty();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> lookups.uniqueLookup(site, "post_tag").parent(tag)))
                .isPresent();
    }

    @Test
    void childrenAreListedByAscendingIdNotByName() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId a = TestData.term(dataSource, site, "category", "A", "a", null);
        TermId z = TestData.term(dataSource, site, "category", "Z", "z", a);
        TermId m = TestData.term(dataSource, site, "category", "M", "m", a);
        TermId b = TestData.term(dataSource, site, "category", "B", "b", a);
        Map<TermId, List<TermId>> children = Identities.onSite(
                site, Identities.system(), () -> lookups.children(site, taxonomies.require("category")));
        assertThat(children.get(a)).containsExactly(z, m, b);
    }

    @Test
    void theAnywhereProbeStripsPercentOctetsLikeAStoredName() throws Exception {
        SiteId site = TestData.site(dataSource);
        TestData.term(dataSource, site, "post_tag", "Foobar", "unrelated", null);
        UniqueTermSlugs.Lookup lookup =
                Identities.onSite(site, Identities.system(), () -> lookups.uniqueLookup(site, "category"));
        assertThat(Identities.onSite(site, Identities.system(), () -> lookup.existsAnywhere("foo%41bar")))
                .isTrue();
        assertThat(Identities.onSite(site, Identities.system(), () -> lookup.existsAnywhere("foo%4bar")))
                .isFalse();
        assertThat(Identities.onSite(site, Identities.system(), () -> lookup.existsAnywhere("  ")))
                .isFalse();
    }

    private <T> T on(SiteId site, java.util.concurrent.Callable<T> action) throws Exception {
        return Identities.onSite(site, Identities.system(), action);
    }

    @Test
    void candidatesComeInCodePointOrderOfTheLoweredName() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId accent = TestData.term(dataSource, site, "category", cp(0xE9) + "tre", "accent", null);
        TermId zeta = TestData.term(dataSource, site, "category", "Zeta", "zeta", null);
        TermId upper = TestData.term(dataSource, site, "category", "NEWS", "news", null);
        TermId lower = TestData.term(dataSource, site, "category", "news", "news-2", null);
        TermId abcd = TestData.term(dataSource, site, "category", "abcd", "abcd", null);
        TermId underscore = TestData.term(dataSource, site, "category", "_abc", "underscore", null);
        List<DuplicateNames.Stored> found =
                on(site, () -> lookups.sameLength(site, "category", Optional.empty(), "News"));
        assertThat(found.stream().map(DuplicateNames.Stored::id))
                .containsExactly(underscore, abcd, upper, lower, zeta, accent);
    }

    @Test
    void lengthsCountCharactersAndTiesGoToTheLowestId() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId cafe = TestData.term(dataSource, site, "category", "Caf" + cp(0xE9), "cafe", null);
        TermId smile = TestData.term(dataSource, site, "category", cp(0x1F600) + "ab", "smile", null);
        assertThat(on(site, () -> lookups.sameLength(site, "category", Optional.empty(), "CAF" + cp(0xE9))).stream()
                        .map(DuplicateNames.Stored::id))
                .containsExactly(cafe);
        assertThat(on(site, () -> lookups.sameLength(site, "category", Optional.empty(), cp(0x1F600) + "AB")).stream()
                        .map(DuplicateNames.Stored::id))
                .containsExactly(smile);
        TermId first = TestData.term(dataSource, site, "post_tag", "Qq", "x1", null);
        TestData.term(dataSource, site, "post_tag", "qQ", "x2", null);
        assertThat(on(site, () -> lookups.byName(site, "post_tag", "QQ").map(term -> term.id)))
                .contains(first.value());
        assertThat(on(
                        site,
                        () -> lookups.exists(site, "post_tag", "QQ", OptionalLong.empty())
                                .map(term -> term.id)))
                .contains(first.value());
    }

    @Test
    void theSlugIsTriedBeforeTheName() throws Exception {
        SiteId site = TestData.site(dataSource);
        TestData.term(dataSource, site, "post_tag", "beta", "other", null);
        TermId bySlug = TestData.term(dataSource, site, "post_tag", "Gamma", "beta", null);
        assertThat(on(
                        site,
                        () -> lookups.exists(site, "post_tag", "beta", OptionalLong.empty())
                                .map(term -> term.id)))
                .contains(bySlug.value());
    }

    @Test
    void lookupsNameTheirSiteThemselves() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId parent = TestData.term(dataSource, site, "category", "Only Here", "only-here", null);
        TermId child = TestData.term(dataSource, site, "category", "Only Child", "only-child", parent);
        SiteId main = TestData.MAIN;
        assertThat(on(site, () -> lookups.find(main, parent, "category"))).isEmpty();
        assertThat(on(site, () -> lookups.bySlug(main, "category", "only-here")))
                .isEmpty();
        assertThat(on(site, () -> lookups.byName(main, "category", "Only Here")))
                .isEmpty();
        assertThat(on(site, () -> lookups.exists(main, "category", "only-here", OptionalLong.empty())))
                .isEmpty();
        assertThat(on(site, () -> lookups.exists(main, "category", "Only Here", OptionalLong.empty())))
                .isEmpty();
        assertThat(on(site, () -> lookups.sameLength(main, "category", Optional.empty(), "Only Here")))
                .isEmpty();
        assertThat(on(site, () -> lookups.children(main, taxonomies.require("category"))))
                .isEmpty();
        assertThat(on(site, () -> lookups.storedParent(main, "category", child)))
                .isEmpty();
        assertThat(on(site, () -> lookups.uniqueLookup(main, "category").takenInTaxonomy("only-here")))
                .isFalse();
        assertThat(on(site, () -> lookups.uniqueLookup(main, "category").parent(parent)))
                .isEmpty();
        assertThat(on(site, () -> lookups.uniqueLookup(main, "category").existsAnywhere("only-here")))
                .isFalse();
        assertThat(on(site, () -> lookups.uniqueLookup(main, "category").existsAnywhere("Only Here")))
                .isFalse();
        assertThat(on(
                        site,
                        () -> lookups.uniqueLookup(main, "category").slugTakenAnywhere("only-here", Optional.empty())))
                .isFalse();
    }

    @Test
    void aParentFindsNothingForNegativeIdsAndInFlatTaxonomies() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId top = TestData.term(dataSource, site, "category", "Top", "top", null);
        TermId tag = TestData.term(dataSource, site, "post_tag", "Parent Tag", "parent-tag", null);
        TestData.term(dataSource, site, "post_tag", "Odd Child", "odd-child", tag);
        assertThat(on(site, () -> lookups.exists(site, "category", "Top", OptionalLong.of(0))))
                .isPresent();
        assertThat(on(site, () -> lookups.exists(site, "category", "Top", OptionalLong.of(-1))))
                .isEmpty();
        assertThat(on(site, () -> lookups.exists(site, "post_tag", "Odd Child", OptionalLong.of(tag.value()))))
                .isEmpty();
        assertThat(on(site, () -> lookups.exists(site, "post_tag", "Odd Child", OptionalLong.empty())))
                .isPresent();
        assertThat(top.value()).isPositive();
    }

    @Test
    void probesAreFilteredUnslashedLikeWordPressDoes() throws Exception {
        SiteId site = TestData.site(dataSource);
        TermId plain = TestData.term(dataSource, site, "post_tag", "Ab", "zzz", null);
        TermId backslash = TestData.term(dataSource, site, "post_tag", "A\\B", "backslash", null);
        String nul = String.valueOf((char) 0);
        assertThat(on(site, () -> lookups.byName(site, "post_tag", "a\\b").map(term -> term.id)))
                .contains(plain.value());
        assertThat(on(
                        site,
                        () -> lookups.exists(site, "post_tag", "a\\b", OptionalLong.empty())
                                .map(term -> term.id)))
                .contains(plain.value());
        assertThat(on(
                        site,
                        () -> lookups.byName(site, "post_tag", "a" + nul + "b").map(term -> term.id)))
                .contains(plain.value());
        assertThat(on(
                        site,
                        () -> lookups.exists(site, "post_tag", "a" + nul + "b", OptionalLong.empty())
                                .map(term -> term.id)))
                .contains(plain.value());
        assertThat(on(
                        site,
                        () -> lookups.exists(site, "post_tag", "a\\\\b", OptionalLong.empty())
                                .map(term -> term.id)))
                .contains(backslash.value());
        assertThat(on(site, () -> lookups.uniqueLookup(site, "category").existsAnywhere("a\\b")))
                .isTrue();
    }
}
