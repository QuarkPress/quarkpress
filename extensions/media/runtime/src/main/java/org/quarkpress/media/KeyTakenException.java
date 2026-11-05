package org.quarkpress.media;

import java.nio.file.FileAlreadyExistsException;

/** A {@link WriteMode#CREATE_NEW} write found its key already holding a file; that file was not replaced. */
public class KeyTakenException extends FileAlreadyExistsException {

    private static final long serialVersionUID = 1L;

    public KeyTakenException(String key) {
        super(key);
    }

    /** The storage key that was taken. */
    public String key() {
        return getFile();
    }
}
