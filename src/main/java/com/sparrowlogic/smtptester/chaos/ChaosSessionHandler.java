package com.sparrowlogic.smtptester.chaos;

import org.subethamail.smtp.server.Session;
import org.subethamail.smtp.server.SessionHandler;

/**
 * Refuses a proportion of incoming connections.
 *
 * <p>This is the only fault that has to happen before any command is read, which is why it lives in
 * a {@code SessionHandler} rather than in the message handler with the rest. 421 is the code RFC
 * 5321 reserves for "service not available, closing transmission channel", so a client sees exactly
 * what it would see if the far end were overloaded.
 */
public class ChaosSessionHandler implements SessionHandler {

    private static final int SERVICE_NOT_AVAILABLE = 421;

    private final ChaosMonkey chaos;
    private final SessionHandler delegate;

    public ChaosSessionHandler(final ChaosMonkey chaos, final SessionHandler delegate) {
        this.chaos = chaos;
        this.delegate = delegate;
    }

    @Override
    public SessionAcceptance accept(final Session session) {
        if (this.chaos.refuseConnection()) {
            return SessionAcceptance.failure(SERVICE_NOT_AVAILABLE,
                    "4.3.2 Chaos monkey: " + ChaosAction.REFUSE_CONNECTION.description());
        }
        return this.delegate.accept(session);
    }

    @Override
    public void onSessionEnd(final Session session) {
        this.delegate.onSessionEnd(session);
    }
}
