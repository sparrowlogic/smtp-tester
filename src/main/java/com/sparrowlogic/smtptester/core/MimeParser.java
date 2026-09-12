package com.sparrowlogic.smtptester.core;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.jspecify.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * Decodes raw RFC 5322 bytes into the view the UI, the API and the MCP tools read.
 *
 * <p>The underlying jakarta.mail session is configured to be as permissive as it can be. That is
 * deliberate and slightly counter-intuitive for a validation tool: the parser's job is to show the
 * developer what they sent even when it is malformed, and a parser that throws on a bad message
 * turns "here is what is wrong with your email" into "500". Strictness lives in the validation
 * rules, which read the raw octets anyway.
 */
public class MimeParser {

    private static final String ENABLED = "true";
    private static final String DISABLED = "false";

    /** Header values that are stored raw rather than RFC 2047 decoded, so rules see the wire form. */
    private static final Properties LENIENT = lenientProperties();

    private final Session session;

    public MimeParser() {
        this.session = Session.getInstance(LENIENT);
    }

    private static Properties lenientProperties() {
        final Properties properties = new Properties();
        properties.setProperty("mail.mime.address.strict", DISABLED);
        properties.setProperty("mail.mime.multipart.allowempty", ENABLED);
        properties.setProperty("mail.mime.base64.ignoreerrors", ENABLED);
        properties.setProperty("mail.mime.ignoreunknownencoding", ENABLED);
        properties.setProperty("mail.mime.decodetext.strict", DISABLED);
        properties.setProperty("mail.mime.parameters.strict", DISABLED);
        properties.setProperty("mail.mime.multipart.ignoreexistingboundaryparameter", DISABLED);
        return properties;
    }

    /** Parses the message, never throwing; failures surface as {@link ParsedMessage#parseError()}. */
    public ParsedMessage parse(final byte[] raw) {
        try {
            return this.parseInternal(raw);
        } catch (final MessagingException | IOException e) {
            return this.unparseable(e);
        }
    }

    private ParsedMessage parseInternal(final byte[] raw) throws MessagingException, IOException {
        final MimeMessage message = new MimeMessage(this.session, new ByteArrayInputStream(raw));
        final BodyCollector collector = new BodyCollector();
        @Nullable String walkError = null;
        try {
            this.walk(message, collector);
        } catch (final MessagingException | IOException e) {
            walkError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return new ParsedMessage(
                this.headers(message),
                this.addresses(message, "From"),
                this.addresses(message, "To"),
                this.addresses(message, "Cc"),
                this.addresses(message, "Bcc"),
                this.addresses(message, "Reply-To"),
                this.decode(message.getSubject()),
                this.sentAt(message),
                message.getHeader("Message-ID", null),
                collector.text,
                collector.html,
                collector.attachments,
                walkError);
    }

    private ParsedMessage unparseable(final Exception cause) {
        return new ParsedMessage(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, List.of(),
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    private List<ParsedMessage.Header> headers(final MimeMessage message) throws MessagingException {
        final List<ParsedMessage.Header> headers = new ArrayList<>();
        final Enumeration<Header> all = message.getAllHeaders();
        while (all.hasMoreElements()) {
            final Header header = all.nextElement();
            headers.add(new ParsedMessage.Header(header.getName(), header.getValue()));
        }
        return headers;
    }

    /**
     * Addresses are read from the raw header and parsed leniently rather than via
     * {@code getFrom()}/{@code getRecipients()}, which throw on the malformed input this tool
     * exists to capture.
     */
    private List<String> addresses(final MimeMessage message, final String field) throws MessagingException {
        final String value = message.getHeader(field, ",");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            final Address[] parsed = InternetAddress.parse(value, false);
            return java.util.Arrays.stream(parsed)
                    .map(a -> ((InternetAddress) a).getAddress())
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (final MessagingException e) {
            return List.of(value.trim());
        }
    }

    private @Nullable Instant sentAt(final MimeMessage message) {
        try {
            final Date date = message.getSentDate();
            return date == null ? null : date.toInstant();
        } catch (final MessagingException e) {
            return null;
        }
    }

    private @Nullable String decode(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(value);
        } catch (final IOException e) {
            return value;
        }
    }

    private void walk(final Part part, final BodyCollector collector)
            throws MessagingException, IOException {
        final Object content = part.getContent();
        if (content instanceof final Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                final BodyPart child = multipart.getBodyPart(i);
                this.walk(child, collector);
            }
            return;
        }
        collector.leaf(part);
    }

    /** Accumulates bodies and attachment metadata across a depth-first walk of the MIME tree. */
    private static final class BodyCollector {

        private final List<AttachmentPart> attachments = new ArrayList<>();
        private @Nullable String text;
        private @Nullable String html;
        private int leafIndex;

        private void leaf(final Part part) throws MessagingException, IOException {
            final int index = this.leafIndex++;
            final String mimeType = MimeTypes.baseType(part.getContentType());
            final String filename = part.getFileName();
            final String disposition = part.getDisposition();
            if (this.claimBody(part, mimeType, filename, disposition)) {
                return;
            }
            final byte[] content = this.read(part);
            this.attachments.add(new AttachmentPart(
                    index,
                    filename,
                    mimeType,
                    disposition == null ? "unknown" : disposition.toLowerCase(java.util.Locale.ROOT),
                    this.firstHeader(part, "Content-ID"),
                    content.length,
                    Digests.sha512(content)));
        }

        /**
         * A leaf counts as a body only when it is textual, unnamed, and not explicitly marked as an
         * attachment. A {@code text/plain} part with a filename is a {@code .txt} attachment, and
         * treating it as the body would hide it from the attachment list.
         */
        private boolean claimBody(final Part part, final String mimeType, final @Nullable String filename,
                final @Nullable String disposition) throws MessagingException, IOException {
            final boolean named = filename != null && !filename.isBlank();
            if (named || Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                return false;
            }
            if ("text/plain".equals(mimeType) && this.text == null) {
                this.text = this.asText(part);
                return true;
            }
            if ("text/html".equals(mimeType) && this.html == null) {
                this.html = this.asText(part);
                return true;
            }
            return false;
        }

        private String asText(final Part part) throws MessagingException, IOException {
            final Object content = part.getContent();
            if (content instanceof final String string) {
                return string;
            }
            return new String(this.read(part), java.nio.charset.StandardCharsets.UTF_8);
        }

        private @Nullable String firstHeader(final Part part, final String name)
                throws MessagingException {
            final String[] values = part.getHeader(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        private byte[] read(final Part part) throws MessagingException, IOException {
            try (InputStream in = part.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toByteArray();
            }
        }
    }
}
