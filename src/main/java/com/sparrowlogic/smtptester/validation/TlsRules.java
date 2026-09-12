package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reports on the transport security of the delivering connection, in RFC 7817 terms.
 *
 * <p>It is worth being precise about what a server can and cannot claim here. RFC 7817 defines how
 * an email <em>client</em> verifies a <em>server's</em> identity: it takes the DNS name it set out
 * to reach as the reference identifier and matches it against the certificate's subjectAltName
 * dNSName entries, the CN-ID fallback being deprecated. The server is on the far side of that
 * check and cannot observe its outcome — only that the handshake completed at all.
 *
 * <p>So these rules report the facts the server does hold: whether TLS was used, at what version,
 * and which name the client asked for via SNI. That name is the client's reference identifier, so
 * comparing it against the certificate actually presented answers the one question a developer
 * needs — would a client doing the check properly have accepted this connection?
 */
public class TlsRules implements ComplianceRule {

    private static final String RFC7817 = "RFC 7817 section 3";

    /** TLS versions with known weaknesses that a modern client should refuse. */
    private static final List<String> OBSOLETE_PROTOCOLS = List.of("SSLv3", "TLSv1", "TLSv1.1");

    private final boolean tlsOffered;
    private final @Nullable CertificateIdentity identity;

    public TlsRules(final boolean tlsOffered) {
        this(tlsOffered, null);
    }

    public TlsRules(final boolean tlsOffered, final @Nullable CertificateIdentity identity) {
        this.tlsOffered = tlsOffered;
        this.identity = identity;
    }

    @Override
    public void check(final byte[] raw, final Envelope envelope, final ParsedMessage parsed,
            final SessionInfo session, final Consumer<Finding> sink) {
        if (!session.tls()) {
            this.checkPlaintext(sink);
            return;
        }
        this.checkProtocol(session, sink);
        this.checkReferenceIdentifier(session, sink);
    }

    /**
     * Only worth saying when STARTTLS was on the table. With TLS disabled every delivery is
     * plaintext by design and a finding on each one would be noise.
     */
    private void checkPlaintext(final Consumer<Finding> sink) {
        if (this.tlsOffered) {
            sink.accept(Finding.warning("RFC3207_STARTTLS_NOT_USED",
                    "The server advertised STARTTLS but the client delivered in the clear; "
                            + "credentials and message content crossed the connection unprotected",
                    null, "RFC 3207 section 2", EnhancedStatusCode.SECURITY_STATUS));
        }
    }

    private void checkProtocol(final SessionInfo session, final Consumer<Finding> sink) {
        final String protocol = session.protocol();
        if (protocol != null && OBSOLETE_PROTOCOLS.contains(protocol)) {
            sink.accept(Finding.warning("TLS_OBSOLETE_PROTOCOL",
                    "The client negotiated an obsolete TLS version",
                    protocol + " (" + session.cipherSuite() + ")",
                    "RFC 8996", EnhancedStatusCode.SECURITY_FEATURES_NOT_SUPPORTED));
        }
    }

    /**
     * The SNI name is the client's reference identifier. Its absence is not a fault — SNI is not
     * required, and a client may verify against the name it dialled without announcing it — but it
     * does mean the server cannot confirm the identity check would have succeeded.
     */
    private void checkReferenceIdentifier(final SessionInfo session, final Consumer<Finding> sink) {
        final List<String> requested = session.sniServerNames();
        if (requested.isEmpty()) {
            sink.accept(Finding.info("RFC7817_NO_SNI_REFERENCE_IDENTIFIER",
                    "The client sent no SNI server name, so the reference identifier it will check "
                            + "the certificate against could not be observed",
                    session.protocol(), RFC7817));
            return;
        }
        sink.accept(Finding.info("RFC7817_REFERENCE_IDENTIFIER",
                "The client's RFC 7817 reference identifier, matched against the certificate's "
                        + "subjectAltName dNSName entries",
                String.join(", ", requested), RFC7817));
        this.checkCertificateCovers(requested, sink);
    }

    /**
     * The finding that actually matters: the client named a host, and the certificate this server
     * presented does or does not cover it. If it does not, a client performing the RFC 7817 check
     * correctly would have aborted — so a delivery that succeeded anyway means the client skipped
     * verification, which is worth knowing before the same code talks to a real server.
     */
    private void checkCertificateCovers(final List<String> requested, final Consumer<Finding> sink) {
        final CertificateIdentity certificate = this.identity;
        if (certificate == null) {
            return;
        }
        if (certificate.reliesOnDeprecatedCommonName()) {
            sink.accept(Finding.warning("RFC7817_NO_SUBJECT_ALT_NAME",
                    "The presented certificate has no subjectAltName dNSName entry, so a client "
                            + "following RFC 7817 must reject it; the CN-ID fallback is deprecated",
                    null, RFC7817, EnhancedStatusCode.SECURITY_FEATURES_NOT_SUPPORTED));
            return;
        }
        final List<String> uncovered = requested.stream()
                .filter(name -> !certificate.covers(name))
                .toList();
        if (!uncovered.isEmpty()) {
            sink.accept(Finding.warning("RFC7817_IDENTITY_MISMATCH",
                    "The client asked for a server name the presented certificate does not cover, so "
                            + "an RFC 7817 identity check would have failed; this client is not "
                            + "verifying the certificate it is given",
                    "requested " + String.join(", ", uncovered)
                            + "; certificate covers " + String.join(", ", certificate.dnsNames()),
                    RFC7817, EnhancedStatusCode.SECURITY_STATUS));
        }
    }
}
