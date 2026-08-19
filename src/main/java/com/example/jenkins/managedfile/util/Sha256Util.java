package com.example.jenkins.managedfile.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 helper for content fingerprinting.
 *
 * <p>Intentionally minimal: we do not pull in Guava or commons-codec to keep
 * the plugin dependency surface as small as possible.</p>
 */
public final class Sha256Util {

    private Sha256Util() {
    }

    public static String hash(String content) {
        if (content == null) {
            content = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated to be available by every JRE.
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }
}
