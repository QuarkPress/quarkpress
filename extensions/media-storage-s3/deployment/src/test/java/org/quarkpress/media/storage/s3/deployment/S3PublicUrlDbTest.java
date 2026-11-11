package org.quarkpress.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.storage.s3.runtime.S3MediaStorage;
import org.quarkpress.media.storage.s3.runtime.S3Settings;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** The URLs of stored files: under a public base URL, presigned, or the bucket's own, against moto. */
class S3PublicUrlDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.active(S3PublicUrlDbTest.class);

    /** A key with a space and a non-ASCII letter, which the presigned and bucket URLs encode. */
    private static final String K = "2026/11/a b/caf\u00e9.txt";

    private static final String BASE = "https://cdn.example.com/media/";

    private static final SiteInfo SITE =
            new SiteInfo(SiteId.of(1), "localhost", "/", "Site", 1, false, false, false, false);

    @Inject
    @Identifier("s3")
    S3MediaStorage active;

    @Inject
    S3Client client;

    @Inject
    S3Presigner presigner;

    @BeforeEach
    void storeTheFile() throws IOException {
        Path directory = Files.createDirectories(S3App.directory("S3PublicUrlDbTest"));
        Path file = Files.writeString(directory.resolve("hello.txt"), "hello");
        active.put(K, file, "text/plain", WriteMode.REPLACE);
    }

    private S3MediaStorage storage(String keyPrefix, Optional<String> baseUrl, Optional<Duration> ttl) {
        return new S3MediaStorage(client, presigner, new S3Settings(S3App.BUCKET, keyPrefix, baseUrl, ttl, true));
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aPublicBaseUrlIsJoinedAsIs() {
        assertThat(storage("t1", Optional.of(BASE), Optional.empty()).publicUrl(SITE, K))
                .isEqualTo("https://cdn.example.com/media/t1/2026/11/a b/caf\u00e9.txt");
        assertThat(storage("", Optional.of(BASE), Optional.empty()).publicUrl(SITE, K))
                .isEqualTo("https://cdn.example.com/media/2026/11/a b/caf\u00e9.txt");
    }

    @Test
    void theBaseUrlWinsOverPresigning() {
        assertThat(storage("t1", Optional.of(BASE), Optional.of(Duration.ofMinutes(10)))
                        .publicUrl(SITE, K))
                .isEqualTo("https://cdn.example.com/media/t1/2026/11/a b/caf\u00e9.txt");
    }

    @Test
    void aPresignedUrlServesTheFile() throws IOException, InterruptedException {
        String url = storage(active.settings().keyPrefix(), Optional.empty(), Optional.of(Duration.ofMinutes(10)))
                .publicUrl(SITE, K);
        assertThat(url).contains("X-Amz-Expires=600");
        assertThat(URI.create(url).getRawPath()).endsWith("/a%20b/caf%C3%A9.txt");
        HttpResponse<String> response = get(url);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hello");
    }

    @Test
    void theBucketUrlServesTheFile() throws IOException, InterruptedException {
        String prefix = active.settings().keyPrefix();
        String url = storage(prefix, Optional.empty(), Optional.empty()).publicUrl(SITE, K);
        assertThat(URI.create(url).getRawPath())
                .endsWith("/" + S3App.BUCKET + "/" + prefix + "/2026/11/a%20b/caf%C3%A9.txt");
        // the bucket URL serves a file only when it can be read without credentials
        assertThat(get(url).statusCode()).isEqualTo(403);
        client.putObjectAcl(
                request -> request.bucket(S3App.BUCKET).key(prefix + "/" + K).acl(ObjectCannedACL.PUBLIC_READ));
        HttpResponse<String> response = get(url);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hello");
    }

    @Test
    void theGuidUrlIsNeverPresigned() {
        S3MediaStorage presigning =
                storage(active.settings().keyPrefix(), Optional.empty(), Optional.of(Duration.ofMinutes(10)));
        String guid = presigning.guidUrl(SITE, K);
        assertThat(guid).doesNotContain("?").doesNotContain("X-Amz-");
        assertThat(guid)
                .isEqualTo(storage(active.settings().keyPrefix(), Optional.empty(), Optional.empty())
                        .publicUrl(SITE, K));
        assertThat(URI.create(guid).getRawPath())
                .endsWith("/" + S3App.BUCKET + "/" + active.settings().keyPrefix() + "/2026/11/a%20b/caf%C3%A9.txt");
        // with a public base URL, the guid is that URL's form, as the served URL is
        assertThat(storage("t1", Optional.of(BASE), Optional.of(Duration.ofMinutes(10)))
                        .guidUrl(SITE, K))
                .isEqualTo("https://cdn.example.com/media/t1/2026/11/a b/caf\u00e9.txt");
    }

    @Test
    void invalidKeysAreRefused() {
        assertThatThrownBy(() -> active.publicUrl(SITE, "a//b")).isInstanceOf(IllegalArgumentException.class);
    }
}
