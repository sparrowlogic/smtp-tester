package com.sparrowlogic.smtptester.core;

/**
 * Notified whenever a message leaves the store, whether by explicit deletion or by eviction once
 * the retention limit is reached.
 *
 * <p>This is how the spool stays consistent with the inbox: deleting a message in the UI or via MCP
 * has to delete the file on disk too, and so does ageing one out.
 */
@FunctionalInterface
public interface MailRemovalListener {

    /** A no-op listener, used when nothing is spooled. */
    MailRemovalListener NONE = message -> { };

    void onRemoved(MailMessage message);
}
