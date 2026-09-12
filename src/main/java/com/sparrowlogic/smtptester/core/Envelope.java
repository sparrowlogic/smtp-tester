package com.sparrowlogic.smtptester.core;

import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * The SMTP-level envelope, i.e. what the client said in {@code MAIL FROM} / {@code RCPT TO},
 * as opposed to what the {@code From:} / {@code To:} headers inside the message claim.
 *
 * <p>Keeping the two separate is the point: a very common real-world bug is an envelope sender
 * that does not align with the header {@code From:}, which breaks SPF/DMARC. The validator
 * reports on that, so the envelope must survive alongside the message body.
 *
 * @param from the reverse-path; empty string for a null sender ({@code MAIL FROM:<>}), as used by bounces
 * @param recipients every accepted forward-path, in the order the client sent them
 * @param remoteAddress the connecting peer, for debugging which service sent the mail
 * @param helo the argument of {@code HELO}/{@code EHLO}, when the client sent one
 * @param authenticatedUser the SASL username, when the client authenticated
 * @param tls whether the message body was transferred inside STARTTLS
 */
public record Envelope(
        String from,
        List<String> recipients,
        String remoteAddress,
        @Nullable String helo,
        @Nullable String authenticatedUser,
        boolean tls) {

    public Envelope {
        recipients = List.copyOf(recipients);
    }

    /** True for {@code MAIL FROM:<>}, the null reverse-path reserved for bounce messages. */
    public boolean nullSender() {
        return this.from.isEmpty();
    }
}
