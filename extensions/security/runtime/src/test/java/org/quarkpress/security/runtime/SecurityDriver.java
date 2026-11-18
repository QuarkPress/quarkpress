package org.quarkpress.security.runtime;

import java.nio.file.Path;

/**
 * The PHP driver the security parity tests run, and the WordPress files it loads. Each test that runs the driver
 * downloads all of them ({@code SecurityDriverFilesTest} checks the list against the driver), so no test depends on
 * another having downloaded a file first.
 */
final class SecurityDriver {

    static final Path PATH = Path.of("src/test/php/security-driver.php");

    static final String[] WORDPRESS_FILES = {
        "wp-includes/formatting.php",
        "wp-includes/functions.php",
        "wp-includes/capabilities.php",
        "wp-includes/class-wp-role.php",
        "wp-includes/class-wp-roles.php",
        "wp-includes/post.php",
        "wp-admin/includes/schema.php"
    };

    private SecurityDriver() {}
}
