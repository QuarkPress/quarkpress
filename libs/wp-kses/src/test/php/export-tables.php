<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Writes WordPress's kses data tables in the line format of
 * src/main/resources/org/quarkpress/lib/wpkses/wordpress-tables.txt:
 *   php src/test/php/export-tables.php <wordpress-develop/src directory> > .../wordpress-tables.txt
 * The directory needs the pinned WordPress files (target/wp-source/<version>/ after a parity run).
 */

require __DIR__ . '/kses-cli.php';

/** One attribute rule: "attribute <context> <element> <name> [required] [<check>=<value>]...". */
function qp_attribute_line(string $context, string $element, string $name, mixed $rule): string
{
    $line = "attribute $context $element $name";
    if (true === $rule) {
        return $line;
    }
    if (!is_array($rule)) {
        throw new UnexpectedValueException("unexpected rule for $element $name");
    }
    foreach ($rule as $check => $value) {
        if ('required' === $check && true === $value) {
            $line .= ' required';
        } elseif (is_array($value)) {
            $line .= " $check=" . implode(',', $value);
        } elseif (is_string($value) || is_int($value)) {
            $line .= " $check=$value";
        } else {
            throw new UnexpectedValueException("unexpected check $check for $element $name");
        }
    }
    return $line;
}

$qp_css = null;
add_filter('safe_style_css', function ($properties) use (&$qp_css) {
    $qp_css = $properties;
    return $properties;
}, PHP_INT_MAX);
safecss_filter_attr('color:red');

echo "# WordPress 7.1.3 kses tables, written by src/test/php/export-tables.php. Do not edit by hand.\n";
echo 'protocols ', implode(' ', wp_allowed_protocols()), "\n";
echo 'uri-attributes ', implode(' ', wp_kses_uri_attributes()), "\n";
echo 'safe-style-css ', implode(' ', $qp_css), "\n";
foreach (['post', 'data'] as $qp_context) {
    foreach (wp_kses_allowed_html($qp_context) as $qp_element => $qp_spec) {
        echo "element $qp_context $qp_element\n";
        foreach ($qp_spec as $qp_name => $qp_rule) {
            echo qp_attribute_line($qp_context, (string) $qp_element, (string) $qp_name, $qp_rule), "\n";
        }
    }
}
