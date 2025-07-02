package org.qubership.integration.platform.variables.management.rest.exception;

public class SecretNotFoundException extends RuntimeException {
    public SecretNotFoundException(String message) {
        super(message);
    }

    public SecretNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public static SecretNotFoundException forSecret(String secretName) {
        String message = buildMessage(secretName);
        return new SecretNotFoundException(message);
    }

    public static SecretNotFoundException forSecretAndCause(String secretName, Throwable cause) {
        String message = buildMessage(secretName);
        return new SecretNotFoundException(message, cause);
    }

    private static String buildMessage(String secretName) {
        return String.format("Secret with name %s not found", secretName);
    }
}
