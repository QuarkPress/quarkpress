package org.quarkpress.restwp.runtime.wpv2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkpress.lib.wpkses.Kses;

/**
 * The markup WordPress puts in front of an attachment's rendered description, and the caption it generates from
 * that markup when the stored caption is empty: a linked medium-size image for images (with a srcset when the stored
 * sizes allow one), a link carrying the title for other files, and the audio or video player for media files.
 *
 * <p>It takes plain values, so it loads without the media module; the media controller maps its snapshots to
 * {@link Attachment}.
 */
final class AttachmentMarkup {

    /** Sizes wider than this are left out of a srcset, unless they are the displayed file. */
    static final int MAX_SRCSET_WIDTH = 2048;

    /** The number of words a generated caption keeps. */
    static final int CAPTION_WORDS = 55;

    /** Shown in place of the link when the attachment has no file URL. */
    static final String MISSING = "Missing Attachment";

    private static final int DEFAULT_VIDEO_WIDTH = 640;
    private static final int DEFAULT_VIDEO_HEIGHT = 360;

    /** Extensions that get the audio player, with the type its source announces. */
    private static final Map<String, String> AUDIO_TYPES = Map.of(
            "mp3", "audio/mpeg",
            "m4a", "audio/mpeg",
            "ogg", "audio/ogg",
            "flac", "audio/flac",
            "wav", "audio/wav");

    /** Extensions that get the video player, with the type its source announces. */
    private static final Map<String, String> VIDEO_TYPES = Map.of(
            "mp4", "video/mp4",
            "m4v", "video/mp4",
            "webm", "video/webm",
            "ogv", "video/ogg",
            "flv", "video/x-flv");

    /** The URL schemes a link may use; any other scheme empties the URL. */
    private static final List<String> PROTOCOLS = List.of(
            "http", "https", "ftp", "ftps", "mailto", "news", "irc", "irc6", "ircs", "gopher", "nntp", "feed", "telnet",
            "mms", "rtsp", "sms", "svn", "tel", "fax", "xmpp", "webcal", "urn");

    /** The ASCII characters a URL keeps; the others are dropped (non-ASCII characters are kept). */
    private static final String URL_PUNCTUATION = "-~+_.?#=!&;,/:%@$|*'()[]";

    private AttachmentMarkup() {}

    /**
     * An attachment as the markup needs it.
     *
     * @param fileUrl the file URL, {@code null} or empty when the attachment has none
     * @param title the raw title
     * @param fileName the stored file's name, used when the title is empty
     * @param alt the stored alternative text, raw
     * @param width the stored width, empty when the metadata has none
     * @param height the stored height, empty when the metadata has none
     * @param sizes the stored sizes in stored order; their files live in the main file's folder
     * @param poster the file URL of a video's cover image, empty when it has none
     */
    record Attachment(
            long id,
            String mimeType,
            String fileUrl,
            String title,
            String fileName,
            String alt,
            OptionalInt width,
            OptionalInt height,
            List<Size> sizes,
            String poster) {

        Attachment {
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(alt, "alt");
            Objects.requireNonNull(width, "width");
            Objects.requireNonNull(height, "height");
            Objects.requireNonNull(poster, "poster");
            sizes = List.copyOf(sizes);
        }

        /** An attachment without a cover image. */
        Attachment(
                long id,
                String mimeType,
                String fileUrl,
                String title,
                String fileName,
                String alt,
                OptionalInt width,
                OptionalInt height,
                List<Size> sizes) {
            this(id, mimeType, fileUrl, title, fileName, alt, width, height, sizes, "");
        }

        boolean hasUrl() {
            return fileUrl != null && !fileUrl.isEmpty();
        }
    }

    /** A stored size: its name, its file name (no folder) and its dimensions. */
    record Size(String name, String file, int width, int height) {

        Size {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * The image shown for an attachment at the medium size.
     *
     * @param url the displayed file's URL, unescaped
     * @param alt the alternative text, tags stripped and trimmed but not escaped
     * @param srcset the srcset, empty when there is none
     * @param sizes the sizes attribute, empty when there is no srcset
     */
    record Image(String url, int width, int height, String alt, String srcset, String sizes) {}

    /**
     * The per-request numbers of the audio and video players. Every player built during one request takes the next
     * number of its kind, the hidden ones built while generating a caption included, so one instance is shared by
     * every attachment of a response.
     */
    static final class Counters {

        private int audio;
        private int video;

        int nextAudio() {
            return ++audio;
        }

        int nextVideo() {
            return ++video;
        }
    }

    /**
     * The image an attachment shows at the medium size: the {@code medium} size when it is stored, otherwise the main
     * file (or, for a file with a preview, the {@code full} preview) fitted into the medium box without enlarging it.
     * Empty for a file that is not an image and has no preview, or that has no URL.
     */
    static Optional<Image> mediumImage(Attachment a, int mediumWidth, int mediumHeight) {
        boolean image = startsWithIgnoreCase(a.mimeType(), "image/");
        Optional<Size> medium = size(a, "medium");
        Optional<Size> full = size(a, "full");
        if (!a.hasUrl() || (!image && full.isEmpty())) {
            return Optional.empty();
        }
        String folder = folder(a.fileUrl());
        String url;
        String displayed;
        int width;
        int height;
        if (medium.isPresent()) {
            displayed = medium.get().file();
            url = folder + displayed;
            width = medium.get().width();
            height = medium.get().height();
        } else if (image) {
            displayed = a.fileUrl().substring(folder.length());
            url = a.fileUrl();
            int[] fitted = constrain(a.width().orElse(0), a.height().orElse(0), mediumWidth, mediumHeight);
            width = fitted[0];
            height = fitted[1];
        } else {
            displayed = full.get().file();
            url = folder + displayed;
            int[] fitted = constrain(full.get().width(), full.get().height(), mediumWidth, mediumHeight);
            width = fitted[0];
            height = fitted[1];
        }
        String alt = phpTrim(Kses.stripTags(a.alt()));
        String srcset = srcset(a, folder, displayed, width, height);
        String sizes = srcset.isEmpty() ? "" : "auto, (max-width: " + width + "px) 100vw, " + width + "px";
        return Optional.of(new Image(url, width, height, alt, srcset, sizes));
    }

    /**
     * The markup put in front of the description, followed by a line feed: the audio or video player for media
     * files, otherwise a paragraph holding a link to the file around the medium image, the raw title or the file name
     * without its extension (or the missing-attachment text when there is no file URL).
     */
    static String prepended(Attachment a, int mediumWidth, int mediumHeight, Counters counters) {
        if (a.hasUrl() && startsWithIgnoreCase(a.mimeType(), "audio/")) {
            return audio(a, counters) + "\n";
        }
        if (a.hasUrl() && startsWithIgnoreCase(a.mimeType(), "video/")) {
            return video(a, counters) + "\n";
        }
        if (!a.hasUrl()) {
            return "<p class=\"attachment\">" + MISSING + "</p>\n";
        }
        String text = mediumImage(a, mediumWidth, mediumHeight)
                .map(AttachmentMarkup::imageTag)
                .orElse(a.title());
        if (text.isEmpty()) {
            text = escAttr(stem(a.fileName()));
        }
        return "<p class=\"attachment\"><a href='" + escUrl(a.fileUrl()) + "'>" + text + "</a></p>\n";
    }

    /** The rendered description: the prepended markup followed by the filtered stored description. */
    static String description(String prepended, String filteredContent) {
        return prepended + filteredContent;
    }

    /**
     * The caption generated when the stored one is empty: the text of the prepended markup and the content without
     * tags, cut to {@value #CAPTION_WORDS} words (with an ellipsis when cut), in one paragraph; empty when no text is
     * left.
     */
    static String generatedCaption(String prepended, String content) {
        String text = (prepended + content).replace("]]>", "]]&gt;");
        String trimmed = PostFields.trimWords(Kses.stripAllTags(text, false), CAPTION_WORDS);
        return trimmed.isEmpty() ? "" : "<p>" + trimmed + "</p>\n";
    }

    private static String imageTag(Image image) {
        StringBuilder tag = new StringBuilder("<img loading=\"lazy\" decoding=\"async\" width=\"")
                .append(image.width())
                .append("\" height=\"")
                .append(image.height())
                .append("\" src=\"")
                .append(escAttr(image.url()))
                .append("\" class=\"attachment-medium size-medium\" alt=\"")
                .append(escAttr(image.alt()))
                .append('"');
        if (!image.srcset().isEmpty()) {
            tag.append(" srcset=\"")
                    .append(escAttr(image.srcset()))
                    .append("\" sizes=\"")
                    .append(escAttr(image.sizes()))
                    .append('"');
        }
        return tag.append(" />").toString();
    }

    private static String audio(Attachment a, Counters counters) {
        int n = counters.nextAudio();
        String url = a.fileUrl();
        String type = AUDIO_TYPES.get(extension(url));
        if (type == null) {
            return "<a class=\"wp-embedded-audio\" href=\"" + escUrl(url) + "\">" + escAttr(url) + "</a>";
        }
        String escaped = escUrl(url);
        return "<audio class=\"wp-audio-shortcode\" id=\"audio-" + a.id() + "-" + n
                + "\" preload=\"none\" style=\"width: 100%;\" controls=\"controls\"><source type=\"" + type
                + "\" src=\"" + escUrl(url + "?_=" + n) + "\" /><a href=\"" + escaped + "\">" + escaped
                + "</a></audio>";
    }

    private static String video(Attachment a, Counters counters) {
        int n = counters.nextVideo();
        String url = a.fileUrl();
        String type = VIDEO_TYPES.get(extension(url));
        if (type == null) {
            return "<a class=\"wp-embedded-video\" href=\"" + escUrl(url) + "\">" + escAttr(url) + "</a>";
        }
        int width = a.width().orElse(0);
        int height = a.height().orElse(0);
        if (width <= 0 || height <= 0) {
            width = DEFAULT_VIDEO_WIDTH;
            height = DEFAULT_VIDEO_HEIGHT;
        }
        String escaped = escUrl(url);
        return "<div style=\"width: " + width
                + "px;\" class=\"wp-video\"><video class=\"wp-video-shortcode\" id=\"video-"
                + a.id() + "-" + n + "\" width=\"" + width + "\" height=\"" + height + "\""
                + (a.poster().isEmpty() ? "" : " poster=\"" + escUrl(a.poster()) + "\"")
                + " preload=\"metadata\" controls=\"controls\"><source type=\"" + type + "\" src=\""
                + escUrl(url + "?_=" + n) + "\" /><a href=\"" + escaped + "\">" + escaped + "</a></video></div>";
    }

    /**
     * The srcset of the displayed file: the stored sizes and the main file that have the displayed image's aspect
     * ratio (within one pixel) and are at most {@value #MAX_SRCSET_WIDTH} pixels wide, one per width (a later file
     * takes an earlier one's place, except the displayed file's), the displayed file first; empty without stored
     * sizes, when the displayed file is not a candidate (no stored dimensions) or with fewer than two candidates.
     * Spaces in the URLs are encoded.
     */
    private static String srcset(Attachment a, String folder, String displayed, int width, int height) {
        if (a.sizes().isEmpty()) {
            return "";
        }
        List<Size> candidates = new ArrayList<>(a.sizes());
        int mainWidth = a.width().orElse(0);
        int mainHeight = a.height().orElse(0);
        if (mainWidth > 0 && mainHeight > 0) {
            candidates.add(new Size("", a.fileUrl().substring(folder.length()), mainWidth, mainHeight));
        }
        Map<Integer, String> sources = new LinkedHashMap<>();
        Integer displayedWidth = null;
        for (Size candidate : candidates) {
            if (candidate.file().equals(displayed)) {
                displayedWidth = candidate.width();
                sources.put(displayedWidth, candidate.file());
                break;
            }
        }
        for (Size candidate : candidates) {
            if (candidate.file().equals(displayed)
                    || candidate.width() > MAX_SRCSET_WIDTH
                    || Objects.equals(displayedWidth, candidate.width())
                    || !sameRatio(width, height, candidate.width(), candidate.height())) {
                continue;
            }
            sources.put(candidate.width(), candidate.file());
        }
        if (displayedWidth == null || sources.size() < 2) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> source : sources.entrySet()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append((folder + source.getValue()).replace(" ", "%20"))
                    .append(' ')
                    .append(source.getKey())
                    .append('w');
        }
        return out.toString();
    }

    /** Whether the larger image, fitted to the smaller one's width, is within one pixel of it in both dimensions. */
    private static boolean sameRatio(int width1, int height1, int width2, int height2) {
        if (width1 <= 0 || height1 <= 0 || width2 <= 0 || height2 <= 0) {
            return false;
        }
        boolean firstLarger = width1 > width2;
        int[] fitted = firstLarger ? constrain(width1, height1, width2, 0) : constrain(width2, height2, width1, 0);
        int expectedWidth = firstLarger ? width2 : width1;
        int expectedHeight = firstLarger ? height2 : height1;
        return Math.abs(fitted[0] - expectedWidth) <= 1 && Math.abs(fitted[1] - expectedHeight) <= 1;
    }

    /**
     * WordPress's fit arithmetic: {@code w0}x{@code h0} fitted into {@code maxWidth}x{@code maxHeight} (0 = no limit)
     * without enlarging, at least one pixel each way, a result one pixel short of a limit it was scaled to bumped to
     * the limit.
     */
    static int[] constrain(int w0, int h0, int maxWidth, int maxHeight) {
        if (maxWidth == 0 && maxHeight == 0) {
            return new int[] {w0, h0};
        }
        double widthRatio = 1.0;
        double heightRatio = 1.0;
        boolean didWidth = false;
        boolean didHeight = false;
        if (maxWidth > 0 && w0 > maxWidth) {
            widthRatio = maxWidth / (double) w0;
            didWidth = true;
        }
        if (maxHeight > 0 && h0 > maxHeight) {
            heightRatio = maxHeight / (double) h0;
            didHeight = true;
        }
        double smaller = Math.min(widthRatio, heightRatio);
        double larger = Math.max(widthRatio, heightRatio);
        double ratio = round(w0 * larger) <= maxWidth && round(h0 * larger) <= maxHeight ? larger : smaller;
        long width = Math.max(1, round(w0 * ratio));
        long height = Math.max(1, round(h0 * ratio));
        if (didWidth && width == maxWidth - 1L) {
            width = maxWidth;
        }
        if (didHeight && height == maxHeight - 1L) {
            height = maxHeight;
        }
        return new int[] {(int) width, (int) height};
    }

    /** PHP's round to an integer: half away from zero on the value's shortest decimal form. */
    private static long round(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * WordPress's URL escaping for display: leading whitespace trimmed, spaces encoded, characters a URL cannot hold
     * dropped (non-ASCII kept), encoded line breaks removed, {@code ;//} read as {@code ://}, brackets outside the host
     * encoded, a URL without a scheme given {@code http://} unless it is relative, a disallowed scheme answered with
     * an empty text, character references normalized, then {@code &} written {@code &#038;} and {@code '} written
     * {@code &#039;}. Matches WordPress for absolute file URLs; unusual relative or scheme-less texts may differ.
     */
    static String escUrl(String url) {
        String text = phpLtrim(url);
        if (text.isEmpty()) {
            return "";
        }
        text = text.replace(" ", "%20");
        StringBuilder kept = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x80 || isAsciiAlphanumeric(c) || URL_PUNCTUATION.indexOf(c) >= 0) {
                kept.append(c);
            }
        }
        text = withoutEncodedBreaks(kept.toString()).replace(";//", "://");
        text = encodeBracketsAfterHost(text);
        if (text.isEmpty()) {
            return "";
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            char first = text.charAt(0);
            if (first != '/' && first != '#' && first != '?' && !phpScript(text)) {
                text = "http://" + text;
            }
        } else if (!allowedScheme(text, colon)) {
            return "";
        }
        return Kses.normalizeEntities(text).replace("&amp;", "&#038;").replace("'", "&#039;");
    }

    /**
     * WordPress's attribute (and HTML text) escaping: character references normalized, a bare {@code &} written
     * {@code &amp;}, then {@code < > " '} encoded.
     */
    static String escAttr(String text) {
        return Kses.normalizeEntities(text)
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#039;");
    }

    /** Removes {@code %0d} and {@code %0a} (any case) until none is left, as removing one may form another. */
    private static String withoutEncodedBreaks(String text) {
        String current = text;
        while (true) {
            StringBuilder out = new StringBuilder(current.length());
            boolean removed = false;
            for (int i = 0; i < current.length(); i++) {
                if (current.charAt(i) == '%'
                        && i + 2 < current.length()
                        && current.charAt(i + 1) == '0'
                        && "dDaA".indexOf(current.charAt(i + 2)) >= 0) {
                    i += 2;
                    removed = true;
                } else {
                    out.append(current.charAt(i));
                }
            }
            if (!removed) {
                return current;
            }
            current = out.toString();
        }
    }

    /** Encodes {@code [} and {@code ]} after the authority (where an IPv6 host may hold them). */
    private static String encodeBracketsAfterHost(String text) {
        if (text.indexOf('[') < 0 && text.indexOf(']') < 0) {
            return text;
        }
        int start = 0;
        int scheme = text.indexOf("//");
        if (scheme >= 0 && (scheme == 0 || text.lastIndexOf(':', scheme) == scheme - 1)) {
            start = scheme + 2;
            while (start < text.length() && "/?#".indexOf(text.charAt(start)) < 0) {
                start++;
            }
        }
        StringBuilder out = new StringBuilder(text.length() + 8).append(text, 0, start);
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                out.append("%5B");
            } else if (c == ']') {
                out.append("%5D");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Whether the text before the first colon is an allowed scheme; a colon after the start of a path, query or
     * fragment does not end a scheme.
     */
    private static boolean allowedScheme(String text, int colon) {
        String scheme = text.substring(0, colon);
        for (int i = 0; i < scheme.length(); i++) {
            if ("/?#".indexOf(scheme.charAt(i)) >= 0) {
                return true;
            }
        }
        return PROTOCOLS.contains(scheme.toLowerCase(Locale.ROOT));
    }

    /** Whether the text starts with a PHP script name ({@code name.php}), which keeps a URL without scheme relative. */
    private static boolean phpScript(String text) {
        int i = 0;
        while (i < text.length() && (isAsciiAlphanumeric(text.charAt(i)) || text.charAt(i) == '-')) {
            i++;
        }
        return i > 0 && text.regionMatches(true, i, ".php", 0, 4);
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static Optional<Size> size(Attachment a, String name) {
        return a.sizes().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** The URL up to and including its last slash. */
    private static String folder(String url) {
        return url.substring(0, url.lastIndexOf('/') + 1);
    }

    /** The lowercase extension at the very end of the URL, or empty. */
    private static String extension(String url) {
        int dot = url.lastIndexOf('.');
        if (dot < 0 || url.indexOf('/', dot) >= 0) {
            return "";
        }
        return url.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The file name without its folder and its last extension. */
    private static String stem(String fileName) {
        String name = fileName.substring(fileName.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean phpWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }

    private static String phpLtrim(String text) {
        int start = 0;
        while (start < text.length() && phpWhitespace(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    private static String phpTrim(String text) {
        int end = text.length();
        while (end > 0 && phpWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return phpLtrim(text.substring(0, end));
    }
}
