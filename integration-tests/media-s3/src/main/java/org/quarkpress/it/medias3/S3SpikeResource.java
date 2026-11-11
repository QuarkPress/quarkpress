package org.quarkpress.it.medias3;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Test-only endpoints that call the AWS SDK directly, one per S3 operation the media storage needs, so the same
 * requests run in JVM mode and in the native executable. The bodies of puts are written to a temporary file first,
 * as the storage receives files.
 */
@Path("/it/s3-spike")
public class S3SpikeResource {

    @Inject
    S3Client s3;

    @Inject
    S3Presigner presigner;

    @ConfigProperty(name = "it.s3.bucket")
    String bucket;

    /** A plain put from a file, with the given content type. */
    @POST
    @Path("put")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> put(
            @QueryParam("key") String key, @QueryParam("contentType") String contentType, byte[] body)
            throws IOException {
        java.nio.file.Path file = Files.createTempFile("s3-spike-", ".bin");
        try {
            Files.write(file, body);
            String etag = s3.putObject(
                            PutObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .contentType(contentType)
                                    .build(),
                            file)
                    .eTag();
            return Map.of("etag", etag);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A put that only creates ({@code If-None-Match: *}); reports S3's refusal instead of failing. */
    @POST
    @Path("put-new")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> putNew(@QueryParam("key") String key, byte[] body) throws IOException {
        java.nio.file.Path file = Files.createTempFile("s3-spike-", ".bin");
        try {
            Files.write(file, body);
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/octet-stream")
                            .ifNoneMatch("*")
                            .build(),
                    file);
            return Map.of("written", true);
        } catch (S3Exception e) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("written", false);
            refusal.put("status", e.statusCode());
            refusal.put(
                    "code",
                    e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode());
            refusal.put("exception", e.getClass().getName());
            return refusal;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** The object's bytes, streamed from the SDK's response; its content type in {@code X-S3-Content-Type}. */
    @GET
    @Path("object")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response get(@QueryParam("key") String key) throws IOException {
        try (ResponseInputStream<GetObjectResponse> in =
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] bytes = in.readAllBytes();
            return Response.ok(bytes)
                    .header("X-S3-Content-Type", in.response().contentType())
                    .build();
        } catch (NoSuchKeyException e) {
            return Response.status(404).build();
        }
    }

    /** Size and content type from a head request; 404 when the key is missing. */
    @GET
    @Path("head")
    @Produces(MediaType.APPLICATION_JSON)
    public Response head(@QueryParam("key") String key) {
        try {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(key));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("size", head.contentLength());
            result.put("contentType", head.contentType());
            return Response.ok(result).build();
        } catch (NoSuchKeyException e) {
            return Response.status(404).build();
        }
    }

    /** One level under the prefix: the keys of its objects and its sub-folders (common prefixes), all pages. */
    @GET
    @Path("list")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> list(@QueryParam("prefix") String prefix) {
        ListObjectsV2Iterable pages = s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .delimiter("/")
                .build());
        List<String> files = new ArrayList<>();
        for (S3Object object : pages.contents()) {
            files.add(object.key());
        }
        List<String> folders = new ArrayList<>();
        for (CommonPrefix folder : pages.commonPrefixes()) {
            folders.add(folder.prefix());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("folders", folders);
        return result;
    }

    @DELETE
    @Path("object")
    public Response delete(@QueryParam("key") String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
        return Response.noContent().build();
    }

    /** A presigned GET URL for the key, valid for the given number of seconds. */
    @GET
    @Path("presign")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> presign(@QueryParam("key") String key, @QueryParam("seconds") long seconds) {
        String url = presigner
                .presignGetObject(p -> p.signatureDuration(Duration.ofSeconds(seconds))
                        .getObjectRequest(g -> g.bucket(bucket).key(key)))
                .url()
                .toString();
        return Map.of("url", url);
    }
}
