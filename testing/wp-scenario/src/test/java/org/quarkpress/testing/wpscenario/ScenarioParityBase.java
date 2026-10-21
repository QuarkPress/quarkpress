package org.quarkpress.testing.wpscenario;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext.ContextState;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.UserId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.SiteChanges;
import org.quarkpress.multisite.SiteFlag;
import org.quarkpress.multisite.Sites;
import org.quarkpress.multisite.runtime.HttpResolution;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionWrites;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Membership;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.SiteRoles;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfileProvisioner;
import org.quarkpress.security.UserTarget;
import org.quarkpress.security.runtime.SiteRoleTables;
import org.quarkpress.security.runtime.UserProfileRepository;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.runtime.TermRepository;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;

/**
 * Runs scenarios against a real WordPress (the PHP driver on the MySQL container) and against QuarkPress (this test
 * application's {@code Sites} and HTTP resolution), and compares what each observed.
 */
abstract class ScenarioParityBase {

    static final SiteId MAIN = SiteId.of(1);
    static final String MAIN_DOMAIN = "localhost";
    static final String MAIN_PATH = "/";

    @Inject
    Sites sites;

    @Inject
    HttpResolution resolution;

    @Inject
    SiteScope scope;

    @Inject
    OptionStore store;

    @Inject
    SystemIdentity system;

    @Inject
    UserProfileProvisioner provisioner;

    @Inject
    UserProfileRepository profileRows;

    @Inject
    Memberships memberships;

    @Inject
    SiteRoles siteRoles;

    @Inject
    Capabilities capabilities;

    @Inject
    NetworkOptions networkOptions;

    @Inject
    Options options;

    @Inject
    OptionWrites optionWrites;

    @Inject
    Posts posts;

    @Inject
    Terms termService;

    @Inject
    TermRepository termRows;

    /** Compares every scenario; fails on a difference, a stale skip entry, or fewer comparisons than {@code minimum}. */
    void compareWithWordPress(String library, List<Scenario> scenarios, Path skips, int minimum) throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        Path wordpress = WordPressTree.ensure(System.getProperty("wp.version"));
        ParityRun run =
                new ParityRun(library, ParitySkipList.load(skips), Path.of("target", library + "-failures.json"));
        try (ScenarioDatabase database = ScenarioDatabase.start()) {
            WordPressScenarioOracle oracle = WordPressScenarioOracle.create(wordpress, database);
            for (Scenario scenario : scenarios) {
                Outcome wordpressSide = WordPressOutcomes.normalize(scenario, oracle.run(scenario));
                Outcome quarkpressSide = runOnQuarkPress(scenario);
                if (scenario.generated()) {
                    run.generated(scenario.caseId(), ScenarioJson.write(scenario));
                }
                ScenarioComparison.compare(run, scenario.caseId(), wordpressSide, quarkpressSide);
            }
        }
        System.out.println(library + ": " + scenarios.size() + " scenarios, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(minimum);
    }

    /**
     * Runs one scenario on QuarkPress's network moved to the scenario's address, then removes what it created. Each
     * scenario gets a fresh request context, so no request option cache spans scenarios.
     */
    Outcome runOnQuarkPress(Scenario scenario) throws Exception {
        ManagedContext requests = Arc.container().requestContext();
        ContextState outer = null;
        if (requests.isActive()) {
            outer = requests.getState();
            requests.deactivate();
        }
        requests.activate();
        try {
            return system.call(() -> runInRequest(scenario));
        } finally {
            requests.terminate();
            if (outer != null) {
                requests.activate(outer);
            }
        }
    }

    private Outcome runInRequest(Scenario scenario) throws Exception {
        NetworkVariant variant = scenario.variant();
        Outcome outcome = new Outcome();
        List<SiteId> created = new ArrayList<>();
        List<ScenarioOp.CreateSite> leaked = new ArrayList<>();
        List<UserId> users = new ArrayList<>();
        boolean postScenario = scenario.ops().stream().anyMatch(ScenarioOp.PostOp.class::isInstance);
        PostScenarioRunner postRunner =
                new PostScenarioRunner(posts, scope, store, options, PostRecords.includeGuid(scenario));
        boolean termScenario = scenario.ops().stream().anyMatch(ScenarioOp.TermOp.class::isInstance);
        TermScenarioRunner termRunner = new TermScenarioRunner(termService, termRows, scope, store, postRunner);
        postRunner.terms(termRunner);
        // WordPress's installation has a user named admin (nicename admin); mirror it so nicenames collide alike.
        UserProfile[] installer = new UserProfile[1];
        Throwable failure = null;
        try {
            assertMainAddress("before the scenario " + scenario.caseId());
            installer[0] = provisioner.provision(new ProfileClaims(
                    "tierb-admin-" + UUID.randomUUID(), Optional.of("admin"), Optional.empty(), Optional.empty()));
            if (!installer[0].nicename().equals("admin")) {
                throw new AssertionError("a profile left by an earlier run holds the nicename admin ("
                        + installer[0].nicename() + " was assigned): delete the stale profile whose subject starts"
                        + " with tierb- from the QuarkPress test database and run again");
            }
            sites.update(MAIN, SiteChanges.none().withDomain(variant.domain()).withPath(variant.path()));
            if (termScenario) {
                termRunner.start();
            }
            int op = 0;
            for (ScenarioOp step : scenario.ops()) {
                op++;
                switch (step) {
                    case ScenarioOp.CreateSite create -> {
                        try {
                            SiteInfo site = sites.create(NewSite.of(create.domain(), create.path(), create.title()));
                            created.add(site.id());
                            outcome.created(op, created.size());
                        } catch (IllegalArgumentException refused) {
                            created.add(null);
                            outcome.refused(op, Refusals.ofQuarkPress(refused.getMessage()));
                        } catch (RuntimeException failed) {
                            // the insert may have happened before the failure: cleanup removes the site by address
                            leaked.add(create);
                            throw failed;
                        }
                    }
                    case ScenarioOp.Lookup lookup -> {
                        Binding binding = resolution
                                .route(new SiteRequest("http", lookup.host(), -1, lookup.path()))
                                .binding();
                        outcome.lookedUp(op, nameOf(binding, created));
                    }
                    case ScenarioOp.SetFlag flag -> {
                        SiteId id = created.get(flag.site() - 1);
                        if (id == null) {
                            outcome.flagSet(op, "missing");
                        } else {
                            try {
                                if (flag.flag() == ScenarioOp.Flag.PUBLIC) {
                                    sites.setPublic(id, flag.value() ? 1 : 0);
                                } else {
                                    sites.setFlag(
                                            id, SiteFlag.valueOf(flag.flag().name()), flag.value());
                                }
                                outcome.flagSet(op, "ok");
                            } catch (IllegalArgumentException refused) {
                                outcome.flagSet(op, "failed");
                            }
                        }
                    }
                    case ScenarioOp.ReadOptions read -> {
                        SiteId id = read.site() == 0 ? MAIN : created.get(read.site() - 1);
                        for (String name : read.names()) {
                            if (id == null) {
                                outcome.option(op, name, null);
                                continue;
                            }
                            Optional<JsonNode> value = scope.call(id, () -> store.find(id, name));
                            String text = value.map(node -> node.isTextual() ? node.textValue() : node.toString())
                                    .orElse(null);
                            if (text != null && name.equals("blogname")) {
                                text = Outcome.title(text, id.value(), read.site());
                            }
                            if (text != null && postScenario) {
                                text = postRunner.optionText(name, text);
                            }
                            outcome.option(op, name, text);
                        }
                    }
                    case ScenarioOp.CreateUser user -> runCapabilityOp(op, user, outcome, created, users);
                    case ScenarioOp.AddUser add -> runCapabilityOp(op, add, outcome, created, users);
                    case ScenarioOp.RemoveUser remove -> runCapabilityOp(op, remove, outcome, created, users);
                    case ScenarioOp.SetRole set -> runCapabilityOp(op, set, outcome, created, users);
                    case ScenarioOp.AddCap cap -> runCapabilityOp(op, cap, outcome, created, users);
                    case ScenarioOp.SuperAdmin admin -> runCapabilityOp(op, admin, outcome, created, users);
                    case ScenarioOp.NetworkFlag flag -> runCapabilityOp(op, flag, outcome, created, users);
                    case ScenarioOp.SiteSetting setting -> runCapabilityOp(op, setting, outcome, created, users);
                    case ScenarioOp.AddRole role -> runCapabilityOp(op, role, outcome, created, users);
                    case ScenarioOp.RoleCap role -> runCapabilityOp(op, role, outcome, created, users);
                    case ScenarioOp.RemoveRole role -> runCapabilityOp(op, role, outcome, created, users);
                    case ScenarioOp.Can can -> runCapabilityOp(op, can, outcome, created, users);
                    case ScenarioOp.PostOp post -> postRunner.run(op, post, outcome, users);
                }
            }
            for (int index = 1; index <= created.size(); index++) {
                SiteId id = created.get(index - 1);
                if (id == null) {
                    continue;
                }
                SiteInfo site = sites.find(id).orElseThrow();
                outcome.site(
                        index,
                        site.domain(),
                        site.path(),
                        site.publicFlag(),
                        site.archived(),
                        site.mature(),
                        site.spam(),
                        site.deleted());
            }
            if (postScenario) {
                postRunner.record(outcome, users, installer[0].id());
            }
            if (termScenario) {
                termRunner.record(outcome);
            }
        } catch (Throwable t) {
            failure = t;
        }
        // Every cleanup step runs, whatever failed before: the original error is kept, later ones are suppressed.
        failure = attempt(failure, postRunner::cleanUp);
        // posts first, so the counts are back to zero before the terms go
        if (termScenario) {
            failure = attempt(failure, termRunner::cleanUp);
        }
        for (UserId user : users) {
            failure = attempt(failure, () -> deleteProfile(user));
        }
        if (installer[0] != null) {
            failure = attempt(failure, () -> deleteProfile(installer[0].id()));
        }
        failure = attempt(failure, () -> networkOptions.delete("add_new_users"));
        failure = attempt(failure, () -> networkOptions.delete("menu_items"));
        failure = attempt(
                failure,
                () -> scope.run(MAIN, () -> {
                    options.delete("link_manager_enabled");
                    // the stored role table is the security module's state, which Options refuses to write
                    optionWrites.delete(MAIN, SiteRoleTables.OPTION);
                }));
        for (int index = created.size() - 1; index >= 0; index--) {
            SiteId id = created.get(index);
            if (id != null) {
                failure = attempt(failure, () -> sites.delete(id));
            }
        }
        for (ScenarioOp.CreateSite site : leaked) {
            failure = attempt(failure, () -> removeByAddress(site));
        }
        failure = attempt(
                failure,
                () -> sites.update(
                        MAIN, SiteChanges.none().withDomain(MAIN_DOMAIN).withPath(MAIN_PATH)));
        failure = attempt(failure, () -> assertMainAddress("after the scenario " + scenario.caseId()));
        if (failure != null) {
            if (failure instanceof Exception e) {
                throw e;
            }
            throw (Error) failure;
        }
        return outcome;
    }

    /**
     * Removes a profile and what hangs on it: memberships on every site, super admin status. The foreign keys cascade
     * to both anyway; the explicit calls go through the services so their events and locks run, and are
     * belt-and-braces.
     */
    private void deleteProfile(UserId user) {
        QuarkusTransaction.requiringNew().run(() -> {
            memberships.sitesOf(user).forEach(membership -> memberships.remove(membership.site(), user));
            memberships.revokeSuperAdmin(user);
            profileRows.deleteById(user.value());
        });
    }

    /** Runs a user, role, option or capability operation on QuarkPress, recording what WordPress's side records. */
    private void runCapabilityOp(int op, ScenarioOp step, Outcome outcome, List<SiteId> created, List<UserId> users) {
        switch (step) {
            case ScenarioOp.CreateUser user -> {
                UserProfile profile = provisioner.provision(new ProfileClaims(
                        "tierb-" + UUID.randomUUID(),
                        Optional.of(user.username()),
                        nonEmpty(user.givenName()),
                        nonEmpty(user.familyName())));
                users.add(profile.id());
                outcome.userCreated(
                        op,
                        Outcome.nicename(profile.nicename(), profile.id().value(), users.size()),
                        profile.displayName());
            }
            case ScenarioOp.AddUser add ->
                outcome.done(op, add.kind(), onSite(created, add.site(), site -> {
                    UserId user = users.get(add.user() - 1);
                    memberships.add(site, user, add.role());
                    return stored(memberships.find(site, user), add.role());
                }));
            case ScenarioOp.RemoveUser remove ->
                outcome.done(op, remove.kind(), onSite(created, remove.site(), site -> {
                    UserId user = users.get(remove.user() - 1);
                    memberships.remove(site, user);
                    return memberships.find(site, user).isEmpty() ? "ok" : "failed";
                }));
            case ScenarioOp.SetRole set ->
                outcome.done(op, set.kind(), onSite(created, set.site(), site -> {
                    UserId user = users.get(set.user() - 1);
                    memberships.setRole(site, user, set.role());
                    return stored(memberships.find(site, user), set.role());
                }));
            case ScenarioOp.AddCap cap ->
                outcome.done(op, cap.kind(), onSite(created, cap.site(), site -> {
                    UserId user = users.get(cap.user() - 1);
                    memberships.setCapability(site, user, cap.capability(), cap.grant());
                    Boolean held = memberships
                            .find(site, user)
                            .map(membership -> membership.capabilities().get(cap.capability()))
                            .orElse(null);
                    return Boolean.valueOf(cap.grant()).equals(held) ? "ok" : "failed";
                }));
            case ScenarioOp.SuperAdmin admin -> {
                UserId user = users.get(admin.user() - 1);
                boolean changed =
                        admin.grant() ? memberships.grantSuperAdmin(user) : memberships.revokeSuperAdmin(user);
                outcome.done(op, admin.kind(), "changed=" + changed);
            }
            case ScenarioOp.NetworkFlag flag -> {
                if (flag.name().equals("add_new_users")) {
                    networkOptions.set("add_new_users", TextNode.valueOf(flag.value() ? "1" : "0"));
                } else {
                    networkOptions.set(
                            "menu_items",
                            flag.value()
                                    ? JsonNodeFactory.instance.objectNode().put("plugins", "1")
                                    : JsonNodeFactory.instance.objectNode());
                }
                outcome.done(op, flag.kind(), "ok");
            }
            case ScenarioOp.SiteSetting setting ->
                outcome.done(op, setting.kind(), onSite(created, setting.site(), site -> {
                    scope.run(site, () -> options.set(setting.name(), IntNode.valueOf(setting.value() ? 1 : 0)));
                    return "ok";
                }));
            case ScenarioOp.AddRole role ->
                outcome.done(op, role.kind(), onSite(created, role.site(), site -> {
                    Map<String, Boolean> grants = new LinkedHashMap<>();
                    role.capabilities().forEach(capability -> grants.put(capability, true));
                    return "changed="
                            + siteRoles
                                    .add(site, role.role(), role.name(), grants)
                                    .isPresent();
                }));
            case ScenarioOp.RoleCap role ->
                outcome.done(
                        op,
                        role.kind(),
                        onSite(
                                created,
                                role.site(),
                                site -> siteRoles.setCapability(site, role.role(), role.capability(), role.grant())
                                        ? "ok"
                                        : "failed"));
            case ScenarioOp.RemoveRole role ->
                outcome.done(op, role.kind(), onSite(created, role.site(), site -> {
                    siteRoles.remove(site, role.role());
                    return siteRoles.role(site, role.role()).isEmpty() ? "ok" : "failed";
                }));
            case ScenarioOp.Can can -> {
                SiteId site = can.site() == 0 ? MAIN : created.get(can.site() - 1);
                if (site == null) {
                    outcome.done(op, can.kind(), "missing");
                    break;
                }
                Optional<CapObject> target =
                        can.target() == 0 ? Optional.empty() : Optional.of(new UserTarget(users.get(can.target() - 1)));
                boolean answer = can.user() == 0
                        ? capabilities.anonymousCan(site, can.capability(), target)
                        : capabilities.userCan(users.get(can.user() - 1), site, can.capability(), target);
                outcome.answer(op, answer);
            }
            case ScenarioOp.CreateSite create -> throw notCapability(create);
            case ScenarioOp.Lookup lookup -> throw notCapability(lookup);
            case ScenarioOp.SetFlag flag -> throw notCapability(flag);
            case ScenarioOp.ReadOptions read -> throw notCapability(read);
            case ScenarioOp.PostOp post -> throw notCapability(post);
        }
    }

    /** {@code missing} for a site that was not created, {@code failed} when QuarkPress refuses the change. */
    private static String onSite(List<SiteId> created, int index, Function<SiteId, String> work) {
        SiteId site = index == 0 ? MAIN : created.get(index - 1);
        if (site == null) {
            return "missing";
        }
        try {
            return work.apply(site);
        } catch (IllegalArgumentException refused) {
            return "failed";
        }
    }

    /** {@code ok} when the membership holds exactly {@code role} (none for an empty role), as the driver reads back. */
    private static String stored(Optional<Membership> membership, String role) {
        List<String> expected = role.isEmpty() ? List.of() : List.of(role);
        return membership.isPresent() && membership.get().roles().equals(expected) ? "ok" : "failed";
    }

    private static IllegalStateException notCapability(ScenarioOp step) {
        return new IllegalStateException("not a user or capability operation: " + step.kind());
    }

    private static Optional<String> nonEmpty(String text) {
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** Runs {@code step}; returns {@code failure}, or the step's error when nothing failed before (else suppressed). */
    private static Throwable attempt(Throwable failure, ThrowingStep step) {
        try {
            step.run();
            return failure;
        } catch (Throwable t) {
            if (failure == null) {
                return t;
            }
            failure.addSuppressed(t);
            return failure;
        }
    }

    /** Deletes the site at the address a failed create was aiming for, if the failure left one behind. */
    private void removeByAddress(ScenarioOp.CreateSite site) {
        String path = site.path().toLowerCase(java.util.Locale.ROOT);
        path = (path.startsWith("/") ? path : "/" + path) + (path.endsWith("/") ? "" : "/");
        sites.findByAddress(site.domain().toLowerCase(java.util.Locale.ROOT), path)
                .filter(found -> !found.id().equals(MAIN))
                .ifPresent(found -> sites.delete(found.id()));
    }

    private void assertMainAddress(String when) {
        SiteInfo main = sites.find(MAIN).orElseThrow();
        if (!main.domain().equals(MAIN_DOMAIN) || !main.path().equals(MAIN_PATH)) {
            throw new AssertionError("the main site is at " + main.domain() + main.path() + " " + when
                    + ", not at its original address " + MAIN_DOMAIN + MAIN_PATH);
        }
    }

    @FunctionalInterface
    private interface ThrowingStep {
        void run() throws Exception;
    }

    private static String nameOf(Binding binding, List<SiteId> created) {
        if (binding.kind() != Binding.Kind.SITE) {
            return Outcome.NONE;
        }
        if (binding.site().equals(MAIN)) {
            return Outcome.siteName(0);
        }
        int index = created.indexOf(binding.site());
        return index < 0 ? "#?" + binding.site().value() : Outcome.siteName(index + 1);
    }
}
