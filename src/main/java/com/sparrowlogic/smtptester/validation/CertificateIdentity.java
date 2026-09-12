package com.sparrowlogic.smtptester.validation;

import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * The identity the server presents over TLS, as RFC 7817 defines identity.
 *
 * <p>An interface rather than the certificate itself so the rules stay independent of how the
 * certificate is obtained — generated at startup, loaded from a keystore, or stubbed in a test.
 */
public interface CertificateIdentity {

    /** The subjectAltName dNSName entries, which are what RFC 7817 has a client match against. */
    List<String> dnsNames();

    /** Whether a client asking for this name would find it in the certificate. */
    boolean covers(@Nullable String requestedName);

    /** True when there is no dNSName at all, leaving only the CN-ID that RFC 7817 deprecates. */
    boolean reliesOnDeprecatedCommonName();
}
