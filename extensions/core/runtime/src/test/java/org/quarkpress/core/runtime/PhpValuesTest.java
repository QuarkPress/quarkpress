package org.quarkpress.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** Expected values checked with PHP 8.4 ({@code (string)} and {@code (float)} casts). */
class PhpValuesTest {

    @Test
    void floatsAreWrittenWithFourteenSignificantDigits() {
        assertThat(PhpValues.floatString(5.0)).isEqualTo("5");
        assertThat(PhpValues.floatString(5.5)).isEqualTo("5.5");
        assertThat(PhpValues.floatString(-0.0)).isEqualTo("-0");
        assertThat(PhpValues.floatString(0.1 + 0.2)).isEqualTo("0.3");
        assertThat(PhpValues.floatString(1.0 / 3)).isEqualTo("0.33333333333333");
        assertThat(PhpValues.floatString(12345678901234.0)).isEqualTo("12345678901234");
        assertThat(PhpValues.floatString(1e14)).isEqualTo("1.0E+14");
        assertThat(PhpValues.floatString(99999999999999.5)).isEqualTo("1.0E+14");
        assertThat(PhpValues.floatString(-1e15)).isEqualTo("-1.0E+15");
        assertThat(PhpValues.floatString(123456789012345678.0)).isEqualTo("1.2345678901235E+17");
        assertThat(PhpValues.floatString(0.0001)).isEqualTo("0.0001");
        assertThat(PhpValues.floatString(0.00012345678901234567)).isEqualTo("0.00012345678901235");
        assertThat(PhpValues.floatString(1.5e-5)).isEqualTo("1.5E-5");
        assertThat(PhpValues.floatString(1e-7)).isEqualTo("1.0E-7");
        assertThat(PhpValues.floatString(1e300)).isEqualTo("1.0E+300");
        assertThat(PhpValues.floatString(Double.MIN_VALUE)).isEqualTo("4.9406564584125E-324");
        assertThat(PhpValues.floatString(Double.NaN)).isEqualTo("NAN");
        assertThat(PhpValues.floatString(Double.NEGATIVE_INFINITY)).isEqualTo("-INF");
    }

    @Test
    void valuesAreWrittenAsPhpStrings() {
        assertThat(PhpValues.stringOf(BooleanNode.TRUE)).isEqualTo("1");
        assertThat(PhpValues.stringOf(BooleanNode.FALSE)).isEmpty();
        assertThat(PhpValues.stringOf(IntNode.valueOf(-12))).isEqualTo("-12");
        assertThat(PhpValues.stringOf(LongNode.valueOf(Long.MAX_VALUE))).isEqualTo("9223372036854775807");
        assertThat(PhpValues.stringOf(DoubleNode.valueOf(5.0))).isEqualTo("5");
        assertThat(PhpValues.stringOf(TextNode.valueOf(" x "))).isEqualTo(" x ");
        assertThat(PhpValues.stringOf(NullNode.instance)).isNull();
        assertThat(PhpValues.stringOf(JsonNodeFactory.instance.arrayNode())).isNull();
    }

    @Test
    void leadingFloatsAreReadAsPhpCastsThem() {
        assertThat(PhpValues.leadingFloat("5" + " ".repeat(70))).isEqualTo(OptionalDouble.of(5));
        assertThat(PhpValues.leadingFloat("\u000B\f\t\r\n 5")).isEqualTo(OptionalDouble.of(5));
        assertThat(PhpValues.leadingFloat("1.e1")).isEqualTo(OptionalDouble.of(10));
        assertThat(PhpValues.leadingFloat("-.5")).isEqualTo(OptionalDouble.of(-0.5));
        assertThat(PhpValues.leadingFloat("1e")).isEqualTo(OptionalDouble.of(1));
        assertThat(PhpValues.leadingFloat("1e+")).isEqualTo(OptionalDouble.of(1));
        assertThat(PhpValues.leadingFloat("2E-1x")).isEqualTo(OptionalDouble.of(0.2));
        assertThat(PhpValues.leadingFloat(".e1")).isEmpty();
        assertThat(PhpValues.leadingFloat("+")).isEmpty();
        assertThat(PhpValues.leadingFloat("--1")).isEmpty();
        assertThat(PhpValues.leadingFloat("")).isEmpty();
    }

    @Test
    void numbersLongerThanTheBoundAreNotRead() {
        assertThat(PhpValues.leadingFloat("1".repeat(64))).isPresent();
        assertThat(PhpValues.leadingFloat("1".repeat(65))).isEmpty();
        assertThat(PhpValues.leadingFloat("1".repeat(62) + "e1")).isPresent();
        assertThat(PhpValues.leadingFloat("1".repeat(63) + "e1")).isEmpty();
        assertThat(PhpValues.leadingFloat("1".repeat(64) + "e+1")).isEmpty();
        assertThat(PhpValues.leadingFloat(" ".repeat(1000) + "5")).isEqualTo(OptionalDouble.of(5));
    }
}
