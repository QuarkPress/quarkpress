package org.quarkword.front.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteInfo;
import org.quarkword.core.SiteStatus;

/**
 * Whether the request's site may serve its front end, decided before anything else, as the REST module's site gate
 * decides for its routes (rest-wp-v2's README, "Multisite"): a GET or HEAD that arrived on an alias domain set to
 * redirect is sent with a 301 to the same path and query on the site's address; a site that is archived, spam or
 * deleted answers 410 with WordPress's site-check message to every caller. WordPress itself only redirects a
 * {@code www.} variant of the home host (the canonical redirect does that); the alias redirect is QuarkWord's.
 */
@ApplicationScoped
public class FrontSiteGate {

    /** WordPress's message for an archived or spam site. */
    public static final String ARCHIVED = "This site has been archived or suspended.";

    /** WordPress's message for a deleted site. */
    public static final String GONE = "This site is no longer available.";

    /** The answer that ends a request at the gate. */
    public sealed interface Answer {}

    /** A redirect to the site's address. */
    public record Moved(Redirect redirect) implements Answer {
        public Moved {
            Objects.requireNonNull(redirect, "redirect");
        }
    }

    /** The site is gone: 410 with WordPress's message. */
    public record Gone(int status, String message) implements Answer {
        public Gone {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * The gate's answer for a request with {@code method} to {@code pathAndQuery} (as sent, relative to the alias
     * domain's root) on the bound site of {@code site}, or empty when the request may go on.
     */
    public Optional<Answer> decide(SiteContext site, String method, String pathAndQuery) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pathAndQuery, "pathAndQuery");
        Optional<URI> redirect = site.redirect();
        if (redirect.isPresent() && (method.equals("GET") || method.equals("HEAD"))) {
            return Optional.of(new Moved(Redirect.permanent(location(redirect.get(), pathAndQuery))));
        }
        if (!site.isResolved()) {
            return Optional.empty();
        }
        SiteInfo info = site.site();
        if (info.status() != SiteStatus.ACTIVE) {
            return Optional.of(new Gone(410, info.status() == SiteStatus.DELETED ? GONE : ARCHIVED));
        }
        return Optional.empty();
    }

    /**
     * The request target under the site's address, as rest-wp-v2's site gate builds it: the target always starts a
     * path, so it can never extend the address's authority.
     */
    static String location(URI siteAddress, String target) {
        String base = siteAddress.toString();
        String path = target.startsWith("/") ? target : "/" + target;
        return base.endsWith("/") ? base + path.substring(1) : base + path;
    }
}
