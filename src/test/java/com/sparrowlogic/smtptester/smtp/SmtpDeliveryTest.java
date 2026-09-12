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

import java.io.IOException;
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

    /**
     * RFC 5321 section 4.1.1.4 requires the transaction to be cleared once the end of mail data is
     * received "whether or not the mail transaction was successful". Rejecting is this server's
     * normal behaviour, so a connection stranded in the refused transaction would answer the next
     * MAIL FROM with 503 and then, for a client that carried on, store the following message under
     * the previous sender with both recipient lists merged — and acknowledge it with a 250.
     */
    @Test
    void aRefusedMessageLeavesTheConnectionReadyForTheNextTransaction() throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            smtp.ehlo();
            smtp.command("MAIL FROM:<first@example.com>");
            smtp.command("RCPT TO:<first-rcpt@example.com>");
            smtp.command("DATA");
            assertThat(smtp.data(MailFixtures.NO_DATE)).startsWith("550");

            assertThat(smtp.command("MAIL FROM:<second@example.com>"))
                    .as("the refused transaction is over, so a new sender is not a duplicate")
                    .startsWith("250");
            assertThat(smtp.command("RCPT TO:<second-rcpt@example.com>")).startsWith("250");
            smtp.command("DATA");
            assertThat(smtp.data(MailFixtures.WELL_FORMED)).startsWith("250");
        }

        final MailMessage stored = this.mailbox.list(MailQuery.recent(1)).getFirst();
        assertThat(stored.envelope().from()).isEqualTo("second@example.com");
        assertThat(stored.envelope().recipients()).containsExactly("second-rcpt@example.com");
        assertThat(stored.rejected()).isFalse();
    }

    /** The same guarantee over BDAT, which the greeting advertises through CHUNKING. */
    @Test
    void aRefusedChunkedMessageLeavesTheConnectionReadyForTheNextTransaction() throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            smtp.ehlo();
            smtp.command("MAIL FROM:<first@example.com>");
            smtp.command("RCPT TO:<first-rcpt@example.com>");
            assertThat(smtp.bdat(MailFixtures.NO_DATE)).startsWith("550");

            assertThat(smtp.command("MAIL FROM:<second@example.com>")).startsWith("250");
            assertThat(smtp.command("RCPT TO:<second-rcpt@example.com>")).startsWith("250");
            assertThat(smtp.bdat(MailFixtures.WELL_FORMED)).startsWith("250");
        }

        final MailMessage stored = this.mailbox.list(MailQuery.recent(1)).getFirst();
        assertThat(stored.envelope().from()).isEqualTo("second@example.com");
        assertThat(stored.envelope().recipients()).containsExactly("second-rcpt@example.com");
    }

    /**
     * DATA without a recipient is refused but does not end the transaction, so the client may
     * still send its RCPT TO. This is the case the reset must not touch.
     */
    @Test
    void dataWithNoRecipientLeavesTheTransactionOpenForTheRecipientToFollow() throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            smtp.ehlo();
            smtp.command("MAIL FROM:<app@example.com>");
            assertThat(smtp.command("DATA")).startsWith("503");
            assertThat(smtp.command("RCPT TO:<alice@example.com>"))
                    .as("the sender is still on record, so the recipient is accepted")
                    .startsWith("250");
            smtp.command("DATA");
            assertThat(smtp.data(MailFixtures.WELL_FORMED)).startsWith("250");
        }
        assertThat(this.mailbox.list(MailQuery.recent(1)).getFirst().envelope().from())
                .isEqualTo("app@example.com");
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
}
