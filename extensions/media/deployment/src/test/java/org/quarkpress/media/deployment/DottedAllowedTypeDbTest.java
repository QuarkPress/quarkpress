package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A configured upload type whose pattern holds a dot fails the start, naming the key: a file's type is looked up by the
 * part of its name after the last dot, so such a pattern could never match.
 */
class DottedAllowedTypeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(DottedAllowedTypeDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.media.allowed-types.\"tar.gz\"", "application/gzip")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.media.allowed-types.\"tar.gz\" cannot be used:"));

    @Test
    void aDottedPatternFailsTheStart() {
        // assertException above checks the failure
    }
}
