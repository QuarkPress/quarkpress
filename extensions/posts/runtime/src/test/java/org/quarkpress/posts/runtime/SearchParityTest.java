package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/** Compares the search tokenizer with WordPress 7.1.3's {@code WP_Query}. Active only with {@code -Pwp-parity}. */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class SearchParityTest {

    static final String[] WORDPRESS_FILES = {"wp-includes/class-wp-query.php"};

    static final Path DRIVER = Path.of("src/test/php/posts-search-driver.php");

    static final Path SKIPS = Path.of("src/test/resources/wp-parity-search-skip.txt");

    /** Half of the 7513 comparisons of the first green run, rounded down to a multiple of 50. */
    static final int MIN_COMPARISONS = 3_750;

    private static String id(int index, boolean sentence) {
        return (sentence ? "sentence-" : "terms-") + index;
    }

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun(
                "posts-search", ParitySkipList.load(SKIPS), Path.of("target/wp-parity-posts-search-failures.json"));
        List<String> searches = new ArrayList<>(List.of(
                "hello world",
                "\"hello world\" foo",
                "the cat",
                "a",
                "foo,bar+baz",
                "-draft post",
                "\"-draft\" hello",
                "100%",
                "one two three four five six seven eight nine ten eleven",
                "  spaced   out  ",
                "\"unterminated phrase",
                "\"\" empty",
                "'single' \"double\"",
                "0 00",
                "- -- ---",
                "red\r\ncar",
                "back\\slash \"quo\\\"te\"",
                "THE The the"));
        String accented = new String(Character.toChars(0xE9)) + "t" + new String(Character.toChars(0xE9));
        String cjk = new String(Character.toChars(0x65E5)) + new String(Character.toChars(0x672C));
        String astral = new String(Character.toChars(0x1F600));
        String kelvin = new String(Character.toChars(0x212A));
        searches.add(accented + " " + cjk + " \"" + astral + " " + accented + "\"");
        searches.add(kelvin + " " + new String(Character.toChars(0x130)));
        searches.add("x".repeat(1700));
        searches.add("word ".repeat(400));
        searches.add(("\"a phrase\" -out ").repeat(120));
        searches.add("r s t u v w x y z o -p");
        searches.add("alpha beta gamma delta epsilon zeta eta theta iota -kappa");
        searches.add("alpha beta gamma delta epsilon zeta eta theta iota kappa");
        searches.add("aa bb cc dd ee ff gg hh ii");
        searches.add("aa bb cc dd ee ff gg hh ii jj");
        searches.add("aa bb cc dd ee ff gg hh ii the");
        searches.add("aa bb cc dd ee ff gg hh ii jj kk the a");
        searches.add("--x");
        searches.add("--x y");
        searches.add("-x\ty");
        searches.add("a\tb");
        searches.add("aa\tbb");
        searches.add("aa bb cc dd ee ff gg -hh");
        searches.add("aa bb cc dd ee ff -gg");
        searches.add("aa-bb cc dd ee ff gg hh");
        searches.add("\"aa bb\" cc");
        searches.add("cc \"aa bb");
        searches.add("\"a\"");
        searches.add("\" a \"");
        searches.add("'a' 'bb'");
        for (String word : SearchTerms.STOPWORDS) {
            searches.add(word);
            searches.add(word.toUpperCase(java.util.Locale.ROOT) + " cat");
        }
        int hand = searches.size();
        List<String> pieces = new ArrayList<>(List.of(
                "cat", "Dog", "the", "The", "WITH", "a", "Z", "0", "00", "-", "--", "-cat", "-the", "-a", "x-y", "100%",
                "50_off", "it's", "'", "\\", "\r", "\n", accented, cjk, astral, kelvin, " ", " ", " ", "  ", "\t", ",",
                "+", "\"", "\"", "\" ", " \"", "-\"", "\"-"));
        searches.addAll(GeneratedCases.sample(
                Gens.lists(Gens.elements(pieces), 0, 14).map(parts -> String.join("", parts)),
                "20261020-search-terms",
                500));
        searches.addAll(GeneratedCases.sample(
                Gens.lists(Gens.elements(pieces), 14, 60).map(parts -> String.join("", parts)),
                "20261020-search-terms-long",
                150));
        searches.addAll(GeneratedCases.sample(
                Gens.strings("ab -+,\"'\t0th" + (char) 0xE9, 0, 40), "20261020-search-terms-chars", 300));
        int sentenceLimit = hand + 100;
        List<PhpOracle.Case> cases = new ArrayList<>();
        cases.add(new PhpOracle.Case(
                "stopwords", OracleJson.MAPPER.createObjectNode().put("op", "stopwords")));
        for (int i = 0; i < searches.size(); i++) {
            for (boolean sentence : new boolean[] {false, true}) {
                if (!sentence || i < sentenceLimit) {
                    cases.add(new PhpOracle.Case(
                            id(i, sentence),
                            OracleJson.MAPPER
                                    .createObjectNode()
                                    .put("op", "search_terms")
                                    .put("s", searches.get(i))
                                    .put("sentence", sentence)));
                }
            }
        }
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);
        List<String> wordpressStopwords = new ArrayList<>();
        out.get("stopwords").get("words").forEach(word -> wordpressStopwords.add(word.asText()));
        run.compare("stopwords", "list", String.join(",", wordpressStopwords), String.join(",", SearchTerms.STOPWORDS));
        int phrases = 0;
        int exclusions = 0;
        int dropped = 0;
        int overCount = 0;
        int overLength = 0;
        int nonAscii = 0;
        int unmatchedQuote = 0;
        int nine = 0;
        int ranked = 0;
        int rankSkipped = 0;
        int manyPositive = 0;
        for (int i = 0; i < searches.size(); i++) {
            String search = searches.get(i);
            for (boolean sentence : new boolean[] {false, true}) {
                if (sentence && i >= sentenceLimit) {
                    continue;
                }
                String id = id(i, sentence);
                if (i >= hand) {
                    run.generated(id, OracleJson.MAPPER.writeValueAsString(search));
                }
                JsonNode wordpress = out.get(id);
                List<String> terms = new ArrayList<>();
                wordpress.get("terms").forEach(term -> terms.add(term.asText()));
                SearchTerms.Parsed parsed = SearchTerms.parse(search, sentence);
                run.compare(id, "terms", String.join("\u0001", terms), String.join("\u0001", parsed.terms()));
                run.compare(id, "count", wordpress.get("count").asInt(), parsed.rawCount());
                run.compare(
                        id,
                        "positive",
                        wordpress.get("positive").asInt(),
                        parsed.positive().size());
                String order = wordpress.get("order").asText();
                boolean sentenceRank = parsed.rawCount() > 1 && !parsed.sentenceRankSkipped();
                boolean titleRanks = parsed.rawCount() > 1 && parsed.positive().size() < 7;
                run.compare(id, "rank-sentence", order.contains("THEN 1 "), sentenceRank);
                run.compare(id, "rank-all-words", order.contains("THEN 2 "), titleRanks);
                run.compare(
                        id,
                        "rank-any-word",
                        order.contains("THEN 3 "),
                        titleRanks && parsed.positive().size() > 1);
                if (sentence) {
                    continue;
                }
                phrases += parsed.terms().stream().anyMatch(t -> t.indexOf(' ') > 0) ? 1 : 0;
                exclusions += parsed.excluded().isEmpty() ? 0 : 1;
                dropped += parsed.rawCount() > parsed.terms().size()
                                && parsed.terms().size() > 1
                        ? 1
                        : 0;
                overCount += parsed.rawCount() > 9 ? 1 : 0;
                overLength += Slugs.byteLength(search) > SearchTerms.MAX_BYTES ? 1 : 0;
                nonAscii += search.chars().anyMatch(c -> c > 127) ? 1 : 0;
                unmatchedQuote += search.chars().filter(c -> c == '"').count() % 2 == 1 ? 1 : 0;
                nine += parsed.terms().size() == 9 ? 1 : 0;
                ranked += sentenceRank ? 1 : 0;
                rankSkipped += parsed.rawCount() > 1 && parsed.sentenceRankSkipped() ? 1 : 0;
                manyPositive += parsed.positive().size() >= 7 ? 1 : 0;
            }
        }
        System.out.println("posts search parity: " + cases.size() + " cases, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        assertThat(List.of(
                        phrases,
                        exclusions,
                        dropped,
                        overCount,
                        nonAscii,
                        unmatchedQuote,
                        nine,
                        ranked,
                        rankSkipped,
                        manyPositive))
                .allMatch(n -> n >= 5);
        assertThat(overLength).isGreaterThanOrEqualTo(1);
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }
}
