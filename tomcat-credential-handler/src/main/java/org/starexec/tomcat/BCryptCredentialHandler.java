package org.starexec.tomcat;

import org.apache.catalina.CredentialHandler;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tomcat CredentialHandler that uses BCrypt for password verification.
 * This handler is deployed to Tomcat's lib directory to be available
 * during Realm initialization.
 *
 * <p>Two stored forms are accepted: plain BCrypt, and {@value #LEGACY_SHA512_PREFIX} followed
 * by a BCrypt hash of the SHA-512 hex digest of the password. The second is how passwords
 * migrated from the legacy deployment, which stored the unsalted SHA-512 digest, are kept
 * (sql/wrap-legacy-password-hashes.sql). A bare SHA-512 digest is never accepted.
 *
 * <p>Must stay in step with {@code org.starexec.util.PasswordHasher#verify} in the application.
 */
public class BCryptCredentialHandler implements CredentialHandler {

    private static final int BCRYPT_ROUNDS = 12;

    /** Marks a BCrypt hash whose input is the SHA-512 hex digest of the password. */
    static final String LEGACY_SHA512_PREFIX = "{sha512}";

    @Override
    public boolean matches(String inputCredentials, String storedCredentials) {
        if (inputCredentials == null || storedCredentials == null) {
            return false;
        }
        try {
            if (storedCredentials.startsWith(LEGACY_SHA512_PREFIX)) {
                return BCrypt.checkpw(
                    sha512Hex(inputCredentials),
                    storedCredentials.substring(LEGACY_SHA512_PREFIX.length()));
            }
            return BCrypt.checkpw(inputCredentials, storedCredentials);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public String mutate(String inputCredentials) {
        if (inputCredentials == null) {
            return null;
        }
        return BCrypt.hashpw(inputCredentials, BCrypt.gensalt(BCRYPT_ROUNDS));
    }

    /** SHA-512 of the UTF-8 bytes as lowercase hex: the legacy stored form. */
    static String sha512Hex(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * digest.length);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-512.
            throw new IllegalStateException(e);
        }
    }
}
