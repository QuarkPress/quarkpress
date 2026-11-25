package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteFeatures;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteInfo;
import org.quarkword.core.SiteOptions;

/**
 * The alias redirect's {@code Location}: the request target always lands under the site's address, whatever it starts
 * with, and is sanitised as every redirect is.
 */
class FrontSiteGateTest {

    private static Optional<FrontSiteGate.Answer> decide(String address, String method, String target) {
        return new FrontSiteGate().decide(new Alias(URI.create(address)), method, target);
    }

    private static Optional<FrontSiteGate.Answer> moved(String location) {
        return Optional.of(new FrontSiteGate.Moved(new Redirect(301, location)));
    }

    @Test
    void theTargetStaysOnTheSitesHost() {
        // without the leading slash the target would extend the address's authority: https://primary.test@evil.test/x
        assertThat(decide("https://primary.test", "GET", "@evil.test/x"))
                .isEqualTo(moved("https://primary.test/@evil.test/x"));
        assertThat(decide("https://primary.test/", "GET", "@evil.test/x"))
                .isEqualTo(moved("https://primary.test/@evil.test/x"));
        assertThat(decide("https://primary.test", "GET", "/2025/?p=1"))
                .isEqualTo(moved("https://primary.test/2025/?p=1"));
        assertThat(decide("https://primary.test/", "HEAD", "/2025/?p=1"))
                .isEqualTo(moved("https://primary.test/2025/?p=1"));
        assertThat(decide("https://primary.test/", "POST", "/2025/")).isEmpty();
    }

    @Test
    void theLocationIsSanitised() {
        assertThat(decide("https://primary.test", "GET", "/a b\"<x>\r\n"))
                .isEqualTo(moved("https://primary.test/a%20bx"));
    }

    /** A request on an alias domain that redirects to {@code address}; nothing else is asked. */
    private record Alias(URI address) implements SiteContext {

        @Override
        public boolean isResolved() {
            return false;
        }

        @Override
        public SiteId siteId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SiteInfo site() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SiteFeatures features() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SiteOptions options() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ZoneId zone() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<URI> redirect() {
            return Optional.of(address);
        }
    }
}
