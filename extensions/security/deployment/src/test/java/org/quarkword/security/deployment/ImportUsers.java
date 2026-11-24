package org.quarkword.security.deployment;

import java.util.Optional;
import java.util.UUID;
import org.quarkword.security.NewUser;
import org.quarkword.security.PasswordHash;

/** Users for the import tests. */
final class ImportUsers {

    /** A phpass hash WordPress 7.1.3 made (the provider's fixture row phpass-ascii). */
    static final String PHPASS = "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1";

    private ImportUsers() {}

    /** A lower-case word no other test uses, so nicenames do not meet other tests' profiles. */
    static String word() {
        StringBuilder word = new StringBuilder("i");
        for (char c : UUID.randomUUID().toString().toCharArray()) {
            if (c >= 'a' && c <= 'f') {
                word.append(c);
            } else if (c >= '0' && c <= '9') {
                word.append((char) ('g' + (c - '0')));
            }
            if (word.length() == 12) {
                break;
            }
        }
        return word.toString();
    }

    /** John Doe with an e-mail and a WordPress hash, under {@code username}. */
    static NewUser john(String username) {
        return new NewUser(
                username,
                Optional.of(username + "@example.com"),
                "John Doe",
                Optional.of("John"),
                Optional.of("Doe"),
                Optional.of(PasswordHash.wordpress(PHPASS)));
    }
}
