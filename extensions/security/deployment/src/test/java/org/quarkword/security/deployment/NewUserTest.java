package org.quarkword.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkword.security.NewUser;

/**
 * The bounds of a user to import. WordPress 7.1.3's {@code wp_insert_user} refuses an empty login, {@code 0} and a
 * login of more than 60 characters (checked with the real function); QuarkWord also refuses a padded login, which
 * WordPress would trim.
 */
class NewUserTest {

    private static NewUser user(String username) {
        return NewUser.of(username, "Name");
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    @Test
    void aUsernameHasOneToSixtyCharacters() {
        assertThat(user("a").username()).isEqualTo("a");
        assertThat(user(repeat("a", 60)).username()).hasSize(60);
        assertThatThrownBy(() -> user(repeat("a", 61)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a username has at most 60 characters: 61");
    }

    @Test
    void usernameLengthCountsCodePoints() {
        String clef = "𝄞";
        assertThat(user(repeat(clef, 60)).username()).hasSize(120);
        assertThatThrownBy(() -> user(repeat(clef, 61)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a username has at most 60 characters: 61");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", " ", "   ", "\t"})
    void anEmptyUsernameIsRefused(String username) {
        assertThatThrownBy(() -> user(username))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a username must not be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {" jdoe", "jdoe ", "\tjdoe", "jdoe\n", "jdoe\u0000", " jdoe", "jdoe ", "\u000Bjdoe"})
    void aPaddedUsernameIsRefused(String username) {
        assertThatThrownBy(() -> user(username))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a username must not start or end with whitespace");
    }

    @Test
    void innerSpacesAndOtherCharactersAreKeptForTheIdentityProviderToJudge() {
        assertThat(user("John Doe").username()).isEqualTo("John Doe");
        assertThat(user("O'Brien").username()).isEqualTo("O'Brien");
        assertThat(user("00").username()).isEqualTo("00");
    }

    @Test
    void aDisplayNameHasAtMost250Characters() {
        assertThat(NewUser.of("jdoe", repeat("d", 250)).displayName()).hasSize(250);
        assertThat(NewUser.of("jdoe", "").displayName()).isEmpty();
        assertThatThrownBy(() -> NewUser.of("jdoe", repeat("d", 251)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a display name has at most 250 characters: 251");
        assertThat(NewUser.of("jdoe", repeat("𝄞", 250)).displayName()).hasSize(500);
    }

    @Test
    void anEmailHasAtMost100Characters() {
        String hundred = repeat("e", 88) + "@example.com";
        assertThat(new NewUser("jdoe", Optional.of(hundred), "", Optional.empty(), Optional.empty(), Optional.empty())
                        .email())
                .contains(hundred);
        assertThatThrownBy(() -> new NewUser(
                        "jdoe", Optional.of("e" + hundred), "", Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an e-mail has at most 100 characters: 101");
    }

    @Test
    void nothingIsNull() {
        assertThatThrownBy(() -> NewUser.of(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NewUser.of("x", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewUser("x", null, "", Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewUser("x", Optional.empty(), "", Optional.empty(), Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void namesArePlainTextWithTagsLineBreaksAndPaddingRemovedAsWordPressRemovesThem() {
        assertThat(NewUser.of("jdoe", "<script>alert(1)</script>Bob").displayName())
                .isEqualTo("Bob");
        assertThat(NewUser.of("jdoe", "a\nb\r\nc").displayName()).isEqualTo("a b c");
        assertThat(NewUser.of("jdoe", "<b>Jo</b>hn").displayName()).isEqualTo("John");
        assertThat(NewUser.of("jdoe", "  pad  \t\n").displayName()).isEqualTo("pad");
        assertThat(NewUser.of("jdoe", "<img src=x onerror=alert(1)>").displayName())
                .isEmpty();
        // plain text: entities are not decoded or encoded, percent octets kept
        assertThat(NewUser.of("jdoe", "Tom & Jerry").displayName()).isEqualTo("Tom & Jerry");
        assertThat(NewUser.of("jdoe", "%41").displayName()).isEqualTo("%41");
        NewUser named = new NewUser(
                "jdoe",
                Optional.empty(),
                "",
                Optional.of("<i>Jane</i>\n"),
                Optional.of("<style>x</style> "),
                Optional.empty());
        assertThat(named.firstName()).contains("Jane");
        assertThat(named.lastName()).isEmpty();
    }

    @Test
    void theDisplayNameBoundCountsWhatRemainsAfterTags() {
        assertThat(NewUser.of("jdoe", "<b>" + repeat("d", 250) + "</b>").displayName())
                .hasSize(250);
    }

    private static NewUser withEmail(String email) {
        return new NewUser("jdoe", Optional.of(email), "", Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void anEmailIsTrimmedAndABlankOneIsAbsent() {
        assertThat(withEmail(" jdoe@example.com \t").email()).contains("jdoe@example.com");
        assertThat(withEmail("").email()).isEmpty();
        assertThat(withEmail(" \n ").email()).isEmpty();
        assertThat(withEmail("A.B+c@Example.COM").email()).contains("A.B+c@Example.COM");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "x@y.com\r\nBcc: evil@z.com",
                "a\"<script>@example.com",
                "a b@example.com",
                "a@b@example.com",
                "not-an-email",
                "a@b",
                "\u00fcser@example.com",
                "a@\u4f8b\u3048.jp",
                "a@b..co",
                "a@b_c.co",
                "a\u0000@example.com"
            })
    void anEmailWordPressWouldChangeOrEmptyIsRefusedWithoutBeingNamed(String email) {
        assertThatThrownBy(() -> withEmail(email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an e-mail must be a valid address");
    }
}
