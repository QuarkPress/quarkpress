package org.quarkpress.lib.wpblocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fetches Gutenberg's parser fixtures at a pinned commit with a shallow, blob-filtered, sparse git
 * checkout into {@code gutenberg.fixtures.dir}. The checkout is reused while a marker named after the
 * commit exists. Nothing is written outside that directory.
 */
final class GutenbergFixtures {

    static final String REPO = "https://github.com/WordPress/gutenberg.git";
    static final String SUBDIR = "test/integration/fixtures/blocks";

    private GutenbergFixtures() {}

    /** Returns the fixture directory, cloning on first use. Null when the profile is not active. */
    static Path ensure() throws IOException, InterruptedException {
        String dir = System.getProperty("gutenberg.fixtures.dir");
        String commit = System.getProperty("gutenberg.fixtures.commit");
        if (dir == null || commit == null) {
            return null;
        }
        Path root = Path.of(dir);
        Path marker = root.resolve(".complete-" + commit);
        Path fixtures = root.resolve("repo").resolve(SUBDIR);
        if (Files.exists(marker) && Files.isDirectory(fixtures)) {
            return fixtures;
        }
        Path repo = root.resolve("repo");
        deleteRecursively(repo);
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "remote", "add", "origin", REPO);
        git(repo, "sparse-checkout", "set", "--no-cone", SUBDIR);
        git(repo, "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", commit);
        git(repo, "checkout", "-q", commit);
        if (!Files.isDirectory(fixtures)) {
            throw new IOException("fixture directory missing after checkout: " + fixtures);
        }
        Files.createDirectories(root);
        Files.writeString(marker, commit);
        return fixtures;
    }

    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(args.length + 1);
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("git " + String.join(" ", args) + " timed out");
        }
        if (p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (" + p.exitValue() + "):\n" + output);
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                try {
                    Files.delete(q);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
