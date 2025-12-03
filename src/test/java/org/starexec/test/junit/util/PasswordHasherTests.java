package org.starexec.test.junit.util;

import org.junit.Assert;
import org.junit.Test;
import org.starexec.util.Hash;
import org.starexec.util.PasswordHasher;

/**
 * Unit tests for the PasswordHasher utility class.
 * Tests algorithm detection, password verification, and backward compatibility
 * with legacy SHA-512 hashes.
 */
public class PasswordHasherTests {

    // ===== Algorithm Constants Tests =====

    @Test
    public void testAlgorithmConstantsExist() {
        Assert.assertNotNull("ALG_SHA512 constant should exist", PasswordHasher.ALG_SHA512);
        Assert.assertNotNull("ALG_BCRYPT constant should exist", PasswordHasher.ALG_BCRYPT);
        Assert.assertNotNull("ALG_UNKNOWN constant should exist", PasswordHasher.ALG_UNKNOWN);
    }

    @Test
    public void testAlgorithmConstantValues() {
        Assert.assertEquals("SHA512", PasswordHasher.ALG_SHA512);
        Assert.assertEquals("BCRYPT", PasswordHasher.ALG_BCRYPT);
        Assert.assertEquals("UNKNOWN", PasswordHasher.ALG_UNKNOWN);
    }

    // ===== Algorithm Detection Tests =====

    @Test
    public void testDetectAlgorithm_NullHash() {
        Assert.assertEquals(PasswordHasher.ALG_UNKNOWN, PasswordHasher.detectAlgorithm(null));
    }

    @Test
    public void testDetectAlgorithm_EmptyString() {
        Assert.assertEquals(PasswordHasher.ALG_UNKNOWN, PasswordHasher.detectAlgorithm(""));
    }

    @Test
    public void testDetectAlgorithm_BCrypt2a() {
        // BCrypt hash with $2a$ prefix
        String bcryptHash = "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";
        Assert.assertEquals(PasswordHasher.ALG_BCRYPT, PasswordHasher.detectAlgorithm(bcryptHash));
    }

    @Test
    public void testDetectAlgorithm_BCrypt2b() {
        // BCrypt hash with $2b$ prefix
        String bcryptHash = "$2b$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";
        Assert.assertEquals(PasswordHasher.ALG_BCRYPT, PasswordHasher.detectAlgorithm(bcryptHash));
    }

    @Test
    public void testDetectAlgorithm_SHA512() {
        // SHA-512 produces a 128-character hex string
        String sha512Hash = Hash.hashPasswordLegacy("testpassword");
        Assert.assertNotNull("Legacy hash should not be null", sha512Hash);
        Assert.assertEquals(128, sha512Hash.length());
        Assert.assertEquals(PasswordHasher.ALG_SHA512, PasswordHasher.detectAlgorithm(sha512Hash));
    }

    @Test
    public void testDetectAlgorithm_InvalidHash() {
        // Random string that doesn't match any known format
        Assert.assertEquals(PasswordHasher.ALG_UNKNOWN, PasswordHasher.detectAlgorithm("randomstring"));
        Assert.assertEquals(PasswordHasher.ALG_UNKNOWN, PasswordHasher.detectAlgorithm("12345"));
        Assert.assertEquals(PasswordHasher.ALG_UNKNOWN, PasswordHasher.detectAlgorithm("$3a$invalid"));
    }

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
    public void testVerify_BCryptCorrectPassword() {
        String password = "mySecurePassword123!";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertTrue("Correct password should verify", 
            PasswordHasher.verify(password, hash, PasswordHasher.ALG_BCRYPT));
    }

    @Test
    public void testVerify_BCryptIncorrectPassword() {
        String password = "mySecurePassword123!";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertFalse("Incorrect password should not verify", 
            PasswordHasher.verify("wrongPassword", hash, PasswordHasher.ALG_BCRYPT));
    }

    @Test
    public void testVerify_SHA512CorrectPassword() {
        String password = "legacyPassword456";
        String hash = Hash.hashPasswordLegacy(password);
        
        Assert.assertTrue("Correct password should verify with legacy hash", 
            PasswordHasher.verify(password, hash, PasswordHasher.ALG_SHA512));
    }

    @Test
    public void testVerify_SHA512IncorrectPassword() {
        String password = "legacyPassword456";
        String hash = Hash.hashPasswordLegacy(password);
        
        Assert.assertFalse("Incorrect password should not verify with legacy hash", 
            PasswordHasher.verify("wrongPassword", hash, PasswordHasher.ALG_SHA512));
    }

    @Test
    public void testVerify_NullParameters() {
        String validHash = PasswordHasher.hash("test");
        
        Assert.assertFalse("Null plaintext should return false", 
            PasswordHasher.verify(null, validHash, PasswordHasher.ALG_BCRYPT));
        Assert.assertFalse("Null hash should return false", 
            PasswordHasher.verify("test", null, PasswordHasher.ALG_BCRYPT));
        Assert.assertFalse("Null algorithm should return false", 
            PasswordHasher.verify("test", validHash, null));
    }

    @Test
    public void testVerify_UnknownAlgorithm() {
        String hash = PasswordHasher.hash("test");
        
        Assert.assertFalse("Unknown algorithm should return false", 
            PasswordHasher.verify("test", hash, "UNKNOWN_ALG"));
        Assert.assertFalse("Unknown algorithm should return false", 
            PasswordHasher.verify("test", hash, PasswordHasher.ALG_UNKNOWN));
    }

    @Test
    public void testVerify_InvalidBCryptHashFormat() {
        // Provide an invalid BCrypt hash format
        Assert.assertFalse("Invalid BCrypt hash should return false", 
            PasswordHasher.verify("test", "invalid_bcrypt_hash", PasswordHasher.ALG_BCRYPT));
    }

    // ===== Backward Compatibility / Migration Tests =====

    @Test
    public void testMigrationScenario_DetectLegacyThenUpgrade() {
        // Simulate a user with a legacy SHA-512 password
        String password = "userPassword";
        String legacyHash = Hash.hashPasswordLegacy(password);
        
        // Detection phase
        String detectedAlgorithm = PasswordHasher.detectAlgorithm(legacyHash);
        Assert.assertEquals("Should detect as SHA512", 
            PasswordHasher.ALG_SHA512, detectedAlgorithm);
        
        // Verification with legacy algorithm
        Assert.assertTrue("Should verify with SHA512", 
            PasswordHasher.verify(password, legacyHash, detectedAlgorithm));
        
        // Upgrade to BCrypt
        String newHash = PasswordHasher.hash(password);
        String newAlgorithm = PasswordHasher.detectAlgorithm(newHash);
        Assert.assertEquals("New hash should be BCRYPT", 
            PasswordHasher.ALG_BCRYPT, newAlgorithm);
        
        // Verification with new algorithm
        Assert.assertTrue("Should verify with BCRYPT", 
            PasswordHasher.verify(password, newHash, newAlgorithm));
    }

    @Test
    public void testAutoDetectAndVerify() {
        // Test the pattern used in actual code: detect then verify
        String password = "testPassword";
        
        // Test with BCrypt hash
        String bcryptHash = PasswordHasher.hash(password);
        String bcryptAlg = PasswordHasher.detectAlgorithm(bcryptHash);
        Assert.assertTrue(PasswordHasher.verify(password, bcryptHash, bcryptAlg));
        
        // Test with SHA-512 hash
        String sha512Hash = Hash.hashPasswordLegacy(password);
        String sha512Alg = PasswordHasher.detectAlgorithm(sha512Hash);
        Assert.assertTrue(PasswordHasher.verify(password, sha512Hash, sha512Alg));
    }

    // ===== Edge Cases =====

    @Test
    public void testVerify_CaseSensitivePassword() {
        String hash = PasswordHasher.hash("Password");
        
        Assert.assertTrue(PasswordHasher.verify("Password", hash, PasswordHasher.ALG_BCRYPT));
        Assert.assertFalse(PasswordHasher.verify("password", hash, PasswordHasher.ALG_BCRYPT));
        Assert.assertFalse(PasswordHasher.verify("PASSWORD", hash, PasswordHasher.ALG_BCRYPT));
    }

    @Test
    public void testVerify_SpecialCharacters() {
        String password = "p@$$w0rd!#%^&*()_+-=[]{}|;':\",./<>?`~";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertTrue(PasswordHasher.verify(password, hash, PasswordHasher.ALG_BCRYPT));
    }

    @Test
    public void testVerify_UnicodeCharacters() {
        String password = "пароль密码パスワード";
        String hash = PasswordHasher.hash(password);
        
        Assert.assertTrue(PasswordHasher.verify(password, hash, PasswordHasher.ALG_BCRYPT));
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
        
        Assert.assertTrue(PasswordHasher.verify(longPassword, hash, PasswordHasher.ALG_BCRYPT));
    }
}
