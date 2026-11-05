package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.Crop;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.ImageSizeProvider;
import org.quarkpress.media.ImageSizes;

/** A site's image sizes come from its options and from the provider beans, in priority order. */
class ImageSizesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            MediaApp.with(ImageSizesDbTest.class, LowProvider.class, HighProvider.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    ImageSizes sizes;

    @ApplicationScoped
    @Priority(1)
    public static class LowProvider implements ImageSizeProvider {
        @Override
        public List<ImageSizeDefinition> sizes() {
            return List.of(
                    new ImageSizeDefinition("x", 100, 100, new Crop.At("left", "top")),
                    new ImageSizeDefinition("shared", 10, 10, Crop.none()));
        }
    }

    @ApplicationScoped
    @Priority(10)
    public static class HighProvider implements ImageSizeProvider {
        @Override
        public List<ImageSizeDefinition> sizes() {
            return List.of(
                    new ImageSizeDefinition("y", -50, 80, Crop.none()),
                    new ImageSizeDefinition("shared", 20, 20, Crop.hard()));
        }
    }

    private Map<String, ImageSizeDefinition> on(SiteId site) throws Exception {
        return Identities.onSite(site, Identities.system(), sizes::registered);
    }

    @Test
    void aNewSiteHasWordPresssSizesThenTheProviders() throws Exception {
        Map<String, ImageSizeDefinition> registered = on(TestData.site(dataSource));
        assertThat(registered.keySet())
                .containsExactly(
                        "thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048", "y", "shared", "x");
        assertThat(registered.get("thumbnail")).isEqualTo(new ImageSizeDefinition("thumbnail", 150, 150, Crop.hard()));
        assertThat(registered.get("medium_large"))
                .isEqualTo(new ImageSizeDefinition("medium_large", 768, 0, Crop.none()));
        assertThat(registered.get("y")).isEqualTo(new ImageSizeDefinition("y", 50, 80, Crop.none()));
        // the later registration's definition, in the first registration's place
        assertThat(registered.get("shared")).isEqualTo(new ImageSizeDefinition("shared", 10, 10, Crop.none()));
    }

    @Test
    void theSiteOptionsChangeTheOptionSizes() throws Exception {
        SiteId site = TestData.site(dataSource);
        TestData.siteOption(dataSource, site, "medium_size_w", "0");
        TestData.siteOption(dataSource, site, "medium_size_h", "\"0\"");
        TestData.siteOption(dataSource, site, "thumbnail_crop", "\"0\"");
        TestData.siteOption(dataSource, site, "large_size_w", "\"2000\"");
        Map<String, ImageSizeDefinition> registered = on(site);
        assertThat(registered.keySet())
                .containsExactly("thumbnail", "medium_large", "large", "1536x1536", "2048x2048", "y", "shared", "x");
        assertThat(registered.get("thumbnail").crop()).isEqualTo(Crop.none());
        assertThat(registered.get("large")).isEqualTo(new ImageSizeDefinition("large", 2000, 1024, Crop.none()));
        // another site keeps its own options
        assertThat(on(TestData.site(dataSource)).get("large").width()).isEqualTo(1024);
    }
}
