package com.sparrowlogic.smtptester.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-512 helpers. The digest is the project's content fingerprint, in logs and in the UI. */
public final class Digests {

    private static final String ALGORITHM = "SHA-512";

    private Digests() {
    }

    /** Lower-case hex SHA-512 of the given bytes. */
    public static String sha512(final byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(content));
        } catch (final NoSuchAlgorithmException e) {
            // SHA-512 is mandated by the JLS for every conformant JRE; unreachable in practice.
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    /** Lower-case hex SHA-512 of the UTF-8 encoding of the given text. */
    public static String sha512Utf8(final String content) {
        return sha512(content.getBytes(StandardCharsets.UTF_8));
    }
}
