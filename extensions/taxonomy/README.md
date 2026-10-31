# QuarkPress Taxonomy

WordPress taxonomies for QuarkPress: categories, tags and custom taxonomies, terms saved with WordPress's rules
(sanitizing, duplicate names, unique slugs, hierarchy), terms assigned to posts with their counts, every site's
default category, and term queries. It requires the posts module and works on a single site and in a network.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-taxonomy</artifactId>
</dependency>
```

The extension brings `quarkpress-posts` (and through it persistence, security, lib-wp-kses and lib-wp-blocks). Public
API lives in `org.quarkpress.taxonomy`; `org.quarkpress.taxonomy.runtime` is implementation and may change.

## Using it

Inject `Terms`. It works on the site the request is bound to; another site is reached through the persistence module's
`SiteScope`, whose work runs without the caller's identity.

```java
TermSnapshot news = terms.create("category", TermChanges.builder().name("News").build());
terms.setPostTerms(post.id(), "post_tag", List.of(TermRef.name("launch"), TermRef.id(tagId)), false);
List<TermSnapshot> popular = terms.query(TermQuery.builder().taxonomies("post_tag").orderBy(TermOrder.COUNT).order("DESC").number(10).build());
```

- `Terms`: `create`, `update`, `delete`, `setPostTerms`, `removePostTerms`, `postTerms`, `find` (with or without a
  taxonomy), `findBy` (`TermField.ID`, `SLUG`, `NAME`), `exists`, `ancestors`, `children`, `query` and `count`.
- `TermChanges` holds the fields of a write; a field not given takes WordPress's default on `create` and keeps the
  stored value on `update` (an empty slug given on `update` is generated again from the name). `parent` 0 is none.
- With `expectedVersion`, an update of a term that changed since that version throws
  `jakarta.persistence.OptimisticLockException`. A term's count is outside its version: posts published by others
  never make an update stale.
- Invalid input throws `TermException` (an `IllegalArgumentException`) whose `code()` is WordPress's error code:
  `invalid_taxonomy`, `invalid_term`, `empty_term_name`, `invalid_term_name`, `term_exists` (`existing()` names the
  term), `duplicate_term_slug`, `missing_parent`, `rest_taxonomy_not_hierarchical`, `db_insert_error` or
  `db_update_error`. Assigning to a missing post throws the posts module's `PostException` `invalid_post`. `delete`
  returns the term as it was, and empty, without any check, when the taxonomy is unknown or the term is missing.
- A refused capability check throws Quarkus's `UnauthorizedException` when nobody is authenticated and
  `ForbiddenException` otherwise.
- **Transactions.** A capability refusal, an input refusal raised before the first write and a stale version leave the
  caller's transaction usable. Once a call has written, any failure marks the transaction for rollback, so a failed
  call changes nothing. A refused lock (the posts module's site lock, see its README) also marks it.

### Capabilities

Checks follow WordPress's REST API:

| Call | Needs |
|---|---|
| `create` | a hierarchical taxonomy's `edit_terms`; a flat taxonomy's `assign_terms` (so contributors create tags) |
| `update` | `edit_term` on the term |
| `delete` | `delete_term` on the term; nobody holds it for the taxonomy's default term (the default category, or a custom default term), the system identity included |
| `setPostTerms`, `removePostTerms` | `edit_post` on the post, `assign_term` on every existing term named, and the create capability for a name that creates a term |
| reads and queries | nothing for a taxonomy that is publicly queryable or shown in REST (WordPress's REST routes serve its terms to anyone, logged out included); otherwise the taxonomy's `assign_terms`; `postTerms`, and `query` and `count` with `posts`, also need each post to exist on the site (`invalid_post`) and be readable |

The meta capabilities `edit_term`, `delete_term` and `assign_term` map as WordPress maps them (security module); this
module describes stored terms to it (`TermCapabilityFacts`).

`TermPermissions` asks the REST API's questions without calling a service; its checks read the database, answer false
instead of throwing, refuse a taxonomy that is not registered, and exist for the current identity and with an
explicit `SecurityIdentity`: `canCreate(taxonomy)` (a hierarchical taxonomy's `edit_terms`, a flat one's
`assign_terms`), `canEdit(term)` (`edit_term`, false for a missing term), `canDelete(term)` (`delete_term`, never for
the taxonomy's default term), `canEditTerms(taxonomy)`, `canAssign(taxonomy)`, and `postTermsAccess(post, taxonomy)`,
whether the post's terms in the taxonomy may be listed: `MISSING_POST` for no such post on the site, `DENIED` when the
post's type does not use the taxonomy or the post is neither publicly viewable (a viewable type and a viewable
status) nor readable (`read_post`), else `ALLOWED`.

### Saving

- **Names** go through WordPress's save filters for every caller alike: `sanitize_text_field`, kses with the tag set of
  term names, then `&`, `<` and `>` as entities (so "Rock & Roll" is stored as `Rock &amp; Roll`). A blank name is
  `empty_term_name`; a name the filters empty is `invalid_term_name` on create and `empty_term_name` on update.
  Descriptions go through kses with the tag set of term descriptions. A backslash is kept (QuarkPress takes plain
  text, as a correctly slashed WordPress call).
- **Duplicate names.** On create, a name equal to a term's with the same parent, ignoring the case of A to Z only
  (accents and other letters count), is refused with `term_exists`, unless a slug that neither that term nor any other
  term of the taxonomy holds is given; in a hierarchical taxonomy the duplicate must also be a sibling with that exact
  name or with the slug. Updates check no names.
- **Slugs** go through the posts module's `SlugSanitizer` (WordPress's `sanitize_title`), the given one or else the
  name's. A slug needs a suffix only when a term of the same taxonomy holds it; then a hierarchical term takes its
  parent's slug, its grandparent's and so on while the result exists anywhere on the site as a slug or a name, and then
  `-2`, `-3`, … against the slugs of every taxonomy of the site. On update, a slug another term of the taxonomy holds
  is `duplicate_term_slug` unless it was generated or the parent changes. A name without sluggable characters gets the
  term's id as slug.
- **Parents** are given only in hierarchical taxonomies (`rest_taxonomy_not_hierarchical`) and must be terms of the
  same taxonomy and site (`missing_parent`). A parent that would make a term its own ancestor is dropped silently, as
  WordPress drops it; a loop further up is cut by leaving its terms without parent.
- **Deleting** a term moves its children of the same taxonomy to its parent, gives a post whose only term of the
  taxonomy it was the taxonomy's default term (other posts just lose it), then removes it.
- `TermWriteFilter`s see the sanitized term whose slug was made unique; what they return is checked again.

### Posts and counts

- A post of a type registered for `category` saved with a status other than `auto-draft` and no category gets the
  site's default category (the `default_category` option), whoever saves it; a taxonomy with a default term gives it
  to posts of its types saved by a caller who may assign its terms. This happens inside the post's save, before
  application observers run, and also when `Posts.publish` publishes a post. A caller that assigns terms after the save
  (as WordPress's REST API does) can leave a post without category until its next save. Attachments get default terms
  only from `Posts.publish` (see D21).
- Counts are stored: the number of published posts of the taxonomy's types in the term, plus, when attachments are
  among its types, attachments that are published or inherit from a published parent; a taxonomy attached to a type
  that is not a registered post type counts every assignment. They are recomputed when assignments change, when a
  post's status moves into or out of `publish`, and when a post is deleted. An attachment whose parent changes status
  is not recounted (as in WordPress).
- Every site has Uncategorized: sites that exist when the module is installed get it from its migration, new sites
  when the multisite module creates them. Default terms of custom taxonomies are ensured at every startup for every
  site, archived and deleted ones included (one transaction per site, each under that site's post lock, so startup
  time grows with the number of sites), and when a site is created. A default term whose insert is refused (a name the save
  filters empty, a value too long, a `TermWriteFilter`'s refusal) is logged as a warning and its option stays unset;
  startup and the site's creation go on, as WordPress ignores the failure.
- Deleting a post removes its assignments and recomputes their counts without announcing it (D22).

### Taxonomies

The built-in taxonomies are `category` (hierarchical) and `post_tag`, both on `post`, with WordPress's arguments.
Register a taxonomy with `@Taxonomy` on any application class, or from an extension with core's `TaxonomyBuildItem`,
which takes every argument; arguments not given take WordPress's defaults (a taxonomy is public). A key registered
twice, the built-in ones included, fails the build. `Taxonomies` answers what is registered and which taxonomies a post
type has.

```java
@Taxonomy(key = "genre", objectTypes = "book", hierarchical = true, defaultTerm = "General")
public class Genres {}
```

### Queries

`TermQuery` reads its arguments as `WP_Term_Query` does, with the API's defaults: name order ascending, empty terms
hidden (an empty term with a counted descendant stays in a hierarchical query), hierarchical. It takes `include`,
`exclude`, `excludeTree`, `slugs`, `names`, `search` (name or slug), `nameLike`, `descriptionLike`, `parent`,
`childOf`, `childless`, `posts`, `all`, `padCounts`, `number` and `offset` (with WordPress's handling around hierarchy
and parents) and the orders of `TermOrder`; the Javadoc of `TermQuery`, `TermOrder` and `Terms.query` gives the rules.
Only "ASC" in any case is ascending; ties are broken by id. Names compare ignoring case and order by code point. A
query without taxonomies walks no hierarchy, as in WordPress: a positive `parent` or `childOf` finds nothing, and
`childless` and `padCounts` change nothing. Padded counts are computed for every result term with each queried
hierarchical taxonomy's post types in turn, as WordPress computes them.
`count` is `wp_count_terms`: it hides empty terms only when `hideEmpty` is turned on.

### Events

Events are `SiteEvent`s, fired synchronously inside the transaction that caused them and again to `@ObservesAsync`
observers once it has committed:

| Event | WordPress | When |
|---|---|---|
| `TermCreated` | `created_term` | a term was created (not the default category of a new site, which WordPress's installer writes silently) |
| `TermUpdated` | `edited_term` | a term was updated, also a loop member cut by another term's update |
| `TermDeleted` | `delete_term` | a term was deleted, after its posts and children were handed on |
| `TermsAssigned` | `set_object_terms` | a post's terms of a taxonomy were written: by an assignment, a removal that removed something, a default term given on save, or a term delete (not a post delete, D22); with the term ids before and after |

### Filters and replaceable beans

- `TermWriteFilter` (`wp_insert_term_data`, `wp_update_term_data`): the term just before it is written, after the
  duplicate-name check and with its slug made unique. A higher `@Priority` runs first, the opposite of WordPress.
- Slugs use the posts module's replaceable `SlugSanitizer`.

## Storage

`qp_term` holds every term of every site (ids are unique across the network; a term's id is also its term-taxonomy
id), with a unique key on site, taxonomy and slug, and its parent as the composite key `(site_id, parent_id)`.
`qp_term_assignment` holds the assignments (`term_order` for sorted taxonomies). The module's migrations are the `V5_*`
band.

### Locks

Term writes take the posts module's per-site lock after their capability checks, so term and post writes of one site
apply one after the other; term rows are locked in ascending id order when counts change. Lock order: the network, a
user's profile, a membership, the site's post lock, post rows, term rows, option rows last.

## Limits

- Names and slugs hold at most 200 characters; longer ones are refused with `db_insert_error` or `db_update_error`.
- Posts cannot be queried by term yet (the posts module's queries come without taxonomy conditions).
- Term meta has a column but no API yet.
- On MySQL, InnoDB stops cascading deletes at 15 levels, so a site with a chain of term parents (in any hierarchical
  taxonomy) about that deep cannot be deleted in one statement. Delete its deepest terms first.
- WordPress hooks without counterpart include `pre_insert_term`, `pre_delete_term`, `create_term`, `saved_term`,
  `edit_terms`/`edited_terms`, `edit_term_taxonomy`/`edited_term_taxonomy`,
  `edit_term_taxonomies`/`edited_term_taxonomies`, `delete_term_taxonomy`, `wp_update_term_parent`,
  `split_shared_term`, `wp_unique_term_slug` and its bad-slug filter, `added_term_relationship`, `get_terms_args`,
  `terms_clauses`, `get_terms_orderby`, `update_post_term_count_statuses` (only `publish` is counted) and
  `update_count_callback`. The relationship hooks `delete_term_relationships`/`deleted_term_relationships` have
  `TermsAssigned` in their place (D17), except on a post delete, which announces nothing (D22).

## Decisions

- Capabilities follow WordPress's REST layer; data rules follow its API, except where REST is stricter about parents.
- Duplicate names fold only A to Z, as WordPress's PHP step does; lookups by name and name order go through `lower()`
  and code points on both databases.
- Term and post writes of one site share the posts module's site lock.
- Counts are stored and recomputed inside the transaction that changes them, never on read.
- A failed write changes nothing: every write runs in one transaction, which a failure after the first write marks for
  rollback.

## Differences from WordPress

| Label | Difference |
|---|---|
| D1 | Capabilities are checked as WordPress's REST API checks them; its `wp_insert_term` and friends check nothing. Deleting a taxonomy's default term is refused for everyone, the system identity included (WordPress's API answers 0 and keeps it). Assignments need `edit_post`, `assign_term` on each term named (removals included) and the create capability for terms created by name. A query or count with `posts` needs each post to exist on the site (`invalid_post`) and be readable by the caller, as the REST API's `post` parameter does; WordPress's `get_terms` takes any object id. |
| D2 | A parent must be a term of the same taxonomy and site, and only hierarchical taxonomies take one; WordPress's API stores any existing term id as parent, in a flat taxonomy or across taxonomies. A missing parent is `missing_parent`, where WordPress's REST controller answers `rest_term_invalid`; a parent of 0 is accepted in a flat taxonomy, where the REST controller refuses any parent parameter there. Deleting a term leaves all children of a flat term, and children of other taxonomies, without parent (both possible only with rows written outside QuarkPress); WordPress hands a hierarchical term's parent down only to children of its taxonomy, and leaves every other child (all of a flat term's) naming the deleted term. |
| D3 | Lookups by name, `exists`, `search`, `nameLike`, `descriptionLike` and name order ignore case through `lower()` and do not ignore accents; names and slugs order by code point. WordPress follows its MySQL collation, which also ignores accents and orders by Unicode rules. Duplicate names behave alike (both fold only A to Z). On PostgreSQL, `lower()` follows the database's locale: under the `C` locale only ASCII capitals are lowered. |
| D4 | Term ids are unique across the network, one row holds a term and its taxonomy (no shared terms), and a term's id is also its term-taxonomy id; WordPress numbers terms and term-taxonomy rows per site in two sequences, where every site's Uncategorized is term 1; here it is not. |
| D5 | A name or slug longer than 200 characters is refused on both databases alike: with `db_insert_error` on create, as WordPress's failed insert reports it, and with `db_update_error` on update, where WordPress ignores the failed write of its terms row, keeps the old name and slug, saves the rest (description, parent) and reports success. A NUL that a `TermWriteFilter` puts back in a name, slug or description is refused the same way, where WordPress's MySQL stores it (the save filters remove it otherwise, as in WordPress: kses for names and descriptions, the slug sanitizer for slugs). |
| D6 | Removing terms the post does not have, or names that do not exist, removes nothing and announces nothing; WordPress passes such a term's id (an empty id for an unknown name) to its removal hooks (`delete_term_relationships`, `deleted_term_relationships`) and requests a recount. Only events differ, so Tier B cannot observe it; the module's own tests cover it. |
| D7 | No `alias_of` or term groups: every term's group is 0, and ordering by `term_group` orders by id. |
| D8 | Assignments name terms as `TermRef.id` or `TermRef.name` (WordPress reads the text "5" as a name and the number 5 as an id); the post must exist on the site (`invalid_post`), where WordPress accepts any object id. |
| D9 | A failed call changes nothing: an assignment whose later name fails leaves no term created and nothing assigned, where WordPress keeps what it wrote before the failure. |
| D10 | The built-in taxonomies are `category` and `post_tag`; menus, link categories, post formats and the block-theme taxonomies come with their modules. A taxonomy registered twice fails the build; WordPress replaces the earlier registration. A default term named "" or "0" is no default term in either form; WordPress ignores such a text `default_term` too, but keeps an array one, creating a term "0" (and failing silently for ""). Rewrite rules, query variables, meta boxes, `args` and `update_count_callback` are not kept. |
| D11 | Default terms of custom taxonomies are ensured at startup (on every site, at every startup) and when a site is created, not on every request; one renamed while the application runs is looked up again at the next startup. On save they go to posts whose caller may assign the taxonomy's terms, the system identity included, where WordPress's user 0 never gets them on save; the same rule holds on publish (`Posts.publish`), where WordPress's `wp_publish_post` gives them whoever the caller is. |
| D12 | `PostChanges` has no `post_category`, `tags_input` or `tax_input`: callers assign terms after the save, as WordPress's REST API does. |
| D13 | A term whose name gives no slug gets its id as slug, made unique when another term of the taxonomy holds that text; WordPress stores the duplicate. |
| D14 | Term writes of one site are serialized with its post writes; WordPress lets concurrent inserts race (its late duplicate check deletes one of two racing inserts). |
| D15 | Term meta is not exposed yet, and deleting a term deletes no menu items (menus come later). |
| D16 | Descriptions inherit the kses and blocks libraries' differences for block markup: block attributes that lib-wp-blocks re-serializes differently (its D10–D13, reaching kses through lib-wp-kses #7) and a block attribute holding `<` (lib-wp-kses #11). |
| D17 | `TermsAssigned` lists the post's terms before and after, by id; WordPress's `set_object_terms` passes the requested term-taxonomy ids in the order given and the previous ones in database order (none when appending). `TermsAssigned` also announces removals, and a term delete's change to a post in a taxonomy without default term, which WordPress announces only with `delete_term_relationships` and `deleted_term_relationships`. |
| D18 | Queries return term records only (no `fields` shapes, no meta queries, no caches); without taxonomies they cover the registered ones the caller may read, and `excludeTree` there excludes only the terms it names, where WordPress's lookup of their descendants fails and its failure object also excludes term id 1; ties are broken by id; `count` ignores `number` and `offset`, which WordPress puts into its count statement as a limit (so its count given both finds nothing). |
| D19 | A loop further up that an update cuts is cut only when the update succeeds: a refused update leaves it as it was, where WordPress has already cut it before the refusal. Each member of the loop only loses its parent (announced with `TermUpdated`); WordPress saves each member again with a full `wp_update_term`. |
| D20 | Posts of every type registered for `category` get the default category, on save and on publish; WordPress gives it on save only to `post` (and the types of its `default_category_post_types` filter), and on publish to any type with categories. |
| D21 | An attachment saved other than by `Posts.publish` gets no default terms, and its terms are not recounted when such a save changes its status, because the posts module announces no save of an attachment (the media module will); WordPress gives saved attachments the default terms of their custom taxonomies and recounts their terms when their status moves into or out of `publish`. |
| D22 | Deleting a post removes its assignments in every taxonomy, recomputes their counts and announces nothing; WordPress's `wp_delete_post` removes only the terms of the taxonomies of the post's type (through `wp_remove_object_terms`, which fires `delete_term_relationships` and `deleted_term_relationships`). |

The parity tests (`-Pwp-parity`) compare taxonomy registration, the save filters of names, descriptions and slugs, ASCII
lower-casing, PHP's integer cast of ids and order normalization with WordPress 7.1.3's functions (Tier A,
`testing/wp-oracle`; 8509 comparisons, at least 3850 required), and run term scenarios against a full WordPress 7.1.3
multisite in a MySQL container (Tier B, `testing/wp-scenario`; 10 hand-written and 40 generated scenarios, 8399
comparisons, at least 4150 required). Their skip lists name the items above; the term scenarios' skip lines so far cite
only the posts README's D5, for the records of the scenarios' draft posts.
