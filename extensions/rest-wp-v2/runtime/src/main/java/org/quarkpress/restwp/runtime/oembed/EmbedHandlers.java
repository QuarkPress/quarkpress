package org.quarkpress.restwp.runtime.oembed;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkpress.restwp.runtime.TextFields;

/**
 * WordPress's embed handlers, tried when no provider answered: a YouTube {@code /v/} or {@code /embed/} URL is
 * re-embedded as its watch URL (through the provider path, else as the bare URL), and an audio or video file URL
 * becomes the shortcode text the handlers answer with (the URL sanitized, not escaped for display).
 */
public final class EmbedHandlers {

    /** What a handler made of the URL. */
    public sealed interface Handled {}

    /** The shortcode text of the audio or video handler. */
    public record Shortcode(String html) implements Handled {}

    /** The watch URL a YouTube embed URL stands for, to be embedded through the provider path. */
    public record YouTube(String watchUrl) implements Handled {}

    private static final List<String> AUDIO = List.of(".mp3", ".ogg", ".flac", ".m4a", ".wav");

    private static final List<String> VIDEO = List.of(".mp4", ".m4v", ".webm", ".ogv", ".flv");

    private static final List<String> YOUTUBE = List.of(
            "http://youtube.com/", "https://youtube.com/", "http://www.youtube.com/", "https://www.youtube.com/");

    private EmbedHandlers() {}

    /**
     * The first handler matching {@code url}: YouTube, then audio, then video. The video shortcode carries
     * {@code width} and {@code height} only when both are more than zero.
     */
    public static Optional<Handled> match(String url, int width, OptionalInt height) {
        Optional<String> watch = youtube(url);
        if (watch.isPresent()) {
            return Optional.of(new YouTube(watch.get()));
        }
        int scheme = schemeLength(url);
        if (scheme == 0) {
            return Optional.empty();
        }
        if (endsWithOneOf(url, scheme, AUDIO)) {
            return Optional.of(new Shortcode("[audio src=\"" + TextFields.url(url) + "\" /]"));
        }
        if (endsWithOneOf(url, scheme, VIDEO)) {
            String dimensions = width > 0 && height.isPresent() && height.getAsInt() > 0
                    ? "width=\"" + width + "\" height=\"" + height.getAsInt() + "\" "
                    : "";
            return Optional.of(new Shortcode("[video " + dimensions + " src=\"" + TextFields.url(url) + "\" /]"));
        }
        return Optional.empty();
    }

    /** The watch URL of a {@code youtube.com/v/<id>} or {@code youtube.com/embed/<id>} URL (www. or not). */
    private static Optional<String> youtube(String url) {
        for (String prefix : YOUTUBE) {
            int at = OEmbedHtml.indexOfIgnoreCase(url, prefix, 0);
            if (at < 0) {
                continue;
            }
            int path = at + prefix.length();
            int id;
            if (OEmbedHtml.regionMatchesIgnoreCase(url, path, "v/")) {
                id = path + 2;
            } else if (OEmbedHtml.regionMatchesIgnoreCase(url, path, "embed/")) {
                id = path + 6;
            } else {
                continue;
            }
            int end = url.indexOf('/', id);
            String value = url.substring(id, end < 0 ? url.length() : end);
            if (!value.isEmpty()) {
                return Optional.of("https://youtube.com/watch?v=" + value);
            }
        }
        return Optional.empty();
    }

    /** The length of the URL's {@code http://} or {@code https://} start, 0 when it has neither. */
    private static int schemeLength(String url) {
        if (OEmbedHtml.regionMatchesIgnoreCase(url, 0, "https://")) {
            return 8;
        }
        return OEmbedHtml.regionMatchesIgnoreCase(url, 0, "http://") ? 7 : 0;
    }

    /** Whether the URL ends with one of the extensions, with at least one character between the scheme and the dot. */
    private static boolean endsWithOneOf(String url, int scheme, List<String> extensions) {
        for (String extension : extensions) {
            int at = url.length() - extension.length();
            if (at > scheme && OEmbedHtml.regionMatchesIgnoreCase(url, at, extension)) {
                return true;
            }
        }
        return false;
    }
}
