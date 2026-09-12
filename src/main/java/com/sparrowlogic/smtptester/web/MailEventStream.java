package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.MailArrivalListener;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import com.sparrowlogic.smtptester.mcp.McpViews;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fans arriving mail out to server-sent-events subscribers.
 *
 * <p>The point of the feed is to let an integration test stop guessing. Instead of sending a
 * message and then polling the inbox behind a sleep, a test subscribes, waits for the {@code ready}
 * event, sends, and blocks on the next {@code mail.received} — so "the mail never arrived" and "the
 * mail had not arrived yet" stop looking the same.
 *
 * <p>Two properties make that work, and both are why this class is more than a list of emitters:
 *
 * <ul>
 *   <li><strong>No gap.</strong> A subscriber is registered for live delivery <em>before</em>
 *       anything is written to it, so a message that arrives during the handshake or the replay is
 *       still delivered rather than falling between the two.
 *   <li><strong>No duplicate.</strong> Registering first means the live feed and a replay can both
 *       reach for the same message, so each subscriber tracks what it has already been sent.
 * </ul>
 *
 * <p>Every write happens on this class's own single thread. That keeps ingestion free of a slow
 * consumer's socket — an SMTP session must never block behind a browser tab someone left open —
 * and it is what makes the ordering above decidable at all: replay and live delivery cannot
 * interleave when there is only one writer.
 */
public class MailEventStream implements MailArrivalListener, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(MailEventStream.class);

    /** Matches the {@code event} field of the structured log line, so one name means one thing. */
    private static final String MAIL_EVENT = "mail.received";

    /** Written once the subscriber is live and any replay has been flushed. */
    private static final String READY_EVENT = "ready";

    /**
     * Never time the request out. An SSE subscription that is merely waiting is doing its job, and
     * the container's async default (30 seconds on Tomcat) would end it silently. The alternative,
     * {@code spring.mvc.async.request-timeout}, is global: it would also change the timeout of every
     * other endpoint, which is a much larger thing to do than fixing it on the one request here.
     */
    private static final long NO_TIMEOUT = 0L;

    /**
     * Undelivered events held for a stalled subscriber before new ones are dropped.
     *
     * <p>A subscriber whose TCP window has filled blocks the dispatch thread, and mail keeps
     * arriving; without a ceiling the queue would grow until the heap gave out, each entry pinning
     * a message's raw bytes. Dropping is the right failure: the inbox still holds everything, and
     * the alternative is taking the whole server down on behalf of one dead client.
     */
    private static final int MAX_PENDING_EVENTS = 1000;

    private final MailboxService mailbox;
    private final ObjectMapper json;
    private final SmtpTesterProperties.Stream settings;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final ScheduledExecutorService dispatcher;

    public MailEventStream(final MailboxService mailbox, final ObjectMapper json,
            final SmtpTesterProperties.Stream settings) {
        this.mailbox = mailbox;
        this.json = json;
        this.settings = settings;
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("smtp-tester-mail-stream").factory());
        final long heartbeat = Math.max(1L, settings.heartbeat().toMillis());
        this.dispatcher.scheduleWithFixedDelay(this::heartbeat, heartbeat, heartbeat,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a stream, or returns empty when the subscriber limit is already reached.
     *
     * @param filter which messages this subscriber wants, using the same fields as {@code
     *     GET /messages}; its limit caps how much history a replay may send
     * @param afterId deliver only messages newer than this id, from a {@code Last-Event-ID} header
     * @param replay whether to write matching history before going live
     */
    public Optional<SseEmitter> subscribe(final MailQuery filter, final @Nullable String afterId,
            final boolean replay) {
        if (this.subscriptions.size() >= this.settings.maxSubscribers()) {
            return Optional.empty();
        }
        final SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        final Subscription subscription = new Subscription(emitter, filter);
        emitter.onCompletion(() -> this.subscriptions.remove(subscription));
        emitter.onTimeout(() -> this.subscriptions.remove(subscription));
        emitter.onError(error -> this.subscriptions.remove(subscription));
        this.subscriptions.add(subscription);
        this.enqueue(() -> this.open(subscription, afterId, replay));
        return Optional.of(emitter);
    }

    /** How many subscribers are currently connected. */
    public int subscriberCount() {
        return this.subscriptions.size();
    }

    @Override
    public void onReceived(final MailMessage message) {
        this.submit(() -> this.broadcast(message));
    }

    @Override
    public void destroy() {
        this.dispatcher.shutdownNow();
        this.subscriptions.forEach(subscription -> subscription.emitter.complete());
        this.subscriptions.clear();
    }

    /** Hands a message to the dispatch thread, dropping it rather than growing without bound. */
    private void submit(final Runnable work) {
        if (this.subscriptions.isEmpty()) {
            return;
        }
        if (this.pending.get() >= MAX_PENDING_EVENTS) {
            LOG.warn("Dropping a mail stream event: {} are already queued behind a stalled "
                    + "subscriber", MAX_PENDING_EVENTS);
        } else {
            this.enqueue(work);
        }
    }

    /**
     * Queues work unconditionally, which is what opening a subscription needs: a handshake dropped
     * for being behind a stalled subscriber would leave the new client waiting for a {@code ready}
     * event that never comes.
     *
     * <p>{@link RejectedExecutionException} is expected during shutdown and means only that nobody
     * is listening any more.
     */
    private void enqueue(final Runnable work) {
        this.pending.incrementAndGet();
        try {
            this.dispatcher.execute(() -> {
                this.pending.decrementAndGet();
                work.run();
            });
        } catch (final RejectedExecutionException e) {
            this.pending.decrementAndGet();
        }
    }

    /** Writes the replay, if any, then the handshake that tells the client it is caught up. */
    private void open(final Subscription subscription, final @Nullable String afterId,
            final boolean replay) {
        int replayed = 0;
        if (replay) {
            for (final MailMessage message : this.history(subscription.filter, afterId)) {
                if (subscription.accepts(message)) {
                    this.write(subscription, this.mailEvent(message));
                    replayed++;
                }
            }
        }
        subscription.replayFinished();
        this.write(subscription, SseEmitter.event().name(READY_EVENT)
                .data(this.serialise(new Ready(replayed, subscription.watermarkOrNull()))));
    }

    /** Matching history oldest-first, so the client reads the stream in arrival order throughout. */
    private List<MailMessage> history(final MailQuery filter, final @Nullable String afterId) {
        final List<MailMessage> oldestFirst = this.mailbox.list(filter).reversed();
        if (afterId == null) {
            return oldestFirst;
        }
        return oldestFirst.stream().filter(message -> message.id().compareTo(afterId) > 0).toList();
    }

    private void broadcast(final MailMessage message) {
        for (final Subscription subscription : this.subscriptions) {
            if (subscription.accepts(message)) {
                this.write(subscription, this.mailEvent(message));
            }
        }
    }

    /**
     * A keep-alive comment, which carries no data and so cannot be mistaken for a message.
     *
     * <p>Without it an idle subscription looks indistinguishable from a hung one to any proxy
     * between the two, and a dropped connection is only noticed on the next write — which for a
     * test waiting on mail that never comes could be never.
     */
    private void heartbeat() {
        this.subscriptions.forEach(
                subscription -> this.write(subscription, SseEmitter.event().comment("keep-alive")));
    }

    private SseEmitter.SseEventBuilder mailEvent(final MailMessage message) {
        return SseEmitter.event()
                .id(message.id())
                .name(MAIL_EVENT)
                .data(this.serialise(McpViews.EmailSummary.of(message)));
    }

    /**
     * Writes one event, forgetting the subscriber if the write fails.
     *
     * <p>A closed browser tab surfaces as an {@link IOException} here and an already-completed
     * async request as an {@link IllegalStateException}; neither is an error worth logging, and in
     * both cases the container has already dealt with the response, so the emitter is simply
     * dropped rather than completed again.
     */
    private void write(final Subscription subscription, final SseEmitter.SseEventBuilder event) {
        try {
            subscription.emitter.send(event);
        } catch (final IOException | IllegalStateException e) {
            this.subscriptions.remove(subscription);
        }
    }

    private String serialise(final Object value) {
        try {
            return this.json.writeValueAsString(value);
        } catch (final JacksonException e) {
            LOG.warn("Could not serialise a mail stream event", e);
            return "{}";
        }
    }

    /**
     * The handshake event.
     *
     * <p>It is what makes the subscribe-then-send order safe to rely on: a client that waits for
     * this before sending knows the subscription is live, so the message it is about to send cannot
     * be missed.
     *
     * @param replayed how many historical messages were written before this event
     * @param lastEventId the newest id delivered so far, to resume from after a disconnect
     */
    public record Ready(int replayed, @Nullable String lastEventId) {
    }

    /**
     * One connected subscriber.
     *
     * <p>Every field is read and written on the dispatch thread only; publication to it is safe
     * through the queue the work is submitted on.
     */
    private static final class Subscription {

        private final SseEmitter emitter;
        private final MailQuery filter;

        /**
         * Ids already sent, kept only until the replay has been written.
         *
         * <p>During that window the live feed and the replay can both offer the same message, and
         * neither order of the two is guaranteed, so exact bookkeeping is the only thing that
         * answers "has this one gone out". Afterwards ids only ever increase, so the highest one
         * sent is enough and the set is released.
         */
        private @Nullable Set<String> replayWindow = new HashSet<>();

        private String watermark = "";

        private Subscription(final SseEmitter emitter, final MailQuery filter) {
            this.emitter = emitter;
            this.filter = filter;
        }

        private boolean accepts(final MailMessage message) {
            if (!this.filter.matches(message)) {
                return false;
            }
            return this.notYetSent(message.id());
        }

        private boolean notYetSent(final String id) {
            final Set<String> window = this.replayWindow;
            final boolean fresh =
                    window != null ? window.add(id) : id.compareTo(this.watermark) > 0;
            if (fresh && id.compareTo(this.watermark) > 0) {
                this.watermark = id;
            }
            return fresh;
        }

        private void replayFinished() {
            this.replayWindow = null;
        }

        private @Nullable String watermarkOrNull() {
            return this.watermark.isEmpty() ? null : this.watermark;
        }
    }
}
