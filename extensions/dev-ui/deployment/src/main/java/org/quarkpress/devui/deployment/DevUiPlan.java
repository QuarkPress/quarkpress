package org.quarkpress.devui.deployment;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * What the Dev UI of an application shows, decided from the capabilities present: the pages, the JSON-RPC provider
 * classes, the beans they need and the method names Dev UI publishes. A page or provider of an optional module is
 * planned only when that module is installed; a provider's class is referenced only inside the check for its
 * module's capability, so an application without that module never loads it.
 */
record DevUiPlan(List<PagePlan> pages, List<Class<?>> providers, List<String> beans, List<String> methods) {

    static final String PERSISTENCE = "org.quarkpress.persistence";
    static final String MULTISITE = "org.quarkpress.multisite";
    static final String SECURITY = "org.quarkpress.security";
    static final String POSTS = "org.quarkpress.posts";
    static final String TAXONOMY = "org.quarkpress.taxonomy";
    static final String REVISIONS = "org.quarkpress.revisions";
    static final String SCHEDULING = "org.quarkpress.scheduling";
    static final String OIDC = "io.quarkus.oidc";

    /** The web component of the Modules page, the one page every application gets. */
    static final String MODULES_COMPONENT = "qwc-quarkpress-modules.js";

    /** One Dev UI page: its title, its icon and the file name of its web component. */
    record PagePlan(String title, String icon, String component) {}

    DevUiPlan {
        pages = List.copyOf(pages);
        providers = List.copyOf(providers);
        beans = List.copyOf(beans);
        methods = List.copyOf(methods);
    }

    /** The plan for an application whose capabilities are those {@code capabilityPresent} accepts. */
    static DevUiPlan of(Predicate<String> capabilityPresent) {
        List<PagePlan> pages = new ArrayList<>();
        List<Class<?>> providers = new ArrayList<>();
        List<String> beans = new ArrayList<>();
        pages.add(new PagePlan("Modules", "font-awesome-solid:puzzle-piece", MODULES_COMPONENT));
        return new DevUiPlan(pages, providers, beans, methodsOf(providers));
    }

    /**
     * The names of the methods Dev UI publishes for {@code providers}: their public, non-synthetic declared methods,
     * static ones included, each name once (Dev UI addresses a method by its name), sorted.
     */
    static List<String> methodsOf(List<Class<?>> providers) {
        TreeSet<String> methods = new TreeSet<>();
        for (Class<?> provider : providers) {
            for (Method method : provider.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    methods.add(method.getName());
                }
            }
        }
        return List.copyOf(methods);
    }
}
