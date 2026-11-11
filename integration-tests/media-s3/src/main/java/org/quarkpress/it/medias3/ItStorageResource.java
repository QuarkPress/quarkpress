package org.quarkpress.it.medias3;

import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.SiteContext;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.storage.s3.runtime.S3MediaStorage;
import org.quarkpress.media.storage.s3.runtime.S3Settings;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Test scaffolding, not an API: the S3 storage's own operations by storage key ({@code key} or {@code folder} query
 * parameter), so that a conditional create on a taken key, a listing and the bucket-URL mode run in the native
 * executable too.
 */
@Path("/it/storage")
@Produces(MediaType.APPLICATION_JSON)
public class ItStorageResource {

    @Inject
    @Identifier("s3")
    S3MediaStorage storage;

    @Inject
    S3Client client;

    @Inject
    S3Presigner presigner;

    @Inject
    SiteContext site;

    /** Stores the body under the key if the key is free: 201, or 409 when it holds a file. */
    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response create(@QueryParam("key") String key, byte[] body) throws IOException {
        java.nio.file.Path file = storage.temporaryFile();
        try {
            Files.write(file, body);
            storage.put(key, file, "application/octet-stream", WriteMode.CREATE_NEW);
            return Response.status(Response.Status.CREATED).build();
        } catch (KeyTakenException taken) {
            return Response.status(Response.Status.CONFLICT).build();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** The names one level below the folder. */
    @GET
    @Path("/list")
    public List<String> list(@QueryParam("folder") String folder) {
        return storage.list(folder == null ? "" : folder);
    }

    /** Deletes the file of the key: {@code deleted}, whether it was there. */
    @DELETE
    public Map<String, Object> delete(@QueryParam("key") String key) {
        return Map.of("deleted", storage.delete(key));
    }

    /**
     * The key's URL in the bucket-URL mode (no public base URL, no presigning): a storage with the active one's bucket
     * and key prefix and neither setting.
     */
    @GET
    @Path("/bucket-url")
    public Map<String, Object> bucketUrl(@QueryParam("key") String key) {
        S3Settings settings = storage.settings();
        S3MediaStorage bucketUrls = new S3MediaStorage(
                client,
                presigner,
                new S3Settings(settings.bucket(), settings.keyPrefix(), Optional.empty(), Optional.empty(), true));
        return Map.of("url", bucketUrls.publicUrl(site.site(), key));
    }

    /** Makes the key's object readable without credentials (a {@code public-read} ACL), as a public bucket's are. */
    @POST
    @Path("/public-read")
    public Response publicRead(@QueryParam("key") String key) {
        S3Settings settings = storage.settings();
        String objectKey = settings.keyPrefix().isEmpty() ? key : settings.keyPrefix() + "/" + key;
        client.putObjectAcl(
                request -> request.bucket(settings.bucket()).key(objectKey).acl(ObjectCannedACL.PUBLIC_READ));
        return Response.noContent().build();
    }
}
