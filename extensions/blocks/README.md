# QuarkPress Blocks

The block types, block renderers, block patterns and pattern categories a QuarkPress application registers, as
WordPress's block type and pattern registries hold them: plain Java and CDI, no database, no REST. The REST API
module serves what is registered here on `/wp/v2/block-types`, `/wp/v2/block-renderer/<name>`,
`/wp/v2/block-patterns/{patterns,categories}` and in the block editor's settings; later modules (block rendering,
themes) read the same registries.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-blocks</artifactId>
</dependency>
```

The extension brings `quarkpress-core` (for its configuration keys only), `quarkus-arc`, Jackson and the libraries
`quarkpress-lib-wp-blocks` (the block parser and serializer) and `quarkpress-lib-wp-kses` (WordPress's text-field
sanitizing). It needs no database and reads no site: a renderer is told the post it renders for as an id, if any.
Public API lives in `org.quarkpress.blocks`; `org.quarkpress.blocks.runtime` is implementation and may change.

## Using it

### Registering block types

A CDI bean implementing `BlockTypeProvider` registers block types. Each is a `BlockType`, built with
`BlockType.builder(name)` or read from a `block.json` file with `BlockType.fromBlockJson(JsonNode)`:

```java
@ApplicationScoped
public class ShopBlocks implements BlockTypeProvider {

    @Override
    public List<BlockType> blockTypes() {
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.putObject("amount").put("type", "number");
        return List.of(
                BlockType.builder("shop/price").title("Price").category("widgets").attributes(attributes).build(),
                BlockType.fromBlockJson(readJson("/blocks/cart/block.json")));
    }
}
```

- A name is `<namespace>/<name>`, each part starting with a lower-case ASCII letter and holding only lower-case ASCII
  letters, digits and dashes (`acme/notice`); anything else is refused with `IllegalArgumentException`
  ("QuarkPress: a block type name is <namespace>/<name> in lower-case letters, digits and dashes: ...").
- `fromBlockJson` reads WordPress's `block.json` keys `apiVersion`, `name`, `title`, `category`, `parent`,
  `ancestor`, `allowedBlocks`, `icon`, `description`, `keywords`, `textdomain`, `attributes`, `providesContext`,
  `usesContext`, `selectors`, `supports`, `styles`, `example`, `variations` and `blockHooks`; other keys (the asset
  files among them) are ignored, as is a key whose value has another JSON type than the key takes. Script and style
  handles (`editorScriptHandles`, `scriptHandles`, `viewScriptHandles`, `viewScriptModuleIds`, `editorStyleHandles`,
  `styleHandles`, `viewStyleHandles`) are set on the builder; they are empty by default.
- The getters give WordPress's defaults for what is not set (`title` and `description` `""`, `apiVersion` 1, empty
  lists and objects; `icon`, `category`, `parent`, `ancestor`, `allowedBlocks`, `textdomain` and `example` as
  `Optional`). Every JSON node handed out is a copy.
- **Order.** Providers are read by `order()` (lower first; the default is 1000), then by bean class name, so the
  registration order is stable although CDI hands beans out in no order; a provider's types keep their list order.
  The registries read the providers once, when the application starts; a `@Dependent` provider is destroyed right
  after.
- **Start-up refusals** (`IllegalStateException`, the application does not start): a type registered twice
  ("QuarkPress: the block type <name> is registered more than once"), as WordPress's `register_block_type` refuses
  it.

### Renderers

A CDI bean implementing `BlockRenderer` makes the type it names dynamic (rendered on the server):

```java
@ApplicationScoped
public class PriceRenderer implements BlockRenderer {

    @Override
    public String blockName() {
        return "shop/price";
    }

    @Override
    public String render(BlockRenderContext context) {
        return "<p class=\"price\">" + escape(context.attributes().path("amount").asText()) + "</p>";
    }
}
```

`BlockRenderContext` carries the type's name, the block's attributes (the sanitized ones with their declared defaults,
copied in and out, so a renderer cannot change the caller's node) and the post the block is rendered for as an
`OptionalLong`. A renderer answering `null` counts as the empty string. The renderer's HTML is served as it is: the
registered type's supports add no classes or styles to it (rest-wp-v2 D51). Start-up refusals: two renderers for one
type ("QuarkPress: two block renderers for <name>") and a renderer whose type no provider registers ("QuarkPress: the
block renderer for <name> names no registered block type").

### The attributes supports add

`BlockTypeRegistry.attributes(type)` (and `BlockSupports.attributes(type)` without the registry) answers a type's
attributes as WordPress's block type registry does: the declared attributes in their order, then `lock` and
`metadata` (objects) unless declared, then the attributes the type's supports add, in the order WordPress's block
supports add them and each only when the attribute is not there yet: `align` (`align`); `className`
(`customClassName`, on unless false); `backgroundColor`, `textColor` and `gradient` (`color`); `fontSize` and
`fontFamily` (`typography.fontSize`, `typography.__experimentalFontFamily`); `borderColor` (`__experimentalBorder`
or its `color`); `layout` (`layout` or `__experimentalLayout`); `ariaLabel`; `anchor`. `style` is added at the slot
of the first support that wants it (colour, typography, the experimental border, then position, spacing, dimensions,
duotone and background, after `layout`) and last by `customCSS` (on unless false), so `customCSS: false` drops it only
when no other support wants it. `border`, `shadow`, `background`'s other keys and the rest of `filter` add none. A type
whose supports turn `autoRegister` on marks each declared attribute of a simple type (string, number, integer,
boolean) without `source` and whose `role` is not `local` with `autoGenerateControl: true`. A support is on when it is
`true`, an object or a list (WordPress's test for most supports; its colour and typography checks also count other
truthy values such as `1`, which QuarkPress does not). WordPress's shadow support replaces a declared `style` or
`shadow` attribute's schema with its bare one; QuarkPress keeps the declared schema.

What each support adds is data, `block-support-attributes.json` beside `BlockSupports`, recorded from WordPress 7.1.3
and registered as a native-image resource. Every QuarkPress type is registered at start-up, so every type gets the
attributes of its supports (WordPress gives them to the types registered before its block supports are set up and
only `lock` and `metadata` to a later one; rest-wp-v2 D50).

### Patterns and pattern categories

A `BlockPatternProvider` bean registers `BlockPattern`s (`BlockPattern.builder(name, title, content)` with
`description`, `viewportWidth`, `inserter`, `categories`, `keywords`, `blockTypes`, `postTypes`, `templateTypes`
and `source`; an absent optional is a property the pattern does not set) and a `BlockPatternCategoryProvider` bean
registers `BlockPatternCategory`s (`name`, optional `label` and `description`). Both are read by `order()` then class
name like the type providers. A pattern or category registered again under the same name **replaces** the earlier one
where it stood, as WordPress's pattern and category registries do (types refuse duplicates instead).
`BlockPatternRegistry.all()`/`find(name)` and `BlockPatternCategoryRegistry.all()` answer what is registered, in
registration order.

QuarkPress ships no pattern (rest-wp-v2 D52). It ships WordPress's 21 core pattern categories
(`CoreBlockPatternCategories`, order 0, so they come first), by name and label in WordPress's order, with
descriptions written by QuarkPress (rest-wp-v2 D53):

| Name | Label | Description |
|---|---|---|
| `banner` | Banners | Large sections that open a page or carry one message. |
| `buttons` | Buttons | Buttons and calls to action. |
| `columns` | Columns | Layouts in several columns. |
| `text` | Text | Mostly text. |
| `query` | Posts | Lists and grids of posts. |
| `featured` | Featured | Patterns chosen to stand out. |
| `call-to-action` | Call to action | Sections that ask the reader to act. |
| `team` | Team | People and their roles. |
| `testimonials` | Testimonials | Quotes from customers and readers. |
| `services` | Services | What a business offers. |
| `contact` | Contact | Ways to get in touch. |
| `about` | About | An introduction to the site or its people. |
| `portfolio` | Portfolio | Work to show. |
| `gallery` | Gallery | Images in groups. |
| `media` | Media | Audio and video layouts. |
| `videos` | Videos | Video layouts. |
| `audio` | Audio | Audio layouts. |
| `posts` | Posts | Lists and grids of posts. |
| `footer` | Footers | The bottom of a page: links and site details. |
| `header` | Headers | The top of a page: site title and navigation. |
| `navigation` | Navigation | Menus and site navigation. |

### Resolving nested patterns

`PatternResolver.content(pattern)` (an `@ApplicationScoped` bean over the pattern registry; `new
PatternResolver(lookup, maxResolvedBlocks)` for a resolver over any lookup) answers a pattern's content as WordPress
lists it: the registered markup parsed as it is (its surrounding whitespace kept), the nested `core/pattern`
references resolved, and the whole written again in WordPress's canonical block form (attribute JSON re-encoded,
empty blocks in the void form, the text between a parent's blocks kept).

Resolution walks the blocks depth first. A `core/pattern` block whose `slug` is text PHP does not call empty (`""`
and `"0"` are) is replaced by the blocks of the pattern registered under that name (its markup trimmed), themselves
resolved; one naming no registered pattern, or without a slug, stays where it is, as does one whose attribute JSON
does not parse. A reference to a pattern already being expanded further up is removed. The pattern whose content is
resolved is not itself on that chain, so a pattern that refers to itself has that reference expanded once, and only
the reference inside the inserted copy is removed; A → B → A likewise inserts A's blocks once inside B's. When the
inserted pattern has exactly one top-level block, that block's `metadata` gains `patternName` (the slug), `name` (the
title) and, when the pattern has them, `description` and `categories` (as registered), each sanitized as a text field
(a falsy `"0"` title or description skipped); the block's own metadata keeps its place and what the pattern does not
set.

Unlike WordPress, the work is bounded, in two ways, each logged once at WARN naming the pattern:

- once `quarkpress.blocks.max-resolved-blocks` blocks have been inserted into one pattern's content (10 000 by
  default), the remaining references are left as they are ("Block pattern <name>: nested patterns are left
  unresolved after <n> inserted blocks (quarkpress.blocks.max-resolved-blocks)"); nested references fan out, so a
  pattern holding 500 references to a pattern of 500 paragraphs stops at the bound instead of inserting 250 000
  blocks;
- wherever the patterns and blocks would nest deeper than the block parser reads (1024 levels, the chain of patterns
  being expanded counted with the blocks), the remaining references are left as they are too ("Block pattern <name>:
  nested patterns are left unresolved deeper than 1024 levels"); a chain 200 patterns deep resolves. Markup beyond the
  parser's own limits is answered as registered, with a warning.

Each pattern's markup is parsed once per resolution, so the work is linear in the blocks inserted; a cycle of any
length is removed without a stack overflow.

## Configuration

Run time; checked at start-up with the other `quarkpress.*` keys (an unknown `quarkpress.blocks.*` key fails the
start).

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.blocks.max-resolved-blocks` | `10000` | How many blocks the nested patterns of one pattern may insert when its content is resolved; the later references are left unresolved and one warning is logged. WordPress has no such bound. A negative value fails the start. |

## Limits

- The registries are per application, not per site: every site of a network sees the same block types, renderers,
  patterns and categories, as WordPress's plugins register them network-wide.
- WordPress's core block types, their renderers and its core patterns are not registered (rest-wp-v2 D50, D51, D52);
  an application registers what its editor needs. The core pattern categories are (D53).
- A type's attributes under its supports follow WordPress 7.1.3's recorded block supports; a support WordPress adds
  later needs a new entry in `block-support-attributes.json`.
- `fromBlockJson` reads a `block.json`'s declarations only: the asset files it names (`editorScript`, `style`, ...)
  are not loaded or registered; an application sets the handles it serves on the builder.

## Decisions

- No core dependency beyond the configuration keys and no database: a renderer's post is an `OptionalLong`, so the
  module's tests need no database and the block rendering and theme modules can use it freely.
- Registration order is provider `order()` then class name, since CDI has no order; types refuse duplicates
  (WordPress's `register_block_type` does), patterns and categories replace in place (WordPress's registries do).
- The attributes supports add come from recorded data, not code, so every support is covered rather than
  `className` and `style` alone.
- Pattern resolution is bounded (`max-resolved-blocks`, the parser's depth) because the fan-out of nested references
  is exponential; WordPress has no bound.
