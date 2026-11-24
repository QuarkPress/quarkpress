package org.quarkword.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostStatusDefinition;
import org.quarkword.core.PostTypeDefinition;

class RegistryJsonTest {

    @Test
    void typesReadBackAsWritten() {
        List<PostTypeDefinition> types = new ArrayList<>(BuiltInPostTypes.all());
        types.add(PostTypeDefinition.builder("thing")
                .label("Things")
                .description("Things to keep")
                .publicly(true)
                .hierarchical(true)
                .embeddable(false)
                .showInNavMenus(false)
                .showInAdminBar(true)
                .showInMenu(false)
                .capabilityType("thing", "thingz")
                .capabilities(Map.of("create_posts", "make_things"))
                .noSupports()
                .hasArchive(true)
                .showInRest(true)
                .build());
        PostTypeDefinition thing = types.getLast();
        assertThat(thing.showInNavMenus()).isNotEqualTo(thing.showInAdminBar());
        assertThat(thing.restBase()).isEmpty();
        types.add(PostTypeDefinition.builder("book")
                .publicly(true)
                .hasArchive("library")
                .queryVar("shelf")
                .rewrite("books", false, false, true)
                .build());
        PostTypeDefinition book = types.getLast();
        assertThat(book.archiveSlug()).contains("library");
        assertThat(book.queryVar()).contains("shelf");
        assertThat(book.rewrite()).contains(new PostTypeDefinition.Rewrite("books", false, false, true));
        assertThat(thing.queryVar()).contains("thing");
        assertThat(thing.rewrite()).contains(new PostTypeDefinition.Rewrite("thing", true, true, true));
        assertThat(thing.archiveSlug()).isEmpty();

        String json = RegistryJson.writeTypes(types);
        assertThat(json)
                .contains("\"archiveSlug\":\"library\"")
                .contains("\"queryVar\":\"shelf\"")
                .contains("\"rewrite\":{\"slug\":\"books\",\"withFront\":false,\"pages\":false,\"feeds\":true}");
        assertThat(RegistryJson.readTypes(json)).isEqualTo(types);
    }

    @Test
    void builtInTypesHaveNoQueryVarAndNoRewrite() {
        for (PostTypeDefinition type : BuiltInPostTypes.all()) {
            assertThat(type.queryVar()).as(type.key()).isEmpty();
            assertThat(type.rewrite()).as(type.key()).isEmpty();
            assertThat(type.archiveSlug()).as(type.key()).isEmpty();
        }
    }

    @Test
    void statusesReadBackAsWritten() {
        List<PostStatusDefinition> statuses = new ArrayList<>(BuiltInPostStatuses.all());
        statuses.add(PostStatusDefinition.builder("archived")
                .label("Archived")
                .publicly(true)
                .showInAdminAllList(false)
                .showInAdminStatusList(true)
                .dateFloating(true)
                .build());

        assertThat(RegistryJson.readStatuses(RegistryJson.writeStatuses(statuses)))
                .isEqualTo(statuses);
    }
}
