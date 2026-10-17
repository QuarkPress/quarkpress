<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Runs WordPress 7.1.3's export_wp() once, for a full export ('content' => 'all') of a JSON dataset, and
 * prints the WXR file:
 *   php export-child.php <wordpress-src-dir> <abspath-dir> <dataset.json>
 * export_wp() declares its helper functions inside its own body, so one process can run it only once; the
 * driver (export-driver.php) starts one child per case. Data access is stubbed from the dataset (posts,
 * users, terms, meta, comments by the query shapes export_wp issues); the WordPress files that shape the
 * output (export.php, formatting.php, functions.php, kses.php, the UTF-8 helpers, plugin.php and four
 * functions of general-template.php) are the real, pinned ones. <abspath-dir> holds an empty
 * wp-includes/option.php, which functions.php requires.
 */

error_reporting(E_ALL & ~E_DEPRECATED);
ini_set('display_errors', 'stderr');

$qp_wp = rtrim($argv[1], '/') . '/';
define('ABSPATH', rtrim($argv[2], '/') . '/');
define('WPINC', 'wp-includes');

function qp_objects(array $rows): array
{
    return array_map(fn ($row) => (object) $row, $rows);
}

function qp_dataset(array $d): array
{
    $d['users'] = array_map(fn ($u) => (object) $u, $d['users']);
    foreach (['categories', 'tags', 'terms', 'nav_menus', 'posts'] as $k) {
        $d[$k] = qp_objects($d[$k]);
    }
    foreach (['termmeta', 'postmeta', 'comments', 'commentmeta', 'relationships'] as $k) {
        $d[$k] = array_map('qp_objects', $d[$k]);
    }
    return $d;
}

$GLOBALS['DATA'] = qp_dataset(json_decode(file_get_contents($argv[3]), true, 512, JSON_THROW_ON_ERROR));

function __($text, $domain = 'default') { return $text; }
function _x($text, $context, $domain = 'default') { return $text; }
function is_admin() { return false; }
function absint($value) { return abs((int) $value); } // wp-includes/load.php, not loaded here
function _is_utf8_charset($charset) { return is_string($charset) && in_array(strtolower($charset), ['utf-8', 'utf8'], true); }
function wp_parse_url($url, $component = -1) { return parse_url($url, $component); }
function get_option($option, $default = false)
{
    $options = ['blog_charset' => $GLOBALS['DATA']['charset'], 'timezone_string' => 'UTC', 'gmt_offset' => 0];
    return array_key_exists($option, $options) ? $options[$option] : $default;
}
function get_bloginfo($show = '', $filter = 'raw')
{
    $site = $GLOBALS['DATA']['site'];
    $value = match ($show) {
        'url', 'home' => $site['url'],
        'name' => $site['name'],
        'description' => $site['description'],
        'language' => $site['language'],
        'charset' => $GLOBALS['DATA']['charset'],
        'version' => '7.1.3',
        default => '',
    };
    if ('display' === $filter) {
        $value = in_array($show, ['url', 'home'], true)
            ? apply_filters('bloginfo_url', $value, $show)
            : apply_filters('bloginfo', $value, $show);
    }
    return $value;
}
function get_bloginfo_rss($show = '') { return apply_filters('get_bloginfo_rss', convert_chars(strip_tags(get_bloginfo($show, 'display'))), $show); }
function bloginfo_rss($show = '') { echo apply_filters('bloginfo_rss', get_bloginfo_rss($show), $show); }
function is_multisite() { return false; }
function network_home_url() { return $GLOBALS['DATA']['site']['url']; }
class WP_Comment { public function __construct($c) { foreach ((array) $c as $k => $v) { $this->$k = $v; } } }
function get_comment($c) { return new WP_Comment($c); }
function post_type_exists($type) { return isset($GLOBALS['DATA']['post_types'][$type]); }
function get_post_type_object($type) { return (object) ['can_export' => $GLOBALS['DATA']['post_types'][$type]]; }
function get_post_types($args) { return array_keys(array_filter($GLOBALS['DATA']['post_types'])); }
function get_categories($args) { return $GLOBALS['DATA']['categories']; }
function get_tags($args) { return $GLOBALS['DATA']['tags']; }
function get_taxonomies($args) { return array_values(array_unique(array_map(fn ($t) => $t->taxonomy, $GLOBALS['DATA']['terms']))); }
function get_terms($args) { return $GLOBALS['DATA']['terms']; }
function wp_get_nav_menus() { return $GLOBALS['DATA']['nav_menus']; }
function get_userdata($id) { return $GLOBALS['DATA']['users'][$id] ?? false; }
function get_post($post = null) { return $GLOBALS['post']; }
function setup_postdata($post) { $GLOBALS['post'] = $post; }
function get_object_taxonomies($type) { return $GLOBALS['DATA']['object_taxonomies'][$type] ?? []; }
function wp_get_object_terms($id, $taxonomies) { return $GLOBALS['DATA']['relationships'][$id] ?? []; }
function the_permalink_rss() { echo esc_url(apply_filters('the_permalink_rss', $GLOBALS['DATA']['permalinks'][$GLOBALS['post']->ID])); }
function the_guid() { $p = $GLOBALS['post']; echo apply_filters('the_guid', $p->guid, $p->ID); }
function get_the_author_meta($field) { $u = get_userdata($GLOBALS['post']->post_author); return $u ? $u->user_login : ''; }
function is_sticky($id) { return in_array($id, $GLOBALS['DATA']['sticky'], true); }
function wp_get_attachment_url($id) { return $GLOBALS['DATA']['attachment_urls'][$id]; }

/** Answers the query shapes export_wp() issues from the dataset. */
final class QpWpdb
{
    public $posts = 'wp_posts', $postmeta = 'wp_postmeta', $comments = 'wp_comments', $commentmeta = 'wp_commentmeta',
        $termmeta = 'wp_termmeta', $term_relationships = 'wp_term_relationships';

    public function prepare($query, ...$args)
    {
        if (1 === count($args) && is_array($args[0])) {
            $args = $args[0];
        }
        $i = 0;
        return preg_replace_callback('/%[sd]/', function ($m) use (&$i, $args) {
            $v = $args[$i++];
            return '%d' === $m[0] ? (string) (int) $v : "'" . addslashes((string) $v) . "'";
        }, $query);
    }

    private function postsMatching(string $q): array
    {
        $out = [];
        foreach ($GLOBALS['DATA']['posts'] as $p) {
            if (preg_match('/post_type IN \(([^)]*)\)/', $q, $m)
                && !in_array("'" . $p->post_type . "'", array_map('trim', explode(',', $m[1])), true)) {
                continue;
            }
            if (preg_match("/post_type = '([^']*)'/", $q, $m) && $p->post_type !== $m[1]) {
                continue;
            }
            if (str_contains($q, "post_status != 'auto-draft'") && 'auto-draft' === $p->post_status) {
                continue;
            }
            if (preg_match('/ID IN \(([^)]*)\)/', $q, $m)
                && !in_array((string) $p->ID, array_map('trim', explode(',', $m[1])), true)) {
                continue;
            }
            $out[] = $p;
        }
        return $out;
    }

    public function get_col($q)
    {
        return str_starts_with($q, 'SELECT ID FROM wp_posts') ? array_map(fn ($p) => $p->ID, $this->postsMatching($q)) : [];
    }

    public function get_results($q)
    {
        $d = $GLOBALS['DATA'];
        if (str_contains($q, 'SELECT DISTINCT post_author')) {
            $authors = [];
            foreach ($this->postsMatching($q) as $p) {
                $authors[$p->post_author] = (object) ['post_author' => $p->post_author];
            }
            return array_values($authors);
        }
        if (preg_match('/FROM wp_termmeta WHERE term_id = (\d+)/', $q, $m)) {
            return $d['termmeta'][$m[1]] ?? [];
        }
        if (preg_match('/FROM wp_postmeta WHERE post_id = (\d+)/', $q, $m)) {
            return $d['postmeta'][$m[1]] ?? [];
        }
        if (preg_match('/FROM wp_commentmeta WHERE comment_id = (\d+)/', $q, $m)) {
            return $d['commentmeta'][$m[1]] ?? [];
        }
        if (preg_match('/FROM wp_comments WHERE comment_post_ID = (\d+)/', $q, $m)) {
            return array_values(array_filter($d['comments'][$m[1]] ?? [], fn ($c) => 'spam' !== $c->comment_approved));
        }
        if (str_starts_with($q, 'SELECT * FROM wp_posts')) {
            return $this->postsMatching($q);
        }
        throw new RuntimeException('unhandled query: ' . $q);
    }
}

require $qp_wp . 'wp-includes/plugin.php';
require $qp_wp . 'wp-includes/formatting.php';
require $qp_wp . 'wp-includes/functions.php';
require getenv('QP_ORACLE_HOME') . '/extract-functions.php';
qp_load_functions($qp_wp . 'wp-includes/general-template.php', ['get_post_time', 'get_post_datetime', 'get_the_generator', 'the_generator']);
require $qp_wp . 'wp-includes/kses.php';
require $qp_wp . 'wp-includes/compat-utf8.php';
require $qp_wp . 'wp-includes/utf8.php';

// Default filters of wp-includes/default-filters.php that the export output depends on (wptexturize and the
// site icon are not reproduced; see D11).
add_filter('bloginfo_rss', 'ent2ncr', 8);
add_filter('the_guid', 'esc_url');
add_action('rss2_head', 'the_generator');
add_filter('bloginfo', 'convert_chars');
add_filter('bloginfo', 'esc_html');

$GLOBALS['wpdb'] = new QpWpdb();
$GLOBALS['wp_query'] = new stdClass();
require $qp_wp . 'wp-admin/includes/export.php';
export_wp();
