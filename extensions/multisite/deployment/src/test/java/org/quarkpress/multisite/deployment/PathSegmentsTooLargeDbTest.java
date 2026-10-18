package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** 17 is above the largest path segment count. */
class PathSegmentsTooLargeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(PathSegmentsTooLargeDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.multisite.path-segments", "17")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.multisite.path-segments is 17; it must be between 1 and 16"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
