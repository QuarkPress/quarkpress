package org.quarkpress.core;

import io.quarkus.arc.InstanceHandle;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** Applies a filter chain. */
public final class Filters {

    private Filters() {}

    /**
     * Orders filter beans for a chain: higher {@code @Priority} first (none counts as 0), equal priorities by the fully
     * qualified class name of the bean, so the order never depends on discovery order. Bean metadata is used, so
     * client proxies do not influence the order. Inject the handles with {@code @Inject @All
     * List<InstanceHandle<ContentFilter>>}.
     */
    public static <F> List<F> ordered(List<InstanceHandle<F>> handles) {
        Objects.requireNonNull(handles, "handles");
        return handles.stream()
                .sorted(Comparator.<InstanceHandle<F>>comparingInt(
                                h -> h.getBean().getPriority())
                        .reversed()
                        .thenComparing(h -> h.getBean().getBeanClass().getName()))
                .map(InstanceHandle::get)
                .toList();
    }

    /**
     * Passes {@code value} through every filter in list order (use {@link #ordered}). The value and every filter must be
     * non-null. A filter that returns null fails with a NullPointerException naming its class. A filter that throws
     * propagates its exception unchanged and the later filters do not run.
     */
    public static <F, T> T apply(List<F> filters, T value, BiFunction<F, T, T> step) {
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(step, "step");
        T current = Objects.requireNonNull(value, "value must not be null");
        for (F filter : filters) {
            Objects.requireNonNull(filter, "filters must not contain null");
            T next = step.apply(filter, current);
            if (next == null) {
                throw new NullPointerException(filter.getClass().getName() + " returned null");
            }
            current = next;
        }
        return current;
    }
}
