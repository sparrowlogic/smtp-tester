package com.sparrowlogic.smtptester.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The default store: a bounded, time-ordered map held in memory.
 *
 * <p>A {@code ConcurrentSkipListMap} keyed by the time-sortable message id means arrival order,
 * "newest first" listing and "evict the oldest" are all just navigation on the key order, with no
 * second index to keep consistent under concurrent delivery.
 */
public class InMemoryMailStore implements MailStore {

    private final ConcurrentSkipListMap<String, MailMessage> messages = new ConcurrentSkipListMap<>();
    private final int maxMessages;
    private final MailRemovalListener removalListener;

    public InMemoryMailStore(final int maxMessages, final MailRemovalListener removalListener) {
        this.maxMessages = maxMessages;
        this.removalListener = removalListener;
    }

    @Override
    public void add(final MailMessage message) {
        this.messages.put(message.id(), message);
        this.evictOverflow();
    }

    private void evictOverflow() {
        while (this.messages.size() > this.maxMessages) {
            final Map.Entry<String, MailMessage> oldest = this.messages.pollFirstEntry();
            if (oldest == null) {
                return;
            }
            this.removalListener.onRemoved(oldest.getValue());
        }
    }

    @Override
    public Optional<MailMessage> find(final String id) {
        return Optional.ofNullable(this.messages.get(id));
    }

    @Override
    public List<MailMessage> list(final MailQuery query) {
        return this.matching(query)
                .skip(Math.max(0, query.offset()))
                .limit(query.limit() <= 0 ? Long.MAX_VALUE : query.limit())
                .toList();
    }

    @Override
    public long count(final MailQuery query) {
        return this.matching(query).count();
    }

    @Override
    public long size() {
        return this.messages.size();
    }

    /** Newest first, which is descending key order because ids sort by arrival time. */
    private java.util.stream.Stream<MailMessage> matching(final MailQuery query) {
        return this.messages.descendingMap().values().stream().filter(query::matches);
    }

    @Override
    public List<InboxSummary> inboxes() {
        final Map<String, InboxAccumulator> byAddress = new LinkedHashMap<>();
        for (final MailMessage message : this.messages.values()) {
            for (final String recipient : this.recipientsOf(message)) {
                byAddress.computeIfAbsent(recipient, InboxAccumulator::new).accept(message);
            }
        }
        return byAddress.values().stream()
                .map(InboxAccumulator::toSummary)
                .sorted(Comparator.comparing(InboxSummary::lastReceivedAt).reversed())
                .toList();
    }

    /**
     * An inbox is defined by the SMTP envelope, not by the To/Cc headers, so that the inbox list
     * mirrors where mail was actually delivered. A Bcc recipient appears in no header at all, and a
     * header address that never appeared in a RCPT TO was not delivered to — showing it as an inbox
     * would invent a mailbox that holds nothing.
     */
    private List<String> recipientsOf(final MailMessage message) {
        final List<String> recipients = new ArrayList<>();
        message.envelope().recipients().forEach(r -> recipients.add(r.toLowerCase(Locale.ROOT)));
        return recipients.stream().distinct().toList();
    }

    @Override
    public Optional<MailMessage> delete(final String id) {
        final Optional<MailMessage> removed = Optional.ofNullable(this.messages.remove(id));
        removed.ifPresent(this.removalListener::onRemoved);
        return removed;
    }

    @Override
    public int deleteMatching(final MailQuery query) {
        final List<MailMessage> doomed = this.list(query);
        int deleted = 0;
        for (final MailMessage message : doomed) {
            if (this.delete(message.id()).isPresent()) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public int clear() {
        return this.deleteMatching(new MailQuery(null, null, null, null, null, false, 0, 0));
    }

    /** Mutable tally used while grouping messages into inboxes. */
    private static final class InboxAccumulator {

        private final String address;
        private long messageCount;
        private long failingCount;
        private Instant lastReceivedAt = Instant.EPOCH;

        private InboxAccumulator(final String address) {
            this.address = address;
        }

        private void accept(final MailMessage message) {
            this.messageCount++;
            if (message.validation().errorCount() > 0) {
                this.failingCount++;
            }
            if (message.receivedAt().isAfter(this.lastReceivedAt)) {
                this.lastReceivedAt = message.receivedAt();
            }
        }

        private InboxSummary toSummary() {
            return new InboxSummary(this.address, this.messageCount, this.failingCount, this.lastReceivedAt);
        }
    }
}
