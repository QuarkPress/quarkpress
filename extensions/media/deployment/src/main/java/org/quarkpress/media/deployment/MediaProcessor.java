package org.quarkpress.media.deployment;

import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.media.MediaFeature;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.runtime.ActiveStorage;
import org.quarkpress.media.runtime.DefaultMimeTypes;
import org.quarkpress.media.runtime.MediaBuildTimeConfig;
import org.quarkpress.media.runtime.MediaConfig;
import org.quarkpress.media.runtime.MediaRecorder;

class MediaProcessor {

    static final String FEATURE = "quarkpress-media";

    static final String NO_STORAGE = "QuarkPress media needs a storage backend: add quarkpress-media-storage-fs or"
            + " quarkpress-media-storage-s3, or provide a MediaStorage bean";

    private static final DotName MEDIA_STORAGE = DotName.createSimple(MediaStorage.class);
    private static final DotName IDENTIFIER = DotName.createSimple("io.smallrye.common.annotation.Identifier");
    private static final DotName METADATA_DIRECTORY = DotName.createSimple("com.drew.metadata.Directory");

    /** The storage backend the build chose: its name and its bean's id. */
    static final class ChosenStorageBuildItem extends SimpleBuildItem {

        final String name;
        final String beanId;

        ChosenStorageBuildItem(String name, String beanId) {
            this.name = name;
            this.beanId = beanId;
        }
    }

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(ActiveStorage.class, MediaStorage.class);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("media", 7);
    }

    @BuildStep
    SiteFeatureBuildItem siteFeature() {
        return new SiteFeatureBuildItem(
                MediaFeature.KEY, "media", true, "Uploads, image sizes and the media library of a site");
    }

    @BuildStep
    void configKeys(BuildProducer<QuarkPressConfigKeysBuildItem> keys) {
        keys.produce(QuarkPressConfigKeysBuildItem.of("media", "quarkpress.media", MediaConfig.class));
        keys.produce(QuarkPressConfigKeysBuildItem.of("media", "quarkpress.media", MediaBuildTimeConfig.class));
    }

    /** Tika's type database, which its detector loads from the class path. */
    @BuildStep
    NativeImageResourceBuildItem tikaResources() {
        return new NativeImageResourceBuildItem("org/apache/tika/mime/tika-mimetypes.xml");
    }

    /** WordPress's upload type list, which the media module reads from the class path. */
    @BuildStep
    NativeImageResourceBuildItem uploadTypeResources() {
        return new NativeImageResourceBuildItem(DefaultMimeTypes.RESOURCE);
    }

    /** metadata-extractor's classes, so its directory classes can be found below. */
    @BuildStep
    IndexDependencyBuildItem metadataExtractorIndex() {
        return new IndexDependencyBuildItem("com.drewnoakes", "metadata-extractor");
    }

    /**
     * The constructors of every concrete metadata-extractor directory class: its TIFF reader (behind EXIF and the
     * maker notes) creates directories from their class with {@code Class.newInstance}, which a native image only
     * allows for registered constructors.
     */
    @BuildStep
    ReflectiveClassBuildItem metadataExtractorReflection(CombinedIndexBuildItem index) {
        return ReflectiveClassBuildItem.builder(
                        metadataDirectories(index.getIndex()).toArray(String[]::new))
                .constructors()
                .methods(false)
                .fields(false)
                .build();
    }

    /** The names of the concrete subclasses of metadata-extractor's {@code Directory} in {@code index}, sorted. */
    static List<String> metadataDirectories(IndexView index) {
        TreeSet<String> names = new TreeSet<>();
        for (ClassInfo subclass : index.getAllKnownSubclasses(METADATA_DIRECTORY)) {
            if (!subclass.isAbstract() && !subclass.isInterface()) {
                names.add(subclass.name().toString());
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * Fails the build without a storage backend, or with several and no {@code quarkpress.media.storage} naming one;
     * otherwise records the one to use. Backend modules count by their build item's name, other {@code MediaStorage}
     * beans by their {@code @Identifier} value or class simple name; two beans under one name fail the build too.
     */
    @BuildStep
    void chooseStorage(
            List<MediaStorageBuildItem> backends,
            BeanDiscoveryFinishedBuildItem beans,
            MediaBuildTimeConfig config,
            BuildProducer<ChosenStorageBuildItem> chosen,
            BuildProducer<ValidationErrorBuildItem> errors) {
        Map<String, List<BeanInfo>> byName = new TreeMap<>();
        for (BeanInfo bean : beans.beanStream().withBeanType(MEDIA_STORAGE)) {
            byName.computeIfAbsent(storageName(bean), key -> new ArrayList<>()).add(bean);
        }
        Map<String, String> beanIds = new TreeMap<>();
        boolean sharedNames = false;
        for (Map.Entry<String, List<BeanInfo>> entry : byName.entrySet()) {
            if (entry.getValue().size() > 1) {
                TreeSet<String> classes = new TreeSet<>();
                for (BeanInfo bean : entry.getValue()) {
                    classes.add(storageClass(bean));
                }
                errors.produce(new ValidationErrorBuildItem(
                        new IllegalStateException("Several media storage backends are named " + entry.getKey() + " ("
                                + String.join(", ", classes) + "): give each its own @Identifier")));
                sharedNames = true;
            } else {
                beanIds.put(entry.getKey(), entry.getValue().get(0).getIdentifier());
            }
        }
        if (sharedNames) {
            return;
        }
        TreeSet<String> names = new TreeSet<>(beanIds.keySet());
        for (MediaStorageBuildItem backend : backends) {
            names.add(backend.name());
        }
        Optional<String> name = choose(names, config.storage());
        if (name.isEmpty()) {
            errors.produce(new ValidationErrorBuildItem(new IllegalStateException(refusal(names, config.storage()))));
            return;
        }
        String beanId = beanIds.get(name.get());
        if (beanId == null) {
            errors.produce(new ValidationErrorBuildItem(new IllegalStateException("The media storage backend "
                    + name.get() + " provides no MediaStorage bean with @Identifier(\"" + name.get() + "\")")));
            return;
        }
        chosen.produce(new ChosenStorageBuildItem(name.get(), beanId));
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void recordStorage(
            Optional<ChosenStorageBuildItem> chosen, MediaRecorder recorder, BeanContainerBuildItem container) {
        chosen.ifPresent(storage -> recorder.chooseStorage(container.getValue(), storage.name, storage.beanId));
    }

    /** The backend to use among {@code names}: the configured one when it is present, else the only one. */
    static Optional<String> choose(TreeSet<String> names, Optional<String> configured) {
        if (configured.isPresent()) {
            return names.contains(configured.get()) ? configured : Optional.empty();
        }
        return names.size() == 1 ? Optional.of(names.first()) : Optional.empty();
    }

    /** Why {@link #choose} found no backend. */
    static String refusal(TreeSet<String> names, Optional<String> configured) {
        if (names.isEmpty()) {
            return NO_STORAGE;
        }
        String present = String.join(", ", names);
        if (configured.isPresent()) {
            return "quarkpress.media.storage names " + configured.get()
                    + ", which is not one of the media storage backends present (" + present + ")";
        }
        return "Several media storage backends are present (" + present + "): choose one with quarkpress.media.storage";
    }

    /** A storage bean's name: its {@code @Identifier} value, else its class's simple name. */
    private static String storageName(BeanInfo bean) {
        for (AnnotationInstance qualifier : bean.getQualifiers()) {
            if (qualifier.name().equals(IDENTIFIER) && qualifier.value() != null) {
                return qualifier.value().asString();
            }
        }
        String name = storageClass(bean);
        int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        return name.substring(cut + 1);
    }

    /** The implementation class of a storage bean, by its full name. */
    private static String storageClass(BeanInfo bean) {
        ClassInfo type = bean.getImplClazz();
        return type != null ? type.name().toString() : bean.getBeanClass().toString();
    }
}
