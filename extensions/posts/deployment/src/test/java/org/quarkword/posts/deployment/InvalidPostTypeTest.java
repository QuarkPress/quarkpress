package org.quarkword.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.posts.PostType;

class InvalidPostTypeTest {

    @PostType(key = "a_very_long_post_type_name")
    static class TooLong {}

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.buildOnly(InvalidPostTypeTest.class, TooLong.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: QuarkWord: @PostType on "
                            + "org.quarkword.posts.deployment.InvalidPostTypeTest$TooLong: post type "
                            + "'a_very_long_post_type_name': Post type names must be between 1 and 20 characters in "
                            + "length."));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
