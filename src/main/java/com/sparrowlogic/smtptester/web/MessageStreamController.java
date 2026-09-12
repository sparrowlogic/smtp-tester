package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.MailQuery;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;

/**
 * A live feed of arriving mail, as server-sent events.
 *
 * <p>This is the half of the API that closes the loop on an integration test. The rest of it
 * answers "what has arrived"; a test that has just triggered a sign-up email needs to know when
 * something arrives, and polling {@code GET /messages} behind a sleep is the workaround this
 * replaces: too short and the test is flaky, too long and the suite crawls.
 *
 * <p>The filters are the ones {@code GET /messages} takes, so a suite running tests in parallel can
 * subscribe with {@code ?inbox=} and see only its own mail.
 */
@RestController
@RequestMapping("${smtp-tester.web.base-path:}/api/v1")
public class MessageStreamController {

    private final MailEventStream stream;
    private final SmtpTesterProperties.Stream settings;

    public MessageStreamController(final MailEventStream stream,
            final SmtpTesterProperties.Stream settings) {
        this.stream = stream;
        this.settings = settings;
    }

    /**
     * Subscribes to matching mail.
     *
     * <p>The stream opens with a {@code ready} event and then carries one {@code mail.received}
     * event per message — the same summary {@code GET /messages} returns — tagged with the message
     * id, so a follow-up call for the full message and a {@code Last-Event-ID} on reconnect both
     * have what they need. A client that waits for {@code ready} before sending cannot miss the
     * message it goes on to send.
     *
     * <p>History is only replayed when asked for, by {@code since} or by the {@code Last-Event-ID}
     * header an {@code EventSource} resends automatically after a dropped connection. Without
     * either, a subscriber gets what arrives from now on, which is what a test that has not sent
     * anything yet wants.
     *
     * @param since replay messages received at or after this ISO-8601 instant, covering mail sent
     *     just before the subscription was opened
     * @param lastEventId sent by the browser's {@code EventSource} on reconnect; replays only what
     *     was missed
     */
    @GetMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @RequestParam(required = false) final @Nullable String inbox,
            @RequestParam(required = false) final @Nullable String from,
            @RequestParam(required = false) final @Nullable String subject,
            @RequestParam(required = false) final @Nullable String text,
            @RequestParam(required = false) final @Nullable String since,
            @RequestParam(defaultValue = "false") final boolean onlyFailed,
            @RequestHeader(name = "Last-Event-ID", required = false)
                    final @Nullable String lastEventId) {
        final Instant replayFrom = RequestQueries.instantOf(since);
        final MailQuery filter = new MailQuery(inbox, from, subject, text, replayFrom, onlyFailed,
                this.settings.replayLimit(), 0);
        final boolean replay = replayFrom != null || lastEventId != null;
        return this.stream.subscribe(filter, lastEventId, replay)
                .map(emitter -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        // Any cache between client and server would defeat the point of a stream.
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(emitter))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
    }
}
