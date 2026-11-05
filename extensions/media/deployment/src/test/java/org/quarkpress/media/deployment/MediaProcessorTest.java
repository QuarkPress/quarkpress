package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.drew.metadata.Directory;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

/** The media build steps that need no application. */
class MediaProcessorTest {

    @Test
    void tikaResourcesAreIncludedInTheNativeImage() {
        assertThat(new MediaProcessor().tikaResources().getResources())
                .contains("org/apache/tika/mime/tika-mimetypes.xml");
        assertThat(MediaProcessorTest.class.getClassLoader().getResource("org/apache/tika/mime/tika-mimetypes.xml"))
                .isNotNull();
    }

    @Test
    void metadataExtractorIsIndexed() {
        var item = new MediaProcessor().metadataExtractorIndex();
        assertThat(item.getGroupId()).isEqualTo("com.drewnoakes");
        assertThat(item.getArtifactId()).isEqualTo("metadata-extractor");
    }

    @Test
    void metadataExtractorDirectoriesAreRegisteredForReflection() throws Exception {
        Index index = metadataExtractorIndex();
        ReflectiveClassBuildItem item =
                new MediaProcessor().metadataExtractorReflection(new CombinedIndexBuildItem(index, index));
        assertThat(item.getClassNames())
                .contains(
                        "com.drew.metadata.exif.ExifIFD0Directory",
                        "com.drew.metadata.exif.ExifSubIFDDirectory",
                        "com.drew.metadata.iptc.IptcDirectory",
                        "com.drew.metadata.xmp.XmpDirectory",
                        "com.drew.metadata.icc.IccDirectory",
                        "com.drew.metadata.mp4.Mp4Directory")
                .doesNotContain("com.drew.metadata.Directory", "com.drew.metadata.exif.ExifDirectoryBase");
        ClassLoader loader = MediaProcessorTest.class.getClassLoader();
        for (String name : item.getClassNames()) {
            Class<?> type = Class.forName(name, false, loader);
            assertThat(Directory.class.isAssignableFrom(type)).as(name).isTrue();
            assertThat(Modifier.isAbstract(type.getModifiers())).as(name).isFalse();
        }
        assertThat(item.isConstructors()).isTrue();
        assertThat(item.isMethods()).isFalse();
        assertThat(item.isFields()).isFalse();
    }

    @Test
    void everyDirectoryWithANoArgConstructorIsRegistered() throws Exception {
        // metadata-extractor creates directories through their no-arg constructors; the spike counted 90 of them
        Index index = metadataExtractorIndex();
        var names = MediaProcessor.metadataDirectories(index);
        ClassLoader loader = MediaProcessorTest.class.getClassLoader();
        long withNoArg = 0;
        for (var info : index.getKnownClasses()) {
            Class<?> type = Class.forName(info.name().toString(), false, loader);
            if (Directory.class.isAssignableFrom(type)
                    && !Modifier.isAbstract(type.getModifiers())
                    && hasNoArgConstructor(type)) {
                withNoArg++;
                assertThat(names).as(type.getName()).contains(type.getName());
            }
        }
        assertThat(withNoArg).isGreaterThanOrEqualTo(90);
    }

    @Test
    void theMigrationsAreBandSeven() {
        assertThat(new MediaProcessor().migrations().band()).isEqualTo(7);
    }

    private static boolean hasNoArgConstructor(Class<?> type) {
        try {
            type.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Index metadataExtractorIndex() throws IOException, URISyntaxException {
        Path jar = Path.of(Directory.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Indexer indexer = new Indexer();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        indexer.index(in);
                    }
                }
            }
        }
        return indexer.complete();
    }
}
