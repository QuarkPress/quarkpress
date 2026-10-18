package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WordPressTreeTest {

    @TempDir
    Path tmp;

    private Path zip(Map<String, String> entries) throws IOException {
        Path archive = tmp.resolve("archive-" + entries.hashCode() + ".zip");
        try (OutputStream out = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private static Map<String, String> release() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("wordpress/wp-settings.php", "<?php // settings");
        entries.put("wordpress/wp-includes/version.php", "<?php // version");
        return entries;
    }

    @Test
    void theReleaseIsUnpackedOncePerArchive() throws Exception {
        Path archive = zip(release());
        Path out = tmp.resolve("out");
        Path tree = WordPressTree.unpack(archive, "a".repeat(64), out);
        assertThat(tree).isEqualTo(out.resolve("wordpress"));
        assertThat(tree.resolve("wp-includes/version.php")).hasContent("<?php // version");
        Files.writeString(tree.resolve("left-over.txt"), "from an earlier run");
        assertThat(WordPressTree.unpack(archive, "a".repeat(64), out)).isEqualTo(tree);
        assertThat(tree.resolve("left-over.txt")).exists();
        WordPressTree.unpack(archive, "b".repeat(64), out);
        assertThat(tree.resolve("left-over.txt")).doesNotExist();
        assertThat(tree.resolve("wp-settings.php")).exists();
    }

    @Test
    void anEntryOutsideTheTargetIsRefused() throws Exception {
        Path archive = zip(Map.of("../evil.php", "<?php"));
        assertThatThrownBy(() -> WordPressTree.extract(archive, tmp.resolve("out")))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("the archive entry ../evil.php lies outside ");
        assertThat(tmp.resolve("evil.php")).doesNotExist();
    }

    @Test
    void anArchiveWithoutWordPressIsRefused() throws Exception {
        Path archive = zip(Map.of("other/index.php", "<?php"));
        assertThatThrownBy(() -> WordPressTree.unpack(archive, "c".repeat(64), tmp.resolve("out")))
                .isInstanceOf(IOException.class)
                .hasMessage("the archive " + archive + " holds no wordpress/wp-settings.php");
    }

    @Test
    void tooManyEntriesAreRefused() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            entries.put("wordpress/f" + i + ".txt", "x");
        }
        Path archive = zip(entries);
        assertThatThrownBy(() -> WordPressTree.extract(archive, tmp.resolve("out"), 4, 1000))
                .isInstanceOf(IOException.class)
                .hasMessage("the archive " + archive + " holds more than 4 entries");
    }

    @Test
    void tooMuchUncompressedDataIsRefused() throws Exception {
        Path archive = zip(Map.of("wordpress/big.txt", "x".repeat(5000)));
        assertThatThrownBy(() -> WordPressTree.extract(archive, tmp.resolve("out"), 10, 4999))
                .isInstanceOf(IOException.class)
                .hasMessage("the archive " + archive + " unpacks to more than 4999 bytes");
    }

    @Test
    void twoThreadsUnpackOneAfterTheOther() throws Exception {
        Path archive = zip(release());
        Path out = tmp.resolve("out");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<Path>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> WordPressTree.unpack(archive, "d".repeat(64), out)));
            }
            for (java.util.concurrent.Future<Path> result : results) {
                assertThat(result.get()).isEqualTo(out.resolve("wordpress"));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
