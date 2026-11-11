package org.quarkpress.media.storage.s3.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.MediaStorageContract;

/** The storage contract on the S3 backend, in a bucket in moto under a fresh key prefix. */
class S3ContractDbTest extends MediaStorageContract {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.active(S3ContractDbTest.class, MediaStorageContract.class);

    @Inject
    @Identifier("s3")
    MediaStorage storage;

    @Override
    protected MediaStorage storage() {
        return storage;
    }
}
