package com.sparrowlogic.smtptester.config;

import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.chaos.ChaosSettings;
import com.sparrowlogic.smtptester.core.InMemoryMailStore;
import com.sparrowlogic.smtptester.core.MailRemovalListener;
import com.sparrowlogic.smtptester.core.MailStore;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.docs.AgentDocs;
import com.sparrowlogic.smtptester.logging.ReceivedMailLogger;
import com.sparrowlogic.smtptester.mcp.ChaosMcpTools;
import com.sparrowlogic.smtptester.mcp.DocsMcpTools;
import com.sparrowlogic.smtptester.mcp.MailMcpAdminTools;
import com.sparrowlogic.smtptester.mcp.MailMcpTools;
import com.sparrowlogic.smtptester.smtp.IngestMessageHandlerFactory;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import com.sparrowlogic.smtptester.smtp.ServerCertificate;
import com.sparrowlogic.smtptester.smtp.SmtpReceiver;
import com.sparrowlogic.smtptester.smtp.TlsContextFactory;
import com.sparrowlogic.smtptester.smtp.ValidationPolicy;
import com.sparrowlogic.smtptester.spool.FilesystemMailSpool;
import com.sparrowlogic.smtptester.spool.MailSpool;
import com.sparrowlogic.smtptester.spool.SpoolRetentionTask;
import com.sparrowlogic.smtptester.validation.MailValidator;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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
 * <p>Every bean is declared here explicitly rather than found by component scanning, so the bean
 * graph is exactly what this file says and adding a class to a package cannot quietly add a bean.
 * {@link SmtpTesterApplication} imports this class; nothing registers it automatically.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SmtpTesterProperties.class)
public class SmtpTesterConfiguration {

    /** The jte-maven-plugin precompiles templates into the jar; this loads them from the classpath. */
    @Bean
    public TemplateEngine smtpTesterTemplateEngine() {
        return TemplateEngine.createPrecompiled(ContentType.Html);
    }

    /**
     * The packaged documentation, read once and shared by the HTTP and MCP surfaces so that the two
     * cannot drift apart.
     */
    @Bean
    public AgentDocs smtpTesterAgentDocs() {
        return new AgentDocs();
    }

    /**
     * Not gated on {@code allow-delete} like the admin tools: documentation is read-only, and a
     * model that cannot delete still needs to know what a 550 meant.
     */
    @Bean
    @ConditionalOnProperty(prefix = "smtp-tester.mcp", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public DocsMcpTools smtpTesterDocsMcpTools(final AgentDocs docs) {
        return new DocsMcpTools(docs);
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
    @ConditionalOnExpression("'${smtp-tester.smtp.tls.mode:OFF}' != 'OFF'")
    public ServerCertificate smtpTesterServerCertificate(final SmtpTesterProperties properties) {
        return new TlsContextFactory(properties.smtp().tls()).create();
    }

    @Bean
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
    public MailStore smtpTesterMailStore(final SmtpTesterProperties properties, final MailSpool spool) {
        final MailRemovalListener listener =
                spool == MailSpool.DISABLED ? MailRemovalListener.NONE : spool::remove;
        return new InMemoryMailStore(properties.store().maxMessages(), listener);
    }

    /**
     * Registered even when spooling is off: {@link MailSpool#DISABLED} keeps nothing, so the sweep
     * is a no-op rather than a bean whose presence depends on a property read at build time.
     */
    @Bean
    public SpoolRetentionTask smtpTesterSpoolRetentionTask(final MailSpool spool,
            final SmtpTesterProperties properties) {
        return new SpoolRetentionTask(spool, properties.store().maxMessages());
    }

    @Bean
    public ReceivedMailLogger smtpTesterReceivedMailLogger(final SmtpTesterProperties properties,
            final ObjectProvider<ObjectMapper> objectMapper) {
        return new ReceivedMailLogger(this.objectMapper(objectMapper), properties.logging());
    }

    @Bean
    public MailboxService smtpTesterMailboxService(final MailStore store, final MimeParser parser,
            final MailValidator validator, final MailSpool spool, final ReceivedMailLogger mailLogger,
            final SmtpTesterProperties properties, final ObjectProvider<Clock> clock) {
        return new MailboxService(store, parser,
                new ValidationPolicy(validator, properties.validation()), spool, mailLogger,
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Registered whenever spooling is on, rather than only when restoring.
     *
     * <p>It used to be conditional on {@code load-on-startup}, which meant the one configuration
     * that most needs the directory-side retention cap — a spool kept across runs but not read back
     * in — was the one configuration that never applied it.
     */
    @Bean
    public SpoolRestoreLifecycle smtpTesterSpoolRestore(final MailboxService mailbox,
            final SmtpTesterProperties properties) {
        return new SpoolRestoreLifecycle(mailbox, properties.spool().loadOnStartup(),
                properties.store().maxMessages());
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp-tester.smtp", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public SmtpReceiver smtpTesterReceiver(final SmtpTesterProperties properties,
            final MailboxService mailbox, final ObjectProvider<ServerCertificate> certificate,
            final ChaosMonkey chaos) {
        return new SmtpReceiver(properties.smtp(),
                new IngestMessageHandlerFactory(mailbox, chaos,
                        properties.smtp().maxMessageSizeBytes()),
                certificate.getIfAvailable(), chaos);
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp-tester.mcp", name = "enabled", havingValue = "true",
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
    @ConditionalOnProperty(prefix = "smtp-tester.mcp", name = "allow-delete", havingValue = "true",
            matchIfMissing = true)
    public MailMcpAdminTools smtpTesterMcpAdminTools(final MailboxService mailbox) {
        return new MailMcpAdminTools(mailbox);
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp-tester.mcp", name = "enabled", havingValue = "true",
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
