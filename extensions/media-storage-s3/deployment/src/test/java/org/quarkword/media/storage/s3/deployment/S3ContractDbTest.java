package org.quarkword.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.media.MediaStorage;
import org.quarkword.media.MediaStorageContract;
import org.quarkword.media.storage.s3.runtime.S3MediaStorage;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** The storage contract on the S3 backend, in a bucket in moto under a fresh key prefix. */
class S3ContractDbTest extends MediaStorageContract {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.active(S3ContractDbTest.class, MediaStorageContract.class);

    @Inject
    @Identifier("s3")
    MediaStorage storage;

    @Inject
    S3Client client;

    @Inject
    S3Presigner presigner;

    /** Held by the raced client for each request, by every writer alike. */
    private final Object oneRequest = new Object();

    /** Every writer's upload waits here, so that all of them are sent before moto answers any. */
    private final CyclicBarrier allUploads = new CyclicBarrier(WRITERS);

    /** The {@code If-None-Match} of each upload the raced client sent. */
    private final List<String> ifNoneMatch = new CopyOnWriteArrayList<>();

    /** The uploads moto refused with 412 Precondition Failed. */
    private final AtomicInteger refused = new AtomicInteger();

    private MediaStorage raced;

    @Override
    protected MediaStorage storage() {
        return storage;
    }

    /**
     * The S3 backend over a client that sends one request at a time, built once per test. S3 decides concurrent
     * {@code If-None-Match: *} uploads of one key atomically; moto (5.2.5) does not: it looks the key up and then
     * stores the object, without a lock, on a threaded server, so two uploads that interleave there can both succeed.
     * The writers still race in {@link S3MediaStorage#put}: each upload waits until every writer has sent its own
     * before it reaches moto whole, so a backend that checked before writing would find the key free for all of them.
     */
    @BeforeEach
    void racedClient() {
        S3Client oneAtATime = (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class}, (proxy, method, arguments) -> {
                    boolean upload =
                            arguments != null && arguments.length > 0 && arguments[0] instanceof PutObjectRequest;
                    if (upload) {
                        ifNoneMatch.add(String.valueOf(((PutObjectRequest) arguments[0]).ifNoneMatch()));
                        allUploads.await(1, TimeUnit.MINUTES);
                    }
                    synchronized (oneRequest) {
                        try {
                            return method.invoke(client, arguments);
                        } catch (InvocationTargetException error) {
                            if (upload
                                    && error.getCause() instanceof AwsServiceException answer
                                    && answer.statusCode() == 412) {
                                refused.incrementAndGet();
                            }
                            throw error.getCause();
                        }
                    }
                });
        raced = new S3MediaStorage(oneAtATime, presigner, ((S3MediaStorage) storage).settings());
    }

    @Override
    protected MediaStorage racedStorage() {
        return raced;
    }

    /** Every writer sent a conditional upload, and moto, not the backend, refused all but one. */
    @Override
    protected void afterRace() {
        assertThat(ifNoneMatch)
                .as("the uploads' If-None-Match")
                .hasSize(WRITERS)
                .containsOnly("*");
        assertThat(refused).as("uploads refused with 412").hasValue(WRITERS - 1);
    }
}
