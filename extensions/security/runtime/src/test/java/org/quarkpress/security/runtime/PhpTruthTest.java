package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PhpTruthTest {

    @Test
    void falsyValuesAreThoseOfPhp() {
        assertThat(PhpTruth.truthy(null)).isFalse();
        assertThat(PhpTruth.truthy(MissingNode.getInstance())).isFalse();
        assertThat(PhpTruth.truthy(NullNode.getInstance())).isFalse();
        assertThat(PhpTruth.truthy(BooleanNode.FALSE)).isFalse();
        assertThat(PhpTruth.truthy(IntNode.valueOf(0))).isFalse();
        assertThat(PhpTruth.truthy(DecimalNode.valueOf(new BigDecimal("0.00")))).isFalse();
        assertThat(PhpTruth.truthy(TextNode.valueOf(""))).isFalse();
        assertThat(PhpTruth.truthy(TextNode.valueOf("0"))).isFalse();
        assertThat(PhpTruth.truthy(JsonNodeFactory.instance.arrayNode())).isFalse();
        assertThat(PhpTruth.truthy(JsonNodeFactory.instance.objectNode())).isFalse();
    }

    @Test
    void everythingElseIsTruthy() {
        assertThat(PhpTruth.truthy(BooleanNode.TRUE)).isTrue();
        assertThat(PhpTruth.truthy(IntNode.valueOf(-1))).isTrue();
        assertThat(PhpTruth.truthy(TextNode.valueOf("0.0"))).isTrue();
        assertThat(PhpTruth.truthy(TextNode.valueOf(" "))).isTrue();
        assertThat(PhpTruth.truthy(JsonNodeFactory.instance.arrayNode().add(0))).isTrue();
    }

    @Test
    void integerCastsFollowPhp() {
        assertThat(PhpTruth.intValue(null)).isZero();
        assertThat(PhpTruth.intValue(IntNode.valueOf(12))).isEqualTo(12);
        assertThat(PhpTruth.intValue(DecimalNode.valueOf(new BigDecimal("12.9"))))
                .isEqualTo(12);
        assertThat(PhpTruth.intValue(DecimalNode.valueOf(new BigDecimal("-12.9"))))
                .isEqualTo(-12);
        assertThat(PhpTruth.intValue(TextNode.valueOf("12"))).isEqualTo(12);
        assertThat(PhpTruth.intValue(TextNode.valueOf(" 12abc"))).isEqualTo(12);
        assertThat(PhpTruth.intValue(TextNode.valueOf("-7"))).isEqualTo(-7);
        assertThat(PhpTruth.intValue(TextNode.valueOf("abc"))).isZero();
        assertThat(PhpTruth.intValue(BooleanNode.TRUE)).isEqualTo(1);
        assertThat(PhpTruth.intValue(JsonNodeFactory.instance.arrayNode().add(5)))
                .isEqualTo(1);
        assertThat(PhpTruth.intValue(TextNode.valueOf("99999999999999999999"))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void numericTextWithFractionOrExponentIsReadAsPhpDoes() {
        assertThat(PhpTruth.intValue(TextNode.valueOf("1e3"))).isEqualTo(1000);
        assertThat(PhpTruth.intValue(TextNode.valueOf("1.5e3"))).isEqualTo(1500);
        assertThat(PhpTruth.intValue(TextNode.valueOf("2.9"))).isEqualTo(2);
        assertThat(PhpTruth.intValue(TextNode.valueOf("-2.9"))).isEqualTo(-2);
        assertThat(PhpTruth.intValue(TextNode.valueOf(".5"))).isZero();
        assertThat(PhpTruth.intValue(TextNode.valueOf("1e"))).isEqualTo(1);
        assertThat(PhpTruth.intValue(TextNode.valueOf("1e+2x"))).isEqualTo(100);
        assertThat(PhpTruth.intValue(TextNode.valueOf("1e999"))).isEqualTo(Long.MAX_VALUE);
        assertThat(PhpTruth.intValue(TextNode.valueOf("-1e99999999999"))).isEqualTo(Long.MIN_VALUE);
        assertThat(PhpTruth.intValue(TextNode.valueOf("1e-99999999999"))).isZero();
    }
}
