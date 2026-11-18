package org.starexec.test.junit.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.starexec.util.PasswordHasher;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the PasswordHasher utility class.
 * Tests BCrypt password hashing and verification.
 */
public class PasswordHasherTests {

    // ===== BCrypt Hashing Tests =====

    @Test
    public void testHash_ProducesBCryptFormat() {
        String hash = PasswordHasher.hash("password123");
        Assertions.assertNotNull(hash, "Hash should not be null");
        Assertions.assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"), 
            "Hash should start with $2a$ or $2b$");
        Assertions.assertEquals(60, hash.length());
    }

    @Test
    public void testHash_DifferentSaltsProduceDifferentHashes() {
        String hash1 = PasswordHasher.hash("password123");
        String hash2 = PasswordHasher.hash("password123");
        Assertions.assertNotEquals(hash1, 
            hash2, "Same password should produce different hashes due to salt");
    }

    @Test
    public void testHash_NullPassword() {
        Assertions.assertNull(PasswordHasher.hash(null), "Null password should return null hash");
    }

    @Test
    public void testHash_EmptyPassword() {
        String hash = PasswordHasher.hash("");
        Assertions.assertNotNull(hash, "Empty password should still produce a hash");
        Assertions.assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"), 
            "Hash should be valid BCrypt format");
    }

    // ===== Password Verification Tests =====

    @Test
    public void testVerify_CorrectPassword() {
        String password = "mySecurePassword123!";
        String hash = PasswordHasher.hash(password);
        
        Assertions.assertTrue(PasswordHasher.verify(password, hash), 
            "Correct password should verify");
    }

    @Test
    public void testVerify_IncorrectPassword() {
        String password = "mySecurePassword123!";
        String hash = PasswordHasher.hash(password);
        
        Assertions.assertFalse(PasswordHasher.verify("wrongPassword", hash), 
            "Incorrect password should not verify");
    }

    @Test
    public void testVerify_NullParameters() {
        String validHash = PasswordHasher.hash("test");
        
        Assertions.assertFalse(PasswordHasher.verify(null, validHash), 
            "Null plaintext should return false");
        Assertions.assertFalse(PasswordHasher.verify("test", null), 
            "Null hash should return false");
    }

    @Test
    public void testVerify_InvalidHashFormat() {
        Assertions.assertFalse(PasswordHasher.verify("test", "invalid_hash"), 
            "Invalid hash should return false");
    }

    // ===== Edge Cases =====

    @Test
    public void testVerify_CaseSensitivePassword() {
        String hash = PasswordHasher.hash("Password");
        
        Assertions.assertTrue(PasswordHasher.verify("Password", hash));
        Assertions.assertFalse(PasswordHasher.verify("password", hash));
        Assertions.assertFalse(PasswordHasher.verify("PASSWORD", hash));
    }

    @Test
    public void testVerify_SpecialCharacters() {
        String password = "p@$$w0rd!#%^&*()_+-=[]{}|;':\",./<>?`~";
        String hash = PasswordHasher.hash(password);
        
        Assertions.assertTrue(PasswordHasher.verify(password, hash));
    }

    @Test
    public void testVerify_UnicodeCharacters() {
        String password = "пароль密码パスワード";
        String hash = PasswordHasher.hash(password);
        
        Assertions.assertTrue(PasswordHasher.verify(password, hash));
    }

    @Test
    public void testVerify_LongPassword() {
        // BCrypt has a 72-byte limit, but should handle longer passwords gracefully
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("abcdefghij");
        }
        String longPassword = sb.toString();
        String hash = PasswordHasher.hash(longPassword);
        
        Assertions.assertTrue(PasswordHasher.verify(longPassword, hash));
    }

    @Test
    public void testVerify_WhitespacePassword() {
        String password = "   spaces   ";
        String hash = PasswordHasher.hash(password);
        
        Assertions.assertTrue(PasswordHasher.verify(password, hash));
        Assertions.assertFalse(PasswordHasher.verify("spaces", hash));
    }

    @Test
    public void testHash_Consistency() {
        // Same password should always verify against its hash
        String password = "consistentPassword";
        for (int i = 0; i < 5; i++) {
            String hash = PasswordHasher.hash(password);
            Assertions.assertTrue(PasswordHasher.verify(password, hash),
                "Password should always verify against its hash");
        }
    }

    // ===== Passwords migrated from the legacy deployment (unsalted SHA-512) =====

    /**
     * Produced by PostgreSQL pgcrypto, exactly as sql/wrap-legacy-password-hashes.sql
     * does: crypt(sha512_hex('Correct-Horse-9'), gen_salt('bf', 12)). Verifying it here proves the
     * SQL wrapping and the Java verification agree, not merely that Java agrees with itself.
     */
    private static final String PGCRYPTO_WRAPPED_ASCII =
        "{sha512}$2a$12$1ZbIyh3LvasjAyzkiLLSW.XN4yLozRov4s6QQDcIcoZ0uzf6X2dRO";

    /** As above for 'contraseña-ñ' (UTF-8): the legacy digest of a non-ASCII password. */
    private static final String PGCRYPTO_WRAPPED_UTF8 =
        "{sha512}$2a$12$Dxui85bFqlEdsHRi.Ya2uuYYHm74DkkszH/u8TZG4CYud4SRp/VPS";

    /** SHA-512 hex of 'Correct-Horse-9', the value se-m1 stores for that password. */
    private static final String LEGACY_HEX =
        "63b40d13b71279d42afdb91d709f75d7053d1639d9cf125b9c67c9471f022d96"
            + "26fb5d962718da8e31f93da2564a3ac3d48a1cd58d7b35e3cd5be09b633748ca";

    @Test
    public void testVerify_PgcryptoWrappedLegacyHashAcceptsTheOriginalPassword() {
        Assertions.assertTrue(PasswordHasher.verify("Correct-Horse-9", PGCRYPTO_WRAPPED_ASCII));
        Assertions.assertTrue(PasswordHasher.verify("contraseña-ñ", PGCRYPTO_WRAPPED_UTF8));
    }

    @Test
    public void testVerify_PgcryptoWrappedLegacyHashRejectsOtherPasswords() {
        Assertions.assertFalse(PasswordHasher.verify("correct-horse-9", PGCRYPTO_WRAPPED_ASCII));
        Assertions.assertFalse(PasswordHasher.verify("", PGCRYPTO_WRAPPED_ASCII));
        // The digest itself must not work as a password: that would make a leaked legacy
        // hash a working credential.
        Assertions.assertFalse(PasswordHasher.verify(LEGACY_HEX, PGCRYPTO_WRAPPED_ASCII));
    }

    @Test
    public void testWrapLegacySha512_RoundTripsWithVerify() {
        String wrapped = PasswordHasher.wrapLegacySha512(LEGACY_HEX);
        Assertions.assertTrue(wrapped.startsWith(PasswordHasher.LEGACY_SHA512_PREFIX + "$2a$12$"));
        Assertions.assertTrue(wrapped.length() <= 128, "fits users.password VARCHAR(128)");
        Assertions.assertTrue(PasswordHasher.verify("Correct-Horse-9", wrapped));
        Assertions.assertFalse(PasswordHasher.verify("Correct-Horse-8", wrapped));
    }

    @Test
    public void testVerify_AnUnwrappedLegacyDigestIsRefused() {
        // A migration that skipped the wrapping step must fail closed, not authenticate.
        Assertions.assertFalse(PasswordHasher.verify("Correct-Horse-9", LEGACY_HEX));
        Assertions.assertFalse(PasswordHasher.verify(LEGACY_HEX, LEGACY_HEX));
    }

    @Test
    public void testWrapLegacySha512_RefusesAnythingButALegacyDigest() {
        String bcrypt = PasswordHasher.hash("already-bcrypt");
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.wrapLegacySha512(bcrypt));
    }

    @Test
    public void testVerify_PlainBCryptIsUnaffectedByTheLegacyPath() {
        String hash = PasswordHasher.hash("{sha512}looks-like-a-prefix");
        Assertions.assertTrue(PasswordHasher.verify("{sha512}looks-like-a-prefix", hash));
    }
}
