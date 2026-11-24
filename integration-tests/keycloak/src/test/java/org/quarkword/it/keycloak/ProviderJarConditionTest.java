package org.quarkword.it.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.io.TempDir;

/** When the end-to-end tests run, skip or fail: missing and stale provider files, the require flag, the pin check. */
class ProviderJarConditionTest {

    private static final String PIN = "8875ba968ef33cd8efc74bd7a47e41a40b4be1b5";

    private static final String OTHER = "74bd0789f90939b400445253fd9556b8e518418a";

    @TempDir
    Path dir;

    private Path jar;

    private Path hashes;

    private Path source;

    @BeforeEach
    void checkout() throws IOException {
        source = write("wp-password/src/main/java/Provider.java", "class Provider {}");
        write("pom.xml", "<project/>");
        write("wp-password/pom.xml", "<project/>");
        hashes = write("wp-password/src/test/resources/wordpress-7.1.3-hashes.txt", "# rows");
        jar = write("wp-password/target/quarkword-keycloak-wp-password.jar", "jar");
        age(source, 100);
        age(dir.resolve("pom.xml"), 100);
        age(dir.resolve("wp-password/pom.xml"), 100);
        age(jar, 10);
    }

    private Path write(String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, UTF_8);
        return file;
    }

    private static void age(Path file, long seconds) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(seconds)));
    }

    private List<String> problems() {
        return ProviderJarCondition.problems(jar.toString(), hashes.toString(), dir.toString());
    }

    @Test
    void aJarNewerThanItsSourcesWithItsFixturesRuns() {
        assertThat(problems()).isEmpty();
        assertThat(ProviderJarCondition.decide(problems(), false).isDisabled()).isFalse();
        assertThat(ProviderJarCondition.decide(problems(), true).isDisabled()).isFalse();
    }

    @Test
    void aMissingJarSkipsLocallyAndFailsWhereRequired() throws IOException {
        Files.delete(jar);

        assertThat(problems()).singleElement().asString().startsWith("SKIPPED: provider jar not found at " + jar);
        ConditionEvaluationResult local = ProviderJarCondition.decide(problems(), false);
        assertThat(local.isDisabled()).isTrue();
        assertThat(local.getReason())
                .hasValueSatisfying(reason -> assertThat(reason).contains("not found"));
        assertThatThrownBy(() -> ProviderJarCondition.decide(problems(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider jar not found");
    }

    @Test
    void missingFixturesSkipLocallyAndFailWhereRequired() throws IOException {
        Files.delete(hashes);

        assertThat(problems()).singleElement().asString().contains("WordPress hash fixtures not found");
        assertThat(ProviderJarCondition.decide(problems(), false).isDisabled()).isTrue();
        assertThatThrownBy(() -> ProviderJarCondition.decide(problems(), true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnsetPropertyIsMissing() {
        assertThat(ProviderJarCondition.problems(null, hashes.toString(), dir.toString()))
                .singleElement()
                .asString()
                .contains("provider jar not found");
    }

    @Test
    void aJarOlderThanASourceFileIsStale() throws IOException {
        age(source, 1);

        assertThat(problems()).singleElement().asString().contains("older than its sources");
        assertThat(ProviderJarCondition.decide(problems(), false).isDisabled()).isTrue();
        assertThatThrownBy(() -> ProviderJarCondition.decide(problems(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("older than its sources");
    }

    @Test
    void aJarOlderThanEitherPomIsStale() throws IOException {
        age(dir.resolve("pom.xml"), 1);
        assertThat(problems()).singleElement().asString().contains("older than its sources");

        age(dir.resolve("pom.xml"), 100);
        age(dir.resolve("wp-password/pom.xml"), 1);
        assertThat(problems()).singleElement().asString().contains("older than its sources");
    }

    @Test
    void theCheckoutsCommitIsReadFromABranchPackedRefsOrADetachedHead() throws IOException {
        assertThat(ProviderJarCondition.headCommit(dir)).isEmpty();

        write(".git/HEAD", "ref: refs/heads/main\n");
        write(".git/packed-refs", "# pack-refs with: peeled fully-peeled sorted\n" + OTHER + " refs/heads/main\n");
        assertThat(ProviderJarCondition.headCommit(dir)).contains(OTHER);

        write(".git/refs/heads/main", PIN + "\n");
        assertThat(ProviderJarCondition.headCommit(dir)).contains(PIN);

        write(".git/HEAD", OTHER + "\n");
        assertThat(ProviderJarCondition.headCommit(dir)).contains(OTHER);
    }

    @Test
    void aCheckoutAtAnotherCommitThanThePinIsReported() throws IOException {
        write(".git/HEAD", OTHER + "\n");

        assertThat(ProviderJarCondition.pinWarning(dir.toString(), PIN))
                .hasValueSatisfying(
                        warning -> assertThat(warning).contains(OTHER).contains(PIN));
        assertThat(ProviderJarCondition.pinWarning(dir.toString(), OTHER)).isEmpty();
        // the pin is unknown when the root POM does not set it: Maven leaves the expression as it is
        assertThat(ProviderJarCondition.pinWarning(dir.toString(), "${quarkword-keycloak.commit}"))
                .isEmpty();
        assertThat(ProviderJarCondition.pinWarning(dir.toString(), null)).isEmpty();
    }
}
