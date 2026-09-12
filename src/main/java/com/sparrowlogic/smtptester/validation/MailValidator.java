package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every compliance rule over a received message.
 *
 * <p>The rules are split by the layer they police — the SMTP transport encoding (RFC 5321), the
 * message header syntax (RFC 5322), and the MIME structure (RFC 2045/2046) — because those are
 * three genuinely different failure modes and a developer reading the report needs to know which
 * one they are looking at.
 */
public class MailValidator {

    private final List<ComplianceRule> rules;

    public MailValidator(final List<ComplianceRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The standard rule set, with TLS reporting disabled. */
    public static MailValidator standard() {
        return standard(false);
    }

    /**
     * The standard rule set.
     *
     * @param tlsOffered whether the listener advertises STARTTLS, which decides whether a plaintext
     *     delivery is worth remarking on at all
     */
    public static MailValidator standard(final boolean tlsOffered) {
        return standard(tlsOffered, null);
    }

    /**
     * The standard rule set, told which certificate the listener presents so that the RFC 7817
     * rules can say whether a client's requested name is actually covered by it.
     *
     * @param tlsOffered whether the listener advertises STARTTLS
     * @param identity the certificate the listener presents, or null when TLS is off
     */
    public static MailValidator standard(final boolean tlsOffered,
            final @Nullable CertificateIdentity identity) {
        return new MailValidator(List.of(new TransportRules(), new HeaderRules(), new MimeRules(),
                new TlsRules(tlsOffered, identity)));
    }

    /** Runs every rule and collects the findings. */
    public ValidationReport validate(final byte[] raw, final Envelope envelope,
            final ParsedMessage parsed, final SessionInfo session) {
        final List<Finding> findings = new ArrayList<>();
        for (final ComplianceRule rule : this.rules) {
            rule.check(raw, envelope, parsed, session, findings::add);
        }
        return new ValidationReport(findings);
    }
}
