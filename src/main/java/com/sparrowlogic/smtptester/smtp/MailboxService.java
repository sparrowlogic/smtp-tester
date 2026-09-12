package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.InboxSummary;
import com.sparrowlogic.smtptester.core.MailArrivalListener;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import com.sparrowlogic.smtptester.core.MailStore;
import com.sparrowlogic.smtptester.core.MessageIds;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.PartExtractor;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.logging.ReceivedMailLogger;
import com.sparrowlogic.smtptester.spool.MailSpool;
import com.sparrowlogic.smtptester.validation.Finding;
import com.sparrowlogic.smtptester.validation.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single place a received message passes through: parse, validate, store, spool, log.
 *
 * <p>The SMTP handler, the REST controllers and the MCP tools all call this rather than the store
 * directly, so there is exactly one definition of what "receive" and "delete" mean. That matters
 * most for deletion, which has to reach the spool as well as memory.
 */
public class MailboxService {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxService.class);

    private final MailStore store;
    private final MimeParser parser;
    private final MailSpool spool;
    private final ReceivedMailLogger mailLogger;
    private final PartExtractor partExtractor;
    private final ValidationPolicy validation;
    private final Clock clock;
    private volatile List<MailArrivalListener> arrivalListeners = List.of();

    public MailboxService(final MailStore store, final MimeParser parser,
            final ValidationPolicy validation, final MailSpool spool,
            final ReceivedMailLogger mailLogger, final Clock clock) {
        this.store = store;
        this.parser = parser;
        this.validation = validation;
        this.spool = spool;
        this.mailLogger = mailLogger;
        this.partExtractor = new PartExtractor();
        this.clock = clock;
    }

    /**
     * Ingests one delivered message.
     *
     * @param raw the bytes exactly as transferred
     * @param envelope the SMTP envelope they arrived in
     * @param session what the transport layer observed about the connection
     * @return the stored message, and the finding to reject on when it fails the configured policy
     */
    public IngestResult receive(final byte[] raw, final Envelope envelope, final SessionInfo session) {
        final Instant now = Instant.now(this.clock);
        final ParsedMessage parsed = this.parser.parse(raw);
        final ValidationReport report = this.validation.validate(raw, envelope, parsed, session);
        final Finding rejection = this.validation.rejectionFor(report);
        final MailMessage message = new MailMessage(MessageIds.next(now), now, envelope, raw, parsed,
                report, rejection != null, session);
        this.store.add(message);
        this.spool.store(message);
        this.mailLogger.log(message);
        this.announce(message);
        return new IngestResult(message, rejection);
    }

    /**
     * Registers a listener for every subsequently received message.
     *
     * <p>Registration rather than a constructor argument because the interesting listener — the SSE
     * broadcaster — lives in the web layer, which is wired after this bean and is switched off
     * entirely by {@code smtp-tester.web.enabled=false}.
     */
    public synchronized void addArrivalListener(final MailArrivalListener listener) {
        final List<MailArrivalListener> updated = new ArrayList<>(this.arrivalListeners);
        updated.add(listener);
        this.arrivalListeners = List.copyOf(updated);
    }

    /** Fired after the message is in the store, so a listener that reads it back cannot miss it. */
    private void announce(final MailMessage message) {
        this.arrivalListeners.forEach(listener -> listener.onReceived(message));
    }

    /** Re-reads a spool directory into the store at startup. */
    public int restoreFromSpool() {
        final List<MailMessage> restored = this.spool.loadAll();
        restored.forEach(this.store::add);
        if (!restored.isEmpty()) {
            LOG.info("Restored {} message(s) from the spool directory", restored.size());
        }
        return restored.size();
    }

    /**
     * Trims the spool directory to at most {@code keep} messages, returning how many were deleted.
     *
     * <p>Separate from eviction because it has to cover what eviction cannot see: the store only
     * evicts messages it holds, so anything on disk that was not restored is beyond its reach.
     */
    public int enforceSpoolRetention(final int keep) {
        return this.spool.enforceRetention(keep);
    }

    public List<MailMessage> list(final MailQuery query) {
        return this.store.list(query);
    }

    public long count(final MailQuery query) {
        return this.store.count(query);
    }

    public long size() {
        return this.store.size();
    }

    public Optional<MailMessage> find(final String id) {
        return this.store.find(id);
    }

    public List<InboxSummary> inboxes() {
        return this.store.inboxes();
    }

    /** Deletes one message, and its spooled files with it. */
    public boolean delete(final String id) {
        return this.store.delete(id).isPresent();
    }

    /** Deletes every message in one inbox, and their spooled files. */
    public int clearInbox(final String inbox) {
        return this.store.deleteMatching(new MailQuery(inbox, null, null, null, null, false, 0, 0));
    }

    /** Deletes every message in every inbox, and their spooled files. */
    public int clearAll() {
        return this.store.clear();
    }

    /** The decoded bytes of one MIME part, re-extracted from the retained raw message. */
    public Optional<byte[]> attachment(final String id, final int index) {
        return this.find(id).flatMap(message -> this.partExtractor.extract(message.raw(), index));
    }

    /** Where a message was spooled, when spooling is on. */
    public Optional<java.nio.file.Path> spoolDirectory(final MailMessage message) {
        return this.spool.directoryFor(message);
    }
}
