<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wp-style-engine. A case is {"fn", "in", "args"}, with "in" any JSON value: the driver
 * empties the style engine's stores, runs the WordPress function named by "fn" on "in" with the arguments in
 * "args", and returns {"out": result}.
 */

declare(strict_types=1);

require getenv('QW_ORACLE_HOME') . '/bootstrap.php';
require QW_WP . 'compat.php';
require QW_WP . 'formatting.php';
require QW_WP . 'kses.php';
require QW_WP . 'class-wp-token-map.php';
require QW_WP . 'html-api/html5-named-character-references.php';
require QW_WP . 'html-api/class-wp-html-decoder.php';
qw_load_functions(QW_WP . 'functions.php', [
    'wp_parse_args',
    '_wp_array_get',
    '_wp_to_kebab_case',
    'wp_json_encode',
    '_wp_json_sanity_check',
    '_wp_json_convert_string',
]);
require QW_WP . 'style-engine.php';
require QW_WP . 'style-engine/class-wp-style-engine.php';
require QW_WP . 'style-engine/class-wp-style-engine-css-declarations.php';
require QW_WP . 'style-engine/class-wp-style-engine-css-rule.php';
require QW_WP . 'style-engine/class-wp-style-engine-css-rules-store.php';
require QW_WP . 'style-engine/class-wp-style-engine-processor.php';

/**
 * A protected static WP_Style_Engine method called through reflection. An argument {"$def": "group.key"} stands
 * for that row of the definitions table. "php_string" is a driver-local helper: PHP's string cast.
 */
function qw_protected(string $name, array $args): mixed
{
    foreach ($args as $i => $arg) {
        if (is_array($arg) && 1 === count($arg) && isset($arg['$def']) && is_string($arg['$def'])) {
            [$group, $key] = explode('.', $arg['$def'], 2);
            $args[$i] = WP_Style_Engine::BLOCK_STYLE_DEFINITIONS_METADATA[$group][$key];
        }
    }
    if ('php_string' === $name) {
        return (string) $args[0];
    }
    $method = new ReflectionMethod(WP_Style_Engine::class, $name);
    return $method->invokeArgs(null, $args);
}

// Task 2: declarations, rules, stores
/** Property names as strings: PHP turns a decimal key into an integer, which json_encode would write as a number. */
function qw_se_keys(array $a): array
{
    return array_map('strval', array_keys($a));
}

/** The properties whose options hold important === true. */
function qw_se_important(WP_Style_Engine_CSS_Declarations $d): array
{
    return qw_se_keys(array_filter($d->get_declaration_options(), fn($o) => ($o['important'] ?? null) === true));
}

/** A declarations object from a raw map, with the listed properties added again with important => true. */
function qw_se_object(mixed $map, array $important): WP_Style_Engine_CSS_Declarations
{
    $o = new WP_Style_Engine_CSS_Declarations($map);
    foreach ($important as $p) {
        $o->add_declaration($p, $map[$p], ['important' => true]);
    }
    return $o;
}

/** A declarations object after the ops, as its map, its important properties and five renderings. */
function qw_se_declarations(array $in): array
{
    $d = new WP_Style_Engine_CSS_Declarations($in['init'] ?? []);
    foreach ($in['ops'] ?? [] as $op) {
        match ($op[0]) {
            'add' => $d->add_declaration($op[1], $op[2], count($op) > 3 ? ['important' => $op[3]] : []),
            'addAll' => (function () use ($d, $op) {
                // a rule's add_declarations(object) on a rule that already has declarations
                $o = qw_se_object($op[1]['map'], $op[1]['important'] ?? []);
                $options = $o->get_declaration_options();
                foreach ($o->get_declarations() as $p => $v) {
                    $d->add_declaration($p, $v, $options[$p] ?? []);
                }
            })(),
            'remove' => $d->remove_declaration($op[1]),
            'removeAll' => $d->remove_declarations($op[1]),
        };
    }
    return [
        'declarations' => $d->get_declarations(),
        'important' => qw_se_important($d),
        'css' => [
            $d->get_declarations_string(),
            $d->get_declarations_string(true),
            $d->get_declarations_string(true, 1),
            $d->get_declarations_string(true, 2),
            $d->get_declarations_string(false, 3),
        ],
    ];
}

/** A rule after the ops, rendered four ways. */
function qw_se_rule(array $in): array
{
    $source = null;
    $declarations = $in['declarations'] ?? [];
    if (!empty($in['object'])) {
        $declarations = $source = qw_se_object($declarations, $in['important'] ?? []);
    }
    $r = new WP_Style_Engine_CSS_Rule($in['selector'], $declarations, $in['rules_group'] ?? '');
    foreach ($in['ops'] ?? [] as $op) {
        match ($op[0]) {
            'add' => $r->add_declarations(isset($op[2]) ? qw_se_object($op[1], $op[2]) : $op[1]),
            'mutateSource' => $source->add_declaration($op[1], $op[2]),
        };
    }
    return [$r->get_css(), $r->get_css(true), $r->get_css(true, 1), $r->get_css(true, 2)];
}

/**
 * Store ops on a fresh registry. "get_store" only fetches (and so creates) a store; "add_rule" records whether
 * add_rule returned null.
 */
function qw_se_store(array $ops): array
{
    $nulls = [];
    foreach ($ops as $op) {
        switch ($op[0]) {
            case 'get_store':
                WP_Style_Engine_CSS_Rules_Store::get_store($op[1]);
                break;
            case 'add_rule':
                $r = WP_Style_Engine_CSS_Rules_Store::get_store($op[1])->add_rule($op[2], $op[3] ?? '');
                if ($r && ($op[4] ?? null) !== null) {
                    $r->add_declarations($op[4]);
                }
                $nulls[] = null === $r;
                break;
            case 'remove_rule':
                WP_Style_Engine_CSS_Rules_Store::get_store($op[1])->remove_rule($op[2]);
                break;
            case 'store_css_rule':
                WP_Style_Engine::store_css_rule($op[1], $op[2], $op[3], $op[4] ?? '');
                break;
        }
    }
    $rules = [];
    foreach (WP_Style_Engine_CSS_Rules_Store::get_stores() as $name => $store) {
        $list = [];
        foreach ($store->get_all_rules() as $key => $rule) {
            $d = $rule->get_declarations();
            $list[] = [
                (string) $key,
                $rule->get_selector(),
                $rule->get_rules_group(),
                $d ? $d->get_declarations() : [],
                $d ? qw_se_important($d) : [],
            ];
        }
        $rules[(string) $name] = $list;
    }
    return ['nulls' => $nulls, 'stores' => qw_se_keys(WP_Style_Engine_CSS_Rules_Store::get_stores()), 'rules' => $rules];
}

qw_run(function ($in) {
    WP_Style_Engine_CSS_Rules_Store::remove_all_stores();
    return ['out' => match ($in['fn']) {
        // Task 1
        'kebab' => _wp_to_kebab_case($in['in']),
        'sanitize_key' => sanitize_key($in['in']),
        'protected' => qw_protected($in['in']['name'], $in['in']['args']),
        // Task 2
        'declarations' => qw_se_declarations($in['in']),
        'rule' => qw_se_rule($in['in']),
        'store' => qw_se_store($in['in']),
        'compile_css' => WP_Style_Engine::compile_css($in['in'][0], $in['in'][1]),
        'safecss' => safecss_filter_attr($in['in']),
    }];
});
