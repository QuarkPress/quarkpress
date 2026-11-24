package org.quarkword.core.deployment;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.Map;
import java.util.function.Consumer;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;

/** Test applications whose only QuarkWord settings are those of {@link Settings}, which holds a map. */
final class MapConfigKeys {

    private MapConfigKeys() {}

    /** The test mapping under {@code quarkword.x}: {@code m} is a map, {@code plain} a plain setting. */
    interface Settings {
        Map<String, String> m();

        String plain();
    }

    static QuarkusExtensionTest app(Class<?> test) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(test, MapConfigKeys.class, Settings.class, Failures.class))
                .addBuildChainCustomizer(declare());
    }

    private static Consumer<BuildChainBuilder> declare() {
        return builder -> builder.addBuildStep(new BuildStep() {
                    @Override
                    public void execute(BuildContext context) {
                        context.produce(QuarkWordConfigKeysBuildItem.of("x", "quarkword.x", Settings.class));
                    }
                })
                .produces(QuarkWordConfigKeysBuildItem.class)
                .build();
    }
}
