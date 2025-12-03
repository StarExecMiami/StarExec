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
}
