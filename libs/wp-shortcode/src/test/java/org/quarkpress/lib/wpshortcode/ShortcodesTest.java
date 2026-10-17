package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ShortcodesTest {

    @Test
    void rejectsNullArguments() { // review focus 5
        Shortcodes s = Shortcodes.builder(Registries.echo()).build();
        assertThatNullPointerException().isThrownBy(() -> s.apply(null)).withMessageContaining("content");
        assertThatNullPointerException().isThrownBy(() -> Shortcodes.builder(null));
        assertThatNullPointerException()
                .isThrownBy(() -> Shortcodes.builder(Registries.echo()).hooks(null));
        assertThatNullPointerException()
                .isThrownBy(() -> Shortcodes.builder(Registries.echo()).attributeSanitizer(null));
        assertThatNullPointerException()
                .isThrownBy(() -> Shortcodes.builder(Registries.echo()).options(null));
    }

    @Test
    void validatesOptions() {
        assertThat(ShortcodeOptions.DEFAULTS.maxDepth()).isEqualTo(64);
        assertThat(ShortcodeOptions.DEFAULTS.withMaxDepth(256).maxDepth()).isEqualTo(256);
        assertThat(new ShortcodeOptions(0).maxDepth()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new ShortcodeOptions(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ShortcodeOptions(257));
    }

    @Test
    void isSafeToShareBetweenThreads() throws Exception { // review focus 3
        Shortcodes s = Shortcodes.builder(Registries.callbacks()).build();
        String content = "[outer][outer][inner][/outer]<b title=\"[x]\">[inner]</b>[/outer] [raw]&#91;[/raw]";
        String expected = s.apply(content);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                results.add(pool.submit(() -> s.apply(content)));
            }
            for (Future<String> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
