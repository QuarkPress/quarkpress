package org.quarkpress.posts.runtime;

import io.quarkus.runtime.annotations.Recorder;
import java.util.function.Supplier;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;

@Recorder
public class PostsRecorder {

    /** The post types, recorded at build time in their JSON form. */
    public Supplier<PostTypes> postTypes(String json) {
        return () -> new RegisteredPostTypes(RegistryJson.readTypes(json));
    }

    /** The post statuses, recorded at build time in their JSON form. */
    public Supplier<PostStatuses> postStatuses(String json) {
        return () -> new RegisteredPostStatuses(RegistryJson.readStatuses(json));
    }
}
