package org.qubership.integration.platform.runtime.catalog.cr.sources;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PlaceholdersSubstitutionService {
    @Deprecated(since = "23.4")
    private static final String RANDOM_ID_PLACEHOLDER_PATTERN = "%%\\{random-id-placeholder}";
    private static final String DEPLOYMENT_ID_PLACEHOLDER_PATTERN = "%%\\{deployment-id-placeholder}";
    private static final String DOMAIN_PLACEHOLDER_PATTERN = "%%\\{domain-placeholder}";

    public String substitute(String input) {
        return input
                .replaceAll(RANDOM_ID_PLACEHOLDER_PATTERN, UUID.randomUUID().toString())
                .replaceAll(DEPLOYMENT_ID_PLACEHOLDER_PATTERN, UUID.randomUUID().toString())
                .replaceAll(DOMAIN_PLACEHOLDER_PATTERN, "default");
    }
}
