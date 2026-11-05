package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;

class TikaTypesTest {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final Path DIR = Path.of("target", "tika-types-test");

    static Path write(String name, byte[] bytes) throws IOException {
        Files.createDirectories(DIR);
        Path file = DIR.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void tikaAnswersFromContentOnly() throws IOException {
        assertThat(TikaTypes.detect(write("x.bin", MediaFixtures.zipOoxml(DOCX))))
                .isEqualTo("application/zip");
        assertThat(TikaTypes.detect(write("photo.png", MediaFixtures.jpeg(8, 8))))
                .isEqualTo("image/jpeg");
        assertThat(TikaTypes.detect(write("x.csv", MediaFixtures.text("a,b\n1,2\n"))))
                .isEqualTo("text/plain");
        assertThat(TikaTypes.detect(write("favicon.ico", MediaFixtures.ico()))).isEqualTo("image/vnd.microsoft.icon");
    }

    @Test
    void parametersAreDropped() throws IOException {
        assertThat(TikaTypes.detect(write("archive.rar", TypeCheckTest.rar())))
                .isEqualTo("application/x-rar-compressed");
    }

    @Test
    void theNameIsNeverAHint() throws IOException {
        assertThat(TikaTypes.detect(write("report.pdf", MediaFixtures.text("plain words"))))
                .isEqualTo("text/plain");
        assertThat(TikaTypes.detect(write("clip.webm", MediaFixtures.text("plain words"))))
                .isEqualTo("text/plain");
        // names that would make Tika answer their own type: the content still decides
        assertThat(TikaTypes.detect(write("notes.txt", MediaFixtures.pdf()))).isEqualTo("application/pdf");
        assertThat(TikaTypes.detect(write("sheet.xlsx", MediaFixtures.zipOoxml(DOCX))))
                .isEqualTo("application/zip");
        assertThat(TikaTypes.detect(write("clip.webm", matroska("matroska")))).isEqualTo("video/x-matroska");
    }

    @Test
    void matroskaHeaderSizesAndIdsAreReadAsEbmlWritesThem() throws IOException {
        // the header size in its eight-byte form
        int body = matroska("webm").length - 5 - 5;
        byte[] eightBytes = {0x01, 0, 0, 0, 0, 0, 0, (byte) body};
        assertThat(TikaTypes.detect(write("e1.bin", matroska("webm", eightBytes, new byte[0]))))
                .isEqualTo("video/webm");
        // a declared size far beyond the file
        byte[] huge = {0x01, 0, 0, 0, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThat(TikaTypes.detect(write("e2.bin", matroska("webm", huge, new byte[0]))))
                .isEqualTo("application/x-matroska");
        // the unknown size (all ones)
        byte[] unknown = {
            0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        assertThat(TikaTypes.detect(write("e3.bin", matroska("webm", unknown, new byte[0]))))
                .isEqualTo("application/x-matroska");
        // a child whose id is five bytes long (EBML ids are at most four) before the DocType
        byte[] fiveByteId = {0x08, 0x01, 0x02, 0x03, 0x04, (byte) 0x81, 0x00};
        assertThat(TikaTypes.detect(write("e4.bin", matroska("webm", eightBytesFor(fiveByteId), fiveByteId))))
                .isEqualTo("application/x-matroska");
        // a child skipped by its size, then the DocType
        byte[] voidElement = {(byte) 0xEC, (byte) 0x83, 0x00, 0x00, 0x00};
        assertThat(TikaTypes.detect(write("e5.bin", matroska("webm", eightBytesFor(voidElement), voidElement))))
                .isEqualTo("video/webm");
    }

    /** The eight-byte size form of a header holding {@code firstChild} and the usual elements of a webm header. */
    private static byte[] eightBytesFor(byte[] firstChild) {
        int body = matroska("webm").length - 5 - 5 + firstChild.length;
        return new byte[] {0x01, 0, 0, 0, 0, 0, 0, (byte) body};
    }

    @Test
    void matroskaIsNamedByItsDocType() throws IOException {
        assertThat(TikaTypes.detect(write("a.bin", MediaFixtures.webm()))).isEqualTo("video/webm");
        assertThat(TikaTypes.detect(write("b.bin", matroska("matroska")))).isEqualTo("video/x-matroska");
        assertThat(TikaTypes.detect(write("c.bin", matroska("other")))).isEqualTo("application/x-matroska");
    }

    @Test
    void aTruncatedOrBrokenMatroskaHeaderKeepsTikasAnswer() throws IOException {
        byte[] webm = MediaFixtures.webm();
        // cut inside the DocType value
        assertThat(TikaTypes.detect(write("t1.bin", Arrays.copyOf(webm, 26)))).isEqualTo("application/x-matroska");
        // cut right after the EBML element's size
        assertThat(TikaTypes.detect(write("t2.bin", Arrays.copyOf(webm, 5)))).isNotIn("video/webm", "video/x-matroska");
        // an EBML header of unknown size
        byte[] unknown = webm.clone();
        unknown[4] = (byte) 0xFF;
        assertThat(TikaTypes.detect(write("t3.bin", unknown))).isNotIn("video/webm", "video/x-matroska");
        // a DocType past the header's declared end is not read
        byte[] shortHeader = webm.clone();
        shortHeader[4] = (byte) 0x82; // the header claims two bytes only
        assertThat(TikaTypes.detect(write("t4.bin", shortHeader))).isNotIn("video/webm", "video/x-matroska");
    }

    @Test
    void oggTheoraIsVideo() throws IOException {
        assertThat(TikaTypes.detect(write("a.ogx", MediaFixtures.ogv()))).isEqualTo("video/ogg");
        assertThat(TikaTypes.detect(write("b.ogx", MediaFixtures.ogg()))).isEqualTo("audio/vorbis");
    }

    @Test
    void aTruncatedOggPageKeepsTikasAnswer() throws IOException {
        byte[] ogv = MediaFixtures.ogv();
        assertThat(TikaTypes.detect(write("t1.ogx", Arrays.copyOf(ogv, 32)))).isNotEqualTo("video/ogg");
        byte[] manySegments = ogv.clone();
        manySegments[26] = (byte) 0xFF; // a segment table longer than the file
        assertThat(TikaTypes.detect(write("t2.ogx", manySegments))).isNotEqualTo("video/ogg");
    }

    /** An EBML header with {@code docType} and an empty Segment, as a Matroska muxer writes it. */
    static byte[] matroska(String docType) {
        return matroska(docType, null, new byte[0]);
    }

    /**
     * An EBML header with {@code docType}, its size written as {@code size} (null: the one-byte form of the real size)
     * and {@code firstChild} placed before the version elements.
     */
    static byte[] matroska(String docType, byte[] size, byte[] firstChild) {
        byte[] type = docType.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.writeBytes(firstChild);
        header.writeBytes(new byte[] {0x42, (byte) 0x86, (byte) 0x81, 0x01}); // EBMLVersion 1
        header.writeBytes(new byte[] {0x42, (byte) 0xF7, (byte) 0x81, 0x01}); // EBMLReadVersion 1
        header.writeBytes(new byte[] {0x42, (byte) 0xF2, (byte) 0x81, 0x04}); // EBMLMaxIDLength 4
        header.writeBytes(new byte[] {0x42, (byte) 0xF3, (byte) 0x81, 0x08}); // EBMLMaxSizeLength 8
        header.writeBytes(new byte[] {0x42, (byte) 0x82, (byte) (0x80 | type.length)});
        header.writeBytes(type);
        header.writeBytes(new byte[] {0x42, (byte) 0x87, (byte) 0x81, 0x04}); // DocTypeVersion 4
        header.writeBytes(new byte[] {0x42, (byte) 0x85, (byte) 0x81, 0x02}); // DocTypeReadVersion 2
        byte[] body = header.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
        out.writeBytes(size == null ? new byte[] {(byte) (0x80 | body.length)} : size);
        out.writeBytes(body);
        out.writeBytes(new byte[] {0x18, 0x53, (byte) 0x80, 0x67, (byte) 0x80}); // empty Segment
        return out.toByteArray();
    }
}
