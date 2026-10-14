package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"paragraph", "core/paragraph", "my-plugin/my_block-2", "a", "a/b"})
    void acceptsGrammarNames(String name) {
        assertThat(BlockNames.isValid(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Paragraph",
                "core/Paragraph",
                "4-invalid",
                "core/4-invalid",
                "-x",
                "core/",
                "/x",
                "a/b/c",
                "core paragraph",
                "core/par agraph",
                "é"
            })
    void rejectsEverythingElse(String name) {
        assertThat(BlockNames.isValid(name)).isFalse();
    }

    @Test
    void qualifyAddsCoreNamespaceOnlyWhenMissing() {
        assertThat(BlockNames.qualify("paragraph")).isEqualTo("core/paragraph");
        assertThat(BlockNames.qualify("core/paragraph")).isEqualTo("core/paragraph");
        assertThat(BlockNames.qualify("acme/hero")).isEqualTo("acme/hero");
    }

    @Test
    void shortenDropsOnlyCoreNamespace() {
        assertThat(BlockNames.shorten("core/paragraph")).isEqualTo("paragraph");
        assertThat(BlockNames.shorten("acme/hero")).isEqualTo("acme/hero");
    }
}
