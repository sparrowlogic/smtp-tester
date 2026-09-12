package com.sparrowlogic.smtptester.chaos;

/** The faults the chaos monkey can inject, and how each is reported. */
public enum ChaosAction {

    /** The connection was refused before any command was read. */
    REFUSE_CONNECTION("connection refused"),

    /** The session was dropped mid-transaction, with no reply. */
    DISCONNECT("session dropped"),

    /** MAIL FROM was refused. */
    REJECT_SENDER("sender rejected"),

    /** RCPT TO was refused. */
    REJECT_RECIPIENT("recipient rejected"),

    /** AUTH was failed. */
    REJECT_AUTH("authentication failed"),

    /** The message transfer was rate-limited. */
    THROTTLE("transfer throttled");

    private final String description;

    ChaosAction(final String description) {
        this.description = description;
    }

    /** Human-readable label, used in the SMTP reply text and in the log. */
    public String description() {
        return this.description;
    }
}
