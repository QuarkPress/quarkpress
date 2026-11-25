package org.quarkword.front.deployment;

import io.quarkus.builder.item.SimpleBuildItem;

/** The absolute path the catch-all route re-routes front-end requests to: the page resource's {@code page} method. */
final class FrontEntryPathBuildItem extends SimpleBuildItem {

    private final String path;

    FrontEntryPathBuildItem(String path) {
        this.path = path;
    }

    String path() {
        return path;
    }
}
