package org.quarkword.front.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteContext;
import org.quarkword.media.Media;
import org.quarkword.media.MediaFeature;
import org.quarkword.media.MediaSnapshot;

/** The attachment files of the media module; left out of the application without that module. */
@ApplicationScoped
public class MediaAttachmentFiles implements AttachmentFiles {

    @Inject
    Media media;

    @Inject
    SiteContext site;

    @Override
    public Optional<String> url(PostId attachment) {
        if (!site.features().isEnabled(MediaFeature.KEY)) {
            return Optional.empty();
        }
        return media.read(attachment).map(found -> found.url(MediaSnapshot.FULL));
    }
}
