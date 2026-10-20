package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostStatusDefinitionTest {

    @Test
    void aStatusWithoutArgumentsIsInternal() {
        PostStatusDefinition archived = PostStatusDefinition.builder("archived").build();
        assertThat(archived.internal()).isTrue();
        assertThat(archived.publicly()).isFalse();
        assertThat(archived.privateStatus()).isFalse();
        assertThat(archived.protectedStatus()).isFalse();
        assertThat(archived.excludeFromSearch()).isTrue();
        assertThat(archived.showInAdminAllList()).isFalse();
        assertThat(archived.showInAdminStatusList()).isFalse();
        assertThat(archived.dateFloating()).isFalse();
        assertThat(archived.label()).isEqualTo("archived");
        assertThat(archived.labelCountSingular()).isEqualTo("archived");
        assertThat(archived.labelCountPlural()).isEqualTo("archived");
    }

    @Test
    void aPublicStatusIsSearchableAndListed() {
        PostStatusDefinition archived =
                PostStatusDefinition.builder("archived").publicly(true).build();
        assertThat(archived.internal()).isFalse();
        assertThat(archived.excludeFromSearch()).isFalse();
        assertThat(archived.publiclyQueryable()).isTrue();
        assertThat(archived.showInAdminAllList()).isTrue();
        assertThat(archived.showInAdminStatusList()).isTrue();
    }

    @Test
    void anyOfTheFourVisibilityFlagsKeepsItFromBeingInternal() {
        Map<String, PostStatusDefinition> flagged = new LinkedHashMap<>();
        flagged.put("public", PostStatusDefinition.builder("pub").publicly(true).build());
        flagged.put(
                "protected",
                PostStatusDefinition.builder("prot").protectedStatus(true).build());
        flagged.put(
                "private",
                PostStatusDefinition.builder("priv").privateStatus(true).build());
        flagged.put(
                "internal false",
                PostStatusDefinition.builder("intf").internal(false).build());
        flagged.forEach((flag, status) -> {
            assertThat(status.internal()).as(flag).isFalse();
            assertThat(status.excludeFromSearch()).as(flag).isFalse();
            assertThat(status.showInAdminAllList()).as(flag).isTrue();
            assertThat(status.showInAdminStatusList()).as(flag).isTrue();
        });
        assertThat(flagged.get("protected").publiclyQueryable()).isFalse();
        assertThat(flagged.get("private").publiclyQueryable()).isFalse();
        PostStatusDefinition internal =
                PostStatusDefinition.builder("inte").internal(true).build();
        assertThat(internal.internal()).isTrue();
        assertThat(internal.excludeFromSearch()).isTrue();
        assertThat(internal.showInAdminAllList()).isFalse();
        assertThat(internal.showInAdminStatusList()).isFalse();
    }

    @Test
    void anExplicitFlagOverridesTheOneDerivedFromTheVisibilityFlags() {
        PostStatusDefinition shown = PostStatusDefinition.builder("shown")
                .excludeFromSearch(true)
                .showInAdminAllList(false)
                .showInAdminStatusList(false)
                .publiclyQueryable(true)
                .publicly(true)
                .build();
        assertThat(shown.excludeFromSearch()).isTrue();
        assertThat(shown.showInAdminAllList()).isFalse();
        assertThat(shown.showInAdminStatusList()).isFalse();
        PostStatusDefinition quiet = PostStatusDefinition.builder("quiet")
                .excludeFromSearch(false)
                .showInAdminAllList(true)
                .showInAdminStatusList(true)
                .build();
        assertThat(quiet.internal()).isTrue();
        assertThat(quiet.excludeFromSearch()).isFalse();
        assertThat(quiet.showInAdminAllList()).isTrue();
        assertThat(quiet.showInAdminStatusList()).isTrue();
    }

    @Test
    void theNameIsSanitizedAsAKey() {
        assertThat(PostStatusDefinition.builder("Needs Review").build().name()).isEqualTo("needsreview");
    }

    @Test
    void aNameOfExactlyTwentyCharactersIsAccepted() {
        assertThat(PostStatusDefinition.builder("a".repeat(20)).build().name()).isEqualTo("a".repeat(20));
    }

    @Test
    void anEmptyOrOverlongNameIsRefused() {
        assertThatThrownBy(() -> PostStatusDefinition.builder("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a post status name must hold 1 to 20 of a-z, 0-9, _ and -: '  '");
        assertThatThrownBy(() -> PostStatusDefinition.builder("a".repeat(21)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a post status name must hold 1 to 20 of a-z, 0-9, _ and -: '" + "a".repeat(21) + "'");
    }
}
