package org.quarkpress.it.multisite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.it.feature.DemoFeatures;

/** Hears, after commit, of every site created with the demo feature on. */
@ApplicationScoped
public class CreatedLog {

    private final List<Long> ids = new CopyOnWriteArrayList<>();
    private final List<Long> all = new CopyOnWriteArrayList<>();

    @SiteFeature(DemoFeatures.DEMO)
    void on(@ObservesAsync SiteCreated event) {
        ids.add(event.site().value());
    }

    /** Every created site, whatever its features: the reference the gated list is judged against. */
    void onAny(@ObservesAsync SiteCreated event) {
        all.add(event.site().value());
    }

    public List<Long> allIds() {
        return List.copyOf(all);
    }

    public List<Long> ids() {
        return List.copyOf(ids);
    }
}
