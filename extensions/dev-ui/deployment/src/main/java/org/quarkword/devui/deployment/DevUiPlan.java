package org.quarkword.devui.deployment;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.quarkword.devui.runtime.AnonymousDevIdentity;
import org.quarkword.devui.runtime.ContentDevJsonRpc;
import org.quarkword.devui.runtime.DevCalls;
import org.quarkword.devui.runtime.QuarkWordDevJsonRpc;
import org.quarkword.devui.runtime.RevisionReadsBean;
import org.quarkword.devui.runtime.SchedulerDevJsonRpc;
import org.quarkword.devui.runtime.SitesDevJsonRpc;
import org.quarkword.devui.runtime.SystemDevIdentity;
import org.quarkword.devui.runtime.TaxonomyReadsBean;
import org.quarkword.devui.runtime.UsersDevJsonRpc;

/**
 * What the Dev UI of an application shows, decided from the capabilities present: the pages, the JSON-RPC provider
 * classes, the beans they need and the method names Dev UI publishes. A page or provider of an optional module is
 * planned only when that module is installed; a provider's class is referenced only inside the check for its
 * module's capability, so an application without that module never loads it.
 */
record DevUiPlan(List<PagePlan> pages, List<Class<?>> providers, List<String> beans, List<String> methods) {

    static final String PERSISTENCE = "org.quarkword.persistence";
    static final String MULTISITE = "org.quarkword.multisite";
    static final String SECURITY = "org.quarkword.security";
    static final String POSTS = "org.quarkword.posts";
    static final String TAXONOMY = "org.quarkword.taxonomy";
    static final String REVISIONS = "org.quarkword.revisions";
    static final String SCHEDULING = "org.quarkword.scheduling";
    static final String OIDC = "io.quarkus.oidc";

    /** The web component of the Modules page, the one page every application gets. */
    static final String MODULES_COMPONENT = "qwc-quarkword-modules.js";

    /** The Keycloak page of the OIDC extension's Dev UI card, where a dev user's token is obtained. */
    static final String TOKEN_PAGE = "dev-ui/quarkus-oidc/keycloak-provider";

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
            pages.add(new PagePlan("Sites", "font-awesome-solid:sitemap", "qwc-quarkword-sites.js"));
        }
        if (persistence) {
            pages.add(new PagePlan("Features", "font-awesome-solid:toggle-on", "qwc-quarkword-features.js"));
            pages.add(new PagePlan("Persistence", "font-awesome-solid:database", "qwc-quarkword-persistence.js"));
            providers.add(QuarkWordDevJsonRpc.class);
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
            pages.add(new PagePlan("Registries", "font-awesome-solid:list", "qwc-quarkword-registries.js"));
            pages.add(new PagePlan("Content counts", "font-awesome-solid:calculator", "qwc-quarkword-counts.js"));
            providers.add(ContentDevJsonRpc.class);
            // the bean classes name the optional module's types, so they are named only when it is installed
            if (capabilityPresent.test(TAXONOMY)) {
                beans.add(TaxonomyReadsBean.class.getName());
            }
            if (capabilityPresent.test(REVISIONS)) {
                beans.add(RevisionReadsBean.class.getName());
            }
        }
        if (persistence && capabilityPresent.test(SCHEDULING)) {
            pages.add(new PagePlan("Scheduler", "font-awesome-solid:clock", "qwc-quarkword-scheduler.js"));
            providers.add(SchedulerDevJsonRpc.class);
        }
        if (persistence && capabilityPresent.test(SECURITY)) {
            pages.add(new PagePlan("Dev users", "font-awesome-solid:users", "qwc-quarkword-users.js"));
            providers.add(UsersDevJsonRpc.class);
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

    /**
     * The path of the Keycloak token page, resolved by {@code resolvePath}: present only when the OIDC extension is
     * installed and Dev Services for Keycloak are enabled (otherwise there is no such page to link to).
     */
    static Optional<String> tokenPage(
            boolean oidcPresent, boolean keycloakDevServices, UnaryOperator<String> resolvePath) {
        if (!oidcPresent || !keycloakDevServices) {
            return Optional.empty();
        }
        return Optional.of(resolvePath.apply(TOKEN_PAGE));
    }

    /**
     * The absolute path of {@code path} under the non-application root, as Quarkus serves it: a relative
     * {@code nonApplicationRootPath} (the default {@code q}) is under the HTTP root path {@code rootPath}, an absolute
     * one is used as it is.
     */
    static String nonApplicationPath(String rootPath, String nonApplicationRootPath, String path) {
        String base = nonApplicationRootPath.startsWith("/")
                ? nonApplicationRootPath
                : join(rootPath, nonApplicationRootPath);
        return join(base, path);
    }

    /** {@code parent} and {@code child} joined by exactly one slash, with a leading slash. */
    private static String join(String parent, String child) {
        int end = parent.length();
        while (end > 0 && parent.charAt(end - 1) == '/') {
            end--;
        }
        int start = 0;
        while (start < child.length() && child.charAt(start) == '/') {
            start++;
        }
        String head = parent.substring(0, end);
        if (!head.startsWith("/")) {
            head = "/" + head;
        }
        return (head.endsWith("/") ? head : head + "/") + child.substring(start);
    }
}
