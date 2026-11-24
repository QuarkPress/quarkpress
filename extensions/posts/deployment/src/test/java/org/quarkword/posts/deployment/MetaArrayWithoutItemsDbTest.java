package org.quarkword.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Singleton;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.posts.MetaKeyDefinition;
import org.quarkword.posts.MetaKeyProvider;
import org.quarkword.posts.PostTypes;

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
                            + "QuarkWord: the meta key colors is shown in REST as an array without schema items"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
