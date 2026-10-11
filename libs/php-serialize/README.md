# quarkpress-lib-php-serialize

A plain-Java reader and writer for the PHP `serialize()` format. Zero runtime dependencies,
no reflection, no Quarkus.

WordPress stores options, widget settings, post meta, user roles and cron data as PHP serialized
strings. This library lets migration and import code read those values safely and write them back
byte for byte.

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
PhpValue value = PhpSerialize.parse(bytesFromDatabase);

if (value instanceof PhpArray a) {
    PhpValue title = a.get("title");          // lookup by UTF-8 string key
    List<PhpValue> items = a.values();
}

Object plain = value.toJavaObject();          // List / LinkedHashMap / String / Long / Double / Boolean / null
String json = value.toJson();                 // compact JSON, no dependency
```

Pass a `String` only if you know its encoding matches `ParseOptions.charset()`: declared lengths
are **byte** counts, so the text is encoded first and then parsed.

### Repair corrupted lengths

A naive search-and-replace on serialized data (changing a site URL, for example) leaves string
lengths wrong. Repair mode recovers such values:

```java
ParseOptions lenient = ParseOptions.DEFAULTS.withRepairLengths(true);
PhpValue repaired = PhpSerialize.parse(bytes, lenient);
byte[] fixed = PhpSerialize.serialize(repaired);   // lengths are correct again
```

Strict mode (the default) rejects the same input with a `PhpSerializeException` carrying the byte
offset of the problem.

### Detect

```java
if (PhpSerialize.looksSerialized(optionValue)) { ... }
```

A cheap heuristic in the spirit of what WordPress uses to decide whether an option needs
unserializing. It does not validate the content.

### Write

```java
PhpValue v = PhpArray.builder()
        .put("sidebar-1", PhpArray.of(PhpString.of("block-2"), PhpString.of("block-3")))
        .put("array_version", new PhpInt(3))
        .build();
byte[] bytes = PhpSerialize.serialize(v);
```

Parsing and re-serializing any supported document reproduces the original bytes exactly, including
float lexemes and non-UTF-8 string payloads.

## Data model

| Java type | PHP syntax | Notes |
|---|---|---|
| `PhpNull` | `N;` | singleton |
| `PhpBool` | `b:1;` | |
| `PhpInt` | `i:123;` | 64-bit |
| `PhpFloat` | `d:0.1;` | keeps the original lexeme; `INF`, `-INF`, `NAN` supported |
| `PhpString` | `s:5:"hello";` | raw bytes plus a charset hint; equality by bytes |
| `PhpArray` | `a:2:{...}` | ordered; keys are `Key.Int` or `Key.Str` |
| `PhpObject` | `O:3:"Foo":1:{...}` | class name is data, never looked up |
| `PhpCustomObject` | `C:3:"Foo":5:{hello}` | opaque payload bytes |
| `PhpReference` | `r:2;` / `R:2;` | kept symbolic, not resolved |
| `PhpEnum` | `E:11:"Suit:Hearts";` | PHP 8.1 enums |

## Safety

- **Never instantiates classes from data.** Class names are plain strings in the tree.
- **Bounded.** `ParseOptions` limits nesting depth (default 512), total element count (default
  1 000 000) and single string size (default 64 MiB). Declared sizes are checked before any
  allocation, so hostile input fails fast.
- **Only one exception type.** Malformed input raises `PhpSerializeException`; property-based
  fuzz tests assert that nothing else escapes.

## Licence

Apache License 2.0.
