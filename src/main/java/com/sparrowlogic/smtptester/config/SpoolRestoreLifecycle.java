package com.sparrowlogic.smtptester.config;

import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.springframework.context.SmartLifecycle;

/**
 * Reconciles the spool directory with the inbox before the SMTP listener opens.
 *
 * <p>A lifecycle rather than an {@code ApplicationRunner} purely for ordering: runners fire after
 * the context has finished refreshing, by which point the listener is already accepting mail, and
 * restored messages would then interleave with live ones. A lower phase than
 * {@code SmtpReceiver} guarantees this completes first.
 *
 * <p>Two jobs, and the second runs whether or not the first does. Restoring is optional — some
 * setups want a spool for the record but an empty inbox each run. Retention is not: a directory
 * nobody loads is still a directory that grows, so the {@code max-messages} cap is applied to the
 * disk here as well as to memory. Retention runs after the restore so it acts on the same
 * directory listing the inbox was built from.
 */
public class SpoolRestoreLifecycle implements SmartLifecycle {

    private final MailboxService mailbox;
    private final boolean restoreOnStartup;
    private final int maxMessages;
    private volatile boolean running;

    public SpoolRestoreLifecycle(final MailboxService mailbox, final boolean restoreOnStartup,
            final int maxMessages) {
        this.mailbox = mailbox;
        this.restoreOnStartup = restoreOnStartup;
        this.maxMessages = maxMessages;
    }

    @Override
    public void start() {
        if (this.restoreOnStartup) {
            this.mailbox.restoreFromSpool();
        }
        this.mailbox.enforceSpoolRetention(this.maxMessages);
        this.running = true;
    }

    @Override
    public void stop() {
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2048;
    }
}
