package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.posts.PostType;

class InvalidPostTypeLabelTest {

    @PostType(
            key = "book",
            labels = {"oops"})
    static class BadLabel {}

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.buildOnly(InvalidPostTypeLabelTest.class, BadLabel.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: QuarkPress: @PostType on "
                            + "org.quarkpress.posts.deployment.InvalidPostTypeLabelTest$BadLabel: "
                            + "a label of @PostType book must read key=value: 'oops'"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
