package com.sparrowlogic.smtptester.validation;

/**
 * The subset of the IANA SMTP Enhanced Mail System Status Code registry that this server answers
 * with.
 *
 * <p>RFC 5248 created that registry and seeded it from RFC 3463, and RFC 2034 requires a server to
 * advertise {@code ENHANCEDSTATUSCODES} in its {@code EHLO} response before putting these codes in
 * replies. Rejections therefore quote a registered code rather than an invented one, so a client's
 * own bounce handling classifies the failure correctly instead of treating every refusal as a
 * generic 550.
 *
 * <p>Codes are {@code class.subject.detail}: class 4 is a transient failure the client should
 * retry, class 5 is permanent.
 */
public enum EnhancedStatusCode {

    /** X.0.0 — other or undefined status. */
    OTHER_UNDEFINED("5.0.0", 550, "Other or undefined status"),

    /** X.1.3 — bad destination mailbox address syntax. */
    BAD_DESTINATION_ADDRESS_SYNTAX("5.1.3", 553, "Bad destination mailbox address syntax"),

    /** X.1.7 — bad sender's mailbox address syntax. */
    BAD_SENDER_ADDRESS_SYNTAX("5.1.7", 553, "Bad sender's mailbox address syntax"),

    /** X.3.4 — message too big for system. */
    MESSAGE_TOO_BIG("5.3.4", 552, "Message too big for system"),

    /** X.3.5 — system incorrectly configured. */
    SYSTEM_INCORRECTLY_CONFIGURED("5.3.5", 554, "System incorrectly configured"),

    /** X.5.2 — syntax error; the command could not be interpreted. */
    SYNTAX_ERROR("5.5.2", 501, "Syntax error"),

    /** X.5.4 — invalid command arguments. */
    INVALID_COMMAND_ARGUMENTS("5.5.4", 501, "Invalid command arguments"),

    /** X.6.0 — other or undefined media error. */
    MEDIA_ERROR("5.6.0", 550, "Other or undefined media error"),

    /** X.6.1 — media not supported. */
    MEDIA_NOT_SUPPORTED("5.6.1", 550, "Media not supported"),

    /** X.6.2 — conversion required and prohibited. */
    CONVERSION_REQUIRED_AND_PROHIBITED("5.6.2", 550, "Conversion required and prohibited"),

    /** X.6.7 — non-ASCII addresses not permitted for that sender/recipient. */
    NON_ASCII_ADDRESSES_NOT_PERMITTED("5.6.7", 553, "Non-ASCII addresses not permitted"),

    /** X.7.0 — other or undefined security status. */
    SECURITY_STATUS("5.7.0", 550, "Other or undefined security status"),

    /** X.7.4 — security features not supported. */
    SECURITY_FEATURES_NOT_SUPPORTED("5.7.4", 504, "Security features not supported"),

    /** X.7.20 — no passing DKIM signature found. */
    NO_PASSING_DKIM_SIGNATURE("5.7.20", 550, "No passing DKIM signature found"),

    /** X.7.21 — no acceptable DKIM signature found. */
    NO_ACCEPTABLE_DKIM_SIGNATURE("5.7.21", 550, "No acceptable DKIM signature found"),

    /** X.7.22 — no valid author-matched DKIM signature found. */
    NO_AUTHOR_MATCHED_DKIM_SIGNATURE("5.7.22", 550, "No valid author-matched DKIM signature found"),

    /** X.7.30 — REQUIRETLS support required. */
    REQUIRETLS_REQUIRED("5.7.30", 550, "REQUIRETLS support required"),

    /** 4.7.0 — transient security status, used when TLS is required but was not negotiated. */
    TRANSIENT_SECURITY_STATUS("4.7.0", 454, "Temporary security status");

    private final String code;
    private final int replyCode;
    private final String meaning;

    EnhancedStatusCode(final String code, final int replyCode, final String meaning) {
        this.code = code;
        this.replyCode = replyCode;
        this.meaning = meaning;
    }

    /** The {@code class.subject.detail} triple, e.g. {@code 5.6.0}. */
    public String code() {
        return this.code;
    }

    /** The RFC 5321 three-digit reply code this enhanced code accompanies. */
    public int replyCode() {
        return this.replyCode;
    }

    /** The registry's description of the code. */
    public String meaning() {
        return this.meaning;
    }

    /**
     * Formats a complete SMTP reply line, e.g. {@code 550 5.6.0 Missing required Date header}.
     *
     * <p>RFC 3463 puts the enhanced code first in the reply text, immediately after the reply code.
     */
    public String reply(final String text) {
        return "%d %s %s".formatted(this.replyCode, this.code, text);
    }
}
