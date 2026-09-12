package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server cannot observe whether a client verified the certificate it was handed, so every rule
 * here is phrased in terms of what the server does hold: the negotiated protocol and the SNI name.
 * The mismatch case is the one that earns its keep — a delivery that succeeded against a
 * certificate not covering the requested name means the client skipped verification entirely.
 */
class TlsRulesTest {

    private static final byte[] RAW = MailFixtures.bytes(MailFixtures.WELL_FORMED);

    private List<Finding> check(final TlsRules rules, final SessionInfo session) {
        final ParsedMessage parsed = new MimeParser().parse(RAW);
        final List<Finding> findings = new ArrayList<>();
        rules.check(RAW, MailFixtures.defaultEnvelope(), parsed, session, findings::add);
        return findings;
    }

    private static SessionInfo tls(final String protocol, final String... sniNames) {
        return new SessionInfo(true, protocol, "TLS_AES_256_GCM_SHA384", List.of(sniNames), null);
    }

    private static CertificateIdentity certificateFor(final String... names) {
        return new CertificateIdentity() {
            @Override
            public List<String> dnsNames() {
                return List.of(names);
            }

            @Override
            public boolean covers(final @Nullable String requestedName) {
                return requestedName != null && List.of(names).contains(requestedName);
            }

            @Override
            public boolean reliesOnDeprecatedCommonName() {
                return names.length == 0;
            }
        };
    }

    @Test
    void saysNothingAboutPlaintextWhenStartTlsWasNeverOffered() {
        assertThat(this.check(new TlsRules(false), SessionInfo.plaintext())).isEmpty();
    }

    @Test
    void warnsWhenStartTlsWasAdvertisedButTheClientDeliveredInTheClear() {
        assertThat(this.check(new TlsRules(true), SessionInfo.plaintext()))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.rule()).isEqualTo("RFC3207_STARTTLS_NOT_USED");
                    assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                    assertThat(finding.statusCode())
                            .isEqualTo(EnhancedStatusCode.SECURITY_STATUS);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSLv3", "TLSv1", "TLSv1.1"})
    void warnsOnAnObsoleteNegotiatedProtocol(final String protocol) {
        assertThat(this.check(new TlsRules(true), tls(protocol, "localhost")))
                .extracting(Finding::rule)
                .contains("TLS_OBSOLETE_PROTOCOL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void acceptsACurrentProtocolWithoutComplaint(final String protocol) {
        assertThat(this.check(new TlsRules(true), tls(protocol, "localhost")))
                .extracting(Finding::rule)
                .doesNotContain("TLS_OBSOLETE_PROTOCOL");
    }

    @Test
    void recordsThatNoReferenceIdentifierCouldBeObservedWhenTheClientSentNoSni() {
        assertThat(this.check(new TlsRules(true), tls("TLSv1.3")))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.rule()).isEqualTo("RFC7817_NO_SNI_REFERENCE_IDENTIFIER");
                    // Absent SNI is permitted, so this must not be raised as a fault.
                    assertThat(finding.severity()).isEqualTo(Severity.INFO);
                });
    }

    @Test
    void reportsTheReferenceIdentifierTheClientAskedFor() {
        assertThat(this.check(new TlsRules(true), tls("TLSv1.3", "mail.example.com")))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.rule()).isEqualTo("RFC7817_REFERENCE_IDENTIFIER");
                    assertThat(finding.detail()).contains("mail.example.com");
                });
    }

    @Test
    void staysQuietWhenTheCertificateCoversEveryRequestedName() {
        final TlsRules rules = new TlsRules(true, certificateFor("localhost", "smtp-tester"));

        assertThat(this.check(rules, tls("TLSv1.3", "localhost")))
                .extracting(Finding::rule)
                .containsExactly("RFC7817_REFERENCE_IDENTIFIER");
    }

    /** The finding worth having: the handshake succeeded, so the client did not check. */
    @Test
    void warnsWhenTheRequestedNameIsNotCoveredByThePresentedCertificate() {
        final TlsRules rules = new TlsRules(true, certificateFor("localhost"));

        assertThat(this.check(rules, tls("TLSv1.3", "mail.example.com")))
                .filteredOn(finding -> "RFC7817_IDENTITY_MISMATCH".equals(finding.rule()))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                    assertThat(finding.detail())
                            .contains("requested mail.example.com")
                            .contains("certificate covers localhost");
                });
    }

    @Test
    void namesEveryUncoveredIdentifierRatherThanJustTheFirst() {
        final TlsRules rules = new TlsRules(true, certificateFor("localhost"));

        assertThat(this.check(rules, tls("TLSv1.3", "a.example.com", "localhost", "b.example.com")))
                .filteredOn(finding -> "RFC7817_IDENTITY_MISMATCH".equals(finding.rule()))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail())
                        .contains("a.example.com")
                        .contains("b.example.com"));
    }

    @Test
    void warnsWhenTheCertificateHasNoSubjectAltNameAtAll() {
        final TlsRules rules = new TlsRules(true, certificateFor());

        assertThat(this.check(rules, tls("TLSv1.3", "localhost")))
                .filteredOn(finding -> "RFC7817_NO_SUBJECT_ALT_NAME".equals(finding.rule()))
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.WARNING));
    }

    /**
     * A certificate with no subjectAltName must be rejected outright rather than compared, so the
     * mismatch rule is not also reported; one finding per problem keeps the report readable.
     */
    @Test
    void doesNotAlsoReportAMismatchWhenThereIsNoSubjectAltNameToMatchAgainst() {
        final TlsRules rules = new TlsRules(true, certificateFor());

        assertThat(this.check(rules, tls("TLSv1.3", "localhost")))
                .extracting(Finding::rule)
                .doesNotContain("RFC7817_IDENTITY_MISMATCH");
    }

    @Test
    void reportsTheIdentifierButNoVerdictWhenNoCertificateIsKnown() {
        assertThat(this.check(new TlsRules(true), tls("TLSv1.3", "mail.example.com")))
                .extracting(Finding::rule)
                .containsExactly("RFC7817_REFERENCE_IDENTIFIER");
    }

    @Test
    void combinesAnObsoleteProtocolWithTheIdentityVerdict() {
        final TlsRules rules = new TlsRules(true, certificateFor("localhost"));

        assertThat(this.check(rules, tls("TLSv1.1", "mail.example.com")))
                .extracting(Finding::rule)
                .containsExactlyInAnyOrder(
                        "TLS_OBSOLETE_PROTOCOL",
                        "RFC7817_REFERENCE_IDENTIFIER",
                        "RFC7817_IDENTITY_MISMATCH");
    }
}
