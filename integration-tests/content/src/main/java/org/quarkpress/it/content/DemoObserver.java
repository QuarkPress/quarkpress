package org.quarkpress.it.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.atomic.AtomicInteger;
import org.quarkpress.core.SiteFeature;

@ApplicationScoped
public class DemoObserver {

    private final AtomicInteger calls = new AtomicInteger();

    @SiteFeature("demo")
    void on(@Observes DemoPing ping) {
        calls.incrementAndGet();
    }

    public int calls() {
        return calls.get();
    }
}
