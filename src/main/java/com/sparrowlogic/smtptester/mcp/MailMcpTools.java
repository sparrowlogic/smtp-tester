package com.sparrowlogic.smtptester.mcp;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import com.sparrowlogic.smtptester.core.MimeTypes;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The read side of the MCP surface.
 *
 * <p>Tool descriptions are the only documentation a model gets, so they say what the tool is for
 * and how its arguments interact rather than restating the method name. The recurring job these
 * exist to serve is "my application just sent an email — did it arrive, and is it correct?", which
 * is why validation findings are part of the ordinary message view rather than a separate concern.
 */
public class MailMcpTools {

    private static final int MAX_INLINE_ATTACHMENT_BYTES = 256 * 1024;

    private final MailboxService mailbox;
    private final SmtpTesterProperties.Mcp settings;

    public MailMcpTools(final MailboxService mailbox, final SmtpTesterProperties.Mcp settings) {
        this.mailbox = mailbox;
        this.settings = settings;
    }

    @McpTool(name = "list_inboxes",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            generateOutputSchema = true,
            title = "List inboxes",
            description = "Lists every recipient address that has received mail on this test SMTP "
                    + "server, with how many messages each holds and how many of those failed RFC "
                    + "validation. An inbox here is simply a recipient address. Call this first when "
                    + "you do not already know which address the application under test sends to.")
    public McpViews.InboxList listInboxes() {
        final List<McpViews.InboxView> inboxes =
                this.mailbox.inboxes().stream().map(McpViews.InboxView::of).toList();
        return new McpViews.InboxList(inboxes.size(), inboxes);
    }

    @McpTool(name = "list_recent_emails",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            generateOutputSchema = true,
            title = "List recent emails",
            description = "Lists the most recently received emails, newest first, as compact "
                    + "summaries. Restrict to one recipient with 'inbox'. Returns summaries only; "
                    + "call get_email with an id for bodies, headers and attachments.")
    public McpViews.EmailList listRecentEmails(
            @McpToolParam(required = false,
                    description = "Only messages delivered to this recipient address. Omit for all inboxes.")
            final @Nullable String inbox,
            @McpToolParam(required = false,
                    description = "Maximum number of messages to return. Defaults to 20.")
            final @Nullable Integer limit,
            @McpToolParam(required = false,
                    description = "How many of the newest messages to skip, for paging. Defaults to 0.")
            final @Nullable Integer offset) {
        final MailQuery query = new MailQuery(inbox, null, null, null, null, false,
                this.limitOf(limit), offset == null ? 0 : offset);
        return this.listFor(query);
    }

    @McpTool(name = "search_emails",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            generateOutputSchema = true,
            title = "Search emails",
            description = "Finds emails by content or by sender, subject and recipient. 'text' "
                    + "matches case-insensitively across the subject, both bodies and the "
                    + "addresses, which is the fastest way to find the message carrying a specific "
                    + "token such as a password-reset link or an order number. The other filters "
                    + "combine with AND. Set 'onlyFailed' to see just the messages that break an RFC.")
    public McpViews.EmailList searchEmails(
            @McpToolParam(required = false,
                    description = "Free text matched against subject, bodies and addresses.")
            final @Nullable String text,
            @McpToolParam(required = false, description = "Only messages delivered to this recipient address.")
            final @Nullable String inbox,
            @McpToolParam(required = false, description = "Substring of the sender address.")
            final @Nullable String from,
            @McpToolParam(required = false, description = "Substring of the subject line.")
            final @Nullable String subject,
            @McpToolParam(required = false,
                    description = "ISO-8601 instant, e.g. 2026-09-11T14:00:00Z. Only messages received "
                            + "at or after this time.")
            final @Nullable String since,
            @McpToolParam(required = false,
                    description = "When true, return only messages with an ERROR-severity RFC finding.")
            final @Nullable Boolean onlyFailed,
            @McpToolParam(required = false, description = "Maximum number of messages to return. Defaults to 20.")
            final @Nullable Integer limit) {
        final MailQuery query = new MailQuery(inbox, from, subject, text, this.instantOf(since),
                Boolean.TRUE.equals(onlyFailed), this.limitOf(limit), 0);
        return this.listFor(query);
    }

    @McpTool(name = "get_email",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            // No generated output schema. This result has genuinely optional fields -- an
            // email may have no HTML body, no attachment filename, no spool directory -- and
            // the schema generator types a @Nullable String as a plain "string", so the
            // server rejects its own response the moment one of them is absent. The full
            // JSON still arrives as text content; only the machine-readable schema is dropped.
            title = "Get one email",
            description = "Returns one email in full: headers, plain-text body, attachment metadata "
                    + "with a SHA-512 per part, and the RFC compliance verdict. Bodies are truncated "
                    + "if very long. Ask for the HTML body or the verbatim RFC 5322 source only when "
                    + "you actually need them, since both can be large.")
    public McpViews.EmailDetail getEmail(
            @McpToolParam(description = "The message id, as returned by list_recent_emails or search_emails.")
            final String id,
            @McpToolParam(required = false, description = "Include the HTML body. Defaults to false.")
            final @Nullable Boolean includeHtml,
            @McpToolParam(required = false,
                    description = "Include the verbatim RFC 5322 message source. Defaults to false.")
            final @Nullable Boolean includeRaw) {
        final MailMessage message = this.require(id);
        return this.detail(message, Boolean.TRUE.equals(includeHtml), Boolean.TRUE.equals(includeRaw));
    }

    @McpTool(name = "get_email_validation",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            // No generated output schema. This result has genuinely optional fields -- an
            // email may have no HTML body, no attachment filename, no spool directory -- and
            // the schema generator types a @Nullable String as a plain "string", so the
            // server rejects its own response the moment one of them is absent. The full
            // JSON still arrives as text content; only the machine-readable schema is dropped.
            title = "Get the RFC compliance report for an email",
            description = "Returns just the RFC 5321/5322/2045 compliance findings for one message, "
                    + "each with a stable rule id, the specification section it comes from, and the "
                    + "RFC 5248 enhanced status code a real server would reject it with. Use this to "
                    + "explain why a message was refused, or to check that mail an application "
                    + "produces would survive a strict relay.")
    public McpViews.ValidationView getEmailValidation(
            @McpToolParam(description = "The message id.") final String id) {
        return this.validationView(this.require(id));
    }

    @McpTool(name = "get_email_attachment",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            // No generated output schema. This result has genuinely optional fields -- an
            // email may have no HTML body, no attachment filename, no spool directory -- and
            // the schema generator types a @Nullable String as a plain "string", so the
            // server rejects its own response the moment one of them is absent. The full
            // JSON still arrives as text content; only the machine-readable schema is dropped.
            title = "Get the content of an attachment",
            description = "Returns the decoded content of one MIME part. Textual parts come back as "
                    + "text; binary parts come back base64 encoded when small enough, and as "
                    + "metadata plus a SHA-512 digest when not. Use the digest to compare an "
                    + "attachment against a known-good copy without transferring it.")
    public McpViews.AttachmentContent getEmailAttachment(
            @McpToolParam(description = "The message id.") final String id,
            @McpToolParam(description = "The attachment index, as listed by get_email.") final int index) {
        final MailMessage message = this.require(id);
        return this.attachmentContent(message, index).orElseThrow(() -> new IllegalArgumentException(
                "Message " + id + " has no attachment at index " + index
                        + "; call get_email to see the available indexes"));
    }

    /**
     * Looks a message up, failing loudly when it is gone.
     *
     * <p>A tool that quietly returns nothing leaves a model to guess whether the message is absent
     * or the call was malformed. MCP's convention is an error result, and the exception message is
     * what the model reads, so it says what to do next.
     */
    private MailMessage require(final String id) {
        return this.mailbox.find(id).orElseThrow(() -> new IllegalArgumentException(
                "No message with id " + id
                        + "; call list_recent_emails or search_emails for current ids"));
    }

    private McpViews.EmailList listFor(final MailQuery query) {
        final List<McpViews.EmailSummary> messages = this.mailbox.list(query).stream()
                .map(McpViews.EmailSummary::of)
                .toList();
        return new McpViews.EmailList(this.mailbox.count(query), messages.size(), messages);
    }

    private int limitOf(final @Nullable Integer limit) {
        return limit == null || limit <= 0 ? this.settings.defaultLimit() : limit;
    }

    private @Nullable Instant instantOf(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'since' must be an ISO-8601 instant such as 2026-09-11T14:00:00Z, but was " + value, e);
        }
    }

    private McpViews.EmailDetail detail(final MailMessage message, final boolean includeHtml,
            final boolean includeRaw) {
        final ParsedMessage parsed = message.parsed();
        final String text = this.truncate(parsed.text());
        final String html = includeHtml ? this.truncate(parsed.html()) : null;
        return new McpViews.EmailDetail(
                McpViews.EmailSummary.of(message),
                message.envelope().from(),
                parsed.from(),
                parsed.to(),
                parsed.cc(),
                parsed.messageId(),
                parsed.header("Date"),
                text,
                html,
                this.wasTruncated(parsed.text()) || includeHtml && this.wasTruncated(parsed.html()),
                parsed.headers().stream().map(McpViews.HeaderView::of).toList(),
                parsed.attachments().stream().map(McpViews.AttachmentView::of).toList(),
                this.validationView(message),
                includeRaw ? new String(message.raw(), StandardCharsets.UTF_8) : null,
                this.mailbox.spoolDirectory(message).map(java.nio.file.Path::toString).orElse(null));
    }

    private boolean wasTruncated(final @Nullable String body) {
        return body != null && body.length() > this.settings.maxBodyCharacters();
    }

    private @Nullable String truncate(final @Nullable String body) {
        if (body == null) {
            return null;
        }
        final int limit = this.settings.maxBodyCharacters();
        return body.length() <= limit ? body : body.substring(0, limit) + "\n…[truncated]";
    }

    private McpViews.ValidationView validationView(final MailMessage message) {
        final List<McpViews.FindingView> findings = message.validation().findings().stream()
                .sorted(Comparator.comparingInt(f -> f.severity().ordinal()))
                .map(McpViews.FindingView::of)
                .toList();
        return new McpViews.ValidationView(
                message.validation().status().name(),
                message.validation().errorCount(),
                message.validation().warningCount(),
                findings);
    }

    private Optional<McpViews.AttachmentContent> attachmentContent(final MailMessage message, final int index) {
        return message.parsed().attachments().stream()
                .filter(a -> a.index() == index)
                .findFirst()
                .map(part -> this.renderAttachment(message, part));
    }

    private McpViews.AttachmentContent renderAttachment(final MailMessage message,
            final com.sparrowlogic.smtptester.core.AttachmentPart part) {
        final byte[] content = this.mailbox.attachment(message.id(), part.index()).orElse(new byte[0]);
        final boolean textual = MimeTypes.isTextual(part.mimeType());
        final boolean small = content.length <= MAX_INLINE_ATTACHMENT_BYTES;
        return new McpViews.AttachmentContent(
                part.index(),
                part.filename(),
                part.mimeType(),
                part.sizeBytes(),
                part.sha512(),
                textual && small ? new String(content, StandardCharsets.UTF_8) : null,
                !textual && small ? Base64.getEncoder().encodeToString(content) : null,
                small ? null : "Content omitted: " + content.length
                        + " bytes exceeds the inline limit. Download it from "
                        + "/api/v1/messages/" + message.id() + "/attachments/" + part.index()
                        + " or compare the sha512 above.");
    }

}
