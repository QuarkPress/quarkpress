# QuarkPress Revisions

WordPress revisions for QuarkPress: a revision saved after every update-like save of a post with WordPress's change
check and limits, one autosave per user and post with the rules of WordPress's REST autosaves endpoint, restoring,
deleting and reading revisions with WordPress's capability rules, and a per-site switch. It requires the posts module
and works on a single site and in a network.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-revisions</artifactId>
</dependency>
```

The extension brings `quarkpress-posts` (and through it persistence, security, lib-wp-kses and lib-wp-blocks). Public
API lives in `org.quarkpress.revisions`; `org.quarkpress.revisions.runtime` is implementation and may change.

## Using it

Revisions are saved by the module itself: an observer of the posts module's saves, inside the saving transaction, writes
one after every update, trash, untrash and publish of a post whose revisioned fields or meta changed since its latest
regular revision (never after an insert: the first revision holds the state after the first update, as in WordPress).
Inject `Revisions` for everything else. It works on the site the request is bound to; another site is reached through
the persistence module's `SiteScope`, whose work runs without the caller's identity.

```java
List<RevisionSnapshot> history = revisions.ofPost(post.id(), RevisionQuery.all());
AutosaveResult result = revisions.autosave(post.id(), AutosaveChanges.builder().content(draftText).build());
PostSnapshot restored = revisions.restore(history.get(1).id(), Set.of());
```

- `Revisions`: `save` (a revision now, as a save would), `autosave`, `restore`, `delete`, `find`, `ofPost`,
  `autosaveOf`, `autosavesOf`, `latestAndCount`.
- `RevisionSnapshot` holds a revision as stored: its post, author (none for the system identity), title, content,
  excerpt, revisioned meta (key to values), whether it is an autosave, and its time: the post's modification time for a
  regular revision, the last write for an autosave.
- `RevisionQuery` reads as WordPress's `wp_get_post_revisions` lists: newest first (by time, then id), or ascending;
  a limit and an offset; one author's; autosaves included, only, or excluded; and whether a post whose limit is 0
  lists nothing (the default).
- `AutosaveChanges` gives a title, content or excerpt (a field not given is not compared, stored empty on a new
  autosave and kept on an updated one) and revisioned meta values by key.
- Invalid input throws `RevisionException` (an `IllegalArgumentException`) whose `code()` is WordPress's error code:
  `revisions_not_enabled`, `autosave_not_supported`, `rest_post_invalid_id` (no such revision on this site). A
  missing post, or one of another site, throws the posts module's `PostException` `invalid_post`. A meta key that is
  not revisioned for the post's type is an `IllegalArgumentException`.
- A refused capability check throws Quarkus's `UnauthorizedException` when nobody is authenticated and
  `ForbiddenException` otherwise.
- `RevisionException.autosaveNotSupported(type)` names the post's type in its message.
- **Transactions.** A capability refusal and an input refusal raised before the first write leave the caller's
  transaction usable. Once a call has written, any failure marks the transaction for rollback, so a failed call changes
  nothing. A refused lock (the posts module's site lock, see its README) also marks it. A restore refused by the
  update it makes (`empty_content`) marks it too.

### Capabilities

Checks follow WordPress's REST API, and its revisions screen for restoring:

| Call | Needs |
|---|---|
| `find`, `ofPost`, `autosaveOf`, `autosavesOf`, `latestAndCount` | `edit_post` on the revision's post |
| `autosave` | `edit_post` on the post; the post's type must support `autosave` |
| `save` | `edit_post` on the post (WordPress's function checks nothing) |
| `restore` | `edit_post` on the post, and the post's revisions enabled unless the revision is an autosave |
| `delete` | nobody but the system identity: deleting a revision maps to `do_not_allow` for every user, as in WordPress |

Reads need `edit_post` on the parent post for every caller; the system identity passes. The security module answers
`edit_post` and `read_post` on a revision as on its post, and `delete_post` on one as `do_not_allow`; this module asks
it with the post's facts.

### Saving

- A revision is saved after an update-like save of a post (update, trash, untrash, `Posts.publish`), inside that
  save's transaction before application observers run, unless: the post is an auto-draft; its type does not support
  `revisions`; its limit is 0 (see Configuration); the module itself is autosaving the post; or nothing changed since
  the post's latest regular revision — the revisioned fields compared after whitespace normalization (trimmed, every
  carriage return a line feed, runs of line feeds and of spaces or tabs collapsed; other spaces untouched) and the
  revisioned meta compared as lists of values. A post without a regular revision (none, or autosaves only) is always
  revisioned. Changes to other fields alone (status, slug, author, dates, parent, menu order, other meta) save none.
- The revision copies the post's revisioned fields, filtered for the caller as a save filters them (the saver just
  filtered the post the same way; `Posts.publish` by the system, or `save` by another caller, can copy less than the
  post holds, as WordPress's cron does), and the post's revisioned meta as the save left it. Its author is the caller
  (none for the system); its time the post's modification time of that save.
- Then the oldest revisions beyond the post's limit are removed: candidates are the post's revisions oldest first,
  the new one included; autosaves count but are skipped without replacement, so fewer regular revisions than the
  limit can remain. Trimming only happens when a revision is saved.
- Meta written after a save (through `PostMeta`) is not in that save's revision: the next save sees it as a change,
  or `save` copies it now.

### Autosaves

`autosave` follows WordPress's REST autosaves endpoint. A draft or auto-draft of the caller's own is updated with the
given fields through the full save path (filtering, a floating date re-stamped, the post's events), saves no revision,
ignores the given meta, and is the result (`AutosaveResult.Draft`). Any other post gets the caller's autosave
(`AutosaveResult.Stored`): created without comparison when the caller has none (fields not given stored empty, the
post's revisioned meta copied, then the given keys), updated when a given field differs from the post after whitespace
normalization or, when meta is given, a revisioned key's first value differs from the given one (a key not given
compares as the empty text; a key the post does not store always counts as different, as WordPress reads null there
and compares strictly; an empty meta map means no meta check at all, as in WordPress) — re-dated now, fields not given
kept, given keys replaced —, or returned untouched when nothing differs. The limit, the site feature and the type's
`revisions` support play no part; the type must support `autosave` (every type with an editor). One autosave per user
and post is kept by the lookup and by the table's unique index; the system identity's autosave belongs to nobody.

### Restoring

`restore` writes the revision's fields (an empty set means every revisioned field; the full set comes from the
parent's policy, where WordPress reads the revision's own fields) to the post through an ordinary update as the
caller, which filters the text for the caller, dates a floating draft now, keeps the status, slug, author and dates,
fires the post's events and saves a revision of the result unless it equals the latest regular revision (restoring the
newest saves none). Then `RevisionRestored` fires, then the revision's revisioned meta replaces the post's (a key the
revision lacks ends empty), so the revision the restore just saved still carries the post's previous meta, as in
WordPress; the next save sees the meta change. Autosaves can be restored.

### Events

Events are `SiteEvent`s, fired synchronously inside the transaction that caused them and again to `@ObservesAsync`
observers once it has committed:

| Event | WordPress | When |
|---|---|---|
| `RevisionCreated` | `_wp_put_post_revision` | a revision or a new autosave was written (not an autosave update) |
| `AutosaveSaved` | `wp_creating_autosave` | an autosave was created (`updated` false) or updated; not when an identical autosave is returned |
| `RevisionRestored` | `wp_restore_post_revision` | after the restoring update and its revision, before the meta restore |
| `RevisionDeleted` | `wp_delete_post_revision` | a revision was removed: by trimming, by `delete`, or one by one before its post is deleted |

The posts module's events do not fire for revision rows (D3). The module observes `PostSaved` at `LIBRARY_BEFORE`, and
CDI calls observers with lower priority values first: an application observer at a higher value runs after the
revision is written, one at a lower value runs before it.

### Filters

Beans run in a chain, higher `@Priority` first, the opposite of WordPress:

- `RevisionsToKeepFilter` (`wp_revisions_to_keep`): the post's limit, after the configuration and the type's support,
  before the site feature. One filter sees every post; a rule per type reads `post.type()`.
- `RevisionFieldsFilter` (`_wp_post_revision_fields`): the revisioned fields among `RevisionField.TITLE`, `CONTENT`,
  `EXCERPT`; a filter may leave fields out. It runs for the save, the change check, the autosave comparison and the
  restore.
- `RevisionMetaKeysFilter` (`wp_post_revision_meta_keys`): the revisioned meta keys by type; `footnotes` for every type
  supporting an editor, custom fields and revisions.
- `RevisionChangeFilter` (`wp_save_post_revision_post_has_changed`): the change decision, after the built-in field and
  meta comparison, with the latest regular revision; it runs only when one exists.

### Configuration

- `quarkpress.revisions.keep` (default `-1`): WordPress's `WP_POST_REVISIONS`, every post's starting point: negative
  keeps every revision, 0 saves none, a positive number keeps that many.
- The per-site feature `revisions` (on by default; `FeatureToggles`, the `qp_features` option): off, every post of the
  site has a limit of 0, with exactly WordPress's keep = 0 behaviour: no new revisions, `ofPost` lists nothing (unless
  the query turns the check off), `latestAndCount` is `revisions_not_enabled`, `find` by id still works, restores are
  refused unless the revision is an autosave, and autosaves are still created, listed and restorable.

## Storage

`qp_revision` holds every revision of every site in a table of its own (WordPress keeps them as rows of its posts
table): the post (a composite key of the post's site, cascading the post's delete), the author (set to null when the
profile is deleted), the three texts, the revisioned meta as JSON (key to array of values), the autosave flag and the
time; `autosave_owner`, a plain nullable column the entity derives in a lifecycle callback (the author for an
autosave, null otherwise) with its own key that is set to null when the profile is deleted (MySQL forbids a set-null
key on the base of a generated column). The unique index on the post and `autosave_owner` ignores null owners: it
limits nothing but autosaves with an author, the system identity writes to the newest authorless autosave, and a
deleted profile leaves authorless rows. The module's migration is the `V6_*` band.

### Locks

Revision writes take the posts module's per-site lock after their capability checks. Lock order, as taken: the site's
post lock, then the parent post row, then revision rows (`delete` and `restore` lock the post row before the revision
row; deleting a post locks its revision rows before the posts module goes on to lock the post's children), then
whatever the posts module locks next, option rows last. The site's post lock serialises every content write of a site,
so revision and post writes of one site apply one after the other and the order among revision rows (ids ascending
where several are locked, though trimming during a restore may lock lower ids after a higher one) cannot deadlock.

## Limits

- Revisions are not shown, diffed or previewed (the admin screens, previews and the REST controllers come later).
- No editor lock: an autosave of one's own draft always updates the draft.
- WordPress hooks without counterpart: `wp_{type}_revisions_to_keep`, `wp_save_post_revision_check_for_changes`,
  `wp_save_post_revision_revisions_before_deletion`, the preview filters, the revision UI filters, `heartbeat_received`,
  `wp_check_post_lock_window`.

## Decisions

- Capabilities follow WordPress's REST layer, and its revisions screen for restoring; data rules follow its API.
- A revision is a side effect of the save, written by an observer inside the save's transaction, as the taxonomy
  module gives posts their default terms.
- Autosaves follow the REST autosaves endpoint, not the classic editor's heartbeat.
- The site feature applies after the filters: a filter cannot re-enable a site the operator switched off.
- A restore writes no `_edit_last` and a revision's time keeps its first value, as in WordPress (listed for the
  screens).
- Revision and post writes of one site share the posts module's site lock; a failed write changes nothing.

## Differences from WordPress

| Label | Difference |
|---|---|
| D1 | Capabilities are checked as WordPress's REST API checks them: every read needs `edit_post` on the post (WordPress's functions check nothing); `delete` is refused for every user, the system identity alone may (WordPress's function checks nothing and its REST endpoint refuses everyone); `save` needs `edit_post` on the post; `restore` applies the revisions screen's checks (`edit_post`, enabled or an autosave) without an editor lock. |
| D2 | Revisions live in their own table and id space, so a revision has no slug, guid, local date, modification date, comment or ping status or menu order: one time, the post's modification time (an autosave's last write). WordPress's `?p=` links and `{id}-revision-v1` names have no counterpart. |
| D3 | The posts module's events (`PostSaved`, `PostCreated`, `PostStatusChanged` and the rest) never fire for revision rows; WordPress fires `save_post`, `transition_post_status` and `wp_insert_post` for them. |
| D4 | Revisioned meta is a JSON column of the revision, compared and restored as JSON values: no meta write is redirected from a revision to its post (WordPress's `update_post_meta` on a revision id writes the parent). Autosave meta is given as single values and compared by value. |
| D5 | The system identity's revisions have no author (WordPress's user 0); a deleted profile leaves its revisions without author (WordPress deletes or reassigns a user's revisions with the user). |
| D6 | One autosave per user and post is also a unique index (WordPress keeps one by lookup; concurrent requests can leave two); the system identity's autosave belongs to nobody and is looked up as such, where WordPress's user 0 would overwrite the latest autosave of any user. |
| D7 | `autosave` is the REST path: an autosave identical to the post is returned untouched (the classic editor deletes it); only a title, content, excerpt and revisioned meta are accepted (the REST request could also publish a draft or change its author, slug and dates); the given meta is ignored on one's own draft, as there. |
| D8 | No editor lock: an own draft is always updated, whoever else is editing it. |
| D9 | (not a difference: a restore writes no `_edit_last` in WordPress either; see Decisions) |
| D10 | One `RevisionsToKeepFilter` sees every post (no per-type filter); there is no filter to skip the change check or to change the trimming candidates; `RevisionChangeFilter` receives the latest regular revision, the built-in meta comparison having run. |
| D11 | The revisioned fields are a fixed set (title, content, excerpt) that filters only narrow; a restore cannot name other columns (WordPress's filter can add any column, and an explicit restore list can name `post_name` or `post_author`). |
| D12 | Meta written after a save is not in that save's revision (WordPress's REST controller writes meta before the revision is saved); the next save, or `save`, carries it. |
| D13 | Deleting a revision never touches its post's trash records; WordPress's redirect deletes the parent's `_wp_trash_meta_status` and `_wp_trash_meta_time` with a trashed post's revision. |
| D14 | The site feature `revisions` is a per-site switch WordPress does not have (its constant is one for the installation); it applies after the filters and behaves as keep = 0. |
| D15 | A missing post is `invalid_post` and a missing revision on `restore` is `rest_post_invalid_id` (the REST answers), where WordPress's functions return null or false; `find` and `delete` answer empty as the functions do. |
| D16 | `AutosaveChanges` refuses a meta key that is not revisioned for the type; WordPress's REST request validates registered meta and ignores the rest. |
| D17 | Trimming, the revision and the save are one transaction: a failure after the first write rolls everything back; WordPress keeps what it wrote before a failure. |
| D18 | Revision times are kept to the second and ties are broken by id in the query's direction; WordPress leaves ties to the database (with an id tie-break only in its `date ID` order). |
| D19 | The posts module refuses the type `revision` (`invalid_post_type`); WordPress refuses only a revision of a revision. |
| D20 | WordPress sanitizes the `footnotes` meta for users without `unfiltered_html` (a value that is not a JSON array of `{id, content}` objects becomes `[]`, valid values are re-encoded); QuarkPress stores the value it is given. Tier B scenarios use canonical values. |
| D21 | A post's revisions are deleted when `PostDeleting` fires, before the posts module re-parents the post's children; WordPress deletes them after re-parenting, so a `RevisionDeleted` observer may see children not yet re-parented. |

The parity tests (`-Pwp-parity`) compare the limit, the revisioned fields, a revision's row data, whitespace
normalization and the change check with WordPress 7.1.3's functions (Tier A, `testing/wp-oracle`; 1434 comparisons, at
least 700 required), and run revision scenarios against a full WordPress 7.1.3 multisite in a MySQL container (Tier B,
`testing/wp-scenario`; 9 hand-written and 40 generated scenarios; the 9 hand-written ones make 1152 comparisons, at
least 550 required, and the generated
ones add to them). Their skip lists name the items above: Tier A cites D11, Tier B D13 and, for the posts
module's records, the posts README's D5.
