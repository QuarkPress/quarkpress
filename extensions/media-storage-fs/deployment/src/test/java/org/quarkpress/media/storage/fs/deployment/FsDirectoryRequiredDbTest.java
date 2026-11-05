package org.quarkpress.media.storage.fs.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The filesystem backend in use without its directory fails the start, naming the setting. */
class FsDirectoryRequiredDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FsApp.app(FsDirectoryRequiredDbTest.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.media.fs.directory is required by quarkpress-media-storage-fs"));

    @Test
    void theStartFails() {
        // assertException above checks the failure
    }
}
