package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.deployment.spi.PostStatusBuildItem;

class DuplicatePostStatusTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.buildOnly(DuplicatePostStatusTest.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new PostStatusBuildItem(PostStatusDefinition.builder("draft")
                                    .protectedStatus(true)
                                    .build()));
                        }
                    })
                    .produces(PostStatusBuildItem.class)
                    .build())
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the post status draft is registered more than once"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
