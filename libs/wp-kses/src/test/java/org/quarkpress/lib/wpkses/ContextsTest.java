package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextsTest {

    private static Allowlist resolve(String context, AllowedHtmlFilter... filters) {
        return Contexts.resolve(KsesContext.named(context), Map.of(), List.of(filters));
    }

    @Test
    void builtInContextsResolveLikeWordPress() {
        assertThat(resolve("post")).isEqualTo(WordPressTables.INSTANCE.post());
        assertThat(resolve("data")).isEqualTo(WordPressTables.INSTANCE.data());
        assertThat(resolve("title_save_pre")).isEqualTo(WordPressTables.INSTANCE.data());
        assertThat(resolve("strip")).isEqualTo(Allowlist.empty());
        for (String name : List.of("user_description", "pre_term_description", "pre_user_description")) {
            assertThat(resolve(name).element("a").orElseThrow().attributes().keySet())
                    .as(name)
                    .containsExactly("href", "title", "rel", "target");
        }
    }

    @Test
    void entitiesKeepsOnlyClosingTagsOfItsNumericKeys() {
        Allowlist entities = resolve("entities");
        assertThat(entities.elements()).hasSize(253);
        assertThat(entities.element("252").orElseThrow().allowsOpeningTags()).isFalse();
        assertThat(entities.allows("253")).isFalse();
    }

    @Test
    void noteMentionAddsSpanClassForCommentsOnly() {
        assertThat(resolve("pre_comment_content", Contexts.NOTE_MENTION)
                        .element("span")
                        .orElseThrow()
                        .attributes())
                .containsOnlyKeys("class");
        assertThat(resolve("pre_comment_content").allows("span")).isFalse();
        assertThat(resolve("data", Contexts.NOTE_MENTION).allows("span")).isFalse();
    }

    @Test
    void inputInPostAddsFormFromTheUnfilteredTable() {
        AllowedHtmlFilter addInput = (tags, context) -> context.equals(KsesContext.POST)
                ? tags.toBuilder()
                        .attribute("input", "name")
                        .attribute("input", "type")
                        .build()
                : tags;
        Allowlist post = resolve("post", addInput);
        assertThat(post.element("form").orElseThrow().attributes().keySet())
                .containsExactly("action", "accept", "accept-charset", "enctype", "method", "name", "target");
        assertThat(post.element("input").orElseThrow().attributes().keySet()).containsExactly("name", "type");
    }

    @Test
    void registeredBasesReplaceTablesAndSkipTheFormCase() {
        Allowlist custom = Allowlist.builder().attribute("input", "name").build();
        Allowlist post = Contexts.resolve(KsesContext.POST, Map.of(KsesContext.POST, custom), List.of());
        assertThat(post).isEqualTo(custom);
    }

    @Test
    void filtersMayNotReturnNull() {
        assertThatThrownBy(() -> resolve("post", (tags, context) -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("post");
    }
}
