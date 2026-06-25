package org.qubership.integration.platform.camelk.naming.validation;

import org.springframework.stereotype.Component;

import java.util.UUID;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.camelk.naming.validation.K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT;

@Component
public class K8sNameValidator {
    public String validate(String name) {
        name = isNull(name) ? "" : name.replaceAll("[^-a-z0-9]", "");
        if ((!name.isEmpty()) && (name.startsWith("-") || Character.isDigit(name.charAt(0)))) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            name = UUID.randomUUID().toString();
        }
        return name.substring(0, Math.min(name.length(), K8S_RESOURCE_NAME_LENGTH_LIMIT));
    }
}
