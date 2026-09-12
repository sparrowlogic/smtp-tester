package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MailDateFormat;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * RFC 5322 section 3.6 rules about which header fields must be present and how often.
 *
 * <p>The "appears more than once" checks matter more than they look. A duplicated {@code From:} or
 * {@code Date:} is accepted by most test servers and then handled inconsistently downstream — some
 * agents read the first occurrence, some the last — which is exactly the kind of bug that only
 * shows up in production.
 */
public class HeaderRules implements ComplianceRule {

    private static final String RFC5322_FIELDS = "RFC 5322 section 3.6";
    private static final String MESSAGE_ID = "Message-ID";
    private static final String FROM = "From";
    private static final String DATE = "Date";
    private static final String TO = "To";
    private static final String CC = "Cc";
    private static final String BCC = "Bcc";

    /** Header fields RFC 5322 permits at most once in a message. */
    private static final List<String> SINGLE_OCCURRENCE = List.of(
            DATE, FROM, "Sender", "Reply-To", TO, CC, BCC, MESSAGE_ID,
            "In-Reply-To", "References", "Subject");

    private static final Pattern MESSAGE_ID_SYNTAX = Pattern.compile("^<[^<>@\\s]+@[^<>@\\s]+>$");

    @Override
    public void check(final byte[] raw, final Envelope envelope, final ParsedMessage parsed,
            final SessionInfo session, final Consumer<Finding> sink) {
        this.checkRequiredFields(parsed, sink);
        this.checkSingleOccurrence(parsed, sink);
        this.checkDate(parsed, sink);
        this.checkMessageId(parsed, sink);
        this.checkAddresses(parsed, sink);
        this.checkSenderRequired(parsed, sink);
        this.checkRecipients(parsed, sink);
        this.checkBcc(parsed, sink);
        this.checkEnvelopeAlignment(envelope, parsed, sink);
        this.checkSubject(parsed, sink);
    }

    /** RFC 5322 section 3.6: {@code Date:} and {@code From:} are the only mandatory fields. */
    private void checkRequiredFields(final ParsedMessage parsed, final Consumer<Finding> sink) {
        if (parsed.header(DATE) == null) {
            sink.accept(Finding.error("RFC5322_MISSING_DATE",
                    "Missing the required Date header",
                    null, RFC5322_FIELDS, EnhancedStatusCode.MEDIA_ERROR));
        }
        if (parsed.header(FROM) == null) {
            sink.accept(Finding.error("RFC5322_MISSING_FROM",
                    "Missing the required From header",
                    null, RFC5322_FIELDS, EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkSingleOccurrence(final ParsedMessage parsed, final Consumer<Finding> sink) {
        for (final String field : SINGLE_OCCURRENCE) {
            final long count = parsed.headerCount(field);
            if (count > 1) {
                sink.accept(Finding.error("RFC5322_DUPLICATE_HEADER",
                        "The " + field + " header appears more than once; RFC 5322 allows it at most once",
                        field + " occurs " + count + " times",
                        RFC5322_FIELDS, EnhancedStatusCode.MEDIA_ERROR));
            }
        }
    }

    private void checkDate(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final String date = parsed.header(DATE);
        if (date == null) {
            return;
        }
        try {
            new MailDateFormat().parse(date);
        } catch (final ParseException e) {
            sink.accept(Finding.error("RFC5322_INVALID_DATE",
                    "The Date header is not a valid RFC 5322 date-time",
                    date, "RFC 5322 section 3.3", EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    /**
     * {@code Message-ID} is a SHOULD rather than a MUST, but omitting it breaks threading and makes
     * duplicate suppression at the receiving end impossible, so it is worth a warning.
     */
    private void checkMessageId(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final String messageId = parsed.header(MESSAGE_ID);
        if (messageId == null) {
            sink.accept(Finding.warning("RFC5322_MISSING_MESSAGE_ID",
                    "No Message-ID header; threading and duplicate detection depend on it",
                    null, "RFC 5322 section 3.6.4", EnhancedStatusCode.MEDIA_ERROR));
            return;
        }
        if (!MESSAGE_ID_SYNTAX.matcher(messageId.trim()).matches()) {
            sink.accept(Finding.warning("RFC5322_INVALID_MESSAGE_ID",
                    "The Message-ID is not of the form <id-left@id-right>",
                    messageId, "RFC 5322 section 3.6.4", EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkAddresses(final ParsedMessage parsed, final Consumer<Finding> sink) {
        this.checkAddressField(parsed, FROM, sink);
        this.checkAddressField(parsed, TO, sink);
        this.checkAddressField(parsed, CC, sink);
        this.checkAddressField(parsed, "Reply-To", sink);
    }

    private void checkAddressField(final ParsedMessage parsed, final String field,
            final Consumer<Finding> sink) {
        final String value = parsed.header(field);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            InternetAddress.parse(value, true);
        } catch (final AddressException e) {
            sink.accept(Finding.error("RFC5322_INVALID_ADDRESS",
                    "The " + field + " header does not parse as an address list",
                    value + " (" + e.getMessage() + ")",
                    "RFC 5322 section 3.4", EnhancedStatusCode.BAD_DESTINATION_ADDRESS_SYNTAX));
        }
    }

    /**
     * RFC 5322 section 3.6.2: when {@code From:} names more than one mailbox, {@code Sender:}
     * becomes mandatory so the agent that actually transmitted the message is identifiable.
     */
    private void checkSenderRequired(final ParsedMessage parsed, final Consumer<Finding> sink) {
        if (parsed.from().size() > 1 && parsed.header("Sender") == null) {
            sink.accept(Finding.error("RFC5322_MULTIPLE_FROM_WITHOUT_SENDER",
                    "From names several mailboxes but there is no Sender header to disambiguate",
                    String.join(", ", parsed.from()),
                    "RFC 5322 section 3.6.2", EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkRecipients(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final boolean none = parsed.header(TO) == null
                && parsed.header(CC) == null
                && parsed.header(BCC) == null;
        if (none) {
            sink.accept(Finding.warning("RFC5322_NO_DESTINATION_HEADER",
                    "No To, Cc or Bcc header; many filters treat headerless recipients as spam",
                    null, "RFC 5322 section 3.6.3", EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkBcc(final ParsedMessage parsed, final Consumer<Finding> sink) {
        if (parsed.header(BCC) != null) {
            sink.accept(Finding.warning("RFC5322_BCC_TRANSMITTED",
                    "A Bcc header was transmitted; the sending agent should strip it, since leaving it "
                            + "in discloses the blind recipients to everyone",
                    String.join(", ", parsed.bcc()),
                    "RFC 5322 section 3.6.3", EnhancedStatusCode.SECURITY_STATUS));
        }
    }

    /**
     * The envelope sender and the header {@code From:} not matching is legal and routine for
     * forwarders and list servers, but it is also what makes SPF fail to align under DMARC, so it
     * is worth stating plainly rather than leaving to be discovered in a deliverability report.
     */
    private void checkEnvelopeAlignment(final Envelope envelope, final ParsedMessage parsed,
            final Consumer<Finding> sink) {
        if (envelope.nullSender() || parsed.from().isEmpty()) {
            return;
        }
        final String headerFrom = parsed.from().getFirst();
        if (!this.domainOf(headerFrom).equals(this.domainOf(envelope.from()))) {
            sink.accept(Finding.info("DMARC_SPF_ALIGNMENT",
                    "The envelope sender domain differs from the From header domain, so SPF will not "
                            + "align under DMARC; DKIM alignment would have to carry the message",
                    "MAIL FROM " + envelope.from() + " vs From " + headerFrom,
                    "RFC 7489 section 3.1.2"));
        }
    }

    private void checkSubject(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final String subject = parsed.header("Subject");
        if (subject == null || subject.isBlank()) {
            sink.accept(Finding.info("RFC5322_NO_SUBJECT",
                    "No Subject header", null, RFC5322_FIELDS));
        }
    }

    private String domainOf(final String address) {
        final int at = address.lastIndexOf('@');
        return at < 0 ? "" : address.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
