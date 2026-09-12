package com.sparrowlogic.smtptester.spool;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.validation.MailValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemMailSpoolTest {

    @TempDir
    private Path root;

    private ObjectMapper json;
    private FilesystemMailSpool spool;

    @BeforeEach
    void setUp() {
        this.json = JsonMapper.builder().build();
        this.spool = new FilesystemMailSpool(this.root, this.json, new MimeParser(),
                MailValidator.standard(), true, true);
    }

    private MailMessage store(final String id, final String raw) {
        final MailMessage message = MailFixtures.message(id, raw);
        this.spool.store(message);
        return message;
    }

    private Path directoryOf(final MailMessage message) {
        return this.spool.directoryFor(message).orElseThrow();
    }

    /** The whole point of the spool: what lands on disk must equal what came off the wire. */
    @Test
    void writesTheRawMessageByteForByte() throws IOException {
        final MailMessage message = this.store("000000000001-00000001", MailFixtures.WELL_FORMED);
        final byte[] onDisk = Files.readAllBytes(this.directoryOf(message).resolve("message.eml"));
        assertThat(onDisk).isEqualTo(MailFixtures.bytes(MailFixtures.WELL_FORMED));
    }

    @Test
    void writesMetadataCarryingTheEnvelopeAndVerdict() {
        final MailMessage message = this.store("000000000001-00000001", MailFixtures.NO_DATE);
        final Path metadata = this.directoryOf(message).resolve("metadata.json");
        assertThat(metadata).exists();
        final SpooledMetadata read = this.json.readValue(metadata.toFile(), SpooledMetadata.class);
        assertThat(read.id()).isEqualTo("000000000001-00000001");
        assertThat(read.envelopeFrom()).isEqualTo("app@example.com");
        assertThat(read.envelopeRecipients()).containsExactly("alice@example.com");
        assertThat(read.validationStatus()).isEqualTo("FAIL");
        assertThat(read.findings()).extracting(f -> f.rule()).contains("RFC5322_MISSING_DATE");
        assertThat(read.sha512()).hasSize(128);
    }

    /**
     * Decoded parts are what a developer actually diffs: the raw message differs on every send
     * because of Date and Message-ID, but the attachment should not.
     */
    @Test
    void writesEachDecodedPartSoAttachmentsCanBeComparedDirectly() throws IOException {
        final MailMessage message =
                this.store("000000000001-00000001", MailFixtures.MULTIPART_WITH_ATTACHMENT);
        final Path parts = this.directoryOf(message).resolve("parts");
        assertThat(parts).isDirectory();
        try (var listed = Files.list(parts)) {
            final List<Path> files = listed.toList();
            assertThat(files).singleElement()
                    .satisfies(p -> assertThat(p.getFileName().toString()).endsWith("-invoice.pdf"));
            assertThat(Files.readString(files.getFirst())).startsWith("%PDF-1.4");
        }
    }

    @Test
    void doesNotCreateAPartsDirectoryWhenThereAreNoAttachments() {
        final MailMessage message = this.store("000000000001-00000001", MailFixtures.WELL_FORMED);
        assertThat(this.directoryOf(message).resolve("parts")).doesNotExist();
    }

    @Test
    void removingAMessageDeletesItsWholeDirectory() {
        final MailMessage message =
                this.store("000000000001-00000001", MailFixtures.MULTIPART_WITH_ATTACHMENT);
        final Path directory = this.directoryOf(message);
        this.spool.remove(message);
        assertThat(directory).doesNotExist();
        assertThat(this.spool.directoryFor(message)).isEmpty();
    }

    @Test
    void reloadsSpooledMessagesWithTheirEnvelopeAndVerdict() {
        this.store("000000000001-00000001", MailFixtures.WELL_FORMED);
        this.store("000000000002-00000002", MailFixtures.NO_DATE);
        final List<MailMessage> loaded = this.spool.loadAll();
        assertThat(loaded).hasSize(2);
        assertThat(loaded).extracting(MailMessage::id)
                .containsExactly("000000000001-00000001", "000000000002-00000002");
        assertThat(loaded.getLast().validation().errorCount()).isEqualTo(1);
        assertThat(loaded.getFirst().envelope().recipients()).containsExactly("alice@example.com");
    }

    /** A hand-dropped .eml with no sidecar should still be usable as spool input. */
    @Test
    void reloadsAMessageThatHasNoMetadataSidecar() throws IOException {
        final Path directory = this.root.resolve("20260911T120000000Z_000000000009-00000009");
        Files.createDirectories(directory);
        Files.write(directory.resolve("message.eml"), MailFixtures.bytes(MailFixtures.WELL_FORMED));
        final List<MailMessage> loaded = this.spool.loadAll();
        assertThat(loaded).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo("000000000009-00000009");
            assertThat(message.envelope().recipients()).contains("alice@example.com");
        });
    }

    @Test
    void ignoresDirectoriesWithoutAMessageFile() throws IOException {
        Files.createDirectories(this.root.resolve("not-a-message"));
        assertThat(this.spool.loadAll()).isEmpty();
    }

    @Test
    void metadataAndPartsCanBeTurnedOff() {
        final FilesystemMailSpool minimal = new FilesystemMailSpool(this.root.resolve("minimal"),
                this.json, new MimeParser(), MailValidator.standard(), false, false);
        final MailMessage message =
                MailFixtures.message("000000000001-00000001", MailFixtures.MULTIPART_WITH_ATTACHMENT);
        minimal.store(message);
        final Path directory = minimal.directoryFor(message).orElseThrow();
        assertThat(directory.resolve("message.eml")).exists();
        assertThat(directory.resolve("metadata.json")).doesNotExist();
        assertThat(directory.resolve("parts")).doesNotExist();
    }

    @Test
    void theDisabledSpoolDoesNothingAndReportsNothing() {
        final MailMessage message = MailFixtures.message("000000000001-00000001", MailFixtures.WELL_FORMED);
        MailSpool.DISABLED.store(message);
        MailSpool.DISABLED.remove(message);
        assertThat(MailSpool.DISABLED.loadAll()).isEmpty();
        assertThat(MailSpool.DISABLED.directoryFor(message)).isEmpty();
    }

    @Test
    void loadingFromAMissingRootIsEmptyRatherThanAFailure() {
        final FilesystemMailSpool fresh = new FilesystemMailSpool(this.root.resolve("brand-new"),
                this.json, new MimeParser(), MailValidator.standard(), true, true);
        assertThat(fresh.loadAll()).isEmpty();
    }
}
