package org.quarkpress.security.deployment;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** A token principal with the given claims, standing in for a verified OIDC access token. */
public record TestJwt(Map<String, Object> claims) implements JsonWebToken {

    @Override
    public String getName() {
        return (String) claims.getOrDefault("preferred_username", claims.get("sub"));
    }

    @Override
    public Set<String> getClaimNames() {
        return claims.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
        return (T) claims.get(claimName);
    }
}
