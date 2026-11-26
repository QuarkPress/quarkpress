package org.quarkword.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One scenario: setup steps on a fresh subdirectory site, then requests. Request targets, header values and bodies may
 * name refs in braces ({@code {t:news}}, {@code {u:carol}}, {@code {p:draft}}, {@code {m:photo}}); each side replaces
 * them by its own ids before sending (see {@link #call}). Each request has a {@link Form}: its body as given, a
 * multipart upload of a fixture, or a fixture's bytes as the body, whether it is sent over HTTPS, and whether it is a
 * preload comparison (the editor route's GET, whose {@code preload} map both sides answer). {@code editor}
 * marks a scenario that needs an image editor on WordPress's side (PHP's GD): its setup uploads make their sizes, and
 * without GD it cannot run there.
 *
 * <p>{@code front} marks a front-end scenario: its requests are page requests of an anonymous visitor (a request's
 * {@code session} names the user whose login session it carries), its first setup step sets
 * {@code permalink_structure}, and only its answers' status and a few headers are compared ({@link RestComparison}).
 * A front-end request's target may hold {@code {preview:p:<ref>}}: the query part of a valid preview link of that
 * post for the session's user, which each side fetches before sending ({@link Side#resolvePreview}).
 */
public record RestScenario(
        String id,
        String rule,
        boolean editor,
        boolean front,
        List<SetupStep> setup,
        List<RestCall> requests,
        List<Form> forms) {

    /** The callers a request may name. */
    public static final List<String> CALLERS = List.of("anonymous", "invalid", "alice", "bob", "carol", "dave", "erin");

    /** The refs every scenario's site starts with: its default category and the five users. */
    public static final List<String> STARTING_REFS =
            List.of("t:uncategorized", "u:alice", "u:bob", "u:carol", "u:dave", "u:erin");

    /** The kinds of refs: terms, users, posts, created items ({@code c:}), revisions or autosaves, and media. */
    static final String REF_KINDS = "tupcrm";

    /** The start of a preview placeholder in a front-end request's target: {@code {preview:p:<ref>}}. */
    public static final String PREVIEW = "{preview:";

    /**
     * The keys a media step's {@code fields} may hold: the text fields of the setup upload, and
     * {@code generate_sub_sizes} ({@code "false"} only), with which an editor scenario stores an image without its
     * sizes.
     */
    public static final Set<String> MEDIA_FIELDS =
            Set.of("post", "title", "caption", "description", "alt_text", "status", "generate_sub_sizes");

    /**
     * A multipart upload: {@code fixture}'s bytes as the {@code file} part named {@code name} with the declared
     * {@code type}, then one text part per field, in order (refs in braces in their values resolved).
     */
    public record Upload(MediaScenario.Fixture fixture, String name, String type, Map<String, String> fields) {
        public Upload {
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /**
     * How a request is sent beyond its call: as a multipart {@code upload}, with a {@code raw} fixture's bytes as its
     * body (the call's content type and header lines kept), or as given; and over HTTPS when {@code https} is true.
     */
    public record Form(
            Optional<Upload> upload, Optional<MediaScenario.Fixture> raw, boolean https, Optional<Preload> preload) {

        /** A request sent as given, over plain HTTP. */
        public static final Form PLAIN = new Form(Optional.empty(), Optional.empty(), false);

        public Form {
            Objects.requireNonNull(upload, "upload");
            Objects.requireNonNull(raw, "raw");
            Objects.requireNonNull(preload, "preload");
            if (upload.isPresent() && raw.isPresent()) {
                throw new IllegalArgumentException("a request is an upload or a raw body, not both");
            }
            if (preload.isPresent() && (upload.isPresent() || raw.isPresent())) {
                throw new IllegalArgumentException("a preload comparison sends no body");
            }
        }

        /** A request that is not a preload comparison. */
        public Form(Optional<Upload> upload, Optional<MediaScenario.Fixture> raw, boolean https) {
            this(upload, raw, https, Optional.empty());
        }
    }

    /**
     * A preload comparison: the editor route's GET for the post of {@code type} named by {@code post} (a {@code p:}
     * ref in braces), whose {@code preload} map QuarkWord's side answers and WordPress's side builds from the same
     * requests ({@link EditorPreloadPaths}) sent as the caller.
     */
    public record Preload(String type, String post) {
        public Preload {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(post, "post");
        }
    }

    /** The keys a post step's {@code fields} may hold. */
    public static final Set<String> POST_FIELDS = Set.of(
            "content",
            "excerpt",
            "password",
            "parent",
            "date_gmt",
            "slug",
            "menu_order",
            "comment_status",
            "sticky",
            "categories",
            "tags",
            "format",
            "featured_media",
            "thumbnail_meta",
            "template",
            "meta");

    /** One step that prepares the scenario's site through the side's own API. */
    public sealed interface SetupStep {
        /**
         * An attachment uploaded by {@code as}: {@code fixture}'s bytes as the file {@code name} declared as
         * {@code type}, with the text {@code fields} ({@code post}, a {@code p:} or {@code m:} ref; {@code title},
         * {@code caption}, {@code description}, {@code alt_text}, {@code status}; {@code generate_sub_sizes}
         * {@code "false"}). WordPress's side uploads it through its REST media route, QuarkWord's through the setup
         * resource; without {@code editor} on the scenario both make no sizes ({@code generate_sub_sizes=false}). Setup
         * uploads are not compared.
         */
        record Media(String ref, String as, MediaScenario.Fixture fixture, String name, String type, ObjectNode fields)
                implements SetupStep {
            public Media {
                Objects.requireNonNull(fixture, "fixture");
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(type, "type");
                fields = fields == null ? JsonNodeFactory.instance.objectNode() : fields.deepCopy();
            }
        }

        /**
         * A post written by {@code as} ({@code type} post, page, book or attachment). {@code fields} (absent = the
         * defaults): {@code content}, {@code excerpt}, {@code password}, {@code parent} (a {@code p:} ref),
         * {@code date_gmt} ({@code yyyy-MM-ddTHH:mm:ss}), {@code slug}, {@code menu_order}, {@code comment_status},
         * {@code sticky}, {@code categories} and {@code tags} ({@code t:} refs), {@code format}, {@code featured_media}
         * (a {@code p:} ref), {@code thumbnail_meta} (a {@code p:} or {@code m:} ref written as the raw
         * {@code _thumbnail_id} meta, past WordPress's image check: harness rule, for a featured id no write would
         * store), {@code template}, {@code meta} ({key: value}).
         */
        record Post(String ref, String as, String type, String status, String title, ObjectNode fields)
                implements SetupStep {
            public Post {
                fields = fields == null ? JsonNodeFactory.instance.objectNode() : fields.deepCopy();
            }

            /** A post with the default fields. */
            public Post(String ref, String as, String type, String status, String title) {
                this(ref, as, type, status, title, null);
            }
        }

        /**
         * A regular revision of a post: an update by {@code as} of title and content (WordPress's revision on
         * update).
         */
        record Revision(String ref, String as, String postRef, String title, String content) implements SetupStep {}

        /** An autosave of a post by {@code as} (WordPress's autosave revision). */
        record Autosave(String ref, String as, String postRef, String title, String content) implements SetupStep {}

        /**
         * The auto-draft a new post of {@code type} starts from, as WordPress's new-post screen creates it for
         * {@code as} (on QuarkWord's side through {@code POST /quarkword/v1/drafts} as that user, who needs the
         * type's {@code edit_posts} and {@code create_posts}).
         */
        record Draft(String ref, String as, String type) implements SetupStep {}

        /** {@code _edit_lock} of the post set to {@code "<now - ageSeconds>:<user>"}. */
        record Lock(String postRef, String user, int ageSeconds) implements SetupStep {}

        /**
         * The user leaves the scenario's site and becomes administrator of a new site {@code /<scenario>-own/}. Never
         * alice, the super admin.
         */
        record OwnSite(String user) implements SetupStep {}

        /** A term of {@code taxonomy} (category or post_tag) created by {@code as} through the REST API. */
        record Term(String ref, String as, String taxonomy, String name, Optional<String> parentRef)
                implements SetupStep {}

        /**
         * An option of the scenario's site, set directly. A value that is one ref in braces ({@code "{p:home}"}) is
         * that ref's id on each side, as a number ({@code page_on_front}).
         */
        record Option(String name, JsonNode value) implements SetupStep {
            /** The ref the value names ({@code p:home} for {@code "{p:home}"}), when it is one ref in braces. */
            public Optional<String> ref() {
                if (value == null || !value.isTextual()) {
                    return Optional.empty();
                }
                String text = value.textValue();
                return text.length() > 2
                                && text.charAt(0) == '{'
                                && text.charAt(text.length() - 1) == '}'
                                && isRef(text.substring(1, text.length() - 1))
                        ? Optional.of(text.substring(1, text.length() - 1))
                        : Optional.empty();
            }

            /** The value as a side sets it: the ref's id on that side when the value names one. */
            public JsonNode valueOn(Map<String, Long> refs) {
                return ref().<JsonNode>map(ref -> JsonNodeFactory.instance.numberNode(Side.refId(refs, ref)))
                        .orElse(value);
            }
        }

        /**
         * An update of the post {@code postRef} (of {@code type}, as its post step names it) by {@code as} through each
         * side's REST API ({@code POST /wp/v2/<base>/<id>}), with the REST fields {@code fields} (refs resolved as in
         * a post step): what an author's edit does, so WordPress records the old slug or date of a published post.
         */
        record Update(String postRef, String type, String as, ObjectNode fields) implements SetupStep {
            public Update {
                Objects.requireNonNull(postRef, "postRef");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(as, "as");
                fields = fields == null ? JsonNodeFactory.instance.objectNode() : fields.deepCopy();
            }
        }

        /**
         * {@code user} leaves the scenario's site (no role there) and stays a member of the main site: a member of
         * another site only, for the requests that follow. Never alice, the super admin.
         */
        record Leave(String user) implements SetupStep {}
    }

    public RestScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        setup = List.copyOf(setup);
        requests = List.copyOf(requests);
        forms = forms.isEmpty() ? java.util.Collections.nCopies(requests.size(), Form.PLAIN) : List.copyOf(forms);
        if (forms.size() != requests.size()) {
            throw new IllegalArgumentException(
                    id + ": " + forms.size() + " request forms for " + requests.size() + " requests");
        }
        validate(id, front, setup, requests, forms);
    }

    /** A scenario of the REST API (not a front-end one). */
    public RestScenario(
            String id, String rule, boolean editor, List<SetupStep> setup, List<RestCall> requests, List<Form> forms) {
        this(id, rule, editor, false, setup, requests, forms);
    }

    /** A scenario without an image editor whose requests are all sent as given. */
    public RestScenario(String id, String rule, List<SetupStep> setup, List<RestCall> requests) {
        this(id, rule, false, setup, requests, List.of());
    }

    /**
     * The post type of each post ref the setup defines: a post step's and a draft step's type, {@code attachment} for
     * a media step's ref.
     */
    public Map<String, String> postTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        for (SetupStep step : setup) {
            switch (step) {
                case SetupStep.Post post -> types.put(post.ref(), post.type());
                case SetupStep.Draft draft -> types.put(draft.ref(), draft.type());
                case SetupStep.Media media -> types.put(media.ref(), "attachment");
                default -> {}
            }
        }
        return types;
    }

    /** Whether the scenario uploads anything: a media step, or a request that is an upload or a raw body. */
    public boolean uploads() {
        return setup.stream().anyMatch(step -> step instanceof SetupStep.Media)
                || forms.stream()
                        .anyMatch(
                                form -> form.upload().isPresent() || form.raw().isPresent());
    }

    /**
     * Request {@code index} (0-based) as a side sends it: refs resolved ({@link #resolve}); an upload's multipart body
     * built ({@link RestCall#multipart}) from its fixture and resolved fields; a raw request's body the fixture's bytes;
     * an HTTPS request marked with {@link Side#HTTPS_MARKER}, which each side turns into its own form of the scheme. A
     * preview placeholder {@code {preview:p:<ref>}} stays a placeholder, with the post's REST base and id in place of
     * the ref ({@code {preview:posts:12}}), for the side to replace by its own preview link
     * ({@link Side#resolvePreview}).
     */
    public RestCall call(int index, Map<String, Long> refs) {
        RestCall call = resolve(requests.get(index), refs);
        if (call.target().contains(PREVIEW)) {
            call = call.withTarget(previewTargets(call.target(), refs, postTypes()));
        }
        Form form = forms.get(index);
        if (form.upload().isPresent()) {
            Upload upload = form.upload().get();
            Map<String, String> fields = new LinkedHashMap<>();
            upload.fields().forEach((name, value) -> fields.put(name, substitute(value, refs)));
            RestCall multipart = RestCall.multipart(
                    call.as(),
                    call.target(),
                    upload.name(),
                    upload.type(),
                    MediaScenarios.fixture(upload.fixture()),
                    fields);
            call = new RestCall(
                    call.as(),
                    call.method(),
                    call.target(),
                    call.headers(),
                    multipart.contentType(),
                    multipart.body(),
                    call.headerLines());
        } else if (form.raw().isPresent()) {
            call = new RestCall(
                    call.as(),
                    call.method(),
                    call.target(),
                    call.headers(),
                    call.contentType(),
                    Optional.of(MediaScenarios.fixture(form.raw().get())),
                    call.headerLines());
        }
        return form.https() ? call.withHeader(Side.HTTPS_MARKER, "1") : call;
    }

    /** The site path of the scenario: {@code /<id>/}. */
    public String sitePath() {
        return "/" + id + "/";
    }

    /**
     * {@code call} with every {@code {ref}} in its target, header values, header lines and body replaced by the ref's
     * id.
     */
    public static RestCall resolve(RestCall call, Map<String, Long> refs) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : call.headers().entrySet()) {
            headers.put(header.getKey(), substitute(header.getValue(), refs));
        }
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> line : call.headerLines().entrySet()) {
            lines.put(
                    line.getKey(),
                    line.getValue().stream()
                            .map(value -> substitute(value, refs))
                            .toList());
        }
        Optional<byte[]> body = call.body()
                .map(bytes -> substitute(new String(bytes, StandardCharsets.UTF_8), refs)
                        .getBytes(StandardCharsets.UTF_8));
        return new RestCall(
                call.as(),
                call.method(),
                substitute(call.target(), refs),
                headers,
                call.contentType(),
                body,
                lines,
                call.session());
    }

    /**
     * {@code target} with each {@code {preview:<ref>}} written {@code {preview:<rest base>:<id>}}: the post's REST base
     * by its type ({@link Side#postBase}) and its id among {@code refs}.
     */
    static String previewTargets(String target, Map<String, Long> refs, Map<String, String> types) {
        StringBuilder out = new StringBuilder(target.length());
        int i = 0;
        for (String ref : previewRefs(target)) {
            String placeholder = PREVIEW + ref + "}";
            int at = target.indexOf(placeholder, i);
            out.append(target, i, at)
                    .append(PREVIEW)
                    .append(Side.postBase(Objects.requireNonNull(types.get(ref), ref)))
                    .append(':')
                    .append(Side.refId(refs, ref))
                    .append('}');
            i = at + placeholder.length();
        }
        return out.append(target.substring(i)).toString();
    }

    /** The refs of the preview placeholders ({@code {preview:p:<ref>}}) in {@code text}, in order. */
    static List<String> previewRefs(String text) {
        List<String> refs = new ArrayList<>();
        int at = text.indexOf(PREVIEW);
        while (at >= 0) {
            int end = text.indexOf('}', at);
            if (end < 0) {
                throw new IllegalArgumentException("an unclosed preview placeholder in " + text);
            }
            refs.add(text.substring(at + PREVIEW.length(), end));
            at = text.indexOf(PREVIEW, end);
        }
        return refs;
    }

    /** {@code text} with each {@code {ref}} replaced by its id; an unknown ref fails. */
    static String substitute(String text, Map<String, Long> refs) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int end = refEnd(text, i);
            if (end < 0) {
                out.append(text.charAt(i++));
                continue;
            }
            String ref = text.substring(i + 1, end);
            Long id = refs.get(ref);
            if (id == null) {
                throw new IllegalArgumentException("unknown ref {" + ref + "} in " + text);
            }
            out.append(id);
            i = end + 1;
        }
        return out.toString();
    }

    /** The refs named in braces in {@code text}, in order. */
    static List<String> refsIn(String text) {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            int end = refEnd(text, i);
            if (end >= 0) {
                refs.add(text.substring(i + 1, end));
                i = end;
            }
        }
        return refs;
    }

    /** When a ref in braces starts at {@code start}, the index of its closing brace; otherwise -1. */
    private static int refEnd(String text, int start) {
        if (text.charAt(start) != '{' || start + 3 >= text.length() || text.charAt(start + 2) != ':') {
            return -1;
        }
        if (REF_KINDS.indexOf(text.charAt(start + 1)) < 0) {
            return -1;
        }
        int i = start + 3;
        while (i < text.length() && isRefChar(text.charAt(i))) {
            i++;
        }
        return i > start + 3 && i < text.length() && text.charAt(i) == '}' ? i : -1;
    }

    /**
     * Whether {@code ref} is a well-formed ref name: {@code t:}, {@code u:}, {@code p:}, {@code c:}, {@code r:} or
     * {@code m:} and a name.
     */
    static boolean isRef(String ref) {
        if (ref.length() < 3 || REF_KINDS.indexOf(ref.charAt(0)) < 0 || ref.charAt(1) != ':') {
            return false;
        }
        for (int i = 2; i < ref.length(); i++) {
            if (!isRefChar(ref.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRefChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static void validate(
            String id, boolean front, List<SetupStep> setup, List<RestCall> requests, List<Form> forms) {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("a scenario needs an id");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                throw new IllegalArgumentException("scenario id " + id + ": only a-z, 0-9 and '-' (it is a site path)");
            }
        }
        if (front
                && (setup.isEmpty()
                        || !(setup.get(0) instanceof SetupStep.Option first)
                        || !first.name().equals("permalink_structure"))) {
            throw new IllegalArgumentException(
                    id + ": a front-end scenario's first setup step sets permalink_structure (no harness default)");
        }
        Set<String> defined = new HashSet<>(STARTING_REFS);
        for (SetupStep step : setup) {
            switch (step) {
                case SetupStep.Post post -> {
                    caller(id, post.as(), false);
                    postFields(id, defined, post.fields());
                    define(id, defined, post.ref(), 'p');
                }
                case SetupStep.Revision revision -> {
                    caller(id, revision.as(), false);
                    used(id, defined, revision.postRef());
                    refKind(id, revision.postRef(), 'p');
                    define(id, defined, revision.ref(), 'r');
                }
                case SetupStep.Autosave autosave -> {
                    caller(id, autosave.as(), false);
                    used(id, defined, autosave.postRef());
                    refKind(id, autosave.postRef(), 'p');
                    define(id, defined, autosave.ref(), 'r');
                }
                case SetupStep.Draft draft -> {
                    caller(id, draft.as(), false);
                    if (draft.type().isEmpty()) {
                        throw new IllegalArgumentException(id + ": a draft needs a type");
                    }
                    define(id, defined, draft.ref(), 'p');
                }
                case SetupStep.Lock lock -> {
                    caller(id, lock.user(), false);
                    used(id, defined, lock.postRef());
                    refKind(id, lock.postRef(), 'p');
                    if (lock.ageSeconds() < 0) {
                        throw new IllegalArgumentException(id + ": a lock's age is not negative");
                    }
                }
                case SetupStep.OwnSite ownSite -> {
                    caller(id, ownSite.user(), false);
                    if (ownSite.user().equals("alice")) {
                        throw new IllegalArgumentException(id + ": alice, the super admin, never leaves a site");
                    }
                }
                case SetupStep.Term term -> {
                    caller(id, term.as(), false);
                    if (!term.taxonomy().equals("category")
                            && !term.taxonomy().equals("post_tag")
                            && !term.taxonomy().equals("wp_pattern_category")) {
                        throw new IllegalArgumentException(id + ": unknown taxonomy " + term.taxonomy());
                    }
                    term.parentRef().ifPresent(parent -> used(id, defined, parent));
                    define(id, defined, term.ref(), 't');
                }
                case SetupStep.Option option -> {
                    if (option.name().isEmpty()) {
                        throw new IllegalArgumentException(id + ": an option needs a name");
                    }
                    option.ref().ifPresent(ref -> used(id, defined, ref));
                }
                case SetupStep.Update update -> {
                    caller(id, update.as(), false);
                    used(id, defined, update.postRef());
                    postOrMedia(id, update.postRef());
                    if (update.type().isEmpty()) {
                        throw new IllegalArgumentException(id + ": an update needs the post's type");
                    }
                    for (Iterator<String> it = update.fields().fieldNames(); it.hasNext(); ) {
                        String key = it.next();
                        if (key.equals("title") || key.equals("status")) {
                            if (!update.fields().get(key).isTextual()) {
                                throw new IllegalArgumentException(id + ": the update field " + key + " is a string");
                            }
                        } else if (key.equals("thumbnail_meta")) {
                            throw new IllegalArgumentException(
                                    id + ": an update writes REST fields, not thumbnail_meta");
                        }
                    }
                    ObjectNode rest = update.fields().deepCopy();
                    rest.remove(List.of("title", "status"));
                    postFields(id, defined, rest);
                }
                case SetupStep.Leave leave -> {
                    caller(id, leave.user(), false);
                    if (leave.user().equals("alice")) {
                        throw new IllegalArgumentException(id + ": alice, the super admin, never leaves a site");
                    }
                }
                case SetupStep.Media media -> {
                    caller(id, media.as(), false);
                    mediaFields(id, defined, media.fields());
                    if (media.name().isEmpty() || media.type().isEmpty()) {
                        throw new IllegalArgumentException(id + ": a media step needs a file name and a type");
                    }
                    define(id, defined, media.ref(), 'm');
                }
            }
        }
        if (requests.isEmpty()) {
            throw new IllegalArgumentException(id + ": a scenario sends at least one request");
        }
        for (int i = 0; i < requests.size(); i++) {
            RestCall call = requests.get(i);
            Form form = forms.get(i);
            caller(id, call.as(), true);
            if (front && !call.as().equals("anonymous")) {
                throw new IllegalArgumentException(id + ": a front-end request is sent by an anonymous visitor (a"
                        + " logged-in one names a session): " + call.as());
            }
            if (call.session().isPresent()) {
                if (!front) {
                    throw new IllegalArgumentException(id + ": only a front-end request carries a session");
                }
                caller(id, call.session().get(), false);
            }
            List<String> previews = previewRefs(call.target());
            if (!previews.isEmpty() && call.session().isEmpty()) {
                throw new IllegalArgumentException(id + ": a preview placeholder is the session's preview link");
            }
            for (String ref : previews) {
                used(id, defined, ref);
                postOrMedia(id, ref);
            }
            if (form.upload().isPresent() || form.raw().isPresent()) {
                String what = form.upload().isPresent() ? "an upload" : "a raw body";
                if (!call.method().equals("POST")) {
                    throw new IllegalArgumentException(id + ": " + what + " is a POST: " + call.method());
                }
                if (call.body().isPresent()) {
                    throw new IllegalArgumentException(id + ": " + what + " has no body of its own");
                }
                if (form.upload().isPresent() && call.contentType().isPresent()) {
                    throw new IllegalArgumentException(id + ": an upload's content type is the multipart one");
                }
            }
            if (form.preload().isPresent()) {
                Preload preload = form.preload().get();
                if (preload.type().isEmpty()) {
                    throw new IllegalArgumentException(id + ": a preload comparison needs a type");
                }
                if (!preload.post().startsWith("{")
                        || !preload.post().endsWith("}")
                        || !isRef(preload.post().substring(1, preload.post().length() - 1))
                        || preload.post().charAt(1) != 'p') {
                    throw new IllegalArgumentException(
                            id + ": a preload comparison's post is a p: ref in braces: " + preload.post());
                }
                if (!call.method().equals("GET")
                        || !call.target().equals(EditorPreloadPaths.editorTarget(preload.type(), preload.post()))) {
                    throw new IllegalArgumentException(id + ": a preload comparison is the editor route's GET");
                }
            }
            List<String> texts = new ArrayList<>();
            texts.add(call.target());
            texts.addAll(call.headers().values());
            call.headerLines().values().forEach(texts::addAll);
            call.body().ifPresent(body -> texts.add(new String(body, StandardCharsets.UTF_8)));
            form.upload().ifPresent(upload -> texts.addAll(upload.fields().values()));
            for (String text : texts) {
                for (String ref : refsIn(text)) {
                    used(id, defined, ref);
                }
            }
        }
    }

    private static void caller(String id, String as, boolean invalidAllowed) {
        if (!CALLERS.contains(as) || (!invalidAllowed && (as.equals("invalid") || as.equals("anonymous")))) {
            throw new IllegalArgumentException(id + ": unknown caller '" + as + "'");
        }
    }

    /** The fields of a post step: known keys, values of the right shape, refs of the right kind already defined. */
    private static void postFields(String id, Set<String> defined, ObjectNode fields) {
        if (fields.has("thumbnail_meta")
                && (fields.has("featured_media") || fields.path("meta").has("_thumbnail_id"))) {
            throw new IllegalArgumentException(
                    id + ": thumbnail_meta writes _thumbnail_id; featured_media and meta must not name it too");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = fields.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (!POST_FIELDS.contains(key)) {
                throw new IllegalArgumentException(id + " post step fields: unknown key " + key);
            }
            boolean fine = switch (key) {
                case "menu_order" -> value.isIntegralNumber();
                case "sticky" -> value.isBoolean();
                case "meta" -> value.isObject();
                case "categories", "tags" -> value.isArray();
                default -> value.isTextual();
            };
            if (!fine) {
                throw new IllegalArgumentException(id + ": the post field " + key + " has the wrong shape: " + value);
            }
            switch (key) {
                case "parent", "featured_media", "thumbnail_meta" -> {
                    used(id, defined, value.textValue());
                    postOrMedia(id, value.textValue());
                }
                case "categories", "tags" -> {
                    for (JsonNode term : value) {
                        if (!term.isTextual()) {
                            throw new IllegalArgumentException(id + ": " + key + " holds t: refs: " + value);
                        }
                        used(id, defined, term.textValue());
                        refKind(id, term.textValue(), 't');
                    }
                }
                default -> {}
            }
        }
    }

    /** The fields of a media step: known keys, text values, a {@code post} that is a post or media ref defined. */
    private static void mediaFields(String id, Set<String> defined, ObjectNode fields) {
        for (Iterator<Map.Entry<String, JsonNode>> it = fields.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (!MEDIA_FIELDS.contains(field.getKey())) {
                throw new IllegalArgumentException(id + " media step fields: unknown key " + field.getKey());
            }
            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException(
                        id + ": the media field " + field.getKey() + " is a string: " + field.getValue());
            }
            if (field.getKey().equals("post")) {
                used(id, defined, field.getValue().textValue());
                postOrMedia(id, field.getValue().textValue());
            }
            // the only use: an editor scenario's image stored without its sizes
            if (field.getKey().equals("generate_sub_sizes")
                    && !field.getValue().textValue().equals("false")) {
                throw new IllegalArgumentException(
                        id + ": the media field generate_sub_sizes is \"false\" or absent: " + field.getValue());
            }
        }
    }

    /** A ref naming a post: a {@code p:} ref, or an {@code m:} ref (an attachment is a post). */
    private static void postOrMedia(String id, String ref) {
        if (!isRef(ref) || (ref.charAt(0) != 'p' && ref.charAt(0) != 'm')) {
            throw new IllegalArgumentException(id + ": not a p: or m: ref: " + ref);
        }
    }

    private static void refKind(String id, String ref, char kind) {
        if (!isRef(ref) || ref.charAt(0) != kind) {
            throw new IllegalArgumentException(id + ": not a " + kind + ": ref: " + ref);
        }
    }

    private static void define(String id, Set<String> defined, String ref, char kind) {
        refKind(id, ref, kind);
        if (!defined.add(ref)) {
            throw new IllegalArgumentException(id + ": ref " + ref + " is defined twice");
        }
    }

    private static void used(String id, Set<String> defined, String ref) {
        if (!defined.contains(ref)) {
            throw new IllegalArgumentException(id + ": ref " + ref + " is used before it is defined");
        }
    }
}
