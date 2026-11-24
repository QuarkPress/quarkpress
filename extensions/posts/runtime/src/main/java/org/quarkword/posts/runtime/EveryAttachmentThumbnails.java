package org.quarkword.posts.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.posts.AttachmentThumbnails;

/** Without a module that knows attachment files, every attachment counts as an image. */
@DefaultBean
@ApplicationScoped
public class EveryAttachmentThumbnails implements AttachmentThumbnails {

    @Override
    public boolean canBeThumbnail(SiteId site, PostId attachment) {
        return true;
    }
}
