package org.starexec.test.junit.util;

import org.junit.Assert;
import org.junit.Test;
import org.starexec.util.PasswordHasher;

/**
 * Unit tests for the PasswordHasher utility class.
 * Tests BCrypt password hashing and verification.
 */
public class PasswordHasherTests {

    // ===== BCrypt Hashing Tests =====

    @Test
    public void testHash_ProducesBCryptFormat() {
        String hash = PasswordHasher.hash("password123");
        Assert.assertNotNull("Hash should not be null", hash);
        Assert.assertTrue("Hash should start with $2a$ or $2b$", 
            hash.startsWith("$2a$") || hash.startsWith("$2b$"));
        Assert.assertEquals(60, hash.length());
    }

    @Test
    public void testHash_DifferentSaltsProduceDifferentHashes() {
        String hash1 = PasswordHasher.hash("password123");
        String hash2 = PasswordHasher.hash("password123");
        Assert.assertNotEquals("Same password should produce different hashes due to salt", 
            hash1, hash2);
    }

    @Test
    public void testHash_NullPassword() {
        Assert.assertNull("Null password should return null hash", PasswordHasher.hash(null));
    }

    @Test
    public void testHash_EmptyPassword() {
        String hash = PasswordHasher.hash("");
        Assert.assertNotNull("Empty password should still produce a hash", hash);
        Assert.assertTrue("Hash should be valid BCrypt format", 
            hash.startsWith("$2a$") || hash.startsWith("$2b$"));
    }

    // ===== Password Verification Tests =====

    @Test
    public void testVerify_CorrectPassword() {
        String password = "mySecurePassword123!";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertTrue("Correct password should verify", 
            PasswordHasher.verify(password, hash));
    }

    @Test
    public void testVerify_IncorrectPassword() {
        String password = "mySecurePassword123!";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertFalse("Incorrect password should not verify", 
            PasswordHasher.verify("wrongPassword", hash));
    }

    @Test
    public void testVerify_NullParameters() {
        String validHash = PasswordHasher.hash("test");
        
        Assert.assertFalse("Null plaintext should return false", 
            PasswordHasher.verify(null, validHash));
        Assert.assertFalse("Null hash should return false", 
            PasswordHasher.verify("test", null));
    }

    @Test
    public void testVerify_InvalidHashFormat() {
        Assert.assertFalse("Invalid hash should return false", 
            PasswordHasher.verify("test", "invalid_hash"));
    }

    // ===== Edge Cases =====

    @Test
    public void testVerify_CaseSensitivePassword() {
        String hash = PasswordHasher.hash("Password");
        
        Assert.assertTrue(PasswordHasher.verify("Password", hash));
        Assert.assertFalse(PasswordHasher.verify("password", hash));
        Assert.assertFalse(PasswordHasher.verify("PASSWORD", hash));
    }

    @Test
    public void testVerify_SpecialCharacters() {
        String password = "p@$$w0rd!#%^&*()_+-=[]{}|;':\",./<>?`~";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertTrue(PasswordHasher.verify(password, hash));
    }

    @Test
    public void testVerify_UnicodeCharacters() {
        String password = "пароль密码パスワード";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertTrue(PasswordHasher.verify(password, hash));
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
        
        Assert.assertTrue(PasswordHasher.verify(longPassword, hash));
    }

    @Test
    public void testVerify_WhitespacePassword() {
        String password = "   spaces   ";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertTrue(PasswordHasher.verify(password, hash));
        Assert.assertFalse(PasswordHasher.verify("spaces", hash));
    }

    @Test
    public void testHash_Consistency() {
        // Same password should always verify against its hash
        String password = "consistentPassword";
        for (int i = 0; i < 5; i++) {
            String hash = PasswordHasher.hash(password);
            Assert.assertTrue("Password should always verify against its hash",
                PasswordHasher.verify(password, hash));
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
        Assert.assertTrue(PasswordHasher.verify("Correct-Horse-9", PGCRYPTO_WRAPPED_ASCII));
        Assert.assertTrue(PasswordHasher.verify("contraseña-ñ", PGCRYPTO_WRAPPED_UTF8));
    }

    @Test
    public void testVerify_PgcryptoWrappedLegacyHashRejectsOtherPasswords() {
        Assert.assertFalse(PasswordHasher.verify("correct-horse-9", PGCRYPTO_WRAPPED_ASCII));
        Assert.assertFalse(PasswordHasher.verify("", PGCRYPTO_WRAPPED_ASCII));
        // The digest itself must not work as a password: that would make a leaked legacy
        // hash a working credential.
        Assert.assertFalse(PasswordHasher.verify(LEGACY_HEX, PGCRYPTO_WRAPPED_ASCII));
    }

    @Test
    public void testWrapLegacySha512_RoundTripsWithVerify() {
        String wrapped = PasswordHasher.wrapLegacySha512(LEGACY_HEX);
        Assert.assertTrue(wrapped.startsWith(PasswordHasher.LEGACY_SHA512_PREFIX + "$2a$12$"));
        Assert.assertTrue("fits users.password VARCHAR(128)", wrapped.length() <= 128);
        Assert.assertTrue(PasswordHasher.verify("Correct-Horse-9", wrapped));
        Assert.assertFalse(PasswordHasher.verify("Correct-Horse-8", wrapped));
    }

    @Test
    public void testVerify_AnUnwrappedLegacyDigestIsRefused() {
        // A migration that skipped the wrapping step must fail closed, not authenticate.
        Assert.assertFalse(PasswordHasher.verify("Correct-Horse-9", LEGACY_HEX));
        Assert.assertFalse(PasswordHasher.verify(LEGACY_HEX, LEGACY_HEX));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWrapLegacySha512_RefusesAnythingButALegacyDigest() {
        PasswordHasher.wrapLegacySha512(PasswordHasher.hash("already-bcrypt"));
    }

    @Test
    public void testVerify_PlainBCryptIsUnaffectedByTheLegacyPath() {
        String hash = PasswordHasher.hash("{sha512}looks-like-a-prefix");
        Assert.assertTrue(PasswordHasher.verify("{sha512}looks-like-a-prefix", hash));
    }
}
