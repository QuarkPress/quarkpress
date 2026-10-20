package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.posts.PostType;

class DuplicatePostTypeTest {

    @PostType(key = "post", publicly = true)
    static class AnotherPost {}

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.buildOnly(DuplicatePostTypeTest.class, AnotherPost.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the post type post is registered more than once"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
