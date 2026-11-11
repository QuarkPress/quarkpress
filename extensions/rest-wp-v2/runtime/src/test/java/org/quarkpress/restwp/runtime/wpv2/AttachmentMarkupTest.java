package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.runtime.wpv2.AttachmentMarkup.Attachment;
import org.quarkpress.restwp.runtime.wpv2.AttachmentMarkup.Counters;
import org.quarkpress.restwp.runtime.wpv2.AttachmentMarkup.Size;

/** The markup WordPress puts in front of an attachment's description and builds its generated caption from. */
class AttachmentMarkupTest {

    private static final String UPLOADS = "http://wp.test/wp-content/uploads/2026/11/";

    /** A size whose file is in the uploads folder of the test attachments. */
    private static Size size(String name, String file, int width, int height) {
        return new Size(name, file, width, height, UPLOADS + file);
    }

    private static Attachment image(String file, int width, int height, List<Size> sizes) {
        return new Attachment(
                7, "image/jpeg", UPLOADS + file, "a", file, "", OptionalInt.of(width), OptionalInt.of(height), sizes);
    }

    private static Attachment file(long id, String mimeType, String url, String title) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        return new Attachment(id, mimeType, url, title, name, "", OptionalInt.empty(), OptionalInt.empty(), List.of());
    }

    private static String prepended(Attachment attachment) {
        return AttachmentMarkup.prepended(attachment, 300, 300, new Counters());
    }

    private static String medium(String src, int width, int height) {
        return "<img loading=\"lazy\" decoding=\"async\" width=\"" + width + "\" height=\"" + height + "\" src=\"" + src
                + "\" class=\"attachment-medium size-medium\" alt=\"\" />";
    }

    @Test
    void anImageWithoutSizesShowsTheMainFileInTheMediumBox() {
        assertThat(prepended(image("a.jpg", 4000, 3000, List.of())))
                .isEqualTo("<p class=\"attachment\"><a href='http://wp.test/wp-content/uploads/2026/11/a.jpg'><img"
                        + " loading=\"lazy\" decoding=\"async\" width=\"300\" height=\"225\""
                        + " src=\"http://wp.test/wp-content/uploads/2026/11/a.jpg\""
                        + " class=\"attachment-medium size-medium\" alt=\"\" /></a></p>\n");
        assertThat(prepended(image("b.jpg", 1200, 800, List.of()))).contains(medium(UPLOADS + "b.jpg", 300, 200));
        assertThat(prepended(image("c.jpg", 64, 48, List.of()))).contains(medium(UPLOADS + "c.jpg", 64, 48));
        Attachment avif = new Attachment(
                8,
                "image/avif",
                UPLOADS + "d.avif",
                "d",
                "d.avif",
                "",
                OptionalInt.of(3000),
                OptionalInt.of(2000),
                List.of());
        assertThat(prepended(avif)).contains(medium(UPLOADS + "d.avif", 300, 200));
        Attachment heic = new Attachment(
                9,
                "image/heic",
                UPLOADS + "e.heic",
                "e",
                "e.heic",
                "",
                OptionalInt.empty(),
                OptionalInt.empty(),
                List.of());
        assertThat(prepended(heic))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "e.heic'>" + medium(UPLOADS + "e.heic", 1, 1)
                        + "</a></p>\n");
    }

    @Test
    void theAltTextIsStrippedTrimmedAndEscaped() {
        Attachment withAlt = new Attachment(
                7,
                "image/jpeg",
                UPLOADS + "a.jpg",
                "Title is never the alt",
                "a.jpg",
                "  <b>Pier</b> & \"sea\" ",
                OptionalInt.of(64),
                OptionalInt.of(48),
                List.of());
        assertThat(prepended(withAlt)).contains(" alt=\"Pier &amp; &quot;sea&quot;\" />");
    }

    @Test
    void theMediumSizeAndItsSrcsetAreUsed() {
        // The sizes in the order the browser's upload stores them (thumbnail first); the expected srcset is the one
        // WordPress showed for that upload. The -scaled main file (2560) is wider than 2048 and is left out.
        List<Size> sizes = List.of(
                size("thumbnail", "a-150x150.jpg", 150, 150),
                size("medium", "a-300x225.jpg", 300, 225),
                size("medium_large", "a-768x576.jpg", 768, 576),
                size("large", "a-1024x768.jpg", 1024, 768),
                size("1536x1536", "a-1536x1152.jpg", 1536, 1152),
                size("2048x2048", "a-2048x1536.jpg", 2048, 1536));
        Attachment scaled = image("a-scaled.jpg", 2560, 1920, sizes);
        assertThat(prepended(scaled))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "a-scaled.jpg'><img loading=\"lazy\""
                        + " decoding=\"async\" width=\"300\" height=\"225\" src=\"" + UPLOADS + "a-300x225.jpg\""
                        + " class=\"attachment-medium size-medium\" alt=\"\" srcset=\""
                        + UPLOADS + "a-300x225.jpg 300w, "
                        + UPLOADS + "a-768x576.jpg 768w, "
                        + UPLOADS + "a-1024x768.jpg 1024w, "
                        + UPLOADS + "a-1536x1152.jpg 1536w, "
                        + UPLOADS + "a-2048x1536.jpg 2048w\""
                        + " sizes=\"auto, (max-width: 300px) 100vw, 300px\" /></a></p>\n");
    }

    @Test
    void theSrcsetFollowsTheStoredOrderAfterTheDisplayedFile() {
        // Sizes as the server generates them: medium, large, thumbnail, medium_large, then the others.
        List<Size> sizes = List.of(
                size("medium", "a-300x225.jpg", 300, 225),
                size("large", "a-1024x768.jpg", 1024, 768),
                size("thumbnail", "a-150x150.jpg", 150, 150),
                size("medium_large", "a-768x576.jpg", 768, 576));
        assertThat(AttachmentMarkup.mediumImage(image("a.jpg", 1600, 1200, sizes), 300, 300)
                        .orElseThrow()
                        .srcset())
                .isEqualTo(UPLOADS + "a-300x225.jpg 300w, " + UPLOADS + "a-1024x768.jpg 1024w, " + UPLOADS
                        + "a-768x576.jpg 768w, " + UPLOADS + "a.jpg 1600w");
    }

    @Test
    void theDisplayedFileKeepsItsWidthAndALaterFileTakesAnotherWidth() {
        // Reference only: one entry per width, a later file taking an earlier one's slot, never the displayed file's.
        List<Size> sizes = List.of(
                size("medium", "a-300x225.jpg", 300, 225),
                size("custom", "a-custom.jpg", 300, 225),
                size("medium_large", "a-768x576.jpg", 768, 576),
                size("large", "a-1024x768.jpg", 1024, 768),
                size("other", "a-other.jpg", 768, 576));
        assertThat(AttachmentMarkup.mediumImage(image("a.jpg", 2560, 1920, sizes), 300, 300)
                        .orElseThrow()
                        .srcset())
                .isEqualTo(UPLOADS + "a-300x225.jpg 300w, " + UPLOADS + "a-other.jpg 768w, " + UPLOADS
                        + "a-1024x768.jpg 1024w");
    }

    @Test
    void imageAttributesAreEscaped() {
        // Reference only: src, srcset and sizes go through attribute escaping; the href through URL escaping.
        List<Size> sizes =
                List.of(size("medium", "a&b-300x225.jpg", 300, 225), size("medium_large", "a&b-768x576.jpg", 768, 576));
        assertThat(prepended(image("a&b.jpg", 1600, 1200, sizes)))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "a&#038;b.jpg'><img loading=\"lazy\""
                        + " decoding=\"async\" width=\"300\" height=\"225\" src=\"" + UPLOADS + "a&amp;b-300x225.jpg\""
                        + " class=\"attachment-medium size-medium\" alt=\"\" srcset=\""
                        + UPLOADS + "a&amp;b-300x225.jpg 300w, "
                        + UPLOADS + "a&amp;b-768x576.jpg 768w, "
                        + UPLOADS + "a&amp;b.jpg 1600w\""
                        + " sizes=\"auto, (max-width: 300px) 100vw, 300px\" /></a></p>\n");
    }

    @Test
    void storedSizesWithoutMainDimensionsGiveNoSrcset() {
        // Reference only (sized half of the deleted-metadata case pending): the 1x1 main file is not a candidate,
        // so the square sizes never form a srcset without it.
        List<Size> sizes =
                List.of(size("thumbnail", "a-150x150.jpg", 150, 150), size("square", "a-300x300.jpg", 300, 300));
        Attachment noDimensions = new Attachment(
                7, "image/jpeg", UPLOADS + "a.jpg", "a", "a.jpg", "", OptionalInt.empty(), OptionalInt.empty(), sizes);
        assertThat(prepended(noDimensions))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "a.jpg'>" + medium(UPLOADS + "a.jpg", 1, 1)
                        + "</a></p>\n");
    }

    @Test
    void aFileWithAFullPreviewShowsItInTheMediumBox() {
        // Reference only (PDF previews need Imagick): the full preview fitted into the medium box, linked to the file.
        Attachment pdf = new Attachment(
                4,
                "application/pdf",
                UPLOADS + "doc.pdf",
                "doc",
                "doc.pdf",
                "",
                OptionalInt.empty(),
                OptionalInt.empty(),
                List.of(size("full", "doc-pdf.jpg", 1088, 1408)));
        String markup = prepended(pdf);
        assertThat(markup)
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "doc.pdf'>"
                        + medium(UPLOADS + "doc-pdf.jpg", 232, 300) + "</a></p>\n");
        assertThat(AttachmentMarkup.generatedCaption(markup, "")).isEmpty();
    }

    @Test
    void aSrcsetNeedsTwoCandidates() {
        // Only the thumbnail crop beside the displayed main file: no srcset, no sizes.
        List<Size> sizes = List.of(size("thumbnail", "a-150x150.jpg", 150, 150));
        AttachmentMarkup.Image shown = AttachmentMarkup.mediumImage(image("a.jpg", 200, 150, sizes), 300, 300)
                .orElseThrow();
        assertThat(shown.srcset()).isEmpty();
        assertThat(shown.sizes()).isEmpty();
        assertThat(prepended(image("a.jpg", 200, 150, sizes))).contains(medium(UPLOADS + "a.jpg", 200, 150));
    }

    @Test
    void aFileWithoutPreviewLinksItsTitle() {
        assertThat(prepended(file(4, "application/pdf", "http://localhost/wp-content/uploads/2026/11/i07.pdf", "i07")))
                .isEqualTo("<p class=\"attachment\"><a href='http://localhost/wp-content/uploads/2026/11/i07.pdf'>i07"
                        + "</a></p>\n");
        assertThat(AttachmentMarkup.mediumImage(
                        file(4, "application/pdf", "http://localhost/wp-content/uploads/2026/11/i07.pdf", "i07"),
                        300,
                        300))
                .isEmpty();
        // The raw title, then the escaped file name without its extension.
        assertThat(prepended(file(4, "application/zip", UPLOADS + "x.zip", "<b>R&D</b>")))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "x.zip'><b>R&D</b></a></p>\n");
        assertThat(prepended(file(4, "application/zip", UPLOADS + "R&D.v2.zip", "")))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "R&#038;D.v2.zip'>R&amp;D.v2</a></p>\n");
        assertThat(prepended(new Attachment(
                        4,
                        "application/pdf",
                        null,
                        "i07",
                        "i07.pdf",
                        "",
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        List.of())))
                .isEqualTo("<p class=\"attachment\">Missing Attachment</p>\n");
    }

    @Test
    void audioAndVideoGetTheirPlayers() {
        String mp3 = UPLOADS + "song.mp3";
        assertThat(prepended(file(9, "audio/mpeg", mp3, "song")))
                .isEqualTo("<audio class=\"wp-audio-shortcode\" id=\"audio-9-1\" preload=\"none\""
                        + " style=\"width: 100%;\" controls=\"controls\"><source type=\"audio/mpeg\" src=\"" + mp3
                        + "?_=1\" /><a href=\"" + mp3 + "\">" + mp3 + "</a></audio>\n");
        assertThat(prepended(file(9, "audio/mpeg", UPLOADS + "song.m4a", "song")))
                .contains("<source type=\"audio/mpeg\" src=\"" + UPLOADS + "song.m4a?_=1\" />");
        assertThat(prepended(file(9, "audio/aac", UPLOADS + "song.aac", "song")))
                .isEqualTo("<a class=\"wp-embedded-audio\" href=\"" + UPLOADS + "song.aac\">" + UPLOADS
                        + "song.aac</a>\n");
        String mp4 = UPLOADS + "clip.mp4";
        Attachment video = new Attachment(
                5, "video/mp4", mp4, "clip", "clip.mp4", "", OptionalInt.of(64), OptionalInt.of(48), List.of());
        assertThat(prepended(video))
                .isEqualTo("<div style=\"width: 64px;\" class=\"wp-video\"><video class=\"wp-video-shortcode\""
                        + " id=\"video-5-1\" width=\"64\" height=\"48\" preload=\"metadata\" controls=\"controls\">"
                        + "<source type=\"video/mp4\" src=\"" + mp4 + "?_=1\" /><a href=\"" + mp4 + "\">" + mp4
                        + "</a></video></div>\n");
        assertThat(prepended(file(6, "video/webm", UPLOADS + "clip.webm", "clip")))
                .startsWith("<div style=\"width: 640px;\" class=\"wp-video\"><video class=\"wp-video-shortcode\""
                        + " id=\"video-6-1\" width=\"640\" height=\"360\" preload=\"metadata\" controls=\"controls\">"
                        + "<source type=\"video/webm\" src=\"" + UPLOADS + "clip.webm?_=1\" />");
        assertThat(prepended(file(6, "video/quicktime", UPLOADS + "clip.mov", "clip")))
                .isEqualTo("<a class=\"wp-embedded-video\" href=\"" + UPLOADS + "clip.mov\">" + UPLOADS
                        + "clip.mov</a>\n");
    }

    @Test
    void aVideoWithACoverShowsItsPosterBeforePreload() {
        String mp4 = UPLOADS + "clip.mp4";
        Attachment video = new Attachment(
                5,
                "video/mp4",
                mp4,
                "clip",
                "clip.mp4",
                "",
                OptionalInt.of(64),
                OptionalInt.of(48),
                List.of(),
                UPLOADS + "cover a&b.jpg");
        assertThat(prepended(video))
                .isEqualTo("<div style=\"width: 64px;\" class=\"wp-video\"><video class=\"wp-video-shortcode\""
                        + " id=\"video-5-1\" width=\"64\" height=\"48\" poster=\"" + UPLOADS + "cover%20a&#038;b.jpg\""
                        + " preload=\"metadata\" controls=\"controls\"><source type=\"video/mp4\" src=\"" + mp4
                        + "?_=1\" /><a href=\"" + mp4 + "\">" + mp4 + "</a></video></div>\n");
    }

    @Test
    void theCountersGrowAcrossDescriptionsAndGeneratedCaptions() {
        Counters counters = new Counters();
        for (long id = 5; id >= 3; id--) {
            String url = UPLOADS + "s" + id + ".mp3";
            Attachment audio = file(id, "audio/mpeg", url, "s");
            String description = AttachmentMarkup.prepended(audio, 300, 300, counters);
            long n = 2 * (5 - id) + 1;
            assertThat(description).contains("id=\"audio-" + id + "-" + n + "\"", url + "?_=" + n + "\"");
            assertThat(AttachmentMarkup.generatedCaption(AttachmentMarkup.prepended(audio, 300, 300, counters), ""))
                    .isEqualTo("<p>" + url + "</p>\n");
        }
        assertThat(AttachmentMarkup.prepended(file(5, "video/mp4", UPLOADS + "v.mp4", "v"), 300, 300, counters))
                .contains("id=\"video-5-1\"");
    }

    @Test
    void captionsAreGeneratedFromTheMarkup() {
        String image = prepended(image("a.jpg", 64, 48, List.of()));
        assertThat(AttachmentMarkup.generatedCaption(image, "")).isEmpty();
        assertThat(AttachmentMarkup.generatedCaption(
                        prepended(file(4, "application/pdf", UPLOADS + "i07.pdf", "i07")), ""))
                .isEqualTo("<p>i07</p>\n");
        String mp3 = UPLOADS + "song.mp3";
        assertThat(AttachmentMarkup.generatedCaption(prepended(file(9, "audio/mpeg", mp3, "song")), ""))
                .isEqualTo("<p>" + mp3 + "</p>\n");
        assertThat(AttachmentMarkup.generatedCaption(image, "<p>Hello world</p>\n"))
                .isEqualTo("<p>Hello world</p>\n");
        assertThat(AttachmentMarkup.description(image, "<p>Hello world</p>\n"))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "a.jpg'>" + medium(UPLOADS + "a.jpg", 64, 48)
                        + "</a></p>\n<p>Hello world</p>\n");
    }

    @Test
    void aGeneratedCaptionKeepsFiftyFiveWords() {
        StringBuilder words = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            words.append("w").append(i).append(i % 2 == 0 ? "\n\t " : " ");
        }
        String caption = AttachmentMarkup.generatedCaption("", "<p>" + words + "</p>");
        assertThat(caption).startsWith("<p>w1 w2 w3 ").endsWith(" w54 w55 [&hellip;]</p>\n");
        assertThat(AttachmentMarkup.generatedCaption("", "  one \n two  ")).isEqualTo("<p>one two</p>\n");
    }

    @Test
    void urlsAreEscapedForHtml() {
        String photo = new String(new int[] {0x0424, 0x043e, 0x0442, 0x043e}, 0, 4);
        assertThat(prepended(file(4, "application/pdf", UPLOADS + "a&b c.pdf", "a")))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + "a&#038;b%20c.pdf'>a</a></p>\n");
        assertThat(prepended(file(4, "application/pdf", UPLOADS + photo + "-1.pdf", "a")))
                .isEqualTo("<p class=\"attachment\"><a href='" + UPLOADS + photo + "-1.pdf'>a</a></p>\n");
        String odd = UPLOADS + "a b&c.mp3";
        assertThat(prepended(file(9, "audio/mpeg", odd, "song")))
                .contains("src=\"" + UPLOADS + "a%20b&#038;c.mp3?_=1\" /><a href=\"" + UPLOADS + "a%20b&#038;c.mp3\">"
                        + UPLOADS + "a%20b&#038;c.mp3</a>");
        assertThat(prepended(file(9, "audio/aac", UPLOADS + "a b&c'd.aac", "song")))
                .isEqualTo("<a class=\"wp-embedded-audio\" href=\"" + UPLOADS + "a%20b&#038;c&#039;d.aac\">" + UPLOADS
                        + "a b&amp;c&#039;d.aac</a>\n");
        // In a srcset only the spaces are encoded.
        List<Size> sizes =
                List.of(size("medium", "a b-300x225.jpg", 300, 225), size("medium_large", "a b-768x576.jpg", 768, 576));
        assertThat(AttachmentMarkup.mediumImage(image("a b.jpg", 1600, 1200, sizes), 300, 300)
                        .orElseThrow()
                        .srcset())
                .isEqualTo(UPLOADS + "a%20b-300x225.jpg 300w, " + UPLOADS + "a%20b-768x576.jpg 768w, " + UPLOADS
                        + "a%20b.jpg 1600w");
    }

    @Test
    void urlEscapingDropsUnsafeCharactersAndProtocols() {
        assertThat(AttachmentMarkup.escUrl("http://wp.test/u/a'b\"c<d>.jpg"))
                .isEqualTo("http://wp.test/u/a&#039;bcd.jpg");
        assertThat(AttachmentMarkup.escUrl("http://wp.test/u/[x]{y}|z^`\\.jpg"))
                .isEqualTo("http://wp.test/u/%5Bx%5Dy|z.jpg");
        assertThat(AttachmentMarkup.escUrl("http://[::1]/a[b].jpg")).isEqualTo("http://[::1]/a%5Bb%5D.jpg");
        assertThat(AttachmentMarkup.escUrl("http://x/a%0%0dd%0A.jpg")).isEqualTo("http://x/a.jpg");
        assertThat(AttachmentMarkup.escUrl("http://wp.test/u/a&amp;b&&c&#038;d&bogus;.jpg"))
                .isEqualTo("http://wp.test/u/a&#038;b&#038;&#038;c&#038;d&#038;bogus;.jpg");
        assertThat(AttachmentMarkup.escUrl("  http://wp.test/u/t.jpg ")).isEqualTo("http://wp.test/u/t.jpg%20");
        assertThat(AttachmentMarkup.escUrl("http://wp.test/u/&lt;//x.jpg"))
                .isEqualTo("http://wp.test/u/&#038;lt://x.jpg");
        assertThat(AttachmentMarkup.escUrl("javascript:alert(1)")).isEmpty();
        assertThat(AttachmentMarkup.escUrl("wp.test/a.jpg")).isEqualTo("http://wp.test/a.jpg");
        assertThat(AttachmentMarkup.escUrl("/wp-content/a.jpg")).isEqualTo("/wp-content/a.jpg");
        assertThat(AttachmentMarkup.escUrl(" ")).isEmpty();
        String eAcute = Character.toString(0x00e9);
        assertThat(AttachmentMarkup.escAttr("x\"y'z<>&amp;&bogus;" + eAcute))
                .isEqualTo("x&quot;y&#039;z&lt;&gt;&amp;&amp;bogus;" + eAcute);
    }

    @Test
    void signedSizeUrlsAreUsedAsTheStorageGaveThem() {
        // a storage whose URLs carry a signature for one file: the medium image and the srcset use each size's own URL
        String signed = "https://bucket.s3.test/p/2026/11/";
        List<Size> sizes = List.of(
                new Size("medium", "a-300x225.jpg", 300, 225, signed + "a-300x225.jpg?X-Amz-Signature=m"),
                new Size("medium_large", "a-768x576.jpg", 768, 576, signed + "a-768x576.jpg?X-Amz-Signature=ml"));
        Attachment a = new Attachment(
                7,
                "image/jpeg",
                signed + "a.jpg?X-Amz-Signature=main",
                "a",
                "a.jpg",
                "",
                OptionalInt.of(1600),
                OptionalInt.of(1200),
                sizes);
        AttachmentMarkup.Image shown = AttachmentMarkup.mediumImage(a, 300, 300).orElseThrow();
        assertThat(shown.url()).isEqualTo(signed + "a-300x225.jpg?X-Amz-Signature=m");
        assertThat(shown.srcset())
                .isEqualTo(signed + "a-300x225.jpg?X-Amz-Signature=m 300w, " + signed
                        + "a-768x576.jpg?X-Amz-Signature=ml 768w, " + signed + "a.jpg?X-Amz-Signature=main 1600w");
    }
}
