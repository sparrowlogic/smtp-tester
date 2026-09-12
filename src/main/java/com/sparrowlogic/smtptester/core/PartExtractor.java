package com.sparrowlogic.smtptester.core;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Pulls the decoded bytes of one MIME part back out of a raw message on demand.
 *
 * <p>Attachments are not retained in memory — only their metadata and digest are — so downloads,
 * spooling and the MCP attachment tool all come back through here. The walk order is identical to
 * {@link MimeParser}'s, which is what makes {@link AttachmentPart#index()} a stable handle.
 */
public class PartExtractor {

    private final Session session = Session.getInstance(new Properties());

    /** The decoded content of the leaf part at the given depth-first index. */
    public Optional<byte[]> extract(final byte[] raw, final int index) {
        try {
            final MimeMessage message = new MimeMessage(this.session, new ByteArrayInputStream(raw));
            final List<Part> leaves = new ArrayList<>();
            this.collectLeaves(message, leaves);
            if (index < 0 || index >= leaves.size()) {
                return Optional.empty();
            }
            return Optional.of(this.read(leaves.get(index)));
        } catch (final MessagingException | IOException e) {
            return Optional.empty();
        }
    }

    private void collectLeaves(final Part part, final List<Part> leaves)
            throws MessagingException, IOException {
        final Object content = part.getContent();
        if (content instanceof final Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                final BodyPart child = multipart.getBodyPart(i);
                this.collectLeaves(child, leaves);
            }
            return;
        }
        leaves.add(part);
    }

    private byte[] read(final Part part) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
