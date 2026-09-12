package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the {@link SSLContext} used for STARTTLS, from a supplied keystore or from a certificate
 * minted at startup.
 *
 * <p>Generating rather than shipping a certificate is deliberate. A committed private key in an
 * open-source repository is a key everyone has, and the whole point of the TLS support here is to
 * let a developer exercise their client's certificate handling — which is only meaningful against
 * a certificate whose subjectAltName entries actually name the host they are connecting to. The
 * generated certificate therefore carries dNSName entries per RFC 7817 rather than relying on the
 * deprecated CN-ID.
 */
public class TlsContextFactory {

    private static final Logger LOG = LoggerFactory.getLogger(TlsContextFactory.class);

    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String GENERATED_ALIAS = "smtp-tester";
    private static final Duration VALIDITY = Duration.ofDays(825);

    private final SmtpTesterProperties.Tls settings;

    public TlsContextFactory(final SmtpTesterProperties.Tls settings) {
        this.settings = settings;
    }

    /** The certificate and context to present, loading a keystore when one was configured. */
    public ServerCertificate create() {
        try {
            final String keystore = this.settings.keystore();
            return keystore == null || keystore.isBlank()
                    ? this.generated()
                    : this.fromKeystore(Path.of(keystore));
        } catch (final GeneralSecurityException | IOException | OperatorCreationException e) {
            throw new IllegalStateException("Cannot prepare the STARTTLS certificate", e);
        }
    }

    private ServerCertificate fromKeystore(final Path path)
            throws GeneralSecurityException, IOException {
        final char[] password = this.password();
        final KeyStore store = KeyStore.getInstance(this.keystoreType(path));
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        final String alias = this.aliasIn(store);
        final X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
        LOG.info("STARTTLS using certificate {} from {}", certificate.getSubjectX500Principal(), path);
        return ServerCertificate.of(certificate, this.contextFor(store, password));
    }

    private String keystoreType(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jks") ? "JKS" : "PKCS12";
    }

    private String aliasIn(final KeyStore store) throws GeneralSecurityException {
        final String configured = this.settings.keyAlias();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        final java.util.Enumeration<String> aliases = store.aliases();
        if (!aliases.hasMoreElements()) {
            throw new IllegalStateException("The configured keystore contains no entries");
        }
        return aliases.nextElement();
    }

    private ServerCertificate generated()
            throws GeneralSecurityException, IOException, OperatorCreationException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(KEY_SIZE, new SecureRandom());
        final KeyPair keyPair = generator.generateKeyPair();
        final X509Certificate certificate = this.selfSign(keyPair);

        final char[] password = this.password();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry(GENERATED_ALIAS, keyPair.getPrivate(), password,
                new X509Certificate[] {certificate});

        LOG.info("STARTTLS using a self-signed certificate generated for {} (valid {} days). "
                        + "Fetch it from GET /api/v1/tls/certificate to add it to a client's trust store.",
                this.subjectAltNames(), VALIDITY.toDays());
        return ServerCertificate.of(certificate, this.contextFor(store, password));
    }

    private X509Certificate selfSign(final KeyPair keyPair)
            throws GeneralSecurityException, IOException, OperatorCreationException {
        final Instant now = Instant.now();
        final X500Name subject = new X500Name("CN=" + this.settings.hostname() + ",O=smtp-tester");
        final JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofHours(1))),
                Date.from(now.plus(VALIDITY)),
                subject,
                keyPair.getPublic());

        // The subjectAltName entries are the load-bearing part: RFC 7817 has clients match the name
        // they dialled against these, and treats the CN as deprecated.
        builder.addExtension(Extension.subjectAlternativeName, false, this.generalNames());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                new JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.getPublic()));

        return new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.getPrivate())));
    }

    private GeneralNames generalNames() {
        final List<GeneralName> names = this.subjectAltNames().stream()
                .map(name -> new GeneralName(GeneralName.dNSName, name))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        names.add(new GeneralName(GeneralName.iPAddress, "::1"));
        return new GeneralNames(names.toArray(new GeneralName[0]));
    }

    /**
     * The configured hostname, plus the names a container or a compose service is actually reached
     * by, so the certificate satisfies an RFC 7817 check without per-environment configuration.
     */
    private Set<String> subjectAltNames() {
        final Set<String> names = new LinkedHashSet<>();
        names.add(this.settings.hostname().toLowerCase(Locale.ROOT));
        names.add("localhost");
        names.add("smtp-tester");
        return names;
    }

    private SSLContext contextFor(final KeyStore store, final char[] password)
            throws GeneralSecurityException {
        final KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, password);
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private char[] password() {
        final String configured = this.settings.keystorePassword();
        return (configured == null ? "" : configured).toCharArray();
    }
}
