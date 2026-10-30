package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.BuildTimeConditionBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.runtime.RestEntryResource;

/**
 * A build in which Quarkus REST leaves the REST entry resource out (here another extension's build-time condition
 * turns it off) cannot serve REST requests, so it fails with a clear message.
 */
class EntryExcludedTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.buildOnly(EntryExcludedTest.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(context -> {
                        CombinedIndexBuildItem index = context.consume(CombinedIndexBuildItem.class);
                        context.produce(new BuildTimeConditionBuildItem(
                                index.getIndex().getClassByName(RestEntryResource.class), false));
                    })
                    .consumes(CombinedIndexBuildItem.class)
                    .produces(BuildTimeConditionBuildItem.class)
                    .build())
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress-rest-wp-v2: Quarkus REST does not deploy"
                            + " org.quarkpress.restwp.runtime.RestEntryResource, so no REST request can be served;"
                            + " do not exclude it from the build"));

    @Test
    void theApplicationDoesNotBuild() {
        // assertException above checks the failure
    }
}
