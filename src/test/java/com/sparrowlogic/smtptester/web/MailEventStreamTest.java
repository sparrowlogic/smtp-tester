package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.MailQuery;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the feed the way a test suite would: a real connection held open while mail is delivered.
 *
 * <p>It runs over a real port rather than through MockMvc because the guarantee being made is about
 * a connection that stays open — servlet async, a flushed response, and events that arrive while
 * the request is still in flight. MockMvc completes the async dispatch and hands back a finished
 * body, which would let a stream that never flushed a thing pass every assertion here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MailEventStreamTest {

    private static final String MAIL_EVENT = "mail.received";

    @Autowired
    private MailboxService mailbox;

    @Autowired
    private MailEventStream stream;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        this.mailbox.clearAll();
    }

    @Test
    void deliversAMessageToASubscriberAsItArrives() throws Exception {
        try (SseTestClient client = this.subscribe("")) {
            client.awaitReady();

            final String id = this.receive("alice@example.com");

            final Event event = client.next();
            assertThat(event.name()).isEqualTo(MAIL_EVENT);
            assertThat(event.id()).isEqualTo(id);
            assertThat(event.data())
                    .contains("\"id\":\"" + id + "\"")
                    .contains("Order 4711 shipped")
                    .contains("alice@example.com");
        }
    }

    @Test
    void doesNotDeliverAnotherInboxesMailToAFilteredSubscriber() throws Exception {
        try (SseTestClient client = this.subscribe("?inbox=alice@example.com")) {
            client.awaitReady();

            this.receive("bob@example.com");
            final String wanted = this.receive("alice@example.com");

            final Event event = client.next();
            assertThat(event.id())
                    .as("the first event must be alice's, so bob's was never sent")
                    .isEqualTo(wanted);
            assertThat(event.data()).contains("alice@example.com").doesNotContain("bob@example.com");
        }
    }

    @Test
    void startsFromNowWhenNoStartingPointIsAskedFor() throws Exception {
        final String before = this.receive("alice@example.com");

        try (SseTestClient client = this.subscribe("")) {
            final Event ready = client.awaitReady();
            assertThat(ready.data()).isEqualTo("{\"replayed\":0,\"lastEventId\":null}");

            final String after = this.receive("alice@example.com");
            final Event event = client.next();
            assertThat(event.id()).isEqualTo(after).isNotEqualTo(before);
        }
    }

    @Test
    void replaysMailThatArrivedBeforeTheSubscriptionWhenGivenAStartingInstant() throws Exception {
        final String earlier = this.receive("alice@example.com");

        try (SseTestClient client = this.subscribe("?since=" + Instant.now().minusSeconds(60))) {
            final Event replayed = client.next();
            assertThat(replayed.name()).isEqualTo(MAIL_EVENT);
            assertThat(replayed.id()).isEqualTo(earlier);

            final Event ready = client.awaitReady();
            assertThat(ready.data()).isEqualTo(
                    "{\"replayed\":1,\"lastEventId\":\"" + earlier + "\"}");
        }
    }

    @Test
    void replaysOnlyWhatWasMissedAfterTheLastEventIdOnReconnect() throws Exception {
        final String seen = this.receive("alice@example.com");
        final String missed = this.receive("alice@example.com");

        try (SseTestClient client = this.subscribe("", seen)) {
            final Event replayed = client.next();
            assertThat(replayed.id())
                    .as("only the message newer than the last one the client saw")
                    .isEqualTo(missed);

            final Event ready = client.awaitReady();
            assertThat(ready.data()).isEqualTo(
                    "{\"replayed\":1,\"lastEventId\":\"" + missed + "\"}");
        }
    }

    @Test
    void deliversEachMessageOnceWhenALiveMessageArrivesDuringAReplay() throws Exception {
        final String earlier = this.receive("alice@example.com");

        try (SseTestClient client = this.subscribe("?since=" + Instant.now().minusSeconds(60))) {
            // Racing the replay on purpose: this message is both in the history the subscriber is
            // about to be sent and in the live feed it is already registered for.
            final String during = this.receive("alice@example.com");

            assertThat(client.idsUntilQuiet())
                    .as("each message exactly once, in arrival order")
                    .containsExactly(earlier, during);
        }
    }

    @Test
    void forgetsASubscriberWhoseConnectionHasGoneAway() throws Exception {
        final int before = this.stream.subscriberCount();
        final SseTestClient client = this.subscribe("");
        client.awaitReady();
        assertThat(this.stream.subscriberCount()).isGreaterThan(before);
        client.close();

        // The loss of a client is only discoverable by writing to it, so keep delivering mail
        // until the failed write has retired the subscription.
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    this.receive("alice@example.com");
                    assertThat(this.stream.subscriberCount()).isLessThanOrEqualTo(before);
                });
    }

    @Test
    void answersWithServiceUnavailableWhenTheSubscriberLimitIsReached() {
        final SmtpTesterProperties.Stream full = new SmtpTesterProperties.Stream(
                true, Duration.ofSeconds(20), 0, 100);
        final ObjectMapper json = JsonMapper.builder().build();
        final MailEventStream limited = new MailEventStream(this.mailbox, json, full);
        try {
            assertThat(limited.subscribe(MailQuery.recent(10), null, false)).isEmpty();

            final ResponseEntity<SseEmitter> response = new MessageStreamController(limited, full)
                    .stream(null, null, null, null, null, false, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isNull();
        } finally {
            limited.destroy();
        }
    }

    private String receive(final String recipient) {
        return this.mailbox.receive(MailFixtures.bytes(MailFixtures.WELL_FORMED),
                MailFixtures.envelope("app@example.com", recipient), SessionInfo.plaintext())
                .message().id();
    }

    private SseTestClient subscribe(final String query) throws Exception {
        return this.subscribe(query, null);
    }

    private SseTestClient subscribe(final String query, final @Nullable String lastEventId)
            throws Exception {
        return new SseTestClient(
                URI.create("http://localhost:" + this.port + "/api/v1/messages/stream" + query),
                lastEventId);
    }

    /** One server-sent event, reassembled from its lines. */
    private record Event(@Nullable String name, @Nullable String id, String data) {
    }

    /**
     * A minimal {@code EventSource}: it reads the response body on its own thread so that events
     * can be asserted on while the request is still open, which is the whole point of the feature.
     */
    private static final class SseTestClient implements AutoCloseable {

        private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final HttpClient http = HttpClient.newHttpClient();
        private final HttpResponse<InputStream> response;

        SseTestClient(final URI uri, final @Nullable String lastEventId) throws Exception {
            final HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(10));
            if (lastEventId != null) {
                request.header("Last-Event-ID", lastEventId);
            }
            this.response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(this.response.statusCode()).isEqualTo(200);
            assertThat(this.response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/event-stream");
            final Thread reader = new Thread(this::read, "sse-test-client");
            reader.setDaemon(true);
            reader.start();
        }

        private void read() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(this.response.body(), StandardCharsets.UTF_8))) {
                String name = null;
                String id = null;
                final StringBuilder data = new StringBuilder();
                String line = in.readLine();
                while (line != null) {
                    if (line.isEmpty()) {
                        if (name != null || !data.isEmpty()) {
                            this.events.add(new Event(name, id, data.toString()));
                        }
                        name = null;
                        id = null;
                        data.setLength(0);
                    } else if (line.startsWith("id:")) {
                        id = line.substring("id:".length()).trim();
                    } else if (line.startsWith("event:")) {
                        name = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring("data:".length()).trim());
                    }
                    line = in.readLine();
                }
            } catch (final IOException e) {
                // The stream was closed, which is how this client stops.
            }
        }

        Event next() throws InterruptedException {
            final Event event = this.events.poll(10, TimeUnit.SECONDS);
            assertThat(event).as("an event within ten seconds").isNotNull();
            return event;
        }

        Event awaitReady() throws InterruptedException {
            final Event ready = this.next();
            assertThat(ready.name()).isEqualTo("ready");
            return ready;
        }

        /** Every message id delivered until the stream goes quiet, so duplicates cannot hide. */
        java.util.List<String> idsUntilQuiet() throws InterruptedException {
            final java.util.List<String> ids = new java.util.ArrayList<>();
            Event event = this.events.poll(2, TimeUnit.SECONDS);
            while (event != null) {
                if (MAIL_EVENT.equals(event.name())) {
                    ids.add(event.id());
                }
                event = this.events.poll(500, TimeUnit.MILLISECONDS);
            }
            return ids;
        }

        @Override
        public void close() throws IOException {
            this.response.body().close();
            this.http.shutdownNow();
        }
    }
}
