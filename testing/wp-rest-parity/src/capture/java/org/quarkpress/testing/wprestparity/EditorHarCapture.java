package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.HarContentPolicy;
import com.microsoft.playwright.options.HarMode;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * Records the block editor's network traffic against the live WordPress site as HAR files, one per role and flow, in
 * {@code <har.dir>/<role>-<flow>.har}. Manual only (profile {@code capture-har}), never in CI.
 *
 * <p>The harness's router presents every request as one to {@code localhost:80}, so every absolute URL WordPress emits
 * names a port nothing listens on. Each browser context therefore forwards {@code http://localhost/**} to the server's
 * real address: the browser keeps WordPress's URLs and cookies, and the HAR records them. Everything runs on the main
 * site: under the router a subdirectory site's {@code wp-admin} and {@code wp-login.php} do not work. Saves, autosaves,
 * scheduling and publishing go through the editor's own data stores where clicking would depend on unstable markup;
 * the requests are the editor's own either way.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class EditorHarCapture {

    /** Role name (the HAR file's prefix) to the dev realm user that has it; users log in with their name. */
    static final Map<String, String> ROLES = new LinkedHashMap<>();

    static {
        ROLES.put("administrator", "alice");
        ROLES.put("author", "carol");
    }

    static final String BROWSER_ROOT = "http://localhost";
    static final double TIMEOUT_MS = 120_000;
    /** A 4000x3000 upload: the browser or the server makes every sub-size. */
    static final double UPLOAD_TIMEOUT_MS = 300_000;
    /** Only REST and admin-ajax traffic is recorded: pages, scripts and styles would make each HAR 20-40 MB. */
    static final Pattern RECORDED =
            Pattern.compile("^http://localhost/(wp-json/.*|.*[?&]rest_route=.*|wp-admin/admin-ajax\\.php.*)$");
    /** Everything WordPress serves (the unfiltered HAR); the forwarded fetches to 127.0.0.1 stay out. */
    static final Pattern ALL_OF_WORDPRESS = Pattern.compile("^http://localhost/.*$");
    /** How long the network must stay quiet before a flow counts as finished. */
    static final Duration QUIET = Duration.ofMillis(1500);

    static ScenarioDatabase database;
    static WordPressRestSite site;
    static RestClient client;
    static Playwright playwright;
    static Browser browser;
    static Path harDir;
    static Path image;
    /** A 4000x3000 JPEG (EXIF orientation 1): larger than every image size and the big-image threshold. */
    static Path largeImage;
    /** The capture-only must-use plugin (see {@link #installServerMediaSwitch}); removed after the run. */
    static Path muPlugin;

    static boolean createdMuPlugins;
    static final AtomicInteger FIXTURES = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        Path tree = WordPressTree.ensure(System.getProperty("wp.version"));
        installServerMediaSwitch(tree);
        site = WordPressRestSite.start(tree, database, System.getProperty("wp.php", "php"));
        client = new RestClient(site.root(), RestClient.applicationPasswords(site));
        harDir = Path.of(System.getProperty("har.dir", "target/har")).toAbsolutePath();
        Files.createDirectories(harDir);
        image = writeImage();
        largeImage = writeLargeJpeg();
        // The inserter's Media tab and the media modal list what the library holds: one image, uploaded as alice.
        uploadThroughRest("alice");
        playwright = Playwright.create();
        // A redirect answered through route.fulfill is followed by the browser without the route, so the redirect's
        // next hop (the login's 302 to /wp-admin/) would go to port 80. The resolver rule sends those hops to the
        // server too; every other request still goes through the forwarding route.
        browser = playwright
                .chromium()
                .launch(new BrowserType.LaunchOptions()
                        .setArgs(List.of("--host-resolver-rules=MAP localhost:80 "
                                + site.root().getHost() + ":" + site.root().getPort())));
        checkAdminAndRest();
    }

    @AfterAll
    static void stop() {
        try {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        } finally {
            try {
                if (site != null) {
                    site.close();
                }
                if (database != null) {
                    database.close();
                }
            } finally {
                removeServerMediaSwitch();
            }
        }
    }

    /**
     * Harness rule, capture only: writes a must-use plugin into the WordPress tree that turns client-side media
     * processing off (filter {@code wp_client_side_media_processing_enabled}) for requests carrying the cookie
     * {@value #SERVER_MEDIA_COOKIE}=1, so the server-side upload path can be recorded although localhost is a secure
     * context. Requests without the cookie, and so every Part D or parity run, are untouched; the file is removed after
     * the run.
     */
    static void installServerMediaSwitch(Path tree) throws IOException {
        Path dir = tree.resolve("wp-content").resolve("mu-plugins");
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            createdMuPlugins = true;
        }
        muPlugin = dir.resolve("qp-har-server-media.php");
        Files.writeString(
                muPlugin,
                "<?php\n"
                        + "/*\n"
                        + " * Harness rule, not WordPress behaviour: written by the REST parity harness's HAR capture\n"
                        + " * (EditorHarCapture) and removed after it. A request with the cookie "
                        + SERVER_MEDIA_COOKIE + "=1 gets the\n"
                        + " * server-side media pipeline (client-side media processing off); other requests are untouched.\n"
                        + " */\n"
                        + "if (isset($_COOKIE['" + SERVER_MEDIA_COOKIE + "']) && '1' === $_COOKIE['"
                        + SERVER_MEDIA_COOKIE + "']) {\n"
                        + "    add_filter('wp_client_side_media_processing_enabled', '__return_false');\n"
                        + "}\n");
    }

    static void removeServerMediaSwitch() {
        try {
            if (muPlugin != null) {
                Files.deleteIfExists(muPlugin);
                if (createdMuPlugins) {
                    Files.deleteIfExists(muPlugin.getParent());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot remove " + muPlugin, e);
        }
    }

    static final String SERVER_MEDIA_COOKIE = "qp_har_server_media";

    @Test
    void newPost() throws Exception {
        capture("new-post", (page, user) -> openNew(page));
    }

    @Test
    void openPost() throws Exception {
        capture("open-post", (page, user) -> openExisting(page, createPost(user, "draft")));
    }

    /**
     * The one HAR without the REST/admin-ajax filter: the administrator opening an existing post, so the editor page
     * itself (with its preload list) and its scripts stay reproducible.
     */
    @Test
    void openPostUnfiltered() throws Exception {
        captureOne(
                "administrator-open-post-unfiltered",
                "alice",
                false,
                false,
                (page, user) -> openExisting(page, createPost(user, "draft")));
    }

    @Test
    void autosave() throws Exception {
        capture("autosave", (page, user) -> {
            // A draft of the user's own (the autosave updates the post itself) and a published post (a revision).
            for (String status : List.of("draft", "publish")) {
                openExisting(page, createPost(user, status));
                addParagraph(page, "Autosaved text of a " + status + " post.");
                page.evaluate("async () => { await wp.data.dispatch('core/editor').autosave(); }");
                awaitQuiet(page);
            }
        });
    }

    @Test
    void saveDraft() throws Exception {
        capture("save-draft", (page, user) -> {
            openNew(page);
            editTitle(page, "HAR draft");
            addParagraph(page, "First draft.");
            savePost(page);
            addParagraph(page, "Second save of the same draft.");
            savePost(page);
        });
    }

    @Test
    void preview() throws Exception {
        capture("preview", (page, user) -> {
            // A new draft (a full save) and a published post (an autosave with a preview link).
            openNew(page);
            editTitle(page, "HAR preview draft");
            addParagraph(page, "Previewed draft.");
            previewIn(page);
            openExisting(page, createPost(user, "publish"));
            addParagraph(page, "Previewed change of a published post.");
            previewIn(page);
        });
    }

    @Test
    void schedule() throws Exception {
        capture("schedule", (page, user) -> {
            openNew(page);
            editTitle(page, "HAR scheduled post");
            addParagraph(page, "Scheduled.");
            String future = LocalDateTime.ofInstant(Instant.now().plus(7, ChronoUnit.DAYS), ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            page.evaluate("date => wp.data.dispatch('core/editor').editPost({ status: 'future', date })", future);
            savePost(page);
        });
    }

    @Test
    void publish() throws Exception {
        capture("publish", (page, user) -> {
            openNew(page);
            closeWelcomeGuide(page);
            editTitle(page, "HAR published post");
            addParagraph(page, "Published.");
            // The header's Publish button opens the pre-publish panel, whose checks probe tags and categories.
            page.locator(".editor-post-publish-panel__toggle").first().click();
            page.locator(".editor-post-publish-panel").first().waitFor();
            awaitQuiet(page);
            page.evaluate("() => wp.data.dispatch('core/editor').editPost({ status: 'publish' })");
            savePost(page);
            addParagraph(page, "An update of the published post.");
            savePost(page);
        });
    }

    @Test
    void imageUpload() throws Exception {
        // localhost is a secure context, so WordPress 7.1 makes the sub-sizes in the browser (client-side path).
        capture("image-upload", (page, user) -> uploadInImageBlock(page, false));
    }

    @Test
    void imageUploadServerSide() throws Exception {
        // Client-side processing off through the capture's must-use plugin: the server makes the sub-sizes.
        capture("image-upload-server-side", true, (page, user) -> uploadInImageBlock(page, true));
    }

    @Test
    void inserterMedia() throws Exception {
        capture("inserter-media", (page, user) -> openInserterImages(page, false));
    }

    @Test
    void inserterMediaServerSide() throws Exception {
        capture("inserter-media-server-side", true, (page, user) -> openInserterImages(page, true));
    }

    @Test
    void mediaModal() throws Exception {
        capture("media-modal", (page, user) -> {
            openNew(page);
            closeWelcomeGuide(page);
            insertImageBlock(page);
            canvas(page)
                    .getByRole(AriaRole.BUTTON, new FrameLocator.GetByRoleOptions().setName("Media Library"))
                    .first()
                    .click();
            page.locator(".media-modal").first().waitFor();
            awaitQuiet(page);
            page.keyboard().press("Escape");
            awaitQuiet(page);
        });
    }

    @Test
    void reusableBlock() throws Exception {
        capture("reusable-block", (page, user) -> {
            openNew(page);
            editTitle(page, "HAR reusable block");
            // Create a synced pattern as the patterns store does, insert it, save the post.
            Object ref = page.evaluate(
                    "async () => { const r = await wp.data.dispatch('core').saveEntityRecord('postType', 'wp_block',"
                            + " { title: 'HAR pattern', status: 'publish',"
                            + " content: '<!-- wp:paragraph -->\\n<p>Pattern text.</p>\\n<!-- /wp:paragraph -->' });"
                            + " return r.id; }");
            page.evaluate(
                    "ref => wp.data.dispatch('core/block-editor')"
                            + ".insertBlocks(wp.blocks.createBlock('core/block', { ref }))",
                    ref);
            awaitQuiet(page);
            savePost(page);
            // Edit the pattern in place and save it as the "entities saved" panel does: one record at a time.
            page.evaluate(
                    "async ref => { wp.data.dispatch('core').editEntityRecord('postType', 'wp_block', ref,"
                            + " { content: '<!-- wp:paragraph -->\\n<p>Edited pattern text.</p>\\n"
                            + "<!-- /wp:paragraph -->' });"
                            + " await wp.data.dispatch('core').saveEditedEntityRecord('postType', 'wp_block', ref); }",
                    ref);
            awaitQuiet(page);
            // A second post that inserts the existing pattern.
            openNew(page);
            page.evaluate(
                    "ref => wp.data.dispatch('core/block-editor')"
                            + ".insertBlocks(wp.blocks.createBlock('core/block', { ref }))",
                    ref);
            awaitQuiet(page);
            savePost(page);
        });
    }

    @Test
    void linkSearch() throws Exception {
        capture("link-search", (page, user) -> {
            createPost(user, "publish");
            openNew(page);
            // The link control's fetcher: the initial suggestions, then a search.
            page.evaluate("async () => { const fetch = wp.data.select('core/block-editor').getSettings()"
                    + ".__experimentalFetchLinkSuggestions;"
                    + " await fetch('', { isInitialSuggestions: true });"
                    + " await fetch('HAR', { isInitialSuggestions: false }); }");
            awaitQuiet(page);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Uploads the large JPEG through the Image block's file input, then saves the post. */
    static void uploadInImageBlock(Page page, boolean serverSide) {
        openNew(page);
        assertMediaPath(page, serverSide);
        editTitle(page, "HAR image upload");
        insertImageBlock(page);
        Response upload = page.waitForResponse(
                r -> r.request().method().equals("POST") && r.url().contains("/wp-json/wp/v2/media?"),
                new Page.WaitForResponseOptions().setTimeout(UPLOAD_TIMEOUT_MS),
                () -> canvas(page)
                        .locator(".wp-block-image input[type=file], .block-editor-media-placeholder input[type=file]")
                        .first()
                        .setInputFiles(largeImage));
        if (!serverSide) {
            assertThat(upload.status())
                    .as("client-side upload on %s: %s", page.url(), upload.text())
                    .isBetween(200, 299);
        } else if (upload.status() >= 300) {
            // Only the server-side path may be refused: a PHP without an image editor for JPEG (no GD, no Imagick)
            // answers 400 rest_upload_image_type_not_supported, and the HAR keeps that refusal.
            System.err.println("upload refused on " + page.url() + ": " + upload.status() + " " + upload.text());
            awaitQuiet(page);
            return;
        }
        page.waitForFunction(
                "() => wp.data.select('core/block-editor').getBlocks()"
                        + ".some(b => b.name === 'core/image' && b.attributes.id && !/^blob:/.test(b.attributes.url))",
                null,
                new Page.WaitForFunctionOptions().setTimeout(UPLOAD_TIMEOUT_MS));
        awaitQuiet(page);
        savePost(page);
    }

    /** Opens the inserter's Media tab and its Images category. */
    static void openInserterImages(Page page, boolean serverSide) {
        openNew(page);
        assertMediaPath(page, serverSide);
        closeWelcomeGuide(page);
        page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened({ tab: 'media' })");
        awaitQuiet(page);
        // The Images category lists the library's images; its label is the only markup this flow depends on.
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Images"))
                .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Images")))
                .first()
                .click();
        awaitQuiet(page);
    }

    /** The editor page runs the media path the flow names: client-side processing on, or off through the switch. */
    static void assertMediaPath(Page page, boolean serverSide) {
        Object clientSide = page.evaluate("() => !!window.__clientSideMediaProcessing");
        assertThat(clientSide)
                .as("window.__clientSideMediaProcessing on %s", page.url())
                .isEqualTo(!serverSide);
    }

    @FunctionalInterface
    interface Flow {
        void run(Page page, String user) throws Exception;
    }

    /** Runs one flow per role, each in its own recording context, and checks each HAR. */
    static void capture(String flow, Flow body) throws Exception {
        capture(flow, false, body);
    }

    /**
     * Runs one flow per role; with {@code serverSideMedia} the context carries the cookie that turns client-side media
     * processing off.
     */
    static void capture(String flow, boolean serverSideMedia, Flow body) throws Exception {
        for (Map.Entry<String, String> role : ROLES.entrySet()) {
            captureOne(role.getKey() + "-" + flow, role.getValue(), true, serverSideMedia, body);
        }
    }

    /**
     * Runs one flow as one user into {@code <har.dir>/<name>.har}; {@code filtered} keeps only REST and admin-ajax
     * traffic.
     */
    static void captureOne(String name, String user, boolean filtered, boolean serverSideMedia, Flow body)
            throws Exception {
        Path har = harDir.resolve(name + ".har");
        Files.deleteIfExists(har);
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setRecordHarPath(har)
                .setRecordHarContent(HarContentPolicy.EMBED)
                .setRecordHarMode(HarMode.FULL);
        options.setRecordHarUrlFilter(filtered ? RECORDED : ALL_OF_WORDPRESS);
        BrowserContext context = browser.newContext(options);
        if (serverSideMedia) {
            context.addCookies(List.of(new Cookie(SERVER_MEDIA_COOKIE, "1").setUrl(BROWSER_ROOT + "/")));
        }
        try {
            Page page = loggedIn(context, user);
            body.run(page, user);
            awaitQuiet(page);
        } finally {
            context.close();
        }
        assertThat(har).as("HAR of %s", har.getFileName()).exists();
        assertThat(restRequests(har))
                .as("REST requests in %s", har.getFileName())
                .isPositive();
    }

    /** A context that forwards WordPress's {@code http://localhost/...} URLs to the server's real address. */
    static void forward(BrowserContext context) {
        String target = site.root().getScheme() + "://" + site.root().getHost() + ":"
                + site.root().getPort();
        context.setDefaultTimeout(TIMEOUT_MS);
        context.setDefaultNavigationTimeout(TIMEOUT_MS);
        context.route(BROWSER_ROOT + "/**", route -> {
            String url = route.request().url();
            String real = target + url.substring(BROWSER_ROOT.length());
            try {
                route.fulfill(new Route.FulfillOptions()
                        .setResponse(route.fetch(new Route.FetchOptions()
                                .setUrl(real)
                                .setMaxRedirects(0)
                                .setTimeout(TIMEOUT_MS))));
            } catch (PlaywrightException e) {
                System.err.println("forwarding " + url + " to " + real + " failed: " + e.getMessage());
                try {
                    route.abort();
                } catch (PlaywrightException closed) {
                    // The context is closing; nothing is waiting for this request any more.
                }
            }
        });
        context.onPage(EditorHarCapture::track);
    }

    /** Each page's requests in flight, for {@link #awaitQuiet}. */
    static final Map<Page, Set<Request>> PENDING = new ConcurrentHashMap<>();

    /**
     * Tracks the page's requests in flight. A main-frame navigation abandons the previous document's requests (the
     * browser does not always report them as failed), so it empties the set.
     */
    static void track(Page page) {
        Set<Request> pending = ConcurrentHashMap.newKeySet();
        PENDING.put(page, pending);
        page.onRequest(r -> {
            if (r.url().startsWith("http")) {
                pending.add(r);
            }
        });
        page.onRequestFinished(pending::remove);
        page.onRequestFailed(pending::remove);
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                pending.clear();
            }
        });
        page.onClose(closed -> PENDING.remove(closed));
    }

    /** Logs the user in (users' passwords are their names) and answers the page, now on the dashboard. */
    static Page loggedIn(BrowserContext context, String user) {
        forward(context);
        Page page = context.newPage();
        page.navigate(BROWSER_ROOT + "/wp-login.php");
        page.locator("#user_login").fill(user);
        page.locator("#user_pass").fill(user);
        page.locator("#wp-submit").click();
        page.waitForURL(BROWSER_ROOT + "/wp-admin/**");
        return page;
    }

    /**
     * Before any recording: alice reaches the dashboard through the forwarding route, and one editor REST call with the
     * page's REST nonce answers 200. Fails with the URL that broke otherwise.
     */
    static void checkAdminAndRest() {
        try (BrowserContext context = browser.newContext()) {
            forward(context);
            Page page = context.newPage();
            String login = BROWSER_ROOT + "/wp-login.php";
            Response loginPage = page.navigate(login);
            assertThat(loginPage == null ? 0 : loginPage.status())
                    .as("GET %s", login)
                    .isEqualTo(200);
            page.locator("#user_login").fill("alice");
            page.locator("#user_pass").fill("alice");
            page.locator("#wp-submit").click();
            page.waitForLoadState();
            String dashboard = BROWSER_ROOT + "/wp-admin/";
            Response admin = page.navigate(dashboard);
            assertThat(admin == null ? 0 : admin.status())
                    .as("GET %s (after logging in, the page was %s)", dashboard, page.url())
                    .isEqualTo(200);
            assertThat(page.locator("#wpadminbar").count())
                    .as("#wpadminbar on %s", page.url())
                    .isPositive();
            String types = BROWSER_ROOT + "/wp-json/wp/v2/types?context=edit";
            if (!Boolean.TRUE.equals(page.evaluate("() => !!(window.wpApiSettings && wpApiSettings.nonce)"))) {
                // The dashboard does not always load the REST settings; the editor always does.
                page.navigate(BROWSER_ROOT + "/wp-admin/post-new.php");
            }
            Object status = page.evaluate(
                    "url => fetch(url, { headers: { 'X-WP-Nonce': wpApiSettings.nonce } }).then(r => r.status)", types);
            assertThat(((Number) status).intValue())
                    .as("GET %s with the page's REST nonce", types)
                    .isEqualTo(200);
        }
    }

    static void openNew(Page page) {
        page.navigate(BROWSER_ROOT + "/wp-admin/post-new.php");
        awaitEditor(page);
    }

    static void openExisting(Page page, long id) {
        page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + id + "&action=edit");
        awaitEditor(page);
    }

    static void awaitEditor(Page page) {
        page.waitForFunction(
                "() => window.wp && wp.data && wp.blocks && wp.data.select('core/editor')"
                        + " && wp.data.select('core/editor').getCurrentPostId()"
                        + " && wp.data.select('core/block-editor').getSettings().__experimentalFetchLinkSuggestions"
                        + " && document.querySelector('iframe[name=\"editor-canvas\"]')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(TIMEOUT_MS));
        awaitQuiet(page);
    }

    /**
     * Closes the editor's welcome guide, a modal that hides the rest of the editor from clicks and from the
     * accessibility tree, as a user would: with its Close button, or Escape when there is none (Escape reaches the
     * modal only while focus is in the main document). Only the flows that click need it.
     */
    static void closeWelcomeGuide(Page page) {
        Locator overlay = page.locator(".components-modal__screen-overlay");
        if (overlay.count() > 0) {
            // Escape reaches the modal only while focus is in the main document; the close button always does.
            Locator close = overlay.first().getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Close"));
            if (close.count() > 0) {
                close.first().click();
            } else {
                page.keyboard().press("Escape");
            }
            overlay.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
            awaitQuiet(page);
        }
    }

    static FrameLocator canvas(Page page) {
        return page.frameLocator("iframe[name=\"editor-canvas\"]");
    }

    static void editTitle(Page page, String title) {
        page.evaluate("title => wp.data.dispatch('core/editor').editPost({ title })", title);
    }

    static void addParagraph(Page page, String text) {
        page.evaluate(
                "content => wp.data.dispatch('core/block-editor')"
                        + ".insertBlocks(wp.blocks.createBlock('core/paragraph', { content }))",
                text);
    }

    static void insertImageBlock(Page page) {
        page.evaluate("() => wp.data.dispatch('core/block-editor').insertBlocks(wp.blocks.createBlock('core/image'))");
        canvas(page).locator(".wp-block-image").first().waitFor();
    }

    static void savePost(Page page) {
        page.evaluate("async () => { await wp.data.dispatch('core/editor').savePost(); }");
        awaitQuiet(page);
    }

    /** Saves for preview as the Preview button does, then opens the preview link in a new tab as the editor does. */
    static void previewIn(Page page) {
        Object link = page.evaluate("async () => await wp.data.dispatch('core/editor').__unstableSaveForPreview()");
        awaitQuiet(page);
        if (link instanceof String url && !url.isEmpty()) {
            Page preview = page.context().newPage();
            preview.navigate(url);
            awaitQuiet(preview);
            preview.close();
        }
    }

    /** Waits until none of the page's requests has been in flight for {@link #QUIET}. */
    static void awaitQuiet(Page page) {
        Set<Request> pending = PENDING.getOrDefault(page, Set.of());
        long deadline = System.nanoTime() + Duration.ofMillis((long) TIMEOUT_MS).toNanos();
        long quietSince = -1;
        while (System.nanoTime() < deadline) {
            if (pending.isEmpty()) {
                if (quietSince < 0) {
                    quietSince = System.nanoTime();
                } else if (System.nanoTime() - quietSince >= QUIET.toNanos()) {
                    return;
                }
            } else {
                quietSince = -1;
            }
            // Lets Playwright dispatch its events (and the forwarding route) while waiting.
            page.waitForTimeout(100);
        }
        throw new AssertionError("network of " + page.url() + " still busy after " + (long) TIMEOUT_MS + " ms: "
                + pending.stream().map(Request::url).toList());
    }

    /** Creates a post through REST as alice, authored by the given user; answers its id. */
    static long createPost(String author, String status) throws IOException, InterruptedException {
        ObjectNode body = OracleJson.MAPPER.createObjectNode();
        body.put("title", "HAR fixture " + FIXTURES.incrementAndGet());
        body.put("content", "<!-- wp:paragraph -->\n<p>A fixture post.</p>\n<!-- /wp:paragraph -->");
        body.put("status", status);
        body.put("author", site.userId(author));
        Exchange created = client.send(RestCall.json("alice", "POST", "/wp-json/wp/v2/posts", body.toString()));
        assertThat(created.status()).as("create post: %s", created.text()).isEqualTo(201);
        return created.json().get("id").longValue();
    }

    static void uploadThroughRest(String user) throws IOException, InterruptedException {
        Exchange uploaded = client.send(new RestCall(
                user,
                "POST",
                "/wp-json/wp/v2/media",
                Map.of("Content-Disposition", "attachment; filename=\"har-library.png\""),
                Optional.of("image/png"),
                Optional.of(Files.readAllBytes(image))));
        assertThat(uploaded.status()).as("upload: %s", uploaded.text()).isEqualTo(201);
    }

    /** A 64x48 PNG written at run time. */
    static Path writeImage() throws IOException {
        BufferedImage picture = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        try {
            g.setColor(new Color(0x2271b1));
            g.fillRect(0, 0, 64, 48);
            g.setColor(Color.WHITE);
            g.fillOval(16, 8, 32, 32);
        } finally {
            g.dispose();
        }
        Path file = Path.of("target", "editor-har-image.png").toAbsolutePath();
        Files.createDirectories(file.getParent());
        if (!ImageIO.write(picture, "png", file.toFile())) {
            throw new IOException("no PNG writer");
        }
        return file;
    }

    /**
     * A 4000x3000 JPEG with an EXIF orientation of 1, written at run time: smooth gradients, so it stays below PHP's
     * default 2 MB upload limit.
     */
    static Path writeLargeJpeg() throws IOException {
        BufferedImage picture = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        try {
            g.setPaint(new GradientPaint(0, 0, new Color(0x2271b1), 4000, 3000, new Color(0xf0b849)));
            g.fillRect(0, 0, 4000, 3000);
            g.setColor(Color.WHITE);
            g.fillOval(1000, 500, 2000, 2000);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        if (!ImageIO.write(picture, "jpg", jpeg)) {
            throw new IOException("no JPEG writer");
        }
        Path file = Path.of("target", "editor-har-large.jpg").toAbsolutePath();
        Files.write(file, withExifOrientation(jpeg.toByteArray(), 1));
        assertThat(Files.size(file)).as("size of %s", file).isLessThan(2L * 1024 * 1024);
        return file;
    }

    /**
     * Inserts an EXIF APP1 segment holding only the Orientation tag after the JPEG's JFIF APP0 segment (or right after
     * SOI when there is none).
     */
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
            (byte) 0xFF,
            (byte) 0xE1,
            0,
            34, // marker, length (34 bytes after the marker)
            'E',
            'x',
            'i',
            'f',
            0,
            0, // EXIF header
            'M',
            'M',
            0,
            42,
            0,
            0,
            0,
            8, // TIFF header, big-endian, first IFD at 8
            0,
            1, // one entry
            0x01,
            0x12,
            0,
            3,
            0,
            0,
            0,
            1,
            0,
            (byte) orientation,
            0,
            0, // Orientation, SHORT, count 1, value
            0,
            0,
            0,
            0 // no next IFD
        };
        int at = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, at);
        System.arraycopy(app1, 0, out, at, app1.length);
        System.arraycopy(jpeg, at, out, at + app1.length, jpeg.length - at);
        return out;
    }

    /** The number of requests in the HAR whose URL names the REST API. */
    static long restRequests(Path har) throws IOException {
        JsonNode entries = OracleJson.MAPPER.readTree(har.toFile()).path("log").path("entries");
        long count = 0;
        for (JsonNode entry : entries) {
            String url = entry.path("request").path("url").asText();
            if (url.contains("/wp-json/") || url.contains("rest_route=")) {
                count++;
            }
        }
        return count;
    }
}
