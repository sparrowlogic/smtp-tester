package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.chaos.ChaosAction;
import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.auth.LoginFailedException;
import org.subethamail.smtp.auth.UsernamePasswordValidator;

/**
 * Accepts any username and password.
 *
 * <p>This is a test server, and application configs routinely insist on authenticating before they
 * will send. Refusing would force every such config to be edited for local use, which is precisely
 * the friction this tool exists to remove. The credentials are recorded on the message so that
 * "which service sent this?" is still answerable.
 */
public class AcceptAnyCredentialsValidator implements UsernamePasswordValidator {

    private static final Logger LOG = LoggerFactory.getLogger(AcceptAnyCredentialsValidator.class);

    private final ChaosMonkey chaos;

    public AcceptAnyCredentialsValidator(final ChaosMonkey chaos) {
        this.chaos = chaos;
    }

    @Override
    public void login(final String username, final String password, final MessageContext context)
            throws LoginFailedException {
        if (this.chaos.rejectAuth()) {
            throw new LoginFailedException("Chaos monkey: " + ChaosAction.REJECT_AUTH.description());
        }
        LOG.debug("Accepting SMTP AUTH for user {} from {}", username, context.getRemoteAddress());
    }
}
