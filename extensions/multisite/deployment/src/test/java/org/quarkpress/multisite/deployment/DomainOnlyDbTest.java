package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.runtime.HttpResolution;

class DomainOnlyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(DomainOnlyDbTest.class, Network.class)
            .overrideRuntimeConfigKey("quarkpress.multisite.resolvers", "domain");

    @Inject
    HttpResolution resolution;

    @Inject
    Network network;

    private Binding resolve(String host, String path) {
        return resolution.route(new SiteRequest("http", host, -1, path)).binding();
    }

    @Test
    void onlyMappedDomainsAndAliasesResolve() {
        String mapped = Network.unique("m") + ".example";
        SiteId site = network.site(mapped, "/");
        String alias = Network.unique("al") + ".example";
        network.alias(site, alias, false);
        String sub = Network.unique("s") + ".localhost";
        network.site(sub, "/");
        assertThat(resolve(mapped, "/")).isEqualTo(Binding.site(site));
        assertThat(resolve(alias, "/")).isEqualTo(Binding.site(site));
        assertThat(resolve(sub, "/")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("localhost", "/")).isEqualTo(Binding.UNRESOLVED);
    }
}
