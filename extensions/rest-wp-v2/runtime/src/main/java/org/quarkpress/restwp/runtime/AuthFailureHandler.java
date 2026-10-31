package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quarkpress.restwp.RestError;

/**
 * Answers a REST request whose credentials failed authentication with the REST error {@code rest_invalid_token}
 * (status 401), rendered by {@link RestServer#serveAuthFailure} like every other REST answer, instead of the empty
 * challenge the security layer would send. The refusal comes before any route is matched, so even a public route
 * never answers a client whose token is invalid or expired; no {@code WWW-Authenticate} header is sent.
 *
 * <p>Registered as a failure handler of the router, ahead of the security layer's own handlers; it looks only at
 * requests the {@link RestRequestFilter} re-routed, and only at authentication failures. Everything else goes on.
 */
@ApplicationScoped
public class AuthFailureHandler {

    private static final Logger LOG = Logger.getLogger(AuthFailureHandler.class);

    /** Ahead of Quarkus REST's authentication failure handler and the default error handler. */
    static final int ORDER = -1_000_000;

    /** How deep wrapped failures are unwrapped. */
    private static final int CAUSE_DEPTH = 8;

    static final RestError INVALID_TOKEN =
            RestError.of("rest_invalid_token", "The access token is invalid or has expired.", 401);

    @Inject
    RestServer server;

    @Inject
    CurrentVertxRequest current;

    /** The largest request body the server accepts; a larger form body is not read. */
    @ConfigProperty(name = "quarkus.http.limits.max-body-size", defaultValue = "10240K")
    MemorySize maxBodySize;

    void register(@Observes Router router) {
        router.route().order(ORDER).failureHandler(this::handle);
    }

    void handle(RoutingContext context) {
        if (!(context.get(RestRequestFilter.ENTRY) instanceof RestRequestFilter.Entry entry)
                || !authenticationFailed(context.failure())) {
            context.next();
            return;
        }
        HttpServerResponse response = context.response();
        if (response.ended()) {
            return;
        }
        if (!entry.form()) {
            answer(context, entry, new byte[0]);
            return;
        }
        // a form POST may carry rest_route in its body, which nothing has read yet
        RequestBody read = context.body();
        if (read != null && read.buffer() != null) {
            answer(context, entry, read.buffer().getBytes());
            return;
        }
        HttpServerRequest request = context.request();
        if (request.isEnded()) {
            answer(context, entry, new byte[0]);
            return;
        }
        long limit = maxBodySize.asLongValue();
        Buffer collected = Buffer.buffer();
        boolean[] done = {false};
        request.handler(chunk -> {
            if (done[0]) {
                return;
            }
            if (collected.length() + (long) chunk.length() > limit) {
                // as Quarkus answers a body larger than the server accepts: 413, and the connection closed
                done[0] = true;
                tooLarge(context);
                return;
            }
            collected.appendBuffer(chunk);
        });
        request.exceptionHandler(failure -> {
            if (!done[0]) {
                done[0] = true;
                answer(context, entry, new byte[0]);
            }
        });
        request.endHandler(ended -> {
            if (!done[0]) {
                done[0] = true;
                answer(context, entry, collected.getBytes());
            }
        });
        request.resume();
    }

    private static void tooLarge(RoutingContext context) {
        HttpServerRequest request = context.request();
        HttpServerResponse response = context.response();
        request.handler(null);
        if (response.ended()) {
            return;
        }
        boolean http1 = request.version() != HttpVersion.HTTP_2;
        if (http1) {
            response.headers().set("Connection", "close");
        }
        response.headers().remove("WWW-Authenticate");
        response.setStatusCode(413).end().onComplete(sent -> {
            if (http1) {
                request.connection().close();
            } else {
                response.reset();
            }
        });
    }

    /**
     * Answers the failed request as the entry resource would have answered it up to authentication: a request
     * refused for its {@code rest_route} gets that refusal (WordPress reads it before authenticating); one that turns
     * out not to be a REST request keeps the security layer's own answer; every other gets the token error. The form
     * is parsed, the route decided and the answer rendered on a worker, never on the event loop.
     */
    private void answer(RoutingContext context, RestRequestFilter.Entry entry, byte[] body) {
        HttpServerResponse response = context.response();
        if (response.ended()) {
            return;
        }
        String contentType = context.request().getHeader("Content-Type");
        Map<String, List<String>> headers = RestEntryResource.headers(context);
        context.vertx()
                .<Optional<ServedResponse>>executeBlocking(
                        () -> decideAndServe(context, entry, contentType, headers, body), false)
                .onComplete(result -> {
                    if (response.ended()) {
                        return;
                    }
                    if (result.failed()) {
                        LOG.error("the answer to a failed authentication could not be rendered", result.cause());
                        response.setStatusCode(500).end();
                        return;
                    }
                    if (result.result().isEmpty()) {
                        // not a REST request after all: the security layer answers
                        context.next();
                        return;
                    }
                    response.headers().remove("WWW-Authenticate");
                    write(response, result.result().get());
                });
    }

    /** On a worker: the form, the route decision and the answer; empty when the request is no REST request. */
    private Optional<ServedResponse> decideAndServe(
            RoutingContext context,
            RestRequestFilter.Entry entry,
            String contentType,
            Map<String, List<String>> headers,
            byte[] body) {
        assert !Context.isOnEventLoopThread() : "a failed authentication is answered on a worker";
        ObjectNode form = RestEntryResource.form(entry, contentType, body);
        RestEntryResource.Decision decision = RestEntryResource.route(entry, form);
        if (decision instanceof RestEntryResource.Decision.NotRest) {
            return Optional.empty();
        }
        IncomingRequest request =
                new IncomingRequest(entry.method(), "/", entry.query(), headers, body, form, entry.target());
        return Optional.of(serve(context, request, decision));
    }

    /** Renders the answer in a request context of its own, which sees the request's site decision. */
    private ServedResponse serve(RoutingContext context, IncomingRequest request, RestEntryResource.Decision decision) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activated = !requestContext.isActive();
        if (activated) {
            requestContext.activate();
        }
        try {
            current.setCurrent(context);
            return switch (decision) {
                case RestEntryResource.Decision.Route route ->
                    server.serveAuthFailure(request.withRoute(route.route()), INVALID_TOKEN);
                case RestEntryResource.Decision.Refused refused ->
                    server.refuse(request, refused.code(), refused.message());
                case RestEntryResource.Decision.NotRest notRest ->
                    throw new IllegalStateException("not a REST request");
            };
        } finally {
            if (activated) {
                requestContext.terminate();
            }
        }
    }

    private static boolean authenticationFailed(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < CAUSE_DEPTH; depth++) {
            if (current instanceof AuthenticationFailedException) {
                return true;
            }
            if (!(current instanceof CompletionException)) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void write(HttpServerResponse response, ServedResponse served) {
        response.setStatusCode(served.status());
        for (Map.Entry<String, List<String>> header : served.headers().entrySet()) {
            response.headers().remove(header.getKey());
            for (String value : header.getValue()) {
                response.headers().add(header.getKey(), value);
            }
        }
        response.end(Buffer.buffer(served.body()));
    }
}
