package com.sparrowlogic.smtptester.spool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Trims the spool directory on a timer rather than on the thread that just accepted a message.
 *
 * <p>Deleting a message's directory is a recursive filesystem walk. Doing it inline, once per
 * arrival, puts that walk on an SMTP session thread while the client waits for its {@code 250} —
 * fine against a local SSD, much less fine against a bind-mounted volume or network storage, and
 * entirely wasted work when a burst is going to evict a thousand messages anyway. Sweeping in
 * batch turns a thousand one-directory deletes into one pass that lists the root once.
 *
 * <p>The consequence, and it is deliberate: between sweeps the spool may hold more than
 * {@code keep} messages. Disk is the cheap resource here and the overshoot is bounded by the
 * arrival rate over one interval, whereas ingest latency is what a test suite waits on.
 * {@link MailSpool#enforceRetention(int)} works from directory names alone, so it also removes
 * what eviction never could: directories left by a previous run that were never loaded back in.
 */
public class SpoolRetentionTask {

    private static final Logger LOG = LoggerFactory.getLogger(SpoolRetentionTask.class);

    private final MailSpool spool;
    private final int keep;

    public SpoolRetentionTask(final MailSpool spool, final int keep) {
        this.spool = spool;
        this.keep = keep;
    }

    /**
     * One sweep. A no-op when spooling is off, because {@link MailSpool#DISABLED} keeps nothing.
     *
     * @return how many message directories were removed, for the benefit of tests
     */
    @Scheduled(fixedDelayString = "${smtp-tester.spool.retention-interval:30s}",
            initialDelayString = "${smtp-tester.spool.retention-interval:30s}")
    public int sweep() {
        final int removed = this.spool.enforceRetention(this.keep);
        if (removed > 0) {
            LOG.debug("Spool sweep removed {} message director(ies)", removed);
        }
        return removed;
    }
}
