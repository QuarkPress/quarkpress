package org.quarkpress.it.media;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.quarkpress.media.MediaException;
import org.quarkpress.posts.PostException;

/**
 * The test resources' answers to refusals, as {@code {code, message}} (no {@code message} when the exception has none): a media refusal with its WordPress code, 400 for
 * the REST preamble's codes ({@code rest_...}) and 500 for the upload's own ({@code upload_error}, the status WordPress
 * gives it); a post refusal 400; a capability refusal 403, or 401 without a user. A malformed
 * request (a {@code post} field that is no post id) is answered 400 by the resource itself. Test scaffolding, not an API.
 */
public class ItRefusals {

    @ServerExceptionMapper
    public Response media(MediaException refused) {
        int status = refused.code().startsWith("rest_") ? 400 : 500;
        return answer(status, refused.code(), refused.getMessage());
    }

    @ServerExceptionMapper
    public Response post(PostException refused) {
        return answer(400, refused.code(), refused.getMessage());
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException refused) {
        return answer(403, "forbidden", refused.getMessage());
    }

    @ServerExceptionMapper
    public Response unauthorized(UnauthorizedException refused) {
        return answer(401, "unauthorized", refused.getMessage());
    }

    /** The answer; without a message when the exception has none. */
    private static Response answer(int status, String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        if (message != null) {
            body.put("message", message);
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
