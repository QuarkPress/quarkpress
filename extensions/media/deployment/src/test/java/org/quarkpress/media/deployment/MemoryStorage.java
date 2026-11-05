package org.quarkpress.media.deployment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.StoredKey;
import org.quarkpress.media.WriteMode;

/** A media storage held in memory, for tests; the test beans extend it. */
public abstract class MemoryStorage implements MediaStorage {

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Path source, String mimeType, WriteMode mode) throws IOException {
        StoredKey.require(key);
        byte[] bytes = Files.readAllBytes(source);
        if (mode == WriteMode.CREATE_NEW) {
            if (files.putIfAbsent(key, bytes) != null) {
                throw new KeyTakenException(key);
            }
        } else {
            files.put(key, bytes);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
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
        String prefix = StoredKey.require(folderKey) + "/";
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
        return files.remove(StoredKey.require(key)) != null;
    }

    @Override
    public OptionalLong size(String key) {
        byte[] bytes = files.get(StoredKey.require(key));
        return bytes == null ? OptionalLong.empty() : OptionalLong.of(bytes.length);
    }

    @Override
    public String publicUrl(SiteInfo site, String key) {
        return site.url("http") + "/media/" + StoredKey.require(key);
    }
}
