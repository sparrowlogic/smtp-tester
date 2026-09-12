package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real listening socket. Everything below this test is unit-tested in isolation; this
 * is the one that proves an ordinary SMTP client can actually deliver mail to the server, which is
 * the completion criterion that matters most.
 */
@SpringBootTest
class SmtpDeliveryTest {

    @Autowired
    private SmtpReceiver receiver;

    @Autowired
    private MailboxService mailbox;

    private int port;

    @BeforeEach
    void setUp() {
        this.mailbox.clearAll();
        this.port = this.receiver.port();
    }

    private Session session() {
        final Properties properties = new Properties();
        properties.put("mail.smtp.host", "127.0.0.1");
        properties.put("mail.smtp.port", String.valueOf(this.port));
        return Session.getInstance(properties);
    }

    @Test
    void theListenerBindsAnEphemeralPortWhenConfiguredWithZero() {
        assertThat(this.port).isPositive();
        assertThat(this.receiver.isRunning()).isTrue();
    }

    @Test
    void aStandardClientCanDeliverAMultipartMessageWithAnAttachment() throws Exception {
        final MimeMessage message = new MimeMessage(this.session());
        message.setFrom(new InternetAddress("app@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO,
                InternetAddress.parse("alice@example.com"));
        message.setSubject("Order 4711 shipped");
        message.setSentDate(new Date());

        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Your order shipped.", "UTF-8");
        final MimeBodyPart attachment = new MimeBodyPart();
        attachment.setContent("%PDF-1.4".getBytes(StandardCharsets.UTF_8), "application/pdf");
        attachment.setFileName("invoice.pdf");
        final MimeMultipart multipart = new MimeMultipart("mixed");
        multipart.addBodyPart(body);
        multipart.addBodyPart(attachment);
        message.setContent(multipart);

        Transport.send(message);

        final List<MailMessage> received = this.mailbox.list(MailQuery.recent(10));
        assertThat(received).singleElement().satisfies(stored -> {
            assertThat(stored.envelope().from()).isEqualTo("app@example.com");
            assertThat(stored.envelope().recipients()).containsExactly("alice@example.com");
            assertThat(stored.displaySubject()).isEqualTo("Order 4711 shipped");
            assertThat(stored.parsed().text()).contains("Your order shipped.");
            assertThat(stored.parsed().attachments()).singleElement()
                    .satisfies(part -> assertThat(part.filename()).isEqualTo("invoice.pdf"));
            assertThat(stored.rejected()).isFalse();
        });
    }

    /** One DATA command must produce one stored message, however many recipients it had. */
    @Test
    void aMessageWithSeveralRecipientsIsStoredOnce() throws Exception {
        final MimeMessage message = new MimeMessage(this.session());
        message.setFrom(new InternetAddress("app@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO,
                InternetAddress.parse("alice@example.com,bob@example.com"));
        message.setSubject("to two people");
        message.setSentDate(new Date());
        message.setText("hello");

        Transport.send(message);

        assertThat(this.mailbox.size()).isEqualTo(1);
        assertThat(this.mailbox.list(MailQuery.recent(10)).getFirst().envelope().recipients())
                .containsExactly("alice@example.com", "bob@example.com");
        assertThat(this.mailbox.inboxes()).extracting(i -> i.address())
                .contains("alice@example.com", "bob@example.com");
    }

    @Test
    void theGreetingAdvertisesEnhancedStatusCodesAsRfc2034Requires() throws IOException {
        assertThat(this.ehlo()).contains("ENHANCEDSTATUSCODES").contains("8BITMIME").contains("SIZE");
    }

    @Test
    void aMessageMissingTheDateHeaderIsRejectedWithARegisteredStatusCode() throws IOException {
        final String reply = this.deliver(MailFixtures.NO_DATE);
        assertThat(reply).startsWith("550 5.6.0").contains("RFC5322_MISSING_DATE");
    }

    /** Rejected mail must still be inspectable, otherwise the rejection cannot be debugged. */
    @Test
    void aRejectedMessageIsStillStoredAndFlagged() throws IOException {
        this.deliver(MailFixtures.NO_DATE);
        assertThat(this.mailbox.list(MailQuery.recent(10))).singleElement().satisfies(stored -> {
            assertThat(stored.rejected()).isTrue();
            assertThat(stored.validation().findings()).extracting(f -> f.rule())
                    .contains("RFC5322_MISSING_DATE");
        });
    }

    @Test
    void aCompliantMessageIsAcknowledgedWithItsIdSoTheClientCanFetchItBack() throws IOException {
        final String reply = this.deliver(MailFixtures.WELL_FORMED);
        assertThat(reply).startsWith("250 2.0.0 Ok: queued as ");
        final String id = reply.substring(reply.lastIndexOf(' ') + 1);
        assertThat(this.mailbox.find(id)).isPresent();
    }

    /**
     * Bare LF has to be caught on the real socket, not just over a byte array. The rule reads raw
     * octets precisely because a MIME parser would normalise them away, and this asserts the SMTP
     * library does not normalise them first either — if it did, the rule could never fire in
     * production and the claim that this server catches the defect would be false.
     */
    @Test
    void bareLineFeedsSurviveTheWireAndAreRejected() throws IOException {
        final String bareLf = MailFixtures.WELL_FORMED.replace("\r\n", "\n");
        final String reply = this.deliver(bareLf);
        assertThat(reply).startsWith("550 5.6.0").contains("RFC5321_BARE_LF");
        assertThat(this.mailbox.list(MailQuery.recent(1)).getFirst().validation().findings())
                .extracting(f -> f.rule())
                .contains("RFC5321_BARE_LF");
    }

    /**
     * SMTPUTF8 is deliberately not advertised: this server reports raw 8-bit header octets as an
     * RFC 5322 violation, and offering an extension whose whole purpose is to permit them would
     * leave a client unable to tell its own encoding bug from a server bug.
     */
    @Test
    void theGreetingDoesNotOfferSmtpUtf8GivenEightBitHeadersAreRejected() throws IOException {
        assertThat(this.ehlo()).doesNotContain("SMTPUTF8");
        final String raw = MailFixtures.join("From: a@b.c", "To: c@d.e",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <u@b.c>",
                "Subject: caf\u00e9 latte", "", "body", "");
        assertThat(this.deliver(raw)).contains("5.6.7").contains("RFC5322_NON_ASCII_HEADER");
    }

    /** The stored bytes must equal the transmitted bytes: no Received: header is inserted. */
    @Test
    void theStoredMessageIsByteForByteWhatWasTransmitted() throws IOException {
        this.deliver(MailFixtures.WELL_FORMED);
        final MailMessage stored = this.mailbox.list(MailQuery.recent(1)).getFirst();
        assertThat(stored.raw()).isEqualTo(MailFixtures.bytes(MailFixtures.WELL_FORMED));
        assertThat(new String(stored.raw(), StandardCharsets.UTF_8)).doesNotContain("Received:");
    }

    @Test
    void anyCredentialsAreAcceptedSoExistingApplicationConfigsNeedNoChanges() throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            smtp.ehlo();
            // "anyuser" / "anypassword" as a single base64 PLAIN token.
            final String token = java.util.Base64.getEncoder().encodeToString(
                    "\0anyuser\0anypassword".getBytes(StandardCharsets.UTF_8));
            assertThat(smtp.command("AUTH PLAIN " + token)).startsWith("235");
        }
    }

    private String ehlo() throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            return smtp.ehlo();
        }
    }

    private String deliver(final String raw) throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            smtp.ehlo();
            smtp.command("MAIL FROM:<app@example.com>");
            smtp.command("RCPT TO:<alice@example.com>");
            smtp.command("DATA");
            return smtp.data(raw);
        }
    }

    /** A minimal SMTP client, so the tests can assert on exact reply lines. */
    private static final class SmtpConversation implements AutoCloseable {

        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        private SmtpConversation(final int port) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setSoTimeout(10_000);
            this.in = new BufferedReader(
                    new InputStreamReader(this.socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(
                    new OutputStreamWriter(this.socket.getOutputStream()), false);
        }

        private void readGreeting() throws IOException {
            this.in.readLine();
        }

        private String ehlo() throws IOException {
            this.send("EHLO test.example.com");
            final StringBuilder response = new StringBuilder();
            String line = this.in.readLine();
            while (line != null) {
                response.append(line).append('\n');
                if (line.length() < 4 || line.charAt(3) != '-') {
                    break;
                }
                line = this.in.readLine();
            }
            return response.toString();
        }

        private String command(final String command) throws IOException {
            this.send(command);
            return this.in.readLine();
        }

        private String data(final String raw) throws IOException {
            // RFC 5321 section 4.1.1.4: the terminator is CRLF.CRLF, so a message that already ends
            // with CRLF needs only ".CRLF" appended. Adding another CRLF would append a blank line
            // to the message body -- which the server would then faithfully store, as it should.
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
            this.send("QUIT");
            this.socket.close();
        }
    }

    /** Kept local so the conversation writes raw bytes without platform line-ending surprises. */
    private static final class OutputStreamWriter extends java.io.OutputStreamWriter {

        private OutputStreamWriter(final OutputStream out) {
            super(out, StandardCharsets.UTF_8);
        }
    }
}
