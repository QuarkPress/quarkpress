# quarkpress-lib-wp-kses

A plain-Java version of WordPress's kses HTML sanitizer: the same allowlists, URL-protocol handling,
inline-CSS filtering and output bytes as WordPress 7.1.3, so content saved or rendered through
QuarkPress is as safe as, and identical to, what WordPress produces. It also provides the attribute
sanitizer that `lib-wp-shortcode` needs. One dependency (`quarkpress-lib-wp-blocks`, for WordPress's
block-attribute pre-filter, which brings Jackson), no reflection, no Quarkus.

## Coordinates

```xml
<dependency>
  <groupId>org.quarkpress.lib</groupId>
  <artifactId>quarkpress-lib-wp-kses</artifactId>
</dependency>
```

The version comes from `quarkpress-bom`.

## Usage

```java
import java.util.Optional;
import org.quarkpress.lib.wpkses.*;

Kses kses = Kses.wordPress();                    // WordPress's defaults, default filters on
String safe = kses.post(html);                   // wp_kses_post
String data = kses.data(html);                   // wp_kses_data
String bio  = kses.sanitize(html, KsesContext.USER_DESCRIPTION);
String own  = kses.sanitize(html, Allowlist.builder().attribute("a", "href").element("b").build());
String url  = kses.badProtocol(value);           // wp_kses_bad_protocol
String css  = kses.safeCss(style);               // safecss_filter_attr
Optional<String> attr = kses.oneAttr("a", "href=\"javascript:x\""); // wp_kses_one_attr; empty = nothing safe
String text = Kses.stripAllTags(html, false);    // wp_strip_all_tags
String norm = Kses.normalizeEntities(text);      // wp_kses_normalize_entities

// lib-wp-shortcode: HtmlAttributeSanitizer sanitizer = kses::oneAttr;
```

`null` arguments throw `NullPointerException`; no input throws otherwise.

## Configuration

Every WordPress filter that changes kses becomes a typed builder option, set once per instance:

| WordPress | `Kses.Builder` |
|---|---|
| `wp_kses_allowed_html` | `allowedHtmlFilter(AllowedHtmlFilter)`; `allowlist(KsesContext, Allowlist)` replaces a context's table |
| `kses_allowed_protocols` | `protocols(List<String>)` |
| `wp_kses_uri_attributes` | `uriAttributes(List<String>)` |
| `safe_style_css` | `safeStyleProperties(List<String>)` (an empty list keeps every declaration unfiltered, as in WordPress; it does not mean "allow no CSS") |
| `safecss_filter_attr_allow_css` | `cssAllowFilter(CssAllowFilter)` |
| `pre_kses` | `preFilter(PreKsesFilter)` |
| default filters (`wp_pre_kses_less_than`, `wp_pre_kses_block_attributes`, `_wp_kses_allow_note_mention_span`) | `preFilters(boolean)`, default `true`; `false` gives bare kses |
| `wp_upload_dir()` for PDF objects | `uploadBaseUrl(String)`; without it `<object>` never keeps `data`; an invalid URI throws `IllegalArgumentException`, and a host `java.net.URI` cannot parse (for example with underscores) makes PDF objects fail closed |

`Kses` instances are immutable and thread-safe. Built-in contexts and contexts registered with
`allowlist(...)` are resolved once, when the instance is built; other context names (for example a
WordPress filter name passed as context) resolve to the data table on each call, after the filters.

## Behaviour

The behaviour is WordPress's, including the parts that surprise:

- Bare kses turns a lone `<` into a tag (`a < b` becomes `a <b>`); WordPress's default less-than filter,
  on in `Kses.wordPress()`, escapes it instead.
- A colon before any `/?` makes the text before it a URL scheme: `/path?q=a:b` becomes `b`;
  `&Colon;` counts as a colon.
- CSS declarations without a colon are kept; `rgb()` is dropped while `calc(1px & 2)` passes.
- A protocol list without `http` turns `http://x` into `//x`.
- `<!DOCTYPE html>` is removed, `<!doctype html>` kept.
- kses is not idempotent: a second pass can change WordPress's own output (in bare mode `<!a<b>>` gains a
  `&gt;` on every pass; with `Kses.wordPress()` the output is stable).

## Differences from WordPress

Checked by the `wp-parity` profile; each row is an entry of `src/test/resources/wp-parity-skip.txt`. Rows that
an input can show are pinned by cases in `src/test/resources/wp-parity-cases.json` or `WpKsesParityTest`; an
entry fails the build once none of the comparisons it covers differs from WordPress any more (the check is per
entry, not per case). Generated WordPress-mode soups often contain comments such as `<!-- wp:p >`
(difference 11); they are compared under a separate aspect that accepts only neutralized delimiters, so any
other change in them still fails.

| # | QuarkPress | WordPress | Reason |
|---|---|---|---|
| 1 | lone UTF-16 surrogates become U+FFFD | invalid UTF-8 bytes pass through | a Java `String` cannot hold invalid UTF-8 |
| 2 | with the `entities` context, a numeric start tag such as `<5>` or `<12 a=1>` is removed | PHP `TypeError` | fail safe |
| 3 | CSS of any size or nesting is processed, in linear time | PCRE's backtracking and recursion limits drop the declaration | robustness |
| 4 | allowed-HTML filters run once per instance (built-in and registered contexts) | once per tag | no runtime hooks; visible only through filter side effects |
| 5 | the protocol list belongs to the instance | a static cache frozen after `wp_loaded` | no global state |
| 6 | PDF objects need `uploadBaseUrl` | `wp_upload_dir()` | no WordPress runtime |
| 7 | the block pre-filter uses lib-wp-blocks, so its differences apply; for example attribute keys that become `0..n-1` stay an object, because a list would no longer read back as block attributes | PHP writes a list (`["a"]`) | reuse; no data loss |
| 8 | a non-empty array or object `tagName` in `core/template-part` becomes `""`; a numeric character reference beyond PHP's integer range in a URL scheme is decoded modulo 256, so the scheme survives only if the decoded text is an allowed protocol | PHP `TypeError` | fail safe |
| 9 | comments that WordPress keeps rewriting are dropped after 64 rounds without a fixed point. Comment recursion is also bounded by a depth of 32 and a work budget of 32 characters per input character (at least 1 Mi); past either, the comment is dropped, which can also drop comments in very deep legitimate nesting | never finishes on inputs such as `a<!-- <!a<b>> -->b` (bare mode; the default less-than filter avoids the known cases) | linear time; every call ends |
| 10 | when block markup exceeds the block parser's limits (nesting beyond 1024, or recovery of unclosed blocks that would repeat too much text), every block delimiter is neutralized (its `<` written as `&lt;`), then kses runs normally | parses it, with output that can grow quadratically | linear time; no unfiltered block attributes |
| 11 | with the block pre-filter on, a block delimiter that the filter did not process is neutralized the same way: (a) delimiters in the recovered text of unclosed blocks and after a closer that closes nothing, plain closers in that text included; (b) comments that kses rebuilds into a delimiter (for example `<!--\nwp:p {...} --->`); (c) comments that start like a delimiter but are not a complete delimiter on their own, such as `<!-- wp:more text -->` or `<!-- wp:p {"a":"x"}-->`, even when nothing later completes them; (d) closers with JSON (`<!-- /wp:p {"a":1} -->`) in classic content | leaves them live, some with unfiltered attributes | no unfiltered block attributes reach a renderer |
| 12 | above 32 `url()` or gradient removals in one declaration, matched spans are removed instead of every occurrence of each text, and the declaration is dropped if a removed text still occurs: some pathological style values that WordPress keeps are dropped | removes every occurrence with `str_replace` | linear time; fails closed |
| 13 | a `pre_kses` filter (`preFilter`) always runs after the default pre-filters | the block-attribute filter re-adds itself at the end of priority 10, so from the second kses call on, a host filter at priority 10 runs before it | fixed order, no global hook state |
| 14 | the attribute parser reads an unquoted value past `>` (`x=a>b c=d` gives `x` and `c`) | stops at `>` (`x=a` only) | unreachable: kses's tokenizer ends the attribute text at `>`, and `oneAttr` (whose text can contain `>`, as in `title=a>b c=d`) splits on `=` itself and never uses the parser |

## Parity testing

`mvn verify -Pwp-parity` (needs `php` 8.4 with the `pcntl` extension, and network) downloads the
pinned WordPress 7.1.3 files into the repository's `target/wp-source/` and compares, through
`testing/wp-oracle`: the data tables (`WpKsesTablesParityTest`), every reference example in
`src/test/resources/examples/` (WordPress must still give the recorded value), the ruling and edge cases
(`wp-parity-cases.json`), the XSS corpus (`xss-corpus.json`) in three settings, and fixed-seed
generated inputs for every function, in bare and WordPress mode (`WpKsesParityTest`). WordPress cases
that run longer than 2 seconds (difference 9) are stopped with `pcntl_alarm`.

Data that comes from WordPress is regenerated, then reviewed as a diff, after a WordPress bump:

- `src/main/resources/org/quarkpress/lib/wpkses/wordpress-tables.txt`:
  `php src/test/php/export-tables.php <wordpress-src-dir>`
- `src/test/resources/examples/*.json`:
  `php src/test/php/kses-examples.php <wordpress-src-dir> src/test/resources/examples` (needs `iconv`)
- `src/main/resources/org/quarkpress/lib/wpkses/html5-named-references.txt`:
  `php src/test/php/export-html5-references.php <wordpress-src-dir>` (needs `iconv`)

`<wordpress-src-dir>` is the repository's `target/wp-source/7.1.3` after a parity run.

## Source policy

Written from a description of WordPress's observable behaviour, WordPress's output and the HTML5
specification. WordPress PHP was read to understand that behaviour and runs only as a test oracle; no
WordPress code is copied (QUARKPRESS-SPEC.md §13). The tag-stripping state machine inside `stripAllTags` reproduces
the behaviour of PHP's own C `strip_tags` function (PHP License), which WordPress calls; it is not WordPress code.
