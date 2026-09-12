package com.sparrowlogic.smtptester;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.validation.MailValidator;
import com.sparrowlogic.smtptester.validation.ValidationReport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Raw message fixtures, written as wire bytes so the transport rules see what they must see. */
public final class MailFixtures {

    public static final String WELL_FORMED = join(
            "From: App <app@example.com>",
            "To: alice@example.com",
            "Subject: Order 4711 shipped",
            "Date: Fri, 11 Sep 2026 12:00:00 +0000",
            "Message-ID: <order-4711@example.com>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=utf-8",
            "",
            "Your order shipped.",
            "");

    /** Bare LF line endings: the single most common real-world transport defect. */
    public static final String BARE_LF = WELL_FORMED.replace("\r\n", "\n");

    public static final String NO_DATE = join(
            "From: app@example.com",
            "To: alice@example.com",
            "Subject: no date",
            "Message-ID: <nodate@example.com>",
            "",
            "body",
            "");

    public static final String DUPLICATE_FROM = join(
            "From: one@example.com",
            "From: two@example.com",
            "To: alice@example.com",
            "Subject: two from headers",
            "Date: Fri, 11 Sep 2026 12:00:00 +0000",
            "Message-ID: <dup@example.com>",
            "",
            "body",
            "");

    public static final String HTML_ONLY = join(
            "From: app@example.com",
            "To: alice@example.com",
            "Subject: html only",
            "Date: Fri, 11 Sep 2026 12:00:00 +0000",
            "Message-ID: <html@example.com>",
            "MIME-Version: 1.0",
            "Content-Type: text/html; charset=utf-8",
            "",
            "<p>hello</p>",
            "");

    public static final String MULTIPART_WITH_ATTACHMENT = join(
            "From: app@example.com",
            "To: alice@example.com",
            "Subject: invoice attached",
            "Date: Fri, 11 Sep 2026 12:00:00 +0000",
            "Message-ID: <invoice@example.com>",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"BOUND\"",
            "",
            "--BOUND",
            "Content-Type: text/plain; charset=utf-8",
            "",
            "See attached.",
            "--BOUND",
            "Content-Type: application/pdf",
            "Content-Transfer-Encoding: base64",
            "Content-Disposition: attachment; filename=\"invoice.pdf\"",
            "",
            "JVBERi0xLjQK",
            "--BOUND--",
            "");

    public static final String UNCLOSED_MULTIPART = join(
            "From: app@example.com",
            "To: alice@example.com",
            "Subject: unterminated",
            "Date: Fri, 11 Sep 2026 12:00:00 +0000",
            "Message-ID: <unterminated@example.com>",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"BOUND\"",
            "",
            "--BOUND",
            "Content-Type: text/plain; charset=utf-8",
            "",
            "no closing delimiter",
            "");

    private MailFixtures() {
    }

    /** Joins lines with CRLF, which is what RFC 5321 requires on the wire. */
    public static String join(final String... lines) {
        return String.join("\r\n", lines);
    }

    public static byte[] bytes(final String message) {
        return message.getBytes(StandardCharsets.UTF_8);
    }

    public static Envelope envelope(final String from, final String... recipients) {
        return new Envelope(from, List.of(recipients), "127.0.0.1:12345", "client.example.com", null, false);
    }

    public static Envelope defaultEnvelope() {
        return envelope("app@example.com", "alice@example.com");
    }

    /** A fully built message, parsed and validated exactly as the SMTP path would build it. */
    public static MailMessage message(final String id, final String raw, final Envelope envelope) {
        final byte[] bytes = bytes(raw);
        final ParsedMessage parsed = new MimeParser().parse(bytes);
        final ValidationReport report = MailValidator.standard()
                .validate(bytes, envelope, parsed, SessionInfo.plaintext());
        return new MailMessage(id, Instant.parse("2026-09-11T12:00:00Z"), envelope, bytes, parsed,
                report, report.errorCount() > 0, SessionInfo.plaintext());
    }

    public static MailMessage message(final String id, final String raw) {
        return message(id, raw, defaultEnvelope());
    }
}
