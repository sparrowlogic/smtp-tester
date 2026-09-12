package com.sparrowlogic.smtptester.smtp;

import org.subethamail.smtp.AuthenticationHandlerFactory;
import org.subethamail.smtp.internal.server.BaseCommand;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.smtp.server.Session;
import java.io.IOException;
import java.util.Optional;

/**
 * Replaces the library's {@code EHLO} handler so the greeting advertises
 * {@code ENHANCEDSTATUSCODES}.
 *
 * <p>RFC 2034 is explicit that a server must advertise the extension before it may put enhanced
 * status codes in its replies, and this server answers rejections with registered RFC 5248 codes.
 * Advertising them is what tells a client those codes are deliberate, so its bounce classifier
 * reads {@code 550 5.6.0} as a content failure rather than treating the leading token as part of
 * the human-readable text.
 *
 * <p>Everything else reproduces the library's own EHLO response, keyword for keyword and condition
 * for condition, so replacing the command changes nothing but the added line.
 */
public class EnhancedStatusCodesEhloCommand extends BaseCommand {

    public EnhancedStatusCodesEhloCommand() {
        super("EHLO", "Introduce yourself.", "<hostname>");
    }

    @Override
    public void execute(final String commandString, final Session session) throws IOException {
        final String[] args = getArgs(commandString);
        if (args.length < 2) {
            session.sendResponse("501 Syntax: EHLO hostname");
            return;
        }
        session.resetMailTransaction();
        session.setHelo(args[1]);
        session.sendResponse(this.greeting(session));
    }

    private String greeting(final Session session) {
        final SMTPServer server = session.getServer();
        final StringBuilder response = new StringBuilder(256);
        response.append("250-").append(server.getHostName());
        response.append("\r\n250-8BITMIME");
        response.append("\r\n250-SIZE ").append(server.getMaxMessageSize());
        if (server.getEnableTLS() && !server.getHideTLS()) {
            response.append("\r\n250-STARTTLS");
        }
        response.append("\r\n250-CHUNKING");
        // SMTPUTF8 is deliberately NOT advertised, unlike the library's own EHLO. RFC 6532 mail
        // carries raw 8-bit octets in header fields, and this server reports those as an RFC 5322
        // violation — which is the right answer for the overwhelming majority of senders, who
        // produce them by accident rather than by negotiating the extension. Advertising support
        // and then rejecting the very content it authorises would be worse than not offering it:
        // the client would have no way to tell a bug in its encoding from a bug in this server.
        // RFC 2034: this is the line that licenses the X.Y.Z codes used in every rejection.
        response.append("\r\n250-ENHANCEDSTATUSCODES");
        this.appendAuth(session, server, response);
        response.append("\r\n250 Ok");
        return response.toString();
    }

    /**
     * AUTH is hidden until TLS is up when the server requires TLS, matching the library, so that a
     * client cannot be tempted into sending credentials in the clear.
     */
    private void appendAuth(final Session session, final SMTPServer server, final StringBuilder response) {
        final Optional<AuthenticationHandlerFactory> factory = server.getAuthenticationHandlerFactory();
        if (factory.isEmpty()) {
            return;
        }
        final boolean allowed = session.isTLSStarted()
                || !server.getRequireTLS()
                || server.getShowAuthCapabilitiesBeforeSTARTTLS();
        if (!allowed || factory.get().getAuthenticationMechanisms().isEmpty()) {
            return;
        }
        response.append("\r\n250-AUTH ")
                .append(String.join(" ", factory.get().getAuthenticationMechanisms()));
    }
}
