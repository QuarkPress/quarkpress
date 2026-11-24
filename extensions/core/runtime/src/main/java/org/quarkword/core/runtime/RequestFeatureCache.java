package org.quarkword.core.runtime;

import jakarta.enterprise.context.RequestScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.quarkword.core.FeatureValue;
import org.quarkword.core.SiteId;

/**
 * Feature values computed during the current request, per site. Threads that share the request context may use it
 * concurrently. The compute function must not call back into this cache.
 */
@RequestScoped
public class RequestFeatureCache {

    private final Map<SiteId, Map<String, FeatureValue>> bySite = new ConcurrentHashMap<>();

    public Map<String, FeatureValue> get(SiteId site, Function<SiteId, Map<String, FeatureValue>> compute) {
        return bySite.computeIfAbsent(site, compute);
    }

    public void invalidate(SiteId site) {
        bySite.remove(site);
    }

    public void invalidateAll() {
        bySite.clear();
    }
}
