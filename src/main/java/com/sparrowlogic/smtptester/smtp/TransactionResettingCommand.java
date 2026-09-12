package com.sparrowlogic.smtptester.smtp;

import org.subethamail.smtp.DropConnectionException;
import org.subethamail.smtp.internal.server.Command;
import org.subethamail.smtp.internal.server.CommandException;
import org.subethamail.smtp.internal.server.HelpMessage;
import org.subethamail.smtp.server.Session;
import java.io.IOException;

/**
 * Wraps the command that ends a mail transaction so the transaction is cleared even when the
 * message was refused.
 *
 * <p>RFC 5321 section 4.1.1.4 is unconditional: once the end of mail data is received the server
 * must clear its buffers and reset its state, "whether or not the mail transaction was successful".
 * The library's {@code DataCommand} and {@code BdatCommand} both return straight out of their
 * {@code RejectException} handler and only reset on the success path, which leaves the finished
 * transaction looking live.
 *
 * <p>That matters far more here than it would in most servers using this library, because
 * rejecting mail is this server's normal behaviour rather than an exception: validation refuses
 * non-compliant messages by default. Left unreset, the next {@code MAIL FROM} on the connection is
 * answered {@code 503 5.5.1 Sender already specified}, and a client that carries on regardless has
 * its next message stored under the previous sender's address with the two recipient lists merged
 * — acknowledged with a {@code 250}, so nothing anywhere reports a problem. Well-behaved clients
 * send {@code RSET} after a failure and never see it; the ones most likely to be pointed at a test
 * SMTP server are the hand-written ones that do not.
 */
public class TransactionResettingCommand implements Command {

    private final Command delegate;

    public TransactionResettingCommand(final Command delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return this.delegate.getName();
    }

    @Override
    public HelpMessage getHelp() throws CommandException {
        return this.delegate.getHelp();
    }

    @Override
    public void execute(final String commandString, final Session session)
            throws IOException, DropConnectionException {
        // Recorded before delegating, because a successful transfer resets the session itself and
        // there would afterwards be no way to tell "the transfer happened" from "the client sent
        // DATA with no recipients". Only the first of those ends the transaction: the library
        // answers the second with 503 and leaves the client free to send its RCPT TO.
        final boolean transferred = session.isMailTransactionInProgress()
                && session.getRecipientCount() > 0;
        this.delegate.execute(commandString, session);
        if (transferred && session.isMailTransactionInProgress()) {
            session.resetMailTransaction();
        }
    }
}
