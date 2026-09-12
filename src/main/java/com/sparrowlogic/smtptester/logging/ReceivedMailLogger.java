package com.sparrowlogic.smtptester.logging;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.AttachmentPart;
import com.sparrowlogic.smtptester.core.Digests;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.validation.Finding;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Writes one JSON document per received message to stdout.
 *
 * <p>It logs through a dedicated logger name, {@code smtptester.mail.received}, which the bundled
 * logback configuration binds to a bare {@code %msg%n} appender with additivity off. The result is
 * a clean stream of JSON objects — one per email, no timestamp or level prefix — so
 * {@code docker logs | jq} works without any filtering, while the application's ordinary logs keep
 * their normal human-readable format.
 */
public class ReceivedMailLogger {

    /** Not a class-based logger on purpose: the name is the contract for the logback appender. */
    private static final Logger MAIL_LOG = LoggerFactory.getLogger("smtptester.mail.received");

    private static final Logger LOG = LoggerFactory.getLogger(ReceivedMailLogger.class);

    private static final String EVENT_NAME = "mail.received";

    private final ObjectMapper json;
    private final SmtpTesterProperties.Logging settings;

    public ReceivedMailLogger(final ObjectMapper json, final SmtpTesterProperties.Logging settings) {
        this.json = json;
        this.settings = settings;
    }

    /** Emits the structured line for a message, unless logging is disabled. */
    public void log(final MailMessage message) {
        if (!this.settings.enabled()) {
            return;
        }
        try {
            MAIL_LOG.info("{}", this.json.writeValueAsString(this.toEvent(message)));
        } catch (final JacksonException e) {
            LOG.warn("Could not serialise the received-mail log event for {}", message.id(), e);
        }
    }

    /** Builds the event document, exposed so tests can assert on its shape without a log appender. */
    public ReceivedMailEvent toEvent(final MailMessage message) {
        final ParsedMessage parsed = message.parsed();
        final SessionInfo session = message.session();
        return new ReceivedMailEvent(
                EVENT_NAME,
                message.id(),
                message.receivedAt().toString(),
                message.envelope().remoteAddress(),
                message.envelope().helo(),
                session != null && session.tls(),
                new ReceivedMailEvent.EnvelopeView(message.envelope().from(),
                        message.envelope().recipients()),
                new ReceivedMailEvent.HeadersView(parsed.from(), parsed.to(), parsed.cc(),
                        parsed.subject(), parsed.messageId(), parsed.header("Date")),
                this.bodyView(parsed),
                message.sizeBytes(),
                message.sha512(),
                this.attachmentsView(parsed),
                this.validationView(message),
                message.rejected());
    }

    private ReceivedMailEvent.@Nullable BodyView bodyView(final ParsedMessage parsed) {
        if (!this.settings.includeBody()) {
            return null;
        }
        final String text = parsed.text();
        final String html = parsed.html();
        final boolean truncated = this.exceedsLimit(text) || this.exceedsLimit(html);
        return new ReceivedMailEvent.BodyView(
                this.truncate(text),
                text == null ? null : Digests.sha512Utf8(text),
                this.truncate(html),
                html == null ? null : Digests.sha512Utf8(html),
                truncated);
    }

    private boolean exceedsLimit(final @Nullable String value) {
        return value != null && value.length() > this.settings.maxBodyCharacters();
    }

    private @Nullable String truncate(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        final int limit = this.settings.maxBodyCharacters();
        return value.length() <= limit ? value : value.substring(0, limit) + "…[truncated]";
    }

    private ReceivedMailEvent.AttachmentsView attachmentsView(final ParsedMessage parsed) {
        final List<AttachmentPart> attachments = parsed.attachments();
        final List<ReceivedMailEvent.PartView> parts = attachments.stream()
                .map(a -> new ReceivedMailEvent.PartView(a.index(), a.mimeType(), a.filename(),
                        a.sizeBytes(), a.sha512()))
                .toList();
        return new ReceivedMailEvent.AttachmentsView(
                attachments.size(), parsed.attachmentCountsByMimeType(), parts);
    }

    private ReceivedMailEvent.ValidationView validationView(final MailMessage message) {
        final List<ReceivedMailEvent.FindingView> findings = message.validation().findings().stream()
                .map(this::findingView)
                .toList();
        return new ReceivedMailEvent.ValidationView(
                message.validation().status().name(),
                message.validation().errorCount(),
                message.validation().warningCount(),
                findings);
    }

    private ReceivedMailEvent.FindingView findingView(final Finding finding) {
        return new ReceivedMailEvent.FindingView(finding.rule(), finding.severity().name(),
                finding.summary(), finding.detail(), finding.reference());
    }
}
