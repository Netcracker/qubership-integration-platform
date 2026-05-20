package org.qubership.integration.platform.runtime.catalog.cr.sources;

public class LanguageNotSupportedError extends RuntimeException {
    public LanguageNotSupportedError(String name) {
        super(buildMessage(name));
    }

    private static String buildMessage(String name) {
        return String.format("Language not supported: %s", name);
    }
}
