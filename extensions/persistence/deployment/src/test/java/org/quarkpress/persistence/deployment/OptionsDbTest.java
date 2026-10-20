package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.NetworkOptionChanged;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteStatus;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.JsonValues;

class OptionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(OptionsDbTest.class, EventLog.class, FailingObserver.class);

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    // Built from code points: the formatter turns printable escapes into literal characters.
    private static final String GRINNING_FACE = Character.toString(0x1F600);
    private static final char E_ACUTE = (char) 0xE9;

    @ApplicationScoped
    public static class EventLog {
        private final List<OptionChanged> site = new CopyOnWriteArrayList<>();
        private final List<NetworkOptionChanged> network = new CopyOnWriteArrayList<>();

        void onSite(@Observes OptionChanged event) {
            site.add(event);
        }

        void onNetwork(@Observes NetworkOptionChanged event) {
            network.add(event);
        }

        public List<OptionChanged> site(SiteId siteId, String name) {
            return site.stream()
                    .filter(e -> e.site().equals(siteId) && e.name().equals(name))
                    .toList();
        }

        public List<NetworkOptionChanged> network(String name) {
            return network.stream().filter(e -> e.name().equals(name)).toList();
        }
    }

    /** Throws from inside the write for option names that ask for it. */
    @ApplicationScoped
    public static class FailingObserver {

        void onSite(@Observes OptionChanged event) {
            fail(event.name());
        }

        void onNetwork(@Observes NetworkOptionChanged event) {
            fail(event.name());
        }

        private static void fail(String name) {
            if (name.startsWith("boom-iae")) {
                throw new IllegalArgumentException("observer refused " + name);
            }
            if (name.startsWith("boom-ise")) {
                throw new IllegalStateException("observer failed on " + name);
            }
        }
    }

    @Inject
    Options options;

    @Inject
    UserTransaction userTransaction;

    @Inject
    NetworkOptions network;

    @Inject
    OptionStore store;

    @Inject
    SiteScope scope;

    @Inject
    SiteContext context;

    @Inject
    SiteBinding binding;

    @Inject
    TestSites sites;

    @Inject
    EventLog events;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void withoutMultisiteTheMainSiteIsCurrent() {
        Requests.fresh(() -> {
            assertThat(context.isResolved()).isTrue();
            assertThat(context.siteId()).isEqualTo(SiteId.of(1));
            assertThat(context.site().domain()).isEqualTo("localhost");
            assertThat(context.site().status()).isEqualTo(SiteStatus.ACTIVE);
        });
    }

    @Test
    void setReadAndDelete() {
        SiteId site = sites.create("opts");
        scope.run(site, () -> {
            assertThat(options.set("color", TextNode.valueOf("blue"))).isTrue();
            assertThat(options.set("color", TextNode.valueOf("blue"))).isFalse();
            assertThat(context.options().string("color", "")).isEqualTo("blue");
            assertThat(options.set("color", TextNode.valueOf("red"))).isTrue();
            assertThat(context.options().string("color", "")).isEqualTo("red");
            assertThat(options.delete("color")).isTrue();
            assertThat(options.delete("color")).isFalse();
            assertThat(context.options().get("color")).isEmpty();
        });
        List<OptionChanged> changes = events.site(site, "color");
        assertThat(changes).hasSize(3);
        assertThat(changes.get(0).before().isMissingNode()).isTrue();
        assertThat(changes.get(0).after()).isEqualTo(TextNode.valueOf("blue"));
        assertThat(changes.get(1).before()).isEqualTo(TextNode.valueOf("blue"));
        assertThat(changes.get(1).after()).isEqualTo(TextNode.valueOf("red"));
        assertThat(changes.get(2).before()).isEqualTo(TextNode.valueOf("red"));
        assertThat(changes.get(2).after().isMissingNode()).isTrue();
    }

    @Test
    void numbersCompareByValue() {
        SiteId site = sites.create("numbers");
        scope.run(site, () -> {
            assertThat(options.set("n", IntNode.valueOf(1))).isTrue();
            assertThat(options.set("n", LongNode.valueOf(1))).isFalse();
            assertThat(options.set("n", DoubleNode.valueOf(1.0))).isFalse();
        });
        scope.run(
                site,
                () -> assertThat(options.set("n", DoubleNode.valueOf(1.0))).isFalse());
        assertThat(events.site(site, "n")).hasSize(1);
    }

    @Test
    void reorderingAnObjectsKeysIsAChange() {
        SiteId site = sites.create("order");
        JsonNode ab = F.objectNode().put("a", 1).put("b", 2);
        JsonNode ba = F.objectNode().put("b", 2).put("a", 1);
        scope.run(site, () -> assertThat(options.set("roles", ab)).isTrue());
        scope.run(site, () -> {
            assertThat(options.set("roles", ab)).isFalse();
            assertThat(options.set("roles", ba)).isTrue();
        });
        assertThat(stored(site, "roles"))
                .hasValueSatisfying(value -> assertThat(value.toString()).isEqualTo("{\"b\":2,\"a\":1}"));
        assertThat(events.site(site, "roles")).hasSize(2);
    }

    @Test
    void anUnchangedValueKeepsItsAutoloadFlagLikeUpdateOption() {
        SiteId site = sites.create("autoload");
        scope.run(site, () -> {
            assertThat(options.set("lazy", TextNode.valueOf("v"), false)).isTrue();
            assertThat(store.autoloaded(site)).doesNotContainKey("lazy");
            assertThat(options.set("lazy", TextNode.valueOf("v"), true)).isFalse();
            assertThat(store.autoloaded(site)).doesNotContainKey("lazy");
            assertThat(options.set("lazy", TextNode.valueOf("v"))).isFalse();
            assertThat(options.set("lazy", TextNode.valueOf("w"), true)).isTrue();
            assertThat(store.autoloaded(site)).containsEntry("lazy", TextNode.valueOf("w"));
            assertThat(options.set("lazy", TextNode.valueOf("x"))).isTrue();
            assertThat(store.autoloaded(site)).containsEntry("lazy", TextNode.valueOf("x"));
        });
        assertThat(events.site(site, "lazy")).hasSize(3);
    }

    @Test
    void setAutoloadChangesOnlyTheFlagLikeWpSetOptionAutoload() {
        SiteId site = sites.create("set-autoload");
        scope.run(site, () -> {
            assertThat(options.setAutoload("missing", false)).isFalse();
            assertThat(store.find(site, "missing")).isEmpty();
            options.set("flag", TextNode.valueOf("v"));
            assertThat(context.options().get("flag")).contains(TextNode.valueOf("v"));
            assertThat(options.setAutoload("flag", true)).isFalse();
            assertThat(options.setAutoload(" flag ", false)).isTrue();
            assertThat(store.autoloaded(site)).doesNotContainKey("flag");
            assertThat(options.setAutoload("flag", false)).isFalse();
            assertThat(context.options().get("flag")).contains(TextNode.valueOf("v"));
            assertThat(options.setAutoload("flag", true)).isTrue();
            assertThat(store.autoloaded(site)).containsEntry("flag", TextNode.valueOf("v"));
        });
        assertThat(events.site(site, "flag")).hasSize(1);
        assertThat(events.site(site, "missing")).isEmpty();
    }

    @Test
    void addNeverOverwritesLikeAddOption() {
        SiteId site = sites.create("add");
        scope.run(site, () -> {
            assertThat(options.add("added", TextNode.valueOf("first"), false)).isTrue();
            assertThat(store.autoloaded(site)).doesNotContainKey("added");
            assertThat(options.add(" added", TextNode.valueOf("second"), true)).isFalse();
            assertThat(store.find(site, "added")).contains(TextNode.valueOf("first"));
            assertThat(store.autoloaded(site)).doesNotContainKey("added");
            assertThatThrownBy(() -> options.add("0", TextNode.valueOf("x"), true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("option name is");
            assertThatThrownBy(() -> options.add("other", NullNode.getInstance(), true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JSON null");
        });
        List<OptionChanged> changes = events.site(site, "added");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).before().isMissingNode()).isTrue();
        assertThat(changes.get(0).after()).isEqualTo(TextNode.valueOf("first"));
    }

    @Test
    void anObserverFailureRollsTheWriteBackWithoutACallerTransaction() {
        SiteId site = sites.create("boom");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            assertThatThrownBy(() -> options.set("boom-iae-set", TextNode.valueOf("v")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("observer refused");
            assertThatThrownBy(() -> options.add("boom-ise-add", TextNode.valueOf("v"), true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("observer failed");
            assertThat(context.options().get("boom-iae-set")).isEmpty();
            assertThat(context.options().get("boom-ise-add")).isEmpty();
        });
        assertThat(stored(site, "boom-iae-set")).isEmpty();
        assertThat(stored(site, "boom-ise-add")).isEmpty();
    }

    @Test
    void anObserverFailureRollsBackTheCallersTransaction() {
        SiteId site = sites.create("boom-tx");
        String kept = "kept-" + UUID.randomUUID();
        for (String boom : List.of("boom-iae-tx", "boom-ise-tx")) {
            assertThatThrownBy(() -> scope.run(site, () -> {
                        options.set(kept, TextNode.valueOf("v"));
                        assertThatThrownBy(() -> options.set(boom, TextNode.valueOf("v")))
                                .isInstanceOf(RuntimeException.class)
                                .hasMessageContaining(boom);
                    }))
                    .satisfies(failure -> assertThat(Failures.messages(failure)).containsIgnoringCase("rollback"));
            assertThat(stored(site, kept)).isEmpty();
            assertThat(stored(site, boom)).isEmpty();
        }
    }

    @Test
    void anObserverFailureRollsBackANetworkWrite() {
        String iae = "boom-iae-net-" + UUID.randomUUID();
        String ise = "boom-ise-net-" + UUID.randomUUID();
        String kept = "qp_test_kept_" + UUID.randomUUID();
        Requests.fresh(() -> {
            assertThatThrownBy(() -> network.set(iae, TextNode.valueOf("v")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("observer refused");
            assertThatThrownBy(() -> network.set(ise, TextNode.valueOf("v")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("observer failed");
            assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                        network.set(kept, TextNode.valueOf("v"));
                        assertThatThrownBy(() -> network.set(iae, TextNode.valueOf("v")))
                                .isInstanceOf(IllegalArgumentException.class);
                    }))
                    .satisfies(failure -> assertThat(Failures.messages(failure)).containsIgnoringCase("rollback"));
            assertThat(network.find(iae)).isEmpty();
            assertThat(network.find(ise)).isEmpty();
            assertThat(network.find(kept)).isEmpty();
        });
    }

    @Test
    void aRollbackDropsTheRequestsCachedValue() {
        SiteId site = sites.create("rollback-cache");
        for (boolean autoload : new boolean[] {true, false}) {
            String name = "rolled-" + autoload + "-" + UUID.randomUUID();
            Requests.fresh(() -> {
                binding.bind(Binding.site(site));
                assertThat(options.set(name, TextNode.valueOf("old"), autoload)).isTrue();
                assertThat(context.options().get(name)).contains(TextNode.valueOf("old"));
                try {
                    userTransaction.begin();
                    assertThat(options.set(name, TextNode.valueOf("new"))).isTrue();
                    assertThat(context.options().get(name)).contains(TextNode.valueOf("new"));
                    userTransaction.rollback();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                assertThat(context.options().get(name)).contains(TextNode.valueOf("old"));
            });
            assertThat(stored(site, name)).contains(TextNode.valueOf("old"));
        }
    }

    @Test
    void aCommitDropsWhatAnotherThreadOfTheRequestCachedMeanwhile() {
        SiteId site = sites.create("commit-cache");
        for (boolean autoload : new boolean[] {true, false}) {
            String name = "committed-" + autoload + "-" + UUID.randomUUID();
            Requests.fresh(() -> {
                binding.bind(Binding.site(site));
                assertThat(options.set(name, TextNode.valueOf("old"), autoload)).isTrue();
                try {
                    userTransaction.begin();
                    assertThat(options.set(name, TextNode.valueOf("new"))).isTrue();
                    // Another thread of the request reads in a transaction of its own and caches the committed value.
                    Optional<JsonNode> seen =
                            Requests.onSharedContext(() -> context.options().get(name));
                    assertThat(seen).contains(TextNode.valueOf("old"));
                    userTransaction.commit();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                assertThat(context.options().get(name)).contains(TextNode.valueOf("new"));
            });
        }
    }

    @Test
    void aStoredValueTooDeepToCompareCanBeOverwritten() {
        SiteId site = sites.create("too-deep");
        String name = "deep-" + UUID.randomUUID();
        String deep = "[".repeat(70) + "\"leaf\"" + "]".repeat(70);
        String parameter = TestDb.kind().equals("postgresql") ? "cast(? as json)" : "?";
        Jdbc.update(
                dataSource,
                "insert into qp_option (site_id, name, value) values (?, ?, " + parameter + ")",
                site.value(),
                name,
                deep);
        scope.run(site, () -> assertThat(options.set(name, nested(64))).isTrue());
        assertThat(stored(site, name)).contains(nested(64));
        assertThat(events.site(site, name)).hasSize(1);
    }

    @Test
    void aRequestBoundToOneSiteReadsNothingOfAnother() {
        SiteId a = sites.create("iso-a");
        SiteId b = sites.create("iso-b");
        String name = "secret-" + UUID.randomUUID();
        scope.run(b, () -> options.set(name, TextNode.valueOf("b only")));
        Requests.fresh(() -> {
            binding.bind(Binding.site(a));
            assertThat(store.find(b, name)).isEmpty();
            assertThat(store.autoloaded(b)).isEmpty();
            assertThat(context.options().get(name)).isEmpty();
        });
        assertThat(stored(b, name)).contains(TextNode.valueOf("b only"));
    }

    @Test
    void namesAtTheColumnLimitWorkOnBothDatabases() {
        SiteId site = sites.create("names");
        String longest = GRINNING_FACE.repeat(191);
        scope.run(site, () -> {
            assertThat(options.set(longest, TextNode.valueOf("ok"))).isTrue();
            assertThat(options.set(" padded\n", TextNode.valueOf("p"))).isTrue();
            assertThat(options.set("padded", TextNode.valueOf("p"))).isFalse();
            assertThatThrownBy(() -> options.set(GRINNING_FACE.repeat(192), TextNode.valueOf("no")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("longer than 191");
        });
        assertThat(stored(site, longest)).contains(TextNode.valueOf("ok"));
        assertThat(stored(site, "padded")).contains(TextNode.valueOf("p"));
    }

    @Test
    void emptyNamesAndZeroAreRefusedLikeWordPress() {
        SiteId site = sites.create("zero");
        scope.run(site, () -> {
            for (String name : new String[] {"0", " 0\n", "", " \t"}) {
                assertThatThrownBy(() -> options.set(name, TextNode.valueOf("x")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("option name is");
                assertThatThrownBy(() -> options.delete(name))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("option name is");
            }
        });
        assertThat(events.site(site, "0")).isEmpty();
    }

    @Test
    void namesStartingWithQpAreReservedForQuarkPressModules() {
        // a module's own state (a site's role table, its feature switches) is no option a caller may write
        SiteId site = sites.create("reserved");
        Jdbc.update(
                dataSource,
                "insert into qp_option (site_id, name, value) values (?, 'qp_user_roles', '[]')",
                site.value());
        scope.run(site, () -> {
            for (String name : new String[] {"qp_user_roles", " qp_user_roles\n", "qp_features", "qp_new"}) {
                List<Callable<Boolean>> writes = List.of(
                        () -> options.set(name, TextNode.valueOf("x")),
                        () -> options.set(name, TextNode.valueOf("x"), false),
                        () -> options.add(name, TextNode.valueOf("x"), true),
                        () -> options.setAutoload(name, false),
                        () -> options.delete(name));
                for (Callable<Boolean> write : writes) {
                    assertThatThrownBy(write::call)
                            .as(name)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("option names starting with qp_ are reserved for QuarkPress's modules: "
                                    + name.strip());
                }
            }
        });
        assertThat(stored(site, "qp_user_roles")).contains(JsonNodeFactory.instance.arrayNode());
        assertThat(stored(site, "qp_new")).isEmpty();
        assertThat(events.site(site, "qp_user_roles")).isEmpty();
        // other names are as before, and so is a name that only contains qp_
        scope.run(
                site,
                () -> assertThat(options.set("my_qp_setting", TextNode.valueOf("x")))
                        .isTrue());
    }

    @Test
    void valuesRoundTripTheSameOnBothDatabases() {
        SiteId site = sites.create("values");
        List<JsonNode> accepted = List.of(
                LongNode.valueOf(Long.MAX_VALUE),
                LongNode.valueOf(Long.MIN_VALUE),
                LongNode.valueOf(9007199254740993L),
                DoubleNode.valueOf(0.1),
                DoubleNode.valueOf(-1.5e300),
                TextNode.valueOf("caf" + E_ACUTE + " " + GRINNING_FACE),
                TextNode.valueOf(""),
                TextNode.valueOf("a\u0000b"),
                F.objectNode().put("k\u0000", 1),
                F.booleanNode(true),
                F.arrayNode(),
                F.objectNode(),
                F.objectNode().set("cl" + E_ACUTE, F.arrayNode().add(1).add("two")),
                F.objectNode().put("z", 1).put("a", 2).put("m", 3),
                nested(64));
        for (int i = 0; i < accepted.size(); i++) {
            String name = "accepted-" + i;
            JsonNode value = accepted.get(i);
            scope.run(site, () -> options.set(name, value));
            JsonNode checked = JsonValues.requireStorable(value);
            assertThat(stored(site, name)).hasValueSatisfying(stored -> {
                assertThat(JsonValues.sameValue(stored, value))
                        .as("%s round trip", value)
                        .isTrue();
                assertThat(stored).as("%s reads back as stored", value).isEqualTo(checked);
                assertThat(stored.toString())
                        .as("%s keeps its key order", value)
                        .isEqualTo(checked.toString());
            });
        }
        List<JsonNode> rejected = List.of(
                NullNode.getInstance(),
                TextNode.valueOf("\uD800"),
                F.objectNode().put("k\uDC00", 1),
                BigIntegerNode.valueOf(BigInteger.TWO.pow(64)),
                nested(65));
        for (int i = 0; i < rejected.size(); i++) {
            String name = "rejected-" + i;
            JsonNode value = rejected.get(i);
            assertThatThrownBy(() -> scope.run(site, () -> options.set(name, value)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(stored(site, name)).isEmpty();
        }
        assertThat(events.site(site, "rejected-0")).isEmpty();
    }

    @Test
    void aStoredJsonNullReadsAsNullNode() {
        SiteId site = sites.create("json-null");
        String name = "null-" + UUID.randomUUID();
        Jdbc.update(
                dataSource, "insert into qp_option (site_id, name, value) values (?, ?, 'null')", site.value(), name);
        assertThat(stored(site, name)).contains(NullNode.getInstance());
        scope.run(site, () -> {
            assertThat(store.autoloaded(site)).containsEntry(name, NullNode.getInstance());
            assertThat(context.options().get(name)).contains(NullNode.getInstance());
            assertThat(options.set(name, TextNode.valueOf("now set"))).isTrue();
        });
        List<OptionChanged> changes = events.site(site, name);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).before()).isEqualTo(NullNode.getInstance());
        String networkName = "qp_test_null_" + UUID.randomUUID();
        Jdbc.update(dataSource, "insert into qp_network_option (name, value) values (?, 'null')", networkName);
        Requests.fresh(() -> assertThat(network.find(networkName)).contains(NullNode.getInstance()));
    }

    @Test
    void networkOptionsAreSharedBySites() {
        String name = "qp_test_" + UUID.randomUUID();
        Requests.fresh(() -> {
            assertThat(network.find("qp_feature_defaults")).contains(F.objectNode());
            assertThat(network.set(name, TextNode.valueOf("everywhere"))).isTrue();
            assertThat(network.set(" " + name, TextNode.valueOf("everywhere"))).isFalse();
            assertThat(network.set(name, DoubleNode.valueOf(2.0))).isTrue();
            assertThat(network.set(name, IntNode.valueOf(2))).isFalse();
            assertThat(network.find(name)).contains(JsonValues.requireStorable(DoubleNode.valueOf(2.0)));
            assertThatThrownBy(() -> network.set(name, NullNode.getInstance()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JSON null");
            assertThatThrownBy(() -> network.find("0"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("option name is");
            assertThat(network.delete(name)).isTrue();
            assertThat(network.delete(name)).isFalse();
            assertThat(network.find(name)).isEmpty();
        });
        List<NetworkOptionChanged> changes = events.network(name);
        assertThat(changes).hasSize(3);
        assertThat(changes.get(0).before().isMissingNode()).isTrue();
        assertThat(changes.get(1).before()).isEqualTo(TextNode.valueOf("everywhere"));
        assertThat(changes.get(2).after().isMissingNode()).isTrue();
        for (SiteId site : List.of(sites.create("net-a"), sites.create("net-b"))) {
            String shared = "qp_test_shared_" + UUID.randomUUID();
            Requests.fresh(() -> network.set(shared, TextNode.valueOf("v")));
            assertThat(scopeCall(site, () -> network.find(shared))).contains(TextNode.valueOf("v"));
        }
    }

    @Test
    void aReadOutsideATransactionSeesWritesOfTheSameRequest() {
        SiteId site = sites.create("fresh-read");
        String name = "fresh-" + UUID.randomUUID();
        scope.run(site, () -> options.set(name, TextNode.valueOf("a")));
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            assertThat(store.find(site, name)).contains(TextNode.valueOf("a"));
            scope.run(site, () -> options.set(name, TextNode.valueOf("b")));
            assertThat(store.find(site, name)).contains(TextNode.valueOf("b"));
            assertThat(context.options().get(name)).contains(TextNode.valueOf("b"));
        });
    }

    @Test
    void readsReturnCopies() {
        SiteId site = sites.create("copies");
        scope.run(site, () -> {
            options.set("obj", F.objectNode().put("a", 1));
            ((ObjectNode) store.find(site, "obj").orElseThrow()).put("a", 2);
            ((ObjectNode) store.autoloaded(site).get("obj")).put("a", 3);
            assertThat(store.find(site, "obj")).contains(F.objectNode().put("a", 1));
            assertThat(options.set("obj", F.objectNode().put("a", 1))).isFalse();
        });
    }

    @Test
    void aCaughtValidationErrorKeepsTheCallersTransaction() {
        SiteId site = sites.create("keep-tx");
        String name = "keep-" + UUID.randomUUID();
        scope.run(site, () -> {
            options.set(name, TextNode.valueOf("kept"));
            assertThatThrownBy(() -> options.set("bad", NullNode.getInstance()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> options.delete("0")).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(stored(site, name)).contains(TextNode.valueOf("kept"));
    }

    @Test
    void writesNeedASite() {
        Requests.fresh(() -> {
            binding.bind(Binding.NETWORK);
            assertThatThrownBy(() -> options.set("x", TextNode.valueOf("y")))
                    .isInstanceOf(SiteNotResolvedException.class);
            assertThatThrownBy(() -> options.delete("x")).isInstanceOf(SiteNotResolvedException.class);
        });
    }

    @Test
    void theSiteZoneFollowsItsOptions() {
        SiteId site = sites.create("zone");
        scope.run(site, () -> {
            assertThat(context.zone()).isEqualTo(ZoneOffset.UTC);
            options.set("gmt_offset", DoubleNode.valueOf(5.5));
            assertThat(context.zone()).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
        });
        scope.run(site, () -> assertThat(context.zone()).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30)));
    }

    /** Reads straight from the database (no request cache), in a transaction bound to {@code site}. */
    private Optional<JsonNode> stored(SiteId site, String name) {
        return scopeCall(site, () -> store.find(site, name));
    }

    private <T> T scopeCall(SiteId site, Callable<T> work) {
        try {
            return scope.call(site, work);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JsonNode nested(int depth) {
        JsonNode value = TextNode.valueOf("leaf");
        for (int i = 0; i < depth; i++) {
            value = F.arrayNode().add(value);
        }
        return value;
    }
}
