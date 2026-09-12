package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.MimeTypes;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MIME structure rules from RFC 2045 and RFC 2046.
 *
 * <p>The transfer-encoding check is the valuable one: declaring {@code 7bit} and then putting
 * UTF-8 in the body is the classic way accented characters turn into mojibake between one hop and
 * the next, and it is invisible on a server that simply decodes whatever it is given.
 */
public class MimeRules implements ComplianceRule {

    private static final String RFC2045 = "RFC 2045 section 6";
    private static final String RFC2046_MULTIPART = "RFC 2046 section 5.1.1";
    private static final String DELIMITER = "--";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String TEXT_HTML = "text/html";

    /** The transfer encodings RFC 2045 section 6.1 defines. */
    private static final List<String> KNOWN_ENCODINGS =
            List.of("7bit", "8bit", "binary", "quoted-printable", "base64");

    /** Encodings that promise the body contains no octet with the high bit set. */
    private static final List<String> SEVEN_BIT_ENCODINGS = List.of("7bit", "quoted-printable", "base64");

    private static final Pattern BOUNDARY = Pattern.compile("boundary\\s*=\\s*\"?([^\";\\r\\n]+)\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([^\";\\r\\n]+)\"?",
            Pattern.CASE_INSENSITIVE);

    @Override
    public void check(final byte[] raw, final Envelope envelope, final ParsedMessage parsed,
            final SessionInfo session, final Consumer<Finding> sink) {
        this.checkParseFailure(parsed, sink);
        this.checkMimeVersion(parsed, sink);
        this.checkTransferEncoding(raw, parsed, sink);
        this.checkMultipartBoundary(raw, parsed, sink);
        this.checkCharset(parsed, sink);
        this.checkBodyPresent(parsed, sink);
        this.checkHtmlAlternative(parsed, sink);
        this.checkAttachmentNames(parsed, sink);
    }

    private void checkParseFailure(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final String parseError = parsed.parseError();
        if (parseError != null) {
            sink.accept(Finding.error("MIME_UNPARSEABLE",
                    "The message could not be parsed as MIME",
                    parseError, RFC2045, EnhancedStatusCode.MEDIA_NOT_SUPPORTED));
        }
    }

    /** RFC 2045 section 4: a MIME message must declare {@code MIME-Version: 1.0}. */
    private void checkMimeVersion(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final String version = parsed.header("MIME-Version");
        final boolean usesMime = parsed.header(CONTENT_TYPE) != null
                || parsed.header("Content-Transfer-Encoding") != null;
        if (version == null) {
            if (usesMime) {
                sink.accept(Finding.warning("RFC2045_MISSING_MIME_VERSION",
                        "MIME headers are present but MIME-Version is not; agents may treat the body "
                                + "as plain text and show the raw markup",
                        null, "RFC 2045 section 4", EnhancedStatusCode.MEDIA_ERROR));
            }
            return;
        }
        if (!"1.0".equals(version.trim())) {
            sink.accept(Finding.warning("RFC2045_UNEXPECTED_MIME_VERSION",
                    "MIME-Version is not 1.0", version, "RFC 2045 section 4",
                    EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkTransferEncoding(final byte[] raw, final ParsedMessage parsed,
            final Consumer<Finding> sink) {
        final String declared = parsed.header("Content-Transfer-Encoding");
        if (declared == null) {
            return;
        }
        final String encoding = declared.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN_ENCODINGS.contains(encoding)) {
            sink.accept(Finding.error("RFC2045_UNKNOWN_TRANSFER_ENCODING",
                    "Unrecognised Content-Transfer-Encoding; RFC 2045 defines only 7bit, 8bit, binary, "
                            + "quoted-printable and base64",
                    declared, RFC2045, EnhancedStatusCode.MEDIA_NOT_SUPPORTED));
            return;
        }
        if (SEVEN_BIT_ENCODINGS.contains(encoding) && this.bodyHasHighBitOctets(raw)) {
            sink.accept(Finding.error("RFC2045_ENCODING_CONTENT_MISMATCH",
                    "Content-Transfer-Encoding promises 7-bit content but the body contains 8-bit "
                            + "octets; a relay that honours the declaration will corrupt them",
                    "declared " + encoding, RFC2045, EnhancedStatusCode.CONVERSION_REQUIRED_AND_PROHIBITED));
        }
    }

    /**
     * RFC 2046 section 5.1.1: a multipart type must carry a boundary parameter, that boundary must
     * actually delimit the parts, and the final delimiter must be closed with a trailing {@code --}.
     */
    private void checkMultipartBoundary(final byte[] raw, final ParsedMessage parsed,
            final Consumer<Finding> sink) {
        final String contentType = parsed.header(CONTENT_TYPE);
        if (contentType == null || !MimeTypes.baseType(contentType).startsWith("multipart/")) {
            return;
        }
        final Matcher matcher = BOUNDARY.matcher(contentType);
        if (!matcher.find()) {
            sink.accept(Finding.error("RFC2046_MISSING_BOUNDARY",
                    "A multipart Content-Type has no boundary parameter, so the parts cannot be split",
                    contentType, RFC2046_MULTIPART, EnhancedStatusCode.MEDIA_ERROR));
            return;
        }
        final String boundary = matcher.group(1).trim();
        final String body = new String(raw, StandardCharsets.ISO_8859_1);
        final String delimiter = DELIMITER + boundary;
        if (!body.contains(delimiter)) {
            sink.accept(Finding.error("RFC2046_BOUNDARY_NOT_FOUND",
                    "The declared multipart boundary never appears in the body",
                    boundary, RFC2046_MULTIPART, EnhancedStatusCode.MEDIA_ERROR));
        } else if (!body.contains(delimiter + DELIMITER)) {
            sink.accept(Finding.error("RFC2046_UNCLOSED_BOUNDARY",
                    "The multipart body has no closing delimiter; the last part is unterminated",
                    "expected " + delimiter + DELIMITER, RFC2046_MULTIPART,
                    EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkCharset(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final String contentType = parsed.header(CONTENT_TYPE);
        if (contentType == null || !MimeTypes.baseType(contentType).startsWith("text/")) {
            return;
        }
        final Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) {
            sink.accept(Finding.warning("RFC2046_MISSING_CHARSET",
                    "A text/* part declares no charset, so recipients must guess; RFC 2046 says to "
                            + "assume us-ascii, which is wrong for most real content",
                    contentType, "RFC 2046 section 4.1.2", EnhancedStatusCode.MEDIA_ERROR));
            return;
        }
        final String charset = matcher.group(1).trim();
        if (!this.charsetSupported(charset)) {
            sink.accept(Finding.warning("RFC2046_UNSUPPORTED_CHARSET",
                    "The declared charset is not one this JVM recognises",
                    charset, "RFC 2046 section 4.1.2", EnhancedStatusCode.MEDIA_NOT_SUPPORTED));
        }
    }

    private void checkBodyPresent(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final boolean noText = parsed.text() == null || parsed.text().isBlank();
        final boolean noHtml = parsed.html() == null || parsed.html().isBlank();
        if (noText && noHtml && parsed.attachments().isEmpty()) {
            sink.accept(Finding.warning("MIME_EMPTY_BODY",
                    "The message has no text body, no HTML body and no attachments",
                    null, "RFC 5322 section 3.5", EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    /**
     * Not an RFC violation, but an HTML-only message is treated as a spam signal by most filters
     * and is unreadable in a text-only client, so it belongs in a report a developer reads before
     * shipping.
     */
    private void checkHtmlAlternative(final ParsedMessage parsed, final Consumer<Finding> sink) {
        final boolean hasHtml = parsed.html() != null && !parsed.html().isBlank();
        final boolean hasText = parsed.text() != null && !parsed.text().isBlank();
        if (hasHtml && !hasText) {
            sink.accept(Finding.warning("DELIVERABILITY_HTML_WITHOUT_TEXT",
                    "HTML body with no text/plain alternative; send multipart/alternative so text-only "
                            + "clients and spam filters see readable content",
                    TEXT_HTML, "RFC 2046 section 5.1.4", EnhancedStatusCode.MEDIA_ERROR));
        }
    }

    private void checkAttachmentNames(final ParsedMessage parsed, final Consumer<Finding> sink) {
        parsed.attachments().stream()
                .filter(a -> a.filename() == null || a.filename().isBlank())
                .forEach(a -> sink.accept(Finding.info("RFC2183_ATTACHMENT_WITHOUT_FILENAME",
                        "An attachment declares no filename, so recipients see an unnamed blob",
                        "part " + a.index() + " (" + a.mimeType() + ")",
                        "RFC 2183 section 2.3")));
    }

    private boolean charsetSupported(final String charset) {
        try {
            return Charset.isSupported(charset);
        } catch (final IllegalCharsetNameException | UnsupportedCharsetException e) {
            return false;
        }
    }

    private boolean bodyHasHighBitOctets(final byte[] raw) {
        final String text = new String(raw, StandardCharsets.ISO_8859_1);
        final int separator = text.indexOf("\r\n\r\n");
        final int start = separator < 0 ? 0 : separator + 4;
        for (int i = start; i < raw.length; i++) {
            if ((raw[i] & 0x80) != 0) {
                return true;
            }
        }
        return false;
    }
}
