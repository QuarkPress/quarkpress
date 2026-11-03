package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Singleton;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.PostTypes;

class MetaArrayWithoutItemsDbTest {

    @Singleton
    static class ColorsWithoutItems implements MetaKeyProvider {
        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(MetaKeyDefinition.builder("colors")
                    .subtype("post")
                    .type("array")
                    .showInRest()
                    .build());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(MetaArrayWithoutItemsDbTest.class, ColorsWithoutItems.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the meta key colors is shown in REST as an array without schema items"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
