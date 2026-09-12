package com.sparrowlogic.smtptester.chaos;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import com.sparrowlogic.smtptester.smtp.SmtpReceiver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chaos faults reaching a real SMTP client over a real socket.
 *
 * <p>Each test pins one probability to 1.0 so the fault is certain — a chaos monkey is only
 * testable when you can make it stop being random. That is the same lever a developer uses to
 * reproduce one specific failure rather than waiting for it.
 */
@SpringBootTest
class ChaosDeliveryTest {

    @Autowired
    private SmtpReceiver receiver;

    @Autowired
    private MailboxService mailbox;

    @Autowired
    private ChaosMonkey chaos;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
        this.mailbox.clearAll();
        this.chaos.resetCounters();
    }

    @AfterEach
    void tearDown() {
        this.chaos.reconfigure(ChaosSettings.disabled());
    }

    /** Everything off except the one fault under test. */
    private void onlyFault(final String which) {
        final double on = 1.0;
        final double off = 0.0;
        this.chaos.reconfigure(new ChaosSettings(true,
                "connection".equals(which) ? off : 1.0,
                "disconnect".equals(which) ? on : off,
                "sender".equals(which) ? on : off,
                "recipient".equals(which) ? on : off,
                "auth".equals(which) ? on : off,
                "throttle".equals(which) ? on : off,
                1024, 10240));
    }

    @Test
    void chaosIsOffByDefaultSoOrdinaryDeliveryIsUntouched() throws IOException {
        assertThat(this.chaos.enabled()).isFalse();
        try (Conversation smtp = this.connect()) {
            assertThat(smtp.greeting()).startsWith("220");
            smtp.ehlo();
            assertThat(smtp.command("MAIL FROM:<a@b.c>")).startsWith("250");
            assertThat(smtp.command("RCPT TO:<c@d.e>")).startsWith("250");
        }
    }

    @Test
    void aRefusedConnectionGetsA421BeforeAnyCommand() throws IOException {
        this.onlyFault("connection");
        try (Conversation smtp = this.connect()) {
            assertThat(smtp.greeting()).startsWith("421").contains("Chaos monkey");
        }
        assertThat(this.chaos.injectedFaults()).containsEntry(ChaosAction.REFUSE_CONNECTION, 1L);
    }

    @Test
    void aRejectedSenderGetsATransientFailureSoTheClientCanRetry() throws IOException {
        this.onlyFault("sender");
        try (Conversation smtp = this.connect()) {
            smtp.greeting();
            smtp.ehlo();
            final String reply = smtp.command("MAIL FROM:<a@b.c>");
            assertThat(reply).startsWith("451").contains("4.3.0").contains("sender rejected");
        }
        assertThat(this.chaos.injectedFaults()).containsEntry(ChaosAction.REJECT_SENDER, 1L);
    }

    @Test
    void aRejectedRecipientGetsATransientFailure() throws IOException {
        this.onlyFault("recipient");
        try (Conversation smtp = this.connect()) {
            smtp.greeting();
            smtp.ehlo();
            assertThat(smtp.command("MAIL FROM:<a@b.c>")).startsWith("250");
            assertThat(smtp.command("RCPT TO:<c@d.e>")).startsWith("451").contains("recipient rejected");
        }
        assertThat(this.chaos.injectedFaults()).containsEntry(ChaosAction.REJECT_RECIPIENT, 1L);
    }

    @Test
    void aFailedAuthIsReportedAsAnAuthenticationFailure() throws IOException {
        this.onlyFault("auth");
        try (Conversation smtp = this.connect()) {
            smtp.greeting();
            smtp.ehlo();
            final String token = java.util.Base64.getEncoder()
                    .encodeToString("\0user\0password".getBytes(StandardCharsets.UTF_8));
            assertThat(smtp.command("AUTH PLAIN " + token)).startsWith("535");
        }
        assertThat(this.chaos.injectedFaults()).containsEntry(ChaosAction.REJECT_AUTH, 1L);
    }

    /** The nastiest failure mode: no reply at all, which is what clients hang on. */
    @Test
    void aDisconnectDropsTheSessionWithoutAUsableReply() throws IOException {
        this.onlyFault("disconnect");
        try (Conversation smtp = this.connect()) {
            smtp.greeting();
            smtp.ehlo();
            final String reply = smtp.command("MAIL FROM:<a@b.c>");
            assertThat(reply == null || reply.startsWith("451"))
                    .as("expected a drop or a transient failure, got: %s", reply)
                    .isTrue();
        }
        assertThat(this.chaos.injectedFaults()).containsEntry(ChaosAction.DISCONNECT, 1L);
    }

    /** Throttling must slow the transfer without corrupting it. */
    @Test
    void aThrottledTransferStillArrivesIntact() throws IOException {
        this.chaos.reconfigure(new ChaosSettings(true, 1.0, 0, 0, 0, 0, 1.0, 50_000, 50_000));
        try (Conversation smtp = this.connect()) {
            smtp.greeting();
            smtp.ehlo();
            smtp.command("MAIL FROM:<app@example.com>");
            smtp.command("RCPT TO:<alice@example.com>");
            smtp.command("DATA");
            assertThat(smtp.data(MailFixtures.WELL_FORMED)).startsWith("250");
        }
        assertThat(this.chaos.injectedFaults()).containsEntry(ChaosAction.THROTTLE, 1L);
        assertThat(this.mailbox.size()).isEqualTo(1);
        assertThat(this.mailbox.list(com.sparrowlogic.smtptester.core.MailQuery.recent(1)).getFirst()
                .raw()).isEqualTo(MailFixtures.bytes(MailFixtures.WELL_FORMED));
    }

    @Test
    void theApiReportsAndTogglesChaosAtRuntime() throws Exception {
        this.mvc.perform(get("/api/v1/chaos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.enabled").value(false))
                .andExpect(jsonPath("$.injectedFaults.REJECT_SENDER").value(0));

        this.mvc.perform(post("/api/v1/chaos/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.enabled").value(true));
        assertThat(this.chaos.enabled()).isTrue();

        this.mvc.perform(post("/api/v1/chaos/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.enabled").value(false));
    }

    @Test
    void theApiResetsTheFaultCountersBetweenRuns() throws Exception {
        this.onlyFault("sender");
        this.chaos.rejectSender();
        this.mvc.perform(delete("/api/v1/chaos/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.injectedFaults.REJECT_SENDER").value(0));
    }

    private Conversation connect() throws IOException {
        return new Conversation(this.receiver.port());
    }

    /** A minimal SMTP client that can observe a dropped socket as a null reply. */
    private static final class Conversation implements AutoCloseable {

        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        private Conversation(final int port) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setSoTimeout(10_000);
            this.in = new BufferedReader(
                    new InputStreamReader(this.socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(
                    new OutputStreamWriter(this.socket.getOutputStream(), StandardCharsets.UTF_8), false);
        }

        private String greeting() throws IOException {
            final String line = this.in.readLine();
            return line == null ? "" : line;
        }

        private void ehlo() throws IOException {
            this.send("EHLO test.example.com");
            String line = this.in.readLine();
            while (line != null && line.length() >= 4 && line.charAt(3) == '-') {
                line = this.in.readLine();
            }
        }

        private String command(final String command) throws IOException {
            this.send(command);
            return this.in.readLine();
        }

        private String data(final String raw) throws IOException {
            final String terminator = raw.endsWith("\r\n") ? ".\r\n" : "\r\n.\r\n";
            this.out.print(raw + terminator);
            this.out.flush();
            return this.in.readLine();
        }

        private void send(final String command) {
            this.out.print(command + "\r\n");
            this.out.flush();
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
        }
    }
}
