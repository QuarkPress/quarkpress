package org.quarkpress.media.storage.fs.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An uploads path of the site root would take every request: the start fails, naming the setting. */
class FsRootPathDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FsApp.multisite(FsApp.directory("root-path"), FsRootPathDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.media.fs.path", "/")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.media.fs.path must name a path below the site's root, such as"
                            + " /wp-content/uploads: /"));

    @Test
    void theStartFails() {
        // assertException above checks the failure
    }
}
