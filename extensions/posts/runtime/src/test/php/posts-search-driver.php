<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the search parsing of the posts module. It loads only WP_Query, always, so it does not depend on
 * which WordPress files other tests have fetched.
 *   {"op":"search_terms","s":...,"sentence":...}                     the terms, raw token count and number of positive
 *                                                                    terms WP_Query's search parsing gives for s, and
 *                                                                    its ordering text
 *   {"op":"stopwords"}                                               WP_Query's English stopword list
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
require QP_WP . 'class-wp-query.php';

/** The search parsing of WP_Query, which is protected, reached from a subclass. */
final class QpSearchQuery extends WP_Query
{
    public function qp_terms(string $search, bool $sentence): array
    {
        // Driver-applied rule: query variables arrive slashed, as WordPress's request parsing leaves them.
        $vars = ['s' => addslashes($search), 'sentence' => $sentence, 'exact' => false, 'search_columns' => []];
        $this->parse_search($vars);
        return [
            'terms' => array_values($vars['search_terms']),
            'count' => $vars['search_terms_count'],
            'positive' => count($vars['search_orderby_title']),
            // The rank gates, from WordPress's own ordering text (the database stub leaves the SQL text unfilled).
            'order' => $this->parse_search_order($vars),
        ];
    }

    public function qp_stopwords(): array
    {
        return $this->get_search_stopwords();
    }
}

/** The parts of the database object the search parsing calls to build SQL text, which the driver ignores. */
final class QpSearchWpdb
{
    public string $posts = 'wp_posts';

    public function esc_like($text)
    {
        return addcslashes((string) $text, '_%\\');
    }

    public function prepare($query, ...$args)
    {
        return $query;
    }
}

/** Stub (driver-applied): translation returns the English text, as for the en_US locale. */
function _x($text, $context, $domain = 'default')
{
    return $text;
}

/** Stub (driver-applied): searches run for a visitor, so the password clause is added and ignored. */
function is_user_logged_in()
{
    return false;
}

qp_run(function (array $in): array {
    switch ($in['op']) {
        case 'search_terms':
            $GLOBALS['wpdb'] = new QpSearchWpdb();
            return (new QpSearchQuery())->qp_terms($in['s'], (bool) $in['sentence']);
        case 'stopwords':
            return ['words' => (new QpSearchQuery())->qp_stopwords()];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
