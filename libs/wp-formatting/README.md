# quarkword-lib-wp-formatting

WordPress's text formatters for plain Java: `wpautop`, `wptexturize`, `convert_smilies`, `convert_chars`,
`capital_P_dangit`, `force_balance_tags`, `wp_trim_words`, `wp_html_excerpt`, `excerpt_remove_footnotes`, the excerpt
`wp_trim_excerpt` builds, `wp_html_split` and `wp_replace_in_html_tags`, with the output of WordPress 7.1.3, odd cases
included. Immutable, thread-safe, no global state, settings as arguments and builders instead of options and filters.
Every function runs in linear time without regular expressions. One dependency: `quarkword-lib-wp-kses`, for
`wp_strip_all_tags`.

## Coordinates

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.quarkword</groupId>
      <artifactId>quarkword-bom</artifactId>
      <version>${quarkword.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>org.quarkword.lib</groupId>
  <artifactId>quarkword-lib-wp-formatting</artifactId>
</dependency>
```

## Usage

The example composes WordPress's `the_content`, `the_title` and `get_the_excerpt` chains. `Shortcodes` comes from
`quarkword-lib-wp-shortcode`, which provides `shortcode_unautop`, `do_shortcode` and `strip_shortcodes`.

```java
import java.util.function.UnaryOperator;
import org.quarkword.lib.wpformatting.*;
import org.quarkword.lib.wpshortcode.*;

ShortcodeRegistry registry = ShortcodeRegistry.builder()
        .add("year", (shortcode, context) -> "2026")
        .build();
Shortcodes shortcodes = Shortcodes.builder(registry).build();

Texturize texturize = Texturize.builder()
        .registeredShortcodes(registry.names())   // these shortcode tags are left as they are
        .build();
Smilies smilies = Smilies.wordPress("https://example.com/wp-includes/images/smilies/");

// the_content, for content without blocks (wpautop is not run on block content)
UnaryOperator<String> chain = content -> {
    String html = texturize.apply(content);                              // wptexturize        (10)
    html = Formatting.autop(html);                                       // wpautop            (10)
    html = shortcodes.unautop(html);                                     // shortcode_unautop  (10)
    html = Formatting.capitalPDangit(html, CapitalPMode.JUDICIOUS);      // capital_P_dangit   (11)
    html = shortcodes.apply(html);                                       // do_shortcode       (11)
    return smilies.convert(html);                                        // convert_smilies    (20)
};
String body = chain.apply("\"Hello\" -- it's [year].\n\nWordpress :)");

// the_title
String title = texturize.apply("Wordpress & \"friends\"");
title = Formatting.convertChars(title);
title = Formatting.phpTrim(title);
title = Formatting.capitalPDangit(title, CapitalPMode.forFilter("the_title"));   // SIMPLE

// get_the_excerpt (wp_trim_excerpt), then the_excerpt
String postContent = "First paragraph [year]\n\nSecond paragraph";
String excerpt = Excerpts.trimExcerpt("", postContent, ExcerptOptions.builder()
        .shortcodeStripper(shortcodes::strip)                           // strip_shortcodes
        .contentFilter(chain)                                           // the_content without do_blocks
        .build());
excerpt = Formatting.autop(Formatting.convertChars(smilies.convert(texturize.apply(excerpt))));
excerpt = shortcodes.unautop(excerpt);

String words = Excerpts.trimWords("one two three four", 2);               // "one two&hellip;"
String cut = Excerpts.htmlExcerpt("<b>Hello</b> &amp; world", 8, "...");   // "Hello..."
```

## WordPress mapping

| QuarkWord | WordPress |
|---|---|
| `Formatting.autop(text)`, `autop(text, br)` | `wpautop($text, $br)` |
| `Formatting.autop(text, AutopOptions)`, `AutopOptions.Builder.lineBreaks` | `wpautop`, `$br` |
| `AutopOptions.Builder.maxOutputLength` | none: an output cap, see Differences |
| `Texturize.wordPress()`, `Texturize.apply` | `wptexturize` |
| `Texturize.Builder.strings(TexturizeStrings)` | the translated quote, prime, dash and apostrophe strings, and `$cockney` |
| `Texturize.Builder.enabled` | `run_wptexturize` |
| `Texturize.Builder.noTexturizeTags`, `Texturize.DEFAULT_NO_TEXTURIZE_TAGS` | `no_texturize_tags` |
| `Texturize.Builder.noTexturizeShortcodes`, `Texturize.DEFAULT_NO_TEXTURIZE_SHORTCODES` | `no_texturize_shortcodes` |
| `Texturize.Builder.registeredShortcodes` | the registered names in `$shortcode_tags` |
| `TexturizeStrings.english()`, `withCockney` | the en_US strings, `$cockney` and `$cockneyreplace` |
| `Smilies.wordPress(base)`, `Smilies.convert` | `convert_smilies` with the default `$wpsmiliestrans` |
| `Smilies.Builder.enabled` | `use_smilies` |
| `Smilies.Builder.imageBaseUrl` | `includes_url('images/smilies/')` |
| `Smilies.codes()` | the keys of the default `$wpsmiliestrans` |
| `Formatting.convertChars` | `convert_chars` |
| `Formatting.capitalPDangit(text, mode[, strings])` | `capital_P_dangit` |
| `CapitalPMode.forFilter(name)` | the `current_filter()` test in `capital_P_dangit` |
| `Formatting.forceBalanceTags` | `force_balance_tags` |
| `Formatting.balanceTags(text, force, useBalanceTagsOption)` | `balanceTags($text, $force)` with the `use_balanceTags` option |
| `Formatting.phpTrim` | PHP's `trim`, as the `the_title` chain runs it |
| `Formatting.htmlSplit` | `wp_html_split` |
| `Formatting.replaceInHtmlTags` | `wp_replace_in_html_tags` |
| `Excerpts.trimWords(text, numWords[, more[, type, utf8Charset]])` | `wp_trim_words` |
| `WordCountType`, `WordCountType.fromWordPress` | `wp_get_word_count_type()` |
| `Excerpts.htmlExcerpt(text, count[, more])` | `wp_html_excerpt` |
| `Excerpts.removeFootnotes` | `excerpt_remove_footnotes` |
| `Excerpts.trimExcerpt(rawExcerpt, content, options)` | `wp_trim_excerpt` |
| `ExcerptOptions.Builder.shortcodeStripper` | `strip_shortcodes` |
| `ExcerptOptions.Builder.blockRemover` | `excerpt_remove_blocks` |
| `ExcerptOptions.Builder.contentFilter` | `apply_filters('the_content')` without `do_blocks` and `wp_filter_content_tags` |
| `ExcerptOptions.Builder.length` | `excerpt_length` |
| `ExcerptOptions.Builder.more` | `excerpt_more` |
| `ExcerptOptions.Builder.wordCountType`, `utf8Charset` | `wp_get_word_count_type()`, the `blog_charset` UTF-8 test |
| `Excerpts.DEFAULT_WORDS`, `DEFAULT_MORE`, `DEFAULT_EXCERPT_MORE` | 55, `&hellip;`, `' [&hellip;]'` |

### Options and defaults

| Setting | Default |
|---|---|
| `AutopOptions.lineBreaks` | `true` |
| `AutopOptions.maxOutputLength` | empty: the larger of 16 MiB (`DEFAULT_MIN_OUTPUT_LENGTH`, in chars) and 64 times the input (`DEFAULT_OUTPUT_RATIO`) |
| `Texturize.Builder.strings` | `TexturizeStrings.english()`: `&#8220;` `&#8221;` `&#8217;` `&#8242;` `&#8243;` `&#8216;` `&#8217;` `&#8211;` `&#8212;`, and the 11 English cockney words |
| `Texturize.Builder.enabled` | `true` |
| `Texturize.Builder.noTexturizeTags` | `pre`, `code`, `kbd`, `style`, `script`, `tt` |
| `Texturize.Builder.noTexturizeShortcodes` | `code` |
| `Texturize.Builder.registeredShortcodes` | none |
| `Smilies.Builder.enabled` | `true` |
| `Smilies.Builder.imageBaseUrl` | none; required when enabled (`build` throws `IllegalStateException`) |
| `ExcerptOptions` hooks | identity: no shortcodes, blocks or content filters |
| `ExcerptOptions.Builder.length` | 55 |
| `ExcerptOptions.Builder.more` | `" [&hellip;]"` (`null` also means this) |
| `ExcerptOptions.Builder.wordCountType` | `WordCountType.WORDS` |
| `ExcerptOptions.Builder.utf8Charset` | `true` |

`FormattingLimitException` (unchecked) reports `requiredLength()` and `allowedLength()`. `TexturizeStrings` rejects a
string equal to the ASCII character it replaces and an empty closing quote. `null` arguments throw
`NullPointerException`, except a `more` text, where `null` means WordPress's default.

## Composing the WordPress filter chains

The library offers the functions; the caller runs them in WordPress's default order. Other filters in these chains,
such as `do_blocks`, `make_clickable` and `wp_filter_content_tags`, belong to other modules.

- `the_content`: texturize → autop → `Shortcodes.unautop` (lib-wp-shortcode) → `capitalPDangit(JUDICIOUS)` →
  `do_shortcode` (`Shortcodes.apply`) → smilies. Autop is skipped for block content; that is the caller's decision.
- `the_excerpt`: texturize → smilies → `convertChars` → autop → `Shortcodes.unautop`.
- `the_title`: texturize → `convertChars` → `phpTrim` → `capitalPDangit(SIMPLE)`.
- `comment_text`: texturize → `convertChars` → smilies → `forceBalanceTags` → autop → `capitalPDangit(JUDICIOUS)`.
- `get_the_excerpt`: `Excerpts.trimExcerpt` with the `shortcodeStripper` (`Shortcodes.strip`), `blockRemover` and
  `contentFilter` hooks. A generated excerpt therefore passes through texturize twice, once in `contentFilter` and once
  in `the_excerpt`, as in WordPress.

## Behavior worth knowing

All of it is WordPress's behavior, reproduced on purpose:

- wpautop matches block-tag names as prefixes: `<link>` counts as `<li>`, `<track>` as `<tr>`, and `<picture>`,
  `<progress>` and `<source>` match too. So `a\n\n<track>\n\nb` gives `<p>a<track>b</p>`, and
  `<p><link rel=x></p>` loses its paragraph.
- A `<pre wp-pre-tag-N></pre>` typed by an author is replaced by the text of a real `<pre>` block:
  `<pre wp-pre-tag-1></pre>`, a blank line and `<pre>x</pre>` give `<pre>x</pre>` twice.
- A blank line inside `<script>` or `<style>` splits it into two paragraphs, which breaks the script.
- A stray `<` with no later `>`, or an unclosed `<!--`, swallows the rest of the text: no paragraphs are made after it.
- Texturize reads tag names crudely: a self-closed `<code />` opens `code` and disables texturize for the rest of the
  text. `textarea` content is texturized.
- Texturize applies its `&` fix inside `<script>`: `if (a<b && c>d)` becomes `if (a<b &#038;&#038; c>d)`.
- A quote after a non-ASCII character closes: `日本"語"` gives two closing quotes.
- `[code]` keeps its content plain only when `code` is among the registered shortcodes.
- `convertChars` and texturize disagree about `&`: `convertChars` keeps `&abcdefghi;` and escapes `&a;`; texturize does
  the opposite. Both escape an `&` before a non-ASCII character, and `convertChars` keeps `&&amp;`.
- `capitalPDangit` in `JUDICIOUS` mode leaves a "Wordpress" at the very start of the text and inside a word;
  `SIMPLE` corrects every one.
- `trimWords` with −1 or −2 keeps every word but the last, below −2 it keeps none; in character mode
  `CHARACTERS_EXCLUDING_SPACES` still counts the single spaces left between words.
- Only `:mrgreen:` becomes an `<img>`; the other 42 smileys become emoji. A code needs a line start or space before it
  and a line end or space after it, and only a line feed ends a line.

## Differences from WordPress

Each is listed in `src/test/resources/wp-parity-skip.txt`.

| Item | WordPress | QuarkWord | Why |
|---|---|---|---|
| D1 long `<li` paragraph | `wpautop` returns `""` from 1,000,000 characters after `<li>`, from 166,674 for repeated `<p><li` | formats it in linear time | PCRE backtrack limit |
| D2 unclosed `<script>`, `<style>`, `<svg>` or `<math>` | `wpautop` returns `""` when 999,993 characters follow, or 333,336 of repeated `x` and a blank line | formats it | PCRE backtrack limit |
| D3 typed `<pre>` placeholders that chain | output grows with the square of the input until PHP's `memory_limit` kills the request | measures the output first and throws `FormattingLimitException` above `AutopOptions` `maxOutputLength` (default: the larger of 16 MiB and 64 × the input) | bounded memory |
| D4 `run_wptexturize` | one `false` answer disables texturize for the rest of the process, even across resets | per instance, `Texturize.Builder.enabled` | no global state |
| D5 comment holding 999,996 or more hyphens | the texturize split fails and PHP throws a `TypeError` | the comment stays as it is, the text around it is texturized | PCRE backtrack limit |
| D6 product such as `1x1…` whose second number runs 999,999 or more digits into a letter or `_` | `wptexturize` returns `""` | the product is left unchanged | PCRE backtrack limit |
| D7 `$1`, `${1}` or `\1` in the prime, double-prime, closing-quote and dash strings; an empty closing quote | read as regex backreferences; an empty closing quote fails | inserted literally; an empty closing quote is rejected | strings are text |
| D8 `capital_P_dangit` mode and quote | mode from the running filter; the quote translated once per request, kept after a locale switch | `CapitalPMode` and `TexturizeStrings` arguments | no global state |
| D9 smiley table and image URL | filterable, presettable `$wpsmiliestrans`; URL through `smilies_src` and `esc_url` | WordPress's 43 default codes; the caller's base URL, already escaped, used verbatim | no filters; the library cannot know the site URL |
| D10 excerpt settings and steps | locale's word-count type, `blog_charset`, `get_the_content`, `the_content` and the `wp_trim_words`, `excerpt_length`, `excerpt_more` and `wp_trim_excerpt` filters | arguments and three caller hooks | no global state |
| D11 custom-element-like name with 18 or more characters after its first hyphen that does not end a tag | `force_balance_tags` copies the rest of the text unbalanced and closes only the elements already open | balances the rest in linear time | PCRE backtrack limit |
| D12 invalid UTF-8 and lone surrogates | works on UTF-8 bytes; invalid bytes reach the functions | text is UTF-16, so invalid UTF-8 cannot reach the library; a lone surrogate passes through as one code point, except in `trimWords`, `htmlExcerpt` and `trimExcerpt`, which strip tags with lib-wp-kses and turn it into U+FFFD | Java strings |

For valid text the UTF-16 and UTF-8 results are the same. Counts in `trimWords` and `htmlExcerpt` are in code points,
so a cut never splits a surrogate pair.

## Performance and safety

Every function is linear in its input. The scanners are hand-written over `String` and `StringBuilder`, with no
`java.util.regex` anywhere in the main code (a test enforces this), forward searches that never rescan, and explicit
stacks instead of recursion. The cases where WordPress's regular expressions give up (D1, D2, D5, D6, D11) are
formatted normally here. The only output that can grow faster than the input, typed `<pre>` placeholders, is measured
before it is built and capped (D3).

The hostile-input tests run each function on inputs of about 1 MB within 10 seconds and with allocation bounded by
200 bytes per input char plus 10 MB: runs of `<`, `<!--`, `<![CDATA[`, `<pre`, `<script`, `[code `, `&`, quotes,
hyphens, digits, spaces, smiley codes and nested or unclosed tags, and the backtrack-limit shapes above. Their exact
outputs are pinned.

## Parity testing

`mvn verify -Pwp-parity` (needs `php` 8.4 and network) downloads the pinned WordPress 7.1.3 files into
`.cache/wp-source/` and compares every formatter and the `the_content`, `the_title` and excerpt chains with
WordPress, on the behavior references' examples and the large shapes in `src/test/resources/wp-parity-cases.json`
(about 550 cases, also checked without `php` in the normal build) and on about 18,000 generated inputs. Differences
are shrunk to a small input and written to `target/wp-parity-failures.json`. The run ends with a line such as
`lib-wp-formatting parity: 19129 cases, 19668 comparisons, 0 differences`. In CI the parity step also fails when
the formatting parity test did not run or was skipped (`.github/scripts/require-tests-ran.sh`). See `testing/wp-oracle`.

## Source policy

Written from a prose description of WordPress's observable behavior and from WordPress's output. WordPress PHP
was read to understand that behavior and runs as a test oracle; no WordPress code, regular expression or
comment is copied (QUARKWORD-SPEC.md §13).
