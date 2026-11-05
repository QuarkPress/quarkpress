package org.quarkpress.media.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.media.MediaStorage;

/**
 * The storage backend the media module uses: the one present, or the one {@code quarkpress.media.storage} chose when
 * several are. The build fails when there is none, or several and no choice.
 */
@ApplicationScoped
public class ActiveStorage {

    private volatile String name;
    private volatile String beanId;
    private volatile MediaStorage storage;

    /** Records the build's choice: the backend's name and the id of its bean. Called once, by the start-up code. */
    public void choose(String name, String beanId) {
        this.name = name;
        this.beanId = beanId;
    }

    /** The active backend. */
    public MediaStorage storage() {
        MediaStorage current = storage;
        if (current == null) {
            if (beanId == null) {
                throw new IllegalStateException("no media storage backend was chosen at start-up");
            }
            InjectableBean<?> bean = Arc.container().bean(beanId);
            if (bean == null) {
                throw new IllegalStateException("the media storage bean " + beanId + " is not present");
            }
            current = (MediaStorage) Arc.container().instance(bean).get();
            storage = current;
        }
        return current;
    }

    /**
     * The active backend's name: a backend module's name ({@code fs}, {@code s3}), else the bean's
     * {@code @Identifier} value or class simple name.
     */
    public String name() {
        if (name == null) {
            throw new IllegalStateException("no media storage backend was chosen at start-up");
        }
        return name;
    }
}
