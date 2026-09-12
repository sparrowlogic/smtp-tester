package com.sparrowlogic.smtptester.config;

import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.chaos.ChaosSettings;
import com.sparrowlogic.smtptester.core.InMemoryMailStore;
import com.sparrowlogic.smtptester.core.MailRemovalListener;
import com.sparrowlogic.smtptester.core.MailStore;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.logging.ReceivedMailLogger;
import com.sparrowlogic.smtptester.mcp.ChaosMcpTools;
import com.sparrowlogic.smtptester.mcp.MailMcpAdminTools;
import com.sparrowlogic.smtptester.mcp.MailMcpTools;
import com.sparrowlogic.smtptester.smtp.IngestMessageHandlerFactory;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import com.sparrowlogic.smtptester.smtp.ServerCertificate;
import com.sparrowlogic.smtptester.smtp.SmtpReceiver;
import com.sparrowlogic.smtptester.smtp.TlsContextFactory;
import com.sparrowlogic.smtptester.spool.FilesystemMailSpool;
import com.sparrowlogic.smtptester.spool.MailSpool;
import com.sparrowlogic.smtptester.validation.MailValidator;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Wires the whole server.
 *
 * <p>This is an {@code @AutoConfiguration} rather than a {@code @Configuration} so that adding the
 * jar as a dependency to an existing Spring Boot application is enough to get a working test SMTP
 * server inside it. Every bean is {@code @ConditionalOnMissingBean}, so a host application can
 * replace any single piece — most usefully the {@link MailStore} — without forking the rest.
 */
@AutoConfiguration
@EnableConfigurationProperties(SmtpTesterProperties.class)
public class SmtpTesterAutoConfiguration {

    /** The jte-maven-plugin precompiles templates into the jar; this loads them from the classpath. */
    @Bean
    @ConditionalOnMissingBean(name = "smtpTesterTemplateEngine")
    public TemplateEngine smtpTesterTemplateEngine() {
        return TemplateEngine.createPrecompiled(ContentType.Html);
    }

    /**
     * The chaos monkey, always registered but inert unless switched on.
     *
     * <p>A bean rather than a conditional one because it is controllable at runtime: a suite turns
     * it on for the tests that assert retry behaviour and off again afterwards, and a bean that
     * only existed when a property was set could not be turned on at all.
     *
     * <p>With a seed configured the sequence is reproducible, which is the difference between
     * "this test is flaky" and "this test fails when the server drops the session after RCPT".
     * Seeded runs share one generator across sessions, so reproducibility holds for sequential
     * traffic; concurrent senders interleave their draws and will not replay identically.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosMonkey smtpTesterChaosMonkey(final SmtpTesterProperties properties) {
        final SmtpTesterProperties.Chaos chaos = properties.chaos();
        final ChaosSettings settings = new ChaosSettings(chaos.enabled(), chaos.acceptConnection(),
                chaos.disconnect(), chaos.rejectSender(), chaos.rejectRecipient(), chaos.rejectAuth(),
                chaos.throttle(), chaos.minBytesPerSecond(), chaos.maxBytesPerSecond());
        return new ChaosMonkey(settings, randomSource(chaos.seed()));
    }

    private static DoubleSupplier randomSource(final @org.jspecify.annotations.Nullable Long seed) {
        if (seed == null) {
            return () -> ThreadLocalRandom.current().nextDouble();
        }
        final Random seeded = new Random(seed);
        return () -> {
            synchronized (seeded) {
                return seeded.nextDouble();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public MimeParser smtpTesterMimeParser() {
        return new MimeParser();
    }

    /**
     * The certificate presented for STARTTLS.
     *
     * <p>Declared only when TLS is actually switched on, so the default setup never generates a key
     * pair and never touches the BouncyCastle code path. Spring Boot has no "property does not
     * equal" condition, hence the expression.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${smtptester.smtp.tls.mode:OFF}' != 'OFF'")
    public ServerCertificate smtpTesterServerCertificate(final SmtpTesterProperties properties) {
        return new TlsContextFactory(properties.smtp().tls()).create();
    }

    @Bean
    @ConditionalOnMissingBean
    public MailValidator smtpTesterMailValidator(final SmtpTesterProperties properties,
            final ObjectProvider<ServerCertificate> certificate) {
        final ServerCertificate presented = certificate.getIfAvailable();
        return MailValidator.standard(properties.smtp().tls().enabled(), presented);
    }

    /**
     * Spooling is off unless a directory is configured, in which case the spool is also registered
     * as the store's removal listener so that deleting or evicting a message takes its files with
     * it.
     */
    @Bean
    @ConditionalOnMissingBean
    public MailSpool smtpTesterMailSpool(final SmtpTesterProperties properties,
            final ObjectProvider<ObjectMapper> objectMapper, final MimeParser parser,
            final MailValidator validator) {
        final SmtpTesterProperties.Spool spool = properties.spool();
        final String directory = spool.directory();
        if (!spool.enabled() || directory == null) {
            return MailSpool.DISABLED;
        }
        return new FilesystemMailSpool(Path.of(directory), this.objectMapper(objectMapper), parser,
                validator, spool.writeMetadata(), spool.writeParts());
    }

    @Bean
    @ConditionalOnMissingBean
    public MailStore smtpTesterMailStore(final SmtpTesterProperties properties, final MailSpool spool) {
        final MailRemovalListener listener =
                spool == MailSpool.DISABLED ? MailRemovalListener.NONE : spool::remove;
        return new InMemoryMailStore(properties.store().maxMessages(), listener);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReceivedMailLogger smtpTesterReceivedMailLogger(final SmtpTesterProperties properties,
            final ObjectProvider<ObjectMapper> objectMapper) {
        return new ReceivedMailLogger(this.objectMapper(objectMapper), properties.logging());
    }

    @Bean
    @ConditionalOnMissingBean
    public MailboxService smtpTesterMailboxService(final MailStore store, final MimeParser parser,
            final MailValidator validator, final MailSpool spool, final ReceivedMailLogger mailLogger,
            final SmtpTesterProperties properties, final ObjectProvider<Clock> clock) {
        return new MailboxService(store, parser, validator, spool, mailLogger, properties.validation(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "smtptester.spool", name = "load-on-startup", havingValue = "true",
            matchIfMissing = true)
    public SpoolRestoreLifecycle smtpTesterSpoolRestore(final MailboxService mailbox) {
        return new SpoolRestoreLifecycle(mailbox);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "smtptester.smtp", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public SmtpReceiver smtpTesterReceiver(final SmtpTesterProperties properties,
            final MailboxService mailbox, final ObjectProvider<ServerCertificate> certificate,
            final ChaosMonkey chaos) {
        return new SmtpReceiver(properties.smtp(), new IngestMessageHandlerFactory(mailbox, chaos),
                certificate.getIfAvailable(), chaos);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "smtptester.mcp", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public MailMcpTools smtpTesterMcpTools(final MailboxService mailbox,
            final SmtpTesterProperties properties) {
        return new MailMcpTools(mailbox, properties.mcp());
    }

    /**
     * Destructive tools are a separate bean so that turning them off removes them from
     * {@code tools/list} entirely, rather than leaving a model to discover at call time that it is
     * not allowed to use them.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "smtptester.mcp", name = "allow-delete", havingValue = "true",
            matchIfMissing = true)
    public MailMcpAdminTools smtpTesterMcpAdminTools(final MailboxService mailbox) {
        return new MailMcpAdminTools(mailbox);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "smtptester.mcp", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ChaosMcpTools smtpTesterChaosMcpTools(final ChaosMonkey chaos) {
        return new ChaosMcpTools(chaos);
    }

    /**
     * Boot auto-configures a Jackson 3 mapper in a web application, but this jar is also usable in
     * one that has no web stack at all, so a private mapper is the fallback.
     */
    private ObjectMapper objectMapper(final ObjectProvider<ObjectMapper> provider) {
        return provider.getIfAvailable(() -> JsonMapper.builder().build());
    }
}
