package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An undeclared quarkpress.media.* key fails the start, naming the key. */
class UnknownMediaKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(UnknownMediaKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.media.unknown", "1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkPress setting quarkpress.media.unknown:"));

    @Test
    void undeclaredMediaKeysFailTheStart() {
        // assertException above checks the failure
    }
}
