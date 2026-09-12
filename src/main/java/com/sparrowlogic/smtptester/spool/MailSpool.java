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
    }
}
