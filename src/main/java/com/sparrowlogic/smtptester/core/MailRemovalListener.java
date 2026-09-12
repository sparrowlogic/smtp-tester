package com.sparrowlogic.smtptester.core;

/**
 * Notified when a message is explicitly deleted from the store.
 *
 * <p>This is how the spool stays consistent with the inbox: deleting a message in the UI or via
 * MCP has to delete the file on disk too, and a user who deletes something expects it gone now.
 *
 * <p>Eviction past the retention limit deliberately does <em>not</em> fire this. That path runs on
 * an SMTP session thread, and the spool is trimmed in batch by {@code SpoolRetentionTask}.
 */
@FunctionalInterface
public interface MailRemovalListener {

    /** A no-op listener, used when nothing is spooled. */
    MailRemovalListener NONE = message -> { };

    void onRemoved(MailMessage message);
}
