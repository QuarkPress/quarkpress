package org.quarkpress.it.medias3;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.Callable;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.quarkpress.core.PostId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;

/**
 * Test scaffolding, not a product API: the media library of the site the request is bound to, through the media
 * module's Java service, as the caller. {@code /it/media} acts on the main site, {@code /<site>/it/media}
 * ({@link ItSiteMediaResource}) on a site in a subdirectory. Uploads run outside any transaction, as the service
 * requires.
 */
@Path("/it/media")
@Produces(MediaType.APPLICATION_JSON)
public class ItMediaResource {

    @Inject
    Media media;

    /** Uploads the {@code file} part with its declared type; answers {@code 201} with the attachment, processed. */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public RestResponse<MediaView> upload(@RestForm("file") FileUpload file) {
        if (file == null) {
            throw new BadRequestException("a file part is required");
        }
        Upload.Builder upload = Upload.builder(file.fileName(), file.uploadedFile());
        if (file.contentType() != null && !file.contentType().isBlank()) {
            upload.declaredType(file.contentType());
        }
        Upload built = upload.build();
        MediaSnapshot stored = outsideTransaction(() -> media.upload(built));
        return RestResponse.status(RestResponse.Status.CREATED, MediaView.of(stored));
    }

    /** The attachment; 404 when it is no attachment of the site. */
    @GET
    @Path("/{id}")
    public MediaView find(@PathParam("id") long id) {
        return media.find(PostId.of(id)).map(MediaView::of).orElseThrow(NotFoundException::new);
    }

    /** Deletes the attachment for good, its files once the delete has committed; answers it as it was. */
    @DELETE
    @Path("/{id}")
    public MediaView delete(@PathParam("id") long id) {
        return media.delete(PostId.of(id)).map(MediaView::of).orElseThrow(NotFoundException::new);
    }

    /** Runs {@code work} with any transaction of the caller suspended, rethrowing its own exception. */
    private static <T> T outsideTransaction(Callable<T> work) {
        try {
            return QuarkusTransaction.suspendingExisting().call(work);
        } catch (QuarkusTransactionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw wrapped;
        }
    }
}
