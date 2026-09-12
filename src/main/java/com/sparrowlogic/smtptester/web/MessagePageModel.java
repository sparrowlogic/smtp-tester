package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.core.MailMessage;
import org.jspecify.annotations.Nullable;

/**
 * What the message detail page renders.
 *
 * @param basePath prefix every link must carry
 * @param message the message being shown
 * @param rawSource the verbatim RFC 5322 source, for the Source tab
 * @param spoolDirectory where the message was written on disk, when spooling is on
 */
public record MessagePageModel(
        String basePath,
        MailMessage message,
        String rawSource,
        @Nullable String spoolDirectory) {
}
