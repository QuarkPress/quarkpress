package org.quarkpress.persistence.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The associations between site-scoped entities that the build found, for the listener's site check. */
public final class SiteAssociations {

    private static volatile Map<String, List<String>> fieldNames = Map.of();

    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = type;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (String name : fieldNames.getOrDefault(current.getName(), List.of())) {
                    try {
                        Field field = current.getDeclaredField(name);
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (NoSuchFieldException e) {
                        throw new IllegalStateException(
                                "QuarkPress recorded the association " + current.getName() + "." + name
                                        + ", but the class has no such field",
                                e);
                    }
                }
            }
            return List.copyOf(fields);
        }
    };

    private SiteAssociations() {}

    static void install(Map<String, List<String>> byClass) {
        Map<String, List<String>> copy = new HashMap<>();
        byClass.forEach((type, names) -> copy.put(type, List.copyOf(names)));
        fieldNames = Map.copyOf(copy);
    }

    static List<Field> of(Class<?> type) {
        return FIELDS.get(type);
    }

    static Object read(Field field, Object entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("QuarkPress cannot read " + field, e);
        }
    }
}
