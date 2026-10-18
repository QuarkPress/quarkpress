package org.quarkpress.it.multisite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.it.feature.DemoFeatures;

/** Hears, after commit, of every site created with the demo feature on. */
@ApplicationScoped
public class CreatedLog {

    private final List<Long> ids = new CopyOnWriteArrayList<>();
    private final List<Long> all = new CopyOnWriteArrayList<>();
    private final Map<String, String> contexts = new ConcurrentHashMap<>();

    @Inject
    SiteContext context;

    @SiteFeature(DemoFeatures.DEMO)
    void on(@ObservesAsync SiteCreated event) {
        ids.add(event.site().value());
    }

    /** Every created site, whatever its features: the reference the gated list is judged against. */
    void onAny(@ObservesAsync SiteCreated event) {
        contexts.put(String.valueOf(event.site().value()), seenContext());
        all.add(event.site().value());
    }

    /**
     * What the asynchronous notification sees of the current site: it runs outside the request that created the site,
     * so it must see none, never the creating request's site.
     */
    private String seenContext() {
        try {
            return context.isResolved() ? "site " + context.siteId().value() : "unresolved";
        } catch (RuntimeException failure) {
            return "failed: " + failure;
        }
    }

    /** Per created site id: "unresolved", "site n" or the failure the observer met reading the current site. */
    public Map<String, String> contexts() {
        return Map.copyOf(contexts);
    }

    public List<Long> allIds() {
        return List.copyOf(all);
    }

    public List<Long> ids() {
        return List.copyOf(ids);
    }
}
