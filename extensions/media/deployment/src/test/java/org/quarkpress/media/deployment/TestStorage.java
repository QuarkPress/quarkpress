package org.quarkpress.media.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.MediaStored;
import org.quarkpress.media.StoredKey;
import org.quarkpress.media.WriteMode;

/**
 * The storage of the upload tests, held in memory: files are served under {@code <site url>test-uploads/<key>}, a
 * name segment longer than 255 UTF-8 bytes is refused as a Linux filesystem refuses it, the main site's uploads base
 * (the empty folder key) can be listed, and switches let a test make a file appear between the listing and the write
 * or make listing fail, or run a test's work during each write.
 */
@ApplicationScoped
public class TestStorage implements MediaStorage {

    /** The longest name segment a typical Linux filesystem holds, in bytes. */
    static final int MAX_SEGMENT_BYTES = 255;

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, byte[]> appearBeforeWrite = new ConcurrentHashMap<>();
    private final AtomicInteger appearAlways = new AtomicInteger();
    private volatile boolean listingFails;
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger unpaddedPuts = new AtomicInteger(Integer.MAX_VALUE);
    private volatile int padding;
    private volatile RuntimeException openFailure;
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private volatile RuntimeException deleteFailure;
    private final AtomicBoolean failUrlAfterStore = new AtomicBoolean();
    private final AtomicBoolean failNextUrl = new AtomicBoolean();
    private final AtomicInteger failingUrls = new AtomicInteger();
    private volatile Runnable duringPut;

    /** Removes every file and resets the switches. */
    public void clear() {
        files.clear();
        appearBeforeWrite.clear();
        appearAlways.set(0);
        listingFails = false;
        puts.set(0);
        unpaddedPuts.set(Integer.MAX_VALUE);
        padding = 0;
        openFailure = null;
        deleted.clear();
        deleteFailure = null;
        failUrlAfterStore.set(false);
        failNextUrl.set(false);
        failingUrls.set(0);
        duringPut = null;
    }

    /** Every write first runs {@code work} on the writing thread; null to write at once. */
    public void duringPuts(Runnable work) {
        duringPut = work;
    }

    /**
     * Once the next upload is stored (its snapshot made), the next public URL asked for throws: the first read of the
     * processing run that follows fails, as a database or backend failure there would.
     */
    public void failNextUrlAfterStore() {
        failUrlAfterStore.set(true);
    }

    /** The next {@code times} public URLs asked for throw, as a backend that fails for a while would. */
    public void failUrls(int times) {
        failingUrls.set(times);
    }

    void stored(@Observes MediaStored event) {
        if (failUrlAfterStore.getAndSet(false)) {
            failNextUrl.set(true);
        }
    }

    /** The keys callers asked to delete since the last {@link #clear}, in call order, whether or not they existed. */
    public List<String> deletes() {
        return List.copyOf(deleted);
    }

    /**
     * Every delete throws {@code failure} and removes nothing, as a backend whose client fails would; null to delete.
     */
    public void deleteFails(RuntimeException failure) {
        deleteFailure = failure;
    }

    /** How many files callers stored since the last {@link #clear}, whether or not they were removed again. */
    public int puts() {
        return puts.get();
    }

    /** The next create-exclusive write of {@code key} finds {@code bytes} there, written by another upload. */
    public void appearBeforeWrite(String key, byte[] bytes) {
        appearBeforeWrite.put(key, bytes.clone());
    }

    /** Every create-exclusive write finds its key taken by another upload, at most {@code times} times. */
    public void takeEveryName(int times) {
        appearAlways.set(times);
    }

    /**
     * After the next {@code skipped} writes, every write stores {@code bytes} more bytes than its source holds, as a
     * backend that adds to what it stores would: sizes must then be read from the storage, not from the source.
     */
    public void padWritesAfter(int skipped, int bytes) {
        unpaddedPuts.set(skipped);
        padding = bytes;
    }

    /** Every read throws {@code failure}, as a backend whose client fails would; null to read again. */
    public void openFails(RuntimeException failure) {
        openFailure = failure;
    }

    public void listingFails(boolean fails) {
        listingFails = fails;
    }

    /** The bytes stored under {@code key}. */
    public byte[] read(String key) {
        byte[] bytes = files.get(key);
        if (bytes == null) {
            throw new IllegalStateException("nothing is stored under " + key);
        }
        return bytes.clone();
    }

    /** Every stored key, sorted. */
    public List<String> keys() {
        return new ArrayList<>(new TreeSet<>(files.keySet()));
    }

    @Override
    public void put(String key, Path source, String mimeType, WriteMode mode) throws IOException {
        StoredKey.require(key);
        Runnable work = duringPut;
        if (work != null) {
            work.run();
        }
        for (String segment : key.split("/")) {
            if (segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
                throw new FileSystemException(key, null, "File name too long");
            }
        }
        byte[] bytes = Files.readAllBytes(source);
        if (unpaddedPuts.getAndUpdate(n -> n == Integer.MAX_VALUE ? n : Math.max(-1, n - 1)) <= 0) {
            bytes = Arrays.copyOf(bytes, bytes.length + padding);
        }
        if (mode == WriteMode.CREATE_NEW) {
            byte[] other = appearBeforeWrite.remove(key);
            if (other != null) {
                files.putIfAbsent(key, other);
            }
            if (appearAlways.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                files.putIfAbsent(key, "another upload".getBytes(StandardCharsets.US_ASCII));
            }
            if (files.putIfAbsent(key, bytes) != null) {
                throw new KeyTakenException(key);
            }
        } else {
            files.put(key, bytes);
        }
        puts.incrementAndGet();
    }

    @Override
    public InputStream open(String key) throws IOException {
        RuntimeException failure = openFailure;
        if (failure != null) {
            throw failure;
        }
        byte[] bytes = files.get(StoredKey.require(key));
        if (bytes == null) {
            throw new NoSuchFileException(key);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public boolean exists(String key) {
        return files.containsKey(StoredKey.require(key));
    }

    @Override
    public List<String> list(String folderKey) {
        if (listingFails) {
            throw new UncheckedIOException(new IOException("the test storage cannot be listed"));
        }
        String prefix = folderKey.isEmpty() ? "" : StoredKey.require(folderKey) + "/";
        TreeSet<String> names = new TreeSet<>();
        for (String key : files.keySet()) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public boolean delete(String key) {
        deleted.add(StoredKey.require(key));
        RuntimeException failure = deleteFailure;
        if (failure != null) {
            throw failure;
        }
        return files.remove(key) != null;
    }

    @Override
    public OptionalLong size(String key) {
        byte[] bytes = files.get(StoredKey.require(key));
        return bytes == null ? OptionalLong.empty() : OptionalLong.of(bytes.length);
    }

    @Override
    public String publicUrl(SiteInfo site, String key) {
        if (failingUrls.getAndUpdate(n -> Math.max(0, n - 1)) > 0 || failNextUrl.getAndSet(false)) {
            throw new IllegalStateException("the test storage cannot tell the URL of " + key);
        }
        return site.url("http") + "test-uploads/" + StoredKey.require(key);
    }
}
