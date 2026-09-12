package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.smtp.ServerCertificate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * Publishes the STARTTLS certificate so a client can be pointed at it.
 *
 * <p>This exists because the certificate is generated at startup rather than committed: without an
 * endpoint to fetch it from, nobody could add it to a trust store, and an RFC 7817 identity check
 * against this server could never succeed on purpose — only by the client skipping verification,
 * which is the very thing the TLS support is meant to catch.
 */
@RestController
@RequestMapping("${smtp-tester.web.base-path:}/api/v1/tls")
public class TlsInfoController {

    private final ServerCertificate certificate;

    public TlsInfoController(final ServerCertificate certificate) {
        this.certificate = certificate;
    }

    /** The certificate in PEM form, ready to drop into a trust store. */
    @GetMapping(value = "/certificate", produces = "application/x-pem-file")
    public ResponseEntity<String> pem() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-pem-file"))
                .body(this.certificate.toPem());
    }

    /** What an RFC 7817 client would match against. */
    @GetMapping("/identity")
    public ResponseEntity<CertificateIdentityView> identity() {
        return ResponseEntity.ok(new CertificateIdentityView(
                this.certificate.subject(),
                this.certificate.dnsNames(),
                this.certificate.ipAddresses(),
                this.certificate.certificate().getNotAfter().toInstant().toString(),
                this.certificate.reliesOnDeprecatedCommonName()));
    }

    /**
     * @param subject the certificate's subject distinguished name
     * @param dnsNames the subjectAltName dNSName entries RFC 7817 has clients match against
     * @param ipAddresses the subjectAltName iPAddress entries
     * @param notAfter when the certificate expires
     * @param reliesOnDeprecatedCommonName true when there is no dNSName, which RFC 7817 clients
     *     must reject rather than falling back to the CN
     */
    public record CertificateIdentityView(
            String subject,
            List<String> dnsNames,
            List<String> ipAddresses,
            String notAfter,
            boolean reliesOnDeprecatedCommonName) {
    }
}
