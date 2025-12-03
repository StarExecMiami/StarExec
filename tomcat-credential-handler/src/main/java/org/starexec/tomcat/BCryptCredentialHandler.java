package org.starexec.tomcat;

import org.apache.catalina.CredentialHandler;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Tomcat CredentialHandler that uses BCrypt for password verification.
 * This handler is deployed to Tomcat's lib directory to be available
 * during Realm initialization.
 */
public class BCryptCredentialHandler implements CredentialHandler {

    private static final int BCRYPT_ROUNDS = 12;

    @Override
    public boolean matches(String inputCredentials, String storedCredentials) {
        if (inputCredentials == null || storedCredentials == null) {
            return false;
        }
        try {
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
}
