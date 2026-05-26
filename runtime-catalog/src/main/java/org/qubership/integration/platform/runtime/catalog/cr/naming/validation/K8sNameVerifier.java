package org.qubership.integration.platform.runtime.catalog.cr.naming.validation;

import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT;
import static org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNames.K8S_RESOURCE_NAME_PATTERN;

@Component
public class K8sNameVerifier {
    public void verify(String name) {
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
