# quarkpress-lib-wxr

Read and write WordPress eXtended RSS (WXR), the format of WordPress's Tools, Export, in plain Java.
The reader streams files of any size and survives the malformed XML that real exports contain; the writer
produces WXR 1.2 that the WordPress Importer reads like a WordPress export, without WordPress's
malformed-XML quirks. Dependencies: the JDK (StAX) and `quarkpress-lib-php-serialize`.

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
  <artifactId>quarkpress-lib-wxr</artifactId>
</dependency>
```

## Reading

```java
try (WxrReader reader = WxrReader.open(Files.newInputStream(path), WxrReadOptions.LENIENT)) {
    WxrSite site = reader.site();                  // channel fields seen so far
    while (reader.hasNext()) {
        switch (reader.next()) {
            case WxrAuthor a -> users.add(a);
            case WxrCategory c -> categories.add(c);   // parent: a slug (1.1 and later) or a name (1.0)
            case WxrTag t -> tags.add(t);
            case WxrTerm t -> terms.add(t);            // custom taxonomies and nav menus
            case WxrItem i -> posts.add(i);            // with terms, post meta and comments
        }
    }
    String generator = reader.site().generator();  // WordPress writes it after the terms
    List<WxrProblem> problems = reader.problems(); // every repair, with line and column
}
```

- One entry at a time: memory follows the largest entry (one item with its meta and comments), never the file.
- Field order inside an entry does not matter. Unknown elements are skipped with their subtree; a leaf in
  another namespace is kept in `rawFields()` as `{uri}local`.
- Text is the concatenation of text and CDATA with references decoded, and is never trimmed.
- Integer fields are `Long`, null when absent or not a whole number; the text of every field stays in `rawFields()`.
- Meta values are raw strings. `WxrMeta.parsed()` gives the value the importer would store: WordPress's
  `is_serialized` check, every object header rewritten to `stdClass`, then `maybe_unserialize`. It is empty when the value is not serialized or cannot be
  parsed, and is recomputed on each call.
- WXR 1.0 shapes are read as written: bare `<category>` elements keep a null domain, `domain="tag"` stays `tag`
  (`WxrItemTerm.taxonomy()` gives `post_tag`), parents may be names.
- `withAcceptAnyWpNamespace(boolean)` controls whether any URI bound to `wp`/`excerpt` counts as WordPress's.
- A gzip stream is detected and decompressed (`withGzipAutoDetect(false)` turns that off).

### Lenient and strict

`LENIENT` (the default) repairs and records; `STRICT` throws `WxrException` where lenient mode would repair.

| Problem | Lenient | Strict |
|---|---|---|
| `INVALID_ENCODING` | bytes invalid in the file's encoding become U+FFFD | throws |
| `UNSUPPORTED_ENCODING` | an encoding the JVM does not know, or a declared UTF-16/UTF-32 on a file whose first bytes are single-byte ASCII, is read as UTF-8 | throws |
| `ILLEGAL_CHARACTER` | characters XML 1.0 forbids (and references to them) become U+FFFD | throws |
| `BARE_AMPERSAND` | an `&` that starts no reference is text (WordPress writes these in comment URLs) | throws |
| `UNDEFINED_ENTITY` | `&nbsp;` and other undefined named references stay as text | throws |
| `BARE_LESS_THAN` | a `<` that starts no markup is text | throws |
| `INVALID_MARKUP` | `]]>` in text and `--` in a comment are escaped or split | throws |
| `DOCTYPE` | skipped; DTDs are never processed | throws |
| `UNKNOWN_NAMESPACE` | another URI bound to `wp`/`excerpt`, or a WordPress prefix used without a declaration, is accepted | not accepted: an undeclared prefix throws `MALFORMED`; a foreign URI leaves the elements unrecognised, so `VERSION_MISSING` is thrown |
| `NOT_WXR` | the root is not `rss`, there is no `channel`, or there is no element at all | throws |
| `VERSION_MISSING`, `VERSION_INVALID` | read anyway (the importer rejects such files) | throws |
| `VERSION_NEWER` | read; recorded | read; recorded |
| `TRUNCATED`, `MALFORMED` | every complete entry, then the entry being read with its completed fields, then the end | the complete entries, then throws |

A byte-order mark is not a problem: it is skipped. At most 1000 problems are stored (`TOO_MANY_PROBLEMS` marks
the cut); `problemCount()` counts all of them. A gzip stream that is cut off or corrupt is a content problem
(`TRUNCATED` or `MALFORMED`); any other `IOException` of the stream surfaces as `UncheckedIOException` in both
modes.

### Limits and security

- `withMaxCharsPerValue(int)` (default 512 Mi chars) bounds every value, including comments, processing
  instructions, attribute names and attribute values, and also inside gzip streams. Exceeding it throws
  `VALUE_TOO_LARGE`, except that a lenient read truncates an overlong comment, processing instruction or attribute
  and records the problem. A value costs roughly 3 to 6 bytes of heap per character while it is assembled, and the
  limit is per value, not per entry, so lower it to what your largest legitimate value needs when reading
  untrusted files. `withMaxItems(long)` (default unbounded) throws `TOO_MANY_ITEMS`.
- A start tag may carry at most 10000 attributes, namespace declarations included. Strict mode throws
  `VALUE_TOO_LARGE`; lenient mode drops the extra attributes and records one problem. WXR never needs more.
- No DTD is ever processed and no external entity or DTD is ever fetched. Entity expansion is not a risk: only
  the five predefined references exist.
- The repairs run in a streaming pre-filter with bounded lookahead; its cost is linear in the input.

## Writing

```java
try (WxrWriter writer = WxrWriter.open(out, site)) {   // XML declaration, namespaces, channel fields
    authors.forEach(writer::author);                    // then categories, tags, terms, items, in this order
    categories.forEach(writer::category);               // a parent must be written before its children
    tags.forEach(writer::tag);
    terms.forEach(writer::term);
    posts.forEach(writer::item);
}                                                       // ends channel and rss, closes the stream
```

Elements, namespaces and CDATA usage follow WordPress 7.1.3's export. Policy stays with the caller: WordPress's
exporter skips `_edit_lock` meta, spam comments, revisions and auto-drafts, and lists parents first; this writer
writes what it is given and only checks that each parent was written earlier. Memory is one entry at a time.

## Differences from WordPress

The reader and writer follow WordPress's export and the WordPress Importer (0.9.6) closely. These are the
deliberate or unavoidable differences. The parity tests' skip lists cite the items they need by their label
(D1 to D12).

### Reading

The importer has several parsers whose results differ from each other: SimpleXML where PHP has it, otherwise
an XML-processor parser, which also reads the files SimpleXML rejects. D1 to D3 are quirks of that second parser.

- **D1 References decode completely.** The XML-processor parser drops the text before each numeric reference
  (`&#038;`, `&#8217;`), and leaves `&amp;`, `&lt;` or `&gt;` undecoded when the reference and the text after it
  fit in five bytes, as `&gt;` at the end of a value does.
- **D2 An item's fields are read in any order.** The XML-processor parser loses the fields after the first meta
  row or comment.
- **D3 A byte-order mark, a non-UTF-8 declaration and `standalone` are accepted.** The XML-processor parser
  rejects such a file.
- **D4 Foreign namespaces.** Lenient mode accepts any URI bound to `wp` or `excerpt`, and a WordPress prefix used
  without a declaration. The importer's XML-processor parser finds no version in such files, and SimpleXML none
  when the prefix is undeclared, so the importer rejects them.
  `UNKNOWN_NAMESPACE` has these two meanings; the problem detail says which.
- **D5 `wp:wp_author` is skipped**, and authors are not re-keyed by login, email, id or position as the importer
  does.
- **D6 An item's `description` stays `description()`**; the importer's XML-processor parser reuses it as the
  excerpt when `excerpt:encoded` is missing.
- **Item terms.** A `category` element without a `domain` attribute keeps a null domain, and `taxonomy()` returns
  `"category"` (SimpleXML reads the missing attribute as an empty string). Bare categories of WXR 1.0 files are
  kept, not dropped.
- **Version check while streaming.** The version is checked at the first entry; a `wp:wxr_version` that appears
  after it is rejected, since the entries before it are already delivered. The importer sees the whole file first.
- **`NOT_WXR` also covers input with no element at all**, such as an empty file.
- **Error columns on the root line shift** in lenient mode when the reader injects missing prefix declarations
  into the root start tag; positions on other lines are exact up to earlier repairs on the same line.
- **D8 XML-illegal characters** become U+FFFD in lenient mode; the importer's parsers pass them through or fail.
- **D13 Meta values.** `WxrMeta.parsed()` inherits the kept differences of `quarkpress-lib-php-serialize`: an `E:`
  enum value parses to an enum for any class and case (WordPress returns `false` for an unknown one), `C:`
  payloads are kept as opaque bytes (WordPress builds an incomplete class and drops them), and a few
  malformed object headers that PHP refuses can still parse. Classes are never looked up, and every object is
  read as `stdClass`.

### Writing

- **Null fields are omitted** instead of written as empty elements, so null round-trips as null. WordPress always
  has every field, so data shaped like WordPress's yields WordPress's exact element tree.
- **D7 Attributes are escaped.** The comment author URL and the `category` attributes are escaped; WordPress writes
  them raw, so a `&` makes its file malformed. Inside attributes a tab, a line feed and a carriage return are
  written as numeric references, and `"` as `&quot;`.
- **D8 XML-illegal characters** become U+FFFD; WordPress writes them raw and produces a file no parser accepts.
- **D9 Values are valid UTF-8**, written as given. WordPress re-encodes a value that is not valid UTF-8 as
  Latin-1, double-encoding its valid characters. No byte-order mark; the declaration says UTF-8.
- **D10 A carriage return survives.** In CDATA it is written as `]]>&#13;<![CDATA[` and in plain text as `&#13;`;
  WordPress writes it raw, so every XML parser turns it into a line feed. A parser that does not decode
  references inside the file, such as a regular-expression reader, shows the reference literally.
- **Named and numeric references.** Plain text uses `&amp;`, `&lt;` and `&gt;`; numeric references appear only
  for carriage returns and for tab and line feed in attributes.
- **D11 Channel title and description are written as given.** WordPress passes them through its display filters
  (`esc_html`, `convert_chars`, `wptexturize`); a library writes data, not display text.
- **D12 A term or category named `0` keeps its name**; WordPress drops it (PHP treats `"0"` as empty). Other empty
  names and descriptions are omitted, as in WordPress.
- **D14 Always version 1.2.** A site read from a 1.0 or 1.1 file is written as 1.2.
- **No prolog comments.** WordPress writes a generator comment and a how-to-import comment before `<rss>`; they
  carry no data and are omitted.
- **Tidy indentation.** Layout is close to WordPress's but whitespace between elements is not a goal and not
  significant.

## Parity testing

`mvn verify -Pwp-parity` (needs `php` 8.4 and network) downloads the pinned WordPress 7.1.3 files and the
WordPress Importer 0.9.6 (commit `3ca8839`) into the repository's `target/wp-source/`, and runs the importer
(`src/test/php/importer-driver.php`) and WordPress's own `export_wp()` (`src/test/php/export-driver.php`) through
`testing/wp-oracle` on the same inputs as this library. The reader, the writer, a round trip and the meta values are compared.

- **Reader against the importer's parsers** (`WxrParityTest`): the reference examples, two real WordPress
  exports, the edge cases in `src/test/resources/wxr/cases/` and 300 generated documents. The importer parses each
  file with the parser it would choose; both results are mapped to one normal form (lists in order, every field as
  a string, the importer's integer casts applied) and compared field by field.
- **Which parser.** With PHP's `simplexml`, `dom` and `xml` extensions (as in CI) that is SimpleXML, with the
  XML-processor parser for the files SimpleXML rejects. Without any of them it is the XML-processor parser alone,
  and the test that checks SimpleXML really runs is skipped with a message saying so. With `xml` but without
  `simplexml` or `dom` the importer would use its expat parser, which is not compared: the parity test is skipped.
- **Writer against `export_wp()`** (`WxrParityTest`): 60 generated datasets and five hand-written ones in
  `src/test/resources/wp-parity-cases.json` (the reference example, a full site, quirks, Windows line endings, a
  category named `0`). WordPress 7.1.3's `export_wp()` runs once per dataset in its own PHP process, with the real
  export, formatting, kses and UTF-8 files and its database queries answered from the dataset; the same dataset goes
  through `WxrWriter`, with the selection and ordering a full export does (exportable post types without
  auto-drafts, the authors of exported posts, parents before children, no edit locks, no spam comments) applied by
  the test as a caller would. Every field WordPress always writes is set, so each element is present on both sides.
  Both files are parsed by the same reader (this library's lenient input, so WordPress's raw `&` and control
  characters parse) and the element trees are compared in order: names, attributes, leaf text and whether it came
  from CDATA. Whitespace between elements, comments and the channel's run-time `pubDate` and `generator` are
  ignored, and references are compared as the values they decode to, so the omitted prolog comments and the choice
  of named or numeric references never show. The library's file must also parse with the JDK's plain XML parser,
  without repair.
- **Round trip** (`WxrParityTest`): the importer's view of a WordPress file against its view of the library's
  rewrite of it (read in lenient mode, every entry written back in order). The files are the three reference
  examples and the 65 files `export_wp()` wrote above, except the Windows line endings one (D10 is shown there).
  Both views go through the importer's parser and the normal form of the reader comparison.
- **Meta values** (`WxrParityTest`): each meta value of the corpus, 19 fixed shapes (wrong lengths, objects, object
  headers inside strings, surrounding whitespace, cuts) and 200 generated values go through `WxrMeta.unserialized()`
  and through the importer's own object-header rewrite and `maybe_unserialize`; the typed trees (types, array
  order, byte strings, floats, objects with their class names) are compared.
- **Differences.** `src/test/resources/wp-parity-skip-simplexml.txt` and `wp-parity-skip-xmlprocessor.txt` list
  each expected difference with its item above: numeric references and short trailing named references (D1),
  fields after meta (D2), a BOM, a Latin-1 declaration and `standalone` (D3), foreign or undeclared namespaces
  (D4), `wp:wp_author` (D5) and XML-illegal characters (D8); for the round trip, the version the writer always sets (D14),
  D1 and D8 where the original's own parse shows them; for the meta values, an unknown enum (D13: the `C:` payload
  and malformed-header differences of that row are shown by no value); for the writer, carriage returns (D10) and a category
  named `0` (D12), with escaped attributes (D7), XML-illegal characters (D8), valid UTF-8 (D9) and channel text
  (D11) listed as differences no dataset shows once both files are parsed. An entry that no longer differs fails
  the build.

## Source policy

Written from WXR files, a prose description of WordPress's observable behaviour and observed WordPress output.
WordPress and importer PHP were read to understand that behaviour and run as a test oracle; no WordPress code is
copied (QUARKPRESS-SPEC.md section 13).
