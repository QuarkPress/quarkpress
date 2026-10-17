package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttributeParserTest {

    @Test
    void matchesWordPress() {
        Examples.assertFile("hair.json");
    }

    @Test
    void keepsTheFirstOfDuplicateNamesInSourceOrder() {
        assertThat(AttributeParser.parse("B=1 a=2 b=3 A"))
                .containsExactly(new HairAttribute("b", "1"), new HairAttribute("a", "2"));
    }

    @Test
    void booleanAttributesHaveNoValue() {
        List<HairAttribute> attributes = AttributeParser.parse("hidden title= x=''");
        assertThat(attributes).containsExactly(new HairAttribute("hidden", null), new HairAttribute("title", "x=''"));
        assertThat(attributes.get(0).valueless()).isTrue();
    }

    @Test
    void anUnclosedQuoteLosesEveryAttribute() {
        assertThat(AttributeParser.parse("a=1 b=\"2")).isEmpty();
        assertThat(AttributeParser.parse("a=1 b='2")).isEmpty();
    }

    @Test
    void reencodesTheFiveSyntaxCharacters() {
        assertThat(AttributeParser.reencode("&<>'\"x")).isEqualTo("&amp;&lt;&gt;&apos;&quot;x");
        String plain = "plain";
        assertThat(AttributeParser.reencode(plain)).isSameAs(plain);
    }

    @Test
    void manyAttributesStayLinear() {
        String attr = " a=1".repeat(200_000) + " x=\"" + "y".repeat(5_000_000) + "\"";
        List<HairAttribute> parsed =
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> AttributeParser.parse(attr));
        // 200,000 duplicates of "a" collapse to one; the long value must arrive whole.
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0)).isEqualTo(new HairAttribute("a", "1"));
        assertThat(parsed.get(1).value()).hasSize(5_000_000);
    }
}
