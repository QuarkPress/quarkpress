# Media native spike (throwaway, M2a task 1)

Checks that a Quarkus native executable can use JDK ImageIO with TwelveMonkeys 3.15 (JPEG incl. CMYK, WebP read),
Apache Tika's default MIME detector and metadata-extractor. Each check is an HTTP endpoint the tests post image bytes
to (`/media/readers`, `/media/inspect`, `/media/resize`), so `MediaSpikeTest` (JVM) and `MediaSpikeIT` (native, CI
only) share assertions.

```bash
mvn -pl integration-tests/media-native-spike -am verify
```

The test images in `src/test/resources/images` are written by `TestImageGenerator` with the JDK alone (the WebP is
hand-encoded VP8L): `java src/test/java/org/quarkpress/it/media/TestImageGenerator.java src/test/resources/images`.
Native registrations are in `application.properties` and `NativeRegistrations`. The module is removed once the
`quarkpress-media-image-imageio` extension carries these registrations and its own native test.
