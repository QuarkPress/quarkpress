package org.quarkword.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UnknownPostsKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(UnknownPostsKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkword.posts.empty-trash-day", "0")
            .assertException(
                    failure -> assertThat(Failures.messages(failure)).contains("quarkword.posts.empty-trash-day"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
