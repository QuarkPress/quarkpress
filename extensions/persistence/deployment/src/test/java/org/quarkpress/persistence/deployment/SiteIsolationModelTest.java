package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.quarkpress.persistence.deployment.SiteIsolationScanner.Finding;
import org.quarkpress.persistence.deployment.isolation.ModelFixtures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class SiteIsolationModelTest {

    private static Index index(Class<?>... types) throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> type : types) {
            String resource = type.getName().replace('.', '/') + ".class";
            try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                indexer.index(in);
            }
        }
        return indexer.complete();
    }

    @Test
    void collectionsAreFoundThroughTargetEntityArraysWildcardsMapsAndGetters() throws IOException {
        List<String> problems = SiteIsolationModel.problems(
                index(SiteScopedEntity.class, ModelFixtures.Book.class, ModelFixtures.Shelf.class));
        String shelf = ModelFixtures.Shelf.class.getName();
        assertThat(problems)
                .containsExactlyInAnyOrder(
                        "collection " + shelf + "#raw of site-scoped " + ModelFixtures.Book.class.getName()
                                + " must carry @Filter(name = \"qp_site\")",
                        "collection " + shelf + "#array of site-scoped " + ModelFixtures.Book.class.getName()
                                + " must carry @Filter(name = \"qp_site\")",
                        "collection " + shelf + "#wildcard of site-scoped " + ModelFixtures.Book.class.getName()
                                + " must carry @Filter(name = \"qp_site\")",
                        "collection " + shelf + "#byName of site-scoped " + ModelFixtures.Book.class.getName()
                                + " must carry @Filter(name = \"qp_site\")",
                        "collection " + shelf + "#getProperty of site-scoped " + ModelFixtures.Book.class.getName()
                                + " must carry @Filter(name = \"qp_site\")");
    }

    @Test
    void anEntitySubclassUsesTheFilterOfItsRootButCachingIsInherited() throws IOException {
        List<String> problems = SiteIsolationModel.problems(index(
                SiteScopedEntity.class,
                ModelFixtures.Root.class,
                ModelFixtures.Child.class,
                ModelFixtures.CachedBase.class,
                ModelFixtures.ViaCachedBase.class));
        assertThat(problems)
                .singleElement()
                .satisfies(p -> assertThat(p)
                        .contains(ModelFixtures.ViaCachedBase.class.getName())
                        .contains("must not be cached"));
    }

    @Test
    void sqlAndHqlInMappingAnnotationsAreChecked() throws IOException {
        List<Finding> findings = SiteIsolationModel.queryAnnotations(
                index(ModelFixtures.Queries.class, ModelFixtures.AllowedNative.class),
                Set.of("qp_option"),
                Set.of("OptionEntity"));
        assertThat(findings)
                .extracting(Finding::problem)
                .containsExactlyInAnyOrder(
                        "native SQL on site-scoped table qp_option in @NamedNativeQuery",
                        "assigns siteId in a query in @NamedQuery",
                        "inserts into site-scoped entity OptionEntity in @NamedQuery",
                        "native SQL on site-scoped table qp_option in @Formula");
        assertThat(findings)
                .allSatisfy(f -> assertThat(f.className()).isEqualTo(ModelFixtures.Queries.class.getName()));
    }
}
