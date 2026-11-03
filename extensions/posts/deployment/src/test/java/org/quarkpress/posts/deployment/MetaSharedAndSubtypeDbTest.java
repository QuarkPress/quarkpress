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

class MetaSharedAndSubtypeDbTest {

    /** The same key for every type (first bean by class name) and for pages. */
    @Singleton
    static class EveryTypeColor implements MetaKeyProvider {
        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(MetaKeyDefinition.builder("color").single().build());
        }
    }

    @Singleton
    static class PageColor implements MetaKeyProvider {
        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(
                    MetaKeyDefinition.builder("color").subtype("page").single().build());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(
                    MetaSharedAndSubtypeDbTest.class, EveryTypeColor.class, PageColor.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the meta key color is registered for every type and for page"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
