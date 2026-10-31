package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The error mapping looks the revisions module's exception up by name; a native executable finds it only when the
 * build registers it for reflection. A probe build step reads the registrations and stops the build with what it saw.
 */
class RevisionExceptionReflectionTest {

    private static final String REVISION_EXCEPTION = "org.quarkpress.revisions.RevisionException";

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.buildOnly(RevisionExceptionReflectionTest.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(context -> {
                        List<ReflectiveClassBuildItem> items = context.consumeMulti(ReflectiveClassBuildItem.class);
                        boolean registered = items.stream()
                                .anyMatch(item -> item.getClassNames().contains(REVISION_EXCEPTION));
                        throw new IllegalStateException("probe: " + REVISION_EXCEPTION + " registered=" + registered);
                    })
                    .consumes(ReflectiveClassBuildItem.class)
                    .produces(FeatureBuildItem.class)
                    .build())
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("probe: " + REVISION_EXCEPTION + " registered=true"));

    @Test
    void theRevisionsExceptionIsRegisteredForReflection() {
        // assertException above checks what the probe saw
    }
}
