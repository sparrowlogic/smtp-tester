package com.sparrowlogic.smtptester.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the defaults the README documents.
 *
 * <p>Every value here is published in the configuration table, so a reader plans around it without
 * running anything. Changing one is a decision, not a detail — and without a test the table and the
 * code drift apart silently, which is worse than either being wrong on its own.
 *
 * <p>Bound through {@link Binder} against an empty environment rather than read off the record,
 * because {@code @DefaultValue} is applied by the binder: calling the canonical constructor
 * directly would test nothing about what a user actually gets.
 */
class SmtpTesterPropertiesTest {

    private static SmtpTesterProperties boundWithNoConfiguration() {
        return new Binder(ConfigurationPropertySources.from(
                new StandardEnvironment().getPropertySources()))
                .bindOrCreate("smtp-tester", SmtpTesterProperties.class);
    }

    private final SmtpTesterProperties properties = boundWithNoConfiguration();

    @Test
    void retainsAThousandMessagesByDefault() {
        assertThat(this.properties.store().maxMessages()).isEqualTo(1000);
    }

    @Test
    void listensOnTheMailHogCompatiblePort() {
        assertThat(this.properties.smtp().port()).isEqualTo(1025);
        assertThat(this.properties.smtp().enabled()).isTrue();
    }

    @Test
    void capsMessageSizeAtTwentyFiveMebibytes() {
        assertThat(this.properties.smtp().maxMessageSizeBytes()).isEqualTo(26_214_400);
    }

    @Test
    void acceptsAnyCredentialsSoApplicationConfigsNeedNoEditing() {
        assertThat(this.properties.smtp().acceptAnyCredentials()).isTrue();
    }

    @Test
    void leavesStartTlsOffAndBindsEveryInterface() {
        assertThat(this.properties.smtp().tls().mode()).isEqualTo(SmtpTesterProperties.TlsMode.OFF);
        assertThat(this.properties.smtp().tls().enabled()).isFalse();
        assertThat(this.properties.smtp().tls().hostname()).isEqualTo("localhost");
        assertThat(this.properties.smtp().bindAddress()).isNull();
    }

    /**
     * Off here, and only here. {@code SmtpTesterApplication} turns it on by defaulting the property
     * to {@code ./mail}; the record bound with no configuration at all must still spool nowhere, so
     * that writing to disk is always something a property asked for rather than a default that
     * arrives with the type.
     */
    @Test
    void spoolsNowhereUntilADirectoryIsConfigured() {
        assertThat(this.properties.spool().directory()).isNull();
        assertThat(this.properties.spool().enabled()).isFalse();
        assertThat(this.properties.spool().loadOnStartup()).isTrue();
        assertThat(this.properties.spool().writeParts()).isTrue();
        assertThat(this.properties.spool().writeMetadata()).isTrue();
    }

    @Test
    void rejectsMailThatBreaksAMustLevelRule() {
        assertThat(this.properties.validation().enabled()).isTrue();
        assertThat(this.properties.validation().rejectOn())
                .isEqualTo(SmtpTesterProperties.RejectPolicy.ERROR);
    }

    @Test
    void exposesTheMcpToolsIncludingTheDestructiveOnes() {
        assertThat(this.properties.mcp().enabled()).isTrue();
        assertThat(this.properties.mcp().allowDelete()).isTrue();
        assertThat(this.properties.mcp().maxBodyCharacters()).isEqualTo(20_000);
    }

    @Test
    void servesTheUiAtTheRootWithBodyLogging() {
        assertThat(this.properties.web().enabled()).isTrue();
        assertThat(this.properties.web().basePath()).isEmpty();
        assertThat(this.properties.logging().enabled()).isTrue();
        assertThat(this.properties.logging().includeBody()).isTrue();
    }

    @Test
    void streamsArrivingMailWithAKeepAliveAndABoundedReplay() {
        assertThat(this.properties.web().stream().enabled()).isTrue();
        assertThat(this.properties.web().stream().heartbeat()).isEqualTo(java.time.Duration.ofSeconds(20));
        assertThat(this.properties.web().stream().maxSubscribers()).isEqualTo(100);
        assertThat(this.properties.web().stream().replayLimit()).isEqualTo(100);
    }

    /** A test server that randomly refuses mail is only useful when it was asked for. */
    @Test
    void injectsNoFaultsUntilChaosIsSwitchedOn() {
        assertThat(this.properties.chaos().enabled()).isFalse();
    }
}
