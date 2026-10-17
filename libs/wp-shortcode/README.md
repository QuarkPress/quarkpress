# quarkpress-lib-wp-shortcode

WordPress shortcodes for plain Java: register handlers, then expand, strip, find and parse `[shortcodes]`
exactly as WordPress 7.1.3 does — same matches, same attributes, same output, same edge cases. Immutable,
thread-safe, no global registry, typed hooks instead of string-named filters. Zero dependencies.

## Coordinates

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.quarkpress</groupId>
      <artifactId>quarkpress-bom</artifactId>
      <version>${quarkpress.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>org.quarkpress.lib</groupId>
  <artifactId>quarkpress-lib-wp-shortcode</artifactId>
</dependency>
```

## Usage

```java
import java.util.*;
import org.quarkpress.lib.wpshortcode.*;

ShortcodeRegistry registry = ShortcodeRegistry.builder()
        .add("caption", (shortcode, context) -> {
            Map<String, String> atts = shortcode.attributes()
                    .withDefaults(Map.of("align", "alignnone"), shortcode.tag());
            return "<figure class=\"" + atts.get("align") + "\">"
                    + context.expand(shortcode.content())          // nested shortcodes, like do_shortcode($content)
                    + "</figure>";
        })
        .add("year", (shortcode, context) -> "2026")
        .build();

Shortcodes shortcodes = Shortcodes.builder(registry)
        .hooks(ShortcodeHooks.builder()
                .postRender((output, shortcode) -> output.strip())
                .build())
        .build();

String html = shortcodes.apply("[caption align=\"left\"]Taken in [year][/caption]");
String text = shortcodes.strip(postContent);                     // strip_shortcodes
boolean gallery = shortcodes.has(postContent, "gallery");        // has_shortcode
List<String> tags = shortcodes.tagsIn(postContent);              // get_shortcode_tags_in_content
List<ShortcodeNode> nodes = shortcodes.parse(postContent);       // matches without rendering
String unwrapped = shortcodes.unautop(autopContent);             // shortcode_unautop
ShortcodeAttributes atts = ShortcodeAttributes.parse(" id=\"5\" size=large");  // shortcode_parse_atts
```

## WordPress mapping

| QuarkPress | WordPress |
|---|---|
| `ShortcodeRegistry.Builder.add` / `remove` / `clear`, `ShortcodeRegistry.contains` | `add_shortcode`, `remove_shortcode`, `remove_all_shortcodes`, `shortcode_exists` |
| `Shortcodes.apply(content)` / `apply(content, ignoreHtml)` | `do_shortcode`, `apply_shortcodes` |
| `Shortcodes.strip` | `strip_shortcodes` |
| `Shortcodes.has` | `has_shortcode` |
| `Shortcodes.tagsIn` | `get_shortcode_tags_in_content` |
| `Shortcodes.unautop` | `shortcode_unautop` |
| `ShortcodeAttributes.parse` | `shortcode_parse_atts` |
| `ShortcodeAttributes.withDefaults(defaults[, tag])` | `shortcode_atts` |
| `ShortcodeContext.expand` | a callback calling `do_shortcode($content)` |
| `ShortcodeContext.insideShortcodes` | the `wp_get_attachment_image_context` "do_shortcode" context |
| `PreRenderHook` | `pre_do_shortcode_tag` (`Optional.empty()` is WordPress's `false`) |
| `PostRenderHook` | `do_shortcode_tag` |
| `AttributeDefaultsHook` | `shortcode_atts_{$shortcode}` |
| `StripTagsHook` | `strip_shortcodes_tagnames` |
| `HtmlAttributeSanitizer` / `BasicAttributeSanitizer` | `wp_kses_one_attr` in the "post" context |

Hooks run in ascending priority (default 10), then registration order, as `apply_filters` does.

## Behavior worth knowing

All of it is WordPress's behavior, reproduced on purpose:

- A name is matched only where it is a whole "candidate" somewhere in the text: `[foo.x]` alone stays text, but
  `[foo.x] [foo]` renders both (as `foo` with attribute `.x`).
- When two names could match at one place, the earlier-registered one wins (`x` before `x.y`).
- The attribute text ends at the first `]`, even inside quotes; a `/` right before it makes the tag
  self-closing, so `[link url=http://x.com/]` loses its trailing slash.
- An opening tag encloses everything up to the first exact `[/name]` anywhere later; the same tag does not nest.
- Handler output is never rescanned; call `context.expand(content)` for nested shortcodes.
- `[[tag]]` renders as `[tag]` without calling anything; a one-sided `[[tag]` keeps the stray bracket.
- Inside HTML markup, shortcodes in unquoted attributes are expanded raw; in quoted values the result goes
  through the attribute sanitizer and is kept only if it survives. A bare `<` in prose starts an "element" that
  can hide the brackets of a later closing tag.
- Literal `&#91;`/`&#93;` become `&#091;`/`&#093;` once any registered shortcode is present.
- Attributes follow PHP array rules: `5=a b` gives keys 5 and 6, the name `0` is dropped, values lose
  backslash escapes, and a value with an unclosed `<` becomes empty.

## Differences from WordPress

Each is listed in `src/test/resources/wp-parity-skip.txt`.

| Item | WordPress | QuarkPress | Why |
|---|---|---|---|
| D1 invalid name (R1.2) | notice, call ignored | `IllegalArgumentException` | fail fast |
| D2 uncallable callback (R1.5, R7.5) | notice, text unchanged | impossible | typed handlers |
| D3 non-string results (R7.4) | PHP casts (`true` → `1`, arrays → `Array`) | handlers return `String`; `null` → `""` | Java types |
| D4 `style` in an expanded quoted attribute | filtered by safecss | rejected (original text kept) | no safe-CSS filter until lib-wp-kses |
| D5 regex engine failure (R15.1) | whole content becomes `""` | processed normally, linear time | PHP artifact |
| D6 `expand` depth | unlimited (until PHP crashes) | `ShortcodeOptions.maxDepth`, default 64, cap 256, `ShortcodeException` | stack safety |
| D7 `tagsIn` with no shortcodes registered | reports `""` for `[ x]` | no tags | PHP artifact |
| D8 positional after integer key 9223372036854775807 | fatal error | dropped | robustness |
| D9 `object data` in an expanded quoted attribute | allowed for PDFs on the site's upload host | rejected | the library cannot know the host |

`ShortcodeOptions.maxDepth` accepts 0 to 256 (`MAX_DEPTH_CAP`); larger values throw
`IllegalArgumentException`. Each level of `expand` nesting uses Java stack, and depth 256 needs a thread stack of
at least 512 KB (a 256 KB stack overflows); the default of 64 is safe on normal thread sizes.

Text is handled as UTF-16 where WordPress handles UTF-8 bytes; for valid text the results are the same. An
unpaired surrogate in attribute text gives empty attributes (WordPress: invalid UTF-8 does). Backslash escapes
that produce invalid UTF-8 bytes (`\xff`) decode to U+FFFD.

## Performance and safety

Every operation is linear in the input (plus handler work): no regular expressions, a closing-tag index instead
of rescans, explicit stacks instead of recursion. `has` and `tagsIn` rescan nested content, so their cost also
grows with the nesting depth, which is bounded by the number of registered names (about 200 distinct nested
names around 1 MB of text take well under a second). Handler and hook exceptions propagate unchanged.

## Parity testing

`mvn verify -Pwp-parity` (needs `php` 8.4 and network) downloads the pinned WordPress 7.1.3 files into
`target/wp-source/` and compares `apply` (both `ignoreHtml` values), `strip`, `has`, `tagsIn`, `unautop`,
attribute parsing and `withDefaults` with WordPress on the behavior reference's examples
(`src/test/resources/wp-parity-cases.json`) and a few thousand generated inputs, and checks the "post"
allowlist table against `wp_kses_allowed_html('post')`. See `testing/wp-oracle`.

## Source policy

Written from a prose description of WordPress's observable behavior and from WordPress's output. WordPress PHP
was read to understand that behavior and runs as a test oracle; no WordPress code, regular expression or
comment is copied (QUARKPRESS-SPEC.md §13).
