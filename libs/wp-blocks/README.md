# quarkpress-lib-wp-blocks

A plain-Java parser and serializer for Gutenberg block markup, the `<!-- wp:name {json} -->`
comments WordPress stores in `post_content`. One dependency (Jackson databind), no reflection,
no Quarkus.

Parsed, untouched content serializes back **byte for byte**. Edited blocks re-serialize from
their attribute nodes; their untouched siblings stay identical.

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
        b.innerBlocks();              // nested blocks
        b.innerContent();             // text chunks, null where each inner block sits
    }
}

String same = WpBlocks.serialize(blocks);   // equals postContent, byte for byte

boolean usesBlocks = WpBlocks.hasBlocks(postContent);

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
| `rawAttributes()` | Exact JSON text from the source, present on parsed, unmodified blocks. |
| `innerBlocks()` | Nested blocks. |
| `innerHtml()` | Own markup with inner block markup removed. |
| `innerContent()` | Ordered chunks; `null` marks an inner block position. |

Invariants: `null` count equals `innerBlocks().size()`; non-null chunks concatenated equal
`innerHtml()`. Equality compares name, attributes as JSON values, inner blocks and content, not
the raw attribute text.

`attributes()` returns a fresh deep copy on every call; renderers walking large trees should
read it once per block and keep it.

Attributes must be JSON values only (objects, arrays, strings, numbers, booleans, null); a POJO
or binary node is rejected with `IllegalArgumentException`. Number nodes are normalised when a
block is built: short, int, long and big integer become the smallest of int, long or big
integer; float becomes the double with the same decimal text (`0.1f` becomes `0.1`); big decimal
becomes double. So API-built and parsed attributes compare equal. Non-finite doubles (NaN,
infinities, or a literal such as `1e400` that overflows) are written as `null`.

### Round trip of API-built trees

`WpBlocks.parse(WpBlocks.serialize(blocks))` equals `blocks` when:

- no two top-level freeform blocks are adjacent (they re-parse as one freeform block);
- no freeform block is empty (an empty one produces no text and disappears);
- no text, from `Builder.text` or a freeform block, contains block delimiters. Text is written
  as is, so `text("<!-- /wp:p -->x")` closes the block early on re-parse.

## Grammar and leniency

- Delimiters: `<!-- wp:name {json} -->` ... `<!-- /wp:name -->`, and void `<!-- wp:name {json} /-->`.
  Whitespace (space, tab, CR, LF) is required after `<!--`, after the name, and after the JSON.
- Names: `[a-z][a-z0-9_-]*`, optionally `namespace/name` with the same shape. Uppercase or a
  digit-first segment makes the comment plain text.
- Attributes run from the `{` after the name to the first `}` that is followed by whitespace
  and then `-->` or `/-->` (the documented grammar). The search is linear, so hostile input
  cannot make parsing quadratic. Invalid JSON yields empty attributes but the raw text is kept,
  so the round trip still holds.
- Importers of corrupted content: one malformed `{` in an opener (an unbalanced brace, a missing
  `}`) makes the attributes run to the next `} -->` anywhere later in the document, so the
  following blocks are swallowed into that opener's raw attribute text. The round trip is still
  exact, but renderers see one block with empty attributes where there were several.
- Closers match by position, not by name. A closer with nothing open is text. Open blocks are
  closed at end of input, keeping the trailing text as their inner HTML.
- Malformed markup never throws. `BlockParseException` is thrown only when `ParseOptions`
  limits are exceeded: `maxDepth` (default 64) and `maxBlocks` (default 100 000, freeform
  blocks included).

## Serialization

- Freeform: written as is.
- Parsed delimiters are written back verbatim. A block without them (API-built, or after
  `withAttributes`/`withInnerBlocks`) gets regenerated delimiters: the void form when it has no
  inner blocks and no inner HTML, unless it came from an opener/closer pair (that explicit close
  is kept, also after `withAttributes` and `withInnerBlocks`); otherwise opener, content, closer.
- `core/` is dropped from names on output; other namespaces are kept.
- Raw attribute text is reused verbatim. Regenerated attributes are compact JSON with `--`,
  `<`, `>`, `&` and escaped quotes written as `\u002d\u002d`, `\u003c`, `\u003e`, `\u0026`,
  `\u0022`, so they can never terminate the comment.

## Conformance

`mvn verify -Pconformance` fetches Gutenberg's published parser fixtures at a pinned commit
(shallow sparse git checkout into `target/`) and compares `parse` output with each
`*.parsed.json`, plus a byte-identical round trip of every fixture file, plus canonical
serialization: each `*.serialized.html` is parsed, rebuilt without raw delimiter and attribute
text, and serialized again, which must reproduce the file. The fixtures are not
part of this repository. CI runs the profile.
The fetch is bounded by a 10-minute timeout per git step and the run fails if fewer than 300
fixtures are found.

## Clean-room

Written from the block grammar documentation, the published fixtures and observed WordPress
output. No WordPress PHP or Gutenberg JavaScript source was consulted.
