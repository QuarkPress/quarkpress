package org.quarkpress.security;

/** How a post status counts when reading is checked: public, private, any other registered status, or unregistered. */
public enum PostVisibility {
    PUBLIC,
    PRIVATE,
    OTHER,
    UNREGISTERED;

    /** The visibility of WordPress's built-in statuses; any other status counts as unregistered. */
    public static PostVisibility ofBuiltInStatus(String status) {
        return switch (status) {
            case "publish" -> PUBLIC;
            case "private" -> PRIVATE;
            case "future",
                    "draft",
                    "pending",
                    "trash",
                    "auto-draft",
                    "inherit",
                    "request-pending",
                    "request-confirmed",
                    "request-failed",
                    "request-completed" -> OTHER;
            default -> UNREGISTERED;
        };
    }
}
