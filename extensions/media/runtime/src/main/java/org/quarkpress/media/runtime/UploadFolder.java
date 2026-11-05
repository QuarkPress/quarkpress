package org.quarkpress.media.runtime;

import java.util.List;

/** The folder an upload is stored in, as the choice of a unique file name sees it. */
public interface UploadFolder {

    /** Whether a file or folder named exactly {@code name} (case-sensitively) is in this folder. */
    boolean exists(String name);

    /** The names of the folder's entries (files and folders); empty when the folder does not exist. */
    List<String> entries();
}
