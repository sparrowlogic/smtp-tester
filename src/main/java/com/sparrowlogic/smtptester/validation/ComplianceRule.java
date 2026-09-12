package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import java.util.function.Consumer;

/** A group of related compliance checks over one received message. */
@FunctionalInterface
public interface ComplianceRule {

    /**
     * Inspects the message and reports any findings.
     *
     * @param raw the message exactly as transferred; rules about line endings and octet widths must
     *     read this rather than the parsed view, which has already normalised both away
     * @param envelope the SMTP envelope the message arrived in
     * @param parsed the decoded view
     * @param session what the transport layer observed about the delivering connection, which is
     *     where the RFC 7817 rules get the negotiated protocol and the client's SNI name
     * @param sink where findings are reported
     */
    void check(byte[] raw, Envelope envelope, ParsedMessage parsed, SessionInfo session,
            Consumer<Finding> sink);
}
