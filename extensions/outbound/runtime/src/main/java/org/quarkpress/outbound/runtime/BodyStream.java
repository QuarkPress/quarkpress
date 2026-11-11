package org.quarkpress.outbound.runtime;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.quarkpress.outbound.FetchException;

/**
 * A blocking stream over a Vert.x response body. The event loop queues the buffers as they arrive and pauses the
 * response while the queue is full, so a slow reader holds at most {@value #QUEUE} buffers; past the byte limit, the
 * deadline, or an idle time without data while the reader waits, the connection is reset and reading fails. The idle
 * time counts only while the response is not paused: a slow reader does not make a server look silent.
 */
public final class BodyStream extends InputStream {

    /** The most buffers queued before the response is paused. */
    static final int QUEUE = 16;

    private final HttpClientRequest request;
    private final HttpClientResponse response;
    private final Context context;
    private final long maxBytes;
    private final long deadline;
    private final Duration timeout;
    private final long idleNanos;

    private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
    private Buffer current;
    private int position;
    private long received;
    private boolean ended;
    private boolean paused;
    private boolean closed;
    private FetchException failure;
    /** When data last arrived, or the response was last resumed ({@link System#nanoTime()}). */
    private long lastActivity = System.nanoTime();

    /** Attaches to {@code response}; called on its event loop before any of its data is delivered. */
    BodyStream(
            HttpClientRequest request,
            HttpClientResponse response,
            Context context,
            long maxBytes,
            long deadline,
            Duration timeout,
            Duration idleTimeout) {
        this.request = request;
        this.response = response;
        this.context = context;
        this.maxBytes = maxBytes;
        this.deadline = deadline;
        this.timeout = timeout;
        this.idleNanos = idleTimeout.toNanos();
        response.handler(this::onData);
        response.endHandler(ignored -> onEnd());
        response.exceptionHandler(this::onFailure);
    }

    private synchronized void onData(Buffer buffer) {
        if (closed || failure != null) {
            return;
        }
        received += buffer.length();
        if (received > maxBytes) {
            failure = new FetchException(
                    FetchException.Kind.TOO_LARGE, "the response is larger than " + maxBytes + " bytes");
            queue.clear();
            request.reset();
            notifyAll();
            return;
        }
        lastActivity = System.nanoTime();
        queue.add(buffer);
        if (queue.size() >= QUEUE && !paused) {
            paused = true;
            response.pause();
        }
        notifyAll();
    }

    private synchronized void onEnd() {
        ended = true;
        notifyAll();
    }

    private synchronized void onFailure(Throwable cause) {
        if (failure == null && !ended && !closed) {
            failure = VertxFetch.failure(cause, timeout);
        }
        notifyAll();
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public synchronized int read(byte[] target, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) {
            return 0;
        }
        while (true) {
            if (failure != null) {
                throw failure;
            }
            if (closed) {
                throw new IOException("the body stream is closed");
            }
            long left = deadline - System.nanoTime();
            if (!ended && left <= 0) {
                failure = new FetchException(
                        FetchException.Kind.TIMEOUT, "the exchange took longer than " + timeout.toMillis() + " ms");
                abort();
                continue;
            }
            if (current != null && position < current.length()) {
                break;
            }
            current = queue.poll();
            position = 0;
            if (current != null) {
                resumeIfDrained();
                continue;
            }
            if (ended) {
                return -1;
            }
            long idleLeft = lastActivity + idleNanos - System.nanoTime();
            if (idleLeft <= 0) {
                failure = new FetchException(
                        FetchException.Kind.TIMEOUT,
                        "no data arrived for " + TimeUnit.NANOSECONDS.toMillis(idleNanos) + " ms");
                abort();
                continue;
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(left, idleLeft));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                abort();
                throw new FetchException(FetchException.Kind.IO, "interrupted while reading the body", e);
            }
        }
        int count = Math.min(length, current.length() - position);
        current.getBytes(position, position + count, target, offset);
        position += count;
        return count;
    }

    /** Closes the connection unless the body arrived to its end. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        queue.clear();
        current = null;
        if (!ended || failure != null) {
            abort();
        }
        notifyAll();
    }

    private void resumeIfDrained() {
        if (paused && queue.size() <= QUEUE / 2) {
            paused = false;
            lastActivity = System.nanoTime();
            context.runOnContext(ignored -> response.resume());
        }
    }

    private void abort() {
        context.runOnContext(ignored -> request.reset());
    }
}
