package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.multisite.runtime.SiteLookup;

class EventLoopDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(EventLoopDbTest.class);

    @Inject
    Vertx vertx;

    @Inject
    SiteLookup lookup;

    @Test
    void theLookupRefusesToBlockAnEventLoop() throws Exception {
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(ignored -> {
            try {
                lookup.network();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        Throwable thrown = outcome.get(30, TimeUnit.SECONDS);
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QuarkPress looks sites up with blocking JDBC and must not do so on an event loop thread");
        assertThat(lookup.network()).isPresent();
    }
}
