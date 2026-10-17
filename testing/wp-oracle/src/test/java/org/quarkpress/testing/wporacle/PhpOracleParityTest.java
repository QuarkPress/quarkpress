package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Exercises the oracle itself against the real php binary. Active only with {@code -Pwp-parity}. */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class PhpOracleParityTest {

    static final Path DRIVER = Path.of("src/test/php/echo-driver.php");

    private static Path wordpress;

    @BeforeAll
    static void sources() throws Exception {
        wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty());
    }

    private static ObjectNode valueCase(ObjectNode value) {
        ObjectNode in = OracleJson.MAPPER.createObjectNode();
        in.set("value", value);
        return in;
    }

    private static String serialized(Map<String, JsonNode> out, String id) {
        return new String(
                Base64.getDecoder().decode(out.get(id).get("serialize").asText()), StandardCharsets.ISO_8859_1);
    }

    @Test
    void typedValuesRoundTripThroughPhp() throws Exception {
        ObjectNode keys = PhpTree.array(List.of(
                new PhpTree.Entry(PhpTree.string("5"), PhpTree.bool(true)),
                new PhpTree.Entry(PhpTree.integer(-5), PhpTree.nul()),
                new PhpTree.Entry(PhpTree.append(), PhpTree.string("x"))));
        ObjectNode object =
                PhpTree.object("stdClass", List.of(new PhpTree.Entry(PhpTree.string("a"), PhpTree.integer(1))));
        List<PhpOracle.Case> cases = List.of(
                new PhpOracle.Case("int", valueCase(PhpTree.integer(Long.MIN_VALUE))),
                new PhpOracle.Case("float", valueCase(PhpTree.floating("0.1"))),
                new PhpOracle.Case("minus-zero", valueCase(PhpTree.floating("-0.0"))),
                new PhpOracle.Case("nan", valueCase(PhpTree.floating("NAN"))),
                new PhpOracle.Case("bytes", valueCase(PhpTree.string(new byte[] {0, (byte) 0xFF, '"'}))),
                new PhpOracle.Case("keys", valueCase(keys)),
                new PhpOracle.Case("object", valueCase(object)));

        Map<String, JsonNode> out = PhpOracle.create(wordpress).run(DRIVER, cases);

        assertThat(out.keySet()).containsExactly("int", "float", "minus-zero", "nan", "bytes", "keys", "object");
        assertThat(out.get("int").get("tree")).isEqualTo(PhpTree.integer(Long.MIN_VALUE));
        assertThat(serialized(out, "int")).isEqualTo("i:-9223372036854775808;");
        assertThat(out.get("float").get("tree")).isEqualTo(PhpTree.floating("0.1"));
        assertThat(out.get("minus-zero").get("tree")).isEqualTo(PhpTree.floating("-0"));
        assertThat(serialized(out, "nan")).isEqualTo("d:NAN;");
        assertThat(PhpTree.bytes(out.get("bytes").get("tree"))).containsExactly(0, 0xFF, '"');
        assertThat(out.get("keys").get("tree"))
                .isEqualTo(PhpTree.array(List.of(
                        new PhpTree.Entry(PhpTree.integer(5), PhpTree.bool(true)),
                        new PhpTree.Entry(PhpTree.integer(-5), PhpTree.nul()),
                        new PhpTree.Entry(PhpTree.integer(6), PhpTree.string("x")))));
        assertThat(serialized(out, "keys")).isEqualTo("a:3:{i:5;b:1;i:-5;N;i:6;s:1:\"x\";}");
        assertThat(serialized(out, "object")).isEqualTo("O:8:\"stdClass\":1:{s:1:\"a\";i:1;}");
        assertThat(out.values())
                .allSatisfy(o -> assertThat(o.get("hooks").booleanValue()).isTrue());
    }

    @Test
    void aCaseThatThrowsFailsTheRunWithTheLogPath() {
        ObjectNode in = OracleJson.MAPPER.createObjectNode().put("fail", "boom");
        assertThatThrownBy(() -> PhpOracle.create(wordpress).run(DRIVER, List.of(new PhpOracle.Case("x", in))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RuntimeException: boom")
                .hasMessageContaining("wp-oracle-echo-driver.log");
    }

    @Test
    void missingPhpFailsInsteadOfSkipping() {
        PhpOracle oracle = new PhpOracle(
                "/nonexistent/php-8.4", wordpress, Path.of("target").toAbsolutePath());
        ObjectNode in = valueCase(PhpTree.nul());
        assertThatThrownBy(() -> oracle.run(DRIVER, List.of(new PhpOracle.Case("x", in))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cannot start PHP");
    }

    @Test
    void aBrokenDriverFailsWithItsExitStatus() {
        ObjectNode in = valueCase(PhpTree.nul());
        assertThatThrownBy(() -> PhpOracle.create(wordpress)
                        .run(Path.of("src/test/php/no-such-driver.php"), List.of(new PhpOracle.Case("x", in))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exited with status");
    }

    @Test
    void duplicateIdsAreRejected() {
        ObjectNode in = valueCase(PhpTree.nul());
        assertThatThrownBy(() -> PhpOracle.create(wordpress)
                        .run(DRIVER, List.of(new PhpOracle.Case("x", in), new PhpOracle.Case("x", in))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate case id x");
    }

    @Test
    void environmentReachesTheDriver() throws Exception {
        ObjectNode in = OracleJson.MAPPER.createObjectNode().put("env", "QP_TEST_VALUE");
        Map<String, JsonNode> out = PhpOracle.create(wordpress)
                .withEnvironment("QP_TEST_VALUE", "hello")
                .run(DRIVER, List.of(new PhpOracle.Case("env", in)));
        assertThat(out.get("env").get("env").asText()).isEqualTo("hello");
    }
}
