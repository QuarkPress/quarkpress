package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.PasswordHash;

/**
 * Which stored hashes count as WordPress password hashes. The accepted ones were made by WordPress 7.1.3 (the
 * provider repository's fixture {@code wordpress-7.1.3-hashes.txt}, rows wp-ascii, phpass-ascii, md5-ascii,
 * bcrypt-ascii, bcrypt2a-ascii, bcrypt2b-ascii, refused-phpbb, refused-generic, refused-argon2id, refused-empty and
 * refused-star; refused-2x, refused-bcrypt-cost17, refused-phpass-countJ and refused-phpass-countS, which the
 * verifier always refuses).
 */
class PasswordHashTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "$wp$2y$12$IVHlbOSUa0j7vSMBy77C/O5r6OuCABN2sda2qkvpisF2tqDKR5jB6",
                "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1",
                "3cb4e732631f47e6eb961f34554b7cde",
                "$2y$10$KA0NEnytg/zxSoTENNiaEOkTc8wdO96L1V03KZcgwz4Qh/R557qme",
                "$2a$10$DnO3Muuy.NseTA4cBlI3YOJ2UoYaLXEbtghLaRuu2ZAvapLc3Ccna",
                "$2b$10$PvnY/tLSUpKqbYjTnW6iauTANTMwWNA9AnS.R8FulibhaoK7eMgcO"
            })
    void wordPressFormsAreAccepted(String hash) {
        PasswordHash accepted = PasswordHash.wordpress(hash);
        assertThat(accepted.value()).isEqualTo(hash);
        assertThat(accepted.algorithm()).isEqualTo("wordpress");
        assertThat(accepted.toString()).isEqualTo("PasswordHash[wordpress, redacted]");
        assertThat(accepted.toString()).doesNotContain(hash).doesNotContain(hash.substring(7, 20));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "$H$BHV2le2g4NIn/epSFJcMMREdVfv2f2/",
                "$generic$UXmSEHHlWHmuvc_VOFdJGL2KVqdjIDFJmRmKbjGr",
                "$argon2id$v=19$m=65536,t=4,p=1$OENod2R2SEVHT2Nqbm9tSw$oUnZNAeWZcsFrlU/YoBreKju/5l71NW6hmfAdr1VwNE",
                "",
                "*",
                "   ",
                "3CB4E732631F47E6EB961F34554B7CDE",
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
                "$2x$10$d9.qA9cPBh3X1n6myuxwneXyoY9H2DQcyYa5wJxfwZ8k80dGYTX7O",
                "$2y$17$2mJcVm8HVCFy/SijFIhqeussjDXDCJ1kn7h3GeKi9v3eY2yHz.SN.",
                "$P$JWTW41qB.dSHr1nMXsspaHrCktywFp1",
                "$P$SdSGrAF/YxeC1xi6b8biIl0AL6sDLh0",
                "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1x",
                "$wp$2y$12$IVHlbOSUa0j7vSMBy77C/O5r6OuCABN2sda2qkvpisF2tqDKR5jB"
            })
    void otherValuesAreRefusedWithoutBeingNamed(String hash) {
        assertThatThrownBy(() -> PasswordHash.wordpress(hash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a WordPress password hash");
    }

    @Test
    void nullIsRefused() {
        assertThatThrownBy(() -> PasswordHash.wordpress(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aValueOverWordPressColumnIsRefused() {
        String long256 = "$P$B" + "a".repeat(252);
        assertThatThrownBy(() -> PasswordHash.wordpress(long256))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a WordPress password hash");
    }

    /** A holder as an audit logger might serialize it. */
    record Holder(String name, PasswordHash password, NewUser user) {}

    @Test
    void jacksonNeverWritesTheHashEvenWithFieldVisibilityAny() throws Exception {
        String hash = "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1";
        PasswordHash value = PasswordHash.wordpress(hash);
        NewUser user =
                new NewUser("jdoe", Optional.empty(), "John", Optional.empty(), Optional.empty(), Optional.of(value));
        ObjectMapper any = new ObjectMapper()
                .findAndRegisterModules()
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        String json = any.writeValueAsString(new Holder("x", value, user));
        assertThat(json)
                .doesNotContain(hash)
                .doesNotContain("RFhzU1")
                .contains("\"name\":\"x\"")
                .contains("\"password\":\"PasswordHash[wordpress, redacted]\"");
        assertThat(any.writeValueAsString(List.of(value))).isEqualTo("[\"PasswordHash[wordpress, redacted]\"]");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(user))
                .doesNotContain(hash)
                .doesNotContain("RFhzU1");
    }
}
