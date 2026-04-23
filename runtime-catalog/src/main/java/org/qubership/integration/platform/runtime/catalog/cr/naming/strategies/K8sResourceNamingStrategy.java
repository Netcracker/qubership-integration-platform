package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;

import java.util.regex.Pattern;

@Slf4j
public abstract class K8sResourceNamingStrategy<T> implements NamingStrategy<T> {
    protected static final int K8S_RESOURCE_NAME_LENGTH_LIMIT = 63;
    protected static final Pattern K8S_RESOURCE_NAME_PATTERN =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");

    @Override
    public String getName(T context) {
        String name = proposeName(context);
        verify(name);
        return name;
    }

    protected abstract String proposeName(T context);

    private void verify(String name) {
        if (name.length() > K8S_RESOURCE_NAME_LENGTH_LIMIT) {
            String message = String.format("Name exceeds maximum length of %d: %s",
                    K8S_RESOURCE_NAME_LENGTH_LIMIT, name);
            throw new IllegalArgumentException(message);
        }
        if (!K8S_RESOURCE_NAME_PATTERN.matcher(name).matches()) {
            String message = String.format("Resource name should match pattern: %s", K8S_RESOURCE_NAME_PATTERN);
            throw new IllegalArgumentException(message);
        }
    }
}
