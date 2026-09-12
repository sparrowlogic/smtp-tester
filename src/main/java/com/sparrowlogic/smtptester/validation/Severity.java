package com.sparrowlogic.smtptester.validation;

/** How badly a finding breaks the relevant RFC. */
public enum Severity {

    /** A MUST-level violation. Mail like this is what gets silently mangled or dropped in transit. */
    ERROR,

    /** A SHOULD-level violation, or something that works but will hurt deliverability. */
    WARNING,

    /** An observation worth surfacing that breaks no rule. */
    INFO;

    /** True when this severity is at least as severe as the given threshold. */
    public boolean atLeast(final Severity threshold) {
        return this.ordinal() <= threshold.ordinal();
    }
}
