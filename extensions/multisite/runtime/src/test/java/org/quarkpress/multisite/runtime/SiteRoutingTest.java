package org.quarkpress.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;

class SiteRoutingTest {

    /** A lookup that records what it is asked and answers from fixed values, or fails. */
    private static final class StubLookup extends SiteLookup {
        final List<List<String>> domains = new ArrayList<>();
        final List<List<String>> paths = new ArrayList<>();
        boolean fail;

        @Override
        public Optional<Address> network() {
            return Optional.of(new Address("localhost", "/"));
        }

        @Override
        public Optional<AliasHit> alias(List<String> asked) {
            if (fail) {
                throw new IllegalStateException("QuarkPress could not look up sites: down");
            }
            return Optional.empty();
        }

        @Override
        public Optional<SiteId> site(List<String> askedDomains, List<String> askedPaths) {
            if (fail) {
                throw new IllegalStateException("QuarkPress could not look up sites: down");
            }
            domains.add(askedDomains);
            paths.add(askedPaths);
            return Optional.of(SiteId.of(7));
        }
    }

    private static SiteRouting routing(StubLookup lookup, ResolverStrategy... strategies) {
        SiteRouting routing = new SiteRouting();
        routing.lookup = lookup;
        routing.settings = new MultisiteSettingsView() {
            @Override
            public Set<ResolverStrategy> resolvers() {
                return Set.of(strategies);
            }

            @Override
            public int pathSegments() {
                return 1;
            }
        };
        return routing;
    }

    @Test
    void theLookupIsAskedForNormalizedValuesOnly() {
        StubLookup lookup = new StubLookup();
        SiteRouting routing = routing(lookup, ResolverStrategy.PATH);
        String path = "/Shop/" + (char) 0 + "x";
        assertThat(routing.match(ResolverStrategy.PATH, new SiteRequest("http", "LocalHost:8080", -1, path)))
                .contains(SiteId.of(7));
        assertThat(lookup.domains).containsExactly(List.of("localhost"));
        assertThat(lookup.paths).containsExactly(List.of("/shop/", "/"));
        assertThat(routing.match(ResolverStrategy.PATH, new SiteRequest("http", "a" + (char) 0 + "b", -1, "/")))
                .isEmpty();
        assertThat(lookup.domains).hasSize(1);
    }

    @Test
    void aStrategyThatIsNotEnabledNeverAsksTheLookup() {
        StubLookup lookup = new StubLookup();
        SiteRouting routing = routing(lookup, ResolverStrategy.DOMAIN);
        assertThat(routing.match(ResolverStrategy.PATH, new SiteRequest("http", "localhost", -1, "/")))
                .isEmpty();
        assertThat(lookup.domains).isEmpty();
    }

    @Test
    void aFailingLookupFailsTheMatchAndBindsNothing() {
        StubLookup lookup = new StubLookup();
        lookup.fail = true;
        for (ResolverStrategy strategy : ResolverStrategy.values()) {
            SiteRouting routing = routing(lookup, strategy);
            String host = strategy == ResolverStrategy.SUBDOMAIN ? "a.localhost" : "localhost";
            assertThatThrownBy(() -> routing.match(strategy, new SiteRequest("http", host, -1, "/")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("QuarkPress could not look up sites: down");
        }
    }
}
