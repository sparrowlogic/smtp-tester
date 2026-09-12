package com.sparrowlogic.smtptester.spool;

import com.sparrowlogic.smtptester.core.MailMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Write-through persistence of received mail to a directory. */
public interface MailSpool {

    /** A spool that does nothing, used when no directory is configured. */
    MailSpool DISABLED = new DisabledMailSpool();

    /** Writes the message, its metadata and its decoded parts. */
    void store(MailMessage message);

    /** Removes everything written for the message. Deleting mail must delete its files too. */
    void remove(MailMessage message);

    /** Re-reads a spool directory into memory at startup. */
    List<MailMessage> loadAll();

    /**
     * Deletes the oldest spooled messages until at most {@code keep} remain, and reports how many
     * went.
     *
     * <p>Retention is otherwise enforced only as a side effect of eviction from the in-memory
     * store, which can only evict what it holds. A spool directory that outlives the process —
     * the normal case, since persisting across restarts is the point of it — therefore accumulates
     * whatever was never loaded back in, and {@code max-messages} silently stops bounding the disk
     * even though it still bounds memory. This is the directory-side half of that cap, applied at
     * startup so the bound holds regardless of what was restored.
     *
     * <p>A startup sweep bounds the directory; it does not pin it to {@code keep} at every instant.
     * When nothing was restored, the messages this run writes sit alongside the {@code keep} it
     * left behind, so the directory can reach roughly twice that between sweeps. Enforcing it
     * exactly would mean listing the directory on every delivery, which is a real cost on the hot
     * path to buy precision nobody needs from a retention limit.
     *
     * @param keep the greatest number of messages to leave on disk
     * @return how many were deleted
     */
    int enforceRetention(int keep);

    /** Where the message was written, when it was. */
    Optional<Path> directoryFor(MailMessage message);

    /**
     * The no-op spool.
     *
     * <p>A null object rather than a nullable field, so every call site — ingest, deletion,
     * eviction, the UI's "spooled to" row — is written once and does not have to ask whether
     * spooling happens to be on.
     */
    final class DisabledMailSpool implements MailSpool {

        @Override
        public void store(final MailMessage message) {
            // Nothing is written when spooling is off.
        }

        @Override
        public void remove(final MailMessage message) {
            // Nothing was written, so there is nothing to remove.
        }

        @Override
        public List<MailMessage> loadAll() {
            return List.of();
        }

        @Override
        public Optional<Path> directoryFor(final MailMessage message) {
            return Optional.empty();
        }

        @Override
        public int enforceRetention(final int keep) {
            return 0;
        }
    }
}
