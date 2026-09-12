package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Wire-level rules from RFC 5321 and RFC 5322 section 2.
 *
 * <p>These read the raw octets on purpose. By the time a MIME parser has been over the bytes, bare
 * line feeds have been normalised, over-long lines have been folded and 8-bit octets have been
 * decoded to a String — so every defect this class exists to catch has already been erased. Bare LF
 * in particular is the single most common real-world bug here: it survives a loopback test against
 * a lenient server and then gets mangled by the first standards-conforming relay in the path.
 */
public class TransportRules implements ComplianceRule {

    /** RFC 5321 section 4.5.3.1.6: a text line is at most 1000 octets including the CRLF. */
    private static final int MAX_LINE_OCTETS = 998;

    private static final String RFC5321_LINES = "RFC 5321 section 4.5.3.1.6";
    private static final String RFC5321_CRLF = "RFC 5321 section 2.3.8";

    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s<>]+@[A-Za-z0-9.\\-\\[\\]:]+$");

    @Override
    public void check(final byte[] raw, final Envelope envelope, final ParsedMessage parsed,
            final SessionInfo session, final Consumer<Finding> sink) {
        this.checkNotEmpty(raw, sink);
        this.checkLineEndings(raw, sink);
        this.checkLineLengths(raw, sink);
        this.checkHeaderOctets(raw, sink);
        this.checkFinalCrlf(raw, sink);
        this.checkEnvelopeAddresses(envelope, sink);
    }

    private void checkNotEmpty(final byte[] raw, final Consumer<Finding> sink) {
        if (raw.length == 0) {
            sink.accept(Finding.error("RFC5322_EMPTY_MESSAGE",
                    "The message contained no data at all",
                    null, "RFC 5322 section 3.5", EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    /**
     * RFC 5321 section 2.3.8: CR and LF may only appear as the CRLF pair. A lone LF is what a
     * program that built the message with "\n" produces.
     */
    private void checkLineEndings(final byte[] raw, final Consumer<Finding> sink) {
        int bareLf = 0;
        int bareCr = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\n' && (i == 0 || raw[i - 1] != '\r')) {
                bareLf++;
            }
            if (raw[i] == '\r' && (i == raw.length - 1 || raw[i + 1] != '\n')) {
                bareCr++;
            }
        }
        if (bareLf > 0) {
            sink.accept(Finding.error("RFC5321_BARE_LF",
                    "Line feed not preceded by a carriage return; lines must end with CRLF",
                    bareLf + " bare LF octet(s); the message was probably assembled with \"\\n\"",
                    RFC5321_CRLF, EnhancedStatusCode.MEDIA_ERROR));
        }
        if (bareCr > 0) {
            sink.accept(Finding.error("RFC5321_BARE_CR",
                    "Carriage return not followed by a line feed; lines must end with CRLF",
                    bareCr + " bare CR octet(s)", RFC5321_CRLF, EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkLineLengths(final byte[] raw, final Consumer<Finding> sink) {
        int lineStart = 0;
        int worstLength = 0;
        int worstLine = 0;
        int lineNumber = 1;
        for (int i = 0; i <= raw.length; i++) {
            final boolean end = i == raw.length || raw[i] == '\n';
            if (!end) {
                continue;
            }
            final int length = this.lineLength(raw, lineStart, i);
            if (length > worstLength) {
                worstLength = length;
                worstLine = lineNumber;
            }
            lineStart = i + 1;
            lineNumber++;
        }
        if (worstLength > MAX_LINE_OCTETS) {
            sink.accept(Finding.error("RFC5321_LINE_TOO_LONG",
                    "A line exceeds the 998-octet limit; relays are permitted to fold or truncate it",
                    "line " + worstLine + " is " + worstLength + " octets",
                    RFC5321_LINES, EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private int lineLength(final byte[] raw, final int start, final int end) {
        final int stop = end > start && raw[end - 1] == '\r' ? end - 1 : end;
        return stop - start;
    }

    /**
     * RFC 5322 section 2.1 restricts header field content to US-ASCII. Non-ASCII there requires
     * either RFC 2047 encoded-words or the SMTPUTF8 extension, neither of which is in play for a
     * raw 8-bit byte.
     */
    private void checkHeaderOctets(final byte[] raw, final Consumer<Finding> sink) {
        final int headerEnd = this.headerBlockEnd(raw);
        if (headerEnd < 0) {
            sink.accept(Finding.error("RFC5322_NO_HEADER_BODY_SEPARATOR",
                    "No blank line separating headers from body; the whole message reads as headers",
                    null, "RFC 5322 section 2.1", EnhancedStatusCode.MEDIA_ERROR));
            return;
        }
        for (int i = 0; i < headerEnd; i++) {
            if ((raw[i] & 0x80) != 0) {
                sink.accept(Finding.error("RFC5322_NON_ASCII_HEADER",
                        "Raw 8-bit octet in the header block; headers must be US-ASCII, using RFC 2047 "
                                + "encoded-words for anything else",
                        "first at offset " + i + " in the header block: 0x"
                                + Integer.toHexString(raw[i] & 0xFF),
                        "RFC 5322 section 2.1", EnhancedStatusCode.NON_ASCII_ADDRESSES_NOT_PERMITTED));
                return;
            }
        }
    }

    private void checkFinalCrlf(final byte[] raw, final Consumer<Finding> sink) {
        if (raw.length >= 2 && (raw[raw.length - 1] != '\n' || raw[raw.length - 2] != '\r')) {
            sink.accept(Finding.warning("RFC5322_NO_TRAILING_CRLF",
                    "The message does not end with CRLF",
                    null, RFC5321_CRLF, EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkEnvelopeAddresses(final Envelope envelope, final Consumer<Finding> sink) {
        if (!envelope.nullSender() && !ADDRESS.matcher(envelope.from()).matches()) {
            sink.accept(Finding.error("RFC5321_INVALID_REVERSE_PATH",
                    "The MAIL FROM reverse-path is not a syntactically valid mailbox",
                    envelope.from(), "RFC 5321 section 4.1.2",
                    EnhancedStatusCode.BAD_SENDER_ADDRESS_SYNTAX));
        }
        for (final String recipient : envelope.recipients()) {
            if (!ADDRESS.matcher(recipient).matches()) {
                sink.accept(Finding.error("RFC5321_INVALID_FORWARD_PATH",
                        "A RCPT TO forward-path is not a syntactically valid mailbox",
                        recipient, "RFC 5321 section 4.1.2",
                        EnhancedStatusCode.BAD_DESTINATION_ADDRESS_SYNTAX));
            }
        }
    }

    /** Index just past the end of the header block, or -1 when there is no blank line at all. */
    private int headerBlockEnd(final byte[] raw) {
        final String text = new String(raw, StandardCharsets.ISO_8859_1);
        final int crlf = text.indexOf("\r\n\r\n");
        if (crlf >= 0) {
            return crlf;
        }
        return text.indexOf("\n\n");
    }
}
