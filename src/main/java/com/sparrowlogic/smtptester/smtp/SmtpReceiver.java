package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.chaos.ChaosSessionHandler;
import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory;
import org.subethamail.smtp.internal.command.BdatCommand;
import org.subethamail.smtp.internal.command.DataCommand;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.smtp.server.SessionHandler;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Owns the listening SMTP socket and ties its lifecycle to the Spring context.
 *
 * <p>{@code SmartLifecycle} rather than {@code @PostConstruct} so the socket opens only once the
 * rest of the context is up — otherwise a message could arrive before the store, spool and MCP
 * server exist — and closes deterministically on shutdown.
 */
public class SmtpReceiver implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpReceiver.class);

    private final SmtpTesterProperties.Smtp settings;
    private final IngestMessageHandlerFactory handlerFactory;
    private final @Nullable ServerCertificate certificate;
    private final ChaosMonkey chaos;

    /**
     * Built on every start rather than once in the constructor.
     *
     * <p>An {@code SMTPServer} refuses to be started twice, and a Spring context can legitimately
     * be stopped and restarted — the test-context cache does exactly that between classes, and so
     * does a devtools reload. Constructing a fresh server per start is what makes this bean
     * restartable instead of failing the second time round.
     */
    private volatile @Nullable SMTPServer server;
    private volatile int allocatedPort;
    private volatile boolean running;

    public SmtpReceiver(final SmtpTesterProperties.Smtp settings,
            final IngestMessageHandlerFactory handlerFactory,
            final @Nullable ServerCertificate certificate, final ChaosMonkey chaos) {
        this.settings = settings;
        this.handlerFactory = handlerFactory;
        this.certificate = certificate;
        this.chaos = chaos;
    }

    /** The certificate presented for STARTTLS, when TLS is enabled. */
    public java.util.Optional<ServerCertificate> certificate() {
        return java.util.Optional.ofNullable(this.certificate);
    }

    private SMTPServer build() {
        final SMTPServer.Builder builder = SMTPServer.port(this.settings.port())
                .hostName(this.settings.hostName())
                .softwareName("smtp-tester")
                .messageHandlerFactory(this.handlerFactory)
                .maxMessageSize(this.settings.maxMessageSizeBytes())
                .maxConnections(this.settings.maxConnections())
                .maxRecipients(this.settings.maxRecipients())
                .connectionTimeoutMs(this.settings.connectionTimeout())
                // Byte-exactness is the whole point of the spool: with a Received: header prepended,
                // the stored .eml would differ from what the client sent on every single message and
                // a binary diff against a fixture would never match.
                .insertReceivedHeaders(false)
                .sessionHandler(new ChaosSessionHandler(this.chaos, SessionHandler.acceptAll()));
        this.bindAddress().ifPresent(builder::bindAddress);
        this.configureTls(builder);
        if (this.settings.acceptAnyCredentials()) {
            builder.authenticationHandlerFactory(new EasyAuthenticationHandlerFactory(
                    new AcceptAnyCredentialsValidator(this.chaos)));
        }
        final SMTPServer built = builder.build();
        // addCommand replaces the entry for a verb that is already registered, so this swaps the
        // library's EHLO for the one that advertises ENHANCEDSTATUSCODES. The builder has no
        // setter for this; getCommandHandler() on the built server is the supported way in.
        built.getCommandHandler().addCommand(new EnhancedStatusCodesEhloCommand());
        // Both verbs that end a mail transaction, wrapped for the same reason: see
        // TransactionResettingCommand. BDAT is included because CHUNKING is advertised, so a
        // client is entitled to use it and would meet exactly the same stale transaction.
        built.getCommandHandler().addCommand(new TransactionResettingCommand(new DataCommand()));
        built.getCommandHandler().addCommand(new TransactionResettingCommand(new BdatCommand()));
        return built;
    }

    /**
     * STARTTLS rather than implicit TLS on a second port: RFC 3207 is what an SMTP submission
     * client expects on 1025, and it keeps a single port working for clients that do not want TLS
     * at all.
     */
    private void configureTls(final SMTPServer.Builder builder) {
        final ServerCertificate presented = this.certificate;
        if (presented == null) {
            return;
        }
        builder.enableTLS(true)
                .requireTLS(this.settings.tls().mode() == SmtpTesterProperties.TlsMode.REQUIRED)
                .startTlsSocketFactory(presented.sslContext());
    }

    private java.util.Optional<InetAddress> bindAddress() {
        final String configured = this.settings.bindAddress();
        if (configured == null || configured.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(InetAddress.getByName(configured));
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException("Unknown smtp-tester.smtp.bind-address " + configured, e);
        }
    }

    @Override
    public synchronized void start() {
        if (this.running) {
            return;
        }
        final SMTPServer started = this.build();
        started.start();
        this.server = started;
        this.allocatedPort = started.getPortAllocated();
        this.running = true;
        LOG.info("SMTP listening on port {} (STARTTLS {})", this.allocatedPort,
                this.settings.tls().mode());
    }

    @Override
    public synchronized void stop() {
        final SMTPServer current = this.server;
        if (current != null) {
            current.stop();
        }
        this.server = null;
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    /**
     * The port actually bound, which differs from the configured one when 0 was requested. Tests
     * depend on this to find an ephemeral listener. Zero before the first start.
     */
    public int port() {
        return this.allocatedPort;
    }

    /** Phase late enough that the store, spool and web layer are ready before mail can arrive. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1024;
    }
}
