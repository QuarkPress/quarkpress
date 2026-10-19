package org.quarkpress.security.runtime;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonString;
import java.util.Optional;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.quarkpress.security.ProfileClaims;

/** Reads the claims provisioning needs from an authenticated identity. */
public final class IdentityClaims {

    private IdentityClaims() {}

    /**
     * From a token principal that carries {@code sub}: that subject, {@code preferred_username}, {@code given_name} and
     * {@code family_name}. Empty for a token without {@code sub} and for any other principal: the user key is the
     * token's subject, and a principal name (which other mechanisms, or a token's {@code upn} or
     * {@code preferred_username}, may set to anything) never stands in for it.
     *
     * @throws AuthenticationFailedException when {@code sub} is present but no usable subject (not text, blank, padded
     *     with whitespace or too long)
     */
    public static Optional<ProfileClaims> of(SecurityIdentity identity) {
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Optional.empty();
        }
        Object sub = token.getClaim("sub");
        if (sub == null) {
            return Optional.empty();
        }
        Optional<String> subject = text(sub);
        if (subject.isEmpty()) {
            throw new AuthenticationFailedException("the token's sub claim is not text");
        }
        try {
            return Optional.of(new ProfileClaims(
                    subject.get(),
                    text(token.getClaim("preferred_username")),
                    text(token.getClaim("given_name")),
                    text(token.getClaim("family_name"))));
        } catch (IllegalArgumentException unusable) {
            throw new AuthenticationFailedException("the token's sub claim is not a usable subject", unusable);
        }
    }

    private static Optional<String> text(Object value) {
        if (value instanceof String text) {
            return Optional.of(text);
        }
        if (value instanceof JsonString json) {
            return Optional.of(json.getString());
        }
        return Optional.empty();
    }
}
