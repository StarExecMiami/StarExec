package org.starexec.util;

import org.mindrot.jbcrypt.BCrypt;
import org.starexec.logger.StarLogger;

/**
 * Secure password hashing utility using BCrypt.
 * Supports migration from legacy SHA-512 hashing to BCrypt.
 *
 * @author StarExec Team
 */
public class PasswordHasher {
    private static final StarLogger log = StarLogger.getLogger(PasswordHasher.class);

    // BCrypt work factor (2^12 iterations)
    private static final int BCRYPT_ROUNDS = 12;

    // Length of SHA-512 hex hash (legacy)
    private static final int SHA512_HEX_LENGTH = 128;

    /** Algorithm constant for legacy SHA-512 password hashing */
    public static final String ALG_SHA512 = "SHA512";
    
    /** Algorithm constant for BCrypt password hashing (current standard) */
    public static final String ALG_BCRYPT = "BCRYPT";
    
    /** Algorithm constant for unknown/invalid hash formats */
    public static final String ALG_UNKNOWN = "UNKNOWN";

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
     * Verify a password against a stored hash.
     * Automatically detects whether the hash is legacy SHA-512 or BCrypt.
     *
     * @param plaintext  The plaintext password to verify
     * @param storedHash The stored hash to verify against
     * @param algorithm  The algorithm used: {@link #ALG_SHA512} or {@link #ALG_BCRYPT}
     * @return true if the password matches, false otherwise
     */
    public static boolean verify(String plaintext, String storedHash, String algorithm) {
        if (plaintext == null || storedHash == null || algorithm == null) {
            log.warn("Null parameter in password verification");
            return false;
        }

        if (ALG_SHA512.equals(algorithm)) {
            return verifyLegacy(plaintext, storedHash);
        } else if (ALG_BCRYPT.equals(algorithm)) {
            try {
                return BCrypt.checkpw(plaintext, storedHash);
            } catch (IllegalArgumentException e) {
                log.error("Invalid BCrypt hash format", e);
                return false;
            }
        } else {
            log.error("Unknown password algorithm: " + algorithm);
            return false;
        }
    }

    /**
     * Verify a password using legacy SHA-512 hashing.
     *
     * @param plaintext  The plaintext password
     * @param storedHash The stored SHA-512 hash
     * @return true if the password matches
     */
    private static boolean verifyLegacy(String plaintext, String storedHash) {
        String computedHash = Hash.hashPasswordLegacy(plaintext);
        return computedHash != null && computedHash.equals(storedHash);
    }

    /**
     * Detect the hashing algorithm based on the hash format.
     * SHA-512 hashes are exactly 128 hex characters.
     * BCrypt hashes start with "$2a$" or "$2b$" and are 60 characters.
     *
     * @param hash The hash to analyze
     * @return {@link #ALG_SHA512}, {@link #ALG_BCRYPT}, or {@link #ALG_UNKNOWN}
     */
    public static String detectAlgorithm(String hash) {
        if (hash == null) {
            return ALG_UNKNOWN;
        }

        // BCrypt hashes start with "$2a$" or "$2b$" and are 60 characters
        if (hash.startsWith("$2a$") || hash.startsWith("$2b$")) {
            return ALG_BCRYPT;
        }

        // SHA-512 hex hashes are exactly 128 characters
        if (hash.length() == SHA512_HEX_LENGTH && hash.matches("^[0-9a-f]{128}$")) {
            return ALG_SHA512;
        }

        return ALG_UNKNOWN;
    }
}
