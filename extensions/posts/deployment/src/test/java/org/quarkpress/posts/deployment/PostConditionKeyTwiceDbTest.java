package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostConditionProvider;
import org.quarkpress.posts.TermCondition;

/** Two beans providing the same post condition key: the application does not start. */
class PostConditionKeyTwiceDbTest {

    @ApplicationScoped
    static class First implements PostConditionProvider {
        @Override
        public String key() {
            return "test.same";
        }

        @Override
        public TermCondition condition(SiteId site, JsonNode argument, String postId, String paramPrefix) {
            return new TermCondition.None();
        }
    }

    @ApplicationScoped
    static class Second extends First {}

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostConditionKeyTwiceDbTest.class, First.class, Second.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the post condition key test.same is provided more than once"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
