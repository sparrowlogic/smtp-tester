package com.sparrowlogic.smtptester.spool;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The spool wired into a running application, rather than exercised in isolation.
 *
 * <p>"Deleting a message deletes its files" is a chain of three links — the API, the store's
 * removal listener, and the spool — each unit-tested on its own and, until this test, never
 * together. The rest of the suite runs with spooling off, so this is the only place the wiring is
 * proved.
 */
@SpringBootTest
class SpoolLifecycleTest {

    @TempDir
    private static Path spoolRoot;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MailboxService mailbox;

    @Autowired
    private SpoolRetentionTask retention;

    private MockMvc mvc;

    @DynamicPropertySource
    static void spoolDirectory(final DynamicPropertyRegistry registry) {
        registry.add("smtp-tester.spool.directory", () -> spoolRoot.toString());
        registry.add("smtp-tester.store.max-messages", () -> 3);
    }

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
        this.mailbox.clearAll();
    }

    private MailMessage receive(final String raw, final String recipient) {
        return this.mailbox.receive(MailFixtures.bytes(raw),
                MailFixtures.envelope("app@example.com", recipient), SessionInfo.plaintext()).message();
    }

    private long spoolDirectories() throws IOException {
        try (Stream<Path> entries = Files.list(spoolRoot)) {
            return entries.filter(Files::isDirectory).count();
        }
    }

    @Test
    void aReceivedMessageIsWrittenToTheSpoolDirectory() throws IOException {
        final MailMessage message = this.receive(MailFixtures.MULTIPART_WITH_ATTACHMENT, "alice@example.com");
        final Path directory = this.mailbox.spoolDirectory(message).orElseThrow();
        assertThat(directory.resolve("message.eml")).exists();
        assertThat(directory.resolve("metadata.json")).exists();
        assertThat(Files.readAllBytes(directory.resolve("message.eml")))
                .isEqualTo(MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT));
    }

    /** The stated completion criterion: deleting a message takes its persisted files with it. */
    @Test
    void deletingThroughTheApiRemovesTheSpooledFiles() throws Exception {
        final MailMessage message = this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        final Path directory = this.mailbox.spoolDirectory(message).orElseThrow();
        assertThat(directory).exists();

        this.mvc.perform(delete("/api/v1/messages/{id}", message.id())).andExpect(status().isOk());

        assertThat(directory).doesNotExist();
        assertThat(this.mailbox.find(message.id())).isEmpty();
    }

    @Test
    void clearingAnInboxRemovesEverySpooledDirectoryForIt() throws Exception {
        this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        final MailMessage kept = this.receive(MailFixtures.WELL_FORMED, "bob@example.com");
        assertThat(this.spoolDirectories()).isEqualTo(3);

        this.mvc.perform(delete("/api/v1/messages").param("inbox", "alice@example.com"))
                .andExpect(status().isOk());

        assertThat(this.spoolDirectories()).isEqualTo(1);
        assertThat(this.mailbox.spoolDirectory(kept)).isPresent();
    }

    /**
     * Eviction bounds memory immediately and leaves the disk alone; the background sweep is what
     * reclaims it. Both halves matter, so both are asserted here rather than only the end state.
     */
    @Test
    void evictionBoundsMemoryAtOnceAndTheSweepReclaimsTheDiskAfterwards() throws Exception {
        final MailMessage first = this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        final Path firstDirectory = this.mailbox.spoolDirectory(first).orElseThrow();
        for (int i = 0; i < 3; i++) {
            this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        }

        assertThat(this.mailbox.size()).as("the in-memory bound is exact and immediate").isEqualTo(3);
        assertThat(firstDirectory)
                .as("the SMTP thread must not have paid for a recursive delete")
                .exists();
        assertThat(this.spoolDirectories()).isEqualTo(4);

        assertThat(this.retention.sweep()).isEqualTo(1);
        assertThat(firstDirectory).doesNotExist();
        assertThat(this.spoolDirectories()).isEqualTo(3);
    }

    /** A sweep with nothing to reclaim must not touch what is still held. */
    @Test
    void aSweepWithNothingBeyondTheLimitRemovesNothing() throws Exception {
        this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        this.receive(MailFixtures.WELL_FORMED, "bob@example.com");

        assertThat(this.retention.sweep()).isZero();
        assertThat(this.spoolDirectories()).isEqualTo(2);
    }

    /**
     * The sweep works from directory names, so it reclaims what eviction never could: a directory
     * left by an earlier run that nothing loaded back into the store.
     */
    @Test
    void theSweepAlsoReclaimsDirectoriesTheStoreNeverHeld() throws Exception {
        this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        for (int i = 0; i < 5; i++) {
            Files.createDirectories(spoolRoot.resolve("19700101T00000" + i + "Z-orphan" + i));
        }
        assertThat(this.spoolDirectories()).isEqualTo(6);

        assertThat(this.retention.sweep()).isEqualTo(3);
        assertThat(this.spoolDirectories()).isEqualTo(3);
    }

    @Test
    void clearingEverythingEmptiesTheSpoolDirectory() throws Exception {
        this.receive(MailFixtures.WELL_FORMED, "alice@example.com");
        this.receive(MailFixtures.WELL_FORMED, "bob@example.com");
        this.mvc.perform(delete("/api/v1/messages")).andExpect(status().isOk());
        assertThat(this.spoolDirectories()).isZero();
    }

    /** What the spool is for: two captures of the same payload have the same digest. */
    @Test
    void theSpooledAttachmentIsByteIdenticalAcrossRuns() throws IOException {
        final MailMessage first = this.receive(MailFixtures.MULTIPART_WITH_ATTACHMENT, "alice@example.com");
        final MailMessage second = this.receive(MailFixtures.MULTIPART_WITH_ATTACHMENT, "bob@example.com");
        final Path firstPart = this.onlyPart(this.mailbox.spoolDirectory(first).orElseThrow());
        final Path secondPart = this.onlyPart(this.mailbox.spoolDirectory(second).orElseThrow());
        assertThat(Files.readAllBytes(firstPart)).isEqualTo(Files.readAllBytes(secondPart));
    }

    private Path onlyPart(final Path directory) throws IOException {
        try (Stream<Path> parts = Files.list(directory.resolve("parts"))) {
            return parts.findFirst().orElseThrow();
        }
    }
}
