package com.sparrowlogic.smtptester.core;

import com.sparrowlogic.smtptester.validation.ValidationReport;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

/**
 * One received message: the exact bytes, the envelope they arrived in, the decoded view, and the
 * RFC compliance verdict.
 *
 * @param id time-sortable identifier, also the handle used by the UI, the REST API and MCP
 * @param receivedAt when the {@code DATA} command completed
 * @param envelope the SMTP-level envelope
 * @param raw the message exactly as it came off the wire, with no {@code Received:} header added
 * @param parsed the decoded view
 * @param validation the RFC compliance findings
 * @param rejected whether the server answered {@code DATA} with a permanent failure; rejected mail
 *     is still retained, because a message you cannot look at is a message you cannot debug
 * @param session TLS and authentication facts about the connection that delivered it
 */
public record MailMessage(
        String id,
        Instant receivedAt,
        Envelope envelope,
        byte[] raw,
        ParsedMessage parsed,
        ValidationReport validation,
        boolean rejected,
        @Nullable SessionInfo session) {

    /** Size of the message as transferred, in octets. */
    public int sizeBytes() {
        return this.raw.length;
    }

    /** SHA-512 of the raw message, so two captures can be compared without diffing bytes. */
    public String sha512() {
        return Digests.sha512(this.raw);
    }

    /** A one-line subject for list views, never null. */
    public String displaySubject() {
        final String subject = this.parsed.subject();
        return subject == null || subject.isBlank() ? "(no subject)" : subject;
    }

    /** The best available sender label: the header {@code From:} if present, else the envelope. */
    public String displayFrom() {
        return this.parsed.from().isEmpty() ? this.envelope.from() : this.parsed.from().getFirst();
    }
}
