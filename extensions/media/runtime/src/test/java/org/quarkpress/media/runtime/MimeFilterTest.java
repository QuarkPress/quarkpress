package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.runtime.MimeFilter.MimeClause;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

/** WordPress's MIME-type filter of attachment queries, with the values WordPress 7.1.3 gave for its examples. */
class MimeFilterTest {

    @Test
    void wordPresssExamples() {
        assertThat(MimeFilter.parse(List.of("image"))).contains(List.of(new MimeClause("image/%", true)));
        assertThat(MimeFilter.parse(List.of("image/jpeg"))).contains(List.of(new MimeClause("image/jpeg", false)));
        assertThat(MimeFilter.parse(List.of("image/*", "video")))
                .contains(List.of(new MimeClause("image/%", true), new MimeClause("video/%", true)));
        assertThat(MimeFilter.parse(List.of("*"))).contains(List.of(new MimeClause("%", true)));
        assertThat(MimeFilter.parse(List.of("application/vnd.ms-*")))
                .contains(List.of(new MimeClause("application/vnd.ms-%", true)));
        assertThat(MimeFilter.parse(List.of(" image / jpeg "))).contains(List.of(new MimeClause("image/jpeg", false)));
        assertThat(MimeFilter.parse(List.of("image/"))).contains(List.of(new MimeClause("image/%", true)));
        assertThat(MimeFilter.parse(List.of("ima!ge"))).contains(List.of(new MimeClause("image/%", true)));
        assertThat(MimeFilter.parse(List.of("image/sv+g**"))).contains(List.of(new MimeClause("image/sv+g%", true)));
    }

    @Test
    void anEmptyOrPercentValueDropsTheWholeFilter() {
        for (String v : List.of("", "%", "%/%")) {
            assertThat(MimeFilter.parse(List.of("image", v))).as(v).isEmpty();
            assertThat(MimeFilter.parse(List.of(v, "image"))).as(v).isEmpty();
        }
        // only those exact values: the same characters inside other values are cleaned away
        assertThat(MimeFilter.parse(List.of("%%"))).contains(List.of(new MimeClause("/%", true)));
        assertThat(MimeFilter.parse(List.of("image/%"))).contains(List.of(new MimeClause("image/%", true)));
        assertThat(MimeFilter.parse(List.of())).isEmpty();
    }

    @Test
    void theGroupAndTheSubtypeKeepTheirOwnCharacters() {
        // a plus sign is kept in the subtype only; a value with a star keeps no implied subtype
        assertThat(MimeFilter.parse(List.of("a+b/c+d"))).contains(List.of(new MimeClause("ab/c+d", false)));
        assertThat(MimeFilter.parse(List.of("a+b"))).contains(List.of(new MimeClause("ab/%", true)));
        assertThat(MimeFilter.parse(List.of("vnd.*"))).contains(List.of(new MimeClause("vnd.%", true)));
        assertThat(MimeFilter.parse(List.of("A-Z.09_x/Y_z-1.0")))
                .contains(List.of(new MimeClause("A-Z.09x/Yz-1.0", false)));
        assertThat(MimeFilter.parse(List.of("*/jpeg"))).contains(List.of(new MimeClause("%/jpeg", true)));
        assertThat(MimeFilter.parse(List.of("i*m**a***ge"))).contains(List.of(new MimeClause("i%m%a%ge", true)));
        assertThat(MimeFilter.parse(List.of("\timage/\njpeg\r", "\u000Bvideo\f")))
                .contains(List.of(new MimeClause("image/jpeg", false), new MimeClause("video/%", true)));
        assertThat(MimeFilter.parse(List.of("!!!"))).contains(List.of(new MimeClause("/%", true)));
        assertThat(MimeFilter.parse(List.of("image/" + (char) 0xE9 + "jpeg")))
                .contains(List.of(new MimeClause("image/jpeg", false)));
    }

    @Test
    void eachValueIsACommaListKeepingEmptyPieces() {
        assertThat(MimeFilter.parse(List.of("image/*,video")))
                .contains(List.of(new MimeClause("image/%", true), new MimeClause("video/%", true)));
        assertThat(MimeFilter.parse(List.of("image/jpeg,application/pdf", "video")))
                .contains(List.of(
                        new MimeClause("image/jpeg", false),
                        new MimeClause("application/pdf", false),
                        new MimeClause("video/%", true)));
        for (String v : List.of("image,", ",image", "image,,video", ",", "image,%", "image,%/%")) {
            assertThat(MimeFilter.parse(List.of(v))).as(v).isEmpty();
            assertThat(MimeFilter.parse(List.of("video", v))).as(v).isEmpty();
        }
        assertThat(MimeFilter.parse(List.of("image, video")))
                .contains(List.of(new MimeClause("image/%", true), new MimeClause("video/%", true)));
    }

    @Test
    void theNumberOfValuesIsBounded() {
        List<String> most = new ArrayList<>(Collections.nCopies(MimeFilter.MAX_VALUES, "image"));
        assertThat(MimeFilter.parse(most))
                .hasValueSatisfying(clauses -> assertThat(clauses).hasSize(MimeFilter.MAX_VALUES));
        most.add("video");
        assertThatThrownBy(() -> MimeFilter.parse(most))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at most " + MimeFilter.MAX_VALUES + " MIME types may be given");
        assertThat(MimeFilter.MAX_VALUES).isEqualTo(1000);
        // pieces of comma lists count one by one
        List<String> pairs = new ArrayList<>(Collections.nCopies(MimeFilter.MAX_VALUES / 2, "image,video"));
        assertThat(MimeFilter.parse(pairs))
                .hasValueSatisfying(clauses -> assertThat(clauses).hasSize(MimeFilter.MAX_VALUES));
        pairs.add("audio");
        assertThatThrownBy(() -> MimeFilter.parse(pairs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at most " + MimeFilter.MAX_VALUES + " MIME types may be given");
        assertThatThrownBy(() -> MimeFilter.parse(List.of(",".repeat(MimeFilter.MAX_VALUES))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clausesHoldOnlyKeptCharactersAndOneWildcardPerRun() {
        Properties.check(
                "MimeFilterTest.clauses", 3_000, Gens.lists(Gens.strings("ab+*/-.%! \t9Z_", 0, 12), 1, 4), values -> {
                    Optional<List<MimeClause>> parsed = MimeFilter.parse(values);
                    boolean dropped = values.stream().anyMatch(v -> v.isEmpty() || v.equals("%") || v.equals("%/%"));
                    assertThat(parsed.isEmpty()).isEqualTo(dropped);
                    parsed.ifPresent(clauses -> {
                        assertThat(clauses).hasSize(values.size());
                        for (MimeClause clause : clauses) {
                            String value = clause.value();
                            assertThat(value).doesNotContain("%%", " ", "\t", "!", "_", "*");
                            // a slash only from the value, or added to a value without a star; never two
                            int slash = value.indexOf('/');
                            if (!clause.like()) {
                                assertThat(slash).isNotNegative();
                            }
                            assertThat(slash).isEqualTo(value.lastIndexOf('/'));
                            String group = slash < 0 ? value : value.substring(0, slash);
                            assertThat(group).doesNotContain("+");
                            assertThat(clause.like()).isEqualTo(value.contains("%"));
                        }
                    });
                });
    }
}
