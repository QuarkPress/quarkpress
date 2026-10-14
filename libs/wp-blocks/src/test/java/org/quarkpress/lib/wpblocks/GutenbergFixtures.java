package org.quarkpress.lib.wpblocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    static final long GIT_TIMEOUT_MINUTES = 10;

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
        git(root, repo, "init", "-q");
        git(root, repo, "remote", "add", "origin", REPO);
        git(root, repo, "sparse-checkout", "set", "--no-cone", SUBDIR);
        git(root, repo, "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", commit);
        git(root, repo, "checkout", "-q", commit);
        if (!Files.isDirectory(fixtures)) {
            throw new IOException("fixture directory missing after checkout: " + fixtures);
        }
        Files.createDirectories(root);
        Files.writeString(marker, commit);
        return fixtures;
    }

    /**
     * Runs one git step with its merged output in {@code root/git-<step>.log}. The output goes to a file
     * rather than a pipe so that the timeout applies even when git hangs without closing its output.
     */
    private static void git(Path root, Path cwd, String... args) throws IOException, InterruptedException {
        String step = args[0];
        Path log = root.resolve("git-" + step + ".log");
        List<String> cmd = new java.util.ArrayList<>(args.length + 1);
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        if (!p.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("git " + step + " timed out after " + GIT_TIMEOUT_MINUTES + " minutes; log " + log
                    + " ends with:\n" + tail(log));
        }
        if (p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (" + p.exitValue() + "), log " + log
                    + ":\n" + Files.readString(log, StandardCharsets.UTF_8));
        }
    }

    private static String tail(Path log) {
        try {
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            return text.length() <= 4000 ? text : text.substring(text.length() - 4000);
        } catch (IOException e) {
            return "(log unreadable: " + e.getMessage() + ")";
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
