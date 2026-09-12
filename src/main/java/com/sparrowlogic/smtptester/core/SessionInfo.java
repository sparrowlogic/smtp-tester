package com.sparrowlogic.smtptester.core;

import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * What the transport layer could observe about the delivering connection.
 *
 * <p>A server can only report what it saw. It cannot know whether the client verified the
 * certificate it was presented, so RFC 7817 reporting is framed around facts the server does hold:
 * whether TLS was negotiated, at what version and cipher, and which server name the client asked
 * for via SNI. That last one is the input to the identity-check rule, because RFC 7817 says the
 * reference identifier is the name the client intended to reach.
 *
 * @param tls whether {@code STARTTLS} was negotiated before {@code DATA}
 * @param protocol the negotiated TLS version, e.g. {@code TLSv1.3}
 * @param cipherSuite the negotiated cipher suite
 * @param sniServerNames the SNI names the client offered, which RFC 7817 treats as the reference
 *     identifiers it will check the certificate against
 * @param clientCertificateSubject the subject DN when the client presented a certificate
 */
public record SessionInfo(
        boolean tls,
        @Nullable String protocol,
        @Nullable String cipherSuite,
        List<String> sniServerNames,
        @Nullable String clientCertificateSubject) {

    public SessionInfo {
        sniServerNames = List.copyOf(sniServerNames);
    }

    /** A plaintext session, which is what every connection looks like with TLS disabled. */
    public static SessionInfo plaintext() {
        return new SessionInfo(false, null, null, List.of(), null);
    }
}
