package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TextFieldsTest {

    @Test
    void textFieldsAsWordPressSanitizesThem() {
        Map<String, String[]> cases = Map.ofEntries(
                Map.entry("  a \t\n b  ", new String[] {"a b", "a \t\n b"}),
                Map.entry("a  b", new String[] {"a b", "a  b"}),
                Map.entry("a\n\nb", new String[] {"a b", "a\n\nb"}),
                Map.entry(" \n a%41b %41 c \n", new String[] {"ab c", "ab c"}),
                Map.entry("a <b>x</b> <\n y", new String[] {"a x &lt; y", "a x &lt;\n y"}),
                Map.entry("%%4141", new String[] {"", ""}),
                Map.entry("a%4", new String[] {"a%4", "a%4"}),
                Map.entry("<script>x</script>y", new String[] {"y", "y"}),
                Map.entry("a\r\nb", new String[] {"a b", "a\r\nb"}),
                Map.entry("\u0000a\u0000", new String[] {"a", "a"}),
                Map.entry("a %20 b", new String[] {"a b", "a b"}),
                Map.entry("a\tb%41\tc", new String[] {"a b c", "a\tb\tc"}),
                Map.entry("a\ud800b", new String[] {"", ""}));
        cases.forEach((input, expected) -> {
            assertThat(TextFields.textField(input)).as(input).isEqualTo(expected[0]);
            assertThat(TextFields.textareaField(input)).as(input).isEqualTo(expected[1]);
        });
        assertThatThrownBy(() -> TextFields.textField(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void repeatedRemovalIsLinear() {
        int n = 300_000;
        String nested = "%".repeat(n) + "41".repeat(n);
        long[] work = new long[1];
        assertThat(TextFields.removeOctets(nested, work)).isEmpty();
        assertThat(work[0]).isLessThanOrEqualTo(nested.length());
        long started = System.nanoTime();
        assertThat(TextFields.textField(nested)).isEmpty();
        assertThat(TextFields.textField("<" + "a<".repeat(n))).isNotNull();
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
    }
}
