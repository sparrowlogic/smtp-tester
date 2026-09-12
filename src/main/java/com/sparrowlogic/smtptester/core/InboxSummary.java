package com.sparrowlogic.smtptester.core;

import java.time.Instant;

/**
 * One recipient address that has received mail, with its counts.
 *
 * <p>"Inbox" is not an SMTP concept — a receiving server has recipients, not mailboxes — so an
 * inbox here is simply the set of messages addressed to one envelope recipient. That is the
 * grouping a developer testing several user journeys at once actually wants.
 *
 * @param address the envelope recipient, lower-cased
 * @param messageCount how many messages were delivered to it
 * @param failingCount how many of those have an ERROR-severity validation finding
 * @param lastReceivedAt arrival time of the most recent message
 */
public record InboxSummary(String address, long messageCount, long failingCount, Instant lastReceivedAt) {
}
