package com.sparrowlogic.smtptester.mcp;

import com.sparrowlogic.smtptester.core.AttachmentPart;
import com.sparrowlogic.smtptester.core.InboxSummary;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.validation.Finding;
import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * The shapes returned to an MCP client.
 *
 * <p>These are deliberately not the internal records. A tool result is read by a model with a
 * finite context window, so the list view carries only what is needed to choose a message, and the
 * detail view drops the raw bytes unless they were asked for. Field names are the model's only
 * documentation once the schema is generated, so they spell things out.
 */
public final class McpViews {

    private McpViews() {
    }

    /**
     * One line in a listing.
     *
     * @param id the handle to pass to get_email, delete_email and the REST API
     * @param receivedAt ISO-8601 time the server accepted the message
     * @param from the sender, taken from the From header when present, otherwise the SMTP envelope
     * @param to every envelope recipient
     * @param subject the decoded subject, or "(no subject)"
     * @param sizeBytes octets transferred
     * @param attachmentCount how many non-body MIME parts the message carries
     * @param validationStatus PASS, WARN or FAIL against the RFC rule set
     * @param rejected true when the server answered the SMTP DATA command with a failure
     */
    public record EmailSummary(
            String id,
            String receivedAt,
            String from,
            List<String> to,
            String subject,
            int sizeBytes,
            int attachmentCount,
            String validationStatus,
            boolean rejected) {

        public static EmailSummary of(final MailMessage message) {
            return new EmailSummary(
                    message.id(),
                    message.receivedAt().toString(),
                    message.displayFrom(),
                    message.envelope().recipients(),
                    message.displaySubject(),
                    message.sizeBytes(),
                    message.parsed().attachments().size(),
                    message.validation().status().name(),
                    message.rejected());
        }
    }

    /**
     * A full message.
     *
     * @param summary the same fields as the list view
     * @param envelopeFrom the SMTP MAIL FROM reverse-path, which may differ from the From header
     * @param headerFrom addresses from the From header
     * @param headerTo addresses from the To header
     * @param headerCc addresses from the Cc header
     * @param messageId the Message-ID header
     * @param date the Date header as sent
     * @param text the plain-text body, truncated when very long
     * @param html the HTML body, omitted unless requested and truncated when very long
     * @param truncated true when either body was cut short
     * @param headers every header in wire order
     * @param attachments metadata for each non-body part, including a SHA-512 of its content
     * @param validation the RFC compliance verdict
     * @param raw the verbatim RFC 5322 message, omitted unless requested
     * @param spoolDirectory where the message was written on disk, when spooling is enabled
     */
    public record EmailDetail(
            EmailSummary summary,
            String envelopeFrom,
            List<String> headerFrom,
            List<String> headerTo,
            List<String> headerCc,
            @Nullable String messageId,
            @Nullable String date,
            @Nullable String text,
            @Nullable String html,
            boolean truncated,
            List<HeaderView> headers,
            List<AttachmentView> attachments,
            ValidationView validation,
            @Nullable String raw,
            @Nullable String spoolDirectory) {
    }

    /** One header occurrence, in the order it appeared on the wire. */
    public record HeaderView(String name, String value) {

        public static HeaderView of(final ParsedMessage.Header header) {
            return new HeaderView(header.name(), header.value());
        }
    }

    /**
     * One attachment.
     *
     * @param index pass this to get_email_attachment to fetch the content
     * @param filename the declared filename, when the part declares one
     * @param mimeType the media type
     * @param sizeBytes decoded size
     * @param sha512 SHA-512 of the decoded content, for comparing against a known-good payload
     */
    public record AttachmentView(
            int index,
            @Nullable String filename,
            String mimeType,
            long sizeBytes,
            String sha512) {

        public static AttachmentView of(final AttachmentPart part) {
            return new AttachmentView(part.index(), part.filename(), part.mimeType(),
                    part.sizeBytes(), part.sha512());
        }
    }

    /**
     * The RFC compliance verdict.
     *
     * @param status PASS, WARN or FAIL
     * @param errors count of MUST-level violations
     * @param warnings count of SHOULD-level violations
     * @param findings every finding, most severe first
     */
    public record ValidationView(String status, long errors, long warnings, List<FindingView> findings) {
    }

    /**
     * One compliance problem.
     *
     * @param rule stable identifier, safe to assert on in a test
     * @param severity ERROR, WARNING or INFO
     * @param summary what is wrong, in one line
     * @param detail the offending value, when there is one
     * @param reference the specification section that defines the rule
     * @param smtpStatusCode the RFC 5248 enhanced status code the server would reject with
     */
    public record FindingView(
            String rule,
            String severity,
            String summary,
            @Nullable String detail,
            String reference,
            String smtpStatusCode) {

        public static FindingView of(final Finding finding) {
            return new FindingView(finding.rule(), finding.severity().name(), finding.summary(),
                    finding.detail(), finding.reference(), finding.statusCode().code());
        }
    }

    /**
     * One recipient address that has received mail.
     *
     * @param address the recipient address, which is what "inbox" means on a receiving server
     * @param messageCount how many messages it holds
     * @param failingCount how many of those failed RFC validation
     * @param lastReceivedAt when the most recent one arrived
     */
    public record InboxView(String address, long messageCount, long failingCount, String lastReceivedAt) {

        public static InboxView of(final InboxSummary summary) {
            return new InboxView(summary.address(), summary.messageCount(), summary.failingCount(),
                    summary.lastReceivedAt().toString());
        }
    }

    /**
     * Every inbox on the server.
     *
     * <p>Wrapped in a record rather than returned as a bare list because MCP requires a tool's
     * structured output to be a JSON object, not an array.
     *
     * @param count how many inboxes there are
     * @param inboxes the inboxes, most recently active first
     */
    public record InboxList(int count, List<InboxView> inboxes) {
    }

    /**
     * The outcome of a listing.
     *
     * @param total how many messages matched in total, before limit and offset
     * @param returned how many are in this response
     * @param messages the messages, newest first
     */
    public record EmailList(long total, int returned, List<EmailSummary> messages) {
    }

    /**
     * The outcome of a deletion.
     *
     * @param deleted how many messages were removed from both the inbox and the spool directory
     * @param detail a sentence describing what happened
     */
    public record DeletionResult(int deleted, String detail) {
    }

    /**
     * The content of one attachment.
     *
     * @param index the part index that was fetched
     * @param filename the declared filename
     * @param mimeType the media type
     * @param sizeBytes decoded size
     * @param sha512 SHA-512 of the decoded content
     * @param text the decoded content when the part is textual
     * @param base64 the decoded content, base64 encoded, when the part is binary and small enough
     * @param note why the content was omitted, when it was
     */
    public record AttachmentContent(
            int index,
            @Nullable String filename,
            String mimeType,
            long sizeBytes,
            String sha512,
            @Nullable String text,
            @Nullable String base64,
            @Nullable String note) {
    }
}
