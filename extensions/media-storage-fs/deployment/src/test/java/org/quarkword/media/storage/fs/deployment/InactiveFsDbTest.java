package org.quarkword.media.storage.fs.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.media.runtime.ActiveStorage;
import org.quarkword.media.storage.fs.runtime.FsStartup;
import org.quarkword.media.storage.fs.runtime.UploadsRoute;

/** When another backend is in use, the filesystem backend needs no directory and serves nothing. */
class InactiveFsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            FsApp.app(InactiveFsDbTest.class, OneStorage.class).overrideConfigKey("quarkword.media.storage", "one");

    @Inject
    ActiveStorage active;

    @TestHTTPResource("/")
    URL root;

    @Test
    void inactiveFsNeedsNoDirectory() throws IOException {
        assertThat(active.name()).isEqualTo("one");
        assertThat(active.storage()).isInstanceOf(OneStorage.class);
        // with the route, a request would reach the filesystem backend, which has no directory here
        assertThat(RawHttp.send(root, "GET", "/wp-content/uploads/x").status()).isEqualTo(404);
        assertThat(Arc.container().instance(UploadsRoute.class).isAvailable()).isFalse();
        assertThat(Arc.container().instance(FsStartup.class).isAvailable()).isFalse();
    }
}
