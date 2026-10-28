package org.starexec.tomcat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Container-managed login accepts passwords migrated from the legacy deployment.
 *
 * <p>The legacy deployment stored the unsalted SHA-512 hex digest of each password. The
 * migration wraps that digest in BCrypt with pgcrypto; the vectors below were produced exactly
 * that way, so these tests prove the SQL wrapping and this handler agree. The same vectors are
 * checked in the application's PasswordHasherTests.
 */
public class BCryptCredentialHandlerTest {

    /** crypt(sha512_hex('Correct-Horse-9'), gen_salt('bf', 12)), with the stored prefix. */
    private static final String WRAPPED_ASCII =
        "{sha512}$2a$12$1ZbIyh3LvasjAyzkiLLSW.XN4yLozRov4s6QQDcIcoZ0uzf6X2dRO";

    /** As above for 'contraseña-ñ' (UTF-8). */
    private static final String WRAPPED_UTF8 =
        "{sha512}$2a$12$Dxui85bFqlEdsHRi.Ya2uuYYHm74DkkszH/u8TZG4CYud4SRp/VPS";

    /** The legacy stored value for 'Correct-Horse-9'. */
    private static final String LEGACY_HEX =
        "63b40d13b71279d42afdb91d709f75d7053d1639d9cf125b9c67c9471f022d96"
            + "26fb5d962718da8e31f93da2564a3ac3d48a1cd58d7b35e3cd5be09b633748ca";

    private final BCryptCredentialHandler handler = new BCryptCredentialHandler();

    @Test
    public void aWrappedLegacyHashAcceptsTheOriginalPassword() {
        assertTrue(handler.matches("Correct-Horse-9", WRAPPED_ASCII));
        assertTrue(handler.matches("contraseña-ñ", WRAPPED_UTF8));
    }

    @Test
    public void aWrappedLegacyHashRejectsOtherInput() {
        assertFalse(handler.matches("correct-horse-9", WRAPPED_ASCII));
        assertFalse(handler.matches("", WRAPPED_ASCII));
        // A leaked legacy digest must not work as a password.
        assertFalse(handler.matches(LEGACY_HEX, WRAPPED_ASCII));
    }

    @Test
    public void anUnwrappedLegacyDigestIsRefused() {
        assertFalse(handler.matches("Correct-Horse-9", LEGACY_HEX));
        assertFalse(handler.matches(LEGACY_HEX, LEGACY_HEX));
    }

    @Test
    public void plainBCryptStillWorks() {
        String stored = handler.mutate("s3cret");
        assertTrue(handler.matches("s3cret", stored));
        assertFalse(handler.matches("s3cret!", stored));
    }

    @Test
    public void theDigestMatchesTheLegacyFormat() {
        assertEquals(LEGACY_HEX, BCryptCredentialHandler.sha512Hex("Correct-Horse-9"));
    }

    @Test
    public void nullsAreRefused() {
        assertFalse(handler.matches(null, WRAPPED_ASCII));
        assertFalse(handler.matches("x", null));
    }
}
