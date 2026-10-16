# quarkpress-lib-wp-blocks

A plain-Java parser and serializer for Gutenberg block markup, the `<!-- wp:name {json} -->`
comments WordPress stores in `post_content`, plus WordPress's block helpers (`has_blocks`,
`has_block`, `serialize_blocks`, `strip_core_block_namespace`). One dependency (Jackson databind),
no reflection, no Quarkus.

The tree is the one WordPress's `parse_blocks` builds, malformed markup included. Parsed, untouched
content serializes back **byte for byte**; `serializeCanonical` writes exactly what WordPress's
`serialize_blocks` writes.

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
  <artifactId>quarkpress-lib-wp-blocks</artifactId>
</dependency>
```

## Usage

```java
import org.quarkpress.lib.wpblocks.*;

List<Block> blocks = WpBlocks.parse(postContent);
for (Block b : blocks) {
    if (b.isFreeform()) {            // classic content between blocks
        System.out.println(b.innerHtml());
    } else {
        System.out.println(b.name()); // "core/paragraph", "acme/hero", ...
        ObjectNode attrs = b.attributes();
        b.attributesValid();          // false where WordPress has null attributes
        b.innerBlocks();              // nested blocks
        b.innerContent();             // text chunks, null where each inner block sits
    }
}

String same = WpBlocks.serialize(blocks);               // equals postContent, byte for byte
String wordpress = WpBlocks.serializeCanonical(blocks); // what serialize_blocks writes

boolean usesBlocks = WpBlocks.hasBlocks(postContent);           // has_blocks
boolean hasGallery = WpBlocks.hasBlock(postContent, "gallery"); // has_block
String shortName = WpBlocks.stripCoreNamespace("core/gallery"); // "gallery"

Block edited = blocks.get(0).withAttributes(newAttrs);   // re-serializes from the node
Block rewired = blocks.get(0).withInnerBlocks(children); // one null chunk per child

Block fresh = Block.builder("acme/hero")
        .attributes(attrs)
        .text("<section>")
        .inner(Block.builder("paragraph").text("<p>Hi</p>").build())
        .text("</section>")
        .build();
```

## Model

| Accessor | Meaning |
|---|---|
| `name()` | Qualified name (`core/paragraph`); bare names in the source get `core/`. `null` for freeform. |
| `attributes()` | Deep copy of the JSON object; empty when absent or invalid. |
| `attributesValid()` | False when the attribute text is not JSON that PHP's `json_decode` accepts; WordPress then has `null` attributes. |
| `rawAttributes()` | Exact JSON text from the source, present on parsed, unmodified blocks. |
| `innerBlocks()` | Nested blocks. |
| `innerHtml()` | Own markup with inner block markup removed. |
| `innerContent()` | Ordered chunks; `null` marks an inner block position. |
| `isRecoveredCopy()` | True for a block WordPress repeats when blocks are left open at the end of the document (see below). |

Invariants: `null` count equals `innerBlocks().size()`; non-null chunks concatenated equal
`innerHtml()`. Equality compares name, attributes as JSON values (numbers by value, so `3` equals
`3.0`), inner blocks and content (empty chunks ignored), not the raw attribute text.

`attributes()` returns a fresh deep copy on every call; renderers walking large trees should
read it once per block and keep it.

Attributes must be JSON values only (objects, arrays, strings, numbers, booleans, null); a POJO
or binary node is rejected with `IllegalArgumentException`. Number nodes are normalised when a
block is built: short, int, long and big integer become the smallest of int, long or big
integer; float becomes the double with the same decimal text (`0.1f` becomes `0.1`); big decimal
becomes double.

### Round trip of API-built trees

`WpBlocks.parse(WpBlocks.serialize(blocks))` equals `blocks` when:

- no two top-level freeform blocks are adjacent (they re-parse as one freeform block);
- no freeform block is empty (an empty one produces no text and disappears);
- no text, from `Builder.text` or a freeform block, contains block delimiters. Text is written
  as is, so `text("<!-- /wp:p -->x")` closes the block early on re-parse.

Integral doubles come back as integers (`3.0` is written `3`, as WordPress writes it); they still
compare equal.

## Grammar and recovery

- Delimiters: `<!-- wp:name {json} -->` ... `<!-- /wp:name -->`, and void `<!-- wp:name {json} /-->`.
  Whitespace (space, tab, line feed, vertical tab, form feed, carriage return) is required after
  `<!--`, after the name, and after the JSON. A no-break space is not whitespace.
- A closer may carry attributes, which are ignored (`<!-- /wp:a {"x":1} -->`); a closer with the
  void flag is a void block (`<!-- /wp:a {"k":2} /-->`).
- Names: `[a-z][a-z0-9_-]*`, optionally `namespace/name` with the same shape. Uppercase or a
  digit-first segment makes the comment plain text.
- Attributes run from the `{` after the name to the first `}` that is followed by whitespace
  and then `-->` or `/-->`. The search is linear, so hostile input cannot make parsing quadratic.
  The JSON is judged as PHP's `json_decode` judges it (at most 511 nested containers, surrogate
  escapes in pairs); otherwise `attributes()` is empty, `attributesValid()` is false and the raw
  text is kept, so the round trip still holds.
- Importers of corrupted content: one malformed `{` in an opener (an unbalanced brace, a missing
  `}`) makes the attributes run to the next `} -->` anywhere later in the document, so the
  following blocks are swallowed into that opener's raw attribute text, as in WordPress.
- Closers match by position, not by name.
- A closer while no block is open ends parsing, as in WordPress: everything after the previous
  delimiter becomes one freeform block, so blocks after it are not parsed as blocks.
- Blocks still open at the end of the document are closed as WordPress closes them: each is emitted
  at top level, innermost first, after a freeform block holding the text before its opener, and the
  outermost one keeps the rest of the document as its text. WordPress therefore shows inner text
  twice. The repeated blocks are marked `isRecoveredCopy()`: `serialize` skips them, so the round
  trip stays exact; `serializeCanonical` writes them, as WordPress does.
- A nested block that closes right after an inner block or its own opener gets an empty text chunk,
  as in WordPress.
- `BlockParseException` is thrown only when a `ParseOptions` limit is exceeded; otherwise malformed
  markup is read as WordPress reads it. The limits are `maxDepth` (default 1024), `maxBlocks`
  (default 1 000 000, freeform blocks included) and the budget for text repeated into recovered
  copies: the larger of `maxRecoveredChars` (default 8 000 000) and `recoveredCharsPerInputChar`
  (default 16) times the input length. WordPress has no such limits; the defaults sit far above
  real content.

## Serialization

- `serialize`: parsed delimiters are written back verbatim and recovered copies are skipped. A
  block without raw delimiters (API-built, or after `withAttributes`/`withInnerBlocks`) gets
  canonical delimiters: the void form when its content is empty, otherwise opener, content,
  closer. Raw attribute text is reused when present (`withInnerBlocks` keeps it).
- `serializeCanonical`: ignores all raw text, as `serialize_blocks` does: `core/` dropped, single
  spaces, void form exactly when the content is empty, and attributes as WordPress writes them:
  PHP `json_encode` output (slashes and non-ASCII as they are, U+2028/U+2029 escaped, `1.0` as
  `1`, `1e20` as `1.0e+20`) with backslash, `--`, `<`, `>`, `&` and quote written as `\u005c`,
  `\u002d\u002d`, `\u003c`, `\u003e`, `\u0026` and `\u0022`, so the text can never end the comment.

## Differences from WordPress

Checked by the `wp-parity` profile; each is listed in `src/test/resources/wp-parity-skip.txt` with
its audit item.

| Item | QuarkPress | WordPress | Reason |
|---|---|---|---|
| D5 | a text chunk `"0"` is kept | dropped (PHP's `empty("0")`); a block whose content is `"0"` is written void | that is data loss |
| D9 | a number whose integer, fraction or exponent part exceeds 1000 characters (floats included) makes the attributes invalid | parsed | bounded parsing (Jackson's limit is kept) |
| D10 | integers beyond 64 bits stay exact | become floats (`1.2345678901234567e+19`) | precision |
| D11 | a number that overflows to infinity is written as `null` | encoding fails and all attributes are dropped | that is data loss |
| D12 | `{"0":"x","1":"y"}` stays an object | becomes a list, written `["x","y"]`, which no longer parses as attributes | that corrupts the block |
| D13 | `{}` inside attributes stays `{}` | written as `[]` | same meaning |
| D20 | attributes of any size parse in linear time | PCRE limits turn the whole document into one freeform block | robustness |
| (limit) | the text repeated into recovered copies is bounded by the larger of `maxRecoveredChars` (default 8 000 000) and `recoveredCharsPerInputChar` (default 16) times the input length; exceeding it throws `BlockParseException` | builds the tree, bounded only by PHP's `memory_limit` | each block left open at the end repeats the rest of the document, so 1024 unclosed openers before 1 MB of text would copy about 1 GB |

## Conformance

`mvn verify -Pconformance` fetches Gutenberg's published parser fixtures at a pinned commit
(shallow sparse git checkout into `target/`) and compares `parse` output with each
`*.parsed.json`, checks a byte-identical round trip of every fixture file, and checks that
`serializeCanonical` reproduces each `*.serialized.html`. The fixtures are not part of this
repository. The fetch is bounded by a 10-minute timeout per git step and the run fails if fewer
than 300 fixtures are found.

## Parity testing

`mvn verify -Pwp-parity` (needs `php` 8.4 and network) downloads the pinned WordPress 7.1.3 block
parser files into `target/wp-source/` and compares the parse tree, `serializeCanonical`,
`hasBlocks` and `hasBlock` with `parse_blocks`, `serialize_blocks`, `has_blocks` and `has_block` on
hand-written cases (`src/test/resources/wp-parity-cases.json`), the corpus in
`src/test/resources/wp/`, the Gutenberg fixtures when `-Pconformance` is active too, and a few
thousand generated documents. CI runs both profiles. See `testing/wp-oracle`.

## Source policy

Written from the block grammar documentation, the published fixtures and WordPress's observed
behaviour. WordPress PHP was read to understand that behaviour and runs as a test oracle; no
WordPress code is copied (QUARKPRESS-SPEC.md §13).
