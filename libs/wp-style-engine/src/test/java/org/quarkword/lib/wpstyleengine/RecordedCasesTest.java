package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Checks every case of the shared case file whose WordPress output is recorded, without php. */
class RecordedCasesTest {

    private static final List<RecordedCase> CASES = RecordedCases.load();

    @TestFactory
    Stream<DynamicTest> recordedOutputs() {
        return CASES.stream()
                .filter(c -> c.out() != null)
                .map(c -> DynamicTest.dynamicTest(
                        c.id(),
                        () -> assertThat(Functions.ours(c.fn(), c.in(), c.args()))
                                .as("%s %s", c.id(), c.fn())
                                .isEqualTo(c.out())));
    }

    @Test
    void everyCaseHasAKnownFn() {
        assertThat(CASES)
                .allSatisfy(
                        c -> assertThat(Functions.FNS).as("fn of %s", c.id()).contains(c.fn()));
    }

    @Test
    void idsAreUniqueAndPrefixed() {
        Set<String> seen = new HashSet<>();
        assertThat(CASES).allSatisfy(c -> {
            assertThat(seen.add(c.id())).as("duplicate id %s", c.id()).isTrue();
            assertThat(c.id()).matches("se-g\\d+[a-z]?|rf\\d-[a-z0-9-]+|t\\d-[a-z0-9-]+");
        });
    }
}
