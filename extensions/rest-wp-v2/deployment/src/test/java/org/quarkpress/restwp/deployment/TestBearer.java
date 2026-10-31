package org.quarkpress.restwp.deployment;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import org.quarkpress.core.UserId;
import org.quarkpress.security.QuarkPressPrincipal;

/**
 * Bearer tokens for tests: {@code Authorization: Bearer test-<userId>} authenticates as that profile (the identity
 * the security module's augmentor would leave, without its permission checker); any other bearer value, such as
 * {@code Bearer invalid}, fails authentication; no bearer header leaves the request anonymous.
 *
 * <p>A request without an {@code Authorization} header but with the cookie {@code qp-test-session=test-<userId>} is
 * authenticated as that profile too, as a session cookie of an OIDC web application would authenticate it.
 */
@ApplicationScoped
public class TestBearer implements HttpAuthenticationMechanism, IdentityProvider<TestBearer.Request> {

    private static final String SCHEME = "Bearer ";
    private static final String PREFIX = "test-";

    /** The cookie that authenticates a request without an {@code Authorization} header. */
    public static final String SESSION = "qp-test-session";

    /** The bearer value of a request. */
    public static final class Request extends BaseAuthenticationRequest {

        private final String token;

        Request(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /** The identity of {@code user}, as {@code Bearer test-<id>} makes it. */
    public static SecurityIdentity identity(UserId user) {
        String subject = "user-" + user.value();
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(subject))
                .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(user, subject))
                .build();
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        String header = context.request().getHeader("Authorization");
        if (header == null) {
            Cookie session = context.request().getCookie(SESSION);
            return session == null
                    ? Uni.createFrom().nullItem()
                    : identityProviderManager.authenticate(new Request(session.getValue()));
        }
        if (!header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return Uni.createFrom().nullItem();
        }
        return identityProviderManager.authenticate(new Request(header.substring(SCHEME.length())));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer"));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(Request.class);
    }

    @Override
    public int getPriority() {
        // ahead of the (disabled) OIDC mechanism
        return DEFAULT_PRIORITY + 1000;
    }

    @Override
    public Class<Request> getRequestType() {
        return Request.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(Request request, AuthenticationRequestContext context) {
        String token = request.token();
        if (token.startsWith(PREFIX)) {
            try {
                long id = Long.parseLong(token.substring(PREFIX.length()));
                if (id > 0) {
                    return Uni.createFrom().item(identity(UserId.of(id)));
                }
            } catch (NumberFormatException notAnId) {
                // falls through to the failure
            }
        }
        return Uni.createFrom().failure(new AuthenticationFailedException("invalid test token"));
    }
}
