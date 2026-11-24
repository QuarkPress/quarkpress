package org.quarkword.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.quarkword.testing.wprestparity.EditorHarCapture.ALL_OF_WORDPRESS;
import static org.quarkword.testing.wprestparity.EditorHarCapture.BROWSER_ROOT;
import static org.quarkword.testing.wprestparity.EditorHarCapture.SERVER_MEDIA_COOKIE;
import static org.quarkword.testing.wprestparity.EditorHarCapture.canvas;
import static org.quarkword.testing.wprestparity.EditorHarCapture.insertImageBlock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.HarContentPolicy;
import com.microsoft.playwright.options.HarMode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkword.testing.wporacle.OracleJson;

/**
 * Records what the WordPress 7.1.3 block editor does in a real browser for the questions the admin-editor and
 * editor-assets references left to a browser run: one test per question ({@code a01}-{@code a14} for the admin-editor
 * reference's browser questions, {@code s01}-{@code s12} for the assets reference's, {@code x01}-{@code x04} for the
 * plan's additions), each noting what it saw under its item in {@code target/wp-rest-parity/verification-facts.json}
 * and keeping its network log as {@code <har.dir>/facts-<item>-<label>.har}. Manual only (profiles {@code wp-parity}
 * and {@code capture-har}), never in CI; it uses {@link EditorHarCapture}'s site, browser and helpers.
 *
 * <p>Firm assertions are few: the release zip's published checksums (s09), the {@code oidc-client-ts} tarball's
 * published sha1 (x02) and, with the DataViews media modal switched on, no media action sent to admin-ajax (a09).
 * Everything else is recorded, not asserted. The harness's switches (site options read by {@code qw-parity-mu.php})
 * are set for one item and reset in its {@code finally}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@TestMethodOrder(MethodOrderer.MethodName.class)
// The switches are try-with-resources guards whose variable the body never reads.
@SuppressWarnings("try")
class EditorBrowserFactsDbTest {

    static final String MAIN = "/";
    static final Path PAGES = VerificationFacts.DIRECTORY.resolve("pages");
    static final Path WP_PIN = Path.of("target", "wp-pin");
    /** admin-ajax actions that only the legacy media modal sends. */
    static final Set<String> MEDIA_ACTIONS = Set.of(
            "query-attachments",
            "get-attachment",
            "save-attachment",
            "save-attachment-compat",
            "send-attachment-to-editor",
            "upload-attachment");

    static long attachmentId;
    static String attachmentFile;

    @BeforeAll
    static void start() throws Exception {
        EditorHarCapture.start();
        Files.createDirectories(PAGES);
        JsonNode media = EditorHarCapture.client
                .send(RestCall.get("alice", "/wp-json/wp/v2/media?per_page=1&context=edit"))
                .json();
        attachmentId = media.get(0).get("id").longValue();
        String source = media.get(0).get("source_url").asText();
        attachmentFile = source.substring(source.lastIndexOf('/') + 1);
    }

    /** Each item's notes are written as soon as it ends, so an aborted run keeps the items it finished. */
    @org.junit.jupiter.api.AfterEach
    void saveFacts() {
        VerificationFacts.save();
    }

    @AfterAll
    static void stop() {
        try {
            VerificationFacts.save();
        } finally {
            EditorHarCapture.stop();
        }
    }

    // ---- admin-editor reference -------------------------------------------------------------------------------------

    /** Whether leaving the editor releases the post lock (a remove-lock request, the lock's time rewound). */
    @Test
    void a01LockReleaseOnUnload() throws Exception {
        String item = "a01";
        long post = createPost("bob", "publish", paragraph("Lock release."));
        note(item, "post", post);
        note(item, "editLockBeforeOpening", meta(post, "_edit_lock"));
        try (Session s = session(item, "bob", "bob")) {
            openExisting(s.page, post);
            note(item, "editLockOpen", meta(post, "_edit_lock"));
            note(
                    item,
                    "postLockSettings",
                    s.page.evaluate("() => wp.data.select('core/editor').getEditorSettings()" + ".postLock || null"));
            int mark = s.log.size();
            s.page.navigate(BROWSER_ROOT + "/wp-admin/");
            awaitQuiet(s.page);
            note(item, "requestsOnNavigatingAway", s.log.dynamic(mark));
            note(item, "editLockAfterNavigatingAway", meta(post, "_edit_lock"));

            openExisting(s.page, post);
            note(item, "editLockReopened", meta(post, "_edit_lock"));
            mark = s.log.size();
            s.page.close(new Page.CloseOptions().setRunBeforeUnload(true));
            settle(s.context, 3000);
            note(item, "requestsOnClosingTab", s.log.dynamic(mark));
            note(
                    item,
                    "beaconsOnClosingTab",
                    s.log.matching(mark, e -> "ping".equals(e.type) || e.url.contains("admin-ajax")));
            note(item, "editLockAfterClosingTab", meta(post, "_edit_lock"));
            note(item, "consoleErrors", s.log.errors());
        }
    }

    /** What the user who lost the lock keeps sending after a takeover, idle and while typing. */
    @Test
    void a02TakeoverLoserTicks() throws Exception {
        String item = "a02";
        long post = createPost("bob", "publish", paragraph("Takeover."));
        note(item, "post", post);
        try (Session bob = session(item, "bob", "bob");
                Session alice = session(item, "alice", "alice")) {
            openExisting(bob.page, post);
            alice.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
            Locator modal = alice.page.locator(".editor-post-locked-modal");
            modal.first().waitFor();
            note(item, "lockModalText", modal.first().innerText());
            note(item, "lockModalLinks", links(modal.first()));
            alice.page.waitForResponse(
                    r -> r.url().contains("get-post-lock"),
                    () -> alice.page
                            .locator(".editor-post-locked-modal__buttons a:text-is(\"Take over\")")
                            .first()
                            .click());
            alice.page.waitForLoadState();
            awaitEditor(alice.page);
            note(item, "takeOverUrl", alice.page.url());
            note(item, "editLockAfterTakeover", meta(post, "_edit_lock"));

            int mark = bob.log.size();
            Instant start = Instant.now();
            List<Object> forced = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Response tick = bob.page.waitForResponse(
                        r -> r.url().contains("admin-ajax.php")
                                && String.valueOf(r.request().postData()).contains("heartbeat"),
                        () -> bob.page.evaluate("() => wp.heartbeat.connectNow()"));
                forced.add(Map.of("request", decode(tick.request().postData()), "response", safeText(tick)));
            }
            note(item, "forcedTicks", forced);
            Locator loserModal = bob.page.locator(".editor-post-locked-modal");
            note(
                    item,
                    "loserModalText",
                    loserModal.count() > 0 ? loserModal.first().innerText() : null);
            note(item, "heartbeatInterval", bob.page.evaluate("() => wp.heartbeat.interval()"));
            bob.page.waitForTimeout(35_000);
            note(item, "idleRequests", bob.log.dynamic(mark));
            note(item, "idleSeconds", Duration.between(start, Instant.now()).toSeconds());

            mark = bob.log.size();
            start = Instant.now();
            for (int i = 0; i < 3; i++) {
                bob.page.evaluate(
                        "text => wp.data.dispatch('core/block-editor')"
                                + ".insertBlocks(wp.blocks.createBlock('core/paragraph', { content: text }))",
                        "Typed after the takeover " + i + ".");
                bob.page.waitForTimeout(12_000);
            }
            note(item, "typingRequests", bob.log.dynamic(mark));
            note(item, "typingSeconds", Duration.between(start, Instant.now()).toSeconds());
            note(item, "loserNotices", notices(bob.page));
            note(item, "consoleErrors", bob.log.errors());
        }
    }

    /** The draft preview's window URL, and what the same URL shows to an anonymous visitor. */
    @Test
    void a03DraftPreviewUrl() throws Exception {
        String item = "a03";
        String text = "Draft preview text QPA03.";
        long post = createPost("bob", "draft", paragraph(text));
        note(item, "post", post);
        String url;
        try (Session s = session(item, "bob", "bob")) {
            openExisting(s.page, post);
            closeWelcomeGuide(s.page);
            s.page.evaluate("() => wp.data.dispatch('core/editor').editPost({ title: 'Draft QPA03 edited' })");
            int mark = s.log.size();
            Page popup = preview(s.page);
            url = popup.url();
            note(item, "previewUrl", url);
            note(item, "previewTitle", popup.title());
            note(item, "previewShowsText", popup.content().contains(text));
            note(item, "requestsOfPreview", s.log.dynamic(mark));
            note(item, "previewDocumentStatus", s.log.statusOf(url));
        }
        try (Session anonymous = session(item, "anonymous", null)) {
            Response answer = anonymous.page.navigate(url);
            note(item, "anonymousStatus", answer == null ? null : answer.status());
            note(item, "anonymousTitle", anonymous.page.title());
            note(item, "anonymousShowsText", anonymous.page.content().contains(text));
        }
    }

    /** The published post's preview: the autosave, the window URL, the overlay of edited text and featured image. */
    @Test
    void a04PublishedPreviewUrl() throws Exception {
        String item = "a04";
        long post = createPost("bob", "publish", paragraph("Published text QPA04."));
        note(item, "post", post);
        note(item, "attachment", attachmentId);
        try (Session s = session(item, "bob", "bob")) {
            openExisting(s.page, post);
            closeWelcomeGuide(s.page);
            s.page.evaluate(
                    "id => wp.data.dispatch('core/editor').editPost({ featured_media: id })", (int) attachmentId);
            EditorHarCapture.addParagraph(s.page, "Edited text QPA04-EDIT.");
            int mark = s.log.size();
            Page popup = preview(s.page);
            note(item, "previewUrl", popup.url());
            note(item, "requestsOfPreview", s.log.dynamic(mark));
            String html = popup.content();
            note(item, "previewShowsEditedText", html.contains("QPA04-EDIT"));
            note(item, "previewShowsFeaturedImage", html.contains(stem(attachmentFile)));
            note(item, "previewTitle", popup.title());
        }
    }

    /** The newer-autosave notice (another user's autosave, then the author's own) and "View the autosave". */
    @Test
    void a05NewerAutosaveNotice() throws Exception {
        String item = "a05";
        for (String author : List.of("alice", "bob")) {
            long post = createPost("bob", "publish", paragraph("Saved text QPA05."));
            backdate(post, 2);
            JsonNode autosave = EditorHarCapture.site.operation(
                    "autosave",
                    args().put("site", MAIN)
                            .put("as", author)
                            .put("post", post)
                            .put("title", "Autosaved title QPA05")
                            .put("content", paragraph("Autosaved text QPA05 by " + author + ".")));
            String key = "autosaveBy" + author;
            note(item, key + ".post", post);
            note(item, key + ".autosave", autosave);
            try (Session s = session(item, "bob-" + author, "bob")) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                note(
                        item,
                        key + ".autosaveSetting",
                        s.page.evaluate("() => wp.data.select('core/editor').getEditorSettings().autosave || null"));
                note(item, key + ".notices", notices(s.page));
                Locator action = s.page.getByText("View the autosave");
                if (action.count() > 0) {
                    int mark = s.log.size();
                    action.first().click();
                    s.page.waitForURL(u -> u.contains("revision"), new Page.WaitForURLOptions().setTimeout(30_000));
                    s.page.waitForLoadState();
                    note(item, key + ".afterViewUrl", s.page.url());
                    note(item, key + ".afterViewTitle", s.page.title());
                    awaitQuiet(s.page);
                    note(item, key + ".afterViewRequests", s.log.dynamic(mark));
                    note(
                            item,
                            key + ".afterViewHasEditor",
                            s.page.evaluate("() => !!(window.wp && wp.editPost && document.querySelector('#editor'))"));
                }
            }
        }
    }

    /** The editor with a failing notes request (404, then 400): mounted, the Notes entry, the console. */
    @Test
    void a06NotesFailure() throws Exception {
        String item = "a06";
        long post = createPost("alice", "publish", paragraph("Notes."));
        for (String variant : List.of("baseline", "qw_parity_notes_404", "qw_parity_notes_400")) {
            try (Switches ignored = variant.equals("baseline") ? Switches.on() : Switches.on(variant);
                    Session s = session(item, variant, "alice")) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                note(item, variant + ".notesRequests", s.log.matching(0, e -> e.url.contains("comments")));
                note(item, variant + ".errorBoundary", errorBoundary(s.page));
                note(
                        item,
                        variant + ".noteButtons",
                        s.page.evaluate("() => [...document.querySelectorAll('button')]"
                                + ".map(b => b.getAttribute('aria-label') || b.innerText)"
                                + ".filter(t => t && /note/i.test(t))"));
                Locator notes = s.page.locator("button[aria-label='Notes'], button:has-text('Notes')");
                if (notes.count() > 0) {
                    notes.first().click(new Locator.ClickOptions().setTimeout(10_000));
                    awaitQuiet(s.page);
                    note(
                            item,
                            variant + ".notesSidebarText",
                            s.page.evaluate("() => { const p = document.querySelector('.interface-complementary-area');"
                                    + " return p ? p.innerText.slice(0, 1500) : null; }"));
                }
                note(item, variant + ".console", s.log.console());
                note(item, variant + ".pageErrors", s.log.errors());
            }
        }
    }

    /** Plain-HTML content with and without the classic editor script: which blocks it becomes. */
    @Test
    void a07ContentWithoutBlocks() throws Exception {
        String item = "a07";
        long post = createPost("alice", "publish", "<p>Plain one QPA07.</p>\n<p>Plain two QPA07.</p>");
        for (String variant : List.of("baseline", "qw_parity_no_classic_editor")) {
            try (Switches ignored = variant.equals("baseline") ? Switches.on() : Switches.on(variant);
                    Session s = session(item, variant, "alice")) {
                Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
                String html = answer.text();
                awaitEditor(s.page);
                note(
                        item,
                        variant + ".editorScriptTag",
                        html.contains("id=\"editor-js\"") || html.contains("id='editor-js'"));
                note(item, variant + ".tinymceInPage", html.contains("tinymce"));
                note(
                        item,
                        variant + ".state",
                        s.page.evaluate("() => ({ oldEditor: typeof (wp.oldEditor), tinymce: typeof window.tinymce,"
                                + " freeformHandler: wp.blocks.getFreeformContentHandlerName(),"
                                + " freeformRegistered: !!wp.blocks.getBlockType('core/freeform'),"
                                + " freeformInserter: (() => { const t = wp.blocks.getBlockType('core/freeform');"
                                + " return t ? (t.supports && t.supports.inserter) : null; })(),"
                                + " blocks: wp.data.select('core/block-editor').getBlocks()"
                                + ".map(b => ({ name: b.name, content: b.attributes.content"
                                + " ? String(b.attributes.content).slice(0, 80) : undefined })) })"));
                note(item, variant + ".pageErrors", s.log.errors());
            }
        }
    }

    /** The canvas without resolved assets, styles and default editor styles: mounted, what the iframe holds. */
    @Test
    void a08BareCanvas() throws Exception {
        String item = "a08";
        long post = createPost(
                "alice",
                "publish",
                paragraph("Bare canvas QPA08.")
                        + "\n<!-- wp:buttons -->\n<div class=\"wp-block-buttons\"><!-- wp:button -->\n"
                        + "<div class=\"wp-block-button\"><a class=\"wp-block-button__link wp-element-button\">"
                        + "Button</a></div>\n<!-- /wp:button --></div>\n<!-- /wp:buttons -->");
        for (String variant : List.of("baseline", "qw_parity_bare_canvas")) {
            try (Switches ignored = variant.equals("baseline") ? Switches.on() : Switches.on(variant);
                    Session s = session(item, variant, "alice")) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                note(
                        item,
                        variant + ".settingsKeys",
                        s.page.evaluate("() => { const s = wp.data.select('core/block-editor').getSettings();"
                                + " return { resolvedAssets: !!s.__unstableResolvedAssets,"
                                + " styles: (s.styles || []).length,"
                                + " defaultEditorStyles: (s.defaultEditorStyles || []).length }; }"));
                note(item, variant + ".iframe", iframeAssets(s.page));
                note(
                        item,
                        variant + ".buttonStyle",
                        canvasEval(
                                s.page,
                                "() => { const a = document.querySelector('.wp-block-button__link');"
                                        + " if (!a) return null; const c = getComputedStyle(a);"
                                        + " return { background: c.backgroundColor, color: c.color, padding: c.padding,"
                                        + " display: c.display, font: c.fontFamily }; }"));
                Path shot = PAGES.resolve(item + "-" + variant + ".png");
                s.page.locator("#editor").screenshot(new Locator.ScreenshotOptions().setPath(shot));
                note(item, variant + ".screenshot", shot.toString());
                note(item, variant + ".errorBoundary", errorBoundary(s.page));
                note(item, variant + ".pageErrors", s.log.errors());
            }
        }
    }

    /**
     * The DataViews media modal with the legacy modal's scripts omitted: every media entry point, the admin-ajax
     * requests (firm: no media action), the console.
     */
    @Test
    void a09DataViewsModal() throws Exception {
        String item = "a09";
        Path har;
        try (Switches ignored = Switches.on("qw_parity_dataviews_media", "qw_parity_stub_media");
                Session s = session(item, "alice", "alice", HarContentPolicy.EMBED)) {
            har = s.har;
            openNew(s.page);
            closeWelcomeGuide(s.page);
            note(
                    item,
                    "flags",
                    s.page.evaluate(
                            "() => ({ dataViews: window.__experimentalDataViewsMediaModal,"
                                    + " wpMedia: typeof (wp.media), mediaView: typeof (wp.media && wp.media.view),"
                                    + " tags: [...document.querySelectorAll('script[id^=media-], script[id^=wp-plupload], script[id^=mce-view]')].map(e => e.id + ' ' + (e.src || 'inline')) })"));
            Map<String, Object> entries = new LinkedHashMap<>();
            entries.put("imageMediaLibrary", attempt(s, () -> {
                insertImageBlock(s.page);
                return openMediaButton(
                        s,
                        canvas(s.page)
                                .getByRole(
                                        AriaRole.BUTTON,
                                        new com.microsoft.playwright.FrameLocator.GetByRoleOptions()
                                                .setName("Media Library")));
            }));
            entries.put("featuredImage", attempt(s, () -> {
                s.page.evaluate("() => wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/document')");
                awaitQuiet(s.page);
                return openMediaButton(s, s.page.getByText("Set featured image"));
            }));
            entries.put("galleryMediaLibrary", attempt(s, () -> {
                s.page.evaluate("() => wp.data.dispatch('core/block-editor')"
                        + ".insertBlocks(wp.blocks.createBlock('core/gallery'))");
                canvas(s.page).locator(".wp-block-gallery").first().waitFor();
                return openMediaButton(
                        s,
                        canvas(s.page)
                                .locator(".wp-block-gallery")
                                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Media Library")));
            }));
            entries.put("coverMediaLibrary", attempt(s, () -> {
                s.page.evaluate("() => wp.data.dispatch('core/block-editor')"
                        + ".insertBlocks(wp.blocks.createBlock('core/cover'))");
                canvas(s.page).locator(".wp-block-cover").first().waitFor();
                return openMediaButton(
                        s,
                        canvas(s.page)
                                .locator(".wp-block-cover")
                                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Media Library")));
            }));
            entries.put("inserterMediaTab", attempt(s, () -> {
                int mark = s.log.size();
                s.page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened({ tab: 'media' })");
                awaitQuiet(s.page);
                s.page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Images"))
                        .or(s.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Images")))
                        .first()
                        .click();
                awaitQuiet(s.page);
                return Map.of(
                        "requests", s.log.dynamic(mark),
                        "items",
                                s.page.locator(".block-editor-inserter__media-list__list-item")
                                        .count());
            }));
            note(item, "entries", entries);
            note(item, "adminAjax", s.log.matching(0, e -> e.url.contains("admin-ajax.php")));
            note(
                    item,
                    "wpMediaConsole",
                    s.log.console().stream()
                            .filter(m -> m.contains("wp.media") || m.contains("media"))
                            .toList());
            note(item, "pageErrors", s.log.errors());
        }
        Map<String, Integer> actions = adminAjaxActions(har);
        note(item, "harAdminAjaxActions", actions);
        assertThat(actions.keySet())
                .as("media actions sent to admin-ajax with the DataViews modal (HAR %s)", har)
                .doesNotContainAnyElementsOf(MEDIA_ACTIONS);
    }

    /** Pattern UI by role: the author's "My patterns" with another user's synced pattern, the contributor's menu. */
    @Test
    void a10PatternUiByRole() throws Exception {
        String item = "a10";
        Exchange created = EditorHarCapture.client.send(RestCall.json(
                "alice",
                "POST",
                "/wp-json/wp/v2/blocks",
                "{\"title\":\"Alice synced QPA10\",\"status\":\"publish\",\"content\":"
                        + OracleJson.MAPPER.writeValueAsString(paragraph("Synced by alice.")) + "}"));
        long ref = created.json().get("id").longValue();
        note(item, "pattern", ref);
        for (String user : List.of("carol", "dave")) {
            long post = createPost(
                    user,
                    "draft",
                    paragraph("Own text of " + user + ".") + "\n<!-- wp:block {\"ref\":" + ref + "} /-->");
            try (Session s = session(item, user, user)) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                note(
                        item,
                        user + ".canUser",
                        s.page.evaluate("async () => ({ createBlock: await wp.data.resolveSelect('core')"
                                + ".canUser('create', { kind: 'postType', name: 'wp_block' }),"
                                + " readBlocks: (await wp.data.resolveSelect('core').getEntityRecords('postType',"
                                + " 'wp_block', { per_page: -1 }) || []).map(r => r.title && r.title.raw) })"));
                int mark = s.log.size();
                s.page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened({ tab: 'patterns' })");
                awaitQuiet(s.page);
                Locator mine = s.page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("My patterns"))
                        .or(s.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("My patterns")))
                        .or(s.page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("My patterns")));
                note(item, user + ".myPatternsCategory", mine.count());
                if (mine.count() > 0) {
                    mine.first().click();
                    awaitQuiet(s.page);
                    note(
                            item,
                            user + ".myPatternsListed",
                            s.page.evaluate(
                                    "() => [...document.querySelectorAll('.block-editor-block-patterns-list__item-title,"
                                            + " .block-editor-block-patterns-list__item [aria-label]')]"
                                            + ".map(e => e.innerText || e.getAttribute('aria-label'))"));
                }
                note(
                        item,
                        user + ".inserterCategories",
                        s.page.evaluate(
                                "() => { const p = document.querySelector('.block-editor-inserter__panel-content,"
                                        + " .block-editor-inserter__menu'); return p ? p.innerText.slice(0, 1500) : null; }"));
                s.page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened(false)");
                s.page.evaluate("() => { const b = wp.data.select('core/block-editor').getBlocks()[0];"
                        + " wp.data.dispatch('core/block-editor').selectBlock(b.clientId); }");
                awaitQuiet(s.page);
                Locator options = s.page.locator(".block-editor-block-toolbar button[aria-label='Options']");
                if (options.count() > 0) {
                    options.first().click();
                    awaitQuiet(s.page);
                    note(
                            item,
                            user + ".blockMenuItems",
                            s.page.evaluate(
                                    "() => [...document.querySelectorAll('[role=menuitem]')].map(e => e.innerText.trim())"));
                    s.page.keyboard().press("Escape");
                } else {
                    note(item, user + ".blockMenuItems", "no Options button in the block toolbar");
                }
                note(
                        item,
                        user + ".optionsRequests",
                        s.log.matching(0, e -> "OPTIONS".equals(e.method) || e.url.contains("_method=OPTIONS")));
                note(item, user + ".patternRequests", s.log.dynamic(mark));
                note(item, user + ".blocksRequests", s.log.matching(0, e -> e.url.contains("/wp/v2/blocks")));
                Exchange listed = EditorHarCapture.client.send(
                        RestCall.get(user, "/wp-json/wp/v2/blocks?context=edit&per_page=100&_fields=id,title,author"));
                note(item, user + ".restBlocks", Map.of("status", listed.status(), "body", listed.json()));
            }
        }
    }

    /** A failing (500, then empty) themes route with its preload dropped: mount, panels, second wave, embeds. */
    @Test
    void a11FailingThemes() throws Exception {
        String item = "a11";
        long post = createPost("alice", "publish", paragraph("Themes QPA11."));
        for (String value : List.of("1", "empty")) {
            String variant = "themes-" + ("1".equals(value) ? "500" : "empty");
            EditorHarCapture.site.option("qw_parity_themes_500", TextNode.valueOf(value));
            try (Session s = session(item, variant, "alice")) {
                s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
                boolean mounted = tryAwaitEditor(s.page, 60_000);
                note(item, variant + ".mounted", mounted);
                note(item, variant + ".errorBoundary", errorBoundary(s.page));
                note(item, variant + ".requests", s.log.dynamic(0));
                if (mounted) {
                    closeWelcomeGuide(s.page);
                    s.page.evaluate(
                            "() => wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/document')");
                    awaitQuiet(s.page);
                    note(
                            item,
                            variant + ".panels",
                            s.page.evaluate(
                                    "() => ({ featuredImage: !!document.querySelector('.editor-post-featured-image'),"
                                            + " format: !!document.querySelector('.editor-post-format'),"
                                            + " themeSupports: wp.data.select('core').getThemeSupports(),"
                                            + " sidebar: (document.querySelector('.editor-sidebar') || {}).innerText })"));
                    int mark = s.log.size();
                    s.page.evaluate("() => wp.data.dispatch('core/block-editor').insertBlocks("
                            + "wp.blocks.createBlock('core/embed', { url: 'https://unembeddable.invalid/qpa11' }))");
                    awaitQuiet(s.page);
                    note(item, variant + ".embedRequests", s.log.dynamic(mark));
                    note(
                            item,
                            variant + ".embedText",
                            canvasEval(
                                    s.page,
                                    "() => { const e = document.querySelector('.wp-block-embed');"
                                            + " return e ? e.innerText : null; }"));
                }
                note(item, variant + ".pageErrors", s.log.errors());
                note(
                        item,
                        variant + ".consoleErrors",
                        s.log.console().stream()
                                .filter(m -> m.startsWith("error"))
                                .toList());
            } finally {
                EditorHarCapture.site.option("qw_parity_themes_500", TextNode.valueOf("0"));
            }
        }
    }

    /** Preload clearing (the console line, the media OPTIONS request) and the bindings panel without its key. */
    @Test
    void a12PreloadClearingAndBindings() throws Exception {
        String item = "a12";
        long post = createPost("alice", "publish", paragraph("Bindings QPA12."));
        for (String variant : List.of("baseline", "qw_parity_no_bindings_key")) {
            try (Switches ignored = variant.equals("baseline") ? Switches.on() : Switches.on(variant);
                    Session s = session(item, variant, "alice")) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                note(
                        item,
                        variant + ".preloadConsole",
                        s.log.console().stream()
                                .filter(m -> m.contains("[api-fetch]"))
                                .toList());
                note(
                        item,
                        variant + ".bindingsKey",
                        s.page.evaluate("() => wp.data.select('core/block-editor')"
                                + ".getSettings().__experimentalBlockBindingsSupportedAttributes || null"));
                int mark = s.log.size();
                s.page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened({ tab: 'media' })");
                awaitQuiet(s.page);
                note(item, variant + ".mediaTabRequests", s.log.dynamic(mark));
                note(
                        item,
                        variant + ".mediaOptions",
                        s.log.matching(
                                0,
                                e -> e.url.contains("/wp/v2/media")
                                        && ("OPTIONS".equals(e.method) || e.url.contains("_method=OPTIONS"))));
                note(
                        item,
                        variant + ".inserterOpen",
                        s.page.evaluate("() => wp.data.select('core/editor').isInserterOpened()"));
                s.page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened(false)");
                s.page.evaluate("() => { const b = wp.data.select('core/block-editor').getBlocks()[0];"
                        + " wp.data.dispatch('core/block-editor').selectBlock(b.clientId);"
                        + " wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/block'); }");
                awaitQuiet(s.page);
                note(
                        item,
                        variant + ".bindingsPanel",
                        s.page.evaluate("() => { const p = document.querySelector('.block-editor-bindings__panel');"
                                + " return p ? p.innerText.slice(0, 500) : null; }"));
                note(
                        item,
                        variant + ".inspectorText",
                        s.page.evaluate("() => { const p = document.querySelector('.block-editor-block-inspector');"
                                + " return p ? p.innerText.slice(0, 1500) : null; }"));
                note(
                        item,
                        variant + ".consoleAfter",
                        s.log.console().stream()
                                .filter(m -> m.contains("[api-fetch]"))
                                .toList());
            }
        }
    }

    /** The nonce refresh: one refused save (retried), then every save refused (one notice, two attempts). */
    @Test
    void a13NonceRetry() throws Exception {
        String item = "a13";
        long post = createPost("bob", "publish", paragraph("Nonce QPA13."));
        for (String value : List.of("1", "always")) {
            String variant = "1".equals(value) ? "once" : "always";
            EditorHarCapture.site.option("qw_parity_nonce_403_done", TextNode.valueOf("0"));
            EditorHarCapture.site.option("qw_parity_nonce_403_once", TextNode.valueOf(value));
            try (Session s = session(item, variant, "bob")) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                note(item, variant + ".nonceEndpoint", s.page.evaluate("() => wp.apiFetch.nonceEndpoint || null"));
                s.page.evaluate("t => wp.data.dispatch('core/editor').editPost({ title: t })", "Nonce QPA13 " + value);
                int mark = s.log.size();
                // Not awaited: with every PUT refused the retry has no bound of its own. The save gets 15 s, then
                // the switch goes off (so the next attempt succeeds) and the promise gets 60 s to settle.
                s.page.evaluate("() => { window.qpSave = 'pending'; wp.data.dispatch('core/editor').savePost()"
                        + ".then(() => { window.qpSave = 'resolved'; },"
                        + " e => { window.qpSave = 'rejected: ' + (e && e.message); }); }");
                s.page.waitForTimeout(15_000);
                note(item, variant + ".saveStateAfter15s", s.page.evaluate("() => window.qpSave"));
                note(
                        item,
                        variant + ".putsIn15s",
                        s.log.matching(mark, e -> e.url.contains("/wp/v2/posts/" + post) && !"GET".equals(e.method))
                                .size());
                note(
                        item,
                        variant + ".nonceFetchesIn15s",
                        s.log.matching(mark, e -> e.url.contains("rest-nonce")).size());
                note(item, variant + ".noticesAt15s", notices(s.page));
                EditorHarCapture.site.option("qw_parity_nonce_403_once", TextNode.valueOf("0"));
                s.page.waitForFunction(
                        "() => window.qpSave !== 'pending'",
                        null,
                        new Page.WaitForFunctionOptions().setTimeout(60_000));
                awaitQuiet(s.page);
                note(item, variant + ".saveState", s.page.evaluate("() => window.qpSave"));
                ArrayNode requests = s.log.dynamic(mark);
                note(item, variant + ".requestCount", requests.size());
                ArrayNode head = OracleJson.MAPPER.createArrayNode();
                for (int i = 0; i < Math.min(12, requests.size()); i++) {
                    head.add(requests.get(i));
                }
                note(item, variant + ".saveRequests", head);
                note(item, variant + ".notices", notices(s.page));
                note(
                        item,
                        variant + ".snackbarText",
                        s.page.evaluate(
                                "() => [...document.querySelectorAll('.components-snackbar, .components-notice')]"
                                        + ".map(e => e.innerText)"));
            } finally {
                EditorHarCapture.site.option("qw_parity_nonce_403_once", TextNode.valueOf("0"));
            }
        }
    }

    /** The contributor's publish button and panels, and what an editor sees on the same draft. */
    @Test
    void a14ContributorButtons() throws Exception {
        String item = "a14";
        long post = createPost("dave", "draft", paragraph("Contributor QPA14."));
        note(item, "post", post);
        for (String user : List.of("dave", "bob")) {
            Exchange links = EditorHarCapture.client.send(
                    RestCall.get(user, "/wp-json/wp/v2/posts/" + post + "?context=edit&_fields=_links"));
            List<String> actions = new ArrayList<>();
            links.json().path("_links").fieldNames().forEachRemaining(name -> {
                if (name.startsWith("wp:action")) {
                    actions.add(name);
                }
            });
            note(item, user + ".actionLinks", actions);
            try (Session s = session(item, user, user)) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                s.page.evaluate("() => wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/document')");
                awaitQuiet(s.page);
                note(
                        item,
                        user + ".header",
                        s.page.evaluate(
                                "() => [...document.querySelectorAll('.editor-header button, .edit-post-header button')]"
                                        + ".map(b => (b.innerText || b.getAttribute('aria-label') || '').trim())"
                                        + ".filter(t => t)"));
                note(
                        item,
                        user + ".sidebar",
                        s.page.evaluate("() => (document.querySelector('.editor-sidebar') || {}).innerText || null"));
                note(
                        item,
                        user + ".previewPresent",
                        s.page.locator(".editor-preview-dropdown__toggle").count());
                if ("dave".equals(user)) {
                    int mark = s.log.size();
                    Locator toggle = s.page.locator(".editor-post-publish-panel__toggle, .editor-post-publish-button");
                    note(item, "dave.publishButtonText", toggle.first().innerText());
                    toggle.first().click();
                    Locator confirm = s.page.locator(".editor-post-publish-panel__header-publish-button button");
                    if (confirm.count() > 0 || waitVisible(confirm, 10_000)) {
                        note(
                                item,
                                "dave.prePublishPanel",
                                s.page.locator(".editor-post-publish-panel")
                                        .first()
                                        .innerText());
                        confirm.first().click();
                    }
                    s.page.waitForFunction(
                            "() => wp.data.select('core/editor').getCurrentPost().status === 'pending'",
                            null,
                            new Page.WaitForFunctionOptions().setTimeout(30_000));
                    awaitQuiet(s.page);
                    note(item, "dave.submitRequests", s.log.dynamic(mark));
                    note(
                            item,
                            "dave.statusAfter",
                            s.page.evaluate("() => wp.data.select('core/editor').getCurrentPost().status"));
                }
            }
        }
    }

    // ---- editor-assets reference ------------------------------------------------------------------------------------

    /** The page with concatenation off: every script and stylesheet tag in order, and every static file fetched. */
    @Test
    void s01IndividualTagOrder() throws Exception {
        String item = "s01";
        long post = createPost("alice", "publish", paragraph("Tags QPS01."));
        try (Switches ignored = Switches.on("qw_parity_no_concat");
                Session s = session(item, "alice", "alice")) {
            Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
            String html = answer.text();
            Files.writeString(PAGES.resolve("s01.html"), html);
            awaitEditor(s.page);
            List<Map<String, String>> tags = tags(html);
            note(item, "tags", tags);
            note(item, "loadScriptsOrStyles", html.contains("load-scripts.php") || html.contains("load-styles.php"));
            closeWelcomeGuide(s.page);
            note(item, "staticFiles", s.log.staticPaths(0));
            note(item, "iframeAssets", iframeAssets(s.page));
        }
    }

    /** TinyMCE for a rich-editing browser: the page's tags, what loads at boot and when a Classic block is inserted. */
    @Test
    void s02TinyMce() throws Exception {
        String item = "s02";
        long post = createPost("alice", "publish", paragraph("TinyMCE QPS02."));
        try (Session s = session(item, "alice", "alice")) {
            Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
            String html = answer.text();
            awaitEditor(s.page);
            note(
                    item,
                    "richEditingEnabled",
                    s.page.evaluate("() => wp.data.select('core/editor').getEditorSettings().richEditingEnabled"));
            note(item, "tinyMcePreInit", html.contains("tinyMCEPreInit"));
            note(item, "tinyMceTags", fragmentsAround(html, "tinymce", 240));
            note(
                    item,
                    "tinyMceAtLoad",
                    s.log.staticPaths(0).stream()
                            .filter(p -> p.contains("tinymce"))
                            .toList());
            closeWelcomeGuide(s.page);
            int mark = s.log.size();
            s.page.evaluate("() => wp.data.dispatch('core/block-editor')"
                    + ".insertBlocks(wp.blocks.createBlock('core/freeform', { content: '<p>Classic</p>' }))");
            awaitQuiet(s.page);
            s.page.evaluate("() => { const b = wp.data.select('core/block-editor').getBlocks()"
                    + ".find(x => x.name === 'core/freeform');"
                    + " if (b) wp.data.dispatch('core/block-editor').selectBlock(b.clientId); }");
            awaitQuiet(s.page);
            note(
                    item,
                    "tinyMceEditors",
                    canvasEval(
                            s.page, "() => window.parent.tinymce" + " ? window.parent.tinymce.editors.length : null"));
            note(
                    item,
                    "classicMarkup",
                    canvasEval(
                            s.page,
                            "() => { const e = document.querySelector("
                                    + "'.wp-block-freeform, [data-type=\"core/freeform\"]'); return e ? e.outerHTML.slice(0, 600) : null; }"));
            note(item, "tinyMceOnClassicBlock", s.log.staticPaths(mark));
            // TinyMCE starts only in the Classic block's modal, opened by the block toolbar's "Edit" button.
            int edit = s.log.size();
            s.page.locator(".block-editor-block-toolbar button")
                    .filter(new Locator.FilterOptions().setHasText("Edit"))
                    .first()
                    .click(new Locator.ClickOptions().setTimeout(15_000));
            boolean modal = waitVisible(s.page.locator(".block-editor-freeform-modal__content"), 15_000);
            note(item, "classicModalOpened", modal);
            boolean started;
            try {
                s.page.waitForFunction(
                        "() => window.tinymce && tinymce.editors.length > 0",
                        null,
                        new Page.WaitForFunctionOptions().setTimeout(60_000));
                started = true;
            } catch (PlaywrightException e) {
                started = false;
            }
            awaitQuiet(s.page);
            note(item, "tinyMceStartedOnEdit", started);
            note(item, "tinyMceEditorsOnEdit", s.page.evaluate("() => window.tinymce ? tinymce.editors.length : null"));
            note(
                    item,
                    "tinyMceSettingsOnEdit",
                    s.page.evaluate("() => { const e = window.tinymce && tinymce.editors[0];"
                            + " return e ? { plugins: e.settings.plugins, theme: e.settings.theme, skin: e.settings.skin,"
                            + " toolbar1: e.settings.toolbar1, toolbar2: e.settings.toolbar2,"
                            + " contentCss: e.settings.content_css } : null; }"));
            List<String> onEdit = s.log.staticPaths(edit);
            note(item, "staticFilesOnEdit", onEdit);
            note(
                    item,
                    "tinyMceFilesOnEdit",
                    onEdit.stream().filter(p -> p.contains("/tinymce/")).toList());
            note(
                    item,
                    "tinyMceFilesWithSizeOnEdit",
                    sizes(onEdit.stream().filter(p -> p.contains("/tinymce/")).toList()));
            note(item, "adminAjaxOnEdit", s.log.matching(edit, e -> e.url.contains("admin-ajax.php")));
            note(item, "dynamicOnEdit", s.log.dynamic(edit));
            note(item, "modalScreenshot", PAGES.resolve("s02-classic-modal.png").toString());
            s.page.screenshot(new Page.ScreenshotOptions().setPath(PAGES.resolve("s02-classic-modal.png")));
            note(item, "pageErrors", s.log.errors());
        }
    }

    /** Script modules fetched at boot and on demand (a Math block), and whether modulepreload links are printed. */
    @Test
    void s03ScriptModules() throws Exception {
        String item = "s03";
        long post = createPost("alice", "publish", paragraph("Modules QPS03."));
        try (Session s = session(item, "alice", "alice")) {
            Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
            String html = answer.text();
            awaitEditor(s.page);
            note(item, "modulepreload", html.contains("modulepreload"));
            note(item, "importMap", fragmentsAround(html, "importmap", 3000));
            note(item, "moduleScripts", fragmentsAround(html, "type=\"module\"", 300));
            note(
                    item,
                    "modulesAtBoot",
                    s.log.staticPaths(0).stream()
                            .filter(p -> p.contains("script-modules"))
                            .toList());
            closeWelcomeGuide(s.page);
            note(item, "mathBlock", s.page.evaluate("() => !!wp.blocks.getBlockType('core/math')"));
            int mark = s.log.size();
            s.page.evaluate("() => wp.data.dispatch('core/block-editor')"
                    + ".insertBlocks(wp.blocks.createBlock('core/math', { latex: 'x^2+1' }))");
            awaitQuiet(s.page);
            note(item, "modulesOnMath", s.log.staticPaths(mark));
            note(
                    item,
                    "mathMarkup",
                    canvasEval(
                            s.page,
                            "() => { const m = document.querySelector('.wp-block-math');"
                                    + " return m ? m.innerHTML.slice(0, 400) : null; }"));
            note(item, "pageErrors", s.log.errors());
        }
    }

    /** The legacy media scripts omitted with stub globals, then also the modal's own scripts and postbox. */
    @Test
    void s04StubbedMediaGlobals() throws Exception {
        String item = "s04";
        long post = createPost("alice", "publish", paragraph("Stubs QPS04."));
        for (List<String> variant : List.of(
                List.of("qw_parity_dataviews_media", "qw_parity_stub_media"),
                List.of("qw_parity_dataviews_media", "qw_parity_stub_media", "qw_parity_stub_all_media"))) {
            String label = variant.size() == 2 ? "stub_media" : "stub_all_media";
            try (Switches ignored = Switches.on(variant.toArray(String[]::new));
                    Session s = session(item, label, "alice")) {
                Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
                String html = answer.text();
                Files.writeString(PAGES.resolve("s04-" + label + ".html"), html);
                boolean mounted = tryAwaitEditor(s.page, 60_000);
                note(item, label + ".mounted", mounted);
                note(
                        item,
                        label + ".globals",
                        s.page.evaluate("() => ({ wpMedia: typeof (window.wp && wp.media),"
                                + " mediaView: typeof (window.wp && wp.media && wp.media.view),"
                                + " postboxes: typeof window.postboxes, editPost: typeof (window.wp && wp.editPost),"
                                + " blockLibrary: typeof (window.wp && wp.blockLibrary),"
                                + " l10n: window._wpMediaViewsL10n || null,"
                                + " plupload: window._wpPluploadSettings || null })"));
                note(item, label + ".errorsAtLoad", s.log.errors());
                note(
                        item,
                        label + ".consoleErrorsAtLoad",
                        s.log.console().stream()
                                .filter(m -> m.startsWith("error"))
                                .toList());
                note(item, label + ".mediaTemplatesPrinted", html.contains("tmpl-media-modal"));
                if (mounted) {
                    closeWelcomeGuide(s.page);
                    int errors = s.log.errorCount();
                    insertImageBlock(s.page);
                    awaitQuiet(s.page);
                    s.page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened({ tab: 'media' })");
                    awaitQuiet(s.page);
                    note(item, label + ".errorsAfterImageAndMediaTab", s.log.errorsFrom(errors));
                    note(item, label + ".errorBoundary", errorBoundary(s.page));
                }
            }
        }
    }

    /** The iframe's resolved script assets, what the iframe document loads, and any conditional polyfill. */
    @Test
    void s05IframeAssets() throws Exception {
        String item = "s05";
        long post = createPost("alice", "publish", paragraph("Iframe QPS05."));
        try (Session s = session(item, "alice", "alice")) {
            openExisting(s.page, post);
            note(
                    item,
                    "resolvedScripts",
                    s.page.evaluate("() => wp.data.select('core/block-editor').getSettings()"
                            + ".__unstableResolvedAssets.scripts"));
            note(
                    item,
                    "resolvedStyleIds",
                    s.page.evaluate("() => { const d = document.createElement('div');"
                            + " d.innerHTML = wp.data.select('core/block-editor').getSettings()"
                            + ".__unstableResolvedAssets.styles;"
                            + " return [...d.children].map(e => e.tagName.toLowerCase() + '#' + e.id"
                            + " + (e.href ? ' ' + e.href : '')); }"));
            note(item, "iframe", iframeAssets(s.page));
            note(
                    item,
                    "polyfillRequests",
                    s.log.staticPaths(0).stream()
                            .filter(p -> p.contains("polyfill"))
                            .toList());
        }
    }

    /** SCRIPT_DEBUG cannot be switched from a must-use plugin: the unminified names, read from the release tree. */
    @Test
    void s06UnminifiedNames() throws Exception {
        String item = "s06";
        Path tree = wordpressTree();
        Path dist = tree.resolve("wp-includes/js/dist");
        List<String> minified = new ArrayList<>();
        List<String> withoutSibling = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dist)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = tree.relativize(file).toString();
                if (name.endsWith(".min.js")) {
                    minified.add(name);
                    if (!Files.exists(
                            file.resolveSibling(file.getFileName().toString().replace(".min.js", ".js")))) {
                        withoutSibling.add(name);
                    }
                }
            }
        }
        note(item, "distMinifiedCount", minified.size());
        note(item, "distMinifiedWithoutSibling", withoutSibling);
        note(item, "reactRefresh", listNames(dist.resolve("development")));
        note(item, "tinymceRootFiles", listNames(tree.resolve("wp-includes/js/tinymce")));
        note(
                item,
                "viewTransitionsCss",
                listNames(tree.resolve("wp-admin/css")).stream()
                        .filter(n -> n.startsWith("view-transitions"))
                        .toList());
        JsonNode packages = phpArray(tree.resolve("wp-includes/assets/script-loader-packages.php"));
        List<String> keys = new ArrayList<>();
        packages.fieldNames().forEachRemaining(keys::add);
        note(item, "packageManifests", listNames(tree.resolve("wp-includes/assets")));
        note(item, "packageKeys", keys);
        note(item, "editPostEntry", packages.path("edit-post.js"));
        note(
                item,
                "verdict",
                "SCRIPT_DEBUG not switched (wp_initial_constants defines it before must-use plugins"
                        + " load); file names read from the release tree only");
    }

    /** The editor without the admin-chrome stylesheets: what changes inside #editor. */
    @Test
    void s07AdminChromeStyles() throws Exception {
        String item = "s07";
        long post = createPost("alice", "publish", paragraph("Chrome QPS07."));
        Map<String, BufferedImage> shots = new LinkedHashMap<>();
        for (String variant : List.of("baseline", "qw_parity_no_chrome_css")) {
            try (Switches ignored = variant.equals("baseline")
                            ? Switches.on("qw_parity_no_concat")
                            : Switches.on("qw_parity_no_concat", variant);
                    Session s = session(item, variant, "alice")) {
                openExisting(s.page, post);
                closeWelcomeGuide(s.page);
                awaitQuiet(s.page);
                note(
                        item,
                        variant + ".stylesheets",
                        s.page.evaluate("() => [...document.querySelectorAll('link[rel=stylesheet]')].map(l => l.id)"));
                note(
                        item,
                        variant + ".computed",
                        s.page.evaluate(
                                "() => { const pick = (sel) => { const e = document.querySelector(sel); if (!e) return null;"
                                        + " const c = getComputedStyle(e); return { font: c.fontFamily, size: c.fontSize,"
                                        + " color: c.color, background: c.backgroundColor, height: c.height,"
                                        + " position: c.position }; };"
                                        + " return { body: pick('body'), editor: pick('#editor'),"
                                        + " header: pick('.editor-header'), sidebar: pick('.editor-sidebar'),"
                                        + " wpwrap: pick('#wpwrap'), bodyClass: document.body.className }; }"));
                byte[] png = s.page.locator("#editor")
                        .screenshot(
                                new Locator.ScreenshotOptions().setPath(PAGES.resolve(item + "-" + variant + ".png")));
                shots.put(variant, ImageIO.read(new ByteArrayInputStream(png)));
            }
        }
        note(item, "differingPixels", differingPixels(shots.get("baseline"), shots.get("qw_parity_no_chrome_css")));
    }

    /** Translations need a language pack, which this offline harness cannot install. */
    @Test
    void s08Translations() throws Exception {
        String item = "s08";
        Path languages = wordpressTree().resolve("wp-content/languages");
        note(item, "languagesDirectory", Files.isDirectory(languages) ? listNames(languages) : List.of());
        note(
                item,
                "verdict",
                "unobservable: no de_DE language pack in the release tree and no outbound network to" + " install one");
    }

    /** The release zip from wordpress.org: the published sha1 and md5 verified, the SHA-256 computed. */
    @Test
    void s09ZipChecksums() throws Exception {
        String item = "s09";
        String version = System.getProperty("wp.version");
        String base = "https://wordpress.org/wordpress-" + version + ".zip";
        Files.createDirectories(WP_PIN);
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        Path zip = WP_PIN.resolve("wordpress-" + version + ".zip");
        HttpResponse<Path> download = http.send(
                HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofFile(zip));
        assertThat(download.statusCode()).as("GET %s", base).isEqualTo(200);
        String sha1 = fetchText(http, base + ".sha1");
        String md5 = fetchText(http, base + ".md5");
        Files.writeString(WP_PIN.resolve(zip.getFileName() + ".sha1"), sha1 + "\n");
        Files.writeString(WP_PIN.resolve(zip.getFileName() + ".md5"), md5 + "\n");
        byte[] bytes = Files.readAllBytes(zip);
        note(item, "url", base);
        note(item, "finalUrl", download.uri().toString());
        note(item, "bytes", bytes.length);
        note(item, "publishedSha1", sha1);
        note(item, "publishedMd5", md5);
        note(item, "sha1", digest("SHA-1", bytes));
        note(item, "md5", digest("MD5", bytes));
        note(item, "sha256", digest("SHA-256", bytes));
        note(item, "contentType", download.headers().firstValue("content-type").orElse(null));
        assertThat(digest("SHA-1", bytes)).as("sha1 of %s", zip).isEqualTo(sha1);
        assertThat(digest("MD5", bytes)).as("md5 of %s", zip).isEqualTo(md5);
    }

    /** A classic theme declaring wp-block-styles: the block-library theme sheet in the head and in the iframe. */
    @Test
    void s10BlockLibraryTheme() throws Exception {
        String item = "s10";
        long post = createPost("alice", "publish", paragraph("Theme sheet QPS10."));
        for (String variant : List.of("classic", "classic-with-wp-block-styles")) {
            List<String> on = new ArrayList<>(List.of("qw_parity_no_concat", "qw_parity_quarkword_theme"));
            if (variant.endsWith("styles")) {
                on.add("qw_parity_block_styles");
            }
            try (Switches ignored = Switches.on(on.toArray(String[]::new));
                    Session s = session(item, variant, "alice")) {
                Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
                String html = answer.text();
                awaitEditor(s.page);
                List<String> ids = new ArrayList<>();
                for (Map<String, String> tag : tags(html)) {
                    if ("link".equals(tag.get("tag"))) {
                        ids.add(tag.get("id") + ("head".equals(tag.get("group")) ? "" : " (footer)"));
                    }
                }
                note(item, variant + ".linkIds", ids);
                note(
                        item,
                        variant + ".theme",
                        s.page.evaluate("() => wp.data.select('core').getCurrentTheme() && wp.data.select('core')"
                                + ".getCurrentTheme().stylesheet"));
                note(
                        item,
                        variant + ".resolvedHasTheme",
                        s.page.evaluate(
                                "() => wp.data.select('core/block-editor').getSettings().__unstableResolvedAssets.styles"
                                        + ".includes('block-library/theme')"));
            }
        }
    }

    /** Response headers of a package file under php -S, and how the browser caches it without the forwarding route. */
    @Test
    void s11CacheHeaders() throws Exception {
        String item = "s11";
        long post = createPost("alice", "publish", paragraph("Cache QPS11."));
        HttpClient http = HttpClient.newHttpClient();
        URI direct = EditorHarCapture.site.root().resolve("/wp-includes/js/dist/edit-post.min.js?ver=qp");
        HttpResponse<byte[]> file =
                http.send(HttpRequest.newBuilder(direct).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        note(item, "status", file.statusCode());
        note(item, "headers", file.headers().map());
        try (BrowserContext context = EditorHarCapture.browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setRecordHarPath(EditorHarCapture.harDir.resolve("facts-" + item + "-alice.har"))
                .setRecordHarContent(HarContentPolicy.OMIT)
                .setRecordHarMode(HarMode.FULL)
                .setRecordHarUrlFilter(ALL_OF_WORDPRESS))) {
            // No forwarding route: Playwright turns the HTTP cache off for routed contexts. The launch's resolver rule
            // sends localhost:80 to the server, which presents every request as localhost anyway.
            context.setDefaultTimeout(EditorHarCapture.TIMEOUT_MS);
            Page page = context.newPage();
            page.navigate(BROWSER_ROOT + "/wp-login.php");
            page.locator("#user_login").fill("alice");
            page.locator("#user_pass").fill("alice");
            page.locator("#wp-submit").click();
            page.waitForURL(BROWSER_ROOT + "/wp-admin/**");
            CDPSession cdp = context.newCDPSession(page);
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            cdp.send("Network.enable");
            cdp.on("Network.responseReceived", event -> {
                JsonObject response = event.getAsJsonObject("response");
                String url = response.get("url").getAsString();
                if (url.contains("edit-post.min.js") || url.contains("/wp-includes/css/dashicons")) {
                    events.add(url.substring(BROWSER_ROOT.length()) + " status=" + response.get("status")
                            + " fromDiskCache=" + response.get("fromDiskCache")
                            + " fromPrefetchCache=" + response.get("fromPrefetchCache"));
                }
            });
            cdp.on("Network.requestServedFromCache", event -> events.add("servedFromCache " + event.get("requestId")));
            for (int i = 1; i <= 2; i++) {
                events.add("-- load " + i);
                page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
                page.waitForFunction(
                        "() => window.wp && wp.editPost && wp.data.select('core/editor')" + ".getCurrentPostId()");
                page.waitForTimeout(3000);
            }
            events.add("-- fetch with another ver");
            page.evaluate("() => fetch('/wp-includes/js/dist/edit-post.min.js?ver=qw-other').then(r => r.status)");
            page.waitForTimeout(1000);
            note(item, "cdpEvents", new ArrayList<>(events));
        }
    }

    /** The client-side media flags and the isolation header on localhost, and with the server deciding "insecure". */
    @Test
    void s12ClientSideMediaFlags() throws Exception {
        String item = "s12";
        long post = createPost("alice", "publish", paragraph("Flags QPS12."));
        for (String variant : List.of("localhost", "insecure-decision")) {
            try (Session s = session(item, variant, "alice")) {
                if (variant.equals("insecure-decision")) {
                    // The router presents every request as localhost, so a plain-HTTP host cannot reach the server's
                    // decision; the capture's own cookie makes the same decision through WordPress's filter.
                    s.context.addCookies(List.of(new Cookie(SERVER_MEDIA_COOKIE, "1").setUrl(BROWSER_ROOT + "/")));
                }
                Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
                String html = answer.text();
                awaitEditor(s.page);
                note(item, variant + ".userAgent", s.page.evaluate("() => navigator.userAgent"));
                note(
                        item,
                        variant + ".documentIsolationPolicy",
                        answer.allHeaders().get("document-isolation-policy"));
                note(item, variant + ".blockEditorBefore", fragmentsAround(html, "wp-block-editor-js-before", 300));
                note(
                        item,
                        variant + ".flags",
                        s.page.evaluate("() => ({ clientSide: window.__clientSideMediaProcessing ?? null,"
                                + " isolation: window.__documentIsolationPolicy ?? null,"
                                + " crossOriginIsolated: window.crossOriginIsolated,"
                                + " sharedArrayBuffer: typeof SharedArrayBuffer, secure: window.isSecureContext })"));
                note(item, variant + ".crossoriginAttributes", count(html, "crossorigin=\"anonymous\""));
            }
        }
    }

    // ---- plan additions ---------------------------------------------------------------------------------------------

    /** The inline settings scripts of an uncompressed page, as printed. */
    @Test
    void x01InlineSettingsTexts() throws Exception {
        String item = "x01";
        long post = createPost("alice", "publish", paragraph("Inline QPX01."));
        try (Switches ignored = Switches.on("qw_parity_no_concat");
                Session s = session(item, "alice", "alice")) {
            Response answer = s.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + post + "&action=edit");
            String html = answer.text();
            Files.writeString(PAGES.resolve("x01.html"), html);
            for (String id : List.of(
                    "wp-date-js-after",
                    "moment-js-after",
                    "wp-data-js-after",
                    "wp-preferences-js-after",
                    "wp-i18n-js-after")) {
                note(item, id, scriptText(html, id));
            }
        }
    }

    /** The oidc-client-ts tarball: published sha1 verified, SHA-256 and entry names (opt-in: one npm download). */
    @Test
    void x02OidcClientTarball() throws Exception {
        String item = "x02";
        if (!Boolean.getBoolean("facts.npm")) {
            note(item, "verdict", "not run: the download from registry.npmjs.org needs -Dfacts.npm=true");
        }
        assumeTrue(Boolean.getBoolean("facts.npm"), "the npm download is opt-in (-Dfacts.npm=true)");
        String url = "https://registry.npmjs.org/oidc-client-ts/-/oidc-client-ts-3.5.0.tgz";
        Files.createDirectories(WP_PIN);
        Path tgz = WP_PIN.resolve("oidc-client-ts-3.5.0.tgz");
        HttpResponse<Path> download = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(tgz));
        assertThat(download.statusCode()).as("GET %s", url).isEqualTo(200);
        byte[] bytes = Files.readAllBytes(tgz);
        note(item, "url", url);
        note(item, "bytes", bytes.length);
        note(item, "sha1", digest("SHA-1", bytes));
        note(item, "sha256", digest("SHA-256", bytes));
        note(item, "entries", tarEntries(bytes));
        // The registry's own record of the version: its dist.shasum and dist.integrity (sha512, base64).
        String metaUrl = "https://registry.npmjs.org/oidc-client-ts/3.5.0";
        HttpResponse<String> meta = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(HttpRequest.newBuilder(URI.create(metaUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode dist = OracleJson.MAPPER.readTree(meta.body()).path("dist");
        String sha512 = "sha512-"
                + java.util.Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes));
        note(item, "registryShasum", dist.path("shasum").asText());
        note(item, "registryIntegrity", dist.path("integrity").asText());
        note(item, "registryTarball", dist.path("tarball").asText());
        note(item, "sha512Integrity", sha512);
        note(item, "integrityMatches", sha512.equals(dist.path("integrity").asText()));
        assertThat(digest("SHA-1", bytes)).isEqualTo("222c145d35e8d654ea4e1dee71cc5dde5e1c91a2");
        assertThat(digest("SHA-1", bytes)).isEqualTo(dist.path("shasum").asText());
        assertThat(sha512).isEqualTo(dist.path("integrity").asText());
    }

    /** The default editor styles file in the zip, and whether it is what the editor settings carry. */
    @Test
    void x03DefaultEditorStyles() throws Exception {
        String item = "x03";
        Path tree = wordpressTree();
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : List.of("default-editor-styles.css", "default-editor-styles.min.css")) {
            Path file = tree.resolve("wp-includes/css/dist/block-editor").resolve(name);
            files.put(name, Files.exists(file) ? Files.readString(file) : null);
            note(item, name + ".exists", Files.exists(file));
            note(item, name + ".bytes", Files.exists(file) ? Files.size(file) : null);
            note(item, name + ".sha256", Files.exists(file) ? digest("SHA-256", Files.readAllBytes(file)) : null);
        }
        note(item, "minifiedText", files.get("default-editor-styles.min.css"));
        long post = createPost("alice", "publish", paragraph("Default styles QPX03."));
        try (Session s = session(item, "alice", "alice")) {
            openExisting(s.page, post);
            Object styles = s.page.evaluate(
                    "() => wp.data.select('core/editor').getEditorSettings().defaultEditorStyles || null");
            note(item, "settingsDefaultEditorStyles", styles);
            String css = s.page.evaluate("() => { const s = wp.data.select('core/editor').getEditorSettings()"
                                    + ".defaultEditorStyles; return s && s[0] ? s[0].css : null; }")
                            instanceof String text
                    ? text
                    : null;
            for (Map.Entry<String, String> file : files.entrySet()) {
                note(item, "equals." + file.getKey(), css != null && css.equals(file.getValue()));
                note(
                        item,
                        "equalsTrimmed." + file.getKey(),
                        css != null
                                && file.getValue() != null
                                && css.strip().equals(file.getValue().strip()));
            }
        }
    }

    /** The lock dialog's texts (locked, and after a takeover) and the list's "moved to the Trash" notice. */
    @Test
    void x04LockAndTrashTexts() throws Exception {
        String item = "x04";
        long locked = createPost("alice", "publish", paragraph("Locked QPX04."));
        EditorHarCapture.site.operation(
                "lock",
                args().put("site", MAIN).put("post", locked).put("user", "bob").put("age", 0));
        try (Session alice = session(item, "alice-locked", "alice")) {
            alice.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + locked + "&action=edit");
            Locator modal = alice.page.locator(".editor-post-locked-modal");
            modal.first().waitFor();
            note(item, "lockedModal.text", modal.first().innerText());
            note(item, "lockedModal.html", modal.first().innerHTML());
            note(item, "lockedModal.links", links(modal.first()));
            note(
                    item,
                    "lockedModal.title",
                    alice.page.evaluate("() => { const h = document.querySelector('.editor-post-locked-modal h1,"
                            + " .components-modal__header-heading'); return h ? h.innerText : null; }"));
        }
        long taken = createPost("bob", "publish", paragraph("Taken QPX04."));
        try (Session bob = session(item, "bob-loser", "bob");
                Session alice = session(item, "alice-taker", "alice")) {
            openExisting(bob.page, taken);
            alice.page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + taken + "&action=edit");
            alice.page.locator(".editor-post-locked-modal").first().waitFor();
            alice.page.waitForResponse(
                    r -> r.url().contains("get-post-lock"),
                    () -> alice.page
                            .locator(".editor-post-locked-modal__buttons a:text-is(\"Take over\")")
                            .first()
                            .click());
            alice.page.waitForLoadState();
            awaitEditor(alice.page);
            bob.page.waitForResponse(
                    r -> r.url().contains("admin-ajax.php")
                            && String.valueOf(r.request().postData()).contains("heartbeat"),
                    () -> bob.page.evaluate("() => wp.heartbeat.connectNow()"));
            Locator modal = bob.page.locator(".editor-post-locked-modal");
            if (waitVisible(modal, 15_000)) {
                note(item, "takenOverModal.text", modal.first().innerText());
                note(item, "takenOverModal.html", modal.first().innerHTML());
                note(item, "takenOverModal.links", links(modal.first()));
            } else {
                note(item, "takenOverModal.text", null);
            }
        }
        long trashed = createPost("alice", "publish", paragraph("Trashed QPX04."));
        try (Session alice = session(item, "alice-trash", "alice")) {
            openExisting(alice.page, trashed);
            closeWelcomeGuide(alice.page);
            alice.page.evaluate("() => wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/document')");
            awaitQuiet(alice.page);
            Locator trash = alice.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Move to trash"));
            note(item, "trashButtons", trash.count());
            if (trash.count() > 0) {
                trash.first().click(new Locator.ClickOptions().setTimeout(10_000));
                alice.page.waitForTimeout(3000);
                note(
                        item,
                        "trashDialogs",
                        alice.page.evaluate(
                                "() => [...document.querySelectorAll('[role=dialog], .components-confirm-dialog')]"
                                        + ".map(d => ({ className: d.className, text: d.innerText.slice(0, 400),"
                                        + " buttons: [...d.querySelectorAll('button')].map(b => b.innerText.trim()) }))"));
                Locator confirm = alice.page.locator(
                        "[role=dialog] button.is-primary, .components-confirm-dialog button.is-primary");
                if (confirm.count() > 0) {
                    note(item, "trashConfirm.button", confirm.first().innerText());
                    confirm.first().click(new Locator.ClickOptions().setTimeout(10_000));
                }
            }
            if (!waitUrl(alice.page, "edit.php", 30_000)) {
                note(item, "trashFallback", "no navigation after the UI: core/editor trashPost() dispatched");
                alice.page.evaluate("() => wp.data.dispatch('core/editor').trashPost()");
            }
            if (!waitUrl(alice.page, "edit.php", 60_000)) {
                note(item, "trashUrl", alice.page.url());
                note(item, "trashNotices", notices(alice.page));
                return;
            }
            alice.page.waitForLoadState();
            note(item, "trashUrl", alice.page.url());
            Locator message = alice.page.locator("#message");
            note(item, "trashNotice.text", message.count() > 0 ? message.first().innerText() : null);
            note(item, "trashNotice.html", message.count() > 0 ? message.first().innerHTML() : null);
        }
    }

    // ---- harness ----------------------------------------------------------------------------------------------------

    /** One browser context as one user (or anonymous), with its HAR and its request/console log. */
    static final class Session implements AutoCloseable {
        final BrowserContext context;
        final Log log;
        final Path har;
        final Page page;

        Session(BrowserContext context, Log log, Path har, Page page) {
            this.context = context;
            this.log = log;
            this.har = har;
            this.page = page;
        }

        @Override
        public void close() {
            context.close();
        }
    }

    static Session session(String item, String label, String user) throws IOException {
        return session(item, label, user, HarContentPolicy.OMIT);
    }

    /** As above; {@code EMBED} keeps the request bodies too (the a09 firm reading needs admin-ajax's form data). */
    static Session session(String item, String label, String user, HarContentPolicy content) throws IOException {
        Path har = EditorHarCapture.harDir.resolve("facts-" + item + "-" + label + ".har");
        Files.deleteIfExists(har);
        BrowserContext context = EditorHarCapture.browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setRecordHarPath(har)
                .setRecordHarContent(content)
                .setRecordHarMode(HarMode.FULL)
                .setRecordHarUrlFilter(ALL_OF_WORDPRESS));
        Log log = Log.attach(context);
        direct(context);
        Page page = context.newPage();
        if (user != null) {
            page.navigate(BROWSER_ROOT + "/wp-login.php");
            page.locator("#user_login").fill(user);
            page.locator("#user_pass").fill(user);
            page.locator("#wp-submit").click();
            page.waitForURL(BROWSER_ROOT + "/wp-admin/**");
        }
        return new Session(context, log, har, page);
    }

    /**
     * A context without the capture's forwarding route: the launch's resolver rule already sends {@code localhost:80}
     * to the server, which presents every request as one to localhost. Without the route no request waits on a
     * Playwright-side fetch (a forwarded request could stay pending for the route's whole timeout), and the browser's
     * own HTTP cache stays on, as for a user.
     */
    static void direct(BrowserContext context) {
        context.setDefaultTimeout(EditorHarCapture.TIMEOUT_MS);
        context.setDefaultNavigationTimeout(EditorHarCapture.TIMEOUT_MS);
        context.onPage(EditorHarCapture::track);
    }

    static final Map<BrowserContext, Log> LOGS = new ConcurrentHashMap<>();

    /**
     * Waits (at most 120 s, never failing) until no request has started or ended for 1.5 s and no request younger than
     * 20 s is in flight. Some editor requests never end in this browser (one stayed pending for minutes while the HAR
     * shows its answer, another never reached the server), so an older pending request does not hold the wait; this
     * run records, it does not assert timing.
     */
    static void awaitQuiet(Page page) {
        Log log = LOGS.get(page.context());
        long start = System.nanoTime();
        long deadline = start + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            long now = System.nanoTime();
            boolean young = log != null
                    && log.pending.values().stream()
                            .anyMatch(t -> now - t < Duration.ofSeconds(20).toNanos());
            // The quiet window starts no earlier than the call: requests an action triggers may not have been
            // reported yet when the wait begins.
            long idle = now - Math.max(start, log == null ? start : log.activity);
            if (!young && idle >= Duration.ofMillis(1500).toNanos()) {
                return;
            }
            page.waitForTimeout(100);
        }
    }

    static void awaitEditor(Page page) {
        page.waitForFunction(
                "() => window.wp && wp.data && wp.blocks && wp.data.select('core/editor')"
                        + " && wp.data.select('core/editor').getCurrentPostId()"
                        + " && wp.data.select('core/block-editor').getSettings().__experimentalFetchLinkSuggestions"
                        + " && document.querySelector('iframe[name=\"editor-canvas\"]')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(EditorHarCapture.TIMEOUT_MS));
        awaitQuiet(page);
    }

    static void openNew(Page page) {
        page.navigate(BROWSER_ROOT + "/wp-admin/post-new.php");
        awaitEditor(page);
    }

    static void openExisting(Page page, long id) {
        page.navigate(BROWSER_ROOT + "/wp-admin/post.php?post=" + id + "&action=edit");
        awaitEditor(page);
    }

    /** As the capture's: the welcome guide's Close button, or Escape; bounded, and a guide that stays is recorded. */
    static void closeWelcomeGuide(Page page) {
        Locator overlay = page.locator(".components-modal__screen-overlay");
        if (overlay.count() == 0) {
            return;
        }
        try {
            Locator close = overlay.first().getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Close"));
            if (close.count() > 0) {
                close.first().click(new Locator.ClickOptions().setTimeout(10_000));
            } else {
                page.keyboard().press("Escape");
            }
            overlay.first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
                            .setTimeout(10_000));
        } catch (PlaywrightException e) {
            System.err.println("closeWelcomeGuide on " + page.url() + ": " + e.getMessage());
            page.keyboard().press("Escape");
        }
        awaitQuiet(page);
    }

    /** One request as seen by the browser. */
    static final class Entry {
        final int seq;
        final long at = System.currentTimeMillis();
        final String method;
        final String url;
        final String type;
        final String postData;
        volatile int status = -1;
        /** Kept only for REST writes and admin-ajax; read after the observation, never inside a listener. */
        volatile Response response;

        volatile boolean finished;
        volatile String body;
        volatile String failure;

        Entry(int seq, Request request) {
            this.seq = seq;
            this.method = request.method();
            this.url = request.url().startsWith(BROWSER_ROOT)
                    ? request.url().substring(BROWSER_ROOT.length())
                    : request.url();
            this.type = request.resourceType();
            String data = null;
            if (!"GET".equals(method) && dynamic(url)) {
                try {
                    data = request.postData();
                } catch (PlaywrightException e) {
                    data = "(unreadable: " + e.getMessage() + ")";
                }
            }
            this.postData = data == null ? null : clip(decode(data), 3000);
        }

        /**
         * The response body, read on first use from the test thread and only once the request has finished: a body
         * read inside an event listener blocks the dispatcher when the response never completes.
         */
        String body() {
            if (body == null && response != null && finished && failure == null) {
                try {
                    body = clip(response.text(), 3000);
                } catch (PlaywrightException e) {
                    body = "(no body: " + clip(e.getMessage(), 200) + ")";
                }
            }
            return body;
        }

        ObjectNode json() {
            ObjectNode node = OracleJson.MAPPER.createObjectNode();
            node.put("seq", seq);
            node.put("at", at);
            node.put("method", method);
            node.put("url", url);
            node.put("type", type);
            node.put("status", status);
            if (postData != null) {
                node.put("postData", postData);
            }
            String text = body();
            if (text != null) {
                node.put("body", text);
            }
            if (failure != null) {
                node.put("failure", failure);
            }
            return node;
        }
    }

    /** Every request, console message and page error of one context, in order. */
    static final class Log {
        final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
        final Map<Request, Entry> byRequest = new ConcurrentHashMap<>();
        final List<String> console = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        final Map<Request, Long> pending = new ConcurrentHashMap<>();
        volatile long activity = System.nanoTime();

        static Log attach(BrowserContext context) {
            Log log = new Log();
            LOGS.put(context, log);
            context.onRequest(request -> {
                log.activity = System.nanoTime();
                log.pending.put(request, System.nanoTime());
                Entry entry = new Entry(log.entries.size(), request);
                log.byRequest.put(request, entry);
                log.entries.add(entry);
            });
            context.onResponse(response -> {
                Entry entry = log.byRequest.get(response.request());
                if (entry != null) {
                    entry.status = response.status();
                    if (EditorBrowserFactsDbTest.dynamic(entry.url)
                            && (!"GET".equals(entry.method) || entry.url.contains("admin-ajax"))) {
                        entry.response = response;
                    }
                }
            });
            context.onRequestFinished(request -> {
                log.activity = System.nanoTime();
                log.pending.remove(request);
                Entry entry = log.byRequest.get(request);
                if (entry != null) {
                    entry.finished = true;
                }
            });
            context.onRequestFailed(request -> {
                log.activity = System.nanoTime();
                log.pending.remove(request);
                Entry entry = log.byRequest.get(request);
                if (entry != null) {
                    entry.failure = request.failure();
                }
            });
            context.onConsoleMessage(message -> log.console.add(message.type() + ": " + clip(message.text(), 1500)));
            context.onWebError(error -> log.errors.add(clip(error.error(), 1500)));
            return log;
        }

        int size() {
            return entries.size();
        }

        int errorCount() {
            return errors.size();
        }

        List<String> console() {
            synchronized (console) {
                return new ArrayList<>(console);
            }
        }

        List<String> errors() {
            synchronized (errors) {
                return new ArrayList<>(errors);
            }
        }

        List<String> errorsFrom(int from) {
            synchronized (errors) {
                return new ArrayList<>(errors.subList(from, errors.size()));
            }
        }

        ArrayNode matching(int from, java.util.function.Predicate<Entry> test) {
            ArrayNode out = OracleJson.MAPPER.createArrayNode();
            List<Entry> copy;
            synchronized (entries) {
                copy = new ArrayList<>(entries.subList(from, entries.size()));
            }
            // Reading a body lets Playwright dispatch events, which may add entries: iterate over a copy.
            for (Entry entry : copy) {
                if (test.test(entry)) {
                    out.add(entry.json());
                }
            }
            return out;
        }

        /** REST and admin-ajax requests from {@code from} on. */
        ArrayNode dynamic(int from) {
            return matching(from, e -> EditorBrowserFactsDbTest.dynamic(e.url));
        }

        /** Paths (without query) of the static files fetched from {@code from} on, in first-fetch order. */
        List<String> staticPaths(int from) {
            Set<String> paths = new LinkedHashSet<>();
            synchronized (entries) {
                for (Entry entry : entries.subList(from, entries.size())) {
                    if (!EditorBrowserFactsDbTest.dynamic(entry.url) && entry.url.startsWith("/wp-")) {
                        int query = entry.url.indexOf('?');
                        paths.add(query < 0 ? entry.url : entry.url.substring(0, query));
                    }
                }
            }
            return new ArrayList<>(paths);
        }

        Integer statusOf(String absoluteUrl) {
            String url =
                    absoluteUrl.startsWith(BROWSER_ROOT) ? absoluteUrl.substring(BROWSER_ROOT.length()) : absoluteUrl;
            synchronized (entries) {
                for (Entry entry : entries) {
                    if (entry.url.equals(url)) {
                        return entry.status;
                    }
                }
            }
            return null;
        }
    }

    /** Site options of the main site set to "1" for one item, reset to "0" when closed. */
    static final class Switches implements AutoCloseable {
        final List<String> names;

        private Switches(List<String> names) {
            this.names = names;
        }

        static Switches on(String... names) throws IOException, InterruptedException {
            Switches switches = new Switches(List.of(names));
            for (String name : names) {
                EditorHarCapture.site.option(name, TextNode.valueOf("1"));
            }
            return switches;
        }

        @Override
        public void close() throws IOException {
            try {
                for (String name : names) {
                    EditorHarCapture.site.option(name, TextNode.valueOf("0"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while resetting " + names, e);
            }
        }
    }

    @FunctionalInterface
    interface Observation {
        Object run() throws Exception;
    }

    /** Runs one observation; a failure is recorded as its value instead of ending the item. */
    static Object attempt(Session s, Observation observation) {
        int mark = s.log.size();
        int errors = s.log.errorCount();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("result", observation.run());
        } catch (Exception | AssertionError e) {
            out.put("failure", clip(String.valueOf(e.getMessage()), 1500));
            try {
                s.page.keyboard().press("Escape");
            } catch (PlaywrightException ignored) {
                // nothing left to close
            }
        }
        out.put("adminAjax", s.log.matching(mark, e -> e.url.contains("admin-ajax.php")));
        out.put("pageErrors", s.log.errorsFrom(errors));
        return out;
    }

    /** Clicks a media button and records the dialog it opens (if any), then closes it with Escape. */
    static int shots;

    static Object openMediaButton(Session s, Locator button) {
        int mark = s.log.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buttons", button.count());
        button.first().click(new Locator.ClickOptions().setTimeout(15_000));
        Locator dialog = s.page.locator("[role=dialog]:visible, .media-modal:visible");
        out.put("opened", waitVisible(dialog, 10_000));
        awaitQuiet(s.page);
        String script = "() => [...document.querySelectorAll('[role=dialog], .media-modal, .components-modal__frame')]"
                + ".map(d => ({ className: String(d.className).slice(0, 200), label: d.getAttribute('aria-label'),"
                + " visible: !!(d.offsetWidth || d.offsetHeight), text: d.innerText.slice(0, 300) }))";
        out.put("dialogs", s.page.evaluate(script));
        out.put("canvasDialogs", canvasEval(s.page, script));
        Path shot = PAGES.resolve("a09-" + (++shots) + ".png");
        s.page.screenshot(new Page.ScreenshotOptions().setPath(shot));
        out.put("screenshot", shot.toString());
        out.put("legacyModal", s.page.locator(".media-modal").count());
        out.put("requests", s.log.dynamic(mark));
        s.page.keyboard().press("Escape");
        awaitQuiet(s.page);
        return out;
    }

    static boolean waitVisible(Locator locator, double timeout) {
        try {
            locator.first().waitFor(new Locator.WaitForOptions().setTimeout(timeout));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    static boolean waitUrl(Page page, String part, double timeout) {
        try {
            page.waitForURL(u -> u.contains(part), new Page.WaitForURLOptions().setTimeout(timeout));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    static boolean tryAwaitEditor(Page page, double timeout) {
        try {
            page.waitForFunction(
                    "() => window.wp && wp.data && wp.data.select('core/editor')"
                            + " && wp.data.select('core/editor').getCurrentPostId()"
                            + " && document.querySelector('iframe[name=\"editor-canvas\"]')",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(timeout));
            awaitQuiet(page);
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /** Opens the preview from the header's preview menu (as a user does) and answers the window once it has loaded. */
    static Page preview(Page page) {
        Page popup = page.waitForPopup(() -> {
            page.locator(".editor-preview-dropdown__toggle").first().click();
            page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Preview in new tab"))
                    .first()
                    .click();
        });
        popup.waitForURL(u -> !u.startsWith("about:"), new Page.WaitForURLOptions().setTimeout(60_000));
        popup.waitForLoadState();
        awaitQuiet(page);
        return popup;
    }

    static Object notices(Page page) {
        return page.evaluate("() => wp.data.select('core/notices').getNotices().map(n => ({ status: n.status,"
                + " content: n.content, type: n.type, id: n.id,"
                + " actions: (n.actions || []).map(a => ({ label: a.label, url: a.url || null })) }))");
    }

    static Object errorBoundary(Page page) {
        return page.evaluate("() => { const e = document.querySelector('.editor-error-boundary');"
                + " return e ? e.innerText.slice(0, 500) : null; }");
    }

    static Object links(Locator scope) {
        return scope.evaluate("e => [...e.querySelectorAll('a, button')].map(a => ({ text: a.innerText.trim(),"
                + " href: a.getAttribute('href') }))");
    }

    static Frame canvasFrame(Page page) {
        return page.frame("editor-canvas");
    }

    static Object canvasEval(Page page, String script) {
        Frame frame = canvasFrame(page);
        return frame == null ? "no editor-canvas frame" : frame.evaluate(script);
    }

    static Object iframeAssets(Page page) {
        return canvasEval(
                page,
                "() => ({ scripts: [...document.querySelectorAll('script')].map(s => s.src || ('inline:' + s.id)),"
                        + " links: [...document.querySelectorAll('link[rel=stylesheet]')].map(l => l.id + ' '"
                        + " + l.getAttribute('href')),"
                        + " styles: [...document.querySelectorAll('style')].map(s => s.id || ('(' + s.textContent"
                        + ".length + ' chars)')) })");
    }

    /** Lets the context deliver the events of a closed page: a blank page waits there. */
    static void settle(BrowserContext context, double millis) {
        Page idle = context.newPage();
        idle.waitForTimeout(millis);
        idle.close();
    }

    static String safeText(Response response) {
        try {
            return response.text();
        } catch (PlaywrightException e) {
            return "(no body: " + e.getMessage() + ")";
        }
    }

    static boolean dynamic(String url) {
        return url.contains("/wp-json/") || url.contains("rest_route=") || url.contains("admin-ajax.php");
    }

    static String decode(String data) {
        if (data == null) {
            return null;
        }
        try {
            return URLDecoder.decode(data, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return data;
        }
    }

    static String clip(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…(" + text.length() + ")";
    }

    static String paragraph(String text) {
        return "<!-- wp:paragraph -->\n<p>" + text + "</p>\n<!-- /wp:paragraph -->";
    }

    static String stem(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static void note(String item, String key, Object value) {
        VerificationFacts.note(item, key, value instanceof JsonNode node ? node : OracleJson.MAPPER.valueToTree(value));
    }

    static JsonNode meta(long post, String key) throws IOException, InterruptedException {
        return EditorHarCapture.site
                .operation("meta", args().put("site", MAIN).put("post", post).put("key", key))
                .path("values");
    }

    /** Sets the post's modified (and published) date {@code hours} back, so a new autosave is newer. */
    static void backdate(long post, int hours) throws IOException, InterruptedException {
        String at = LocalDateTime.ofInstant(Instant.now().minus(hours, ChronoUnit.HOURS), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        ObjectNode args = args().put("site", MAIN);
        args.putArray("posts").addObject().put("id", post).put("at", at).put("date", true);
        EditorHarCapture.site.operation("backdate", args);
    }

    /** Creates a post through REST as alice, authored by {@code author}; answers its id. */
    static long createPost(String author, String status, String content) throws IOException, InterruptedException {
        ObjectNode body = args();
        body.put("title", "Browser facts " + EditorHarCapture.FIXTURES.incrementAndGet());
        body.put("content", content);
        body.put("status", status);
        body.put("author", EditorHarCapture.site.userId(author));
        Exchange created =
                EditorHarCapture.client.send(RestCall.json("alice", "POST", "/wp-json/wp/v2/posts", body.toString()));
        assertThat(created.status()).as("create post: %s", created.text()).isEqualTo(201);
        return created.json().get("id").longValue();
    }

    static Path wordpressTree() throws IOException, InterruptedException {
        return org.quarkword.testing.wpscenario.WordPressTree.ensure(System.getProperty("wp.version"));
    }

    static List<String> listNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    /** A PHP file that returns an array, as JSON (through the harness's PHP). */
    static JsonNode phpArray(Path file) throws IOException, InterruptedException {
        Process php = new ProcessBuilder(
                        System.getProperty("wp.php", "php"),
                        "-r",
                        "echo json_encode(include $argv[1]);",
                        file.toString())
                .redirectErrorStream(false)
                .start();
        byte[] out = php.getInputStream().readAllBytes();
        php.waitFor();
        return OracleJson.MAPPER.readTree(out);
    }

    /** The script and stylesheet tags of a page in document order, with the group (head or footer) of each. */
    static List<Map<String, String>> tags(String html) {
        int headEnd = html.indexOf("</head>");
        List<Map<String, String>> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int script = html.indexOf("<script", from);
            int link = html.indexOf("<link", from);
            int at = script < 0 ? link : link < 0 ? script : Math.min(script, link);
            if (at < 0) {
                break;
            }
            int end = html.indexOf('>', at);
            if (end < 0) {
                break;
            }
            String tag = html.substring(at, end + 1);
            boolean isScript = at == script;
            from = end + 1;
            if (!isScript && !tag.contains("stylesheet")) {
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("tag", isScript ? "script" : "link");
            entry.put("id", attribute(tag, "id"));
            String src = attribute(tag, isScript ? "src" : "href");
            if (src != null) {
                entry.put("src", src.startsWith(BROWSER_ROOT) ? src.substring(BROWSER_ROOT.length()) : src);
            }
            String type = attribute(tag, "type");
            if (type != null) {
                entry.put("type", type);
            }
            entry.put("group", headEnd >= 0 && at < headEnd ? "head" : "footer");
            if (isScript && src == null) {
                int close = html.indexOf("</script>", end);
                entry.put(
                        "inline",
                        clip(
                                html.substring(end + 1, close < 0 ? end + 1 : close)
                                        .strip(),
                                160));
            }
            out.add(entry);
        }
        return out;
    }

    static String attribute(String tag, String name) {
        for (char quote : new char[] {'"', '\''}) {
            String marker = " " + name + "=" + quote;
            int at = tag.indexOf(marker);
            if (at >= 0) {
                int start = at + marker.length();
                int end = tag.indexOf(quote, start);
                return end < 0 ? null : tag.substring(start, end);
            }
        }
        return null;
    }

    /** The inner text of the inline script with this id. */
    static String scriptText(String html, String id) {
        for (String marker : List.of("id=\"" + id + "\"", "id='" + id + "'")) {
            int at = html.indexOf(marker);
            if (at >= 0) {
                int start = html.indexOf('>', at) + 1;
                int end = html.indexOf("</script>", start);
                return end < 0 ? null : html.substring(start, end).strip();
            }
        }
        return null;
    }

    /** Every place {@code marker} occurs, with {@code radius} characters around it. */
    static List<String> fragmentsAround(String html, String marker, int radius) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (out.size() < 20) {
            int at = html.indexOf(marker, from);
            if (at < 0) {
                break;
            }
            int start = Math.max(0, html.lastIndexOf('<', at));
            if (at - start > radius) {
                start = at - 40;
            }
            out.add(html.substring(start, Math.min(html.length(), at + radius)));
            from = at + Math.max(marker.length(), radius);
        }
        return out;
    }

    /** The byte size in the release tree of each path ({@code -1} when absent). */
    static Map<String, Long> sizes(List<String> paths) throws IOException, InterruptedException {
        Path tree = wordpressTree();
        Map<String, Long> out = new LinkedHashMap<>();
        for (String path : paths) {
            Path file = tree.resolve(path.substring(1));
            out.put(path, Files.exists(file) ? Files.size(file) : -1L);
        }
        return out;
    }

    static int count(String text, String marker) {
        int n = 0;
        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + marker.length())) {
            n++;
        }
        return n;
    }

    /** Sizes of both images and the differing pixels over their common top-left area. */
    static Map<String, Object> differingPixels(BufferedImage a, BufferedImage b) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (a == null || b == null) {
            out.put("missing", true);
            return out;
        }
        out.put("baseline", a.getWidth() + "x" + a.getHeight());
        out.put("switched", b.getWidth() + "x" + b.getHeight());
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long n = 0;
        int firstRow = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    n++;
                    if (firstRow < 0) {
                        firstRow = y;
                    }
                }
            }
        }
        out.put("differingInCommonArea", n);
        out.put("firstDifferingRow", firstRow);
        return out;
    }

    /** The admin-ajax actions of a HAR's requests, with their counts. */
    static Map<String, Integer> adminAjaxActions(Path har) throws IOException {
        Map<String, Integer> actions = new LinkedHashMap<>();
        for (JsonNode entry :
                OracleJson.MAPPER.readTree(har.toFile()).path("log").path("entries")) {
            JsonNode request = entry.path("request");
            String url = request.path("url").asText();
            if (!url.contains("admin-ajax.php")) {
                continue;
            }
            String action = null;
            for (JsonNode param : request.path("queryString")) {
                if ("action".equals(param.path("name").asText())) {
                    action = param.path("value").asText();
                }
            }
            String text = decode(request.path("postData").path("text").asText(""));
            for (String pair : text.split("&")) {
                if (pair.startsWith("action=")) {
                    action = pair.substring("action=".length());
                }
            }
            for (JsonNode param : request.path("postData").path("params")) {
                if ("action".equals(param.path("name").asText())) {
                    action = param.path("value").asText();
                }
            }
            actions.merge(String.valueOf(action), 1, Integer::sum);
        }
        return actions;
    }

    static String fetchText(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> answer =
                http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as("GET %s", url).isEqualTo(200);
        return answer.body().strip();
    }

    static String digest(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    /** The entry names of a gzipped tar (ustar headers walked by hand). */
    static List<String> tarEntries(byte[] tgz) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(tgz))) {
            byte[] header = new byte[512];
            while (in.readNBytes(header, 0, 512) == 512) {
                if (header[0] == 0) {
                    break;
                }
                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                long size = Long.parseLong(
                        cString(header, 124, 12).strip().isEmpty()
                                ? "0"
                                : cString(header, 124, 12).strip(),
                        8);
                names.add(prefix.isEmpty() ? name : prefix + "/" + name);
                long padded = (size + 511) / 512 * 512;
                in.skipNBytes(padded);
            }
        }
        return new ArrayList<>(new TreeSet<>(names));
    }

    static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
