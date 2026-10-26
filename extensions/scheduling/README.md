# QuarkPress Scheduling

WordPress's cron-driven post jobs for QuarkPress: scheduled posts published when their time comes by a polling job, the
trash emptied and old auto-drafts removed by a housekeeping job, and a per-site switch. There is no cron: the jobs run
on Quarkus's scheduler at fixed intervals, on every node, whatever the traffic. It requires the posts module and works
on a single site and in a network.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-scheduling</artifactId>
</dependency>
```

The extension brings `quarkpress-posts` (and through it persistence, security, lib-wp-kses and lib-wp-blocks),
`quarkpress-security`, `quarkpress-persistence` and `quarkus-scheduler`. Public API lives in
`org.quarkpress.scheduling`; `org.quarkpress.scheduling.runtime` is implementation and may change.

## Using it

There is nothing to call: the jobs run by themselves once the extension is on the classpath. Inject `SchedulingStatus`
to read what this node's jobs did (see Status). The handler the poll calls for each due post is the posts module's
`Posts.publishIfDue`, which any caller with `publish_post` may call too.

```java
SchedulingStatus.Snapshot snapshot = status.snapshot();
boolean published = posts.publishIfDue(post.id());
```

The jobs' beans in the runtime package, `PublishPoll.poll()` and `Housekeeping.run()`, `purgeTrash(site)` and
`purgeAutoDrafts(site)`, may be called from anywhere outside a transaction (a test, the Dev UI): each call hops to a
thread of its own with nothing of the caller's context (no request, transaction, identity or scope binding), runs
there and returns its result (the posts published, in order; what a housekeeping purged). A call made inside an active
transaction is refused with `IllegalStateException` ("a scheduling job is not called inside a transaction"): the job's
own transactions would wait on locks the caller's holds, from another thread, and no database would see the deadlock.
A `RuntimeException` of the job is rethrown on the caller's thread as it is. `quarkpress.scheduling.enabled=false`
stops the scheduler from running the jobs on this node; a direct call still works.

### The publish poll

Every `quarkpress.scheduling.interval` (15 s by default) the poll runs, on every node, and overlapping runs on one node
are skipped. One run:

- lists the sites with at least one `future` post whose GMT date is now or past, in ascending site id order, in one
  network-wide read as the system identity that ends before any site is visited;
- then, for each site in a scope of that site: skips the site when its `scheduling` feature is off; otherwise reads
  its due posts (`future`, GMT date now or past) by date and then id, at most `quarkpress.scheduling.batch-size` of
  them (the rest wait for the next poll);
- then publishes each of them through `Posts.publishIfDue` in a site scope and transaction of its own, as the system
  identity. Under the site's post lock the post is read again: one that is no longer `future`, or whose date is still
  ahead, is left alone.

A publication changes the status only: no date, slug or modification date is touched and the author is kept, as
WordPress's `wp_publish_post`. It fires `PostStatusChanged`, `PostPublished` and `PostSaved` for every type, never
`PostUpdated`; the taxonomy and revisions modules observe those as they observe any save.

A post whose publication fails (an observer throws, the database refuses) is logged at WARN, recorded as the status
bean's last failure, and stays `future`: the poll goes on with the next post and the post is retried on every later
poll until it succeeds or the post is re-saved. A post that vanished between the read and the publication is logged
at DEBUG and skipped. A site whose due posts cannot be listed is recorded and retried on the next poll; when the site
list itself cannot be read the poll records the failure and ends empty.

### Housekeeping

Every `quarkpress.scheduling.housekeeping-interval` (24 h by default), counted from application start so that the
first run is one interval after start, the housekeeping runs on every node, overlapping runs on one node skipped. It
visits every site of the network in ascending id order, skips a site whose `scheduling` feature is off, and for the
others purges the trash, then the auto-drafts:

- The trash purge deletes every trashed post, of any type, whose trash time is strictly older than now minus
  `quarkpress.posts.empty-trash-days` days. The trash time is the meta `_wp_trash_meta_time` the posts module writes
  when trashing, read as a number of seconds: the first value under the key, a JSON number or a text of 1 to 18 ASCII
  digits; anything else, or no such meta, and the post is never purged.
- The auto-draft purge deletes every `auto-draft`, of any type, last modified more than seven days ago (the stored
  modification instant strictly before now minus 7 days).

The candidates of a purge are selected in one site transaction that ends before any delete, page by page of
`batch-size` rows in id order; the trash selection reads only ids and metas, so a large trash is not loaded whole.
Each candidate is then read again in a site scope and transaction of its own, as the system identity, as WordPress's
handler reads a post again just before deleting it: one that is no longer in the trash, no longer overdue, no longer
an auto-draft or no longer stale is skipped. A trashed post goes through `Posts.delete(id, false)` (already in the
trash, it is deleted for good); an auto-draft through `Posts.delete(id, true)`. A delete fires the posts module's
`PostDeleting` and `PostDeleted`, so the taxonomy and revisions modules clean up what they keep. A delete that fails
is logged at WARN and recorded, and the purge goes on; a candidate already gone at the re-read is skipped silently,
and one deleted by another node between the re-read and the delete is logged at DEBUG.

### Several nodes

Any number of nodes may run the jobs on one database; nothing elects a leader. Each node polls on its own, and every
node sees the same due posts. The guarantee is exactly once, not work-sharing: `publishIfDue` takes the posts module's
per-site post lock, reads the post again under its row lock and publishes only a post still `future` and due, so of
two nodes publishing the same post one publishes and fires the events and the other finds it published and does
nothing. Nodes select the same batch in the same order and none skips locked rows, so a node whose poll runs while
another holds the locks waits on each row and may publish nothing in that poll. The purges re-read each candidate the
same way and delete it once.

### Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.scheduling.enabled` | `true` | Whether this node runs the jobs. `false` skips every scheduled run here through the scheduler's skip predicate; direct calls to the jobs and `Posts.publishIfDue` still work. |
| `quarkpress.scheduling.interval` | `15s` | How often due posts are looked for. WordPress has no interval: its latency depends on traffic. |
| `quarkpress.scheduling.batch-size` | `100` | How many due posts are published per site per poll (the rest wait for the next poll), and how many rows the housekeeping reads per page. A value below 1 counts as 1. |
| `quarkpress.scheduling.housekeeping-interval` | `24h` | How often the trash and old auto-drafts are purged, counted from application start. |

The trash retention is the posts module's `quarkpress.posts.empty-trash-days` (30 by default). The scheduler itself is
the host application's: `quarkus.scheduler.enabled=false` stops every scheduled job of the application, this module's
included.

### The site feature

The per-site feature `scheduling` (on by default; `FeatureToggles`, the `qp_features` option) gates the three jobs for
the site: off, the site's scheduled posts wait, its trash is not emptied and its auto-drafts are kept, until it is on
again; nothing is lost meanwhile, since an overdue post publishes on the first poll that sees it. The posts module's
save-time switch (a `future` post re-saved with a date less than 60 seconds ahead becomes `publish`) is not affected by
the feature.

### Status

`SchedulingStatus.snapshot()` is an immutable `Snapshot` of this node's jobs since it started: `lastPollStarted` and
`lastPollEnded`, `lastBatch` (the last poll's published posts, as `Published(site, post)`, in order),
`publishedSinceStart`, `lastFailure` (a `Failure` with the site and post it concerned when it concerned one, its
message and when it happened) and `lastHousekeeping`. It is per node and in memory: nothing is persisted or shared
between nodes, and a restart starts from nothing. A list of the next due posts comes with the Dev UI.

## Transactions and locks

The module takes no lock of its own. A run reads the site list in one network-wide transaction of its own, which only
reads and ends before any site scope opens; each site is then visited in a scope of its own, and each post is published
or deleted in a scope and transaction of its own, as the system identity. Every write goes through the posts service, so
it takes the site's post lock after its capability check and follows the posts module's lock order (network, user
profile, membership, the site's post lock, post rows, option rows last); a write refused before the first write (a
capability check, a lock timeout), or failing after its first write, rolls back that post's transaction alone and the
run goes on. The jobs run on a thread of their own, so no transaction, identity or scope of the caller reaches them.

## Limits

- No pings: a publication writes no `_pingme`, `_encloseme` or `_trackbackme` meta and queues nothing.
- No comment purge (there are no comments yet), no transient or update housekeeping.
- No cron option, no general event queue and no API to schedule, reschedule, unschedule or inspect events: the
  `future` status with its date is the schedule.
- A `future` post without a GMT date is left to `publishIfDue`, which publishes it when called (the poll never
  selects it, since its date is not due).
- The `future` to `publish` switch at save time (an overdue scheduled post re-saved becomes published) belongs to the
  posts module and ignores the feature.

## Decisions

- Polling plus the posts module's per-site lock, instead of Quartz or a bulk status update, so that every publication
  runs the posts module's full publish path and its events and observers run per post, and so that any number of
  nodes may poll with exactly one publication per post.
- Due posts are published per site in batches of `batch-size`, sites ascending and posts by date then id, so one site
  with a large backlog cannot hold the others.
- The jobs hop to a thread of their own and refuse a call made inside a transaction: a job's transactions
  must never wait on locks held by the caller from another thread.
- The purges run from application start on an interval, every site, instead of WordPress's per-site daily events
  scheduled from an admin visit; the first run is one interval after start.
- The multi-node guarantee is exactly once, not work-sharing: nodes do not skip each other's locked rows.
- The trash purge re-reads each candidate in its own transaction before deleting, as WordPress's handler re-reads,
  and selects ids and metas only.

## Differences from WordPress

| Label | Difference |
|---|---|
| D1 | One network-wide poller publishes due posts every `quarkpress.scheduling.interval` (15 s by default) on every node, whatever the traffic; WordPress runs each site's cron only when a request reaches that site and spawns a loopback, so a site nobody visits publishes nothing and an unreachable loopback publishes nothing. |
| D2 | There is no "missed schedule" state and no lost event: an overdue scheduled post publishes on the first poll that sees it, however late; a publication that fails (an observer throws, the database refuses) is logged, shown on the status bean and retried on every poll until it succeeds or the post is re-saved. WordPress removes a cron event before running it, so a crashing handler loses it, and shows "Missed schedule" in the admin list. |
| D3 | A post publishes at its one stored GMT date; WordPress queues the event at the local date's conversion made at save time and re-checks the GMT date when it fires, so an inconsistent pair or a zone change can make it publish at the later of the two. |
| D4 | The per-site feature `scheduling` holds a site's scheduled posts and purges back until it is on again; WordPress's `DISABLE_WP_CRON` is installation-wide and only stops spawning. The posts module's save-time switch (an overdue scheduled post re-saved becomes published) ignores the feature. |
| D5 | Due posts are published per site in batches of `batch-size` per poll, sites in ascending id order, posts by date then id; WordPress runs every due event of a site in one run, in timestamp order and then event insertion order. |
| D6 | Cron's publication writes no `_pingme`, `_encloseme` or `_trackbackme` meta and queues no `do_pings`: there are no pings in this release. |
| D7 | The trash purge and the auto-draft purge run from application start every `housekeeping-interval` (24 h by default) on every node, for every site; WordPress schedules them from the first admin visit (the trash purge) and the first "Add New" (the auto-draft purge) of each site, daily from that time of day. |
| D8 | The trash purge examines only rows in the trash: a row carrying `_wp_trash_meta_time` that is no longer in the trash is left alone; WordPress deletes its two trash meta keys. QuarkPress's untrash removes them, and a direct database write is not a supported path. |
| D9 | The auto-draft purge compares the stored modification instant with now, both in UTC, over a fixed 7 days; WordPress compares the local post date text with the MySQL server's clock, with a slip of up to the site's offset. |
| D10 | No comments are purged (no comments yet), no transients, no update checks, no other core cron events: only scheduled publishing and the two post purges have counterparts. |
| D11 | There is no cron option, no general event queue and no API to schedule, reschedule, unschedule or inspect events; the `future` status with its date is the schedule. `PostScheduled` fires on every save ending in `future`, as `future_{type}` does; nothing fires when a schedule is cleared, as in WordPress. |
| D12 | `Posts.publishIfDue`, the callable handler, needs `publish_post` for a caller other than the system identity and is a lock-protected compare-and-set; WordPress's `check_and_publish_future_post` checks nothing and writes a plain status update guarded only by the cron lock and its status check. |
| D13 | `quarkpress.scheduling.enabled=false` stops this node's jobs only, through the scheduler's skip predicate; other nodes keep polling, and direct calls to the jobs and `Posts.publishIfDue` still work. WordPress's `DISABLE_WP_CRON` is installation-wide and only stops requests from spawning cron: a direct run of `wp-cron.php` still fires every due event. |

The parity tests (`-Pwp-parity`) run scheduling scenarios against a full WordPress 7.1.3 multisite in a MySQL container
(Tier B, `testing/wp-scenario`): the publication check through `publishIfDue` as the system identity, the two purges
through the housekeeping's purges of one site, and a forced-date fixture written directly to the row on both sides; 44
scenarios, 4 hand-written and 40 generated; 3742 comparisons in the first green run, at least 1850 required (the four
hand-written ones alone made 489 when they first ran green). Their skip list is labelled with this table; today every
line is the posts README's (D5 and D3). The posts integration application (`integration-tests/posts`) carries this
module, exercises the jobs over HTTP and launches the packaged application a second time on one database to check that
two nodes publish every due post exactly once.
