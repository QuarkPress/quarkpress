package org.quarkpress.posts.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * WordPress's search matching and rank as HQL over an entity with {@code title}, {@code excerpt} and
 * {@code content} columns: the patterns a term is matched by, the case-insensitive {@code like} conditions, and the
 * rank expression a search orders by. Shared by the post and the revision queries, so the rule lives once. Values
 * are never written into the HQL: each is bound through the placeholder function the caller gives.
 */
public final class SearchRank {

    private SearchRank() {}

    /** {@code %text%} with {@code !}, {@code %} and {@code _} escaped by {@code !}. */
    public static String like(String text) {
        return "%" + escaped(text) + "%";
    }

    /** {@code text} with {@code !}, {@code %} and {@code _} escaped by {@code !}: a pattern for the whole column. */
    public static String escaped(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                out.append('!');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The pattern a term is matched by: as a part of the column, or as the whole of it for an exact search. */
    public static String pattern(String term, boolean exact) {
        return exact ? escaped(term) : like(term);
    }

    /** {@code column} holds the pattern bound to {@code placeholder}, ignoring case. */
    public static String contains(String column, String placeholder) {
        return "lower(" + column + ") like lower(" + placeholder + ") escape '!'";
    }

    /**
     * {@code column} does not hold the pattern bound to {@code placeholder}, ignoring case (the columns are never
     * null).
     */
    public static String lacks(String column, String placeholder) {
        return "lower(" + column + ") not like lower(" + placeholder + ") escape '!'";
    }

    /**
     * WordPress's search rank, as an ascending expression over the columns of {@code alias} ({@code p} for
     * {@code p.title}, {@code p.excerpt}, {@code p.content}); none for a search without positive terms, for the
     * search {@code "0"} once line breaks are removed (which PHP counts as empty), or when no rank applies (seven or
     * more positive terms and a hyphen starting a word). With several raw tokens: the whole string in the title, every
     * positive term in the title (up to six terms), any of them (two to six terms), the whole string in the excerpt,
     * then in the content (the whole string only when no hyphen starts a word), then the rest. With one token: title
     * matches first. An exact search ranks by whole columns, as it matches. Every pattern is bound through
     * {@code param}, which returns the placeholder for a value.
     */
    public static Optional<String> expression(
            SearchTerms.Parsed parsed, boolean exact, String alias, Function<Object, String> param) {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(param, "param");
        String title = alias + ".title";
        List<String> positive = parsed.positive();
        if (positive.isEmpty() || PostRules.phpEmpty(parsed.cleaned())) {
            return Optional.empty();
        }
        if (parsed.rawCount() <= 1) {
            return Optional.of("case when " + contains(title, param.apply(pattern(positive.get(0), exact)))
                    + " then 0 else 1 end");
        }
        boolean sentenceRank = !parsed.sentenceRankSkipped();
        String whole = sentenceRank ? param.apply(pattern(parsed.cleaned(), exact)) : null;
        StringBuilder rank = new StringBuilder();
        if (sentenceRank) {
            rank.append(" when ").append(contains(title, whole)).append(" then 1");
        }
        if (positive.size() < 7) {
            List<String> titleTerms = new ArrayList<>();
            for (String term : positive) {
                titleTerms.add(contains(title, param.apply(pattern(term, exact))));
            }
            rank.append(" when ").append(String.join(" and ", titleTerms)).append(" then 2");
            if (positive.size() > 1) {
                rank.append(" when ").append(String.join(" or ", titleTerms)).append(" then 3");
            }
        }
        if (sentenceRank) {
            rank.append(" when ").append(contains(alias + ".excerpt", whole)).append(" then 4");
            rank.append(" when ").append(contains(alias + ".content", whole)).append(" then 5");
        }
        if (rank.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("case" + rank + " else 6 end");
    }
}
