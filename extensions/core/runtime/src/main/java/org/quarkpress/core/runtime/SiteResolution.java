package org.quarkpress.core.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;

/**
 * Asks every {@link SiteResolver}, higher priority first and equal priorities by class name, and returns the first
 * answer. A resolver that throws stops the resolution; no lower resolver answers in its place.
 */
@ApplicationScoped
public class SiteResolution {

    @Inject
    @All
    List<InstanceHandle<SiteResolver>> handles;

    private volatile List<SiteResolver> ordered;
    private final Map<SiteResolver, String> names = new IdentityHashMap<>();

    @PostConstruct
    void order() {
        List<InstanceHandle<SiteResolver>> sorted = handles.stream()
                .sorted(Comparator.<InstanceHandle<SiteResolver>>comparingInt(
                                h -> h.getBean().getPriority())
                        .reversed()
                        .thenComparing(h -> h.getBean().getBeanClass().getName()))
                .toList();
        for (InstanceHandle<SiteResolver> handle : sorted) {
            names.put(handle.get(), handle.getBean().getBeanClass().getName());
        }
        ordered = sorted.stream().map(InstanceHandle::get).toList();
    }

    /** Dependent resolvers are created for this bean, so they are destroyed with it. */
    @PreDestroy
    void destroy() {
        for (InstanceHandle<SiteResolver> handle : handles) {
            if (handle.getBean().getScope() == Dependent.class) {
                handle.destroy();
            }
        }
    }

    public Optional<SiteId> resolve(SiteRequest request) {
        Objects.requireNonNull(request, "request");
        for (SiteResolver resolver : ordered) {
            Optional<SiteId> answer = resolver.resolve(request);
            Objects.requireNonNull(answer, () -> names.get(resolver) + " returned null");
            if (answer.isPresent()) {
                return answer;
            }
        }
        return Optional.empty();
    }
}
