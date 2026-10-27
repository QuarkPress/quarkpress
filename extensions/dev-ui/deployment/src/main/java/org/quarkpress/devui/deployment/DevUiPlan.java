package org.quarkpress.devui.deployment;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.quarkpress.devui.runtime.AnonymousDevIdentity;
import org.quarkpress.devui.runtime.ContentDevJsonRpc;
import org.quarkpress.devui.runtime.DevCalls;
import org.quarkpress.devui.runtime.QuarkPressDevJsonRpc;
import org.quarkpress.devui.runtime.RevisionReadsBean;
import org.quarkpress.devui.runtime.SitesDevJsonRpc;
import org.quarkpress.devui.runtime.SystemDevIdentity;
import org.quarkpress.devui.runtime.TaxonomyReadsBean;

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
        boolean persistence = capabilityPresent.test(PERSISTENCE);
        boolean multisite = persistence && capabilityPresent.test(MULTISITE);
        boolean posts = persistence && capabilityPresent.test(POSTS);
        pages.add(new PagePlan("Modules", "font-awesome-solid:puzzle-piece", MODULES_COMPONENT));
        if (multisite) {
            pages.add(new PagePlan("Sites", "font-awesome-solid:sitemap", "qwc-quarkpress-sites.js"));
        }
        if (persistence) {
            pages.add(new PagePlan("Features", "font-awesome-solid:toggle-on", "qwc-quarkpress-features.js"));
            pages.add(new PagePlan("Persistence", "font-awesome-solid:database", "qwc-quarkpress-persistence.js"));
            providers.add(QuarkPressDevJsonRpc.class);
            beans.add(DevCalls.class.getName());
            beans.add(
                    capabilityPresent.test(SECURITY)
                            ? SystemDevIdentity.class.getName()
                            : AnonymousDevIdentity.class.getName());
        }
        if (multisite) {
            providers.add(SitesDevJsonRpc.class);
        }
        if (posts) {
            pages.add(new PagePlan("Registries", "font-awesome-solid:list", "qwc-quarkpress-registries.js"));
            pages.add(new PagePlan("Content counts", "font-awesome-solid:calculator", "qwc-quarkpress-counts.js"));
            providers.add(ContentDevJsonRpc.class);
            // the bean classes name the optional module's types, so they are named only when it is installed
            if (capabilityPresent.test(TAXONOMY)) {
                beans.add(TaxonomyReadsBean.class.getName());
            }
            if (capabilityPresent.test(REVISIONS)) {
                beans.add(RevisionReadsBean.class.getName());
            }
        }
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
