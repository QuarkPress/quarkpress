package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.runtime.NetworkOptionEntity;
import org.quarkpress.persistence.runtime.NetworkOptionRepository;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;

/** Option names and values behave the same on PostgreSQL and MySQL. */
class OptionValuesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(OptionValuesDbTest.class);

    private static final Instant AT = Instant.parse("2026-10-17T12:00:00Z");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    OptionRepository options;

    @Inject
    NetworkOptionRepository networkOptions;

    @Inject
    SiteBinding binding;

    @Inject
    TestSites sites;

    @Test
    void namesWithSurroundingWhitespaceAreRefusedBeforeAnyQuery() {
        SiteId site = sites.create("names");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> options.persist(OptionEntity.create(site, "foo", TextNode.valueOf("x"), true, AT)));
            QuarkusTransaction.requiringNew().run(() -> {
                assertThat(options.findOption(site, "foo")).isPresent();
                for (String name : new String[] {"foo ", " foo", "foo\t", "\nfoo"}) {
                    assertThatThrownBy(() -> options.findOption(site, name))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("leading or trailing whitespace");
                }
            });
        });
        assertThatThrownBy(() -> OptionEntity.create(site, "foo ", TextNode.valueOf("y"), true, AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading or trailing whitespace");
        assertThatThrownBy(() -> NetworkOptionEntity.create(" foo", TextNode.valueOf("y"), AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading or trailing whitespace");
        Requests.fresh(() -> QuarkusTransaction.requiringNew().run(() -> {
            assertThat(networkOptions.findOption("qp_feature_defaults")).isPresent();
            assertThatThrownBy(() -> networkOptions.findOption("qp_feature_defaults "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("leading or trailing whitespace");
        }));
    }

    @Test
    void objectKeysKeepTheirOrder() {
        String text = "{\"zz\":1,\"a\":2,\"mmm\":3}";
        assertThat(roundTrip("order", Json.parse(text))).isEqualTo(text);
        String network = "qp_order_" + UUID.randomUUID();
        Requests.fresh(() -> QuarkusTransaction.requiringNew()
                .run(() -> networkOptions.persist(NetworkOptionEntity.create(network, Json.parse(text), AT))));
        Requests.fresh(() -> QuarkusTransaction.requiringNew()
                .run(() -> assertThat(networkOptions
                                .findOption(network)
                                .orElseThrow()
                                .value
                                .toString())
                        .isEqualTo(text)));
        assertThat(Jdbc.strings(dataSource, "select value from qp_network_option where name = ?", network))
                .containsExactly(text);
    }

    @Test
    void numbersReadBackExactly() {
        String text = "{\"d\":12345678901234567890.123456789,\"i\":123456789012345678901234567890,\"z\":1.50,"
                + "\"n\":42,\"l\":9007199254740993}";
        assertThat(roundTrip("numbers", Json.parse(text))).isEqualTo(text);
        SiteId site = sites.create("numbers-typed");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> options.persist(OptionEntity.create(site, "numbers", Json.parse(text), true, AT)));
            QuarkusTransaction.requiringNew().run(() -> {
                JsonNode value = options.findOption(site, "numbers").orElseThrow().value;
                assertThat(value.get("d").decimalValue()).isEqualTo(new BigDecimal("12345678901234567890.123456789"));
                assertThat(value.get("i").bigIntegerValue())
                        .isEqualTo(new BigInteger("123456789012345678901234567890"));
                assertThat(value.get("z").decimalValue()).isEqualTo(new BigDecimal("1.50"));
                assertThat(value.get("i").isBigInteger()).isTrue();
                assertThat(value.get("n")).isEqualTo(IntNode.valueOf(42));
                assertThat(value.get("l")).isEqualTo(LongNode.valueOf(9007199254740993L));
            });
        });
    }

    @Test
    void stringsMayContainNul() {
        String withNul = "a" + (char) 0 + "b";
        JsonNode value = JsonNodeFactory.instance.objectNode().put("s", withNul);
        String stored = roundTrip("nul", value);
        assertThat(Json.parse(stored).get("s").textValue()).isEqualTo(withNul);
        assertThat(stored).isEqualTo("{\"s\":\"a\\u0000b\"}");
    }

    @Test
    void unpairedSurrogatesAreRefused() {
        SiteId site = sites.create("surrogate");
        JsonNode value = JsonNodeFactory.instance.objectNode().put("s", "x" + (char) 0xD800 + "y");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            assertThatThrownBy(() -> QuarkusTransaction.requiringNew()
                            .run(() -> options.persist(OptionEntity.create(site, "surrogate", value, true, AT))))
                    .satisfies(failure -> assertThat(Failures.messages(failure)).contains("unpaired surrogate"));
        });
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_option where site_id = ?", site.value()))
                .isZero();
    }

    /** Stores {@code value} for a new site and returns the text read back through Hibernate, checked against the column. */
    private String roundTrip(String label, JsonNode value) {
        SiteId site = sites.create(label);
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> options.persist(OptionEntity.create(site, label, value, true, AT)));
        });
        String[] read = new String[1];
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew().run(() -> {
                JsonNode stored = options.findOption(site, label).orElseThrow().value;
                assertThat(stored).isEqualTo(value);
                read[0] = stored.toString();
            });
        });
        assertThat(Jdbc.strings(
                        dataSource, "select value from qp_option where site_id = ? and name = ?", site.value(), label))
                .containsExactly(read[0]);
        return read[0];
    }
}
