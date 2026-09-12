package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.validation.CertificateIdentity;
import org.jspecify.annotations.Nullable;
import javax.net.ssl.SSLContext;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The certificate this server presents for STARTTLS, and the RFC 7817 facts about it.
 *
 * <p>RFC 7817 section 3 is specific about what a conforming email client checks: the reference
 * identifier is the DNS name the client set out to reach, it is matched against the certificate's
 * subjectAltName dNSName entries, and the CN-ID fallback is deprecated. A server can therefore say
 * something genuinely useful without observing the client at all — whether the certificate it
 * presents would satisfy that check for a given name.
 */
public record ServerCertificate(X509Certificate certificate, List<String> dnsNames,
        List<String> ipAddresses, SSLContext sslContext) implements CertificateIdentity {

    private static final int SAN_DNS_NAME = 2;
    private static final int SAN_IP_ADDRESS = 7;

    public ServerCertificate {
        dnsNames = List.copyOf(dnsNames);
        ipAddresses = List.copyOf(ipAddresses);
    }

    /** Reads the subjectAltName entries out of a certificate, which is what RFC 7817 matches on. */
    public static ServerCertificate of(final X509Certificate certificate, final SSLContext context) {
        final List<String> dns = new ArrayList<>();
        final List<String> ips = new ArrayList<>();
        for (final List<?> entry : subjectAlternativeNames(certificate)) {
            if (entry.size() < 2 || !(entry.get(1) instanceof final String value)) {
                continue;
            }
            if (Integer.valueOf(SAN_DNS_NAME).equals(entry.getFirst())) {
                dns.add(value.toLowerCase(Locale.ROOT));
            } else if (Integer.valueOf(SAN_IP_ADDRESS).equals(entry.getFirst())) {
                ips.add(value);
            }
        }
        return new ServerCertificate(certificate, dns, ips, context);
    }

    private static Collection<List<?>> subjectAlternativeNames(final X509Certificate certificate) {
        try {
            final Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            return names == null ? List.of() : names;
        } catch (final CertificateParsingException e) {
            return List.of();
        }
    }

    /**
     * Whether a client asking for this name would find it in the certificate.
     *
     * <p>Wildcards are matched the way RFC 6125 section 6.4.3 allows: a leading {@code *} covers
     * exactly one label, never a dot.
     */
    @Override
    public boolean covers(final @Nullable String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return false;
        }
        final String wanted = requestedName.toLowerCase(Locale.ROOT);
        return this.dnsNames.stream().anyMatch(name -> matches(name, wanted))
                || this.ipAddresses.contains(requestedName);
    }

    private static boolean matches(final String presented, final String wanted) {
        if (!presented.startsWith("*.")) {
            return presented.equals(wanted);
        }
        final String suffix = presented.substring(1);
        return wanted.endsWith(suffix)
                && !wanted.substring(0, wanted.length() - suffix.length()).contains(".");
    }

    /**
     * True when the certificate carries no dNSName at all, in which case a client following RFC
     * 7817 must reject it: the CN-ID fallback it would otherwise use is deprecated.
     */
    @Override
    public boolean reliesOnDeprecatedCommonName() {
        return this.dnsNames.isEmpty();
    }

    /** The certificate in PEM form, so a client can be told to trust it. */
    public String toPem() {
        try {
            final String body = Base64.getMimeEncoder(64, new byte[] {'\n'})
                    .encodeToString(this.certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
        } catch (final CertificateEncodingException e) {
            throw new IllegalStateException("Cannot encode the server certificate", e);
        }
    }

    /** The subject distinguished name, for display. */
    public String subject() {
        return this.certificate.getSubjectX500Principal().getName();
    }
}
