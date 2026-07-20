package org.qubership.integration.platform.camelk.sources;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
@Builder
public class SourceBuilderContext {
    private String domainName;
    private String buildName;
    private Instant buildTimestamp;
    private IntegrationServiceCatalog integrationServiceCatalog;
}
