package com.sparrowlogic.smtptester.core;

import java.util.Locale;
import java.util.Map;

/** Small helpers for normalising and labelling media types. */
public final class MimeTypes {

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("text/plain", ".txt"),
            Map.entry("text/html", ".html"),
            Map.entry("text/calendar", ".ics"),
            Map.entry("text/csv", ".csv"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("application/json", ".json"),
            Map.entry("application/zip", ".zip"),
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/svg+xml", ".svg"),
            Map.entry("message/rfc822", ".eml"));

    private MimeTypes() {
    }

    /**
     * Reduces a {@code Content-Type} header value to a bare lower-case media type.
     *
     * <p>{@code "TEXT/HTML; charset=UTF-8"} becomes {@code "text/html"}. Returns
     * {@code application/octet-stream} for anything unparseable, which is what RFC 2045 says a
     * recipient should assume.
     */
    public static String baseType(final String contentType) {
        final int semicolon = contentType.indexOf(';');
        final String head = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        final String normalised = head.trim().toLowerCase(Locale.ROOT);
        return normalised.isEmpty() ? "application/octet-stream" : normalised;
    }

    /** A conventional file extension for a media type, or {@code .bin} when unknown. */
    public static String extensionFor(final String mimeType) {
        return EXTENSIONS.getOrDefault(baseType(mimeType), ".bin");
    }

    /** True for types whose payload is human-readable text worth rendering inline. */
    public static boolean isTextual(final String mimeType) {
        final String base = baseType(mimeType);
        return base.startsWith("text/") || isStructuredText(base);
    }

    /** JSON and XML payloads, including the {@code +json} / {@code +xml} structured suffixes. */
    private static boolean isStructuredText(final String base) {
        return "application/json".equals(base)
                || "application/xml".equals(base)
                || base.endsWith("+json")
                || base.endsWith("+xml");
    }
}
