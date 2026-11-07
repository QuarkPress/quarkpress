package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.quarkpress.testing.wporacle.ParityRun;

/**
 * Compares what WordPress and QuarkPress stored for one {@link MediaScenario}, step by step, after normalising what
 * legitimately differs between the two runs. Aspects (the case is the scenario's id):
 *
 * <ul>
 *   <li>{@code step<n>:outcome}: {@code accepted}, or the refusal's code and message; WordPress's REST code {@code
 *       rest_upload_unknown_error} is the pipeline's {@code upload_error}, every other code is compared as is. The
 *       remaining aspects of a step are compared only when both sides accepted it.
 *   <li>{@code step<n>:post:<column>} for each column of {@link #COLUMNS}, {@code step<n>:meta:<key>} for every meta
 *       key either side stores (an absent {@code _wp_attachment_image_alt} reads as {@code ""}, as WordPress's
 *       {@code get_post_meta(..., true)} reads it), {@code step<n>:metadata:<JSON pointer>} for every leaf of
 *       {@code _wp_attachment_metadata}, and {@code step<n>:files}, the names in the attachment's folder: after an
 *       upload, an update or a post-process.
 *   <li>{@code step<n>:files} after a delete: the names left in the deleted attachment's folder; and {@code
 *       step<n>:featured:<ref>}, each setup post's featured image (also after a step that sets one).
 *   <li>{@code step<n>:ids}: a query's attachments.
 * </ul>
 *
 * <p>Normalisation: ids become the refs of the scenario ({@code step<n>}, {@code p:...}, {@code u:<user>}), by
 * creation order on each side; the site URL ({@code http://localhost/<site>/}) becomes {@code {site}/}, a network
 * site's uploads folder {@code sites/<id>/} becomes {@code sites/{id}/}, and the current upload folder ({@code
 * yyyy/MM} of the step, UTC) {@code {ym}}; the {@code filesize} of every file a side wrote itself (a size, and the
 * main file when {@code original_image} says it replaced the upload) becomes {@code {bytes}} (the original's size is
 * compared); a post date within {@link #NOW} of the step becomes {@code {now}}. An empty JSON array and an empty
 * object are the same (PHP has one empty array).
 */
public final class MediaComparison {

    /** The post row's columns compared, under WordPress's names. */
    public static final List<String> COLUMNS = List.of(
            "ID",
            "post_type",
            "post_title",
            "post_excerpt",
            "post_content",
            "post_status",
            "post_mime_type",
            "post_name",
            "post_parent",
            "post_author",
            "guid",
            "comment_status",
            "ping_status",
            "menu_order",
            "post_date",
            "post_date_gmt",
            "post_modified",
            "post_modified_gmt");

    /** How close to the step a date must be to count as "now". */
    public static final Duration NOW = Duration.ofMinutes(2);

    static final String ABSENT = "(absent)";

    private static final DateTimeFormatter MYSQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * What one side observed after one step.
     *
     * @param step the step's number, from 1
     * @param kind {@code upload}, {@code update}, {@code feature}, {@code delete}, {@code process} or {@code query}
     * @param at when the step started
     * @param attachment the attachment after the step (upload, update, process), in {@link MediaSide#attachment}'s
     *     shape
     * @param files the names left in the attachment's folder after a delete
     * @param ids a query's attachment ids, in the side's order
     * @param featured the featured image ids of each setup post (after a delete, or a step that set one)
     * @param posts the ref of each id the scenario created so far (setup posts and uploads)
     * @param users the ref ({@code u:<user>}) of each user id
     * @param site the site's path ({@code /m01/})
     */
    public record Observed(
            int step,
            String kind,
            Instant at,
            MediaSide.Outcome outcome,
            Optional<ObjectNode> attachment,
            Optional<List<String>> files,
            Optional<List<Long>> ids,
            Map<String, List<Long>> featured,
            Map<Long, String> posts,
            Map<Long, String> users,
            String site) {

        public Observed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(attachment, "attachment");
            files = files.map(List::copyOf);
            ids = ids.map(List::copyOf);
            featured = Collections.unmodifiableMap(new LinkedHashMap<>(featured));
            posts = Collections.unmodifiableMap(new LinkedHashMap<>(posts));
            users = Collections.unmodifiableMap(new LinkedHashMap<>(users));
            Objects.requireNonNull(site, "site");
        }
    }

    private MediaComparison() {}

    /** Compares each step's observations; both lists hold one entry per step of the scenario. */
    public static void compare(MediaScenario scenario, List<Observed> wp, List<Observed> qp, ParityRun run) {
        if (wp.size() != scenario.steps().size()
                || qp.size() != scenario.steps().size()) {
            throw new IllegalArgumentException(scenario.id() + ": expected "
                    + scenario.steps().size() + " observations per side, got " + wp.size() + " and " + qp.size());
        }
        String id = scenario.id();
        for (int i = 0; i < wp.size(); i++) {
            Observed w = wp.get(i);
            Observed q = qp.get(i);
            String step = "step" + w.step() + ":";
            run.compare(id, step + "outcome", outcome(w.outcome()), outcome(q.outcome()));
            if (!w.outcome().accepted() || !q.outcome().accepted()) {
                continue;
            }
            if (w.attachment().isPresent() || q.attachment().isPresent()) {
                compareAttachment(id, step, w, q, run);
            }
            if (w.files().isPresent() || q.files().isPresent()) {
                run.compare(id, step + "files", fileList(w.files(), w), fileList(q.files(), q));
            }
            if (w.ids().isPresent() || q.ids().isPresent()) {
                run.compare(id, step + "ids", idList(w.ids(), w), idList(q.ids(), q));
            }
            TreeSet<String> refs = new TreeSet<>(w.featured().keySet());
            refs.addAll(q.featured().keySet());
            for (String ref : refs) {
                run.compare(
                        id,
                        step + "featured:" + ref,
                        Optional.ofNullable(w.featured().get(ref))
                                .map(v -> refList(v, w))
                                .orElse(ABSENT),
                        Optional.ofNullable(q.featured().get(ref))
                                .map(v -> refList(v, q))
                                .orElse(ABSENT));
            }
        }
    }

    /** {@code accepted}, or {@code <code>: <message>} with WordPress's REST upload code mapped. */
    static String outcome(MediaSide.Outcome outcome) {
        if (outcome.accepted()) {
            return "accepted";
        }
        String code = outcome.code().equals("rest_upload_unknown_error") ? "upload_error" : outcome.code();
        return code + ": " + outcome.message();
    }

    private static void compareAttachment(String id, String step, Observed w, Observed q, ParityRun run) {
        ObjectNode wa = w.attachment()
                .orElseGet(() -> com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        ObjectNode qa = q.attachment()
                .orElseGet(() -> com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        for (String column : COLUMNS) {
            run.compare(
                    id,
                    step + "post:" + column,
                    column(column, wa.path("post").get(column), w),
                    column(column, qa.path("post").get(column), q));
        }
        TreeSet<String> keys = new TreeSet<>();
        wa.path("meta").fieldNames().forEachRemaining(keys::add);
        qa.path("meta").fieldNames().forEachRemaining(keys::add);
        keys.add("_wp_attachment_image_alt");
        keys.remove("_wp_attachment_metadata");
        for (String key : keys) {
            run.compare(
                    id,
                    step + "meta:" + key,
                    meta(key, wa.path("meta").get(key), w),
                    meta(key, qa.path("meta").get(key), q));
        }
        Map<String, String> wm = leaves(wa.path("meta").get("_wp_attachment_metadata"), w);
        Map<String, String> qm = leaves(qa.path("meta").get("_wp_attachment_metadata"), q);
        TreeSet<String> pointers = new TreeSet<>(wm.keySet());
        pointers.addAll(qm.keySet());
        for (String pointer : pointers) {
            run.compare(
                    id,
                    step + "metadata:" + pointer,
                    wm.getOrDefault(pointer, ABSENT),
                    qm.getOrDefault(pointer, ABSENT));
        }
        run.compare(id, step + "files", fileList(names(wa.get("files")), w), fileList(names(qa.get("files")), q));
    }

    private static Optional<List<String>> names(JsonNode files) {
        if (files == null || !files.isArray()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        files.forEach(file -> names.add(file.asText()));
        return Optional.of(names);
    }

    static String column(String column, JsonNode value, Observed side) {
        if (value == null || value.isNull()) {
            return ABSENT;
        }
        String text = value.asText();
        return switch (column) {
            case "ID", "post_parent" -> ref(text, side.posts());
            case "post_author" -> ref(text, side.users());
            case "post_date", "post_date_gmt", "post_modified", "post_modified_gmt" -> date(text, side.at());
            default -> normalise(text, side);
        };
    }

    static String meta(String key, JsonNode value, Observed side) {
        if (value == null || value.isNull()) {
            return key.equals("_wp_attachment_image_alt") ? "\"\"" : ABSENT;
        }
        return normalise(value.toString(), side);
    }

    /** The leaves of the metadata by JSON pointer, normalised. */
    static Map<String, String> leaves(JsonNode metadata, Observed side) {
        Map<String, String> out = new LinkedHashMap<>();
        if (metadata == null || metadata.isMissingNode()) {
            return out;
        }
        boolean replacedMain = metadata.isObject() && metadata.has("original_image");
        collect(metadata, "", out, side, replacedMain);
        return out;
    }

    private static void collect(
            JsonNode node, String pointer, Map<String, String> out, Observed side, boolean replacedMain) {
        if (node.isContainerNode() && node.isEmpty()) {
            out.put(pointer.isEmpty() ? "/" : pointer, "{}");
            return;
        }
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                collect(field.getValue(), pointer + "/" + escape(field.getKey()), out, side, replacedMain);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), pointer + "/" + i, out, side, replacedMain);
            }
            return;
        }
        String key = pointer.isEmpty() ? "/" : pointer;
        boolean written = (pointer.startsWith("/sizes/") && pointer.endsWith("/filesize"))
                || (pointer.equals("/filesize") && replacedMain);
        out.put(key, written ? "{bytes}" : normalise(node.toString(), side));
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static String fileList(Optional<List<String>> files, Observed side) {
        return files.map(list -> {
                    List<String> sorted = new ArrayList<>(list);
                    sorted.sort(null);
                    return normalise(String.join(", ", sorted), side);
                })
                .orElse(ABSENT);
    }

    private static String idList(Optional<List<Long>> ids, Observed side) {
        return ids.map(list -> {
                    List<String> names = new ArrayList<>();
                    List<Long> order = new ArrayList<>(side.posts().keySet());
                    List<Long> sorted = new ArrayList<>(list);
                    sorted.sort((a, b) -> Integer.compare(rank(order, a), rank(order, b)));
                    sorted.forEach(id -> names.add(ref(String.valueOf(id), side.posts())));
                    return String.join(", ", names);
                })
                .orElse(ABSENT);
    }

    private static int rank(List<Long> order, long id) {
        int at = order.indexOf(id);
        return at < 0 ? Integer.MAX_VALUE : at;
    }

    private static String refList(List<Long> ids, Observed side) {
        List<String> names = new ArrayList<>();
        ids.forEach(id -> names.add(ref(String.valueOf(id), side.posts())));
        return String.join(", ", names);
    }

    /** The ref of an id; {@code 0} stays {@code 0}; an id the scenario did not create is {@code ?<id>}. */
    static String ref(String id, Map<Long, String> refs) {
        long value;
        try {
            value = Long.parseLong(id.strip());
        } catch (NumberFormatException notANumber) {
            return id;
        }
        if (value == 0) {
            return "0";
        }
        String ref = refs.get(value);
        return ref == null ? "?" + value : ref;
    }

    /** {@code {now}} for a date within {@link #NOW} of {@code at} (both UTC), else the date. */
    static String date(String text, Instant at) {
        try {
            Instant value = LocalDateTime.parse(text, MYSQL_DATE).toInstant(ZoneOffset.UTC);
            if (Duration.between(value, at).abs().compareTo(NOW) <= 0) {
                return "{now}";
            }
        } catch (DateTimeParseException notADate) {
            // compared as written
        }
        return text;
    }

    /** The site URL, a network site's uploads folder and the step's month folder replaced by placeholders. */
    static String normalise(String text, Observed side) {
        String out = sitesFolder(text.replace("http://localhost" + side.site(), "{site}/"));
        return out.replace(MediaSide.monthFolder(side.at()) + "/", "{ym}/");
    }

    /** {@code sites/<digits>/} becomes {@code sites/{id}/}. */
    static String sitesFolder(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("sites/", i)) {
                int digits = i + "sites/".length();
                int end = digits;
                while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
                    end++;
                }
                if (end > digits && end < text.length() && text.charAt(end) == '/') {
                    out.append("sites/{id}/");
                    i = end + 1;
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }
}
