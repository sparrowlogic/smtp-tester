package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.AttachmentPart;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.mcp.McpViews;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns stored messages into the records the HTTP layer serves.
 *
 * <p>Kept out of the controller so the REST API and the HTML UI produce identical values, and so
 * the mapping can be unit tested without a servlet.
 */
public class MessageViewMapper {

    private final MailboxService mailbox;
    private final SmtpTesterProperties.Mcp limits;

    public MessageViewMapper(final MailboxService mailbox, final SmtpTesterProperties.Mcp limits) {
        this.mailbox = mailbox;
        this.limits = limits;
    }

    /** The full view of a message. */
    public McpViews.EmailDetail detail(final MailMessage message, final boolean includeHtml,
            final boolean includeRaw) {
        final ParsedMessage parsed = message.parsed();
        return new McpViews.EmailDetail(
                McpViews.EmailSummary.of(message),
                message.envelope().from(),
                parsed.from(),
                parsed.to(),
                parsed.cc(),
                parsed.messageId(),
                parsed.header("Date"),
                this.truncate(parsed.text()),
                includeHtml ? this.truncate(parsed.html()) : null,
                this.wasTruncated(parsed.text()) || this.wasTruncated(parsed.html()),
                parsed.headers().stream().map(McpViews.HeaderView::of).toList(),
                parsed.attachments().stream().map(McpViews.AttachmentView::of).toList(),
                this.validation(message),
                includeRaw ? new String(message.raw(), StandardCharsets.UTF_8) : null,
                this.mailbox.spoolDirectory(message).map(Path::toString).orElse(null));
    }

    /** The compliance report, most severe finding first. */
    public McpViews.ValidationView validation(final MailMessage message) {
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

    /**
     * An attachment download. The declared media type is served as-is except that anything
     * HTML-ish is forced to a download rather than rendered, so a malicious attachment in a
     * captured message cannot execute against the inbox's own origin. {@code nosniff} closes the
     * other half of that: without it a browser may content-sniff a payload whose declared type is
     * innocuous and render it as HTML anyway.
     */
    public Optional<ResponseEntity<byte[]>> attachmentResponse(final MailMessage message, final int index) {
        final Optional<AttachmentPart> part = message.parsed().attachments().stream()
                .filter(a -> a.index() == index)
                .findFirst();
        if (part.isEmpty()) {
            return Optional.empty();
        }
        return this.mailbox.attachment(message.id(), index)
                .map(content -> ResponseEntity.ok()
                        .contentType(this.safeMediaType(part.get()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(part.get().downloadName()).build().toString())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(content));
    }

    private MediaType safeMediaType(final AttachmentPart part) {
        try {
            final MediaType declared = MediaType.parseMediaType(part.mimeType());
            final boolean scriptable = MediaType.TEXT_HTML.includes(declared)
                    || "svg+xml".equals(declared.getSubtype())
                    || declared.getSubtype().contains("javascript");
            return scriptable ? MediaType.APPLICATION_OCTET_STREAM : declared;
        } catch (final org.springframework.util.InvalidMimeTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean wasTruncated(final @Nullable String body) {
        return body != null && body.length() > this.limits.maxBodyCharacters();
    }

    private @Nullable String truncate(final @Nullable String body) {
        if (body == null) {
            return null;
        }
        final int limit = this.limits.maxBodyCharacters();
        return body.length() <= limit ? body : body.substring(0, limit) + "\n…[truncated]";
    }
}
