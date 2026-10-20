# QuarkPress Security

WordPress's users, roles and capabilities on top of OIDC: every authenticated subject gets a user profile named as
WordPress names a new user, users are members of sites with roles and individual capabilities, network
administrators (WordPress's super admins) administer every site of a network, and service methods are secured with
WordPress's capability checks, answered as WordPress 7.1.3 answers them.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-security</artifactId>
</dependency>
```

The extension brings `quarkpress-persistence`, `quarkus-oidc` (and with it `quarkus-security`) and
`quarkpress-lib-wp-kses`. Configure OIDC as Quarkus documents it (`quarkus.oidc.auth-server-url` and the client in
production). In dev and test, Dev Services for Keycloak starts Keycloak with QuarkPress's dev realm (see
[Dev mode](#dev-mode)). With the security module installed, the network services (`Sites`, `SiteDomains`) check
capabilities for real; without it they allow every caller. The installation is a network when
the multisite module is installed (decided at build time) and a single site otherwise.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.security.initial-super-admins` | none | OIDC subjects (`sub`) made network administrators at every start (a profile is created if missing). Entries are trimmed and empty ones skipped. Without the multisite module they become administrators of the main site. |
| `quarkpress.security.disallow-unfiltered-html` | `false` | Refuse `unfiltered_html` and `edit_css` to everyone, network administrators included (WordPress's `DISALLOW_UNFILTERED_HTML`). |
| `quarkpress.dev.seed-memberships` | `true` | In dev mode only: give the dev realm's users their memberships on the main site at startup. |

## Users

- **Profiles.** The first time a subject authenticates with an OIDC token that carries `sub`, it gets a profile in
  `qp_user_profile`: a numeric `UserId`, the subject, a display name and a nicename. Concurrent first logins of one
  subject create one profile. `UserProfiles` reads them; `UserProfileProvisioner` can be replaced with an
  `@Alternative @Priority` bean (it does not shape the profiles that `initial-super-admins` and dev seeding create).
- **Subjects.** A subject with leading or trailing whitespace, or longer than 255 characters, is refused (the token
  fails authentication with 401): MySQL's collation would otherwise match `abc ` to the profile of `abc`. This is a
  QuarkPress rule, not WordPress behaviour.
- **Nicename** (WordPress's `user_nicename`, unique in the network): `preferred_username` sanitized as WordPress
  sanitizes a login (tags, accents with the `en_US` rules, percent octets and entities removed, then only ASCII
  letters, digits, space, `_ . - @` kept), its first 50 characters, then WordPress's slug rules; taken nicenames get
  `-2`, `-3`, … within 50 characters. A username that gives no nicename (missing, only non-Latin letters, only dots,
  or `0`) gives `user-<id>`.
- **Display name**: `given_name family_name`, else `given_name`, else `family_name`, else `preferred_username`, else the
  subject; given and family name trimmed as PHP trims (one that is empty or `0` after trimming counts as absent), at
  most 250 characters, stored as given. It is set when the profile is created; later logins do not change it.
- **Login time**: written on creation and when the stored one is more than 15 minutes old. Profiles made at start
  (`initial-super-admins`, dev seeding) record no login.
- `UserProfileCreated` is fired when a profile is created.

## The identity

Every identity authenticated with an OIDC token that carries a usable `sub` gets:

- the attribute `QuarkPressPrincipal` (`QuarkPressPrincipal.of(identity)`): its `UserId` and subject;
- as roles, the user's role keys on the request's site (never a network role);
- a permission checker answering `StringPermission(capability)` (without actions) with the capability check below,
  for the binding in effect when the permission is checked.

What else happens to an identity:

- **Identity-provider roles are dropped.** Token groups and realm or client roles never become roles: WordPress
  roles are per site. A host application on the same server that relies on `@RolesAllowed` with IdP roles must read
  the token's claims itself. `quarkus.http.auth.roles-mapping` still applies, after augmentation, so it maps from the
  site roles.
- **Only tokens with `sub` are augmented.** A token without `sub`, a principal that is not a `JsonWebToken` (for
  example an opaque token checked by introspection) and any other mechanism's identity pass through unchanged and
  count as logged out for QuarkPress (no principal, no roles, no capabilities: fail closed). A `sub` that is blank,
  padded with whitespace or too long fails authentication.
- **Roles are a snapshot** taken when the identity is built; capabilities are decided at every check, so a change is
  seen by the next check even on a token issued before it.
- **Order.** The augmentor runs with Quarkus's other augmentors, highest priority first, except that Quarkus's own
  permission augmentor (which turns `@PermissionChecker` methods into permissions) always runs last.
- **Permissions without actions are capabilities.** Every `StringPermission` without actions is treated as a
  WordPress capability, and network administrators pass all of them. Give your own permissions an action, or use your
  own `Permission` class. Token scopes also become permissions in Quarkus OIDC: do not give scopes the names of
  capabilities.

## Checking capabilities

`Capabilities` answers WordPress's `current_user_can`, `current_user_can_for_site` and `user_can_for_site`:

- `can(capability)` and `can(capability, object)`: the current identity, for the binding in effect: the site the
  request or `SiteScope` is bound to, the main site for a network-bound caller, no site when unresolved (only `exist`
  is held, and network administrators and the system pass);
- `can(identity, capability, object)`: for `@PermissionChecker` methods;
- `canForSite(identity, site, …)`, `userCan(user, site, …)`, `anonymousCan(site, …)`: an explicit site (on a single
  site the site id is ignored, as WordPress ignores it there, and the answer is for the only site);
- `isSuperAdmin(user)`: WordPress's `is_super_admin`: in a network, a network administrator; on a single site, a user
  who can delete users (which gives no bypass).

A user id without a profile counts as a logged-out visitor. A check maps the capability to the primitive capabilities
it requires (WordPress's `map_meta_cap`), then:

- in a network, a network administrator passes every check that does not require `do_not_allow`, on every site,
  with or without membership; on a single site there is no such bypass;
- everyone else needs every required capability among their capabilities on the site: the roles' capabilities in
  role order (a later role wins), then the user's individual entries over them (`false` denies), plus WordPress's core
  grants (language installs, resuming plugins and themes, site health on a single site);
- `exist` is held by everyone, logged out or not; `do_not_allow` by nobody.

Meta capabilities about an object take a `CapObject`:

- posts: `PostFacts` (the post type's `PostTypeCapabilities`, the status, the author, the status before trashing,
  the effective status and its `PostVisibility`), built by the module that owns the posts (`PostFacts.of`;
  `PostFacts.ofAttachment` for attachments); `edit_post`, `delete_post`, `read_post`, `publish_post` and their page
  forms follow WordPress's tables (own and others' posts, published, scheduled, private and trashed ones, the front
  page, the page for posts and the privacy policy page);
- terms: `TermFacts` with the taxonomy's `TaxonomyCapabilities`; the taxonomy's default term cannot be deleted;
- users: `UserTarget` for `edit_user`, `promote_user`, `remove_user`, `delete_user`;
- `MissingObject` for an id that resolves to nothing: refused.

A network restricts to its administrators what WordPress restricts: `unfiltered_html`, `edit_css`, the file editors,
plugin, theme, core and language updates and installs, `update_php`, `update_https`, `delete_users`,
`view_site_health_checks`, `setup_network`, the privacy capabilities, `edit_users`/`edit_user` of other users (they
need `manage_network_users`, see D20; editing a network administrator needs being one), `create_users` (unless the
network option `add_new_users` is on) and plugin activation (unless the network option `menu_items` enables the
plugins menu). The network capabilities themselves (`manage_network`, `manage_sites`, `manage_network_users`,
`manage_network_plugins`, `manage_network_themes`, `manage_network_options`, `upgrade_network`, `setup_network`,
`create_sites`, `delete_sites`) are held only by network administrators and the system identity (D20). `manage_links`
requires the site option `link_manager_enabled`. `delete_site` is refused on a single site. `customize` requires
`edit_theme_options`.

Checks read the database (nothing is cached: a change is seen by the next check). A check made inside the caller's
transaction sees what that transaction sees; on MySQL a change committed by others after the transaction's first
read, such as a revoked network administrator, is not visible until it ends. An identity's permission check made on
an event-loop thread runs its reads on a worker thread.

Work inside a `SiteScope` for another site runs in a fresh request context, without the caller's identity: checks
there are anonymous. Check before opening the scope, or use `canForSite`.

## Secured methods

Primitive capabilities:

```java
@PermissionsAllowed("edit_posts")
public void saveDraft(...) { ... }
```

Meta capabilities, with a `@PermissionChecker` whose parameters bind by name to the secured method's:

```java
@PermissionsAllowed("edit_post")
public void update(PostId postId, PostChanges changes) { ... }

@PermissionChecker("edit_post")
@Blocking
boolean canEdit(SecurityIdentity identity, PostId postId) {
    return capabilities.can(identity, "edit_post", Optional.of(facts(postId)));
}
```

A checker that reads data (every capability check does) must carry `@Blocking`
(`io.smallrye.common.annotation.Blocking`): Quarkus runs a checker without it on the calling thread, which is the event
loop for an endpoint-level `@PermissionsAllowed`.

Quarkus binds a `@PermissionChecker("x")` to every `@PermissionsAllowed("x")` of the application, so QuarkPress's
own services check site-targeted capabilities in code (with the same exceptions: `UnauthorizedException` for a
logged-out caller, `ForbiddenException` otherwise; an authenticated caller without a QuarkPress principal gets
`ForbiddenException`).

### Network services in a host application

The network services (`Sites`, `SiteDomains`) throw core's `CapabilityDeniedException` (a `SecurityException`) and
do not know whether the caller is anonymous. A host application maps it: 401 (with `WWW-Authenticate: Bearer`) for
an anonymous caller, 403 otherwise, as a refused `@PermissionsAllowed` answers. `integration-tests/security`'s
`CapabilityDeniedMapper` is the example:

```java
@Provider
public class CapabilityDeniedMapper implements ExceptionMapper<CapabilityDeniedException> {
    @Inject SecurityIdentity identity;

    public Response toResponse(CapabilityDeniedException exception) {
        if (identity.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED).header("WWW-Authenticate", "Bearer").build();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}
```

## Roles

- `Roles` holds the default table: WordPress's administrator, editor, author, contributor and subscriber with their
  capabilities (the same table on a single site and in a network), then roles extensions register with
  `RoleBuildItem`, in key order, with capabilities extensions grant with `CapabilityBuildItem`.
- `SiteRoles` reads and changes a site's table. The first change stores the whole table, in normalised form, in the site
  option `qp_user_roles`; until then the site follows the default table of the running build, and a change that changes
  nothing stores nothing. Adding a role that exists changes nothing; removing a role resets a `default_role` that named
  it to `subscriber`; users keep a removed role's key as a plain capability. Adding a role with the key `""` or `0`
  changes nothing. Reads are unguarded (like WordPress's `get_role`); changes require `manage_options` on the site. On a
  single site every site id means the main site.

## Memberships

`Memberships` manages users' memberships of sites (`qp_site_membership`): a role list and individual capabilities.

- `add` sets the user's role (replacing their roles, keeping individual capabilities; an empty role makes a member
  without role) and gives a user without a primary site this one; `remove` ends the membership and, when it was the
  user's primary site, moves the primary site to the user's first other site (by id) that is not archived, spam or
  deleted; a user left with no such site has no primary site; `setRole`, `addRole`, `removeRole`, `setCapability`,
  `removeCapability` follow WordPress's user API. `remove` of a non-member returns false and fires no event. On a single
  site every membership is of the main site, whatever site id is given.
- Changes require `promote_user` on the target user (`remove_user` for removal) on the target site and fire
  `MembershipChanged` when the user's roles change (adding and removing always).
- What a change gives is checked (D24): the role must exist in the site's table, and only the system and network
  administrators may give any role or capability. Anyone else may give only roles whose capabilities they hold on the
  site and grant only capabilities they hold, and never a network capability, granted or denied; a role key stored
  as an individual entry (granted or denied) counts as giving the role. Removals are not checked: removing a stored
  denial restores only what the user's roles grant. `SiteRoles.add` and `setCapability` follow the same rule. On a
  single site, a user who can delete users may grant any name but a network capability. The check and the write are
  separate steps: a concurrent change between them is not detected.
- `isMember`: in a network, a membership row on a site that is not archived, spam or deleted; on a single site, every
  existing user.
- Adding or removing a user after another change of that user on a different site, inside one caller transaction, is
  refused with `IllegalStateException` (it would wait for the first change's lock on the user's profile). A change that
  meets a deadlock or lock timeout fails with `PessimisticLockException`: retry the whole transaction.
- `grantSuperAdmin`, `revokeSuperAdmin` (network administrators, `qp_network_admin`) require
  `manage_network_options`, return false on a single site and fire `SuperAdminChanged`. Revoking the last one is
  allowed, and with `initial-super-admins` empty there is then no recovery except code running as the system
  identity: list a subject and restart.
- `initial-super-admins` is applied at start after the application's startup observers of default priority, so those
  see the state of the previous run. A login that interleaves with the bootstrap can fail with a retryable deadlock.

## The system identity

`SystemIdentity.run(work)` and `call(work)` run background work (scheduling, imports, startup tasks) as the system:
every capability except `unfiltered_html`, never past `do_not_allow`, and no user (user-editing checks refuse it).
They refuse on an event-loop thread, inside an HTTP request and when a user is authenticated. These refusals are a
tripwire, not a boundary: a plain Vert.x handler, a websocket handler and work that hops threads are not recognised
as serving a request. The system identity is never derived from a token and cannot be forged, but `identity()` is
public: code that holds the bean (trusted, in-process code) can use it. A service that acts as the system for a
caller must check the caller's capability first, on the caller's thread. Inside a `SiteScope` for another site, call
`run` inside the scope's work.

## Dev mode

Dev Services for Keycloak imports `quarkpress-dev-realm.json` (realm `quarkpress`, client `quarkus-app` with secret
`secret`). The realm and its passwords (each user's password is the user name) are dev-only fixtures: never import
them into a real Keycloak.

| User | Password | Subject | Role on the main site |
|---|---|---|---|
| alice | alice | `a11ce000-0000-4000-8000-000000000001` | network administrator (single site: administrator) |
| bob | bob | `b0b00000-0000-4000-8000-000000000002` | editor |
| carol | carol | `ca401000-0000-4000-8000-000000000003` | author |
| dave | dave | `da7e0000-0000-4000-8000-000000000004` | contributor |
| erin | erin | `e4100000-0000-4000-8000-000000000005` | subscriber |

In dev mode (not in tests, not in production), `quarkpress.dev.seed-memberships` (default true) creates their profiles
and roles at every startup, resetting the roles of these five users to the table above. Against a shared database this
inserts five users with fixed subjects and makes alice a network administrator (single site: administrator): set
`quarkpress.dev.seed-memberships=false` there. Override `quarkus.keycloak.devservices.realm-path` and `realm-name` to
use another realm.

Bob also holds the Keycloak realm role `administrator`, on purpose: it is a probe that identity-provider roles are
ignored. His token carries it; his identity never does (he is an editor on the main site).

## Rules for module authors

- Describe your objects for capability checks with `PostFacts`, `TermFacts` or `UserTarget`; register custom post
  type meta capability names with a `MetaCapabilityNames` bean.
- Change the mapping with a `MetaCapabilityFilter` (it runs for network administrators too) and a user's
  capabilities for one check with a `UserCapabilitiesFilter` (not for network administrators or the system); both run
  in `@Priority` order, higher first, which is the reverse of WordPress's filters.
- Check capabilities before opening a `SiteScope` for another site, and run background work as `SystemIdentity`.
- Site-scoped tables that refer to a membership use the composite key `(site_id, user_id)`.
- Do not provide your own `CapabilityCheck` bean next to this module: the dependency is ambiguous.
- Names are derived by `UserNames` (runtime package, not API): `nicenameBase` is the gate that decides whether
  WordPress would refuse the username; `login` and `nicenameOfLogin` are raw steps and do not refuse.

## Differences from WordPress

| Item | Difference |
|---|---|
| D1 | Meta capabilities for post, term, user and comment meta, `edit_comment` and `edit_block_binding` are not mapped yet (they arrive with their modules) and behave as unknown names: held only by network administrators and users given them. |
| D2 | Revisions are not posts: the post rules never see one; the revisions module checks a revision against its parent. |
| D3 | A page form (`edit_page`, `read_page`, `delete_page`) checked on a post of a type that does not map meta capabilities and does not name that form requires the empty name, which no role holds; WordPress requires an undefined property (null). The decision is the same; only the name a `MetaCapabilityFilter` sees differs. A type that names the form requires that name. |
| D4 | File editing and file modifications count as allowed and unfiltered uploads are always refused (WordPress's defaults); WordPress's `DISALLOW_FILE_EDIT`, `DISALLOW_FILE_MODS` and `ALLOW_UNFILTERED_UPLOADS` have no setting. |
| D5 | A username strict sanitizing empties, or whose nicename sanitizes to nothing, gives the nicename `user-<id>`; WordPress refuses to create the user. |
| D6 | Several users may have the same username (OIDC subjects tell them apart); their nicenames get suffixes. WordPress refuses an existing login (compared case-insensitively). |
| D7 | A username longer than 60 characters is accepted (its first 50 characters start the nicename); WordPress refuses the login. |
| D8 | The display name is stored as given (WordPress HTML-escapes `&`, `<` and `>` on save); without names it is the `preferred_username`, trimmed but otherwise as given (WordPress uses the sanitized login); later logins never change it. |
| D9 | No `user_level` meta is kept and numeric capabilities (`current_user_can(8)`) are not supported; the `level_N` capabilities are in the role table. |
| D10 | A site whose roles never changed follows the default table of the running QuarkPress build; WordPress stores a copy of the defaults when the site is created. |
| D11 | Role and membership changes check capabilities (`manage_options`; `promote_user`, `remove_user`; `manage_network_options` for network administrator grants and revocations); WordPress's role, user and super-admin functions check nothing (its screens do). |
| D12 | Network administrators are kept by user id; WordPress lists logins in the network option `site_admins` (so renaming a login drops the status), treats a missing list as `admin`, and supports a `$super_admins` global. |
| D13 | `quarkpress.security.initial-super-admins` on a single site gives the main site's administrator role; WordPress has no super-admin list on a single site. |
| D14 | A user removed from their primary site gets their first other site by id that is not archived, spam or deleted as primary site; WordPress takes the first site of its list. |
| D15 | Background work can run as a system identity with every capability except `unfiltered_html`; WordPress's cron runs as nobody and saves through functions that check nothing. |
| D16 | The identity's roles are the user's roles on the request's site when it authenticated; capability checks follow the binding in effect, as WordPress's `switch_to_blog` does. |
| D17 | Work in a `SiteScope` for another site runs without the caller's identity; WordPress's `switch_to_blog` keeps the current user. |
| D18 | `user_can` for a missing user or id 0 while the current user is a network administrator is false; WordPress answers true there (its `is_super_admin` treats 0 as the current user). |
| D19 | Changing a role's capabilities or a membership through QuarkPress is visible to the next check at once; WordPress's registry-level `add_cap`/`remove_cap` do not update a role object already built for the request. |
| D20 | In a network, the network capabilities (`manage_network`, `manage_sites`, `manage_network_users`, `manage_network_plugins`, `manage_network_themes`, `manage_network_options`, `upgrade_network`, `setup_network`, `create_sites`, `delete_sites`) are held only by network administrators and the system identity; a grant stored on a role or user is ignored, so it unlocks nothing (for example `edit_users` for a site administrator given `manage_network_users`). WordPress honours such grants. On a single site a stored grant is honoured. |
| D21 | Given and family names are trimmed before the test that treats an empty name or `0` as absent; WordPress does not trim there, so a whitespace-padded `0` differs. |
| D22 | `Memberships.remove` of a user who is not a member returns false and fires no event; WordPress returns true and fires its hook. |
| D23 | On a single site, adding a user to the site gives them a primary site; WordPress has none there. |
| D24 | What a membership or role-table change gives is checked: a caller who is not the system or a network administrator may give only roles and capabilities they hold, and never a network capability (on a single site, a user who can delete users may grant any name but a network one); D11 covers the checks of the change itself. WordPress's functions check nothing. |

The parity tests (`-Pwp-parity`) compare the user-name rules (`sanitize_user`, `remove_accents`, the nicename),
`map_meta_cap` on a matrix of capabilities, objects, users and both install types, the post-type capability names
and the role table with WordPress 7.1.3's functions (Tier A), and run user, role, membership, super-admin and
capability scenarios against a full WordPress 7.1.3 multisite in a MySQL container (Tier B, `testing/wp-scenario`).
Their skip lists name the items above.

## Design decisions

- One role table for both install types; the network restrictions apply when a capability is checked.
- Capability data is read for an explicit site, so a check works from any binding and any thread; nothing is cached.
- Multisite-specific rules live here and follow the build-time install type; the multisite module knows nothing of
  security and asks core's `CapabilityCheck`.
- Network capabilities are never satisfied by stored grants in a network (D20): a site administrator cannot grant
  themselves network rights, whatever the write checks let through.
- Membership and role-table changes run in a transaction bound to the target site, with the row locked; network
  administrator writes serialize on the network lock.
- Profiles are created with an insert that cannot fail on a unique key, so concurrent first logins never fail.
