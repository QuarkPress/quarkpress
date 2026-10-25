package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.Arc;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.SystemIdentity;

/**
 * Plays the post operations of a scenario on QuarkPress's main site and records what {@link WordPressOutcomes} records
 * for WordPress, through the same normalization. Scenario user 0 is the system identity. Not thread-safe: one runner
 * per scenario run.
 */
final class PostScenarioRunner {

    static final SiteId MAIN = ScenarioParityBase.MAIN;

    /** The options the posts module writes by itself (sticky posts, reading settings reset by a trash or delete). */
    private static final List<String> MODULE_OPTIONS =
            List.of("sticky_posts", "show_on_front", "page_on_front", "page_for_posts");

    private final Posts posts;
    private final SiteScope scope;
    private final OptionStore store;
    private final Options options;
    private final boolean guids;
    private final Instant started = Instant.now();
    /** Inserted posts by index - 1; null where the insert failed or named a missing reference. */
    private final List<PostId> inserted = new ArrayList<>();
    /** The options this run may have changed, with what they held before (empty: absent). */
    private final Map<String, Optional<JsonNode>> changed = new LinkedHashMap<>();
    /** The inserted posts a trash or delete of this run removed for good. */
    private final Set<PostId> deleted = new HashSet<>();
    /** The runner of the scenario's term operations, attached by {@link #terms}. */
    private TermScenarioRunner termRunner;
    /** The runner of the scenario's revision operations, attached by {@link #revisions}. */
    private RevisionScenarioRunner revisionRunner;

    PostScenarioRunner(Posts posts, SiteScope scope, OptionStore store, Options options, boolean guids) {
        this.posts = posts;
        this.scope = scope;
        this.store = store;
        this.options = options;
        this.guids = guids;
    }

    /** Attaches the runner of the scenario's term operations, which this runner hands them to. */
    void terms(TermScenarioRunner runner) {
        this.termRunner = runner;
    }

    /** Attaches the runner of the scenario's revision operations, which this runner hands them to. */
    void revisions(RevisionScenarioRunner runner) {
        this.revisionRunner = runner;
    }

    void run(int op, ScenarioOp.PostOp step, Outcome outcome, List<UserId> users) throws Exception {
        for (String name : MODULE_OPTIONS) {
            remember(name);
        }
        String kind = step.kind();
        switch (step) {
            case ScenarioOp.SiteOption option ->
                outcome.done(
                        op, kind, "changed=" + setOption(option.name(), optionValue(option.name(), option.value())));
            case ScenarioOp.FrontPage front -> {
                Optional<PostId> post = post(front.post());
                if (front.post() != 0 && post.isEmpty()) {
                    outcome.done(op, kind, "missing");
                } else {
                    long id = post.map(PostId::value).orElse(0L);
                    outcome.done(op, kind, "changed=" + setOption(front.option(), LongNode.valueOf(id)));
                }
            }
            case ScenarioOp.InsertPost insert -> {
                Optional<PostChanges.Builder> changes = changes(insert.fields(), users);
                if (changes.isEmpty()) {
                    inserted.add(null);
                    outcome.done(op, kind, "missing");
                    return;
                }
                try {
                    PostSnapshot post = as(
                            insert.as(), users, () -> posts.create(changes.get().build()));
                    inserted.add(post.id());
                    outcome.postCreated(op, inserted.size());
                } catch (PostException refused) {
                    inserted.add(null);
                    outcome.done(op, kind, "refused:" + refused.code());
                } catch (ForbiddenException | UnauthorizedException refused) {
                    inserted.add(null);
                    outcome.done(op, kind, "refused:forbidden");
                }
            }
            case ScenarioOp.UpdatePost update -> {
                Optional<PostId> post = post(update.post());
                Optional<PostChanges.Builder> changes = changes(update.fields(), users);
                if (post.isEmpty() || changes.isEmpty()) {
                    outcome.done(op, kind, "missing");
                    return;
                }
                outcome.done(op, kind, refusing(kind, deleted.contains(post.get()), () -> {
                    as(
                            update.as(),
                            users,
                            () -> posts.update(
                                    post.get(),
                                    changes.get().editDate(update.editDate()).build()));
                    return "ok";
                }));
            }
            case ScenarioOp.PublishPost publish ->
                onPost(op, kind, publish.post(), outcome, id -> {
                    as(0, users, () -> posts.publish(id));
                    return "ok";
                });
            case ScenarioOp.TrashPost trash ->
                onPost(op, kind, trash.post(), outcome, id -> answer(as(trash.as(), users, () -> posts.trash(id))));
            case ScenarioOp.UntrashPost untrash ->
                onPost(
                        op,
                        kind,
                        untrash.post(),
                        outcome,
                        id -> answer(as(untrash.as(), users, () -> posts.untrash(id))));
            case ScenarioOp.DeletePost delete ->
                onPost(
                        op,
                        kind,
                        delete.post(),
                        outcome,
                        id -> answer(as(delete.as(), users, () -> posts.delete(id, delete.force()))));
            case ScenarioOp.Stick stick ->
                onPost(op, kind, stick.post(), outcome, id -> {
                    as(0, users, () -> stick.sticky() ? posts.stick(id) : posts.unstick(id));
                    return "ok";
                });
            case ScenarioOp.QueryPosts query -> {
                Optional<PostQuery> built = query(query.args(), users);
                if (built.isEmpty()) {
                    outcome.done(op, kind, "missing");
                    return;
                }
                try {
                    PostPage page = as(query.as(), users, () -> posts.query(built.get()));
                    Map<Long, Integer> index = postIndex();
                    List<String> refs = page.posts().stream()
                            .map(post -> PostRecords.postRef(post.id().value(), index))
                            .toList();
                    outcome.queried(op, query.args().unordered() ? PostRecords.sortedRefs(refs) : refs, page.found());
                } catch (ForbiddenException | UnauthorizedException refused) {
                    outcome.done(op, kind, "refused:forbidden");
                }
            }
            case ScenarioOp.PageUri uri ->
                onPost(
                        op,
                        kind,
                        uri.post(),
                        outcome,
                        id -> PostRecords.pageUri(as(0, users, () -> posts.uri(id)), postIndex()));
            case ScenarioOp.ReadPost read -> {
                Optional<PostId> post = post(read.post());
                if (post.isEmpty()) {
                    outcome.done(op, kind, "missing");
                } else {
                    outcome.observed("op" + op + ":read_post", observe(post.get(), users, null));
                }
            }
            case ScenarioOp.TermOp term -> termRunner.run(op, term, outcome, users);
            case ScenarioOp.RevisionOp revision -> revisionRunner.run(op, revision, outcome, users);
            case ScenarioOp.UpdatePostMeta meta -> revisionRunner.run(op, meta, outcome, users);
            case ScenarioOp.SchedulingOp scheduling ->
                throw new UnsupportedOperationException("scheduling operations arrive with the scheduling runner");
        }
    }

    /** Records every inserted post's final state, as the driver reports {@code posts}. */
    void record(Outcome outcome, List<UserId> users, UserId installer) throws Exception {
        for (int i = 0; i < inserted.size(); i++) {
            if (inserted.get(i) != null) {
                outcome.observed("post#" + (i + 1), observe(inserted.get(i), users, installer));
            }
        }
    }

    /** An option's value as {@code read_options} compares it: a post id named by insertion index. */
    String optionText(String name, String text) {
        return PostRecords.optionText(name, text, postIndex());
    }

    /** Deletes the posts this run made (latest first) and restores the options it changed. */
    void cleanUp() throws Exception {
        for (int i = inserted.size() - 1; i >= 0; i--) {
            PostId post = inserted.get(i);
            if (post != null) {
                try {
                    as(0, List.of(), () -> posts.delete(post, true));
                } catch (PostException gone) {
                    // the scenario deleted it already
                }
            }
        }
        for (Map.Entry<String, Optional<JsonNode>> option : changed.entrySet()) {
            scope.call(
                    MAIN,
                    () -> option.getValue().isPresent()
                            ? options.set(option.getKey(), option.getValue().get())
                            : options.delete(option.getKey()));
        }
    }

    /**
     * What WordPress answers where QuarkPress refuses an operation on a post this scenario deleted: trash, untrash and
     * delete report nothing, publishing and sticking report ok; empty for any other operation or refusal, and for a
     * post that was not deleted, whose refusal is recorded as it is.
     */
    static Optional<String> goneAnswer(String kind, PostException refused, boolean deleted) {
        // QuarkPress refuses an operation on a missing post with invalid_post (difference D19), where WordPress's
        // wp_trash_post, wp_untrash_post and wp_delete_post return false or null, and wp_publish_post and stick_post
        // return nothing.
        if (!deleted || !refused.code().equals("invalid_post")) {
            return Optional.empty();
        }
        return switch (kind) {
            case "trash_post", "untrash_post", "delete_post" -> Optional.of(Outcome.NOTHING);
            case "publish_post", "stick" -> Optional.of("ok");
            default -> Optional.empty();
        };
    }

    @FunctionalInterface
    private interface PostWork {
        String apply(PostId post) throws Exception;
    }

    private void onPost(int op, String kind, int index, Outcome outcome, PostWork work) throws Exception {
        Optional<PostId> post = post(index);
        if (post.isEmpty()) {
            outcome.done(op, kind, "missing");
            return;
        }
        outcome.done(op, kind, refusing(kind, deleted.contains(post.get()), () -> work.apply(post.get())));
        if ((kind.equals("delete_post") || kind.equals("trash_post"))
                && as(0, List.of(), () -> posts.find(post.get())).isEmpty()) {
            deleted.add(post.get());
        }
    }

    /** What the work answers, or how QuarkPress refused it ({@code deleted}: the post was deleted earlier). */
    private static String refusing(String kind, boolean deleted, Callable<String> work) throws Exception {
        try {
            return work.call();
        } catch (PostException refused) {
            return goneAnswer(kind, refused, deleted).orElse("refused:" + refused.code());
        } catch (ForbiddenException | UnauthorizedException refused) {
            return "refused:forbidden";
        }
    }

    private static String answer(Optional<PostSnapshot> before) {
        return before.map(post -> "done:" + post.status()).orElse(Outcome.NOTHING);
    }

    /** Runs {@code work} bound to the main site as scenario user {@code user} (0: the system identity). */
    <T> T as(int user, List<UserId> users, Callable<T> work) throws Exception {
        SecurityIdentity identity = user == 0
                ? Arc.container().instance(SystemIdentity.class).get().identity()
                : identityOf(users.get(user - 1));
        return scope.call(MAIN, () -> {
            CurrentIdentityAssociation association =
                    Arc.container().instance(CurrentIdentityAssociation.class).get();
            SecurityIdentity previous = association.getIdentity();
            association.setIdentity(identity);
            try {
                return work.call();
            } finally {
                association.setIdentity(previous);
            }
        });
    }

    /** A profile's identity as QuarkPress's augmentor leaves it (its capabilities are checked explicitly). */
    static SecurityIdentity identityOf(UserId user) {
        String subject = "tierb-user-" + user.value();
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(subject))
                .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(user, subject))
                .build();
    }

    Optional<PostId> post(int index) {
        return index < 1 || index > inserted.size() ? Optional.empty() : Optional.ofNullable(inserted.get(index - 1));
    }

    Map<Long, Integer> postIndex() {
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < inserted.size(); i++) {
            if (inserted.get(i) != null) {
                index.put(inserted.get(i).value(), i + 1);
            }
        }
        return index;
    }

    /** The changes of scenario fields; empty when a reference names a post that was not inserted. */
    private Optional<PostChanges.Builder> changes(PostFields fields, List<UserId> users) {
        PostChanges.Builder changes = PostChanges.builder();
        fields.type().ifPresent(changes::type);
        fields.status().ifPresent(changes::status);
        fields.title().ifPresent(changes::title);
        fields.content().ifPresent(changes::content);
        fields.excerpt().ifPresent(changes::excerpt);
        fields.slug().ifPresent(changes::slug);
        fields.password().ifPresent(changes::password);
        fields.commentStatus().ifPresent(changes::commentStatus);
        fields.pingStatus().ifPresent(changes::pingStatus);
        fields.date().ifPresent(date -> changes.date(PostRecords.parse(date)));
        fields.dateGmt().ifPresent(date -> changes.dateGmt(PostRecords.utc(date)));
        fields.menuOrder().ifPresent(changes::menuOrder);
        if (fields.author().isPresent()) {
            int author = fields.author().getAsInt();
            changes.author(author == 0 ? 0L : users.get(author - 1).value());
        }
        if (fields.parent().isPresent()) {
            int parent = fields.parent().getAsInt();
            if (parent == 0) {
                changes.parent(0L);
            } else {
                Optional<PostId> post = post(parent);
                if (post.isEmpty()) {
                    return Optional.empty();
                }
                changes.parent(post.get());
            }
        }
        return Optional.of(changes);
    }

    /** The query of scenario args; empty when a post reference names a post that was not inserted. */
    private Optional<PostQuery> query(QueryArgs args, List<UserId> users) {
        PostQuery.Builder query = PostQuery.builder();
        if (!args.types().isEmpty()) {
            query.types(args.types().toArray(String[]::new));
        }
        if (!args.statuses().isEmpty()) {
            query.statuses(args.statuses().toArray(String[]::new));
        }
        args.authorIn().forEach(user -> query.authorIn(users.get(user - 1)));
        args.authorNotIn().forEach(user -> query.authorNotIn(users.get(user - 1)));
        if (args.parent().isPresent()) {
            int parent = args.parent().getAsInt();
            if (parent == 0) {
                query.parent(0);
            } else if (post(parent).isPresent()) {
                query.parent(post(parent).get().value());
            } else {
                return Optional.empty();
            }
        }
        for (int index : args.postIn()) {
            if (post(index).isEmpty()) {
                return Optional.empty();
            }
            query.postIn(post(index).get());
        }
        for (int index : args.postNotIn()) {
            if (post(index).isEmpty()) {
                return Optional.empty();
            }
            query.postNotIn(post(index).get());
        }
        args.name().ifPresent(query::name);
        args.search().ifPresent(query::search);
        for (String order : args.orderBy()) {
            int space = order.indexOf(' ');
            query.orderBy(
                    orderKey(order.substring(0, space)),
                    order.substring(space + 1).equals("asc"));
        }
        args.perPage().ifPresent(query::perPage);
        args.page().ifPresent(query::page);
        args.offset().ifPresent(query::offset);
        query.stickyFirst(args.stickyFirst());
        return Optional.of(query.build());
    }

    static OrderKey orderKey(String key) {
        return switch (key) {
            case "date" -> OrderKey.DATE;
            case "modified" -> OrderKey.MODIFIED;
            case "title" -> OrderKey.TITLE;
            case "name" -> OrderKey.NAME;
            case "author" -> OrderKey.AUTHOR;
            case "parent" -> OrderKey.PARENT;
            case "type" -> OrderKey.TYPE;
            case "id" -> OrderKey.ID;
            case "menu_order" -> OrderKey.MENU_ORDER;
            case "post_in" -> OrderKey.POST_IN;
            case "none" -> OrderKey.NONE;
            default -> throw new IllegalArgumentException("no order key " + key);
        };
    }

    /**
     * A scenario option value as WordPress's option sanitizing leaves it, in the JSON type QuarkPress's site defaults
     * give the option: {@code posts_per_page} an integer (PHP's integer cast; 0 becomes 1, below -1 its absolute
     * value, refused when that is beyond the 64-bit range, where PHP gives a float), {@code gmt_offset} a text (a
     * number keeps only digits, colons, dots and minus signs; anything else becomes empty), the default comment and
     * ping statuses "closed" for an empty value or "0"; any other value as given.
     */
    static JsonNode optionValue(String name, String value) {
        return switch (name) {
            case "posts_per_page" -> {
                long number = phpInt(value);
                if (number == Long.MIN_VALUE) {
                    throw new IllegalArgumentException(
                            "a posts_per_page whose absolute value is beyond PHP's integer range: " + value);
                }
                yield LongNode.valueOf(number == 0 ? 1 : number < -1 ? -number : number);
            }
            case "gmt_offset" -> TextNode.valueOf(numeric(value) ? numberCharacters(value) : "");
            case "default_comment_status", "default_ping_status" ->
                TextNode.valueOf(value.isEmpty() || value.equals("0") ? "closed" : value);
            default -> TextNode.valueOf(value);
        };
    }

    /**
     * PHP 8's integer cast of a text: leading whitespace is skipped, then the longest leading decimal number counts
     * (sign, digits, fraction, exponent); an integer beyond the 64-bit range saturates, a number with a fraction or
     * exponent is truncated toward zero and saturates too, an infinite one is 0; text without a leading number is 0.
     */
    static long phpInt(String text) {
        int length = text.length();
        int i = 0;
        while (i < length && phpSpace(text.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int integerStart = i;
        i = digitsFrom(text, i);
        boolean integerDigits = i > integerStart;
        boolean decimal = false;
        if (i < length && text.charAt(i) == '.') {
            int fractionEnd = digitsFrom(text, i + 1);
            if (integerDigits || fractionEnd > i + 1) {
                decimal = true;
                i = fractionEnd;
            }
        }
        if (!integerDigits && !decimal) {
            return 0;
        }
        if (i < length && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int exponent = i + 1;
            if (exponent < length && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                exponent++;
            }
            int exponentEnd = digitsFrom(text, exponent);
            if (exponentEnd > exponent) {
                decimal = true;
                i = exponentEnd;
            }
        }
        String number = text.substring(start, i);
        if (decimal) {
            double parsed = Double.parseDouble(number);
            return Double.isInfinite(parsed) ? 0 : (long) parsed;
        }
        return new BigInteger(number)
                .max(BigInteger.valueOf(Long.MIN_VALUE))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
    }

    private static int digitsFrom(String text, int from) {
        int i = from;
        while (i < text.length() && isDigit(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** PHP's {@code is_numeric} for a decimal text: an optional sign, digits with at most one dot, an exponent. */
    private static boolean numeric(String text) {
        int i = 0;
        while (i < text.length() && phpSpace(text.charAt(i))) {
            i++;
        }
        if (i < text.length() && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        boolean dot = false;
        while (i < text.length() && (isDigit(text.charAt(i)) || (text.charAt(i) == '.' && !dot))) {
            dot |= text.charAt(i) == '.';
            digits += isDigit(text.charAt(i)) ? 1 : 0;
            i++;
        }
        if (digits == 0) {
            return false;
        }
        if (i < text.length() && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < text.length() && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
                j++;
            }
            int exponent = j;
            while (j < text.length() && isDigit(text.charAt(j))) {
                j++;
            }
            if (j == exponent) {
                return false;
            }
            i = j;
        }
        while (i < text.length() && phpSpace(text.charAt(i))) {
            i++;
        }
        return i == text.length();
    }

    private static String numberCharacters(String text) {
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isDigit(c) || c == ':' || c == '.' || c == '-') {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean phpSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }

    /** Keeps what an option held before this run first touched it. */
    private void remember(String name) throws Exception {
        if (!changed.containsKey(name)) {
            changed.put(name, scope.call(MAIN, () -> store.find(MAIN, name)));
        }
    }

    /** Sets an option as {@code update_option} does, answering whether its value changed. */
    private boolean setOption(String name, JsonNode value) throws Exception {
        remember(name);
        return scope.call(MAIN, () -> options.set(name, value));
    }

    /** A post as the comparison reads it ({@code installer} null: references to it are not expected). */
    private ObservedPost observe(PostId id, List<UserId> users, UserId installer) throws Exception {
        Optional<PostSnapshot> found = as(0, users, () -> posts.find(id));
        if (found.isEmpty()) {
            return ObservedPost.DELETED;
        }
        PostSnapshot post = found.get();
        RunWindow window = new RunWindow(started, Instant.now());
        SiteContext site = Arc.container().instance(SiteContext.class).get();
        ZoneId zone = scope.call(MAIN, site::zone);
        String home = scope.call(MAIN, () -> {
            String url = site.site().url("http").toString();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        });
        Map<Long, Integer> userIndex = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            userIndex.put(users.get(i).value(), i + 1);
        }
        Map<Long, Integer> postIndex = postIndex();
        String date = PostRecords.format(post.localDate(zone));
        String dateGmt = post.publishedAt()
                .map(at -> PostRecords.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC)))
                .orElse(PostRecords.ZERO_DATE);
        String modified = PostRecords.format(post.localModified(zone));
        String modifiedGmt = PostRecords.format(LocalDateTime.ofInstant(post.modifiedAt(), ZoneOffset.UTC));
        PostRecords.Record record = PostRecords.record()
                .field("type", post.type())
                .field("status", post.status())
                .field("slug", PostRecords.slug(post.slug(), id.value(), postIndex))
                .field("title", post.title())
                .field("content", post.content())
                .field("excerpt", post.excerpt())
                .field(
                        "author",
                        PostRecords.userRef(
                                post.author().map(UserId::value).orElse(0L),
                                userIndex,
                                installer == null ? -1 : installer.value()))
                .field(
                        "parent",
                        PostRecords.postRef(post.parent().map(PostId::value).orElse(0L), postIndex))
                .field("menu_order", Integer.toString(post.menuOrder()))
                .field("date", window.showLocal(date))
                .field("date_gmt", PostRecords.gmtText(dateGmt, window))
                .field("date_offset", PostRecords.offsetMinutes(date, dateGmt))
                .field("modified", window.showLocal(modified))
                .field("modified_gmt", PostRecords.gmtText(modifiedGmt, window))
                .field("modified_offset", PostRecords.offsetMinutes(modified, modifiedGmt))
                .field("comment_status", post.commentStatus())
                .field("ping_status", post.pingStatus())
                .field("password", post.password())
                .field("sticky", post.sticky() ? "1" : "0");
        if (guids) {
            record.field("guid", PostRecords.guid(post.guid(), home, postIndex));
        }
        for (String key : PostRecords.META_KEYS) {
            List<JsonNode> values = post.meta(key);
            if (!values.isEmpty()) {
                List<String> texts = values.stream()
                        .map(value -> value.isTextual() ? value.textValue() : value.asText())
                        .toList();
                record.meta(key, PostRecords.metaText(key, texts, id.value(), postIndex, window));
            }
        }
        return record.build();
    }
}
