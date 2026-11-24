package org.quarkword.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An undeclared quarkword.media.* key fails the start, naming the key. */
class UnknownMediaKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(UnknownMediaKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkword.media.unknown", "1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkWord setting quarkword.media.unknown:"));

    @Test
    void undeclaredMediaKeysFailTheStart() {
        // assertException above checks the failure
    }
}
