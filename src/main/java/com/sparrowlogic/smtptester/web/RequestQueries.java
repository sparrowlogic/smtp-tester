package com.sparrowlogic.smtptester.web;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Conversions shared by the request-parameter parsing of more than one controller. */
final class RequestQueries {

    private RequestQueries() {
    }

    /**
     * A {@code since} parameter as an instant, or null when it was not supplied.
     *
     * <p>An unparseable value is an error rather than "no filter": silently listing everything
     * would answer a question the caller did not ask, and a CI script comparing counts would never
     * find out why.
     */
    static @Nullable Instant instantOf(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'since' must be an ISO-8601 instant, but was " + value, e);
        }
    }
}
