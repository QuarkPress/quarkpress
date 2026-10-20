package org.quarkpress.posts.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.lib.wpblocks.WpBlocks;
import org.quarkpress.lib.wpkses.Kses;
import org.quarkpress.lib.wpkses.KsesContext;
import org.quarkpress.posts.ContentSanitizer;
import org.quarkpress.posts.SaveFilters;

/**
 * WordPress's save filters: titles trimmed, then kses with the tag set of the {@code title_save_pre} context; content
 * stripped of block custom CSS, its Windows-1252 entities converted, then kses with the {@code post} tag set; excerpts
 * converted and filtered like content. Kses and the CSS stripping apply only when the caller's filters say so.
 */
@DefaultBean
@ApplicationScoped
public class KsesContentSanitizer implements ContentSanitizer {

    private static final KsesContext TITLE = KsesContext.named("title_save_pre");

    private final Kses kses = Kses.wordPress();

    @Override
    public String title(String title, SaveFilters filters) {
        String trimmed = trim(title);
        return filters.kses() ? kses.sanitize(trimmed, TITLE) : trimmed;
    }

    @Override
    public String content(String content, SaveFilters filters) {
        String text = filters.stripCustomCss() ? WpBlocks.stripCustomCss(content) : content;
        text = PostTextRules.convertInvalidEntities(text);
        return filters.kses() ? kses.post(text) : text;
    }

    @Override
    public String excerpt(String excerpt, SaveFilters filters) {
        String text = PostTextRules.convertInvalidEntities(excerpt);
        return filters.kses() ? kses.post(text) : text;
    }

    /**
     * PHP's trim on the slashed text WordPress filters: a NUL there is a backslash and a zero, so it is not
     * trimmed.
     */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isTrimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B;
    }
}
