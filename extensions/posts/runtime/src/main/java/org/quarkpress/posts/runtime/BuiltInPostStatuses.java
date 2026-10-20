package org.quarkpress.posts.runtime;

import java.util.List;
import org.quarkpress.core.PostStatusDefinition;

/** WordPress's built-in post statuses, with the arguments WordPress registers them with. */
public final class BuiltInPostStatuses {

    private BuiltInPostStatuses() {}

    public static List<PostStatusDefinition> all() {
        return List.of(
                PostStatusDefinition.builder("publish")
                        .label("Published")
                        .labelCount(count("Published"), count("Published"))
                        .publicly(true)
                        .builtIn()
                        .build(),
                PostStatusDefinition.builder("future")
                        .label("Scheduled")
                        .labelCount(count("Scheduled"), count("Scheduled"))
                        .protectedStatus(true)
                        .builtIn()
                        .build(),
                PostStatusDefinition.builder("draft")
                        .label("Draft")
                        .labelCount(count("Draft"), count("Drafts"))
                        .protectedStatus(true)
                        .dateFloating(true)
                        .builtIn()
                        .build(),
                PostStatusDefinition.builder("pending")
                        .label("Pending")
                        .labelCount(count("Pending"), count("Pending"))
                        .protectedStatus(true)
                        .dateFloating(true)
                        .builtIn()
                        .build(),
                PostStatusDefinition.builder("private")
                        .label("Private")
                        .labelCount(count("Private"), count("Private"))
                        .privateStatus(true)
                        .builtIn()
                        .build(),
                PostStatusDefinition.builder("trash")
                        .label("Trash")
                        .labelCount(count("Trash"), count("Trash"))
                        .internal(true)
                        .showInAdminStatusList(true)
                        .builtIn()
                        .build(),
                PostStatusDefinition.builder("auto-draft")
                        .label("auto-draft")
                        .internal(true)
                        .dateFloating(true)
                        .builtIn()
                        .build(),
                PostStatusDefinition.builder("inherit")
                        .label("inherit")
                        .internal(true)
                        .excludeFromSearch(false)
                        .builtIn()
                        .build(),
                request("request-pending", "Pending"),
                request("request-confirmed", "Confirmed"),
                request("request-failed", "Failed"),
                request("request-completed", "Completed"));
    }

    private static PostStatusDefinition request(String name, String label) {
        return PostStatusDefinition.builder(name)
                .label(label)
                .labelCount(count(label), count(label))
                .internal(true)
                .excludeFromSearch(false)
                .builtIn()
                .build();
    }

    private static String count(String label) {
        return label + " <span class=\"count\">(%s)</span>";
    }
}
