package org.quarkpress.posts.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.AttachmentThumbnails;

/** Without a module that knows attachment files, every attachment counts as an image. */
@DefaultBean
@ApplicationScoped
public class EveryAttachmentThumbnails implements AttachmentThumbnails {

    @Override
    public boolean canBeThumbnail(SiteId site, PostId attachment) {
        return true;
    }
}
