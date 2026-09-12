package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.chaos.ChaosAction;
import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.chaos.ThrottledInputStream;
import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.validation.Finding;
import org.jspecify.annotations.Nullable;
import org.subethamail.smtp.DropConnectionException;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.RejectException;
import org.subethamail.smtp.server.Session;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Bridges the SMTP conversation to {@link MailboxService}.
 *
 * <p>A bespoke {@link MessageHandler} is used rather than the library's
 * {@code SimpleMessageListenerAdapter} because that adapter invokes the listener once per
 * recipient, which would store the same message several times and make the inbox lie about how
 * many emails were sent. Here one {@code DATA} command produces exactly one stored message
 * carrying the full recipient list.
 */
public class IngestMessageHandlerFactory implements MessageHandlerFactory {

    private final MailboxService mailbox;
    private final ChaosMonkey chaos;
    private final int maxMessageSizeBytes;

    public IngestMessageHandlerFactory(final MailboxService mailbox, final ChaosMonkey chaos,
            final int maxMessageSizeBytes) {
        this.mailbox = mailbox;
        this.chaos = chaos;
        this.maxMessageSizeBytes = maxMessageSizeBytes;
    }

    @Override
    public MessageHandler create(final MessageContext context) {
        return new IngestMessageHandler(this.mailbox, context, this.chaos, this.maxMessageSizeBytes);
    }

    /** One SMTP transaction: MAIL FROM, one or more RCPT TO, DATA. */
    private static final class IngestMessageHandler implements MessageHandler {

        /** RFC 5321 4.3.0: a transient failure, which is the one that exercises retry logic. */
        private static final int TRANSIENT_FAILURE = 451;

        /** RFC 5321 4.2.3 / RFC 3463 3.6: the message is too big, permanently. */
        private static final int SIZE_EXCEEDED = 552;

        private static final int TRANSFER_BUFFER_BYTES = 8192;

        private final MailboxService mailbox;
        private final MessageContext context;
        private final ChaosMonkey chaos;
        private final List<String> recipients = new ArrayList<>();
        private final int maxMessageSizeBytes;
        private String from = "";

        private IngestMessageHandler(final MailboxService mailbox, final MessageContext context,
                final ChaosMonkey chaos, final int maxMessageSizeBytes) {
            this.mailbox = mailbox;
            this.context = context;
            this.chaos = chaos;
            this.maxMessageSizeBytes = maxMessageSizeBytes;
        }

        @Override
        public void from(final String reversePath) throws RejectException {
            this.injectChaos(ChaosAction.REJECT_SENDER);
            this.from = reversePath;
        }

        @Override
        public void recipient(final String forwardPath) throws RejectException {
            this.injectChaos(ChaosAction.REJECT_RECIPIENT);
            this.recipients.add(forwardPath);
        }

        /**
         * Applies whatever fault the chaos monkey rolls for this command.
         *
         * <p>A disconnect is checked first and thrown as a {@link DropConnectionException}, which
         * closes the socket without a reply — the failure mode a client is least likely to handle
         * and most likely to hang on.
         */
        private void injectChaos(final ChaosAction action) throws RejectException {
            if (this.chaos.disconnect()) {
                throw new DropConnectionException(TRANSIENT_FAILURE,
                        "4.3.0 Chaos monkey: " + ChaosAction.DISCONNECT.description());
            }
            final boolean reject = action == ChaosAction.REJECT_SENDER
                    ? this.chaos.rejectSender()
                    : this.chaos.rejectRecipient();
            if (reject) {
                throw new RejectException(TRANSIENT_FAILURE,
                        "4.3.0 Chaos monkey: " + action.description());
            }
        }

        @Override
        public String data(final InputStream data) throws RejectException, IOException {
            final byte[] raw = this.readFully(this.throttled(data));
            final IngestResult result = this.mailbox.receive(raw, this.envelope(), this.session());
            final Finding rejection = result.rejection();
            if (rejection != null) {
                throw new RejectException(rejection.statusCode().replyCode(),
                        "%s %s (%s)".formatted(rejection.statusCode().code(), rejection.summary(),
                                rejection.rule()));
            }
            // Postfix-style acknowledgement carrying the enhanced success code 2.0.0. Handing the
            // id back makes the message addressable by the caller immediately, without polling the
            // API to work out which of several just arrived.
            return "2.0.0 Ok: queued as " + result.message().id();
        }

        /** Wraps the transfer in a rate limiter when the chaos monkey calls for a slow link. */
        private InputStream throttled(final InputStream data) {
            final OptionalInt bytesPerSecond = this.chaos.throttleBytesPerSecond();
            return bytesPerSecond.isPresent()
                    ? new ThrottledInputStream(data, bytesPerSecond.getAsInt())
                    : data;
        }

        /**
         * Reads the transfer, refusing one that exceeds the configured limit.
         *
         * <p>The limit has to be applied here rather than left to the library, which enforces it
         * only against a {@code SIZE=} parameter the client volunteered on {@code MAIL FROM}. A
         * client that declares nothing — most of them — would otherwise be able to transfer a
         * message of any size at all, and every byte of it would be held in memory and retained,
         * which is precisely what {@code smtp-tester.smtp.max-message-size-bytes} exists to stop.
         *
         * <p>Unlike a validation failure, an oversized message is not stored: keeping it would
         * defeat the purpose of refusing it.
         */
        private byte[] readFully(final InputStream data) throws IOException, RejectException {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[TRANSFER_BUFFER_BYTES];
                int read = data.read(buffer);
                while (read >= 0) {
                    if (this.exceedsLimit(out.size() + read)) {
                        this.discard(data);
                        throw new RejectException(SIZE_EXCEEDED,
                                "5.3.4 Message size exceeds fixed limit of "
                                        + this.maxMessageSizeBytes + " bytes");
                    }
                    out.write(buffer, 0, read);
                    read = data.read(buffer);
                }
                return out.toByteArray();
            }
        }

        private boolean exceedsLimit(final int size) {
            return this.maxMessageSizeBytes > 0 && size > this.maxMessageSizeBytes;
        }

        /**
         * Consumes the rest of the transfer before refusing it.
         *
         * <p>The library drains the stream after the handler returns, but not after it throws, and
         * whatever is left in the socket would then be read back as SMTP commands. Draining first
         * keeps the connection usable and the reply aligned with the command that earned it.
         */
        private void discard(final InputStream data) throws IOException {
            data.transferTo(OutputStream.nullOutputStream());
        }

        private Envelope envelope() {
            return new Envelope(
                    this.from,
                    List.copyOf(this.recipients),
                    String.valueOf(this.context.getRemoteAddress()),
                    this.context.getHelo().orElse(null),
                    this.authenticatedUser(),
                    this.tlsStarted());
        }

        private @Nullable String authenticatedUser() {
            return this.context.getAuthenticationHandler()
                    .map(handler -> String.valueOf(handler.getIdentity()))
                    .orElse(null);
        }

        private boolean tlsStarted() {
            return this.context instanceof final Session session && session.isTLSStarted();
        }

        /**
         * What the transport could actually observe. A server cannot know whether the client
         * verified the certificate it was shown, so RFC 7817 reporting is built from the facts that
         * are visible here: the negotiated protocol and cipher, the SNI name the client asked for,
         * and any client certificate.
         */
        private SessionInfo session() {
            if (!(this.context instanceof final Session session) || !session.isTLSStarted()) {
                return SessionInfo.plaintext();
            }
            final SSLSession sslSession = this.sslSession(session);
            return new SessionInfo(
                    true,
                    sslSession == null ? null : sslSession.getProtocol(),
                    sslSession == null ? null : sslSession.getCipherSuite(),
                    TlsSessionFacts.requestedServerNames(sslSession),
                    this.clientCertificateSubject(session));
        }

        private @Nullable SSLSession sslSession(final Session session) {
            return session.getSocket() instanceof final SSLSocket socket ? socket.getSession() : null;
        }

        private @Nullable String clientCertificateSubject(final Session session) {
            final Certificate[] chain = session.getTlsPeerCertificates();
            if (chain == null || chain.length == 0) {
                return null;
            }
            return chain[0] instanceof final X509Certificate certificate
                    ? certificate.getSubjectX500Principal().getName()
                    : null;
        }

        @Override
        public void done() {
            this.recipients.clear();
        }
    }
}
