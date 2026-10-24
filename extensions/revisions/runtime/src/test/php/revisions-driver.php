<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the revisions module. The process defines WP_POST_REVISIONS from the environment variable
 * QP_WP_POST_REVISIONS ("true", "false", or a text cast as PHP casts a constant's value: "5", "-1", "abc", "2.9"),
 * since a constant cannot change within a process; the test runs one process per value. The fields op also needs a
 * process of its own per filter: _wp_post_revision_fields keeps the filtered result in its static.
 *   {"op":"keep","type":...,"supports":{...},"generic":...,"typed":...}
 *       wp_revisions_to_keep and wp_revisions_enabled for a post of the type, with post_type_supports answering
 *       from the supports map (type => list), and the optional filters wp_revisions_to_keep (generic) and
 *       wp_{type}_revisions_to_keep (typed) returning the given values
 *   {"op":"fields","add":[...],"remove":[...]}
 *       _wp_post_revision_fields with a filter that adds and removes keys (no filter when both are empty)
 *   {"op":"data","post":{...},"autosave":bool}
 *       _wp_post_revision_data of the array
 *   {"op":"version","name":...}
 *       _wp_get_post_revision_version of an object with that post_name, or of the text when name starts with
 *       "text:"
 *   {"op":"normalize","text":...}
 *       normalize_whitespace(text)
 *   {"op":"changed","last":{...},"post":{...},"remove":[...]}
 *       the per-field decision of wp_save_post_revision (driver-applied rule, see qp_changed)
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'functions.php', ['wp_parse_args', 'maybe_serialize', 'is_serialized']);
qp_load_functions(QP_WP . 'formatting.php', ['normalize_whitespace']);
qp_load_functions(QP_WP . 'revision.php', [
    '_wp_post_revision_fields', '_wp_post_revision_data', '_wp_get_post_revision_version', 'wp_revisions_to_keep',
    'wp_revisions_enabled',
]);

define('OBJECT', 'OBJECT');
define('ARRAY_A', 'ARRAY_A');

$qp_constant = getenv('QP_WP_POST_REVISIONS');
if (false === $qp_constant) {
    $qp_constant = 'true';
}
define('WP_POST_REVISIONS', match ($qp_constant) {
    'true' => true,
    'false' => false,
    default => $qp_constant,
});

/** Stub: post_type_supports answers from the case's supports map. */
function post_type_supports($post_type, $feature)
{
    return in_array($feature, $GLOBALS['qp_supports'][$post_type] ?? [], true);
}

/** Stub: get_post hands back the object it is given, as an array on request (the cases build the posts). */
function get_post($post = null, $output = OBJECT, $filter = 'raw')
{
    if (!is_object($post)) {
        return null;
    }
    return ARRAY_A === $output ? get_object_vars($post) : $post;
}

function qp_post_object(array $fields): stdClass
{
    $post = new stdClass();
    foreach ($fields as $key => $value) {
        $post->$key = $value;
    }
    return $post;
}

/**
 * Driver-applied rule: the loop of wp_save_post_revision that decides whether the post changed, replayed with
 * WordPress's own functions on two post objects, without the database: for each revisioned field of the post,
 * normalize_whitespace(maybe_serialize(value)) of the post against the same of the latest revision.
 */
function qp_changed(stdClass $last, stdClass $post): bool
{
    foreach (array_keys(_wp_post_revision_fields(get_object_vars($post))) as $field) {
        if (normalize_whitespace(maybe_serialize($post->$field ?? ''))
            !== normalize_whitespace(maybe_serialize($last->$field ?? ''))) {
            return true;
        }
    }
    return false;
}

function qp_fields_filter(array $add, array $remove): ?Closure
{
    if ([] === $add && [] === $remove) {
        return null;
    }
    return static function (array $fields) use ($add, $remove): array {
        foreach ($add as $key) {
            $fields[$key] = $key;
        }
        foreach ($remove as $key) {
            unset($fields[$key]);
        }
        return $fields;
    };
}

qp_run(function (array $in): array {
    $GLOBALS['qp_supports'] = $in['supports'] ?? ['post' => ['title', 'editor', 'revisions']];
    foreach (['_wp_post_revision_fields', 'wp_revisions_to_keep'] as $tag) {
        remove_all_filters($tag);
    }
    switch ($in['op']) {
        case 'keep':
            $type = $in['type'];
            remove_all_filters('wp_' . $type . '_revisions_to_keep');
            if (array_key_exists('generic', $in)) {
                add_filter('wp_revisions_to_keep', static fn($num, $post) => $in['generic'], 10, 2);
            }
            if (array_key_exists('typed', $in)) {
                add_filter('wp_' . $type . '_revisions_to_keep', static fn($num, $post) => $in['typed'], 10, 2);
            }
            $post = qp_post_object(['ID' => 7, 'post_type' => $type, 'post_status' => 'publish']);
            return ['keep' => wp_revisions_to_keep($post), 'enabled' => wp_revisions_enabled($post)];
        case 'fields':
            $filter = qp_fields_filter($in['add'] ?? [], $in['remove'] ?? []);
            if (null !== $filter) {
                add_filter('_wp_post_revision_fields', $filter);
            }
            return ['keys' => array_keys(_wp_post_revision_fields(['ID' => 7, 'post_type' => 'post']))];
        case 'data':
            $data = _wp_post_revision_data($in['post'], (bool) $in['autosave']);
            return ['data' => $data, 'keys' => array_keys($data)];
        case 'version':
            $name = $in['name'];
            $result = str_starts_with($name, 'text:')
                ? _wp_get_post_revision_version(substr($name, 5))
                : _wp_get_post_revision_version(qp_post_object(['post_name' => $name]));
            return ['version' => false === $result ? 'false' : (string) $result];
        case 'normalize':
            return ['value' => normalize_whitespace($in['text'])];
        case 'changed':
            $filter = qp_fields_filter([], $in['remove'] ?? []);
            if (null !== $filter) {
                add_filter('_wp_post_revision_fields', $filter);
            }
            return ['changed' => qp_changed(qp_post_object($in['last']), qp_post_object($in['post']))];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
