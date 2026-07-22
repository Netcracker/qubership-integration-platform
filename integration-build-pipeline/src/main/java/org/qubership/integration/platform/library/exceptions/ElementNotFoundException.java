package org.qubership.integration.platform.library.exceptions;

import lombok.Getter;

public class ElementNotFoundException extends ElementLibraryException {
    @Getter
    private final String name;

    public ElementNotFoundException(String name) {
        super(buildMessage(name));
        this.name = name;
    }

    private static String buildMessage(String name) {
        return String.format("Element descriptor '%s' not found.", name);
    }
}
