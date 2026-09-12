package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.validation.Finding;
import com.sparrowlogic.smtptester.validation.MailValidator;
import com.sparrowlogic.smtptester.validation.Severity;
import com.sparrowlogic.smtptester.validation.ValidationReport;
import org.jspecify.annotations.Nullable;

/**
 * Whether a received message is checked against the RFC rule set, and whether what that found is
 * bad enough to refuse it on the wire.
 *
 * <p>Split out of {@link MailboxService} because it is the one step of ingestion that is policy
 * rather than plumbing. Storing, spooling and logging happen to every message the same way; this
 * reads two settings and is what can turn an otherwise accepted message into a {@code 550}.
 */
public class ValidationPolicy {

    private final MailValidator validator;
    private final boolean enabled;

    /** The lowest severity that rejects, or null when nothing does. */
    private final @Nullable Severity rejectThreshold;

    public ValidationPolicy(final MailValidator validator,
            final SmtpTesterProperties.Validation settings) {
        this.validator = validator;
        this.enabled = settings.enabled();
        this.rejectThreshold = switch (settings.rejectOn()) {
            case NONE -> null;
            case ERROR -> Severity.ERROR;
            case WARNING -> Severity.WARNING;
        };
    }

    /** The compliance report for a message, or an empty one when checking is switched off. */
    public ValidationReport validate(final byte[] raw, final Envelope envelope,
            final ParsedMessage parsed, final SessionInfo session) {
        if (!this.enabled) {
            return ValidationReport.empty();
        }
        return this.validator.validate(raw, envelope, parsed, session);
    }

    /**
     * The finding a rejection should quote, or null to accept.
     *
     * <p>The most severe finding is chosen so the SMTP reply names the worst problem rather than
     * whichever rule happened to run first.
     */
    public @Nullable Finding rejectionFor(final ValidationReport report) {
        final Severity threshold = this.rejectThreshold;
        if (threshold == null || !report.hasAtLeast(threshold)) {
            return null;
        }
        return report.mostSevere().orElse(null);
    }
}
