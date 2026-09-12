package com.sparrowlogic.smtptester.chaos;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Rate-limits reads to a fixed number of bytes per second.
 *
 * <p>Used to reproduce the slow link that makes a client's socket timeout fire. It sleeps rather
 * than busy-waits, and it paces against elapsed time from the first read instead of sleeping a
 * fixed amount per chunk, so the realised rate matches the target even when the caller reads in
 * irregular sizes.
 */
public class ThrottledInputStream extends FilterInputStream {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int bytesPerSecond;
    private final long startedAt;
    private long bytesRead;

    public ThrottledInputStream(final InputStream delegate, final int bytesPerSecond) {
        super(delegate);
        if (bytesPerSecond <= 0) {
            throw new IllegalArgumentException("bytesPerSecond must be positive, but was " + bytesPerSecond);
        }
        this.bytesPerSecond = bytesPerSecond;
        this.startedAt = System.nanoTime();
    }

    @Override
    public int read() throws IOException {
        final int value = super.read();
        if (value >= 0) {
            this.bytesRead++;
            this.pace();
        }
        return value;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        final int count = super.read(buffer, offset, length);
        if (count > 0) {
            this.bytesRead += count;
            this.pace();
        }
        return count;
    }

    /** Sleeps until the bytes read so far would have taken this long at the target rate. */
    private void pace() throws IOException {
        final long targetNanos = this.bytesRead * NANOS_PER_SECOND / this.bytesPerSecond;
        final long elapsedNanos = System.nanoTime() - this.startedAt;
        final long sleepNanos = targetNanos - elapsedNanos;
        if (sleepNanos <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while throttling the message transfer", e);
        }
    }
}
