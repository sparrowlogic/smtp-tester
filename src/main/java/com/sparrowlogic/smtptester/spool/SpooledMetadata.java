package com.sparrowlogic.smtptester.spool;

import com.sparrowlogic.smtptester.core.AttachmentPart;
import com.sparrowlogic.smtptester.validation.Finding;
import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * The sidecar written beside each spooled message.
 *
 * <p>It exists because the envelope is not recoverable from the message bytes — {@code MAIL FROM}
 * and {@code RCPT TO} live only in the SMTP conversation — and because a diff of two spool
 * directories should show the compliance verdict changing, not just the payload.
 *
 * @param id the message id, which is also the directory name suffix
 * @param receivedAt ISO-8601 arrival time
 * @param envelopeFrom the reverse-path
 * @param envelopeRecipients the forward-paths
 * @param remoteAddress the delivering peer
 * @param helo the client's EHLO argument
 * @param tls whether the message arrived over STARTTLS
 * @param subject the decoded subject
 * @param sizeBytes octets transferred
 * @param sha512 digest of the raw message
 * @param rejected whether the server refused it
 * @param validationStatus PASS, WARN or FAIL
 * @param findings the compliance findings
 * @param attachments attachment metadata, including each part's own digest
 */
public record SpooledMetadata(
        String id,
        String receivedAt,
        String envelopeFrom,
        List<String> envelopeRecipients,
        String remoteAddress,
        @Nullable String helo,
        boolean tls,
        @Nullable String subject,
        int sizeBytes,
        String sha512,
        boolean rejected,
        String validationStatus,
        List<Finding> findings,
        List<AttachmentPart> attachments) {
}
