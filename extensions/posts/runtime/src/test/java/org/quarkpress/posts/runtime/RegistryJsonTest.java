package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;

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

        assertThat(RegistryJson.readTypes(RegistryJson.writeTypes(types))).isEqualTo(types);
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
