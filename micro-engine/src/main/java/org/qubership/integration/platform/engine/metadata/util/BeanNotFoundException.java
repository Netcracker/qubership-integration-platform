package org.qubership.integration.platform.engine.metadata.util;

import lombok.Getter;

public class BeanNotFoundException extends RuntimeException {
    @Getter
    private final String beanName;

    public BeanNotFoundException(String beanName) {
        super("Bean not found in registry: " + beanName);
        this.beanName = beanName;
    }
}
