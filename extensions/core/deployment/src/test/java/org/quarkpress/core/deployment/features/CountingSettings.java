package org.quarkpress.core.deployment.features;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.quarkpress.core.FeatureSettings;
import org.quarkpress.core.SiteId;

/** Feature switches held in memory, counting how often they are read. */
@ApplicationScoped
public class CountingSettings implements FeatureSettings {

    private final Map<String, Boolean> site = new ConcurrentHashMap<>();
    private final Map<SiteId, Map<String, Boolean>> perSite = new ConcurrentHashMap<>();
    private final Map<SiteId, AtomicInteger> readsPerSite = new ConcurrentHashMap<>();
    private final Map<String, Boolean> network = new ConcurrentHashMap<>();
    private final AtomicInteger siteReads = new AtomicInteger();

    @Override
    public Map<String, Boolean> siteValues(SiteId siteId) {
        siteReads.incrementAndGet();
        readsPerSite.computeIfAbsent(siteId, key -> new AtomicInteger()).incrementAndGet();
        return Map.copyOf(perSite.getOrDefault(siteId, site));
    }

    @Override
    public Map<String, Boolean> networkDefaults() {
        return Map.copyOf(network);
    }

    public Map<String, Boolean> site() {
        return site;
    }

    public Map<String, Boolean> network() {
        return network;
    }

    /** Values for one site only; sites without their own values use {@link #site()}. */
    public Map<String, Boolean> site(SiteId siteId) {
        return perSite.computeIfAbsent(siteId, key -> new ConcurrentHashMap<>());
    }

    public int siteReads(SiteId siteId) {
        AtomicInteger reads = readsPerSite.get(siteId);
        return reads == null ? 0 : reads.get();
    }

    public int siteReads() {
        return siteReads.get();
    }

    public void reset() {
        site.clear();
        network.clear();
        perSite.clear();
        readsPerSite.clear();
        siteReads.set(0);
    }
}
