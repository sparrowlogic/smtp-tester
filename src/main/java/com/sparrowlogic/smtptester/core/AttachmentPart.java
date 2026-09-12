package com.sparrowlogic.smtptester.core;

import org.jspecify.annotations.Nullable;

/**
 * Metadata for one non-body MIME part.
 *
 * <p>The bytes themselves are deliberately <em>not</em> held here. The raw RFC 5322 message is
 * already retained verbatim, so keeping a second copy of every attachment would double the
 * memory cost of the store for no benefit; downloads re-extract from the raw bytes. What is
 * retained is the SHA-512 digest, which is what makes "did my app send the same PDF it sent
 * yesterday?" answerable without holding the payload.
 *
 * @param index position of the part in a depth-first walk of the MIME tree, and the handle used by
 *     the download endpoint and the MCP tools
 * @param filename the declared filename, when the part declares one
 * @param mimeType the base media type, lower-cased and stripped of parameters
 * @param disposition {@code attachment}, {@code inline}, or {@code unknown}
 * @param contentId the {@code Content-ID}, present for images referenced by inline HTML
 * @param sizeBytes decoded size of the part content
 * @param sha512 lower-case hex SHA-512 of the decoded part content
 */
public record AttachmentPart(
        int index,
        @Nullable String filename,
        String mimeType,
        String disposition,
        @Nullable String contentId,
        long sizeBytes,
        String sha512) {

    public boolean inline() {
        return "inline".equalsIgnoreCase(this.disposition);
    }

    /** A stable, filesystem-safe name for downloads, synthesised when the part declares none. */
    public String downloadName() {
        final String declared = this.filename;
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        return "part-" + this.index + MimeTypes.extensionFor(this.mimeType);
    }
}
