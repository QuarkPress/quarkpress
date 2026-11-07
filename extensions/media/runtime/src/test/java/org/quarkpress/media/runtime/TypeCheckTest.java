package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.media.runtime.DefaultMimeTypes.PATTERNS;
import static org.quarkpress.media.runtime.TikaTypesTest.DOCX;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.runtime.TypeCheck.TypeResult;
import org.quarkpress.testing.wprestparity.MediaFixtures;

class TypeCheckTest {

    private static final Path DIR = Path.of("target", "type-check-test");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final AllowedTypes author = AllowedTypes.of(PATTERNS, false, List.of(), Optional.empty());
    private final AllowedTypes admin = AllowedTypes.of(PATTERNS, true, List.of(), Optional.empty());

    /** The bytes in a file whose name carries no extension, so only the content can speak. */
    static Path write(byte[] bytes) throws IOException {
        Files.createDirectories(DIR);
        Path file = DIR.resolve("upload-" + COUNTER.incrementAndGet() + ".tmp");
        Files.write(file, bytes);
        return file;
    }

    static Path write(String name, byte[] bytes) throws IOException {
        Files.createDirectories(DIR);
        Path file = DIR.resolve(COUNTER.incrementAndGet() + "-" + name);
        Files.write(file, bytes);
        return file;
    }

    private TypeResult check(byte[] bytes, String clientName) throws IOException {
        return TypeCheck.check(write(bytes), clientName, PATTERNS, author);
    }

    private static TypeResult result(String extension, String mimeType, String properName) {
        return new TypeResult(Optional.of(extension), Optional.of(mimeType), Optional.ofNullable(properName));
    }

    private static TypeResult accepted(String extension, String mimeType) {
        return result(extension, mimeType, null);
    }

    @Test
    void imagesAreRenamedToTheirRealType() throws IOException {
        assertThat(check(MediaFixtures.jpeg(8, 8), "photo.png")).isEqualTo(result("jpg", "image/jpeg", "photo.jpg"));
        assertThat(check(MediaFixtures.jpeg(8, 8), "photo.PNG")).isEqualTo(result("jpg", "image/jpeg", "photo.jpg"));
        assertThat(check(MediaFixtures.jpeg(8, 8), "photo.jpeg")).isEqualTo(result("jpeg", "image/jpeg", null));
        assertThat(check(MediaFixtures.jpeg(8, 8), "photo.JPG")).isEqualTo(result("JPG", "image/jpeg", null));
        assertThat(check(MediaFixtures.png(8, 8), "pic.webp")).isEqualTo(result("png", "image/png", "pic.png"));
        assertThat(check(MediaFixtures.heif("heif"), "x.heif")).isEqualTo(result("heic", "image/heic", "x.heic"));
        assertThat(check(MediaFixtures.heif("heic"), "x.heics")).isEqualTo(result("heic", "image/heic", "x.heic"));
        assertThat(check(MediaFixtures.heif("heif"), "x.HEIF")).isEqualTo(result("HEIF", "image/heif", null));
        assertThat(check(MediaFixtures.heif("avis"), "x.avif")).isEqualTo(result("avif", "image/avif", null));
        assertThat(check(MediaFixtures.heif("mif1"), "x.avif")).isEqualTo(result("heic", "image/heic", "x.heic"));
        assertThat(check(MediaFixtures.heif("msf1"), "x.heifs")).isEqualTo(result("heic", "image/heic", "x.heic"));
        assertThat(check(MediaFixtures.png(8, 8), "a.b.jpg")).isEqualTo(result("png", "image/png", "a.b.png"));
        assertThat(check(MediaFixtures.pdf(), "x.jpg").refused()).isTrue();
        assertThat(check(MediaFixtures.text("hello"), "x.jpg").refused()).isTrue();
    }

    @Test
    void theFiveAcceptanceRules() throws IOException {
        assertThat(check(MediaFixtures.pdf(), "report.pdf")).isEqualTo(accepted("pdf", "application/pdf"));
        assertThat(check(MediaFixtures.text("just words\n"), "report.pdf")).isEqualTo(TypeResult.REFUSED);
        assertThat(check(MediaFixtures.text("just words\n"), "notes.txt")).isEqualTo(accepted("txt", "text/plain"));
        assertThat(check(MediaFixtures.text("name,value\none,1\ntwo,2\n"), "data.csv"))
                .isEqualTo(accepted("csv", "text/csv"));
        assertThat(check(MediaFixtures.text("name\tvalue\none\t1\ntwo\t2\n"), "data.tsv")
                        .refused())
                .isTrue();
        assertThat(check(MediaFixtures.text("body {\n  color: red;\n}\n"), "style.css")
                        .refused())
                .isTrue();
        assertThat(check(MediaFixtures.mp4(64, 48, 1), "movie.mp4")).isEqualTo(accepted("mp4", "video/mp4"));
        assertThat(check(MediaFixtures.m4a(), "song.m4a")).isEqualTo(accepted("m4a", "audio/mpeg"));
        assertThat(check(MediaFixtures.mp4(64, 48, 1), "song.m4a").refused()).isTrue();
        assertThat(check(MediaFixtures.zipOoxml(DOCX), "archive.docx")).isEqualTo(accepted("docx", DOCX));
        assertThat(check(MediaFixtures.xls(), "legacy.xls")).isEqualTo(accepted("xls", "application/vnd.ms-excel"));
        assertThat(check(MediaFixtures.psd(), "design.psd").refused()).isTrue();
        assertThat(check(MediaFixtures.ico(), "favicon.ico").refused()).isTrue();
        assertThat(check(ics(), "cal.ics")).isEqualTo(accepted("ics", "text/calendar"));
        assertThat(check(rtf(), "doc.rtf")).isEqualTo(accepted("rtf", "application/rtf"));
        assertThat(check(MediaFixtures.webm(), "clip.webm")).isEqualTo(accepted("webm", "video/webm"));
        assertThat(check(MediaFixtures.wav(1), "sound.wav")).isEqualTo(accepted("wav", "audio/wav"));
        assertThat(check(MediaFixtures.ogv(), "clip.ogv")).isEqualTo(accepted("ogv", "video/ogg"));
        assertThat(check(vtt(), "subs.vtt")).isEqualTo(accepted("vtt", "text/vtt"));
        assertThat(check(flac(), "sound.flac")).isEqualTo(accepted("flac", "audio/flac"));
        assertThat(check(MediaFixtures.odt(), "doc.odt"))
                .isEqualTo(accepted("odt", "application/vnd.oasis.opendocument.text"));
        assertThat(check(MediaFixtures.ogg(), "sound.ogg")).isEqualTo(accepted("ogg", "audio/ogg"));
        // a Matroska file is named by its DocType, so .mkv passes as the map's type
        assertThat(check(TikaTypesTest.matroska("matroska"), "film.mkv"))
                .isEqualTo(accepted("mkv", "video/x-matroska"));
        // an Ogg Vorbis file named .ogv is audio content for a video name: the top levels differ
        assertThat(check(MediaFixtures.ogg(), "clip.ogv").refused()).isTrue();
        // WordPress refuses these; Tika's content-only answers refuse them as well
        assertThat(check(cSource(), "main.c").refused()).isTrue();
        assertThat(check(srt(), "subs.srt").refused()).isTrue();
        assertThat(check(gzip(MediaFixtures.text("gzip content\n")), "archive.gz")
                        .refused())
                .isTrue();
        assertThat(check(rar(), "archive.rar").refused()).isTrue();
        assertThat(TypeCheck.check(write(MediaFixtures.text("console.log('x');\n")), "app.js", PATTERNS, admin)
                        .refused())
                .isTrue();
    }

    @Test
    void nonSpecificContentPassesOnlyForApplicationAudioAndVideoNames() throws IOException {
        byte[] zip = MediaFixtures.zipOoxml(DOCX);
        assertThat(check(zip, "archive.zip")).isEqualTo(accepted("zip", "application/zip"));
        assertThat(check(zip, "x.mp3")).isEqualTo(accepted("mp3", "audio/mpeg"));
        assertThat(check(zip, "x.avi")).isEqualTo(accepted("avi", "video/avi"));
        assertThat(check(zip, "x.txt").refused()).isTrue();
    }

    @Test
    void contentAnswersAgreeWithTheirRelatedTypes() {
        assertThat(TypeCheck.contentAgrees("application/csv", "text/csv")).isTrue();
        assertThat(TypeCheck.contentAgrees("application/csv", "text/plain")).isTrue();
        assertThat(TypeCheck.contentAgrees("application/csv", "application/csv"))
                .isTrue();
        assertThat(TypeCheck.contentAgrees("application/csv", "text/tab-separated-values"))
                .isFalse();
        assertThat(TypeCheck.contentAgrees("text/rtf", "text/rtf")).isTrue();
        assertThat(TypeCheck.contentAgrees("text/rtf", "text/plain")).isTrue();
        assertThat(TypeCheck.contentAgrees("text/rtf", "application/rtf")).isTrue();
        assertThat(TypeCheck.contentAgrees("text/rtf", "text/csv")).isFalse();
        assertThat(TypeCheck.contentAgrees("text/plain", "text/richtext")).isTrue();
        assertThat(TypeCheck.contentAgrees("text/plain", "text/tsv")).isTrue();
        assertThat(TypeCheck.contentAgrees("text/plain", "application/csv")).isTrue();
        assertThat(TypeCheck.contentAgrees("text/plain", "text/tab-separated-values"))
                .isFalse();
        assertThat(TypeCheck.contentAgrees("text/plain", "application/rtf")).isFalse();
        assertThat(TypeCheck.contentAgrees("application/CDFV2-encrypted", "application/msword"))
                .isTrue();
        assertThat(TypeCheck.contentAgrees("application/encrypted", "image/png"))
                .isFalse();
        assertThat(TypeCheck.contentAgrees("audio/x-flac", "video/ogg")).isFalse();
        assertThat(TypeCheck.contentAgrees("text/csv", "text/csv")).isTrue();
        assertThat(TypeCheck.contentAgrees("text/csv", "text/plain")).isFalse();
    }

    @Test
    void aMissingFileKeepsTheNamesType() throws IOException {
        Files.createDirectories(DIR);
        Path missing = DIR.resolve("never-written.tmp");
        Files.deleteIfExists(missing);
        assertThat(TypeCheck.check(missing, "photo.png", PATTERNS, author)).isEqualTo(accepted("png", "image/png"));
        assertThat(TypeCheck.check(missing, "x.php", PATTERNS, author)).isEqualTo(TypeResult.REFUSED);
    }

    @Test
    void heifFamilyBrandsAreRecognisedAsWordPressReadsThem() throws IOException {
        assertThat(ImageSignatures.realType(write(MediaFixtures.heif("heif")))).contains("image/heif");
        assertThat(ImageSignatures.realType(write(MediaFixtures.heif("heic")))).contains("image/heic");
        assertThat(ImageSignatures.realType(write(MediaFixtures.heif("avif")))).contains("image/avif");
        assertThat(ImageSignatures.realType(write(MediaFixtures.heif("avis")))).contains("image/avif");
        assertThat(ImageSignatures.realType(write(MediaFixtures.heif("mif1")))).contains("image/heif");
        assertThat(ImageSignatures.realType(write(MediaFixtures.heif("msf1")))).contains("image/heif-sequence");
        assertThat(ImageSignatures.realType(write(MediaFixtures.heif("isom")))).isEmpty();
        assertThat(ImageSignatures.realType(write(MediaFixtures.webp()))).contains("image/webp");
        assertThat(ImageSignatures.realType(write(MediaFixtures.gif(4, 4, 1)))).contains("image/gif");
        assertThat(ImageSignatures.realType(write(MediaFixtures.bmp(4, 4)))).contains("image/bmp");
        assertThat(ImageSignatures.realType(write(MediaFixtures.tiff(4, 4)))).contains("image/tiff");
        assertThat(ImageSignatures.realType(write(MediaFixtures.ico()))).isEmpty();
        assertThat(ImageSignatures.realType(write(MediaFixtures.psd()))).isEmpty();
        assertThat(ImageSignatures.realType(write(MediaFixtures.pdf()))).isEmpty();
        assertThat(ImageSignatures.realType(write(new byte[] {'R', 'I', 'F', 'F'})))
                .isEmpty();
        assertThat(ImageSignatures.realType(write(new byte[0]))).isEmpty();
    }

    @Test
    void theFinalTypeMustBeAllowedForTheUser() throws IOException {
        byte[] html = MediaFixtures.text("<html><body>x</body></html>");
        assertThat(TypeCheck.check(write("x.html", html), "x.html", PATTERNS, author)
                        .refused())
                .isTrue();
        assertThat(TypeCheck.check(write("x.html", html), "x.html", PATTERNS, admin)
                        .mimeType())
                .contains("text/html");
        // a real PNG named x.jpg when png is denied: the rewritten name has no allowed type
        AllowedTypes noPng = AllowedTypes.of(PATTERNS, false, List.of("png"), Optional.empty());
        assertThat(TypeCheck.check(write(MediaFixtures.png(8, 8)), "x.jpg", PATTERNS, noPng))
                .isEqualTo(TypeResult.REFUSED);
        // a real JPEG or PNG named .webp with webp denied: the client's name is not allowed, so nothing is renamed and
        // the upload is refused, as WordPress refuses it (recorded by the media parity run)
        AllowedTypes noWebp = AllowedTypes.of(PATTERNS, false, List.of("webp"), Optional.empty());
        assertThat(TypeCheck.check(write(MediaFixtures.jpeg(8, 8)), "x.webp", PATTERNS, noWebp))
                .isEqualTo(TypeResult.REFUSED);
        assertThat(TypeCheck.check(write(MediaFixtures.png(8, 8)), "pic.webp", PATTERNS, noWebp))
                .isEqualTo(TypeResult.REFUSED);
        // with webp allowed the same PNG is renamed
        assertThat(TypeCheck.check(write(MediaFixtures.png(8, 8)), "pic.webp", PATTERNS, author))
                .isEqualTo(result("png", "image/png", "pic.png"));
        // a real WebP named x.webp with webp denied keeps its denied extension
        assertThat(TypeCheck.check(write(MediaFixtures.webp()), "x.webp", PATTERNS, noWebp))
                .isEqualTo(TypeResult.REFUSED);
        // the final check applies without content too
        Path missing = DIR.resolve("never-written-html.tmp");
        Files.deleteIfExists(missing);
        assertThat(TypeCheck.check(missing, "x.html", PATTERNS, author)).isEqualTo(TypeResult.REFUSED);
        // the final extension must be allowed with the whole list's type
        AllowedTypes otherText = AllowedTypes.of(
                List.of(java.util.Map.entry("txt", "text/x-other")), false, List.of(), Optional.empty());
        assertThat(TypeCheck.check(write(MediaFixtures.text("words\n")), "notes.txt", PATTERNS, otherText))
                .isEqualTo(TypeResult.REFUSED);
        AllowedTypes plainText =
                AllowedTypes.of(List.of(java.util.Map.entry("txt", "text/plain")), false, List.of(), Optional.empty());
        assertThat(TypeCheck.check(write(MediaFixtures.text("words\n")), "notes.txt", PATTERNS, plainText))
                .isEqualTo(accepted("txt", "text/plain"));
        // the type must still be allowed after the rewrite when only the content's extension remains
        AllowedTypes noJpeg = AllowedTypes.of(PATTERNS, false, List.of("jpg", "jpeg", "jpe"), Optional.empty());
        assertThat(TypeCheck.check(write(MediaFixtures.jpeg(8, 8)), "x.png", PATTERNS, noJpeg))
                .isEqualTo(TypeResult.REFUSED);
    }

    @Test
    void contentThatLiesIsRefused() throws IOException {
        assertThat(check(MediaFixtures.text("<?php system($_GET['c']); ?>"), "photo.jpg")
                        .refused())
                .isTrue();
        assertThat(check(MediaFixtures.text("<html><script>x</script></html>"), "x.png")
                        .refused())
                .isTrue();
        assertThat(check(MediaFixtures.text("<svg xmlns='http://www.w3.org/2000/svg'/>"), "x.svg")
                        .refused())
                .isTrue();
        assertThat(check(polyglotGif(), "x.gif")).isEqualTo(result("gif", "image/gif", null));
        assertThat(check(MediaFixtures.jpeg(8, 8), "shell.php.jpg")).isEqualTo(result("jpg", "image/jpeg", null));
        assertThat(check(MediaFixtures.jpeg(8, 8), "photo")).isEqualTo(TypeResult.REFUSED);
        assertThat(check(MediaFixtures.jpeg(8, 8), "x.jpg.php")).isEqualTo(TypeResult.REFUSED);
        assertThat(check(MediaFixtures.pdf(), "x.jpg")).isEqualTo(TypeResult.REFUSED);
    }

    @Test
    void refusedResultsAreRefused() {
        assertThat(TypeResult.REFUSED.refused()).isTrue();
        assertThat(new TypeResult(Optional.of("jpg"), Optional.empty(), Optional.empty()).refused())
                .isTrue();
        assertThat(new TypeResult(Optional.empty(), Optional.of("image/jpeg"), Optional.empty()).refused())
                .isTrue();
        assertThat(accepted("jpg", "image/jpeg").refused()).isFalse();
    }

    /** A one-frame GIF followed by a JavaScript statement after its trailer. */
    static byte[] polyglotGif() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MediaFixtures.gif(4, 4, 1));
        out.writeBytes("\n;alert(document.domain);//\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    static byte[] ics() {
        return MediaFixtures.text("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//qp//EN\r\nEND:VCALENDAR\r\n");
    }

    static byte[] rtf() {
        return MediaFixtures.text("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times;}}\\f0 Hello}\n");
    }

    static byte[] vtt() {
        return MediaFixtures.text("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n");
    }

    static byte[] srt() {
        return MediaFixtures.text("1\n00:00:01,000 --> 00:00:02,000\nHello\n\n");
    }

    static byte[] cSource() {
        return MediaFixtures.text("#include <stdio.h>\nint main(void) {\n    return 0;\n}\n");
    }

    static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.toByteArray();
    }

    /** A RAR 4 signature followed by a main archive header and the end-of-archive block. */
    static byte[] rar() {
        return new byte[] {
            0x52,
            0x61,
            0x72,
            0x21,
            0x1A,
            0x07,
            0x00,
            (byte) 0xCF,
            (byte) 0x90,
            0x73,
            0x00,
            0x00,
            0x0D,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            (byte) 0xC4,
            0x3D,
            0x7B,
            0x00,
            0x40,
            0x07,
            0x00
        };
    }

    /** A FLAC stream marker and one final STREAMINFO block (44.1 kHz, stereo, 16 bits). */
    static byte[] flac() {
        byte[] out = new byte[42];
        out[0] = 'f';
        out[1] = 'L';
        out[2] = 'a';
        out[3] = 'C';
        out[4] = (byte) 0x80;
        out[7] = 34;
        out[8] = 0x10;
        out[10] = 0x10;
        out[18] = 0x0A;
        out[19] = (byte) 0xC4;
        out[20] = 0x42;
        out[21] = (byte) 0xF0;
        return out;
    }
}
