package org.quarkpress.media.image.vips.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.runtime.NoImageProcessor;

/**
 * The image processor that handles the types libvips does not: the application's other image processor bean (that of
 * the ImageIO module, when installed). Looked up among all beans of the type, not resolved: resolution would only ever
 * give the libvips processor, which takes precedence.
 */
final class Fallback {

    private Fallback() {}

    /**
     * The first other image processor bean by class name; never the libvips processor, nor the media module's own,
     * which handles no type.
     */
    @SuppressWarnings("unchecked")
    static Optional<ImageProcessor> of(BeanManager beans) {
        List<Bean<?>> others = new ArrayList<>();
        for (Bean<?> bean : beans.getBeans(ImageProcessor.class, Any.Literal.INSTANCE)) {
            Class<?> type = bean.getBeanClass();
            if (!VipsImageProcessor.class.isAssignableFrom(type) && !NoImageProcessor.class.isAssignableFrom(type)) {
                others.add(bean);
            }
        }
        return others.stream()
                .min(Comparator.comparing(bean -> bean.getBeanClass().getName()))
                .map(bean -> Arc.container()
                        .instance((InjectableBean<ImageProcessor>) bean)
                        .get());
    }
}
