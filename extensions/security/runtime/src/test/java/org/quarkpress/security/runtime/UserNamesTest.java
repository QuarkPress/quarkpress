package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.UserId;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class UserNamesTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void strictSanitizingFollowsWordPress() {
        assertThat(UserNames.sanitizeUserStrict(cp(0xC9) + "milie Dubois")).isEqualTo("Emilie Dubois");
        assertThat(UserNames.sanitizeUserStrict("  John    SMITH ")).isEqualTo("John SMITH");
        assertThat(UserNames.sanitizeUserStrict("jane.doe@example.com")).isEqualTo("jane.doe@example.com");
        assertThat(UserNames.sanitizeUserStrict("O'Brien")).isEqualTo("OBrien");
        assertThat(UserNames.sanitizeUserStrict("<b>bob</b>")).isEqualTo("bob");
        assertThat(UserNames.sanitizeUserStrict("<script>x</script>bob")).isEqualTo("bob");
        assertThat(UserNames.sanitizeUserStrict("AT&amp;T")).isEqualTo("ATT");
        assertThat(UserNames.sanitizeUserStrict("AT&T")).isEqualTo("ATT");
        assertThat(UserNames.sanitizeUserStrict("100%25off")).isEqualTo("100off");
        assertThat(UserNames.sanitizeUserStrict(cp(0x418, 0x432, 0x430, 0x43D))).isEmpty();
        assertThat(UserNames.sanitizeUserStrict(cp(0x674E, 0x5C0F, 0x9F99) + " lee"))
                .isEqualTo("lee");
        assertThat(UserNames.sanitizeUserStrict("Stra" + cp(0xDF) + "e")).isEqualTo("Strase");
    }

    @Test
    void entitiesEndAtTheFirstSemicolonOnTheSameLine() {
        assertThat(UserNames.sanitizeUserStrict("a&b\nc;d")).isEqualTo("abcd");
        assertThat(UserNames.sanitizeUserStrict("&;;x")).isEqualTo("x");
        assertThat(UserNames.sanitizeUserStrict("x&;")).isEqualTo("x");
        assertThat(UserNames.sanitizeUserStrict("a&bc;d&e;f")).isEqualTo("adf");
        assertThat(UserNames.sanitizeUserStrict("%%4141")).isEqualTo("41");
    }

    @Test
    void manyAmpersandsWithoutSemicolonsAreHandledInOnePass() {
        String input = "&".repeat(20_000) + "x";
        assertThat(UserNames.sanitizeUserStrict(input)).isEqualTo("x");
    }

    @Test
    void theNicenameComesFromTheLogin() {
        assertThat(UserNames.nicenameOfLogin("Emilie Dubois")).isEqualTo("emilie-dubois");
        assertThat(UserNames.nicenameOfLogin("John SMITH")).isEqualTo("john-smith");
        assertThat(UserNames.nicenameOfLogin("jane.doe@example.com")).isEqualTo("jane-doeexample-com");
        assertThat(UserNames.nicenameOfLogin("John_Smith")).isEqualTo("john_smith");
        assertThat(UserNames.nicenameOfLogin("a.-.b")).isEqualTo("a-b");
        assertThat(UserNames.nicenameOfLogin("Bob 2")).isEqualTo("bob-2");
        assertThat(UserNames.nicenameOfLogin("...")).isEmpty();
        assertThat(UserNames.nicenameOfLogin("@@@")).isEmpty();
        assertThat(UserNames.nicenameOfLogin("abcdefghij".repeat(6))).isEqualTo("abcdefghij".repeat(5));
    }

    @Test
    void aBaseIsMissingWhereWordPressWouldRefuseTheUser() {
        assertThat(UserNames.nicenameBase("John.Smith")).contains("john-smith");
        assertThat(UserNames.nicenameBase(cp(0x418, 0x432, 0x430, 0x43D))).isEmpty();
        assertThat(UserNames.nicenameBase("...")).isEmpty();
        assertThat(UserNames.nicenameBase("")).isEmpty();
        assertThat(UserNames.fallbackNicename(UserId.of(42))).isEqualTo("user-42");
    }

    @Test
    void suffixesKeepTheNicenameWithinFiftyCharacters() {
        assertThat(UserNames.withSuffix("john", 2)).isEqualTo("john-2");
        assertThat(UserNames.withSuffix("x".repeat(50), 2)).isEqualTo("x".repeat(48) + "-2");
        assertThat(UserNames.withSuffix("x".repeat(50), 10)).isEqualTo("x".repeat(47) + "-10");
        assertThat(UserNames.withSuffix("x".repeat(47) + "-yy", 2)).isEqualTo("x".repeat(47) + "--2");
        assertThatThrownBy(() -> UserNames.withSuffix("john", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a nicename suffix starts at 2: 1");
    }

    @Test
    void theDisplayNameFollowsWordPressDefaults() {
        String first = cp(0xC9) + "milie";
        assertThat(UserNames.displayName(Optional.of(first), Optional.of("Dubois"), Optional.of("emilie"), "s1"))
                .isEqualTo(first + " Dubois");
        assertThat(UserNames.displayName(Optional.of(" Ann "), Optional.empty(), Optional.of("ann"), "s1"))
                .isEqualTo("Ann");
        assertThat(UserNames.displayName(Optional.of(""), Optional.of("Dubois"), Optional.empty(), "s1"))
                .isEqualTo("Dubois");
        assertThat(UserNames.displayName(Optional.empty(), Optional.empty(), Optional.of("jane.doe@example.com"), "s1"))
                .isEqualTo("jane.doe@example.com");
        assertThat(UserNames.displayName(Optional.of("  "), Optional.empty(), Optional.of(" "), "sub-1"))
                .isEqualTo("sub-1");
        assertThat(UserNames.displayName(Optional.of("Tom & Jerry"), Optional.empty(), Optional.empty(), "s"))
                .isEqualTo("Tom & Jerry");
        String longName = "n".repeat(300);
        assertThat(UserNames.displayName(Optional.of(longName), Optional.empty(), Optional.empty(), "s"))
                .hasSize(250);
        String emoji = cp(0x1F600).repeat(260);
        assertThat(UserNames.displayName(Optional.of(emoji), Optional.empty(), Optional.empty(), "s")
                        .codePointCount(0, 500))
                .isEqualTo(250);
    }

    @Test
    void aLoginOrNicenameThatPhpCallsEmptyIsRefused() {
        assertThat(UserNames.nicenameBase("0")).isEmpty();
        assertThat(UserNames.nicenameBase(" 0 ")).isEmpty();
        assertThat(UserNames.nicenameBase("0.")).isEmpty();
        assertThat(UserNames.nicenameBase("-0")).isEmpty();
        assertThat(UserNames.nicenameBase("0%41")).isEmpty();
        assertThat(UserNames.nicenameBase("00")).contains("00");
        assertThat(UserNames.nicenameBase("0a")).contains("0a");
    }

    @Test
    void aNameOfZeroCountsAsAbsent() {
        assertThat(UserNames.displayName(Optional.of("0"), Optional.of("Smith"), Optional.of("u"), "s"))
                .isEqualTo("Smith");
        assertThat(UserNames.displayName(Optional.of("Ann"), Optional.of(" 0 "), Optional.of("u"), "s"))
                .isEqualTo("Ann");
        assertThat(UserNames.displayName(Optional.of("0"), Optional.of("0"), Optional.of("u"), "s"))
                .isEqualTo("u");
        assertThat(UserNames.displayName(Optional.of("00"), Optional.empty(), Optional.empty(), "s"))
                .isEqualTo("00");
    }

    private static final String ALPHABET = "aZ09 _.-@<>/&;%#'\"\n\t" + cp(0xC9, 0xDF, 0x142, 0x418, 0x674E, 0x1EA1);

    @Test
    void strictNamesHoldOnlyPortableCharactersAndAreStable() {
        Gen<String> names = Gens.strings(ALPHABET, 0, 40);
        Properties.check("strict user names", 2_000, names, raw -> {
            String strict = UserNames.sanitizeUserStrict(raw);
            for (char c : strict.toCharArray()) {
                assertThat((c >= 'a' && c <= 'z')
                                || (c >= 'A' && c <= 'Z')
                                || (c >= '0' && c <= '9')
                                || c == ' '
                                || c == '_'
                                || c == '.'
                                || c == '-'
                                || c == '@')
                        .as("character %s of %s", (int) c, strict)
                        .isTrue();
            }
            assertThat(strict).doesNotStartWith(" ").doesNotEndWith(" ").doesNotContain("  ");
            assertThat(UserNames.sanitizeUserStrict(strict)).isEqualTo(strict);
        });
    }

    @Test
    void nicenamesAreSlugsOfAtMostFiftyCharacters() {
        Gen<String> names = Gens.strings(ALPHABET, 0, 80);
        Properties.check("nicenames", 2_000, names, raw -> {
            String nicename = UserNames.nicenameOfLogin(UserNames.login(raw));
            assertThat(nicename.length()).isLessThanOrEqualTo(UserNames.MAX_NICENAME);
            for (char c : nicename.toCharArray()) {
                assertThat((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')
                        .as("character %s of %s", (int) c, nicename)
                        .isTrue();
            }
            assertThat(nicename).doesNotStartWith("-").doesNotEndWith("-").doesNotContain("--");
            for (int suffix : new int[] {2, 9, 10, 99, 100, 12345}) {
                String suffixed = UserNames.withSuffix(nicename, suffix);
                assertThat(suffixed).endsWith("-" + suffix);
                assertThat(suffixed.length()).isLessThanOrEqualTo(UserNames.MAX_NICENAME);
            }
        });
    }
}
