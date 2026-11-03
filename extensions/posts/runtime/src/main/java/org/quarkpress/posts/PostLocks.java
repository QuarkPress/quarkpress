package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * The editor's post lock, as WordPress's edit screen and heartbeat keep it: one {@code _edit_lock} meta value
 * {@code "<epoch seconds>:<user id>"} per post, written by the user who edits it and refreshed while the editor is
 * open. A lock is <em>fresh</em> while its time is strictly later than now minus the lock window
 * ({@code quarkpress.posts.lock-window}, 150 seconds by default); a fresh lock of another existing user is what
 * {@link #holder} reports, and the acting user's own lock never counts against him. A stored value without user part
 * names the post's {@code _edit_last} meta as the holder (QuarkPress itself always writes the user part).
 *
 * <p>Taking, refreshing, taking over and releasing need {@code edit_post} on the post; the system identity cannot
 * hold a lock. Lock writes change neither the post's modification date nor its version, so a caller sending an
 * expected version is never refused because of an editor's heartbeat. Only the autosave branch of the revisions
 * module reads locks; every other read and write ignores them, as WordPress's REST API does.
 */
public interface PostLocks {

    /** A lock as stored: {@code "<epoch seconds>:<user id>"}. */
    record Lock(UserId user, Instant since) {

        /** Epoch seconds of a lock have at most this many digits (enough past the year 5000); more is no lock. */
        private static final int MAX_SECONDS_DIGITS = 11;

        public Lock {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(since, "since");
        }

        /** The stored text, {@code "<epoch seconds>:<user id>"}. */
        public String value() {
            return since.getEpochSecond() + ":" + user.value();
        }

        /**
         * Reads a value QuarkPress writes: decimal seconds (at most eleven digits), a colon, a positive decimal user
         * id. Anything else, a value without user part included, is empty; nothing throws, whatever the text.
         */
        public static Optional<Lock> parse(String value) {
            Objects.requireNonNull(value, "value");
            int colon = value.indexOf(':');
            if (colon <= 0 || colon > MAX_SECONDS_DIGITS) {
                return Optional.empty();
            }
            long seconds = 0;
            for (int i = 0; i < colon; i++) {
                char c = value.charAt(i);
                if (c < '0' || c > '9') {
                    return Optional.empty();
                }
                seconds = seconds * 10 + (c - '0');
            }
            Instant since = Instant.ofEpochSecond(seconds);
            return UserId.parse(value.substring(colon + 1)).map(user -> new Lock(user, since));
        }
    }

    /** The outcome of taking or refreshing a lock. */
    sealed interface Take permits Take.Taken, Take.Held {

        /** The lock is now the caller's. */
        record Taken(Lock lock) implements Take {
            public Taken {
                Objects.requireNonNull(lock, "lock");
            }
        }

        /** Another user holds a fresh lock; nothing was written. */
        record Held(UserId holder) implements Take {
            public Held {
                Objects.requireNonNull(holder, "holder");
            }
        }
    }

    /**
     * The user whose fresh lock keeps {@code acting} out of the post: the stored lock's user when it exists, its
     * time is strictly later than now minus the window and it is not {@code acting} (a lock of the acting user, an
     * expired one, one of a user that does not exist, or none at all gives empty; a missing post too). A value
     * without user part names the post's {@code _edit_last}. No capability check; reads without any lock.
     */
    Optional<UserId> holder(PostId post, Optional<UserId> acting);

    /**
     * Takes the lock for the caller unless another user holds a fresh one, in which case nothing is written and
     * that holder is reported. Needs {@code edit_post}.
     */
    Take take(PostId post);

    /**
     * The heartbeat's refresh: the same rule as {@link #take} (an absent or expired lock is taken again, even if it
     * was another user's; a fresh lock of another user is reported and left alone). Needs {@code edit_post}.
     */
    Take refresh(PostId post);

    /** Takes the lock for the caller whatever is stored. Needs {@code edit_post}. */
    Lock takeOver(PostId post);

    /**
     * Releases the caller's lock on leaving: when the user part of {@code expected} is the caller and the stored
     * value still equals {@code expected}, the lock becomes {@code "<now - window + 5>:<caller>"}, the same holder
     * expiring five seconds later, and the answer is true; otherwise nothing changes and the answer is false. Needs
     * {@code edit_post}.
     */
    boolean release(PostId post, String expected);

    /** The stored lock, when the post has one that {@link Lock#parse} reads; empty for a missing post. */
    Optional<Lock> current(PostId post);
}
