package com.sparrowlogic.smtptester.core;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Locale;

/**
 * Filter for listing messages. Every field is optional; an all-null query lists everything.
 *
 * @param inbox restrict to messages whose SMTP envelope delivered them to this address
 * @param from substring match against the envelope and header sender
 * @param subject substring match against the subject
 * @param text substring match across subject, addresses and both bodies
 * @param since only messages received at or after this instant
 * @param onlyFailed only messages with an ERROR-severity validation finding
 * @param limit maximum number of results
 * @param offset how many of the newest results to skip
 */
public record MailQuery(
        @Nullable String inbox,
        @Nullable String from,
        @Nullable String subject,
        @Nullable String text,
        @Nullable Instant since,
        boolean onlyFailed,
        int limit,
        int offset) {

    /** An unfiltered query returning at most {@code limit} of the newest messages. */
    public static MailQuery recent(final int limit) {
        return new MailQuery(null, null, null, null, null, false, limit, 0);
    }

    /** Everything in one inbox. */
    public static MailQuery inbox(final String inbox, final int limit) {
        return new MailQuery(inbox, null, null, null, null, false, limit, 0);
    }

    /** True when the given message satisfies every set filter. */
    public boolean matches(final MailMessage message) {
        return this.matchesAddressing(message) && this.matchesContent(message);
    }

    private boolean matchesAddressing(final MailMessage message) {
        return this.matchesInbox(message) && this.matchesFrom(message);
    }

    private boolean matchesContent(final MailMessage message) {
        return this.matchesSubject(message)
                && this.matchesText(message)
                && this.matchesSince(message)
                && this.matchesFailed(message);
    }

    /**
     * Inbox membership is decided by the SMTP envelope alone, never by the To/Cc headers.
     *
     * <p>Those two disagree more often than they agree: a Bcc recipient appears in no header, and a
     * header To: that was never in a RCPT TO is exactly the bug this tool should expose. Deciding by
     * envelope keeps the destructive operations honest — clearing one inbox must not delete a
     * message that was actually delivered to somebody else and merely names this address in a
     * header. Header recipients stay findable through the free-text filter.
     */
    private boolean matchesInbox(final MailMessage message) {
        if (this.inbox == null || this.inbox.isBlank()) {
            return true;
        }
        final String wanted = this.inbox.toLowerCase(Locale.ROOT);
        return message.envelope().recipients().stream()
                .anyMatch(r -> r.toLowerCase(Locale.ROOT).equals(wanted));
    }

    private boolean matchesFrom(final MailMessage message) {
        return this.from == null || this.from.isBlank()
                || contains(message.envelope().from(), this.from)
                || contains(message.displayFrom(), this.from);
    }

    private boolean matchesSubject(final MailMessage message) {
        return this.subject == null || this.subject.isBlank()
                || contains(message.parsed().subject(), this.subject);
    }

    private boolean matchesSince(final MailMessage message) {
        return this.since == null || !message.receivedAt().isBefore(this.since);
    }

    private boolean matchesFailed(final MailMessage message) {
        return !this.onlyFailed || message.validation().errorCount() > 0;
    }

    private boolean matchesText(final MailMessage message) {
        if (this.text == null || this.text.isBlank()) {
            return true;
        }
        return this.matchesBody(message) || this.matchesAddresses(message);
    }

    private boolean matchesBody(final MailMessage message) {
        final ParsedMessage parsed = message.parsed();
        return contains(parsed.subject(), this.text)
                || contains(parsed.text(), this.text)
                || contains(parsed.html(), this.text);
    }

    /** Envelope and header addresses both, so a Bcc recipient is still findable by search. */
    private boolean matchesAddresses(final MailMessage message) {
        return contains(message.displayFrom(), this.text)
                || contains(String.join(" ", message.envelope().recipients()), this.text)
                || contains(String.join(" ", message.parsed().allRecipients()), this.text);
    }

    private static boolean contains(final @Nullable String haystack, final @Nullable String needle) {
        if (needle == null) {
            return false;
        }
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
