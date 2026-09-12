package com.sparrowlogic.smtptester.docs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The documentation an agent can read at runtime, loaded once from the classpath.
 *
 * <p>There is one copy of this text. It is served over HTTP at {@code /llms.txt} and
 * {@code /llms-full.txt} and returned by the {@code get_documentation} MCP tool, both reading this
 * class, so the two surfaces cannot describe different servers. Keeping it as a resource rather
 * than a string constant also means the README-shaped content stays readable as markdown.
 *
 * <p>Read eagerly at construction: a missing or unreadable resource is a packaging mistake, and
 * failing at startup surfaces it in CI rather than on the first agent that asks for help.
 */
public final class AgentDocs {

    /** Which of the two documents is wanted. */
    public enum Document {

        /** The short index: what the server is, how to run it, the endpoints that matter. */
        INDEX("llms.txt"),

        /** The full reference: every endpoint, every configuration key, the rule set. */
        FULL("llms-full.txt");

        private final String fileName;

        Document(final String fileName) {
            this.fileName = fileName;
        }

        /**
         * The name this document is served under.
         *
         * @return the file name, without a leading slash
         */
        public String fileName() {
            return this.fileName;
        }
    }

    private static final String RESOURCE_ROOT = "/agent-docs/";

    private final Map<Document, String> documents;

    public AgentDocs() {
        final Map<Document, String> loaded = new EnumMap<>(Document.class);
        for (final Document document : Document.values()) {
            loaded.put(document, read(document));
        }
        this.documents = Map.copyOf(loaded);
    }

    /**
     * @param document which document to return
     * @return its full text, as UTF-8
     */
    public String text(final Document document) {
        // Every constant is loaded in the constructor, so this cannot be absent; requireNonNull
        // states that for NullAway without adding a branch nothing can ever take.
        return Objects.requireNonNull(this.documents.get(document), "No text loaded");
    }

    private static String read(final Document document) {
        return readResource(RESOURCE_ROOT + document.fileName());
    }

    /**
     * Package-private so a test can prove the missing-resource guard fires. That guard is the one
     * that turns "someone changed the build and the docs stopped being packaged" into a startup
     * failure instead of an agent being handed an empty string.
     *
     * @param path an absolute classpath resource path
     * @return the resource as UTF-8
     */
    static String readResource(final String path) {
        try (InputStream in = AgentDocs.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Packaged documentation is missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not read packaged documentation: " + path, e);
        }
    }
}
