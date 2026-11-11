package org.quarkpress.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The S3 backend in use without its bucket fails the start, naming the setting. */
class S3BucketRequiredDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.app(S3BucketRequiredDbTest.class)
            .overrideConfigKey("quarkus.s3.devservices.enabled", "false")
            .overrideRuntimeConfigKey("quarkus.s3.aws.region", "us-east-1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.media.s3.bucket is required by quarkpress-media-storage-s3"));

    @Test
    void theStartFails() {
        // assertException above checks the failure
    }
}
