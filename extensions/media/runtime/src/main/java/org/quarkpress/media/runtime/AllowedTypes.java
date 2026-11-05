package org.quarkpress.media.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The upload types one user may upload on one site, in lookup order: the site's type list without {@code swf} and
 * {@code exe}, without {@code htm|html} and {@code js} for a user who may not post unfiltered HTML, without the denied
 * extensions, and narrowed by the network's list of upload types when there is one.
 *
 * @param patterns the allowed patterns, in lookup order
 */
public record AllowedTypes(List<Pattern> patterns) {

    /** The patterns removed for everyone. */
    private static final Set<String> NEVER = Set.of("swf", "exe");

    /** The patterns removed for a user who may not post unfiltered HTML. */
    private static final Set<String> UNFILTERED_HTML_ONLY = Set.of("htm|html", "js");

    public AllowedTypes {
        patterns = List.copyOf(patterns);
    }

    /**
     * One allowed type.
     *
     * @param key the pattern as the list writes it, such as {@code jpg|jpeg|jpe}
     * @param extensions the pattern's extensions, in order
     * @param mimeType the type a file with one of those extensions is stored as
     */
    public record Pattern(String key, List<String> extensions, String mimeType) {

        public Pattern {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(mimeType, "mimeType");
            extensions = List.copyOf(extensions);
        }
    }

    /**
     * The type a file name maps to.
     *
     * @param extension the name's extension as the name writes it (its case kept)
     * @param mimeType the type of the pattern that holds it
     */
    public record Match(String extension, String mimeType) {}

    /**
     * The types allowed from {@code map}.
     *
     * @param map the site's type list, pattern to MIME type, in lookup order
     * @param unfilteredHtml whether the user may post unfiltered HTML (keeps {@code htm|html} and {@code js})
     * @param deniedExtensions extensions removed from their patterns (a pattern left without any is dropped), each
     *     trimmed and without one leading dot ({@code .pdf} denies {@code pdf}); blank entries are ignored
     * @param networkList the network's space-separated upload types: a pattern is kept when one of the list's
     *     entries occurs anywhere in it, so {@code mp} keeps {@code bmp} as well; the kept patterns are ordered by
     *     list entry, then by map order
     */
    public static AllowedTypes of(
            List<Map.Entry<String, String>> map,
            boolean unfilteredHtml,
            List<String> deniedExtensions,
            Optional<String> networkList) {
        Set<String> denied = new LinkedHashSet<>();
        for (String extension : deniedExtensions) {
            String bare = extension.strip();
            if (bare.startsWith(".")) {
                bare = bare.substring(1);
            }
            if (!bare.isEmpty()) {
                denied.add(bare.toLowerCase(Locale.ROOT));
            }
        }
        List<Pattern> kept = new ArrayList<>();
        for (Map.Entry<String, String> entry : map) {
            String key = entry.getKey();
            if (NEVER.contains(key) || (!unfilteredHtml && UNFILTERED_HTML_ONLY.contains(key))) {
                continue;
            }
            List<String> extensions = extensionsOf(key);
            if (!denied.isEmpty()) {
                List<String> left = new ArrayList<>();
                for (String extension : extensions) {
                    if (!denied.contains(extension.toLowerCase(Locale.ROOT))) {
                        left.add(extension);
                    }
                }
                if (left.isEmpty()) {
                    continue;
                }
                if (left.size() != extensions.size()) {
                    extensions = left;
                    key = String.join("|", left);
                }
            }
            kept.add(new Pattern(key, extensions, entry.getValue()));
        }
        return new AllowedTypes(networkList.map(list -> narrow(kept, list)).orElse(kept));
    }

    /** The patterns of {@code patterns} holding one of the entries of {@code list}, ordered by entry, then map. */
    private static List<Pattern> narrow(List<Pattern> patterns, String list) {
        boolean[] taken = new boolean[patterns.size()];
        List<Pattern> narrowed = new ArrayList<>();
        int at = 0;
        int length = list.length();
        while (at < length) {
            while (at < length && list.charAt(at) == ' ') {
                at++;
            }
            int end = at;
            while (end < length && list.charAt(end) != ' ') {
                end++;
            }
            if (end > at) {
                String entry = list.substring(at, end);
                for (int i = 0; i < patterns.size(); i++) {
                    if (!taken[i] && patterns.get(i).key().contains(entry)) {
                        taken[i] = true;
                        narrowed.add(patterns.get(i));
                    }
                }
            }
            at = end;
        }
        return narrowed;
    }

    /** The extensions of a {@code |}-separated pattern, in order, empty pieces left out. */
    static List<String> extensionsOf(String key) {
        List<String> extensions = new ArrayList<>();
        int start = 0;
        while (start <= key.length()) {
            int bar = key.indexOf('|', start);
            int end = bar < 0 ? key.length() : bar;
            if (end > start) {
                extensions.add(key.substring(start, end));
            }
            if (bar < 0) {
                break;
            }
            start = bar + 1;
        }
        return extensions;
    }

    /**
     * The type of {@code fileName}: the first pattern holding the name's last extension (the part after its last
     * dot), compared without regard to case; empty when the name has no extension or no pattern holds it.
     */
    public Optional<Match> lookup(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1);
        for (Pattern pattern : patterns) {
            for (String candidate : pattern.extensions()) {
                if (candidate.equalsIgnoreCase(extension)) {
                    return Optional.of(new Match(extension, pattern.mimeType()));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether one of the allowed patterns has type {@code mimeType}. */
    public boolean allowsType(String mimeType) {
        for (Pattern pattern : patterns) {
            if (pattern.mimeType().equals(mimeType)) {
                return true;
            }
        }
        return false;
    }

    /** Every allowed extension, in lower case, in lookup order. */
    public Set<String> extensions() {
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (Pattern pattern : patterns) {
            for (String extension : pattern.extensions()) {
                extensions.add(extension.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(extensions);
    }

    /** The allowed MIME types, each once, in lookup order. */
    public List<String> mimeTypes() {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (Pattern pattern : patterns) {
            types.add(pattern.mimeType());
        }
        return List.copyOf(types);
    }
}
