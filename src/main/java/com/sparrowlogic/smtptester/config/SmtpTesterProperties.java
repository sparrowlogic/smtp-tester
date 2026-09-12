package com.sparrowlogic.smtptester.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every knob the server exposes, under the {@code smtptester.} prefix.
 *
 * <p>Defaults are declared here rather than in an {@code application.yaml}, because this jar is
 * also publishable as a library: a packaged {@code application.yaml} would silently override a
 * host application's own {@code server.port}. Standalone-only defaults (HTTP port 8025, logging
 * config) are applied in {@code SmtpTesterApplication} instead.
 *
 * @param smtp the receiving SMTP listener
 * @param store how many messages are retained in memory
 * @param spool optional write-through of received mail to disk
 * @param validation RFC compliance checking and whether failures are rejected on the wire
 * @param mcp the Model Context Protocol tool surface
 * @param web the inbox UI and REST API
 * @param logging the structured per-message JSON log line
 * @param chaos optional transport-level fault injection
 */
@ConfigurationProperties(prefix = "smtptester")
public record SmtpTesterProperties(
        @DefaultValue Smtp smtp,
        @DefaultValue Store store,
        @DefaultValue Spool spool,
        @DefaultValue Validation validation,
        @DefaultValue Mcp mcp,
        @DefaultValue Web web,
        @DefaultValue Logging logging,
        @DefaultValue Chaos chaos) {

    /**
     * The SMTP listener. Port 1025 matches MailHog and Mailpit so existing dev configs work
     * unchanged.
     *
     * @param enabled set false to run the UI/API/MCP against an externally fed store
     * @param port listening port; 0 binds an ephemeral port, which is what the tests use
     * @param bindAddress interface to bind, or null for all interfaces
     * @param hostName the name announced in the SMTP greeting
     * @param maxMessageSizeBytes messages above this are rejected with 552 5.3.4
     * @param maxConnections concurrent client connections
     * @param maxRecipients maximum {@code RCPT TO} per transaction
     * @param connectionTimeout socket read timeout in milliseconds
     * @param acceptAnyCredentials accept any {@code AUTH} username/password, as Mailpit does, so
     *     that application configs which insist on authenticating do not have to be changed
     */
    public record Smtp(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1025") int port,
            @Nullable String bindAddress,
            @DefaultValue("smtp-tester") String hostName,
            @DefaultValue("26214400") int maxMessageSizeBytes,
            @DefaultValue("200") int maxConnections,
            @DefaultValue("1000") int maxRecipients,
            @DefaultValue("300000") int connectionTimeout,
            @DefaultValue("true") boolean acceptAnyCredentials,
            @DefaultValue Tls tls) {
    }

    /**
     * STARTTLS.
     *
     * <p>Off by default, because the overwhelmingly common case is an application talking to
     * localhost and TLS only gets in the way. Turn it on to exercise a client's own certificate
     * handling: RFC 7817 says an email client must check the server name it intended to reach
     * against the certificate's subjectAltName dNSName entries, and this is the server side of
     * that conversation.
     *
     * @param mode OFF, OPTIONAL (advertise STARTTLS, accept plaintext too) or REQUIRED
     * @param hostname the name the certificate is issued for, and the name clients should verify
     * @param keystore path to a PKCS#12 or JKS keystore; when null a self-signed certificate is
     *     generated at startup, so no private key is ever committed to this repository
     * @param keystorePassword password for that keystore
     * @param keyAlias which key in the keystore to present
     */
    public record Tls(
            @DefaultValue("OFF") TlsMode mode,
            @DefaultValue("localhost") String hostname,
            @Nullable String keystore,
            @Nullable String keystorePassword,
            @Nullable String keyAlias) {

        public boolean enabled() {
            return this.mode != TlsMode.OFF;
        }
    }

    /** How the SMTP listener treats STARTTLS. */
    public enum TlsMode {
        /** Do not offer STARTTLS at all. */
        OFF,
        /** Offer STARTTLS; plaintext delivery is still accepted. */
        OPTIONAL,
        /** Offer STARTTLS and refuse to accept mail until it has been negotiated. */
        REQUIRED
    }

    /**
     * In-memory retention.
     *
     * @param maxMessages oldest messages are evicted beyond this count; eviction also removes the
     *     spooled copy, so the directory does not grow without bound
     */
    public record Store(@DefaultValue("500") int maxMessages) {
    }

    /**
     * Write-through of received mail to a directory, so that what was sent can be diffed
     * byte-for-byte against a known-good fixture.
     *
     * @param directory the spool root; spooling is off when null
     * @param writeMetadata also write {@code metadata.json} beside the raw message
     * @param writeParts also write each decoded MIME part under {@code parts/}, which is what makes
     *     attachment-level binary comparison possible
     * @param loadOnStartup re-read an existing spool directory into the inbox at boot
     */
    public record Spool(
            @Nullable String directory,
            @DefaultValue("true") boolean writeMetadata,
            @DefaultValue("true") boolean writeParts,
            @DefaultValue("true") boolean loadOnStartup) {

        public boolean enabled() {
            return this.directory != null && !this.directory.isBlank();
        }
    }

    /**
     * RFC compliance checking.
     *
     * @param enabled run the rule set at all
     * @param rejectOn the lowest severity that causes an SMTP rejection. {@code ERROR} is the
     *     default: the point of this server is to catch broken mail at the point of sending.
     *     {@code NONE} is the lenient, MailHog/Mailpit-compatible behaviour. Rejected messages are
     *     still stored and flagged, so the inbox always shows what was refused and why.
     */
    public record Validation(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("ERROR") RejectPolicy rejectOn) {
    }

    /** How strict the SMTP listener is about validation findings. */
    public enum RejectPolicy {
        /** Never reject; accept and annotate. */
        NONE,
        /** Reject when any {@code ERROR}-severity finding is raised. */
        ERROR,
        /** Reject when any {@code WARNING}- or {@code ERROR}-severity finding is raised. */
        WARNING
    }

    /**
     * The MCP tool surface.
     *
     * @param enabled register the MCP tools
     * @param allowDelete expose the destructive tools ({@code delete_email}, {@code clear_inbox})
     * @param maxBodyCharacters bodies longer than this are truncated in tool output, so one large
     *     marketing email cannot blow an agent's context window
     * @param defaultLimit page size for {@code list_recent_emails} when the caller omits one
     */
    public record Mcp(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean allowDelete,
            @DefaultValue("20000") int maxBodyCharacters,
            @DefaultValue("20") int defaultLimit) {
    }

    /**
     * The inbox UI and REST API.
     *
     * @param enabled serve them at all
     * @param basePath prefix for every route, so a host application embedding this jar can mount
     *     the inbox somewhere that does not collide with its own routes
     */
    public record Web(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String basePath) {
    }

    /**
     * Transport-level fault injection, modelled on MailHog's "Jim".
     *
     * <p>Off by default and deliberately so: a test server that randomly refuses mail is only
     * useful when you asked for it. Turn it on to check that an application retries, backs off and
     * surfaces a sensible error when the mail server misbehaves — the paths that are otherwise
     * almost impossible to reach against localhost.
     *
     * @param enabled the master switch
     * @param seed fixes the random sequence so a failing run can be reproduced; omit for a
     *     different sequence each start
     * @param acceptConnection chance of accepting a connection, so 0.99 refuses one in a hundred
     * @param disconnect chance of dropping a session mid-transaction with no reply
     * @param rejectSender chance of refusing a MAIL FROM with a transient 451
     * @param rejectRecipient chance of refusing a RCPT TO with a transient 451
     * @param rejectAuth chance of failing an AUTH
     * @param throttle chance of rate-limiting a transfer
     * @param minBytesPerSecond slowest rate applied when throttling
     * @param maxBytesPerSecond fastest rate applied when throttling
     */
    public record Chaos(
            @DefaultValue("false") boolean enabled,
            @Nullable Long seed,
            @DefaultValue("0.99") double acceptConnection,
            @DefaultValue("0.005") double disconnect,
            @DefaultValue("0.05") double rejectSender,
            @DefaultValue("0.05") double rejectRecipient,
            @DefaultValue("0.05") double rejectAuth,
            @DefaultValue("0.1") double throttle,
            @DefaultValue("1024") int minBytesPerSecond,
            @DefaultValue("10240") int maxBytesPerSecond) {
    }

    /**
     * The structured per-message JSON log line written to stdout.
     *
     * @param enabled emit it at all
     * @param includeBody include the decoded text/HTML body; turn off when the mail under test
     *     carries data you would rather not have in a log aggregator
     * @param maxBodyCharacters truncate the logged body at this length
     */
    public record Logging(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean includeBody,
            @DefaultValue("2000") int maxBodyCharacters) {
    }
}
