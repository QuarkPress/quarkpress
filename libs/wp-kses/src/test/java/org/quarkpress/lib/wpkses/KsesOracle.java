package org.quarkpress.lib.wpkses;

import java.nio.file.Path;

/** The WordPress 7.1.3 files the kses oracle driver loads, besides the bootstrap's hook API. */
final class KsesOracle {

    static final Path DRIVER = Path.of("src/test/php/kses-driver.php");

    static final String[] WORDPRESS_FILES = {
        "wp-includes/compat.php",
        "wp-includes/formatting.php",
        "wp-includes/class-wp-token-map.php",
        "wp-includes/html-api/class-wp-html-attribute-token.php",
        "wp-includes/html-api/class-wp-html-span.php",
        "wp-includes/html-api/class-wp-html-text-replacement.php",
        "wp-includes/html-api/class-wp-html-decoder.php",
        "wp-includes/html-api/class-wp-html-doctype-info.php",
        "wp-includes/html-api/class-wp-html-unsupported-exception.php",
        "wp-includes/html-api/class-wp-html-tag-processor.php",
        "wp-includes/html-api/html5-named-character-references.php",
        "wp-includes/kses.php",
        "wp-includes/class-wp-block-parser.php",
        "wp-includes/class-wp-block-parser-block.php",
        "wp-includes/class-wp-block-parser-frame.php",
        "wp-includes/blocks.php"
    };

    private KsesOracle() {}
}
