package com.sparrowlogic.smtptester.smtp;

import org.jspecify.annotations.Nullable;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSession;
import java.util.List;

/**
 * Reads the facts an RFC 7817 identity check depends on out of a negotiated TLS session.
 *
 * <p>RFC 7817 defines how an email client verifies the <em>server's</em> identity: the reference
 * identifier is the DNS name the client set out to reach, matched against the certificate's
 * subjectAltName dNSName entries, with the CN-ID fallback deprecated. A server sits on the other
 * side of that check and cannot see its outcome — only whether the handshake completed. What it can
 * see is the name the client asked for via SNI, which is the same reference identifier the client
 * will check the certificate against. Capturing it is what lets this server report whether the
 * certificate it presented would have satisfied the check.
 */
public final class TlsSessionFacts {

    private TlsSessionFacts() {
    }

    /** The SNI names the client offered, which RFC 7817 treats as its reference identifiers. */
    public static List<String> requestedServerNames(final @Nullable SSLSession session) {
        if (!(session instanceof final ExtendedSSLSession extended)) {
            return List.of();
        }
        final List<SNIServerName> requested = extended.getRequestedServerNames();
        return requested.stream()
                .filter(SNIHostName.class::isInstance)
                .map(name -> ((SNIHostName) name).getAsciiName())
                .toList();
    }
}
