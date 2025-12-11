package org.starexec.util;

import org.mindrot.jbcrypt.BCrypt;
import org.starexec.logger.StarLogger;

/**
 * Secure password hashing utility using BCrypt.
 *
 * @author StarExec Team
 */
public class PasswordHasher {
    private static final StarLogger log = StarLogger.getLogger(PasswordHasher.class);

    // BCrypt work factor (2^12 iterations)
    private static final int BCRYPT_ROUNDS = 12;

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
     * Verify a password against a stored BCrypt hash.
     *
     * @param plaintext  The plaintext password to verify
     * @param storedHash The stored BCrypt hash to verify against
     * @return true if the password matches, false otherwise
     */
    public static boolean verify(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null) {
            log.warn("Null parameter in password verification");
            return false;
        }

        try {
            return BCrypt.checkpw(plaintext, storedHash);
        } catch (IllegalArgumentException e) {
            log.error("Invalid BCrypt hash format", e);
            return false;
        }
    }
}
