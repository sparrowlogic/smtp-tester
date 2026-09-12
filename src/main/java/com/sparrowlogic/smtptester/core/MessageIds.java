package com.sparrowlogic.smtptester.core;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Generates message identifiers that sort lexicographically by arrival time.
 *
 * <p>That property is load-bearing rather than cosmetic: the in-memory store is a
 * {@code ConcurrentSkipListMap} keyed by id, so "newest first" and "evict the oldest" are both
 * just navigation on the key order, with no separate index to keep in sync.
 */
public final class MessageIds {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** The exact shape {@link #next(Instant)} produces: 12 hex digits, a hyphen, 8 hex digits. */
    private static final Pattern VALID = Pattern.compile("[0-9a-f]{12}-[0-9a-f]{8}");

    private MessageIds() {
    }

    /** A new id of the form {@code <12 hex ms since epoch>-<8 hex sequence>}. */
    public static String next(final Instant at) {
        return "%012x-%08x".formatted(at.toEpochMilli(), SEQUENCE.incrementAndGet() & 0xFFFFFFFFL);
    }

    /**
     * Whether a string is an id this class could have minted.
     *
     * <p>Ids reach the filesystem: the spool derives a directory name from one, and a spool
     * directory can be a bind-mounted host path or an archive copied between machines, so the
     * metadata read back out of it is not trusted input. Checking the shape here means a crafted
     * {@code metadata.json} carrying {@code ../../..} can never be resolved against the spool root.
     */
    public static boolean isValid(final String id) {
        return VALID.matcher(id).matches();
    }
}
