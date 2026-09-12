package com.sparrowlogic.smtptester.smtp;

import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.validation.Finding;
import org.jspecify.annotations.Nullable;

/**
 * What happened to one delivered message.
 *
 * @param message the stored message; present whether or not it was rejected, because a message you
 *     cannot look at is a message you cannot debug
 * @param rejection the finding that caused an SMTP rejection, or null when the message was accepted
 */
public record IngestResult(MailMessage message, @Nullable Finding rejection) {

    public boolean rejected() {
        return this.rejection != null;
    }
}
