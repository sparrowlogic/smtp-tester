package com.sparrowlogic.smtptester.core;

/**
 * Notified once a received message has been accepted and stored.
 *
 * <p>This is the hook the SSE stream hangs off, and it is deliberately in {@code core} with no
 * Spring or servlet types on it. {@code core} holds the mail model and knows nothing about how the
 * mail is served; keeping this interface free of web types is what lets the web layer depend on
 * {@code core} and not the other way round.
 *
 * <p>Listeners are called on the thread that ingested the message — an SMTP session thread — after
 * the message is in the store, so a listener that immediately reads it back cannot miss it. That
 * places two obligations on an implementation: hand off rather than work, because the SMTP
 * conversation is waiting, and do not throw, because the message is already stored and spooled by
 * this point and nothing a subscriber does with the news should turn a completed delivery into an
 * SMTP error.
 */
@FunctionalInterface
public interface MailArrivalListener {

    /** A no-op listener, used where nothing is subscribed. */
    MailArrivalListener NONE = message -> { };

    void onReceived(MailMessage message);
}
