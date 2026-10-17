package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.elsewhere.NetworkBinder;
import org.quarkpress.elsewhere.NetworkReader;
import org.quarkpress.elsewhere.SiteBinder;
import org.quarkpress.persistence.deployment.SiteIsolationScanner.Finding;
import org.quarkpress.persistence.deployment.isolation.Fixtures;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class SiteIsolationScannerTest {

    private static final Set<String> TABLES = Set.of("qp_option");
    private static final Set<String> ENTITIES = Set.of("OptionEntity");

    private static List<Finding> scan(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            return SiteIsolationScanner.scan(in.readAllBytes(), TABLES, ENTITIES);
        }
    }

    @Test
    void findsNativeQueriesOnSiteTables() throws IOException {
        assertThat(scan(Fixtures.Unsafe.class)).singleElement().satisfies(f -> {
            assertThat(f.methodName()).isEqualTo("count");
            assertThat(f.problem()).contains("native SQL on site-scoped table qp_option");
        });
        assertThat(scan(Fixtures.UpperCase.class)).hasSize(1);
        assertThat(scan(Fixtures.Concatenated.class)).hasSize(1);
        assertThat(scan(Fixtures.Mutation.class)).hasSize(1);
    }

    @Test
    void ignoresNetworkTablesSimilarNamesAnnotatedClassesAndPlainText() throws IOException {
        assertThat(scan(Fixtures.NetworkTable.class)).isEmpty();
        assertThat(scan(Fixtures.SimilarName.class)).isEmpty();
        assertThat(scan(Fixtures.Annotated.class)).isEmpty();
        assertThat(scan(Fixtures.TextOnly.class)).isEmpty();
    }

    @Test
    void findsHqlInsertsAndSiteIdAssignments() throws IOException {
        assertThat(scan(Fixtures.HqlInsert.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("inserts into site-scoped entity OptionEntity"));
        assertThat(scan(Fixtures.SiteIdSet.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("assigns siteId"));
        assertThat(scan(Fixtures.PanacheSiteIdUpdate.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("assigns siteId"));
    }

    @Test
    void siteConditionsAreNotAssignments() throws IOException {
        assertThat(scan(Fixtures.GuardedBulkUpdate.class)).isEmpty();
        assertThat(scan(Fixtures.FindBySite.class)).isEmpty();
        assertThat(scan(Fixtures.FindThenUpdateRow.class)).isEmpty();
    }

    @Test
    void findsTheSiteFilterBeingDisabledAndStatelessSessions() throws IOException {
        assertThat(scan(Fixtures.DisableFilter.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("disables the site filter"));
        assertThat(scan(Fixtures.DisableOtherFilter.class)).isEmpty();
        assertThat(scan(Fixtures.OpenStateless.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("opens a StatelessSession"));
    }

    @Test
    void statelessSessionsAreRefusedForTheSnapshotAndTheFilter() throws IOException {
        assertThat(scan(Fixtures.OpenStateless.class))
                .singleElement()
                .satisfies(f ->
                        assertThat(f.problem()).contains("stored-site snapshot").contains("not filtered by site"));
    }

    @Test
    void onlyCoreAndPersistenceMayBind() throws IOException {
        assertThat(scan(SiteBinder.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem())
                        .contains("calls SiteBinding.bind outside QuarkPress core and persistence"));
        assertThat(scan(NetworkBinder.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem())
                        .contains("calls SiteBinding.bind outside QuarkPress core and persistence"));
        assertThat(scan(NetworkReader.class)).isEmpty();
        assertThat(scan(Fixtures.NetworkInside.class)).isEmpty();
    }

    @Test
    void nativeSqlIsJudgedPerClassAndIncludesJdbc() throws IOException {
        for (Class<?> type : List.of(
                Fixtures.Jdbc.class, Fixtures.DoWork.class, Fixtures.HelperMethod.class, Fixtures.SplitLambda.class)) {
            assertThat(scan(type))
                    .as(type.getSimpleName())
                    .singleElement()
                    .satisfies(f -> assertThat(f.problem()).contains("native SQL on site-scoped table qp_option"));
        }
    }

    @Test
    void hqlShapesFoundInReview() throws IOException {
        assertThat(scan(Fixtures.InsertWithoutInto.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("inserts into site-scoped entity OptionEntity"));
        assertThat(scan(Fixtures.SubqueryBeforeSiteId.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("assigns siteId"));
        assertThat(scan(Fixtures.QueryInVariable.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("assigns siteId"));
        assertThat(scan(Fixtures.CriteriaSiteId.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("assigns siteId in a criteria update"));
        assertThat(scan(Fixtures.FindAndUpdate.class)).isEmpty();
    }

    @Test
    void enablingOrReparameterisingTheSiteFilterIsLikeDisablingIt() throws IOException {
        assertThat(scan(Fixtures.EnableFilter.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("changes the site filter through enableFilter"));
        assertThat(scan(Fixtures.GetEnabledFilter.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("changes the site filter through getEnabledFilter"));
        assertThat(scan(Fixtures.DisableThroughHelper.class))
                .singleElement()
                .satisfies(f -> assertThat(f.problem()).contains("disables the site filter"));
    }

    @Test
    void everyReferenceToAStatelessSessionIsReported() throws IOException {
        for (Class<?> type : List.of(
                Fixtures.InStatelessTransaction.class,
                Fixtures.StatelessWithOptions.class,
                Fixtures.StatelessLookup.class)) {
            assertThat(scan(type))
                    .as(type.getSimpleName())
                    .singleElement()
                    .satisfies(f -> assertThat(f.problem()).contains("opens a StatelessSession or refers to one"));
        }
    }

    @Test
    void persistenceNeedsItsPackageBoundary() {
        assertThat(SiteIsolationScanner.mayBind("org.quarkpress.persistence.runtime.Scopes"))
                .isTrue();
        assertThat(SiteIsolationScanner.mayBind("org.quarkpress.core.runtime.SiteResolution"))
                .isTrue();
        assertThat(SiteIsolationScanner.mayBind("org.quarkpress.persistencex.Scopes"))
                .isFalse();
        assertThat(SiteIsolationScanner.mayBind("org.quarkpress.content.runtime.Posts"))
                .isFalse();
    }

    @Test
    void tableNamesMatchWholeWordsWithoutCase() {
        assertThat(SiteIsolationScanner.mentionsTable("select * from public.qp_option o", "qp_option"))
                .isTrue();
        assertThat(SiteIsolationScanner.mentionsTable("SELECT * FROM \"QP_OPTION\"", "qp_option"))
                .isTrue();
        assertThat(SiteIsolationScanner.mentionsTable("select * from qp_options", "qp_option"))
                .isFalse();
        assertThat(SiteIsolationScanner.mentionsTable("select * from xqp_option", "qp_option"))
                .isFalse();
        assertThat(SiteIsolationScanner.mentionsTable("", "qp_option")).isFalse();
    }

    @Test
    void insertsAndAssignmentsAreRecognisedInTheirShapes() {
        assertThat(SiteIsolationScanner.insertsInto("INSERT  INTO org.quarkpress.x.OptionEntity (a)", "OptionEntity"))
                .isTrue();
        assertThat(SiteIsolationScanner.insertsInto("insert into OptionEntityArchive (a)", "OptionEntity"))
                .isFalse();
        assertThat(SiteIsolationScanner.insertsInto("insert OptionEntity (a) values (1)", "OptionEntity"))
                .isTrue();
        assertThat(SiteIsolationScanner.assignsSiteId(
                        "update E e set e.a = (select x.a from X x where x.id = 1), e.siteId = 3", false))
                .isTrue();
        assertThat(SiteIsolationScanner.assignsSiteId(
                        "update E e set e.a = (select x.a from X x where x.siteId = 3) where e.id = 1", false))
                .isFalse();
        assertThat(SiteIsolationScanner.assignsSiteId("update E e set e.a = ')' , e.siteId = 3", false))
                .isTrue();
        assertThat(SiteIsolationScanner.assignsSiteId("update E e set e.name = 'a', e.siteId= 3", false))
                .isTrue();
        assertThat(SiteIsolationScanner.assignsSiteId("update E e set e.name = 'a' where e.siteId = 3", false))
                .isFalse();
        assertThat(SiteIsolationScanner.assignsSiteId("from E e where e.siteId == 3", false))
                .isFalse();
        assertThat(SiteIsolationScanner.assignsSiteId("siteId = ?1 and name = ?2", false))
                .isFalse();
        assertThat(SiteIsolationScanner.assignsSiteId("siteId = ?1 where id = ?2", true))
                .isTrue();
    }

    @Test
    void aPanacheUpdateAssignsOnlyAtTheTopLevel() {
        assertThat(SiteIsolationScanner.assignsSiteId("autoload = false where (siteId = ?1 or name = ?2)", true))
                .isFalse();
        assertThat(SiteIsolationScanner.assignsSiteId("autoload = false where ((siteId = ?1))", true))
                .isFalse();
        assertThat(SiteIsolationScanner.assignsSiteId("  siteId = ?1 where id = ?2", true))
                .isTrue();
        assertThat(SiteIsolationScanner.assignsSiteId("autoload = (select 1), siteId = ?1", true))
                .isTrue();
    }

    @Test
    void aMatchMeansTheTextContainsTheTable() {
        Properties.check("SiteIsolationScannerTest.containment", 3000, Gens.strings("qp_otin \"`.$Q", 0, 40), text -> {
            if (SiteIsolationScanner.mentionsTable(text, "qp_option")) {
                assertThat(text.toLowerCase(Locale.ROOT)).contains("qp_option");
            }
        });
    }

    @Test
    void theTableBetweenSpacesIsAlwaysFound() {
        Properties.check(
                "SiteIsolationScannerTest.separated",
                3000,
                Gens.lists(Gens.strings("qp_otin \"`.$Q", 0, 20), 2, 2),
                parts -> assertThat(SiteIsolationScanner.mentionsTable(
                                parts.get(0) + " QP_Option " + parts.get(1), "qp_option"))
                        .isTrue());
    }

    @Test
    void anAssignmentNeedsAnEqualsSignAfterSiteId() {
        Properties.check(
                "SiteIsolationScannerTest.assignment", 3000, Gens.strings("siteId setwhr=.:? ", 0, 40), text -> {
                    if (SiteIsolationScanner.assignsSiteId(text, true)) {
                        assertThat(text.toLowerCase(Locale.ROOT)).contains("siteid");
                        assertThat(text).contains("=");
                    }
                });
    }
}
