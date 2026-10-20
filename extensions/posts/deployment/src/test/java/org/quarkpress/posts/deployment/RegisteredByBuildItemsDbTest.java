package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.deployment.spi.PostStatusBuildItem;
import org.quarkpress.core.deployment.spi.PostTypeBuildItem;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.PostVisibility;

class RegisteredByBuildItemsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(RegisteredByBuildItemsDbTest.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new PostTypeBuildItem(PostTypeDefinition.builder("Recipe")
                                    .publicly(true)
                                    .hierarchical(true)
                                    .build()));
                            context.produce(new PostStatusBuildItem(PostStatusDefinition.builder("archived")
                                    .publicly(true)
                                    .build()));
                        }
                    })
                    .produces(PostTypeBuildItem.class)
                    .produces(PostStatusBuildItem.class)
                    .build());

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Test
    void buildItemsRegisterTypesAndStatuses() {
        assertThat(types.find("recipe")).isPresent();
        assertThat(types.hierarchical("recipe")).isTrue();
        assertThat(types.searchable()).contains("recipe");
        assertThat(statuses.find("archived")).isPresent();
        assertThat(statuses.publicStatuses()).containsExactly("publish", "archived");
        assertThat(statuses.visibility("archived")).isEqualTo(PostVisibility.PUBLIC);
    }
}
