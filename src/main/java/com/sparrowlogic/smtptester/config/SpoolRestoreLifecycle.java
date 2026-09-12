package com.sparrowlogic.smtptester.config;

import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.springframework.context.SmartLifecycle;

/**
 * Reloads a spool directory into the inbox before the SMTP listener opens.
 *
 * <p>A lifecycle rather than an {@code ApplicationRunner} purely for ordering: runners fire after
 * the context has finished refreshing, by which point the listener is already accepting mail, and
 * restored messages would then interleave with live ones. A lower phase than
 * {@code SmtpReceiver} guarantees the restore completes first.
 */
public class SpoolRestoreLifecycle implements SmartLifecycle {

    private final MailboxService mailbox;
    private volatile boolean running;

    public SpoolRestoreLifecycle(final MailboxService mailbox) {
        this.mailbox = mailbox;
    }

    @Override
    public void start() {
        this.mailbox.restoreFromSpool();
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
