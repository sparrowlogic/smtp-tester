package com.sparrowlogic.smtptester.logging;

import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;

/**
 * The shape of the one-line JSON document written to stdout for every received message.
 *
 * <p>Bodies are truncated and attachment payloads are never included — only a per-part SHA-512 and
 * a count by media type. That keeps the line small enough to ship to a log aggregator while still
 * answering the question the digest is there for: is this byte-for-byte the same attachment the
 * last run produced?
 *
 * @param event always {@code mail.received}, so the line can be selected out of a mixed stream
 * @param id the message id, usable directly against the REST API and the MCP tools
 * @param receivedAt ISO-8601 arrival time
 * @param remoteAddress the delivering peer
 * @param helo the client's EHLO argument
 * @param tls whether the message arrived over STARTTLS
 * @param envelope MAIL FROM and RCPT TO
 * @param headers the addressing headers as sent
 * @param body the decoded bodies, truncated, and their digests
 * @param sizeBytes octets transferred
 * @param sha512 digest of the raw message
 * @param attachments counts by media type plus a digest per part
 * @param validation the RFC compliance verdict
 * @param rejected whether the server refused the message
 */
public record ReceivedMailEvent(
        String event,
        String id,
        String receivedAt,
        String remoteAddress,
        @Nullable String helo,
        boolean tls,
        EnvelopeView envelope,
        HeadersView headers,
        @Nullable BodyView body,
        int sizeBytes,
        String sha512,
        AttachmentsView attachments,
        ValidationView validation,
        boolean rejected) {

    /** MAIL FROM and RCPT TO, as distinct from the headers. */
    public record EnvelopeView(String from, List<String> recipients) {
    }

    /**
     * @param from the From header addresses
     * @param to the To header addresses
     * @param cc the Cc header addresses
     * @param subject the decoded subject
     * @param messageId the Message-ID header
     * @param date the Date header, verbatim
     */
    public record HeadersView(
            List<String> from,
            List<String> to,
            List<String> cc,
            @Nullable String subject,
            @Nullable String messageId,
            @Nullable String date) {
    }

    /**
     * @param text the text/plain body, truncated
     * @param textSha512 digest of the untruncated text body
     * @param html the text/html body, truncated
     * @param htmlSha512 digest of the untruncated HTML body
     * @param truncated whether either body was cut short
     */
    public record BodyView(
            @Nullable String text,
            @Nullable String textSha512,
            @Nullable String html,
            @Nullable String htmlSha512,
            boolean truncated) {
    }

    /**
     * @param count total attachment parts
     * @param countByMimeType how many parts of each media type
     * @param parts one entry per part, with its own digest
     */
    public record AttachmentsView(int count, Map<String, Long> countByMimeType, List<PartView> parts) {
    }

    /**
     * @param index the part's depth-first index, the handle used to download it
     * @param mimeType the media type
     * @param filename the declared filename, when there is one
     * @param sizeBytes decoded size
     * @param sha512 digest of the decoded content
     */
    public record PartView(
            int index,
            String mimeType,
            @Nullable String filename,
            long sizeBytes,
            String sha512) {
    }

    /**
     * @param status PASS, WARN or FAIL
     * @param errors count of ERROR-severity findings
     * @param warnings count of WARNING-severity findings
     * @param findings every finding, flattened for log searching
     */
    public record ValidationView(String status, long errors, long warnings, List<FindingView> findings) {
    }

    /**
     * @param rule the stable rule identifier
     * @param severity ERROR, WARNING or INFO
     * @param summary the one-line problem statement
     * @param detail the offending value, when there is one
     * @param reference the specification section
     */
    public record FindingView(
            String rule,
            String severity,
            String summary,
            @Nullable String detail,
            String reference) {
    }
}
