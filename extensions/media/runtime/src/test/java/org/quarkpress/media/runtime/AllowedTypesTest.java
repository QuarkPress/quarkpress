package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quarkpress.media.runtime.DefaultMimeTypes.PATTERNS;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AllowedTypesTest {

    private final AllowedTypes author = AllowedTypes.of(PATTERNS, false, List.of(), Optional.empty());
    private final AllowedTypes admin = AllowedTypes.of(PATTERNS, true, List.of(), Optional.empty());

    private static List<String> keys(AllowedTypes types) {
        return types.patterns().stream().map(AllowedTypes.Pattern::key).toList();
    }

    @Test
    void theDefaultMapHoldsWordPresssNinetyEightPatternsInOrder() {
        assertThat(DefaultMimeTypes.PATTERNS).hasSize(98);
        assertThat(DefaultMimeTypes.PATTERNS.get(0)).isEqualTo(Map.entry("jpg|jpeg|jpe", "image/jpeg"));
        assertThat(DefaultMimeTypes.PATTERNS.get(11)).isEqualTo(Map.entry("heifs", "image/heif-sequence"));
        assertThat(DefaultMimeTypes.PATTERNS.get(45)).isEqualTo(Map.entry("mka", "audio/x-matroska"));
        assertThat(DefaultMimeTypes.PATTERNS.get(46)).isEqualTo(Map.entry("rtf", "application/rtf"));
        assertThat(DefaultMimeTypes.PATTERNS.get(97)).isEqualTo(Map.entry("pages", "application/vnd.apple.pages"));
        assertThat(DefaultMimeTypes.PATTERNS)
                .contains(
                        Map.entry("avi", "video/avi"),
                        Map.entry("wav|x-wav", "audio/wav"),
                        Map.entry("rar", "application/rar"),
                        Map.entry("gz|gzip", "application/x-gzip"),
                        Map.entry("ico", "image/x-icon"),
                        Map.entry("class", "application/java"),
                        Map.entry("tsv", "text/tab-separated-values"),
                        Map.entry("psd", "application/octet-stream"));
        assertThat(DefaultMimeTypes.defaultExtension("image/heif-sequence")).isEqualTo("heifs");
        assertThat(DefaultMimeTypes.defaultExtension("image/jpeg")).isEqualTo("jpg");
        assertThat(DefaultMimeTypes.defaultExtension("image/heic")).isEqualTo("heic");
        assertThat(DefaultMimeTypes.defaultExtension("application/octet-stream"))
                .isEqualTo("psd");
    }

    @Test
    void theDefaultMapIsUnmodifiable() {
        assertThatThrownBy(() -> DefaultMimeTypes.PATTERNS.add(Map.entry("svg", "image/svg+xml")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fullExtensionsAreEveryExtensionOfTheMapInLowerCase() {
        assertThat(DefaultMimeTypes.fullExtensions())
                .hasSize(130)
                .contains("jpe", "x-wav", "swf", "exe", "onepkg", "pages")
                .doesNotContain("svg", "jpg|jpeg|jpe");
    }

    @Test
    void anAuthorGetsNinetyFourPatternsAndAnAdministratorNinetySix() {
        assertThat(author.patterns())
                .hasSize(94)
                .noneMatch(p -> Set.of("swf", "exe", "htm|html", "js").contains(p.key()));
        assertThat(admin.patterns()).hasSize(96).anyMatch(p -> p.key().equals("htm|html"));
        assertThat(admin.patterns()).noneMatch(p -> Set.of("swf", "exe").contains(p.key()));
        assertThat(admin.patterns().get(0))
                .isEqualTo(new AllowedTypes.Pattern("jpg|jpeg|jpe", List.of("jpg", "jpeg", "jpe"), "image/jpeg"));
    }

    @Test
    void lookupTakesTheFirstMatchingPatternAndKeepsTheWrittenExtension() {
        assertThat(author.lookup("photo.JPG")).contains(new AllowedTypes.Match("JPG", "image/jpeg"));
        assertThat(author.lookup("x.php.jpg")).contains(new AllowedTypes.Match("jpg", "image/jpeg"));
        assertThat(author.lookup("x.jpg.php")).isEmpty();
        assertThat(author.lookup("photo")).isEmpty();
        assertThat(author.lookup("photo.")).isEmpty();
        assertThat(author.lookup("song.m4a")).contains(new AllowedTypes.Match("m4a", "audio/mpeg"));
        assertThat(author.lookup("page.html")).isEmpty();
        assertThat(admin.lookup("page.html")).contains(new AllowedTypes.Match("html", "text/html"));
        assertThat(admin.lookup("movie.swf")).isEmpty();
        assertThat(author.lookup("sound.x-wav")).contains(new AllowedTypes.Match("x-wav", "audio/wav"));
    }

    @Test
    void lookupTakesTheFirstPatternWhenTwoHoldTheExtension() {
        AllowedTypes types = AllowedTypes.of(
                List.of(Map.entry("png", "image/png"), Map.entry("png|apng", "image/apng")),
                false,
                List.of(),
                Optional.empty());
        assertThat(types.lookup("a.png")).contains(new AllowedTypes.Match("png", "image/png"));
        assertThat(types.lookup("a.apng")).contains(new AllowedTypes.Match("apng", "image/apng"));
    }

    @Test
    void theAllowedExtensionsAndTypesFollowTheMap() {
        assertThat(author.extensions()).contains("jpg", "x-wav").doesNotContain("html", "js", "swf", "exe");
        assertThat(author.mimeTypes()).startsWith("image/jpeg", "image/gif").doesNotHaveDuplicates();
        assertThat(author.mimeTypes()).containsOnlyOnce("application/octet-stream");
        assertThat(author.allowsType("image/heic")).isTrue();
        assertThat(author.allowsType("text/html")).isFalse();
        assertThat(admin.allowsType("text/html")).isTrue();
    }

    @Test
    void theNetworkListKeepsPatternsContainingAListedExtension() {
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("jpg png pdf"))))
                .containsExactly("jpg|jpeg|jpe", "png", "pdf");
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("mp"))))
                .containsExactly(
                        "bmp", "mpeg|mpg|mpe", "mp4|m4v", "mp3|m4a|m4b", "mpp", "onetoc|onetoc2|onetmp|onepkg");
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("heic heif"))))
                .containsExactly("heic", "heics", "heif", "heifs");
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("c"))))
                .containsExactly(
                        "ico",
                        "heic",
                        "heics",
                        "txt|asc|c|cc|h|srt",
                        "csv",
                        "ics",
                        "css",
                        "aac",
                        "flac",
                        "class",
                        "xcf",
                        "doc",
                        "docx",
                        "docm",
                        "onetoc|onetoc2|onetmp|onepkg",
                        "odc");
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("jpg  png"))))
                .hasSize(2);
        assertThat(keys(AllowedTypes.of(PATTERNS, false, List.of(), Optional.of("htm html js jpg"))))
                .containsExactly("jpg|jpeg|jpe");
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("swf exe"))))
                .isEmpty();
    }

    @Test
    void theNetworkListOrdersByListEntryThenMapOrder() {
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("pdf jpg wm"))))
                .containsExactly("pdf", "jpg|jpeg|jpe", "wmv", "wmx", "wm", "wma");
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("png png  jpg "))))
                .containsExactly("png", "jpg|jpeg|jpe");
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(), Optional.of("   "))))
                .isEmpty();
    }

    @Test
    void deniedExtensionsLeaveTheirPatternsOrRemoveThem() {
        AllowedTypes denied = AllowedTypes.of(PATTERNS, true, List.of("pdf", "jpe"), Optional.empty());
        assertThat(keys(denied))
                .doesNotContain("pdf")
                .contains("jpg|jpeg")
                .doesNotContain("jpg|jpeg|jpe")
                .hasSize(95);
        assertThat(denied.lookup("a.jpe")).isEmpty();
        assertThat(denied.lookup("a.jpeg")).contains(new AllowedTypes.Match("jpeg", "image/jpeg"));
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of("JPE"), Optional.empty())))
                .contains("jpg|jpeg");
    }

    @Test
    void configuredTypesFollowTheMapOrReplaceAPatternsTypeInPlace() {
        Map<String, String> configured = new java.util.LinkedHashMap<>();
        configured.put("svg", "image/svg+xml");
        configured.put("png", "image/x-png");
        configured.put("md", "text/markdown");
        List<Map.Entry<String, String>> merged = DefaultMimeTypesBean.merge(PATTERNS, configured);
        assertThat(merged).hasSize(100);
        assertThat(merged.get(2)).isEqualTo(Map.entry("png", "image/x-png"));
        assertThat(merged.subList(98, 100))
                .containsExactly(Map.entry("svg", "image/svg+xml"), Map.entry("md", "text/markdown"));
        assertThat(DefaultMimeTypesBean.merge(PATTERNS, Map.of())).isEqualTo(PATTERNS);
    }

    @Test
    void theTypeListFileIsExactlyWordPresssList() throws Exception {
        byte[] bytes;
        try (InputStream in = DefaultMimeTypes.class.getClassLoader().getResourceAsStream(DefaultMimeTypes.RESOURCE)) {
            bytes = in.readAllBytes();
        }
        // The 98 lines checked one by one against WordPress's table; any change to the file must be checked again.
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("e31577e861de45d10717a9a94afc8b205455e28ca5549fb273990272738b0d26");
        assertThat(bytes).hasSize(3205);
    }

    @Test
    void deniedExtensionsAreTrimmedAndLoseOneLeadingDot() {
        AllowedTypes denied =
                AllowedTypes.of(PATTERNS, true, List.of(" pdf ", ".JPE", "..png", " ", ""), Optional.empty());
        assertThat(keys(denied))
                .doesNotContain("pdf", "jpg|jpeg|jpe")
                .contains("jpg|jpeg", "png")
                .hasSize(95);
        assertThat(keys(AllowedTypes.of(PATTERNS, true, List.of(" . "), Optional.empty())))
                .hasSize(96);
    }

    @Test
    void aConfiguredPatternWithADotIsRefused() {
        Map<String, String> configured = new java.util.LinkedHashMap<>();
        configured.put("svg", "image/svg+xml");
        configured.put("tar.gz", "application/gzip");
        configured.put("tgz|tar.bz2", "application/x-tar");
        assertThat(DefaultMimeTypesBean.refusal(configured))
                .contains(
                        "quarkpress.media.allowed-types.\"tar.gz\" cannot be used:",
                        "quarkpress.media.allowed-types.\"tgz|tar.bz2\" cannot be used:",
                        "the part of the file name after its last dot")
                .doesNotContain("\"svg\"");
        assertThat(DefaultMimeTypesBean.refusal(Map.of("svg", "image/svg+xml", "tgz|tbz", "application/x-tar")))
                .isEmpty();
    }

    @Test
    void installerExtensionsAreEveryExtensionButSwfAndExe() {
        assertThat(DefaultMimeTypes.installerExtensions())
                .hasSize(128)
                .startsWith("jpg", "jpeg", "jpe", "gif", "png", "bmp")
                .endsWith("wp", "wpd", "key", "numbers", "pages")
                .contains("htm", "html", "js")
                .doesNotContain("swf", "exe")
                .doesNotHaveDuplicates();
    }
}
