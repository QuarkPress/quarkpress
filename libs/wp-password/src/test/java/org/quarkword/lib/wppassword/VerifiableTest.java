package org.quarkword.lib.wppassword;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link WpPasswords#verifiable}: true for every hash some password passes, false for the forms that never pass. */
class VerifiableTest {

    @Test
    void everyHashWordPressAndQuarkWordAcceptIsVerifiable() throws IOException {
        List<WordPressVectorsTest.Vector> vectors = WordPressVectorsTest.load();
        int accepted = 0;
        for (WordPressVectorsTest.Vector v : vectors) {
            if (!v.function().equals("wp_check_password") || !v.context().equals("-")) {
                continue;
            }
            boolean quarkWord = WordPressVectorsTest.QUARKWORD_CHECK.getOrDefault(
                    v.id(), v.check().equals("true"));
            if (quarkWord) {
                accepted++;
                assertThat(WpPasswords.verifiable(v.hash())).as(v.id()).isTrue();
            }
            if (!WpPasswords.verifiable(v.hash())) {
                assertThat(quarkWord).as(v.id()).isFalse();
            }
        }
        assertThat(accepted).isGreaterThan(20);
    }

    /** Hashes WordPress 7.1.3 made (the provider repository's fixture), in forms that are verified. */
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
    void verifiedForms(String hash) {
        assertThat(WpPasswords.verifiable(hash)).isTrue();
    }

    /**
     * Forms that never pass: WordPress-made {@code $2x$}, cost 17 bcrypt, phpass counts {@code J} and {@code S},
     * phpBB, {@code $generic$}, argon2id, empty and {@code *} (the provider's fixture), and malformed look-alikes.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "$2x$10$d9.qA9cPBh3X1n6myuxwneXyoY9H2DQcyYa5wJxfwZ8k80dGYTX7O",
                "$2y$17$2mJcVm8HVCFy/SijFIhqeussjDXDCJ1kn7h3GeKi9v3eY2yHz.SN.",
                "$wp$2y$17$2mJcVm8HVCFy/SijFIhqeussjDXDCJ1kn7h3GeKi9v3eY2yHz.SN.",
                "$P$JWTW41qB.dSHr1nMXsspaHrCktywFp1",
                "$P$SdSGrAF/YxeC1xi6b8biIl0AL6sDLh0",
                "$P$4dSGrAF/YxeC1xi6b8biIl0AL6sDLh0",
                "$H$BHV2le2g4NIn/epSFJcMMREdVfv2f2/",
                "$generic$UXmSEHHlWHmuvc_VOFdJGL2KVqdjIDFJmRmKbjGr",
                "$argon2id$v=19$m=65536,t=4,p=1$OENod2R2SEVHT2Nqbm9tSw$oUnZNAeWZcsFrlU/YoBreKju/5l71NW6hmfAdr1VwNE",
                "",
                "*",
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
                "3CB4E732631F47E6EB961F34554B7CDE",
                "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1x",
                "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU!",
                "$wp$2y$12$IVHlbOSUa0j7vSMBy77C/O5r6OuCABN2sda2qkvpisF2tqDKR5jB",
                "$wp$2y$12$IVHlbOSUa0j7vSMBy77C/O5r6OuCABN2sda2qkvpisF2tqDKR5jB!",
                "$wpxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                "$2y$03$KA0NEnytg/zxSoTENNiaEOkTc8wdO96L1V03KZcgwz4Qh/R557qme",
                "$2y$1x$KA0NEnytg/zxSoTENNiaEOkTc8wdO96L1V03KZcgwz4Qh/R557qme"
            })
    void formsThatNeverPass(String hash) {
        assertThat(WpPasswords.verifiable(hash)).isFalse();
    }

    @Test
    void nullIsNotVerifiable() {
        assertThat(WpPasswords.verifiable(null)).isFalse();
    }
}
