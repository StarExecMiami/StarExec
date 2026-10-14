package org.starexec.util;

import org.mindrot.jbcrypt.BCrypt;
import org.starexec.logger.StarLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * Secure password hashing utility using BCrypt.
 *
 * <p>Two stored forms are accepted:
 * <ul>
 *   <li>plain BCrypt ({@code $2a$...}), written for every password set in this application;</li>
 *   <li>{@value #LEGACY_SHA512_PREFIX}{@code $2a$...}: a password migrated from the legacy
 *       deployment, whose stored value was the unsalted SHA-512 hex digest of the password.
 *       The migration wraps that digest in BCrypt (sql/wrap-legacy-password-hashes.sql),
 *       so no unsalted hash remains in the database, and verification hashes the input with
 *       SHA-512 before checking it.</li>
 * </ul>
 * A bare 128-character hex digest is never accepted: an unwrapped legacy hash is refused, not
 * verified, so a migration that skipped the wrapping step fails closed.
 *
 * <p>{@code tomcat-credential-handler/.../BCryptCredentialHandler} implements the same rule for
 * container-managed login; the two must stay in step.
 *
 * @author StarExec Team
 */
public class PasswordHasher {
    private static final StarLogger log = StarLogger.getLogger(PasswordHasher.class);

    // BCrypt work factor (2^12 iterations)
    private static final int BCRYPT_ROUNDS = 12;

    /** Marks a BCrypt hash whose input is the SHA-512 hex digest of the password. */
    public static final String LEGACY_SHA512_PREFIX = "{sha512}";

    private static final Pattern LEGACY_SHA512_HEX = Pattern.compile("[0-9a-f]{128}");

    /**
     * Hash a password using BCrypt with salt and work factor.
     *
     * @param plaintext The plaintext password to hash
     * @return The BCrypt hash (includes salt)
     */
    public static String hash(String plaintext) {
        if (plaintext == null) {
            log.warn("Attempted to hash null password");
            return null;
        }
        return BCrypt.hashpw(plaintext, BCrypt.gensalt(BCRYPT_ROUNDS));
    }

    /**
     * Verify a password against a stored hash, plain BCrypt or wrapped legacy SHA-512.
     *
     * @param plaintext  The plaintext password to verify
     * @param storedHash The stored hash to verify against
     * @return true if the password matches, false otherwise
     */
    public static boolean verify(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null) {
            log.warn("Null parameter in password verification");
            return false;
        }

        try {
            if (storedHash.startsWith(LEGACY_SHA512_PREFIX)) {
                return BCrypt.checkpw(
                    sha512Hex(plaintext), storedHash.substring(LEGACY_SHA512_PREFIX.length()));
            }
            return BCrypt.checkpw(plaintext, storedHash);
        } catch (IllegalArgumentException e) {
            log.error("Invalid BCrypt hash format", e);
            return false;
        }
    }

    /**
     * Wraps a legacy unsalted SHA-512 hex digest for storage.
     *
     * <p>The Java counterpart of sql/wrap-legacy-password-hashes.sql, for tooling
     * and tests; both produce a value {@link #verify} accepts for the original password.
     *
     * @param legacyHex the stored legacy value: 128 lowercase hex characters
     * @return {@value #LEGACY_SHA512_PREFIX} followed by a BCrypt hash of that digest
     * @throws IllegalArgumentException if {@code legacyHex} is not a legacy SHA-512 digest
     */
    public static String wrapLegacySha512(String legacyHex) {
        if (legacyHex == null || !LEGACY_SHA512_HEX.matcher(legacyHex).matches()) {
            throw new IllegalArgumentException("not a lowercase SHA-512 hex digest");
        }
        return LEGACY_SHA512_PREFIX + BCrypt.hashpw(legacyHex, BCrypt.gensalt(BCRYPT_ROUNDS));
    }

    /**
     * The legacy digest: SHA-512 of the UTF-8 bytes, as lowercase hex.
     *
     * <p>UTF-8 is explicit. The legacy code used the platform default charset, which matches
     * for every ASCII password; a non-ASCII password verifies only if the legacy server's
     * default charset was UTF-8.
     */
    static String sha512Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            return Hash.getHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-512.
            throw new IllegalStateException(e);
        }
    }
}
