package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostAuthors;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.PhpObjectNode;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaSanitizer;
import org.quarkpress.restwp.runtime.SchemaValidator;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MemberPage;
import org.quarkpress.security.MemberQuery;
import org.quarkpress.security.Membership;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.SiteRoles;
import org.quarkpress.security.UserMeta;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.UserTarget;
import org.quarkpress.security.runtime.AllCaps;

/**
 * WordPress's users routes for the request's site: {@code /wp/v2/users} (list; create), {@code /wp/v2/users/<id>}
 * (read; update; delete) and {@code /wp/v2/users/me} (the current user). A user exists here when it is a member of
 * the site (on a single site, every user, whether it has a role or not), except that {@code users/me} reads the
 * current user wherever it is; its writes, like every write, need the user to exist here.
 *
 * <p>Who sees whom is WordPress's rule: without {@code list_users} (and without {@code who=authors}) the listing holds
 * only authors of a published post of a type shown in REST on this site, filtered in the database; a single user is
 * readable by itself, by a caller who may edit it or list users, or when it has posts the caller may count.
 * E-mail addresses, roles, capabilities and stored preferences are shown only in the edit context, which needs
 * {@code list_users} for the listing and {@code edit_user} for another user; the e-mail is never searched without
 * {@code list_users}.
 *
 * <p>Users are managed by the identity provider: after WordPress's permission check, creating a user, deleting one and
 * changing any profile field answer 501. The one write applied is a user's own editor preferences ({@code
 * meta.persisted_preferences}), stored per site.
 */
@ApplicationScoped
public class UsersController implements RestRouteProvider {

    private static final String NAMESPACE = "wp/v2";

    private static final String COLLECTION = "/" + NAMESPACE + "/users";

    private static final String PREFERENCES = "persisted_preferences";

    private static final String PREFERENCES_PARAM = "meta." + PREFERENCES;

    private static final DateTimeFormatter REGISTERED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'").withZone(ZoneOffset.UTC);

    @Inject
    Memberships memberships;

    @Inject
    UserProfiles profiles;

    @Inject
    UserMeta userMeta;

    @Inject
    Capabilities capabilities;

    @Inject
    SiteRoles siteRoles;

    @Inject
    PostPermissions postPermissions;

    @Inject
    PostAuthors authors;

    @Inject
    PostTypes types;

    @Inject
    RestUrls urls;

    @Inject
    SiteContext site;

    @Override
    public int order() {
        return 500;
    }

    @Override
    public void register(RouteRegistry routes) {
        ObjectNode schema = UserSchema.of();
        List<String> restTypes = restTypes();
        routes.route(NAMESPACE, "/users")
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .args(UserSchema.collectionArgs(schema, restTypes))
                        .permission(this::mayList)
                        .handler(request -> list(schema, restTypes, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("POST")
                        .args(UserSchema.writeArgs(true))
                        .permission(this::mayCreate)
                        .handler(request -> {
                            throw new RestException(managedExternally());
                        })
                        .allowBatch(true)
                        .build())
                .register();
        routes.route(NAMESPACE, "/users/(?P<id>[\\d]+)")
                .arg(UserSchema.idArg())
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(schema))
                        .permission(request -> mayRead(restTypes, request))
                        .handler(request -> item(schema, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .args(UserSchema.writeArgs(false))
                        .permission(request -> mayUpdate(requested(request), request))
                        .handler(request -> update(schema, requested(request), request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .args(UserSchema.deleteArgs())
                        .permission(request -> mayDelete(requested(request), request))
                        .handler(request -> {
                            throw new RestException(cannotDelete());
                        })
                        .allowBatch(true)
                        .build())
                .register();
        routes.route(NAMESPACE, "/users/me")
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(schema))
                        .handler(request -> me(schema, request))
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .args(UserSchema.writeArgs(false))
                        .permission(request -> mayUpdate(current(request), request))
                        .handler(request -> update(schema, current(request), request))
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .args(UserSchema.deleteArgs())
                        .permission(request -> mayDelete(current(request), request))
                        .handler(request -> {
                            throw new RestException(cannotDelete());
                        })
                        .build())
                .register();
    }

    // ---- permissions

    private PermissionResult mayList(RestRequest request) {
        boolean listUsers = can(request, "list_users");
        if (!listUsers && given(request, "roles")) {
            return denied(request, "rest_user_cannot_view", "Sorry, you are not allowed to filter users by role.");
        }
        if (!listUsers && given(request, "capabilities")) {
            return denied(
                    request, "rest_user_cannot_view", "Sorry, you are not allowed to filter users by capability.");
        }
        if (!listUsers && request.isEditContext()) {
            return denied(request, "rest_forbidden_context", "Sorry, you are not allowed to edit users.");
        }
        String orderby = text(request.param("orderby"));
        if (!listUsers && (orderby.equals("email") || orderby.equals("registered_date"))) {
            return denied(
                    request, "rest_forbidden_orderby", "Sorry, you are not allowed to order users by this parameter.");
        }
        if (text(request.param("who")).equals("authors")
                && !postPermissions.canEditSomeRestTypeSupporting(request.identity(), "author")) {
            return denied(
                    request, "rest_forbidden_who", "Sorry, you are not allowed to query users by this parameter.");
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayRead(List<String> restTypes, RestRequest request) {
        Optional<UserProfile> found = requested(request);
        if (found.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        UserId user = found.get().id();
        if (isSelf(request, user)) {
            return PermissionResult.allowed();
        }
        boolean mayEdit = canEdit(request.identity(), user);
        if (request.isEditContext() && !mayEdit) {
            return denied(request, "rest_forbidden_context", "Sorry, you are not allowed to edit this user.");
        }
        if (mayEdit
                || can(request, "list_users")
                || authors.hasPostsVisibleToCaller(request.identity(), user, restTypes)) {
            return PermissionResult.allowed();
        }
        return denied(request, "rest_user_cannot_view", "Sorry, you are not allowed to list users.");
    }

    private PermissionResult mayCreate(RestRequest request) {
        if (!can(request, "create_users")) {
            return denied(request, "rest_cannot_create_user", "Sorry, you are not allowed to create new users.");
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayUpdate(Optional<UserProfile> target, RestRequest request) {
        if (target.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        // roles to set are checked first, against promote_user
        if (request.param("roles").filter(roles -> !PhpValues.isEmpty(roles)).isPresent()
                && !capabilities.can(
                        request.identity(),
                        "promote_user",
                        Optional.of(new UserTarget(target.get().id())))) {
            return denied(request, "rest_cannot_edit_roles", "Sorry, you are not allowed to edit roles of this user.");
        }
        if (!canEdit(request.identity(), target.get().id())) {
            return denied(request, "rest_cannot_edit", "Sorry, you are not allowed to edit this user.");
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayDelete(Optional<UserProfile> target, RestRequest request) {
        if (target.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        if (!capabilities.can(
                request.identity(),
                "delete_user",
                Optional.of(new UserTarget(target.get().id())))) {
            return denied(request, "rest_user_cannot_delete", "Sorry, you are not allowed to delete this user.");
        }
        return PermissionResult.allowed();
    }

    // ---- handlers

    private RestResponse list(ObjectNode schema, List<String> restTypes, RestRequest request) {
        Paging paging = Paging.of(request);
        boolean listUsers = can(request, "list_users");
        Map<String, RoleDefinition> roles = roleTable();
        Optional<MemberQuery> query = UserQueries.of(request, restTypes, listUsers, paging, roles);
        if (query.isEmpty()) {
            // an include list of only zeros: WordPress's IN (0) matches nothing, and counts nothing
            return RestResponse.paged(RestJson.NODES.arrayNode(), 0, paging, request);
        }
        MemberPage page = memberships.members(site.siteId(), query.get());
        if (request.method().equals("HEAD")) {
            // no body is sent, so no user is rendered
            return RestResponse.paged(RestJson.NODES.arrayNode(), page.total(), paging, request);
        }
        boolean edit = request.isEditContext();
        Fields.Selection selection = Fields.forResponse(request, schema);
        ArrayNode items = RestJson.NODES.arrayNode();
        for (UserProfile profile : profiles.findAll(page.users())) {
            boolean mayEdit = edit && (isSelf(request, profile.id()) || canEdit(request.identity(), profile.id()));
            if (edit && !mayEdit) {
                // dropped after paging, so the totals still count it
                continue;
            }
            items.add(CollectionItems.inline(
                    prepare(schema, profile, selection, request.context(), mayEdit, listUsers, roles), request));
        }
        return RestResponse.paged(items, page.total(), paging, request);
    }

    private RestResponse item(ObjectNode schema, RestRequest request) {
        UserProfile profile = requested(request).orElseThrow(() -> new RestException(invalidId()));
        return render(schema, profile, Fields.forResponse(request, schema), request.context(), request);
    }

    private RestResponse me(ObjectNode schema, RestRequest request) {
        UserProfile profile = request.user()
                .flatMap(profiles::find)
                .orElseThrow(() ->
                        new RestException(RestError.of("rest_not_logged_in", "You are not currently logged in.", 401)));
        return render(schema, profile, Fields.forResponse(request, schema), request.context(), request);
    }

    /**
     * Applies the one write QuarkPress takes, a user's own {@code meta.persisted_preferences} (null removes them),
     * when it is the only field the request writes; anything else is refused with 501 and nothing is written.
     */
    private RestResponse update(ObjectNode schema, Optional<UserProfile> target, RestRequest request) {
        UserProfile profile = target.orElseThrow(() -> new RestException(invalidId()));
        Optional<JsonNode> preferences = preferencesOnly(request);
        if (!isSelf(request, profile.id()) || preferences.isEmpty()) {
            throw new RestException(managedExternally());
        }
        String key = userMeta.siteKey(site.siteId(), PREFERENCES);
        JsonNode value = preferences.get();
        if (value.isNull()) {
            userMeta.delete(profile.id(), key);
        } else {
            ObjectNode valueSchema = UserSchema.preferences();
            Optional<RestError> invalid = SchemaValidator.validate(value, valueSchema, PREFERENCES_PARAM);
            if (invalid.isPresent()) {
                ObjectNode status = RestJson.NODES.objectNode();
                status.put("status", 400);
                throw new RestException(invalid.get().withData(status));
            }
            userMeta.set(profile.id(), key, SchemaSanitizer.sanitize(value, valueSchema, PREFERENCES_PARAM));
        }
        UserProfile updated = profiles.find(profile.id()).orElseThrow(() -> new RestException(invalidId()));
        Fields.Selection selection = Fields.forResponse(Fields.of(request), schema, "edit", request.hasParam("_embed"));
        return render(schema, updated, selection, "edit", request);
    }

    // ---- helpers

    /** The member of this site the request's {@code id} names; empty for an id below 1, unknown or not a member. */
    private Optional<UserProfile> requested(RestRequest request) {
        return member(request.param("id").map(PhpValues::toLong).orElse(0L));
    }

    /** The current user as a member of this site; empty when logged out or not a member. */
    private Optional<UserProfile> current(RestRequest request) {
        return member(request.user().map(UserId::value).orElse(0L));
    }

    private Optional<UserProfile> member(long id) {
        if (id <= 0) {
            return Optional.empty();
        }
        UserId user = UserId.of(id);
        // on a single site every user is a user of the site; on a network, a member of it
        if (!memberships.isMember(site.siteId(), user)) {
            return Optional.empty();
        }
        return profiles.find(user);
    }

    /**
     * The request's writes when they are exactly {@code meta} holding exactly {@code persisted_preferences}: its value
     * (a JSON null when the preferences are to be removed); empty otherwise.
     */
    private static Optional<JsonNode> preferencesOnly(RestRequest request) {
        List<String> written = new ArrayList<>();
        for (String field : UserSchema.WRITE_FIELDS) {
            if (request.param(field).isPresent()) {
                written.add(field);
            }
        }
        if (!written.equals(List.of("meta"))) {
            return Optional.empty();
        }
        JsonNode meta = request.param("meta").orElseThrow();
        if (!meta.isObject() || meta.size() != 1 || !meta.has(PREFERENCES)) {
            return Optional.empty();
        }
        return Optional.of(meta.get(PREFERENCES));
    }

    private RestResponse render(
            ObjectNode schema, UserProfile profile, Fields.Selection fields, String context, RestRequest request) {
        boolean edit = context.equals("edit");
        boolean mayEdit = edit && (isSelf(request, profile.id()) || canEdit(request.identity(), profile.id()));
        boolean listUsers = edit && can(request, "list_users");
        return prepare(schema, profile, fields, context, mayEdit, listUsers, edit ? roleTable() : Map.of());
    }

    /**
     * The user's selected fields in WordPress's order, filtered by {@code context}; its links when they are selected.
     * The fields only the edit context shows are computed only there: the e-mail address and roles for a caller who
     * may edit the user or list users, the stored preferences only for one who may edit the user.
     */
    private RestResponse prepare(
            ObjectNode schema,
            UserProfile profile,
            Fields.Selection fields,
            String context,
            boolean mayEdit,
            boolean listUsers,
            Map<String, RoleDefinition> roles) {
        boolean edit = context.equals("edit");
        boolean shown = edit && (mayEdit || listUsers);
        UserId id = profile.id();
        Optional<Membership> membership =
                edit && (fields.includesExactly("roles") || fields.includesExactly("extra_capabilities"))
                        ? memberships.find(site.siteId(), id)
                        : Optional.empty();
        ObjectNode data = RestJson.NODES.objectNode();
        if (fields.includesExactly("id")) {
            data.put("id", id.value());
        }
        if (fields.includesExactly("username")) {
            data.put("username", profile.nicename());
        }
        if (fields.includesExactly("name")) {
            data.put("name", profile.displayName());
        }
        if (fields.includesExactly("first_name")) {
            data.put("first_name", "");
        }
        if (fields.includesExactly("last_name")) {
            data.put("last_name", "");
        }
        if (shown && fields.includesExactly("email")) {
            data.put("email", profile.email().orElse(""));
        }
        if (fields.includesExactly("url")) {
            data.put("url", "");
        }
        if (fields.includesExactly("description")) {
            data.put("description", profile.bio().orElse(""));
        }
        if (fields.includesExactly("link")) {
            data.put("link", urls.home() + "?author=" + id.value());
        }
        if (fields.includesExactly("locale")) {
            data.put("locale", "en_US");
        }
        if (fields.includesExactly("nickname")) {
            data.put("nickname", profile.nicename());
        }
        if (fields.includesExactly("slug")) {
            data.put("slug", profile.nicename());
        }
        if (shown && fields.includesExactly("roles")) {
            ArrayNode list = data.putArray("roles");
            membership.ifPresent(found ->
                    AllCaps.roles(roles, found.roles(), found.capabilities()).forEach(list::add));
        }
        if (fields.includesExactly("registered_date")) {
            data.put("registered_date", REGISTERED.format(profile.createdAt().truncatedTo(ChronoUnit.SECONDS)));
        }
        if (edit && fields.includesExactly("capabilities")) {
            PhpObjectNode all = new PhpObjectNode();
            capabilities.allCapabilities(id, site.siteId()).forEach(all::put);
            data.set("capabilities", all);
        }
        if (edit && fields.includesExactly("extra_capabilities")) {
            PhpObjectNode extra = new PhpObjectNode();
            membership.ifPresent(found -> {
                found.roles().forEach(role -> extra.put(role, true));
                found.capabilities().forEach(extra::put);
            });
            data.set("extra_capabilities", extra);
        }
        if (fields.includesExactly("avatar_urls")) {
            Avatars.urls(profile.email(), avatarName(profile), site.options())
                    .ifPresent(avatars -> data.set("avatar_urls", avatars));
        }
        if (fields.includesExactly("meta")) {
            // outside the edit context the context filter removes the preferences, leaving WordPress's []
            JsonNode preferences = edit && mayEdit
                    ? userMeta.get(id, userMeta.siteKey(site.siteId(), PREFERENCES))
                            .map(UsersController::phpArrays)
                            .orElseGet(RestJson.NODES::arrayNode)
                    : RestJson.NODES.arrayNode();
            data.putObject("meta").set(PREFERENCES, preferences);
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, schema, context));
        if (fields.includes("_links") || fields.includes("_embedded")) {
            response = response.withLinks(Links.none()
                    .add("self", urls.route(COLLECTION + "/" + id.value()))
                    .add("collection", urls.route(COLLECTION)));
        }
        return response;
    }

    /**
     * A stored JSON value as PHP prints the array it decodes to: an empty object is an empty list, an object whose
     * keys are 0, 1, 2 and so on in order is a list.
     */
    private static JsonNode phpArrays(JsonNode value) {
        if (value.isArray()) {
            ArrayNode list = RestJson.NODES.arrayNode(value.size());
            value.forEach(item -> list.add(phpArrays(item)));
            return list;
        }
        if (!value.isObject()) {
            return value;
        }
        boolean asList = true;
        int index = 0;
        for (String key : (Iterable<String>) value::fieldNames) {
            if (!key.equals(Integer.toString(index++))) {
                asList = false;
                break;
            }
        }
        if (asList) {
            ArrayNode list = RestJson.NODES.arrayNode(value.size());
            value.forEach(item -> list.add(phpArrays(item)));
            return list;
        }
        ObjectNode object = RestJson.NODES.objectNode();
        value.properties().forEach(entry -> object.set(entry.getKey(), phpArrays(entry.getValue())));
        return object;
    }

    /** The name WordPress takes initials from: the display name, else the login (the nicename here). */
    private static String avatarName(UserProfile profile) {
        return profile.displayName().isEmpty() ? profile.nicename() : profile.displayName();
    }

    private Map<String, RoleDefinition> roleTable() {
        Map<String, RoleDefinition> table = new LinkedHashMap<>();
        for (RoleDefinition role : siteRoles.roles(site.siteId())) {
            table.put(role.key(), role);
        }
        return table;
    }

    private List<String> restTypes() {
        List<String> rest = new ArrayList<>();
        for (PostTypeDefinition type : types.all()) {
            if (type.showInRest()) {
                rest.add(type.key());
            }
        }
        return List.copyOf(rest);
    }

    private boolean can(RestRequest request, String capability) {
        return capabilities.can(request.identity(), capability, Optional.empty());
    }

    private boolean canEdit(SecurityIdentity identity, UserId user) {
        return capabilities.can(identity, "edit_user", Optional.of(new UserTarget(user)));
    }

    private static boolean isSelf(RestRequest request, UserId user) {
        return request.user().filter(user::equals).isPresent();
    }

    /** Whether the argument is given and not empty, as PHP's {@code empty} sees it. */
    private static boolean given(RestRequest request, String name) {
        return request.param(name).filter(value -> !PhpValues.isEmpty(value)).isPresent();
    }

    private static String text(Optional<JsonNode> value) {
        return UserQueries.text(value);
    }

    private static PermissionResult denied(RestRequest request, String code, String message) {
        return PermissionResult.denied(RestError.of(code, message, request.authCode()));
    }

    private static RestError invalidId() {
        return RestError.of("rest_user_invalid_id", "Invalid user ID.", 404);
    }

    private static RestError managedExternally() {
        return RestError.of("quarkpress_users_managed_externally", "Users are managed by the identity provider.", 501);
    }

    private static RestError cannotDelete() {
        return RestError.of("rest_cannot_delete", "The user cannot be deleted.", 501);
    }
}
