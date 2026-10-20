package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.wporacle.GeneratedCases;

/**
 * The multisite scenarios: hand-written ones per behaviour-reference rule, and generated ones drawn with test-gen from
 * fixed seeds (a few sites on a pool of domains and paths, maybe one flag change, then lookups of a pool of hosts and
 * paths), so every run compares the same cases.
 */
public final class MultisiteScenarios {

    static final String DIRECTORY = "scenarios/multisite/";

    /** Generated scenarios per variant with one path segment. */
    public static final int GENERATED = 20;

    /** Generated scenarios for the variant with two path segments. */
    public static final int GENERATED_TWO_SEGMENTS = 25;

    private MultisiteScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/multisite/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** Every scenario to compare: the hand-written ones, then the generated ones of every variant. */
    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>(handWritten());
        for (NetworkVariant variant : NetworkVariant.ALL) {
            int count = variant.pathSegments() == 1 ? GENERATED : GENERATED_TWO_SEGMENTS;
            scenarios.addAll(generated(variant, "20261017-ms-scenarios-" + variant.name(), count));
        }
        return List.copyOf(scenarios);
    }

    /** {@code count} generated scenarios on {@code variant}, the same for the same seed on every run. */
    public static List<Scenario> generated(NetworkVariant variant, String seed, int count) {
        List<List<ScenarioOp>> drawn = GeneratedCases.sample(ops(variant), seed, count);
        List<Scenario> scenarios = new ArrayList<>(drawn.size());
        for (int i = 0; i < drawn.size(); i++) {
            scenarios.add(new Scenario("gen-" + variant.name() + "-" + i, "generated", variant, drawn.get(i), true));
        }
        return List.copyOf(scenarios);
    }

    static Gen<List<ScenarioOp>> ops(NetworkVariant variant) {
        Gen<String> siteDomain = Gens.elements(siteDomains(variant));
        Gen<String> sitePath = Gens.elements(sitePaths(variant));
        Gen<String> title = Gens.elements("", "Generated");
        Gen<ScenarioOp.CreateSite> create =
                random -> new ScenarioOp.CreateSite(siteDomain.next(random), sitePath.next(random), title.next(random));
        Gen<String> host = Gens.elements(lookupHosts(variant));
        Gen<String> segment = Gens.elements(SEGMENTS);
        // The length is drawn uniformly, then that many segments: Gens.lists biases towards short lists.
        Gen<String> freePath = random -> {
            int length = Gens.ints(0, 4).next(random);
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                parts.add(segment.next(random));
            }
            return "/" + String.join("/", parts)
                    + (length > 0 && Gens.booleans().next(random) ? "/" : "");
        };
        Gen<ScenarioOp.Flag> flag = Gens.elements(ScenarioOp.Flag.values());
        return random -> {
            List<ScenarioOp> ops = new ArrayList<>();
            List<ScenarioOp.CreateSite> creates = new ArrayList<>();
            int count = Gens.ints(2, 5).next(random);
            for (int i = 0; i < count; i++) {
                ScenarioOp.CreateSite site = create.next(random);
                creates.add(site);
                ops.add(site);
            }
            if (Gens.booleans().next(random)) {
                ops.add(new ScenarioOp.SetFlag(
                        Gens.ints(1, count).next(random),
                        flag.next(random),
                        Gens.booleans().next(random)));
            }
            int lookups = Gens.ints(4, 8).next(random);
            for (int i = 0; i < lookups; i++) {
                // about three lookups in four start from a created site's address
                if (Gens.ints(0, 3).next(random) < 3) {
                    ops.add(fromSite(creates.get(Gens.ints(0, count - 1).next(random)), segment, random));
                } else {
                    ops.add(new ScenarioOp.Lookup(host.next(random), freePath.next(random)));
                }
            }
            return List.copyOf(ops);
        };
    }

    private static final List<String> SEGMENTS = List.of("x", "y", "X", "0", "", "z", "blog", "net", "shop");

    /**
     * A lookup built from a created site's address: its host (sometimes upper-cased, sometimes with {@code www.} added or
     * removed) and its path, exactly, with extra trailing segments, upper-cased, or with a {@code 0} or empty segment
     * inserted.
     */
    private static ScenarioOp.Lookup fromSite(ScenarioOp.CreateSite site, Gen<String> segment, RandomGenerator random) {
        String host = site.domain();
        switch (Gens.ints(0, 5).next(random)) {
            case 0 -> host = host.toUpperCase(Locale.ROOT);
            case 1 -> host = host.startsWith("www.") ? host.substring(4) : "www." + host;
            default -> {}
        }
        String base =
                site.path().endsWith("/") ? site.path().substring(0, site.path().length() - 1) : site.path();
        List<String> parts = new ArrayList<>(
                base.isEmpty() ? List.of() : List.of(base.substring(1).split("/", -1)));
        int extra = Gens.ints(0, 2).next(random);
        for (int i = 0; i < extra; i++) {
            parts.add(segment.next(random));
        }
        switch (Gens.ints(0, 5).next(random)) {
            case 0 -> parts.replaceAll(part -> part.toUpperCase(Locale.ROOT));
            case 1 -> parts.add(Gens.ints(0, parts.size()).next(random), "0");
            case 2 -> parts.add(Gens.ints(0, parts.size()).next(random), "");
            default -> {}
        }
        String path = "/" + String.join("/", parts)
                + (!parts.isEmpty() && Gens.booleans().next(random) ? "/" : "");
        return new ScenarioOp.Lookup(host, path);
    }

    /** Where generated sites live: subdomain networks put none on the network domain itself. */
    private static List<String> siteDomains(NetworkVariant variant) {
        if (variant.subdomainInstall()) {
            return List.of("a.localhost", "b.localhost", "www.a.localhost", "m.example", "www.m.example");
        }
        return List.of("localhost", "localhost", "localhost", "a.localhost", "m.example", "www.m.example");
    }

    private static List<String> sitePaths(NetworkVariant variant) {
        if (variant.subdomainInstall()) {
            return List.of("/", "/", "/shop/", "/x/");
        }
        if (!variant.path().equals("/")) {
            return List.of("/net/x/", "/net/y/", "/net/x/y/", "/net/blog/", "/net/", "/x/");
        }
        return List.of("/", "/x/", "/y/", "/x/y/", "/X/", "/blog/", "/shop/");
    }

    private static List<String> lookupHosts(NetworkVariant variant) {
        if (variant.subdomainInstall()) {
            return List.of(
                    "localhost",
                    "www.localhost",
                    "a.localhost",
                    "A.LOCALHOST",
                    "b.localhost",
                    "www.a.localhost",
                    "c.localhost",
                    "m.example",
                    "www.m.example",
                    "zz.example");
        }
        return List.of(
                "localhost", "LOCALHOST", "www.localhost", "a.localhost", "m.example", "www.m.example", "zz.example");
    }
}
