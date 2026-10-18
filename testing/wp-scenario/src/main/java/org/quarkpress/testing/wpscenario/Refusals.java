package org.quarkpress.testing.wpscenario;

/** Sorts a refused site creation into a category both sides share. */
public final class Refusals {

    private Refusals() {}

    /** From the error code of {@code wp_insert_site} (or {@code reserved}, the driver's Add Site screen rule). */
    public static String ofWordPress(String code) {
        return switch (code) {
            case "site_taken" -> "taken";
            case "site_empty_domain", "site_empty_path" -> "empty";
            case "reserved" -> "reserved";
            default -> "invalid: " + code;
        };
    }

    /** From the message of the {@code IllegalArgumentException} that {@code Sites.create} threw. */
    public static String ofQuarkPress(String message) {
        if (message.startsWith("a site already exists at ")) {
            return "taken";
        }
        if (message.equals("a site domain must not be empty")) {
            return "empty";
        }
        if (message.startsWith("the first path segment '")) {
            return "reserved";
        }
        return "invalid: " + message;
    }
}
