package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.persistence.OptimisticLockException;
import org.jboss.logging.Logger;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.posts.PostException;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.taxonomy.TermException;

/**
 * Turns an exception that escaped a permission check or a handler into the error object WordPress would answer: the
 * safety net under the controllers, which refuse with WordPress's own codes before the services throw. An exception
 * nothing here knows is logged and answered as WordPress answers a fatal error.
 */
public final class ErrorMapping {

    private static final Logger LOG = Logger.getLogger(ErrorMapping.class);

    private static final String REVISION_EXCEPTION = "org.quarkpress.revisions.RevisionException";

    /** Whether the (optional) revisions module is present; only then is its exception type looked at. */
    private static final boolean REVISIONS = present(REVISION_EXCEPTION);

    /** How deep the cause chain is searched for an optimistic lock failure. */
    private static final int CAUSE_DEPTH = 16;

    private static final String HEX = "0123456789abcdef";

    private ErrorMapping() {}

    public static RestError toError(Throwable failure, RestRequest request) {
        return request == null ? toError(failure, "", "") : toError(failure, request.method(), request.path());
    }

    /**
     * The error for {@code failure} in a request not (yet) matched to a route; {@code method} and {@code route} only
     * describe the request in the log.
     */
    public static RestError toError(Throwable failure, String method, String route) {
        if (failure instanceof RestException refused) {
            return refused.error();
        }
        if (failure instanceof AuthenticationFailedException) {
            // only with lazy authentication: proactive authentication fails before the REST server runs
            return AuthFailureHandler.INVALID_TOKEN;
        }
        if (failure instanceof UnauthorizedException) {
            return forbidden(401);
        }
        if (failure instanceof ForbiddenException) {
            return forbidden(403);
        }
        if (failure instanceof CapabilityDeniedException denied) {
            return forbidden(denied.anonymous() ? 401 : 403);
        }
        if (failure instanceof SiteFeatureDisabledException) {
            return RestError.of("rest_no_route", "No route was found matching the URL and request method.", 404);
        }
        if (failure instanceof TermException term) {
            return termError(term);
        }
        if (failure instanceof PostException post) {
            return RestError.noStatus(post.code(), post.getMessage());
        }
        if (REVISIONS && Revisions.is(failure)) {
            return Revisions.error(failure);
        }
        if (conflict(failure)) {
            return RestError.of("quarkpress_conflict", "The item was changed by another request.", 409);
        }
        LOG.error(logLine(method, route), failure);
        return internalError();
    }

    /** WordPress's answer to a fatal error. */
    static RestError internalError() {
        return RestError.of("internal_server_error", "There has been a critical error on this website.", 500);
    }

    /** The log line of an unexpected failure, the request's own text with its control characters escaped. */
    static String logLine(String method, String route) {
        return "REST request " + printable(method) + " " + printable(route) + " failed";
    }

    /**
     * {@code text} with every control character (C0, DEL, C1) and the Unicode line and paragraph separators written as
     * a backslash, {@code x} and two hex digits, or a backslash, {@code u} and four hex digits, and the backslash
     * doubled, so request text cannot forge log lines.
     */
    static String printable(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                out.append("\\x").append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xF));
            } else if (c == '\u2028' || c == '\u2029') {
                out.append("\\u").append(Integer.toHexString(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static RestError forbidden(int status) {
        return RestError.of("rest_forbidden", "Sorry, you are not allowed to do that.", status);
    }

    /**
     * A term error: a duplicate name carries the existing term's id, then the status and id together, as WordPress's
     * terms controller leaves it; a parent in a flat taxonomy is a 400; every other code has no status.
     */
    private static RestError termError(TermException term) {
        RestError error = RestError.noStatus(term.code(), term.getMessage());
        if (term.code().equals("term_exists") && term.existing().isPresent()) {
            long id = term.existing().get().value();
            ObjectNode data = RestJson.NODES.objectNode();
            data.put("status", 400);
            data.put("term_id", id);
            return error.withData(RestJson.NODES.numberNode(id))
                    .withData(RestJson.NODES.numberNode(id))
                    .withData(data);
        }
        if (term.code().equals("term_exists") || term.code().equals("rest_taxonomy_not_hierarchical")) {
            return RestError.of(term.code(), term.getMessage(), 400);
        }
        return error;
    }

    private static boolean conflict(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < CAUSE_DEPTH; depth++) {
            if (current instanceof OptimisticLockException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, ErrorMapping.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /** The revisions module's exception; loaded only when that module is present. */
    private static final class Revisions {

        private Revisions() {}

        static boolean is(Throwable failure) {
            return failure instanceof org.quarkpress.revisions.RevisionException;
        }

        static RestError error(Throwable failure) {
            org.quarkpress.revisions.RevisionException revision = (org.quarkpress.revisions.RevisionException) failure;
            return RestError.noStatus(revision.code(), revision.getMessage());
        }
    }
}
