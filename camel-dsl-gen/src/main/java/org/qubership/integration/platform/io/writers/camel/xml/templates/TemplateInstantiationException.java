package org.qubership.integration.platform.io.writers.camel.xml.templates;

import org.qubership.integration.platform.chain.model.Element;

import java.util.Optional;

public class TemplateInstantiationException extends RuntimeException {
    private final Element element;
    private final Exception originalException;

    public TemplateInstantiationException(String message) {
        this(message, null);
    }

    public TemplateInstantiationException(String message, Element element) {
        this(message, element, null);
    }

    public TemplateInstantiationException(String message, Element element, Exception exception) {
        super(message, exception);
        this.element = element;
        this.originalException = exception;
    }

    public Optional<Element> getElement() {
        return Optional.ofNullable(element);
    }

    public Optional<Exception> getOriginalException() {
        return Optional.ofNullable(originalException);
    }

    public String toString() {
        return getElement().map(e -> {
            String elementId = e.getOriginalId().orElse(e.getId());
            String elementName = e.getName();
            return String.format("%s Element id: %s. Element name: %s.", super.toString(), elementId, elementName);
        }).orElse(super.toString());
    }
}
