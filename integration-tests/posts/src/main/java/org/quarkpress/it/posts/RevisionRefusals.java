package org.quarkpress.it.posts;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.quarkpress.posts.PostException;
import org.quarkpress.revisions.RevisionException;

/** The refusals of the revision endpoints as HTTP answers (test application only). */
final class RevisionRefusals {

    private RevisionRefusals() {}

    static Response of(RevisionException refused) {
        return answer(400, refused.code());
    }

    static Response of(PostException refused) {
        return answer("invalid_post".equals(refused.code()) ? 404 : 400, refused.code());
    }

    private static Response answer(int status, String code) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", code))
                .build();
    }
}
