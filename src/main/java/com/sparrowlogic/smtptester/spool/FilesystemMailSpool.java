package com.sparrowlogic.smtptester.spool;

import com.sparrowlogic.smtptester.core.AttachmentPart;
import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MessageIds;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.PartExtractor;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.validation.MailValidator;
import com.sparrowlogic.smtptester.validation.ValidationReport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Writes each received message into its own directory under a spool root.
 *
 * <p>The layout is chosen so that two runs can be compared with {@code diff -r}:
 *
 * <pre>
 *   &lt;root&gt;/20260911T142233123Z_019a7f3c2b10-00000001/
 *       message.eml      raw bytes, exactly as transferred
 *       metadata.json    envelope, digests and the compliance verdict
 *       parts/0001-invoice.pdf   each decoded MIME part
 * </pre>
 *
 * <p>{@code message.eml} is byte-exact — no {@code Received:} header is inserted by this server —
 * so it is the artefact to diff when the question is "did the bytes change". The decoded parts
 * under {@code parts/} are what you diff when the question is "did the attachment change", which is
 * the more common one and is not answerable from the raw message, where the payload is base64.
 */
public class FilesystemMailSpool implements MailSpool {

    private static final Logger LOG = LoggerFactory.getLogger(FilesystemMailSpool.class);

    private static final String MESSAGE_FILE = "message.eml";
    private static final String METADATA_FILE = "metadata.json";
    private static final String PARTS_DIRECTORY = "parts";

    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Path root;
    private final ObjectMapper json;
    private final MimeParser parser;
    private final MailValidator validator;
    private final boolean writeMetadata;
    private final boolean writeParts;

    public FilesystemMailSpool(final Path root, final ObjectMapper json, final MimeParser parser,
            final MailValidator validator, final boolean writeMetadata, final boolean writeParts) {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
        this.parser = parser;
        this.validator = validator;
        this.writeMetadata = writeMetadata;
        this.writeParts = writeParts;
        this.createRoot();
    }

    private void createRoot() {
        try {
            Files.createDirectories(this.root);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot create spool directory " + this.root, e);
        }
    }

    @Override
    public void store(final MailMessage message) {
        final Optional<Path> resolved = this.resolve(message);
        if (resolved.isEmpty()) {
            return;
        }
        final Path directory = resolved.get();
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(MESSAGE_FILE), message.raw());
            if (this.writeMetadata) {
                Files.writeString(directory.resolve(METADATA_FILE), this.metadataJson(message));
            }
            if (this.writeParts) {
                this.storeParts(message, directory);
            }
        } catch (final IOException | JacksonException e) {
            LOG.warn("Could not spool message {} to {}", message.id(), directory, e);
        }
    }

    private String metadataJson(final MailMessage message) {
        final SessionInfo session = message.session();
        final SpooledMetadata metadata = new SpooledMetadata(
                message.id(),
                message.receivedAt().toString(),
                message.envelope().from(),
                message.envelope().recipients(),
                message.envelope().remoteAddress(),
                message.envelope().helo(),
                session != null && session.tls(),
                message.parsed().subject(),
                message.sizeBytes(),
                message.sha512(),
                message.rejected(),
                message.validation().status().name(),
                message.validation().findings(),
                message.parsed().attachments());
        return this.json.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
    }

    /**
     * Re-extracts each part from the raw bytes rather than holding decoded payloads in memory for
     * the lifetime of the message.
     */
    private void storeParts(final MailMessage message, final Path directory) throws IOException {
        final List<AttachmentPart> attachments = message.parsed().attachments();
        if (attachments.isEmpty()) {
            return;
        }
        final Path parts = directory.resolve(PARTS_DIRECTORY);
        Files.createDirectories(parts);
        final PartExtractor extractor = new PartExtractor();
        for (final AttachmentPart attachment : attachments) {
            final Optional<byte[]> content = extractor.extract(message.raw(), attachment.index());
            if (content.isPresent()) {
                Files.write(parts.resolve(this.partFileName(attachment)), content.get());
            }
        }
    }

    private String partFileName(final AttachmentPart attachment) {
        final String safe = attachment.downloadName().replaceAll("[^A-Za-z0-9._-]", "_");
        return "%04d-%s".formatted(attachment.index(), safe);
    }

    private String directoryName(final MailMessage message) {
        return DIRECTORY_TIME.format(message.receivedAt()) + "_" + message.id();
    }

    @Override
    public void remove(final MailMessage message) {
        this.directoryFor(message).ifPresent(this::deleteRecursively);
    }

    private void deleteRecursively(final Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (final IOException e) {
            LOG.warn("Could not remove spool directory {}", directory, e);
        }
    }

    private void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException e) {
            LOG.warn("Could not delete {}", path, e);
        }
    }

    @Override
    public Optional<Path> directoryFor(final MailMessage message) {
        return this.resolve(message).filter(Files::isDirectory);
    }

    /**
     * The directory a message belongs in, or empty when the message could not name one safely.
     *
     * <p>Two independent guards, because this path is what {@link #remove(MailMessage)} deletes
     * recursively. The id is checked against the shape {@link MessageIds} mints, which rejects
     * every separator and dot-segment outright; the resolved path is then required to still be
     * under the spool root, which catches anything the first check did not anticipate — a symlink
     * in the middle of the root path, for instance.
     */
    private Optional<Path> resolve(final MailMessage message) {
        if (!MessageIds.isValid(message.id())) {
            LOG.warn("Refusing to spool message with an unexpected id {}", message.id());
            return Optional.empty();
        }
        final Path directory = this.root.resolve(this.directoryName(message)).normalize();
        if (!directory.startsWith(this.root) || directory.equals(this.root)) {
            LOG.warn("Refusing spool path {} outside the spool root {}", directory, this.root);
            return Optional.empty();
        }
        return Optional.of(directory);
    }

    @Override
    public List<MailMessage> loadAll() {
        if (!Files.isDirectory(this.root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(this.root)) {
            final List<Path> directories = entries.filter(Files::isDirectory).sorted().toList();
            final List<MailMessage> loaded = new ArrayList<>();
            for (final Path directory : directories) {
                this.load(directory).ifPresent(loaded::add);
            }
            return loaded;
        } catch (final IOException e) {
            LOG.warn("Could not read spool directory {}", this.root, e);
            return List.of();
        }
    }

    private Optional<MailMessage> load(final Path directory) {
        final Path raw = directory.resolve(MESSAGE_FILE);
        if (!Files.isRegularFile(raw)) {
            return Optional.empty();
        }
        try {
            final byte[] bytes = Files.readAllBytes(raw);
            final SpooledMetadata metadata = this.readMetadata(directory);
            final Envelope envelope = this.envelopeOf(metadata, directory);
            final ParsedMessage parsed = this.parser.parse(bytes);
            final ValidationReport report =
                    this.validator.validate(bytes, envelope, parsed, SessionInfo.plaintext());
            return Optional.of(new MailMessage(
                    this.idOf(directory, metadata),
                    this.receivedAtOf(metadata, raw),
                    envelope,
                    bytes,
                    parsed,
                    report,
                    metadata != null && metadata.rejected(),
                    SessionInfo.plaintext()));
        } catch (final IOException e) {
            LOG.warn("Could not load spooled message from {}", directory, e);
            return Optional.empty();
        }
    }

    private @Nullable SpooledMetadata readMetadata(final Path directory) {
        final Path file = directory.resolve(METADATA_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return this.json.readValue(Files.readString(file), SpooledMetadata.class);
        } catch (final IOException | JacksonException e) {
            LOG.warn("Ignoring unreadable spool metadata {}", file, e);
            return null;
        }
    }

    /**
     * Without metadata the envelope is unknown, so the header recipients stand in. That is a
     * deliberate approximation: it keeps a hand-dropped {@code .eml} fixture usable as spool input.
     */
    private Envelope envelopeOf(final @Nullable SpooledMetadata metadata,
            final Path directory) throws IOException {
        if (metadata != null) {
            return new Envelope(metadata.envelopeFrom(), metadata.envelopeRecipients(),
                    metadata.remoteAddress(), metadata.helo(), null, metadata.tls());
        }
        final ParsedMessage parsed = this.parser.parse(Files.readAllBytes(directory.resolve(MESSAGE_FILE)));
        final String from = parsed.from().isEmpty() ? "" : parsed.from().getFirst();
        return new Envelope(from, parsed.allRecipients(), "spool", null, null, false);
    }

    /**
     * The id to restore a spooled message under.
     *
     * <p>Prefers the recorded id, but only when it has the shape {@link MessageIds} mints. A spool
     * directory is an ordinary directory on the host — bind-mounted, copied between machines, or
     * hand-populated with {@code .eml} fixtures — so nothing read out of it is trusted. An id that
     * fails the check falls back to a freshly minted one, which keeps the message readable in the
     * inbox while guaranteeing its spool path stays inside the root.
     */
    private String idOf(final Path directory, final @Nullable SpooledMetadata metadata) {
        if (metadata != null && MessageIds.isValid(metadata.id())) {
            return metadata.id();
        }
        final String name = directory.getFileName().toString();
        final int underscore = name.indexOf('_');
        final String fromName = underscore < 0 ? name : name.substring(underscore + 1);
        if (MessageIds.isValid(fromName)) {
            return fromName;
        }
        LOG.warn("Spooled message in {} has no usable id; assigning a new one", directory);
        return MessageIds.next(Instant.now());
    }

    private Instant receivedAtOf(final @Nullable SpooledMetadata metadata,
            final Path raw) throws IOException {
        if (metadata != null) {
            return Instant.parse(metadata.receivedAt());
        }
        return Files.getLastModifiedTime(raw).toInstant();
    }
}
