package com.sparrowlogic.smtptester.core;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The decoded view of a message: headers and bodies as a human (or an LLM) wants to read them.
 *
 * <p>This is derived data. The raw bytes on {@link MailMessage} remain the source of truth, and
 * anything that must be byte-exact reads those instead.
 *
 * @param headers every header in wire order, as name/value pairs; repeated names appear repeatedly
 * @param from addresses from the {@code From:} header
 * @param to addresses from the {@code To:} header
 * @param cc addresses from the {@code Cc:} header
 * @param bcc addresses from a {@code Bcc:} header, which a correct sender should have stripped
 * @param replyTo addresses from the {@code Reply-To:} header
 * @param subject the decoded subject
 * @param sentAt the parsed {@code Date:} header
 * @param messageId the {@code Message-ID:} header
 * @param text the {@code text/plain} body
 * @param html the {@code text/html} body
 * @param attachments metadata for every non-body leaf part
 * @param parseError the failure message when the bytes could not be parsed as MIME at all
 */
public record ParsedMessage(
        List<Header> headers,
        List<String> from,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        List<String> replyTo,
        @Nullable String subject,
        @Nullable Instant sentAt,
        @Nullable String messageId,
        @Nullable String text,
        @Nullable String html,
        List<AttachmentPart> attachments,
        @Nullable String parseError) {

    public ParsedMessage {
        headers = List.copyOf(headers);
        from = List.copyOf(from);
        to = List.copyOf(to);
        cc = List.copyOf(cc);
        bcc = List.copyOf(bcc);
        replyTo = List.copyOf(replyTo);
        attachments = List.copyOf(attachments);
    }

    /** The first value of the named header, case-insensitively, or null when absent. */
    public @Nullable String header(final String name) {
        return this.headers.stream()
                .filter(h -> h.name().equalsIgnoreCase(name))
                .map(Header::value)
                .findFirst()
                .orElse(null);
    }

    /** How many times the named header occurs, which is how the RFC 5322 rules spot duplicates. */
    public long headerCount(final String name) {
        return this.headers.stream().filter(h -> h.name().equalsIgnoreCase(name)).count();
    }

    /** Every recipient across {@code To:}, {@code Cc:} and {@code Bcc:}. */
    public List<String> allRecipients() {
        return java.util.stream.Stream.of(this.to, this.cc, this.bcc)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    /** Attachment counts keyed by media type, which is what the structured log line reports. */
    public Map<String, Long> attachmentCountsByMimeType() {
        return this.attachments.stream().collect(java.util.stream.Collectors.groupingBy(
                AttachmentPart::mimeType, java.util.TreeMap::new, java.util.stream.Collectors.counting()));
    }

    /** One header occurrence, preserving wire order and duplicates. */
    public record Header(String name, String value) {
    }
}
