package org.quarkpress.blocks.runtime;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** How the block registries read their providers. */
public final class Registries {

    private Registries() {}

    /**
     * The items of every provider bean, providers by their order, then by bean class name (CDI gives beans in no
     * particular order, so the class name makes the registration order stable), each provider's items in its order.
     * The items are read once, at start-up; a {@code @Dependent} provider is destroyed right after it was read.
     */
    public static <P, T> List<T> read(Instance<P> providers, ToIntFunction<P> order, Function<P, List<T>> items) {
        record Entry<P>(Instance.Handle<P> handle, int order, String className) {}
        List<Entry<P>> entries = new ArrayList<>();
        for (Instance.Handle<P> handle : providers.handles()) {
            entries.add(new Entry<>(
                    handle,
                    order.applyAsInt(handle.get()),
                    handle.getBean().getBeanClass().getName()));
        }
        entries.sort(Comparator.<Entry<P>>comparingInt(Entry::order).thenComparing(Entry::className));
        List<T> read = new ArrayList<>();
        for (Entry<P> entry : entries) {
            read.addAll(items.apply(entry.handle().get()));
            if (entry.handle().getBean().getScope() == Dependent.class) {
                entry.handle().destroy();
            }
        }
        return read;
    }

    /**
     * The items keyed by name, in order; an item registered again replaces the earlier one in the earlier one's
     * position, as WordPress's pattern and pattern category registries do.
     */
    public static <T> Map<String, T> replacingInPlace(List<T> items, Function<T, String> name) {
        Map<String, T> registered = new LinkedHashMap<>();
        for (T item : items) {
            registered.put(name.apply(item), item);
        }
        return registered;
    }
}
