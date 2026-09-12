package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The certificate is generated at startup rather than committed, so these tests are what stands
 * between "STARTTLS works" and "STARTTLS presents something a client could actually verify". The
 * subjectAltName assertions are the load-bearing ones: RFC 7817 has clients match the name they
 * dialled against dNSName entries and treats the CN as deprecated, so a certificate carrying only
 * a CN would make the whole TLS feature untestable by a correctly-behaving client.
 */
class TlsContextFactoryTest {

    @TempDir
    private Path directory;

    private static SmtpTesterProperties.Tls settings(final String hostname,
            final @org.jspecify.annotations.Nullable String keystore,
            final @org.jspecify.annotations.Nullable String password,
            final @org.jspecify.annotations.Nullable String alias) {
        return new SmtpTesterProperties.Tls(SmtpTesterProperties.TlsMode.OPTIONAL, hostname,
                keystore, password, alias);
    }

    private ServerCertificate generated(final String hostname) {
        return new TlsContextFactory(settings(hostname, null, null, null)).create();
    }

    @Test
    void generatesACertificateCoveringTheConfiguredHostnameAndTheUsualLocalNames() {
        final ServerCertificate certificate = this.generated("mail.test");

        assertThat(certificate.dnsNames())
                .containsExactlyInAnyOrder("mail.test", "localhost", "smtp-tester");
        assertThat(certificate.covers("mail.test")).isTrue();
        assertThat(certificate.covers("localhost")).isTrue();
        assertThat(certificate.covers("not-in-the-certificate.test")).isFalse();
    }

    @Test
    void generatesACertificateAnRfc7817ClientCanVerifyWithoutFallingBackToTheCommonName() {
        assertThat(this.generated("localhost").reliesOnDeprecatedCommonName()).isFalse();
    }

    @Test
    void generatesACertificateThatIsAlreadyValidAndStaysValidForTheWholeSuite() {
        final X509Certificate certificate = this.generated("localhost").certificate();

        assertThat(certificate.getNotBefore().toInstant()).isBefore(Instant.now());
        assertThat(certificate.getNotAfter().toInstant())
                .isAfter(Instant.now().plusSeconds(365L * 24 * 3600));
        assertThat(certificate.getBasicConstraints())
                .as("a leaf certificate must not be usable as a CA")
                .isEqualTo(-1);
        assertThat(certificate.getPublicKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(certificate.getSigAlgName()).isEqualTo("SHA256withRSA");
    }

    /** A fresh key per start is the reason no private key has to live in the repository. */
    @Test
    void mintsAFreshKeyOnEveryStartRatherThanReusingOne() {
        assertThat(this.generated("localhost").certificate().getPublicKey())
                .isNotEqualTo(this.generated("localhost").certificate().getPublicKey());
    }

    @Test
    void exportsThePublicCertificateAsPemAndNothingElse() {
        final String pem = this.generated("localhost").toPem();

        assertThat(pem).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(pem).contains("-----END CERTIFICATE-----");
        assertThat(pem).doesNotContain("PRIVATE KEY");
    }

    @Test
    void buildsAUsableSslContext() {
        final SSLContext context = this.generated("localhost").sslContext();

        assertThat(context.getProtocol()).isEqualTo("TLS");
        // The JDK disables SSLv3/TLSv1/TLSv1.1 by default, so a default context is already modern.
        assertThat(context.getDefaultSSLParameters().getProtocols())
                .doesNotContain("SSLv3", "TLSv1", "TLSv1.1");
    }

    private Path writeKeystore(final String fileName, final String password, final String alias) {
        final ServerCertificate generated = this.generated("keystore.test");
        final Path path = this.directory.resolve(fileName);
        try {
            final KeyStore store = KeyStore.getInstance(fileName.endsWith(".jks") ? "JKS" : "PKCS12");
            store.load(null, password.toCharArray());
            store.setCertificateEntry(alias, generated.certificate());
            try (OutputStream out = Files.newOutputStream(path)) {
                store.store(out, password.toCharArray());
            }
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        return path;
    }

    @Test
    void readsTheAliasByNameFromASuppliedKeystore() {
        final Path keystore = this.writeKeystore("test.p12", "changeit", "mine");

        final ServerCertificate loaded = new TlsContextFactory(
                settings("ignored", keystore.toString(), "changeit", "mine")).create();

        assertThat(loaded.dnsNames()).contains("keystore.test");
    }

    @Test
    void fallsBackToTheOnlyAliasWhenNoneWasConfigured() {
        final Path keystore = this.writeKeystore("test.p12", "changeit", "only");

        final ServerCertificate loaded = new TlsContextFactory(
                settings("ignored", keystore.toString(), "changeit", null)).create();

        assertThat(loaded.dnsNames()).contains("keystore.test");
    }

    /** The extension picks the store type, so a .jks path must not be read as PKCS#12. */
    @Test
    void readsAJksKeystoreByItsExtension() {
        final Path keystore = this.writeKeystore("test.jks", "changeit", "mine");

        final ServerCertificate loaded = new TlsContextFactory(
                settings("ignored", keystore.toString(), "changeit", null)).create();

        assertThat(loaded.dnsNames()).contains("keystore.test");
    }

    @Test
    void failsLoudlyRatherThanSilentlyRunningWithoutTlsWhenTheKeystoreIsMissing() {
        final String missing = this.directory.resolve("absent.p12").toString();

        assertThatThrownBy(() -> new TlsContextFactory(
                settings("localhost", missing, "changeit", null)).create())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STARTTLS certificate");
    }

    @Test
    void failsLoudlyWhenTheKeystorePasswordIsWrong() {
        final Path keystore = this.writeKeystore("test.p12", "changeit", "mine");

        assertThatThrownBy(() -> new TlsContextFactory(
                settings("localhost", keystore.toString(), "wrong", null)).create())
                .isInstanceOf(IllegalStateException.class);
    }
}
