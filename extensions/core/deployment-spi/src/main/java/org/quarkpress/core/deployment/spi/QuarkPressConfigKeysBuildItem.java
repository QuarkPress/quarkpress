package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Declares the {@code quarkpress.*} configuration keys a module reads. The {@code quarkpress.*} namespace is reserved:
 * at startup core fails on any key under it (from any source except environment variables) that no module declared,
 * so a typo cannot silently leave a setting at its default.
 *
 * <p>A key is the full dotted name in kebab case. A key ending in {@code []} is a list: the name itself and
 * {@code name[0]}, {@code name[1]}, and so on are accepted. Nothing else is: {@code name.x} is not.
 */
public final class QuarkPressConfigKeysBuildItem extends MultiBuildItem {

    private final Set<String> keys;

    public QuarkPressConfigKeysBuildItem(Set<String> keys) {
        this.keys = Set.copyOf(Objects.requireNonNull(keys, "keys"));
        for (String key : this.keys) {
            if (!key.startsWith("quarkpress.")) {
                throw new IllegalArgumentException("not a quarkpress.* key: " + key);
            }
        }
    }

    public Set<String> keys() {
        return keys;
    }

    /**
     * The keys of a configuration mapping interface: every method, in kebab case under {@code prefix}, nested mapping
     * interfaces followed, collections as lists. {@code @WithName} is honored; maps and {@code @WithParentName} are
     * not supported (the build fails; declare those keys by hand).
     */
    public static QuarkPressConfigKeysBuildItem of(String prefix, Class<?> mapping) {
        Set<String> keys = new TreeSet<>();
        collect(prefix, mapping, keys);
        return new QuarkPressConfigKeysBuildItem(keys);
    }

    private static void collect(String prefix, Class<?> mapping, Set<String> keys) {
        for (Method method : mapping.getMethods()) {
            if (method.isDefault()
                    || method.isSynthetic()
                    || method.getParameterCount() != 0
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String name = null;
            for (var annotation : method.getAnnotations()) {
                String type = annotation.annotationType().getSimpleName();
                if (type.equals("WithParentName")) {
                    throw new UnsupportedOperationException(mapping.getName() + "." + method.getName()
                            + " uses @WithParentName; declare its keys by hand");
                }
                if (type.equals("WithName")) {
                    try {
                        name = (String)
                                annotation.annotationType().getMethod("value").invoke(annotation);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            if (name == null) {
                name = kebab(method.getName());
            }
            String key = prefix + "." + name;
            Type generic = method.getGenericReturnType();
            Class<?> type = method.getReturnType();
            if (type == Optional.class && generic instanceof ParameterizedType parameterized) {
                generic = parameterized.getActualTypeArguments()[0];
                type = rawType(generic);
            }
            if (Map.class.isAssignableFrom(type)) {
                throw new UnsupportedOperationException(
                        mapping.getName() + "." + method.getName() + " is a map; declare its keys by hand");
            } else if (Collection.class.isAssignableFrom(type)) {
                keys.add(key + "[]");
            } else if (type.isInterface() && !type.getName().startsWith("java.")) {
                collect(key, type, keys);
            } else {
                keys.add(key);
            }
        }
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        return Object.class;
    }

    private static String kebab(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
