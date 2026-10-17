package org.quarkpress.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;

class ConcurrentResolutionTest {

    static volatile CountDownLatch inside = new CountDownLatch(2);

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Requests.class, Gate.class, ConcurrentResolutionTest.class));

    @ApplicationScoped
    public static class Gate implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            CountDownLatch latch = inside;
            latch.countDown();
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the second thread never entered the resolution");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return Optional.of(SiteId.of(5));
        }
    }

    @Inject
    SiteBinding binding;

    @Test
    void twoThreadsResolvingTogetherShareOneBindingAndLeaveNoStaleGuard() throws Exception {
        inside = new CountDownLatch(2);
        Requests.run(() -> {
            SiteBinding shared = (SiteBinding) ClientProxy.unwrap(binding);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Binding> first = pool.submit(shared::current);
                Future<Binding> second = pool.submit(shared::current);
                Binding a = first.get(20, TimeUnit.SECONDS);
                Binding b = second.get(20, TimeUnit.SECONDS);
                assertThat(a).isEqualTo(Binding.site(SiteId.of(5)));
                assertThat(b).isEqualTo(a);
                assertThat(pool.submit(shared::current).get(20, TimeUnit.SECONDS))
                        .isEqualTo(a);
                assertThat(shared.current()).isEqualTo(a);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                pool.shutdownNow();
            }
        });
    }
}
