package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.NetworkScopePolicy;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.Scopes;
import org.quarkpress.scheduling.runtime.ScopeProbe;

class NetworkScopeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            DbApp.with(NetworkScopeDbTest.class, ScopeProbe.class, AllowListedCaller.class, AllowListPolicy.class);

    @ApplicationScoped
    public static class AllowListedCaller {
        @Inject
        NetworkScope network;

        public String run() {
            network.run(() -> {});
            return "allowed";
        }
    }

    @ApplicationScoped
    public static class AllowListPolicy implements NetworkScopePolicy {
        static final List<Class<?>> ASKED = new CopyOnWriteArrayList<>();

        @Override
        public boolean allows(Class<?> caller) {
            ASKED.add(caller);
            return caller == AllowListedCaller.class;
        }
    }

    @Inject
    NetworkScope network;

    @Inject
    SiteScope scope;

    @Inject
    OptionRepository options;

    @Inject
    ScopeProbe probe;

    @Inject
    AllowListedCaller allowListed;

    @Inject
    TestSites sites;

    @Test
    void ordinaryCallersAreRefused() {
        AllowListPolicy.ASKED.clear();
        assertThatThrownBy(() -> network.run(() -> {}))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(NetworkScopeDbTest.class.getName());
        assertThatThrownBy(() -> network.call(() -> "x"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(NetworkScopeDbTest.class.getName());
        assertThat(AllowListPolicy.ASKED).containsExactly(NetworkScopeDbTest.class, NetworkScopeDbTest.class);
    }

    @Test
    void theNetworkOpensOnlyThroughNetworkScope() {
        assertThatThrownBy(() -> Scopes.within(Binding.NETWORK, () -> options.count()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(NetworkScope.class.getName());
        assertThatThrownBy(() -> Scopes.withinNetwork(null, () -> options.count()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(NetworkScope.class.getName());
    }

    @Test
    void trustedModulesSeeEverySite() throws Exception {
        SiteId a = sites.create("net-a");
        SiteId b = sites.create("net-b");
        String name = "net-" + UUID.randomUUID();
        Instant at = Instant.parse("2026-10-17T12:00:00Z");
        scope.run(a, () -> options.persist(OptionEntity.create(a, name, TextNode.valueOf("a"), true, at)));
        scope.run(b, () -> options.persist(OptionEntity.create(b, name, TextNode.valueOf("b"), true, at)));
        assertThat(probe.countOptionsNamed(name)).isEqualTo(2);
    }

    @Test
    void aPolicyCanAllowACaller() {
        assertThat(allowListed.run()).isEqualTo("allowed");
    }

    @Test
    void networkWritesMustNameTheirSite() {
        String name = "nosite-" + UUID.randomUUID();
        assertThat(Failures.messages(probe.insertWithoutSite(name))).contains("must name its site");
    }
}
