# quarkword-lib-wp-style-engine

WordPress's style engine for plain Java: block style attributes (`attrs.style`) and `theme.json` style values become
CSS declarations, class names, CSS rules and stylesheets, with the output of WordPress 7.1.3, odd cases included. It
covers `wp_style_engine_get_styles`, `wp_style_engine_get_stylesheet_from_css_rules`,
`wp_style_engine_get_stylesheet_from_context` and the declarations, rule, store and processor classes behind them.
Declarations, rules and options are immutable values; stores are owned by the caller, typically one registry per
request, so there is no global state. Every entry point runs without regular expressions, in time linear in its input
(details under [Performance and safety](#performance-and-safety)). Values are sanitised through
`quarkword-lib-wp-kses` (`wp_strip_all_tags` and `safecss_filter_attr`); JSON input is Jackson's `JsonNode`.

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
  <artifactId>quarkword-lib-wp-style-engine</artifactId>
</dependency>
```

Package `org.quarkword.lib.wpstyleengine`, automatic module name `org.quarkword.lib.wpstyleengine`.

## Usage

### Block supports

How the block renderer (`blocks-render`) turns a block's `style` attribute into class names and a per-block rule, and
prints the collected rules once per page.

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.quarkword.lib.wpstyleengine.*;

ObjectMapper json = new ObjectMapper();
JsonNode attrs = json.readTree("""
        {"style": {"color": {"text": "var:preset|color|vivid-red", "background": "#fff"},
                   "spacing": {"padding": {"top": "var:preset|spacing|40", "left": "1rem"}}}}""");

StyleStores stores = new StyleStores();   // one per request: WordPress's store registry
StyleResult result = StyleEngine.getStyles(attrs.path("style"), StyleOptions.defaults()
        .withSelector(".wp-container-1")
        .withContext(stores.store("block-supports")));

result.classnames();   // "has-text-color has-vivid-red-color has-background"
result.css();          // ".wp-container-1{color:var(--wp--preset--color--vivid-red);background-color:#fff;
                       //  padding-top:var(--wp--preset--spacing--40);padding-left:1rem;}"

// at the end of the page: every rule stored under "block-supports"
String stylesheet = StyleEngine.stylesheetFromContext(
        stores, "block-supports", StylesheetOptions.defaults().withOptimize(true));
```

Without a selector, `css()` holds the bare declarations (`color:red;`), as for an inline `style` attribute.
`declarations()` holds the raw declarations, which are **not** sanitised (see [Behavior worth knowing](#behavior-worth-knowing)).

### Global styles

How the `theme.json` stylesheet is built from rules: raw JSON declarations, as they come from the resolved
`theme.json`, or a map, each with an optional rules group.

```java
import java.util.List;
import java.util.Map;

JsonNode node = json.readTree("""
        {"--wp--preset--color--black": "#000", "background-color": "var(--wp--preset--color--black)"}""");

String css = StyleEngine.stylesheetFromCssRules(
        List.of(
                CssRuleEntry.fromJson(":root", node, null),
                CssRuleEntry.of(".a", Map.of("color", "red"), "@media (min-width: 600px)")),
        StylesheetOptions.defaults().withPrettify(true));
// ":root {\n\t--wp--preset--color--black: #000;\n\tbackground-color: var(--wp--preset--color--black);\n}\n"
// + "@media (min-width: 600px) {\n\t.a {\n\t\tcolor: red;\n\t}\n}\n"
```

`StylesheetOptions.withContext(store)` also adds each rule to that store, as WordPress's `context` option does.

## WordPress mapping

| QuarkWord | WordPress |
|---|---|
| `StyleEngine.getStyles(styles)`, `getStyles(styles, StyleOptions)` | `wp_style_engine_get_styles($block_styles, $options)` |
| `StyleOptions.selector`, `withSelector` | the `selector` option |
| `StyleOptions.context`, `withContext(StyleStore)` | the `context` option, a store name; here the store itself |
| `StyleOptions.convertVarsToClassnames`, `withConvertVarsToClassnames` | the `convert_vars_to_classnames` option |
| `StyleResult.css`, `declarations`, `classnames` | the `css`, `declarations` and `classnames` keys of the result |
| `StyleResult.EMPTY`, `isEmpty()` | the empty array returned for empty or invalid styles |
| `StyleEngine.stylesheetFromCssRules(rules, StylesheetOptions)` | `wp_style_engine_get_stylesheet_from_css_rules($css_rules, $options)` |
| `CssRuleEntry(selector, rawDeclarations, declarations, rulesGroup)` | one entry of `$css_rules`: `selector`, `declarations`, `rules_group` |
| `CssRuleEntry.fromJson(selector, node, group)`, `of(selector, Map[, group])` | an entry whose `declarations` is an array |
| `CssRuleEntry.of(selector, CssDeclarations, group)` | an entry whose `declarations` is a `WP_Style_Engine_CSS_Declarations` |
| `StylesheetOptions.context`, `withContext` | the `context` option of the stylesheet functions |
| `StylesheetOptions.optimize`, `withOptimize` | the `optimize` option |
| `StylesheetOptions.prettify`, `withPrettify` | the `prettify` option, which defaults to `SCRIPT_DEBUG` |
| `StyleEngine.stylesheetFromContext(stores, context, StylesheetOptions)` | `wp_style_engine_get_stylesheet_from_context($context, $options)` |
| `StyleEngine.stylesheetFromStore(store, StylesheetOptions)` | `WP_Style_Engine::compile_stylesheet_from_css_rules($store->get_all_rules(), $options)` |
| `StyleEngine.compileCss(CssDeclarations, selector)` | `WP_Style_Engine::compile_css` |
| `StyleStores` | the static registry of `WP_Style_Engine_CSS_Rules_Store` |
| `StyleStores.store(name)` | `WP_Style_Engine_CSS_Rules_Store::get_store($name)` (creates the store) |
| `StyleStores.find(name)` | none: a lookup that creates nothing |
| `StyleStores.names()` | the keys of `WP_Style_Engine_CSS_Rules_Store::get_stores()` |
| `StyleStores.clear()` | `WP_Style_Engine_CSS_Rules_Store::remove_all_stores()` |
| `StyleStore(name)`, `name()` | `WP_Style_Engine_CSS_Rules_Store`, `set_name`, `get_name` |
| `StyleStore.addRule(selector, rulesGroup, CssDeclarations)` | `add_rule($selector, $rules_group)->add_declarations(...)`, as `WP_Style_Engine::store_css_rule` does |
| `StyleStore.removeRule(key)` | `remove_rule($selector)` |
| `StyleStore.rules()` | `get_all_rules()`, as a snapshot |
| `CssDeclarations` | `WP_Style_Engine_CSS_Declarations` |
| `CssDeclarations.empty()`, `of(Map)`, `fromJson(node)` | `new WP_Style_Engine_CSS_Declarations($declarations)` |
| `CssDeclarations.with(property, value[, important])`, `Builder.add` | `add_declaration($property, $value, ['important' => …])` |
| `CssDeclarations.withAll(other)`, `Builder.addAll` | merging a declarations object, as `WP_Style_Engine_CSS_Rule::add_declarations` does |
| `CssDeclarations.without(property)`, `withoutAll(properties)`, `Builder.remove` | `remove_declaration`, `remove_declarations` |
| `CssDeclarations.declarations()`, `important()` | `get_declarations()`, the `important` entries of `get_declaration_options()` |
| `CssDeclarations.toCss()`, `toCss(prettify, indent)` | `get_declarations_string($should_prettify, $indent_count)` |
| `CssDeclarations.builder()`, `toBuilder()`, `isEmpty()` | none: the mutable form of the value |
| `CssRule(selector, declarations, rulesGroup)`, `CssRule.of` | `WP_Style_Engine_CSS_Rule`, `get_selector`, `get_declarations`, `get_rules_group` |
| `CssRule.withDeclarations(more)` | `add_declarations` |
| `CssRule.toCss()`, `toCss(prettify, indent)` | `get_css($should_prettify, $indent_count)` |
| `StyleProcessor` | `WP_Style_Engine_Processor` |
| `StyleProcessor.addStore(store)`, `addRules(rules)` | `add_store`, `add_rules` |
| `StyleProcessor.css()`, `css(optimize, prettify)` | `get_css(['optimize' => …, 'prettify' => …])` |

### Options and defaults

| Setting | Default |
|---|---|
| `StyleOptions.selector` | `null`: bare declarations; `""` and `"0"` also mean none |
| `StyleOptions.context` | `null`: nothing is stored |
| `StyleOptions.convertVarsToClassnames` | `false`: presets become `var(--wp--preset--<type>--<slug>)` |
| `StylesheetOptions.context` | `null`: nothing is stored |
| `StylesheetOptions.optimize` | `false` |
| `StylesheetOptions.prettify` | `false`, WordPress's default when `SCRIPT_DEBUG` is off |
| `CssRule.rulesGroup`, `CssRuleEntry.rulesGroup` | none (`null` or `""`); `"0"` also means none |

`StyleStore` and `StyleStores` reject the names `""` and `"0"` with `IllegalArgumentException`; a store selector that
is empty after trimming is rejected the same way (D3). `null` arguments throw `NullPointerException`, except where a
parameter is documented as optional (`selector`, `context`, `rulesGroup`, and the JSON block styles of `getStyles`).
`CssRuleEntry` needs exactly one of its two declaration forms. `toCss(true, indent)` with a negative indent throws
`IllegalArgumentException`.

## The definitions table

`getStyles` reads a block style object through WordPress's table of 37 definitions, always in this order, whatever the
order of the input: it fixes the order of the declarations and of the class names. Keys that are not in the table
(`elements`, `spacing.blockGap`, `typography.textAlign`, `dimensions.maxWidth`, filters) are ignored. "Sides" means a
value may also be an object of per-side or per-corner values, each written with the pattern; a preset value
`var:preset|<type>|<slug>` becomes `var(<custom property>)` only for the preset types listed.

| Path | Property | Sides | Preset custom property | Class names |
|---|---|---|---|---|
| `background.backgroundImage` | `background-image` | | none; `{"url": u}` becomes `url('u')` | |
| `background.backgroundPosition` | `background-position` | | | |
| `background.backgroundRepeat` | `background-repeat` | | | |
| `background.backgroundSize` | `background-size` | | | |
| `background.backgroundAttachment` | `background-attachment` | | | |
| `background.gradient` | `background-image` | | `gradient`: `--wp--preset--gradient--<slug>` | `has-background` |
| `color.text` | `color` | | `color`: `--wp--preset--color--<slug>` | `has-text-color`, `has-<slug>-color` |
| `color.background` | `background-color` | | `color`: `--wp--preset--color--<slug>` | `has-background`, `has-<slug>-background-color` |
| `color.gradient` | `background` | | `gradient`: `--wp--preset--gradient--<slug>` | `has-background`, `has-<slug>-gradient-background` |
| `border.color` | `border-color` | `border-<side>-color` | none | `has-border-color`, `has-<slug>-border-color` |
| `border.radius` | `border-radius` | `border-<corner>-radius` | `border-radius`: `--wp--preset--border-radius--<slug>` | |
| `border.style` | `border-style` | `border-<side>-style` | | |
| `border.width` | `border-width` | `border-<side>-width` | | |
| `border.top` | `border-top-<key>` for its keys `color`, `radius`, `style` and `width` | | `color` only: `--wp--preset--color--<slug>` | |
| `border.right` | `border-right-<key>` | | `color` only | |
| `border.bottom` | `border-bottom-<key>` | | `color` only | |
| `border.left` | `border-left-<key>` | | `color` only | |
| `shadow` (top-level key) | `box-shadow` | | `shadow`: `--wp--preset--shadow--<slug>` | |
| `dimensions.aspectRatio` | `aspect-ratio` | | | `has-aspect-ratio` |
| `dimensions.height` | `height` | | `dimension`: `--wp--preset--dimension--<slug>` | |
| `dimensions.minHeight` | `min-height` | | `dimension` | |
| `dimensions.minWidth` | `min-width` | | `dimension` | |
| `dimensions.objectFit` | `object-fit` | | | |
| `dimensions.width` | `width` | | `dimension` | |
| `spacing.padding` | `padding` | `padding-<side>` | `spacing`: `--wp--preset--spacing--<slug>` | |
| `spacing.margin` | `margin` | `margin-<side>` | `spacing` | |
| `typography.fontSize` | `font-size` | | `font-size`: `--wp--preset--font-size--<slug>` | `has-<slug>-font-size` |
| `typography.fontFamily` | `font-family` | | `font-family`: `--wp--preset--font-family--<slug>` | `has-<slug>-font-family` |
| `typography.fontStyle` | `font-style` | | | |
| `typography.fontWeight` | `font-weight` | | | |
| `typography.lineHeight` | `line-height` | | | |
| `typography.textColumns` | `column-count` | | | |
| `typography.textDecoration` | `text-decoration` | | | |
| `typography.textIndent` | `text-indent` | | | |
| `typography.textTransform` | `text-transform` | | | |
| `typography.letterSpacing` | `letter-spacing` | | | |
| `typography.writingMode` | `writing-mode` | | | |

The side or corner key is kebab-cased as WordPress's `_wp_to_kebab_case` does it (`topLeft` → `top-left`, a list
index `0` → `0`), and so is the slug of a preset (`Large2X` → `large-2-x`). A class name without `<slug>` is added
whenever the value is non-empty; one with `<slug>` only for a preset of the type in the same row. The class names are
joined by single spaces, each once.

## Behavior worth knowing

All of it is WordPress's behavior, reproduced on purpose:

- `declarations()` is raw: values that never render, such as `rgb(1,2,3)` outside a gradient, numbers (`"fontWeight":
  700`), booleans and nested objects, are in `declarations()` but not in `css()`. Only text values render.
- `rgb()`, `rgba()` and `hsl()` are rejected by the CSS sanitiser outside gradients, so `"color": {"text":
  "rgb(1,2,3)"}` gives the class `has-text-color` and no CSS.
- `border.color` has no preset custom property: `var:preset|color|black` gives only the classes `has-border-color
  has-black-border-color` and no CSS. The per-side `border.top.color` preset does convert.
- With `convertVarsToClassnames`, presets give no CSS, but a per-side border colour preset leaks into the CSS as the
  raw text: `border-top-color:var:preset|color|red;`.
- `"0"` is a valid value in some places and empty in others: `{"color": {"text": "0"}}` gives `color:0;` but no
  `has-text-color`, `border.top.width` `"0"` gives nothing, `spacing.padding.bottom` `"0"` gives `padding-bottom:0`.
- Property names pass through `sanitize_key`: custom properties are lowercased (`--MyVar` → `--myvar`) and characters
  other than `a-z 0-9 _ -` are removed.
- `background.backgroundImage` comes before `background.gradient`, and the gradient is prepended:
  `background-image:linear-gradient(red,blue), url('a.jpg');`.
- Optimize compares rules by selector only, ignoring the rules group: `.a{red}`, `@media print .a{blue}` and
  `.b{blue}` give `@media print{.a{color:blue;}}.a,.b{color:red;}`, so `.b` turns red. The combined rule goes after
  the others, or in place of an existing rule with that exact selector list, and has no rules group.
- Optimize compares raw values, rejected ones included: rules whose declarations are all rejected are combined and
  then render nothing.
- Rules are keyed by selector, or by rules group, a space and selector, so an ungrouped selector `@media print .a`
  and the group `@media print` with `.a` are merged into one rule. A later value for a property keeps the
  property's first position.
- A prettified stylesheet puts every comma of a selector on a new line, including one inside `:is(.a, .b)`, and ends
  every rule with a newline, also one that renders empty.
- `stylesheetFromContext` with an unknown store name creates that store empty and gives `""`.

## Differences from WordPress

| Item | WordPress | QuarkWord | Why |
|---|---|---|---|
| D1 kebab case of very long words (side keys, preset slugs) | under PHP's default `pcre.jit` and `pcre.backtrack_limit`, `_wp_to_kebab_case` keeps only the words before the one that hits a PCRE limit (`""` when it is the first): a run of 8,192 or more letters matched by a repeated group (capitals or other letters, or small letters directly followed by a digit or another letter), a plain digit run of 499,998 or more, or a digit run of 999,994 or more ending in an ordinal (`1st`, `4th`, ...) | converts the whole text in linear time | PCRE limits |
| D2 declarations and rules | a rule built from a declarations object shares it and sees later changes; the `important` option keeps any truthy value | `CssDeclarations` and `CssRule` are values: a rule never sees later changes, and `important` is a boolean | immutable values |
| D3 invalid store names and selectors | a store or context name of `""` or `"0"` gives `null` or a fatal error; a selector that is empty after trimming (`" "`, `" 0 "`) stops with a fatal error when it is stored, whether through `store_css_rule`, `wp_style_engine_get_styles` with a `context`, or `wp_style_engine_get_stylesheet_from_css_rules` with a `context`; `store_css_rule` with raw declarations that are not an array adds the rule, then fails calling `get_declarations()` on the value | `IllegalArgumentException` from `StyleStore`, `StyleStores.store`, `stylesheetFromContext`, `getStyles` with a context, and `stylesheetFromCssRules` with a context; the rule is not stored | PHP fatal errors are not copied |
| D4 options | options can be a query string; `convert_vars_to_classnames` and `optimize` are tested for strict `true`, other options for truthiness; `prettify` defaults to `SCRIPT_DEBUG` | typed records with booleans (`important`: see D2); `prettify` defaults to `false` | typed API, no global constants |
| D5 a `var()` value of 8,194 characters or more | under PHP's default PCRE limits, `safecss_filter_attr` drops it, so the declarations are returned without the CSS | sanitises CSS of any size in linear time and keeps it | inherited from lib-wp-kses (its difference 3) |
| D6 the processor and its inputs | merges store rules into the caller's rule objects, adds the store rules again on each `get_css` call, and keeps the rules an optimize call combined | never changes the rules or stores it is given; `css()` gives the same text on every call | immutable values |
| D7 optimize with two grouped rules with identical declarations whose selector has no ungrouped rule | a warning, then a fatal error | takes the declarations of the first rule with that selector, appends the combined rule without a group and leaves the grouped rules in place | PHP fatal errors are not copied |
| D8 optimize with numeric property names such as `"10"`, `"010"` and `"1e1"` | compares declarations after sorting the property names with PHP's array-key comparison, under which such names can compare equal or out of order, so two such rules can stay apart | compares the same properties with the same values and important flags, in any order | never valid CSS, so never rendered |
| D9 invalid UTF-8 and lone surrogates | works on UTF-8 bytes | strings are UTF-16: invalid UTF-8 cannot reach the library; a lone surrogate reaching the CSS becomes U+FFFD (lib-wp-kses), the kebab case of text that holds one is `""`, and declarations keep it as is | Java strings |

Each is listed in `src/test/resources/wp-parity-skip.txt`.

## Security notes

WordPress's style engine trusts its sanitiser more than it should, and QuarkWord copies its output for fidelity. What
that means for callers:

- **`;` inside a value injects declarations.** The sanitiser splits a value at every `;` and judges each part on its
  own, so `"color": {"text": "red;position:fixed"}` renders `color:red;position:fixed;`. Any allowlisted property can
  be injected this way, and a part without a `:` is passed through as a bare token.
- **`backgroundImage.url` is not escaped.** The URL is put into `url('…')` as it is, so the sanitiser is the only
  guard. A quote breakout injects declarations: `a.jpg');color:red;x:url('b` renders
  `background-image:url('a.jpg');color:red;`. A `data:` URL containing `;` is split, and its tail is left as junk
  (`base64,AA');`). `javascript:` URLs and URLs containing `)` are dropped.
- **Selectors and rules groups are emitted raw**, neither escaped nor checked: a selector `</style><script>…` ends up
  in the stylesheet as given.
- **`rgb()` and `hsl()` outside gradients are dropped silently**, which is a correctness trap rather than a hole.

A hardened mode (escaping URLs, refusing `;` in values) would change WordPress's output, so it is a later decision, to
be taken in the security review of public pages. Until then:

- pass only selectors and rules groups the application builds itself, never text from a request or from content;
- escape `</style` (for example as `<\/style`) when inlining a stylesheet into HTML;
- treat `StyleResult.declarations()` as untrusted, unsanitised input, and render only `css()`;
- treat the CSS of untrusted block attributes as author-controlled: it can set any allowlisted property on the
  element it styles, as it can in WordPress.

## Performance and safety

- No `java.util.regex` anywhere in the main code (a test enforces this): kebab case is one forward scan over code
  points with precomputed Unicode tables (WordPress's Unicode 17 digit and word classes, so the output does not
  depend on the JDK), and preset slugs are found with `contains` and `lastIndexOf`.
- Every entry point is linear in its input: block styles are read once in table order (nested values are never
  walked), stores and the processor use insertion-ordered hash maps, and optimize groups rules by identity in one
  pass, sorting each rule's declarations once.
- Hostile-input tests run within 10 seconds and allocate less than 200 bytes per input character plus 10 MB, with
  their exact outputs pinned: values of 1 MB (letters, `;` runs, `red;` repeated, 1 MB of `|` in a preset), the PCRE
  shapes of D1, 10,000 side properties, a side value nested 10,000 levels deep (passed through as it is, never
  walked), 10,000 rules (identical, distinct, merged into one, prettified), a selector list of 100,000 parts, and
  10,000 `getStyles` calls into one store.
- Stores are owned by the caller (`StyleStores` per request), so styles cannot leak from one request into another.
  Stores and processors are mutable and not thread-safe; everything else is immutable and thread-safe.

## Parity testing

`mvn verify -Pwp-parity` (needs `php` 8.4 and network) downloads the pinned WordPress 7.1.3 files into
`.cache/wp-source/` and compares `wp_style_engine_get_styles`, the stylesheet functions, the declarations, rule,
store and processor classes, `_wp_to_kebab_case`, `sanitize_key` and `safecss_filter_attr` with WordPress, on the
behavior reference's examples and the large shapes in `src/test/resources/wp-parity-cases.json` (about 460 cases,
also checked without `php` in the normal build) and on generated inputs. Differences are shrunk to a small input and
written to `target/wp-parity-failures.json`; the run also fails below a pinned minimum number of comparisons. The run
ends with a line such as
`lib-wp-style-engine parity: 16503 cases, 16948 comparisons, 0 differences`. See `testing/wp-oracle`.

## Source policy

Written from a prose description of WordPress's observable behavior and from WordPress's output. WordPress PHP
was read to understand that behavior and runs as a test oracle; no WordPress code, regular expression or
comment is copied (QUARKWORD-SPEC.md §13).
