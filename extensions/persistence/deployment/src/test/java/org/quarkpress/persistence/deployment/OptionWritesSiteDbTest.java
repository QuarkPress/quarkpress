package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionWrites;
import org.quarkpress.scheduling.runtime.ScopeProbe;

/** Native option inserts bypass the site filter and the listener; the writer checks the binding itself. */
class OptionWritesSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(OptionWritesSiteDbTest.class, ScopeProbe.class);

    @Inject
    OptionWrites writes;

    @Inject
    SiteScope scope;

    @Inject
    ScopeProbe probe;

    @Inject
    SiteBinding binding;

    @Inject
    Options options;

    @Inject
    TestSites sites;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private long rowsOf(SiteId site, String name) {
        return Jdbc.count(
                dataSource, "select count(*) from qp_option where site_id = ? and name = ?", site.value(), name);
    }

    @Test
    void aSiteCannotWriteAnotherSitesOptions() {
        SiteId a = sites.create("writes-a");
        SiteId b = sites.create("writes-b");
        String name = "foreign-" + UUID.randomUUID();
        String message = "option of site " + b.value() + " written under site " + a.value();
        // Each refused call runs in its own scope: the refusal rolls the scope's transaction back.
        assertRefused(() -> scope.run(a, () -> writes.set(b, name, TextNode.valueOf("x"), null)), message);
        assertRefused(() -> scope.run(a, () -> writes.add(b, name, TextNode.valueOf("x"), true)), message);
        assertRefused(() -> scope.run(a, () -> writes.setAutoload(b, name, false)), message);
        assertRefused(() -> scope.run(a, () -> writes.delete(b, name)), message);
        assertThat(rowsOf(b, name)).isZero();
    }

    @Test
    void aRefusedWriteRollsBackTheCallersTransaction() {
        SiteId a = sites.create("writes-rollback-a");
        SiteId b = sites.create("writes-rollback-b");
        String kept = "kept-" + UUID.randomUUID();
        assertThatThrownBy(() -> scope.run(a, () -> {
                    options.set(kept, TextNode.valueOf("x"));
                    try {
                        writes.set(b, "foreign", TextNode.valueOf("x"), null);
                    } catch (IllegalStateException refused) {
                        // caught: the transaction must still not commit
                    }
                }))
                .isExactlyInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class)
                .hasMessageContaining("Could not commit transaction");
        assertThat(rowsOf(a, kept)).isZero();
    }

    @Test
    void anUnresolvedBindingWritesNothing() {
        SiteId b = sites.create("writes-unresolved");
        String name = "unresolved-" + UUID.randomUUID();
        Requests.fresh(() -> {
            binding.bind(Binding.UNRESOLVED);
            String message = "no site is bound: a site-scoped row needs a site";
            assertRefused(() -> writes.set(b, name, TextNode.valueOf("x"), null), message);
            assertRefused(() -> writes.add(b, name, TextNode.valueOf("x"), true), message);
            assertRefused(() -> writes.setAutoload(b, name, false), message);
            assertRefused(() -> writes.delete(b, name), message);
        });
        assertThat(rowsOf(b, name)).isZero();
    }

    private static void assertRefused(ThrowingCallable call, String message) {
        assertThatThrownBy(call)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(message);
    }

    @Test
    void theNetworkScopeWritesAnySite() throws Exception {
        SiteId b = sites.create("writes-network");
        String set = "net-set-" + UUID.randomUUID();
        String added = "net-add-" + UUID.randomUUID();
        assertThat(probe.inNetwork(() -> writes.set(b, set, TextNode.valueOf("x"), null)))
                .isTrue();
        assertThat(probe.inNetwork(() -> writes.add(b, added, TextNode.valueOf("y"), true)))
                .isTrue();
        assertThat(rowsOf(b, set)).isEqualTo(1);
        assertThat(rowsOf(b, added)).isEqualTo(1);
    }

    @Test
    void writesWithoutABoundRequestAreRefused() {
        SiteId b = sites.create("writes-unbound");
        String name = "unbound-" + UUID.randomUUID();
        Requests.outside(() -> {
            assertThatThrownBy(() -> writes.set(b, name, TextNode.valueOf("x"), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("no request context: site-scoped data needs a bound site");
            assertThatThrownBy(() -> writes.add(b, name, TextNode.valueOf("x"), true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("no request context: site-scoped data needs a bound site");
        });
        assertThat(rowsOf(b, name)).isZero();
    }
}
