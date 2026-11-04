package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.ExcerptFilter;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TitleFilter;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.revisions.RevisionSnapshot;

/**
 * A revision's fields as WordPress's revisions controller prepares them, in its order: {@code author} (0 for none),
 * the four dates from the revision's one time (the site's local time and GMT; never null), {@code id},
 * {@code parent}, {@code slug} ({@code <parent>-revision-v1} or {@code <parent>-autosave-v1}), {@code guid}
 * ({@code <home>?p=<revision id>}), then {@code title}, {@code content} and {@code excerpt} when the parent type has
 * them ({@code content} without the protection flags: a revision has no password; an empty excerpt stays empty, it
 * is never cut from the content), {@code meta} (the revisioned keys from the revision, every other key at its
 * default), and a {@code parent} link to the post's own route; filtered by the context. The rendered texts run the
 * registered filter chains. The item schema is the type's revision schema: its own properties, the text fields
 * borrowed from the type's posts schema, and the type's {@code meta}. A bean only while the revisions module is
 * present: the build removes it otherwise.
 */
@ApplicationScoped
class RevisionFields {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final List<String> ALL = List.of("view", "edit", "embed");
    private static final List<String> VIEW_EDIT = List.of("view", "edit");
    private static final List<String> EDIT = List.of("edit");

    private record Rendering(SiteId site) implements RenderContext {}

    /** A type's revision schema and the routes it was built for. */
    private record Cached(PostRoutes routes, ObjectNode schema) {}

    /**
     * What a revision answer is built from, whether the row is a revision or the post itself ({@code date} and
     * {@code dateGmt} null for a post whose date floats).
     */
    private record Values(
            long author,
            String date,
            String dateGmt,
            long id,
            String modified,
            String modifiedGmt,
            long parent,
            String slug,
            String guid,
            String title,
            String content,
            String excerpt,
            JsonNode meta,
            SiteId site) {}

    @Inject
    SiteContext site;

    @Inject
    RestUrls urls;

    @Inject
    MetaFields meta;

    @Inject
    @All
    List<InstanceHandle<TitleFilter>> titleHandles;

    @Inject
    @All
    List<InstanceHandle<ContentFilter>> contentHandles;

    @Inject
    @All
    List<InstanceHandle<ExcerptFilter>> excerptHandles;

    private final Map<String, Cached> schemas = new ConcurrentHashMap<>();

    private List<TitleFilter> titleFilters;
    private List<ContentFilter> contentFilters;
    private List<ExcerptFilter> excerptFilters;

    @PostConstruct
    void order() {
        titleFilters = Filters.ordered(titleHandles);
        contentFilters = Filters.ordered(contentHandles);
        excerptFilters = Filters.ordered(excerptHandles);
    }

    /**
     * The revision's selected fields in WordPress's order, filtered by {@code context}, with its parent link when
     * {@code parent} is selected (whether or not {@code _links} is).
     */
    RestResponse prepare(PostRoutes routes, RevisionSnapshot revision, Fields.Selection fields, String context) {
        ZoneId zone = site.zone();
        String local = DATE.format(LocalDateTime.ofInstant(revision.createdAt(), zone));
        String gmt = gmt(revision.createdAt());
        long parent = revision.post().value();
        Values values = new Values(
                revision.author().map(UserId::value).orElse(0L),
                local,
                gmt,
                revision.id().value(),
                local,
                gmt,
                parent,
                parent + (revision.autosave() ? "-autosave-v1" : "-revision-v1"),
                urls.home() + "?p=" + revision.id().value(),
                revision.title(),
                revision.content(),
                revision.excerpt(),
                meta.read(routes.key(), revision::meta),
                revision.site());
        return prepare(routes, values, fields, context, Optional.of(revision.post()));
    }

    /**
     * The post itself in the revision shape (the autosave answer for a draft of the caller's own, which WordPress
     * updates in place and answers through the revision preparation): its own dates ({@code date} and
     * {@code date_gmt} null while its date floats), id, parent, slug and guid, its texts and meta, and a parent link
     * when it has a parent.
     */
    RestResponse prepareOwnDraft(PostRoutes routes, PostSnapshot post, Fields.Selection fields, String context) {
        ZoneId zone = site.zone();
        Optional<Instant> dateGmt = post.publishedAt();
        Values values = new Values(
                post.author().map(UserId::value).orElse(0L),
                dateGmt.map(date -> DATE.format(LocalDateTime.ofInstant(date, zone)))
                        .orElse(null),
                dateGmt.map(RevisionFields::gmt).orElse(null),
                post.id().value(),
                DATE.format(post.localModified(zone)),
                gmt(post.modifiedAt()),
                post.parent().map(PostId::value).orElse(0L),
                post.slug(),
                post.guid(),
                post.title(),
                post.content(),
                post.excerpt(),
                meta.read(post.type(), post),
                post.site());
        return prepare(routes, values, fields, context, post.parent());
    }

    private RestResponse prepare(
            PostRoutes routes, Values values, Fields.Selection fields, String context, Optional<PostId> parent) {
        ObjectNode data = RestJson.NODES.objectNode();
        if (fields.includes("author")) {
            data.put("author", values.author());
        }
        if (fields.includes("date")) {
            data.put("date", values.date());
        }
        if (fields.includes("date_gmt")) {
            data.put("date_gmt", values.dateGmt());
        }
        if (fields.includes("id")) {
            data.put("id", values.id());
        }
        if (fields.includes("modified")) {
            data.put("modified", values.modified());
        }
        if (fields.includes("modified_gmt")) {
            data.put("modified_gmt", values.modifiedGmt());
        }
        if (fields.includes("parent")) {
            data.put("parent", values.parent());
        }
        if (fields.includes("slug")) {
            data.put("slug", values.slug());
        }
        if (fields.includes("guid")) {
            ObjectNode guid = data.putObject("guid");
            if (fields.includes("guid.rendered")) {
                guid.put("rendered", values.guid());
            }
            if (fields.includes("guid.raw")) {
                guid.put("raw", values.guid());
            }
        }
        Rendering rendering = new Rendering(values.site());
        if (routes.has("title") && fields.includes("title")) {
            ObjectNode title = data.putObject("title");
            if (fields.includes("title.raw")) {
                title.put("raw", values.title());
            }
            if (fields.includes("title.rendered")) {
                title.put(
                        "rendered",
                        Filters.apply(titleFilters, values.title(), (filter, value) -> filter.apply(value, rendering)));
            }
        }
        if (routes.has("content") && fields.includes("content")) {
            ObjectNode content = data.putObject("content");
            if (fields.includes("content.raw")) {
                content.put("raw", values.content());
            }
            if (fields.includes("content.rendered")) {
                content.put(
                        "rendered",
                        Filters.apply(
                                contentFilters, values.content(), (filter, value) -> filter.apply(value, rendering)));
            }
        }
        if (routes.has("excerpt") && fields.includes("excerpt")) {
            ObjectNode excerpt = data.putObject("excerpt");
            if (fields.includes("excerpt.raw")) {
                excerpt.put("raw", values.excerpt());
            }
            if (fields.includes("excerpt.rendered")) {
                excerpt.put(
                        "rendered",
                        Filters.apply(
                                excerptFilters, values.excerpt(), (filter, value) -> filter.apply(value, rendering)));
            }
        }
        if (fields.includes("meta")) {
            data.set("meta", values.meta());
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, schema(routes), context));
        // WordPress links the parent whenever the answer carries it, whatever the requested fields say of _links
        if (parent.isPresent() && fields.includes("parent")) {
            response = response.withLinks(Links.none().add("parent", urls.route(routes.item(parent.get()))));
        }
        return response;
    }

    /**
     * The revision schema of the type's routes, computed once per registration of those routes (the registry hands
     * each registration new routes, so a changed meta schema is picked up when the routes are rebuilt).
     */
    ObjectNode schema(PostRoutes routes) {
        Cached cached = schemas.get(routes.key());
        if (cached == null || cached.routes() != routes) {
            cached = new Cached(routes, schema(routes, meta.schema(routes.key())));
            schemas.put(routes.key(), cached);
        }
        return cached.schema();
    }

    /**
     * The {@code <type>-revision} schema: the revision's own properties, then {@code title}, {@code content} and
     * {@code excerpt} as the type's posts schema has them (when it has them), then the type's {@code meta}.
     */
    static ObjectNode schema(PostRoutes routes, ObjectNode metaSchema) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("$schema", "http://json-schema.org/draft-04/schema#");
        schema.put("title", routes.key() + "-revision");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        property(properties, "author", "The ID for the author of the revision.", "integer", ALL);
        dateProperty(properties, "date", "The date the revision was published, in the site's timezone.", ALL);
        dateProperty(properties, "date_gmt", "The date the revision was published, as GMT.", VIEW_EDIT);
        ObjectNode guid =
                property(properties, "guid", "The globally unique identifier for the post.", "object", VIEW_EDIT);
        guid.put("readonly", true);
        ObjectNode guidProperties = guid.putObject("properties");
        property(guidProperties, "raw", "GUID for the post, as it exists in the database.", "string", EDIT)
                .put("readonly", true);
        property(guidProperties, "rendered", "GUID for the post, transformed for display.", "string", VIEW_EDIT)
                .put("readonly", true);
        property(properties, "id", "Unique identifier for the revision.", "integer", ALL);
        dateProperty(
                properties, "modified", "The date the revision was last modified, in the site's timezone.", VIEW_EDIT);
        dateProperty(properties, "modified_gmt", "The date the revision was last modified, as GMT.", VIEW_EDIT);
        property(properties, "parent", "The ID for the parent of the revision.", "integer", ALL);
        property(properties, "slug", "An alphanumeric identifier for the revision unique to its type.", "string", ALL);
        JsonNode posts = routes.schema().get("properties");
        for (String borrowed : List.of("title", "content", "excerpt")) {
            if (posts.has(borrowed)) {
                properties.set(borrowed, posts.get(borrowed).deepCopy());
            }
        }
        properties.set("meta", metaSchema.deepCopy());
        return schema;
    }

    private static ObjectNode property(
            ObjectNode properties, String name, String description, String type, List<String> contexts) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        property.put("type", type);
        ArrayNode list = property.putArray("context");
        contexts.forEach(list::add);
        return property;
    }

    private static void dateProperty(ObjectNode properties, String name, String description, List<String> contexts) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        property.put("type", "string");
        property.put("format", "date-time");
        ArrayNode list = property.putArray("context");
        contexts.forEach(list::add);
    }

    private static String gmt(Instant instant) {
        return DATE.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
