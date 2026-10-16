# quarkpress-lib-php-serialize

A plain-Java reader and writer for the PHP `serialize()` format, plus WordPress's helpers around it
(`maybe_serialize`, `maybe_unserialize`, `is_serialized`, `is_serialized_string`). Zero runtime
dependencies, no reflection, no Quarkus.

WordPress stores options, widget settings, post meta, user roles and cron data as PHP serialized
strings. This library lets migration and import code read those values the way WordPress reads them
and write them back the way PHP writes them.

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
  <artifactId>quarkpress-lib-php-serialize</artifactId>
</dependency>
```

## Usage

### Parse

```java
PhpValue value = PhpSerialize.parse(bytesFromDatabase);                       // strict grammar
PhpValue lenient = PhpSerialize.parse(bytes, ParseOptions.PHP_COMPATIBLE);   // what PHP 8.4 accepts

if (value instanceof PhpArray a) {
    PhpValue title = a.get("title");          // string key
    PhpValue widget = a.get("2");             // finds the integer key 2, as $a["2"] does in PHP
    List<PhpValue> items = a.values();
}

Object plain = value.toJavaObject();          // List / LinkedHashMap / String / Long / Double / Boolean / null
String json = value.toJson();                 // compact JSON, no dependency
```

Pass a `String` only if you know its encoding matches `ParseOptions.charset()`: declared lengths
are **byte** counts, so the text is encoded first and then parsed.

`ParseOptions.DEFAULTS` is strict: exactly one value and 64-bit integers. `ParseOptions.PHP_COMPATIBLE`
also accepts what PHP 8.4's `unserialize()` accepts: bytes after a complete value are ignored,
out-of-range integers are clamped to `Long.MIN_VALUE`/`Long.MAX_VALUE`, and legacy `S:` strings with
`\xx` escapes are read. Each of these is also a `with…` option. In both modes, as in PHP, integers and
floats may carry a leading `+`, class names must be PHP class names, `R:` must refer to an earlier
value and `r:` to an earlier object.

`toJavaObject()` and `toJson()` resolve `R:`/`r:` back-references to copies of their targets
(a reference cycle throws `PhpSerializeException("cyclic reference")`) and show object properties
by their visible names (a public property wins over a private or protected one with the same name).
`toJson()` writes floats as PHP's `json_encode()` does (`1.0e+25`, `10000000`, `0.1`; `INF` and `NAN`
become `null`).

### WordPress helpers

```java
WordPressSerialization.isSerialized(optionValue);          // is_serialized($data)
WordPressSerialization.isSerialized(optionValue, false);   // is_serialized($data, false)
WordPressSerialization.isSerializedString(metaValue);      // is_serialized_string($data)

PhpValue v = WordPressSerialization.maybeUnserialize(bytesFromDatabase);
// the raw value as a PhpString when it does not look serialized, the parsed value when it does
// (trimmed like PHP's trim(), parsed with PHP_COMPATIBLE), PhpBool.FALSE when it looks serialized
// but does not parse, as WordPress returns false. tryMaybeUnserialize(...) tells the three apart.

byte[] stored = WordPressSerialization.maybeSerialize(value);
// arrays and objects are serialized; a string that looks serialized is serialized again, so it reads
// back as the same string; other scalars become the text the column receives
// (5 -> "5", true -> "1", false and null -> "", 0.1 + 0.2 -> "0.3").
```

WordPress's rules look at bytes. `isSerialized` and `isSerializedString` read the characters of the text
directly, which gives the same answers because every rule they apply tests ASCII characters only.
`maybeUnserialize(String)` and `tryMaybeUnserialize(String)` encode the text as UTF-8 first, so they are
wrong for text from a non-UTF-8 column (declared lengths count the original bytes): use the `byte[]`
overloads with the bytes as stored.

### Repair corrupted lengths

A naive search-and-replace on serialized data (changing a site URL, for example) leaves string
lengths wrong. Repair mode recovers such values:

```java
ParseOptions lenient = ParseOptions.DEFAULTS.withRepairLengths(true);
PhpValue repaired = PhpSerialize.parse(bytes, lenient);
byte[] fixed = PhpSerialize.serialize(repaired);   // lengths are correct again
```

Strict mode (the default) rejects the same input with a `PhpSerializeException` carrying the byte
offset of the problem. Repair mode has no PHP equivalent; use it only as an explicit migration tool.

### Write

```java
PhpValue v = PhpArray.builder()
        .put("sidebar-1", PhpArray.of(PhpString.of("block-2"), PhpString.of("block-3")))
        .put("array_version", new PhpInt(3))
        .build();
byte[] bytes = PhpSerialize.serialize(v);
```

Parsing and re-serializing a document that PHP wrote reproduces it byte for byte, including floats
and non-UTF-8 string payloads. Other documents are normalized the way PHP normalizes them:
a repeated key keeps its first position and its last value, integer text keys (`s:1:"5"`) become
integer keys (`i:5`), integers and floats are written from their values (`i:+5` becomes `i:5`,
`d:+1.50` becomes `d:1.5`, `d:1e3` becomes `d:1000`), and object property names are written as strings. Appending with
`Builder.add` follows PHP 8.3+: the next key is one more than the largest integer key, even a
negative one.

Floats created in Java are written as PHP's `serialize()` writes the same double (`1.0E+25`,
`10000000`, `0.1`, `-0`), so they compare byte for byte with WordPress-written values.

## Data model

| Java type | PHP syntax | Notes |
|---|---|---|
| `PhpNull` | `N;` | singleton |
| `PhpBool` | `b:1;` | |
| `PhpInt` | `i:123;` | 64-bit |
| `PhpFloat` | `d:0.1;` | `raw()` keeps the lexeme read; written as PHP writes the double; `INF`, `-INF`, `NAN` supported |
| `PhpString` | `s:5:"hello";` | raw bytes plus a charset hint; equality by bytes |
| `PhpArray` | `a:2:{...}` | ordered; keys are `Key.Int` or `Key.Str` (integer text is always `Key.Int`) |
| `PhpObject` | `O:3:"Foo":1:{...}` | class name is data, never looked up |
| `PhpCustomObject` | `C:3:"Foo":5:{hello}` | opaque payload bytes |
| `PhpReference` | `r:2;` / `R:2;` | the tree keeps it for byte-exact round trips; `toJavaObject()` and `toJson()` resolve it to a copy of its target (a cycle throws `PhpSerializeException("cyclic reference")`) |
| `PhpEnum` | `E:11:"Suit:Hearts";` | PHP 8.1 enums |

## Safety

- **Never instantiates classes from data.** Class names are plain strings in the tree.
- **Bounded.** `ParseOptions` limits nesting depth (default 256, hard cap 1024 because parsing is
  recursive), total element count (default 1 000 000) and single string size (default 64 MiB).
  Declared sizes are checked before any allocation, so hostile input fails fast. Resolving
  back-references stops once the copies made for references exceed 1 000 000 values or nest deeper
  than 1024 levels (`PhpSerializeException`).
- **Only one exception type.** Malformed input raises `PhpSerializeException`; property-based
  fuzz tests assert that nothing else escapes.

## Differences from PHP

Checked by the `wp-parity` profile against PHP 8.4 and WordPress 7.1.3; each is listed in
`src/test/resources/wp-parity-skip.txt` with its audit item.

| Item | QuarkPress | PHP / WordPress | Reason |
|---|---|---|---|
| #23 | an enum is data: `E:9:"Foo:Hello";` parses to `PhpEnum` | fails (WordPress returns `false`) when the enum class or case does not exist | no class registry |
| #24 | `C:` payloads are kept as `PhpCustomObject`; unknown classes stay `PhpObject` | builds `__PHP_Incomplete_Class` and drops a `C:` payload | classes are never looked up (like `allowed_classes => false`, without losing data) |
| #25 | nesting deeper than `maxDepth` (default 256, at most 1024) fails | allows 4096 | the parser is recursive; stack safety |
| #26 | at most 1 000 000 elements and 64 MiB per string | memory only | bounded parsing of untrusted input |
| #22 | class names are text: bytes that are not UTF-8 become U+FFFD | keeps the bytes | class names are `String`s |
| #14 | after a repeated key, references are numbered over the deduplicated tree | numbers the overwritten value too | the overwritten value is not kept; PHP never writes repeated keys |
| #29 | `serializeToString` decodes the bytes as ISO-8859-1 | n/a | a debugging aid; use `serialize` for storage |

## Parity testing

`mvn verify -Pwp-parity` (needs `php` 8.4 and network) downloads the pinned WordPress 7.1.3 files
into `target/wp-source/`, runs PHP's `unserialize()`/`serialize()` and WordPress's four helpers on the
hand-written cases (`src/test/resources/wp-parity-cases.json`), the fixtures in `src/test/resources/wp/`
and a few thousand generated inputs, and compares every result with this library. See
`testing/wp-oracle`.

## Source policy

WordPress PHP is read to understand behaviour and is run as a test oracle; it is never copied into
this library (QUARKPRESS-SPEC.md §13).

## Licence

Apache License 2.0.
