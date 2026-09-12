package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that {@code max-message-size-bytes} bounds an actual transfer, not just the greeting.
 *
 * <p>The library enforces the limit only against a {@code SIZE=} parameter the client volunteered
 * on {@code MAIL FROM}. Most clients volunteer nothing, so without the check in
 * {@code IngestMessageHandlerFactory} a message of any size at all would be accepted, held in
 * memory and retained — and the documented ceiling on this server's heap, which is this limit times
 * {@code store.max-messages}, would mean nothing.
 *
 * <p>A small limit is configured here rather than sending 25 MB at the default.
 */
@SpringBootTest(properties = "smtp-tester.smtp.max-message-size-bytes=2048")
class SmtpMessageSizeTest {

    private static final int LIMIT = 2048;

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

    @Test
    void aTransferLargerThanTheLimitIsRefusedEvenWhenTheClientDeclaredNoSize() throws IOException {
        final String reply = this.deliver(oversized());

        assertThat(reply).startsWith("552 5.3.4").contains(String.valueOf(LIMIT));
        assertThat(this.mailbox.list(MailQuery.recent(10)))
                .as("refusing it and then keeping it would defeat the point of the limit")
                .isEmpty();
    }

    @Test
    void aTransferWithinTheLimitIsAccepted() throws IOException {
        assertThat(this.deliver(MailFixtures.WELL_FORMED)).startsWith("250");
        assertThat(this.mailbox.list(MailQuery.recent(10))).hasSize(1);
    }

    /**
     * The rest of an oversized transfer still has to be read off the socket before the refusal, or
     * the remainder is taken for SMTP commands and every later reply is answering the wrong one.
     */
    @Test
    void theConnectionStaysUsableAfterAnOversizedTransfer() throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            smtp.ehlo();
            smtp.command("MAIL FROM:<big@example.com>");
            smtp.command("RCPT TO:<big-rcpt@example.com>");
            smtp.command("DATA");
            assertThat(smtp.data(oversized())).startsWith("552");

            assertThat(smtp.command("MAIL FROM:<next@example.com>")).startsWith("250");
            assertThat(smtp.command("RCPT TO:<next-rcpt@example.com>")).startsWith("250");
            smtp.command("DATA");
            assertThat(smtp.data(MailFixtures.WELL_FORMED)).startsWith("250");
        }

        final MailMessage stored = this.mailbox.list(MailQuery.recent(10)).getFirst();
        assertThat(this.mailbox.list(MailQuery.recent(10))).hasSize(1);
        assertThat(stored.envelope().from()).isEqualTo("next@example.com");
        assertThat(stored.envelope().recipients()).containsExactly("next-rcpt@example.com");
    }

    /** The greeting has to carry the same number, so a client can decide before it sends. */
    @Test
    void theLimitIsAdvertisedAndADeclaredOversizeIsRefusedUpFront() throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            assertThat(smtp.ehlo()).contains("SIZE " + LIMIT);
            assertThat(smtp.command("MAIL FROM:<big@example.com> SIZE=999999")).startsWith("552");
        }
    }

    /** Well over the limit, in short lines so it is the size that is refused and not the lines. */
    private static String oversized() {
        final StringBuilder body = new StringBuilder(LIMIT * 2);
        while (body.length() < LIMIT * 2) {
            body.append("x".repeat(70)).append("\r\n");
        }
        return MailFixtures.join("From: big@example.com", "To: big-rcpt@example.com",
                "Subject: oversized", "Date: Fri, 11 Sep 2026 12:00:00 +0000",
                "Message-ID: <oversized@example.com>", "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=utf-8", "", body.toString());
    }

    private String deliver(final String raw) throws IOException {
        try (SmtpConversation smtp = new SmtpConversation(this.port)) {
            smtp.readGreeting();
            smtp.ehlo();
            smtp.command("MAIL FROM:<big@example.com>");
            smtp.command("RCPT TO:<big-rcpt@example.com>");
            smtp.command("DATA");
            return smtp.data(raw);
        }
    }
}
