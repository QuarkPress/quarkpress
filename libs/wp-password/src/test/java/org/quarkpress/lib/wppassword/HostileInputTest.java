package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);
    private static final int MEGABYTE = 1 << 20;
    private static final String BCRYPT_AFTER_COST = "$06gRD94okD45dxtYQWn5quHxb9lT1cn7c8781KPpb5OP8SoVDUyq2";

    @Test
    void megabyteStoredHashesAreRejectedQuickly() {
        String[] prefixes = {
            "", "$wp", "$wp$2y$05$", "$P$B", "$H$9", "$2y$04$", "$generic$", "5f4dcc3b5aa765d61d8327deb882cf99"
        };
        for (String prefix : prefixes) {
            String hash = prefix + "a".repeat(MEGABYTE);
            assertTimeoutPreemptively(LIMIT, () -> {
                assertThat(WpPasswords.check("password", hash)).as(prefix).isFalse();
                assertThat(WpPasswords.verify("password", hash)).as(prefix).isFalse();
                assertThat(WpPasswords.checkFastHash("password", hash))
                        .as(prefix)
                        .isFalse();
                assertThat(WpPasswords.needsRehash(hash)).as(prefix).isTrue();
                assertThat(WpPasswords.detect(hash)).as(prefix).isNotNull();
            });
        }
    }

    @Test
    void megabytePasswordsAreHandled() {
        String password = "p".repeat(MEGABYTE);
        String md5 = new String(PhpBytes.md5Hex(PhpBytes.utf8(password)), US_ASCII);
        assertTimeoutPreemptively(LIMIT, () -> {
            assertThat(WpPasswords.check(password, md5)).isTrue(); // the MD5 rule has no length limit
            assertThat(WpPasswords.check(password, "$wp$2y$05$5KLnDnATV5NLkbHSOiGp7OBSSVzspqjDios4TlIEUuIwunPkuBtvi"))
                    .isFalse(); // over 4096 bytes: refused before bcrypt
            assertThat(WpPasswords.checkFastHash(password, "$generic$F6W18u0QEytvRVb59DemVWRxyTxU6vg_VK0s_f-h"))
                    .isFalse();
        });
    }

    @Test
    void bcryptCostsAbove16AreRefusedWithoutComputing() {
        for (String cost : new String[] {"17", "20", "31"}) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                assertThat(WpPasswords.check("password", "$2y$" + cost + BCRYPT_AFTER_COST))
                        .isFalse();
                assertThat(WpPasswords.check("password", "$wp$2y$" + cost + BCRYPT_AFTER_COST))
                        .isFalse();
                assertThat(WpPasswords.verify("O'Brien", "$wp$2y$" + cost + BCRYPT_AFTER_COST))
                        .isFalse();
            });
        }
    }

    @Test
    void phpassCountAbove20IsRefusedWithoutComputing() {
        // made by WordPress's crypt_private for "password": count 20 (character I) verifies, count 21 (J) is refused
        assertTimeoutPreemptively(LIMIT, () -> {
            assertThat(WpPasswords.check("password", "$P$ISALTsaltAfa/ywetlBTKYiuuYc4Tv."))
                    .isTrue();
            assertThat(WpPasswords.check("password", "$P$JSALTsalt2XOQEEDeEJpVehRAYfnLS0"))
                    .isFalse();
            assertThat(WpPasswords.verify("password", "$P$JSALTsalt2XOQEEDeEJpVehRAYfnLS0"))
                    .isFalse();
            assertThat(WpPasswords.check("password", "$P$SSALTsalt2XOQEEDeEJpVehRAYfnLS0"))
                    .isFalse();
        });
    }

    @Test
    void concurrentChecksAgree() throws Exception {
        record Case(String password, String hash, boolean expected) {}
        String wp = "$wp$2y$05$5KLnDnATV5NLkbHSOiGp7OBSSVzspqjDios4TlIEUuIwunPkuBtvi";
        String bcrypt = "$2y$05$06gRD94okD45dxtYQWn5quHxb9lT1cn7c8781KPpb5OP8SoVDUyq2";
        List<Case> cases = List.of(
                new Case("password", wp, true),
                new Case("Password", wp, false),
                new Case("password", bcrypt, true),
                new Case("passw0rd", bcrypt, false),
                new Case("password", "$P$BSALTsaltoN07aSztUl8KSk2X7DmZP0", true),
                new Case("abcdEFGH1234ijklMNOP5678", "$generic$F6W18u0QEytvRVb59DemVWRxyTxU6vg_VK0s_f-h", true));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int round = 0; round < 5; round++) {
                for (Case c : cases) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return c.hash().startsWith("$generic$")
                                ? WpPasswords.checkFastHash(c.password(), c.hash())
                                : WpPasswords.check(c.password(), c.hash());
                    }));
                }
            }
            start.countDown();
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get(60, SECONDS))
                        .as("task %d", i)
                        .isEqualTo(cases.get(i % cases.size()).expected());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
