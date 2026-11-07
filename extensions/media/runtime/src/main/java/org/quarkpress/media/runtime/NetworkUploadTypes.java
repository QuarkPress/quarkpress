package org.quarkpress.media.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.security.runtime.InstallMode;

/** The network's list of upload types, which narrows every user's types on a network. */
@ApplicationScoped
public class NetworkUploadTypes {

    /** The list WordPress uses when the network has none. */
    static final String FALLBACK = "jpg jpeg png gif";

    @Inject
    InstallMode installMode;

    @Inject
    NetworkOptions network;

    /**
     * The network option {@code upload_filetypes}, space-separated extensions, as PHP prints it ({@code jpg jpeg png
     * gif} when unset or not a text, number or boolean); empty on a single site, which has no such list.
     */
    public Optional<String> list() {
        if (!installMode.multisite()) {
            return Optional.empty();
        }
        return Optional.of(
                network.find("upload_filetypes").map(PhpOptionStrings::of).orElse(FALLBACK));
    }
}
