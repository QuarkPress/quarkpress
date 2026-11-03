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

class MetaRegisteredTwiceDbTest {

    /** A provider registering the built-in footnotes key again for posts. */
    @Singleton
    static class FootnotesAgain implements MetaKeyProvider {
        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(MetaKeyDefinition.builder("footnotes")
                    .subtype("post")
                    .single()
                    .build());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(MetaRegisteredTwiceDbTest.class, FootnotesAgain.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the meta key footnotes is registered more than once for post"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
