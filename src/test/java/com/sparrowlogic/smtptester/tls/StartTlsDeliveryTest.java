package com.sparrowlogic.smtptester.tls;

import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import com.sparrowlogic.smtptester.smtp.ServerCertificate;
import com.sparrowlogic.smtptester.smtp.SmtpReceiver;
import com.sparrowlogic.smtptester.validation.Finding;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STARTTLS end to end, against the certificate the server mints at startup.
 *
 * <p>Runs with TLS on, which the rest of the suite does not, so the default configuration stays
 * the one every other test exercises.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "smtp-tester.smtp.tls.mode=OPTIONAL",
        "smtp-tester.smtp.tls.hostname=localhost"
})
class StartTlsDeliveryTest {

    @Autowired
    private SmtpReceiver receiver;

    @Autowired
    private MailboxService mailbox;

    @Autowired
    private ServerCertificate certificate;

    @LocalServerPort
    private int httpPort;

    private RestClient rest;

    @BeforeEach
    void setUp() {
        this.mailbox.clearAll();
        this.rest = RestClient.create("http://localhost:" + this.httpPort);
    }

    /** RFC 7817 matches on dNSName entries; a certificate without them must be rejected by clients. */
    @Test
    void theGeneratedCertificateCarriesSubjectAltNamesRatherThanRelyingOnTheCommonName() {
        assertThat(this.certificate.dnsNames()).contains("localhost", "smtp-tester");
        assertThat(this.certificate.ipAddresses()).contains("127.0.0.1");
        assertThat(this.certificate.reliesOnDeprecatedCommonName()).isFalse();
    }

    @Test
    void nameMatchingFollowsTheReferenceIdentifierRules() {
        assertThat(this.certificate.covers("localhost")).isTrue();
        assertThat(this.certificate.covers("LOCALHOST")).isTrue();
        assertThat(this.certificate.covers("mail.example.com")).isFalse();
        assertThat(this.certificate.covers(null)).isFalse();
        assertThat(this.certificate.covers("")).isFalse();
    }

    @Test
    void theCertificateIsPublishedAsPemSoAClientCanTrustIt() {
        final String pem = this.rest.get().uri("/api/v1/tls/certificate")
                .retrieve().body(String.class);
        assertThat(pem).startsWith("-----BEGIN CERTIFICATE-----")
                .contains("-----END CERTIFICATE-----");
    }

    @Test
    void theIdentityEndpointReportsWhatAnRfc7817ClientWouldMatchOn() {
        final TlsIdentityResponse identity = this.rest.get().uri("/api/v1/tls/identity")
                .retrieve().body(TlsIdentityResponse.class);
        assertThat(identity).isNotNull();
        assertThat(identity.dnsNames()).contains("localhost");
        assertThat(identity.reliesOnDeprecatedCommonName()).isFalse();
        assertThat(identity.subject()).contains("localhost");
    }

    @Test
    void aClientCanDeliverOverStartTlsAndTheSessionIsRecorded() throws Exception {
        Transport.send(this.message("delivered over TLS"));

        final MailMessage stored = this.mailbox.list(MailQuery.recent(1)).getFirst();
        assertThat(stored.session()).isNotNull();
        assertThat(stored.session().tls()).isTrue();
        assertThat(stored.session().protocol()).startsWith("TLS");
        assertThat(stored.session().cipherSuite()).isNotBlank();
        assertThat(stored.envelope().tls()).isTrue();
    }

    /** The server can only report what it saw; SNI is the client's reference identifier. */
    @Test
    void theClientsSniNameIsCapturedAsItsReferenceIdentifier() throws Exception {
        Transport.send(this.message("sni please"));

        final MailMessage stored = this.mailbox.list(MailQuery.recent(1)).getFirst();
        final List<String> rules = stored.validation().findings().stream().map(Finding::rule).toList();
        assertThat(rules).containsAnyOf("RFC7817_REFERENCE_IDENTIFIER", "RFC7817_NO_SNI_REFERENCE_IDENTIFIER");
        if (!stored.session().sniServerNames().isEmpty()) {
            assertThat(stored.session().sniServerNames()).contains("localhost");
            assertThat(rules).contains("RFC7817_REFERENCE_IDENTIFIER")
                    .doesNotContain("RFC7817_IDENTITY_MISMATCH");
        }
    }

    @Test
    void plaintextDeliveryIsFlaggedWhenStartTlsWasOffered() throws Exception {
        final Properties properties = this.baseProperties();
        // Both keys have to go: leaving `required` set makes jakarta.mail negotiate STARTTLS even
        // with `enable` false, which is what this test needs to avoid.
        properties.remove("mail.smtp.starttls.required");
        properties.put("mail.smtp.starttls.enable", "false");
        Transport.send(this.message("in the clear", Session.getInstance(properties)));

        final MailMessage stored = this.mailbox.list(MailQuery.recent(1)).getFirst();
        assertThat(stored.session().tls()).isFalse();
        assertThat(stored.validation().findings()).extracting(Finding::rule)
                .contains("RFC3207_STARTTLS_NOT_USED");
    }

    private Properties baseProperties() {
        final Properties properties = new Properties();
        properties.put("mail.smtp.host", "localhost");
        properties.put("mail.smtp.port", String.valueOf(this.receiver.port()));
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.starttls.required", "true");
        // The certificate is self-signed and minted at startup, so this client trusts it blindly;
        // what is under test here is the server's reporting, not the JDK's trust engine.
        properties.put("mail.smtp.ssl.trust", "*");
        return properties;
    }

    private MimeMessage message(final String subject) throws Exception {
        return this.message(subject, Session.getInstance(this.baseProperties()));
    }

    private MimeMessage message(final String subject, final Session session) throws Exception {
        final MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("app@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO,
                InternetAddress.parse("alice@example.com"));
        message.setSubject(subject);
        message.setSentDate(new Date());
        message.setText("body");
        return message;
    }

    /**
     * @param subject the certificate subject
     * @param dnsNames subjectAltName dNSName entries
     * @param ipAddresses subjectAltName iPAddress entries
     * @param notAfter expiry
     * @param reliesOnDeprecatedCommonName whether there is no dNSName at all
     */
    record TlsIdentityResponse(String subject, List<String> dnsNames, List<String> ipAddresses,
            String notAfter, boolean reliesOnDeprecatedCommonName) {
    }
}
