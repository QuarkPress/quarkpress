package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CallbackContractTest {

    private static final Shortcodes SHORTCODES =
            Shortcodes.builder(Registries.callbacks()).build();

    private static void assertApply(String content, String expected) {
        assertThat(SHORTCODES.apply(content)).as(content).isEqualTo(expected);
    }

    @Test
    void handlersSeeContentAndExpandItThemselves() { // R7.1, R7.2, R7.6, R7.6a
        assertApply("[outer][inner][inner/][/outer]", "<o>II</o>");
        assertApply("[emit]", "[inner]");
        assertApply("[ent]", "[inner]");
        assertApply("[outer]<b title=\"[x]\">[inner]</b>[/outer]", "<o><b title=\"&#091;x&#093;\">I</b></o>");
        assertApply("[outer]<b title=\"[x]\">t</b>[/outer]", "<o><b title=\"[x]\">t</b></o>");
        assertApply("[quiet]<b title=\"[x]\">[inner]</b>[/quiet]", "<o><b title=\"[x]\">[inner]</b></o>");
        assertApply(
                "[raw]<b title=\"[x]\">in</b> [y] &#91;z&#93; <!-- [c] --> <i>[/raw]",
                "C=<b title=\"[x]\">in</b> [y] &#091;z&#093; <!-- [c] --> <i>");
        assertApply("[nul]|[nul/]|[nul]x[/nul]", "");
        assertApply("[raw/] [raw]", "C= C=");
    }

    @Test
    void nullResultIsEmpty() { // R7.4 for null
        Shortcodes s = Shortcodes.builder(Registries.constant(null, "n")).build();
        assertThat(s.apply("a[n]b[n/]c")).isEqualTo("abc");
    }

    @Test
    void handlersGetTheRecordWithItsFlags() {
        List<Shortcode> seen = new ArrayList<>();
        Shortcodes s = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("t", (sc, c) -> {
                            seen.add(sc);
                            return "";
                        })
                        .build())
                .build();
        s.apply("[t a=1]x[/t][t/][t][/t]");
        assertThat(seen).extracting(Shortcode::tag).containsOnly("t");
        assertThat(seen).extracting(Shortcode::content).containsExactly("x", "", "");
        assertThat(seen).extracting(Shortcode::enclosing).containsExactly(true, false, true);
        assertThat(seen).extracting(Shortcode::selfClosing).containsExactly(false, true, false);
        assertThat(seen.get(0).attributes().get("a")).contains("1");
    }

    @Test
    void handlerExceptionsPropagateAndResetTheContext() { // review focus 2
        AtomicReference<ShortcodeContext> leaked = new AtomicReference<>();
        IllegalStateException boom = new IllegalStateException("boom");
        Shortcodes s = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("bad", (sc, c) -> {
                            leaked.set(c);
                            throw boom;
                        })
                        .add("ok", (sc, c) -> "fine")
                        .build())
                .build();
        assertThatThrownBy(() -> s.apply("x [bad] y")).isSameAs(boom);
        assertThat(leaked.get().insideShortcodes()).isFalse();
        assertThat(s.apply("[ok]")).isEqualTo("fine");
    }

    @Test
    void contextReportsDepthAndWhetherApplyIsRunning() { // R2.7
        List<String> seen = new ArrayList<>();
        AtomicReference<ShortcodeContext> leaked = new AtomicReference<>();
        Shortcodes s = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("outer", (sc, c) -> {
                            seen.add("outer@" + c.depth() + ":" + c.insideShortcodes());
                            return "<" + c.expand(sc.content()) + ">";
                        })
                        .add("inner", (sc, c) -> {
                            seen.add("inner@" + c.depth() + ":" + c.insideShortcodes());
                            leaked.set(c);
                            return "i";
                        })
                        .build())
                .build();
        assertThat(s.apply("[outer][inner][/outer]")).isEqualTo("<i>");
        assertThat(seen).containsExactly("outer@0:true", "inner@1:true");
        assertThat(leaked.get().insideShortcodes()).isFalse();
    }

    @Test
    void expandStopsAtMaxDepth() { // deliberate difference D6
        AtomicInteger calls = new AtomicInteger();
        Shortcodes s = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("deep", (sc, c) -> {
                            calls.incrementAndGet();
                            return c.expand("[deep]");
                        })
                        .build())
                .options(ShortcodeOptions.DEFAULTS.withMaxDepth(3))
                .build();
        assertThatThrownBy(() -> s.apply("[deep]"))
                .isInstanceOf(ShortcodeException.class)
                .hasMessageContaining("maxDepth 3");
        assertThat(calls).hasValue(4);
    }

    @Test
    void maxDepthCapEndsInShortcodeExceptionNotStackOverflow() throws Exception {
        Shortcodes s = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("deep", (sc, c) -> c.expand("[deep]"))
                        .build())
                .options(ShortcodeOptions.DEFAULTS.withMaxDepth(ShortcodeOptions.MAX_DEPTH_CAP))
                .build();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(
                null,
                () -> {
                    try {
                        s.apply("[deep]");
                    } catch (Throwable e) {
                        thrown.set(e);
                    }
                },
                "deep",
                512 * 1024);
        t.start();
        t.join();
        assertThat(thrown.get()).isInstanceOf(ShortcodeException.class);
    }
}
