package com.sparrowlogic.smtptester.validation;

import org.jspecify.annotations.Nullable;

/**
 * One compliance problem found in a received message.
 *
 * @param rule stable machine-readable identifier, e.g. {@code RFC5322_MISSING_DATE}; this is what
 *     an agent or a CI check keys off, so it must not change once published
 * @param severity how badly it breaks the rule
 * @param summary a one-line statement of the problem
 * @param detail the offending value or location, when there is one worth quoting
 * @param reference the specification section that defines the rule
 * @param statusCode the RFC 5248 enhanced status code a server should answer with, used verbatim in
 *     the SMTP rejection response when this finding is what caused the rejection
 */
public record Finding(
        String rule,
        Severity severity,
        String summary,
        @Nullable String detail,
        String reference,
        EnhancedStatusCode statusCode) {

    public static Finding error(final String rule, final String summary, final @Nullable String detail,
            final String reference, final EnhancedStatusCode statusCode) {
        return new Finding(rule, Severity.ERROR, summary, detail, reference, statusCode);
    }

    public static Finding warning(final String rule, final String summary, final @Nullable String detail,
            final String reference, final EnhancedStatusCode statusCode) {
        return new Finding(rule, Severity.WARNING, summary, detail, reference, statusCode);
    }

    public static Finding info(final String rule, final String summary, final @Nullable String detail,
            final String reference) {
        return new Finding(rule, Severity.INFO, summary, detail, reference, EnhancedStatusCode.OTHER_UNDEFINED);
    }
}
