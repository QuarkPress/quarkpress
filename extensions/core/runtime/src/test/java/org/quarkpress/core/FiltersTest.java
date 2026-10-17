package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FiltersTest {

    private static final RenderContext CTX = () -> SiteId.of(1);

    @Test
    void foldsInListOrder() {
        List<ContentFilter> filters = List.of((v, c) -> v + "a", (v, c) -> v + "b", (v, c) -> v + "c");
        assertThat(Filters.apply(filters, "x", (f, v) -> f.apply(v, CTX))).isEqualTo("xabc");
    }

    @Test
    void anEmptyListReturnsTheValue() {
        assertThat(Filters.apply(List.<ContentFilter>of(), "x", (f, v) -> f.apply(v, CTX)))
                .isEqualTo("x");
    }

    @Test
    void aFilterReturningNullIsNamed() {
        ContentFilter broken = (v, c) -> null;
        assertThatThrownBy(() -> Filters.apply(List.of(broken), "x", (f, v) -> f.apply(v, CTX)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("returned null");
    }

    @Test
    void aThrowingFilterPropagatesAndStopsTheChain() {
        int[] ran = {0};
        IllegalStateException boom = new IllegalStateException("boom");
        List<ContentFilter> filters = List.of(
                (v, c) -> v + "a",
                (v, c) -> {
                    throw boom;
                },
                (v, c) -> {
                    ran[0]++;
                    return v;
                });
        assertThatThrownBy(() -> Filters.apply(filters, "x", (f, v) -> f.apply(v, CTX)))
                .isSameAs(boom);
        assertThat(ran[0]).isZero();
    }

    @Test
    void nullValueAndNullFilterAreRejected() {
        assertThatThrownBy(() -> Filters.apply(List.<ContentFilter>of(), (String) null, (f, v) -> f.apply(v, CTX)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
        List<ContentFilter> withNull = java.util.Arrays.asList((ContentFilter) null);
        assertThatThrownBy(() -> Filters.apply(withNull, "x", (f, v) -> f.apply(v, CTX)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("null");
    }
}
