package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AllowlistTest {

    @Test
    void builderKeepsOrderAndReplacesInPlace() {
        Allowlist list = Allowlist.builder()
                .attribute("a", "href")
                .attribute("a", "title")
                .element("b")
                .attribute("a", "href", AttributeRule.of(AttributeCheck.maxLength(3)))
                .build();
        assertThat(list.elements().keySet()).containsExactly("a", "b");
        assertThat(list.element("a").orElseThrow().attributes().keySet()).containsExactly("href", "title");
        assertThat(list.element("a")
                        .orElseThrow()
                        .attribute("href")
                        .orElseThrow()
                        .checks())
                .containsExactly(new AttributeCheck.MaxLength(3));
        assertThat(list.element("b").orElseThrow().attributes()).isEmpty();
    }

    @Test
    void elementDoesNotDropExistingAttributes() {
        Allowlist list = Allowlist.builder().attribute("a", "href").element("a").build();
        assertThat(list.element("a").orElseThrow().attributes()).containsKey("href");
    }

    @Test
    void isImmutable() {
        Allowlist.Builder builder = Allowlist.builder().attribute("a", "href");
        Allowlist first = builder.build();
        builder.attribute("a", "title").removeElement("b");
        assertThat(first.element("a").orElseThrow().attributes()).containsOnlyKeys("href");
        assertThatThrownBy(() -> first.elements().put("x", ElementRule.noAttributes()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toBuilderCopies() {
        Allowlist base = Allowlist.builder().attribute("a", "href").build();
        Allowlist changed = base.toBuilder()
                .removeAttribute("a", "href")
                .attribute("p", "class")
                .build();
        assertThat(base.element("a").orElseThrow().attributes()).containsOnlyKeys("href");
        assertThat(changed.element("a").orElseThrow().attributes()).isEmpty();
        assertThat(changed.allows("p")).isTrue();
    }

    @Test
    void equalityIsByContent() {
        Allowlist a = Allowlist.builder().attribute("a", "href").build();
        Allowlist b = Allowlist.builder().attribute("a", "href").build();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(Allowlist.builder().build()).isSameAs(Allowlist.empty());
    }

    @Test
    void rulesRecordRequiredAndChecks() {
        AttributeRule rule = AttributeRule.required(AttributeCheck.values(List.of("application/pdf")));
        assertThat(rule.required()).isTrue();
        assertThat(rule.checks()).containsExactly(new AttributeCheck.Values(List.of("application/pdf")));
        assertThat(AttributeRule.allowed().required()).isFalse();
        assertThat(AttributeRule.allowed().checks()).isEmpty();
    }

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> Allowlist.builder().element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Allowlist.builder().attribute("a", "b", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> KsesContext.named(null)).isInstanceOf(NullPointerException.class);
    }
}
