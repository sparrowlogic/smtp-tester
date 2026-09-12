package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import com.sparrowlogic.smtptester.mcp.McpViews;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The JSON and binary HTTP surface.
 *
 * <p>It returns the same view records as the MCP tools and goes through the same
 * {@link MailboxService}, so the inbox an agent sees over MCP and the one a browser or a CI script
 * sees over HTTP can never disagree.
 */
@RestController
@RequestMapping("${smtptester.web.base-path:}/api/v1")
public class MessageApiController {

    private static final MediaType RFC822 = MediaType.parseMediaType("message/rfc822");

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /**
     * No scripts, no plugins, no form submission, and a unique opaque origin, so the body cannot
     * reach the inbox API even by navigating itself. Images and styles are left alone: a message
     * that renders as a blank page is not a useful preview, and neither can execute. Remote
     * images do mean a tracking pixel in a captured message will fire when the body is previewed,
     * which is the same trade-off MailHog and Mailpit make for the same reason.
     */
    private static final String INERT_HTML_POLICY =
            "sandbox; default-src 'none'; img-src data: http: https:; style-src 'unsafe-inline'; "
                    + "font-src data:; form-action 'none'; frame-ancestors 'self'";

    private final MailboxService mailbox;
    private final MessageViewMapper mapper;

    public MessageApiController(final MailboxService mailbox, final MessageViewMapper mapper) {
        this.mailbox = mailbox;
        this.mapper = mapper;
    }

    /** Every recipient address that has received mail, with counts. */
    @GetMapping("/inboxes")
    public ResponseEntity<List<McpViews.InboxView>> inboxes() {
        return ResponseEntity.ok(this.mailbox.inboxes().stream().map(McpViews.InboxView::of).toList());
    }

    /** Messages matching the filters, newest first. */
    @GetMapping("/messages")
    public ResponseEntity<McpViews.EmailList> list(
            @RequestParam(required = false) final @Nullable String inbox,
            @RequestParam(required = false) final @Nullable String from,
            @RequestParam(required = false) final @Nullable String subject,
            @RequestParam(required = false) final @Nullable String text,
            @RequestParam(required = false) final @Nullable String since,
            @RequestParam(defaultValue = "false") final boolean onlyFailed,
            @RequestParam(defaultValue = "50") final int limit,
            @RequestParam(defaultValue = "0") final int offset) {
        final MailQuery query = new MailQuery(inbox, from, subject, text, this.instantOf(since),
                onlyFailed, limit, offset);
        final List<McpViews.EmailSummary> messages = this.mailbox.list(query).stream()
                .map(McpViews.EmailSummary::of)
                .toList();
        return ResponseEntity.ok(
                new McpViews.EmailList(this.mailbox.count(query), messages.size(), messages));
    }

    /** One message in full. */
    @GetMapping("/messages/{id}")
    public ResponseEntity<McpViews.EmailDetail> get(@PathVariable final String id) {
        return this.mailbox.find(id)
                .map(message -> ResponseEntity.ok(this.mapper.detail(message, true, false)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The RFC compliance report for one message. */
    @GetMapping("/messages/{id}/validation")
    public ResponseEntity<McpViews.ValidationView> validation(@PathVariable final String id) {
        return this.mailbox.find(id)
                .map(message -> ResponseEntity.ok(this.mapper.validation(message)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The verbatim message. No {@code Received:} header is ever inserted by this server, so these
     * bytes are exactly what the client transmitted and can be diffed against a fixture.
     */
    @GetMapping("/messages/{id}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable final String id) {
        return this.mailbox.find(id)
                .map(message -> ResponseEntity.ok()
                        .contentType(RFC822)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(message.id() + ".eml").build().toString())
                        .body(message.raw()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The HTML body of a captured message, rendered inert.
     *
     * <p>These bytes came off the wire from anything that could reach the SMTP port, and they are
     * served from the same origin as the inbox and its unauthenticated API. The message view frames
     * this in a {@code sandbox=""} iframe, but a response is also a URL: following the "open in new
     * tab" path, or a link to it, would otherwise run the sender's script against that origin and
     * let it read or delete every captured message. {@code Content-Security-Policy: sandbox} moves
     * the guarantee onto the response itself, so the body is inert however it is reached, and
     * {@code nosniff} stops a declared-HTML body being re-interpreted as anything else.
     */
    @GetMapping(value = "/messages/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable final String id) {
        return this.mailbox.find(id)
                .map(this::htmlBody)
                .map(body -> ResponseEntity.ok()
                        .header(CONTENT_SECURITY_POLICY, INERT_HTML_POLICY)
                        .header(CONTENT_TYPE_OPTIONS, "nosniff")
                        .contentType(MediaType.TEXT_HTML)
                        .body(body))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String htmlBody(final MailMessage message) {
        final String html = message.parsed().html();
        return html == null ? "<!doctype html><meta charset=\"utf-8\"><p>No HTML body.</p>" : html;
    }

    /** The decoded content of one MIME part. */
    @GetMapping("/messages/{id}/attachments/{index}")
    public ResponseEntity<byte[]> attachment(@PathVariable final String id, @PathVariable final int index) {
        return this.mailbox.find(id)
                .flatMap(message -> this.mapper.attachmentResponse(message, index))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Deletes one message and its spooled files. */
    @DeleteMapping("/messages/{id}")
    public ResponseEntity<McpViews.DeletionResult> delete(@PathVariable final String id) {
        if (!this.mailbox.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new McpViews.DeletionResult(1, "Deleted message " + id));
    }

    /** Deletes every message, or every message in one inbox, along with their spooled files. */
    @DeleteMapping("/messages")
    public ResponseEntity<McpViews.DeletionResult> deleteAll(
            @RequestParam(required = false) final @Nullable String inbox) {
        if (inbox == null || inbox.isBlank()) {
            final int deleted = this.mailbox.clearAll();
            return ResponseEntity.ok(new McpViews.DeletionResult(deleted,
                    "Deleted " + deleted + " message(s) from all inboxes"));
        }
        final int deleted = this.mailbox.clearInbox(inbox);
        return ResponseEntity.ok(new McpViews.DeletionResult(deleted,
                "Deleted " + deleted + " message(s) from " + inbox));
    }

    private @Nullable Instant instantOf(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException("'since' must be an ISO-8601 instant, but was " + value, e);
        }
    }

}
