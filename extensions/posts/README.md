# QuarkPress Posts

WordPress posts for QuarkPress: post types and statuses, saving with WordPress's rules (slugs, dates, sanitizing,
hierarchy, trash, sticky posts), post meta and queries. It requires the persistence and security modules and works on
a single site and in a network.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-posts</artifactId>
</dependency>
```

The extension brings `quarkpress-persistence`, `quarkpress-security`, `quarkpress-lib-wp-kses` and
`quarkpress-lib-wp-blocks`. Public API lives in `org.quarkpress.posts`; `org.quarkpress.posts.runtime` is
implementation and may change.

## Using it

Inject `Posts` and `PostMeta`. They work on the site the request is bound to. Another site is reached through the
persistence module's `SiteScope`, whose work runs without the caller's identity: check the caller first, then run the
work as `SystemIdentity` inside the scope.

```java
PostSnapshot post = posts.create(PostChanges.builder().title("Hello").status("publish").build());
posts.update(post.id(), PostChanges.builder().title("Hello again").expectedVersion(post.version()).build());
PostPage page = posts.query(PostQuery.builder().search("hello").perPage(5).build());
postMeta.add(post.id(), "rating", IntNode.valueOf(5), true);
```

- `Posts`: `create`, `update`, `publish`, `trash`, `untrash`, `delete`, `stick`, `unstick`, `stickyPosts`, `find`,
  `ancestors`, `uri` and `query`. `PostMeta`: `get`, `all`, `add`, `update` and `delete` (a key, or the values of a
  key equal to a value).
- `PostChanges` holds the fields of a write; a field not given takes WordPress's default on `create` and keeps the
  stored value on `update`. `author` and `parent` use 0 for none. `date` is the local date in the site's zone,
  `dateGmt` the GMT date; `editDate` lets an update date a draft whose date floats (WordPress's `edit_date`).
- With `expectedVersion`, an update of a post that changed since that version throws
  `jakarta.persistence.OptimisticLockException`. A post's version changes with every write of its row, meta writes
  included, except sticking and unsticking and the parent change a deleted parent hands down.
- Invalid input throws `PostException` (an `IllegalArgumentException`) whose `code()` is WordPress's error code:
  `invalid_post` (no such post on this site), `invalid_author`, `invalid_parent`, `empty_content`, `invalid_date`,
  `invalid_post_type` (the type `revision`),
  `db_insert_error` or `db_update_error` (a value too long for its column), and `rest_invalid_field` (sticking a post
  that is not of type `post` or has a password, or giving a password to a sticky post). The messages are not
  guaranteed to be WordPress's.
- A refused capability check throws Quarkus's `UnauthorizedException` when nobody is authenticated and
  `ForbiddenException` otherwise.
- **Transactions.** `Posts` and `PostMeta` check capabilities and input before they write. A capability refusal, an
  input refusal raised before the first write and a stale version leave the caller's transaction usable. Once a call
  has written, any failure marks the transaction for rollback before it propagates, so a failed call changes nothing.
  A refused lock (see [Locks](#locks)) also marks it.

### Capabilities

Checks follow WordPress's REST API (wp-admin's for restoring from the trash):

| Call | Needs |
|---|---|
| `create` | the type's `create_posts`; a status of `publish`, `future` or `private` also needs `publish_posts`; an author other than the caller (none included) needs `edit_others_posts` |
| `update` | `edit_post` on the stored post, then the status and author rules of `create` with the capabilities of the type the post will have; changing the type also needs the new type's `create_posts`, and its `publish_posts` and `edit_others_posts` rules apply to the status and author the post will have, given or stored |
| `publish` | `publish_post` |
| `trash`, `untrash`, `delete` | `delete_post` |
| `find`, `ancestors`, `uri`, meta reads | nothing for a post whose effective status is public; `read_post` otherwise |
| meta writes | `edit_post`; a key whose first printable-ASCII character is `_` is protected and written only by the system identity |
| `stick`, `unstick` | `edit_post` on the post, then the `post` type's `edit_others_posts` and `publish_posts` |
| `query` | statuses other than `publish` need every queried type's `edit_posts`; what each caller then sees is described on `PostQuery` |

A type that is not registered is checked with the `post` type's capability names. Every save sanitizes the title,
content and excerpt with kses unless the caller holds `unfiltered_html`, and strips custom CSS from blocks unless it
holds `edit_css`; the system identity does not hold `unfiltered_html`.

### Saving

A save follows `wp_insert_post` and `wp_update_post`:

- **Fields.** Type and status are sanitized as keys (an empty type is `post`, an empty status `draft`; an attachment
  keeps only `inherit`, `private`, `trash` and `auto-draft`). A save whose title, content and excerpt are all empty is
  refused for a type that supports the three. A private post keeps no password. Comment and ping status default to
  the site's `default_comment_status` and `default_ping_status` options, and to `closed` for pages and types without
  comments or trackbacks.
- **Slugs.** A given slug, else the title, goes through the `SlugSanitizer`; draft, pending and auto-draft posts get
  no generated slug and no uniqueness check. Other slugs are made unique as WordPress does (per type, per parent for
  hierarchical types, against every post for attachments, never a feed name or `embed`, never an all-digit or
  `page<digits>` slug for a hierarchical type, never a number the site's permalink structure reads as a date for a
  `post`), with `-2`, `-3`, … within 200 bytes. A post saved outside the trash takes its slug from trashed posts of
  the searchable types that hold it: they get the `__trashed` suffix and keep their slug in `_wp_desired_post_slug`.
  A published post of a flat type records its previous slug and day in `_wp_old_slug` and `_wp_old_date`.
- **Dates.** A post date is stored once, as the GMT instant, to the second; while a draft, pending or auto-draft post
  has no GMT date its date floats and its local date is its modification time. Local dates convert with the site's
  zone: a time in a daylight-saving gap moves forward by the gap, and an ambiguous time takes the offset in force at
  the UTC instant with the same digits (its first occurrence west of UTC, its second east of it), as PHP does. A
  published post dated 60 seconds or more ahead becomes `future`; a scheduled post dated less than that becomes
  `publish` (attachments never switch). A floating post is dated now on update unless `editDate` is set.
- **Hierarchy.** A parent must be a post of the same site. On every save of every type the parent goes through
  WordPress's loop check: a post that is its own parent, or whose parent's chain leads back to it, gets none; a loop
  further up the chain is cut by giving each of its posts no parent, in WordPress's order.
- **Guid.** Set once on insert: the plain permalink on the site's `http` home.

`publish` changes only the status, as WordPress's scheduled publishing (`wp_publish_post`) does; it returns false for a
published post. `publishIfDue` is the scheduled-publishing handler itself (`check_and_publish_future_post`): under the
site's post lock it publishes a `future` post whose GMT date is now or past, to the second, and does nothing for any
other post; the scheduling module calls it as the system identity. `trash` records the previous status and time in
`_wp_trash_meta_status` and `_wp_trash_meta_time`, stops a page being the front page or posts page, unsticks the post,
and saves it as `trash` with the `__trashed` suffix (its slug kept in `_wp_desired_post_slug`); its children keep it as
parent. `untrash` restores a post as a draft (an attachment as `inherit`) with its slug back. `delete` without `force`
trashes a post or page that is not in the trash yet; everything else is deleted for good. Deleting hands the post's
parent down to its same-type children (hierarchical types) and its attachments (a deleted attachment hands nothing
down), clears the parent of its other children, stops a page being the front page, posts page or privacy policy page,
and removes the row with its meta. `trash`, `untrash` and `delete` return the post as it was before; `trash` and
`untrash` return empty when there was nothing to do or the save was refused before it wrote.

### Post types and statuses

The built-in types are `post`, `page`, `attachment` and `wp_block`; the built-in statuses are WordPress's twelve
(`publish`, `future`, `draft`, `pending`, `private`, `trash`, `auto-draft`, `inherit` and the four `request-*`
statuses). Register a type with `@PostType` on any application class, or from an extension with core's
`PostTypeBuildItem`, which takes every argument; register a status with `PostStatusBuildItem`. Arguments not given take
WordPress's defaults. A key registered twice, the built-in ones included, fails the build.

```java
@PostType(key = "book", publicly = true, capabilityType = "book", mapMetaCap = true)
public class Books {}
```

`PostTypes` and `PostStatuses` answer what is registered. For every registered type that maps meta capabilities and
is not built in, the module tells the security module its meta capability names, so `edit_book`, `read_book` and
`delete_book` map as `edit_post`, `read_post` and `delete_post` do.

### Queries

`PostQuery` reads its arguments as `WP_Query` does: types (`any` alone means the searchable types), statuses (`any`
included), authors, parents, ids, slug, exact title, menu order, search, date conditions (`DateCondition` with
`DateBound`s on `DateColumn`s), ordering (`OrderKey`, then the id in the first key's direction), paging with the
site's `posts_per_page` option as default, and sticky posts first (`stickyFirst`, off by default as in `get_posts`).
Without statuses a query shows published posts and, to a logged-in user, the private posts it may read; the system
identity counts as logged out. A query by id or slug is never paged and checks each post it finds as WordPress checks a
single post. `PostPage` holds the posts, the number of matching posts and the number of pages. The Javadoc of
`PostQuery`, `OrderKey`, `DateCondition` and `DateBound` gives the full rules.

### Events

Events are `SiteEvent`s, fired synchronously inside the transaction that caused them (an observer that throws marks
it for rollback, whatever the exception type) and again to `@ObservesAsync` observers once it has committed:

| Event | WordPress | When |
|---|---|---|
| `PostStatusChanged` | `transition_post_status` | every save, also when the status stays the same (`from` is `new` for a created post) |
| `PostPublished` | `publish_{type}` | every save of a registered type whose resulting status is `publish` |
| `PostScheduled` | `future_{type}` | every save of a registered type whose resulting status is `future` |
| `PostUpdated` | `post_updated` | an update, with the post before and after |
| `PostSaved` | `save_post` | every save |
| `PostCreated` | (none) | a created post, after `PostSaved` |
| `StickyChanged` | `post_stuck`, `post_unstuck` | a change of stickiness, also when trashing or deleting a sticky post |
| `PostTrashed` | `trashed_post` | a post moved to the trash, attachments included |
| `PostRestored` | `untrashed_post` | a post left the trash, with the status recorded when it was trashed |
| `PostDeleting` | `before_delete_post`, `delete_attachment` | before a delete changes anything; observers clean up what they keep about the post |
| `PostDeleted` | `deleted_post`, `deleted_post_{type}` | after the delete, with the post's id and type |

Saves of attachments by `create` and `update` fire none of the save events (WordPress announces attachments with
events of their own, which come with the media module); `publish` fires `PostStatusChanged`, `PostPublished` and
`PostSaved` for every type, attachments included, and never `PostUpdated`.

The taxonomy module observes `PostStatusChanged` and `PostDeleting` inside the saving transaction: it gives saved posts
their default terms, keeps term counts, and removes a deleted post's assignments.

The revisions module observes `PostSaved` (except for a new post) and `PostDeleting` inside the saving transaction: it
saves a revision after every update-like save, and removes a deleted post's revisions one by one.

### Filters and replaceable beans

Filters are beans; a higher `@Priority` runs first, the opposite of WordPress. A filter returns a value (changed or
not), never null; one that throws stops the call.

- `PostWriteFilter` (`wp_insert_post_data`): the `PostDraft` just before it is written. A parent or author it changes
  is checked again, and values too long for their column are refused after the filters.
- `PostSlugFilter` (the `wp_unique_post_slug` filter): the unique slug; not called for draft, pending and auto-draft
  posts.
- `PostQueryFilter` (`pre_get_posts`): a query before it runs.
- `SlugSanitizer` (WordPress's `sanitize_title` in its save context, with the `en_US` accent rules) and
  `ContentSanitizer` (the title, content and excerpt save filters with lib-wp-kses) are replaceable beans.

### Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.posts.empty-trash-days` | `30` | WordPress's `EMPTY_TRASH_DAYS`. `0` turns the trash off: `trash` deletes at once, and `delete` no longer trashes first. |

The module also reads the site options WordPress's post code reads: `default_comment_status`, `default_ping_status`,
`posts_per_page`, `permalink_structure`, `show_on_front`, `page_on_front`, `page_for_posts` and
`wp_page_for_privacy_policy`, as well as the site's time zone (from `timezone_string` and `gmt_offset`, see the core
README) and the site's address, which guids are built on.

## Storage

`qp_post` holds every post of every site (ids are unique across the network), with its meta as a JSON object of key
to list of values, and its stickiness as a column. The parent reference is the composite key `(site_id, parent_id)`,
so a post can only have a parent of its own site. `qp_post_lock` holds one row per site, which post writes lock (see
[Locks](#locks)). The module's migrations are the `V4_*` band.

### Locks

Every write of a site's posts or post meta takes the site's post lock, after its capability checks, and holds it until
its transaction ends, so post writes of one site apply one after the other. A write to a stored post reads and checks
it without locks, then takes the locks, reads it again under its row lock and checks it again. Locks are taken in this
order: the network, a user's profile, a membership, the site's post lock, post rows, and option rows last. A create,
and an update that gives an author, share-locks the author's profile before the site's post lock, so the profile
cannot be deleted meanwhile. A write inside a `SiteScope` that needs a lock already held by the transaction that
opened the scope, and is waiting for it, is refused at once with `IllegalStateException` instead of waiting forever.

## Limits

- Slugs are at most 200 bytes, type, status, comment status and ping status names at most 20 characters, passwords
  255 characters and guids 255 characters (a longer guid is cut). A search string longer than 1,600 bytes is ignored,
  as WordPress ignores it.
- Write a new site's posts only after the transaction that creates the site has committed: the post lock's row checks
  the site's row through its foreign key, so a site scope spawned before then fails on PostgreSQL and waits on MySQL
  until the lock-wait timeout.
- On MySQL, InnoDB stops cascading deletes at 15 levels, so a site whose post hierarchy (a chain of parents) is
  about that deep cannot be deleted in one statement. Delete its deepest posts first.
- Slugs compare exactly, byte for byte. WordPress compares them with its MySQL collation, case-insensitively, which
  can matter only for a slug with capitals that a `PostSlugFilter` or `PostWriteFilter` writes.
- What an attachment inherits from its parent for capability checks is followed through at most 32 parents.
- Date bounds whose fields name no real time (month 13, hour 24, 31 February) split dates where their
  `Y-m-d H:i:s` text sorts, by design; how WordPress's MySQL compares such text was not checked.
- WordPress hooks with no counterpart here: the filters `pre_trash_post`, `pre_untrash_post`,
  `wp_untrash_post_status`, `add_trashed_suffix_to_trashed_posts`, `pre_delete_post` and `pre_delete_attachment`, and
  the actions `delete_post`, `delete_post_{type}` and `after_delete_post`. Only WordPress's default behaviour is
  implemented: a restored post is a draft, and a post saved outside the trash always takes its slug from trashed posts.

## Decisions

- Writes of one site are serialized by the site's post lock, so two concurrent saves of one title get distinct slugs.
  The lock order is network, user profile, membership, site post lock, post rows, then option rows last: the reading
  settings a trash or delete resets are written after the post rows. Capability checks run before any lock, so a
  caller who may not write never makes others wait.
- Dates are kept to the second as one instant: the GMT post date (absent while a draft's date floats) and the
  modification date. Local dates are derived with the site's zone, resolving gaps and overlaps as PHP does.
- A parent must be a post of the same site, enforced by a composite foreign key. Deleting a post hands its parent down
  to its same-type children of a hierarchical type and to its attachments, as WordPress does (a deleted attachment
  hands nothing down), and clears the parent of its other children; only the parent changes, not their version or
  modification date.
- Every save sanitizes every text field again with the caller's settings, as WordPress does.
- Stickiness is a column outside the optimistic version, so sticking never makes another caller's update stale.
- A failed write changes nothing: every write runs in one transaction, which a failure after the first write marks
  for rollback.
- Queries break ties by id, so equal values come back in a stable order.

## Differences from WordPress

| Label | Difference |
|---|---|
| D1 | Capabilities are checked as WordPress's REST API checks them (wp-admin's `delete_post` for restoring from the trash); WordPress's `wp_insert_post` and friends check nothing. Changing a post's type, which REST cannot do, also needs the new type's `create_posts`, with its publish and edit-others rules applied to the status and author the post will have. |
| D2 | A parent must be a post of the same site, and deleting a post clears the parent of children it does not hand its parent down to; WordPress accepts any id and leaves those children pointing at the deleted post. The type `revision` is refused with `invalid_post_type` (revisions live in the revisions module's own table, which cascades a post's delete), where WordPress deletes a post's revision rows with it. |
| D3 | A post date is stored once: given both, the GMT date wins and the local date is derived from it; given only a local date, it is converted with the site's zone when saved. WordPress stores both texts as given. A consequence: updating a published post with only a later local date recomputes its GMT date and can make it `future`, where WordPress keeps the old GMT date and the post stays published. A second consequence: after the site's time zone changes, local dates are shown in the new zone, where WordPress keeps the local text it wrote under the old one. |
| D4 | A floating draft given only a local date with `editDate` stays floating; its local date shows its modification time. WordPress stores that local date with a zero GMT date. |
| D5 | A new post whose date floats (a draft, pending or auto-draft without a GMT date) has a real GMT modification date (its save time, or the local date it was given, in GMT); WordPress copies the zero GMT post date into the GMT modification date until the post is next updated. |
| D6 | Dates are typed (`LocalDateTime`/`Instant`), so a time such as `24:00:00`, `25:00:00` or a minute of 60 cannot be given, and years outside 1000–9999 are refused with `invalid_date`; WordPress checks only the calendar part and stores any text MySQL accepts. |
| D7 | Search, the exact-title condition and ordering by title ignore case and not accents, on both databases, and ordering by title, name or type follows code points on both (PostgreSQL compares those under `ucs_basic`, MySQL's columns are `utf8mb4_bin`); WordPress follows the database collation (on MySQL's defaults it ignores accents too). On PostgreSQL, case is ignored through `lower()`, which follows the database's locale: under the `C` locale only ASCII capitals are lowered. |
| D8 | Only type `post` can be sticky, never a password-protected post; `stickyPosts` lists ids in id order. WordPress's `stick_post` accepts any id and keeps insertion order. |
| D9 | A post type or status registered twice fails the build; WordPress replaces the earlier registration. Status names longer than 20 characters are refused when registered, as type keys are; WordPress fails only when a post is saved with one. |
| D10 | The built-in types are `post`, `page`, `attachment` and `wp_block`; WordPress's other built-in types come with the modules that use them. |
| D11 | The guid is always the plain permalink on the site's `http` home; WordPress uses the pretty permalink for posts published while pretty permalinks are on. |
| D12 | Post ids are unique across the network; WordPress numbers posts per site. |
| D13 | No pingback or enclosure markers (`_pingme`, `_encloseme`) are written, and nothing about comments happens yet (deleting a post deletes no comments). |
| D14 | `post_content_filtered`, `to_ping`, `pinged`, `post_mime_type`, `page_template`, `tax_input` (the taxonomy module assigns terms after the save) and `meta_input` are not stored; global styles are not filtered on save. |
| D15 | `PostMeta` checks `edit_post` for meta writes, as WordPress's REST API maps the post meta capabilities; protected keys (leading `_`) are written only by the system identity; meta values are JSON values, so numbers and booleans keep their types. The meta capability names themselves (`edit_post_meta`, `add_post_meta`, `delete_post_meta`) are not mapped for direct checks. |
| D16 | The trash is not emptied after `empty-trash-days`, old auto-drafts are not removed and scheduled posts are not published when due (`publish` does it when called): scheduled jobs come later. |
| D17 | Queries leave out calendar arguments other than date conditions, `rand`, `comment_count`, meta and taxonomy queries; ties are broken by id (WordPress leaves them in database order); when every ordering key is skipped the query orders newest first (WordPress orders nothing when the keys come as a list); statuses other than `publish` need `edit_posts`, and such a query by a caller other than the system identity leaves out the posts the caller may not read, with `found` counting only what it returns (`WP_Query` returns and counts them; the REST API drops them item by item but still counts them in `X-WP-Total`); without statuses the system identity counts as logged out. |
| D18 | Post writes of one site are serialized; WordPress lets concurrent saves race (two saves of one title can get the same slug). |
| D19 | An operation on a missing post, or a post of another site, throws `invalid_post` (as the REST API answers): `trash`, `untrash`, `delete`, `publish`, `stick`, `unstick`, `ancestors`, `uri` and the `PostMeta` calls. WordPress's functions return null (`wp_trash_post`, `wp_untrash_post`, `wp_delete_post`), false (`get_page_uri`) or an empty list (`get_post_ancestors`), do nothing (`wp_publish_post`, `unstick_post`) or add the id to the sticky list (`stick_post`); its meta functions read and write meta rows for the missing id. `find` returns empty. |
| D20 | Save-time sanitizing inherits the kses and blocks libraries' differences: block attributes that lib-wp-blocks re-serializes differently (its D10–D13, reaching kses through lib-wp-kses #7) and a block attribute holding `<` (lib-wp-kses #11). Tier A's save parity labels those inputs with the library differences. |
| D21 | A meta key is protected when its first printable-ASCII character is `_` after skipping every other leading character; WordPress strips bytes that are neither printable ASCII nor Latin-1 letters, so some keys with a leading non-ASCII character (`é_x`, U+200B then `_secret`) are protected here and not there. QuarkPress errs toward protecting. |
| D22 | Post meta values are matched exactly (stored JSON values) when updating or deleting by value; WordPress compares text with the database collation, case-insensitively on its default MySQL collation. `PostMeta` has no `prev_value` on update, and an empty key does not read every key (`all` does). |
| D23 | An author of `0` (no author) is accepted from a caller holding the type's `edit_others_posts`; WordPress's REST layer refuses it as an invalid author. |
| D24 | When breaking a parent loop re-parents another post and that change is refused (possible only with a loop made outside QuarkPress), the whole save fails and changes nothing; WordPress ignores the refused change and saves the post. |
| D25 | A trash that fails or is refused changes nothing; WordPress, which has no transaction, keeps the trash meta, the unstick and the reset reading settings it wrote before the failure. |
| D26 | Deleting an attachment does not remove other posts' `_thumbnail_id` meta that points to it (the media module adds it), and a sticky attachment (possible only after a type change) is unstuck with a `StickyChanged` event, which WordPress's attachment path does not send. |
| D27 | A single-post query whose post the caller may not see reports `found` 0; WordPress counts it (1) and returns no post. |
| D28 | In a list query that names statuses, an attachment whose parent is trashed is judged against the parent's `trash` status; WordPress's REST layer judges it by the status the parent had before it was trashed, so an author may miss other users' attachments under a trashed published post. |
| D29 | Ordering by relevance a search made only of excluded terms orders by date; WordPress builds an invalid SQL statement for it. |
| D30 | `PostDeleting` observers run before the delete resets the reading settings, the privacy policy page setting and the stickiness, and those options change after the row is gone (option rows come last in the lock order); in WordPress, `before_delete_post` observers registered after its own defaults run after those resets. |
| D31 | The fields of a date bound are typed: a year outside 0–9999 or another field outside 0–99 is refused with `IllegalArgumentException`; WordPress accepts such values (it makes a negative one positive). |
| D32 | `PostQueryFilter`s (`pre_get_posts`) do not run on the fetch of the sticky posts that `stickyFirst` adds; WordPress runs them on that inner query. |
| D33 | `stickyFirst` stays on for a query with only paging arguments while the site shows a static front page; WordPress answers that query with the static front page itself (and so with no sticky posts). `types` has no one-element list form: one type with an archive always counts as that type's archive (no sticky posts first), as WordPress's `post_type => 'book'` does, where `post_type => ['book']` keeps them. |
| D34 | The system identity holds every capability but `unfiltered_html`, the type's publish capability included, so a pending post it saves keeps its typed slug; WordPress's user 0 can publish nothing, so its pending posts lose the typed slug. |

The parity tests (`-Pwp-parity`) compare slugs, slug truncation, keys, post type and status registration, local and
GMT date conversions, the save filters of titles, content and excerpts, and the search tokenizer with WordPress
7.1.3's functions (Tier A, `testing/wp-oracle`), and run post scenarios against a full WordPress 7.1.3 multisite in a
MySQL container (Tier B, `testing/wp-scenario`). Their skip lists name the items above.
